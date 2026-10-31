// Small pure helpers that turn numbers into human-friendly text.
// "Pure" = same input always gives the same output, no side effects.
// That makes them trivially easy to reason about and test.

export const fmtInt = (n) => Number(n || 0).toLocaleString('en-US');

export function fmtDuration(ms) {
  let s = Math.max(0, Math.floor(ms / 1000));
  const d = Math.floor(s / 86400);
  s %= 86400;
  const h = Math.floor(s / 3600);
  s %= 3600;
  const m = Math.floor(s / 60);
  s %= 60;
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${m}m`;
  if (m) return `${m}m ${s}s`;
  return `${s}s`;
}

export function fmtTtl(ms) {
  if (ms <= 0) return 'expired';
  const s = Math.ceil(ms / 1000);
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`;
  return fmtDuration(ms);
}

export const fmtMicros = (us) => (us >= 1000 ? `${(us / 1000).toFixed(1)}ms` : `${us}µs`);

export const fmtTime = (ms) => new Date(ms).toTimeString().slice(0, 8);

export const plural = (n, word) => `${fmtInt(n)} ${word}${n === 1 ? '' : 's'}`;
