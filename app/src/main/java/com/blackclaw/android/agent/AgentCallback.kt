package com.blackclaw.android.agent

import com.blackclaw.android.tool.ToolResult

interface AgentCallback {
    /**
     * Callback when a new Agent Loop round starts
     * @param round current round number (starts from 1)
     */
    fun onLoopStart(round: Int)
    fun onContent(round: Int, content: String)
    fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String)
    fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult)
    fun onTokenUpdate(status: TokenMonitor.Status) {}
    fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String? = null)
    fun onError(round: Int, error: Exception, totalTokens: Int)
    fun onSystemDialogBlocked(round: Int, totalTokens: Int)
}
