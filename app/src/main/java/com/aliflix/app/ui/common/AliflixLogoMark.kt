package com.aliflix.app.ui.common

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import com.aliflix.app.R
import org.xmlpull.v1.XmlPullParser

/** All renderers read the same vector, including its measured colors and rounded outlines. */
class AliflixLogoGeometry private constructor(private val layers: List<Pair<Path, Color>>) {
    private val silhouette = layers.map { it.first }.reduce { a, b ->
        Path.combine(PathOperation.Union, a, b)
    }

    fun createCombinedLogoPath(unit: Float, left: Float = 0f, top: Float = 0f): Path =
        Path().apply {
            addPath(silhouette)
            transform(Matrix().apply { scale(unit / VIEWBOX_SIZE, unit / VIEWBOX_SIZE) })
            translate(Offset(left, top))
        }

    fun draw(scope: DrawScope, unit: Float, left: Float, top: Float) = with(scope) {
        withTransform({
            translate(left, top)
            scale(unit / VIEWBOX_SIZE, unit / VIEWBOX_SIZE, pivot = Offset.Zero)
        }) {
            layers.forEach { (path, color) -> drawPath(path, color) }
        }
    }

    companion object {
        const val VIEWBOX_SIZE = 100f
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
        @Volatile private var cached: AliflixLogoGeometry? = null

        @Synchronized fun load(resources: Resources): AliflixLogoGeometry {
            cached?.let { return it }
            val layers = mutableListOf<Pair<Path, Color>>()
            resources.getXml(R.drawable.aliflix_logo).use { xml ->
                while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG && xml.name == "path") {
                        val path = PathParser().parsePathString(xml.getAttributeValue(ANDROID, "pathData")).toPath()
                        val color = Color(android.graphics.Color.parseColor(xml.getAttributeValue(ANDROID, "fillColor")))
                        layers += path to color
                    }
                    xml.next()
                }
            }
            return AliflixLogoGeometry(layers).also { cached = it }
        }
    }
}

@Composable
fun AliflixLogoMark(modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val geometry = remember(resources) { AliflixLogoGeometry.load(resources) }
    Canvas(modifier) {
        val unit = minOf(size.width, size.height)
        geometry.draw(this, unit, (size.width - unit) / 2f, (size.height - unit) / 2f)
    }
}
