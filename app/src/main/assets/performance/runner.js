/* Synthetic fixture: no page history, cookies, extension access or arbitrary URLs. */
(async () => {
  const result = {fixture: 2};
  // Titles deliver checkpoints without navigating away or depending on a render frame.
  const progress = stage => {
    document.title = 'iceraven-performance:__TOKEN__:' + JSON.stringify({stage, result});
  };
  const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
  const checkVisible = () => {
    if (document.hidden) throw new Error('Fixture was backgrounded; measurements invalid');
  };
  const bounded = async (promise, ms, label) => {
    let timer;
    try {
      return await Promise.race([promise, new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(label + ' timed out')), ms);
      })]);
    } finally { clearTimeout(timer); }
  };
  const frame = async () => {
    checkVisible();
    let id;
    try {
      await bounded(new Promise(resolve => { id = requestAnimationFrame(resolve); }), 3000, 'Animation frame');
      checkVisible();
    } finally { cancelAnimationFrame(id); }
  };
  progress('script-started');
  const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
  // Fixed work, fixed seed, observable checksum. A microbenchmark, not a browser score.
  function workload() {
    let value = 123456789;
    for (let i = 0; i < 1000000; i++) {
      value ^= value << 13; value ^= value >>> 17; value ^= value << 5;
    }
    return value >>> 0;
  }
  try {
    const start = performance.now();
    const grid = document.getElementById('grid');
    for (let i = 0; i < 120; i++) {
      const tile = document.createElement('div');
      tile.className = 'tile'; tile.textContent = String(i); grid.append(tile);
    }
    progress('page-frames');
    await frame(); await frame();
    result.fixture_dom_to_render_opportunity_ms = performance.now() - start;
    progress('page-load');
    await bounded(new Promise(resolve => document.readyState === 'complete' ? resolve() : window.addEventListener('load', resolve, {once: true})), 5000, 'Document load');
    await wait(0);
    const navigation = performance.getEntriesByType('navigation')[0];
    result.dom_content_loaded_ms = navigation?.domContentLoadedEventEnd > 0 ? navigation.domContentLoadedEventEnd : 'unavailable';
    result.document_load_ms = navigation?.loadEventEnd > 0 ? navigation.loadEventEnd : 'unavailable';
    const paint = performance.getEntriesByName('first-contentful-paint')[0];
    result.first_contentful_paint_ms = paint ? paint.startTime : 'unavailable from this Gecko document';
  } catch (error) { result.page_error = String(error); }
  // Finish independent CPU work before any network/decode wait can consume the stage budget.
  progress('javascript');
  try {
    workload(); workload(); // Two unrecorded warmup iterations.
    const samples = [];
    let checksum;
    for (let i = 0; i < 5; i++) {
      await wait(50); checkVisible();
      const start = performance.now(); checksum = workload(); samples.push(performance.now() - start);
    }
    result.javascript = {workload: 'xorshift32, 1000000 iterations, 2 warmups, 5 samples',
      samples_ms: samples, median_ms: median(samples), checksum};
  } catch (error) { result.javascript_error = String(error); }
  progress('javascript-complete');
  try {
    result.images = [];
    const url = 'https://raw.githubusercontent.com/mohsen111-ai/My-iceraven-browser/488386baa8ce7e280364edbb041daf625bb3c08d/app/src/forkDebug/ic_launcher-web.png';
    for (let i = 0; i < 3; i++) {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 15000);
      let objectUrl;
      try {
        progress('image-' + (i + 1) + '-fetch');
        checkVisible();
        const start = performance.now();
        const response = await fetch(url + '?sample=' + i + '&run=__TOKEN__', {
          cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer', signal: controller.signal,
        });
        if (!response.ok) throw new Error('Image HTTP ' + response.status);
        const blob = await response.blob();
        if (blob.size !== 38625) throw new Error('Unexpected image size: ' + blob.size);
        const received = performance.now();
        const image = new Image();
        objectUrl = URL.createObjectURL(blob); image.src = objectUrl;
        progress('image-' + (i + 1) + '-decode');
        await bounded(image.decode(), 5000, 'Image decode');
        const decoded = performance.now();
        document.getElementById('images').append(image);
        image.scrollIntoView();
        progress('image-' + (i + 1) + '-frames');
        await frame(); await frame();
        result.images.push({bytes: blob.size, fetch_ms: received - start,
          decode_ms: decoded - received, fetch_to_render_opportunity_ms: performance.now() - start});
      } finally { clearTimeout(timeout); if (objectUrl) URL.revokeObjectURL(objectUrl); }
    }
  } catch (error) { result.image_error = String(error); }
  progress('report-ready');
  // This navigation is intercepted and denied by the dedicated session's delegate.
  location.href = 'https://performance.invalid/report?token=__TOKEN__&data=' + encodeURIComponent(JSON.stringify(result));
})();
