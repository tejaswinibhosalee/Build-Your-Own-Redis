// Top bar: logo, connection status, server facts, and action buttons.
export default function Header({ session, online, theme, onToggleTheme, onLoadDemo, onLogout }) {
  const status = online === null ? ['pill-wait', 'Connecting…'] : online ? ['pill-ok', 'Online'] : ['pill-err', 'Disconnected'];

  return (
    <header className="topbar">
      <div className="brand">
        <img src="/favicon.svg" alt="" width="30" height="30" />
        <div>
          <div className="brand-name">mini-redis</div>
          <div className="brand-sub">An in-memory database built from scratch in Java</div>
        </div>
      </div>

      <div className="topbar-meta">
        <span className={`pill ${status[0]}`}>
          <span className="dot" />
          {status[1]}
        </span>
        {session && (
          <>
            <span className="pill pill-ghost mono">
              {session.bind}:{session.port}
            </span>
            <span className="pill pill-ghost">{session.aofEnabled ? 'AOF persistence on' : 'In-memory only'}</span>
            <span className="pill pill-ghost">{session.authRequired ? '🔒 Password protected' : 'Local mode (no password)'}</span>
          </>
        )}
      </div>

      <div className="topbar-actions">
        <button className="btn" type="button" onClick={onLoadDemo} title="Insert sample keys of every type, some with a TTL">
          Load demo data
        </button>
        <button className="btn btn-icon" type="button" onClick={onToggleTheme} aria-label="Toggle light/dark theme" title="Toggle theme">
          {theme === 'dark' ? '☀' : '☾'}
        </button>
        {session?.authRequired && (
          <button className="btn" type="button" onClick={onLogout}>
            Log out
          </button>
        )}
      </div>
    </header>
  );
}
