package com.blackclaw.android.ui.chat

import androidx.compose.ui.graphics.Color

// ======================== THEME COLORS ========================

data class BlackClawColors(
    val background: Color,
    val surface: Color,
    val userBubble: Color,
    val userText: Color,
    val aiBubble: Color,
    val aiBubbleBorder: Color,
    val aiText: Color,
    val avatar: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val inputBorder: Color,
)

val AbyssDark = BlackClawColors(
    background = Color(0xFF0A0A0F),
    surface = Color(0xFF0F0F1A),
    userBubble = Color(0xFF0066CC),
    userText = Color(0xFFF0F8FF),
    aiBubble = Color(0xFF141420),
    aiBubbleBorder = Color(0xFF1E1E30),
    aiText = Color(0xFFC8D0E8),
    avatar = Color(0xFF0055AA),
    accent = Color(0xFF00D4FF),
    textPrimary = Color(0xFFC8D0E8),
    textSecondary = Color(0xFF7A80A0),
    textTertiary = Color(0xFF3A3A55),
    divider = Color(0xFF0F0F1A),
    inputBorder = Color(0xFF1E1E30),
)
