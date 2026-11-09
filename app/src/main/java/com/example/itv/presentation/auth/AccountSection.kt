package com.example.itv.presentation.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.itv.domain.auth.AuthUser
import com.example.itv.domain.auth.LoginType

/** 설정 위쪽에 보이는 현재 계정 상태. */
@Composable
internal fun AccountStatusCard(user: AuthUser, modifier: Modifier = Modifier) {
    val title: String
    val detail: String
    when (user.loginType) {
        LoginType.GUEST -> {
            title = "게스트로 사용 중"
            detail = "로그인하지 않은 상태예요."
        }
        LoginType.GOOGLE -> {
            val name = user.displayName?.takeIf { it.isNotBlank() }
            title = "${name ?: user.email ?: "Google 계정"}님"
            detail = if (name != null && user.email != null) user.email else "Google 계정으로 로그인했어요."
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 설정 맨 아래 버튼. 게스트면 로그인, 구글 계정이면 로그아웃(확인 창 포함). */
@Composable
internal fun AccountActionButton(
    user: AuthUser,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmLogout by remember { mutableStateOf(false) }

    when (user.loginType) {
        LoginType.GUEST -> Button(
            onClick = onLogin,
            modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("Google로 로그인") }

        LoginType.GOOGLE -> OutlinedButton(
            onClick = { confirmLogout = true },
            modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("로그아웃") }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("로그아웃") },
            text = { Text("로그아웃하면 새 게스트로 전환돼요. 계정 데이터는 이 기기에 보관돼요.") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("로그아웃") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("취소") }
            }
        )
    }
}