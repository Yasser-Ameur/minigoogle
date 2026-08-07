import React, { useCallback, useEffect, useState } from 'react';
import SearchBox from './components/SearchBox';
import { analytics, click, crawl, search, stats } from './api';
import { formatScore, highlightSnippet } from './format.jsx';

function Logo({ size = 'home', onClick }) {
  const cls = size === 'sm' ? 'mini-logo mini-logo--sm' : 'mini-logo mini-logo--home';
  return (
    <span className={cls} onClick={onClick}>
      <span className="m">M</span><span className="i">i</span><span className="n">n</span>
      <span className="g">i</span><span className="o">G</span><span className="g2">o</span>
      <span className="l">o</span><span className="e">g</span><span className="m">l</span>
      <span className="i">e</span>
    </span>
  );
}

function AddUrl({ onAdded }) {
  const [url, setUrl] = useState('');
  const [status, setStatus] = useState(null);

  const submit = async () => {
    const value = url.trim();
    if (!value) return;
    setStatus({ text: 'Crawling...', color: '#70757a' });
    try {
      const data = await crawl(value);
      if (data.success) {
        setStatus({ text: 'Added: ' + (data.title || value), color: '#34a853' });
        setUrl('');
        onAdded();
      } else {
        setStatus({ text: 'Error: ' + (data.error || 'failed'), color: '#ea4335' });
      }
    } catch (e) {
      setStatus({ text: 'Error: ' + e.message, color: '#ea4335' });
    }
  };

  return (
    <div className="add-url">
      <input
        type="url"
        placeholder="Add a URL to the index (e.g. https://example.com/page)"
        value={url}
        onChange={(e) => setUrl(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') submit();
        }}
      />
      <button onClick={submit}>Add</button>
      {status && <span className="status" style={{ color: status.color }}>{status.text}</span>}
    </div>
  );
}

function IndexStats({ data }) {
  if (!data) return null;
  return (
    <div className="index-stats">
      <div className="stat"><div className="stat-val">{data.documentCount}</div>Documents</div>
      <div className="stat"><div className="stat-val">{data.vocabularySize}</div>Terms</div>
      <div className="stat"><div className="stat-val">{data.averageDocumentLength}</div>Avg Words</div>
    </div>
  );
}

function ResultCard({ query, position, result }) {
  const scores = [];
  if (result.bm25Score > 0) scores.push('BM25: ' + formatScore(result.bm25Score));
  if (result.pageRankScore > 0) scores.push('PageRank: ' + formatScore(result.pageRankScore));
  scores.push('Score: ' + formatScore(result.score));

  // Report the click to the backend for learning-to-rank training. Fire and
  // forget so the click never blocks navigation, and never fail the UI.
  const onClick = () => {
    if (query) {
      click(query, result.url, position).catch(() => {});
    }
  };

  return (
    <div className="result">
      <div className="url">{result.url}</div>
      <div className="title">
        <a href={result.url} target="_blank" rel="noreferrer" onClick={onClick}>{result.title}</a>
      </div>
      <div className="snippet">{highlightSnippet(result.snippet)}</div>
      <div className="score">{scores.join(' \u00b7 ')}</div>
    </div>
  );
}

function ResultsPage({ query, onLogoClick, onSearch, onData, data }) {
  const [loading, setLoading] = useState(false);
  const [analyticsData, setAnalyticsData] = useState(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    (async () => {
      try {
        const result = await search(query);
        if (!cancelled) onData(result);
      } catch {
        if (!cancelled) onData({ results: [], totalResults: 0, executionTimeMs: 0 });
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [query, onData]);

  useEffect(() => {
    if (data && data.results && data.results.length > 0) {
      analytics().then(setAnalyticsData).catch(() => {});
    }
  }, [data]);

  const results = data ? data.results : [];
  const total = data ? data.totalResults : 0;
  const ms = data ? data.executionTimeMs : 0;
  const didYouMean = data ? data.didYouMean : null;

  return (
    <div className="results-page">
      <div className="top-bar">
        <Logo size="sm" onClick={onLogoClick} />
        <div className="search-box">
          <SearchBox initialQuery={query} size="sm" onSubmit={onSearch} />
        </div>
      </div>

      {loading && <div className="loading"><span className="spinner" />Searching...</div>}

      {!loading && data && results.length === 0 && (
        <div className="no-results">
          Your search did not match any documents.
          <br />
          Try different keywords or check spelling.
          {didYouMean && (
            <div className="did-you-mean" onClick={() => onSearch(didYouMean)}>
              Did you mean: <b>{didYouMean}</b>
            </div>
          )}
        </div>
      )}

      {!loading && data && results.length > 0 && (
        <>
          <div className="results-stats">About {total} results ({ms} ms)</div>
          {didYouMean && (
            <div className="did-you-mean" onClick={() => onSearch(didYouMean)}>
              Did you mean: <b>{didYouMean}</b>
            </div>
          )}
          {results.map((r, i) => <ResultCard key={r.url} query={query} position={i + 1} result={r} />)}
          {analyticsData && analyticsData.totalQueries > 0 && (
            <div className="analytics-bar">
              <div>Queries: <span>{analyticsData.totalQueries}</span></div>
              <div>Avg latency: <span>{analyticsData.averageLatencyMs.toFixed(1)} ms</span></div>
              <div>Zero-result rate: <span>{(analyticsData.zeroResultRate * 100).toFixed(0)}%</span></div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

export default function App() {
  const [view, setView] = useState('home');
  const [query, setQuery] = useState('');
  const [data, setData] = useState(null);
  const [statsData, setStatsData] = useState(null);

  const refreshStats = useCallback(() => {
    stats().then(setStatsData).catch(() => {});
  }, []);

  useEffect(() => {
    refreshStats();
  }, [refreshStats]);

  const onSearch = useCallback((text) => {
    if (!text || !text.trim()) return;
    setQuery(text.trim());
    setData(null);
    setView('results');
  }, []);

  const onData = useCallback((result) => {
    setData(result);
  }, []);

  const goHome = useCallback(() => {
    setView('home');
    setData(null);
    setQuery('');
    refreshStats();
  }, [refreshStats]);

  return (
    <>
      {view === 'home' ? (
        <div className="center home">
          <Logo size="home" />
          <div className="search-box">
            <SearchBox onSubmit={onSearch} autoFocus />
          </div>
          <AddUrl onAdded={refreshStats} />
          <IndexStats data={statsData} />
        </div>
      ) : (
        <ResultsPage
          key={query}
          query={query}
          onLogoClick={goHome}
          onSearch={onSearch}
          onData={onData}
          data={data}
        />
      )}
      <div className="footer">MiniGoogle &mdash; A distributed search engine built from scratch</div>
    </>
  );
}
