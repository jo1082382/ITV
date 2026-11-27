package com.example.itv.presentation.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.domain.support.AdminInquiryFilter
import com.example.itv.domain.support.InquiryCategory
import com.example.itv.domain.support.InquiryStatus
import com.example.itv.domain.support.SupportInquiry
import com.example.itv.domain.support.SupportReplyValidationResult
import com.example.itv.domain.support.SupportReplyValidator
import com.example.itv.infrastructure.support.SupportFirestoreRepository
import com.example.itv.infrastructure.support.SupportInquiryStore
import com.example.itv.presentation.components.ItvCardSurface
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvInkBorder
import com.example.itv.presentation.components.ItvInkShadow
import com.example.itv.presentation.components.ItvInsetSurface
import com.example.itv.presentation.components.ItvMint
import com.example.itv.presentation.components.ItvTactileButton
import com.example.itv.presentation.components.ItvTactileCard
import com.example.itv.presentation.components.LocalDarkTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AdminDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

private val QuickReplyTemplates: List<Pair<String, String>> = listOf(
    "확인·감사" to "안녕하세요, ITV 팀입니다. 소중한 문의 감사드립니다.\n말씀해 주신 내용을 확인했으며 아래와 같이 안내드립니다.\n\n",
    "오류 수정 예정" to "안녕하세요, ITV 팀입니다. 불편을 드려 죄송합니다.\n제보해 주신 오류를 확인했으며, 다음 업데이트에서 수정 반영할 예정입니다.",
    "권한 점검 안내" to "안녕하세요, ITV 팀입니다.\n설정 › 권한에서 사용정보 수집 동의·사용 정보 접근·접근성 서비스 상태를 확인하시고, 기기 배터리 설정에서 ITV를 '제한 없음'으로 설정해 보시기 바랍니다.",
)

