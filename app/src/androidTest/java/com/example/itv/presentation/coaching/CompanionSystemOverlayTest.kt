package com.example.itv.presentation.coaching

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itv.domain.coaching.CharacterIntensity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class CompanionSystemOverlayTest {
    @Test fun tapOnRealSystemWindowChoosesRestExactlyOnce() {
        checkOverlayChoice(CharacterIntensity.GENTLE, "쉬기") { it is EquippedDogView }
    }

    @Test fun fenceInSeparateSystemWindowReceivesDismissTap() {
        checkOverlayChoice(CharacterIntensity.NORMAL, "닫기") { it.contentDescription == "닫기: 철창 펜스" }
    }

    private fun checkOverlayChoice(intensity: CharacterIntensity, expected: String, target: (View) -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= 29 && Settings.canDrawOverlays(context))
        val choices = CopyOnWriteArrayList<String>()
        val ready = CountDownLatch(1)
        val chosen = CountDownLatch(1)
        val factory = CoachingOverlayFactory(context)
        val wm = context.getSystemService(WindowManager::class.java)
        var root: View? = null
        var actor: View? = null
        val rect = Rect()
        try {
            instrumentation.runOnMainSync {
                val scene = factory.offer(onChoice = { title, _ ->
                    choices.add(title)
                    chosen.countDown()
                }, intensityOverride = intensity)
                root = scene.first
                wm.addView(scene.first, scene.second)
                factory.attachControls(scene.first, onReady = { ready.countDown() })
            }
            assertTrue("Overlay did not reach waiting pose", ready.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val dog = WindowInspector.getGlobalWindowViews().single(target)
                actor = dog
                val loc = IntArray(2)
                dog.getLocationOnScreen(loc)
                rect.set(loc[0],loc[1],loc[0] + dog.width,loc[1] + dog.height)
                val flags = (dog.layoutParams as WindowManager.LayoutParams).flags
                assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                assertTrue(root?.isAttachedToWindow == true)
            }
            repeat(2) {
                val start = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,
                        rect.exactCenterX(),rect.exactCenterY(),0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true)) }
                    finally { event.recycle() }
                }
            }
            assertTrue("Actual overlay tap did not deliver the choice", chosen.await(2,TimeUnit.SECONDS))
            assertEquals(listOf(expected),choices.toList())
        } finally {
            instrumentation.runOnMainSync {
                root?.let { attachedRoot ->
                    factory.removeControls(attachedRoot)
                    if (attachedRoot.isAttachedToWindow) wm.removeViewImmediate(attachedRoot)
                }
            }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertFalse(actor?.isAttachedToWindow == true) }
    }
}
