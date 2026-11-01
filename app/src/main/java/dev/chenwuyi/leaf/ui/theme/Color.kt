package dev.chenwuyi.leaf.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.chenwuyi.leaf.data.NotionColor

// Paperleaf: the same warm paper and ink as Sheaf; Leaf's primary is leaf green and the brand
// ink blue steps back to tertiary. Secondary, error and outline are spelled out so nothing falls
// back to Material's baseline purple.
internal val LightPrimary = Color(0xFF2F6B00)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFC7F2A4)
internal val LightOnPrimaryContainer = Color(0xFF0A2100)
internal val LightSecondary = Color(0xFF57624A)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFE6E3F3)
internal val LightOnSecondaryContainer = Color(0xFF1D1B30)
internal val LightTertiary = Color(0xFF3A3FCB)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFDFE0FF)
internal val LightOnTertiaryContainer = Color(0xFF191C7A)
internal val LightError = Color(0xFFB3261E)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFF9DEDC)
internal val LightOnErrorContainer = Color(0xFF410E0B)
internal val LightSurface = Color(0xFFFBF9F4)
internal val LightSurfaceLowest = Color(0xFFFBF9F4)
internal val LightSurfaceLow = Color(0xFFF5F2EB)
internal val LightSurfaceContainer = Color(0xFFEFEBE3)
internal val LightSurfaceHigh = Color(0xFFE8E4DB)
internal val LightSurfaceHighest = Color(0xFFE1DCD2)
internal val LightOnSurface = Color(0xFF1C1B18)
internal val LightOnSurfaceVariant = Color(0xFF5C5850)
internal val LightOutline = Color(0xFF9A9488)
internal val LightOutlineVariant = Color(0xFFD9D3C7)
// Ink at 32%, as the brand book puts the scrim behind sheets.
internal val LightScrim = Color(0x521C1B18)

internal val DarkPrimary = Color(0xFF95D868)
internal val DarkOnPrimary = Color(0xFF0A2100)
internal val DarkPrimaryContainer = Color(0xFF225100)
internal val DarkOnPrimaryContainer = Color(0xFFC7F2A4)
internal val DarkSecondary = Color(0xFFBFCBAD)
internal val DarkOnSecondary = Color(0xFF29341F)
internal val DarkSecondaryContainer = Color(0xFF3A3950)
internal val DarkOnSecondaryContainer = Color(0xFFE3E1F9)
internal val DarkTertiary = Color(0xFFBFC2FF)
internal val DarkOnTertiary = Color(0xFF1E228E)
internal val DarkTertiaryContainer = Color(0xFF2E33A8)
internal val DarkOnTertiaryContainer = Color(0xFFE0E0FF)
internal val DarkError = Color(0xFFF2B8B5)
internal val DarkOnError = Color(0xFF601410)
internal val DarkErrorContainer = Color(0xFF8C1D18)
internal val DarkOnErrorContainer = Color(0xFFF9DEDC)
internal val DarkSurface = Color(0xFF141311)
internal val DarkSurfaceLowest = Color(0xFF141311)
internal val DarkSurfaceLow = Color(0xFF1A1916)
internal val DarkSurfaceContainer = Color(0xFF211F1B)
internal val DarkSurfaceHigh = Color(0xFF2A2824)
internal val DarkSurfaceHighest = Color(0xFF34312C)
internal val DarkOnSurface = Color(0xFFEAE6DD)
internal val DarkOnSurfaceVariant = Color(0xFFB3AEA3)
internal val DarkOutline = Color(0xFF77726A)
internal val DarkOutlineVariant = Color(0xFF3A3731)
// Black at 48%: dark paper needs a heavier scrim to read as a layer.
internal val DarkScrim = Color(0x7A000000)

// Paperleaf's lime, the highlighter: outside Material's roles, so it rides in LeafAccents below.
internal val LightLime = Color(0xFFD2F55B)
internal val LightOnLime = Color(0xFF1F2B06)
internal val DarkLime = Color(0xFFC9EC4E)
internal val DarkOnLime = Color(0xFF1A2404)

/**
 * The colours Leaf needs beyond Material's scheme. [lime] marks the one most important action on a
 * screen (Leaf's +, at home and in the editor alike) and nothing else; [onLime] is its icon.
 */
@Immutable
data class LeafAccents(val lime: Color, val onLime: Color)

internal val LightAccents = LeafAccents(lime = LightLime, onLime = LightOnLime)
internal val DarkAccents = LeafAccents(lime = DarkLime, onLime = DarkOnLime)

val LocalLeafAccents = staticCompositionLocalOf { LightAccents }

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
        // A step darker than Notion's own grey, which vanished on paper-container (#EFEBE3) in sheets and menus.
        NotionColor.Gray to Color(0xFFDFDDD8),
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
        // A step lighter than 0xFF3A3935, which all but vanished on the dark drawer and sheets (paper-container #211F1B).
        NotionColor.Gray to Color(0xFF4A4843),
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
