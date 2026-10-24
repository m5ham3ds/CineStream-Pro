package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private fun getDarkColorScheme(primaryColorInt: Int) = darkColorScheme(
    primary = when (primaryColorInt) {
        1 -> PrimaryBlue
        2 -> PrimaryGreen
        3 -> PrimaryPurple
        4 -> PrimaryYellow
        else -> PrimaryRed
    },
    secondary = when (primaryColorInt) {
        1 -> PrimaryBlue
        2 -> PrimaryGreen
        3 -> PrimaryPurple
        4 -> PrimaryYellow
        else -> PrimaryRedVariant
    },
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceDarkVariant,
    onPrimary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = Color(0xFF666666)
)

private fun getLightColorScheme(primaryColorInt: Int) = lightColorScheme(
    primary = when (primaryColorInt) {
        1 -> PrimaryBlue
        2 -> PrimaryGreen
        3 -> PrimaryPurple
        4 -> PrimaryYellow
        else -> PrimaryRed
    },
    secondary = when (primaryColorInt) {
        1 -> PrimaryBlue
        2 -> PrimaryGreen
        3 -> PrimaryPurple
        4 -> PrimaryYellow
        else -> PrimaryRedVariant
    },
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = Color(0xFFE5E5E5),
    onPrimary = Color.White,
    onBackground = Color(0xFF1E1E1E),
    onSurface = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFF666666)
)

@Composable
fun MyApplicationTheme(
    themeMode: Int = 0, // 0=System, 1=Light, 2=Dark
    primaryColor: Int = 0,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> getDarkColorScheme(primaryColor)
        else -> getLightColorScheme(primaryColor)
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}