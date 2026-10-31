import { useEffect, useRef } from 'react';
import { fmtInt } from '../format.js';

/** Rounds the y-axis maximum up to a "nice" number (5, 10, 20, 25, 50, 100, ...). */
function niceMax(v) {
  if (v <= 5) return 5;
  const pow = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * pow >= v) return m * pow;
  return 10 * pow;
}

function draw(canvas, data) {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  if (!w || !h) return;
  // Match the canvas's internal pixels to the screen (sharp on retina displays).
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);

  // Read colors from CSS variables so the chart follows the theme.
  const css = getComputedStyle(document.documentElement);
  const accent = css.getPropertyValue('--accent').trim();
  const grid = css.getPropertyValue('--border').trim();
  const muted = css.getPropertyValue('--muted').trim();

  const pad = { l: 40, r: 10, t: 10, b: 20 };
  const cw = w - pad.l - pad.r;
  const ch = h - pad.t - pad.b;
  const max = niceMax(Math.max(...data));
  const x = (i) => pad.l + (cw * i) / (data.length - 1);
  const y = (v) => pad.t + ch - (ch * v) / max;

  // Grid lines + y labels
  ctx.font = '11px ui-monospace, Menlo, Consolas, monospace';
  ctx.fillStyle = muted;
  ctx.strokeStyle = grid;
  ctx.lineWidth = 1;
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (let i = 0; i <= 4; i++) {
    const gy = Math.round(pad.t + ch - (ch * i) / 4) + 0.5;
    ctx.beginPath();
    ctx.moveTo(pad.l, gy);
    ctx.lineTo(w - pad.r, gy);
    ctx.stroke();
    ctx.fillText(fmtInt((max * i) / 4), pad.l - 6, gy);
  }
  // X labels
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  [['-60s', 0], ['-30s', 0.5], ['now', 1]].forEach(([label, f]) => ctx.fillText(label, pad.l + cw * f, h - pad.b + 5));

  // Filled area under the line
  const trace = () => data.forEach((v, i) => (i ? ctx.lineTo(x(i), y(v)) : ctx.moveTo(x(i), y(v))));
  ctx.beginPath();
  trace();
  ctx.lineTo(x(data.length - 1), pad.t + ch);
  ctx.lineTo(x(0), pad.t + ch);
  ctx.closePath();
  const gradient = ctx.createLinearGradient(0, pad.t, 0, pad.t + ch);
  gradient.addColorStop(0, accent);
  gradient.addColorStop(1, 'transparent');
  ctx.globalAlpha = 0.25;
  ctx.fillStyle = gradient;
  ctx.fill();
  ctx.globalAlpha = 1;

  // The line itself, plus a dot on the latest value
  ctx.beginPath();
  trace();
  ctx.strokeStyle = accent;
  ctx.lineWidth = 2;
  ctx.lineJoin = 'round';
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(x(data.length - 1), y(data[data.length - 1]), 3.5, 0, Math.PI * 2);
  ctx.fillStyle = accent;
  ctx.fill();
}

// A hand-drawn line chart. Canvas drawing is "imperative" (step-by-step
// instructions), so we grab the <canvas> element with useRef and redraw it
// in useEffect whenever the data or theme changes.
export default function ThroughputChart({ history, theme }) {
  const canvasRef = useRef(null);
  const data = history ?? new Array(60).fill(0);

  useEffect(() => {
    const canvas = canvasRef.current;
    draw(canvas, data);
    const onResize = () => draw(canvas, data);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [data, theme]);

  return (
    <article className="panel">
      <header className="panel-head">
        <h2>Throughput</h2>
        <span className="muted small">commands / second · last 60s</span>
      </header>
      <div className="chart-wrap">
        <canvas ref={canvasRef} role="img" aria-label="Commands per second over the last 60 seconds" />
      </div>
    </article>
  );
}
