import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { fmtMicros } from '../format.js';
import RespView from './RespView.jsx';

const MAX_ENTRIES = 200;

// One-click examples - handy for demos.
const EXAMPLES = [
  'PING',
  'SET greeting "Hello, world!"',
  'GET greeting',
  'INCR visits',
  'RPUSH tasks design build ship',
  'LRANGE tasks 0 -1',
  'HSET user:42 name Sam role engineer',
  'HGETALL user:42',
  'SADD skills java redis networking',
  'SET temp "self-destructs" EX 15',
  'TTL temp',
  'KEYS *',
];

/**
 * Turns a reply into lines formatted exactly like redis-cli prints them:
 *   OK / (integer) 3 / "hello" / (nil) / (error) ERR ... / 1) "a" 2) "b"
 */
function formatReply(reply, indent = '') {
  const line = (cls, text) => ({ cls, text: indent + text });
  switch (reply.type) {
    case 'simple':
      return [line('c-ok', reply.value)];
    case 'error':
      return [line('c-err', `(error) ${reply.value}`)];
    case 'integer':
      return [line('c-int', `(integer) ${reply.value}`)];
    case 'nil':
      return [line('c-nil', '(nil)')];
    case 'bulk':
      return [line('c-str', JSON.stringify(reply.value))];
    case 'array': {
      if (!reply.value.length) return [line('c-nil', '(empty array)')];
      const width = String(reply.value.length).length;
      const out = [];
      reply.value.forEach((item, i) => {
        const label = `${String(i + 1).padStart(width)}) `;
        const inner = formatReply(item, ' '.repeat(indent.length + label.length));
        inner[0] = { ...inner[0], text: indent + label + inner[0].text.slice(indent.length + label.length) };
        out.push(...inner);
      });
      if (reply.truncated) out.push(line('c-hint', '… (truncated)'));
      return out;
    }
    default:
      return [line('', String(reply.value))];
  }
}

