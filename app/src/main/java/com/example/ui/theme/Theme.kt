package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
  darkColorScheme(
    primary = PulseCoral,
    onPrimary = Color.White,
    primaryContainer = PulseCoralContainer,
    onPrimaryContainer = Color(0xFFFFD6C4),
    secondary = PulseMint,
    onSecondary = Color(0xFF04261B),
    secondaryContainer = PulseMintContainer,
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = PulseAmber,
    onTertiary = Color(0xFF2B1B00),
    tertiaryContainer = PulseAmberContainer,
    onTertiaryContainer = Color(0xFFFDE68A),
    background = MidnightBase,
    onBackground = TextPrimaryDark,
    surface = MidnightSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = MidnightSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerHigh = MidnightSurfaceHigh,
    outline = OutlineDark,
    error = Color(0xFFF87171),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = PulseCoralLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE5DB),
    onPrimaryContainer = Color(0xFF4A1403),
    secondary = PulseMintLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF022C22),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    background = DaylightBase,
    onBackground = TextPrimaryLight,
    surface = DaylightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = DaylightSurfaceElevated,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerHigh = Color(0xFFE2E8F0),
    outline = OutlineLight,
    error = Color(0xFFDC2626),
  )

val AppShapes =
  Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
  )

@Composable
fun LiveDeliveryTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = AppShapes,
    content = content,
  )
}
