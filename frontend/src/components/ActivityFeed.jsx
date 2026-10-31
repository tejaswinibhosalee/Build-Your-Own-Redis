import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { fmtMicros, fmtTime } from '../format.js';

const MAX_ROWS = 120;

// A live feed of every command the server runs - from redis-cli (TCP) or this dashboard (WEB).
// It asks only for entries newer than the last one it has ("after=<id>"), so each poll is tiny.
export default function ActivityFeed({ enabled, refreshTick }) {
  const [rows, setRows] = useState([]);
  const lastId = useRef(0);
  const busy = useRef(false);

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;
    const poll = async () => {
      if (busy.current || document.hidden) return;
      busy.current = true;
      try {
        const fresh = await api(`/api/activity?after=${lastId.current}`);
        if (!cancelled && fresh.length) {
          lastId.current = fresh[fresh.length - 1].id;
          // Newest first; keep the list bounded so the page never slows down.
          setRows((prev) => [...fresh.reverse(), ...prev].slice(0, MAX_ROWS));
        }
      } catch {
        // connection state is shown in the header
      } finally {
        busy.current = false;
      }
    };
    poll();
    const id = setInterval(poll, 1000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [enabled, refreshTick]);

  return (
    <article className="panel">
      <header className="panel-head">
        <h2>Live activity</h2>
        <span className="muted small">
          <span className="badge badge-tcp">TCP</span> client &nbsp;<span className="badge badge-web">WEB</span> dashboard
        </span>
      </header>
      <ul className="activity mono" aria-live="off">
        {rows.length === 0 && <li className="note">Waiting for commands… run one here or from redis-cli.</li>}
        {rows.map((r) => (
          <li key={r.id}>
            <span className="time">{fmtTime(r.time)}</span>
            {r.origin === 'DASHBOARD' ? <span className="badge badge-web">WEB</span> : <span className="badge badge-tcp">TCP</span>}
            <span className="args" title={`${r.command} ${r.args}`}>
              <span className={`cmd${r.error ? ' err' : ''}`}>{r.command}</span> {r.args}
            </span>
            <span className="lat">{fmtMicros(r.micros)}</span>
          </li>
        ))}
      </ul>
    </article>
  );
}
