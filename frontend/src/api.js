const API_KEY_STORAGE = 'minigoogle-api-key';

export function getApiKey() {
  try {
    return window.localStorage.getItem(API_KEY_STORAGE) || '';
  } catch {
    return '';
  }
}

export function setApiKey(key) {
  try {
    if (key) window.localStorage.setItem(API_KEY_STORAGE, key);
    else window.localStorage.removeItem(API_KEY_STORAGE);
  } catch {
    // localStorage unavailable; the key just won't persist across reloads.
  }
}

export class ApiError extends Error {
  constructor(message, { status, code, requestId, retryAfter } = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.retryAfter = retryAfter;
  }
}

async function request(url, options = {}) {
  const apiKey = getApiKey();
  const headers = { ...(options.headers || {}) };
  if (apiKey) headers['X-API-Key'] = apiKey;

  let resp;
  try {
    resp = await fetch(url, { ...options, headers });
  } catch (e) {
    throw new ApiError('Could not reach the server. Check your connection.', {});
  }

  let body = null;
  try {
    body = await resp.json();
  } catch {
    body = null;
  }

  if (!resp.ok) {
    const err = body && body.error;
    const retryAfterHeader = resp.headers.get('Retry-After');
    throw new ApiError(
      (err && err.message) || `Request failed (${resp.status})`,
      {
        status: resp.status,
        code: err && err.code,
        requestId: body && body.requestId,
        retryAfter: retryAfterHeader ? parseInt(retryAfterHeader, 10) : null,
      }
    );
  }
  return body;
}

export function suggest(q) {
  return request('/api/v1/suggest?q=' + encodeURIComponent(q));
}

export function search(query, page = 1, pageSize = 10) {
  return request('/api/v1/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, page, pageSize }),
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
