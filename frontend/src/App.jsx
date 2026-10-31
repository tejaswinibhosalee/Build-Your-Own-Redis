import { useCallback, useEffect, useState } from 'react';
import { api, setApiListeners, setToken } from './api.js';
import { usePolling } from './hooks.js';
import Header from './components/Header.jsx';
import Login from './components/Login.jsx';
import StatCards from './components/StatCards.jsx';
import Console from './components/Console.jsx';
import ThroughputChart from './components/ThroughputChart.jsx';
import Keyspace from './components/Keyspace.jsx';
import KeyBrowser from './components/KeyBrowser.jsx';
import Inspector from './components/Inspector.jsx';
import ActivityFeed from './components/ActivityFeed.jsx';
import Toast from './components/Toast.jsx';

const THEME_KEY = 'mini-redis-theme';

function readTheme() {
  try {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === 'light' || saved === 'dark') return saved;
  } catch {
    // storage unavailable
  }
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

/**
 * The page. App owns the state that several components share (the session,
 * which key is selected, the key filters, the theme) and passes it down as
 * props - "lifting state up".
 */
export default function App() {
  const [session, setSession] = useState(null);
  const [needsLogin, setNeedsLogin] = useState(false);
  const [online, setOnline] = useState(null);
  const [theme, setTheme] = useState(readTheme);
  const [selectedKey, setSelectedKey] = useState(null);
  const [pattern, setPattern] = useState('');
  const [typeFilter, setTypeFilter] = useState('all');
  const [refreshTick, setRefreshTick] = useState(0);
  const [toast, setToast] = useState(null);

  // Bumping this number makes every poller fetch again immediately.
  const refreshNow = useCallback(() => setRefreshTick((t) => t + 1), []);

  // --- session / login ---------------------------------------------------
  const loadSession = useCallback(async () => {
    try {
      const s = await api('/api/session');
      if (s.authRequired && !s.authenticated) {
        setNeedsLogin(true);
        setSession(null);
      } else {
        setNeedsLogin(false);
        setSession(s);
      }
    } catch {
      setTimeout(loadSession, 2000); // server not up yet - retry
    }
  }, []);

  useEffect(() => {
    setApiListeners({
      unauthorized: () => {
        setSession(null);
        setNeedsLogin(true);
      },
      connection: setOnline,
    });
    loadSession();
  }, [loadSession]);

  const logout = async () => {
    try {
      await api('/api/logout', { method: 'POST' });
    } catch {
      // ignore - we're logging out anyway
    }
    setToken(null);
    setSession(null);
    setNeedsLogin(true);
  };

  // --- theme ---------------------------------------------------------------
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    try {
      localStorage.setItem(THEME_KEY, next);
    } catch {
      // storage unavailable: theme just won't be remembered
    }
    setTheme(next);
  };

  // --- live data -------------------------------------------------------------
  const ready = session !== null;
  const { data: stats } = usePolling(() => api('/api/stats'), 1000, { enabled: ready, trigger: refreshTick });
  const { data: keys } = usePolling(
    () => api(`/api/keys?${new URLSearchParams({ pattern: pattern || '*', type: typeFilter })}`),
    2000,
    { enabled: ready, trigger: `${refreshTick}|${pattern}|${typeFilter}` },
  );

  const showToast = useCallback((message) => {
    setToast({ message, id: Date.now() });
  }, []);

  const loadDemoData = async () => {
    try {
      const r = await api('/api/demo-data', { method: 'POST' });
      showToast(`Loaded demo data (${r.executed} commands)`);
      refreshNow();
    } catch (e) {
      showToast(`Could not load demo data: ${e.message}`);
    }
  };

  if (needsLogin) {
    return <Login onLoggedIn={loadSession} />;
  }

  return (
    <>
      <Header
        session={session}
        online={online}
        theme={theme}
        onToggleTheme={toggleTheme}
        onLoadDemo={loadDemoData}
        onLogout={logout}
      />
      <main className="layout">
        <StatCards stats={stats} version={session?.version} />

        <section className="row row-console">
          {session ? <Console session={session} onCommandRan={refreshNow} /> : <div className="panel console-panel" />}
          <div className="stack">
            <ThroughputChart history={stats?.opsHistory} theme={theme} />
            <Keyspace stats={stats} />
          </div>
        </section>

        <section className="row row-three">
          <KeyBrowser
            data={keys}
            pattern={pattern}
            onPatternChange={setPattern}
            typeFilter={typeFilter}
            onTypeFilterChange={setTypeFilter}
            selectedKey={selectedKey}
            onSelect={setSelectedKey}
          />
          <Inspector keyName={selectedKey} refreshTick={refreshTick} onChanged={refreshNow} onToast={showToast} />
          <ActivityFeed enabled={ready} refreshTick={refreshTick} />
        </section>
      </main>
      <footer className="footer">
        mini-redis · RESP protocol, TCP server, TTL expiry &amp; AOF persistence, written from scratch in Java 21 ·
        dashboard in React
      </footer>
      <Toast toast={toast} />
    </>
  );
}
