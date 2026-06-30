package com.blackclaw.android.tool.impl

import com.blackclaw.android.service.ClawAccessibilityService
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.XLog

/**
 * Visual verification tool — lets the agent confirm that an expected result
 * actually appeared on screen after an action, instead of assuming success.
 *
 * Checks the accessibility tree first (fast, free); if the text isn't found
 * there, falls back to OCR over a screen capture (catches games / canvas /
 * SurfaceView where the a11y tree is empty).
 *
 * This is the key to reducing silent failures: the agent can call
 * verify_screen(expect="Mensaje enviado") and KNOW whether it worked.
 */
class VerifyScreenTool : BaseTool() {
    override fun getName() = "verify_screen"
    override fun getDisplayName() = "Verificar pantalla"
    override fun getDescriptionEN() =
        "Verify that expected text is (or is NOT) currently on screen. Checks the accessibility " +
        "tree, then OCR as fallback. Use after a critical action to confirm it worked — e.g. " +
        "verify_screen(expect='Enviado') after sending a message. Returns FOUND or NOT_FOUND."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "confirma que un texto esperado está (o no) en pantalla tras una acción"
    override fun getParameters() = listOf(
        ToolParameter("expect", "string", "Text that should be present (substring, case-insensitive).", true),
        ToolParameter("should_be_absent", "boolean", "If true, success means the text is NOT present. Default false.", false),
        ToolParameter("use_ocr", "boolean", "Also check via OCR if not found in a11y tree. Default true.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val expect = requireString(params, "expect").trim()
        if (expect.isEmpty()) return ToolResult.error("'expect' vacío.")
        val shouldBeAbsent = optionalBoolean(params, "should_be_absent", false)
        val useOcr = optionalBoolean(params, "use_ocr", true)
        val needle = expect.lowercase()

        // 1. Check accessibility tree
        var foundInTree = false
        val service = requireAccessibilityService()
        if (service != null) {
            val tree = runCatching { service.getScreenTree() }.getOrNull()
            if (tree != null && tree.lowercase().contains(needle)) {
                foundInTree = true
            }
        }

        // 2. OCR fallback if not found and allowed
        var foundInOcr = false
        if (!foundInTree && useOcr) {
            runCatching {
                val ocrTool = ToolRegistry.getInstance().getTool("read_screen_ocr")
                val ocrResult = ocrTool?.execute(emptyMap())
                if (ocrResult?.isSuccess == true && ocrResult.data?.lowercase()?.contains(needle) == true) {
                    foundInOcr = true
                }
            }
        }

        val found = foundInTree || foundInOcr
        val source = when {
            foundInTree -> "a11y tree"
            foundInOcr -> "OCR"
            else -> "ninguno"
        }

        return if (shouldBeAbsent) {
            if (!found) ToolResult.success("✓ Verificado: '$expect' NO está en pantalla (correcto).")
            else ToolResult.error("✗ '$expect' SÍ aparece en pantalla (se esperaba ausente, vía $source).")
        } else {
            if (found) ToolResult.success("✓ FOUND: '$expect' está en pantalla (vía $source).")
            else ToolResult.error("✗ NOT_FOUND: '$expect' no aparece en pantalla. La acción podría no haber funcionado; revisa get_screen_info y reintenta.")
        }
    }
}
