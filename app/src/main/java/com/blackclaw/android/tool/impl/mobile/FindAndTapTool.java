package com.blackclaw.android.tool.impl.mobile;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.blackclaw.android.service.ClawAccessibilityService;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;
import com.blackclaw.android.utils.XLog;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Skill: find an element by text (scrolling if needed) and tap it — all in one tool call.
 * Saves 3-5 LLM rounds compared to manually calling scroll_to_find + tap.
 */
public class FindAndTapTool extends BaseTool {

    private static final String TAG = "FindAndTapTool";

    @Override
    public String getName() {
        return "find_and_tap";
    }

    @Override
    public String getDisplayName() {
        return "Find & Tap";
    }

    @Override
    public String getDescriptionEN() {
        return "Find a UI element by text (scrolling if needed) and tap it. Combines scroll_to_find + tap into one action. Use this instead of manual scroll + tap loops.";
    }

    @Override
    public String getDescriptionCN() {
        return "Find a UI element by text (scrolling if needed) and tap it. Combines scroll_to_find + tap into one action.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "The text to find and tap", true),
                new ToolParameter("direction", "string", "Scroll direction: 'up' or 'down' (default 'down')", false),
                new ToolParameter("max_scrolls", "integer", "Max scrolls to attempt (default 10)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String text = requireString(params, "text");
        String direction = optionalString(params, "direction", "down");
        int maxScrolls = optionalInt(params, "max_scrolls", 10);
        maxScrolls = Math.min(Math.max(maxScrolls, 1), 20);

        // First try current screen
        ToolResult tapResult = findAndTap(service, text);
        if (tapResult != null) return tapResult;

        // Scroll + find + tap
        int[] screenSize = getScreenSize();
        int centerX = screenSize[0] / 2;
        int scrollStartY, scrollEndY;
        if ("up".equals(direction)) {
            scrollStartY = (int) (screenSize[1] * 0.3);
            scrollEndY = (int) (screenSize[1] * 0.7);
        } else {
            scrollStartY = (int) (screenSize[1] * 0.7);
            scrollEndY = (int) (screenSize[1] * 0.3);
        }

        String lastScreen = null;
        try { lastScreen = service.getScreenTree(); } catch (Exception ignored) {}

        for (int i = 0; i < maxScrolls; i++) {
            service.performSwipe(centerX, scrollStartY, centerX, scrollEndY, 400);
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("Interrupted");
            }

            tapResult = findAndTap(service, text);
            if (tapResult != null) return tapResult;

            // Detect end of scroll
            String currentScreen = null;
            try { currentScreen = service.getScreenTree(); } catch (Exception ignored) {}
            if (currentScreen != null && currentScreen.equals(lastScreen)) {
                return ToolResult.error("\"" + text + "\" not found. Reached " +
                        ("up".equals(direction) ? "top" : "bottom") + " after " + (i + 1) + " scrolls.");
            }
            lastScreen = currentScreen;
        }

        return ToolResult.error("\"" + text + "\" not found after " + maxScrolls + " scrolls.");
    }

    private ToolResult findAndTap(ClawAccessibilityService service, String text) {
        List<AccessibilityNodeInfo> nodes = service.findNodesByText(text);
        if (nodes.isEmpty()) return null;
        try {
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isVisibleToUser()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    int cx = bounds.centerX();
                    int cy = bounds.centerY();
                    boolean tapped = service.performTap(cx, cy);
                    if (tapped) {
                        XLog.i(TAG, "Found and tapped \"" + text + "\" at (" + cx + "," + cy + ")");
                        return ToolResult.success("Found \"" + text + "\" and tapped at (" + cx + ", " + cy + ")");
                    }
                }
            }
            return null;
        } finally {
            ClawAccessibilityService.recycleNodes(nodes);
        }
    }
}
