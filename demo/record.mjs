// XRail 데모 촬영 스크립트 — demo/RECORDING.md의 3개 장면을 Playwright recordVideo로 촬영.
// 터미널 장면은 ttyd(xterm.js)를 브라우저에 띄워 녹화하고, 완료 감지는 마커 파일 폴링으로 한다
// (xterm 캔버스 렌더러라 DOM 텍스트 스크레이핑 불가).
//
// 사용: node record.mjs scene1|scene2|scene3|all
// 산출: <repo>/demo/footage/scene{1,2,3}.webm (+ scene3a/3b)

import { chromium } from 'playwright';
import { spawn, execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const REPO = '/Users/yunnhho/Dev/xral-msa';
const FOOTAGE = path.join(REPO, 'demo/footage');
const MARK_DIR = '/tmp/xrail-record';
const TTYD_PORT = 7681;
const SIZE = { width: 1920, height: 1080 };

fs.mkdirSync(FOOTAGE, { recursive: true });
fs.rmSync(MARK_DIR, { recursive: true, force: true });
fs.mkdirSync(MARK_DIR, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForMarker(name, timeoutMs) {
  const p = path.join(MARK_DIR, name);
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (fs.existsSync(p)) return true;
    await sleep(500);
  }
  throw new Error(`marker timeout: ${name} (${timeoutMs}ms)`);
}

async function startTtyd() {
  const ttyd = spawn('/opt/homebrew/bin/ttyd', [
    '-p', String(TTYD_PORT), '-W',
    '-t', 'fontSize=22',
    '-t', 'theme={"background":"#0d1117","foreground":"#e6edf3","cursor":"#58a6ff"}',
    'bash', '--noprofile', '--norc',
  ], { cwd: REPO, stdio: ['ignore', 'inherit', 'inherit'] });
  ttyd.on('error', (e) => { throw e; });
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    try {
      await fetch(`http://localhost:${TTYD_PORT}`);
      return ttyd;
    } catch { await sleep(300); }
  }
  throw new Error('ttyd did not start on :' + TTYD_PORT);
}

async function openTerminalPage(context) {
  const page = await context.newPage();
  await page.goto(`http://localhost:${TTYD_PORT}`, { waitUntil: 'networkidle' });
  await page.click('body');
  await sleep(1000);
  // 프롬프트 단정하게 + 프롬프트가 다시 뜰 때마다 마커 갱신(완료 감지용, 화면엔 안 보임)
  await page.keyboard.type(
    `export PS1='\\[\\e[1;34m\\]xrail-demo \\[\\e[0m\\]$ ' PROMPT_COMMAND='date +%s%N > ${MARK_DIR}/prompt'; clear\n`,
    { delay: 5 }
  );
  await sleep(800);
  return page;
}

const promptStamp = () => {
  try { return fs.readFileSync(path.join(MARK_DIR, 'prompt'), 'utf8'); } catch { return ''; }
};

// 명령을 "타이핑되는 것처럼" 입력하고, 프롬프트가 돌아올 때까지 대기(마커 방식)
async function runInTerminal(page, cmd, timeoutMs) {
  const before = promptStamp();
  await page.keyboard.type(cmd, { delay: 35 });
  await sleep(400);
  await page.keyboard.press('Enter');
  if (timeoutMs === 0) return; // 완료를 기다리지 않는 장면(3a)
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (promptStamp() !== before) return;
    await sleep(500);
  }
  throw new Error(`command did not finish in ${timeoutMs}ms: ${cmd}`);
}

async function newRecordingContext(browser) {
  return browser.newContext({
    viewport: SIZE,
    recordVideo: { dir: FOOTAGE, size: SIZE },
  });
}

// page를 닫고 그 page의 영상을 지정 이름으로 저장
async function savePageVideo(page, outName) {
  const video = page.video();
  await page.close();
  const tmp = await video.path();
  const out = path.join(FOOTAGE, outName);
  fs.renameSync(tmp, out);
  console.log(`saved: ${out}`);
}

async function scene1(browser) {
  console.log('--- scene1: oversell ---');
  const ttyd = await startTtyd();
  await sleep(1200);
  const context = await newRecordingContext(browser);
  const page = await openTerminalPage(context);
  await runInTerminal(page, './demo/01-oversell.sh', 180_000);
  await sleep(4000); // 결과 표 클로즈업 정지 컷
  await savePageVideo(page, 'scene1.webm');
  await context.close();
  ttyd.kill();
}

