package com.example.itv.presentation.home

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.itv.presentation.components.*

// Presentation only. Edit this file for card spacing, colors and typography.
@Composable
internal fun HomeUsageLayout(
    total: String, instagram: String, reels: String, reelsTransitions: String,
    youtube: String, shorts: String, shortsTransitions: String,
    notice: String?, error: String?, onDeleteShortform: (() -> Unit)? = null, deleting: Boolean = false, message: String? = null
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("오늘 총 사용량", style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(total, style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
        PlatformUsageCard("Instagram", "com.instagram.android", "IG", Color(0xFFB64A83),
            instagram, "릴스", reels, reelsTransitions)
        PlatformUsageCard("YouTube", "com.google.android.youtube", "YT", Color(0xFFD83D3D),
            youtube, "쇼츠", shorts, shortsTransitions)
        onDeleteShortform?.let { action ->
            TextButton(onClick = action, enabled = !deleting) { Text("숏폼 기록 전체 삭제") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun PlatformUsageCard(title: String, packageName: String, fallback: String,
    accent: Color, total: String, shortName: String, shortTime: String, transitions: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, ItvLine),
        colors = CardDefaults.cardColors(containerColor = ItvMint)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InstalledPlatformIcon(packageName, title, fallback, accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("앱 전체 사용량", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(total, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = ItvLine)
            Column(Modifier.fillMaxWidth().padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("앱 안에서 확인된 $shortName", style = MaterialTheme.typography.labelLarge, color = accent)
                Text(shortTime, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("전환 추정 ${transitions}회", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InstalledPlatformIcon(packageName: String, title: String, fallback: String, accent: Color) {
    val context = LocalContext.current
    val bitmap = remember(packageName, context) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val image = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, image.width, image.height)
            drawable.draw(Canvas(image))
            image.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, contentDescription = "$title 앱 아이콘",
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
    else Surface(Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.12f)) {
        Box(contentAlignment = Alignment.Center) {
            Text(fallback, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}
