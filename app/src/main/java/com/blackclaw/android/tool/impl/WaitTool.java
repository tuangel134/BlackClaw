package com.blackclaw.android.tool.impl;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class WaitTool extends BaseTool {

    @Override
    public String getName() {
        return "wait";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_wait);
    }

    @Override
    public String getDescriptionEN() {
        return "Wait for a specified number of milliseconds. Useful for waiting for UI transitions, animations, or loading to complete.";
    }

    @Override
    public String getDescriptionCN() {
        return "Wait for the specified number of milliseconds. Use this to wait for UI transitions, animations, or loading to complete.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("duration_ms", "integer", "Duration to wait in milliseconds", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long duration = requireLong(params, "duration_ms");
        if (duration < 0 || duration > 30000) {
            return ToolResult.error("Duration must be between 0 and 30000 milliseconds");
        }
        try {
            Thread.sleep(duration);
            return ToolResult.success("Waited for " + duration + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Wait was interrupted");
        }
    }
}
