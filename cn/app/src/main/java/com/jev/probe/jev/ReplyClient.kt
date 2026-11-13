package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts the candidate replies, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Prefs].
 *
 * Works unchanged against DeepSeek official (`https://api.deepseek.com/v1`): the
 * wire format is plain OpenAI chat completions, and the JSON-mode handling for
 * the drafting call lives in [DeepSeekDialect].
 */
class ReplyClient(private val prefs: Prefs) {

    /**
     * [count] varied candidate replies (1..3), in the language of the other
     * person's latest message (English chats get English replies). [avoid] holds
     * replies already shown for this chat ("换一组"), which the model must not repeat.
     *
     * @param ctx D-stage knowledge context. When present its background and
     *        history are prepended to the prompt with an instruction to stay
     *        consistent with them and invent nothing beyond them.
     */
    fun draft(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        count: Int = 3,
        avoid: List<String> = emptyList()
    ): List<String> {
        val n = count.coerceIn(1, 3)
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        // The DeepSeek JSON Output guide requires the word "json" in the prompt
        // AND a sample of the wanted shape; both are present here. The sample is
        // an OBJECT holding the array, because `response_format=json_object`
        // produces a JSON object — asking for a bare array is what made the
        // model answer in a shape the old parser could not read (see [parseN]).
        // A bare array is still accepted there, so a host without
        // `response_format` keeps working.
        val sys = "你是即时通讯回复助手。只输出一个 JSON 对象，含一个字段 \"$REPLIES_KEY\"，" +
            "其值是含且仅含 $n 条候选回复文本的数组，" +
            (if (n > 1) "各条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。"
             else "给出最稳妥、最自然的一条。") +
            "回复语言跟随对方最近一条消息：对方用英文就用自然的英文回复，用中文就用中文回复。" +
            "每条不超过 40 字（英文不超过 30 个单词），口语、自然、像真人在聊天软件里发消息。" +
            "输出格式必须是合法 JSON，形如 {\"$REPLIES_KEY\":[\"第一条\"]}。" +
            "不要解释，不要加任何 JSON 以外的内容，直接输出这个 JSON 对象。"
        val user = knowledgeBlock(relationship, ctx) +
            "关系：$relationship\n\n最近对话：\n$convo\n\n" +
            (if (avoid.isEmpty()) "" else
                "下面这些回复已经给过，不要重复，也不要只是换个说法：\n" + avoid.joinToString("\n") { "- $it" } + "\n\n") +
            "请给出 $n 条候选回复。"
        val out = parseN(chat(sys, user, temperature = if (avoid.isEmpty()) 0.8 else 0.95, jsonMode = true), n)
        // Lengths only — never the drafted text, which is derived from the chat.
        Log.i(TAG, "draft ok n=${out.size} lens=${out.joinToString(",") { it.length.toString() }}")
        return out
    }

    /** The background + history preamble; empty string when there is no context. */
    private fun knowledgeBlock(relationship: String, ctx: ChatContext?): String {
        ctx ?: return ""
        val background = ctx.background(relationship)
        val history = ctx.history
        if (background.isBlank() && history.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是关于我和对方的背景与知识库，回复必须与之一致，")
            .append("可以直接引用其中事实，不要编造知识库里没有的事实。\n")
        if (background.isNotBlank()) sb.append(background).append('\n')
        if (history.isNotEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n")
            history.takeLast(prefs.contextHistoryCount.coerceIn(0, 100)).forEach {
                sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * One plain chat round trip for the settings connectivity test. Deliberately
     * NOT [summarize]: the test should exercise the ordinary path, not whatever
     * the summary prompt happens to be.
     */
    fun ping(): String =
        chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", temperature = 0.0).trim()

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    fun summarize(text: String): String {
        if (text.isBlank()) return ""
        val sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。"
        return chat(sys, text, temperature = 0.2).trim()
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private fun chat(
        system: String,
        user: String,
        temperature: Double,
        jsonMode: Boolean = false
    ): String {
        val url = prefs.replyEndpoint()
        val content = DeepSeekDialect.complete(
            url = url,
            key = prefs.effectiveReplyKey(),
            model = prefs.replyModel,
            system = system,
            user = user,
            temperature = temperature,
            route = Route.REPLY,
            jsonMode = jsonMode
        )
        if (content.isBlank()) {
            // Used to return "" here, which surfaced to the user as three
            // identical "（稍等，我看下）" placeholders and no explanation.
            throw ApiException(Route.REPLY, null, "模型返回了空内容")
        }
        return content
    }

    /**
     * Up to [n] non-blank replies out of the model's answer; fewer when the model
     * gave fewer (never padded with filler).
     *
     * Tries a real JSON array first (fences and surrounding prose tolerated),
     * then a JSON **object** with an array-valued field, then falls back to line
     * splitting. The object branch is not decorative: `response_format=json_object`
     * makes DeepSeek answer with an object, so the whole reply arrives as one
     * line of `{"…":[…]}`. The array parse missed it, the line fallback saw a
     * single line, and the draft was declared failed — measured on device
     * 2026-09-27: `draft returned 1 usable line(s); treating as failure`, i.e.
     * no candidate replies at all. Both shapes are accepted now.
     */
    private fun parseN(content: String, n: Int): List<String> {
        DeepSeekDialect.parseArray(content)?.let { arr -> collect(arr, n)?.let { return it } }
        DeepSeekDialect.parseObject(content)?.let { obj ->
            // `replies` first (what the prompt asks for), then any array field.
            collect(obj.optJSONArray(REPLIES_KEY), n)?.let { return it }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val value = obj.opt(keys.next())
                if (value is JSONArray) collect(value, n)?.let { return it }
            }
        }
        // Fallback: split lines, stripping common list markers.
        val lines = content.split("\n")
            .map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"', '[', '{').trimEnd('"', ',', ']', '}') }
            .filter { it.isNotBlank() }
            .distinct()
        if (lines.isNotEmpty()) return lines.take(n)
        Log.w(TAG, "draft returned no usable text; treating as failure")
        throw ApiException(Route.REPLY, null, "没能从返回里解析出候选回复：${content.take(120)}")
    }

    /** Up to [n] distinct non-blank strings of [arr], or null when none parse. */
    private fun collect(arr: JSONArray?, n: Int): List<String>? {
        if (arr == null) return null
        val out = ArrayList<String>(n)
        for (i in 0 until arr.length()) {
            val t = (arr.opt(i) as? String)?.trim() ?: continue
            if (t.isNotEmpty() && !out.contains(t)) out.add(t)
            if (out.size == n) break
        }
        return out.ifEmpty { null }
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** The field the draft prompt asks the model to put the array under. */
        private const val REPLIES_KEY = "replies"
    }
}
