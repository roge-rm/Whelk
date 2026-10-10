package com.termux.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A session on a pair of streams, such as an SSH shell channel or a Telnet socket. The subclass
 * opens the streams in {@link #connect} on a background thread, and handles resizes and closing.
 */
public abstract class StreamSession extends TerminalSession {

    /** Set when the session ended on an error, like a dropped network, rather than the far end closing. */
    public volatile boolean mEndedWithError;

    protected StreamSession(Integer transcriptRows, TerminalSessionClient client) {
        super(transcriptRows, client);
    }

    /** The streams to talk over. */
    public static final class Streams {
        public final InputStream in;
        public final OutputStream out;

        public Streams(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }
    }

    /**
     * Open the streams, on a background thread. Throwing ends the session with the exception's
     * message. Can show progress with {@link #feed(String)}.
     */
    protected abstract Streams connect(int columns, int rows) throws Exception;

    /** The message shown when the far end closes, or for an error while connecting or talking. */
    protected String closedMessage(Exception error) {
        if (error == null) return "\r\n[Disconnected]";
        String m = error.getMessage();
        return "\r\n[" + (m == null ? error.getClass().getSimpleName() : m) + "]";
    }

    /** Called on the reader thread once the streams are done with, to free anything left. */
    protected void onClosed() {
    }

    @Override
    protected void onStart(final int columns, final int rows, int cellWidthPixels, int cellHeightPixels) {
        new Thread("StreamSessionReader") {
            @Override
            public void run() {
                final Streams streams;
                try {
                    streams = connect(columns, rows);
                } catch (Exception e) {
                    mEndedWithError = true;
                    onClosed();
                    finished(closedMessage(e));
                    return;
                }
                Thread writer = new Thread("StreamSessionWriter") {
                    @Override
                    public void run() {
                        final byte[] buffer = new byte[4096];
                        try {
                            while (true) {
                                int n = readInput(buffer);
                                if (n == -1) return;
                                streams.out.write(buffer, 0, n);
                                streams.out.flush();
                            }
                        } catch (IOException e) {
                            // The reader sees the stream close and finishes the session.
                        }
                    }
                };
                writer.start();
                Exception error = null;
                try {
                    final byte[] buffer = new byte[8192];
                    while (true) {
                        int read = streams.in.read(buffer);
                        if (read == -1) break;
                        if (read > 0 && !feed(buffer, 0, read)) break;
                    }
                } catch (Exception e) {
                    error = e;
                }
                mEndedWithError = error != null;
                onClosed();
                finished(closedMessage(error));
            }
        }.start();
    }

}
