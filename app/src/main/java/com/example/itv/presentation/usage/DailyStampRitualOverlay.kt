package com.example.itv.presentation.usage

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.domain.records.DailyHealthTier
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvLine
import com.example.itv.presentation.components.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class StampRitualRequest(
    val date: LocalDate,
    val tier: DailyHealthTier,
    val score: Int,
    val headline: String,
    val isMorningAuto: Boolean = false,
    val isTestPreview: Boolean = false,
    val character: StampCharacterSpec = StampCharacterRegistry.default,
    val sourceCellBounds: Rect? = null
)

private data class CellHeroTransform(
    val tx: Float,
    val ty: Float,
    val scaleX: Float,
    val scaleY: Float,
    val hasCellOrigin: Boolean
)

private val RITUAL_CARD_HEIGHT_DP = 500.dp

private fun computeCellHeroTransform(
    cellBounds: Rect?,
    rootBounds: Rect?,
    density: Density
): CellHeroTransform {
    if (cellBounds != null && !cellBounds.isEmpty &&
        rootBounds != null && rootBounds.width > 1f && rootBounds.height > 1f
    ) {
        val restW = (rootBounds.width * 0.90f).coerceAtLeast(1f)
        val restH = with(density) { RITUAL_CARD_HEIGHT_DP.toPx() }.coerceAtLeast(1f)
        val tx = cellBounds.center.x - rootBounds.center.x
        val ty = cellBounds.center.y - rootBounds.center.y
        val sx = (cellBounds.width / restW).coerceIn(0.08f, 0.45f)
        val sy = (cellBounds.height / restH).coerceIn(0.08f, 0.45f)
        return CellHeroTransform(tx = tx, ty = ty, scaleX = sx, scaleY = sy, hasCellOrigin = true)
    }
    return CellHeroTransform(
        tx = 0f,
        ty = with(density) { 120.dp.toPx() },
        scaleX = 0.16f,
        scaleY = 0.16f,
        hasCellOrigin = false
    )
}

/**
 * Persists stamped calendar dates and once-per-day morning auto-ritual display tracking.
 */
internal object DailyStampPrefs {
    private const val PREF_NAME = "itv_puppy_stamps_v1"
    private const val KEY_STAMPED_DATES = "stamped_dates"
    private const val KEY_LAST_AUTO_RITUAL_DATE = "last_auto_ritual_date"
    private const val KEY_SELECTED_CHARACTER_ID = "selected_character_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getStampedDates(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_STAMPED_DATES, emptySet())?.toSet() ?: emptySet()

    fun isDateStamped(context: Context, date: LocalDate): Boolean =
        date.toString() in getStampedDates(context)

    fun markDateStamped(context: Context, date: LocalDate): Set<String> {
        val next = getStampedDates(context) + date.toString()
        prefs(context).edit().putStringSet(KEY_STAMPED_DATES, next).apply()
        return next
    }

    fun hasAutoRitualShownForToday(context: Context, today: LocalDate): Boolean =
        prefs(context).getString(KEY_LAST_AUTO_RITUAL_DATE, null) == today.toString()

    fun markAutoRitualShownForToday(context: Context, today: LocalDate) {
        prefs(context).edit().putString(KEY_LAST_AUTO_RITUAL_DATE, today.toString()).apply()
    }

    fun getSelectedCharacter(context: Context): StampCharacterSpec =
        StampCharacterRegistry.findById(prefs(context).getString(KEY_SELECTED_CHARACTER_ID, null))

    fun setSelectedCharacter(context: Context, characterId: String) {
        prefs(context).edit().putString(KEY_SELECTED_CHARACTER_ID, characterId).apply()
    }

    fun restoreFromCloud(
        context: Context,
        stampedDates: Set<String>,
        selectedCharacterId: String?,
        mergeWithLocal: Boolean
    ) {
        val mergedDates = if (mergeWithLocal) getStampedDates(context) + stampedDates else stampedDates
        val editor = prefs(context).edit().putStringSet(KEY_STAMPED_DATES, mergedDates)
        if (!selectedCharacterId.isNullOrBlank()) {
            editor.putString(KEY_SELECTED_CHARACTER_ID, selectedCharacterId)
        }
        editor.commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }
}

/**
 * Full-screen cinematic "도장 쾅!" ritual overlay:
 * 1. Zoom-In: The target calendar date cell zooms out toward the viewer until it fills nearly the screen.
 * 2. Giant Stamp Slam: A giant wooden rubber stamp hovers over the date card, slams down ("쾅!")
 *    with haptic thud, micro-shake, and ink shockwave, then lifts away revealing the stamped outline puppy.
 * 3. Tier Reaction Motion: The stamped puppy performs its tier-specific reaction
 *    (happy bounce & sparkles/hearts, trickling tears, barking sound-waves, or shivering fever steam).
 * 4. Zoom-Out: The stamped date card smoothly zooms back down into the calendar.
 */
