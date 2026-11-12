package com.example.itv

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.itv.composition.AuthComposition
import com.example.itv.composition.CoachingAccess
import com.example.itv.data.local.ItvDatabase
import com.example.itv.infrastructure.consent.FileConsentStore
import com.example.itv.infrastructure.observation.accessibility.WritingGuard
import com.example.itv.presentation.auth.LoginScreen
import com.example.itv.presentation.coaching.CoachingAutoStart
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.PuppyFaceMascot
import com.example.itv.presentation.goal.GoalSettingsScreen
import com.example.itv.presentation.goal.GoalSettingsViewModel
import com.example.itv.ui.theme.ITVTheme
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FORCE_ONBOARDING = "com.team.itv.EXTRA_FORCE_ONBOARDING"
    }

    private var forceOnboardingState by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        com.example.itv.presentation.coaching.CoachingAutoStart.sync(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FORCE_ONBOARDING, false)) {
            forceOnboardingState = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent.getBooleanExtra(EXTRA_FORCE_ONBOARDING, false)) {
            forceOnboardingState = true
        }

        val viewModel = ViewModelProvider(this,
            GoalSettingsViewModel.Factory(ItvDatabase.get(this)))[GoalSettingsViewModel::class.java]
        val introMarker = File(noBackupFilesDir, "permission_intro_v2_seen")
        val loginMarker = File(noBackupFilesDir, "login_completed_v1")

        setContent {
            var showPermissionIntro by remember {
                mutableStateOf(!introMarker.exists())
            }
            var showLoginScreen by remember(forceOnboardingState) {
                mutableStateOf(forceOnboardingState || (!loginMarker.exists() && AuthComposition.authRepository.currentUser.value == null))
            }
            var introError by remember { mutableStateOf<String?>(null) }

            fun acknowledgeIntro() {
                try {
                    FileConsentStore(this@MainActivity).setCollectionConsent(true)
                    CoachingAutoStart.sync(this@MainActivity)
                    introMarker.writeText("seen")
                    showPermissionIntro = false
                } catch (_: Exception) {
                    introError = "안내 완료 저장에 실패했습니다. 앱을 재실행하거나 설정 탭에서 다시 시도해 주세요."
                }
            }

            ITVTheme {
                if (showPermissionIntro) {
                    PermissionIntroDialog(
                        onConfirm = { acknowledgeIntro() }
                    )
                }

                introError?.let { message ->
                    AlertDialog(onDismissRequest = { introError = null }, title = { Text("안내") },
                        text = { Text(message) }, confirmButton = {
                            TextButton(onClick = { introError = null }) { Text("확인") }
                        })
                }

                if (showLoginScreen && !showPermissionIntro) {
                    // 권한 안내 이후 나타나는 로그인 화면
                    LoginScreen(
                        onLoginSuccess = {
                            try { loginMarker.writeText("done") } catch (_: Exception) {}
                            forceOnboardingState = false
                            showLoginScreen = false
                        },
                        onContinueWithCurrentAccount = {
                            try { loginMarker.writeText("done") } catch (_: Exception) {}
                            forceOnboardingState = false
                            showLoginScreen = false
                        },
                        onGoogleSignInRequested = { callback ->
                            lifecycleScope.launch {
                                try {
                                    val client = AuthComposition.googleAuthClient(this@MainActivity)
                                    val tokenRes = client.getGoogleIdToken()
                                    tokenRes.onSuccess { idToken ->
                                        callback(idToken)
                                    }.onFailure { err ->
                                        Toast.makeText(this@MainActivity, "Google 로그인: ${err.message}", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(this@MainActivity, "Google 로그인 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                } else if (!showLoginScreen) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                        GoalSettingsScreen(viewModel, Modifier.padding(padding))
                    }
                }
            }
        }
    }
}

private fun checkUsageAccess(context: Context): Boolean {
    return try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}

private fun checkOverlayAccess(context: Context): Boolean {
    return try {
        Settings.canDrawOverlays(context)
    } catch (_: Exception) {
        false
    }
}

private fun checkAccessibilityAccess(context: Context): Boolean {
    if (WritingGuard.connected) return true
    return try {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabledServices.contains(context.packageName)
    } catch (_: Exception) {
        false
    }
}

private fun checkNotificationAccess(context: Context): Boolean {
    return try {
        CoachingAccess.notifications(context)
    } catch (_: Exception) {
        false
    }
}

private fun openUsageSettings(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(context, "사용 정보 접근 설정을 열지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(context, "다른 앱 위에 표시 설정을 열지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } catch (_: Exception) {
        Toast.makeText(context, "접근성 설정을 열지 못했습니다.", Toast.LENGTH_SHORT).show()
    }
}

private fun openNotificationSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        try {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            })
            return
        } catch (_: Exception) {}
    }
    try {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        })
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(context, "알림 설정을 열지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 모바일 표준(정부24, 토스 등) 스타일의 앱 접근권한 안내 팝업 창
 */
@Composable
private fun PermissionIntroDialog(
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasUsage by remember { mutableStateOf(checkUsageAccess(context)) }
    var hasOverlay by remember { mutableStateOf(checkOverlayAccess(context)) }
    var hasAccessibility by remember { mutableStateOf(checkAccessibilityAccess(context)) }
    var hasNotifications by remember { mutableStateOf(checkNotificationAccess(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsage = checkUsageAccess(context)
                hasOverlay = checkOverlayAccess(context)
                hasAccessibility = checkAccessibilityAccess(context)
                hasNotifications = checkNotificationAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val canConfirm = hasUsage && hasOverlay

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
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
            ) {
                // 1. 헤더: 강아지 아이콘 + 타이틀
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PuppyFaceMascot(
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "ITV 시청 습관 코칭",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "원활한 서비스 이용을 위해 다음 권한이 필요합니다.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                // 2. 권한 목록 (터치 시 해당 설정 화면으로 이동)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PermissionItemRow(
                        icon = "⏱️",
                        title = "기기 및 앱 사용 기록",
                        tag = "필수",
                        tagColor = MaterialTheme.colorScheme.primary,
                        isGranted = hasUsage,
                        description = "쇼츠·릴스 사용시간을 측정하여 과몰입 여부를 판단합니다.",
                        onClick = { openUsageSettings(context) }
                    )

                    PermissionItemRow(
                        icon = "🪟",
                        title = "다른 앱 위에 표시",
                        tag = "필수",
                        tagColor = MaterialTheme.colorScheme.primary,
                        isGranted = hasOverlay,
                        description = "숏폼 시청 중 강아지 캐릭터가 화면 위에 나타나 휴식을 제안합니다.",
                        onClick = { openOverlaySettings(context) }
                    )

                    PermissionItemRow(
                        icon = "🛡️",
                        title = "접근성 서비스",
                        tag = "선택",
                        tagColor = MaterialTheme.colorScheme.secondary,
                        isGranted = hasAccessibility,
                        description = "글이나 댓글을 작성 중일 때는 방해되지 않도록 개입을 보류합니다. (입력 내용 일체 미수집)",
                        onClick = { openAccessibilitySettings(context) }
                    )

                    PermissionItemRow(
                        icon = "🔔",
                        title = "알림",
                        tag = "선택",
                        tagColor = MaterialTheme.colorScheme.secondary,
                        isGranted = hasNotifications,
                        description = "시청 목표 달성 및 권장 휴식 시간 알림을 전달합니다.",
                        onClick = { openNotificationSettings(context) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. 중앙 확인 버튼 (필수 2개 미허용 시 불 꺼짐/비활성화, 허용 시 불 켜짐/활성화)
                Button(
                    onClick = onConfirm,
                    enabled = canConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ItvGreen,
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Text(
                        text = "확인",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(
    icon: String,
    title: String,
    tag: String,
    tagColor: Color,
    isGranted: Boolean,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isGranted) Color(0xFFF1F8E9) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            1.dp,
            if (isGranted) Color(0xFFA5D6A7) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                fontSize = 20.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = tagColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = tag,
                            color = tagColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGranted) "허용됨 ✓" else "설정하기 ›",
                        color = if (isGranted) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

