package dev.chenwuyi.leaf.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Pixel's segmented list (Android 16), the same measures as Sheaf's settings: round outside, tight between rows. */
object Segments {
    val Outer = 20.dp
    val Inner = 4.dp
    val Gap = 2.dp
}

@Immutable
internal data class SegmentSpan(val top: Int, val bottom: Int)

/**
 * A group's rows as a segmented list, ported from Sheaf's SegmentedColumn so the two apps' settings are
 * one design: each child is its own segment on [color], [Segments.Gap] apart, with no dividers. A child
 * that lays out at zero height takes no segment and no gap. Rows are clipped to their segment, so a
 * pressed row's ripple stops at its own edge.
 */
@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    var spans by remember { mutableStateOf(emptyList<SegmentSpan>()) }
    Layout(
        content = content,
        modifier = modifier.drawWithContent {
            val path = segmentPath(spans, size.width, Segments.Outer.toPx(), Segments.Inner.toPx())
            drawPath(path, color)
            clipPath(path) { this@drawWithContent.drawContent() }
        },
    ) { measurables, constraints ->
        val gap = Segments.Gap.roundToPx()
        val rows = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else rows.maxOfOrNull { it.width } ?: 0
        val laid = ArrayList<SegmentSpan>(rows.size)
        var y = 0
        rows.forEach { row ->
            if (row.height <= 0) return@forEach
            if (laid.isNotEmpty()) y += gap
            laid += SegmentSpan(y, y + row.height)
            y += row.height
        }
        layout(width, y.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var next = 0
            rows.forEach { row ->
                if (row.height <= 0) {
                    row.place(0, laid.getOrNull(next)?.top ?: y)
                } else {
                    row.place(0, laid[next].top)
                    next++
                }
            }
            // Read only while drawing; equal spans write nothing, so a steady layout never redraws for this.
            spans = laid
        }
    }
}

internal fun segmentPath(spans: List<SegmentSpan>, width: Float, outer: Float, inner: Float): Path {
    val path = Path()
    spans.forEachIndexed { index, span ->
        val top = CornerRadius(if (index == 0) outer else inner)
        val bottom = CornerRadius(if (index == spans.lastIndex) outer else inner)
        path.addRoundRect(
            RoundRect(
                left = 0f,
                top = span.top.toFloat(),
                right = width,
                bottom = span.bottom.toFloat(),
                topLeftCornerRadius = top,
                topRightCornerRadius = top,
                bottomRightCornerRadius = bottom,
                bottomLeftCornerRadius = bottom,
            ),
        )
    }
    return path
}
