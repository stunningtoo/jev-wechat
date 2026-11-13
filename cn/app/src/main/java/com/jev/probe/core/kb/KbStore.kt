package com.jev.probe.core.kb

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer

/** Note / contact / history counts, for the settings screen. */
data class KbCounts(val notes: Int, val contacts: Int, val logLines: Int)

/**
 * The knowledge-base store: three kinds of JSON file under `filesDir/kb`.
 *
 *   kb/notes.json           all notes
 *   kb/contacts.json        all contacts
 *   kb/logs/<contactId>.json per-contact chat history (≤ 300 lines)
 *
 * Single writer by construction: every read and write goes through one lock, and
 * writes land via a temp file + rename so a kill mid-write can never leave half
 * a JSON document behind. Serialization is hand-written org.json (no Gson/Moshi
 * dependency). Chat text never reaches logcat — only counts and lengths.
 */
class KbStore private constructor(context: Context) {

    private val app = context.applicationContext
    private val lock = Any()

    private val root: File get() = File(app.filesDir, "kb")
    private val notesFile: File get() = File(root, "notes.json")
    private val contactsFile: File get() = File(root, "contacts.json")
    private fun logFile(contactId: String) = File(File(root, "logs"), "$contactId.json")
    private fun screenFile(contactId: String) = File(File(root, "logs"), "$contactId.screen.json")

    private var notesCache: MutableList<Note>? = null
    private var contactsCache: MutableList<Contact>? = null
    private val logCache = HashMap<String, MutableList<LogEntry>>()

    /** Per contact: the comparison keys of the last screen written. See [appendLog]. */
    private val lastScreenCache = HashMap<String, List<String>>()

    // ------------------------------------------------------------------ notes

    fun notes(): List<Note> = synchronized(lock) { loadNotes().toList() }

    fun note(id: String): Note? = synchronized(lock) { loadNotes().firstOrNull { it.id == id } }

    /** Insert or replace by id. Returns false when it did not reach disk. */
    fun saveNote(note: Note): Boolean = synchronized(lock) {
        val list = loadNotes()
        val i = list.indexOfFirst { it.id == note.id }
        val stamped = note.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(notesFile, notesJson(list))
        if (!ok) notesCache = null   // memory must not claim a write that failed
        ok
    }

    fun deleteNote(id: String): Boolean = synchronized(lock) {
        val list = loadNotes()
        if (!list.removeAll { it.id == id }) return@synchronized true
        val ok = writeAtomic(notesFile, notesJson(list))
        if (!ok) notesCache = null
        ok
    }

    // --------------------------------------------------------------- contacts

    fun contacts(): List<Contact> = synchronized(lock) { loadContacts().toList() }

    fun contact(id: String): Contact? = synchronized(lock) { loadContacts().firstOrNull { it.id == id } }

    fun saveContact(c: Contact): Boolean = synchronized(lock) {
        val list = loadContacts()
        val i = list.indexOfFirst { it.id == c.id }
        val stamped = c.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(contactsFile, contactsJson(list))
        if (!ok) contactsCache = null
        ok
    }

    /** Removes the contact and its history file. */
    fun deleteContact(id: String): Boolean = synchronized(lock) {
        val list = loadContacts()
        var ok = true
        if (list.removeAll { it.id == id }) {
            ok = writeAtomic(contactsFile, contactsJson(list))
            if (!ok) contactsCache = null
        }
        // Only drop the history once the contact list no longer references it.
        // A failed contacts write used to leave the contact in place but delete
        // its logs anyway: the contact came back on the next load, with all of
        // its history gone.
        if (!ok) return@synchronized false
        logCache.remove(id)
        lastScreenCache.remove(id)
        runCatching { logFile(id).delete() }
        runCatching { screenFile(id).delete() }
        true
    }

