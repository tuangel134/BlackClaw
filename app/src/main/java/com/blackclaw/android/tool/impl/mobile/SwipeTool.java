package com.blackclaw.android.tool.impl.mobile;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;
import com.blackclaw.android.service.ClawAccessibilityService;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SwipeTool extends BaseTool {

    @Override
    public String getName() {
        return "swipe";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_swipe);
    }

    @Override
    public String getDescriptionEN() {
        return "Swipe from one point to another on the screen. Useful for scrolling, pulling down notifications, etc.";
    }

    @Override
    public String getDescriptionCN() {
        return "Swipe from one point to another on the screen. Use this for scrolling, pulling down notifications, etc.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("start_x", "integer", "Start X coordinate", true),
                new ToolParameter("start_y", "integer", "Start Y coordinate", true),
                new ToolParameter("end_x", "integer", "End X coordinate", true),
                new ToolParameter("end_y", "integer", "End Y coordinate", true),
                new ToolParameter("duration_ms", "integer", "Swipe duration in milliseconds (default 500)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        int startX = requireInt(params, "start_x");
        int startY = requireInt(params, "start_y");
        int endX = requireInt(params, "end_x");
        int endY = requireInt(params, "end_y");
        String boundsError = validateCoordinates(startX, startY);
        if (boundsError != null) return ToolResult.error(boundsError);
        boundsError = validateCoordinates(endX, endY);
        if (boundsError != null) return ToolResult.error(boundsError);
        long duration = optionalLong(params, "duration_ms", 500);
        boolean success = service.performSwipe(startX, startY, endX, endY, duration);
        return success ? ToolResult.success("Swiped from (" + startX + ", " + startY + ") to (" + endX + ", " + endY + ")")
                : ToolResult.error("Failed to swipe");
    }
}
