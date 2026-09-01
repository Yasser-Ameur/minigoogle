import React, { forwardRef, useCallback, useEffect, useId, useImperativeHandle, useRef, useState } from 'react';
import { suggest } from '../api';
import './SearchBox.css';

const MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 150;

function sortSuggestions(items, query) {
  const q = query.toLowerCase();
  return [...items].sort((a, b) => {
    const al = a.toLowerCase();
    const bl = b.toLowerCase();
    const aExact = al === q ? 1 : 0;
    const bExact = bl === q ? 1 : 0;
    if (aExact !== bExact) return bExact - aExact;
    const aStarts = al.startsWith(q) ? 1 : 0;
    const bStarts = bl.startsWith(q) ? 1 : 0;
    if (aStarts !== bStarts) return bStarts - aStarts;
    const aRatio = q.length / a.length;
    const bRatio = q.length / b.length;
    if (aRatio !== bRatio) return bRatio - aRatio;
    return a.localeCompare(b);
  });
}

const SearchBox = forwardRef(function SearchBox(
  { onSubmit, initialQuery = '', autoFocus = false, size = 'lg' },
  ref
) {
  const [value, setValue] = useState(initialQuery);
  const [suggestions, setSuggestions] = useState([]);
  const [acIndex, setAcIndex] = useState(-1);
  const debounceRef = useRef(null);
  const requestSeqRef = useRef(0);
  const inputRef = useRef(null);
  const listboxId = useId();

  useImperativeHandle(ref, () => ({
    focus: () => inputRef.current && inputRef.current.focus(),
  }));

  useEffect(() => {
    setValue(initialQuery);
  }, [initialQuery]);

  const fetchSuggestions = useCallback((q) => {
    clearTimeout(debounceRef.current);
    const seq = ++requestSeqRef.current;
    if (q.length < MIN_QUERY_LENGTH) {
      setSuggestions([]);
      setAcIndex(-1);
      return;
    }
    debounceRef.current = setTimeout(async () => {
      try {
        const items = await suggest(q);
        if (seq !== requestSeqRef.current) return;
        setSuggestions(sortSuggestions(items, q));
        setAcIndex(-1);
      } catch {
        if (seq === requestSeqRef.current) setSuggestions([]);
      }
    }, DEBOUNCE_MS);
  }, []);

  const submit = (text) => {
    setSuggestions([]);
    setAcIndex(-1);
    onSubmit(text);
  };

  const onKeyDown = (e) => {
    if (e.key === 'Escape') {
      if (suggestions.length > 0) {
        setSuggestions([]);
        setAcIndex(-1);
      } else {
        inputRef.current && inputRef.current.blur();
      }
      return;
    }
    if (suggestions.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setAcIndex((i) => Math.min(i + 1, suggestions.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setAcIndex((i) => Math.max(i - 1, -1));
    } else if (e.key === 'Enter' && acIndex >= 0) {
      e.preventDefault();
      submit(suggestions[acIndex]);
    }
  };

  const activeId = acIndex >= 0 ? `${listboxId}-opt-${acIndex}` : undefined;

  return (
    <div
      className={'search-wrap search-wrap--' + size}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget)) {
          setSuggestions([]);
          setAcIndex(-1);
        }
      }}
    >
      <label htmlFor="search-input" className="visually-hidden">Search MiniGoogle</label>
      <form
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          if (value.trim()) submit(value.trim());
        }}
      >
        <input
          ref={inputRef}
          id="search-input"
          className="search-input"
          type="text"
          role="combobox"
          aria-expanded={suggestions.length > 0}
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-activedescendant={activeId}
          value={value}
          autoFocus={autoFocus}
          autoComplete="off"
          spellCheck="false"
          placeholder="Search MiniGoogle…"
          onChange={(e) => {
            setValue(e.target.value);
            fetchSuggestions(e.target.value.trim());
          }}
          onKeyDown={onKeyDown}
        />
        <button className="search-btn" type="submit" aria-label="Search">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />
          </svg>
        </button>
      </form>
      {suggestions.length > 0 && (
        <ul className="autocomplete" id={listboxId} role="listbox" aria-label="Suggestions">
          {suggestions.map((item, i) => (
            <li
              key={item}
              id={`${listboxId}-opt-${i}`}
              role="option"
              aria-selected={i === acIndex}
              className={'ac-item' + (i === acIndex ? ' active' : '')}
              onMouseDown={(e) => {
                e.preventDefault();
                submit(item);
              }}
              onMouseEnter={() => setAcIndex(i)}
            >
              <svg className="ac-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />
              </svg>
              <span>{item}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
});

export default SearchBox;
