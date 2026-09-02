import React, { useCallback, useEffect, useRef, useState } from 'react';
import SearchBox from './components/SearchBox';
import { click, crawl, search, setApiKey, stats, suggest } from './api';
import { formatScore, highlightSnippet, monogram, parseUrl } from './format.jsx';

const PAGE_SIZE = 10;

function readLocation() {
  const params = new URLSearchParams(window.location.search);
  const q = params.get('q') || '';
  const page = Math.max(1, parseInt(params.get('page') || '1', 10) || 1);
  return { q, page };
}

function isTypingTarget(el) {
  return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
}

function suggestReformulations(query) {
  const words = query.trim().split(/\s+/).filter(Boolean);
  const out = [];
  if (words.length > 1) {
    out.push(words.slice(0, -1).join(' '));
    out.push(words[words.length - 1]);
    out.push(words[0]);
  }
  return [...new Set(out)].filter((w) => w && w.toLowerCase() !== query.trim().toLowerCase()).slice(0, 3);
}

function Wordmark() {
  return (
    <>
      Mini
      <span className="l-b">G</span><span className="l-r">o</span><span className="l-y">o</span>
      <span className="l-b">g</span><span className="l-g">l</span><span className="l-r">e</span>
    </>
  );
}

function Logo({ size = 'home', onClick }) {
  const cls = size === 'sm' ? 'mini-logo mini-logo--sm' : 'mini-logo mini-logo--home';
  if (size === 'sm') {
    return (
      <button type="button" className={cls} onClick={onClick} aria-label="MiniGoogle home">
        <Wordmark />
      </button>
    );
  }
  return (
    <span className={cls}>
      <Wordmark />
    </span>
  );
}

function ThemeToggle({ theme, onChange }) {
  const options = [
    { value: 'system', label: 'System' },
    { value: 'light', label: 'Light' },
    { value: 'dark', label: 'Dark' },
  ];
  const btnRefs = useRef([]);

  const move = (fromIdx, delta) => {
    const next = (fromIdx + delta + options.length) % options.length;
    onChange(options[next].value);
    const el = btnRefs.current[next];
    if (el) el.focus();
  };

  return (
    <div className="theme-toggle" role="radiogroup" aria-label="Theme">
      {options.map((opt, i) => (
        <button
          key={opt.value}
          ref={(el) => (btnRefs.current[i] = el)}
          type="button"
          role="radio"
          aria-checked={theme === opt.value}
          tabIndex={theme === opt.value ? 0 : -1}
          className={'theme-toggle__btn' + (theme === opt.value ? ' is-active' : '')}
          onClick={() => onChange(opt.value)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
              e.preventDefault();
              move(i, 1);
            } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
              e.preventDefault();
              move(i, -1);
            }
          }}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}

function AddUrl({ onAdded }) {
  const [url, setUrl] = useState('');
  const [status, setStatus] = useState(null);
  const [needsKey, setNeedsKey] = useState(false);
  const [keyInput, setKeyInput] = useState('');

  const attempt = async (value) => {
    setStatus({ text: 'Crawling…', tone: 'muted' });
    try {
      const data = await crawl(value);
      setStatus({ text: 'Added: ' + (data.title || value), tone: 'good' });
      setUrl('');
      setNeedsKey(false);
      onAdded();
    } catch (e) {
      if (e.status === 401) {
        setNeedsKey(true);
        setStatus({ text: 'This needs an API key.', tone: 'bad' });
      } else if (e.status === 429 || e.status === 503) {
        setStatus({ text: `Busy, try again in ${e.retryAfter || 1} s`, tone: 'bad' });
      } else {
        setStatus({
          text: e.message + (e.requestId ? ' · ' + e.requestId : ''),
          tone: 'bad',
        });
      }
    }
  };

  const submit = () => {
    const value = url.trim();
    if (!value) return;
    attempt(value);
  };

  const saveKeyAndRetry = () => {
    const key = keyInput.trim();
    if (!key) return;
    setApiKey(key);
    setKeyInput('');
    setNeedsKey(false);
    attempt(url.trim());
  };

  return (
    <div className="add-url">
      <div className="add-url__row">
        <label htmlFor="add-url-input" className="visually-hidden">URL to add to the index</label>
        <input
          id="add-url-input"
          type="url"
          inputMode="url"
          autoComplete="off"
          placeholder="Add a URL to the index (e.g. https://example.com/page)…"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') submit();
          }}
        />
        <button type="button" onClick={submit}>Add</button>
      </div>
      {status && <p className={'add-url__status add-url__status--' + status.tone}>{status.text}</p>}
      {needsKey && (
        <div className="add-url__key" role="group" aria-label="API key">
          <label htmlFor="add-url-key" className="visually-hidden">API key</label>
          <input
            id="add-url-key"
            type="password"
            autoComplete="off"
            placeholder="API key…"
            value={keyInput}
            onChange={(e) => setKeyInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') saveKeyAndRetry();
            }}
          />
          <button type="button" onClick={saveKeyAndRetry}>Save &amp; retry</button>
        </div>
      )}
    </div>
  );
}

