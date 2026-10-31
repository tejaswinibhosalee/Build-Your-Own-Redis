# Security Policy

mini-redis is an educational project. It is hardened against common attacks
(see below), but it is **not** intended to store sensitive production data.

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Instead, use
GitHub's private reporting: go to the repository's **Security** tab and click
**Report a vulnerability**. You can expect an acknowledgement within a few days.

## Security design at a glance

| Area | Protection |
|------|------------|
| Network exposure | Listens on `127.0.0.1` by default; **protected mode** refuses remote clients when no password is set |
| Authentication | Optional password via `MINI_REDIS_PASSWORD` or `--password-file` (never a CLI flag); constant-time comparison of SHA-256 hashes; failed-login throttling |
| Secrets in logs | `AUTH` is handled per-connection and never reaches the AOF file or the activity feed |
| Protocol DoS | Hard limits on argument count, bulk-string size and inline-command length |
| ReDoS | `KEYS` glob matching uses a linear-time matcher, not regular expressions |
| Resource exhaustion | `--max-clients` connection cap; bounded metric and activity buffers |
| Dashboard - XSS | React escapes all output; strict `Content-Security-Policy` (`script-src 'self'`, no inline scripts) |
| Dashboard - CSRF | Custom request header + `Origin` check on every POST; bearer tokens instead of cookies |
| Dashboard - DNS rebinding | `Host` header allow-list when bound to loopback |
| Dashboard - clickjacking | `X-Frame-Options: DENY`, `frame-ancestors 'none'` |
| Dashboard - exposure | Refuses to bind to a non-loopback address without a password |
| Sessions | 256-bit `SecureRandom` tokens, idle expiry, capped session count, stored in `sessionStorage` |
| Containers | Runs as a non-root user; compose file drops all capabilities and publishes ports on `127.0.0.1` only |
| Supply chain | Zero runtime Java dependencies; pinned npm versions (`npm ci`), `npm audit` in CI, Dependabot updates |

## Not in scope

There is no TLS: put mini-redis behind a TLS-terminating proxy, or keep it on
a trusted network, if traffic leaves the machine.
