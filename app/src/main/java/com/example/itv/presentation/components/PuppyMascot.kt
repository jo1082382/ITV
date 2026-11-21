package com.example.itv.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.itv.R
import com.example.itv.domain.equip.EquipItem
import com.example.itv.domain.equip.EquipSlot

@Composable
fun PuppyMascot(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.itv_puppy_mascot),
        contentDescription = "하얀색 강아지 캐릭터",
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}

@Composable
fun EquippedPuppyMascot(
    equippedItems: Map<EquipSlot, EquipItem?>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val baseH = 200.dp
        val scale = maxHeight / baseH
        val puppyW = 140.4.dp * scale
        val puppyH = 200.dp * scale
        val puppyLeft = (maxWidth - puppyW) / 2f
        val puppyTop = (maxHeight - puppyH) / 2f

        // 전체 캐릭터와 의상 레이어 (불필요한 무한 애니메이션을 제거하여 가만히 안정적으로 유지 및 렌더링 최적화)
        Box(
            modifier = Modifier.size(maxWidth, maxHeight)
        ) {
            // 1. 순백색 강아지 기본 마스코트 (584 x 832 원본 비율)
            PuppyMascot(modifier = Modifier.matchParentSize())

            // 2. 옷 (강아지의 몸통 전체에 꼭 맞게 입혀진 핏)
            equippedItems[EquipSlot.CLOTHES]?.let { item ->
                val clothesSize = 92.dp * scale
                val clothesCenterX = puppyLeft + puppyW * (280f / 584f)
                val clothesTop = puppyTop + puppyH * (316f / 832f)

                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(clothesSize)
                        .offset(
                            x = clothesCenterX - (clothesSize / 2f),
                            y = clothesTop
                        ),
                    contentScale = ContentScale.Fit
                )
            }

            // 3. 신발 (발 크기에 맞춰 덮어주고, 발걸음 각도 16도에 동기화)
            equippedItems[EquipSlot.SHOES]?.let { item ->
                val shoesSize = 68.dp * scale
                val shoesCenterX = puppyLeft + puppyW * (282f / 584f)
                val shoesCenterY = puppyTop + puppyH * (730f / 832f) - (shoesSize * 0.10f)

                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(shoesSize)
                        .offset(
                            x = shoesCenterX - (shoesSize / 2f),
                            y = shoesCenterY - (shoesSize / 2f)
                        )
                        .graphicsLayer { rotationZ = 16f },
                    contentScale = ContentScale.Fit
                )
            }

            // 4. 코와 입 (강아지 머즐과 코 위치에 꼭 맞게 피팅)
            equippedItems[EquipSlot.NOSE_MOUTH]?.let { item ->
                val isRedNose = item.id == "face_red_nose"
                val itemSize = (if (isRedNose) 34.dp else 60.dp) * scale
                val centerX = puppyLeft + puppyW * (290f / 584f)
                val centerY = puppyTop + puppyH * ((if (isRedNose) 308f else 340f) / 832f)
                val boxCenterY = centerY - (if (isRedNose) itemSize * 0.02f else 0.dp)

                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(itemSize)
                        .offset(
                            x = centerX - (itemSize / 2f),
                            y = boxCenterY - (itemSize / 2f)
                        )
                        .graphicsLayer { rotationZ = 1.5f },
                    contentScale = ContentScale.Fit
                )
            }

            // 5. 안경 (강아지 양쪽 눈의 실제 거리와 위치에 완벽히 일치하는 핏)
            equippedItems[EquipSlot.GLASSES]?.let { item ->
                val glassesSize = 82.dp * scale
                val eyesCenterX = puppyLeft + puppyW * (287f / 584f)
                val glassesCenterY = puppyTop + puppyH * (265f / 832f) - (glassesSize * 0.17f)

                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(glassesSize)
                        .offset(
                            x = eyesCenterX - (glassesSize / 2f),
                            y = glassesCenterY - (glassesSize / 2f)
                        )
                        .graphicsLayer { rotationZ = 1.5f },
                    contentScale = ContentScale.Fit
                )
            }

            // 6. 모자 (머리 정수리에 푹 눌러쓴 실제 모자 크기와 핏)
            equippedItems[EquipSlot.HAT]?.let { item ->
                val hatSize = 85.dp * scale
                val hatCenterX = puppyLeft + puppyW * (280f / 584f)
                val hatCenterY = puppyTop + puppyH * (133f / 832f)

                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(hatSize)
                        .offset(
                            x = hatCenterX - (hatSize / 2f),
                            y = hatCenterY - (hatSize / 2f)
                        )
                        .graphicsLayer { rotationZ = 1.5f },
                    contentScale = ContentScale.Fit
                )
            }
        }
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
