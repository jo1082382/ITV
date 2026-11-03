package com.example.itv

import android.os.Bundle
import android.content.Intent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.window.DialogProperties
import com.example.itv.presentation.permissions.PermissionSettingsActivity
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.itv.data.local.ItvDatabase
import com.example.itv.presentation.goal.GoalSettingsScreen
import com.example.itv.presentation.goal.GoalSettingsViewModel
import com.example.itv.ui.theme.ITVTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        com.example.itv.presentation.coaching.CoachingAutoStart.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = ViewModelProvider(this,
            GoalSettingsViewModel.Factory(ItvDatabase.get(this)))[GoalSettingsViewModel::class.java]
        val introMarker = File(noBackupFilesDir, "permission_intro_v2_seen")
        setContent {
            var showPermissionIntro by remember { mutableStateOf(!introMarker.exists()) }
            var introError by remember { mutableStateOf<String?>(null) }
            fun acknowledgeIntro(openSettings: Boolean) {
                try {
                    introMarker.writeText("seen")
                    showPermissionIntro = false
                    if (openSettings) {
                        startActivity(Intent(this@MainActivity, PermissionSettingsActivity::class.java))
                    }
                } catch (_: Exception) {
                    introError = "안내 완료 저장 또는 권한 화면 열기에 실패했습니다. 앱을 재실행하거나 설정 탭에서 다시 시도해 주세요."
                }
            }
            ITVTheme {
                if (showPermissionIntro) {
                    AlertDialog(
                        onDismissRequest = {},
                        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                        title = { Text("앱 접근권한 안내") },
                        text = {
                            Text(
                                "ITV는 사용량을 확인하고, 작성 중에는 강아지 개입을 보류하기 위해 다음 기능을 사용합니다.\n\n" +
                                "• 사용 정보 접근: 휴대폰·앱 사용시간 확인\n" +
                                "• 다른 앱 위에 표시: 다른 앱 사용 중 강아지와 선택 버튼 표시\n" +
                                "• 알림: 감시 상태와 휴식 제안 알림\n" +
                                "• 접근성 서비스: 키보드 창, 입력 가능한 칸과 포커스를 확인하여 메시지·글 작성 중 개입 보류\n\n" +
                                "접근성 권한 자체는 화면 내용에 접근할 수 있는 강한 권한입니다. ITV의 현재 작성 보호 기능은 화면 구조와 앱 식별 정보만 기기 안에서 일시적으로 확인합니다. 메시지 본문, 입력 문자, 화면 이미지·스크린샷은 읽거나 저장·전송하지 않습니다.\n\n" +
                                "다음 화면에서 수집 동의와 각 기능의 설명을 확인한 뒤, 원하는 권한을 직접 허용해 주세요. 접근성은 Android 설정에서 직접 켜야 하며 언제든 해제할 수 있습니다.\n\n" +
                                "거절하거나 나중에 설정해도 홈·목표 설정은 사용할 수 있습니다. 접근성을 허용하지 않으면 작성 상태를 확인할 수 없어 현재 테스트의 강아지 개입은 보류됩니다.",
                                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                            )
                        },
                        confirmButton = { TextButton(onClick = { acknowledgeIntro(true) }) { Text("권한 설정하기") } },
                        dismissButton = { TextButton(onClick = { acknowledgeIntro(false) }) { Text("나중에") } }
                    )
                }
                introError?.let { message ->
                    AlertDialog(onDismissRequest = { introError = null }, title = { Text("안내") },
                        text = { Text(message) }, confirmButton = {
                            TextButton(onClick = { introError = null }) { Text("확인") }
                        })
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    GoalSettingsScreen(viewModel, Modifier.padding(padding))
                }
            }
        }
    }
}
