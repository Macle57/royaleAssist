package com.macle.royaleassist.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "Tower banner" palette: royal navy panels with a gold trim, parchment text, and the elixir
 * magenta reserved for the opponent (their name is drawn in that colour in the game).
 */
object Royal {
    val Night = Color(0xFF0C1830)
    val Navy = Color(0xFF13254A)
    val Slate = Color(0xFF223A66)
    val Gold = Color(0xFFF2C14E)
    val Parchment = Color(0xFFF6EEDC)
    val Mist = Color(0xFF9FB0CF)
    val Elixir = Color(0xFFD046C8)
    val Danger = Color(0xFFFF7A6B)
}

private val Heavy = FontFamily.SansSerif

val RoyalTypography = Typography(
    headlineSmall = TextStyle(fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 22.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = Heavy, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Heavy, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = Heavy, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun RoyalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Royal.Gold,
            onPrimary = Royal.Night,
            secondary = Royal.Elixir,
            background = Royal.Night,
            onBackground = Royal.Parchment,
            surface = Royal.Navy,
            onSurface = Royal.Parchment,
            surfaceVariant = Royal.Slate,
            onSurfaceVariant = Royal.Mist,
            error = Royal.Danger,
        ),
        typography = RoyalTypography,
    ) {
        // Outside a Surface the default content colour is black; make plain Text readable.
        CompositionLocalProvider(LocalContentColor provides Royal.Parchment, content = content)
    }
}
