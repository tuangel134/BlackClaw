package com.blackclaw.android.tool.impl;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;
import com.blackclaw.android.service.ClawAccessibilityService;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SystemKeyTool extends BaseTool {

    @Override
    public String getName() {
        return "system_key";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_system_key);
    }

    @Override
    public String getDescriptionEN() {
        return "Press a system key. Supported keys: back (navigate back), home (go to home screen), recent_apps (open task switcher), notifications (expand notification shade), collapse_notifications (collapse notification/quick settings), lock_screen (lock screen, Android 9+), unlock_screen (wake up and unlock screen), enter (press Enter/submit), tab (press Tab).";
    }

    @Override
    public String getDescriptionCN() {
        return "Press a system key. Supported keys: back (go back), home (go to home screen), recent_apps (open recent tasks), notifications (expand notification bar), collapse_notifications (collapse notification bar/quick settings), lock_screen (lock screen, requires Android 9+), unlock_screen (wake and unlock screen), enter (press Enter/confirm), tab (press Tab).";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter(
                        "key",
                        "string",
                        "The system key to press. Must be one of: back, home, recent_apps, notifications, collapse_notifications, lock_screen, unlock_screen, enter, tab.",
                        true
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String key = requireString(params, "key");
        boolean success;
        String successMsg;

        switch (key) {
            case "back":
                success = service.pressBack();
                successMsg = "Pressed Back button";
                break;
            case "home":
                success = service.pressHome();
                successMsg = "Pressed Home button";
                break;
            case "recent_apps":
                success = service.openRecentApps();
                successMsg = "Opened recent apps";
                break;
            case "notifications":
                success = service.expandNotifications();
                successMsg = "Expanded notifications";
                break;
            case "collapse_notifications":
                success = service.collapseNotifications();
                successMsg = "Collapsed notifications";
                break;
            case "lock_screen":
                success = service.lockScreen();
                successMsg = "Screen locked";
                break;
            case "unlock_screen":
                success = service.unlockScreen();
                successMsg = "Screen unlock requested";
                break;
            // ProcessUtils drains stdout/stderr, closes all three pipes and
            // destroys the process. The old inline exec()+waitFor() leaked 3 fds
            // per press (1024-fd ceiling → "Too many open files" mid-session) and
            // could hang the agent thread if the command wrote past the pipe buffer.
            case "enter":
                success = com.blackclaw.android.utils.ProcessUtils.execOk(
                        "input", "keyevent", String.valueOf(android.view.KeyEvent.KEYCODE_ENTER));
                successMsg = "Pressed Enter key";
                break;
            case "tab":
                success = com.blackclaw.android.utils.ProcessUtils.execOk(
                        "input", "keyevent", String.valueOf(android.view.KeyEvent.KEYCODE_TAB));
                successMsg = "Pressed Tab key";
                break;
            default:
                return ToolResult.error("Unknown system key: " + key + ". Must be one of: back, home, recent_apps, notifications, collapse_notifications, lock_screen, unlock_screen, enter, tab.");
        }

        return success ? ToolResult.success(successMsg)
                : ToolResult.error("Failed to execute " + key);
    }
}