@Composable
internal fun DailyStampRitualOverlay(
    request: StampRitualRequest,
    overlayRootBounds: Rect? = null,
    resolveCellBounds: ((LocalDate) -> Rect?)? = null,
    onStampCommitted: (() -> Unit)? = null,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isDark = LocalDarkTheme.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val inkColor = stampInkColor(request.tier)

    var measuredOverlayBounds by remember { mutableStateOf(overlayRootBounds) }
    val effectiveRootBounds = overlayRootBounds ?: measuredOverlayBounds

    // Compute exact calendar-cell origin transform on frame 0 so the card lifts straight out of the date cell!
    val initialHero = remember(request) {
        val originCell = request.sourceCellBounds ?: resolveCellBounds?.invoke(request.date)
        computeCellHeroTransform(originCell, effectiveRootBounds, density)
    }

    // Phase state & animatables
    val backdropAlpha = remember(request) { Animatable(0f) }
    val cardScaleX = remember(request) { Animatable(initialHero.scaleX) }
    val cardScaleY = remember(request) { Animatable(initialHero.scaleY) }
    val cardAlpha = remember(request) { Animatable(if (initialHero.hasCellOrigin) 0.95f else 0f) }
    val cardTranslationX = remember(request) { Animatable(initialHero.tx) }
    val cardTranslationY = remember(request) { Animatable(initialHero.ty) }

    // Continuous choreography state:
    // 1. Independent Wooden Stamp:
    //    Starts resting centered on the X-axis slightly ABOVE the target dotted circle (`tx = 0, ty = -112dp`),
    //    puppy runs straight down center behind it, hoists & slams 1:1 flush onto (0, 0),
    //    then turns around beside it and knocks it over with a tier-specific emotional TAIL WHACK!
    val stampAlpha = remember(request) { Animatable(0f) }
    val stampTxDp = remember(request) { Animatable(0f) }
    val stampTyDp = remember(request) { Animatable(-112f) }
    val stampScale = remember(request) { Animatable(0.64f) }
    val stampPitch = remember(request) { Animatable(0f) } // 0f = upright 3D oblique, 1f = flush 1:1 on paper
    val stampTipProgress = remember(request) { Animatable(0f) } // 0f = upright, 1f = knocked over on side
    val stampRollRad = remember(request) { Animatable(0f) } // rolls along curved base
    val stampRollTiltDeg = remember(request) { Animatable(0f) } // wobble while rolling
    val pressSquish = remember(request) { Animatable(0f) }

    // 2. Quadrupedal Puppy (anchored at its planted paw ground plane `(cx, cy)`):
    //    Gallops straight down center using clean blank-face intervention gallop sprites + 5-tier live
    //    expressions, braces its hind legs BEHIND the stamp while lifting/slamming with front paws,
    //    lowers onto all 4 paws beside the upright stamp, flips 180°, whacks the stamp over with its
    //    5-tier tail, and trots away!
    val gait = remember(request.tier) { tierWalkGait(request.tier) }
    val puppyAlpha = remember(request) { Animatable(0f) }
    val puppyTxDp = remember(request) { Animatable(0f) }
    val puppyTyDp = remember(request) { Animatable(-248f) }
    val puppyScale = remember(request) { Animatable(0.44f) }
    val puppyGallopProgress = remember(request) { Animatable(0f) } // 0..1 across intervention-style run-in
    val puppyWalkCycle = remember(request) { Animatable(0f) }
    val puppyWalkActive = remember(request) { Animatable(0f) }
    val puppyHoistProgress = remember(request) { Animatable(0f) } // 0 = on 4 paws, 1 = front paws holding stamp
    val tailStrikeProgress = remember(request) { Animatable(0f) } // -1 = wind-up, +1 = tail swing into stamp
    val tailStrikeFlash = remember(request) { Animatable(0f) } // comic impact sparkle/burst on stamp hit
    val hindKickProgress = remember(request) { Animatable(0f) } // 0..1 BRONZE stamp-side (right) hind-leg kick
    val staggerTiltDeg = remember(request) { Animatable(0f) } // CRITICAL dizzy swaying tilt after stamping
    val turnFlipScaleX = remember(request) { Animatable(1f) }
    val puppyMomentum = remember(request) { Animatable(gait.headNodBias) }

    // Impact & shockwave & reaction animatables
    val shakeX = remember(request) { Animatable(0f) }
    val shakeY = remember(request) { Animatable(0f) }
    val shockwave = remember(request) { Animatable(0f) }
    val kwanFlashAlpha = remember(request) { Animatable(0f) }
    val stampRevealedScale = remember(request) { Animatable(0.92f) }
    val reactionPhase = remember(request) { Animatable(0f) }
    val infoAlpha = remember(request) { Animatable(0f) }

    var isGallopPhase by remember(request) { mutableStateOf(true) }
    var isFacingBack by remember(request) { mutableStateOf(false) }
    var isStamped by remember(request) { mutableStateOf(false) }
    var canSkip by remember(request) { mutableStateOf(false) }
    var exiting by remember(request) { mutableStateOf(false) }

    suspend fun playZoomOutAndFinish() {
        if (exiting) return
        exiting = true
        val latestRoot = overlayRootBounds ?: measuredOverlayBounds
        val targetCell = resolveCellBounds?.invoke(request.date) ?: request.sourceCellBounds
        val endHero = computeCellHeroTransform(targetCell, latestRoot, density)
        val flyBackMs = 580
        val flyEasing = CubicBezierEasing(0.30f, 0.0f, 0.18f, 1.0f)

        coroutineScope {
            launch { infoAlpha.animateTo(0f, tween(160)) }
            launch { stampAlpha.animateTo(0f, tween(140)) }
            launch { puppyAlpha.animateTo(0f, tween(140)) }
            launch { backdropAlpha.animateTo(0f, tween(520, easing = FastOutSlowInEasing)) }
            launch { cardTranslationX.animateTo(endHero.tx, tween(flyBackMs, easing = flyEasing)) }
            launch { cardTranslationY.animateTo(endHero.ty, tween(flyBackMs, easing = flyEasing)) }
            launch { cardScaleX.animateTo(endHero.scaleX, tween(flyBackMs, easing = flyEasing)) }
            launch {
                if (endHero.hasCellOrigin) {
                    cardAlpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = 140,
                            delayMillis = flyBackMs - 140,
                            easing = FastOutLinearInEasing
                        )
                    )
                } else {
                    cardAlpha.animateTo(0f, tween(360, easing = FastOutLinearInEasing))
                }
            }
            cardScaleY.animateTo(endHero.scaleY, tween(flyBackMs, easing = flyEasing))
        }
        onFinished()
    }

    LaunchedEffect(request) {
        // Ensure character bitmaps are warm off the main thread before frame 1 (0ms if already preloaded!)
        withContext(Dispatchers.Default) {
            StampCharacterBitmapCache.preload(context, request.character)
        }
        // Phase 1: Target calendar date cell lifts smoothly out of the calendar
        val zoomInMs = 700
        val zoomInEasing = CubicBezierEasing(0.28f, 0.0f, 0.16f, 1.0f)
        coroutineScope {
            launch { backdropAlpha.animateTo(0.76f, tween(640, easing = FastOutSlowInEasing)) }
            launch { cardAlpha.animateTo(1f, tween(if (initialHero.hasCellOrigin) 110 else 260)) }
            launch { cardTranslationX.animateTo(0f, tween(zoomInMs, easing = zoomInEasing)) }
            launch { cardTranslationY.animateTo(0f, tween(zoomInMs, easing = zoomInEasing)) }
            launch { cardScaleX.animateTo(1f, tween(zoomInMs, easing = zoomInEasing)) }
            cardScaleY.animateTo(1f, tween(zoomInMs, easing = zoomInEasing))
        }

        // Wooden stamp is ALREADY resting beside the enlarged calendar date circle!
        launch { stampAlpha.animateTo(1f, tween(240)) }

        // Hold briefly at full card size so the date number is crystal clear before the puppy runs in
        delay(360)

        // Phase 2a: Authentic intervention-style straight-down-center gallop right up behind the resting stamp!
        // Uses clean blank-face gallop sprites (`itv_gallop_front_01..06` + `00`) with the live 5-tier expression!
        launch { puppyAlpha.animateTo(1f, tween(130)) }
        puppyMomentum.snapTo(gait.headNodBias)
        puppyGallopProgress.snapTo(0f)

        val runInMs = gait.walkInMs
        coroutineScope {
            launch {
                puppyGallopProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(runInMs, easing = LinearEasing)
                )
            }
            launch {
                puppyWalkCycle.animateTo(
                    targetValue = gait.stepCount,
                    animationSpec = tween(runInMs, easing = LinearEasing)
                )
            }
            launch { puppyTyDp.animateTo(-108f, tween(runInMs, easing = LinearEasing)) }
            launch { puppyScale.animateTo(0.86f, tween(runInMs, easing = LinearEasing)) }
        }

        // Phase 2b: Clean 1-frame handoff into the 3-layer stamping rig (100% matching head ratio!),
        // planting short chubby hind legs firmly BEHIND the stamp while lifting the wooden knob over center!
        delay(30)
        isGallopPhase = false
        val hoistMs = 300
        coroutineScope {
            launch { puppyHoistProgress.animateTo(1f, tween(hoistMs, easing = FastOutSlowInEasing)) }
            launch { puppyTyDp.animateTo(-92f, tween(hoistMs, easing = FastOutSlowInEasing)) }
            launch { puppyScale.animateTo(0.92f, tween(hoistMs, easing = FastOutSlowInEasing)) }
            launch { stampTyDp.animateTo(-30f, tween(hoistMs, easing = FastOutSlowInEasing)) }
            launch { stampScale.animateTo(1.0f, tween(hoistMs, easing = FastOutSlowInEasing)) }
            launch { stampPitch.animateTo(0.42f, tween(hoistMs, easing = FastOutSlowInEasing)) }
            launch {
                puppyMomentum.animateTo(
                    targetValue = -0.58f * gait.heaveLift + gait.headNodBias * 0.35f,
                    animationSpec = tween(hoistMs, easing = FastOutSlowInEasing)
                )
            }
        }

        // Phase 2c: Slam down flush 1:1 onto the 208.dp dotted circle ("쾅!")
        // Puppy's compact body follows the slam cleanly (`puppyTyDp = -20dp`) so neither front paws
        // nor braced hind legs ever over-stretch!
        val slamMs = gait.slamMs
        val strikeEasing = CubicBezierEasing(0.32f, 0.0f, 0.88f, 0.22f)
        coroutineScope {
            launch { puppyMomentum.animateTo(0.52f, tween(slamMs, easing = strikeEasing)) }
            launch { puppyTyDp.animateTo(-20f, tween(slamMs, easing = strikeEasing)) }
            launch { stampTyDp.animateTo(0f, tween(slamMs, easing = strikeEasing)) }
            launch { stampPitch.animateTo(1.0f, tween(slamMs, easing = strikeEasing)) }
        }

        // IMPACT MOMENT! (Base lands 1:1 flush on the 208.dp dotted target circle — 0px alignment!)
        isStamped = true
        canSkip = true
        onStampCommitted?.invoke()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        // Impact follow-through: body bounce & micro-shake & shockwave
        launch {
            val overshoot = (1.12f * gait.shakeStrength).coerceIn(0.92f, 1.32f)
            puppyMomentum.animateTo(overshoot, tween(68, easing = FastOutSlowInEasing))
            puppyMomentum.animateTo(
                targetValue = 0.28f + gait.headNodBias * 0.3f,
                animationSpec = spring(dampingRatio = 0.48f, stiffness = Spring.StiffnessMediumLow)
            )
        }
        launch {
            pressSquish.animateTo(1f, tween(55, easing = FastOutSlowInEasing))
            pressSquish.animateTo(0f, tween(135, easing = FastOutSlowInEasing))
        }
        launch {
            kwanFlashAlpha.snapTo(1f)
            kwanFlashAlpha.animateTo(0f, tween(380, easing = FastOutSlowInEasing))
        }
        launch {
            shockwave.snapTo(0.02f)
            shockwave.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        }
        launch {
            val s = gait.shakeStrength
            val shakes = listOf(14f to 9f, -12f to -7f, 8f to 5f, -4f to -3f, 0f to 0f)
            shakes.forEach { (sx, sy) ->
                launch { shakeX.animateTo(sx * s, tween(40)) }
                shakeY.animateTo(sy * s, tween(40))
            }
        }
        launch {
            stampRevealedScale.snapTo(0.95f)
            stampRevealedScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }

        // Phase 2d–2e: 5-TIER DISTINCT POST-STAMP REACTION & EXIT CHOREOGRAPHY!
        // - GOLD / SILVER: Turns naturally & starts trotting happily -> joyful wagging tail accidentally bonks stamp!
        // - BRONZE: Turns sadly with drooping tail -> kicks stamp with the STAMP-SIDE (right) hind leg!
        // - WARNING: Deliberate angry puffed-tail wind-up & fierce slam ("팍!!") with starburst & shake!
        // - CRITICAL: Staggers left/right after stamping, fails to set stamp upright, staggers away as stamp flops over!
        val tailStrike = tierTailStrikeSpec(request.tier)
        val tipForce = tailStrike.tipForce
        val rollTargetTx = tailStrike.rollTargetTxDp
        val rollTargetTy = tailStrike.rollTargetTyDp
        val tipMs = tailStrike.tipMs
        val rollMs = tailStrike.rollMs

        fun launchStampTipAndRoll(fromTipProgress: Float = 0f) {
            launch {
                coroutineScope {
                    launch {
                        stampPitch.animateTo(0f, tween((tipMs * 0.60f).roundToInt(), easing = FastOutSlowInEasing))
                    }
                    launch {
                        if (fromTipProgress > 0f) stampTipProgress.snapTo(fromTipProgress)
                        stampTipProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(tipMs, easing = CubicBezierEasing(0.32f, 0.0f, 0.64f, 1.0f))
                        )
                    }
                    launch {
                        stampScale.animateTo(0.62f, tween(rollMs, easing = FastOutSlowInEasing))
                    }
                    launch {
                        stampTxDp.animateTo(rollTargetTx, tween(rollMs, easing = CubicBezierEasing(0.18f, 0.0f, 0.24f, 1.0f)))
                    }
                    launch {
                        stampTyDp.animateTo(rollTargetTy, tween(rollMs, easing = CubicBezierEasing(0.20f, 0.0f, 0.24f, 1.0f)))
                    }
                    launch {
                        stampRollRad.animateTo(
                            targetValue = 2.6f * tipForce,
                            animationSpec = tween(rollMs, easing = CubicBezierEasing(0.16f, 0.0f, 0.22f, 1.0f))
                        )
                    }
                    launch {
                        stampRollTiltDeg.animateTo(7.5f * tipForce, tween((rollMs * 0.36f).roundToInt(), easing = FastOutSlowInEasing))
                        stampRollTiltDeg.animateTo(-4.4f * tipForce, tween((rollMs * 0.30f).roundToInt(), easing = FastOutSlowInEasing))
                        stampRollTiltDeg.animateTo(1.9f * tipForce, tween((rollMs * 0.18f).roundToInt(), easing = FastOutSlowInEasing))
                        stampRollTiltDeg.animateTo(0f, tween((rollMs * 0.16f).roundToInt(), easing = FastOutSlowInEasing))
                    }
                }
            }
            launch {
                delay(140)
                infoAlpha.animateTo(1f, tween(320))
            }
        }

        fun launchBackViewExit() {
            launch {
                puppyWalkActive.animateTo(1f, tween(80))
                val startStep = puppyWalkCycle.value
                coroutineScope {
                    launch {
                        puppyWalkCycle.animateTo(startStep + gait.exitSteps, tween(gait.exitMs, easing = LinearEasing))
                    }
                    launch {
                        puppyTxDp.animateTo(-185f, tween(gait.exitMs, easing = CubicBezierEasing(0.25f, 0.0f, 0.65f, 1.0f)))
                    }
                    launch {
                        puppyTyDp.animateTo(-165f, tween(gait.exitMs, easing = CubicBezierEasing(0.25f, 0.0f, 0.65f, 1.0f)))
                    }
                    launch {
                        puppyScale.animateTo(0.34f, tween(gait.exitMs, easing = CubicBezierEasing(0.25f, 0.0f, 0.65f, 1.0f)))
                    }
                    launch {
                        puppyAlpha.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(
                                durationMillis = (gait.exitMs * 0.30f).roundToInt(),
                                delayMillis = (gait.exitMs * 0.70f).roundToInt()
                            )
                        )
                    }
                }
            }
        }

        when (request.tier) {
            DailyHealthTier.WARNING -> {
                // 4단계 화남: 일부러 뒤돌아 빵빵한 꼬리를 젖혔다가 팍!! 강하게 쳐서 날려버림!
                delay(160)
                coroutineScope {
                    launch { puppyHoistProgress.animateTo(0f, tween(175, easing = FastOutSlowInEasing)) }
                    launch { puppyTxDp.animateTo(-52f, tween(175, easing = FastOutSlowInEasing)) }
                    launch { puppyTyDp.animateTo(-26f, tween(175, easing = FastOutSlowInEasing)) }
                    launch { puppyScale.animateTo(0.86f, tween(175, easing = FastOutSlowInEasing)) }
                    launch { puppyMomentum.animateTo(gait.headNodBias, tween(175, easing = FastOutSlowInEasing)) }
                    launch { stampPitch.animateTo(0.08f, tween(175, easing = FastOutSlowInEasing)) }
                }
                turnFlipScaleX.animateTo(0.08f, tween(68, easing = FastOutLinearInEasing))
                isFacingBack = true
                turnFlipScaleX.animateTo(1f, tween(76, easing = FastOutSlowInEasing))

                // Deliberate angry wind-up -> fierce tail slam!
                tailStrikeProgress.animateTo(
                    targetValue = tailStrike.windUpTarget,
                    animationSpec = tween(tailStrike.windUpMs, easing = FastOutSlowInEasing)
                )
                tailStrikeProgress.animateTo(
                    targetValue = tailStrike.strikeTarget,
                    animationSpec = tween(tailStrike.strikeMs, easing = CubicBezierEasing(0.18f, 0.0f, 0.25f, 1.0f))
                )
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                launch {
                    listOf(8f to 4f, -6f to -3f, 0f to 0f).forEach { (sx, sy) ->
                        launch { shakeX.animateTo(sx, tween(34)) }
                        shakeY.animateTo(sy, tween(34))
                    }
                }
                launch {
                    tailStrikeFlash.snapTo(1f)
                    tailStrikeFlash.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
                }
                launch {
                    tailStrikeProgress.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
                }
                launchStampTipAndRoll()
                launchBackViewExit()
            }

            DailyHealthTier.BRONZE -> {
                // 3단계 슬픔: 시무룩하게 뒤돌아 도장 쪽(오른쪽) 뒷다리로 힘없이 툭 차고 터벅터벅 퇴장!
                delay(180)
                coroutineScope {
                    launch { puppyHoistProgress.animateTo(0f, tween(210, easing = FastOutSlowInEasing)) }
                    launch { puppyTxDp.animateTo(-44f, tween(210, easing = FastOutSlowInEasing)) }
                    launch { puppyTyDp.animateTo(-26f, tween(210, easing = FastOutSlowInEasing)) }
                    launch { puppyScale.animateTo(0.86f, tween(210, easing = FastOutSlowInEasing)) }
                    launch { puppyMomentum.animateTo(gait.headNodBias, tween(210, easing = FastOutSlowInEasing)) }
                    launch { stampPitch.animateTo(0.08f, tween(210, easing = FastOutSlowInEasing)) }
                }
                turnFlipScaleX.animateTo(0.08f, tween(85, easing = FastOutLinearInEasing))
                isFacingBack = true
                turnFlipScaleX.animateTo(1f, tween(95, easing = FastOutSlowInEasing))

                delay(70)
                // Right (stamp-side!) hind leg extends slowly rightward to nudge the stamp over
                hindKickProgress.animateTo(1f, tween(185, easing = FastOutSlowInEasing))
                launch {
                    tailStrikeFlash.snapTo(1f)
                    tailStrikeFlash.animateTo(0f, tween(340, easing = FastOutSlowInEasing))
                }
                launchStampTipAndRoll()
                hindKickProgress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
                launchBackViewExit()
            }

            DailyHealthTier.CRITICAL -> {
                // 5단계 아픔: 도장을 찍은 뒤 비틀거리며 도장을 제대로 세워두지 못해 도장이 같이 흔들리다 쓰러짐!
                delay(130)
                // Stagger 1: leans left weakly while still holding stamp
                coroutineScope {
                    launch { staggerTiltDeg.animateTo(-11f, tween(185, easing = FastOutSlowInEasing)) }
                    launch { puppyTxDp.animateTo(-16f, tween(185, easing = FastOutSlowInEasing)) }
                    launch { puppyHoistProgress.animateTo(0.62f, tween(185, easing = FastOutSlowInEasing)) }
                    launch { stampPitch.animateTo(0.45f, tween(185, easing = FastOutSlowInEasing)) }
                    launch { stampRollTiltDeg.animateTo(-7.5f, tween(185, easing = FastOutSlowInEasing)) }
                }
                // Stagger 2: sways right trying to steady the stamp, stamp tilts off-balance!
                coroutineScope {
                    launch { staggerTiltDeg.animateTo(12.5f, tween(195, easing = FastOutSlowInEasing)) }
                    launch { puppyTxDp.animateTo(8f, tween(195, easing = FastOutSlowInEasing)) }
                    launch { puppyHoistProgress.animateTo(0.30f, tween(195, easing = FastOutSlowInEasing)) }
                    launch { stampPitch.animateTo(0.16f, tween(195, easing = FastOutSlowInEasing)) }
                    launch { stampRollTiltDeg.animateTo(12.0f, tween(195, easing = FastOutSlowInEasing)) }
                    launch { stampTipProgress.animateTo(0.18f, tween(195, easing = FastOutSlowInEasing)) }
                }
                // Stagger 3: loses grip and stumbles left onto all 4 paws -> stamp loses balance on its own!
                coroutineScope {
                    launch { staggerTiltDeg.animateTo(-9.5f, tween(190, easing = FastOutSlowInEasing)) }
                    launch { puppyTxDp.animateTo(-40f, tween(190, easing = FastOutSlowInEasing)) }
                    launch { puppyTyDp.animateTo(-26f, tween(190, easing = FastOutSlowInEasing)) }
                    launch { puppyScale.animateTo(0.86f, tween(190, easing = FastOutSlowInEasing)) }
                    launch { puppyHoistProgress.animateTo(0f, tween(190, easing = FastOutSlowInEasing)) }
                    launch { puppyMomentum.animateTo(gait.headNodBias, tween(190, easing = FastOutSlowInEasing)) }
                    launch { stampPitch.animateTo(0.06f, tween(190, easing = FastOutSlowInEasing)) }
                }
                // Unbalanced stamp flops over slowly on its own right as the dizzy puppy turns away!
                launch {
                    tailStrikeFlash.snapTo(1f)
                    tailStrikeFlash.animateTo(0f, tween(380, easing = FastOutSlowInEasing))
                }
                launchStampTipAndRoll(fromTipProgress = 0.18f)

                turnFlipScaleX.animateTo(0.08f, tween(95, easing = FastOutLinearInEasing))
                isFacingBack = true
                launch { staggerTiltDeg.animateTo(0f, tween(140, easing = FastOutSlowInEasing)) }
                turnFlipScaleX.animateTo(1f, tween(105, easing = FastOutSlowInEasing))
                launchBackViewExit()
            }

            else -> {
                // 1단계 해맑음(GOLD) & 2단계 웃음(SILVER):
                // 일부러 겨냥하지 않고 기분 좋게 뒤돌아 꼬리를 흔들며 떠나다 자연스럽게 실수로 톡! 치고 감!
                delay(150)
                coroutineScope {
                    launch { puppyHoistProgress.animateTo(0f, tween(165, easing = FastOutSlowInEasing)) }
                    launch { puppyTxDp.animateTo(-40f, tween(165, easing = FastOutSlowInEasing)) }
                    launch { puppyTyDp.animateTo(-28f, tween(165, easing = FastOutSlowInEasing)) }
                    launch { puppyScale.animateTo(0.86f, tween(165, easing = FastOutSlowInEasing)) }
                    launch { puppyMomentum.animateTo(gait.headNodBias, tween(165, easing = FastOutSlowInEasing)) }
                    launch { stampPitch.animateTo(0.08f, tween(165, easing = FastOutSlowInEasing)) }
                }
                // Natural seamless turn-around straight into joyful walking + wagging!
                turnFlipScaleX.animateTo(0.08f, tween(65, easing = FastOutLinearInEasing))
                isFacingBack = true
                // Start trotting away immediately while the wagging tail naturally sweeps across the stamp!
                launchBackViewExit()
                coroutineScope {
                    launch { turnFlipScaleX.animateTo(1f, tween(78, easing = FastOutSlowInEasing)) }
                    // Natural joyful tail swing right across the stamp mid-turn (ZERO deliberate wind-up!)
                    launch {
                        tailStrikeProgress.animateTo(
                            targetValue = tailStrike.strikeTarget,
                            animationSpec = tween(tailStrike.strikeMs, easing = FastOutSlowInEasing)
                        )
                    }
                }
                // Accidental happy tail-bonk contact ("어머 톡!") as puppy happily trots on!
                launch {
                    tailStrikeFlash.snapTo(1f)
                    tailStrikeFlash.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
                }
                launch {
                    tailStrikeProgress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
                }
                launchStampTipAndRoll()
            }
        }

        // Let the stamp finish tipping/rolling before starting the post-stamp facial reaction!
        delay((tipMs * 0.85f).roundToInt().toLong().coerceAtLeast(240L))

        // Phase 3: Tier-specific expression reaction motion (~2.0s) runs smoothly on the freshly revealed stamp!
        reactionPhase.animateTo(1f, tween(durationMillis = 1950, easing = LinearEasing))
        delay(240)

        // Phase 4: Zoom back into the calendar cell!
        if (!exiting) {
            playZoomOutAndFinish()
        }
    }

    val dayOfWeekName = request.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREA)
    val dayNumberColor = when (request.date.dayOfWeek) {
        DayOfWeek.SUNDAY -> Color(0xFFE53935)
        DayOfWeek.SATURDAY -> Color(0xFF1E88E5)
        else -> if (isDark) Color(0xFFE8F1EA) else Color(0xFF1E2433)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { measuredOverlayBounds = it.boundsInRoot() }
            .drawBehind {
                drawRect(Color.Black.copy(alpha = backdropAlpha.value))
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (canSkip && !exiting) {
                    scope.launch { playZoomOutAndFinish() }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Giant Calendar Date Cell Card (zooming from exact calendar cell to near full-screen)
        var cardOuterCoords by remember(request) { mutableStateOf<LayoutCoordinates?>(null) }
        var targetOffsetFromCardCenter by remember(request) { mutableStateOf(Offset.Zero) }

        Box(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .onGloballyPositioned { cardOuterCoords = it }
                .graphicsLayer {
                    scaleX = cardScaleX.value
                    scaleY = cardScaleY.value
                    alpha = cardAlpha.value
                    translationX = cardTranslationX.value + shakeX.value
                    translationY = cardTranslationY.value + shakeY.value
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RITUAL_CARD_HEIGHT_DP),
                shape = RoundedCornerShape(30.dp),
                color = if (isDark) Color(0xFF1E2A22) else Color(0xFFFCFDFB),
                border = BorderStroke(
                    width = 2.dp,
                    color = if (isStamped) inkColor.copy(alpha = 0.55f) else ItvLine
                ),
                shadowElevation = 18.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top of giant calendar cell: date header mimicking an enlarged calendar tile
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                text = "${request.date.year}년 ${request.date.monthValue}월",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFFA6B7A9) else Color(0xFF64748B)
                            )
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${request.date.dayOfMonth}",
                                    fontSize = 44.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = dayNumberColor,
                                    lineHeight = 46.sp
                                )
                                Text(
                                    text = "${dayOfWeekName}요일",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = dayNumberColor.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(bottom = 7.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (request.isMorningAuto) ItvGreen.copy(alpha = 0.14f) else inkColor.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = when {
                                    request.isMorningAuto -> "☀️ 어제의 도장"
                                    request.isTestPreview -> "🐾 도장 미리보기"
                                    else -> "🐾 건강 도장"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (request.isMorningAuto) ItvGreen else inkColor,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    // Center stage: Stamp target area + shockwave + animated monochrome puppy outline
                    // Measures exact unscaled center inside cardOuterCoords so the stamp bottom lands 0px dead-center!
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onGloballyPositioned { stageCoords ->
                                val outer = cardOuterCoords ?: return@onGloballyPositioned
                                val stageCenterInOuter = outer.localPositionOf(
                                    stageCoords,
                                    Offset(stageCoords.size.width * 0.5f, stageCoords.size.height * 0.5f)
                                )
                                val outerCenter = Offset(outer.size.width * 0.5f, outer.size.height * 0.5f)
                                targetOffsetFromCardCenter = stageCenterInOuter - outerCenter
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Dotted circular stamp guide matching the wooden stamp bottom 1:1 (208.dp diameter)
                        if (!isStamped) {
                            Canvas(modifier = Modifier.size(208.dp)) {
                                drawCircle(
                                    color = inkColor.copy(alpha = 0.22f),
                                    radius = size.minDimension * 0.50f,
                                    style = Stroke(
                                        width = 2.dp.toPx(),
                                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                            floatArrayOf(12f, 10f), 0f
                                        )
                                    )
                                )
                            }
                        }

                        // Ground shadow for the independent wooden stamp (resting beside or landing on center)
                        Canvas(modifier = Modifier.size(260.dp)) {
                            val sAlpha = stampAlpha.value
                            if (sAlpha <= 0.01f) return@Canvas
                            val tipP = stampTipProgress.value
                            val pitch = stampPitch.value
                            val rollDeg = stampRollTiltDeg.value

                            // Shadow shifts along with the stamp's tx/ty
                            val sdx = stampTxDp.value.dp.toPx()
                            val sdy = stampTyDp.value.dp.toPx()
                            val sScale = stampScale.value

                            // When tipped over on side (tipP: 0 -> 1), shadow elongates into a wider grounded ellipse
                            val baseRx = 104.dp.toPx() * sScale * lerpFloat(1.0f, 1.25f, tipP)
                            val baseRy = baseRx * lerpFloat(0.55f + 0.42f * pitch, 0.38f, tipP)
                            val shadowAlpha = (0.28f * (1f - tipP * 0.15f) * sAlpha).coerceIn(0f, 0.4f)
                            val sc = Offset(center.x + sdx, center.y + sdy + lerpFloat(0f, 32.dp.toPx() * sScale, tipP))

                            withTransform({
                                if (tipP > 0.01f) {
                                    rotate(degrees = lerpFloat(0f, 65f + rollDeg, tipP), pivot = sc)
                                }
                            }) {
                                drawOval(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = shadowAlpha),
                                            Color.Black.copy(alpha = shadowAlpha * 0.48f),
                                            Color.Transparent
                                        ),
                                        center = sc,
                                        radius = baseRx * 1.05f
                                    ),
                                    topLeft = Offset(sc.x - baseRx, sc.y - baseRy),
                                    size = Size(baseRx * 2f, baseRy * 2f)
                                )
                            }
                        }

                        // Ground shadow for the quadrupedal running/trotting puppy (aligned directly at puppy's ground plane!)
                        Canvas(modifier = Modifier.size(260.dp)) {
                            val pAlpha = puppyAlpha.value
                            if (pAlpha <= 0.01f) return@Canvas
                            val wActive = puppyWalkActive.value
                            val hoistP = puppyHoistProgress.value
                            val stepPhase = puppyWalkCycle.value * PI
                            val stepArch = abs(sin(stepPhase)).toFloat().toDouble().pow(gait.bounceSharpness.toDouble()).toFloat()
                            val bounceShrink = 1f - stepArch * 0.16f * wActive

                            val pdx = puppyTxDp.value.dp.toPx()
                            val pdy = puppyTyDp.value.dp.toPx()
                            val pScale = puppyScale.value
                            val rx = 74.dp.toPx() * pScale * bounceShrink
                            val ry = rx * 0.36f
                            val shadowAlpha = (0.24f * (1f - hoistP * 0.45f) * pAlpha).coerceIn(0f, 0.32f)
                            val sc = Offset(center.x + pdx, center.y + pdy + 6.dp.toPx() * pScale)

                            drawOval(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = shadowAlpha),
                                        Color.Black.copy(alpha = shadowAlpha * 0.42f),
                                        Color.Transparent
                                    ),
                                    center = sc,
                                    radius = rx * 1.08f
                                ),
                                topLeft = Offset(sc.x - rx, sc.y - ry),
                                size = Size(rx * 2f, ry * 2f)
                            )
                        }

                        // Ink impact shockwave ring + comic impact rays (read inside Canvas draw phase!)
                        Canvas(modifier = Modifier.size(290.dp)) {
                            val p = shockwave.value
                            if (p !in 0.01f..0.99f) return@Canvas
                            val ringR = (size.minDimension * 0.30f) * (1f + p * 0.65f)
                            drawCircle(
                                color = inkColor.copy(alpha = (1f - p) * 0.65f),
                                radius = ringR,
                                style = Stroke(width = (8.dp.toPx() * (1f - p * 0.7f)).coerceAtLeast(1.5f))
                            )
                            // 8 crisp comic impact burst lines
                            val burstCount = 8
                            for (i in 0 until burstCount) {
                                val angle = (i * (360f / burstCount) + 18f) * (PI / 180.0)
                                val innerR = ringR * 0.88f
                                val outerR = ringR * 1.14f
                                drawLine(
                                    color = inkColor.copy(alpha = (1f - p) * 0.75f),
                                    start = Offset(
                                        center.x + (cos(angle) * innerR).toFloat(),
                                        center.y + (sin(angle) * innerR).toFloat()
                                    ),
                                    end = Offset(
                                        center.x + (cos(angle) * outerR).toFloat(),
                                        center.y + (sin(angle) * outerR).toFloat()
                                    ),
                                    strokeWidth = 3.dp.toPx() * (1f - p * 0.5f),
                                    cap = StrokeCap.Round
                                )
                            }
                        }

                        // The freshly stamped character outline + tier-specific animated reaction!
                        if (isStamped) {
                            DailyHealthStampBadge(
                                tier = request.tier,
                                size = 225.dp,
                                tiltDegrees = -4f,
                                reactionPhase = reactionPhase.value,
                                character = request.character,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = stampRevealedScale.value
                                    scaleY = stampRevealedScale.value
                                }
                            )
                        }

                        // Brief "쾅!" comic impact stamp flash (animated strictly in graphicsLayer!)
                        Text(
                            text = "쾅!",
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                            color = inkColor,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 6.dp)
                                .graphicsLayer {
                                    val fa = kwanFlashAlpha.value
                                    alpha = fa
                                    rotationZ = 14f
                                    val pop = 0.85f + 0.35f * fa
                                    scaleX = pop
                                    scaleY = pop
                                }
                        )
                    }

                    // Bottom tier verdict reveal inside the giant calendar card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = infoAlpha.value },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = inkColor.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "${request.tier.label} 스탬프 · ${request.score}점",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = inkColor,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                        Text(
                            text = request.headline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDark) Color(0xFFD1DDD4) else Color(0xFF475569),
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tierReactionCaption(request.tier),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = inkColor.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // =========================================================================
            // TRUE 3-LAYER DEPTH STACK (so hind legs naturally brace BEHIND the stamp!)
            //   Layer 1 (BEHIND): Intervention gallop run-in + Braced Hind Legs/Torso + Back-View Exit
            //   Layer 2 (MIDDLE): Independent 3D Wooden Stamp (in front of hind legs & lower torso!)
            //   Layer 3 (FRONT):  Connected Front Legs/Paws gripping the wooden knob + Head/Expression
            // =========================================================================

            // Layer 1 (BEHIND Wooden Stamp):
            StampPuppyBehindLayer(
                tier = request.tier,
                character = request.character,
                gait = gait,
                isGallopPhase = isGallopPhase,
                isFacingBack = isFacingBack,
                puppyAlphaProvider = { puppyAlpha.value },
                puppyTxDpProvider = { puppyTxDp.value },
                puppyTyDpProvider = { puppyTyDp.value },
                puppyScaleProvider = { puppyScale.value },
                turnFlipScaleXProvider = { turnFlipScaleX.value },
                staggerTiltProvider = { staggerTiltDeg.value },
                gallopProgressProvider = { puppyGallopProgress.value },
                hoistProgressProvider = { puppyHoistProgress.value.coerceIn(0f, 1f) },
                pressSquishProvider = { pressSquish.value },
                puppyMomentumProvider = { puppyMomentum.value },
                walkCycleProvider = { puppyWalkCycle.value },
                stampTxDpProvider = { stampTxDp.value },
                stampTyDpProvider = { stampTyDp.value },
                stampScaleProvider = { stampScale.value },
                stampPitchProvider = { stampPitch.value.coerceIn(0f, 1f) },
                stampTipProgressProvider = { stampTipProgress.value.coerceIn(0f, 1f) },
                modifier = Modifier
                    .requiredSize(width = 640.dp, height = 820.dp)
                    .graphicsLayer {
                        alpha = puppyAlpha.value
                        translationX = targetOffsetFromCardCenter.x
                        translationY = targetOffsetFromCardCenter.y
                    }
            )

            // Layer 2 (MIDDLE): Independent 3D Wooden Stamp
            // Rests centered above the dotted circle (`0, -112dp`), sits IN FRONT OF the puppy's lower
            // torso & braced hind legs during hoist/slam, then tumbles & rolls aside!
            IndependentWoodenStamp(
                inkColor = inkColor,
                visibleProvider = { stampAlpha.value > 0.01f },
                pitchProvider = { stampPitch.value.coerceIn(0f, 1f) },
                pressSquishProvider = { pressSquish.value },
                tipProgressProvider = { stampTipProgress.value.coerceIn(0f, 1f) },
                rollRadProvider = { stampRollRad.value },
                modifier = Modifier
                    .requiredSize(width = 640.dp, height = 820.dp)
                    .graphicsLayer {
                        val sa = stampAlpha.value
                        alpha = sa
                        if (sa <= 0.01f) return@graphicsLayer
                        val scale = stampScale.value
                        val squish = pressSquish.value
                        scaleX = scale
                        scaleY = scale * (1f - squish * 0.024f)
                        translationX = targetOffsetFromCardCenter.x + stampTxDp.value.dp.toPx()
                        translationY = targetOffsetFromCardCenter.y + stampTyDp.value.dp.toPx() + squish * 3f
                        rotationZ = stampRollTiltDeg.value
                    }
            )

            // Layer 3 (FRONT — IN FRONT OF Wooden Stamp):
            // Connected front legs gripping the wooden knob + unobstructed head & 5 expressions,
            // AND the 100% unobstructed back-view puppy + 5-tier emotional exit!
            StampPuppyFrontLayer(
                tier = request.tier,
                character = request.character,
                gait = gait,
                isGallopPhase = isGallopPhase,
                isFacingBack = isFacingBack,
                puppyAlphaProvider = { puppyAlpha.value },
                puppyTxDpProvider = { puppyTxDp.value },
                puppyTyDpProvider = { puppyTyDp.value },
                puppyScaleProvider = { puppyScale.value },
                turnFlipScaleXProvider = { turnFlipScaleX.value },
                staggerTiltProvider = { staggerTiltDeg.value },
                hoistProgressProvider = { puppyHoistProgress.value.coerceIn(0f, 1f) },
                pressSquishProvider = { pressSquish.value },
                puppyMomentumProvider = { puppyMomentum.value },
                walkCycleProvider = { puppyWalkCycle.value },
                walkActiveProvider = { puppyWalkActive.value },
                tailStrikeProvider = { tailStrikeProgress.value },
                hindKickProvider = { hindKickProgress.value },
                tailStrikeFlashProvider = { tailStrikeFlash.value },
                stampTxDpProvider = { stampTxDp.value },
                stampTyDpProvider = { stampTyDp.value },
                stampScaleProvider = { stampScale.value },
                stampPitchProvider = { stampPitch.value.coerceIn(0f, 1f) },
                stampTipProgressProvider = { stampTipProgress.value.coerceIn(0f, 1f) },
                modifier = Modifier
                    .requiredSize(width = 640.dp, height = 820.dp)
                    .graphicsLayer {
                        alpha = puppyAlpha.value
                        translationX = targetOffsetFromCardCenter.x
                        translationY = targetOffsetFromCardCenter.y
                    }
            )
        }

        // Subtle skip hint at the bottom once stamped
        if (canSkip) {
            Text(
                text = "화면을 터치하면 바로 캘린더로 돌아가요",
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .graphicsLayer { alpha = 0.72f * infoAlpha.value }
            )
        }
    }
}

