package com.example.itv.presentation.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.itv.domain.auth.AuthProvider
import com.example.itv.presentation.components.PuppyFaceMascot

/**
 * 게스트 상태에서 소셜 계정(Google, Kakao, Naver)으로 로그인/연동할 때
 * 데이터 연동 안내 및 기존 계정 병합 시 게스트 정보가 대체·병합됨을 사전 경고하는 다이얼로그
 */
@Composable
fun GuestAccountLinkDialog(
    provider: AuthProvider,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val providerName = when (provider) {
        AuthProvider.GOOGLE -> "Google"
        AuthProvider.KAKAO -> "카카오"
        AuthProvider.NAVER -> "네이버"
        else -> "소셜"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. 헤더: 강아지 마스코트 + 타이틀
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PuppyFaceMascot(modifier = Modifier.size(38.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "계정 연동 및 데이터 통합 안내",
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "게스트 모드에서 $providerName 계정으로 전환",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text(
                            text = "✕",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // 2. 시각적 연동 다이어그램 카드
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // 현재: 게스트
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                modifier = Modifier.size(42.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    GuestUserIcon(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "현재 게스트",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 연결 화살표 & 뱃지
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "🔗 연동 · 병합",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "────────▶",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }

                        // 대상: 소셜 계정
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                modifier = Modifier.size(42.dp),
                                shape = CircleShape,
                                color = when (provider) {
                                    AuthProvider.GOOGLE -> Color.White
                                    AuthProvider.KAKAO -> Color(0xFFFEE500)
                                    AuthProvider.NAVER -> Color(0xFF03C75A)
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                },
                                shadowElevation = 2.dp,
                                border = when (provider) {
                                    AuthProvider.GOOGLE -> BorderStroke(1.dp, Color(0xFFE4E4E4))
                                    AuthProvider.KAKAO -> BorderStroke(1.dp, Color(0xFFEAD200))
                                    else -> null
                                }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    when (provider) {
                                        AuthProvider.GOOGLE -> GoogleLogoIcon(modifier = Modifier.size(22.dp))
                                        AuthProvider.KAKAO -> KakaoLogoIcon(modifier = Modifier.size(21.dp))
                                        AuthProvider.NAVER -> NaverLogoIcon(fontSize = 17.sp)
                                        else -> {}
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$providerName 계정",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. 안내 및 경고 박스
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 경고: 원래 사용하던 기존 계정으로 로그인 시 (사용자 요청 핵심 경고문)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF7ED),
                        border = BorderStroke(1.dp, Color(0xFFFDBA74))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "⚠️",
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "원래 사용하던 계정으로 로그인 시",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF9A3412)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "기존에 이미 가입하여 사용하시던 $providerName 계정으로 로그인하시면, 현재 게스트 계정의 임시 이용 기록은 사라지고 로그인한 계정의 기존 데이터로 병합 및 전환됩니다.",
                                fontSize = 12.sp,
                                lineHeight = 16.5.sp,
                                color = Color(0xFF7C2D12)
                            )
                        }
                    }

                    // 안내: 새로운 계정으로 연동 시
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "💡",
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "새로운 계정으로 연동 시",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "지금까지 기록된 숏폼 시청 시간, 목표 설정 및 캐릭터 코칭 데이터가 새로 연결할 $providerName 계정에 안전하게 보존되어 기기 변경이나 앱 삭제 시에도 복원할 수 있습니다.",
                                fontSize = 12.sp,
                                lineHeight = 16.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 4. 하단 버튼 영역: 연동 진행 버튼 + 취소 버튼
                when (provider) {
                    AuthProvider.KAKAO -> {
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFEE500),
                                contentColor = Color(0xFF191919)
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                KakaoLogoIcon(modifier = Modifier.size(19.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("카카오로 연동 및 계속하기", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                    AuthProvider.NAVER -> {
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF03C75A),
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NaverLogoIcon(fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("네이버로 연동 및 계속하기", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                    else -> {
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF1F1F1F)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFD0D0D0)),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GoogleLogoIcon(modifier = Modifier.size(19.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Google로 연동 및 계속하기", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "취소 (게스트 계정 유지)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
