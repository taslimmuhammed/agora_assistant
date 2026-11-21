package com.local.assistant.llm

/**
 * A call to one of the phone's tools, as Luna's server sends it during a live call
 * ([com.local.assistant.live.LiveToolBridge]) or as the app makes it itself for a plain command
 * ([com.local.assistant.memory.tools.InstantCommands]).
 */
data class ToolCall(val name: String, val arguments: Map<String, Any?>)
