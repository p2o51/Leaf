package dev.chenwuyi.leaf.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.chenwuyi.leaf.data.NotionColor

// Lime + indigo, shared with Sheaf's palette (prototype v3).
internal val LightPrimary = Color(0xFF2F6B00)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFC6F36A)
internal val LightOnPrimaryContainer = Color(0xFF102000)
internal val LightTertiary = Color(0xFF3A3FCB)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFDFE0FF)
internal val LightOnTertiaryContainer = Color(0xFF14176B)
internal val LightSurface = Color(0xFFFCFDF6)
internal val LightSurfaceLow = Color(0xFFF3F7E7)
internal val LightSurfaceContainer = Color(0xFFEBF2D9)
internal val LightSurfaceHigh = Color(0xFFE2ECC9)
internal val LightSurfaceHighest = Color(0xFFD5E5B2)
internal val LightOnSurface = Color(0xFF17200E)
internal val LightOnSurfaceVariant = Color(0xFF56624A)
internal val LightScrim = Color(0x66101808)

internal val DarkPrimary = Color(0xFFA9D650)
internal val DarkOnPrimary = Color(0xFF1A3700)
internal val DarkPrimaryContainer = Color(0xFF3E6B00)
internal val DarkOnPrimaryContainer = Color(0xFFD9FF8F)
internal val DarkTertiary = Color(0xFFBEC2FF)
internal val DarkOnTertiary = Color(0xFF1E2187)
internal val DarkTertiaryContainer = Color(0xFF353AA6)
internal val DarkOnTertiaryContainer = Color(0xFFE0E0FF)
internal val DarkSurface = Color(0xFF12160D)
internal val DarkSurfaceLow = Color(0xFF1A1F14)
internal val DarkSurfaceContainer = Color(0xFF1E2418)
internal val DarkSurfaceHigh = Color(0xFF282F21)
internal val DarkSurfaceHighest = Color(0xFF33392B)
internal val DarkOnSurface = Color(0xFFE2E6D6)
internal val DarkOnSurfaceVariant = Color(0xFFC2C9B4)
internal val DarkScrim = Color(0x99000000)

/**
 * Notion's nine colours as Leaf draws them: same hues, but toned per theme so dark mode stays
 * calm. Meaning (which Notion colour) never changes, only the shade.
 */
@Immutable
data class NotionTones(val fills: Map<NotionColor, Color>, val defaultFill: Color, val ring: Color, val near: Color) {
    fun fill(c: NotionColor): Color = fills[c] ?: defaultFill
}

internal val LightTones = NotionTones(
    fills = mapOf(
        NotionColor.Gray to Color(0xFFECEAE5),
        NotionColor.Brown to Color(0xFFF3DDC9),
        NotionColor.Orange to Color(0xFFFFD3AE),
        NotionColor.Yellow to Color(0xFFFFEC8A),
        NotionColor.Green to Color(0xFFC9F0B8),
        NotionColor.Blue to Color(0xFFC3E0FF),
        NotionColor.Purple to Color(0xFFE2D0FF),
        NotionColor.Pink to Color(0xFFFFCCE3),
        NotionColor.Red to Color(0xFFFFCDC4),
    ),
    defaultFill = LightSurfaceHigh,
    ring = LightTertiary,
    near = Color(0xFF9DA1EE),
)

internal val DarkTones = NotionTones(
    fills = mapOf(
        NotionColor.Gray to Color(0xFF3A3935),
        NotionColor.Brown to Color(0xFF4A3528),
        NotionColor.Orange to Color(0xFF5A3A1E),
        NotionColor.Yellow to Color(0xFF4F4614),
        NotionColor.Green to Color(0xFF25452A),
        NotionColor.Blue to Color(0xFF1F3A57),
        NotionColor.Purple to Color(0xFF3B2E5C),
        NotionColor.Pink to Color(0xFF55283F),
        NotionColor.Red to Color(0xFF5A2A24),
    ),
    defaultFill = DarkSurfaceHigh,
    ring = DarkTertiary,
    near = Color(0xFF6E72C8),
)

val LocalNotionTones = staticCompositionLocalOf { LightTones }
