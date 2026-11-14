package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg

/**
 * Per-app capture rules. An adapter turns one messaging app's open chat window
 * into a neutral [ChatSnapshot]; everything downstream (Jev judgment, overlay,
 * fill) is app-agnostic.
 *
 * [extract]'s three-way contract (v1.3 B stage — the service depends on it):
 * - `null`            → not in this app's chat window (list screen, moments,
 *                       settings…). The service does nothing at all.
 * - messages empty    → in a chat window, but the tree carries no message text.
 *                       The service may fall back to screenshot + OCR. Each
 *                       adapter names below what proves "we are in a chat".
 * - messages non-empty→ normal capture.
 *
 * Adapters only read what the app exposes to an ordinary accessibility service.
 * An app that hides its node tree or blocks screenshots is not supported.
 */
interface ChatAppAdapter {
    val pkg: String
    fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot?
}

/** Shared helpers. */
private fun looksLikeTimestamp(t: String): Boolean =
    Regex("""\d{1,2}[:：]\d{2}""").containsMatchIn(t) ||
        Regex("""\d+月\d+日""").containsMatchIn(t) ||
        t == "昨天" || t == "今天"

/**
 * Conversation title in the top action bar: the topmost short, roughly centered
 * text above the first message bubble. Constrained so we never grab an in-chat
 * timestamp. Used by QQ as a fallback when its title id is absent, by X, and by
 * the bubble menu's manual OCR capture.
 */
internal fun findTitleInActionBar(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources,
    minCenterRatio: Double = 0.25,
    maxCenterRatio: Double = 0.75
): String? {
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * minCenterRatio).toInt()
    val maxCenterX = (width * maxCenterRatio).toInt()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var best: String? = null
    var bestTop = Int.MAX_VALUE
    var guard = 0
    while (stack.isNotEmpty() && guard < 5000) {
        guard++
        val node = stack.removeLast()
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text)) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.bottom in 1 until actionBarMax && b.centerX() in minCenterX..maxCenterX) {
                if (b.top < bestTop) { bestTop = b.top; best = text }
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return best
}


private val WECHAT_TITLE_EXCLUDE_PUNCT = Regex("""[，。？！、]""")

/** A WeChat group title's "(N)" member-count suffix, half- or full-width. */
private val WECHAT_GROUP_COUNT_SUFFIX = Regex("""[（(]\d+[）)]""")

/**
 * WeChat conversation title (v1.3 fix): a group's pinned announcement or a
 * stray message can sit in the same "topmost, short, centered" search
 * [findTitleInActionBar] does and get mistaken for the title (seen picking up
 * `我有企微，但是用不习惯`, a chat line). A candidate must not read like a
 * sentence (no Chinese punctuation) and must sit above the first bubble; among
 * what is left, a group title's trailing "(N)" member count wins when present.
 * Nothing qualifying → null (the caller's `lastGoodTitle` then carries the
 * previous stable title forward instead of guessing).
 */
internal fun findWeChatTitle(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources
): String? {
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * 0.25).toInt()
    val maxCenterX = (width * 0.75).toInt()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var bestPlain: String? = null
    var bestPlainTop = Int.MAX_VALUE
    var bestCounted: String? = null
    var bestCountedTop = Int.MAX_VALUE
    var guard = 0
    while (stack.isNotEmpty() && guard < 5000) {
        guard++
        val node = stack.removeLast()
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text) &&
            !WECHAT_TITLE_EXCLUDE_PUNCT.containsMatchIn(text)
        ) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.bottom in 1 until actionBarMax && b.bottom < firstBubbleTop &&
                b.centerX() in minCenterX..maxCenterX
            ) {
                if (WECHAT_GROUP_COUNT_SUFFIX.containsMatchIn(text)) {
                    if (b.top < bestCountedTop) { bestCountedTop = b.top; bestCounted = text }
                } else if (b.top < bestPlainTop) { bestPlainTop = b.top; bestPlain = text }
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return bestCounted ?: bestPlain
}

/** WeChat (com.tencent.mm). Message bubbles carry a stable id; sender side is
 *  the bubble's horizontal position (right = me, left = other).
 *
 *  "In a chat window" = a `id/bkl` bubble container exists (even with its text
 *  stripped by the obfuscation) — nothing else counts, so a list screen's
 *  editable search box can no longer pass for a chat window (v1.3 fix: it was
 *  triggering OCR fallback on the conversation list). WeChat 8.0.52+ hides
 *  node text from ordinary services, so an empty read here (a `bkl` with no
 *  text) is exactly the case OCR fallback exists for. */
class WeChatAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mm"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val bubbles = ArrayList<Triple<Int, Int, String>>() // top, centerX, text
        var firstBubbleTop = Int.MAX_VALUE
        var isChat = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID) {
                isChat = true
                if (!text.isNullOrBlank()) {
                    val b = Rect(); node.getBoundsInScreen(b)
                    bubbles.add(Triple(b.top, b.centerX(), text))
                    if (b.top < firstBubbleTop) firstBubbleTop = b.top
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        val title = findWeChatTitle(root, firstBubbleTop, width, res)
        // In a chat but nothing readable → empty snapshot, the OCR fallback cue.
        if (bubbles.isEmpty()) {
            if (isChat) return ChatSnapshot(title, emptyList())
            // No `id/bkl` anywhere. WeChat rotates its obfuscated ids between
            // releases (bkl is not in 8.0.80), and when the marker is gone this
            // adapter used to return null for every screen — which the service
            // reads as "not in a chat window" and answers with silence. Nothing
            // on screen at all, not even the idle bubble. So: second opinion
            // that does not name a single id. A compose box in the lower half
            // means a conversation (the list screen's search field sits at the
            // top), and an empty snapshot then hands the work to OCR, which is
            // the only thing that can read WeChat 8.0.52+ anyway.
            if (!hasBottomInputBelow(root, res.displayMetrics.heightPixels, 0)) return null
            // The container id moved, but the message bodies did not: they are
            // still ordinary text nodes a few levels down. Read them by layout
            // before falling back to OCR — a tree read is exact, OCR is not.
            val rows = scanRows(root, res)
            if (rows.isNotEmpty()) return ChatSnapshot(title, rows)
            logMissingBubbleId(root)
            return ChatSnapshot(title, emptyList())
        }
        bubbles.sortBy { it.first }
        val msgs = bubbles.map { (_, cx, text) ->
            Msg(if (cx > width / 2) "me" else "other", text)
        }
        return ChatSnapshot(title, msgs)
    }

    companion object {
        private const val BUBBLE_ID = "com.tencent.mm:id/bkl"
        private const val TAG = "JEVASSIST"

        /** WeChat's own furniture, which also lives inside the message band. */
        private val UI_CHROME = Regex(
            """^(发送|返回|更多|语音|表情|相册|拍摄|红包|转账|按住\s*说话|"+"|\+|表情包)$"""
        )

        /** Whole-string timestamps and date dividers. */
        private val STAMP = Regex(
            """^(?:\d{1,2}[:：]\d{2}|(?:上午|下午|凌晨|早上|晚上|中午)\s*\d{1,2}[:：]\d{2}|""" +
                """(?:昨天|今天|前天|大前天|星期[一二三四五六日天])|""" +
                """(?:以下为新消息|消息已发出，但被对方拒收了))$"""
        )

        /** Once per process: what the tree actually repeats, so the next release's
         *  bubble id can be read off logcat instead of guessed. */
        @Volatile
        private var shapeLogged = false

        private fun logMissingBubbleId(root: AccessibilityNodeInfo) {
            if (shapeLogged) return
            shapeLogged = true
            val counts = HashMap<String, Int>()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var guard = 0
            while (stack.isNotEmpty() && guard < 3000) {
                guard++
                val n = stack.removeLast()
                val id = n.viewIdResourceName
                if (!id.isNullOrEmpty()) {
                    val short = id.substringAfter(":id/", id)
                    counts[short] = (counts[short] ?: 0) + 1
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
            }
            val top = counts.entries.sortedByDescending { it.value }.take(12)
                .joinToString(", ") { "${it.key}x${it.value}" }
            Log.i(TAG, "wechat: no bkl in this build; repeated ids: $top")
        }
    }

    /**
     * Message bodies read by layout instead of by id.
     *
     * Whatever WeChat names its bubble container this year, the text inside it
     * is still a plain node. Everything in the band between the action bar and
     * the compose box is a candidate; timestamps, the app's own buttons and
     * duplicate parent/child reads are dropped, wrapped lines of one bubble are
     * joined, and the side comes from which half of the screen the row hugs.
     *
     * Returned empty when the tree genuinely carries no message text — that is
     * the OCR fallback's cue, not an error.
     */
    private fun scanRows(root: AccessibilityNodeInfo, res: Resources): List<Msg> {
        val dm = res.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val topLimit = (h * 0.085).toInt()
        val botLimit = (h * 0.87).toInt()
        val rows = ArrayList<Row>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val n = stack.removeLast()
            if (n.isEditable) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                val t = (n.text?.toString() ?: n.contentDescription?.toString())?.trim()
                if (!t.isNullOrEmpty() && t.length <= 800 &&
                    r.top >= topLimit && r.bottom <= botLimit &&
                    r.left >= 0 && r.right <= w &&
                    !UI_CHROME.matches(t) && !STAMP.matches(t) &&
                    // An avatar's contentDescription is "某人的头像", not a message.
                    !t.endsWith("头像") && !t.endsWith("avatar")
                ) {
                    rows.add(Row(t, r.top, r.bottom, r.left, r.right))
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        if (rows.isEmpty()) return emptyList()
        rows.sortBy { it.top }
        // A container and the TextView inside it both report the same text; one
        // row per distinct string is enough. Wrapped lines of one bubble are one
        // node already (the text carries a newline), so nothing is merged here —
        // joining rows by proximity risks welding two messages into one.
        val merged = ArrayList<Row>()
        for (x in rows) {
            if (merged.lastOrNull()?.text != x.text) merged.add(x)
        }
        return merged.map { row ->
            Msg(if ((row.left + row.right) / 2 > w / 2) "me" else "other", row.text)
        }
    }

    private class Row(
        val text: String,
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int
    )
}

/**
 * Mobile QQ (com.tencent.mobileqq). Nodes are NOT obfuscated (verified on QQ
 * 9.3.50 / Xiaomi 14, 1200x2670): message bodies are plain TextViews carrying
 * `id/mjn`, so collecting only that id already excludes timestamps, sender
 * nicknames (`id/mjq`) and the full-width system notice strips.
 *
 * The whole app lives under one SplashActivity (fragment architecture), so
 * "are we in a chat window" can only be answered by the tree itself — here, by
 * the chat input box `id/input`. No input box → not a chat → null; input box
 * but no `id/mjn` bodies → empty snapshot (OCR fallback's cue).
 *
 * Sender side: QQ pins the avatar to the outer edge of its own side (others on
 * the left at x≈156/1200 ≈ 13% of width, me on the right at width−156). A long
 * incoming message can push its center past mid-screen, so we compare which
 * edge of the bubble hugs its avatar column instead of using the center point.
 */
class QQAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mobileqq"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        // top, left, right, text
        val bubbles = ArrayList<Bubble>()
        var firstBubbleTop = Int.MAX_VALUE
        // Top of the first message row, nickname included: the title fallback must
        // stay above it, or a group member's nickname right under the top bar gets
        // taken for the chat title while QQ re-lays out its list during a scroll.
        var firstContentTop = Int.MAX_VALUE
        var title: String? = null
        var hasInput = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID && !text.isNullOrBlank()) {
                val b = Rect(); node.getBoundsInScreen(b)
                bubbles.add(Bubble(b.top, b.left, b.right, text))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            }
            if (id == NICK_ID) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.top < firstContentTop) firstContentTop = b.top
            }
            if (!hasInput && id == INPUT_ID) hasInput = true
            if (id == TITLE_ID && title == null) text?.let { if (it.isNotBlank()) title = it }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (bubbles.isEmpty() && !hasInput) return null

        if (title == null) title = findTitleInActionBar(root, minOf(firstBubbleTop, firstContentTop), width, res)
        if (bubbles.isEmpty()) return ChatSnapshot(title, emptyList())

        val avatarEdge = (width * 0.13).toInt()
        bubbles.sortBy { it.top }
        val msgs = bubbles.map { b ->
            val dl = kotlin.math.abs(b.left - avatarEdge)
            val dr = kotlin.math.abs((width - avatarEdge) - b.right)
            Msg(if (dr < dl) "me" else "other", b.text)
        }
        return ChatSnapshot(title, msgs)
    }

    private data class Bubble(val top: Int, val left: Int, val right: Int, val text: String)

    companion object {
        private const val BUBBLE_ID = "com.tencent.mobileqq:id/mjn"
        private const val TITLE_ID = "com.tencent.mobileqq:id/371"
        private const val NICK_ID = "com.tencent.mobileqq:id/mjq"
        private const val INPUT_ID = "com.tencent.mobileqq:id/input"
    }
}

