import { useEffect, useRef, useState } from 'react';
import { useNow } from '../hooks.js';
import { fmtInt, fmtTtl, plural } from '../format.js';

const FILTERS = ['all', 'string', 'list', 'hash', 'set'];

function TtlBadge({ expiresAt, now }) {
  if (expiresAt == null) return <span className="ttl" />;
  const left = expiresAt - now;
  const cls = left < 5000 ? ' urgent' : left < 15000 ? ' soon' : '';
  return <span className={`ttl${cls}`}>⏱ {fmtTtl(left)}</span>;
}

// Lists keys (filtered by a glob pattern and a type), with live TTL countdowns.
export default function KeyBrowser({ data, pattern, onPatternChange, typeFilter, onTypeFilterChange, selectedKey, onSelect }) {
  const now = useNow(250);
  const [draft, setDraft] = useState(pattern);
  const [fetchedAt, setFetchedAt] = useState(Date.now());
  const knownKeys = useRef(new Set());
  const [newKeys, setNewKeys] = useState(new Set());

  // Debounce: wait until the user stops typing for 200ms before filtering.
  useEffect(() => {
    const id = setTimeout(() => onPatternChange(draft.trim()), 200);
    return () => clearTimeout(id);
  }, [draft, onPatternChange]);

  // When fresh data arrives, remember when (for countdowns) and which keys are new (to flash them).
  useEffect(() => {
    if (!data) return;
    setFetchedAt(Date.now());
    const current = new Set(data.keys.map((k) => k.key));
    const fresh = knownKeys.current.size ? data.keys.filter((k) => !knownKeys.current.has(k.key)).map((k) => k.key) : [];
    knownKeys.current = current;
    setNewKeys(new Set(fresh));
  }, [data]);

  const rows = data?.keys ?? [];
  const countLabel = !data
    ? '…'
    : data.truncated
      ? `showing ${fmtInt(rows.length)} of ${fmtInt(data.total)}`
      : plural(data.total, 'key');

  return (
    <article className="panel">
      <header className="panel-head">
        <h2>Key browser</h2>
        <span className="muted small">{countLabel}</span>
      </header>
      <div className="search">
        <input
          type="search"
          className="mono"
          placeholder="Glob pattern, e.g. user:*"
          aria-label="Filter keys"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
      </div>
      <div className="chips" role="radiogroup" aria-label="Filter by type">
        {FILTERS.map((t) => (
          <button
            key={t}
            type="button"
            role="radio"
            aria-checked={typeFilter === t}
            className={`chip${typeFilter === t ? ' active' : ''}`}
            onClick={() => onTypeFilterChange(t)}
          >
            {t === 'all' ? 'All' : t[0].toUpperCase() + t.slice(1)}
          </button>
        ))}
      </div>
      <ul className="key-list" aria-label="Keys">
        {data && rows.length === 0 && (
          <li className="note">
            {pattern || typeFilter !== 'all'
              ? 'No keys match this filter.'
              : 'The database is empty. Click "Load demo data" or run SET in the console.'}
          </li>
        )}
        {rows.map((row) => (
          <li key={row.key} className={newKeys.has(row.key) ? 'flash' : ''}>
            <button
              type="button"
              className={row.key === selectedKey ? 'sel' : ''}
              title={row.clipped ? 'Key name too long to inspect' : row.key}
              disabled={row.clipped}
              onClick={() => onSelect(row.key)}
            >
              <span className={`badge t-${row.type}`}>{row.type}</span>
              <span className="key-name">{row.key}</span>
              <TtlBadge expiresAt={row.ttlMillis >= 0 ? fetchedAt + row.ttlMillis : null} now={now} />
            </button>
          </li>
        ))}
      </ul>
    </article>
  );
}
