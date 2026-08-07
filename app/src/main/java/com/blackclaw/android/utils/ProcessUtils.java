package com.blackclaw.android.utils;

import java.io.InputStream;

/**
 * Safe wrapper around {@link Runtime#exec(String[])}.
 *
 * <p>WHY THIS EXISTS — the naive pattern that used to be copy-pasted around the
 * codebase was:
 *
 * <pre>Runtime.getRuntime().exec(cmd).waitFor();</pre>
 *
 * <p>That has two concrete failure modes:
 *
 * <ol>
 *   <li><b>File-descriptor leak.</b> Every {@code exec()} opens three pipes
 *       (stdin, stdout, stderr). None of them are closed by {@code waitFor()},
 *       and {@code destroy()} was never called, so each invocation leaked 3 fds.
 *       Android processes have a 1024 fd limit; an agent session that presses
 *       keys hundreds of times walks straight into
 *       "Too many open files" — which then breaks unrelated things like opening
 *       a socket or a database.</li>
 *   <li><b>Deadlock.</b> The child's stdout pipe has a small kernel buffer
 *       (typically 64 KB). If the command writes more than that and nobody
 *       reads, the child blocks on write() forever and our {@code waitFor()}
 *       blocks on the child forever. For {@code input keyevent} that is rare,
 *       but any command that prints an error/usage banner can trip it, and the
 *       thread that hangs is the agent's only worker.</li>
 * </ol>
 *
 * <p>This helper always drains both output streams, always closes all three,
 * and always calls {@code destroy()}, mirroring the discipline in
 * {@code shizuku/ShizukuManager.sh()}.
 */
public final class ProcessUtils {

    private static final String TAG = "ProcessUtils";

    private ProcessUtils() {
    }

    /**
     * Runs a command, drains its output, waits for it and always releases the
     * process resources.
     *
     * @param command argv, e.g. {@code {"input", "keyevent", "66"}}
     * @return the child's exit code, or -1 if it could not be started / was interrupted
     */
    public static int exec(String... command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(command);
            // Drain BEFORE waitFor(): a full stdout pipe would otherwise block
            // the child forever and hang this thread with it.
            drain(process.getInputStream());
            drain(process.getErrorStream());
            return process.waitFor();
        } catch (InterruptedException e) {
            // Preserve the interrupt so cooperative cancellation still works
            // (the agent cancels tasks by interrupting its worker thread).
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            XLog.w(TAG, "exec failed: " + join(command) + " — " + e.getMessage());
            return -1;
        } finally {
            release(process);
        }
    }

    /** Convenience for callers that only care whether the command succeeded. */
    public static boolean execOk(String... command) {
        return exec(command) == 0;
    }

    /** Reads a stream to exhaustion and closes it. Never throws. */
    private static void drain(InputStream in) {
        if (in == null) return;
        try {
            byte[] buf = new byte[512];
            //noinspection StatementWithEmptyBody
            while (in.read(buf) != -1) {
                // discard — we only need to keep the pipe from filling up
            }
        } catch (Exception ignored) {
            // Stream closed by process teardown; nothing useful to do.
        }
    }

    /** Closes all three pipes and reaps the process. */
    private static void release(Process process) {
        if (process == null) return;
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
        try {
            process.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }
}