private fun tierReactionCaption(tier: DailyHealthTier): String = when (tier) {
    DailyHealthTier.GOLD -> "✨ 혀를 내밀고 신나게 반겨주고 있어요!"
    DailyHealthTier.SILVER -> "😊 기분 좋게 웃으며 응원하고 있어요!"
    DailyHealthTier.BRONZE -> "💧 조금 아쉬워서 눈물을 흘리고 있어요..."
    DailyHealthTier.WARNING -> "💢 멍멍! 스마트폰 사용을 조심하자고 짖고 있어요!"
    DailyHealthTier.CRITICAL -> "🤒 열이 펄펄 나고 있어요... 오늘은 꼭 쉬어주세요!"
    else -> ""
}

private data class TierTailStrikeSpec(
    val windUpTarget: Float,
    val windUpMs: Int,
    val strikeTarget: Float,
    val strikeMs: Int,
    val tipForce: Float,
    val rollTargetTxDp: Float,
    val rollTargetTyDp: Float,
    val tipMs: Int,
    val rollMs: Int
)

private fun tierTailStrikeSpec(tier: DailyHealthTier): TierTailStrikeSpec = when (tier) {
    DailyHealthTier.GOLD -> TierTailStrikeSpec(
        // 1단계 해맑음: 기분 좋게 뒤돌며 꼬리 흔들다 실수로 톡! 경쾌하게 튕겨 보내기!
        windUpTarget = 0f,
        windUpMs = 0,
        strikeTarget = 0.96f,
        strikeMs = 110,
        tipForce = 1.22f,
        rollTargetTxDp = 145f,
        rollTargetTyDp = 54f,
        tipMs = 320,
        rollMs = 470
    )
    DailyHealthTier.SILVER -> TierTailStrikeSpec(
        // 2단계 웃음: 뒤돌아 기분 좋게 꼬리 흔들다 자연스럽게 실수로 톡!
        windUpTarget = 0f,
        windUpMs = 0,
        strikeTarget = 0.78f,
        strikeMs = 115,
        tipForce = 0.96f,
        rollTargetTxDp = 122f,
        rollTargetTyDp = 48f,
        tipMs = 380,
        rollMs = 540
    )
    DailyHealthTier.BRONZE -> TierTailStrikeSpec(
        // 3단계 슬픔: 시무룩하게 뒤돌아 도장 쪽(오른쪽) 뒷다리로 힘없이 툭 차기...
        windUpTarget = 0f,
        windUpMs = 0,
        strikeTarget = 0f,
        strikeMs = 0,
        tipForce = 0.68f,
        rollTargetTxDp = 108f,
        rollTargetTyDp = 42f,
        tipMs = 500,
        rollMs = 660
    )
    DailyHealthTier.WARNING -> TierTailStrikeSpec(
        // 4단계 화남: 일부러 빵빵한 꼬리를 힘껏 젖혔다가 팍!! 강하게 쳐내기!
        windUpTarget = -0.92f,
        windUpMs = 145,
        strikeTarget = 1.38f,
        strikeMs = 88,
        tipForce = 1.48f,
        rollTargetTxDp = 176f,
        rollTargetTyDp = 64f,
        tipMs = 250,
        rollMs = 400
    )
    DailyHealthTier.CRITICAL -> TierTailStrikeSpec(
        // 5단계 아픔: 비틀거리며 도장을 제대로 세우지 못해 도장이 스스로 힘없이 쓰러짐...
        windUpTarget = 0f,
        windUpMs = 0,
        strikeTarget = 0f,
        strikeMs = 0,
        tipForce = 0.52f,
        rollTargetTxDp = 88f,
        rollTargetTyDp = 36f,
        tipMs = 580,
        rollMs = 740
    )
    else -> TierTailStrikeSpec(
        windUpTarget = 0f,
        windUpMs = 0,
        strikeTarget = 0.78f,
        strikeMs = 115,
        tipForce = 0.96f,
        rollTargetTxDp = 122f,
        rollTargetTyDp = 48f,
        tipMs = 380,
        rollMs = 540
    )
}

private data class TierWalkGait(
    val walkInMs: Int,
    val stepCount: Float,
    val waddleRollDeg: Float,
    val bounceDp: Float,
    val bounceSharpness: Float,
    val headNodBias: Float,
    val heaveLift: Float,
    val slamMs: Int,
    val shakeStrength: Float,
    val shiverAmpDp: Float,
    val wobbleIrregularity: Float,
    val exitMs: Int,
    val exitSteps: Float
)

private fun tierWalkGait(tier: DailyHealthTier): TierWalkGait = when (tier) {
    DailyHealthTier.GOLD -> TierWalkGait(
        // 1단계 해맑음: 멀리서 신나게 통통 튀며 다가오고, 뒤돌아선 헬리콥터 꼬리로 총총 퇴장!
        walkInMs = 820,
        stepCount = 5.5f,
        waddleRollDeg = 9.5f,
        bounceDp = 24f,
        bounceSharpness = 0.85f,
        headNodBias = -0.18f,
        heaveLift = 1.05f,
        slamMs = 205,
        shakeStrength = 1.0f,
        shiverAmpDp = 0f,
        wobbleIrregularity = 0f,
        exitMs = 820,
        exitSteps = 5.5f
    )
    DailyHealthTier.SILVER -> TierWalkGait(
        // 2단계 웃음: 씩씩하고 경쾌하게 아장아장 걸어오고, 꼬리를 살랑살랑 흔들며 퇴장!
        walkInMs = 880,
        stepCount = 5.0f,
        waddleRollDeg = 8.5f,
        bounceDp = 17f,
        bounceSharpness = 0.95f,
        headNodBias = -0.05f,
        heaveLift = 0.92f,
        slamMs = 225,
        shakeStrength = 0.95f,
        shiverAmpDp = 0f,
        wobbleIrregularity = 0f,
        exitMs = 880,
        exitSteps = 5.0f
    )
    DailyHealthTier.BRONZE -> TierWalkGait(
        // 3단계 슬픔: 고개를 푹 숙이고 터벅터벅 다가오고, 꼬리가 축 처진 뒷모습으로 느릿느릿 퇴장...
        walkInMs = 1160,
        stepCount = 4.5f,
        waddleRollDeg = 6.8f,
        bounceDp = 6.5f,
        bounceSharpness = 1.35f,
        headNodBias = 0.36f,
        heaveLift = 0.56f,
        slamMs = 285,
        shakeStrength = 0.72f,
        shiverAmpDp = 0f,
        wobbleIrregularity = 0f,
        exitMs = 1080,
        exitSteps = 4.0f
    )
    DailyHealthTier.WARNING -> TierWalkGait(
        // 4단계 화남: 씩씩대며 빠르고 거칠게 쿵쿵 걸어와 세게 찍고, 빳빳한 꼬리+김 뿜으며 씩씩 퇴장!
        walkInMs = 680,
        stepCount = 6.0f,
        waddleRollDeg = 13.0f,
        bounceDp = 19f,
        bounceSharpness = 0.60f,
        headNodBias = 0.16f,
        heaveLift = 1.28f,
        slamMs = 170,
        shakeStrength = 1.45f,
        shiverAmpDp = 1.8f,
        wobbleIrregularity = 0f,
        exitMs = 740,
        exitSteps = 5.5f
    )
    DailyHealthTier.CRITICAL -> TierWalkGait(
        // 5단계 아픔: 부들부들 비틀비틀 힘겹게 다가와 찍고, 축 처져 떨리는 꼬리+열기 김 뿜으며 비틀 퇴장...
        walkInMs = 1240,
        stepCount = 4.5f,
        waddleRollDeg = 14.5f,
        bounceDp = 7.5f,
        bounceSharpness = 1.25f,
        headNodBias = 0.48f,
        heaveLift = 0.44f,
        slamMs = 310,
        shakeStrength = 0.65f,
        shiverAmpDp = 4.8f,
        wobbleIrregularity = 0.45f,
        exitMs = 1140,
        exitSteps = 4.0f
    )
    else -> TierWalkGait(
        walkInMs = 860,
        stepCount = 5.0f,
        waddleRollDeg = 8.5f,
        bounceDp = 16f,
        bounceSharpness = 0.95f,
        headNodBias = 0f,
        heaveLift = 0.9f,
        slamMs = 220,
        shakeStrength = 1.0f,
        shiverAmpDp = 0f,
        wobbleIrregularity = 0f,
        exitMs = 860,
        exitSteps = 5.0f
    )
}

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

/**
 * Rigid-body 3D wooden stamp that exists independently of the character:
 * - Upright oblique view (`pitch = 0f, tipProgress = 0f`) when resting beside the card or hoisted.
 * - Flush 1:1 impact view (`pitch = 1f, tipProgress = 0f`) with 0px alignment on the 208.dp dotted circle.
 * - Continuous, pop-free 3D tip-over (`tipProgress: 0f -> 1f`): rotates smoothly onto its side
 *   around its rounded rim, gradually revealing the inked circular rubber stamping surface while
 *   rolling gently along its rounded base (`rollRad`)!
 */
@Composable
private fun IndependentWoodenStamp(
    inkColor: Color,
    visibleProvider: () -> Boolean,
    pitchProvider: () -> Float,
    pressSquishProvider: () -> Float,
    tipProgressProvider: () -> Float,
    rollRadProvider: () -> Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (!visibleProvider()) return@Canvas

        val pitch = pitchProvider()
        val pressSquish = pressSquishProvider()
        val tipP = tipProgressProvider()
        val rollRad = rollRadProvider()

        val cx = size.width * 0.5f
        val cy = size.height * 0.5f // Exact center of the bottom stamping surface when upright!

        val baseR = 104.dp.toPx()
        val woodHighlight = Color(0xFFF5D4A8)
        val woodLight = Color(0xFFDEA66E)
        val woodMid = Color(0xFFBC7D45)
        val woodDark = Color(0xFF7D4B21)
        val woodOutline = Color(0xFF593213)
        val woodStroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Upright oblique-to-flush camera angle `phi`:
        val phiRad = lerpFloat(58f, 20f, pitch) * (PI / 180.0)
        val uprightCosPhi = cos(phiRad).toFloat() // 0.53 -> 0.94

        // Continuous signed axis foreshortening as the stamp tips smoothly onto its right side:
        // > 0f: looking down onto the top wooden plate (upright)
        // = 0f: passing cleanly through edge-on mid-tumble
        // < 0f: tilted so the bottom circular inked rubber face faces left-forward toward the viewer!
        val axisCos = lerpFloat(uprightCosPhi, -0.34f, tipP)
        val absCos = abs(axisCos).coerceAtLeast(0.02f)
        val axisSin = sqrt((1f - axisCos * axisCos).coerceAtLeast(0.08f))

        // Smooth tip rotation around the right-bottom edge pivot (0° upright -> 76° resting on side)
        val tipAngleDeg = lerpFloat(0f, 76f, tipP)
        val pivot = Offset(cx + baseR * 0.35f * tipP, cy)
        val localShiftY = -tipP * baseR * 0.24f

        withTransform({
            if (tipP > 0.001f) {
                rotate(degrees = tipAngleDeg, pivot = pivot)
                translate(top = localShiftY)
            }
        }) {
            val baseRy = baseR * absCos
            val rubberSideH = baseR * 0.09f * axisSin * (1f - pitch * 0.55f * (1f - tipP))
            val woodWallH = baseR * 0.34f * axisSin
            val rubberTopY = cy - rubberSideH
            val woodTopY = rubberTopY - woodWallH

            val stemRise = baseR * 1.40f * axisSin - pressSquish * baseR * 0.05f * (1f - tipP)
            val knobY = woodTopY - stemRise
            val waistY = woodTopY - stemRise * 0.46f

            val knobScale = lerpFloat(1.0f, 1.12f, pitch * (1f - tipP))
            val knobRx = baseR * 0.41f * knobScale
            val knobRy = baseR * lerpFloat(0.37f, 0.41f, pitch * (1f - tipP)) * knobScale

            // Helper: draw wooden neck & round knob (drawn BEFORE base when tipped so base overlaps naturally,
            // or AFTER base when upright so neck rises cleanly out of the top plate!)
            fun drawNeckAndKnob() {
                val neckPath = Path().apply {
                    val baseCollarW = baseR * 0.34f
                    val waistHalfW = baseR * 0.17f
                    val topCollarW = baseR * 0.24f * knobScale
                    moveTo(cx - baseCollarW, woodTopY - baseRy * 0.04f)
                    cubicTo(
                        cx - baseCollarW * 0.82f, woodTopY - stemRise * 0.24f,
                        cx - waistHalfW, waistY + stemRise * 0.12f,
                        cx - waistHalfW, waistY
                    )
                    cubicTo(
                        cx - waistHalfW, waistY - stemRise * 0.18f,
                        cx - topCollarW * 0.90f, knobY + knobRy * 0.65f,
                        cx - topCollarW, knobY + knobRy * 0.35f
                    )
                    lineTo(cx + topCollarW, knobY + knobRy * 0.35f)
                    cubicTo(
                        cx + topCollarW * 0.90f, knobY + knobRy * 0.65f,
                        cx + waistHalfW, waistY - stemRise * 0.18f,
                        cx + waistHalfW, waistY
                    )
                    cubicTo(
                        cx + waistHalfW, waistY + stemRise * 0.12f,
                        cx + baseCollarW * 0.82f, woodTopY - stemRise * 0.24f,
                        cx + baseCollarW, woodTopY - baseRy * 0.04f
                    )
                    quadraticTo(cx, woodTopY + baseRy * 0.22f, cx - baseCollarW, woodTopY - baseRy * 0.04f)
                    close()
                }
                drawPath(
                    path = neckPath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(woodDark, woodLight, woodHighlight, woodMid, woodDark),
                        startX = cx - baseR * 0.36f,
                        endX = cx + baseR * 0.36f
                    )
                )
                drawPath(path = neckPath, color = woodOutline, style = woodStroke)

                // Round wooden knob
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFBE2C6), woodHighlight, woodLight, woodMid, woodDark),
                        center = Offset(cx - knobRx * 0.32f, knobY - knobRy * 0.32f),
                        radius = knobRx * 1.48f
                    ),
                    topLeft = Offset(cx - knobRx, knobY - knobRy),
                    size = Size(knobRx * 2f, knobRy * 2f)
                )
                drawOval(
                    color = woodOutline,
                    topLeft = Offset(cx - knobRx, knobY - knobRy),
                    size = Size(knobRx * 2f, knobRy * 2f),
                    style = woodStroke
                )
                // Specular wood highlight + rotating grain cue while rolling on its rounded base
                val grainShift = sin(rollRad * 2.0).toFloat() * knobRx * 0.28f * tipP
                drawOval(
                    color = Color.White.copy(alpha = 0.28f),
                    topLeft = Offset(cx - knobRx * 0.58f + grainShift, knobY - knobRy * 0.62f),
                    size = Size(knobRx * 0.42f, knobRy * 0.26f)
                )
                if (tipP > 0.2f) {
                    val grainX = cx + sin(rollRad * 2.4).toFloat() * knobRx * 0.52f
                    drawLine(
                        color = woodDark.copy(alpha = 0.26f * tipP),
                        start = Offset(grainX, knobY - knobRy * 0.55f),
                        end = Offset(grainX - knobRx * 0.14f, knobY + knobRy * 0.55f),
                        strokeWidth = 2.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // When bottom rubber face is tilted toward camera (`axisCos < 0`), neck & knob sit behind the wide wooden base!
            if (axisCos < 0f) {
                drawNeckAndKnob()
            }

            // Cylindrical wooden side wall
            val sideWall = Path().apply {
                moveTo(cx - baseR, woodTopY)
                if (axisCos < 0f) {
                    // Top edge curves away behind
                    arcTo(
                        rect = Rect(
                            left = cx - baseR,
                            top = woodTopY - baseRy,
                            right = cx + baseR,
                            bottom = woodTopY + baseRy
                        ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 180f,
                        forceMoveTo = false
                    )
                    lineTo(cx + baseR, rubberTopY)
                    arcTo(
                        rect = Rect(
                            left = cx - baseR,
                            top = rubberTopY - baseRy,
                            right = cx + baseR,
                            bottom = rubberTopY + baseRy
                        ),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 180f,
                        forceMoveTo = false
                    )
                } else {
                    lineTo(cx - baseR, rubberTopY)
                    arcTo(
                        rect = Rect(
                            left = cx - baseR,
                            top = rubberTopY - baseRy,
                            right = cx + baseR,
                            bottom = rubberTopY + baseRy
                        ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false
                    )
                    lineTo(cx + baseR, woodTopY)
                }
                close()
            }
            val grainOffset = sin(rollRad * 2.2).toFloat() * baseR * 0.22f * tipP
            drawPath(
                path = sideWall,
                brush = Brush.horizontalGradient(
                    colors = listOf(woodDark, woodMid, woodLight, woodHighlight, woodMid, woodDark),
                    startX = cx - baseR + grainOffset,
                    endX = cx + baseR + grainOffset
                )
            )
            drawPath(path = sideWall, color = woodOutline, style = woodStroke)

            // Rubber side band
            if (rubberSideH > 1.0f) {
                val rubberRim = Path().apply {
                    moveTo(cx - baseR * 0.975f, rubberTopY)
                    lineTo(cx - baseR * 0.975f, cy)
                    arcTo(
                        rect = Rect(
                            left = cx - baseR * 0.975f,
                            top = cy - baseRy * 0.975f,
                            right = cx + baseR * 0.975f,
                            bottom = cy + baseRy * 0.975f
                        ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false
                    )
                    lineTo(cx + baseR * 0.975f, rubberTopY)
                    close()
                }
                drawPath(path = rubberRim, color = inkColor)
                drawPath(path = rubberRim, color = Color.Black.copy(alpha = 0.22f))
                drawPath(path = rubberRim, color = woodOutline, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
            }

            if (axisCos >= 0f) {
                // Top wooden plate visible (upright / mid-tip)
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(woodHighlight, woodLight, woodMid, woodDark),
                        center = Offset(cx - baseR * 0.24f, woodTopY - baseRy * 0.34f),
                        radius = baseR * 1.40f
                    ),
                    topLeft = Offset(cx - baseR, woodTopY - baseRy),
                    size = Size(baseR * 2f, baseRy * 2f)
                )
                drawOval(
                    color = woodOutline,
                    topLeft = Offset(cx - baseR, woodTopY - baseRy),
                    size = Size(baseR * 2f, baseRy * 2f),
                    style = woodStroke
                )
                val ringRx = baseR * 0.86f
                val ringRy = baseRy * 0.86f
                drawOval(
                    color = inkColor.copy(alpha = 0.82f),
                    topLeft = Offset(cx - ringRx, woodTopY - ringRy),
                    size = Size(ringRx * 2f, ringRy * 2f),
                    style = Stroke(width = 3.2.dp.toPx())
                )
                drawNeckAndKnob()
            } else {
                // Bottom circular inked rubber pad revealed as the stamp tips onto its side & rolls!
                val padRx = baseR * 0.975f
                val padRy = baseRy * 0.975f
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(inkColor, inkColor.copy(alpha = 0.88f), Color(0xFF1F2421).copy(alpha = 0.35f)),
                        center = Offset(cx, cy),
                        radius = padRx * 1.1f
                    ),
                    topLeft = Offset(cx - padRx, cy - padRy),
                    size = Size(padRx * 2f, padRy * 2f)
                )
                // Inner raised stamp ring on the rubber face rotating subtly as it rolls!
                val innerRx = padRx * 0.78f
                val innerRy = padRy * 0.78f
                drawOval(
                    color = Color.White.copy(alpha = 0.24f),
                    topLeft = Offset(cx - innerRx, cy - innerRy),
                    size = Size(innerRx * 2f, innerRy * 2f),
                    style = Stroke(width = 2.4.dp.toPx())
                )
                drawOval(
                    color = woodOutline,
                    topLeft = Offset(cx - padRx, cy - padRy),
                    size = Size(padRx * 2f, padRy * 2f),
                    style = woodStroke
                )
            }
        }
    }
}

