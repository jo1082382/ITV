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

val ItvGreenLight = Color(0xFF2D8050)
val ItvGreenDark = Color(0xFF5ED38C)

val ItvMintLight = Color(0xFFF2F9F2)
val ItvMintDark = Color(0xFF1E2620)

val ItvLineLight = Color(0xFFBFDDC4)
val ItvLineDark = Color(0xFF35463A)

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

private val LightColorScheme = lightColorScheme(
    primary = ItvGreenLight,
    onPrimary = Color.White,
    secondary = Color(0xFF54765B),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF26392D),
    surface = Color.White,
    onSurface = Color(0xFF26392D),
    surfaceVariant = ItvMintLight,
    onSurfaceVariant = Color(0xFF596C5D),
    outline = ItvLineLight,
    primaryContainer = Color(0xFFDFF1DF),
    onPrimaryContainer = Color(0xFF16472A)
)

private val DarkColorScheme = darkColorScheme(
    primary = ItvGreenDark,
    onPrimary = Color(0xFF0D2214),
    secondary = Color(0xFF90B598),
    onSecondary = Color(0xFF003816),
    background = Color(0xFF121513),
    onBackground = Color(0xFFE2EBE4),
    surface = Color(0xFF1A211D),
    onSurface = Color(0xFFE2EBE4),
    surfaceVariant = ItvMintDark,
    onSurfaceVariant = Color(0xFFBFCFBF),
    outline = ItvLineDark,
    primaryContainer = Color(0xFF1D472D),
    onPrimaryContainer = Color(0xFF9FF5BA)
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
