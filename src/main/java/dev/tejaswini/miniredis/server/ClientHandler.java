package dev.tejaswini.miniredis.server;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.protocol.ProtocolException;
import dev.tejaswini.miniredis.protocol.Reply;
import dev.tejaswini.miniredis.protocol.RespReader;
import dev.tejaswini.miniredis.protocol.RespWriter;
import dev.tejaswini.miniredis.security.LoginThrottle;
import dev.tejaswini.miniredis.security.PasswordAuthenticator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.Locale;

/**
 * The read-dispatch-write loop for one client connection: parse a command,
 * hand it to {@link CommandExecutor}, write back the reply, repeat until
 * the client disconnects (or sends {@code QUIT}).
 *
 * <p>This is deliberately blocking, synchronous I/O - one {@link #run()}
 * per connection - rather than the non-blocking {@code Selector}/event-loop
 * style real Redis uses internally. What makes that affordable here is
 * Java 21 <b>virtual threads</b> (see {@link MiniRedisServer}): a blocked
 * virtual thread parks cheaply instead of pinning an OS thread, so we get
 * "one thread per connection" code that reads top-to-bottom like a simple
 * loop, while still scaling to many concurrent, mostly-idle connections -
 * without hand-rolling a state machine around partial reads.
 *
 * <p><b>Authentication</b> is per-connection state, so it lives here rather
 * than in the (connection-agnostic) {@link CommandExecutor}. A nice side
 * effect: {@code AUTH} never reaches the executor, so the password can never
 * end up in the AOF file or in the dashboard's activity feed.
 */
final class ClientHandler implements Runnable {

    private final Socket socket;
    private final CommandExecutor executor;
    private final PasswordAuthenticator authenticator;
    private final LoginThrottle loginThrottle;
    private boolean authenticated;

    ClientHandler(Socket socket, CommandExecutor executor,
                  PasswordAuthenticator authenticator, LoginThrottle loginThrottle) {
        this.socket = socket;
        this.executor = executor;
        this.authenticator = authenticator;
        this.loginThrottle = loginThrottle;
        // With no password configured, every connection is implicitly trusted
        // (the server only lets loopback clients in at all in that case -
        // see "protected mode" in MiniRedisServer).
        this.authenticated = !authenticator.isEnabled();
    }

    @Override
    public void run() {
        try (Socket ignored = socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            RespReader reader = new RespReader(in);

            while (true) {
                List<String> args;
                try {
                    args = reader.readCommand();
                } catch (ProtocolException e) {
                    RespWriter.write(out, new Reply.Error("ERR Protocol error: " + e.getMessage()));
                    out.flush();
                    break;
                }
                if (args == null) {
                    break; // client closed the connection
                }
                if (args.isEmpty()) {
                    continue; // blank inline command line, nothing to do
                }

                String name = args.get(0).toUpperCase(Locale.ROOT);
                Reply reply;
                if (name.equals("AUTH")) {
                    reply = handleAuth(args);
                } else if (!authenticated && !name.equals("QUIT")) {
                    reply = new Reply.Error("NOAUTH Authentication required.");
                } else {
                    reply = executor.execute(args);
                }
                RespWriter.write(out, reply);
                out.flush();

                if (name.equals("QUIT")) {
                    break;
                }
            }
        } catch (IOException e) {
            // Client disconnected mid-command (reset, timeout, ...) - not
            // an error worth surfacing, the socket close in the try-with-
            // resources above already cleaned things up.
        }
    }

    /**
     * {@code AUTH password} or {@code AUTH default password} (Redis 6+ ACL
     * syntax, which {@code redis-cli -a} and most client libraries send).
     * There is only one user here, named "default", like Redis without ACLs.
     */
    private Reply handleAuth(List<String> args) {
        if (args.size() != 2 && args.size() != 3) {
            return new Reply.Error("ERR wrong number of arguments for 'auth' command");
        }
        if (!authenticator.isEnabled()) {
            return new Reply.Error("ERR AUTH <password> called without any password configured. "
                    + "Set MINI_REDIS_PASSWORD to enable authentication.");
        }
        boolean userOk = args.size() == 2 || "default".equals(args.get(1));
        String password = args.get(args.size() - 1);
        // Always run the (constant-time) password check, even when the
        // username is wrong, so timing doesn't reveal which part failed.
        boolean passwordOk = authenticator.check(password);
        if (userOk && passwordOk) {
            authenticated = true;
            loginThrottle.onSuccess();
            return Reply.ok();
        }
        authenticated = false;
        loginThrottle.onFailure(); // blocks this (virtual) thread for a penalty delay
        return new Reply.Error("WRONGPASS invalid username-password pair or user is disabled.");
    }
}
