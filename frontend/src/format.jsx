import React from 'react';

export function highlightSnippet(text) {
  if (!text) return '';
  const parts = String(text).split(/\*\*([^*]+)\*\*/g);
  return parts.map((part, i) =>
    i % 2 === 1 ? <mark key={i}>{part}</mark> : part
  );
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
