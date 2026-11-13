const scenePath = /^\/scene\/(movie|tv)\/([0-9]{1,10})\/([0-9]{1,4})\/([0-9]{1,4})\/([0-9]{1,8})\/([0-9]{1,8})$/;
const host = 'aliflix-recommendations.equable-equipment.workers.dev';
const download = 'https://github.com/alishaban144/aliflix-android/releases/latest';

function escapeHtml(text: string): string {
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

export function sharedSceneRoute(request: Request): Response | null {
  const url = new URL(request.url);
  if (!url.pathname.startsWith('/scene/')) return null;
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response(null, { status: 405, headers: { allow: 'GET, HEAD' } });
  }
  const match = scenePath.exec(url.pathname);
  if (!match || request.url.length > 3200) return new Response('Invalid scene link', { status: 404 });
  const [, kind, idText, seasonText, episodeText, startText, endText] = match;
  const [id, season, episode, start, end] = [idText, seasonText, episodeText, startText, endText].map(Number);
  if (id <= 0 || id > 2147483647 || (kind === 'tv' ? episode === 0 : season !== 0 || episode !== 0) ||
      start >= end || end > 86400000) return new Response('Invalid scene link', { status: 404 });

  const query = new URLSearchParams();
  for (const name of ['title', 'source', 'imdb', 'lang', 'year', 'animation']) {
    const value = url.searchParams.get(name)?.slice(0, name === 'title' ? 180 : 40);
    if (value) query.set(name, value);
  }
  const title = escapeHtml(query.get('title')?.trim() || 'Share Scene');
  const open = escapeHtml(`intent://${host}${url.pathname}?${query}#Intent;scheme=https;package=com.aliflix.app;S.browser_fallback_url=${encodeURIComponent(download)};end`);
  const body = `<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${title} · Aliflix</title>
<style>body{margin:0;background:#08090e;color:#faf7ff;font:16px system-ui;min-height:100vh;display:grid;place-items:center}
main{width:min(24rem,calc(100% - 40px));text-align:center}h1{overflow-wrap:anywhere}a{display:block;background:#c6b6ff;color:#170f28;border-radius:16px;padding:16px;margin:12px 0;text-decoration:none}
a+a{background:#1d1a29;color:#f5efff}</style>
<main><h1>${title}</h1><a href="${open}">Open Aliflix</a><a href="${download}">Get Aliflix</a></main></html>`;
  return new Response(request.method === 'HEAD' ? null : body, { headers: {
    'content-type': 'text/html; charset=utf-8',
    'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer',
  } });
}
