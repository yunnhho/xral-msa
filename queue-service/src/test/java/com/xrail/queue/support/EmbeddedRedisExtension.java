package com.xrail.queue.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lua 스크립트(promote/release/즉시입장)는 Redis 원자 실행이 필요해 Mockito로 검증 불가능하다.
 * 기존 queue-service 테스트(QueueServiceTest)는 순수 Mockito라 실제 Redis 하네스가 없었으므로,
 * 로컬에 설치된 redis-server 바이너리로 테스트 전용 인스턴스를 클래스당 하나씩 기동한다.
 *
 * 사용: {@code @RegisterExtension static EmbeddedRedisExtension redis = new EmbeddedRedisExtension();}
 */
public class EmbeddedRedisExtension implements BeforeAllCallback, AfterAllCallback {

    private Process process;
    private int port;
    private ExecutorService drainer;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        port = findFreePort();
        process = new ProcessBuilder(
                "redis-server",
                "--port", String.valueOf(port),
                "--save", "",
                "--appendonly", "no",
                "--daemonize", "no")
                .redirectErrorStream(true)
                .start();

        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        drainer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "embedded-redis-log-drain");
            t.setDaemon(true);
            return t;
        });
        drainer.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!ready.isDone() && line.contains("Ready to accept connections")) {
                        ready.complete(true);
                    }
                }
            } catch (IOException ignored) {
                // 프로세스 종료 시 스트림 닫힘 — 무해
            }
        });

        try {
            ready.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("redis-server did not become ready in time (port=" + port + ")", e);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (drainer != null) {
            drainer.shutdownNow();
        }
    }

    /** RedissonConfig와 동일하게 코덱을 오버라이드하지 않은 클라이언트를 생성한다(기본 Kryo5Codec). */
    public RedissonClient newClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port);
        return Redisson.create(config);
    }

    public int getPort() {
        return port;
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
