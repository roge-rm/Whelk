package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session: a terminal emulator joined to something that talks to it, a local process
 * ({@link ProcessSession}) or a stream such as an SSH channel ({@link StreamSession}).
 * <p>
 * Emulation starts when the view first reports its size through {@link #updateSize(int, int, int, int)},
 * which calls {@link #onStart}. All terminal emulation and client callbacks happen on the main thread.
 * Subclasses feed output in with {@link #feed} from any thread and take input from
 * {@link #readInput} on their own writer thread.
 * <p>
 * NOTE: The terminal session may outlive the view, so be careful with callbacks!
 */
public abstract class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_FINISHED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Output from the far end, written by a reader thread and read on the main thread. */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /** Input from the user, written on the main thread and read by the subclass's writer thread. */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    protected TerminalSessionClient mClient;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    private boolean mStarted;
    private String mFinishMessage;
    private boolean mFinishShown;
    private volatile boolean mFinished;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    protected static final String LOG_TAG = "TerminalSession";

    protected TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /** Start talking to the far end, now that the size is known. Called once on the main thread. */
    protected abstract void onStart(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** Tell the far end the window size changed. */
    protected abstract void onResize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** Stop the far end. {@link #finished} should follow once it has stopped. */
    protected abstract void onFinish();

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the far end of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            if (!mFinished) onResize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Set the terminal emulator's window size and start terminal emulation. */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        if (!mStarted) {
            mStarted = true;
            onStart(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The current size, or null before the view has reported one. Columns then rows. */
    public int[] getSize() {
        if (mEmulator == null) return null;
        return new int[]{mEmulator.mColumns, mEmulator.mRows};
    }

    /** Sees output from the far end as it arrives, on the reader thread. */
    public interface OutputListener {
        void onOutput(byte[] data, int offset, int count);
    }

    /** Sees what the user types, as opposed to the emulator's own replies. */
    public interface InputListener {
        void onUserInput(byte[] data, int offset, int count);
    }

    public volatile OutputListener mOutputListener;
    public volatile InputListener mInputListener;

    /** Writes keys the user typed: the same as {@link #write}, and tells the input listener. */
    public void writeFromUser(byte[] data, int offset, int count) {
        write(data, offset, count);
        InputListener l = mInputListener;
        if (l != null) l.onUserInput(data, offset, count);
    }

    public final void writeFromUser(String text) {
        if (text == null) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writeFromUser(bytes, 0, bytes.length);
    }

    /** Pass output from the far end to the emulator. Safe from any thread. */
    protected final boolean feed(byte[] data, int offset, int count) {
        OutputListener l = mOutputListener;
        if (l != null) l.onOutput(data, offset, count);
        if (!mProcessToTerminalIOQueue.write(data, offset, count)) return false;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        return true;
    }

    /** Show text in the terminal as if the far end sent it. Safe from any thread. */
    public final void feed(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        feed(bytes, 0, bytes.length);
    }

    /** Block for user input to send to the far end. Returns -1 once the session has finished. */
    protected final int readInput(byte[] buffer) {
        return mTerminalToProcessIOQueue.read(buffer, true);
    }

    /**
     * The far end has gone. Shows the message in the terminal and tells the client. Safe from any
     * thread, and only the first call counts.
     */
    protected final void finished(String message) {
        synchronized (this) {
            if (mFinished) return;
            mFinished = true;
        }
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_FINISHED, message == null ? "" : message));
    }

    /** Write data to the far end. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (!mFinished) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        writeFromUser(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Stop the far end if it's still going. */
    public void finishIfRunning() {
        if (isRunning()) onFinish();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public boolean isRunning() {
        return !mFinished;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @Override
    public void onRemoteCopy(String text) {
        mClient.onRemoteCopyToClipboard(this, text);
    }

    @Override
    public void onNotify(String title, String body) {
        mClient.onNotify(this, title, body);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            int bytesRead;
            boolean any = false;
            // Output that comes before the view has sized the emulator waits in the queue.
            while (mEmulator != null && (bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)) > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                any = true;
            }
            if (any) notifyScreenUpdate();

            if (msg.what == MSG_FINISHED) {
                mTerminalToProcessIOQueue.close();
                mFinishMessage = (String) msg.obj;
                mFinishShown = false;
            }
            // Without an emulator yet, the rest of the output and the message wait for one.
            if (mFinishMessage != null && !mFinishShown && mEmulator != null) {
                mFinishShown = true;
                mProcessToTerminalIOQueue.close();
                byte[] bytesToWrite = mFinishMessage.getBytes(StandardCharsets.UTF_8);
                if (bytesToWrite.length > 0) mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();
            }
            if (msg.what == MSG_FINISHED) {
                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
