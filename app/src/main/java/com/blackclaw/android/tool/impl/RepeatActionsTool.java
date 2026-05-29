package com.blackclaw.android.tool.impl;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolRegistry;
import com.blackclaw.android.tool.ToolResult;
import com.blackclaw.android.utils.XLog;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Action orchestration tool: combines existing atomic tools into an ordered action sequence with loop support.
 *
 * The AI passes an ordered set of tool calls via the actions parameter. Each action specifies the tool name and parameters.
 * The repeat_count parameter controls how many times the entire sequence is repeated.
 *
 * Typical use cases:
 * - Scroll TikTok: [swipe up, wait 10s] × 30 times
 * - Alternating swipes: [swipe up, wait, swipe down, wait, swipe left, wait, swipe right, wait] × 10 times
 * - Repeated likes: [tap like position, wait 5s, swipe up] × 20 times
 */
public class RepeatActionsTool extends BaseTool {

    private static final String TAG = "RepeatActionsTool";
    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();

    private static final int MAX_TOTAL_STEPS = 2000;
    private static final long MAX_TOTAL_DURATION_MS = 30 * 60 * 1000L; // 30 min

    private static final int DEFAULT_INTERVAL_MIN_MS = 3000;
    private static final int DEFAULT_INTERVAL_MAX_MS = 10000;

    private static final Set<String> BLOCKED_TOOLS = new HashSet<>(Arrays.asList(
            "repeat_actions", // prevent recursion
            "finish"          // should not be called inside a loop
    ));

