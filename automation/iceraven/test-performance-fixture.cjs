// Contract tests with a fake browser, not performance measurements.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/performance/runner.js', 'utf8');
const template = fs.readFileSync('app/src/main/assets/performance/page.html', 'utf8');
const activity = fs.readFileSync('app/src/main/java/org/mozilla/fenix/perf/PerformanceTestActivity.kt', 'utf8');
const token = 'test-run-token';
const fixture = template.replace('/*PERFORMANCE_SCRIPT*/', script.replaceAll('__TOKEN__', token));

// Model GeckoSession.Loader's two overloads at the URI boundary, not just bare JS.
// String data is concatenated verbatim; byte[] data is encoded as Base64 (NO_WRAP).
// https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/GeckoSession.java
const stringUri = new URL('data:text/html,' + fixture);
assert.ok(stringUri.hash.startsWith('#fafafa'), 'Regression fixture must contain the CSS fragment trigger');
assert.ok(!stringUri.pathname.includes('<script>'), 'The old String overload loses the script');
assert.match(activity, /\.data\(fixture\.toByteArray\(Charsets\.UTF_8\), "text\/html"\)/,
  'The app must select the byte[] Loader overload so # and % cannot corrupt the document');
const byteUri = new URL('data:text/html;base64,' + Buffer.from(fixture, 'utf8').toString('base64'));
assert.equal(byteUri.hash, '');
const decoded = Buffer.from(byteUri.pathname.split(',')[1], 'base64').toString('utf8');
assert.equal(decoded, fixture);
const loadedScript = decoded.match(/<script>([\s\S]*)<\/script>/)[1];
new vm.Script(loadedScript);

async function run({size = 38625, status = 200, hidden = false, stalledFrame = false,
  stalledDecode = false, stalledLoad = false, stalledFetch = false, backgroundOnFrame = false} = {}) {
  let clock = 1;
  let report;
  let fetches = 0;
  let revoked = 0;
  let cancelledFrames = 0;
  const checkpoints = [];
  const timers = new Set();
  const element = () => ({append() {}, scrollIntoView() {}});
  const context = {
    performance: {now: () => clock++, getEntriesByType: () => [], getEntriesByName: () => []},
    document: {hidden, readyState: stalledLoad ? 'loading' : 'complete',
      getElementById: element, createElement: element,
      set title(value) {
        const prefix = 'iceraven-performance:' + token + ':';
        assert.ok(value.startsWith(prefix));
        assert.ok(value.length < 16384);
        checkpoints.push(JSON.parse(value.slice(prefix.length)));
      },
    },
    window: {addEventListener() {}},
    requestAnimationFrame: callback => {
      if (backgroundOnFrame) context.document.hidden = true;
      if (!stalledFrame) { clock += 16; callback(); }
      return 1;
    },
    cancelAnimationFrame() { cancelledFrames++; },
    // Accelerate deadlines; real scheduling lets unresolved frame/decode/load promises time out.
    setTimeout: (callback, ms) => {
      const timer = setTimeout(() => { timers.delete(timer); clock += ms; callback(); }, ms >= 1000 ? 20 : 0);
      timers.add(timer);
      return timer;
    },
    clearTimeout(timer) { timers.delete(timer); clearTimeout(timer); }, AbortController,
    fetch: async (_url, options) => {
      fetches++;
      assert.equal(options.credentials, 'omit');
      assert.equal(options.cache, 'no-store');
      assert.ok(checkpoints.some(point => point.result.javascript), 'CPU results precede network waits');
      if (stalledFetch) return new Promise((_, reject) => {
        options.signal.addEventListener('abort', () => reject(new Error('Image fetch aborted')));
      });
      return {ok: status === 200, status, blob: async () => ({size})};
    },
    Image: class { async decode() { if (stalledDecode) await new Promise(() => {}); } scrollIntoView() {} },
    URL: {createObjectURL: () => 'blob:test', revokeObjectURL() { revoked++; }},
    location: {set href(value) {
      const uri = new URL(value);
      assert.equal(uri.origin, 'https://performance.invalid');
      assert.equal(uri.pathname, '/report');
      assert.equal(uri.searchParams.get('token'), token);
      report = JSON.parse(uri.searchParams.get('data'));
    }},
  };
  try {
    await vm.runInNewContext(loadedScript, context);
    assert.equal(timers.size, 0, 'Every deadline timer must be cleared');
  } finally { for (const timer of timers) clearTimeout(timer); }
  return {report, fetches, checkpoints, revoked, cancelledFrames};
}

(async () => {
  const good = await run();
  assert.equal(good.fetches, 3);
  assert.equal(good.report.fixture, 2);
  assert.equal(good.revoked, 3);
  assert.equal(good.checkpoints[0].stage, 'script-started');
  assert.equal(good.checkpoints.at(-1).stage, 'report-ready');
  assert.deepEqual(good.checkpoints.at(-1).result, good.report);
  assert.equal(good.report.images.length, 3);
  assert.equal(good.report.javascript.samples_ms.length, 5);
  assert.equal(good.report.dom_content_loaded_ms, 'unavailable');
  assert.match(good.report.first_contentful_paint_ms, /unavailable/);
  assert.equal(good.report.javascript.checksum, (await run()).report.javascript.checksum);
  const truncated = (await run({size: 100})).report;
  assert.match(truncated.image_error, /Unexpected image size/);
  assert.ok(truncated.javascript, 'Image failure must not suppress independent JavaScript results');
  assert.match((await run({status: 503})).report.image_error, /503/);
  const background = (await run({hidden: true})).report;
  assert.match(background.page_error, /backgrounded/);
  assert.match(background.javascript_error, /backgrounded/);
  const frames = await run({stalledFrame: true});
  assert.match(frames.report.page_error, /Animation frame timed out/);
  assert.ok(frames.report.javascript, 'Missing frames must not suppress independent CPU results');
  assert.ok(frames.cancelledFrames > 0);
  assert.match((await run({backgroundOnFrame: true})).report.page_error, /backgrounded/);
  const decode = await run({stalledDecode: true});
  assert.match(decode.report.image_error, /Image decode timed out/);
  assert.ok(decode.report.javascript);
  assert.equal(decode.revoked, 1, 'A timed-out decode must revoke its blob URL');
  assert.match((await run({stalledLoad: true})).report.page_error, /Document load timed out/);
  const fetch = await run({stalledFetch: true});
  assert.match(fetch.report.image_error, /Image fetch aborted/);
  assert.ok(fetch.checkpoints.find(point => point.stage === 'image-1-fetch').result.javascript);
  console.log('Performance fixture contracts passed (mock data; not benchmark results)');
})().catch(error => { console.error(error); process.exitCode = 1; });
