const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { downloadUrl, resolveDownload, connectDownloads } = require('../download.js');
const routes = { github: 'https://github.com/example/app.apk', china: 'https://downloads.example.cn/app.apk', countryApi: 'https://country.example/', timeoutMillis: 20 };

test('mainland China IP uses server APK', () => assert.equal(downloadUrl('CN', routes), routes.china));
test('overseas IP uses GitHub APK', () => {
  for (const country of ['US', 'DE', 'JP', 'HK', 'TW', 'SG']) assert.equal(downloadUrl(country, routes), routes.github);
});
test('unavailable or invalid geolocation uses server fallback', () => {
  for (const country of [null, undefined, '', 'unknown', {}, '123']) assert.equal(downloadUrl(country, routes), routes.china);
});
test('server URL absent keeps working GitHub direct link', () => assert.equal(downloadUrl('CN', { ...routes, china: '' }), routes.github));
test('IP lookup does not send credentials', async () => {
  const result = await resolveDownload(routes, async (url, options) => {
    assert.equal(url, routes.countryApi);
    assert.equal(options.credentials, 'omit');
    return { ok: true, json: async () => ({ ip: '203.0.113.1', country: 'CN' }) };
  });
  assert.equal(result, routes.china);
});
test('lookup failure falls back without blocking download', async () => assert.equal(await resolveDownload(routes, async () => { throw Error('offline'); }), routes.china));
test('lookup timeout falls back even if fetch ignores cancellation', async () => assert.equal(await resolveDownload(routes, () => new Promise(() => {})), routes.china));
test('rate-limited lookup falls back', async () => assert.equal(await resolveDownload(routes, async () => ({ ok: false, status: 429 })), routes.china));
test('first click waits for IP result and starts APK download', async () => {
  let handler; let navigated; let prevented = false;
  const link = { href: routes.github, addEventListener: (_, fn) => { handler = fn; }, setAttribute() {}, removeAttribute() {} };
  const selected = connectDownloads({ querySelectorAll: () => [link] }, { assign: (url) => { navigated = url; } }, async () => ({ ok: true, json: async () => ({ country: 'CN' }) }), routes);
  await handler({ button: 0, preventDefault: () => { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(navigated, routes.china);
  assert.equal(link.href, routes.china);
  await selected;
});
test('both language pages expose APK anchors without JavaScript', () => {
  for (const name of ['index.html', 'en.html']) {
    const html = fs.readFileSync(new URL('../' + name, 'file://' + __filename.replaceAll('\\', '/')), 'utf8');
    const buttons = [...html.matchAll(/<a\b[^>]*data-apk-download[^>]*>/g)].map((m) => m[0]);
    assert.equal(buttons.length, 4);
    for (const button of buttons) {
      assert.match(button, /href="https:\/\/github\.com\/[^\"]+\.apk"/);
      assert.doesNotMatch(button, /target="_blank"/);
    }
  }
});
test('no mirror configured does not delay or geolocate the direct download', async () => {
  let handler; let url;
  const link = { addEventListener: (_, fn) => { handler = fn; }, setAttribute() {}, removeAttribute() {} };
  await connectDownloads({ querySelectorAll: () => [link] }, { assign: (value) => { url = value; } }, () => { throw new Error('must not fetch'); }, { ...routes, china: '' });
  await handler({ button: 0, preventDefault() {} });
  assert.equal(url, routes.github);
});
