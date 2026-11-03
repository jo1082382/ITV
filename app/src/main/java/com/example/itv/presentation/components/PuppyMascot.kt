package com.example.itv.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.itv.R

@Composable
fun PuppyMascot(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.itv_puppy_mascot),
        contentDescription = "고개를 갸웃하며 웃는 크림색 강아지 캐릭터",
        modifier = modifier.size(104.dp),
        contentScale = ContentScale.Fit
    )
}

@Composable
fun PuppyFaceMascot(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.itv_puppy_face),
        contentDescription = "정면을 바라보는 강아지 얼굴",
        modifier = modifier.size(104.dp),
        contentScale = ContentScale.Fit
    )
}
