package com.example.itv.presentation.auth

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.domain.auth.AuthProvider
import com.example.itv.domain.auth.UserProfile
import com.example.itv.presentation.components.PuppyFaceMascot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    viewModel: LoginViewModel = remember { LoginViewModel() },
    onGoogleSignInRequested: (onIdTokenReceived: (String) -> Unit) -> Unit = {},
    onNavigateBack: () -> Unit,
    onAccountDeleted: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentUser by viewModel.currentUser.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showReauthRequiredDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is AuthUiState.Success -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                viewModel.resetState()
                if (state.message.contains("삭제")) {
                    onAccountDeleted()
                } else if (state.message.contains("로그아웃")) {
                    onSignedOut()
                }
            }
            is AuthUiState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                viewModel.resetState()
            }
            is AuthUiState.RequiresRecentLogin -> {
                showReauthRequiredDialog = true
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("계정 관리", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("‹ 뒤로", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. 프로필 요약 카드
            AccountProfileCard(currentUser = currentUser)

            // 2. 게스트 계정일 때: 소셜 계정 연동 안내 섹션
            if (currentUser == null || currentUser?.isAnonymous == true) {
                val lastProvider = remember { viewModel.getLastLoginProvider() }
                GuestAccountLinkingSection(
                    isLoading = uiState is AuthUiState.Loading,
                    lastProvider = lastProvider,
                    onLinkGoogle = {
                        onGoogleSignInRequested { idToken ->
                            viewModel.linkGuestWithGoogle(idToken)
                        }
                    },
                    onLinkKakao = {
                        viewModel.signInWithKakao(context)
                    },
                    onLinkNaver = {
                        viewModel.signInWithNaver(context)
                    }
                )
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 3. 로그아웃 버튼 (소셜 로그인: 구글, 네이버, 카카오 로그인 사용자에게만 노출)
            val isSocialLoggedIn = currentUser != null && !currentUser!!.isAnonymous &&
                (currentUser!!.provider == AuthProvider.GOOGLE ||
                 currentUser!!.provider == AuthProvider.KAKAO ||
                 currentUser!!.provider == AuthProvider.NAVER)

            if (isSocialLoggedIn) {
                OutlinedButton(
                    onClick = { viewModel.signOut() },
                    enabled = uiState !is AuthUiState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("로그아웃", fontSize = 14.sp)
                }
            }

            // 4. 구글 플레이 정책 준수: 계정 및 데이터 삭제 (회원 탈퇴)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "⚠️ 계정 및 데이터 영구 삭제",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "계정 삭제 시 시청 통계, 목표 설정 등 기기 및 서버의 모든 데이터가 즉시 파기되며 복구할 수 없습니다. (Google Play 계정 삭제 정책 준수)",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { showDeleteConfirmDialog = true },
                        enabled = uiState !is AuthUiState.Loading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Text("계정 영구 삭제 (회원 탈퇴)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    // 계정 영구 삭제 확인 팝업 (실수 방지용 2단계 확인 다이얼로그)
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text("정말 계정을 삭제하시겠습니까?", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Text(
                    "계정을 삭제하면 지금까지 기록된 모든 숏폼 시청 통계 및 코칭 설정이 완전히 삭제됩니다.\n삭제된 데이터는 절대 복구할 수 없습니다.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("영구 삭제 확인", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("취소")
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }

    // Google 보안 정책(RequiresRecentLogin)으로 인한 1회 본인 재인증 요청 다이얼로그
    if (showReauthRequiredDialog) {
        AlertDialog(
            onDismissRequest = {
                showReauthRequiredDialog = false
                viewModel.resetState()
            },
            title = {
                Text("Google 본인 확인 필요", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Text(
                    "Google 보안 정책상 소셜 계정 영구 삭제는 최근 1회의 본인 인증이 필요합니다.\n\n아래 버튼을 누르면 본인 계정 확인 팝업이 뜨며, 확인 시 즉시 계정 영구 삭제가 진행됩니다.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReauthRequiredDialog = false
                        viewModel.resetState()
                        onGoogleSignInRequested { idToken ->
                            viewModel.deleteAccount(idToken)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("본인 확인 후 영구 삭제", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReauthRequiredDialog = false
                    viewModel.resetState()
                }) {
                    Text("취소")
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }
}

/**
 * 사용자 프로필 및 상태 확인 카드
 */
@Composable
private fun AccountProfileCard(currentUser: UserProfile?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PuppyFaceMascot(modifier = Modifier.size(38.dp))
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusText = when {
                        currentUser == null -> "테스트 모드 (기록 유지)"
                        currentUser.isAnonymous -> "게스트 계정"
                        currentUser.provider == AuthProvider.GOOGLE -> "Google 계정"
                        currentUser.provider == AuthProvider.KAKAO -> "카카오 계정"
                        currentUser.provider == AuthProvider.NAVER -> "네이버 계정"
                        else -> "연동 계정"
                    }
                    val badgeColor = when {
                        currentUser == null -> MaterialTheme.colorScheme.tertiary
                        currentUser.isAnonymous -> Color(0xFFE67E22)
                        else -> MaterialTheme.colorScheme.primary
                    }

                    Text(
                        text = currentUser?.displayName ?: (if (currentUser?.isAnonymous == true) "게스트 사용자" else "테스트 사용자"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = badgeColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = statusText,
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = currentUser?.email ?: (if (currentUser?.isAnonymous == true) "임시 식별자: ${currentUser.uid.take(12)}..." else "로컬 개발 세션 이용 중"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 우측: 로그인 제공사 공식 로고 아이콘
            Spacer(modifier = Modifier.width(12.dp))
            ProviderLogoBadge(
                provider = currentUser?.provider,
                isAnonymous = currentUser?.isAnonymous == true
            )
        }
    }
}

/**
 * 로그인한 제공사(Google, Kakao, Naver 등)의 공식 로고를 깔끔한 원형 배지 형태로 우측에 표시
 */
@Composable
private fun ProviderLogoBadge(
    provider: AuthProvider?,
    isAnonymous: Boolean
) {
    when {
        provider == AuthProvider.GOOGLE -> {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, Color(0xFFE4E4E4))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    GoogleLogoIcon(modifier = Modifier.size(23.dp))
                }
            }
        }
        provider == AuthProvider.KAKAO -> {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color(0xFFFEE500),
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, Color(0xFFEAD200))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    KakaoLogoIcon(modifier = Modifier.size(22.dp))
                }
            }
        }
        provider == AuthProvider.NAVER -> {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color(0xFF03C75A),
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    NaverLogoIcon(fontSize = 18.sp)
                }
            }
        }
        isAnonymous -> {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    GuestUserIcon(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 게스트일 때 소셜 연동(기록 보존) 유도 섹션
 */
@Composable
private fun GuestAccountLinkingSection(
    isLoading: Boolean,
    lastProvider: AuthProvider?,
    onLinkGoogle: () -> Unit,
    onLinkKakao: () -> Unit,
    onLinkNaver: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "💡 계정 안전 보관 및 연동",
                fontWeight = FontWeight.Bold,
                fontSize = 14.5.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (lastProvider != null) {
                    "이전에 사용하시던 계정이나 새로운 소셜 계정으로 연결하면 시청 기록과 목표 설정을 안전하게 보관할 수 있습니다."
                } else {
                    "현재 임시 게스트 상태입니다. 기기 변경이나 앱 삭제 시에도 지금까지의 시청 기록과 목표 설정을 안전하게 유지하려면 소셜 계정을 연동해 주세요."
                },
                fontSize = 12.sp,
                lineHeight = 16.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            when (lastProvider) {
                AuthProvider.KAKAO -> {
                    // 카카오가 1순위 메인 버튼
                    Button(
                        onClick = onLinkKakao,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFEE500),
                            contentColor = Color(0xFF191919)
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            KakaoLogoIcon(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("카카오 계정으로 연동하기 (최근)", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Google 연동
                        Button(
                            onClick = onLinkGoogle,
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF1F1F1F)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFD0D0D0))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                GoogleLogoIcon()
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Google 연동", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        // 네이버 연동
                        Button(
                            onClick = onLinkNaver,
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF03C75A),
                                contentColor = Color.White
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                NaverLogoIcon(fontSize = 15.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("네이버 연동", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                AuthProvider.NAVER -> {
                    // 네이버가 1순위 메인 버튼
                    Button(
                        onClick = onLinkNaver,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF03C75A),
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            NaverLogoIcon(fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("네이버 계정으로 연동하기 (최근)", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Google 연동
                        Button(
                            onClick = onLinkGoogle,
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF1F1F1F)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFD0D0D0))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                GoogleLogoIcon()
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Google 연동", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        // 카카오 연동
                        Button(
                            onClick = onLinkKakao,
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFEE500),
                                contentColor = Color(0xFF191919)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                KakaoLogoIcon(modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("카카오 연동", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                else -> {
                    // Google이 1순위 기본 버튼
                    Button(
                        onClick = onLinkGoogle,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF1F1F1F)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFD0D0D0)),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            GoogleLogoIcon()
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                if (lastProvider == AuthProvider.GOOGLE) "Google 계정으로 연동하기 (최근)" else "Google 계정으로 연동하기",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 카카오 연동 (카카오 로고 + 노란색 배경 + 갈색 글자)
                        Button(
                            onClick = onLinkKakao,
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFEE500),
                                contentColor = Color(0xFF191919)
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                KakaoLogoIcon(modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("카카오 연동", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        // 네이버 연동 (네이버 로고 + 초록색 배경 + 흰색 글자)
                        Button(
                            onClick = onLinkNaver,
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF03C75A),
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                NaverLogoIcon(fontSize = 15.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("네이버 연동", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
