import React from 'react';

export function highlightSnippet(text) {
  if (!text) return '';
  // The server marks only the matched stem (e.g. **Comput**er); extend the
  // mark to the rest of the word so the highlight covers the whole term.
  const raw = String(text).split(/\*\*([^*]+)\*\*/g);
  const parts = [];
  for (let i = 0; i < raw.length; i++) {
    if (i % 2 === 1) {
      let marked = raw[i];
      const rest = raw[i + 1] || '';
      const wordTail = rest.match(/^[A-Za-z0-9]+/);
      if (wordTail) {
        marked += wordTail[0];
        raw[i + 1] = rest.slice(wordTail[0].length);
      }
      parts.push(<mark key={i}>{marked}</mark>);
    } else {
      parts.push(raw[i]);
    }
  }
  return parts;
}

export function formatScore(value) {
  return (value * 100).toFixed(1) + '%';
}

const MAX_PATH_LENGTH = 42;

export function parseUrl(url) {
  try {
    const u = new URL(url);
    let path = u.pathname + u.search;
    if (path === '/') path = '';
    if (path.length > MAX_PATH_LENGTH) {
      path = path.slice(0, MAX_PATH_LENGTH - 1) + '…';
    }
    return { host: u.hostname.replace(/^www\./, ''), path };
  } catch {
    return { host: url, path: '' };
  }
}

export function monogram(host) {
  const clean = (host || '').replace(/^www\./, '');
  return clean.slice(0, 1).toUpperCase() || '?';
}
