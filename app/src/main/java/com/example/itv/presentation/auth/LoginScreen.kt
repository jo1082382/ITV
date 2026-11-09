package com.example.itv.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.itv.application.auth.AuthService
import com.example.itv.domain.auth.AuthUser
import com.example.itv.domain.auth.LoginCancelledException
import com.example.itv.domain.auth.LoginType
import kotlinx.coroutines.launch



@Composable
internal fun LoginScreen(authService: AuthService, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // 진행 중인 로그인 방식. null이면 대기 상태.
    var pending by remember { mutableStateOf<LoginType?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = pending != null

    fun startSignIn(type: LoginType, block: suspend () -> Result<AuthUser>) {
        if (busy) return
        pending = type
        error = null
        scope.launch {
            try {
                block().onFailure { e ->
                    // 사용자가 계정 선택 창을 닫은 것은 오류가 아니다.
                    if (e !is LoginCancelledException) {
                        error = "로그인에 실패했습니다. 다시 시도해 주세요."
                    }
                }
            } finally {
                pending = null
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            "ITV",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "숏폼 사용 습관을 함께 관리해요",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))

        // 에러 문구 자리. 있든 없든 버튼 위치가 흔들리지 않게 최소 높이를 확보한다.
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { startSignIn(LoginType.GOOGLE) { authService.signInWithGoogle() } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (pending == LoginType.GOOGLE) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Google로 시작하기")
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = { startSignIn(LoginType.GUEST) { authService.signInAsGuest() } },
            enabled = !busy
        ) {
            Text("게스트로 둘러보기")
        }
        Text(
            "게스트는 서버 백업이 되지 않아요",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
    }
}