package com.blackclaw.android.tool.impl.tv;

import com.blackclaw.android.service.ClawAccessibilityService;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Base class for simple TV remote key tools that send a single key event.
 */
public abstract class BaseKeyTool extends BaseTool {

    /**
     * Returns the Android KeyEvent keycode to send.
     */
    protected abstract int getKeyCode();

    /**
     * Returns a human-readable label for logging (e.g. "D-pad Up").
     */
    protected abstract String getKeyLabel();

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        boolean success = service.sendKeyEvent(getKeyCode());
        return success
                ? ToolResult.success("Pressed " + getKeyLabel())
                : ToolResult.error("Failed to press " + getKeyLabel());
    }
}
