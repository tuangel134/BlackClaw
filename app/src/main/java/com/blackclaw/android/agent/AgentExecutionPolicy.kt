package com.blackclaw.android.agent

/** Small deterministic execution rules kept out of the main agent loop. */
internal object AgentExecutionPolicy {
    private val actionTools = setOf(
        "phone_click_node", "phone_tap", "phone_swipe", "phone_long_press",
        "tap", "long_press", "swipe", "scroll_to_find",
        "input_text", "type_text", "system_key", "open_app",
        "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
        "volume_up", "volume_down", "press_menu", "press_power",
        "clipboard", "send_file", "repeat_actions", "wait"
    )
    private val fastSettleTools = setOf(
        "input_text", "type_text", "system_key", "clipboard",
        "volume_up", "volume_down", "press_menu",
    )
    private val slowSettleTools = setOf(
        "open_app", "scroll_to_find", "find_and_tap",
    )

    fun isActionTool(toolName: String): Boolean = toolName in actionTools

    fun settleTimeForTool(toolName: String): Long = when {
        toolName in fastSettleTools -> 250L
        toolName in slowSettleTools -> 800L
        else -> 400L
    }
}
