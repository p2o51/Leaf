package dev.chenwuyi.leaf.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.chenwuyi.leaf.R

val GoogleSansFlex = FontFamily(
    Font(R.font.google_sans_flex_400, FontWeight.Normal),
    Font(R.font.google_sans_flex_500, FontWeight.Medium),
    Font(R.font.google_sans_flex_600, FontWeight.SemiBold),
    Font(R.font.google_sans_flex_700, FontWeight.Bold),
)

// Reading text (card previews, the editor) uses the system serif so Chinese gets Noto Serif CJK.
val ReadingFamily = FontFamily.Serif

private val base = Typography()

val LeafTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = GoogleSansFlex),
    bodyLarge = base.bodyLarge.copy(fontFamily = GoogleSansFlex),
    bodyMedium = base.bodyMedium.copy(fontFamily = GoogleSansFlex),
    bodySmall = base.bodySmall.copy(fontFamily = GoogleSansFlex),
    labelLarge = base.labelLarge.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = GoogleSansFlex),
    labelSmall = base.labelSmall.copy(fontFamily = GoogleSansFlex),
)

val EssayTitleStyle = TextStyle(fontFamily = ReadingFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
val EssayPreviewStyle = TextStyle(fontFamily = ReadingFamily, fontSize = 13.5.sp, lineHeight = 22.sp)
val EditorTitleStyle = TextStyle(fontFamily = ReadingFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 38.sp)
val EditorBodyStyle = TextStyle(fontFamily = ReadingFamily, fontSize = 18.sp, lineHeight = 32.sp)
