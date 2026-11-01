// Contract tests with a fake browser, not performance measurements.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/performance/runner.js', 'utf8');

async function run({size = 38625, status = 200, hidden = false} = {}) {
  let clock = 1;
  let report;
  let fetches = 0;
  const element = () => ({append() {}, scrollIntoView() {}});
  const context = {
    performance: {now: () => clock++, getEntriesByType: () => [], getEntriesByName: () => []},
    document: {hidden, readyState: 'complete', getElementById: element, createElement: element},
    requestAnimationFrame: callback => { clock += 16; callback(); },
    setTimeout: (callback, ms) => { if (ms < 1000) { clock += ms; callback(); } return 1; },
    clearTimeout() {}, AbortController,
    fetch: async (_url, options) => {
      fetches++;
      assert.equal(options.credentials, 'omit');
      assert.equal(options.cache, 'no-store');
      return {ok: status === 200, status, blob: async () => ({size})};
    },
    Image: class { async decode() {} scrollIntoView() {} },
    URL: {createObjectURL: () => 'blob:test', revokeObjectURL() {}},
    location: {set href(value) { report = JSON.parse(new URL(value).searchParams.get('data')); }},
  };
  await vm.runInNewContext(script, context);
  return {report, fetches};
}

(async () => {
  const good = await run();
  assert.equal(good.fetches, 3);
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
  console.log('Performance fixture contracts passed (mock data; not benchmark results)');
})().catch(error => { console.error(error); process.exitCode = 1; });
