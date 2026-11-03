package com.example.itv.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val ItvGreen = Color(0xFF2D8050)
val ItvMint = Color(0xFFF2F9F2)
val ItvLine = Color(0xFFBFDDC4)

@Composable
fun ItvVisualTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = ItvGreen, onPrimary = Color.White,
        secondary = Color(0xFF54765B), onSecondary = Color.White,
        background = Color.White, onBackground = Color(0xFF26392D),
        surface = Color.White, onSurface = Color(0xFF26392D),
        surfaceVariant = ItvMint, onSurfaceVariant = Color(0xFF596C5D),
        outline = ItvLine, primaryContainer = Color(0xFFDFF1DF),
        onPrimaryContainer = Color(0xFF16472A)
    ), typography = MaterialTheme.typography, content = content)
}

// Original vector mascot inspired by the reference; no screenshot or remote asset.
@Composable
fun SproutMascot(modifier: Modifier = Modifier) {
    Canvas(modifier.size(92.dp).semantics { contentDescription = "웃는 새싹 캐릭터" }) {
        val w = size.width; val h = size.height
        drawOval(Color(0xFFE9EEE8), Offset(w*.19f,h*.87f),Size(w*.62f,h*.08f))
        drawLine(ItvGreen,Offset(w*.5f,h*.28f),Offset(w*.5f,h*.06f),w*.035f)
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
