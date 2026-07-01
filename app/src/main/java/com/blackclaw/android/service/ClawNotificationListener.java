package com.blackclaw.android.service;

import android.content.ComponentName;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.blackclaw.android.utils.KVUtils;
import com.blackclaw.android.utils.XLog;

import java.util.Set;
import java.util.HashSet;

/**
 * Listens for ALL notifications (including updates to existing ones).
 * Routes messaging notifications to AutoReplyManager.
 *
 * Unlike AccessibilityService's TYPE_NOTIFICATION_STATE_CHANGED, this fires
 * reliably on notification updates — fixing the bug where WhatsApp updates
 * an existing notification instead of creating a new one.
 *
 * Also provides cancelNotification() to dismiss notifications after replying,
 * ensuring the next message triggers a fresh notification event.
 *
 * Requires: Settings → Notification Access → BlackClaw enabled.
 */
public class ClawNotificationListener extends NotificationListenerService {

    private static final String TAG = "ClawNotifListener";
    private static ClawNotificationListener instance;

    private static final Set<String> MESSAGING_APPS = new HashSet<>();
    static {
        MESSAGING_APPS.add("com.whatsapp");
        MESSAGING_APPS.add("org.telegram.messenger");
        MESSAGING_APPS.add("com.google.android.apps.messaging");
        MESSAGING_APPS.add("jp.naver.line.android");
        MESSAGING_APPS.add("com.tencent.mm");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        KVUtils.INSTANCE.noteNotificationListenerConnected();
        XLog.i(TAG, "Notification listener connected");
        ForegroundService.Companion.syncToBackgroundState(this);
        maybeReturnToAppAfterPermissionFlow();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        instance = null;
        KVUtils.INSTANCE.noteNotificationListenerDisconnected();
        XLog.i(TAG, "Notification listener disconnected");
        ForegroundService.Companion.syncToBackgroundState(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        // Never react to our own notifications (avoids feedback loops).
        if (pkg != null && pkg.equals("com.blackclaw.android")) return;

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;

        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");

        if (title.isEmpty() && text.isEmpty()) return;

        // Richer text (MessagingStyle messages / inbox lines / big text) so the
        // proactive classifier gets the full content and rarely needs the
        // intrusive "open the chat" deep-read.
        String richText = extractRichText(notification, text);

        // Proactive Assistant: route through NotificationBatcher for intelligent
        // batching (groups rapid-fire messages from same app into one LLM call).
        try {
            com.blackclaw.android.proactive.NotificationBatcher.INSTANCE
                    .submit(pkg, title, richText);
        } catch (Throwable t) {
            XLog.w(TAG, "Proactive hook failed: " + t.getMessage());
        }

        // Auto-Replies only care about messaging apps and need both fields.
        if (!MESSAGING_APPS.contains(pkg)) return;
        if (title.isEmpty() || text.isEmpty()) return;

        XLog.d(TAG, "Notification from " + pkg + ": title='" + title + "' text='" + text + "'");

        // Route to AutoReplyManager
        AutoReplyManager.getInstance().onNotificationReceived(pkg, title, text);
    }

    /**
     * Build the richest available text for a notification: MessagingStyle
     * messages > inbox text lines > big text, falling back to [fallback] (the
     * short EXTRA_TEXT). Recovers full content that would otherwise force an
     * intrusive "open the app" deep-read by the proactive assistant.
     */
    private static String extractRichText(Notification n, String fallback) {
        try {
            Bundle extras = n.extras;
            if (extras == null) return fallback;
            StringBuilder sb = new StringBuilder();

            android.os.Parcelable[] msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (msgs != null && msgs.length > 0) {
                for (android.os.Parcelable p : msgs) {
                    if (p instanceof Bundle) {
                        CharSequence mt = ((Bundle) p).getCharSequence("text");
                        if (mt != null && mt.length() > 0) sb.append(mt).append('\n');
                    }
                }
            }
            if (sb.length() == 0) {
                CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0) {
                    for (CharSequence l : lines) if (l != null) sb.append(l).append('\n');
                }
            }
            if (sb.length() == 0) {
                CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (big != null && big.length() > 0) sb.append(big);
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? fallback : result;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Dismiss all notifications from a specific package.
     * Called after replying so the next message triggers a fresh notification.
     */
    public static void dismissNotifications(String packageName) {
        ClawNotificationListener listener = instance;
        if (listener == null) {
            XLog.w(TAG, "Cannot dismiss notifications — listener not connected");
            return;
        }
        try {
            StatusBarNotification[] active = listener.getActiveNotifications();
            if (active == null) return;
            int dismissed = 0;
            for (StatusBarNotification sbn : active) {
                if (sbn.getPackageName().equals(packageName)) {
                    listener.cancelNotification(sbn.getKey());
                    dismissed++;
                }
            }
            XLog.i(TAG, "Dismissed " + dismissed + " notifications from " + packageName);
        } catch (Exception e) {
            XLog.w(TAG, "Failed to dismiss notifications", e);
        }
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean isEnabledInSettings(Context context) {
        try {
            String enabledListeners = Settings.Secure.getString(
                    context.getContentResolver(),
                    "enabled_notification_listeners");
            if (enabledListeners == null || enabledListeners.isEmpty()) return false;
            String myListener = new ComponentName(context, ClawNotificationListener.class).flattenToString();
            return enabledListeners.contains(myListener);
        } catch (Exception e) {
            XLog.e(TAG, "Failed to check notification listener settings", e);
            return false;
        }
    }

    public static boolean awaitConnected(long timeoutMs) {
        if (instance != null) return true;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (instance != null) return true;
        }
        return false;
    }

    /**
     * Get all active notifications. Used by GetNotificationsTool.
     * Returns null if listener is not connected.
     */
    public static StatusBarNotification[] getActiveNotificationList() {
        ClawNotificationListener listener = instance;
        if (listener == null) return null;
        try {
            return listener.getActiveNotifications();
        } catch (Exception e) {
            XLog.w(TAG, "Failed to get active notifications", e);
            return null;
        }
    }

    private void maybeReturnToAppAfterPermissionFlow() {
        boolean pendingReturn;
        try {
            pendingReturn = KVUtils.INSTANCE.consumePendingNotificationAccessReturn(120_000L);
        } catch (Exception e) {
            XLog.w(TAG, "Failed to read pending notification access return flag", e);
            return;
        }
        if (!pendingReturn) {
            return;
        }

        XLog.i(TAG, "Completing pending notification access return");
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.postDelayed(() -> {
            try {
                android.content.Intent intent = new android.content.Intent(this, com.blackclaw.android.ui.settings.SettingsActivity.class);
                intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } catch (Exception e) {
                XLog.w(TAG, "Could not bring app to foreground after listener connected", e);
            }
        }, 400);
    }
}
