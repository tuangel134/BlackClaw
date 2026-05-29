package com.blackclaw.android.tool.impl;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;
import com.blackclaw.android.service.ClawAccessibilityService;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class InputTextTool extends BaseTool {

    @Override
    public String getName() {
        return "input_text";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_input_text);
    }

    @Override
    public String getDescriptionEN() {
        return "Input text into a text field. If node_id is provided, taps that node first to focus it, "
                + "then types the text — use this to target a specific field (e.g. To, Subject, Body). "
                + "If node_id is omitted, types into the currently focused field. "
                + "By default clears existing content before inputting (clear_first=true). "
                + "Set clear_first=false to append text without clearing.";
    }

    @Override
    public String getDescriptionCN() {
        return "Input text into a text field. If node_id is provided, taps that node first to focus it, "
                + "then types the text. If node_id is omitted, types into the currently focused field. "
                + "By default clears existing content (clear_first=true). Set clear_first=false to append.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "The text to input", true),
                new ToolParameter("node_id", "string", "Optional: node ID from get_screen_info (e.g. 'n5') to target a specific text field", false),
                new ToolParameter("clear_first", "boolean", "Whether to clear existing text before input (default true)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String text = requireString(params, "text");
        String nodeId = optionalString(params, "node_id", "");
        boolean clearFirst = optionalBoolean(params, "clear_first", true);
        int[] targetCoords = null;

        // If node_id provided, tap that node first to focus it
        if (!nodeId.isEmpty()) {
            nodeId = nodeId.replace("[", "").replace("]", "").trim();
            targetCoords = service.getNodeCoordinates(nodeId);
            if (targetCoords == null) {
                return ToolResult.error("Node " + nodeId + " not found. Call get_screen_info first to refresh node IDs.");
            }
            service.performTap(targetCoords[0], targetCoords[1]);
            try { Thread.sleep(300); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        AccessibilityNodeInfo targetNode = waitForTargetEditable(service, targetCoords);

        if (targetNode == null) {
            return ToolResult.error("No target text field found" + (nodeId.isEmpty() ? "" : " after tapping node " + nodeId));
        }

        // First try tapping to gain focus
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);

        // If clear_first, select all and delete
        if (clearFirst) {
            clearNodeText(targetNode);
        }

        // Strategy 1: try ACTION_SET_TEXT (standard approach)
        // Note: ACTION_SET_TEXT overwrites existing text; for append mode we must concatenate
        if (trySetTextWithRetries(targetNode, text, clearFirst)) {
            return ToolResult.success(clearFirst ? "Input text: " + text : "Appended text: " + text);
        }

        // Strategy 2: paste via clipboard (better compatibility)
        boolean clipboardSet = setClipboardText(service, text);
        if (!clipboardSet) {
            return ToolResult.error("Failed to set clipboard text");
        }

        targetNode = waitForTargetEditable(service, targetCoords);
        if (targetNode == null) {
            return ToolResult.error("Failed to recover text field before clipboard paste");
        }

        if (clearFirst) {
            // Clear again (some apps may not have fully cleared after strategy 1 failed)
            clearNodeText(targetNode);
        } else {
            // Append mode: move cursor to end
            CharSequence existing = targetNode.getText();
            int end = existing != null ? existing.length() : 0;
            Bundle cursorArgs = new Bundle();
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end);
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs);
        }

        // Perform paste
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            return ToolResult.success(clearFirst ? "Input text (via paste): " + text : "Appended text (via paste): " + text);
        }

        return ToolResult.error("Failed to input text, both ACTION_SET_TEXT and clipboard paste failed");
    }

    /**
     * Clear input field: select all → delete
     */
    private void clearNodeText(AccessibilityNodeInfo node) {
        // Select all
        Bundle selectAllArgs = new Bundle();
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Integer.MAX_VALUE);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs);

        // Overwrite selection with empty string
        Bundle clearArgs = new Bundle();
        clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
    }

    private boolean trySetTextWithRetries(AccessibilityNodeInfo node, String text, boolean clearFirst) {
        for (int attempt = 0; attempt < 3; attempt++) {
            CharSequence existing = node.getText();
            String candidateText = clearFirst ? text : ((existing != null ? existing.toString() : "") + text);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, candidateText);
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private AccessibilityNodeInfo waitForTargetEditable(ClawAccessibilityService service, int[] targetCoords) {
        for (int attempt = 0; attempt < 5; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                sleepShort();
                continue;
            }

            AccessibilityNodeInfo focused = findFocusedEditText(root);
            if (focused != null) {
                return focused;
            }

            if (targetCoords != null) {
                AccessibilityNodeInfo nearTarget = findEditableNearPoint(root, targetCoords[0], targetCoords[1]);
                if (nearTarget != null) {
                    return nearTarget;
                }
            }

            sleepShort();
        }
        return null;
    }

    private boolean setClipboardText(Context context, String text) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = {false};

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("input_text", text));
                    result[0] = true;
                }
            } catch (Exception ignored) {
            }
            latch.countDown();
        });

        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    private AccessibilityNodeInfo findFocusedEditText(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) {
            return focused;
        }
        // Fallback: find first editable node
        return findFirstEditable(root);
    }

    private AccessibilityNodeInfo findEditableNearPoint(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        boolean editable = node.isEditable();
        CharSequence className = node.getClassName();
        boolean isEditText = className != null && className.toString().contains("EditText");
        if ((editable || isEditText) && bounds.contains(x, y)) {
            return node;
        }

        AccessibilityNodeInfo best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo candidate = findEditableNearPoint(child, x, y);
            if (candidate != null) {
                Rect candidateBounds = new Rect();
                candidate.getBoundsInScreen(candidateBounds);
                int dx = candidateBounds.centerX() - x;
                int dy = candidateBounds.centerY() - y;
                int distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = findFirstEditable(child);
            if (result != null) {
                // Don't recycle child if it's the result itself
                if (result != child) {
                    child.recycle();
                }
                return result;
            }
            child.recycle();
        }
        return null;
    }

    private void sleepShort() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