    /**
     * Match a conversation title to a contact by normalized name or alias.
     * Never creates anything: an unknown title simply has no contact (v1.3
     * revision — contacts are only ever created by the user).
     *
     * @param app package name of the chat app the title came from; used only to
     *        prefer a contact that already knows this app when two match.
     */
    fun findContact(title: String, app: String): Contact? {
        synchronized(lock) {
            val want = normalizeName(title)
            if (want.isEmpty()) return null
            val hits = loadContacts().filter { c ->
                normalizeName(c.name) == want || c.aliases.any { normalizeName(it) == want }
            }
            if (hits.isEmpty()) return null
            return hits.firstOrNull { app.isNotBlank() && it.apps.contains(app) } ?: hits.first()
        }
    }

    /**
     * Create a contact from a conversation title, or fold the title/app into the
     * one that already matches. Returns a message for the toast.
     *
     * Held under [lock] for the whole read-modify-write: the capture worker and
     * the analysis thread both call this, and the unlocked version let two
     * callers see "no such contact" and create two.
     */
    fun saveOrMergeContact(title: String, app: String): String = synchronized(lock) {
        val display = displayName(title)
        if (display.isEmpty()) return "当前会话没有标题，存不了"
        val existing = findContact(title, app)
        if (existing == null) {
            val aliases = if (displayName(title) != title.trim()) listOf(title.trim()) else emptyList()
            val ok = saveContact(Contact(
                id = newId(),
                name = display,
                aliases = aliases,
                apps = if (app.isBlank()) emptyList() else listOf(app)
            ))
            return if (ok) "已存为联系人「$display」" else WRITE_FAILED
        }
        val apps = if (app.isBlank() || existing.apps.contains(app)) existing.apps else existing.apps + app
        val raw = title.trim()
        val known = (listOf(existing.name) + existing.aliases).map { normalizeName(it) }
        val aliases = if (raw.isNotEmpty() && normalizeName(raw) !in known)
            existing.aliases + raw else existing.aliases
        if (apps == existing.apps && aliases == existing.aliases)
            return "联系人「${existing.name}」已存在"
        val ok = saveContact(existing.copy(apps = apps, aliases = aliases))
        if (ok) "已并入联系人「${existing.name}」" else WRITE_FAILED
    }

    // ---------------------------------------------------------------- history

    /**
     * Append one screenful of messages, keeping only the newest [MAX_LOG].
     *
     * The unit is a SEQUENCE, not a set of lines. A capture gives us the whole
     * visible screen S, top to bottom; P is whatever screen we last wrote for
     * this contact. Two identical short lines on one screen are two positions and
     * get two entries — they are not folded together, and nothing is dropped for
     * being "too short to dedupe on" or for having been said before.
     *
     * The rules, in order:
     *  - S equals P            → the same screen again, write nothing.
     *  - log is empty          → write all of S.
     *  - log tail matches the first k lines of S (k > 0) → the screen scrolled by
     *    (S.size - k) lines; append only that new tail.
     *  - k is 0 and S shares nothing with P → the user scrolled up into old
     *    messages we already hold; this round writes nothing rather than
     *    duplicating history at the end of the file.
     *  - anything else         → append all of S.
     *
     * @param screenBatch true for a capture (the rules above). False for a
     *        deliberate single-entry injection that is NOT a screen read, which
     *        is appended as-is.
     */
    fun appendLog(contactId: String, entries: List<LogEntry>, screenBatch: Boolean = true): Boolean {
        if (entries.isEmpty()) return true
        synchronized(lock) {
            val screen = entries.filter { it.text.isNotBlank() }
            if (screen.isEmpty()) return true
            val list = loadLog(contactId)
            val keys = screen.map { key(it.side, it.text) }
            val prev = if (screenBatch) loadLastScreen(contactId) else emptyList()

            // Same screen as last time: nothing happened worth recording.
            if (screenBatch && prev.isNotEmpty() && prev == keys) return true

            // How much of S the log already ends with.
            var k = 0
            val maxK = minOf(list.size, keys.size)
            for (cand in maxK downTo 1) {
                var match = true
                for (i in 0 until cand) {
                    val e = list[list.size - cand + i]
                    if (key(e.side, e.text) != keys[i]) { match = false; break }
                }
                if (match) { k = cand; break }
            }

            val tail: List<LogEntry> = when {
                !screenBatch -> screen
                list.isEmpty() -> screen
                k > 0 -> screen.drop(k)
                // Nothing in common with the screen we last wrote → we are looking
                // at older messages, not newer ones. Leave the log alone, but DO
                // record this screen as the new reference: otherwise prev stays
                // pinned to a screen the user has scrolled away from and every
                // later capture keeps failing the same overlap test, so the log
                // stops growing for that contact for as long as the session lasts.
                prev.isNotEmpty() && keys.none { it in prev } -> {
                    Log.d(TAG, "appendLog contact=$contactId skipped: scrolled off the last screen")
                    saveLastScreen(contactId, keys)
                    return true
                }
                else -> screen
            }
            if (tail.isEmpty()) {
                if (screenBatch) saveLastScreen(contactId, keys)
                return true
            }

            list.addAll(tail)
            while (list.size > MAX_LOG) list.removeAt(0)
            val ok = writeAtomic(logFile(contactId), logJson(list))
            if (!ok) logCache.remove(contactId)
            if (ok && screenBatch) saveLastScreen(contactId, keys)
            Log.d(TAG, "appendLog contact=$contactId added=${tail.size} overlap=$k total=${list.size} ok=$ok")
            return ok
        }
    }

