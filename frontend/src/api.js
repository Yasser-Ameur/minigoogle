async function request(url, options) {
  const resp = await fetch(url, options);
  return resp.json();
}

export function suggest(q) {
  return request('/api/v1/suggest?q=' + encodeURIComponent(q));
}

export function search(query, pageSize = 20) {
  return request('/api/v1/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, page: 1, pageSize }),
  });
}

export function stats() {
  return request('/api/v1/stats');
}

export function analytics() {
  return request('/api/v1/analytics');
}

export function crawl(url) {
  return request('/api/v1/crawl', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }),
  });
}
