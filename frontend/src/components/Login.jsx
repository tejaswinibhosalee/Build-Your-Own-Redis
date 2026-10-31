import { useState } from 'react';
import { api, setToken } from '../api.js';

// Shown only when the server has a password configured.
// The password is sent once, exchanged for a random session token, and then
// forgotten - it is never stored in the browser.
export default function Login({ onLoggedIn }) {
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const { token } = await api('/api/login', { method: 'POST', body: password });
      setToken(token);
      setPassword('');
      onLoggedIn();
    } catch (e) {
      setError(e.status === 401 ? 'Wrong password, try again.' : e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login" role="dialog" aria-modal="true" aria-labelledby="login-title">
      <form className="login-card" onSubmit={submit} autoComplete="off">
        <img src="/favicon.svg" alt="" className="login-logo" width="48" height="48" />
        <h1 id="login-title">mini-redis</h1>
        <p className="muted">This server is password protected.</p>
        <label htmlFor="login-password" className="sr-only">
          Password
        </label>
        <input
          id="login-password"
          type="password"
          placeholder="Password"
          autoComplete="current-password"
          autoFocus
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Checking…' : 'Unlock dashboard'}
        </button>
        <p className="login-error" role="alert">
          {error}
        </p>
      </form>
    </div>
  );
}