async function scene2(browser) {
  console.log('--- scene2: queue SSE ---');
  const ttyd = await startTtyd();
  await sleep(1200);
  const context = await newRecordingContext(browser);
  const page = await openTerminalPage(context);
  await runInTerminal(page, './demo/02-queue.sh', 300_000);
  await sleep(3000);
  await savePageVideo(page, 'scene2.webm');
  await context.close();
  ttyd.kill();
}

async function grafanaSessionCookie() {
  const res = await fetch('http://localhost:3000/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ user: 'admin', password: 'admin' }),
  });
  const setCookie = res.headers.get('set-cookie') || '';
  const m = setCookie.match(/grafana_session=([^;]+)/);
  if (!m) throw new Error('grafana login failed: ' + setCookie);
  return { name: 'grafana_session', value: m[1], domain: 'localhost', path: '/' };
}

async function widestGatewayTraceId() {
  try {
    const res = await fetch(
      'http://localhost:9411/api/v2/traces?serviceName=api-gateway&limit=50&lookback=900000'
    );
    const traces = await res.json();
    if (!traces.length) return null;
    traces.sort((a, b) => b.length - a.length);
    return traces[0][0].traceId;
  } catch (e) {
    console.warn('zipkin unreachable:', e.message);
    return null;
  }
}

async function scene3(browser) {
  console.log('--- scene3: load + grafana + zipkin ---');
  const ttyd = await startTtyd();
  await sleep(1200);

  // 3a. 터미널: 부하 시작. 주의 — ttyd는 클라이언트가 끊기면 셸(=jmeter)을 죽이므로
  // 페이지를 닫지 않고 장면 끝까지 유지한다. 영상은 최종 조립에서 앞 22초만 트림.
  const ctxA = await newRecordingContext(browser);
  const pageA = await openTerminalPage(ctxA);
  await runInTerminal(pageA, 'USERS=1000 RAMPUP=60 ./demo/03-load.sh', 0);

  // 본테스트 램프업이 그래프에 잡히도록 대기 (부하는 계속 도는 중, 피크 구간에 진입)
  await sleep(132_000);

  // 3b. Grafana kiosk → 패널 훑기 → Zipkin trace
  const ctxB = await newRecordingContext(browser);
  await ctxB.addCookies([await grafanaSessionCookie()]);
  const pageB = await ctxB.newPage();
  await pageB.goto(
    'http://localhost:3000/d/xrail-msa-ops?kiosk&refresh=5s&from=now-5m&to=now',
    { waitUntil: 'networkidle' }
  );
  await sleep(14_000); // 상단: 에러율 0% · 예약 TPS · p95
  for (let i = 0; i < 2; i++) { // HikariCP/결제 → 대기열/서킷브레이커
    await pageB.mouse.wheel(0, 500);
    await sleep(7_000);
  }
  await pageB.mouse.wheel(0, -3000);
  await sleep(10_000); // 상단 복귀 후 갱신되는 그래프 정지 컷

  const traceId = await widestGatewayTraceId();
  if (traceId) {
    await pageB.goto(`http://localhost:9411/zipkin/traces/${traceId}`, { waitUntil: 'networkidle' });
    await sleep(12_000);
  } else {
    console.warn('no gateway trace found — zipkin cut skipped');
  }
  await savePageVideo(pageB, 'scene3b.webm');
  await ctxB.close();
  await savePageVideo(pageA, 'scene3a-full.webm'); // 전체 길이 — 조립 시 -t 22 트림
  await ctxA.close();
  ttyd.kill(); // 부하 종료(촬영 목적 달성)
}

const scenes = { scene1, scene2, scene3 };
const target = process.argv[2] || 'all';
const browser = await chromium.launch();
try {
  if (target === 'all') {
    for (const fn of Object.values(scenes)) await fn(browser);
  } else if (scenes[target]) {
    await scenes[target](browser);
  } else {
    throw new Error(`unknown scene: ${target}`);
  }
} finally {
  await browser.close();
  try { execSync('pkill -f "ttyd -p 7681"'); } catch {}
}
console.log('done.');