function IndexStats({ data }) {
  if (!data) return null;
  return (
    <div className="index-stats">
      <div className="stat"><div className="stat-val">{data.documentCount}</div>Documents</div>
      <div className="stat"><div className="stat-val">{data.vocabularySize}</div>Terms</div>
      <div className="stat"><div className="stat-val">{data.averageDocumentLength}</div>Avg words</div>
    </div>
  );
}

function ResultCard({ query, position, result, linkRef }) {
  const { host, path } = parseUrl(result.url);

  const onClick = () => {
    if (query) {
      click(query, result.url, position).catch(() => {});
    }
  };

  return (
    <li className="result">
      <div className="result__domain">
        <span className="result__badge" aria-hidden="true">{monogram(host)}</span>
        <span className="result__host">{host}</span>
        {path && <span className="result__path">{path}</span>}
      </div>
      <h3 className="result__title">
        <a ref={linkRef} href={result.url} target="_blank" rel="noreferrer" onClick={onClick}>
          {result.title}
        </a>
      </h3>
      <p className="result__snippet">{highlightSnippet(result.snippet)}</p>
      <details className="result__why">
        <summary>Why this result</summary>
        <ul>
          {result.bm25Score > 0 && <li>Term match (BM25): {formatScore(result.bm25Score)}</li>}
          {result.pageRankScore > 0 && <li>Link authority (PageRank): {formatScore(result.pageRankScore)}</li>}
          <li>Combined score: {formatScore(result.score)}</li>
        </ul>
      </details>
    </li>
  );
}

function Skeleton() {
  return (
    <ul className="skeleton-list" aria-hidden="true">
      {Array.from({ length: 6 }).map((_, i) => (
        <li className="skeleton-card" key={i}>
          <div className="skeleton-line skeleton-line--domain" />
          <div className="skeleton-line skeleton-line--title" />
          <div className="skeleton-line skeleton-line--snippet" />
          <div className="skeleton-line skeleton-line--snippet2" />
        </li>
      ))}
    </ul>
  );
}

function ErrorBlock({ error, onRetry }) {
  const isBadRequest = error.status === 400;
  return (
    <div className="state-block state-block--error" role="alert">
      <p className="state-block__title">
        {isBadRequest ? 'That query could not be parsed.' : 'Something went wrong.'}
      </p>
      <p>{isBadRequest ? 'Try rephrasing your search.' : error.message}</p>
      {error.requestId && <p className="state-block__meta">Request ID: {error.requestId}</p>}
      <button type="button" onClick={onRetry}>Try again</button>
    </div>
  );
}