export default function Console({ session, onCommandRan }) {
  const [entries, setEntries] = useState([
    {
      id: 0,
      hint: [
        `Connected to mini-redis v${session.version}. This console talks to the same engine as redis-cli.`,
        'Try an example below, use ↑/↓ for history, Tab to autocomplete, or type "help".',
      ],
    },
  ]);
  const [line, setLine] = useState('');
  const [showResp, setShowResp] = useState(true);
  const [suggestIndex, setSuggestIndex] = useState(-1);
  const history = useRef([]);
  const historyIndex = useRef(0);
  const nextId = useRef(1);
  const outputRef = useRef(null);

  // Auto-scroll to the newest output.
  useEffect(() => {
    const out = outputRef.current;
    if (out) out.scrollTop = out.scrollHeight;
  }, [entries]);

  const addEntry = (entry) => {
    const id = nextId.current++;
    setEntries((prev) => [...prev, { ...entry, id }].slice(-MAX_ENTRIES));
    return id;
  };
  const updateEntry = (id, patch) => setEntries((prev) => prev.map((e) => (e.id === id ? { ...e, ...patch } : e)));

  const run = async (raw) => {
    const command = raw.trim();
    if (!command) return;
    if (history.current[history.current.length - 1] !== command) history.current.push(command);
    historyIndex.current = history.current.length;

    // Console-only helpers (never sent to the server).
    if (command.toLowerCase() === 'clear') {
      setEntries([]);
      return;
    }
    if (command.toLowerCase() === 'help') {
      addEntry({
        hint: ['Supported commands:', session.commands.join('  '), 'Console-only: help, clear. Quote values with spaces: SET k "hello world"'],
      });
      return;
    }

    const id = addEntry({ command, pending: true });
    try {
      const res = await api('/api/command', { method: 'POST', body: command });
      updateEntry(id, { pending: false, result: res });
    } catch (e) {
      updateEntry(id, { pending: false, failure: e.message });
    }
    onCommandRan();
  };

  // Autocomplete only while typing the first word.
  const suggestions =
    line && !/\s/.test(line)
      ? session.commands.filter((c) => c.startsWith(line.toUpperCase()) && c !== line.toUpperCase()).slice(0, 8)
      : [];

  const accept = (cmd) => {
    setLine(`${cmd} `);
    setSuggestIndex(-1);
  };

  const onKeyDown = (e) => {
    const open = suggestions.length > 0;
    if (e.key === 'Tab' && open) {
      e.preventDefault();
      accept(suggestions[Math.max(0, suggestIndex)]);
    } else if (e.key === 'ArrowDown' && open) {
      e.preventDefault();
      setSuggestIndex((i) => (i + 1) % suggestions.length);
    } else if (e.key === 'ArrowUp' && open && suggestIndex >= 0) {
      e.preventDefault();
      setSuggestIndex((i) => i - 1);
    } else if (e.key === 'Enter' && open && suggestIndex >= 0) {
      e.preventDefault();
      accept(suggestions[suggestIndex]);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (historyIndex.current > 0) historyIndex.current -= 1;
      setLine(history.current[historyIndex.current] ?? line);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (historyIndex.current < history.current.length) historyIndex.current += 1;
      setLine(history.current[historyIndex.current] ?? '');
    } else if (e.key === 'l' && e.ctrlKey) {
      e.preventDefault();
      setEntries([]);
    }
  };

  const submit = (e) => {
    e.preventDefault();
    const command = line;
    setLine('');
    setSuggestIndex(-1);
    run(command);
  };

  const prompt = `${session.bind}:${session.port}>`;

  return (
    <article className="panel console-panel">
      <header className="panel-head">
        <h2>Console</h2>
        <div className="panel-tools">
          <label className="toggle" title="Show the raw bytes sent over the wire">
            <input type="checkbox" checked={showResp} onChange={(e) => setShowResp(e.target.checked)} />
            <span className="toggle-track">
              <span className="toggle-thumb" />
            </span>
            <span>Show RESP protocol</span>
          </label>
          <button className="btn btn-small" type="button" onClick={() => setEntries([])}>
            Clear
          </button>
        </div>
      </header>

      <div ref={outputRef} className={`console-output mono${showResp ? '' : ' hide-resp'}`} role="log" aria-live="polite">
        {entries.map((entry) => (
          <div key={entry.id} className="c-entry">
            {entry.hint?.map((text) => (
              <div key={text} className="line c-hint">
                {text}
              </div>
            ))}
            {entry.command && (
              <div className="line c-cmd">
                <span className="prompt-ghost">› </span>
                {entry.command}
                {entry.result && <span className="c-meta">{fmtMicros(entry.result.micros)}</span>}
              </div>
            )}
            {entry.pending && <div className="line c-hint">…</div>}
            {entry.failure && <div className="line c-err">(dashboard) {entry.failure}</div>}
            {entry.result && (
              <>
                <div className="c-reply">
                  {formatReply(entry.result.reply).map((l, i) => (
                    <div key={i} className={`line ${l.cls}`}>
                      {l.text}
                    </div>
                  ))}
                </div>
                {entry.result.requestResp && (
                  <div className="wire">
                    <RespView title="Request → server" resp={entry.result.requestResp} />
                    <RespView title="Reply ← server" resp={entry.result.replyResp} />
                  </div>
                )}
              </>
            )}
          </div>
        ))}
      </div>

      <div className="quick" aria-label="Example commands">
        {EXAMPLES.map((cmd) => (
          <button key={cmd} type="button" className="chip" onClick={() => run(cmd)}>
            {cmd}
          </button>
        ))}
      </div>

      <form className="console-input mono" onSubmit={submit} autoComplete="off">
        <span className="prompt">{prompt}</span>
        <input
          type="text"
          spellCheck="false"
          autoCapitalize="off"
          aria-label="Command"
          placeholder='Type a command, e.g. SET greeting "hello world"'
          value={line}
          onChange={(e) => {
            setLine(e.target.value);
            setSuggestIndex(-1);
          }}
          onKeyDown={onKeyDown}
        />
        {suggestions.length > 0 && (
          <div className="suggest" role="listbox">
            {suggestions.map((cmd, i) => (
              <button
                key={cmd}
                type="button"
                role="option"
                aria-selected={i === suggestIndex}
                className={i === suggestIndex ? 'sel' : ''}
                onMouseDown={(e) => {
                  e.preventDefault();
                  accept(cmd);
                }}
              >
                {cmd}
              </button>
            ))}
          </div>
        )}
      </form>
    </article>
  );
}
