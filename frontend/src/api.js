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

let sessionId = null;

export function getSessionId() {
  if (sessionId) return sessionId;
  let stored = null;
  try {
    stored = window.sessionStorage.getItem('minigoogle-session');
  } catch {
    stored = null;
  }
  if (!stored) {
    stored = 's-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
    try {
      window.sessionStorage.setItem('minigoogle-session', stored);
    } catch {
      // sessionStorage unavailable (privacy mode); keep the in-memory id.
    }
  }
  sessionId = stored;
  return sessionId;
}

export function click(query, url, position) {
  return request('/api/v1/click', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, url, position, sessionId: getSessionId() }),
  });
}
