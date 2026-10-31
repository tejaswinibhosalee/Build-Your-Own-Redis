import { fmtDuration, fmtInt, plural } from '../format.js';

// One reusable card. `children` lets a caller put anything under the number (like the heap meter).
function StatCard({ label, value, foot, accent = false, children }) {
  return (
    <article className={`stat${accent ? ' stat-accent' : ''}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {foot && <div className="stat-foot">{foot}</div>}
      {children}
    </article>
  );
}

export default function StatCards({ stats, version }) {
  const s = stats ?? {};
  const history = s.opsHistory ?? [0];
  const heapPercent = s.heapMaxBytes ? Math.min(100, (100 * s.heapUsedBytes) / s.heapMaxBytes) : 0;

  return (
    <section className="stats" aria-label="Server statistics">
      <StatCard label="Keys" value={fmtInt(s.keys)} foot={s.keys === 1 ? 'key in the keyspace' : 'keys in the keyspace'} />
      <StatCard label="Ops / sec" value={fmtInt(s.opsPerSecond)} foot={`peak ${fmtInt(Math.max(...history))} in last 60s`} accent />
      <StatCard label="Commands processed" value={fmtInt(s.totalCommands)} foot={plural(s.totalErrors ?? 0, 'error')} />
      <StatCard
        label="Connected clients"
        value={fmtInt(s.connectedClients)}
        foot={`${fmtInt(s.totalConnections)} total connections`}
      />
      <StatCard label="JVM heap" value={`${((s.heapUsedBytes ?? 0) / 1048576).toFixed(0)} MB`}>
        <div className="meter">
          <div className="meter-fill" style={{ width: `${heapPercent.toFixed(1)}%` }} />
        </div>
      </StatCard>
      <StatCard label="Uptime" value={fmtDuration(s.uptimeMillis ?? 0)} foot={version ? `v${version}` : ''} />
    </section>
  );
}
