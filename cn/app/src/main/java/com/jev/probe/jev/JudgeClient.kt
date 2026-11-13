package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import com.jev.probe.core.kb.ChatContext
import org.json.JSONObject

/**
 * The Jev judgment route only: the 7 judgment questions in one call, and the
 * ranking question over already-drafted candidates. Reads judgeProvider /
 * judgeBaseUrl / judgeKey / judgeModel from [Prefs].
 *
 * 两种传输通向同一组 7 个答案：
 * - **Jev 协议**（OpenRouter / Bocha / TypeSafe / Vercel / Zen / Opper / 自定义）：
 *   一次 POST `{model, state, questions}`，答案按题 id 返回。
 * - **OpenAI chat completions**（DeepSeek 官方）：题目渲染成 prompt 发出去，
 *   回过来的 JSON 再映射回同样的 id（见 [JevQuestions.judgeSystemPrompt] 与
 *   [JevQuestions.judgeUserPrompt]）。
 */
class JudgeClient(private val prefs: Prefs) {

    /**
     * The 7 judgment questions (fast, ~1s). Errors are returned, not thrown.
     *
     * @param ctx D-stage knowledge context; null or empty means the request body
     *        is byte-for-byte what v1.2 sent.
     */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val start = System.currentTimeMillis()
        return try {
            val answers = postDecisions(
                snapshot, relationship, ctx,
                JevQuestions.judge()
            )
            Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = e.message ?: "判断接口请求失败")
        }
    }

    /** Ask Jev which of the candidate replies is best; throws on failure. */
    fun rank(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val answers = postDecisions(snapshot, relationship, ctx, questions)
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    /**
     * POST one decisions request, with the knowledge fields when there are any.
     *
     * Defensive retry: whether the live `alpha/decisions` endpoint accepts the
     * new `background` / `history` state fields or rejects unknown ones with a
     * 4xx is not verified against production yet (see the A-stage report). If a
     * request carrying them comes back 4xx, it is sent again once without them,
     * so an unverified field can degrade the analysis but never break it.
     */
    private fun postDecisions(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext?,
        questions: JSONObject
    ): JSONObject {
        val background = ctx?.background(relationship) ?: ""
        val history = ctx?.history ?: emptyList()
        val enriched = background.isNotBlank() || history.isNotEmpty()
        return try {
            send(JevQuestions.buildState(snapshot, relationship, background, history), questions)
        } catch (e: ApiException) {
            if (enriched && e.status != null && e.status in 400..499) {
                Log.w(TAG, "judge HTTP ${e.status} with background/history; retrying plain")
                send(JevQuestions.buildState(snapshot, relationship), questions)
            } else throw e
        }
    }

    private fun send(state: JSONObject, questions: JSONObject): JSONObject {
        val url = prefs.judgeEndpoint()
        // DeepSeek 官方说 chat/completions，不说 Jev 决策协议：把同样的题目渲染成
        // prompt，再把它回过来的 JSON 映射回同样的 key，这样下游所有消费者
        // （悬浮窗、设置页测试、知识库）都不用改。
        if (prefs.judgeUsesChatCompletions()) {
            return sendViaChatCompletions(url, state, questions)
        }
        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("state", state)
            .put("questions", questions)
        val resp = HttpJson.post(url, prefs.judgeKey, body, Route.JUDGE, HttpJson.headersFor(url))
        // `answers` is the whole point of the decision protocol: an empty object
        // here used to be handed downstream, where every question falls back to
        // its default and the user gets a confident-looking "平静 / 不紧张" card
        // out of a 200 that answered nothing. A protocol violation is an error.
        return resp.optJSONObject("answers")
            ?: throw ApiException(Route.JUDGE, null, "返回缺少 answers 字段：${resp.toString().take(120)}")
    }

    /**
     * chat/completions 传输：一个 prompt 带上 state 与被问到的题目，回一个 JSON。
     *
     * 模型被要求只回答被问到的题（judge 与 rank 问的题集不同），也可能合法地只
     * 回一个子集；[JevQuestions.mergeJudgeReply] 用安全默认值补空，而不是让残缺
     * 答案变成一个满是 null 的分析结果。
     */
    private fun sendViaChatCompletions(
        url: String,
        state: JSONObject,
        questions: JSONObject
    ): JSONObject {
        val asked = questions.keys().asSequence().toList()
        val raw = DeepSeekDialect.complete(
            url = url,
            key = prefs.judgeKey,
            model = prefs.judgeModel,
            system = JevQuestions.judgeSystemPrompt(),
            user = JevQuestions.judgeUserPrompt(state, questions),
            temperature = 0.0,
            route = Route.JUDGE,
            jsonMode = true
        )
        val parsed = DeepSeekDialect.parseObject(raw)
        if (parsed == null) {
            Log.w(TAG, "judge reply was not JSON; treating as unparseable")
            throw ApiException(Route.JUDGE, null, "返回内容不是合法 JSON：${raw.take(120)}")
        }
        return JevQuestions.mergeJudgeReply(parsed, asked)
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    companion object { private const val TAG = "JEVASSIST" }
}