/**
 * Shared 3D wooden stamp knob world projection in the `640.dp x 820.dp` stage canvas space
 * (centered at the 208.dp target stamp circle). Used by both [StampPuppyBehindLayer] and
 * [StampPuppyFrontLayer] so front paws stay locked 0px onto the wooden knob through hoist & slam!
 */
private data class WoodenStampKnobWorld(
    val x: Float,
    val y: Float,
    val rx: Float,
    val ry: Float
)

private fun DrawScope.computeWoodenStampKnobWorld(
    cx: Float,
    cy: Float,
    stampTxDp: Float,
    stampTyDp: Float,
    stampScale: Float,
    stampPitch: Float,
    pressSquish: Float
): WoodenStampKnobWorld {
    val baseR = 104.dp.toPx()
    val phiRad = lerpFloat(58f, 20f, stampPitch) * (PI / 180.0)
    val axisCos = cos(phiRad).toFloat()
    val axisSin = sqrt((1f - axisCos * axisCos).coerceAtLeast(0.08f))

    val rubberSideH = baseR * 0.09f * axisSin * (1f - stampPitch * 0.55f)
    val woodWallH = baseR * 0.34f * axisSin
    val woodTopLocalY = -(rubberSideH + woodWallH)
    val stemRise = baseR * 1.40f * axisSin - pressSquish * baseR * 0.05f
    val knobLocalY = woodTopLocalY - stemRise

    val knobScale = lerpFloat(1.0f, 1.12f, stampPitch)
    val scaleY = stampScale * (1f - pressSquish * 0.024f)

    return WoodenStampKnobWorld(
        x = cx + stampTxDp.dp.toPx(),
        y = cy + stampTyDp.dp.toPx() + pressSquish * 3f + knobLocalY * scaleY,
        rx = baseR * 0.41f * knobScale * stampScale,
        ry = baseR * lerpFloat(0.37f, 0.41f, stampPitch) * knobScale * scaleY
    )
}

/**
 * Synchronized world-space pose shared between [StampPuppyBehindLayer] (hind legs & torso behind
 * the wooden stamp) and [StampPuppyFrontLayer] (front legs & head in front of the wooden stamp)
 * so both layers align with 0.0px error at every frame.
 */
private data class StampPuppyPose(
    val anchorX: Float,
    val anchorY: Float,
    val unit: Float,
    val headW: Float,
    val headH: Float,
    val headLeft: Float,
    val headTopY: Float,
    val headBottomY: Float,
    val bellyY: Float,
    val neckJoinY: Float,
    val chestHalfW: Float,
    val neckHalfW: Float,
    val effKnobX: Float,
    val effKnobY: Float,
    val effKnobRx: Float,
    val effKnobRy: Float
)

private fun DrawScope.computeStampPuppyPose(
    cx: Float,
    cy: Float,
    character: StampCharacterSpec,
    puppyTxDp: Float,
    puppyTyDp: Float,
    puppyScale: Float,
    hoistP: Float,
    pressSquish: Float,
    puppyMomentum: Float,
    stampTxDp: Float,
    stampTyDp: Float,
    stampScale: Float,
    stampPitch: Float,
    stampTipProgress: Float
): StampPuppyPose {
    val anchorX = cx + puppyTxDp.dp.toPx()
    val anchorY = cy + puppyTyDp.dp.toPx()
    val unit = 104.dp.toPx() * 0.86f * puppyScale
    val headW = unit * character.headScaleAtRest
    val headH = headW / character.aspectRatio

    val rawKnob = computeWoodenStampKnobWorld(
        cx = cx,
        cy = cy,
        stampTxDp = stampTxDp,
        stampTyDp = stampTyDp,
        stampScale = stampScale,
        stampPitch = stampPitch,
        pressSquish = pressSquish
    )
    // Release knob tracking smoothly as soon as the puppy swats/tips the stamp aside so the rolling
    // stamp never drags the puppy's head or front paws sideways!
    val knobFollow = (1f - stampTipProgress * 2.4f).coerceIn(0f, 1f)
    val restKnobX = anchorX
    val restKnobY = anchorY - unit * 0.56f
    val restKnobRx = unit * 0.44f
    val restKnobRy = unit * 0.40f

    val effKnobX = lerpFloat(restKnobX, rawKnob.x, knobFollow)
    val effKnobY = lerpFloat(restKnobY, rawKnob.y, knobFollow)
    val effKnobRx = lerpFloat(restKnobRx, rawKnob.rx, knobFollow)
    val effKnobRy = lerpFloat(restKnobRy, rawKnob.ry, knobFollow)

    val bellyY = anchorY - unit * lerpFloat(0.28f, 0.22f, hoistP)
    val standHeadBottomY = bellyY - unit * 0.66f + headH * 0.22f
    val hoistHeadBottomY = (
        effKnobY - effKnobRy * 0.72f + headH * 0.14f + puppyMomentum * unit * 0.08f
        ).coerceIn(anchorY - unit * 1.10f, anchorY - unit * 0.22f)
    val headBottomY = lerpFloat(standHeadBottomY, hoistHeadBottomY, hoistP)
    val headTopY = headBottomY - headH
    val headLeft = anchorX - headW * 0.5f

    return StampPuppyPose(
        anchorX = anchorX,
        anchorY = anchorY,
        unit = unit,
        headW = headW,
        headH = headH,
        headLeft = headLeft,
        headTopY = headTopY,
        headBottomY = headBottomY,
        bellyY = bellyY,
        neckJoinY = headBottomY - headH * 0.22f,
        chestHalfW = unit * lerpFloat(0.58f, 0.56f, hoistP),
        neckHalfW = headW * 0.21f,
        effKnobX = effKnobX,
        effKnobY = effKnobY,
        effKnobRx = effKnobRx,
        effKnobRy = effKnobRy
    )
}

/**
 * Layer 1 (BEHIND the Wooden Stamp):
 * - Phase 2a (Run-in): Renders ONLY the clean pure-white + crisp-outline blank-face intervention
 *   gallop sprites (`itv_gallop_front_01..06` + `00`) with live 5-tier facial expressions drawn
 *   directly onto the blank face (zero original face peeking out, zero dual-head overlap!).
 * - Phase 2b–2c (Hoist & Slam): Renders the puppy's peeking tail, WIDE BRACED HIND LEGS planted
 *   solidly on the paper BEHIND the wooden stamp, and plush lower torso.
 * - Phase 2d–2e (180° Turn, 5-Tier Tail Whack & Exit): Renders the 4-legged back-view puppy with
 *   expressive tier-specific tail strike motion & fluffy wagging tail as it trots away.
 */
@Composable
private fun StampPuppyBehindLayer(
    tier: DailyHealthTier,
    character: StampCharacterSpec,
    gait: TierWalkGait,
    isGallopPhase: Boolean,
    isFacingBack: Boolean,
    puppyAlphaProvider: () -> Float,
    puppyTxDpProvider: () -> Float,
    puppyTyDpProvider: () -> Float,
    puppyScaleProvider: () -> Float,
    turnFlipScaleXProvider: () -> Float,
    staggerTiltProvider: () -> Float,
    gallopProgressProvider: () -> Float,
    hoistProgressProvider: () -> Float,
    pressSquishProvider: () -> Float,
    puppyMomentumProvider: () -> Float,
    walkCycleProvider: () -> Float,
    stampTxDpProvider: () -> Float,
    stampTyDpProvider: () -> Float,
    stampScaleProvider: () -> Float,
    stampPitchProvider: () -> Float,
    stampTipProgressProvider: () -> Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val frontGallopFrames by produceState(
        initialValue = StampCharacterBitmapCache.getCachedFrontGallopFrames(character.id).orEmpty(),
        key1 = context,
        key2 = character.id
    ) {
        if (value.isEmpty()) {
            value = withContext(Dispatchers.Default) {
                StampCharacterBitmapCache.getFrontGallopFrames(context, character)
            }
        }
    }
    val simpleHeadBitmap by produceState(
        initialValue = StampCharacterBitmapCache.getCachedSimpleHead(character.id),
        key1 = context,
        key2 = character.id
    ) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                StampCharacterBitmapCache.getSimpleHead(context, character)
            }
        }
    }

    Canvas(modifier = modifier) {
        // Back-view tail-whack & exit render in StampPuppyFrontLayer so the fluffy tail hitting
        // the upright wooden stamp is 100% unobstructed in front of the stamp!
        if (isFacingBack) return@Canvas
        val pa = puppyAlphaProvider()
        if (pa <= 0.01f) return@Canvas

        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val puppyTxDp = puppyTxDpProvider()
        val puppyTyDp = puppyTyDpProvider()
        val pScale = puppyScaleProvider()
        val flipX = turnFlipScaleXProvider()
        val hoistP = hoistProgressProvider()
        val pressSquish = pressSquishProvider()
        val puppyMomentum = puppyMomentumProvider()
        val walkCycle = walkCycleProvider()

        val pawWhite = character.furColor
        val pawShade = character.furShadeColor
        val pawOutline = character.outlineColor
        val strokeW = (3.1.dp.toPx() * pScale).coerceIn(1.5f, 3.6.dp.toPx())
        val pawStroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val stepPhase = (walkCycle * PI).toFloat()

        // -------------------------------------------------------------
        // 2. CLEAN 4-LEGGED GALLOP BODY + IDENTICAL-PROPORTION CHIBI HEAD (Phase 2a)
        //    Uses the exact same `simpleHeadBitmap` & `headW/headH` formula as stamping
        //    so the face NEVER wobbles erratically and keeps a 100% consistent ratio from far to near!
        // -------------------------------------------------------------
        if (isGallopPhase) {
            if (frontGallopFrames.isNotEmpty()) {
                val gp = gallopProgressProvider().coerceIn(0f, 1f)
                val totalFrameSteps = (gait.walkInMs / 70f).coerceAtLeast(6f)
                val frameIdx = ((gp * totalFrameSteps).toInt()).mod(frontGallopFrames.size)
                val bodySprite = frontGallopFrames[frameIdx]

                // Gentle rhythmic arrival hop + soft stride bounce (zero erratic head snap!)
                val hopPx = if (gp in 0.88f..0.99f) {
                    val hopF = (gp - 0.88f) / 0.11f
                    (sin(hopF * PI) * 8.dp.toPx()).toFloat()
                } else {
                    0f
                }
                val shiverPx = if (gait.shiverAmpDp > 0f) {
                    (sin(gp * 36.0).toFloat() * gait.shiverAmpDp * pScale).dp.toPx()
                } else {
                    0f
                }

                // 4-legged running body (`y = 0.505..0.90` = 0.395 height) sized to match stamping torso 1:1
                val spriteSizePx = 264.dp.toPx() * pScale
                val footAnchorX = cx + puppyTxDp.dp.toPx() + shiverPx
                val footAnchorY = cy + puppyTyDp.dp.toPx() - hopPx
                val dstLeftF = footAnchorX - spriteSizePx * 0.50f
                val dstTopF = footAnchorY - spriteSizePx * 0.90f
                val dstDim = spriteSizePx.roundToInt().coerceAtLeast(1)

                drawImage(
                    image = bodySprite,
                    dstOffset = IntOffset(dstLeftF.roundToInt(), dstTopF.roundToInt()),
                    dstSize = IntSize(dstDim, dstDim)
                )

                // Exact same chibi head & live 5-tier expression proportions as stamping!
                val unit = 104.dp.toPx() * 0.86f * pScale
                val headW = unit * character.headScaleAtRest
                val headH = headW / character.aspectRatio
                val neckY = footAnchorY - spriteSizePx * 0.395f
                val strideBobPx = -abs(sin(stepPhase)) * 4.5.dp.toPx() * pScale
                val gentleTiltDeg = sin(stepPhase) * 2.2f

                val headBottomY = neckY + headH * 0.25f + strideBobPx
                val headTopY = headBottomY - headH
                val headLeft = footAnchorX - headW * 0.5f
                val headCenter = Offset(footAnchorX, headTopY + headH * 0.5f)

                withTransform({
                    rotate(degrees = gentleTiltDeg, pivot = headCenter)
                }) {
                    simpleHeadBitmap?.let { headBmp ->
                        drawImage(
                            image = headBmp,
                            dstOffset = IntOffset(headLeft.roundToInt(), headTopY.roundToInt()),
                            dstSize = IntSize(
                                headW.roundToInt().coerceAtLeast(1),
                                headH.roundToInt().coerceAtLeast(1)
                            )
                        )
                    }
                    drawPuppyLiveExpression(
                        tier = tier,
                        character = character,
                        imgLeft = headLeft,
                        imgTop = headTopY,
                        imgW = headW,
                        imgH = headH,
                        outlineColor = pawOutline,
                        impactIntensity = (gp * 0.20f).coerceIn(0f, 0.20f)
                    )
                }
            }
            return@Canvas
        }

        // -------------------------------------------------------------
        // 3. STAMPING RIG BEHIND LAYER: TAIL + BRACED HIND LEGS + TORSO
        //    (Drawn BEHIND IndependentWoodenStamp so hind legs visibly
        //     brace solidly behind the stamp instead of on top of it!)
        // -------------------------------------------------------------
        val pose = computeStampPuppyPose(
            cx = cx,
            cy = cy,
            character = character,
            puppyTxDp = puppyTxDp,
            puppyTyDp = puppyTyDp,
            puppyScale = pScale,
            hoistP = hoistP,
            pressSquish = pressSquish,
            puppyMomentum = puppyMomentum,
            stampTxDp = stampTxDpProvider(),
            stampTyDp = stampTyDpProvider(),
            stampScale = stampScaleProvider(),
            stampPitch = stampPitchProvider(),
            stampTipProgress = stampTipProgressProvider()
        )

        val staggerDeg = staggerTiltProvider()
        withTransform({
            if (abs(staggerDeg) > 0.05f) {
                rotate(degrees = staggerDeg, pivot = Offset(pose.anchorX, pose.anchorY))
            }
            if (abs(flipX - 1f) > 0.002f) {
                scale(scaleX = flipX, scaleY = 1f, pivot = Offset(pose.anchorX, pose.anchorY))
            }
        }) {
            // 3a. Fluffy wagging tail peeking gently behind the rump
            val tailAnchor = Offset(pose.anchorX - pose.chestHalfW * 0.82f, pose.bellyY - pose.unit * 0.22f)
            withTransform({
                rotate(degrees = -30f, pivot = tailAnchor)
                scale(scaleX = 0.80f, scaleY = 0.80f, pivot = tailAnchor)
            }) {
                drawSeamlessExpressiveTail(
                    tier = tier,
                    tailAnchor = tailAnchor,
                    baseR = pose.unit,
                    walkStepPhase = stepPhase,
                    pawWhite = pawWhite,
                    pawShade = pawShade,
                    pawOutline = pawOutline,
                    pawStroke = pawStroke
                )
            }

            // 3b. Wide, sturdy BRACED HIND LEGS planted firmly on the paper BEHIND the wooden stamp!
            drawBracedQuadrupedHindLeg(
                side = -1f,
                pose = pose,
                hoistP = hoistP,
                pressSquish = pressSquish,
                puppyMomentum = puppyMomentum,
                pawWhite = pawWhite,
                pawShade = pawShade,
                pawOutline = pawOutline,
                pawStroke = pawStroke,
                alpha = 1f
            )
            drawBracedQuadrupedHindLeg(
                side = +1f,
                pose = pose,
                hoistP = hoistP,
                pressSquish = pressSquish,
                puppyMomentum = puppyMomentum,
                pawWhite = pawWhite,
                pawShade = pawShade,
                pawOutline = pawOutline,
                pawStroke = pawStroke,
                alpha = 1f
            )

            // 3c. Single Continuous Plush White Puppy Torso & Chest (open top fuses under cheeks!)
            val torsoOutline = Path().apply {
                moveTo(pose.anchorX - pose.neckHalfW, pose.neckJoinY)
                cubicTo(
                    pose.anchorX - pose.chestHalfW * 1.08f, lerpFloat(pose.neckJoinY, pose.bellyY, 0.36f),
                    pose.anchorX - pose.chestHalfW * 1.06f, lerpFloat(pose.neckJoinY, pose.bellyY, 0.84f),
                    pose.anchorX - pose.chestHalfW * 0.62f, pose.bellyY
                )
                quadraticTo(pose.anchorX, pose.bellyY + pose.unit * 0.14f, pose.anchorX + pose.chestHalfW * 0.62f, pose.bellyY)
                cubicTo(
                    pose.anchorX + pose.chestHalfW * 1.06f, lerpFloat(pose.neckJoinY, pose.bellyY, 0.84f),
                    pose.anchorX + pose.chestHalfW * 1.08f, lerpFloat(pose.neckJoinY, pose.bellyY, 0.36f),
                    pose.anchorX + pose.neckHalfW, pose.neckJoinY
                )
            }
            val torsoFill = Path().apply {
                addPath(torsoOutline)
                close()
            }
            drawPath(
                path = torsoFill,
                brush = Brush.verticalGradient(
                    colors = listOf(pawWhite, pawWhite, pawShade.copy(alpha = 0.85f)),
                    startY = pose.neckJoinY,
                    endY = pose.bellyY + pose.unit * 0.16f
                )
            )
            drawPath(path = torsoOutline, color = pawOutline, style = pawStroke)
        }
    }
}

