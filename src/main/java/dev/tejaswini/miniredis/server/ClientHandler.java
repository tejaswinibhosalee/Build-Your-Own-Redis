package dev.tejaswini.miniredis.server;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.protocol.ProtocolException;
import dev.tejaswini.miniredis.protocol.Reply;
import dev.tejaswini.miniredis.protocol.RespReader;
import dev.tejaswini.miniredis.protocol.RespWriter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

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
 */
final class ClientHandler implements Runnable {

    private final Socket socket;
    private final CommandExecutor executor;

    ClientHandler(Socket socket, CommandExecutor executor) {
        this.socket = socket;
        this.executor = executor;
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

                Reply reply = executor.execute(args);
                RespWriter.write(out, reply);
                out.flush();

                if ("QUIT".equalsIgnoreCase(args.get(0))) {
                    break;
                }
            }
        } catch (IOException e) {
            // Client disconnected mid-command (reset, timeout, ...) - not
            // an error worth surfacing, the socket close in the try-with-
            // resources above already cleaned things up.
        }
    }
}
