# mini-redis

A from-scratch, educational re-implementation of core Redis in Java: the
**RESP wire protocol**, a **TCP server**, in-memory **data structures**
(strings, lists, hashes, sets), **key expiry**, and a simple **AOF-style
persistence** layer. It speaks real RESP, so it works with the actual
`redis-cli` and any real Redis client library - it is not a mock.

Built as a learning project to understand what an in-memory data store
actually does "under the hood" - the same category of systems (caches,
fast key-value stores) that sit next to Spring Boot microservices in a
typical backend/banking stack. It intentionally implements a **focused
core** rather than the full Redis command set, so the code stays small
enough to read start to finish in one sitting.

## Quick start

Requires Java 21+ and Maven.

```bash
mvn package
java -jar target/mini-redis.jar
```

By default it listens on port `6379` (Redis's own default) and persists
to `mini-redis.aof` in the working directory.

```bash
java -jar target/mini-redis.jar --port 6380 --aof-file /tmp/my.aof
java -jar target/mini-redis.jar --no-aof   # disable persistence entirely
```

Connect with the real Redis CLI:

```bash
redis-cli -p 6379
127.0.0.1:6379> SET foo bar
OK
127.0.0.1:6379> GET foo
"bar"
127.0.0.1:6379> RPUSH mylist a b c
(integer) 3
127.0.0.1:6379> LRANGE mylist 0 -1
1) "a"
2) "b"
3) "c"
```

(Or skip redis-cli entirely: `telnet localhost 6379` / `nc localhost 6379`
and type `PING` - the server understands plain inline commands too, not
just the length-prefixed RESP arrays real clients send.)

Run the tests:

```bash
mvn test
```

Run with Docker:

```bash
docker build -t mini-redis .
docker run -p 6379:6379 -v mini-redis-data:/data mini-redis
```

## Supported commands

| Category   | Commands |
|------------|----------|
| Connection | `PING`, `ECHO`, `QUIT` |
| Generic    | `DEL`, `EXISTS`, `EXPIRE`, `TTL`, `PERSIST`, `KEYS`, `TYPE`, `FLUSHALL` |
| Strings    | `SET` (`EX`/`PX`/`NX`/`XX`), `GET`, `INCR`, `DECR`, `APPEND`, `STRLEN` |
| Lists      | `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LLEN`, `LRANGE` |
| Hashes     | `HSET`, `HGET`, `HGETALL`, `HDEL` |
| Sets       | `SADD`, `SMEMBERS`, `SREM`, `SISMEMBER` |
| Server     | `DBSIZE`, `SAVE`, `INFO`, `COMMAND`/`CONFIG` (stubs, for client compatibility) |

## Concepts this project covers

- **RESP (REdis Serialization Protocol)** - hand-writing a binary-safe,
  length-prefixed wire protocol parser and encoder (`protocol/`).
- **A keyspace holding multiple types in one map** - `String`,
  `Deque<String>`, `Map<String,String>`, `Set<String>` all under one
  `Map<String, Object>`, with `WRONGTYPE` errors exactly like real Redis
  (`core/Database.java`).
- **Lazy + active key expiry** - checked on every read/write, and swept
  proactively by a background thread (`core/Database.activeExpireCycle`,
  `command/ExpiryScheduler.java`).
- **A concurrency model built around one global lock**, deliberately
  modeling why real Redis's single-threaded execution makes commands
  like `INCR` atomic without per-key locking (`command/CommandExecutor.java`
  - see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full
  explanation and its tradeoffs).
- **Java 21 virtual threads** for connection handling - simple
  one-thread-per-connection blocking I/O code that still scales to many
  concurrent, mostly-idle clients (`server/MiniRedisServer.java`).
- **Durability via an append-only log (AOF)**, including **log
  compaction/rewrite** (`SAVE` reduces the file to the minimal commands
  needed to reconstruct the current dataset, like Redis's
  `BGREWRITEAOF`) (`persistence/`).
- **The command/strategy pattern** - each command is a small, independently
  testable handler registered by name in a dispatch table, rather than one
  giant `switch`.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for a diagram and a
deeper walkthrough of the request lifecycle.

## Project layout

```
src/main/java/dev/tejaswini/miniredis/
├── MiniRedisApplication.java   entry point: arg parsing + wiring
├── protocol/                   RESP parsing (RespReader) & encoding (RespWriter)
├── core/                       Database (the keyspace) + expiry logic
├── command/                    CommandExecutor (dispatch + locking) and…
│   └── impl/                   …command groups: String/List/Hash/Set/Generic/Server
├── persistence/                AofWriter (append + rewrite), AofLoader (replay)
└── server/                     MiniRedisServer (accept loop), ClientHandler (per-connection)
```

## What this project leaves out (on purpose)

To keep this readable as a learning project, it does **not** implement:
pub/sub, transactions (`MULTI`/`EXEC`), Lua scripting, sorted sets,
replication, cluster mode, ACLs/auth, RESP3, or a binary RDB snapshot
format (it uses AOF-only persistence instead - simpler to implement
correctly and just as good a teaching example of the *durability*
concept). Those are natural "what I'd add next" extensions if you want to
go further with this codebase.

## Similar projects worth building next

Given a banking/Spring Boot microservices background, these follow
naturally from the concepts above and make strong companion resume
projects:

1. **Build-your-own message queue** (Kafka-lite) - partitions, offsets,
   consumer groups, at-least-once delivery.
2. **Ledger microservice** - Spring Boot + Postgres double-entry
   bookkeeping service with idempotency keys and outbox-pattern event
   publishing; very directly banking-relevant.
3. **Rate limiter / API gateway** - Spring Boot filter implementing
   token-bucket/sliding-window limiting, backed by this Redis clone (or
   real Redis) for shared counters across instances.
4. **Build-your-own circuit breaker** (like Resilience4j) - state machine
   (closed/open/half-open), used as a Spring Boot starter.
5. **Distributed lock service** - a tiny Raft or single-leader
   lease-based lock manager (etcd/ZooKeeper-lite), then use it to
   coordinate a multi-instance Spring Boot job scheduler.
6. **Event-driven fraud-detection pipeline** - Kafka + Spring Boot
   consumers doing rule-based scoring on a transaction stream.
7. **Build-your-own job scheduler** (like Quartz) - cron parsing, missed-run
   recovery, distributed leader election so only one instance fires a job.

Each of these pairs well with this project: they're systems-level,
Java-first, and map directly onto the kind of infrastructure that sits
around banking microservices (caching, messaging, resilience,
coordination, scheduling).