/** Only my own Feishu bubbles carry the sent/read strip. */
private const val FEISHU_READ_STATE_ID = "time_read_state_container_align_bubble"

/** Does this bubble carry the "sent / read" strip that only mine have? */
private fun feishuHasReadState(bubble: AccessibilityNodeInfo): Boolean {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(bubble)
    var guard = 0
    while (stack.isNotEmpty() && guard < 400) {
        guard++
        val node = stack.removeLast()
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith(FEISHU_READ_STATE_ID)) return true
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return false
}

/**
 * Feishu bubble rectangles in SCREEN coordinates, top to bottom, with the side
 * the read-receipt strip implies.
 *
 * Split out of [FeishuAdapter.extract] so the capture service can call it again
 * from inside the screenshot callback: several hundred ms pass between reading
 * the tree and the picture arriving (debounce + overlay hide + the shot itself),
 * and a list that scrolled in between would make us crop the wrong rows.
 */
internal fun collectFeishuBubbleRects(
    root: AccessibilityNodeInfo,
    res: Resources
): List<BubbleRect> {
    val height = res.displayMetrics.heightPixels
    val topBand = (height * 0.14).toInt()      // action bar + tab row
    val bottomBand = (height * 0.84).toInt()   // input box + keyboard
    val rects = ArrayList<BubbleRect>()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var guard = 0
    while (stack.isNotEmpty() && guard < 6000) {
        guard++
        val node = stack.removeLast()
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith(":id/bubble_content_container")) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.width() > 0 && b.height() > 0 && b.bottom > topBand && b.top < bottomBand) {
                rects.add(BubbleRect(Rect(b), if (feishuHasReadState(node)) "me" else "other"))
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    rects.sortBy { it.rect.top }
    return rects
}

