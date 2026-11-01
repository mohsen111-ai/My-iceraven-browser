/* Synthetic fixture: no page history, cookies, extension access or arbitrary URLs. */
(async () => {
  const result = {fixture: 1};
  const frame = () => new Promise(resolve => requestAnimationFrame(resolve));
  const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
  const checkVisible = () => {
    if (document.hidden) throw new Error('Fixture was backgrounded; measurements invalid');
  };
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
    await frame(); await frame(); checkVisible();
    result.fixture_dom_to_render_opportunity_ms = performance.now() - start;
    await new Promise(resolve => document.readyState === 'complete' ? resolve() : window.addEventListener('load', resolve, {once: true}));
    await wait(0);
    const navigation = performance.getEntriesByType('navigation')[0];
    result.dom_content_loaded_ms = navigation?.domContentLoadedEventEnd > 0 ? navigation.domContentLoadedEventEnd : 'unavailable';
    result.document_load_ms = navigation?.loadEventEnd > 0 ? navigation.loadEventEnd : 'unavailable';
    const paint = performance.getEntriesByName('first-contentful-paint')[0];
    result.first_contentful_paint_ms = paint ? paint.startTime : 'unavailable from this Gecko document';
  } catch (error) { result.page_error = String(error); }
  try {
    result.images = [];
    const url = 'https://raw.githubusercontent.com/mohsen111-ai/My-iceraven-browser/488386baa8ce7e280364edbb041daf625bb3c08d/app/src/forkDebug/ic_launcher-web.png';
    for (let i = 0; i < 3; i++) {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 15000);
      let objectUrl;
      try {
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
        await image.decode();
        const decoded = performance.now();
        document.getElementById('images').append(image);
        image.scrollIntoView();
        await frame(); await frame(); checkVisible();
        result.images.push({bytes: blob.size, fetch_ms: received - start,
          decode_ms: decoded - received, fetch_to_render_opportunity_ms: performance.now() - start});
      } finally { clearTimeout(timeout); if (objectUrl) URL.revokeObjectURL(objectUrl); }
    }
  } catch (error) { result.image_error = String(error); }
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
  // This navigation is intercepted and denied by the dedicated session's delegate.
  location.href = 'https://performance.invalid/report?token=__TOKEN__&data=' + encodeURIComponent(JSON.stringify(result));
})();