/**
 * In-app Admin Support Center screen accessible only to server-verified admins
 * (`support_admins/{uid}` document or Firebase Auth `admin == true` custom claim).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSupportCenterScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current

    var tickets by remember { mutableStateOf<List<SupportInquiry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var selectedFilter by rememberSaveable { mutableStateOf(AdminInquiryFilter.PENDING) }
    var selectedCategory by rememberSaveable { mutableStateOf<InquiryCategory?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteTicket by remember { mutableStateOf<SupportInquiry?>(null) }
    var busyTicketId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            isLoading = true
            loadError = null
            SupportFirestoreRepository.fetchAllInquiriesForAdmin()
                .onSuccess { list ->
                    tickets = list
                    isLoading = false
                }
                .onFailure { err ->
                    loadError = err.message ?: "문의 목록을 불러오지 못했습니다."
                    isLoading = false
                }
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    val pendingCount = remember(tickets) { tickets.count { it.status == InquiryStatus.RECEIVED } }
    val answeredCount = remember(tickets) { tickets.count { it.status == InquiryStatus.ANSWERED } }

    val filteredTickets = remember(tickets, selectedFilter, selectedCategory, searchQuery) {
        val q = searchQuery.trim().lowercase()
        tickets.filter { item ->
            val statusOk = when (selectedFilter) {
                AdminInquiryFilter.PENDING -> item.status == InquiryStatus.RECEIVED
                AdminInquiryFilter.ANSWERED -> item.status == InquiryStatus.ANSWERED
                AdminInquiryFilter.ALL -> true
            }
            val categoryOk = selectedCategory == null || item.category == selectedCategory
            val queryOk = q.isEmpty() ||
                item.title.lowercase().contains(q) ||
                item.content.lowercase().contains(q) ||
                item.authorLabel.lowercase().contains(q) ||
                item.uid.lowercase().contains(q)
            statusOk && categoryOk && queryOk
        }
    }

    if (pendingDeleteTicket != null) {
        val target = pendingDeleteTicket!!
        AlertDialog(
            onDismissRequest = { pendingDeleteTicket = null },
            title = { Text("문의를 영구 삭제할까요?", fontWeight = FontWeight.Bold) },
            text = { Text("제목: ${target.title}\nFirestore에서 해당 문의 문서가 영구 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteTicket = null
                    scope.launch {
                        busyTicketId = target.id
                        SupportFirestoreRepository.deleteInquiryRemote(target.id)
                            .onSuccess {
                                tickets = tickets.filterNot { it.id == target.id }
                                if (expandedId == target.id) expandedId = null
                                statusMessage = "문의가 삭제되었습니다."
                            }
                            .onFailure { err ->
                                statusMessage = "삭제 실패: ${err.message}"
                            }
                        busyTicketId = null
                    }
                }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTicket = null }) {
                    Text("취소", color = ItvGreen)
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "관리자 고객센터",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = ItvGreen,
                        )
                        if (pendingCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xFFC8433A),
                            ) {
                                Text(
                                    text = "대기 $pendingCount",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("‹ 뒤로", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ItvGreen)
                    }
                },
                actions = {
                    TextButton(onClick = { reload() }, enabled = !isLoading) {
                        Text(
                            text = if (isLoading) "조회 중" else "새로고침",
                            fontWeight = FontWeight.Bold,
                            color = ItvGreen,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Summary KPI strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AdminKpiPill(
                    label = "답변 대기",
                    count = pendingCount,
                    accent = if (isDark) Color(0xFFE5C878) else Color(0xFF8C6200),
                    modifier = Modifier.weight(1f),
                    onClick = { selectedFilter = AdminInquiryFilter.PENDING },
                )
                AdminKpiPill(
                    label = "답변 완료",
                    count = answeredCount,
                    accent = ItvGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedFilter = AdminInquiryFilter.ANSWERED },
                )
                AdminKpiPill(
                    label = "전체",
                    count = tickets.size,
                    accent = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedFilter = AdminInquiryFilter.ALL },
                )
            }

            // 2. Status segmented tabs
            AdminFilterTabBar(
                selectedFilter = selectedFilter,
                isDark = isDark,
                onSelect = { selectedFilter = it },
            )

            // 3. Search & category chips
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = {
                    Text(
                        text = "제목, 내용, 작성자, UID 검색",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        TextButton(onClick = { searchQuery = "" }) {
                            Text("지우기", style = MaterialTheme.typography.labelMedium, color = ItvGreen)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ItvCardSurface,
                    unfocusedContainerColor = ItvCardSurface,
                    focusedBorderColor = ItvGreen,
                    unfocusedBorderColor = ItvInkBorder.copy(alpha = 0.45f),
                ),
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "cat_all", contentType = "admin_cat") {
                    AdminCategoryChip(
                        label = "✨ 전체 유형",
                        selected = selectedCategory == null,
                        isDark = isDark,
                        onClick = { selectedCategory = null },
                    )
                }
                items(
                    items = InquiryCategory.entries,
                    key = { it.name },
                    contentType = { "admin_cat" },
                ) { cat ->
                    AdminCategoryChip(
                        label = "${cat.emoji} ${cat.label}",
                        selected = selectedCategory == cat,
                        isDark = isDark,
                        onClick = { selectedCategory = cat },
                    )
                }
            }

            if (statusMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isDark) Color(0xFF1D3A28) else Color(0xFFDCF0E2),
                    border = BorderStroke(1.2.dp, ItvGreen.copy(alpha = 0.55f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = statusMessage!!,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDark) Color(0xFFA8F7C0) else Color(0xFF134025),
                        )
                        TextButton(
                            onClick = { statusMessage = null },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text("닫기", style = MaterialTheme.typography.labelSmall, color = ItvGreen)
                        }
                    }
                }
            }

            when {
                isLoading && tickets.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(color = ItvGreen)
                            Text(
                                text = "접수된 문의를 불러오는 중...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                loadError != null -> {
                    val myUid = remember { SupportFirestoreRepository.currentFirebaseUid() ?: "(미로그인)" }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        ItvTactileCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            containerColor = ItvCardSurface,
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "관리자 권한 또는 Firestore 규칙 확인 필요",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = loadError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "1) firestore.rules 배포 확인\n2) Firebase Console › Firestore에 support_admins/$myUid 문서 등록 확인",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 19.sp,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        copyToClipboard(context, "Firebase UID", myUid)
                                        Toast.makeText(context, "UID를 복사했습니다.", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Text("내 UID 복사", color = ItvGreen, fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(onClick = { reload() }) {
                                        Text("다시 시도", color = ItvGreen, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                filteredTickets.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        ItvTactileCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            containerColor = ItvCardSurface,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("🎉", fontSize = 34.sp)
                                Text(
                                    text = when (selectedFilter) {
                                        AdminInquiryFilter.PENDING -> "답변 대기 중인 문의가 없어요"
                                        AdminInquiryFilter.ANSWERED -> "답변 완료된 문의가 없어요"
                                        AdminInquiryFilter.ALL -> "조건에 맞는 문의가 없어요"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = "새로운 1:1 문의가 접수되면 이곳에 즉시 표시됩니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = filteredTickets,
                            key = { it.id },
                            contentType = { "admin_ticket" },
                        ) { ticket ->
                            AdminTicketCard(
                                ticket = ticket,
                                expanded = expandedId == ticket.id,
                                isBusy = busyTicketId == ticket.id,
                                isDark = isDark,
                                onToggle = {
                                    expandedId = if (expandedId == ticket.id) null else ticket.id
                                },
                                onCopyDiagnostics = { text ->
                                    copyToClipboard(context, "ITV 진단 정보", text)
                                    Toast.makeText(context, "진단 정보를 복사했습니다.", Toast.LENGTH_SHORT).show()
                                },
                                onSubmitReply = { replyText ->
                                    scope.launch {
                                        busyTicketId = ticket.id
                                        SupportFirestoreRepository.submitAdminReply(
                                            inquiryId = ticket.id,
                                            replyText = replyText,
                                            previousUpdatedAtMs = ticket.updatedAtEpochMs,
                                        ).onSuccess { repliedAtMs ->
                                            val adminUid = SupportFirestoreRepository.currentFirebaseUid()
                                            tickets = tickets.map { item ->
                                                if (item.id == ticket.id) {
                                                    item.copy(
                                                        status = InquiryStatus.ANSWERED,
                                                        adminReply = replyText,
                                                        adminUid = adminUid,
                                                        repliedAtEpochMs = repliedAtMs,
                                                        updatedAtEpochMs = repliedAtMs,
                                                        isReplyRead = false,
                                                    )
                                                } else {
                                                    item
                                                }
                                            }
                                            statusMessage = "✅ 답변이 저장되어 사용자 '내 문의 내역'에 반영되었습니다."
                                            // Also sync local store if admin replied to their own test ticket
                                            SupportInquiryStore.refreshFromCloud(context)
                                        }.onFailure { err ->
                                            statusMessage = "답변 저장 실패: ${err.message}"
                                        }
                                        busyTicketId = null
                                    }
                                },
                                onRequestDelete = { pendingDeleteTicket = ticket },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminTicketCard(
    ticket: SupportInquiry,
    expanded: Boolean,
    isBusy: Boolean,
    isDark: Boolean,
    onToggle: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onSubmitReply: (String) -> Unit,
    onRequestDelete: () -> Unit,
) {
    var replyDraft by rememberSaveable(ticket.id, ticket.adminReply) {
        mutableStateOf(ticket.adminReply.orEmpty())
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    val dateLabel = remember(ticket.createdAtEpochMs) {
        if (ticket.createdAtEpochMs <= 0L) "" else {
            Instant.ofEpochMilli(ticket.createdAtEpochMs)
                .atZone(ZoneId.systemDefault())
                .format(AdminDateFormatter)
        }
    }
    val statusBg = when (ticket.status) {
        InquiryStatus.RECEIVED -> if (isDark) Color(0xFF332919) else Color(0xFFFFF0D1)
        InquiryStatus.ANSWERED -> if (isDark) Color(0xFF1D472D) else Color(0xFFDCF0E2)
    }
    val statusFg = when (ticket.status) {
        InquiryStatus.RECEIVED -> if (isDark) Color(0xFFF3CF7A) else Color(0xFF8A5A00)
        InquiryStatus.ANSWERED -> if (isDark) Color(0xFFA8F7C0) else Color(0xFF134025)
    }

    ItvTactileCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(18.dp),
        containerColor = ItvCardSurface,
        borderColor = if (expanded) ItvGreen else ItvInkBorder,
        shadowColor = ItvInkShadow,
        borderWidth = if (expanded) 1.8.dp else 1.4.dp,
        shadowOffsetX = 1.5.dp,
        shadowOffsetY = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Meta header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(shape = RoundedCornerShape(999.dp), color = statusBg) {
                    Text(
                        text = if (ticket.status == InquiryStatus.RECEIVED) "답변 대기" else "답변 완료",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusFg,
                    )
                }
                Text(
                    text = "${ticket.category.emoji} ${ticket.category.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Title & Author
            Text(
                text = ticket.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "작성자: ${ticket.authorLabel} (${ticket.authorProvider}) · UID ${ticket.uid.take(10)}…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = ItvInkBorder.copy(alpha = 0.22f))

                    // Full user inquiry body
                    Text(
                        text = ticket.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp,
                    )

                    // Diagnostics box + copy action
                    ticket.diagnostics?.let { diag ->
                        val summary = remember(diag) { diag.toSummaryText() }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ItvInsetSurface)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "첨부된 진단 정보",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { onCopyDiagnostics(summary) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) {
                                    Text("복사", style = MaterialTheme.typography.labelSmall, color = ItvGreen)
                                }
                            }
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp,
                            )
                        }
                    }

                    // Quick reply templates
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "빠른 답변 템플릿",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(QuickReplyTemplates, key = { it.first }) { (label, body) ->
                                Surface(
                                    modifier = Modifier.clickable {
                                        replyDraft = body
                                        validationError = null
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    color = ItvMint,
                                    border = BorderStroke(1.dp, ItvInkBorder.copy(alpha = 0.35f)),
                                ) {
                                    Text(
                                        text = "+ $label",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ItvGreen,
                                    )
                                }
                            }
                        }
                    }

                    // Reply composer
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (ticket.status == InquiryStatus.ANSWERED) "등록된 답변 수정" else "관리자 답변 작성",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = ItvGreen,
                            )
                            Text(
                                text = "${replyDraft.length} / ${SupportReplyValidator.MAX_REPLY_LENGTH}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = replyDraft,
                            onValueChange = { next ->
                                if (next.length <= SupportReplyValidator.MAX_REPLY_LENGTH) {
                                    replyDraft = next
                                    validationError = null
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp),
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            placeholder = {
                                Text(
                                    text = "사용자에게 전달할 답변을 입력해 주세요.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = ItvInsetSurface,
                                unfocusedContainerColor = ItvInsetSurface,
                                focusedBorderColor = ItvGreen,
                                unfocusedBorderColor = ItvInkBorder.copy(alpha = 0.45f),
                            ),
                        )
                        if (validationError != null) {
                            Text(
                                text = validationError!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    // Submit & Delete row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TextButton(
                            onClick = onRequestDelete,
                            enabled = !isBusy,
                        ) {
                            Text("삭제", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.weight(1f))
                        ItvTactileButton(
                            onClick = {
                                when (val res = SupportReplyValidator.validate(replyDraft)) {
                                    is SupportReplyValidationResult.Invalid -> {
                                        validationError = res.message
                                    }
                                    is SupportReplyValidationResult.Valid -> {
                                        validationError = null
                                        onSubmitReply(res.normalizedReply)
                                    }
                                }
                            },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            containerColor = ItvGreen,
                            contentColor = if (isDark) Color(0xFF0A1F12) else Color.White,
                            borderWidth = 1.4.dp,
                            shadowOffsetX = 1.dp,
                            shadowOffsetY = 2.5.dp,
                        ) {
                            Text(
                                text = when {
                                    isBusy -> "저장 중..."
                                    ticket.status == InquiryStatus.ANSWERED -> "답변 수정 저장"
                                    else -> "답변 등록하기"
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF0A1F12) else Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminKpiPill(
    label: String,
    count: Int,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ItvTactileButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        containerColor = ItvCardSurface,
        borderWidth = 1.3.dp,
        shadowOffsetX = 1.dp,
        shadowOffsetY = 2.5.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AdminFilterTabBar(
    selectedFilter: AdminInquiryFilter,
    isDark: Boolean,
    onSelect: (AdminInquiryFilter) -> Unit,
) {
    val containerBg = if (isDark) Color(0xFF17231C) else Color(0xFFE3DACD)
    val selectedBg = if (isDark) Color(0xFF24382C) else Color(0xFFFFFCF6)
    val selectedBorder = if (isDark) Color(0xFF66BA8B) else ItvInkBorder

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerBg)
            .border(1.2.dp, ItvInkBorder.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AdminInquiryFilter.entries.forEach { tab ->
            val selected = tab == selectedFilter
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val pressScale = animateFloatAsState(
                targetValue = if (isPressed) 0.965f else 1f,
                animationSpec = tween(durationMillis = 85, easing = FastOutSlowInEasing),
                label = "adminTabPress",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        val s = pressScale.value
                        scaleX = s
                        scaleY = s
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) selectedBg else Color.Transparent)
                    .then(
                        if (selected) Modifier.border(1.4.dp, selectedBorder, RoundedCornerShape(12.dp))
                        else Modifier,
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onSelect(tab) },
                    )
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (selected) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AdminCategoryChip(
    label: String,
    selected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        selected -> ItvGreen
        isDark -> Color(0xFF1E2B23)
        else -> ItvCardSurface
    }
    val fg = when {
        selected -> if (isDark) Color(0xFF0A1F12) else Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(1.2.dp, if (selected) ItvGreen else ItvInkBorder.copy(alpha = 0.45f)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