/**
 * Feishu / Lark (com.ss.android.lark). Nodes are not obfuscated, but the message
 * text is DRAWN, not laid out as views (verified 2026-09-21): the tree gives us
 * bubble rectangles and chrome, and almost never a body. So this adapter is a
 * hybrid — it reports what it can read as messages (usually nothing) and always
 * reports the bubble geometry in [ChatSnapshot.bubbleRects] for the service to
 * OCR rect by rect.
 *
 * "In a chat window" = the tree has `id/message`, `id/bubble_content_container`
 * or the `id/kb_rich_text_content` input box.
 *
 * Side: Feishu left-aligns everyone, so geometry says nothing. What does say
 * something is the read-receipt strip (`…time_read_state_container_align_bubble`)
 * that only hangs off MY bubbles — present → "me", absent → "other". Unverified
 * on a real device (see the B-stage report's gaps).
 */
class FeishuAdapter : ChatAppAdapter {
    override val pkg = "com.ss.android.lark"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels
        val topBand = (height * 0.14).toInt()      // action bar + tab row
        val bottomBand = (height * 0.84).toInt()   // input box + keyboard

        var isChat = false
        var title: String? = null
        val items = ArrayList<Triple<Int, Int, String>>() // top, centerX, text
        // Same collection the service re-runs inside the screenshot callback.
        val rects = collectFeishuBubbleRects(root, res)

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName ?: ""
            if (id.endsWith(":id/message") || id.endsWith(":id/bubble_content_container") ||
                id.endsWith(":id/kb_rich_text_content")) isChat = true
            if (id.endsWith(":id/group_name")) node.text?.toString()?.let { if (title == null) title = it }

            val text = node.text?.toString()
            val cls = node.className?.toString()
            if (!text.isNullOrBlank() && cls == "android.widget.TextView" && !isChrome(id) && !looksLikeTimestamp(text)) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.top in (topBand + 1) until bottomBand) {
                    items.add(Triple(b.top, b.centerX(), text.trim()))
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (!isChat) return null

        if (items.isEmpty()) return ChatSnapshot(title, emptyList(), rects)

        items.sortBy { it.first }
        val msgs = items.map { (_, cx, text) ->
            Msg(if (cx > width / 2) "me" else "other", text)
        }
        return ChatSnapshot(title, msgs, rects)
    }

    /** Non-message UI text to skip: title, sender name, time, system notices,
     *  the input EditText. Bodies have no id (bare TextView) so they pass. */
    private fun isChrome(id: String): Boolean =
        id.endsWith(":id/group_name") ||
            id.endsWith(":id/name_tv") ||
            id.endsWith(":id/date_tv") ||
            id.endsWith(":id/system_label") ||
            id.endsWith(":id/kb_rich_text_content") ||
            id.endsWith(":id/thread_title_tv") ||
            id.endsWith(":id/thread_subtitle_tv")
}

