package com.xrail.train.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBitSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.IntegerCodec;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LuaScriptService 단위 테스트.
 * 실제 Lua 실행 원자성은 Redis integration 테스트에서 검증한다.
 * 여기서는 올바른 키/인수 전달과 반환값 처리(Java 계층 계약)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class LuaScriptServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RScript rScript;

    private LuaScriptService service;

    @BeforeEach
    void setUp() throws Exception {
        // lenient: getScript() 스텁은 isFree 테스트에서 필요 없음
        lenient().when(redissonClient.getScript(any(IntegerCodec.class))).thenReturn(rScript);
        service = new LuaScriptService(redissonClient);
        service.loadScripts(); // classpath에서 Lua 스크립트 로드
    }

    // ===== tryReserve — 반환값 처리 =====

    @Test
    void tryReserve_luaReturns1_returnsTrue() {
        // varargs (startIdx, endIdx) = 2개 Object 인수
        stubEvalReturn(1L);

        boolean result = service.tryReserve(10L, 5L, 2, 5);

        assertThat(result).isTrue();
    }

    @Test
    void tryReserve_luaReturns0_returnsFalse() {
        stubEvalReturn(0L);

        boolean result = service.tryReserve(10L, 5L, 2, 5);

        assertThat(result).isFalse();
    }

    // ===== tryReserve — 올바른 키 전달 =====

    @Test
    void tryReserve_correctKeyPassed() {
        stubEvalReturn(1L);
        service.tryReserve(10L, 5L, 0, 3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(rScript).eval(any(), anyString(), any(), keysCaptor.capture(),
                any(Object.class), any(Object.class));

        assertThat(keysCaptor.getValue()).containsExactly("sch:10:seat:5");
    }

    // ===== tryReserve — 올바른 인수 전달 (startIdx, endIdx) =====

    @Test
    void tryReserve_correctArgsPassed() {
        stubEvalReturn(1L);
        service.tryReserve(10L, 5L, 2, 7);

        ArgumentCaptor<Object> arg1 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg2 = ArgumentCaptor.forClass(Object.class);
        verify(rScript).eval(any(), anyString(), any(), anyList(), arg1.capture(), arg2.capture());

        assertThat(arg1.getValue()).isEqualTo("2");
        assertThat(arg2.getValue()).isEqualTo("7");
    }

    // ===== rollback — 스크립트 호출 검증 =====

    @Test
    void rollback_invokesRollbackScript() {
        // rollback은 반환값을 사용하지 않음 — 호출 여부만 검증
        lenient().when(rScript.eval(any(), anyString(), any(), anyList(),
                any(Object.class), any(Object.class))).thenReturn(1L);

        service.rollback(10L, 5L, 2, 7);

        verify(rScript, atLeastOnce()).eval(any(), anyString(), any(), anyList(),
                any(Object.class), any(Object.class));
    }

    // ===== isFree — 비트 확인 로직 =====

    @Test
    void isFree_allBitsFree_returnsTrue() {
        RBitSet bitSet = mock(RBitSet.class);
        when(redissonClient.getBitSet("sch:1:seat:1")).thenReturn(bitSet);
        when(bitSet.get(0)).thenReturn(false);
        when(bitSet.get(1)).thenReturn(false);
        when(bitSet.get(2)).thenReturn(false);

        assertThat(service.isFree(1L, 1L, 0, 3)).isTrue();
    }

    @Test
    void isFree_oneBitSet_returnsFalse() {
        RBitSet bitSet = mock(RBitSet.class);
        when(redissonClient.getBitSet("sch:1:seat:1")).thenReturn(bitSet);
        when(bitSet.get(0)).thenReturn(false);
        when(bitSet.get(1)).thenReturn(true); // 충돌

        assertThat(service.isFree(1L, 1L, 0, 3)).isFalse();
    }

    // ===== areFree — 배치 가용 조회 =====

    @Test
    void areFree_mapsLuaResultPerSeat() {
        when(rScript.eval(any(), anyString(), any(), anyList(),
                any(Object.class), any(Object.class))).thenReturn(List.of(1L, 0L, 1L));

        List<Boolean> result = service.areFree(10L, List.of(1L, 2L, 3L), 0, 3);

        assertThat(result).containsExactly(true, false, true);
    }

    @Test
    void areFree_correctKeysAndArgsPassed() {
        when(rScript.eval(any(), anyString(), any(), anyList(),
                any(Object.class), any(Object.class))).thenReturn(List.of(1L, 1L));

        service.areFree(10L, List.of(5L, 6L), 2, 7);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> arg1 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg2 = ArgumentCaptor.forClass(Object.class);
        verify(rScript).eval(any(), anyString(), any(), keysCaptor.capture(), arg1.capture(), arg2.capture());

        assertThat(keysCaptor.getValue()).containsExactly("sch:10:seat:5", "sch:10:seat:6");
        assertThat(arg1.getValue()).isEqualTo("2");
        assertThat(arg2.getValue()).isEqualTo("7");
    }

    @Test
    void areFree_emptySeatIds_noRedisCall() {
        assertThat(service.areFree(10L, List.of(), 0, 3)).isEmpty();
        verify(rScript, org.mockito.Mockito.never()).eval(any(), anyString(), any(), anyList(),
                any(Object.class), any(Object.class));
    }

    // ===== 헬퍼 =====

    private void stubEvalReturn(long value) {
        lenient().when(rScript.eval(any(), anyString(), any(), anyList(),
                any(Object.class), any(Object.class))).thenReturn(value);
    }
}
