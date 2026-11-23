package com.example.itv.presentation.auth

import android.widget.Toast
import androidx.activity.compose.BackHandler
import com.example.itv.domain.auth.AuthProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import com.example.itv.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.itv.presentation.components.PuppyFaceMascot

enum class SocialLoginType(
    val title: String,
    val brandName: String,
    val brandColor: Color,
    val contentColor: Color,
    val idLabel: String,
    val idPlaceholder: String
) {
    KAKAO(
        title = "카카오 로그인",
        brandName = "카카오",
        brandColor = Color(0xFFFEE500),
        contentColor = Color(0xFF191919),
        idLabel = "카카오계정",
        idPlaceholder = "이메일 또는 전화번호"
    ),
    NAVER(
        title = "네이버 로그인",
        brandName = "네이버",
        brandColor = Color(0xFF03C75A),
        contentColor = Color.White,
        idLabel = "네이버 아이디",
        idPlaceholder = "아이디 또는 이메일"
    )
}

@Composable
fun LoginScreen(
    viewModel: LoginViewModel = remember { LoginViewModel() },
    onGoogleSignInRequested: (onIdTokenReceived: (String) -> Unit) -> Unit = {},
    onLoginSuccess: () -> Unit = {},
    onContinueWithCurrentAccount: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val cloudSyncStatus by viewModel.cloudSyncStatus.collectAsState()
    val isGuest = currentUser?.isAnonymous == true
    val lastProvider = remember { viewModel.getLastLoginProvider() }
    var showAllLoginOptions by remember { mutableStateOf(lastProvider == null) }
    var showIntroDialog by remember { mutableStateOf(false) }
    var activeSocialLogin by remember { mutableStateOf<SocialLoginType?>(null) }
    var pendingLinkProvider by remember { mutableStateOf<AuthProvider?>(null) }

    fun requestSocialLogin(provider: AuthProvider) {
        if (isGuest) {
            pendingLinkProvider = provider
        } else {
            when (provider) {
                AuthProvider.GOOGLE -> {
                    onGoogleSignInRequested { idToken ->
                        viewModel.signInWithGoogle(idToken)
                    }
                }
                AuthProvider.KAKAO -> viewModel.signInWithKakao(context)
                AuthProvider.NAVER -> viewModel.signInWithNaver(context)
                else -> {}
            }
        }
    }

    pendingLinkProvider?.let { provider ->
        GuestAccountLinkDialog(
            provider = provider,
            onConfirm = {
                val target = provider
                pendingLinkProvider = null
                when (target) {
                    AuthProvider.GOOGLE -> {
                        onGoogleSignInRequested { idToken ->
                            viewModel.signInWithGoogle(idToken)
                        }
                    }
                    AuthProvider.KAKAO -> viewModel.signInWithKakao(context)
                    AuthProvider.NAVER -> viewModel.signInWithNaver(context)
                    else -> {}
                }
            },
            onDismiss = {
                pendingLinkProvider = null
            }
        )
    }

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is AuthUiState.Success -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                viewModel.resetState()
                onLoginSuccess()
            }
            is AuthUiState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                viewModel.resetState()
            }
            else -> {}
        }
    }

    // 배경: 청량한 민트/에메랄드 그라데이션
    val gradientBackground = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF2EC8B9),
            Color(0xFF20BAAB),
            Color(0xFF1BA396)
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradientBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            // 1. 화면 중앙: 강아지 캐릭터 + 앱 타이틀 & 습관 코칭 & 소개 캡슐 버튼
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                // 중앙 강아지 캐릭터 얼굴
                PuppyFaceMascot(
                    modifier = Modifier
                        .size(88.dp)
                        .padding(bottom = 8.dp)
                )

                Text(
                    text = "ITV",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = (-1).sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "숏폼 시청 습관 코칭",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.95f),
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.height(20.dp))

                // 타원형 아웃라인 'ITV를 소개합니다. >' 버튼
                Surface(
                    onClick = { showIntroDialog = true },
                    shape = RoundedCornerShape(percent = 50),
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.8f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ITV를 소개합니다. 〉",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White
                        )
                    }
                }
            }

            // 2. 하단: 소셜 로그인 버튼들 (스마트 단일화: 최근 로그인 회사 우선 노출)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (lastProvider != null && !showAllLoginOptions) {
                    // [스마트 단일화 모드]: 최근 로그인한 단 1개 제공사만 큼직하게 노출
                    Text(
                        text = "최근 이용하신 계정으로 바로 연결합니다",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.90f)
                    )

                    when (lastProvider) {
                        AuthProvider.GOOGLE -> {
                            SocialLoginButton(
                                text = "Google로 계속하기",
                                backgroundColor = Color.White,
                                contentColor = Color(0xFF1F1F1F),
                                enabled = uiState !is AuthUiState.Loading,
                                icon = { GoogleLogoIcon() },
                                onClick = { requestSocialLogin(AuthProvider.GOOGLE) }
                            )
                        }
                        AuthProvider.KAKAO -> {
                            SocialLoginButton(
                                text = "카카오로 계속하기",
                                backgroundColor = Color(0xFFFEE500),
                                contentColor = Color(0xFF191919),
                                enabled = uiState !is AuthUiState.Loading,
                                icon = { KakaoLogoIcon() },
                                onClick = { requestSocialLogin(AuthProvider.KAKAO) }
                            )
                        }
                        AuthProvider.NAVER -> {
                            SocialLoginButton(
                                text = "네이버로 계속하기",
                                backgroundColor = Color(0xFF03C75A),
                                contentColor = Color.White,
                                enabled = uiState !is AuthUiState.Loading,
                                icon = { NaverLogoIcon() },
                                onClick = { requestSocialLogin(AuthProvider.NAVER) }
                            )
                        }
                        else -> {}
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "다른 방법으로 로그인",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color.White.copy(alpha = 0.85f),
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = uiState !is AuthUiState.Loading) {
                                showAllLoginOptions = true
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                } else {
                    // [전체 로그인 모드]: 최근 로그인 이력이 없거나 사용자가 '다른 방법으로 로그인'을 눌렀을 때
                    // (1) Google로 시작
                    SocialLoginButton(
                        text = "Google로 시작",
                        backgroundColor = Color.White,
                        contentColor = Color(0xFF1F1F1F),
                        enabled = uiState !is AuthUiState.Loading,
                        icon = { GoogleLogoIcon() },
                        onClick = { requestSocialLogin(AuthProvider.GOOGLE) }
                    )

                    // (2) 카카오로 시작
                    SocialLoginButton(
                        text = "카카오로 시작",
                        backgroundColor = Color(0xFFFEE500),
                        contentColor = Color(0xFF191919),
                        enabled = uiState !is AuthUiState.Loading,
                        icon = { KakaoLogoIcon() },
                        onClick = { requestSocialLogin(AuthProvider.KAKAO) }
                    )

                    // (3) 네이버로 시작
                    SocialLoginButton(
                        text = "네이버로 시작",
                        backgroundColor = Color(0xFF03C75A),
                        contentColor = Color.White,
                        enabled = uiState !is AuthUiState.Loading,
                        icon = { NaverLogoIcon() },
                        onClick = { requestSocialLogin(AuthProvider.NAVER) }
                    )

                    // (4) 게스트로 시작 (원클릭 시작)
                    SocialLoginButton(
                        text = "게스트로 시작",
                        backgroundColor = Color.White.copy(alpha = 0.22f),
                        contentColor = Color.White,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                        enabled = uiState !is AuthUiState.Loading,
                        icon = { GuestUserIcon() },
                        onClick = { viewModel.signInAsGuest() }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // (5) 맨 아래: 테스트 (기존 기록 유지) - 글자만 표시
                    Text(
                        text = "테스트 (기존 기록 유지)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color.White.copy(alpha = 0.90f),
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = uiState !is AuthUiState.Loading) {
                                onContinueWithCurrentAccount?.invoke()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 클라우드 계정 데이터 연동 및 홈 준비 대기 화면 (완료 시 자동으로 홈 화면 이동)
        val loadingState = uiState as? AuthUiState.Loading
        if (loadingState != null) {
            CloudSyncWaitingOverlay(
                title = loadingState.title,
                message = cloudSyncStatus.progressMessage ?: loadingState.message
            )
        }
    }

    // 카카오 / 네이버 아이디·비밀번호 입력 창 (모달 다이얼로그)
    activeSocialLogin?.let { type ->
        SocialLoginInputDialog(
            type = type,
            onDismiss = { activeSocialLogin = null },
            onSubmit = { id, _ ->
                // 현재는 연동 준비 중 안내 (차후 SDK/백엔드 연동 시 여기에 실제 API 연결)
                Toast.makeText(
                    context,
                    "${type.brandName} 로그인 연동 준비 중입니다. (${id})\n현재는 Google 또는 게스트 로그인을 이용해 주세요.",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    // 'ITV를 소개합니다' 팝업 다이얼로그 (칸 별로 구분된 직관적인 카드 레이아웃)
    if (showIntroDialog) {
        AlertDialog(
            onDismissRequest = { showIntroDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PuppyFaceMascot(modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ITV를 소개합니다",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    PuppyFaceMascot(modifier = Modifier.size(28.dp))
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "끝없이 이어지는 숏폼(YouTube 쇼츠, Instagram 릴스) 과몰입을 건강하게 멈출 수 있도록 돕는 디지털 웰빙 코칭 서비스입니다.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )

                    // 1번 칸: 스마트한 과몰입 감지
                    IntroFeatureCard(
                        icon = "📱",
                        title = "스마트한 과몰입 감지",
                        description = "단순 시간뿐만 아니라 빠른 스와이프 속도, 불 끈 방의 심야 조도 환경을 연계하여 꼭 필요한 타이밍을 포착합니다."
                    )

                    // 2번 칸: 귀여운 강아지 개입
                    IntroFeatureCard(
                        icon = "🐶",
                        title = "귀여운 강아지의 휴식 제안",
                        description = "과몰입이 감지되면 화면 위에 귀여운 강아지가 찾아와 강제 차단 대신 스스로 시청을 멈추고 쉴 수 있게 도와줍니다."
                    )

                    // 3번 칸: 쉬운 시작 & 안심 데이터
                    IntroFeatureCard(
                        icon = "🔒",
                        title = "쉬운 시작 & 안심 데이터",
                        description = "복잡한 가입 없이 게스트로 즉시 시작할 수 있으며, 화면 내용이나 개인정보는 일체 수집하지 않아 안심할 수 있습니다."
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showIntroDialog = false },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("확인했습니다", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            },
            shape = RoundedCornerShape(22.dp)
        )
    }
}

/**
 * 카카오 / 네이버 아이디·비밀번호 입력 창
 */
@Composable
private fun SocialLoginInputDialog(
    type: SocialLoginType,
    onDismiss: () -> Unit,
    onSubmit: (id: String, pw: String) -> Unit
) {
    var userId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(24.dp)
            ) {
                // 1. 상단 바: 뒤로가기 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("‹ 뒤로가기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 2. 브랜드 로고 & 타이틀
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(60.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = type.brandColor
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (type == SocialLoginType.KAKAO) {
                                KakaoLogoIcon(modifier = Modifier.size(32.dp))
                            } else {
                                NaverLogoIcon(fontSize = 28.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = type.title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${type.brandName} 계정으로 로그인합니다.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(36.dp))

                // 3. 아이디 & 비밀번호 입력 필드
                OutlinedTextField(
                    value = userId,
                    onValueChange = { userId = it },
                    label = { Text(type.idLabel) },
                    placeholder = { Text(type.idPlaceholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("비밀번호") },
                    placeholder = { Text("비밀번호를 입력하세요") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (userId.isNotBlank() && password.isNotBlank()) {
                                onSubmit(userId, password)
                            }
                        }
                    ),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                text = if (passwordVisible) "숨김" else "보기",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 4. 로그인 버튼
                Button(
                    onClick = { onSubmit(userId, password) },
                    enabled = userId.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = type.brandColor,
                        contentColor = type.contentColor,
                        disabledContainerColor = type.brandColor.copy(alpha = 0.4f),
                        disabledContentColor = type.contentColor.copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        text = "${type.brandName}로 로그인",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "* 현재는 연동 준비 중 단계로, 로그인 버튼 클릭 시 준비 중 안내가 표시됩니다.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 통일된 규격의 소셜/게스트 로그인 버튼
 */
@Composable
private fun SocialLoginButton(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    border: BorderStroke? = null,
    icon: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
        border = border,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Text(
                text = text,
                color = contentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            // 좌우 대칭을 위한 더미 여백
            Spacer(modifier = Modifier.size(24.dp))
        }
    }
}

/** Google 'G' 로고 아이콘 */
/** Google 공식 'G' 4색 로고 아이콘 */
@Composable
internal fun GoogleLogoIcon(modifier: Modifier = Modifier.size(20.dp)) {
    Icon(
        painter = painterResource(id = R.drawable.ic_google_logo),
        contentDescription = "Google 로고",
        tint = Color.Unspecified,
        modifier = modifier
    )
}

/** 카카오 말풍선 로고 아이콘 */
@Composable
internal fun KakaoLogoIcon(modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val kakaoBrown = Color(0xFF381E1F)

        // 말풍선 타원 몸체
        drawOval(
            color = kakaoBrown,
            topLeft = Offset(w * 0.05f, h * 0.1f),
            size = Size(w * 0.9f, h * 0.72f)
        )
        // 말풍선 꼬리표 (좌하단)
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.65f)
            lineTo(w * 0.12f, h * 0.90f)
            lineTo(w * 0.38f, h * 0.75f)
            close()
        }
        drawPath(path, color = kakaoBrown)
    }
}

/** 네이버 'N' 로고 아이콘 */
@Composable
internal fun NaverLogoIcon(fontSize: androidx.compose.ui.unit.TextUnit = 17.sp) {
    Text(
        text = "N",
        color = Color.White,
        fontWeight = FontWeight.ExtraBold,
        fontSize = fontSize,
        textAlign = TextAlign.Center
    )
}

/** 게스트 사용자 아이콘 */
@Composable
internal fun GuestUserIcon(
    modifier: Modifier = Modifier.size(20.dp),
    color: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 머리
        drawCircle(
            color = color,
            radius = w * 0.22f,
            center = Offset(w * 0.5f, h * 0.32f)
        )
        // 몸통
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(w * 0.18f, h * 0.55f),
            size = Size(w * 0.64f, h * 0.45f)
        )
    }
}

/**
 * 'ITV를 소개합니다' 다이얼로그 내부 칸 구분용 카드 컴포넌트
 */
@Composable
private fun IntroFeatureCard(
    icon: String,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(text = icon, fontSize = 22.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 16.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Full-screen waiting overlay displayed while authenticating, restoring/merging
 * 6-month cloud data, and pre-warming the Home calendar so the user lands directly
 * on a fully populated Home screen once sync finishes.
 */
@Composable
internal fun CloudSyncWaitingOverlay(
    title: String,
    message: String
) {
    // Prevent accidental back-press dismissal while cloud sync/restore is in progress
    BackHandler(enabled = true) {}

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFE6F7F5),
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        PuppyFaceMascot(modifier = Modifier.size(48.dp))
                    }
                }

                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    color = Color(0xFF1BA396),
                    strokeWidth = 3.5.dp
                )

                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = message,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "완료되면 자동으로 이동합니다. 잠시만 기다려 주세요.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
