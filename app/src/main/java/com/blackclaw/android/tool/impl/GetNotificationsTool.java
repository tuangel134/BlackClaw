package com.blackclaw.android.tool.impl;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.service.ClawNotificationListener;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;
import com.blackclaw.android.utils.XLog;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Read active notifications directly from NotificationListenerService.
 * No UI interaction needed — faster and more reliable than pulling down the shade.
 */
public class GetNotificationsTool extends BaseTool {

    private static final String TAG = "GetNotificationsTool";

    @Override
    public String getName() { return "get_notifications"; }

    @Override
    public String getDisplayName() { return "Get Notifications"; }

    @Override
    public String getDescriptionEN() {
        return "Read all active notifications directly without opening the notification shade. "
                + "Returns app name, title, text, and time for each notification. "
                + "Requires Notification Access permission to be enabled.";
    }

    @Override
    public String getDescriptionCN() {
        return "Read all active notifications directly without opening the notification shade. "
                + "Returns app name, title, text, and time for each notification.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        if (!ClawNotificationListener.isConnected()) {
            return ToolResult.error("Notification Access is not enabled. Ask the user to enable it in Settings.");
        }

        try {
            StatusBarNotification[] notifications = ClawNotificationListener.getActiveNotificationList();
            if (notifications == null || notifications.length == 0) {
                return ToolResult.success("No active notifications.");
            }

            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (StatusBarNotification sbn : notifications) {
                // Skip BlackClaw's own notifications
                if ("com.blackclaw.android".equals(sbn.getPackageName())) continue;

                Notification notif = sbn.getNotification();
                if (notif == null || notif.extras == null) continue;

                Bundle extras = notif.extras;
                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
                String text = textCs != null ? textCs.toString() : "";
                CharSequence bigTextCs = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                String bigText = bigTextCs != null ? bigTextCs.toString() : "";

                // Use big text if available (more complete), fall back to regular text
                String displayText = !bigText.isEmpty() ? bigText : text;
                if (title.isEmpty() && displayText.isEmpty()) continue;

                count++;
                long when = notif.when;
                String timeAgo = formatTimeAgo(when);
                String pkg = sbn.getPackageName();
                String appLabel = getAppLabel(pkg);

                sb.append(count).append(". ");
                sb.append(appLabel);
                if (!title.isEmpty()) sb.append(": ").append(title);
                if (!displayText.isEmpty()) {
                    // Truncate long text
                    String truncated = displayText.length() > 120
                            ? displayText.substring(0, 120) + "..."
                            : displayText;
                    sb.append(" — ").append(truncated);
                }
                sb.append(" (").append(timeAgo).append(")");
                sb.append("\n");

                if (count >= 15) {
                    sb.append("... and more notifications\n");
                    break;
                }
            }

            if (count == 0) {
                return ToolResult.success("No active notifications (only BlackClaw system notifications present).");
            }

            XLog.d(TAG, "Read " + count + " notifications");
            return ToolResult.success(sb.toString().trim());

        } catch (Exception e) {
            XLog.e(TAG, "Failed to read notifications", e);
            return ToolResult.error("Failed to read notifications: " + e.getMessage());
        }
    }

    private String formatTimeAgo(long whenMs) {
        if (whenMs == 0) return "just now";
        long diff = System.currentTimeMillis() - whenMs;
        if (diff < 0) return "just now";
        long minutes = diff / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }

    private String getAppLabel(String packageName) {
        try {
            android.content.pm.PackageManager pm = ClawApplication.Companion.getInstance().getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            // Fallback: extract readable name from package
            String[] parts = packageName.split("\\.");
            return parts.length > 0 ? parts[parts.length - 1] : packageName;
        }
    }
}