/**
 * Layer 3 (IN FRONT OF the Wooden Stamp):
 * - Active during grab/hoist, slam ("쾅!"), and lowering back onto all 4 paws.
 * - Draws the connected front legs & fluffy 솜방망이 paws wrapping seamlessly from the shoulders
 *   around the front/sides of the wooden stamp knob (0px locked via [computeWoodenStampKnobWorld]!),
 *   plus the 100% unobstructed ITV puppy head & live 5-tier facial expression right above the knob!
 * - During the back-view tail-whack moment (`isFacingBack && flash > 0`), renders the 5-tier
 *   emotional comic contact burst directly at the tail-to-stamp impact point in front of the stamp!
 */
@Composable
private fun StampPuppyFrontLayer(
    tier: DailyHealthTier,
    character: StampCharacterSpec,
    gait: TierWalkGait,
    isGallopPhase: Boolean,
    isFacingBack: Boolean,
    puppyAlphaProvider: () -> Float,
    puppyTxDpProvider: () -> Float,
    puppyTyDpProvider: () -> Float,
    puppyScaleProvider: () -> Float,
    turnFlipScaleXProvider: () -> Float,
    staggerTiltProvider: () -> Float,
    hoistProgressProvider: () -> Float,
    pressSquishProvider: () -> Float,
    puppyMomentumProvider: () -> Float,
    walkCycleProvider: () -> Float,
    walkActiveProvider: () -> Float,
    tailStrikeProvider: () -> Float,
    hindKickProvider: () -> Float,
    tailStrikeFlashProvider: () -> Float,
    stampTxDpProvider: () -> Float,
    stampTyDpProvider: () -> Float,
    stampScaleProvider: () -> Float,
    stampPitchProvider: () -> Float,
    stampTipProgressProvider: () -> Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val simpleHeadBitmap by produceState(
        initialValue = StampCharacterBitmapCache.getCachedSimpleHead(character.id),
        key1 = context,
        key2 = character.id
    ) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                StampCharacterBitmapCache.getSimpleHead(context, character)
            }
        }
    }
    val simpleBackHeadBitmap by produceState(
        initialValue = StampCharacterBitmapCache.getCachedSimpleBackHead(character.id),
        key1 = context,
        key2 = character.id
    ) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                StampCharacterBitmapCache.getSimpleBackHead(context, character)
            }
        }
    }

    Canvas(modifier = modifier) {
        if (isGallopPhase) return@Canvas
        val pa = puppyAlphaProvider()
        if (pa <= 0.01f) return@Canvas

        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val pScale = puppyScaleProvider()
        val flipX = turnFlipScaleXProvider()
        val staggerDeg = staggerTiltProvider()

        // -------------------------------------------------------------
        // BACK-VIEW 5-TIER EXIT (unobstructed in front of wooden stamp!)
        // -------------------------------------------------------------
        if (isFacingBack) {
            val puppyTxDp = puppyTxDpProvider()
            val puppyTyDp = puppyTyDpProvider()
            val walkCycle = walkCycleProvider()
            val walkActive = walkActiveProvider()
            val tailStrike = tailStrikeProvider()
            val hindKick = hindKickProvider()
            val stepPhase = (walkCycle * PI).toFloat()
            val stepSin = sin(stepPhase)

            val stepArch = abs(stepSin).toDouble().pow(gait.bounceSharpness.toDouble()).toFloat()
            val wobbleExtra = if (gait.wobbleIrregularity > 0f) {
                sin(stepPhase * 0.43 + 0.8).toFloat() * gait.wobbleIrregularity
            } else {
                0f
            }
            val shiverXDp = if (gait.shiverAmpDp > 0f) {
                sin(stepPhase * 7.5 + tailStrike * 14.0).toFloat() * gait.shiverAmpDp * walkActive.coerceAtLeast(abs(tailStrike)) * pScale
            } else {
                0f
            }
            val strikeBodyLeanDeg = when (tier) {
                DailyHealthTier.GOLD -> tailStrike * 9f
                DailyHealthTier.SILVER -> tailStrike * 6f
                DailyHealthTier.BRONZE -> -hindKick * 7.5f // leans left onto planted left leg as right leg kicks right!
                DailyHealthTier.WARNING -> tailStrike * 15f
                DailyHealthTier.CRITICAL -> 0f
                else -> tailStrike * 6f
            }
            val strikeHopPx = when (tier) {
                DailyHealthTier.GOLD -> abs(tailStrike) * 6.dp.toPx() * pScale
                DailyHealthTier.SILVER -> abs(tailStrike) * 3.dp.toPx() * pScale
                DailyHealthTier.WARNING -> tailStrike.coerceAtLeast(0f) * 6.dp.toPx() * pScale
                else -> 0f
            }
            val walkRollDeg = (stepSin + wobbleExtra) * gait.waddleRollDeg * 0.65f * walkActive + strikeBodyLeanDeg + staggerDeg
            val walkBouncePx = (stepArch * gait.bounceDp * 0.78f * walkActive * pScale).dp.toPx() + strikeHopPx

            val exitAnchorX = cx + (puppyTxDp + shiverXDp).dp.toPx()
            val exitAnchorY = cy + puppyTyDp.dp.toPx() - walkBouncePx
            val unit = 104.dp.toPx() * 0.86f
            val headW = unit * character.headScaleAtRest
            val headH = headW / character.aspectRatio

            withTransform({
                translate(left = exitAnchorX - cx, top = exitAnchorY - cy)
                rotate(degrees = walkRollDeg, pivot = Offset(cx, cy))
                scale(scaleX = pScale * flipX, scaleY = pScale, pivot = Offset(cx, cy))
            }) {
                drawBackViewTrottingPuppy(
                    tier = tier,
                    simpleBackHeadBitmap = simpleBackHeadBitmap,
                    cx = cx,
                    groundY = cy,
                    unit = unit,
                    headW = headW,
                    headH = headH,
                    walkStepPhase = stepPhase,
                    walkStepSin = stepSin,
                    walkActive = walkActive,
                    tailStrike = tailStrike,
                    hindKick = hindKick,
                    pawWhite = character.furColor,
                    pawShade = character.furShadeColor,
                    pawOutline = character.outlineColor,
                    pawStroke = Stroke(width = 3.1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            val flash = tailStrikeFlashProvider()
            if (flash > 0.01f) {
                val sTx = stampTxDpProvider()
                val sTy = stampTyDpProvider()
                val hitX = if (tier == DailyHealthTier.BRONZE) {
                    cx + (puppyTxDp + 46f).dp.toPx()
                } else {
                    cx + lerpFloat(puppyTxDp + 34f, sTx, 0.50f).dp.toPx()
                }
                val hitY = if (tier == DailyHealthTier.BRONZE) {
                    cy + (puppyTyDp - 14f).dp.toPx()
                } else {
                    cy + lerpFloat(puppyTyDp - 44f, sTy - 44f, 0.50f).dp.toPx()
                }
                drawTailStrikeImpactEffect(
                    tier = tier,
                    center = Offset(hitX, hitY),
                    flash = flash,
                    unit = 86.dp.toPx() * pScale,
                    outlineColor = character.outlineColor
                )
            }
            return@Canvas
        }

        val hoistP = hoistProgressProvider()
        val pressSquish = pressSquishProvider()
        val puppyMomentum = puppyMomentumProvider()

        val pose = computeStampPuppyPose(
            cx = cx,
            cy = cy,
            character = character,
            puppyTxDp = puppyTxDpProvider(),
            puppyTyDp = puppyTyDpProvider(),
            puppyScale = pScale,
            hoistP = hoistP,
            pressSquish = pressSquish,
            puppyMomentum = puppyMomentum,
            stampTxDp = stampTxDpProvider(),
            stampTyDp = stampTyDpProvider(),
            stampScale = stampScaleProvider(),
            stampPitch = stampPitchProvider(),
            stampTipProgress = stampTipProgressProvider()
        )

        val pawWhite = character.furColor
        val pawShade = character.furShadeColor
        val pawOutline = character.outlineColor
        val strokeW = (3.1.dp.toPx() * pScale).coerceIn(1.5f, 3.6.dp.toPx())
        val pawStroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)

        withTransform({
            if (abs(staggerDeg) > 0.05f) {
                rotate(degrees = staggerDeg, pivot = Offset(pose.anchorX, pose.anchorY))
            }
            if (abs(flipX - 1f) > 0.002f) {
                scale(scaleX = flipX, scaleY = 1f, pivot = Offset(pose.anchorX, pose.anchorY))
            }
        }) {
            // 1. Connected Front Legs & 솜방망이 Paws reaching forward around the wooden stamp knob!
            drawSeamlessQuadrupedFrontLeg(
                side = -1f,
                pose = pose,
                hoistP = hoistP,
                pressSquish = pressSquish,
                puppyMomentum = puppyMomentum,
                pawWhite = pawWhite,
                pawShade = pawShade,
                pawOutline = pawOutline,
                pawStroke = pawStroke,
                alpha = 1f
            )
            drawSeamlessQuadrupedFrontLeg(
                side = +1f,
                pose = pose,
                hoistP = hoistP,
                pressSquish = pressSquish,
                puppyMomentum = puppyMomentum,
                pawWhite = pawWhite,
                pawShade = pawShade,
                pawOutline = pawOutline,
                pawStroke = pawStroke,
                alpha = 1f
            )

            // 2. Real ITV Puppy Head + Live 5-Tier Facial Expression (100% unobstructed above knob!)
            val impactBounce = puppyMomentum.coerceAtLeast(0f)
            val headSquishScaleX = 1f + impactBounce * 0.045f + pressSquish * 0.026f
            val headSquishScaleY = 1f - impactBounce * 0.045f - pressSquish * 0.026f
            val headTiltDeg = -puppyMomentum * 3.8f
            val headCenter = Offset(pose.anchorX, pose.headTopY + pose.headH * 0.5f)

            withTransform({
                rotate(degrees = headTiltDeg, pivot = headCenter)
                scale(scaleX = headSquishScaleX, scaleY = headSquishScaleY, pivot = headCenter)
            }) {
                val impactIntensity = (pressSquish * 0.7f + impactBounce * 0.5f).coerceIn(0f, 1f)
                simpleHeadBitmap?.let { headBmp ->
                    drawImage(
                        image = headBmp,
                        dstOffset = IntOffset(pose.headLeft.roundToInt(), pose.headTopY.roundToInt()),
                        dstSize = IntSize(
                            pose.headW.roundToInt().coerceAtLeast(1),
                            pose.headH.roundToInt().coerceAtLeast(1)
                        )
                    )
                }
                drawPuppyLiveExpression(
                    tier = tier,
                    character = character,
                    imgLeft = pose.headLeft,
                    imgTop = pose.headTopY,
                    imgW = pose.headW,
                    imgH = pose.headH,
                    outlineColor = pawOutline,
                    impactIntensity = impactIntensity
                )
            }
        }
    }
}

/**
 * Draws the 5-tier emotional comic impact burst right where the puppy's tail strikes the upright
 * wooden stamp (`StampPuppyFrontLayer` while facing back):
 * - GOLD: Festive golden 4-point sparkles bursting joyously
 * - SILVER: Crisp sky-blue "톡!" sparkle & twin flick accents
 * - BRONZE: Soft gloomy indigo teardrop & faint sigh puff
 * - WARNING: Fierce jagged crimson/orange "팍!" comic impact starburst
 * - CRITICAL: Weak trembling pale-blue ripple & tiny dizzy ring
 */
private fun DrawScope.drawTailStrikeImpactEffect(
    tier: DailyHealthTier,
    center: Offset,
    flash: Float,
    unit: Float,
    outlineColor: Color
) {
    val a = flash.coerceIn(0f, 1f)
    val expand = 1f - a // 0 at contact -> 1 as it fades outward
    val rBase = unit * (0.34f + expand * 0.42f)

    when (tier) {
        DailyHealthTier.GOLD -> {
            val gold = Color(0xFFF59E0B).copy(alpha = a)
            val bright = Color(0xFFFDE047).copy(alpha = a)
            listOf(
                Triple(Offset(center.x + rBase * 0.42f, center.y - rBase * 0.34f), unit * 0.22f * a, gold),
                Triple(Offset(center.x + rBase * 0.78f, center.y + rBase * 0.14f), unit * 0.16f * a, bright),
                Triple(Offset(center.x + rBase * 0.12f, center.y - rBase * 0.68f), unit * 0.14f * a, gold)
            ).forEach { (pt, r, col) ->
                val star = Path().apply {
                    moveTo(pt.x, pt.y - r)
                    quadraticTo(pt.x, pt.y, pt.x + r, pt.y)
                    quadraticTo(pt.x, pt.y, pt.x, pt.y + r)
                    quadraticTo(pt.x, pt.y, pt.x - r, pt.y)
                    quadraticTo(pt.x, pt.y, pt.x, pt.y - r)
                    close()
                }
                drawPath(star, color = col)
            }
            // Playful radial sparkle rays
            val rayStroke = (2.6.dp.toPx() * a).coerceAtLeast(1f)
            listOf(-38f, 4f, 42f).forEach { deg ->
                val rad = Math.toRadians(deg.toDouble())
                val p0 = Offset(center.x + cos(rad).toFloat() * rBase * 0.30f, center.y + sin(rad).toFloat() * rBase * 0.30f)
                val p1 = Offset(center.x + cos(rad).toFloat() * rBase * 0.82f, center.y + sin(rad).toFloat() * rBase * 0.82f)
                drawLine(gold, p0, p1, strokeWidth = rayStroke, cap = StrokeCap.Round)
            }
        }

        DailyHealthTier.SILVER -> {
            val sky = Color(0xFF38BDF8).copy(alpha = a)
            val pt = Offset(center.x + rBase * 0.44f, center.y - rBase * 0.20f)
            val r = unit * 0.18f * a
            val star = Path().apply {
                moveTo(pt.x, pt.y - r)
                quadraticTo(pt.x, pt.y, pt.x + r, pt.y)
                quadraticTo(pt.x, pt.y, pt.x, pt.y + r)
                quadraticTo(pt.x, pt.y, pt.x - r, pt.y)
                quadraticTo(pt.x, pt.y, pt.x, pt.y - r)
                close()
            }
            drawPath(star, color = sky)
            // Crisp comic "톡!" twin flick lines
            val rayStroke = (2.3.dp.toPx() * a).coerceAtLeast(1f)
            listOf(-26f, 22f).forEach { deg ->
                val rad = Math.toRadians(deg.toDouble())
                val p0 = Offset(center.x + cos(rad).toFloat() * rBase * 0.25f, center.y + sin(rad).toFloat() * rBase * 0.25f)
                val p1 = Offset(center.x + cos(rad).toFloat() * rBase * 0.72f, center.y + sin(rad).toFloat() * rBase * 0.72f)
                drawLine(sky, p0, p1, strokeWidth = rayStroke, cap = StrokeCap.Round)
            }
        }

        DailyHealthTier.BRONZE -> {
            // Soft gloomy sigh puff + tiny indigo droplet
            val gloom = Color(0xFF6366F1).copy(alpha = a * 0.70f)
            val puffC = Offset(center.x + rBase * 0.34f, center.y + rBase * 0.10f)
            drawCircle(
                color = Color(0xFFCBD5E1).copy(alpha = a * 0.55f),
                radius = unit * (0.14f + expand * 0.10f),
                center = puffC
            )
            val dropC = Offset(center.x + rBase * 0.56f, center.y - rBase * 0.12f + expand * unit * 0.18f)
            val sw = unit * 0.085f
            val sh = sw * 1.45f
            val drop = Path().apply {
                moveTo(dropC.x, dropC.y - sh * 0.5f)
                cubicTo(dropC.x + sw * 0.6f, dropC.y + sh * 0.1f, dropC.x + sw * 0.48f, dropC.y + sh * 0.5f, dropC.x, dropC.y + sh * 0.5f)
                cubicTo(dropC.x - sw * 0.48f, dropC.y + sh * 0.5f, dropC.x - sw * 0.6f, dropC.y + sh * 0.1f, dropC.x, dropC.y - sh * 0.5f)
                close()
            }
            drawPath(drop, color = gloom)
        }

        DailyHealthTier.WARNING -> {
            // Fierce jagged comic "팍!!" starburst + radial shock lines!
            val red = Color(0xFFEF4444).copy(alpha = a)
            val orange = Color(0xFFF97316).copy(alpha = a * 0.90f)
            val spikes = 9
            val burst = Path().apply {
                for (i in 0 until spikes * 2) {
                    val ang = Math.toRadians((i * 180.0 / spikes) - 18.0)
                    val rr = if (i % 2 == 0) rBase * 0.96f else rBase * 0.40f
                    val px = center.x + cos(ang).toFloat() * rr
                    val py = center.y + sin(ang).toFloat() * rr
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            drawPath(burst, color = orange)
            drawPath(burst, color = red, style = Stroke(width = 2.6.dp.toPx(), join = StrokeJoin.Miter))
            // Inner white comic flash core
            drawCircle(color = Color.White.copy(alpha = a * 0.88f), radius = rBase * 0.24f, center = center)
        }

        DailyHealthTier.CRITICAL -> {
            // Weak shivering impact ripples ("툭...")
            val pale = Color(0xFF93C5FD).copy(alpha = a * 0.75f)
            val tremor = sin(expand * 24f) * 2.5.dp.toPx()
            drawCircle(
                color = pale,
                radius = rBase * 0.48f,
                center = Offset(center.x + tremor, center.y),
                style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = outlineColor.copy(alpha = a * 0.35f),
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(center.x - rBase * 0.70f, center.y - rBase * 0.70f),
                size = Size(rBase * 1.40f, rBase * 1.40f),
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        else -> Unit
    }
}

/**
 * Draws one sturdy, wide-braced hind leg planted firmly on the calendar paper BEHIND the wooden
 * stamp (`StampPuppyBehindLayer`). Because `IndependentWoodenStamp` renders in front of this layer,
 * the wide wooden stamp base naturally overlaps the puppy's lower belly/inner legs while both cute
 * braced haunches & planted hind paws peek solidly behind the left & right flanks of the stamp!
 */
private fun DrawScope.drawBracedQuadrupedHindLeg(
    side: Float,
    pose: StampPuppyPose,
    hoistP: Float,
    pressSquish: Float,
    puppyMomentum: Float,
    pawWhite: Color,
    pawShade: Color,
    pawOutline: Color,
    pawStroke: Stroke,
    alpha: Float
) {
    val unit = pose.unit
    // Compact, cute short chubby hind legs braced snugly behind the wooden stamp (never over-stretched!)
    val impactBrace = (pressSquish * 0.05f + puppyMomentum.coerceAtLeast(0f) * 0.03f) * hoistP
    val braceOut = hoistP * 0.20f + impactBrace

    val rootTopX = pose.anchorX + side * unit * 0.34f
    val rootTopY = pose.bellyY - unit * 0.28f
    val thighBulgeX = pose.anchorX + side * unit * (0.62f + braceOut * 0.80f)
    val thighBulgeY = pose.bellyY - unit * (0.08f - impactBrace * 0.14f)

    val footCenterX = pose.anchorX + side * unit * (0.44f + braceOut)
    val footBottomY = pose.anchorY - unit * 0.02f
    val pawHalfW = unit * (0.185f + impactBrace * 0.10f)
    val pawCorner = unit * 0.115f
    val pawRound = unit * 0.074f

    val outerPawX = footCenterX + side * pawHalfW
    val innerPawX = footCenterX - side * pawHalfW
    val innerRootX = pose.anchorX + side * unit * 0.24f
    val innerRootY = pose.bellyY - unit * 0.02f

    // Subtle grounded contact shadow directly under each braced hind paw on the paper!
    drawOval(
        color = Color.Black.copy(alpha = 0.16f * alpha),
        topLeft = Offset(footCenterX - pawHalfW * 1.18f, footBottomY - pawRound * 0.35f),
        size = Size(pawHalfW * 2.36f, pawRound * 1.70f)
    )

    val legOutline = Path().apply {
        moveTo(rootTopX, rootTopY)
        cubicTo(
            thighBulgeX, rootTopY + unit * 0.05f,
            thighBulgeX + side * unit * 0.02f, thighBulgeY + unit * 0.12f,
            outerPawX, footBottomY - pawCorner
        )
        // Soft round planted hind paw bracing against the paper
        cubicTo(
            outerPawX, footBottomY + pawRound,
            innerPawX, footBottomY + pawRound,
            innerPawX, footBottomY - pawCorner * 0.90f
        )
        // Smooth inner shin curving back into lower tummy behind the stamp
        cubicTo(
            innerPawX - side * unit * 0.02f, lerpFloat(footBottomY, innerRootY, 0.56f),
            innerRootX + side * unit * 0.08f, innerRootY + unit * 0.06f,
            innerRootX, innerRootY
        )
    }
    val legFill = Path().apply {
        addPath(legOutline)
        close()
    }
    drawPath(
        path = legFill,
        brush = Brush.verticalGradient(
            colors = listOf(pawShade.copy(alpha = 0.88f * alpha), pawWhite, pawShade),
            startY = rootTopY,
            endY = footBottomY + pawRound
        )
    )
    drawPath(path = legOutline, color = pawOutline, style = pawStroke)

    // Two tiny cute toe marks on each braced hind paw
    val toeStroke = (pawStroke.width * 0.66f).coerceAtLeast(1.4f)
    listOf(-0.32f, 0.28f).forEach { frac ->
        val tx = footCenterX + side * pawHalfW * frac
        drawLine(
            color = pawOutline,
            start = Offset(tx, footBottomY - pawCorner * 0.32f),
            end = Offset(tx, footBottomY + pawRound * 0.44f),
            strokeWidth = toeStroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Draws one seamlessly connected, short chubby front leg & 솜방망이 paw in [StampPuppyFrontLayer]
 * (IN FRONT OF the wooden stamp) flowing out of the puppy's chest directly beneath the cheeks
 * (open-top stroke + white fur blend = zero shoulder seam!) and locking onto the wooden stamp's
 * live knob coordinates (`pose.effKnobX/Y`) without ever stretching unnaturally long!
 */
private fun DrawScope.drawSeamlessQuadrupedFrontLeg(
    side: Float,
    pose: StampPuppyPose,
    hoistP: Float,
    pressSquish: Float,
    puppyMomentum: Float,
    pawWhite: Color,
    pawShade: Color,
    pawOutline: Color,
    pawStroke: Stroke,
    alpha: Float
) {
    val unit = pose.unit

    // Upper shoulder root tucked right beneath cheeks/chin so front legs stay short & chubby!
    val outerShoulderX = pose.anchorX + side * unit * lerpFloat(0.46f, 0.50f, hoistP)
    val outerShoulderY = lerpFloat(pose.bellyY - unit * 0.38f, pose.headBottomY - pose.headH * 0.08f, hoistP)
    val innerShoulderX = pose.anchorX + side * unit * lerpFloat(0.16f, 0.19f, hoistP)
    val innerShoulderY = lerpFloat(pose.bellyY - unit * 0.26f, pose.headBottomY - pose.headH * 0.02f, hoistP)

    // Standing paw position (on paper when hoistP = 0) vs. locked wooden-knob grip (hoistP = 1)
    val standPawX = pose.anchorX + side * unit * 0.30f
    val standPawY = pose.anchorY - unit * 0.04f

    val squishIn = pressSquish * unit * 0.03f
    val squishDown = pressSquish * unit * 0.03f
    val gripPawX = pose.effKnobX + side * (pose.effKnobRx * 0.86f - squishIn)
    val gripPawY = pose.effKnobY + pose.effKnobRy * 0.12f + squishDown

    val pawX = lerpFloat(standPawX, gripPawX, hoistP)
    val pawY = lerpFloat(standPawY, gripPawY, hoistP)

    val flexOut = (puppyMomentum.coerceAtLeast(0f) * unit * 0.08f + hoistP * unit * 0.10f)
    val pawHalfW = unit * lerpFloat(0.180f, 0.200f, hoistP)
    val pawCorner = unit * 0.120f
    val pawRound = unit * 0.082f

    val outerPawX = pawX + side * pawHalfW
    val innerPawX = pawX - side * pawHalfW

    val elbowOutX = lerpFloat(outerShoulderX, outerPawX, 0.52f) + side * flexOut
    val elbowOutY = lerpFloat(outerShoulderY, pawY, 0.52f)
    val elbowInX = lerpFloat(innerPawX, innerShoulderX, 0.48f) + side * (flexOut * 0.34f)
    val elbowInY = lerpFloat(pawY, innerShoulderY, 0.52f)

    // Soft contact shadow under each gripping paw on the wooden stamp knob
    if (hoistP > 0.2f) {
        drawOval(
            color = Color.Black.copy(alpha = 0.15f * hoistP * alpha),
            topLeft = Offset(pawX - pawHalfW * 0.95f, pawY + pawRound * 0.18f),
            size = Size(pawHalfW * 1.90f, pawRound * 1.25f)
        )
    }

    val legOutline = Path().apply {
        moveTo(outerShoulderX, outerShoulderY)
        cubicTo(
            outerShoulderX + side * (unit * 0.08f + flexOut * 0.45f), lerpFloat(outerShoulderY, elbowOutY, 0.55f),
            elbowOutX, lerpFloat(elbowOutY, pawY, 0.55f),
            outerPawX, pawY - pawCorner * 0.65f
        )
        // Soft round 솜방망이 paw tip
        cubicTo(
            outerPawX + side * unit * 0.03f, pawY + pawRound,
            innerPawX - side * unit * 0.03f, pawY + pawRound,
            innerPawX, pawY - pawCorner * 0.72f
        )
        // Inner foreleg curving smoothly back into white chest (top left open = zero shoulder seam!)
        cubicTo(
            innerPawX + side * unit * 0.02f, lerpFloat(pawY, elbowInY, 0.55f),
            elbowInX, lerpFloat(elbowInY, innerShoulderY, 0.55f),
            innerShoulderX, innerShoulderY
        )
    }
    val legFill = Path().apply {
        addPath(legOutline)
        close()
    }
    drawPath(
        path = legFill,
        brush = Brush.verticalGradient(
            colors = listOf(pawWhite, pawWhite, pawShade.copy(alpha = 0.85f * alpha)),
            startY = outerShoulderY,
            endY = pawY + pawRound
        )
    )
    drawPath(path = legOutline, color = pawOutline, style = pawStroke)

    // Seamless white fur blend over the upper shoulder root so it merges invisibly under the cheeks!
    val shoulderMidX = (outerShoulderX + innerShoulderX) * 0.5f
    val shoulderMidY = (outerShoulderY + innerShoulderY) * 0.5f
    drawCircle(
        color = pawWhite,
        radius = unit * 0.15f,
        center = Offset(shoulderMidX, shoulderMidY + unit * 0.03f)
    )

    // Two tiny cute toe-dimple marks on the rounded 솜방망이 paw
    val toeStroke = (pawStroke.width * 0.68f).coerceAtLeast(1.5f)
    listOf(-0.34f, 0.30f).forEach { frac ->
        val tx = pawX + side * pawHalfW * frac
        drawLine(
            color = pawOutline,
            start = Offset(tx, pawY - pawCorner * 0.25f),
            end = Offset(tx, pawY + pawRound * 0.45f),
            strokeWidth = toeStroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Adorable, seamlessly connected 4-legged trotting back view as the puppy exits into the distance:
 * - Uses the exact same harmonious head-to-body `unit` ratio as the front view (big cute head + plush rear!).
 * - Anchored at `groundY` for a zero-jump 180° turn-around flip.
 * - Subtle connected front legs (NOT floating ovals!) peeking gently from the shoulders behind the torso
 *   in diagonal counter-phase to the hind legs.
 * - Preserves the exact C1-continuous soft plush rear contour, faint hip crease, upturned pink paw pads,
 *   5-tier cotton-fluffy wagging tail, and back-view emotional mood effects!
 */
private fun DrawScope.drawBackViewTrottingPuppy(
    tier: DailyHealthTier,
    simpleBackHeadBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    cx: Float,
    groundY: Float,
    unit: Float,
    headW: Float,
    headH: Float,
    walkStepPhase: Float,
    walkStepSin: Float,
    walkActive: Float,
    tailStrike: Float = 0f,
    hindKick: Float = 0f,
    pawWhite: Color,
    pawShade: Color,
    pawOutline: Color,
    pawStroke: Stroke
) {
    val postureSlump = when (tier) {
        DailyHealthTier.BRONZE -> 0.08f
        DailyHealthTier.CRITICAL -> 0.11f
        DailyHealthTier.WARNING -> -0.05f
        else -> 0f
    }

    val bodySpan = unit * 1.32f
    val neckY = groundY - bodySpan
    val backHeadBottomY = neckY + headH * 0.22f + unit * postureSlump * 0.9f
    val backHeadY = backHeadBottomY - headH

    val neckHalfW = headW * 0.175f
    val shoulderHalfW = unit * (0.56f - postureSlump * 0.05f)
    val waistHalfW = unit * (0.55f - postureSlump * 0.04f)
    val hipBaseHalfW = unit * (0.67f - postureSlump * 0.04f)

    val waistY = neckY + bodySpan * 0.34f
    val hipBaseY = neckY + bodySpan * 0.58f
    val archY = neckY + bodySpan * 0.75f
    val archHalfGap = unit * 0.14f

    val leftLift = walkStepSin.coerceAtLeast(0f) * walkActive * (1f - hindKick)
    val rightLift = ((-walkStepSin).coerceAtLeast(0f) * walkActive).coerceAtLeast(hindKick * 0.75f)
    val strikeHipShift = when (tier) {
        DailyHealthTier.GOLD -> tailStrike * unit * 0.14f
        DailyHealthTier.SILVER -> tailStrike * unit * 0.09f
        DailyHealthTier.BRONZE -> -hindKick * unit * 0.06f
        DailyHealthTier.WARNING -> tailStrike * unit * 0.22f
        DailyHealthTier.CRITICAL -> 0f
        else -> tailStrike * unit * 0.09f
    }
    val hipSwayX = walkStepSin * unit * 0.048f * walkActive + strikeHipShift

    // 1. Subtle, seamlessly connected front legs curving down from shoulders behind the rear torso
    //    (diagonal counter-phase to hind legs — zero detached ovals!)
    listOf(-1f, +1f).forEach { side ->
        val frontLift = if (side < 0f) rightLift * (1f - hindKick) else leftLift
        val shX = cx + side * (shoulderHalfW * 0.85f) + hipSwayX * 0.25f
        val shY = neckY + bodySpan * 0.26f
        val pawOutX = cx + side * (waistHalfW + unit * 0.14f) + hipSwayX * 0.22f
        val pawBotY = groundY - unit * 0.14f - frontLift * unit * 0.22f
        val pawInX = cx + side * (waistHalfW * 0.60f) + hipSwayX * 0.25f

        val frontLegPath = Path().apply {
            moveTo(shX, shY)
            cubicTo(
                pawOutX + side * unit * 0.04f, lerpFloat(shY, pawBotY, 0.45f),
                pawOutX, pawBotY - unit * 0.10f,
                pawOutX - side * unit * 0.02f, pawBotY - unit * 0.04f
            )
            cubicTo(
                pawOutX - side * unit * 0.04f, pawBotY + unit * 0.08f,
                pawInX, pawBotY + unit * 0.08f,
                pawInX, pawBotY - unit * 0.06f
            )
            lineTo(shX - side * unit * 0.18f, shY + unit * 0.10f)
            close()
        }
        drawPath(
            path = frontLegPath,
            brush = Brush.verticalGradient(
                colors = listOf(pawWhite, pawShade),
                startY = shY,
                endY = pawBotY + unit * 0.08f
            )
        )
        drawPath(path = frontLegPath, color = pawOutline, style = pawStroke)
    }

    // 2. C1-continuous, soft plush rear body + gentle rounded hips + chubby hind legs
    //    (For BRONZE, the RIGHT/stamp-side hind leg kicks outward toward +X to nudge the stamp!)
    val leftHipY = hipBaseY - leftLift * unit * 0.04f
    val rightHipY = hipBaseY - rightLift * unit * 0.04f
    val leftHipHalfW = hipBaseHalfW + leftLift * unit * 0.022f
    val rightHipHalfW = hipBaseHalfW + rightLift * unit * 0.022f + hindKick * unit * 0.12f

    val leftFootY = groundY - leftLift * unit * 0.25f
    val rightFootY = groundY - rightLift * unit * 0.25f - hindKick * unit * 0.08f
    val leftAnkleY = lerpFloat(leftHipY, leftFootY, 0.66f)
    val rightAnkleY = lerpFloat(rightHipY, rightFootY, 0.66f)

    val leftWaistX = cx - waistHalfW + hipSwayX * 0.34f
    val rightWaistX = cx + waistHalfW + hipSwayX * 0.34f
    val leftHipX = cx - leftHipHalfW + hipSwayX
    val rightHipX = cx + rightHipHalfW + hipSwayX
    val leftPawOuterX = unit * (0.57f - leftLift * 0.03f)
    val rightPawOuterX = unit * (0.57f - rightLift * 0.03f) + hindKick * unit * 0.44f
    val leftPawInnerX = unit * 0.16f
    val rightPawInnerX = unit * 0.16f + hindKick * unit * 0.36f
    val leftPawOuterXPos = cx - leftPawOuterX + hipSwayX * 0.58f
    val rightPawOuterXPos = cx + rightPawOuterX + hipSwayX * 0.58f
    val leftPawInnerXPos = cx - leftPawInnerX + hipSwayX * 0.58f
    val rightPawInnerXPos = cx + rightPawInnerX + hipSwayX * 0.58f
    val pawCornerR = unit * 0.135f
    val pawRound = unit * 0.088f

    val standingBackOutline = Path().apply {
        moveTo(cx - neckHalfW, neckY)
        cubicTo(cx - shoulderHalfW * 0.98f, neckY + bodySpan * 0.12f, leftWaistX, waistY - bodySpan * 0.11f, leftWaistX, waistY)
        cubicTo(leftWaistX, waistY + bodySpan * 0.10f, leftHipX, leftHipY - bodySpan * 0.11f, leftHipX, leftHipY)
        cubicTo(leftHipX, leftHipY + bodySpan * 0.12f, leftPawOuterXPos, leftAnkleY, leftPawOuterXPos, leftFootY - pawCornerR)
        cubicTo(leftPawOuterXPos, leftFootY + pawRound, leftPawInnerXPos, leftFootY + pawRound, leftPawInnerXPos, leftFootY - pawCornerR * 0.90f)
        cubicTo(leftPawInnerXPos, leftAnkleY - bodySpan * 0.02f, cx - archHalfGap * 1.05f + hipSwayX * 0.92f, archY, cx + hipSwayX, archY)
        cubicTo(cx + archHalfGap * 1.05f + hipSwayX * 0.92f, archY, rightPawInnerXPos, rightAnkleY - bodySpan * 0.02f, rightPawInnerXPos, rightFootY - pawCornerR * 0.90f)
        cubicTo(rightPawInnerXPos, rightFootY + pawRound, rightPawOuterXPos, rightFootY + pawRound, rightPawOuterXPos, rightFootY - pawCornerR)
        cubicTo(rightPawOuterXPos, rightAnkleY, rightHipX, rightHipY + bodySpan * 0.12f, rightHipX, rightHipY)
        cubicTo(rightHipX, rightHipY - bodySpan * 0.11f, rightWaistX, waistY + bodySpan * 0.10f, rightWaistX, waistY)
        cubicTo(rightWaistX, waistY - bodySpan * 0.11f, cx + shoulderHalfW * 0.98f, neckY + bodySpan * 0.12f, cx + neckHalfW, neckY)
    }

    val standingBackFill = Path().apply {
        addPath(standingBackOutline)
        close()
    }
    drawPath(
        path = standingBackFill,
        brush = Brush.verticalGradient(
            colors = listOf(pawWhite, pawWhite, pawWhite, pawShade.copy(alpha = 0.80f)),
            startY = neckY,
            endY = groundY + pawRound
        )
    )
    drawPath(standingBackOutline, color = pawOutline, style = pawStroke)

    // Ultra-soft hip-cheek shadow curves
    val hipDetailStroke = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round)
    val hipDetailColor = pawOutline.copy(alpha = 0.055f)
    listOf(-1f, +1f).forEach { side ->
        val sideHipY = if (side < 0f) leftHipY else rightHipY
        val sideHipW = if (side < 0f) leftHipHalfW else rightHipHalfW
        val crease = Path().apply {
            moveTo(cx + hipSwayX + side * (archHalfGap * 0.92f), archY - bodySpan * 0.015f)
            quadraticTo(
                cx + hipSwayX + side * (sideHipW * 0.44f), archY - bodySpan * 0.05f,
                cx + hipSwayX + side * (sideHipW * 0.66f), sideHipY + bodySpan * 0.06f
            )
        }
        drawPath(path = crease, color = hipDetailColor, style = hipDetailStroke)
    }

    // Adorable upturned pink paw-pad beans on whichever hind foot is currently lifted
    drawLiftedHindSolePads(
        pawCenterX = cx + hipSwayX * 0.58f - (leftPawInnerX + leftPawOuterX) * 0.5f,
        pawBottomY = leftFootY,
        pawWidth = leftPawOuterX - leftPawInnerX,
        lift = leftLift,
        side = -1f,
        pawOutline = pawOutline
    )
    drawLiftedHindSolePads(
        pawCenterX = cx + hipSwayX * 0.58f + (rightPawInnerX + rightPawOuterX) * 0.5f,
        pawBottomY = rightFootY,
        pawWidth = rightPawOuterX - rightPawInnerX,
        lift = rightLift,
        side = +1f,
        pawOutline = pawOutline
    )

    // 3. Seamless back of the character's head & floppy ears
    val headLeft = cx - headW * 0.5f
    if (simpleBackHeadBitmap != null) {
        drawImage(
            image = simpleBackHeadBitmap,
            dstOffset = IntOffset(headLeft.roundToInt(), backHeadY.roundToInt()),
            dstSize = IntSize(headW.roundToInt().coerceAtLeast(1), headH.roundToInt().coerceAtLeast(1))
        )
    }

    // 4. Plump, cotton-fluffy ("복실복실") 5-tier tail seamlessly growing from croup
    val tailAnchor = Offset(cx + hipSwayX, neckY + bodySpan * 0.58f)
    drawSeamlessExpressiveTail(
        tier = tier,
        tailAnchor = tailAnchor,
        baseR = unit,
        walkStepPhase = walkStepPhase,
        tailStrike = tailStrike,
        pawWhite = pawWhite,
        pawShade = pawShade,
        pawOutline = pawOutline,
        pawStroke = pawStroke
    )

    // 5. Back view mood cues (sparkles, gloom lines, anger steam/vein, fever waves)
    drawBackViewMoodEffects(
        tier = tier,
        headCenterX = cx,
        headTopY = backHeadY,
        headW = headW,
        headH = headH,
        baseR = unit,
        walkStepPhase = walkStepPhase,
        walkStepSin = walkStepSin,
        pawOutline = pawOutline
    )
}

/**
 * Draws tiny soft pink paw pads right onto the lifted hind foot's sole as the leg bends back during
 * each walking step — integrated directly into the continuous leg silhouette!
 */
private fun DrawScope.drawLiftedHindSolePads(
    pawCenterX: Float,
    pawBottomY: Float,
    pawWidth: Float,
    lift: Float,
    side: Float,
    pawOutline: Color
) {
    val padAlpha = ((lift - 0.14f) / 0.55f).coerceIn(0f, 1f)
    if (padAlpha <= 0.02f) return

    val padPink = Color(0xFFFFA5BA)
    val soleRx = pawWidth * 0.36f
    val soleRy = pawWidth * 0.25f
    val soleCy = pawBottomY - soleRy * 0.95f

    withTransform({
        rotate(degrees = side * (6f + lift * 10f), pivot = Offset(pawCenterX, soleCy))
    }) {
        // Subtle soft ankle-bend crease right above the upturned sole
        drawArc(
            color = pawOutline.copy(alpha = 0.24f * padAlpha),
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(pawCenterX - soleRx * 1.15f, soleCy - soleRy * 1.35f),
            size = Size(soleRx * 2.3f, soleRy * 1.6f),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        )
        // Main heel pad + three tiny toe beans
        drawOval(
            color = padPink.copy(alpha = 0.88f * padAlpha),
            topLeft = Offset(pawCenterX - soleRx * 0.52f, soleCy - soleRy * 0.15f),
            size = Size(soleRx * 1.04f, soleRy * 0.86f)
        )
        listOf(-0.56f, 0f, 0.56f).forEach { tx ->
            drawCircle(
                color = padPink.copy(alpha = 0.88f * padAlpha),
                radius = soleRy * 0.27f,
                center = Offset(pawCenterX + soleRx * tx, soleCy - soleRy * 0.52f)
            )
        }
    }
}

/**
 * Draws a plump, cotton-fluffy ("복실복실") white puppy tail that grows SEAMLESSLY out of the lower
 * rump (open-base outline, zero dividing seam) with pillowy rounded fur puffs, soft phase-lagged
 * wagging, and 5 distinct emotional tail-whack styles (`tailStrike`: negative wind-up -> positive
 * rightward strike into the wooden stamp)!
 */
private fun DrawScope.drawSeamlessExpressiveTail(
    tier: DailyHealthTier,
    tailAnchor: Offset,
    baseR: Float,
    walkStepPhase: Float,
    tailStrike: Float = 0f,
    pawWhite: Color,
    pawShade: Color,
    pawOutline: Color,
    pawStroke: Stroke
) {
    fun drawFluffyOrganicTail(
        length: Float,
        baseHalfW: Float,
        wavePhase: Float,
        swayAmp: Float,
        phaseLag: Float,
        droop: Boolean = false,
        curlBias: Float = 0f,
        puffed: Boolean = false
    ) {
        val steps = 20
        val dirY = if (droop) 1f else -1f

        val spine = Array(steps + 1) { i ->
            val t = i.toFloat() / steps.toFloat()
            // Gentle traveling S-wave so the plump fluffy tail sways softly without kinking!
            val bendFactor = t.pow(1.28f)
            val swayNorm = swayAmp * sin(wavePhase - t * phaseLag) + curlBias * t.pow(1.45f)
            val lat = length * bendFactor * swayNorm
            val foreshorten = 1f - 0.14f * (swayNorm * swayNorm).coerceAtMost(0.9f)
            val axial = length * t * foreshorten
            Offset(tailAnchor.x + lat, tailAnchor.y + dirY * axial)
        }

        val leftPts = Array(steps + 1) { Offset.Zero }
        val rightPts = Array(steps + 1) { Offset.Zero }
        var tipTangent = Offset(0f, dirY)
        var tipHalfW = baseHalfW * 0.85f

        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val prev = spine[(i - 1).coerceAtLeast(0)]
            val next = spine[(i + 1).coerceAtMost(steps)]
            val dx = next.x - prev.x
            val dy = next.y - prev.y
            val d = hypot(dx, dy).coerceAtLeast(0.0001f)
            val tx = dx / d
            val ty = dy / d
            val nx = -ty
            val ny = tx

            // Soft, pillowy, ROUND ("둥글둥글 복실복실") silhouette — ZERO sharp `abs(sin)` cusps!
            // Gently tapers from the rump, swells smoothly into a round plush cotton-puff bulb!
            val rootFlare = (1f - t * 4.5f).coerceAtLeast(0f) * 0.12f
            val roundBelly = 0.78f + 0.56f * sin(t * PI.toFloat() * 0.80f) - 0.08f * t * t
            val softLobeAmp = if (puffed) 0.075f else 0.035f
            val softWaveEnv = sin(t * PI.toFloat()).coerceAtLeast(0f)
            // C-infinity smooth cosine lobes (never sharp notches!)
            val leftSoft = softLobeAmp * (1f - cos(t * PI.toFloat() * 4.0f)) * 0.5f * softWaveEnv
            val rightSoft = softLobeAmp * (1f - cos((t + 0.12f) * PI.toFloat() * 4.0f)) * 0.5f * softWaveEnv

            val hwL = (baseHalfW * (roundBelly + rootFlare + leftSoft)).coerceAtLeast(baseHalfW * 0.68f)
            val hwR = (baseHalfW * (roundBelly + rootFlare + rightSoft)).coerceAtLeast(baseHalfW * 0.68f)
            leftPts[i] = Offset(spine[i].x + nx * hwL, spine[i].y + ny * hwL)
            rightPts[i] = Offset(spine[i].x - nx * hwR, spine[i].y - ny * hwR)

            if (i == steps) {
                tipTangent = Offset(tx, ty)
                tipHalfW = (hwL + hwR) * 0.5f
            }
        }

        // Generous, circularly rounded pillowy cotton-ball dome tip (100% smooth & round!)
        val tipCenter = spine[steps]
        val tipApex = Offset(
            tipCenter.x + tipTangent.x * (tipHalfW * 1.02f),
            tipCenter.y + tipTangent.y * (tipHalfW * 1.02f)
        )
        val tipCtrlL = Offset(
            leftPts[steps].x + tipTangent.x * (tipHalfW * 0.68f),
            leftPts[steps].y + tipTangent.y * (tipHalfW * 0.68f)
        )
        val tipCtrlR = Offset(
            rightPts[steps].x + tipTangent.x * (tipHalfW * 0.68f),
            rightPts[steps].y + tipTangent.y * (tipHalfW * 0.68f)
        )

        // Open-base C1-smooth quadratic Bezier outline (zero angular polyline corners!)
        val outlinePath = Path().apply {
            moveTo(leftPts[0].x, leftPts[0].y)
            for (i in 1 until steps) {
                val midX = (leftPts[i].x + leftPts[i + 1].x) * 0.5f
                val midY = (leftPts[i].y + leftPts[i + 1].y) * 0.5f
                quadraticTo(leftPts[i].x, leftPts[i].y, midX, midY)
            }
            lineTo(leftPts[steps].x, leftPts[steps].y)
            cubicTo(
                tipCtrlL.x, tipCtrlL.y,
                tipApex.x + (leftPts[steps].x - tipCenter.x) * 0.58f,
                tipApex.y + (leftPts[steps].y - tipCenter.y) * 0.58f,
                tipApex.x, tipApex.y
            )
            cubicTo(
                tipApex.x + (rightPts[steps].x - tipCenter.x) * 0.58f,
                tipApex.y + (rightPts[steps].y - tipCenter.y) * 0.58f,
                tipCtrlR.x, tipCtrlR.y,
                rightPts[steps].x, rightPts[steps].y
            )
            for (i in (steps - 1) downTo 1) {
                val midX = (rightPts[i].x + rightPts[i - 1].x) * 0.5f
                val midY = (rightPts[i].y + rightPts[i - 1].y) * 0.5f
                quadraticTo(rightPts[i].x, rightPts[i].y, midX, midY)
            }
            lineTo(rightPts[0].x, rightPts[0].y)
        }

        // Closed fill path tucked invisibly into the white rump so there is zero gap at any sway angle
        val fillPath = Path().apply {
            addPath(outlinePath)
            val rootTuckY = tailAnchor.y - dirY * (baseHalfW * 0.70f)
            quadraticTo(tailAnchor.x, rootTuckY, leftPts[0].x, leftPts[0].y)
            close()
        }

        val gradStartY = if (droop) tailAnchor.y else tailAnchor.y - length - tipHalfW
        val gradEndY = if (droop) tailAnchor.y + length + tipHalfW else tailAnchor.y
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = if (droop) {
                    listOf(pawWhite, pawWhite, pawShade.copy(alpha = 0.80f))
                } else {
                    listOf(pawWhite, pawWhite, pawWhite)
                },
                startY = gradStartY,
                endY = gradEndY
            )
        )
        drawPath(
            path = outlinePath,
            color = pawOutline,
            style = Stroke(width = pawStroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Tiny soft rounded fluff highlight curve inside the pillowy tail tip
        val puffMid = spine[(steps * 0.66f).roundToInt()]
        val curlR = tipHalfW * 0.36f
        val curlPath = Path().apply {
            moveTo(puffMid.x - curlR * 0.45f, puffMid.y)
            quadraticTo(puffMid.x, puffMid.y + dirY * curlR * 0.42f, puffMid.x + curlR * 0.45f, puffMid.y - dirY * curlR * 0.08f)
        }
        drawPath(
            path = curlPath,
            color = pawOutline.copy(alpha = 0.11f),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        )
    }

    val strikeMag = abs(tailStrike).coerceIn(0f, 1.4f)
    val strikePos = tailStrike.coerceAtLeast(0f)

    when (tier) {
        DailyHealthTier.GOLD -> {
            // 1단계 해맑음: 신나는 프로펠러 스윙! 꼬리를 크게 휘둘러 경쾌하게 톡~!
            val tailLen = baseR * (0.56f + strikeMag * 0.12f)
            withTransform({
                if (strikeMag > 0.005f) {
                    rotate(degrees = tailStrike * 78f, pivot = tailAnchor)
                }
            }) {
                drawFluffyOrganicTail(
                    length = tailLen,
                    baseHalfW = baseR * 0.175f,
                    wavePhase = walkStepPhase * 3.8f,
                    swayAmp = 0.44f * (1f - strikeMag * 0.45f).coerceAtLeast(0.15f),
                    phaseLag = 1.15f,
                    curlBias = tailStrike * 0.56f
                )
            }
            // Playful wagging & golden whip-swoosh arcs
            val wagStroke = Stroke(width = (2.0f + strikePos * 1.4f).dp.toPx(), cap = StrokeCap.Round)
            val arcR = tailLen * (0.96f + strikePos * 0.14f)
            if (strikePos > 0.08f) {
                drawArc(
                    color = Color(0xFFF59E0B).copy(alpha = (strikePos * 0.72f).coerceIn(0f, 0.80f)),
                    startAngle = 240f,
                    sweepAngle = 78f * strikePos.coerceAtMost(1.1f),
                    useCenter = false,
                    topLeft = Offset(tailAnchor.x - arcR, tailAnchor.y - arcR),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = wagStroke
                )
            } else {
                listOf(216f, 294f).forEach { startAngle ->
                    drawArc(
                        color = pawOutline.copy(alpha = 0.34f),
                        startAngle = startAngle,
                        sweepAngle = 30f,
                        useCenter = false,
                        topLeft = Offset(tailAnchor.x - arcR, tailAnchor.y - arcR),
                        size = Size(arcR * 2f, arcR * 2f),
                        style = wagStroke
                    )
                }
            }
        }

        DailyHealthTier.SILVER -> {
            // 2단계 웃음: 앙증맞고 산뜻하게 꼬리를 톡! 치며 살랑~
            val tailLen = baseR * (0.54f + strikeMag * 0.08f)
            val wavePhase = walkStepPhase * 2.2f
            withTransform({
                if (strikeMag > 0.005f) {
                    rotate(degrees = tailStrike * 62f, pivot = tailAnchor)
                }
            }) {
                drawFluffyOrganicTail(
                    length = tailLen,
                    baseHalfW = baseR * 0.170f,
                    wavePhase = wavePhase,
                    swayAmp = 0.32f * (1f - strikeMag * 0.5f).coerceAtLeast(0.12f),
                    phaseLag = 1.05f,
                    curlBias = tailStrike * 0.42f
                )
            }
            val arcR = tailLen * 0.96f
            if (strikePos > 0.08f) {
                drawArc(
                    color = Color(0xFF38BDF8).copy(alpha = (strikePos * 0.65f).coerceIn(0f, 0.72f)),
                    startAngle = 252f,
                    sweepAngle = 62f * strikePos.coerceAtMost(1f),
                    useCenter = false,
                    topLeft = Offset(tailAnchor.x - arcR, tailAnchor.y - arcR),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                )
            } else {
                drawArc(
                    color = pawOutline.copy(alpha = 0.24f),
                    startAngle = if (sin(wavePhase) < 0f) 294f else 220f,
                    sweepAngle = 24f,
                    useCenter = false,
                    topLeft = Offset(tailAnchor.x - arcR, tailAnchor.y - arcR),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        DailyHealthTier.BRONZE -> {
            // 3단계 슬픔: 힘없이 축 처진 꼬리로 천천히 쓱... 스치고 지나감 (droop = true -> -angle swings right!)
            withTransform({
                if (strikeMag > 0.005f) {
                    rotate(degrees = -tailStrike * 46f, pivot = tailAnchor)
                }
            }) {
                drawFluffyOrganicTail(
                    length = baseR * 0.48f,
                    baseHalfW = baseR * 0.155f,
                    wavePhase = walkStepPhase * 1.0f,
                    swayAmp = 0.12f,
                    phaseLag = 0.80f,
                    droop = true,
                    curlBias = 0.14f + tailStrike * 0.34f
                )
            }
        }

        DailyHealthTier.WARNING -> {
            // 4단계 화남: 털이 빵빵하게 바짝 선 꼬리를 크게 젖혔다가 팍!! 강하게 내리침!
            val tailLen = baseR * (0.58f + strikeMag * 0.15f)
            withTransform({
                if (strikeMag > 0.005f) {
                    rotate(degrees = tailStrike * 92f, pivot = tailAnchor)
                }
            }) {
                drawFluffyOrganicTail(
                    length = tailLen,
                    baseHalfW = baseR * (0.195f + strikeMag * 0.038f),
                    wavePhase = walkStepPhase * 6.2f,
                    swayAmp = 0.14f,
                    phaseLag = 0.34f,
                    curlBias = tailStrike * 0.52f,
                    puffed = true
                )
            }
            // Fierce twin crimson/orange comic whip speed-arcs during slam!
            if (strikePos > 0.08f) {
                val rOuter = tailLen * 1.12f
                val rInner = tailLen * 0.84f
                val a = (strikePos * 0.82f).coerceIn(0f, 0.88f)
                drawArc(
                    color = Color(0xFFEF4444).copy(alpha = a),
                    startAngle = 228f,
                    sweepAngle = 92f * strikePos.coerceAtMost(1.1f),
                    useCenter = false,
                    topLeft = Offset(tailAnchor.x - rOuter, tailAnchor.y - rOuter),
                    size = Size(rOuter * 2f, rOuter * 2f),
                    style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color(0xFFF97316).copy(alpha = a * 0.78f),
                    startAngle = 244f,
                    sweepAngle = 72f * strikePos.coerceAtMost(1.1f),
                    useCenter = false,
                    topLeft = Offset(tailAnchor.x - rInner, tailAnchor.y - rInner),
                    size = Size(rInner * 2f, rInner * 2f),
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        DailyHealthTier.CRITICAL -> {
            // 5단계 아픔: 축 처져 부들부들 떨리는 꼬리로 힘없이 겨우 툭... (droop = true -> -angle swings right!)
            val trembleDeg = sin(walkStepPhase * 12f + tailStrike * 26f) * (3.5f + strikeMag * 5.5f)
            withTransform({
                if (strikeMag > 0.005f) {
                    rotate(degrees = -tailStrike * 30f + trembleDeg, pivot = tailAnchor)
                }
            }) {
                drawFluffyOrganicTail(
                    length = baseR * 0.45f,
                    baseHalfW = baseR * 0.145f,
                    wavePhase = walkStepPhase * 8.6f,
                    swayAmp = 0.08f,
                    phaseLag = 0.60f,
                    droop = true,
                    curlBias = -0.12f + tailStrike * 0.24f
                )
            }
        }

        else -> {
            withTransform({
                if (strikeMag > 0.005f) {
                    rotate(degrees = tailStrike * 58f, pivot = tailAnchor)
                }
            }) {
                drawFluffyOrganicTail(
                    length = baseR * 0.52f,
                    baseHalfW = baseR * 0.165f,
                    wavePhase = walkStepPhase * 2.0f,
                    swayAmp = 0.24f,
                    phaseLag = 1.0f,
                    curlBias = tailStrike * 0.36f
                )
            }
        }
    }
}

/**
 * Draws clear tier-specific emotional visual cues around the back of the head so every mood reads
 * instantly even from behind as the character walks away!
 */
private fun DrawScope.drawBackViewMoodEffects(
    tier: DailyHealthTier,
    headCenterX: Float,
    headTopY: Float,
    headW: Float,
    headH: Float,
    baseR: Float,
    walkStepPhase: Float,
    walkStepSin: Float,
    pawOutline: Color
) {
    val thinStroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

    when (tier) {
        DailyHealthTier.GOLD -> {
            // Bouncy joyful golden sparkles popping around the back of the head!
            val pulse = 0.65f + 0.35f * abs(sin(walkStepPhase * 2f))
            val goldSparkle = Color(0xFFF59E0B)
            listOf(
                Offset(headCenterX - headW * 0.36f, headTopY + headH * 0.08f) to (baseR * 0.14f * pulse),
                Offset(headCenterX + headW * 0.38f, headTopY + headH * 0.04f) to (baseR * 0.16f * (1.3f - pulse * 0.5f)),
                Offset(headCenterX + headW * 0.05f, headTopY - headH * 0.10f) to (baseR * 0.12f * pulse)
            ).forEach { (center, r) ->
                val star = Path().apply {
                    moveTo(center.x, center.y - r)
                    quadraticTo(center.x, center.y, center.x + r, center.y)
                    quadraticTo(center.x, center.y, center.x, center.y + r)
                    quadraticTo(center.x, center.y, center.x - r, center.y)
                    quadraticTo(center.x, center.y, center.x, center.y - r)
                    close()
                }
                drawPath(star, color = goldSparkle)
            }
        }

        DailyHealthTier.SILVER -> {
            // Soft sky-blue sparkle near upper-right ear
            val sp = Offset(headCenterX + headW * 0.34f, headTopY + headH * 0.06f)
            val r = baseR * (0.11f + 0.04f * abs(walkStepSin))
            val star = Path().apply {
                moveTo(sp.x, sp.y - r)
                quadraticTo(sp.x, sp.y, sp.x + r, sp.y)
                quadraticTo(sp.x, sp.y, sp.x, sp.y + r)
                quadraticTo(sp.x, sp.y, sp.x - r, sp.y)
                quadraticTo(sp.x, sp.y, sp.x, sp.y - r)
                close()
            }
            drawPath(star, color = Color(0xFF38BDF8))
        }

        DailyHealthTier.BRONZE -> {
            // Gloomy vertical indigo anime-style sigh lines floating above back-left of head
            val gloomColor = Color(0xFF6366F1).copy(alpha = 0.58f)
            val gloomX = headCenterX - headW * 0.26f
            val gloomTop = headTopY - headH * 0.06f
            for (i in 0 until 3) {
                val lx = gloomX + i * baseR * 0.11f
                val len = baseR * (0.26f + (i % 2) * 0.08f)
                drawLine(
                    color = gloomColor,
                    start = Offset(lx, gloomTop),
                    end = Offset(lx, gloomTop + len),
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        DailyHealthTier.WARNING -> {
            // Comic huffing steam puffs shooting out from both sides of the stomping head!
            val puffPhase = abs(walkStepSin)
            listOf(-1f, +1f).forEach { s ->
                val px = headCenterX + s * (headW * 0.36f + puffPhase * baseR * 0.16f)
                val py = headTopY + headH * 0.20f - puffPhase * baseR * 0.14f
                val pr = baseR * (0.12f + puffPhase * 0.07f)
                val puffAlpha = (0.35f + puffPhase * 0.55f).coerceIn(0f, 0.9f)
                drawCircle(color = Color.White.copy(alpha = puffAlpha), radius = pr, center = Offset(px, py))
                drawCircle(color = Color.White.copy(alpha = puffAlpha), radius = pr * 0.72f, center = Offset(px + s * pr * 0.75f, py - pr * 0.28f))
                drawCircle(color = pawOutline.copy(alpha = puffAlpha * 0.55f), radius = pr, center = Offset(px, py), style = Stroke(width = 1.6.dp.toPx()))
            }

            // Pulsing red comic anger mark 💢 on upper-right back of head
            val ac = Offset(headCenterX + headW * 0.25f, headTopY + headH * 0.16f)
            val ar = baseR * (0.16f + 0.04f * puffPhase)
            val gap = ar * 0.24f
            val angerColor = Color(0xFFEF4444)
            listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
                val corner = Path().apply {
                    moveTo(ac.x + sx * ar, ac.y + sy * gap)
                    quadraticTo(ac.x + sx * gap, ac.y + sy * gap, ac.x + sx * gap, ac.y + sy * ar)
                }
                drawPath(corner, color = angerColor, style = thinStroke)
            }
        }

        DailyHealthTier.CRITICAL -> {
            // Rising fever heat steam waves drifting off the back of the head
            val feverRed = Color(0xFFEF4444).copy(alpha = 0.62f)
            listOf(-0.14f, 0f, 0.14f).forEachIndexed { idx, xOff ->
                val sx = headCenterX + headW * xOff
                val baseY = headTopY + headH * 0.02f
                val sway = sin(walkStepPhase * 2.5f + idx) * baseR * 0.08f
                val wave = Path().apply {
                    moveTo(sx, baseY)
                    quadraticTo(sx - sway, baseY - baseR * 0.14f, sx, baseY - baseR * 0.26f)
                    quadraticTo(sx + sway, baseY - baseR * 0.38f, sx, baseY - baseR * 0.48f)
                }
                drawPath(wave, color = feverRed, style = thinStroke)
            }

            // Blue cold sweat drop on upper-right back of head
            val sc = Offset(headCenterX + headW * 0.26f, headTopY + headH * 0.26f)
            val sw = baseR * 0.12f
            val sh = sw * 1.45f
            val sweat = Path().apply {
                moveTo(sc.x, sc.y - sh * 0.5f)
                cubicTo(sc.x + sw * 0.6f, sc.y + sh * 0.1f, sc.x + sw * 0.48f, sc.y + sh * 0.5f, sc.x, sc.y + sh * 0.5f)
                cubicTo(sc.x - sw * 0.48f, sc.y + sh * 0.5f, sc.x - sw * 0.6f, sc.y + sh * 0.1f, sc.x, sc.y - sh * 0.5f)
                close()
            }
            drawPath(sweat, color = Color(0xFF93C5FD))
            drawPath(sweat, color = Color(0xFF3B82F6), style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round))
        }

        else -> Unit
    }
}


/**
 * Draws the live stamping puppy's tier-matched facial expression right onto its pure-white real head
 * so the character stamping the calendar shows the exact same mood as the stamp being printed!
 */
private fun DrawScope.drawPuppyLiveExpression(
    tier: DailyHealthTier,
    character: StampCharacterSpec,
    imgLeft: Float,
    imgTop: Float,
    imgW: Float,
    imgH: Float,
    outlineColor: Color,
    impactIntensity: Float
) {
    fun pt(nx: Float, ny: Float): Offset =
        character.landmarks.toCanvasOffset(nx, ny, imgLeft, imgTop, imgW, imgH)

    val lineW = (imgW * 0.028f).coerceAtLeast(2.4f)
    val thinW = (imgW * 0.022f).coerceAtLeast(1.9f)
    val stroke = Stroke(width = lineW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thinStroke = Stroke(width = thinW, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // Cute dark puppy nose centered on exact real landmark (0.515, 0.612)
    val noseCenter = pt(0.515f, 0.612f)
    drawOval(
        color = outlineColor,
        topLeft = Offset(noseCenter.x - imgW * 0.036f, noseCenter.y - imgH * 0.026f),
        size = Size(imgW * 0.072f, imgH * 0.052f)
    )
    // Tiny glossy nose highlight
    drawOval(
        color = Color.White.copy(alpha = 0.55f),
        topLeft = Offset(noseCenter.x - imgW * 0.018f, noseCenter.y - imgH * 0.016f),
        size = Size(imgW * 0.018f, imgH * 0.011f)
    )

    when (tier) {
        DailyHealthTier.GOLD -> {
            // 1단계: 해맑게 혀 내밀고 웃는 얼굴
            val blushColor = Color(0xFFFF9DB2).copy(alpha = 0.42f)
            drawOval(blushColor, topLeft = Offset(pt(0.325f, 0.595f).x - imgW * 0.045f, pt(0.325f, 0.595f).y - imgH * 0.024f), size = Size(imgW * 0.09f, imgH * 0.048f))
            drawOval(blushColor, topLeft = Offset(pt(0.705f, 0.595f).x - imgW * 0.045f, pt(0.705f, 0.595f).y - imgH * 0.024f), size = Size(imgW * 0.09f, imgH * 0.048f))

            val leftEye = Path().apply {
                moveTo(pt(0.355f, 0.535f).x, pt(0.355f, 0.535f).y)
                quadraticTo(pt(0.405f, 0.445f).x, pt(0.405f, 0.445f).y, pt(0.455f, 0.535f).x, pt(0.455f, 0.535f).y)
            }
            val rightEye = Path().apply {
                moveTo(pt(0.575f, 0.535f).x, pt(0.575f, 0.535f).y)
                quadraticTo(pt(0.625f, 0.445f).x, pt(0.625f, 0.445f).y, pt(0.675f, 0.535f).x, pt(0.675f, 0.535f).y)
            }
            drawPath(leftEye, color = outlineColor, style = stroke)
            drawPath(rightEye, color = outlineColor, style = stroke)

            // Playful pink tongue & big happy smile
            val tongue = Path().apply {
                val tL = pt(0.465f, 0.700f)
                val tLB = pt(0.465f, 0.785f)
                val cL = pt(0.465f, 0.858f)
                val cR = pt(0.565f, 0.858f)
                val tRB = pt(0.565f, 0.785f)
                val tR = pt(0.565f, 0.700f)
                moveTo(tL.x, tL.y)
                lineTo(tLB.x, tLB.y)
                cubicTo(cL.x, cL.y, cR.x, cR.y, tRB.x, tRB.y)
                lineTo(tR.x, tR.y)
                close()
            }
            drawPath(tongue, color = Color(0xFFFF7FA3))
            drawPath(tongue, color = outlineColor, style = thinStroke)
            drawLine(outlineColor, pt(0.515f, 0.715f), pt(0.515f, 0.790f), thinW * 0.72f, StrokeCap.Round)

            val mouthTop = Path().apply {
                moveTo(pt(0.415f, 0.675f).x, pt(0.415f, 0.675f).y)
                quadraticTo(pt(0.515f, 0.725f).x, pt(0.515f, 0.725f).y, pt(0.615f, 0.675f).x, pt(0.615f, 0.675f).y)
            }
            drawPath(mouthTop, color = outlineColor, style = thinStroke)
        }

        DailyHealthTier.SILVER -> {
            // 2단계: 기분 좋게 웃는 얼굴
            val blushColor = Color(0xFFFFA8BC).copy(alpha = 0.34f)
            drawOval(blushColor, topLeft = Offset(pt(0.325f, 0.595f).x - imgW * 0.042f, pt(0.325f, 0.595f).y - imgH * 0.022f), size = Size(imgW * 0.084f, imgH * 0.044f))
            drawOval(blushColor, topLeft = Offset(pt(0.705f, 0.595f).x - imgW * 0.042f, pt(0.705f, 0.595f).y - imgH * 0.022f), size = Size(imgW * 0.084f, imgH * 0.044f))

            val eyeRadius = imgW * 0.032f
            val lEye = pt(0.405f, 0.515f)
            val rEye = pt(0.625f, 0.515f)
            drawCircle(color = outlineColor, radius = eyeRadius, center = lEye)
            drawCircle(color = outlineColor, radius = eyeRadius, center = rEye)
            drawCircle(color = Color.White, radius = eyeRadius * 0.34f, center = Offset(lEye.x - eyeRadius * 0.28f, lEye.y - eyeRadius * 0.28f))
            drawCircle(color = Color.White, radius = eyeRadius * 0.34f, center = Offset(rEye.x - eyeRadius * 0.28f, rEye.y - eyeRadius * 0.28f))

            val smile = Path().apply {
                moveTo(pt(0.415f, 0.690f).x, pt(0.415f, 0.690f).y)
                quadraticTo(pt(0.465f, 0.765f).x, pt(0.465f, 0.765f).y, pt(0.515f, 0.680f).x, pt(0.515f, 0.680f).y)
                quadraticTo(pt(0.565f, 0.765f).x, pt(0.565f, 0.765f).y, pt(0.615f, 0.690f).x, pt(0.615f, 0.690f).y)
            }
            drawPath(smile, color = outlineColor, style = thinStroke)
        }

        DailyHealthTier.BRONZE -> {
            // 3단계: 슬픈 얼굴 (처진 눈썹 + 글썽 눈 + 파란 눈물방울 + 시무룩 입)
            drawLine(outlineColor, pt(0.355f, 0.455f), pt(0.445f, 0.415f), thinW, StrokeCap.Round)
            drawLine(outlineColor, pt(0.675f, 0.455f), pt(0.585f, 0.415f), thinW, StrokeCap.Round)

            val eyeRadius = imgW * 0.031f
            val lEye = pt(0.405f, 0.525f)
            val rEye = pt(0.625f, 0.525f)
            drawCircle(color = outlineColor, radius = eyeRadius, center = lEye)
            drawCircle(color = outlineColor, radius = eyeRadius, center = rEye)
            drawCircle(color = Color.White, radius = eyeRadius * 0.36f, center = Offset(lEye.x - eyeRadius * 0.25f, lEye.y - eyeRadius * 0.25f))
            drawCircle(color = Color.White, radius = eyeRadius * 0.36f, center = Offset(rEye.x - eyeRadius * 0.25f, rEye.y - eyeRadius * 0.25f))

            val frown = Path().apply {
                moveTo(pt(0.435f, 0.755f).x, pt(0.435f, 0.755f).y)
                quadraticTo(pt(0.515f, 0.675f).x, pt(0.515f, 0.675f).y, pt(0.595f, 0.755f).x, pt(0.595f, 0.755f).y)
            }
            drawPath(frown, color = outlineColor, style = thinStroke)

            // Glistening blue teardrops under both eyes
            val dropShift = impactIntensity * 0.045f
            listOf(pt(0.370f, 0.600f + dropShift), pt(0.660f, 0.615f + dropShift)).forEach { tc ->
                val dropW = imgW * 0.046f
                val dropH = dropW * 1.38f
                val tear = Path().apply {
                    moveTo(tc.x, tc.y - dropH * 0.52f)
                    cubicTo(
                        tc.x + dropW * 0.62f, tc.y + dropH * 0.08f,
                        tc.x + dropW * 0.50f, tc.y + dropH * 0.52f,
                        tc.x, tc.y + dropH * 0.52f
                    )
                    cubicTo(
                        tc.x - dropW * 0.50f, tc.y + dropH * 0.52f,
                        tc.x - dropW * 0.62f, tc.y + dropH * 0.08f,
                        tc.x, tc.y - dropH * 0.52f
                    )
                    close()
                }
                drawPath(tear, color = Color(0xFF7DD3FC))
                drawPath(tear, color = Color(0xFF2563EB), style = Stroke(width = thinW * 0.65f, join = StrokeJoin.Round))
            }
        }

        DailyHealthTier.WARNING -> {
            // 4단계: 화난 얼굴 (날카로운 \ / 눈썹 + 강렬 눈 + 짖는 입 + 빨간 💢 마크)
            drawLine(outlineColor, pt(0.345f, 0.430f), pt(0.460f, 0.485f), lineW, StrokeCap.Round)
            drawLine(outlineColor, pt(0.685f, 0.430f), pt(0.570f, 0.485f), lineW, StrokeCap.Round)

            val eyeRadius = imgW * 0.030f
            drawCircle(color = outlineColor, radius = eyeRadius, center = pt(0.410f, 0.535f))
            drawCircle(color = outlineColor, radius = eyeRadius, center = pt(0.620f, 0.535f))

            if (impactIntensity > 0.25f) {
                // Barking open mouth on slam!
                val openW = imgW * (0.12f + 0.05f * impactIntensity)
                val openH = imgH * (0.08f + 0.08f * impactIntensity)
                val mc = pt(0.515f, 0.735f)
                drawOval(Color(0xFFF472B6), topLeft = Offset(mc.x - openW * 0.5f, mc.y - openH * 0.5f), size = Size(openW, openH))
                drawOval(outlineColor, topLeft = Offset(mc.x - openW * 0.5f, mc.y - openH * 0.5f), size = Size(openW, openH), style = thinStroke)
                // Little white fangs
                val fangTop = mc.y - openH * 0.42f
                val fangBot = mc.y - openH * 0.06f
                drawLine(Color.White, Offset(mc.x - openW * 0.24f, fangTop), Offset(mc.x - openW * 0.20f, fangBot), thinW * 0.9f, StrokeCap.Round)
                drawLine(Color.White, Offset(mc.x + openW * 0.24f, fangTop), Offset(mc.x + openW * 0.20f, fangBot), thinW * 0.9f, StrokeCap.Round)
            } else {
                val angryMouth = Path().apply {
                    moveTo(pt(0.420f, 0.755f).x, pt(0.420f, 0.755f).y)
                    lineTo(pt(0.515f, 0.690f).x, pt(0.515f, 0.690f).y)
                    lineTo(pt(0.610f, 0.755f).x, pt(0.610f, 0.755f).y)
                }
                drawPath(angryMouth, color = outlineColor, style = thinStroke)
            }

            // Bold red comic anger vein 💢 on upper-right forehead
            val ac = pt(0.71f, 0.22f)
            val ar = imgW * 0.044f * (1f + impactIntensity * 0.22f)
            val gap = ar * 0.24f
            val angerStroke = Stroke(width = thinW * 0.95f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val angerRed = Color(0xFFEF4444)
            listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
                val corner = Path().apply {
                    moveTo(ac.x + sx * ar, ac.y + sy * gap)
                    quadraticTo(ac.x + sx * gap, ac.y + sy * gap, ac.x + sx * gap, ac.y + sy * ar)
                }
                drawPath(corner, color = angerRed, style = angerStroke)
            }
        }

        DailyHealthTier.CRITICAL -> {
            // 5단계: 아픈 얼굴 (힘없는 > < 눈 + 열 홍조 + 식은땀 + 체온계!)
            val feverBlush = Color(0xFFEF4444).copy(alpha = 0.34f)
            drawOval(feverBlush, topLeft = Offset(pt(0.325f, 0.595f).x - imgW * 0.055f, pt(0.325f, 0.595f).y - imgH * 0.030f), size = Size(imgW * 0.11f, imgH * 0.060f))
            drawOval(feverBlush, topLeft = Offset(pt(0.705f, 0.595f).x - imgW * 0.055f, pt(0.705f, 0.595f).y - imgH * 0.030f), size = Size(imgW * 0.11f, imgH * 0.060f))

            val leftSickEye = Path().apply {
                moveTo(pt(0.365f, 0.465f).x, pt(0.365f, 0.465f).y)
                lineTo(pt(0.445f, 0.515f).x, pt(0.445f, 0.515f).y)
                lineTo(pt(0.365f, 0.565f).x, pt(0.365f, 0.565f).y)
            }
            val rightSickEye = Path().apply {
                moveTo(pt(0.665f, 0.465f).x, pt(0.665f, 0.465f).y)
                lineTo(pt(0.585f, 0.515f).x, pt(0.585f, 0.515f).y)
                lineTo(pt(0.665f, 0.565f).x, pt(0.665f, 0.565f).y)
            }
            drawPath(leftSickEye, color = outlineColor, style = thinStroke)
            drawPath(rightSickEye, color = outlineColor, style = thinStroke)

            // Cold sweat drop on temple
            val sc = pt(0.70f, 0.36f)
            val sw = imgW * 0.036f
            val sh = sw * 1.42f
            val sweat = Path().apply {
                moveTo(sc.x, sc.y - sh * 0.5f)
                cubicTo(sc.x + sw * 0.6f, sc.y + sh * 0.1f, sc.x + sw * 0.48f, sc.y + sh * 0.5f, sc.x, sc.y + sh * 0.5f)
                cubicTo(sc.x - sw * 0.48f, sc.y + sh * 0.5f, sc.x - sw * 0.6f, sc.y + sh * 0.1f, sc.x, sc.y - sh * 0.5f)
                close()
            }
            drawPath(sweat, color = Color(0xFF93C5FD))
            drawPath(sweat, color = Color(0xFF3B82F6), style = Stroke(width = thinW * 0.6f, join = StrokeJoin.Round))

            // Small weak 'o' mouth holding the white & red clinical thermometer
            drawArc(
                color = outlineColor,
                startAngle = 145f,
                sweepAngle = 230f,
                useCenter = false,
                topLeft = pt(0.465f, 0.695f),
                size = Size(imgW * 0.10f, imgH * 0.085f),
                style = thinStroke
            )

            val thermoStart = pt(0.525f, 0.740f)
            val thermoEnd = pt(0.775f, 0.905f)
            val dx = thermoEnd.x - thermoStart.x
            val dy = thermoEnd.y - thermoStart.y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
            val halfThick = imgW * 0.024f
            val nx = -dy / len * halfThick
            val ny = dx / len * halfThick

            val thermoBody = Path().apply {
                moveTo(thermoStart.x + nx, thermoStart.y + ny)
                lineTo(thermoEnd.x + nx, thermoEnd.y + ny)
                lineTo(thermoEnd.x - nx, thermoEnd.y - ny)
                lineTo(thermoStart.x - nx, thermoStart.y - ny)
                close()
            }
            drawPath(thermoBody, color = Color.White)
            drawPath(thermoBody, color = outlineColor, style = Stroke(width = thinW * 0.8f, join = StrokeJoin.Round))
            // Red mercury column & bulb
            drawLine(
                color = Color(0xFFEF4444),
                start = Offset(thermoStart.x + dx * 0.25f, thermoStart.y + dy * 0.25f),
                end = thermoEnd,
                strokeWidth = thinW * 0.7f,
                cap = StrokeCap.Round
            )
            drawCircle(color = Color(0xFFEF4444), radius = imgW * 0.032f, center = thermoEnd)
            drawCircle(color = outlineColor, radius = imgW * 0.032f, center = thermoEnd, style = Stroke(width = thinW * 0.65f))
        }

        else -> {
            val eyeRadius = imgW * 0.030f
            drawCircle(color = outlineColor, radius = eyeRadius, center = pt(0.405f, 0.515f))
            drawCircle(color = outlineColor, radius = eyeRadius, center = pt(0.625f, 0.515f))
        }
    }
}
