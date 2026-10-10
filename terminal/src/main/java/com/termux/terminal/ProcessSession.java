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

/** A session running a local process on a pseudo-terminal. */
public final class ProcessSession extends TerminalSession {

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    private int mShellPid;

    /** The master half of the pseudo-terminal pair. */
    private int mTerminalFileDescriptor;

    public ProcessSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        super(transcriptRows, client);
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
    }

    @Override
    protected void onStart(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
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
    protected void onResize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
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
            finished(null);
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
