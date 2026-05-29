package com.blackclaw.android.utils;

import android.graphics.Rect;

/**
 * Shared heuristics for skipping non-message labels in chat UIs without hard-coding one language.
 */
public final class ChatNoiseFilterUtils {
    private ChatNoiseFilterUtils() {}

    public static boolean isLikelyNonMessageLabel(Rect bounds, Rect rootBounds, String text) {
        if (bounds == null || rootBounds == null) return text == null || text.trim().isEmpty();
        return isLikelyNonMessageLabel(
                bounds.left, bounds.top, bounds.right, bounds.bottom,
                rootBounds.left, rootBounds.top, rootBounds.right, rootBounds.bottom,
                text
        );
    }

    public static boolean isLikelyNonMessageLabel(
            int left,
            int top,
            int right,
            int bottom,
            int rootLeft,
            int rootTop,
            int rootRight,
            int rootBottom,
            String text
    ) {
        if (text == null || text.trim().isEmpty()) return true;
        String trimmed = text.trim();
        if (isLikelyTimestampLike(trimmed)) return true;
        return isLikelyCenteredSystemLabel(left, top, right, bottom, rootLeft, rootTop, rootRight, rootBottom, trimmed);
    }

    public static boolean isLikelyTimestampLike(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 14) return false;
        if (!trimmed.matches(".*\\d.*")) return false;
        if (trimmed.matches("^[\\p{L}\\p{Nd}\\s\\.:/-]+$") && trimmed.matches(".*\\d+:\\d+.*")) {
            return true;
        }
        return trimmed.matches("^\\d{1,2}[\\.:/-]\\d{1,2}([\\.:/-]\\d{1,4})?$");
    }

    public static boolean isLikelyCenteredSystemLabel(Rect bounds, Rect rootBounds, String text) {
        if (bounds == null || rootBounds == null) return false;
        return isLikelyCenteredSystemLabel(
                bounds.left, bounds.top, bounds.right, bounds.bottom,
                rootBounds.left, rootBounds.top, rootBounds.right, rootBounds.bottom,
                text
        );
    }

    public static boolean isLikelyCenteredSystemLabel(
            int left,
            int top,
            int right,
            int bottom,
            int rootLeft,
            int rootTop,
            int rootRight,
            int rootBottom,
            String text
    ) {
        int rootWidth = Math.max(0, rootRight - rootLeft);
        if (rootWidth <= 0) return false;

        int centerX = (left + right) / 2;
        int rootCenterX = (rootLeft + rootRight) / 2;
        int width = Math.max(0, right - left);
        int horizontalCenterDelta = Math.abs(centerX - rootCenterX);
        boolean nearCenter = horizontalCenterDelta <= rootWidth * 0.12f;
        boolean shortLabel = text != null && text.trim().length() <= 32;
        boolean narrowPill = width <= rootWidth * 0.55f;
        boolean wideBanner = width >= rootWidth * 0.6f;
        boolean upperHalf = top <= (rootTop + rootBottom) / 2;

        if (nearCenter && shortLabel && narrowPill) {
            return true;
        }
        if (nearCenter && wideBanner && upperHalf) {
            return true;
        }
        return false;
    }
}
