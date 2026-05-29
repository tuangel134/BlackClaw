package com.blackclaw.android.tool.impl;

import android.graphics.Bitmap;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;
import com.blackclaw.android.service.ClawAccessibilityService;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class TakeScreenshotTool extends BaseTool {

    @Override
    public String getName() {
        return "take_screenshot";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_screenshot);
    }

    @Override
    public String getDescriptionEN() {
        return "Take a screenshot of the current screen. Returns the local file path of the saved PNG image. Requires Android 11+ (API 30).";
    }

    @Override
    public String getDescriptionCN() {
        return "Take a screenshot of the current screen, save it as a PNG file and return the local file path. Requires Android 11+ (API 30).";
    }

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

        Bitmap bitmap = service.takeScreenshot(5000);
        if (bitmap == null) {
            return ToolResult.error("Failed to take screenshot. Requires Android 11+ (API 30).");
        }

        try {
            Bitmap softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (softBitmap != null) {
                bitmap.recycle();
                bitmap = softBitmap;
            }

            File dir = new File(ClawApplication.Companion.getInstance().getCacheDir(), "screenshots");
            if (!dir.exists()) dir.mkdirs();

            String filename = System.currentTimeMillis() + ".png";
            File file = new File(dir, filename);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bitmap.recycle();

            return ToolResult.success(file.getAbsolutePath());
        } catch (Exception e) {
            bitmap.recycle();
            return ToolResult.error("Failed to save screenshot: " + e.getMessage());
        }
    }
}