    @Override
    public String getName() {
        return "repeat_actions";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_repeat_actions);
    }

    @Override
    public String getDescriptionEN() {
        return "Compose and repeat a sequence of existing tool actions. "
                + "Pass a JSON array of action steps in 'actions', each step is {\"tool\": \"<tool_name>\", \"params\": {<tool_params>}}. "
                + "The entire sequence will be executed 'repeat_count' times in order. "
                + "By default, a random delay of 3~10s is added between rounds to simulate human-like timing. "
                + "ONLY set interval_min_ms=interval_max_ms to a fixed value when the user EXPLICITLY requests a specific interval (e.g. 'every 10 seconds'). "
                + "Otherwise, always use the default random interval or customize the range via interval_min_ms/interval_max_ms. "
                + "Example: actions=[{\"tool\":\"swipe\",\"params\":{\"start_x\":540,\"start_y\":1600,\"end_x\":540,\"end_y\":400}}], repeat_count=30. "
                + "Cannot call 'repeat_actions' or 'finish' inside actions.";
    }

    @Override
    public String getDescriptionCN() {
        return "Compose existing tool calls into an action sequence and repeat it. "
                + "Pass a JSON array via the actions parameter; each element has the format {\"tool\": \"<tool_name>\", \"params\": {<params>}}. "
                + "The entire sequence will loop repeat_count times. "
                + "By default, a random 3~10 second delay is added between rounds to simulate human-like pacing. "
                + "Only set interval_min_ms and interval_max_ms to the same fixed value when the user explicitly requests a fixed interval (e.g. 'every 10 seconds'). "
                + "Otherwise use the default random interval, or customize the range. "
                + "Example - scroll TikTok 30 times (default random interval): "
                + "actions=[{\"tool\":\"swipe\",\"params\":{\"start_x\":540,\"start_y\":1600,\"end_x\":540,\"end_y\":400}}], repeat_count=30. "
                + "Cannot call repeat_actions or finish inside actions.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("actions", "string",
                        "JSON array of action steps. Each step: {\"tool\": \"tool_name\", \"params\": {param_key: param_value}}. "
                                + "Example: [{\"tool\":\"swipe\",\"params\":{\"start_x\":540,\"start_y\":1600,\"end_x\":540,\"end_y\":400}}]",
                        true),
                new ToolParameter("repeat_count", "integer",
                        "Number of times to repeat the entire action sequence (default: 1)", false),
                new ToolParameter("interval_min_ms", "integer",
                        "Minimum random delay in ms between rounds (default: 3000). A random value in [min, max] is picked each round. Set min=max for fixed interval only when user explicitly requests it.", false),
                new ToolParameter("interval_max_ms", "integer",
                        "Maximum random delay in ms between rounds (default: 10000). A random value in [min, max] is picked each round. Set min=max for fixed interval only when user explicitly requests it.", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String actionsJson = requireString(params, "actions");
        int repeatCount = optionalInt(params, "repeat_count", 1);
        int intervalMinMs = optionalInt(params, "interval_min_ms", DEFAULT_INTERVAL_MIN_MS);
        int intervalMaxMs = optionalInt(params, "interval_max_ms", DEFAULT_INTERVAL_MAX_MS);

        // Parse actions
        List<ActionStep> actions;
        try {
            Type listType = new TypeToken<List<ActionStep>>() {}.getType();
            actions = GSON.fromJson(actionsJson, listType);
        } catch (Exception e) {
            return ToolResult.error("Failed to parse actions JSON: " + e.getMessage());
        }

        if (actions == null || actions.isEmpty()) {
            return ToolResult.error("actions array is empty or null");
        }

        // Validate
        if (repeatCount < 1) {
            return ToolResult.error("repeat_count must be at least 1");
        }

        if (intervalMinMs < 0 || intervalMaxMs < 0) {
            return ToolResult.error("interval_min_ms and interval_max_ms must be >= 0");
        }
        if (intervalMaxMs > 0 && intervalMinMs > intervalMaxMs) {
            return ToolResult.error("interval_min_ms must be <= interval_max_ms");
        }

        long totalSteps = (long) repeatCount * actions.size();
        if (totalSteps > MAX_TOTAL_STEPS) {
            return ToolResult.error("Total steps (" + totalSteps + ") exceeds max " + MAX_TOTAL_STEPS
                    + ". Reduce repeat_count or number of actions.");
        }

        // Validate each action
        for (int i = 0; i < actions.size(); i++) {
            ActionStep step = actions.get(i);
            if (step.tool == null || step.tool.isEmpty()) {
                return ToolResult.error("Action step " + i + " has no tool name");
            }
            if (BLOCKED_TOOLS.contains(step.tool)) {
                return ToolResult.error("Tool '" + step.tool + "' cannot be used inside repeat_actions");
            }
            if (ToolRegistry.getInstance().getTool(step.tool) == null) {
                return ToolResult.error("Unknown tool '" + step.tool + "' in action step " + i);
            }
            if (step.params == null) {
                step.params = Collections.emptyMap();
            }
        }

        // Estimate total wait time for duration check (include random interval estimate)
        long estimatedWaitMs = estimateTotalWait(actions, repeatCount);
        if (intervalMaxMs > 0 && repeatCount > 1) {
            long avgInterval = ((long) intervalMinMs + intervalMaxMs) / 2;
            estimatedWaitMs += avgInterval * (repeatCount - 1);
        }
        if (estimatedWaitMs > MAX_TOTAL_DURATION_MS) {
            return ToolResult.error("Estimated total wait time (" + estimatedWaitMs + "ms) exceeds max "
                    + MAX_TOTAL_DURATION_MS + "ms (30 min). Reduce wait durations or repeat_count.");
        }

        boolean hasRandomInterval = intervalMaxMs > 0;
        XLog.i(TAG, "Starting repeat_actions: " + actions.size() + " steps × " + repeatCount + " rounds"
                + (hasRandomInterval ? ", interval=" + intervalMinMs + "~" + intervalMaxMs + "ms" : ""));

        int totalSuccess = 0;
        int totalFail = 0;
        int completedRounds = 0;

        for (int round = 0; round < repeatCount; round++) {
            if (Thread.currentThread().isInterrupted()) {
                return buildResult(completedRounds, repeatCount, totalSuccess, totalFail, "interrupted");
            }

            // Random delay between rounds (skip before the first round)
            if (round > 0 && hasRandomInterval) {
                long sleepMs = randomInRange(intervalMinMs, intervalMaxMs);
                XLog.d(TAG, "Round " + (round + 1) + ": random interval " + sleepMs + "ms");
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return buildResult(completedRounds, repeatCount, totalSuccess, totalFail, "interrupted");
                }
            }

            for (int stepIdx = 0; stepIdx < actions.size(); stepIdx++) {
                if (Thread.currentThread().isInterrupted()) {
                    return buildResult(completedRounds, repeatCount, totalSuccess, totalFail, "interrupted");
                }

                ActionStep step = actions.get(stepIdx);
                ToolResult result = ToolRegistry.getInstance().executeTool(step.tool, step.params);

                if (result.isSuccess()) {
                    totalSuccess++;
                } else {
                    totalFail++;
                    XLog.w(TAG, "Step failed: round=" + (round + 1)
                            + ", step=" + stepIdx + " (" + step.tool + "): " + result.getError());
                }
            }

            completedRounds++;
        }

        return buildResult(completedRounds, repeatCount, totalSuccess, totalFail, "completed");
    }

    private long randomInRange(int minMs, int maxMs) {
        if (minMs >= maxMs) return minMs;
        return minMs + RANDOM.nextInt(maxMs - minMs + 1);
    }

    private long estimateTotalWait(List<ActionStep> actions, int repeatCount) {
        long waitPerRound = 0;
        for (ActionStep step : actions) {
            if ("wait".equals(step.tool) && step.params != null) {
                Object durationObj = step.params.get("duration_ms");
                if (durationObj instanceof Number) {
                    waitPerRound += ((Number) durationObj).longValue();
                }
            }
        }
        return waitPerRound * repeatCount;
    }

    private ToolResult buildResult(int completedRounds, int totalRounds, int success, int fail, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append(status.equals("completed") ? "All rounds completed. " : "Stopped early (" + status + "). ");
        sb.append("Rounds: ").append(completedRounds).append("/").append(totalRounds);
        sb.append(", Actions: ").append(success).append(" succeeded");
        if (fail > 0) {
            sb.append(", ").append(fail).append(" failed");
        }
        sb.append(".");
        String msg = sb.toString();
        XLog.i(TAG, msg);
        return ToolResult.success(msg);
    }

    /**
     * Represents a single action step in the sequence.
     */
    static class ActionStep {
        String tool;
        Map<String, Object> params;
    }
}
