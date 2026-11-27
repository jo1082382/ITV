package com.example.itv.presentation.mailbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.itv.domain.coaching.MailboxNotice
import com.example.itv.domain.coaching.MailboxNoticeCategory
import com.example.itv.domain.coaching.MailboxTab
import com.example.itv.domain.coaching.WeeklyCoachLetter
import com.example.itv.domain.coaching.WeeklyCoachReportGenerator
import com.example.itv.domain.support.SupportTab
import com.example.itv.presentation.components.ItvCardSurface
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvInkBorder
import com.example.itv.presentation.components.ItvInkShadow
import com.example.itv.presentation.components.ItvInsetSurface
import com.example.itv.presentation.components.ItvTactileButton
import com.example.itv.presentation.components.ItvTactileCard
import com.example.itv.presentation.components.LocalDarkTheme
import com.example.itv.presentation.support.SupportCenterQuickLinkCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

private enum class MailboxStage {
    INBOX,
    UNSEALING,
    READING,
}

/**
 * Full Mailbox modal opened from the top-left "우편함" button on Home.
 *
 * Performance & motion design:
 * - Fixed stable modal height (`fillMaxHeight(0.82f)`) eliminates frame-by-frame Dialog re-layout
 *   pushing/stuttering when opening or closing letters.
 * - Tapping an **unread** letter plays a smooth GPU-friendly unsealing ritual: the dog-paw wax
 *   seal & top triangular flap lift upward, then the letter sheet slides out of the envelope pocket
 *   directly into the reader.
 * - Already-**read** letters stay visually open in the inbox list and open immediately on tap.
 */
@Composable
internal fun WeeklyMailboxDialog(
    letters: List<WeeklyCoachLetter>,
    notices: List<MailboxNotice> = emptyList(),
    onMarkAsRead: (String) -> Unit,
    onMarkNoticeAsRead: (String) -> Unit = {},
    onOpenSupportCenter: (SupportTab) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var stage by remember { mutableStateOf(MailboxStage.INBOX) }
    var selectedTab by remember { mutableStateOf(MailboxTab.PERSONAL) }
    var activeLetterId by remember { mutableStateOf<String?>(null) }
    var arrivedFromUnseal by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    val activeLetter = remember(activeLetterId, letters) {
        activeLetterId?.let { id -> letters.firstOrNull { it.id == id } }
    }

    Dialog(
        onDismissRequest = {
            when {
                showHelpDialog -> showHelpDialog = false
                stage != MailboxStage.INBOX -> {
                    stage = MailboxStage.INBOX
                    activeLetterId = null
                }
                else -> onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(enabled = stage != MailboxStage.INBOX || showHelpDialog) {
            when {
                showHelpDialog -> showHelpDialog = false
                else -> {
                    stage = MailboxStage.INBOX
                    activeLetterId = null
                }
            }
        }

        val isDark = LocalDarkTheme.current
        val sheetBg = if (isDark) Color(0xFF101814) else Color(0xFFEFE9DF)

        // Fixed stable dialog height prevents Dialog window resizing / pushing lag on open & close
        ItvTactileCard(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.82f),
            shape = RoundedCornerShape(26.dp),
            containerColor = sheetBg,
            borderColor = ItvInkBorder,
            shadowColor = ItvInkShadow,
            borderWidth = 2.dp,
            shadowOffsetX = 2.5.dp,
            shadowOffsetY = 5.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (stage) {
                    MailboxStage.INBOX -> {
                        MailboxInboxListView(
                            selectedTab = selectedTab,
                            onSelectTab = { selectedTab = it },
                            letters = letters,
                            notices = notices,
                            onOpenLetter = { letter ->
                                activeLetterId = letter.id
                                if (letter.isRead) {
                                    // Already read: open immediately without replaying unseal ritual
                                    arrivedFromUnseal = false
                                    stage = MailboxStage.READING
                                } else {
                                    // Unread: play flap-lift -> pull-out -> 2x vertical unfold ritual
                                    arrivedFromUnseal = true
                                    stage = MailboxStage.UNSEALING
                                }
                            },
                            onMarkNoticeAsRead = onMarkNoticeAsRead,
                            onOpenSupportCenter = onOpenSupportCenter,
                            onOpenHelp = { showHelpDialog = true },
                            onDismiss = onDismiss,
                        )
                    }

                    MailboxStage.UNSEALING -> {
                        val target = activeLetter
                        if (target == null) {
                            stage = MailboxStage.INBOX
                        } else {
                            EnvelopeUnsealStageView(
                                letter = target,
                                onFinished = {
                                    onMarkAsRead(target.id)
                                    stage = MailboxStage.READING
                                },
                                onCancel = {
                                    stage = MailboxStage.INBOX
                                    activeLetterId = null
                                },
                            )
                        }
                    }

                    MailboxStage.READING -> {
                        val target = activeLetter
                        if (target == null) {
                            stage = MailboxStage.INBOX
                        } else {
                            WeeklyCoachLetterReaderView(
                                letter = target,
                                fromUnseal = arrivedFromUnseal,
                                onBack = {
                                    stage = MailboxStage.INBOX
                                    activeLetterId = null
                                },
                                onDismiss = onDismiss,
                            )
                        }
                    }
                }
            }
        }

        if (showHelpDialog) {
            WeeklyMailboxHelpDialog(onDismiss = { showHelpDialog = false })
        }
    }
}