    /** The newest [n] entries, oldest first. */
    fun recentLog(contactId: String, n: Int): List<LogEntry> {
        if (n <= 0) return emptyList()
        synchronized(lock) {
            val list = loadLog(contactId)
            return if (list.size <= n) list.toList()
            else list.subList(list.size - n, list.size).toList()
        }
    }

    fun logSize(contactId: String): Int = synchronized(lock) { loadLog(contactId).size }

    fun clearLog(contactId: String) = synchronized(lock) {
        logCache.remove(contactId)
        lastScreenCache.remove(contactId)
        runCatching { logFile(contactId).delete() }
        runCatching { screenFile(contactId).delete() }
        Unit
    }

    /**
     * The (side, text) sequence of the last screen written for this contact, as
     * comparison keys. Kept on disk as well as in memory so that re-opening a
     * chat that has not moved since does not append the same screen again.
     */
    private fun loadLastScreen(contactId: String): List<String> {
        lastScreenCache[contactId]?.let { return it }
        val out = ArrayList<String>()
        val loaded = readJsonArray(screenFile(contactId))
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotEmpty()) out.add(s)
            }
        }
        if (loaded.trustworthy) lastScreenCache[contactId] = out
        return out
    }

    private fun saveLastScreen(contactId: String, keys: List<String>) {
        lastScreenCache[contactId] = keys
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        if (!writeAtomic(screenFile(contactId), arr.toString())) lastScreenCache.remove(contactId)
    }

    // ------------------------------------------------------------------ admin

    fun counts(): KbCounts = synchronized(lock) {
        val contacts = loadContacts()
        var lines = 0
        // Read the length straight off the file instead of through [loadLog]:
        // loadLog CACHES what it reads, so asking for a count pinned every
        // contact's whole history (up to MAX_LOG each) in memory for the rest of
        // the process — the settings screen and the self-check both call this.
        contacts.forEach { c ->
            lines += (logCache[c.id]?.size ?: readJsonArray(logFile(c.id)).arr?.length() ?: 0)
        }
        KbCounts(loadNotes().size, contacts.size, lines)
    }

    /**
     * Wipe every knowledge-base file. Deletes only `filesDir/kb` — API keys,
     * whitelist and every other SharedPreferences value are untouched.
     */
    fun clearAll() = synchronized(lock) {
        notesCache = null
        contactsCache = null
        logCache.clear()
        lastScreenCache.clear()
        runCatching { root.deleteRecursively() }
        Log.i(TAG, "kb cleared")
        Unit
    }

    // ------------------------------------------------------------------ io

    private fun loadNotes(): MutableList<Note> {
        notesCache?.let { return it }
        val list = ArrayList<Note>()
        val loaded = readJsonArray(notesFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Note(
                    id = o.optString("id").ifBlank { newId() },
                    title = o.optString("title"),
                    content = o.optString("content"),
                    tags = strList(o.optJSONArray("tags")),
                    alwaysOn = o.optBoolean("alwaysOn", false),
                    enabled = o.optBoolean("enabled", true),
                    updatedAt = o.optLong("updatedAt", 0L)
                ))
            }
        }
        if (loaded.trustworthy) notesCache = list
        return list
    }

    private fun loadContacts(): MutableList<Contact> {
        contactsCache?.let { return it }
        val list = ArrayList<Contact>()
        val loaded = readJsonArray(contactsFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Contact(
                    id = o.optString("id").ifBlank { newId() },
                    name = o.optString("name"),
                    aliases = strList(o.optJSONArray("aliases")),
                    apps = strList(o.optJSONArray("apps")),
                    relationship = o.optString("relationship"),
                    notes = o.optString("notes"),
                    autoSummary = o.optString("autoSummary"),
                    updatedAt = o.optLong("updatedAt", 0L)
                ))
            }
        }
        if (loaded.trustworthy) contactsCache = list
        return list
    }

    private fun loadLog(contactId: String): MutableList<LogEntry> {
        logCache[contactId]?.let { return it }
        val list = ArrayList<LogEntry>()
        val loaded = readJsonArray(logFile(contactId))
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(LogEntry(
                    side = o.optString("side", "other"),
                    text = o.optString("text"),
                    ts = o.optLong("ts", 0L),
                    app = o.optString("app")
                ))
            }
        }
        if (loaded.trustworthy) logCache[contactId] = list
        return list
    }

    private fun notesJson(list: List<Note>): String {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(JSONObject()
                .put("id", n.id)
                .put("title", n.title)
                .put("content", n.content)
                .put("tags", JSONArray(n.tags))
                .put("alwaysOn", n.alwaysOn)
                .put("enabled", n.enabled)
                .put("updatedAt", n.updatedAt))
        }
        return arr.toString()
    }

    private fun contactsJson(list: List<Contact>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("aliases", JSONArray(c.aliases))
                .put("apps", JSONArray(c.apps))
                .put("relationship", c.relationship)
                .put("notes", c.notes)
                .put("autoSummary", c.autoSummary)
                .put("updatedAt", c.updatedAt))
        }
        return arr.toString()
    }

    private fun logJson(list: List<LogEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject()
                .put("side", e.side)
                .put("text", e.text)
                .put("ts", e.ts)
                .put("app", e.app))
        }
        return arr.toString()
    }

    /**
     * Result of reading one JSON file. [trustworthy] is false only in the one
     * nasty case: the file exists, does not parse, AND could not be moved aside
     * — then an empty list is a guess, so it must not be cached and must not be
     * written over the user's data.
     */
    private class Loaded(val arr: JSONArray?, val trustworthy: Boolean)

    /** Files that failed to parse and could not be preserved; never overwrite. */
    private val unreadable = HashSet<String>()

    private fun readJsonArray(f: File): Loaded {
        if (!f.exists()) { unreadable.remove(f.absolutePath); return Loaded(null, true) }
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            unreadable.remove(f.absolutePath)
            Loaded(arr, true)
        } catch (e: Exception) {
            // Damaged file: set it aside under a dated name rather than let the
            // next save silently write over it. Starting empty is only safe once
            // the original is actually preserved.
            val backup = File(f.parentFile, "${f.name}.corrupt.${System.currentTimeMillis()}")
            val kept = runCatching { f.renameTo(backup) }.getOrDefault(false)
            if (kept) unreadable.remove(f.absolutePath) else unreadable.add(f.absolutePath)
            Log.w(TAG, "unreadable ${f.name}: ${e.javaClass.simpleName} preserved=$kept")
            Loaded(null, kept)
        }
    }

    /**
     * Temp file + fsync + rename, so a crash never leaves a half-written
     * document.
     *
     * The rename REPLACES the destination in one step (POSIX semantics, same
     * directory) — deleting the old file first would mean a kill in between
     * loses everything. The `fd.sync()` before the rename is what makes that
     * true across a power cut: without it the rename can be committed while the
     * temp file's data is still in the page cache, and the destination comes
     * back as a zero-length file. Returns false when the data did not reach
     * disk; callers drop their cache so the next read goes back to the file.
     */
    private fun writeAtomic(f: File, text: String): Boolean {
        if (f.absolutePath in unreadable) {
            Log.w(TAG, "refusing to overwrite unparsable ${f.name}")
            return false
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        return try {
            f.parentFile?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (tmp.renameTo(f)) return true
            // Same-directory rename should not fail. If it somehow does, an
            // in-place overwrite is the only way left — not atomic, so say so.
            Log.w(TAG, "rename failed, overwriting ${f.name} in place")
            FileOutputStream(f).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            runCatching { tmp.delete() }
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            Log.w(TAG, "write failed ${f.name}: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun strList(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun key(side: String, text: String) = side + "\u0000" + text

    companion object {
        private const val TAG = "JEVASSIST"
        const val MAX_LOG = 300

        /** Toast text when the knowledge directory refuses a write. */
        const val WRITE_FAILED = "保存失败：知识库目录不可写"

        @Volatile private var instance: KbStore? = null

        fun get(context: Context): KbStore =
            instance ?: synchronized(this) {
                instance ?: KbStore(context).also { instance = it }
            }

        fun newId(): String = java.util.UUID.randomUUID().toString().substring(0, 12)

        /**
         * Compile a pattern without ever taking the class down with it. A
         * `Regex(...)` straight in a `val` runs during `<clinit>`, so one bad
         * pattern turns into `ExceptionInInitializerError` and every call into
         * [KbStore] dies with it — which is exactly what happened on device
         * (Android's ICU engine rejected the old member-count pattern). A null
         * here only means that one cleanup step is skipped.
         */
        private fun safeRegex(pattern: String): Regex? =
            runCatching { Regex(pattern) }.getOrElse {
                Log.w(TAG, "regex init failed: ${it.javaClass.simpleName} ${it.message ?: ""}")
                null
            }

        private val ZERO_WIDTH = safeRegex("[\\u200B-\\u200D\\uFEFF]")

        /**
         * Trailing group member count. Written as an alternation of escaped code
         * points rather than a character class holding brackets: ICU on device
         * read `[(...)]` as an unterminated class ("missing closing bracket").
         * No literal full-width bracket in the source, on purpose.
         *
         * The digit class spells out the full-width range as escapes too: Java's
         * `\d` is ASCII-only (UNICODE_CHARACTER_CLASS is off), so "（１２）" never
         * matched and a group title kept its member count while the same title
         * typed half-width lost it — two keys for one group.
         */
        private val TRAILING_COUNT = safeRegex(
            "\\s*(?:\\(|\\uFF08)\\s*[0-9\\uFF10-\\uFF19]+\\s*(?:\\)|\\uFF09)\\s*$")

        private fun stripZeroWidth(s: String): String = ZERO_WIDTH?.replace(s, "") ?: s

        private fun stripTrailingCount(s: String): String =
            TRAILING_COUNT?.replace(s, "")?.trim() ?: s

        /**
         * Name key for matching: NFKC-folded, zero-width characters removed, the
         * group member count `(12)` (half- or full-width) dropped, lower-cased.
         *
         * NFKC is what makes "Ａｌｉｃｅ" and "Alice" the same key — `lowercase()`
         * alone leaves the full-width letters alone, so a title typed with an IME
         * in full-width mode never matched the contact saved in half-width.
         * If the patterns failed to compile this degrades to trim + lowercase.
         */
        fun normalizeName(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            val t = stripTrailingCount(stripZeroWidth(nfkc(s)).trim())
            return t.trim().lowercase()
        }

        /** Same cleanup as [normalizeName] but keeps the original characters, for display. */
        fun displayName(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            return stripTrailingCount(stripZeroWidth(s).trim()).trim()
        }

        /** Loose key for substring matching (no member-count stripping). */
        fun normalizeText(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            return stripZeroWidth(nfkc(s)).trim().lowercase()
        }

        /** Compatibility fold; identity when the platform refuses the form. */
        private fun nfkc(s: String): String =
            runCatching { Normalizer.normalize(s, Normalizer.Form.NFKC) }.getOrDefault(s)
    }
}
