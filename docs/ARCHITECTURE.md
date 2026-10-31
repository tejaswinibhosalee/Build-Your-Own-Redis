# Architecture

```
                          ┌─────────────────────────────────────────┐
                          │              MiniRedisServer              │
                          │   ServerSocket.accept() loop (blocking)   │
                          └───────────────────┬───────────────────────┘
                                               │ one virtual thread per
                                               │ accepted connection
                    ┌──────────────────────────┼──────────────────────────┐
                    ▼                          ▼                          ▼
             ┌─────────────┐            ┌─────────────┐            ┌─────────────┐
             │ClientHandler│            │ClientHandler│            │ClientHandler│
             │  (client A) │            │  (client B) │            │  (client C) │
             └──────┬──────┘            └──────┬──────┘            └──────┬──────┘
                    │ RespReader.readCommand()  │                          │
                    ▼                           ▼                          ▼
             ┌──────────────────────────────────────────────────────────────────┐
             │                    CommandExecutor.execute(args)                  │
             │        acquires ONE global lock before running any command       │
             │        (this is what makes INCR, SADD, etc. safe under           │
             │         concurrent clients - see "Concurrency model" below)      │
             └───────────────────────────┬──────────────────────────────────────┘
                                          ▼
                          ┌───────────────────────────────┐        ┌───────────────┐
                          │            Database             │──────▶│   AofWriter   │
                          │  Map<String,Object> + TTL map    │       │ (every write  │
                          │  String / Deque / Map / Set      │       │  -> RESP line │
                          └───────────────────┬───────────────┘       │  on disk)    │
                                          ▲    │                       └───────────────┘
                                          │    ▼
                               ┌──────────────────────┐
                               │   ExpiryScheduler     │   background thread, sweeps
                               │ (every 100ms)         │   past-TTL keys proactively
                               └──────────────────────┘
```

## Request lifecycle

1. `MiniRedisServer` blocks on `ServerSocket.accept()`. Each accepted
   connection is handed to a new **virtual thread** running a
   `ClientHandler`.
2. `ClientHandler` loops: read one command with `RespReader`, run it
   through `CommandExecutor`, write the `Reply` back with `RespWriter`,
   flush, repeat until the socket closes.
3. `CommandExecutor` looks the command name up in a registry
   (`command/impl/*.java`, grouped by data type) and calls its handler
   while holding a single `ReentrantLock`.
4. The handler reads/writes the `Database`. If it's a write command and
   it succeeded, `CommandExecutor` appends the command to the `AofWriter`
   log for durability.
5. A background `ExpiryScheduler` sweeps the keyspace for expired keys
   every 100ms, taking the *same* lock as command execution.

## Concurrency model

Real Redis runs on a single thread and processes one command completely
before starting the next - that's *why* `INCR` (read, add one, write back)
never races with another client's `INCR` even without any explicit
locking in Redis's own command code.

This project reaches the same guarantee a different way: each connection
gets a full, ordinary blocking-I/O thread (made cheap by Java 21 virtual
threads, so thousands of idle connections cost little), but every command
execution is serialized behind one lock in `CommandExecutor`. The result
is the same external behavior - one command completes before the next
one starts - achieved with plain locking instead of a hand-rolled event
loop. It is easier to read and reason about, at the cost of one lock
becoming a bottleneck under very high write concurrency (a real
production engine would shard that lock per key-range, or go
single-threaded with non-blocking I/O like Redis itself).

## Persistence model (AOF)

- **Append-only log**: every successful write command is RESP-encoded and
  appended to a file, flushed immediately. On restart, the file is
  replayed from the top through the exact same `CommandExecutor` used for
  live traffic (see `AofLoader`).
- **Rewrite/compaction**: `SAVE` calls `AofWriter.rewrite()`, which walks
  the current keyspace and writes the *minimal* set of commands needed to
  reconstruct it (one `SET`/`RPUSH`/`HSET`/`SADD` per key, plus an
  `EXPIRE` for keys with a TTL), then atomically replaces the old file.
  This is the same idea as Redis's `BGREWRITEAOF`, just triggered
  manually instead of automatically in the background.

## Web dashboard

```
 Browser (React SPA)                         Java process
 ┌────────────────────────┐   HTTP/JSON    ┌───────────────────────────────────────┐
 │ StatCards  Console     │ ─────────────▶ │ DashboardServer  (JDK HttpServer,     │
 │ Chart      KeyBrowser  │   polls 1-2s   │   one entry point = every security    │
 │ Inspector  Activity    │ ◀───────────── │   check runs on every request)        │
 └────────────────────────┘                │        │                               │
                                           │        ▼                               │
                                           │ DashboardApi ──▶ CommandExecutor ──▶ Database
                                           │        ▲              │ (listener)     │
                                           │        └── ServerStats ◀┘               │
                                           └───────────────────────────────────────┘
```

- **Frontend** (`frontend/`): React components built by Vite into static files. Maven's
  `frontend-maven-plugin` runs the build during `mvn package`, and the output is packed into the jar under
  `/dashboard`, so the whole app ships as one file.
- **API** (`dashboard/DashboardApi`): `/api/stats`, `/api/keys`, `/api/key`, `/api/activity`,
  `/api/command`, `/api/demo-data`, plus `/api/session`, `/api/login` and `/api/logout`. Keyspace reads take the
  same lock as commands (`CommandExecutor.callExclusive`), so the UI always sees a consistent snapshot, and every
  response is size-capped.
- **Console commands** run through the normal `CommandExecutor` with origin `DASHBOARD`, so they are logged to
  the AOF and appear in the activity feed exactly like `redis-cli` traffic.
- **Metrics**: `ServerStats` subscribes to the executor as a `CommandListener` (observer pattern) and keeps
  a 60-second ring buffer of per-second counts plus a bounded feed of recent commands.

## Security model

Security is layered, so no single mistake exposes the data:

1. **Network**: both servers bind to `127.0.0.1` by default. With no password, *protected mode* refuses
   non-loopback TCP clients, and the dashboard refuses to start on a non-loopback address.
2. **Authentication**: `AUTH` is handled in `ClientHandler` (per-connection state), compared in constant time
   and throttled on failure, and is never passed to the executor, so it can't leak into the AOF or the activity
   feed. The dashboard exchanges the password once for a random 256-bit bearer token.
3. **Input limits**: the RESP reader caps argument counts and sizes, and `KEYS` uses a linear-time glob
   matcher (no regex backtracking).
4. **Browser protections**: CSP without inline scripts, React output escaping, CSRF header + `Origin` check,
   `Host` allow-list (DNS rebinding), frame denial, `no-store` caching for API data, and a strict allow-list
   pattern for static file paths (no traversal).

See [SECURITY.md](../SECURITY.md) for the full table.

## What's deliberately not implemented

See the "What this project leaves out" section of the main README for the
full list (pub/sub, transactions, replication, RDB binary snapshots,
LRU/LFU eviction, cluster mode, ...) and why - the goal is a correct,
readable core, not command-for-command parity with Redis.
