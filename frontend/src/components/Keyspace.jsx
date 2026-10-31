import { fmtInt, plural } from '../format.js';

const TYPES = ['string', 'list', 'hash', 'set'];

// Keys broken down by data type (stacked bar) + the most-used commands.
export default function Keyspace({ stats }) {
  const byType = stats?.keysByType ?? {};
  const total = stats?.keys ?? 0;
  const top = stats?.topCommands ?? [];
  const maxCount = top[0]?.count ?? 1;

  return (
    <article className="panel">
      <header className="panel-head">
        <h2>Keyspace</h2>
        <span className="muted small">{plural(total, 'key')}</span>
      </header>

      <div className="typebar">
        {TYPES.filter((t) => total > 0 && byType[t] > 0).map((t) => (
          <span key={t} className={`seg-${t}`} title={`${t}: ${byType[t]}`} style={{ width: `${(100 * byType[t]) / total}%` }} />
        ))}
      </div>
      <ul className="legend">
        {TYPES.map((t) => (
          <li key={t}>
            <i className={`seg-${t}`} />
            {t} <b>{fmtInt(byType[t] ?? 0)}</b>
          </li>
        ))}
      </ul>

      <h3 className="subhead">Top commands</h3>
      <ul className="bars">
        {top.length === 0 && <li className="none">No commands yet. Run one in the console!</li>}
        {top.map(({ name, count }) => (
          <li key={name}>
            <span className="name" title={name}>
              {name}
            </span>
            <div className="track">
              <div className="fill" style={{ width: `${Math.max(3, (100 * count) / maxCount)}%` }} />
            </div>
            <span className="num">{fmtInt(count)}</span>
          </li>
        ))}
      </ul>
    </article>
  );
}
