package com.jev.probe.capture.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One screenshot, taken through the accessibility service (no MediaProjection,
 * no root, no cast permission dialog). Requires `android:canTakeScreenshot="true"`
 * in the service config — and that flag only takes effect after the user turns
 * the accessibility service OFF and ON again.
 *
 * Contract: [capture] is called on the main thread and answers on the main
 * thread, exactly once, with either a software [Result.Ok] bitmap or a
 * [Result.Failed] carrying a sentence the overlay can show as-is.
 *
 * Three things here exist because the platform bites otherwise:
 * - The result arrives as a [android.hardware.HardwareBuffer]. It must be copied
 *   into an ARGB_8888 bitmap and closed immediately; leaking buffers starves the
 *   system compositor after a handful of shots.
 * - The system throttles screenshots (errorCode 3). We throttle ourselves first
 *   (>= 1s between attempts) and back off 1s -> 2s -> 4s ... 30s while failing,
 *   so a chat app whose tree reads empty on every content-changed event can
 *   never turn into a screenshot machine gun.
 * - Our own floating overlay is part of the display and would be baked into the
 *   picture, so the caller hands us hide/restore callbacks.
 */
class ScreenCapture(
    private val service: AccessibilityService,
    private val hideOverlay: () -> Unit = {},
    private val restoreOverlay: () -> Unit = {}
) {

    sealed class Result {
        /**
         * [scaleX]/[scaleY] = bitmap size / captured area size, and
         * [originX]/[originY] = where that area starts on screen.
         *
         * A window shot (API 34+) is NOT the whole display: in split screen, or
         * whenever the window excludes the status bar, the picture is both
         * smaller than the display and offset from its origin. So node rects map
         * as `bitmapX = (screenX - originX) * scaleX`, and OCR boxes map back the
         * other way. For the whole-display fallback the origin is (0,0) and the
         * size is the display's, which is the old behaviour.
         */
        data class Ok(
            val bitmap: Bitmap,
            val scaleX: Float,
            val scaleY: Float,
            val originX: Int = 0,
            val originY: Int = 0
        ) : Result()
        data class Failed(val code: Int, val humanMessage: String) : Result()
    }

    private val main = Handler(Looper.getMainLooper())

    /** Take one screenshot. [onResult] runs on the main thread, exactly once. */
    fun capture(shouldCapture: () -> Boolean = { true }, onResult: (Result) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val need = requiredInterval()
        if (now - lastAttemptAt < need) {
            onResult(Result.Failed(CODE_THROTTLED, "截屏太频繁"))
            return
        }
        lastAttemptAt = now

        val done = AtomicBoolean(false)
        // The shot callback now runs on [shotExecutor] (off-main, upstream #18:
        // the HardwareBuffer -> bitmap copy stalled the UI thread for seconds on
        // some devices), so everything user-visible is marshalled back by
        // posting to the main thread here. The compareAndSet stays on the
        // calling thread, so a late result can never race the timeout runnable.
        val finish: (Result) -> Unit = { r ->
            if (done.compareAndSet(false, true)) {
                main.post {
                    restoreOverlay()
                    if (r is Result.Ok) failStreak = 0
                    else failStreak = (failStreak + 1).coerceAtMost(MAX_STREAK)
                    onResult(r)
                }
            }
        }

        // Hide the bubble, give the compositor a frame to drop it, then shoot.
        runCatching { hideOverlay() }
        main.postDelayed({
            // The foreground can change while the overlay settles. Do not shoot
            // the next app and label its pixels as the original conversation.
            if (shouldCapture()) shoot(finish, done)
            else finish(Result.Failed(CODE_CANCELLED, "会话已变化，已取消截屏"))
        }, HIDE_SETTLE_MS)
    }

    private fun shoot(finish: (Result) -> Unit, done: AtomicBoolean) {
        // API 34+: shooting just the active window is cheaper and is allowed on
        // some OEM builds that refuse a whole-display capture. Fall back to the
        // display shot when the window id is unknown or the call is unavailable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val node = runCatching { service.rootInActiveWindow }.getOrNull()
            val windowId = node?.windowId
            if (windowId != null && windowId != -1) {
                val bounds = runCatching {
                    val r = Rect()
                    node.window?.getBoundsInScreen(r)
                    r
                }.getOrNull()?.takeIf { it.width() > 0 && it.height() > 0 }
                // The window shot is only usable WITH the window's screen rect:
                // that rect is what turns the shot's pixels back into screen
                // coordinates (see toBitmap). When getBoundsInScreen failed or
                // came back empty, taking the window shot anyway left
                // [toBitmap] sizing a window-sized bitmap with the display
                // metrics — origin off by the status bar and every coordinates
                // scale wrong by window/display height, which lands the OCR
                // crops on the wrong lines. Fall through to the display shot
                // instead, which needs no rect.
                if (bounds != null) {
                    shootWindow(bounds, windowId, finish, done)
                    return
                }
                Log.w(TAG, "window bounds unavailable; using the display shot")
            }
        }
        shootDisplay(finish, done)
    }

    /** Whole-display shot. No window rect, so coordinates map 1:1 from (0,0). */
    private fun shootDisplay(finish: (Result) -> Unit, done: AtomicBoolean) {
        val timeout = Runnable { finish(Result.Failed(CODE_TIMEOUT, humanMessage(CODE_TIMEOUT))) }
        val cb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                main.removeCallbacks(timeout)
                if (done.get()) { runCatching { result.hardwareBuffer.close() }; return }
                finish(toBitmap(result, null))
            }
            override fun onFailure(errorCode: Int) {
                main.removeCallbacks(timeout)
                finish(Result.Failed(errorCode, humanMessage(errorCode)))
            }
        }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, cb)
            main.postDelayed(timeout, TIMEOUT_MS)
        } catch (e: Throwable) {
            finish(Result.Failed(CODE_INTERNAL, "截屏失败：${e.javaClass.simpleName}"))
        }
    }

    /**
     * Shot of one window, mapped through [bounds].
     *
     * A window shot can be refused while the display shot still works: WeChat on
     * API 34+ answers `ERROR_TAKE_SCREENSHOT_INVALID_WINDOW` because the window
     * it reports is not one the system will hand over, and the same screen
     * captures fine through the display path. So a refusal of either kind that
     * is about the *window* retries once as a display shot instead of failing.
     */
    private fun shootWindow(
        bounds: Rect,
        windowId: Int,
        finish: (Result) -> Unit,
        done: AtomicBoolean
    ) {
        val timeout = Runnable { finish(Result.Failed(CODE_TIMEOUT, humanMessage(CODE_TIMEOUT))) }
        val cb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                main.removeCallbacks(timeout)
                if (done.get()) { runCatching { result.hardwareBuffer.close() }; return }
                finish(toBitmap(result, bounds))
            }
            override fun onFailure(errorCode: Int) {
                main.removeCallbacks(timeout)
                if (!done.get() && (errorCode == ERROR_SECURE_WINDOW || errorCode == ERROR_INVALID_WINDOW)) {
                    Log.w(TAG, "window shot refused ($errorCode); retrying the display shot")
                    shootDisplay(finish, done)
                    return
                }
                finish(Result.Failed(errorCode, humanMessage(errorCode)))
            }
        }
        try {
            service.takeScreenshotOfWindow(windowId, shotExecutor, cb)
            main.postDelayed(timeout, TIMEOUT_MS)
        } catch (e: Throwable) {
            Log.w(TAG, "takeScreenshotOfWindow unavailable: ${e.javaClass.simpleName}")
            shootDisplay(finish, done)
        }
    }

    /**
     * HardwareBuffer -> software bitmap. The buffer is closed no matter what.
     *
     * [window] is the area the shot covers, in screen coordinates, or null for a
     * whole-display shot (then the display metrics are the frame, as before).
     */
    private fun toBitmap(result: AccessibilityService.ScreenshotResult, window: Rect?): Result {
        val buffer = result.hardwareBuffer
        return try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
            runCatching { hw?.recycle() }
            if (bmp == null) {
                Result.Failed(CODE_INTERNAL, "截屏失败：拿到的画面读不出来")
            } else {
                val dm = service.resources.displayMetrics
                val w = window?.width() ?: dm.widthPixels
                val h = window?.height() ?: dm.heightPixels
                val sx = if (w > 0) bmp.width / w.toFloat() else 1f
                val sy = if (h > 0) bmp.height / h.toFloat() else 1f
                Result.Ok(bmp, sx, sy, window?.left ?: 0, window?.top ?: 0)
            }
        } catch (e: Throwable) {
            Result.Failed(CODE_INTERNAL, "截屏失败：${e.javaClass.simpleName}")
        } finally {
            runCatching { buffer.close() }
        }
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** Our own throttle, not a platform code. */
        const val CODE_THROTTLED = -1
        /** Our own watchdog: the platform callback never arrived. */
        const val CODE_TIMEOUT = -2
        const val CODE_CANCELLED = -3
        private const val CODE_INTERNAL = 1

        /** AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW. */
        private const val ERROR_SECURE_WINDOW = 5
        /** AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW. */
        private const val ERROR_INVALID_WINDOW = 6

        private const val MIN_INTERVAL_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_STREAK = 6
        private const val HIDE_SETTLE_MS = 120L

        /** How long we wait for the screenshot callback before giving up. */
        private const val TIMEOUT_MS = 3000L

        /** One shared off-main thread for the screenshot callback: the
         *  HardwareBuffer -> bitmap copy must never run on the UI thread
         *  (upstream #18). Daemon, so it never blocks process exit. */
        private val shotExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "jev-shot").apply { isDaemon = true }
            }

        // Global across instances on purpose: the system limit is per service,
        // and the service may build a new ScreenCapture per call site.
        @Volatile private var lastAttemptAt = 0L
        @Volatile private var failStreak = 0

        /** 1s normally; 1s, 2s, 4s ... capped at 30s while failures repeat. */
        private fun requiredInterval(): Long {
            if (failStreak <= 0) return MIN_INTERVAL_MS
            val shifted = MIN_INTERVAL_MS shl (failStreak - 1).coerceAtMost(MAX_STREAK)
            return shifted.coerceAtMost(MAX_BACKOFF_MS)
        }

        /** Platform error codes, in words a user can act on. */
        fun humanMessage(code: Int): String = when (code) {
            CODE_THROTTLED -> "截屏太频繁"
            CODE_TIMEOUT -> "截屏超时"
            1 -> "截屏失败：内部错误（系统拒绝，可能是该无障碍服务不被允许截屏）"
            2 -> "截屏失败：无障碍服务未声明截屏能力（去设置里把无障碍关掉再开启）"
            3 -> "截屏失败：间隔太短，等一秒再试"
            4 -> "截屏失败：没有有效的显示"
            5 -> "截屏失败：这个窗口受保护（FLAG_SECURE），拿不到画面"
            6 -> "截屏失败：窗口不可见或已失效"
            else -> "截屏失败（码 ${code}）"
        }
    }
}
