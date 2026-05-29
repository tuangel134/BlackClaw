package com.blackclaw.android.tool.impl

import android.content.Intent
import android.provider.MediaStore
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Opens the system camera (still capture) or video recorder via intent.
 * Capture flow stays in the camera app — we don't try to grab the photo automatically
 * because that requires a result-aware launch and post-processing the agent doesn't need.
 */
class OpenCameraTool : BaseTool() {
    override fun getName() = "open_camera"
    override fun getDisplayName() = "Open Camera"
    override fun getDescriptionEN() =
        "Open the system camera. mode='photo' (default) launches still capture; " +
        "mode='video' launches the video recorder. The user takes the shot manually."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("mode", "string", "photo (default) | video", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val mode = optionalString(params, "mode", "photo").lowercase()
        val action = when (mode) {
            "photo", "" -> MediaStore.ACTION_IMAGE_CAPTURE
            "video", "vid" -> MediaStore.ACTION_VIDEO_CAPTURE
            else -> return ToolResult.error("mode must be 'photo' or 'video'")
        }
        return try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ClawApplication.instance.startActivity(intent)
            ToolResult.success("Camera opened in $mode mode.")
        } catch (e: Exception) {
            ToolResult.error("No camera app available: ${e.message}")
        }
    }
}
