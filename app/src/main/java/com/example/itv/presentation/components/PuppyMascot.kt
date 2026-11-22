package com.example.itv.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import com.example.itv.presentation.coaching.EquippedDogView
import com.example.itv.R
import com.example.itv.domain.equip.EquipItem
import com.example.itv.domain.equip.EquipSlot

@Composable
fun PuppyMascot(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.itv_puppy_mascot),
        contentDescription = "골든 리트리버",
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}

@Composable
fun EquippedPuppyMascot(
    equippedItems: Map<EquipSlot, EquipItem?>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.size(minOf(maxWidth, maxHeight)),
            factory = { context ->
                EquippedDogView(context).apply {
                    setImageResource(R.drawable.itv_motion_front)
                    contentDescription = "골든 리트리버"
                }
            },
            update = { it.setEquipment(equippedItems) }
        )
    }
}

@Composable
fun PuppyFaceMascot(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.itv_puppy_face),
        contentDescription = "정면을 바라보는 강아지 얼굴",
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}
