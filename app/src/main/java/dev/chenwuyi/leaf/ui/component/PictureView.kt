package dev.chenwuyi.leaf.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.SampleArt

/** Draws a cover or a photo scribble. Sample art is painted, so demo data needs no image files. */
@Composable
fun PictureView(picture: Picture, modifier: Modifier = Modifier) {
    when (picture) {
        is Picture.Local -> AsyncImage(model = picture.uri, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
        is Picture.Remote -> AsyncImage(model = picture.url, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
        is Picture.Sample -> SampleArtView(picture.art, modifier)
    }
}

@Composable
private fun SampleArtView(art: SampleArt, modifier: Modifier) {
    when (art) {
        SampleArt.Marble -> Canvas(modifier.background(Brush.linearGradient(listOf(Color(0xFF2B59C3), Color(0xFFF4B860), Color(0xFFE2552E))))) {
            drawCircle(Color(0xFFF4B860), size.minDimension * 0.22f, Offset(size.width * 0.25f, size.height * 0.3f))
            drawCircle(Color(0xFFE2552E), size.minDimension * 0.24f, Offset(size.width * 0.68f, size.height * 0.58f))
            drawCircle(Color(0xFF2B59C3), size.minDimension * 0.22f, Offset(size.width * 0.38f, size.height * 0.82f))
            drawCircle(Color(0xFFF6E3A1), size.minDimension * 0.16f, Offset(size.width * 0.86f, size.height * 0.18f))
        }
        SampleArt.Street -> Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFFC9D2DA), Color(0xFF6E8B5E), Color(0xFF3B3B3F))))) {
            drawRect(Color(0xFFE2662E), Offset(0f, size.height * 0.5f), size.copy(height = size.height * 0.06f))
            val step = size.width / 8f
            for (i in 0 until 8) {
                drawRect(Color(0xBFFFFFFF), Offset(step * i + step * 0.3f, size.height * 0.7f), size.copy(width = step * 0.35f, height = size.height * 0.08f))
            }
        }
        SampleArt.Moss -> Box(modifier.background(Brush.linearGradient(listOf(Color(0xFFD9EDB2), Color(0xFF9EC27B), Color(0xFF2E4D29)))))
        SampleArt.Dusk -> Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFF1D2B53), Color(0xFF7E2553), Color(0xFFFF8C61))))) {
            drawCircle(Color(0xFFFFC27A), size.minDimension * 0.14f, Offset(size.width * 0.75f, size.height * 0.75f))
        }
        SampleArt.Paper -> Box(modifier.background(Color(0xFFEEE5CF)))
    }
}
