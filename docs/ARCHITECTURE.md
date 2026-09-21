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

## What's deliberately not implemented

See the "What this project leaves out" section of the main README for the
full list (pub/sub, transactions, replication, RDB binary snapshots,
LRU/LFU eviction, cluster mode, ...) and why - the goal is a correct,
readable core, not command-for-command parity with Redis.
