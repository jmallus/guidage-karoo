package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize

/**
 * Enveloppe Glance minimale : le champ est dessiné dans un bitmap puis affiché en pleine surface.
 *
 * [cadrage] vaut Fit par défaut, sans danger pour un champ transparent : la marge qu'il laisse
 * quand la place réelle diffère de celle annoncée ne se voit pas. Un champ qui pose son propre
 * fond demande FillBounds, sans quoi cette marge apparaît en bandes de part et d'autre.
 */
@Composable
fun BitmapField(bitmap: Bitmap, cadrage: ContentScale = ContentScale.Fit) {
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = cadrage,
        )
    }
}