/** Trailing "8:11 上午" / "10:29 下午" / "8:11 AM" stamp X glues onto a message. */
private val X_TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}\s*(上午|下午|AM|PM|am|pm)?$""")

/** X uses "。" as a field separator, so a message can end with a run of them. */
private val X_TRAILING_DOTS = Regex("""。+$""")

/**
 * Split one X DM row's contentDescription into (sender, body).
 *
 * "你：你这个说的就是那个虚拟人物，是吗？。8:11 上午。Read。"
 *      → ("你", "你这个说的就是那个虚拟人物，是吗？")
 * "你：他这个东西开源应该问题不大。。。Read。"
 *      → ("你", "他这个东西开源应该问题不大")
 * "All-In：附加的帖子。。"          → ("All-In", "附加的帖子")
 *
 * The sender is everything before the FIRST separator (full-width "：" in the
 * Chinese UI, ": " as a rough fallback elsewhere); the rest is the body plus
 * chrome — the read receipt, the timestamp, and the "。" gluing them on — which
 * is stripped from the tail in that order. Punctuation the user actually typed
 * ("是吗？") survives. Null when there is no separator or nothing is left.
 */
private fun parseXDesc(desc: String): Pair<String, String>? {
    val full = desc.indexOf('：')
    val half = desc.indexOf(": ")
    val cut: Int
    val skip: Int
    when {
        full >= 0 && (half < 0 || full <= half) -> { cut = full; skip = 1 }
        half >= 0 -> { cut = half; skip = 2 }
        else -> return null
    }
    val sender = desc.substring(0, cut).trim()
    var body = desc.substring(cut + skip).trim()
    for (tail in arrayOf("Read。", "Read", "已读。", "已读")) {
        if (body.endsWith(tail)) { body = body.removeSuffix(tail).trim(); break }
    }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    X_TAIL_TIME.find(body)?.let { body = body.substring(0, it.range.first).trim() }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    if (sender.isEmpty() || body.isEmpty()) return null
    return sender to body
}

/**
 * X / Twitter (com.twitter.android) direct messages. Verified on X 12.25.2 /
 * Xiaomi 14 (1200x2670), Chinese system language.
 *
 * The DM thread is Compose UI: each message is a bare `android.view.View` with
 * NO resource-id, full screen width and empty text — the whole message lives in
 * contentDescription ("All-In：重新写了一个😂。10:29 下午。"). The date divider is a
 * TextView with no "：", so filtering on class + full width + a separator keeps
 * it out. An attachment row ("All-In：附加的帖子。。") nests the quoted post's own
 * TextViews; we only take the row View's own desc, never its children.
 *
 * Every screen runs under the same MainActivity, so "are we in a DM thread" can
 * only be answered by the tree: a thread has the message EditText, the DM list
 * does not. The list's rows look similar but read
 * "All-In, @all_in_2026, 你这个说的就是那…", so ", @" is an extra guard.
 *
 * Side comes from the sender label ("你" / "You"), not geometry — every row is
 * full width no matter who spoke.
 */
class XAdapter : ChatAppAdapter {
    override val pkg = "com.twitter.android"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val rows = ArrayList<Row>()
        var firstRowTop = Int.MAX_VALUE
        var hasInput = false
        // A full-width View whose desc has a separator ("：" / ": ") — the shape
        // of a message row, whether or not parseXDesc could fully parse it.
        var hasMessageRowShape = false
        // A thread with zero messages still carries this placeholder text.
        var hasDmLabel = false
        // DM-list-only signals: the "compose new DM" affordance, or a "聊天/
        // Messages" heading with nothing under it (parsed as an actual row).
        var hasNewDmMarker = false
        var sawListHeading = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val cls = node.className?.toString()
            if (!hasInput && (node.isEditable || cls == "android.widget.EditText")) hasInput = true

            val desc = node.contentDescription?.toString()
            if (desc == "新私信" || desc == "New message") hasNewDmMarker = true
            if (cls == "android.view.View" && !desc.isNullOrBlank() && !desc.contains(", @")) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.left == 0 && b.right == width) {
                    if (desc.contains('：') || desc.contains(": ")) hasMessageRowShape = true
                    val parsed = parseXDesc(desc)
                    if (parsed != null) {
                        rows.add(Row(b.top, parsed.first, parsed.second))
                        if (b.top < firstRowTop) firstRowTop = b.top
                    }
                }
            }

            if (cls == "android.widget.TextView") {
                val text = node.text?.toString()?.trim()
                if (text == "私信" || text == "Message" || text == "发送私信") hasDmLabel = true
                if (text == "聊天" || text == "Messages") sawListHeading = true
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        // Explicit "this is the DM list, not a thread" signals — checked before
        // the generic rule below so they win even if a search box's EditText
        // would otherwise have counted as "hasInput".
        if (hasNewDmMarker || (sawListHeading && rows.isEmpty())) return null

        // A chat window needs BOTH an input box AND something that only a
        // thread has: an actual message-row shape, or the "私信" placeholder an
        // empty thread shows. A list screen's search box has an EditText too,
        // so hasInput alone used to misfire OCR fallback on the DM list.
        if (!hasInput || !(hasMessageRowShape || hasDmLabel)) return null

        // X left-aligns the thread title (x≈300..443 of 1200), so widen the
        // shared helper's "roughly centered" band for this app.
        val title = findTitleInActionBar(root, firstRowTop, width, res, 0.15, 0.85)
        // In a DM thread but no rows parsed → empty snapshot (OCR fallback cue).
        if (rows.isEmpty()) return ChatSnapshot(title, emptyList())
        rows.sortBy { it.top }
        val msgs = rows.map { Msg(if (it.sender == "你" || it.sender == "You") "me" else "other", it.text) }
        return ChatSnapshot(title, msgs)
    }

    private data class Row(val top: Int, val sender: String, val text: String)
}


/** A time label that is not a timestamp *pattern* — `looksLikeTimestamp` only
 *  knows clock/date shapes, but Douyin and Duoshan also print relative labels
 *  as their own node. A message that is literally one of these is not worth
 *  losing sleep over; mistaking the label for a message is worse. */
private val RELATIVE_TIME_LABELS = setOf("刚刚", "昨天", "今天", "前天", "更早")

/** Douyin (com.ss.android.ugc.aweme) and Duoshan (my.maya.android).
 *
 *  Duoshan is the SAME codebase — its launcher is
 *  `com.ss.android.ugc.aweme.splash.SplashActivity` — so one implementation
 *  serves both and only [pkg] differs.
 *
 *  Verified against screenshots on a real device (1440x3200, 2026-09-27) by
 *  reading one two-account conversation from each app in turn, which is what
 *  pins the side rule down: the same message sits on the right in the app whose
 *  account sent it and on the left in the other, so **the avatar's edge is the
 *  sender** (left → other, right → me). The avatar's contentDescription is
 *  *not* usable for this — it is the login account's own name on the right and
 *  the peer's on the left, which is the same fact stated twice.
 *
 *  Douyin obfuscates view ids per build (`1o-`, `glu`, `swq`, `dxs`…), so
 *  nothing here may depend on them. The one readable id, the `msg_et` input
 *  box, is used only to answer "are we in a chat". Everything else is found by
 *  structure: the messages sit in a RecyclerView, each item carries a ~124px
 *  avatar pinned to its sender's edge, and the body is a DmtTextView when the
 *  bubble is text or (Douoshan) a plain TextView — picked as the widest
 *  qualifying text so timestamps, avatars, "图片" and "已读" lose out.
 *
 *  Known limit: a shared video/image card contributes its caption or author
 *  label as the message text, because the card's own text is not a bubble.
 *
 *  **v9 (2026-09-27): the stale-screen fix.** Douyin and Duoshan leave the whole
 *  消息 screen — a story ring *and* the conversation list, both RecyclerViews —
 *  in the tree while a chat is open, and both sit above the input box, so the old
 *  geometry test did not exclude them. Avatar counting could not separate them
 *  either (measured on a real Douyin chat: story ring 1, conversation list 1,
 *  the actual message list 1), and the tie went to whichever the depth-first
 *  walk happened to meet first — the story ring. That is exactly why the replies
 *  came out the same no matter which chat was opened: the story ring is the same
 *  five names every time. The list is now chosen as shown in [findMessageList].
 */
class DouyinAdapter(override val pkg: String) : ChatAppAdapter {

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        // Pass 1 — cheap: are we in a chat at all? One walk, no per-node subtree
        // work. The expensive case is not the chat, it is the video feed: Douyin
        // fires a content-changed event for every frame of playback, and each one
        // used to walk the whole tree and then walk it again for every
        // RecyclerView — all on the MAIN thread, which starved the overlay until
        // it could no longer be dragged (reported 2026-09-27).
        //
        // The send box doubles as the anchor for pass 2: it exists only in a chat,
        // the message list is always pinned right above it, and the container it
        // shares with the list is what tells the chat's own list apart from the
        // stale 消息 screen's.
        val input = inputBox(root) ?: run { breadcrumb("not-in-chat"); return null }
        val inputBounds = Rect(); input.getBoundsInScreen(inputBounds)
        val inputTop = inputBounds.top

        // Pass 2 — only inside a chat, and only now, pay to find the list.
        val list = findMessageList(root, width, inputTop, ancestorsOf(input))
        val rows = ArrayList<Row>()
        if (list != null) {
            for (i in 0 until list.childCount) {
                val item = list.getChild(i) ?: continue
                val body = findBody(item) ?: continue
                rows.add(Row(body.rect.top, sideOf(item, body.rect, width), body.text))
            }
        }
        val title = findTitleInActionBar(root, Int.MAX_VALUE, width, res)
        // A one-line breadcrumb, emitted only when the reading changes. Without
        // it a failure here is invisible: targetFor() silently gives up when no
        // title can be determined, so the app simply never analyses and nothing
        // anywhere is logged to say why. The chosen list's own bounds are in it
        // too — `rows` alone cannot show that the wrong list was read.
        val where = list?.let { val b = Rect(); it.getBoundsInScreen(b); "$b" } ?: "none"
        breadcrumb("list=$where rows=${rows.size} title=${title ?: "NULL"}")
        // In a chat but nothing readable → empty snapshot, the OCR-fallback cue.
        if (rows.isEmpty()) return ChatSnapshot(title, emptyList())
        rows.sortBy { it.top }
        return ChatSnapshot(title, rows.map { Msg(it.side, it.text) })
    }

    /** Log [line] under this adapter's package, only when it changes. */
    private fun breadcrumb(line: String) {
        if (line == lastExtractLog) return
        lastExtractLog = line
        android.util.Log.i("JEVASSIST", "$pkg $line")
    }

    /** Last logged reading; see the breadcrumb in [extract]. */
    private var lastExtractLog = ""

    /** One walk, no subtree work: the send-message box node, or null. */
    private fun inputBox(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val n = stack.removeLast()
            if ((n.viewIdResourceName ?: "").endsWith(INPUT_ID_SUFFIX)) {
                val b = Rect(); n.getBoundsInScreen(b)
                if (b.height() > 0) return n
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    /** The input box's ancestor chain, deepest first, capped for safety. */
    private fun ancestorsOf(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val chain = ArrayList<AccessibilityNodeInfo>()
        var cur: AccessibilityNodeInfo? = node
        while (cur != null && chain.size < ANCESTOR_LIMIT) { chain.add(cur); cur = cur.parent }
        return chain
    }

    /**
     * How deep a container [rv] shares with the input box: the index, in
     * `inputChain`, of their deepest common ancestor. 0 would mean the list sits
     * inside the input box itself; smaller is a more specific container.
     * `Int.MAX_VALUE` when they share nothing but the window root.
     */
    private fun sharedDepth(rv: AccessibilityNodeInfo, inputChain: List<AccessibilityNodeInfo>): Int {
        var cur: AccessibilityNodeInfo? = rv
        var steps = 0
        while (cur != null && steps < ANCESTOR_LIMIT) {
            val i = inputChain.indexOfFirst { it == cur }
            if (i >= 0) return i
            cur = cur.parent
            steps++
        }
        return Int.MAX_VALUE
    }

    /**
     * The message list, or null when no RecyclerView qualifies.
     *
     * 1. **Geometry** — it must sit above the send box.
     * 2. **Avatars** — the list whose items carry sender avatars beats the
     *    quick-reply strip (a flat row of pills: 0 avatars).
     * 3. **Container** — ties on the avatar count go to the list that shares the
     *    *deepest* container with the input box. This is what separates the
     *    chat's own list from the stale 消息 screen: the chat's list and the input
     *    live inside one screen container, while the stale story ring and
     *    conversation list only meet the input at the window's content root.
     *    Without this the tie (all three scored 1 on a real Douyin chat) went to
     *    whichever RecyclerView came first in the tree walk — the story ring.
     */
    private fun findMessageList(
        root: AccessibilityNodeInfo,
        width: Int,
        inputTop: Int,
        inputChain: List<AccessibilityNodeInfo>
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestDepth = Int.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val n = stack.removeLast()
            if ((n.className?.toString() ?: "").contains("RecyclerView")) {
                val b = Rect(); n.getBoundsInScreen(b)
                // Above the send box, allowing for the list's own bottom padding.
                if (b.top < inputTop && b.bottom <= inputTop + INPUT_SLACK) {
                    val score = avatarItemCount(n, width)
                    val depth = if (score > 0) sharedDepth(n, inputChain) else Int.MAX_VALUE
                    if (score > bestScore || (score == bestScore && score > 0 && depth < bestDepth)) {
                        bestScore = score
                        bestDepth = depth
                        best = n
                    }
                }
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
        return best
    }

    /**
     * The message body of one list item, or null if the item carries no text.
     *
     * A DmtTextView always wins (that is Douyin's bubble text); otherwise the
     * widest qualifying node wins, which is what picks the bubble over the
     * nickname label, the timestamp and the emoji-reaction chips that share the
     * same item.
     *
     * **v9 fix — the bodies were coming back EMPTY.** This walks a node's `text`
     * *or* its `contentDescription`, but the caller used to store `node.text`
     * unconditionally. Both apps put the message in the contentDescription and
     * leave `text` blank (`DmtTextView.swq desc="…"` on Douyin, a plain
     * `TextView` with `text=""` and a desc on Duoshan), so every message was
     * captured as an empty string. Measured on device 2026-09-27, the breadcrumb
     * read `rows=4 … me:0 | other:0 | other:38 | other:3` — i.e. a full
     * conversation reduced to three empty bodies. The model was handed a blank
     * chat, which is why the suggestions came out as the same few generic lines
     * for every conversation. The matched label is what gets returned now.
     */
    private fun findBody(item: AccessibilityNodeInfo): Body? {
        var widest: Body? = null
        var widestW = -1
        var bubble: Body? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(item)
        var guard = 0
        while (stack.isNotEmpty() && guard < 400) {
            guard++
            val n = stack.removeLast()
            val text = n.text?.toString()?.trim()
            val desc = n.contentDescription?.toString()?.trim()
            val label = if (text.isNullOrBlank()) desc else text
            if (!label.isNullOrBlank() && isBodyLabel(label, desc)) {
                val b = Rect(); n.getBoundsInScreen(b)
                // Reject unset/offscreen rects. RecyclerView keeps detached and
                // not-yet-laid-out items in the tree, and Android reports those as
                // the Int.MAX_VALUE/MIN_VALUE sentinel (seen on Duoshan). Left in,
                // one such node lands in `rows` with a nonsense top and is sorted
                // to the wrong end of the conversation.
                if (b.width() > 0 && b.height() > 0 && b.top >= 0) {
                    val body = Body(label, b)
                    if (b.width() > widestW) { widestW = b.width(); widest = body }
                    if ((n.className?.toString() ?: "").contains("DmtTextView")) bubble = body
                }
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
        return bubble ?: widest
    }

    /** One message body: the text it carries (its `text`, else its description)
     *  and where it sits, which the side rule and the sort order both need. */
    private data class Body(val text: String, val rect: Rect)

    /** Is this node's text a message body rather than chat chrome? */
    private fun isBodyLabel(label: String, desc: String?): Boolean {
        if (looksLikeTimestamp(label)) return false
        if (label in RELATIVE_TIME_LABELS) return false
        // The avatar button is labelled "<nickname>的头像" on both apps.
        if (desc != null && desc.endsWith(AVATAR_SUFFIX)) return false
        if (label == "图片" || label == "视频" || label == "已读") return false
        return true
    }

    /** How many of [list]'s direct children carry a sender avatar. */
    private fun avatarItemCount(list: AccessibilityNodeInfo, width: Int): Int {
        var n = 0
        for (i in 0 until list.childCount) {
            val item = list.getChild(i) ?: continue
            if (hasAvatar(item, width)) n++
        }
        return n
    }

    /**
     * Does [item] carry a sender avatar? Short-circuits on the first hit and
     * stops after [AVATAR_SCAN_BUDGET] nodes: an item can be a whole media card,
     * and this runs once per candidate item of every RecyclerView, on the main
     * thread, for every accessibility event.
     */
    private fun hasAvatar(item: AccessibilityNodeInfo, width: Int): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(item)
        var guard = 0
        while (stack.isNotEmpty() && guard < AVATAR_SCAN_BUDGET) {
            guard++
            val n = stack.removeLast()
            val b = Rect(); n.getBoundsInScreen(b)
            if (isAvatarRect(b, width)) return true
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
        return false
    }

    /** Centre x of [item]'s sender avatar, or -1 when the item has none. */
    private fun avatarCenter(item: AccessibilityNodeInfo, width: Int): Int {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(item)
        var guard = 0
        while (stack.isNotEmpty() && guard < AVATAR_SCAN_BUDGET) {
            guard++
            val n = stack.removeLast()
            val b = Rect(); n.getBoundsInScreen(b)
            if (isAvatarRect(b, width)) return b.centerX()
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
        return -1
    }

    /**
     * A small square hugging one screen edge. The media cards, bubbles and emoji
     * chips sharing an item are either larger or nowhere near an edge.
     */
    private fun isAvatarRect(b: Rect, width: Int): Boolean {
        val w = b.width(); val h = b.height()
        return w in AVATAR_MIN..AVATAR_MAX && h in AVATAR_MIN..AVATAR_MAX &&
            (b.left <= width * EDGE_RATIO || b.right >= width * (1 - EDGE_RATIO))
    }

    /**
     * Which side sent this item: the edge its avatar hugs. Falls back to the
     * body's own centre when no avatar is present, so a layout change degrades
     * the reading instead of throwing the message away.
     */
    private fun sideOf(item: AccessibilityNodeInfo, body: Rect, width: Int): String {
        val avatar = avatarCenter(item, width)
        val cx = if (avatar >= 0) avatar else body.centerX()
        return if (cx > width / 2) "me" else "other"
    }

    private data class Row(val top: Int, val side: String, val text: String)

    companion object {
        /** The send-message box. The only id either app leaves un-obfuscated. */
        private const val INPUT_ID_SUFFIX = ":id/msg_et"
        private const val AVATAR_SUFFIX = "的头像"
        private const val AVATAR_MIN = 90
        private const val AVATAR_MAX = 180

        /** Node budget for one avatar search. Douyin's media cards are deep, and
         *  this runs on the main thread for every event; the avatar is always
         *  near the top of an item, so a small budget finds it and caps the cost. */
        private const val AVATAR_SCAN_BUDGET = 40

        /** How far below the send box's top edge a message list may still end.
         *  Douyin's list overhangs its input by ~90px of padding (measured). */
        private const val INPUT_SLACK = 150

        /** Fraction of the screen width an avatar must sit within to count. */
        private const val EDGE_RATIO = 0.15

        /** Depth cap when walking up from the input box. Real trees are ~30 deep;
         *  this only stops a malformed one. */
        private const val ANCESTOR_LIMIT = 64
    }
}
