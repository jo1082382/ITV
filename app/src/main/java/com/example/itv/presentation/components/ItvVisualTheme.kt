package com.example.itv.presentation.components

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.itv.infrastructure.theme.UserThemeStore

val LocalDarkTheme = compositionLocalOf { false }

val ItvGreenLight = Color(0xFF237A4B)
val ItvGreenDark = Color(0xFF5ED38C)

val ItvMintLight = Color(0xFFF3EFE7)
val ItvMintDark = Color(0xFF26342C)

val ItvLineLight = Color(0xFFCFC5B6)
val ItvLineDark = Color(0xFF3E5446)

val ItvCardLight = Color(0xFFFFFFFF)
val ItvCardDark = Color(0xFF1D2721)

val ItvInsetLight = Color(0xFFF5F1E9)
val ItvInsetDark = Color(0xFF141C17)

val ItvGreen: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) ItvGreenDark else ItvGreenLight

val ItvMint: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) ItvMintDark else ItvMintLight

val ItvLine: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) ItvLineDark else ItvLineLight

val ItvCardSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) ItvCardDark else ItvCardLight

val ItvInsetSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) ItvInsetDark else ItvInsetLight

private val LightColorScheme = lightColorScheme(
    primary = ItvGreenLight,
    onPrimary = Color.White,
    secondary = Color(0xFF4B6D54),
    onSecondary = Color.White,
    background = Color(0xFFEAE4DA),
    onBackground = Color(0xFF241E19),
    surface = ItvCardLight,
    onSurface = Color(0xFF241E19),
    surfaceVariant = ItvMintLight,
    onSurfaceVariant = Color(0xFF61564B),
    outline = ItvLineLight,
    outlineVariant = Color(0xFFDED6C8),
    primaryContainer = Color(0xFFDCF0E2),
    onPrimaryContainer = Color(0xFF134025)
)

private val DarkColorScheme = darkColorScheme(
    primary = ItvGreenDark,
    onPrimary = Color(0xFF0A1F12),
    secondary = Color(0xFF90B598),
    onSecondary = Color(0xFF003816),
    background = Color(0xFF0B0F0D),
    onBackground = Color(0xFFE6EFE8),
    surface = ItvCardDark,
    onSurface = Color(0xFFE6EFE8),
    surfaceVariant = ItvMintDark,
    onSurfaceVariant = Color(0xFFB5C7BA),
    outline = ItvLineDark,
    outlineVariant = Color(0xFF314438),
    primaryContainer = Color(0xFF1D472D),
    onPrimaryContainer = Color(0xFFA8F7C0)
)

@Composable
fun ItvVisualTheme(
    darkTheme: Boolean = UserThemeStore.rememberDarkMode(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = MaterialTheme.typography,
            content = content
        )
    }
}

// Original vector mascot inspired by the reference; no screenshot or remote asset.
@Composable
fun SproutMascot(modifier: Modifier = Modifier) {
    Canvas(modifier.size(92.dp).semantics { contentDescription = "웃는 새싹 캐릭터" }) {
        val w = size.width; val h = size.height
        drawOval(Color(0xFFE9EEE8), Offset(w*.19f,h*.87f),Size(w*.62f,h*.08f))
        drawLine(ItvGreenLight,Offset(w*.5f,h*.28f),Offset(w*.5f,h*.06f),w*.035f)
        drawOval(Color(0xFF75B843),Offset(w*.27f,h*.07f),Size(w*.23f,h*.10f))
        drawOval(Color(0xFF5A9F32),Offset(w*.5f,h*.10f),Size(w*.23f,h*.10f))
        drawOval(Color(0xFFDDF4B9),Offset(w*.12f,h*.24f),Size(w*.76f,h*.64f))
        drawOval(Color(0xFF5A9F42),Offset(w*.12f,h*.24f),Size(w*.76f,h*.64f),style=Stroke(w*.025f))
        drawCircle(Color(0xFF315F31),w*.046f,Offset(w*.36f,h*.53f))
        drawCircle(Color(0xFF315F31),w*.046f,Offset(w*.64f,h*.53f))
        drawCircle(Color(0xFFF0B8A0),w*.055f,Offset(w*.27f,h*.65f))
        drawCircle(Color(0xFFF0B8A0),w*.055f,Offset(w*.73f,h*.65f))
        drawArc(Color(0xFF315F31),0f,180f,false,Offset(w*.4f,h*.61f),Size(w*.2f,h*.12f),style=Stroke(w*.028f))
    }
}
