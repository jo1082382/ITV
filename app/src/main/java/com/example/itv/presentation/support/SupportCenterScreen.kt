package com.example.itv.presentation.support

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.collectAsState
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
import com.example.itv.domain.support.FaqCategory
import com.example.itv.domain.support.FaqItem
import com.example.itv.domain.support.InquiryCategory
import com.example.itv.domain.support.InquiryStatus
import com.example.itv.domain.support.SupportDiagnosticSnapshot
import com.example.itv.domain.support.SupportFaqCatalog
import com.example.itv.domain.support.SupportInquiry
import com.example.itv.domain.support.SupportInquiryValidationResult
import com.example.itv.domain.support.SupportInquiryValidator
import com.example.itv.domain.support.SupportTab
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val InquiryDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

/**
 * Full-screen Customer Center screen with 3 internal tabs:
 * 1. 자주 묻는 질문 (FAQ) — category chips + keyword search + accordion items
 * 2. 1:1 문의 — category selector + title/body form + optional privacy-safe diagnostic attachment
 * 3. 내 문의 내역 — submitted inquiry list with status badges and expandable reply area
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportCenterScreen(
    onNavigateBack: () -> Unit,
    initialTab: SupportTab = SupportTab.FAQ,
    initialFaqCategory: FaqCategory = FaqCategory.ALL,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current

    var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    var selectedFaqCategory by rememberSaveable(initialFaqCategory) { mutableStateOf(initialFaqCategory) }
    var faqQuery by rememberSaveable { mutableStateOf("") }
    var highlightedInquiryId by rememberSaveable { mutableStateOf<String?>(null) }
    var submissionNotice by remember { mutableStateOf<String?>(null) }

    val inquiries by SupportInquiryStore.inquiries.collectAsState()
    val isSyncing by SupportInquiryStore.isSyncing.collectAsState()
    val lastSyncError by SupportInquiryStore.lastSyncError.collectAsState()
    val unreadReplyCount = remember(inquiries) { inquiries.count { it.hasUnreadReply } }

    LaunchedEffect(Unit) {
        SupportInquiryStore.ensureLoaded(context)
        SupportInquiryStore.refreshFromCloud(context)
    }

    // Collect anonymized diagnostic snapshot once off the main thread for the 1:1 inquiry preview
    var diagnosticSnapshot by remember { mutableStateOf<SupportDiagnosticSnapshot?>(null) }
    LaunchedEffect(Unit) {
        diagnosticSnapshot = withContext(Dispatchers.Default) {
            SupportInquiryStore.collectDiagnosticSnapshot(context)
        }
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
                            text = "고객센터",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = ItvGreen,
                        )
                        if (unreadReplyCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xFFC8433A),
                            ) {
                                Text(
                                    text = "답변 $unreadReplyCount",
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
                        Text(
                            text = "‹ 뒤로",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ItvGreen,
                        )
                    }
                },
                actions = {
                    if (selectedTab == SupportTab.HISTORY) {
                        TextButton(
                            onClick = { scope.launch { SupportInquiryStore.refreshFromCloud(context) } },
                            enabled = !isSyncing,
                        ) {
                            Text(
                                text = if (isSyncing) "동기화 중" else "새로고침",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = ItvGreen,
                            )
                        }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SupportTabBar(
                selectedTab = selectedTab,
                inquiryCount = inquiries.size,
                unreadReplyCount = unreadReplyCount,
                isDark = isDark,
                onSelectTab = { tab ->
                    selectedTab = tab
                    if (tab != SupportTab.HISTORY) submissionNotice = null
                },
            )

            when (selectedTab) {
                SupportTab.FAQ -> {
                    SupportFaqTabContent(
                        selectedCategory = selectedFaqCategory,
                        query = faqQuery,
                        isDark = isDark,
                        onSelectCategory = { selectedFaqCategory = it },
                        onQueryChange = { faqQuery = it },
                        onGoToInquiry = { selectedTab = SupportTab.INQUIRY },
                        modifier = Modifier.weight(1f),
                    )
                }

                SupportTab.INQUIRY -> {
                    SupportInquiryFormTabContent(
                        diagnosticSnapshot = diagnosticSnapshot,
                        isDark = isDark,
                        onSubmit = { category, title, content, attachDiagnostics ->
                            scope.launch {
                                val snapshot = if (attachDiagnostics) {
                                    diagnosticSnapshot ?: withContext(Dispatchers.Default) {
                                        SupportInquiryStore.collectDiagnosticSnapshot(context)
                                    }
                                } else {
                                    null
                                }
                                val created = SupportInquiryStore.submitInquiry(
                                    context = context,
                                    category = category,
                                    title = title,
                                    content = content,
                                    diagnostics = snapshot,
                                )
                                highlightedInquiryId = created.id
                                submissionNotice = if (created.syncedToCloud) {
                                    "문의가 클라우드에 안전하게 접수되었어요. 답변이 등록되면 이곳에 표시됩니다."
                                } else {
                                    "문의가 기기에 임시 저장되었어요. 네트워크가 연결되면 자동으로 전송됩니다."
                                }
                                selectedTab = SupportTab.HISTORY
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                SupportTab.HISTORY -> {
                    SupportHistoryTabContent(
                        inquiries = inquiries,
                        highlightedInquiryId = highlightedInquiryId,
                        submissionNotice = submissionNotice,
                        syncError = lastSyncError,
                        isSyncing = isSyncing,
                        isDark = isDark,
                        onDismissNotice = { submissionNotice = null },
                        onRetrySync = { scope.launch { SupportInquiryStore.refreshFromCloud(context) } },
                        onExpandInquiry = { inquiry ->
                            if (inquiry.hasUnreadReply) {
                                scope.launch {
                                    SupportInquiryStore.markReplyRead(context, inquiry.id)
                                }
                            }
                        },
                        onDeleteInquiry = { inquiryId ->
                            scope.launch {
                                SupportInquiryStore.deleteInquiry(context, inquiryId)
                                if (highlightedInquiryId == inquiryId) highlightedInquiryId = null
                            }
                        },
                        onGoToInquiry = {
                            submissionNotice = null
                            selectedTab = SupportTab.INQUIRY
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportTabBar(
    selectedTab: SupportTab,
    inquiryCount: Int,
    unreadReplyCount: Int,
    isDark: Boolean,
    onSelectTab: (SupportTab) -> Unit,
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
        SupportTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val pressScale = animateFloatAsState(
                targetValue = if (isPressed) 0.965f else 1f,
                animationSpec = tween(durationMillis = 85, easing = FastOutSlowInEasing),
                label = "supportTabPress",
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
                        if (selected) {
                            Modifier.border(1.4.dp, selectedBorder, RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onSelectTab(tab) },
                    )
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (selected) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tab == SupportTab.HISTORY && unreadReplyCount > 0) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFC8433A)),
                        )
                    } else if (tab == SupportTab.HISTORY && inquiryCount > 0) {
                        Text(
                            text = "($inquiryCount)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportFaqTabContent(
    selectedCategory: FaqCategory,
    query: String,
    isDark: Boolean,
    onSelectCategory: (FaqCategory) -> Unit,
    onQueryChange: (String) -> Unit,
    onGoToInquiry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredFaqs = remember(selectedCategory, query) {
        SupportFaqCatalog.filter(selectedCategory, query)
    }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Search box
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = {
                Text(
                    text = "궁금한 키워드를 검색해 보세요 (예: 접근성, 암실, 우편함)",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { onQueryChange("") }) {
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

        // Category filter chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(
                items = FaqCategory.entries,
                key = { it.name },
                contentType = { "faq_chip" },
            ) { category ->
                val selected = category == selectedCategory
                SupportFilterChip(
                    label = "${category.emoji} ${category.label}",
                    selected = selected,
                    isDark = isDark,
                    onClick = { onSelectCategory(category) },
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(top = 2.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (filteredFaqs.isEmpty()) {
                item(key = "faq_empty", contentType = "faq_empty") {
                    ItvTactileCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        containerColor = ItvCardSurface,
                        borderWidth = 1.5.dp,
                        shadowOffsetX = 1.5.dp,
                        shadowOffsetY = 3.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("🔍", fontSize = 32.sp)
                            Text(
                                text = "검색어와 일치하는 질문이 없어요",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "다른 키워드로 찾아보거나 1:1 문의를 남겨 주시면 자세히 안내해 드릴게요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    onSelectCategory(FaqCategory.ALL)
                                    onQueryChange("")
                                }) {
                                    Text("검색 초기화", color = ItvGreen, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = onGoToInquiry) {
                                    Text("1:1 문의하기 ›", color = ItvGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                items(
                    items = filteredFaqs,
                    key = { it.id },
                    contentType = { "faq_item" },
                ) { faq ->
                    FaqAccordionCard(
                        item = faq,
                        expanded = expandedId == faq.id,
                        isDark = isDark,
                        onToggle = {
                            expandedId = if (expandedId == faq.id) null else faq.id
                        },
                    )
                }
            }

            // Bottom CTA to switch to 1:1 Inquiry
            item(key = "faq_bottom_cta", contentType = "faq_cta") {
                Spacer(Modifier.height(4.dp))
                ItvTactileCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = ItvMint,
                    borderWidth = 1.5.dp,
                    shadowOffsetX = 1.5.dp,
                    shadowOffsetY = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = "원하는 답을 찾지 못하셨나요?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "오류 제보나 궁금한 점을 직접 남겨 주세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ItvTactileButton(
                            onClick = onGoToInquiry,
                            shape = RoundedCornerShape(14.dp),
                            containerColor = ItvGreen,
                            contentColor = Color.White,
                            borderWidth = 1.4.dp,
                            shadowOffsetX = 1.dp,
                            shadowOffsetY = 2.5.dp,
                        ) {
                            Text(
                                text = "1:1 문의",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
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
private fun FaqAccordionCard(
    item: FaqItem,
    expanded: Boolean,
    isDark: Boolean,
    onToggle: () -> Unit,
) {
    val chevronRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "faqChevron",
    )

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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isDark) Color(0xFF213328) else Color(0xFFE7F2EA),
                ) {
                    Text(
                        text = "${item.category.emoji} ${item.category.label}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ItvGreen,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "▾",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (expanded) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer {
                        rotationZ = chevronRotation.value
                    },
                )
            }
            Text(
                text = "Q. ${item.question}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp,
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = ItvInkBorder.copy(alpha = 0.22f))
                    Text(
                        text = item.answer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportInquiryFormTabContent(
    diagnosticSnapshot: SupportDiagnosticSnapshot?,
    isDark: Boolean,
    onSubmit: (
        category: InquiryCategory,
        title: String,
        content: String,
        attachDiagnostics: Boolean,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by rememberSaveable { mutableStateOf(InquiryCategory.BUG) }
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var attachDiagnostics by rememberSaveable { mutableStateOf(true) }
    var showDiagnosticPreview by rememberSaveable { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Intro note
        ItvTactileCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            containerColor = ItvMint,
            borderWidth = 1.4.dp,
            shadowOffsetX = 1.dp,
            shadowOffsetY = 2.5.dp,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "무엇을 도와드릴까요?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ItvGreen,
                )
                Text(
                    text = "불편한 점이나 개선 아이디어를 남겨 주시면 검토 후 '내 문의 내역'으로 답변을 전해 드릴게요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp,
                )
            }
        }

        // 1. Category chips
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "문의 유형",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(
                    items = InquiryCategory.entries,
                    key = { it.name },
                    contentType = { "inquiry_cat" },
                ) { category ->
                    SupportFilterChip(
                        label = "${category.emoji} ${category.label}",
                        selected = category == selectedCategory,
                        isDark = isDark,
                        onClick = { selectedCategory = category },
                    )
                }
            }
        }

        // 2. Title
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "제목",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${title.length} / ${SupportInquiryValidator.MAX_TITLE_LENGTH}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = title,
                onValueChange = { next ->
                    if (next.length <= SupportInquiryValidator.MAX_TITLE_LENGTH) {
                        title = next
                        errorMessage = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = {
                    Text(
                        text = "예: 접근성을 켠 뒤 특정 화면에서 알림이 안 떠요",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ItvCardSurface,
                    unfocusedContainerColor = ItvCardSurface,
                    focusedBorderColor = ItvGreen,
                    unfocusedBorderColor = ItvInkBorder.copy(alpha = 0.45f),
                ),
            )
        }

        // 3. Body
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "문의 내용",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${content.length} / ${SupportInquiryValidator.MAX_CONTENT_LENGTH}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = content,
                onValueChange = { next ->
                    if (next.length <= SupportInquiryValidator.MAX_CONTENT_LENGTH) {
                        content = next
                        errorMessage = null
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                shape = RoundedCornerShape(14.dp),
                placeholder = {
                    Text(
                        text = "발생한 상황이나 원하시는 기능을 자유롭게 적어 주세요.\n(비밀번호, 연락처 등 민감한 개인정보는 적지 말아 주세요.)",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 19.sp,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ItvCardSurface,
                    unfocusedContainerColor = ItvCardSurface,
                    focusedBorderColor = ItvGreen,
                    unfocusedBorderColor = ItvInkBorder.copy(alpha = 0.45f),
                ),
            )
        }

        // 4. Optional anonymized diagnostic attachment
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = ItvInsetSurface,
            border = BorderStroke(1.2.dp, ItvInkBorder.copy(alpha = 0.35f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { attachDiagnostics = !attachDiagnostics },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = attachDiagnostics,
                        onCheckedChange = { attachDiagnostics = it },
                        colors = CheckboxDefaults.colors(checkedColor = ItvGreen),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "앱 버전·기기·권한 상태 자동 첨부 (권장)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "빠른 원인 파악에 도움이 되며, 화면 내용·개인정보는 포함되지 않습니다.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (attachDiagnostics) {
                    TextButton(
                        onClick = { showDiagnosticPreview = !showDiagnosticPreview },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = if (showDiagnosticPreview) "첨부 항목 접기 ∧" else "첨부되는 정보 미리보기 ∨",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ItvGreen,
                        )
                    }
                    AnimatedVisibility(visible = showDiagnosticPreview) {
                        Text(
                            text = diagnosticSnapshot?.toSummaryText() ?: "진단 정보 확인 중...",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ItvCardSurface)
                                .padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Submit button
        ItvTactileButton(
            onClick = {
                when (val result = SupportInquiryValidator.validate(title, content)) {
                    is SupportInquiryValidationResult.Invalid -> {
                        errorMessage = result.message
                    }

                    is SupportInquiryValidationResult.Valid -> {
                        errorMessage = null
                        val submittedTitle = result.normalizedTitle
                        val submittedContent = result.normalizedContent
                        val submittedCategory = selectedCategory
                        val includeDiag = attachDiagnostics
                        title = ""
                        content = ""
                        onSubmit(submittedCategory, submittedTitle, submittedContent, includeDiag)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            containerColor = ItvGreen,
            contentColor = if (isDark) Color(0xFF0A1F12) else Color.White,
            borderWidth = 1.8.dp,
            shadowOffsetX = 1.5.dp,
            shadowOffsetY = 3.5.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "1:1 문의 접수하기",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isDark) Color(0xFF0A1F12) else Color.White,
                )
            }
        }
    }
}

@Composable
private fun SupportHistoryTabContent(
    inquiries: List<SupportInquiry>,
    highlightedInquiryId: String?,
    submissionNotice: String?,
    syncError: String?,
    isSyncing: Boolean,
    isDark: Boolean,
    onDismissNotice: () -> Unit,
    onRetrySync: () -> Unit,
    onExpandInquiry: (SupportInquiry) -> Unit,
    onDeleteInquiry: (String) -> Unit,
    onGoToInquiry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedId by rememberSaveable(highlightedInquiryId) {
        mutableStateOf(highlightedInquiryId)
    }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("문의 내역을 삭제할까요?", fontWeight = FontWeight.Bold) },
            text = { Text("삭제한 문의 기록은 서버와 현재 기기 목록에서 함께 제거됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = pendingDeleteId
                    pendingDeleteId = null
                    if (id != null) onDeleteInquiry(id)
                }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("취소", color = ItvGreen)
                }
            },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (submissionNotice != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (isDark) Color(0xFF1D3A28) else Color(0xFFDCF0E2),
                border = BorderStroke(1.2.dp, ItvGreen.copy(alpha = 0.6f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "✅ $submissionNotice",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFFA8F7C0) else Color(0xFF134025),
                    )
                    TextButton(
                        onClick = onDismissNotice,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("확인", style = MaterialTheme.typography.labelMedium, color = ItvGreen)
                    }
                }
            }
        }

        if (syncError != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (isDark) Color(0xFF33261E) else Color(0xFFFFF3E0),
                border = BorderStroke(1.dp, Color(0xFFD9822B).copy(alpha = 0.55f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = syncError,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(
                        onClick = onRetrySync,
                        enabled = !isSyncing,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("재시도", style = MaterialTheme.typography.labelSmall, color = ItvGreen, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (inquiries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                ItvTactileCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    containerColor = ItvCardSurface,
                    borderWidth = 1.6.dp,
                    shadowOffsetX = 1.5.dp,
                    shadowOffsetY = 3.5.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("📮", fontSize = 40.sp)
                        Text(
                            text = "아직 접수된 문의가 없어요",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "사용 중 불편한 점이나 제안하고 싶은 기능이 있다면\n언제든 1:1 문의를 남겨 주세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp,
                        )
                        ItvTactileButton(
                            onClick = onGoToInquiry,
                            shape = RoundedCornerShape(14.dp),
                            containerColor = ItvGreen,
                            contentColor = if (isDark) Color(0xFF0A1F12) else Color.White,
                            borderWidth = 1.4.dp,
                            shadowOffsetX = 1.dp,
                            shadowOffsetY = 2.5.dp,
                        ) {
                            Text(
                                text = "1:1 문의 작성하기",
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF0A1F12) else Color.White,
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = 2.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    items = inquiries,
                    key = { it.id },
                    contentType = { "inquiry_history_card" },
                ) { inquiry ->
                    val expanded = expandedId == inquiry.id
                    InquiryHistoryCard(
                        inquiry = inquiry,
                        expanded = expanded,
                        isDark = isDark,
                        onToggle = {
                            val willExpand = !expanded
                            expandedId = if (willExpand) inquiry.id else null
                            if (willExpand) onExpandInquiry(inquiry)
                        },
                        onRequestDelete = { pendingDeleteId = inquiry.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun InquiryHistoryCard(
    inquiry: SupportInquiry,
    expanded: Boolean,
    isDark: Boolean,
    onToggle: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val dateLabel = remember(inquiry.createdAtEpochMs) {
        if (inquiry.createdAtEpochMs <= 0L) {
            ""
        } else {
            Instant.ofEpochMilli(inquiry.createdAtEpochMs)
                .atZone(ZoneId.systemDefault())
                .format(InquiryDateFormatter)
        }
    }
    val statusBg = when (inquiry.status) {
        InquiryStatus.RECEIVED -> if (isDark) Color(0xFF2B2D24) else Color(0xFFF3EBD6)
        InquiryStatus.ANSWERED -> if (isDark) Color(0xFF1D472D) else Color(0xFFDCF0E2)
    }
    val statusFg = when (inquiry.status) {
        InquiryStatus.RECEIVED -> if (isDark) Color(0xFFE5C878) else Color(0xFF7A5A12)
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusBg,
                ) {
                    Text(
                        text = inquiry.status.label,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusFg,
                    )
                }
                Text(
                    text = "${inquiry.category.emoji} ${inquiry.category.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (inquiry.hasUnreadReply) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFC8433A)),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = inquiry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = ItvInkBorder.copy(alpha = 0.22f))
                    Text(
                        text = inquiry.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                        lineHeight = 20.sp,
                    )

                    inquiry.diagnostics?.let { diag ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ItvInsetSurface)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "첨부된 진단 정보",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = diag.toSummaryText(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp,
                            )
                        }
                    }

                    // Reply section
                    val repliedDateLabel = remember(inquiry.repliedAtEpochMs) {
                        inquiry.repliedAtEpochMs?.takeIf { it > 0L }?.let { ms ->
                            Instant.ofEpochMilli(ms)
                                .atZone(ZoneId.systemDefault())
                                .format(InquiryDateFormatter)
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (inquiry.adminReply != null) {
                            if (isDark) Color(0xFF1A3124) else Color(0xFFEAF6EE)
                        } else {
                            ItvMint
                        },
                        border = BorderStroke(
                            1.dp,
                            if (inquiry.adminReply != null) ItvGreen.copy(alpha = 0.5f) else ItvInkBorder.copy(alpha = 0.25f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (inquiry.adminReply != null) "🐶 ITV 팀 답변" else "⏳ 답변 대기 중",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (inquiry.adminReply != null) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (repliedDateLabel != null) {
                                    Text(
                                        text = repliedDateLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                text = inquiry.adminReply
                                    ?: if (inquiry.syncedToCloud) {
                                        "문의가 정상 접수되었습니다. 확인 후 이곳에서 바로 답변을 안내해 드릴게요."
                                    } else {
                                        "현재 기기에 임시 저장된 상태입니다. 상단 '새로고침'을 누르거나 네트워크가 연결되면 자동 전송됩니다."
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                                lineHeight = 19.sp,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = onRequestDelete,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = "내역 삭제",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportFilterChip(
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
    val border = if (selected) ItvGreen else ItvInkBorder.copy(alpha = 0.45f)

    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(1.2.dp, border),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * Reusable subtle secondary entry banner for contextual screens (Mailbox Notice tab, Permissions).
 */
@Composable
fun SupportCenterQuickLinkCard(
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ItvTactileButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        containerColor = ItvMint,
        borderColor = ItvInkBorder.copy(alpha = 0.65f),
        shadowColor = ItvInkShadow,
        borderWidth = 1.3.dp,
        shadowOffsetX = 1.dp,
        shadowOffsetY = 2.5.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "$actionLabel ›",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = ItvGreen,
                maxLines = 1,
            )
        }
    }
}
