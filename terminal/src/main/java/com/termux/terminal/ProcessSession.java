package com.termux.terminal;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

/**
 * A session running a local process on a pseudo-terminal. With a {@link Preparer}, something slow
 * runs first on a background thread and decides what to run, as Mosh logs in over SSH before
 * starting its client.
 */
public final class ProcessSession extends TerminalSession {

    /** What to run. */
    public static final class Command {
        public final String path;
        public final String cwd;
        public final String[] args;
        public final String[] env;

        public Command(String path, String cwd, String[] args, String[] env) {
            this.path = path;
            this.cwd = cwd;
            this.args = args;
            this.env = env;
        }
    }

    /** Works out the command on a background thread. Throwing ends the session with the message. */
    public interface Preparer {
        Command prepare(ProcessSession session, int columns, int rows) throws Exception;

        /** The session was closed while preparing. */
        void cancel();
    }

    private final Command mCommand;
    private final Preparer mPreparer;
    private volatile boolean mCancelled;
    private volatile boolean mPreparing;
    private volatile int mColumns, mRows, mCellWidth, mCellHeight;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    private int mShellPid;

    /** The master half of the pseudo-terminal pair. */
    private int mTerminalFileDescriptor;

    public ProcessSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        super(transcriptRows, client);
        this.mCommand = new Command(shellPath, cwd, args, env);
        this.mPreparer = null;
    }

    public ProcessSession(Preparer preparer, Integer transcriptRows, TerminalSessionClient client) {
        super(transcriptRows, client);
        this.mCommand = null;
        this.mPreparer = preparer;
    }

    /** Shows progress text while preparing. */
    public void progress(String text) {
        feed(text);
    }

    @Override
    protected void onStart(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mColumns = columns;
        mRows = rows;
        mCellWidth = cellWidthPixels;
        mCellHeight = cellHeightPixels;
        if (mPreparer == null) {
            spawn(mCommand);
            return;
        }
        mPreparing = true;
        new Thread("ProcessSessionPrepare") {
            @Override
            public void run() {
                Command command;
                try {
                    command = mPreparer.prepare(ProcessSession.this, columns, rows);
                } catch (Exception e) {
                    String m = e.getMessage();
                    finished(mCancelled ? "\r\n[Disconnected]" : "\r\n[" + (m == null ? e.getClass().getSimpleName() : m) + "]");
                    return;
                }
                synchronized (ProcessSession.this) {
                    if (mCancelled) {
                        finished("\r\n[Disconnected]");
                        return;
                    }
                    spawn(command);
                }
            }
        }.start();
    }

    private void spawn(Command command) {
        int[] processId = new int[1];
        // The size the view has now, which may have changed while preparing.
        mTerminalFileDescriptor = JNI.createSubprocess(command.path, command.cwd, command.args, command.env, processId, mRows, mColumns, mCellWidth, mCellHeight);
        mShellPid = processId[0];

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        if (!feed(buffer, 0, read)) return;
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    while (true) {
                        int bytesToWrite = readInput(buffer);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int exitCode = JNI.waitFor(mShellPid);
                synchronized (ProcessSession.this) {
                    mShellPid = -1;
                }
                JNI.close(mTerminalFileDescriptor);
                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += "]";
                finished(exitDescription);
            }
        }.start();
    }

    @Override
    protected synchronized void onResize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mColumns = columns;
        mRows = rows;
        mCellWidth = cellWidthPixels;
        mCellHeight = cellHeightPixels;
        if (mShellPid > 0) JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
    }

    /** Sends SIGKILL to the shell. */
    @Override
    protected synchronized void onFinish() {
        if (mShellPid > 0) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        } else if (mShellPid == 0) {
            mCancelled = true;
            if (mPreparing) mPreparer.cancel();
            else finished(null);
        }
    }

    public synchronized int getPid() {
        return mShellPid;
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(client, LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }

}
