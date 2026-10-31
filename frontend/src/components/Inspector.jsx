import { useEffect, useState } from 'react';
import { api, quoteArg } from '../api.js';
import { useNow, usePolling } from '../hooks.js';
import { fmtInt, fmtTtl } from '../format.js';

const UNITS = { string: 'char', list: 'element', hash: 'field', set: 'member' };

function Value({ d }) {
  if (d.type === 'string') return <pre className="insp-value">{d.value}</pre>;
  if (d.type === 'set') {
    return (
      <div className="members">
        {d.value.map((m) => (
          <span key={m} className="member">
            {m}
          </span>
        ))}
      </div>
    );
  }
  const isList = d.type === 'list';
  const rows = isList ? d.value.map((v, i) => [String(i), v]) : d.value.map((f) => [f.field, f.value]);
  return (
    <table className="insp-table">
      <thead>
        <tr>
          <th>{isList ? '#' : 'field'}</th>
          <th>{isList ? 'element' : 'value'}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(([a, b], i) => (
          <tr key={i}>
            <td className={isList ? 'idx' : ''}>{a}</td>
            <td>{b}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// Shows the selected key's type, TTL and full value, with Persist / Delete actions.
export default function Inspector({ keyName, refreshTick, onChanged, onToast }) {
  const now = useNow(250);
  const [fetchedAt, setFetchedAt] = useState(0);

  const { data, error } = usePolling(() => api(`/api/key?${new URLSearchParams({ name: keyName })}`), 2000, {
    enabled: keyName !== null,
    trigger: `${keyName}|${refreshTick}`,
  });

  useEffect(() => {
    if (data) setFetchedAt(Date.now());
  }, [data]);

  const run = async (command, message) => {
    try {
      await api('/api/command', { method: 'POST', body: command });
      onToast(message);
      onChanged();
    } catch (e) {
      onToast(e.message);
    }
  };

  let body;
  let actions = null;
  if (keyName === null) {
    body = (
      <div className="empty">
        <div className="empty-icon">⌕</div>
        <p>Select a key to see its value, type and TTL.</p>
      </div>
    );
  } else if (error?.status === 404) {
    body = (
      <div className="empty">
        <div className="empty-icon">∅</div>
        <p>
          <span className="mono">{keyName}</span> no longer exists. It was deleted or its TTL ran out.
        </p>
      </div>
    );
  } else if (!data || data.key !== keyName) {
    body = <p className="muted">Loading…</p>;
  } else {
    const ttlLeft = data.ttlMillis >= 0 ? data.ttlMillis - (now - fetchedAt) : null;
    body = (
      <>
        <h3 className="insp-key">{data.key}</h3>
        <div className="insp-meta">
          <span className={`badge t-${data.type}`}>{data.type}</span>
          <span className="pill pill-ghost">
            {fmtInt(data.size)} {UNITS[data.type]}
            {data.size === 1 ? '' : 's'}
          </span>
          <span className="pill pill-ghost">{ttlLeft === null ? 'No expiry' : `TTL ${fmtTtl(ttlLeft)}`}</span>
        </div>
        <Value d={data} />
        {data.truncated && <p className="trunc-note">Large value: only the first part is shown.</p>}
      </>
    );
    actions = (
      <>
        {data.ttlMillis >= 0 && (
          <button
            className="btn btn-small"
            type="button"
            title="Remove the TTL (PERSIST)"
            onClick={() => run(`PERSIST ${quoteArg(data.key)}`, 'TTL removed: the key will live forever')}
          >
            Persist
          </button>
        )}
        <button
          className="btn btn-small btn-danger"
          type="button"
          onClick={() => window.confirm(`Delete key "${data.key}"?`) && run(`DEL ${quoteArg(data.key)}`, 'Key deleted')}
        >
          Delete
        </button>
      </>
    );
  }

  return (
    <article className="panel">
      <header className="panel-head">
        <h2>Inspector</h2>
        <div className="panel-tools">{actions}</div>
      </header>
      <div className="inspector">{body}</div>
    </article>
  );
}