function ZeroResults({ query, didYouMean, onSearch, onAddUrl }) {
  const reformulations = suggestReformulations(query);
  const [prefixSuggestions, setPrefixSuggestions] = useState([]);

  useEffect(() => {
    const prefix = query.trim().slice(0, 3);
    if (prefix.length < 2) {
      setPrefixSuggestions([]);
      return;
    }
    let cancelled = false;
    suggest(prefix)
      .then((items) => {
        if (!cancelled) setPrefixSuggestions((items || []).filter((s) => s.toLowerCase() !== query.trim().toLowerCase()).slice(0, 5));
      })
      .catch(() => {
        if (!cancelled) setPrefixSuggestions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [query]);

  return (
    <div className="state-block state-block--empty">
      <p className="state-block__title">No results for &ldquo;{query}&rdquo;.</p>
      <p>Try different keywords or check your spelling.</p>
      {didYouMean && (
        <button type="button" className="did-you-mean" onClick={() => onSearch(didYouMean)}>
          Did you mean <strong>{didYouMean}</strong>?
        </button>
      )}
      {reformulations.length > 0 && (
        <div className="reformulations">
          <span>Try instead:</span>
          {reformulations.map((r) => (
            <button type="button" key={r} onClick={() => onSearch(r)}>{r}</button>
          ))}
        </div>
      )}
      {!didYouMean && reformulations.length === 0 && prefixSuggestions.length > 0 && (
        <div className="reformulations">
          <span>Did you mean:</span>
          {prefixSuggestions.map((s) => (
            <button type="button" key={s} onClick={() => onSearch(s)}>{s}</button>
          ))}
        </div>
      )}
      <p className="state-block__hint">
        Try fewer or different words, or{' '}
        <button type="button" className="link-btn" onClick={onAddUrl}>add the page you are looking for</button>.
      </p>
    </div>
  );
}

function Pager({ page, pageCount, hasNext, onGo }) {
  if (pageCount <= 1 && !hasNext) return null;
  const nums = [];
  const start = Math.max(1, page - 2);
  const end = Math.min(pageCount, start + 4);
  for (let n = Math.max(1, end - 4); n <= end; n++) nums.push(n);

  return (
    <nav className="pager" aria-label="Search results pages">
      <button type="button" disabled={page <= 1} onClick={() => onGo(page - 1)}>Previous</button>
      {nums[0] > 1 && <span className="pager__ellipsis">…</span>}
      {nums.map((n) => (
        <button
          key={n}
          type="button"
          className={n === page ? 'is-current' : ''}
          aria-current={n === page ? 'page' : undefined}
          onClick={() => onGo(n)}
        >
          {n}
        </button>
      ))}
      {(nums[nums.length - 1] < pageCount || hasNext) && <span className="pager__ellipsis">…</span>}
      <button type="button" disabled={!hasNext} onClick={() => onGo(page + 1)}>Next</button>
    </nav>
  );
}

export default function App() {
  const initial = readLocation();
  const [view, setView] = useState(initial.q ? 'results' : 'home');
  const [query, setQuery] = useState(initial.q);
  const [page, setPage] = useState(initial.page);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [statsData, setStatsData] = useState(null);
  const [theme, setTheme] = useState(() => {
    try {
      return window.localStorage.getItem('minigoogle-theme') || 'system';
    } catch {
      return 'system';
    }
  });

  const searchBoxRef = useRef(null);
  const resultRefs = useRef([]);

  useEffect(() => {
    const root = document.documentElement;
    if (theme === 'system') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', theme);
    try {
      window.localStorage.setItem('minigoogle-theme', theme);
    } catch {
      // localStorage unavailable; the choice just won't persist.
    }
  }, [theme]);

  const refreshStats = useCallback(() => {
    stats().then(setStatsData).catch(() => {});
  }, []);

  useEffect(() => {
    if (view === 'home') refreshStats();
  }, [view, refreshStats]);

  const runSearch = useCallback((q, p) => {
    setLoading(true);
    setError(null);
    search(q, p, PAGE_SIZE)
      .then((result) => setData(result))
      .catch((e) => setError(e))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (view === 'results' && query) {
      setData(null);
      runSearch(query, page);
    }
  }, [view, query, page, runSearch]);

  useEffect(() => {
    document.title = query ? `${query} — MiniGoogle` : 'MiniGoogle';
  }, [query]);

  useEffect(() => {
    function onPop() {
      const loc = readLocation();
      if (loc.q) {
        setView('results');
        setQuery(loc.q);
        setPage(loc.page);
      } else {
        setView('home');
        setQuery('');
        setPage(1);
        setData(null);
      }
    }
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  const navigateSearch = useCallback((q, p = 1) => {
    const trimmed = (q || '').trim();
    if (!trimmed) return;
    const params = new URLSearchParams();
    params.set('q', trimmed);
    if (p > 1) params.set('page', String(p));
    window.history.pushState(null, '', '/?' + params.toString());
    setView('results');
    setQuery(trimmed);
    setPage(p);
  }, []);

  const goToPage = useCallback(
    (p) => {
      const params = new URLSearchParams();
      params.set('q', query);
      if (p > 1) params.set('page', String(p));
      window.history.pushState(null, '', '/?' + params.toString());
      setPage(p);
      window.scrollTo({ top: 0 });
    },
    [query]
  );

  const goHome = useCallback(() => {
    window.history.pushState(null, '', '/');
    setView('home');
    setQuery('');
    setPage(1);
    setData(null);
    setError(null);
    refreshStats();
  }, [refreshStats]);

  useEffect(() => {
    function onKeyDown(e) {
      if (e.key === '/' && !isTypingTarget(document.activeElement)) {
        e.preventDefault();
        searchBoxRef.current && searchBoxRef.current.focus();
        return;
      }
      if (isTypingTarget(document.activeElement)) return;
      const links = resultRefs.current.filter(Boolean);
      if (links.length === 0) return;
      const idx = links.indexOf(document.activeElement);
      if (e.key === 'ArrowDown' || e.key === 'j') {
        e.preventDefault();
        links[idx < 0 ? 0 : Math.min(idx + 1, links.length - 1)].focus();
      } else if (e.key === 'ArrowUp' || e.key === 'k') {
        e.preventDefault();
        links[idx <= 0 ? 0 : idx - 1].focus();
      } else if (e.key === 'Escape') {
        searchBoxRef.current && searchBoxRef.current.focus();
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [data, page]);

  const results = data && data.results ? data.results : [];
  const hasNext = results.length === PAGE_SIZE;
  const exactTotal = data && !hasNext ? (page - 1) * PAGE_SIZE + results.length : null;
  const pageCount = hasNext
    ? Math.max(page + 1, Math.ceil((data.totalResults || 0) / PAGE_SIZE))
    : page;
  const didYouMean = data ? data.didYouMean : null;
  const liveMessage =
    view === 'results' && !loading && !error && data
      ? results.length > 0
        ? `${hasNext ? data.totalResults : exactTotal} results for ${query}`
        : `No results for ${query}`
      : '';

  resultRefs.current = [];

  return (
    <>
      <a className="skip-link" href="#main">Skip to results</a>
      <header className={view === 'home' ? 'site-header site-header--home' : 'site-header'}>
        {view === 'results' && (
          <>
            <Logo size="sm" onClick={goHome} />
            <div className="search-box">
              <SearchBox
                ref={searchBoxRef}
                initialQuery={query}
                size="sm"
                onSubmit={(q) => navigateSearch(q, 1)}
              />
            </div>
          </>
        )}
        <ThemeToggle theme={theme} onChange={setTheme} />
      </header>

      <main id="main" aria-busy={loading}>
        <div className="visually-hidden" role="status" aria-live="polite">{liveMessage}</div>
        {view === 'home' ? (
          <div className="home">
            <Logo size="home" />
            <div className="search-box">
              <SearchBox ref={searchBoxRef} onSubmit={(q) => navigateSearch(q, 1)} autoFocus />
            </div>
            <AddUrl onAdded={refreshStats} />
            <IndexStats data={statsData} />
          </div>
        ) : (
          <div className="results-page">
            {loading && <Skeleton />}

            {!loading && error && <ErrorBlock error={error} onRetry={() => runSearch(query, page)} />}

            {!loading && !error && data && results.length === 0 && (
              <ZeroResults query={query} didYouMean={didYouMean} onSearch={(q) => navigateSearch(q, 1)} onAddUrl={goHome} />
            )}

            {!loading && !error && data && results.length > 0 && (
              <>
                <p className="results-stats">
                  {hasNext ? `About ${data.totalResults} results` : `${exactTotal} results`}
                  {' '}({data.executionTimeMs} ms)
                </p>
                {didYouMean && (
                  <button type="button" className="did-you-mean" onClick={() => navigateSearch(didYouMean, 1)}>
                    Did you mean <strong>{didYouMean}</strong>?
                  </button>
                )}
                <ul className="result-list">
                  {results.map((r, i) => (
                    <ResultCard
                      key={r.url}
                      query={query}
                      position={(page - 1) * PAGE_SIZE + i + 1}
                      result={r}
                      linkRef={(el) => (resultRefs.current[i] = el)}
                    />
                  ))}
                </ul>
                <Pager page={page} pageCount={pageCount} hasNext={hasNext} onGo={goToPage} />
              </>
            )}
          </div>
        )}
      </main>

      <footer className="site-footer">MiniGoogle — a search engine built from scratch</footer>
    </>
  );
}