/**
 * Continuous zero-disappearance unsealing & unfolding ritual:
 * 1) Top flap + crimson dog-paw wax seal lift upward (640ms).
 * 2) Folded cream letter slides UP and forward in front of the envelope (840ms).
 * 3) Envelope recedes behind while the letter (folded vertically in half twice: 1/4 -> 1/2 -> 1/1)
 *    unfolds smoothly into the exact stationery sheet bounds of [WeeklyCoachLetterReaderView] (1050ms).
 */
@Composable
private fun EnvelopeUnsealStageView(
    letter: WeeklyCoachLetter,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
) {
    val flapAnim = remember(letter.id) { Animatable(0f) }
    val slideAnim = remember(letter.id) { Animatable(0f) }
    val unfoldAnim = remember(letter.id) { Animatable(0f) }
    var completed by remember(letter.id) { mutableStateOf(false) }
    val statusText by remember(letter.id) {
        derivedStateOf {
            when {
                unfoldAnim.value > 0.02f -> "접힌 편지를 펼치고 있어요..."
                flapAnim.value < 0.7f -> "발자국 실링을 여는 중..."
                else -> "편지를 꺼내고 있어요..."
            }
        }
    }

    LaunchedEffect(letter.id) {
        // Step 1: Unhurried lift of the top flap + dog-paw wax seal upward
        val flapJob = launch {
            flapAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 640, easing = FastOutSlowInEasing),
            )
        }
        // Step 2: Once flap crosses 90°, pull the folded letter up and forward in front of the envelope
        delay(400)
        val slideJob = launch {
            slideAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 840, easing = FastOutSlowInEasing),
            )
        }
        flapJob.join()
        slideJob.join()

        // Step 3: Immediately unfold the vertically double-folded letter (ZERO fade-out/disappearance!)
        unfoldAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
        )
        if (!completed) {
            completed = true
            onFinished()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (!completed) {
                        completed = true
                        onFinished()
                    }
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Top bar matching WeeklyCoachLetterReaderView's exact height so the unfolded stationery aligns pixel-for-pixel!
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = ItvGreen,
                modifier = Modifier.graphicsLayer {
                    alpha = (1f - ((unfoldAnim.value - 0.80f) / 0.20f).coerceIn(0f, 1f))
                },
            )
            DialogCloseButton(onClick = onCancel)
        }

        // Full stationery area: envelope opens -> letter pulls forward -> unfolds vertically twice into the exact sheet bounds
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            EnvelopeUnsealAndUnfoldCanvas(
                openProgressProvider = { flapAnim.value },
                letterSlideProgressProvider = { slideAnim.value },
                unfoldProgressProvider = { unfoldAnim.value },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MailboxInboxListView(
    selectedTab: MailboxTab,
    onSelectTab: (MailboxTab) -> Unit,
    letters: List<WeeklyCoachLetter>,
    notices: List<MailboxNotice>,
    onOpenLetter: (WeeklyCoachLetter) -> Unit,
    onMarkNoticeAsRead: (String) -> Unit,
    onOpenSupportCenter: (SupportTab) -> Unit,
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = LocalDarkTheme.current
    val personalUnreadCount = remember(letters) { letters.count { !it.isRead } }
    val noticeUnreadCount = remember(notices) { notices.count { !it.isRead } }
    val totalUnreadCount = personalUnreadCount + noticeUnreadCount
    val sortedNotices = remember(notices) {
        notices.sortedWith(
            compareByDescending<MailboxNotice> { it.isPinned }
                .thenByDescending { it.publishedDate }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header row: left mailbox title + right `?` help & `✕` close buttons (never squished)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MailboxHeaderIcon(hasUnread = totalUnreadCount > 0, modifier = Modifier.size(38.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "우편함",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                        )
                        if (totalUnreadCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xFFC8433A),
                            ) {
                                Text(
                                    text = "NEW $totalUnreadCount",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                    Text(
                        text = when (selectedTab) {
                            MailboxTab.PERSONAL -> "매주 월요일 도착하는 주간 편지"
                            MailboxTab.NOTICE -> "업데이트 · 이벤트 · 설문 · 안내"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DialogHelpButton(onClick = onOpenHelp)
            DialogCloseButton(onClick = onDismiss)
        }

        // Personal / Notice 2-tab segmented switcher
        MailboxTabBar(
            selectedTab = selectedTab,
            personalUnreadCount = personalUnreadCount,
            noticeUnreadCount = noticeUnreadCount,
            isDark = isDark,
            onSelectTab = onSelectTab,
        )

        HorizontalDivider(color = ItvInkBorder.copy(alpha = 0.18f))

        when (selectedTab) {
            MailboxTab.PERSONAL -> {
                if (letters.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyMailboxState()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = letters,
                            key = { it.id },
                            contentType = { "envelope_card" },
                        ) { letter ->
                            MailboxEnvelopeCard(
                                letter = letter,
                                isDark = isDark,
                                onClick = { onOpenLetter(letter) },
                            )
                        }
                    }
                }
            }

            MailboxTab.NOTICE -> {
                if (sortedNotices.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyNoticeState()
                        }
                        SupportCenterQuickLinkCard(
                            title = "궁금한 점이 있거나 도움이 필요하신가요?",
                            subtitle = "FAQ 확인 및 1:1 문의를 남길 수 있어요.",
                            actionLabel = "고객센터",
                            onClick = { onOpenSupportCenter(SupportTab.INQUIRY) },
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = sortedNotices,
                            key = { it.id },
                            contentType = { "notice_card" },
                        ) { notice ->
                            MailboxNoticeCard(
                                notice = notice,
                                isDark = isDark,
                                onMarkRead = { onMarkNoticeAsRead(notice.id) },
                                modifier = Modifier.animateItem(
                                    placementSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
                                ),
                            )
                        }
                        item(key = "notice_support_link", contentType = "support_link") {
                            SupportCenterQuickLinkCard(
                                title = "궁금한 점이 있거나 도움이 필요하신가요?",
                                subtitle = "FAQ 확인 및 1:1 문의를 남길 수 있어요.",
                                actionLabel = "고객센터",
                                onClick = { onOpenSupportCenter(SupportTab.INQUIRY) },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailboxTabBar(
    selectedTab: MailboxTab,
    personalUnreadCount: Int,
    noticeUnreadCount: Int,
    isDark: Boolean,
    onSelectTab: (MailboxTab) -> Unit,
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
        MailboxTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val unread = when (tab) {
                MailboxTab.PERSONAL -> personalUnreadCount
                MailboxTab.NOTICE -> noticeUnreadCount
            }
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val pressScaleState = animateFloatAsState(
                targetValue = if (isPressed) 0.965f else 1f,
                animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
                label = "mailboxTabPressScale",
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        val s = pressScaleState.value
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
                        }
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onSelectTab(tab) }
                    .padding(vertical = 9.dp, horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
                if (unread > 0) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFFC8433A))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (unread > 99) "99+" else unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNoticeState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "📢", fontSize = 44.sp)
        Text(
            text = "등록된 공지사항이 없어요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "업데이트 안내, 이벤트, 설문 보상, 고객센터 답변 등\n" +
                "새로운 소식이 도착하면 이곳에서 바로 확인할 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun MailboxNoticeCard(
    notice: MailboxNotice,
    isDark: Boolean,
    onMarkRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(notice.id) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.975f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "noticeCardPressScale",
    )
    val chevronRotationState = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "noticeChevron",
    )
    val detailRevealState = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "noticeReveal",
    )

    val cardBg = if (!notice.isRead) {
        if (isDark) Color(0xFF1D2C23) else Color(0xFFFFFCF6)
    } else {
        ItvCardSurface
    }
    val borderColor = when {
        !notice.isRead -> if (isDark) Color(0xFFD3564C) else Color(0xFFC8433A)
        notice.isPinned -> if (isDark) Color(0xFF5FA87F) else ItvGreen
        else -> ItvInkBorder.copy(alpha = 0.45f)
    }
    val catBadgeBg = when (notice.category) {
        MailboxNoticeCategory.UPDATE -> if (isDark) Color(0xFF21362A) else Color(0xFFE7F4EB)
        MailboxNoticeCategory.EVENT -> if (isDark) Color(0xFF3A281A) else Color(0xFFFFF0DE)
        MailboxNoticeCategory.SURVEY -> if (isDark) Color(0xFF232C3F) else Color(0xFFE8EEFA)
        MailboxNoticeCategory.SYSTEM -> if (isDark) Color(0xFF2B2724) else Color(0xFFF3ECE2)
    }
    val catBadgeFg = when (notice.category) {
        MailboxNoticeCategory.UPDATE -> if (isDark) Color(0xFF9EE6B6) else Color(0xFF1F6A42)
        MailboxNoticeCategory.EVENT -> if (isDark) Color(0xFFFFC987) else Color(0xFF9E5210)
        MailboxNoticeCategory.SURVEY -> if (isDark) Color(0xFFAEC8FF) else Color(0xFF2850A7)
        MailboxNoticeCategory.SYSTEM -> MaterialTheme.colorScheme.onSurface
    }
    val dateLabel = remember(notice.publishedDate) {
        String.format(
            java.util.Locale.KOREA,
            "%04d.%02d.%02d",
            notice.publishedDate.year,
            notice.publishedDate.monthValue,
            notice.publishedDate.dayOfMonth,
        )
    }
    val cardShape = RoundedCornerShape(18.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = pressScaleState.value
                scaleX = s
                scaleY = s
            }
            .clip(cardShape)
            .background(cardBg)
            .border(if (!notice.isRead) 1.8.dp else 1.2.dp, borderColor, cardShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                expanded = !expanded
                if (!notice.isRead) {
                    onMarkRead()
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(catBadgeBg)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "${notice.category.emoji} ${notice.category.label}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = catBadgeFg,
                    maxLines = 1,
                )
            }
            if (notice.isPinned) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ItvGreen.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "고정",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color(0xFF9EE6B6) else ItvGreen,
                    )
                }
            }
            if (!notice.isRead) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFC8433A)),
                )
            }
            Box(modifier = Modifier.weight(1f))
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "▾",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    rotationZ = chevronRotationState.value
                },
            )
        }

        Text(
            text = notice.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) 4 else 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val r = detailRevealState.value
                        alpha = r
                        translationY = (1f - r) * -8f
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HorizontalDivider(color = borderColor.copy(alpha = 0.35f))
                Text(
                    text = notice.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyMailboxState() {
    val nextMonday = remember { WeeklyCoachReportGenerator.nextDeliveryMonday(LocalDate.now()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        WoodenMailboxArt(
            hasUnread = false,
            modifier = Modifier.size(118.dp),
        )
        Text(
            text = "아직 도착한 주간 편지가 없어요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "다음 월요일(${nextMonday.monthValue}월 ${nextMonday.dayOfMonth}일)이 시작되면\n" +
                "강아지 발자국 실링이 찍힌 첫 주간 편지가 도착할 거예요.\n" +
                "자세한 안내는 오른쪽 위 ? 버튼을 눌러 확인해 보세요!",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun MailboxEnvelopeCard(
    letter: WeeklyCoachLetter,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val cardBg = if (!letter.isRead) {
        if (isDark) Color(0xFF1D2C23) else Color(0xFFFFFCF6)
    } else {
        ItvCardSurface
    }
    val borderColor = if (!letter.isRead) {
        if (isDark) Color(0xFFD3564C) else Color(0xFFC8433A)
    } else {
        ItvInkBorder
    }

    ItvTactileCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        containerColor = cardBg,
        borderColor = borderColor,
        shadowColor = ItvInkShadow,
        borderWidth = if (!letter.isRead) 2.dp else 1.6.dp,
        shadowOffsetX = 1.5.dp,
        shadowOffsetY = 3.5.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Unread: sealed with dog-paw wax seal; Read: open top flap with letter peeking out!
            PawSealedEnvelopeArt(
                isRead = letter.isRead,
                openProgress = if (letter.isRead) 1f else 0f,
                letterSlideProgress = if (letter.isRead) 0.30f else 0f,
                modifier = Modifier.width(96.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (!letter.isRead) {
                        Color(0xFFC8433A)
                    } else {
                        if (isDark) Color(0xFF28392F) else Color(0xFFE7EFE9)
                    },
                ) {
                    Text(
                        text = if (!letter.isRead) "새 편지" else "열림",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (!letter.isRead) Color.White else ItvGreen,
                    )
                }

                Text(
                    text = letter.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = formatWeekRange(letter.weekStartDate, letter.weekEndDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Stationery letter reader with crisp, non-truncating 1-line summary cards that expand
 * effortlessly with zero frame-by-frame relayout jank:
 * - Uses [LazyColumn] with GPU [Modifier.animateItem] placement so cards below glide smoothly.
 * - Each [ExpandableLetterItemCard] owns its own local expanded state (tapping one item NEVER
 *   recomposes sibling cards or the letterhead Canvas).
 * - Detail content reveals in a single layout pass + lightweight GPU fade/slide-in.
 */
@Composable
private fun WeeklyCoachLetterReaderView(
    letter: WeeklyCoachLetter,
    fromUnseal: Boolean = false,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = LocalDarkTheme.current
    val stationeryBg = if (isDark) Color(0xFF17221C) else Color(0xFFFFFCF5)
    val stationeryShape = RoundedCornerShape(20.dp)
    val itemPlacementSpec = tween<androidx.compose.ui.unit.IntOffset>(
        durationMillis = 170,
        easing = FastOutSlowInEasing,
    )

    // When arriving from the unfold animation, keep the stationery sheet 100% solid (zero blink!)
    // and smoothly fade in only the letter content. When opening directly, gently fade+lift the whole view.
    val enterProgress = remember(letter.id, fromUnseal) { Animatable(0f) }
    LaunchedEffect(letter.id, fromUnseal) {
        enterProgress.animateTo(1f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (!fromUnseal) {
                    alpha = enterProgress.value
                    translationY = (1f - enterProgress.value) * 20f
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Top navigation bar inside reader
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = ItvCardSurface,
                border = BorderStroke(1.4.dp, ItvInkBorder),
                modifier = Modifier
                    .graphicsLayer { alpha = if (fromUnseal) enterProgress.value else 1f }
                    .clickable(onClick = onBack),
            ) {
                Text(
                    text = "‹ 우편함",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (letter.isPreview) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isDark) Color(0xFF23382C) else Color(0xFFDCF0E2),
                ) {
                    Text(
                        text = "미리보기",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ItvGreen,
                    )
                }
            }

            DialogCloseButton(onClick = onDismiss)
        }

        // Scrollable stationery sheet backed by LazyColumn for zero-jank item expansion
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(stationeryShape)
                .background(stationeryBg)
                .border(1.6.dp, ItvInkBorder.copy(alpha = 0.65f), stationeryShape),
        ) {
            val neutralBadgeBg = if (isDark) Color(0xFF28362D) else Color(0xFFEAE2D4)

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (fromUnseal) {
                            alpha = enterProgress.value
                        }
                    },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                // Stationery letterhead with crimson dog-paw wax seal
                item(key = "letterhead", contentType = "letterhead") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        PawWaxSealEmblem(isAccentCrimson = true)
                        Text(
                            text = "FROM. 나의 생활 코치",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = ItvGreen,
                        )
                        Text(
                            text = letter.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ItvInsetSurface)
                                .border(1.dp, ItvInkBorder.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "${formatWeekRange(letter.weekStartDate, letter.weekEndDate)} · ${letter.stats.activeDays}일 기록",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "항목을 누르면 자세한 내용이 펼쳐져요",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        )
                    }
                }

                // Warm, number-free Monday Coach greeting tailored to the user's weekly flow
                item(key = "callout", contentType = "expandable_card") {
                    ExpandableLetterItemCard(
                        itemKey = "${letter.id}_greeting",
                        headline = "월요일 코치 인사",
                        badgeText = null,
                        leadingEmoji = "💌",
                        containerColor = if (isDark) Color(0xFF1D3528) else Color(0xFFE7F4EA),
                        borderColor = ItvGreen.copy(alpha = 0.48f),
                        badgeBg = ItvGreen,
                        badgeContentColor = Color.White,
                        modifier = Modifier.animateItem(placementSpec = itemPlacementSpec),
                    ) {
                        Text(
                            text = letter.greeting,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp,
                        )
                    }
                }

                // Section 1: ✨ 지난주 대비 나아진 점
                item(key = "sec_wins", contentType = "section_header") {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .animateItem(placementSpec = itemPlacementSpec),
                    ) {
                        LetterSectionHeader(emoji = "✨", title = "지난주 대비 나아진 점")
                    }
                }
                items(
                    items = letter.improvements,
                    key = { "win_${it.id}" },
                    contentType = { "expandable_card" },
                ) { win ->
                    ExpandableLetterItemCard(
                        itemKey = "${letter.id}_win_${win.id}",
                        headline = win.title,
                        badgeText = win.badgeText,
                        leadingEmoji = null,
                        containerColor = if (isDark) Color(0xFF1D2D24) else Color(0xFFF3F9F4),
                        borderColor = ItvGreen.copy(alpha = 0.42f),
                        badgeBg = ItvGreen,
                        badgeContentColor = Color.White,
                        modifier = Modifier.animateItem(placementSpec = itemPlacementSpec),
                    ) {
                        Text(
                            text = win.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 19.sp,
                        )
                    }
                }

                // Section 2: 🔍 이번 주 생활 패턴 발견
                if (letter.discoveries.isNotEmpty()) {
                    item(key = "sec_discoveries", contentType = "section_header") {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .animateItem(placementSpec = itemPlacementSpec),
                        ) {
                            LetterSectionHeader(emoji = "🔍", title = "이번 주 생활 패턴 발견")
                        }
                    }
                    items(
                        items = letter.discoveries,
                        key = { "disc_${it.id}" },
                        contentType = { "expandable_card" },
                    ) { disc ->
                        ExpandableLetterItemCard(
                            itemKey = "${letter.id}_disc_${disc.id}",
                            headline = disc.title,
                            badgeText = disc.highlightTag,
                            leadingEmoji = disc.category.badgeEmoji,
                            containerColor = ItvInsetSurface,
                            borderColor = ItvInkBorder.copy(alpha = 0.28f),
                            badgeBg = neutralBadgeBg,
                            badgeContentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.animateItem(placementSpec = itemPlacementSpec),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = disc.category.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ItvGreen,
                                )
                                Text(
                                    text = disc.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 19.sp,
                                )
                            }
                        }
                    }
                }

                // Section 3: 📋 지난 한 주 리듬 요약
                item(key = "sec_summary", contentType = "section_header") {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .animateItem(placementSpec = itemPlacementSpec),
                    ) {
                        LetterSectionHeader(emoji = "📋", title = "지난 한 주 리듬 요약")
                    }
                }
                item(key = "card_summary", contentType = "expandable_card") {
                    ExpandableLetterItemCard(
                        itemKey = "${letter.id}_summary",
                        headline = "일평균 ${WeeklyCoachReportGenerator.formatMinutesShort(letter.stats.avgDailyPhoneMinutes)}",
                        badgeText = "${letter.stats.activeDays}일 기록",
                        leadingEmoji = null,
                        containerColor = ItvInsetSurface,
                        borderColor = ItvInkBorder.copy(alpha = 0.28f),
                        badgeBg = neutralBadgeBg,
                        badgeContentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.animateItem(placementSpec = itemPlacementSpec),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            QuietMetricPill(
                                label = "일평균 폰",
                                value = WeeklyCoachReportGenerator.formatMinutesShort(letter.stats.avgDailyPhoneMinutes),
                                sub = letter.stats.prevAvgDailyPhoneMinutes?.let {
                                    "전주 ${WeeklyCoachReportGenerator.formatMinutesShort(it)}"
                                },
                                modifier = Modifier.weight(1f),
                            )
                            QuietMetricPill(
                                label = "일평균 숏폼",
                                value = WeeklyCoachReportGenerator.formatMinutesShort(letter.stats.avgDailyShortformMinutes),
                                sub = letter.stats.prevAvgDailyShortformMinutes?.let {
                                    "전주 ${WeeklyCoachReportGenerator.formatMinutesShort(it)}"
                                },
                                modifier = Modifier.weight(1f),
                            )
                            QuietMetricPill(
                                label = "평균 수면",
                                value = letter.stats.avgSleepMinutes?.let {
                                    WeeklyCoachReportGenerator.formatMinutesShort(it)
                                } ?: "기록 중",
                                sub = letter.stats.avgBedtimeMinute?.let {
                                    "${WeeklyCoachReportGenerator.formatClockMinute(it)} 취침"
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Section 4: 🐾 다음 주 코치의 한 마디
                item(key = "sec_tip", contentType = "section_header") {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .animateItem(placementSpec = itemPlacementSpec),
                    ) {
                        LetterSectionHeader(emoji = "🐾", title = "다음 주 코치의 한 마디")
                    }
                }
                item(key = "card_tip", contentType = "expandable_card") {
                    ExpandableLetterItemCard(
                        itemKey = "${letter.id}_coach_tip",
                        headline = letter.coachTipTitle,
                        badgeText = "제안",
                        leadingEmoji = null,
                        containerColor = if (isDark) Color(0xFF28221B) else Color(0xFFFFF5E6),
                        borderColor = Color(0xFFC88A3A).copy(alpha = 0.55f),
                        badgeBg = Color(0xFFC88A3A),
                        badgeContentColor = Color.White,
                        modifier = Modifier.animateItem(placementSpec = itemPlacementSpec),
                    ) {
                        Text(
                            text = letter.coachTipBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableLetterItemCard(
    itemKey: String,
    headline: String,
    badgeText: String?,
    leadingEmoji: String?,
    containerColor: Color,
    borderColor: Color,
    badgeBg: Color,
    badgeContentColor: Color,
    modifier: Modifier = Modifier,
    detailContent: @Composable () -> Unit,
) {
    var expanded by remember(itemKey) { mutableStateOf(false) }
    val interactionSource = remember(itemKey) { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.972f else 1f,
        animationSpec = tween(durationMillis = 85, easing = FastOutSlowInEasing),
        label = "letter_item_press_scale",
    )
    val pressOffsetYState = animateFloatAsState(
        targetValue = if (isPressed) 2.5f else 0f,
        animationSpec = tween(durationMillis = 85, easing = FastOutSlowInEasing),
        label = "letter_item_press_offset",
    )
    val chevronRotationState = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "letter_item_chevron",
    )
    val detailRevealState = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "letter_item_reveal",
    )

    val cardShape = RoundedCornerShape(15.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = pressScaleState.value
                scaleX = s
                scaleY = s
                translationY = pressOffsetYState.value
            }
            .clip(cardShape)
            .background(containerColor)
            .border(1.2.dp, borderColor, cardShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { expanded = !expanded }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (leadingEmoji != null) {
                Text(text = leadingEmoji, fontSize = 15.sp)
            }
            Text(
                text = headline,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badgeText != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeContentColor,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = "▾",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    rotationZ = chevronRotationState.value
                },
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val r = detailRevealState.value
                        alpha = r
                        translationY = (1f - r) * -8f
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HorizontalDivider(color = borderColor.copy(alpha = 0.35f))
                detailContent()
            }
        }
    }
}

/**
 * Detailed guide dialog opened from the `?` button inside the Coach Mailbox header.
 */
@Composable
private fun WeeklyMailboxHelpDialog(
    onDismiss: () -> Unit,
) {
    val isDark = LocalDarkTheme.current
    val sheetBg = if (isDark) Color(0xFF141E18) else Color(0xFFFFFCF6)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ItvTactileCard(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(24.dp),
            containerColor = sheetBg,
            borderColor = ItvInkBorder,
            shadowColor = ItvInkShadow,
            borderWidth = 2.dp,
            shadowOffsetX = 2.dp,
            shadowOffsetY = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "우편함 안내",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    DialogCloseButton(onClick = onDismiss)
                }

                HorizontalDivider(color = ItvInkBorder.copy(alpha = 0.2f))

                HelpInfoBullet(
                    emoji = "📬",
                    title = "개인 탭 · 매주 월요일 주간 편지",
                    body = "매주 월요일이 시작되면 방금 지난 한 주(월~일)의 기록을 모아 점수 대신 나아진 점과 나만의 생활 패턴을 편지로 전해드려요. 최근 6개월치까지 보관돼요.",
                )
                HelpInfoBullet(
                    emoji = "🔔",
                    title = "방해하지 않는 배려형 도착 알림",
                    body = "새 주간 편지가 도착해도 메신저·금융·통화·글 작성 중이거나 새벽 시간대에는 울리지 않고, 개입 가능한 여유로운 순간에만 알림으로 알려드려요.",
                )
                HelpInfoBullet(
                    emoji = "📢",
                    title = "공지 탭 · 업데이트와 소통 소식",
                    body = "앱 업데이트 공지, 이벤트, 설문조사 보상 안내, 고객센터 답변 등 운영팀의 소식을 개인 편지와 분리해 깔끔하게 확인할 수 있어요.",
                )
                HelpInfoBullet(
                    emoji = "🐾",
                    title = "봉투 상태와 편지 읽기",
                    body = "강아지 발자국 실링으로 닫혀 있는 새 편지를 누르면 실링이 열리며 편지가 꺼내져요. 한 번 확인한 편지는 열려 있는 상태로 남아 언제든 바로 다시 읽을 수 있어요.",
                )

                ItvTactileButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = ItvGreen,
                    contentColor = Color.White,
                    borderColor = ItvInkBorder,
                    shadowColor = ItvInkShadow,
                ) {
                    Text(
                        text = "확인했어요",
                        modifier = Modifier.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpInfoBullet(
    emoji: String,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = emoji, fontSize = 18.sp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun LetterSectionHeader(emoji: String, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = emoji, fontSize = 15.sp)
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun QuietMetricPill(
    label: String,
    value: String,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = ItvCardSurface,
        border = BorderStroke(1.dp, ItvInkBorder.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DialogHelpButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .requiredSize(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = ItvCardSurface,
        border = BorderStroke(1.5.dp, ItvInkBorder),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "?",
                fontSize = 15.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ItvGreen,
            )
        }
    }
}

@Composable
private fun DialogCloseButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .requiredSize(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = ItvCardSurface,
        border = BorderStroke(1.5.dp, ItvInkBorder),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "✕",
                fontSize = 14.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun formatWeekRange(start: LocalDate, end: LocalDate): String {
    val startDow = shortDow(start.dayOfWeek)
    val endDow = shortDow(end.dayOfWeek)
    return "${start.monthValue}.${start.dayOfMonth}($startDow) ~ ${end.monthValue}.${end.dayOfMonth}($endDow)"
}

private fun shortDow(dow: DayOfWeek): String = when (dow) {
    DayOfWeek.MONDAY -> "월"
    DayOfWeek.TUESDAY -> "화"
    DayOfWeek.WEDNESDAY -> "수"
    DayOfWeek.THURSDAY -> "목"
    DayOfWeek.FRIDAY -> "금"
    DayOfWeek.SATURDAY -> "토"
    DayOfWeek.SUNDAY -> "일"
}
