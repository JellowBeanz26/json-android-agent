package com.jellowbeanz.json.ui.theme

import androidx.compose.ui.graphics.Color

// Dark (primary) — warm charcoal with a terracotta accent (editorial, high-end)
val DarkBackground = Color(0xFF1C1A17)
val DarkSurface = Color(0xFF26231F)
val DarkSurfaceElevated = Color(0xFF2E2A25)
val DarkBorder = Color(0xFF3A352F)
val DarkOnBackground = Color(0xFFECE7DE)
val DarkOnMuted = Color(0xFFA69E90)

// Light — warm cream
val LightBackground = Color(0xFFFAF7F2)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceElevated = Color(0xFFF3EEE6)
val LightBorder = Color(0xFFE7E0D5)
val LightOnBackground = Color(0xFF201D19)
val LightOnMuted = Color(0xFF6C6458)

// Shared accent (the default)
val Accent = Color(0xFFD97757)
val OnAccent = Color(0xFF1C1A17)

/** A selectable accent (the app's primary colour) with a matching on-colour for contrast. */
data class AccentOption(val id: String, val label: String, val color: Color, val onColor: Color)

/** The accent palette; the first entry is the default. Colours read well on both the cream and charcoal themes. */
val AccentOptions = listOf(
    AccentOption("terracotta", "Terracotta", Accent, OnAccent),
    AccentOption("purple", "Purple", Color(0xFF8B6DF0), Color(0xFFFFFFFF)),
    AccentOption("blue", "Blue", Color(0xFF4C8DF6), Color(0xFFFFFFFF)),
    AccentOption("teal", "Teal", Color(0xFF2FB0A6), Color(0xFF07201E)),
    AccentOption("green", "Green", Color(0xFF46A96A), Color(0xFF06160D)),
    AccentOption("rose", "Rose", Color(0xFFE86F9E), Color(0xFF2A0E19)),
    AccentOption("amber", "Amber", Color(0xFFE0A33E), Color(0xFF1C1A17)),
)

const val DEFAULT_ACCENT = "terracotta"

fun accentById(id: String): AccentOption = AccentOptions.firstOrNull { it.id == id } ?: AccentOptions.first()
