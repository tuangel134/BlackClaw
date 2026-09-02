package com.blackclaw.android.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import com.blackclaw.android.ui.chat.ComposeChatActivity;
import com.blackclaw.android.utils.XLog;

/**
 * Debug broadcast receiver to trigger tasks via ADB without manual UI interaction.
 *
 * Usage:
 *   adb shell am broadcast -a com.blackclaw.android.TASK --es task "send hi to Mom on WhatsApp" -p com.blackclaw.android
 *   adb shell am broadcast -a com.blackclaw.android.TASK --es chat "read my clipboard and explain what it says" -p com.blackclaw.android
 *   adb shell am broadcast -a com.blackclaw.android.TASK --es chat_b64 "$(printf 'remember token abc123 and reply with only OK' | base64 -w0)" -p com.blackclaw.android
 *
 * Launches ComposeChatActivity with the matching extra — works even after reinstall.
 */
public class TaskTriggerReceiver extends BroadcastReceiver {

    private static final String TAG = "TaskTriggerReceiver";
    public static final String ACTION = "com.blackclaw.android.TASK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!com.blackclaw.android.BuildConfig.DEBUG) return;
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String task = firstNonBlank(
                decodeBase64Extra(intent, "task_b64"),
                intent.getStringExtra("task")
        );
        String chat = firstNonBlank(
                decodeBase64Extra(intent, "chat_b64"),
                intent.getStringExtra("chat")
        );
        boolean hasTask = task != null && !task.isEmpty();
        boolean hasChat = chat != null && !chat.isEmpty();
        if (!hasTask && !hasChat) {
            XLog.w(TAG, "Received broadcast with no task extra");
            return;
        }
        XLog.i(TAG, hasTask ? "Received task via broadcast: " + task : "Received chat via broadcast: " + chat);

        // Auto-reply commands: "autoreply on Mom" / "autoreply off"
        if (hasTask && task.startsWith("autoreply ")) {
            String cmd = task.substring(10).trim();
            if (cmd.equals("off")) {
                com.blackclaw.android.service.AutoReplyManager.getInstance().stopAll();
                XLog.i(TAG, "Auto-reply disabled");
            } else if (cmd.startsWith("on ")) {
                String target = cmd.substring(3).trim();
                String lowerTarget = target.toLowerCase();
                if (lowerTarget.contains(" on whatsapp")
                        || lowerTarget.contains(" on telegram")
                        || lowerTarget.contains(" on messages")
                        || lowerTarget.contains(" on line")
                        || lowerTarget.contains(" on wechat")) {
                    task = "monitor " + target;
                } else {
                    task = "monitor " + target + " on WhatsApp";
                }
                XLog.i(TAG, "Rewriting debug auto-reply command to task flow: " + task);
            }
        }

        // Launch ComposeChatActivity with task/chat extra — this always works
        Intent launch = new Intent(context, ComposeChatActivity.class);
        if (hasTask) {
            launch.putExtra("task", task);
        }
        if (hasChat) {
            launch.putExtra("chat", chat);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launch);
    }

    private static String decodeBase64Extra(Intent intent, String key) {
        String encoded = intent.getStringExtra(key);
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            return new String(Base64.decode(encoded, Base64.DEFAULT)).trim();
        } catch (IllegalArgumentException e) {
            XLog.w(TAG, "Invalid base64 extra for " + key, e);
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        if (second != null && !second.trim().isEmpty()) return second.trim();
        return null;
    }
}
