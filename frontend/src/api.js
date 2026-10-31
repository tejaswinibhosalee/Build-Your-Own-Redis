// The ONLY place in the frontend that talks to the server.
//
// Keeping every request in one function means the security bits can't be
// forgotten on some endpoint:
//   * the session token (if logged in) goes in the Authorization header
//   * every POST carries the anti-CSRF header the server insists on
//   * cookies are never sent (credentials: 'omit')

const TOKEN_KEY = 'mini-redis-token';

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function storage(fn, fallback = null) {
  // sessionStorage can throw in private mode / with storage disabled.
  try {
    return fn();
  } catch {
    return fallback;
  }
}

// sessionStorage (not localStorage): the token disappears when the tab closes.
let token = storage(() => sessionStorage.getItem(TOKEN_KEY));
let onUnauthorized = () => {};
let onConnectionChange = () => {};

export function setToken(newToken) {
  token = newToken;
  storage(() => (newToken ? sessionStorage.setItem(TOKEN_KEY, newToken) : sessionStorage.removeItem(TOKEN_KEY)));
}

/** Registers callbacks the App uses to show the login screen / connection pill. */
export function setApiListeners({ unauthorized, connection }) {
  onUnauthorized = unauthorized;
  onConnectionChange = connection;
}

export async function api(path, { method = 'GET', body } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (method !== 'GET') {
    headers['X-Requested-With'] = 'mini-redis-dashboard'; // anti-CSRF marker
    headers['Content-Type'] = 'text/plain; charset=utf-8';
  }

  let res;
  try {
    res = await fetch(path, { method, headers, body, cache: 'no-store', credentials: 'omit' });
  } catch {
    onConnectionChange(false);
    throw new ApiError(0, 'Server unreachable');
  }
  onConnectionChange(true);

  let data = null;
  try {
    data = await res.json();
  } catch {
    // empty or non-JSON body
  }
  if (res.status === 401 && path !== '/api/login') {
    setToken(null);
    onUnauthorized();
  }
  if (!res.ok) throw new ApiError(res.status, data?.error || res.statusText);
  return data;
}

/** Quotes an argument so the server's redis-cli style parser reads it back exactly. */
export function quoteArg(s) {
  if (s !== '' && /^[^\s"'\\]+$/.test(s)) return s;
  return `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\r/g, '\\r').replace(/\t/g, '\\t')}"`;
}
