package com.macle.royaleassist.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The few Material icons the UI uses, copied from material-icons-core 1.7.8 (Filled style, 24 dp,
 * same paths). The icons library is frozen and not published for every Compose Multiplatform
 * version, so the paths live here.
 */
object RoyalIcons {
    val Close: ImageVector by lazy {
        icon("Filled.Close") {
            moveTo(19.0f, 6.41f)
            lineTo(17.59f, 5.0f)
            lineTo(12.0f, 10.59f)
            lineTo(6.41f, 5.0f)
            lineTo(5.0f, 6.41f)
            lineTo(10.59f, 12.0f)
            lineTo(5.0f, 17.59f)
            lineTo(6.41f, 19.0f)
            lineTo(12.0f, 13.41f)
            lineTo(17.59f, 19.0f)
            lineTo(19.0f, 17.59f)
            lineTo(13.41f, 12.0f)
            close()
        }
    }

    val Delete: ImageVector by lazy {
        icon("Filled.Delete") {
            moveTo(6.0f, 19.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(7.0f)
            horizontalLineTo(6.0f)
            verticalLineToRelative(12.0f)
            close()
            moveTo(19.0f, 4.0f)
            horizontalLineToRelative(-3.5f)
            lineToRelative(-1.0f, -1.0f)
            horizontalLineToRelative(-5.0f)
            lineToRelative(-1.0f, 1.0f)
            horizontalLineTo(5.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(14.0f)
            verticalLineTo(4.0f)
            close()
        }
    }

    /** AutoMirrored.Filled.ArrowBack */
    val ArrowBack: ImageVector by lazy {
        icon("AutoMirrored.Filled.ArrowBack", autoMirror = true) {
            moveTo(20.0f, 11.0f)
            horizontalLineTo(7.83f)
            lineToRelative(5.59f, -5.59f)
            lineTo(12.0f, 4.0f)
            lineToRelative(-8.0f, 8.0f)
            lineToRelative(8.0f, 8.0f)
            lineToRelative(1.41f, -1.41f)
            lineTo(7.83f, 13.0f)
            horizontalLineTo(20.0f)
            verticalLineToRelative(-2.0f)
            close()
        }
    }

    /** AutoMirrored.Filled.KeyboardArrowLeft */
    val ChevronLeft: ImageVector by lazy {
        icon("AutoMirrored.Filled.KeyboardArrowLeft", autoMirror = true) {
            moveTo(15.41f, 16.59f)
            lineTo(10.83f, 12.0f)
            lineToRelative(4.58f, -4.59f)
            lineTo(14.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(1.41f, -1.41f)
            close()
        }
    }

    /** AutoMirrored.Filled.KeyboardArrowRight */
    val ChevronRight: ImageVector by lazy {
        icon("AutoMirrored.Filled.KeyboardArrowRight", autoMirror = true) {
            moveTo(8.59f, 16.59f)
            lineTo(13.17f, 12.0f)
            lineTo(8.59f, 7.41f)
            lineTo(10.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(-1.41f, -1.41f)
            close()
        }
    }

    /** Same defaults as material-icons' `materialIcon` + `materialPath`. */
    private fun icon(name: String, autoMirror: Boolean = false, path: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).path(fill = SolidColor(Color.Black), pathBuilder = path).build()
}
