(function (root) {
  'use strict';

  const config = Object.freeze({
    github: 'https://github.com/mionio233-collab/dsharness-go/releases/download/v1.0.12/DSHarness-Go-v1.0.12-arm64-v8a.apk',
    china: '',
    countryApi: 'https://api.country.is/',
    timeoutMillis: 1800,
  });

  function downloadUrl(country, routes) {
    const code = typeof country === 'string' ? country.trim().toUpperCase() : '';
    if (/^[A-Z]{2}$/.test(code) && code !== 'CN') return routes.github;
    return routes.china || routes.github;
  }

  async function resolveDownload(routes, fetcher, controllerFactory = () => new AbortController()) {
    const controller = controllerFactory();
    let timer;
    try {
      const lookup = (async () => {
        const response = await fetcher(routes.countryApi, {
          signal: controller.signal,
          credentials: 'omit',
          cache: 'no-store',
          mode: 'cors',
        });
        if (!response.ok) throw new Error('Country lookup failed');
        const result = await response.json();
        return downloadUrl(result.country, routes);
      })();
      const timeout = new Promise((resolve) => {
        timer = setTimeout(() => {
          resolve(downloadUrl(null, routes));
          controller.abort();
        }, routes.timeoutMillis);
      });
      return await Promise.race([lookup, timeout]);
    } catch {
      return downloadUrl(null, routes);
    } finally {
      clearTimeout(timer);
    }
  }

  function connectDownloads(document, location, fetcher, routes = config) {
    const links = Array.from(document.querySelectorAll('[data-apk-download]'));
    if (!links.length) return Promise.resolve(routes.github);
    const resolved = routes.china ? resolveDownload(routes, fetcher) : Promise.resolve(routes.github);
    resolved.then((url) => { links.forEach((link) => { link.href = url; }); });
    links.forEach((link) => {
      let pending = false;
      link.addEventListener('click', async (event) => {
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        if (pending) return;
        pending = true;
        link.setAttribute('aria-busy', 'true');
        try {
          const url = await resolved;
          link.href = url;
          location.assign(url);
        } finally {
          pending = false;
          link.removeAttribute('aria-busy');
        }
      });
    });
    return resolved;
  }

  if (typeof module === 'object' && module.exports) {
    module.exports = { downloadUrl, resolveDownload, connectDownloads, config };
  } else if (root.document && root.fetch) {
    connectDownloads(root.document, root.location, root.fetch.bind(root));
  }
})(typeof window === 'undefined' ? globalThis : window);
