package com.blackclaw.android.tool.impl;

import android.os.Build;
import android.os.Environment;

import androidx.core.content.ContextCompat;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.ClawApplicationKt;
import com.blackclaw.android.R;
import com.blackclaw.android.channel.Channel;
import com.blackclaw.android.channel.ChannelManager;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SendFileTool extends BaseTool {

    @Override
    public String getName() {
        return "send_file";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_send_file);
    }

    @Override
    public String getDescriptionEN() {
        return "Send a file from the device to the user through the current message channel. Provide the absolute file path on the device.";
    }

    @Override
    public String getDescriptionCN() {
        return "Send a file on the device to the user. The absolute path of the file must be provided.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("file_path", "string", "Absolute path of the file to send", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        android.content.Context ctx = ClawApplication.Companion.getInstance();
        // Check storage permission
        if (!hasStoragePermission()) {
            return ToolResult.error(ctx.getString(R.string.tool_no_storage_permission));
        }

        String filePath = requireString(params, "file_path");
        File file = new File(filePath);

        if (!file.exists()) {
            return ToolResult.error(ctx.getString(R.string.tool_file_not_found, filePath));
        }
        if (!file.isFile()) {
            return ToolResult.error(ctx.getString(R.string.tool_not_a_file, filePath));
        }
        if (!file.canRead()) {
            return ToolResult.error(ctx.getString(R.string.tool_no_storage_permission));
        }

        // Get current task channel and message ID
        Channel channel = ClawApplicationKt.getAppViewModel().getInProgressTaskChannel();
        String messageId = ClawApplicationKt.getAppViewModel().getInProgressTaskMessageId();

        if (channel == null || messageId.isEmpty()) {
            return ToolResult.error(ctx.getString(R.string.tool_no_task_channel));
        }

        try {
            ChannelManager.sendFile(channel, file, messageId);
            return ToolResult.success(ctx.getString(R.string.tool_file_sent, file.getName()));
        } catch (Exception e) {
            return ToolResult.error(ctx.getString(R.string.tool_file_send_failed, e.getMessage()));
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(
                    ClawApplication.Companion.getInstance(),
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }
}
