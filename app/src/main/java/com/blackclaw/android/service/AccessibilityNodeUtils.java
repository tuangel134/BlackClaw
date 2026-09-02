package com.blackclaw.android.service;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Stateless accessibility-tree traversal shared by auto-reply flows.
 * Keeping traversal out of AutoReplyManager makes its orchestration easier to audit.
 */
final class AccessibilityNodeUtils {
    private AccessibilityNodeUtils() {}

    static void collectTextNodesInRegion(
        AccessibilityNodeInfo node,
        int minY,
        int maxY,
        List<AccessibilityNodeInfo> result
    ) {
        if (node == null) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.top >= minY && bounds.bottom <= maxY && node.getText() != null) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTextNodesInRegion(child, minY, maxY, result);
        }
    }

    static void collectTopBarNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.top < 300 && (node.getText() != null || node.getContentDescription() != null)) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTopBarNodes(child, result);
        }
    }

    static void collectEditTexts(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        CharSequence className = node.getClassName();
        if (node.isEditable() || (className != null && className.toString().contains("EditText"))) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectEditTexts(child, result);
        }
    }

    static void collectAllTextNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (node.getText() != null && node.getText().length() > 0) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectAllTextNodes(child, result);
        }
    }

    static Rect getBottomEditTextBounds(AccessibilityNodeInfo root) {
        java.util.ArrayList<AccessibilityNodeInfo> editables = new java.util.ArrayList<>();
        collectEditTexts(root, editables);
        AccessibilityNodeInfo bottom = null;
        int bestY = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : editables) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.centerY() > bestY) {
                bestY = bounds.centerY();
                bottom = node;
            }
        }
        if (bottom == null) return null;
        Rect bounds = new Rect();
        bottom.getBoundsInScreen(bounds);
        return bounds;
    }
}
