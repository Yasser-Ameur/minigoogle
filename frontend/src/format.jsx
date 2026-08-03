import React from 'react';

export function highlightSnippet(text) {
  if (!text) return '';
  const parts = String(text).split(/\*\*([^*]+)\*\*/g);
  return parts.map((part, i) =>
    i % 2 === 1 ? <b key={i}>{part}</b> : part
  );
}

export function formatScore(value) {
  return (value * 100).toFixed(1) + '%';
}
