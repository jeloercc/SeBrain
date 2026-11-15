package com.sebrain.app.chat

import com.sebrain.cognition.FrontMatter
import com.sebrain.cognition.NoteMarkup
import com.sebrain.cognition.NoteSections
import java.text.Normalizer

/**
 * The tools the assistant has to work on a note that already exists. The local model cannot
 * call functions, so the app starts a tool: from a button on the note, or from a chat message
 * that starts with an edit verb and names an existing note. Each tool ends in a proposal the
 * user reviews; nothing is written until they accept it (decided 2026-11-13).
 *
 * The model only ever sees the note's own text. The front matter is put back by the app, as
 * are the title line and any tag the model dropped, so those cannot be lost to a rewrite.
 */
object NoteEdits {
    enum class Kind(val label: String, val usesModel: Boolean) {
        FIX("Fix spelling", true),
        IMPROVE("Improve", true),
        EDIT("Edit", true),
        TAGS("Suggest tags", true),
        LINK("Link mentions", false),
    }

    /** A note is rewritten whole, so it must fit the model's 4096 tokens twice: once read, once written. */
    const val MAX_CHARS = 4000

    /**
     * Seconds the phone model needs for [kind] on a note of [chars] characters, from the measured
     * speeds: about 300 tokens/s read, 7.5 tokens/s written, 3.6 characters per token. A rewrite
     * writes the note again; a fix or tags only read it and write a few lines.
     */
    fun secondsFor(kind: Kind, chars: Int, tokensPerSecond: Double = 7.5, addition: Boolean = false): Int {
        val tokens = chars / 3.6
        val written = when (kind) {
            Kind.IMPROVE -> tokens
            Kind.EDIT -> if (addition) 40.0 else tokens
            Kind.FIX -> 40.0
            Kind.TAGS -> 15.0
            Kind.LINK -> return 0
        }
        // Reading is about forty times faster than writing on the phone.
        return (tokens / (tokensPerSecond * 40) + written / tokensPerSecond).toInt() + 3
    }

    /** The most tokens a tool's reply may have: a few lines for a fix or tags, the note again and some more for a rewrite. */
    fun maxTokens(kind: Kind, chars: Int): Int = when (kind) {
        Kind.FIX -> 300
        Kind.TAGS -> 60
        Kind.IMPROVE, Kind.EDIT -> (chars / 3.0).toInt() + 300
        Kind.LINK -> 0
    }

    /** The limit for an instruction that only adds text: a sentence or two. */
    const val ADDITION_TOKENS = 120

    const val SYSTEM =
        "You edit one note from the user's vault. You answer with the full text of the edited note in Markdown " +
            "and nothing else: no introduction, no comment, no question. You keep every [[link]], every #tag and " +
            "every code block exactly as written. You never add a fact that is not in the note or in the " +
            "instruction. You write in the language of the note."

    const val TAGS_SYSTEM = "You choose tags for a note. You answer with one line and nothing else."

    const val FIX_SYSTEM =
        "You proofread a note. You answer only with a list of corrections, one per line, and nothing else. " +
            "You never change the meaning, the links, the tags or the code."

    // ---- From a chat message to a tool ----

    private val VERBS = listOf(
        Kind.FIX to Regex("^(corrige|corregir|corrija|revisa la ortograf\\p{L}+|fix|correct|proofread)\\b", RegexOption.IGNORE_CASE),
        Kind.IMPROVE to Regex("^(mejora|mejorar|mejore|reescribe|reescribir|ordena|ordenar|pule|pulir|improve|rewrite|polish|clean up|tidy)\\b", RegexOption.IGNORE_CASE),
        Kind.LINK to Regex("^(enlaza|enlazar|enlace|vincula|vincular|conecta|conectar|link)\\b", RegexOption.IGNORE_CASE),
        Kind.TAGS to Regex("^(etiqueta|etiquetar|etiquete|ponle etiquetas|tag)\\b", RegexOption.IGNORE_CASE),
        Kind.EDIT to Regex(
            "^(edita|editar|edite|modifica|modificar|cambia|cambiar|actualiza|actualizar|a[ñn]ade|a[ñn]adir|agrega|agregar|quita|quitar|" +
                "elimina|eliminar|borra de|edit|update|change|add|remove|delete from|append)\\b",
            RegexOption.IGNORE_CASE,
        ),
    )
    private val POLITE = Regex("^(por favor|porfa|please|puedes|podr[ií]as|can you|could you|quiero que|necesito que)[,\\s]+", RegexOption.IGNORE_CASE)
    private val NOTE_WORD = Regex("\\b(notas?|notes?)\\b", RegexOption.IGNORE_CASE)

    /**
     * The tool a chat message asks for, or null. The message must start with an edit verb and
     * say "note"; whether it names a note that exists is checked afterwards with [mentioned].
     * "Crea una nota sobre cómo mejorar TCP" starts with "crea", so it is not an edit.
     */
    fun kindOf(message: String): Kind? {
        val text = POLITE.replace(message.trim(), "").trim()
        if (!NOTE_WORD.containsMatchIn(text)) return null
        return VERBS.firstOrNull { it.second.containsMatchIn(text) }?.first
    }

    private val LINK_WORD = Regex("enlaz|enlac|vincul|conect|\\blinks?\\b|\\blinked\\b|unlinked", RegexOption.IGNORE_CASE)
    private val WHOLE_VAULT = Regex(
        "\\b(todas|todos|toda|cada|all|every|each|b[oó]veda|vault|nuestras notas|mis notas|las notas|my notes|our notes|the notes)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True when a message asks to look for missing links across the vault, such as "revisa
     * conceptos no enlazados en todas nuestras notas". That is a review of every note, done
     * without the language model.
     */
    fun wantsVaultReview(message: String): Boolean =
        NOTE_WORD.containsMatchIn(message) && LINK_WORD.containsMatchIn(message) && WHOLE_VAULT.containsMatchIn(message)

    /**
     * The other notes a text could mention, indexed by the first word of each of their names,
     * so reviewing a whole vault does not test every name against every note.
     */
    class Names(others: List<Pair<String, List<String>>>) {
        private val byFirstWord = HashMap<String, MutableList<Pair<String, List<String>>>>()

        init {
            for (other in others) {
                for (name in other.second) {
                    val first = fold(name).substringBefore(' ')
                    if (first.isNotEmpty()) byFirstWord.getOrPut(first) { ArrayList() }.add(other)
                }
            }
        }

        /** The notes whose name could appear in [text]: those with a name starting with a word of it. */
        fun candidatesFor(text: String): List<Pair<String, List<String>>> =
            fold(text).split(' ').toSet().flatMap { byFirstWord[it].orEmpty() }.distinct()
    }

    /**
     * Which of the existing notes [message] names: the longest title or alias that appears in it
     * as whole words, ignoring case and accents. [names] pairs a note id with one of its names.
     */
    fun mentioned(message: String, names: List<Pair<Long, String>>): Long? {
        val text = " " + fold(message) + " "
        return names.filter { it.second.trim().length >= MIN_NAME }
            .sortedByDescending { it.second.length }
            .firstOrNull { text.contains(" " + fold(it.second) + " ") }?.first
    }

    /** Lowercase, without accents, with every run of other characters turned into one space. */
    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // ---- What the model is asked ----

    /** The request for a tool that uses the model. [body] is the note without its front matter. */
    fun prompt(kind: Kind, body: String, instruction: String = "", tags: List<String> = emptyList()): String = when (kind) {
        // Only the corrections are asked for, not the note again: a few tokens instead of the
        // whole text, so it takes seconds, and the model cannot disturb the rest of the note.
        Kind.FIX ->
            "Find the spelling, accent and grammar mistakes in the note. Write one correction per line, as the wrong " +
                "word or short phrase, then =>, then its correction. Copy the wrong text exactly as the note has it. " +
                "Do not copy the note. If there is no mistake, write only NONE.\n\n" +
                // A small model follows an example far better than a description.
                "Example note:\nEl servior esta caido desde ayer.\nExample answer:\nservior => servidor\nesta caido => está caído\n\n" +
                "Note:\n$body\n\nAnswer:"
        Kind.IMPROVE ->
            "Rewrite this note so it is clear and well ordered. Keep every fact it states and add none. Keep its title " +
                "line. When it fits, use this structure: one or two sentences that say what it is, a \"## " +
                (if (spanish(body)) "Puntos clave" else "Key points") + "\" list of short bullets, and a \"## " +
                (if (spanish(body)) "Detalles" else "Details") + "\" section. Leave a section out if the note has nothing " +
                "for it.\n\nNote:\n$body"
        Kind.EDIT ->
            if (isAddition(instruction)) {
                // Only the new text is asked for and the app places it: a few tokens instead of
                // the note again, and nothing else in the note can change.
                "Note:\n$body\n\nThe user asks: ${instruction.trim()}\n" +
                    "Write only the new sentence or bullet to add to the note, in the note's language. Do not copy the note."
            } else {
                // The instruction comes last: a small model follows what it read most recently.
                "Note:\n$body\n\nRewrite the whole note applying this instruction, and change nothing else.\nInstruction: ${instruction.trim()}"
            }
        Kind.TAGS -> buildString {
            append("Choose two to four tags for this note: lowercase, one or two words each.")
            if (tags.isNotEmpty()) append(" Prefer tags from this list when they fit: ").append(tags.joinToString(", ")).append('.')
            append(" Answer with exactly one line: Tags: first, second, third\n\nNote:\n").append(body)
        }
        Kind.LINK -> error("Linking does not use the model")
    }

    private fun spanish(text: String) = Assistant.language(text.take(600)) == "Spanish"

    private val ADD_VERB = Regex("^(a[ñn]ade|a[ñn]adir|agrega|agregar|add|append)\\b", RegexOption.IGNORE_CASE)

    /** True when the instruction asks to add something, which needs only the new text from the model. */
    fun isAddition(instruction: String): Boolean = ADD_VERB.containsMatchIn(POLITE.replace(instruction.trim(), "").trim())

    // ---- From the model's reply to a safe new text ----

    /** The outcome of a tool: the note's full new text, and what the user should know before accepting. */
    class Result(val text: String, val warnings: List<String>)

    private val FENCE_LINE = Regex("^\\s*(```|~~~)")
    private val CODE_BLOCK = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val TITLE_LINE = Regex("^#\\s+\\S.*$")
    private val BULLET = Regex("^(\\s*)[*•+]\\s+")

    private val CORRECTION = Regex("^[\\s>*•-]*(?:\\d+[.)]\\s*)?(.+?)\\s*(?:=>|->|→|⇒)\\s*(.+?)\\s*$")
    private val PROTECTED = Regex("!?\\[\\[[^\\]]*]]|```.*?```|`[^`\\n]*`", RegexOption.DOT_MATCHES_ALL)

    /**
     * Applies the corrections of the model's [reply] to FIX. Each line `wrong => right` replaces
     * the first place the note says `wrong`, unless that place is inside a link or code. A
     * correction whose wrong text is not in the note is counted, not guessed. Null if nothing
     * was changed.
     */
    fun corrected(original: String, reply: String): Result? {
        val start = FrontMatter.parse(original).bodyStart
        var text = original
        var applied = 0
        var unplaced = 0
        var rejected = 0
        for (line in reply.lines()) {
            val match = CORRECTION.find(line) ?: continue
            val wrong = unquote(match.groupValues[1])
            val right = unquote(match.groupValues[2])
            if (wrong.isEmpty() || right.isEmpty() || wrong == right) continue
            // "cifrado asimetrico => criptográfico" is not a spelling fix: it changes what the note says.
            if (!spellingOnly(wrong, right)) {
                rejected++
                continue
            }
            // Links, code and the title line are not corrected: links point to the title.
            val guarded = (PROTECTED.findAll(text, start) + TITLE_IN_TEXT.findAll(text, start)).map { it.range }.toList()
            var at = text.indexOf(wrong, start)
            while (at >= 0 && guarded.any { at <= it.last && at + wrong.length - 1 >= it.first }) at = text.indexOf(wrong, at + 1)
            if (at < 0) {
                unplaced++
                continue
            }
            text = text.replaceRange(at, at + wrong.length, right)
            applied++
        }
        if (applied == 0 && rejected == 0) {
            // No usable list. If the model answered with corrected text instead, take from it only
            // the words that changed a little; the note's structure stays as it is.
            return aligned(original, reply)?.let { Result(it, emptyList()) }
        }
        if (applied == 0) return null
        return Result(
            text,
            listOfNotNull(
                "$unplaced correction(s) of the model did not match the note and were left out".takeIf { unplaced > 0 },
                "$rejected suggestion(s) changed more than spelling and were left out".takeIf { rejected > 0 },
            ),
        )
    }

    private val TITLE_IN_TEXT = Regex("(?m)^#\\s+\\S.*$")

    /** True when [right] is [wrong] with its spelling fixed: the same number of words, each one the same or nearly. */
    private fun spellingOnly(wrong: String, right: String): Boolean {
        val a = WORD.findAll(wrong).map { it.value }.toList()
        val b = WORD.findAll(right).map { it.value }.toList()
        if (a.isEmpty() || a.size != b.size) return false
        return a.indices.all { a[it] == b[it] || similar(a[it], b[it]) }
    }

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /**
     * Spelling fixes taken from a corrected copy of the note. The words of both texts are lined
     * up; where the same number of words differ in the same place, and each differs from its
     * pair only slightly, the note's word is replaced. Sentences the model rewrote, added or
     * dropped are ignored. Null if no word qualifies.
     */
    private fun aligned(original: String, corrected: String): String? {
        val start = FrontMatter.parse(original).bodyStart
        val guarded = (PROTECTED.findAll(original, start) + TITLE_IN_TEXT.findAll(original, start)).map { it.range }.toList()
        val a = WORD.findAll(original, start).toList()
        val b = WORD.findAll(corrected).map { it.value }.toList()
        if (a.isEmpty() || b.isEmpty() || a.size.toLong() * b.size > ALIGN_CELLS) return null
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            table[i][j] = if (a[i].value == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
        val swaps = ArrayList<Pair<IntRange, String>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i].value == b[j]) { i++; j++; continue }
            // A run of differing words on both sides, up to the next word they share.
            val fromI = i
            val fromJ = j
            while (i < a.size && j < b.size && a[i].value != b[j]) {
                if (table[i + 1][j] >= table[i][j + 1]) i++ else j++
            }
            if (i - fromI == j - fromJ) {
                for (k in 0 until i - fromI) {
                    val word = a[fromI + k]
                    val fixed = b[fromJ + k]
                    if (similar(word.value, fixed) && guarded.none { word.range.first <= it.last && word.range.last >= it.first }) swaps += word.range to fixed
                }
            }
        }
        if (swaps.isEmpty()) return null
        var text = original
        for ((range, fixed) in swaps.asReversed()) text = text.replaceRange(range, fixed)
        return text
    }

    /** Two spellings of one word: the same letters without accents or case, or a slip of a letter or two. */
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return false
        if (fold(a) == fold(b)) return true
        if (minOf(a.length, b.length) < 4 || kotlin.math.abs(a.length - b.length) > 1) return false
        var previous = IntArray(b.length + 1) { it }
        for (x in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = x
            for (y in 1..b.length) {
                current[y] = minOf(previous[y] + 1, current[y - 1] + 1, previous[y - 1] + if (a[x - 1].equals(b[y - 1], ignoreCase = true)) 0 else 1)
            }
            previous = current
        }
        return previous[b.length] <= 2
    }

    /**
     * The note with the model's new text added: after the last line of the body, above the tags
     * that close the note. Introductions ("Aquí está:") and quotes around the text are dropped.
     * Null if the reply holds nothing to add or only repeats the note.
     */
    fun appended(original: String, reply: String): Result? {
        // A small model often answers with the whole note and the new sentence inside it. Only
        // the sentences the note did not have are the addition.
        val have = flat(original)
        val addition = unwrap(reply).lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith(":") && !TITLE_ANY.matches(it) && !FENCE_LINE.containsMatchIn(it) && !TAGS_ONLY.matches(it) }
            .flatMap { line -> SENTENCE.split(unquote(line)) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !have.contains(flat(it)) }
            .joinToString(" ")
        if (addition.isEmpty()) return null
        val all = original.trimEnd().lines().toMutableList()
        var tail = all.size
        while (tail > 0 && (all[tail - 1].isBlank() || TAGS_ONLY.matches(all[tail - 1]))) tail--
        val closing = all.subList(tail, all.size).filter { it.isNotBlank() }
        val text = (all.subList(0, tail) + listOf("", addition) + (if (closing.isEmpty()) emptyList() else listOf("") + closing)).joinToString("\n") + "\n"
        return Result(text, emptyList())
    }

    private val TITLE_ANY = Regex("^#{1,6}\\s+\\S.*$")
    private val SENTENCE = Regex("(?<=[.!?])\\s+")

    /** Text with its whitespace collapsed, for comparing sentences. */
    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun unquote(value: String): String = value.trim().trim('"', '“', '”', '`', '\'', '«', '»').trim()

    /**
     * Builds the new text of the note from the model's [reply] to IMPROVE or EDIT. The front
     * matter and the title line of [original] are kept as they were, tags the model dropped are
     * put back, and anything else that went missing is reported. Null if the reply is empty.
     */
    fun rewritten(kind: Kind, original: String, reply: String): Result? {
        val front = original.substring(0, FrontMatter.parse(original).bodyStart)
        val before = original.substring(front.length)
        var after = unwrap(reply).trim()
        if (after.isEmpty()) return null

        // The title names the note, and links point to it; a rewrite does not rename the note.
        val title = before.lines().firstOrNull { it.isNotBlank() }?.takeIf { TITLE_LINE.matches(it.trim()) }
        if (title != null) {
            val name = title.trim().trimStart('#').trim()
            val lines = after.lines()
            // The model's own title line: a "# ..." line, or a heading of any level that repeats
            // the title. Anything before it is an introduction.
            val at = lines.indexOfFirst { line ->
                TITLE_LINE.matches(line.trim()) || (TITLE_ANY.matches(line.trim()) && line.trim().trimStart('#').trim().trim('*').equals(name, ignoreCase = true))
            }
            after = if (at >= 0) (listOf(title.trim()) + lines.drop(at + 1)).joinToString("\n") else title.trim() + "\n\n" + after
        }
        // One list marker, as in the notes the assistant writes.
        var fenced = false
        after = after.lines().joinToString("\n") { line ->
            if (FENCE_LINE.containsMatchIn(line)) fenced = !fenced
            if (fenced) line else BULLET.replace(line) { "${it.groupValues[1]}- " }
        }
        // A closing question such as "¿Quieres que cambie algo más?".
        val paragraphs = after.trimEnd().split(Regex("\n\\s*\n"))
        if (paragraphs.size > 1 && paragraphs.last().lines().size == 1 && paragraphs.last().trim().endsWith("?")) {
            after = paragraphs.dropLast(1).joinToString("\n\n")
        }

        val warnings = ArrayList<String>()
        val was = NoteMarkup.parse(before)
        var now = NoteMarkup.parse(after)
        val lostTags = was.tags.filter { it !in now.tags && was.tags.none { other -> other != it && other.startsWith("$it/") } }
        if (lostTags.isNotEmpty()) {
            after = after.trimEnd() + "\n\n" + lostTags.joinToString(" ") { "#$it" }
            now = NoteMarkup.parse(after)
        }
        val lostLinks = was.links.filter { link -> now.links.none { it.equals(link, ignoreCase = true) } }
        if (lostLinks.isNotEmpty() && kind == Kind.IMPROVE) {
            // Improving a note must not cut its connections: the links go back, under "Related".
            after = withRelated(after, lostLinks)
            now = NoteMarkup.parse(after)
            warnings += "The model dropped links; they were put back under Related: " + lostLinks.joinToString(", ") { "[[$it]]" }
        } else if (lostLinks.isNotEmpty()) {
            warnings += "Links removed: " + lostLinks.joinToString(", ") { "[[$it]]" }
        }
        // Code is never the model's to change: a block that is missing or altered is put back.
        val codeAfter = CODE_BLOCK.findAll(after).map { it.value.trim() }.toSet()
        val lostCode = CODE_BLOCK.findAll(before).map { it.value.trim() }.filter { it !in codeAfter }.toList()
        if (lostCode.isNotEmpty() && kind == Kind.EDIT) {
            // An instruction may ask for the code to change; the review shows what did.
            warnings += "A code block was changed or removed"
        } else if (lostCode.isNotEmpty()) {
            val lines = after.trimEnd().lines()
            var tail = lines.size
            while (tail > 0 && (lines[tail - 1].isBlank() || TAGS_ONLY.matches(lines[tail - 1]))) tail--
            val closing = lines.subList(tail, lines.size).filter { it.isNotBlank() }
            after = (lines.subList(0, tail) + lostCode.flatMap { listOf("", it) } + (if (closing.isEmpty()) emptyList() else listOf("") + closing)).joinToString("\n")
            warnings += "The model dropped or changed a code block; the original was put back at the end"
        }
        if (after.length < before.length * SHRINK) warnings += "The note is much shorter than before; something may be missing"
        if (kind != Kind.EDIT) {
            val added = now.links.filter { link -> was.links.none { it.equals(link, ignoreCase = true) } }
            if (added.isNotEmpty()) warnings += "Links added by the model: " + added.joinToString(", ") { "[[$it]]" }
        }
        return Result(front + after.trim() + "\n", warnings)
    }

    /** Removes a code fence the model put around the whole reply. */
    private fun unwrap(reply: String): String {
        val lines = reply.trim().lines()
        val wrapped = lines.size >= 3 && FENCE_LINE.containsMatchIn(lines.first()) && lines.last().trim().let { it == "```" || it == "~~~" } &&
            lines.subList(1, lines.size - 1).none { FENCE_LINE.containsMatchIn(it) }
        return if (wrapped) lines.subList(1, lines.size - 1).joinToString("\n") else reply.trim()
    }

    /**
     * The note with the tags of the model's [reply] added as a last line. Tags the note already
     * has are not repeated. Null if the reply holds no tag the note lacks.
     */
    fun tagged(original: String, reply: String): Result? {
        val line = reply.lines().firstOrNull { it.contains(':') && Regex("tags?|etiquetas?", RegexOption.IGNORE_CASE).containsMatchIn(it.substringBefore(':')) }
            ?: reply.lines().firstOrNull { it.isNotBlank() } ?: return null
        val have = NoteMarkup.parse(original).tags.toSet()
        val wanted = NoteMarkup.parse("x " + line.substringAfter(':').split(',', ';').joinToString(" ") {
            "#" + it.trim().trim('#', '*', '`', '.').lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^\\p{L}\\p{N}_/-]"), "")
        }).tags.filter { it !in have }.take(MAX_NEW_TAGS)
        if (wanted.isEmpty()) return null
        return Result(original.trimEnd() + "\n\n" + wanted.joinToString(" ") { "#$it" } + "\n", emptyList())
    }

    /**
     * Links the notes this one names without linking them. [others] pairs the title of another
     * note with every name it goes by. The language model is not used: a mention becomes a link
     * only where the note's own words name the other note.
     *
     * [related] are titles of notes that retrieval found close in meaning (the same hybrid
     * search the assistant answers with). They are not named in the text, so they are listed
     * under a "Related" heading, and the proposal says they were found by meaning. Null if there
     * is nothing to link.
     */
    fun linked(original: String, others: List<Pair<String, List<String>>>, related: List<String> = emptyList()): Result? {
        var text = original
        val added = ArrayList<String>()
        val already = NoteMarkup.parse(original).links.map { it.lowercase() }.toSet()
        // Longer names first, so "Protocolo TCP" is linked before the "TCP" inside it.
        for ((title, names) in others.sortedByDescending { pair -> pair.second.maxOfOrNull { it.length } ?: 0 }) {
            val usable = names.filter { it.trim().length >= MIN_NAME }
            if (usable.isEmpty() || title.lowercase() in already || names.any { it.lowercase() in already }) continue
            val next = NoteSections.linkMention(text, title, usable) ?: continue
            text = next
            added += title
        }
        val linkedNow = NoteMarkup.parse(text).links.map { it.lowercase() }.toSet()
        val extra = related.filter { it.isNotBlank() && it.lowercase() !in linkedNow }.distinctBy { it.lowercase() }.take(MAX_RELATED)
        if (added.isEmpty() && extra.isEmpty()) return null
        if (extra.isEmpty()) return Result(text, emptyList())
        return Result(
            withRelated(text, extra),
            listOf("Under \"Related\": notes found by meaning, not named in the text. Check that they belong."),
        )
    }

    private val RELATED_HEADING = Regex("^##\\s+(Relacionado|Related)\\s*$", RegexOption.IGNORE_CASE)
    private val TAGS_ONLY = Regex("^(\\s*#[\\p{L}_][\\p{L}\\p{N}_/-]*)+\\s*$")

    /** Adds [titles] as links under the note's "Related" heading, creating it above the closing tag line. */
    private fun withRelated(text: String, titles: List<String>): String {
        val lines = text.trimEnd().lines().toMutableList()
        val bullets = titles.map { "- [[$it]]" }
        val heading = lines.indexOfFirst { RELATED_HEADING.matches(it.trim()) }
        if (heading >= 0) {
            // After the last line of the existing section.
            var end = heading + 1
            while (end < lines.size && !lines[end].trimStart().startsWith("#")) end++
            while (end > heading + 1 && lines[end - 1].isBlank()) end--
            lines.addAll(end, bullets)
            return lines.joinToString("\n") + "\n"
        }
        // The tags that close a note stay last.
        var tail = lines.size
        while (tail > 0 && (lines[tail - 1].isBlank() || TAGS_ONLY.matches(lines[tail - 1]))) tail--
        val closing = lines.subList(tail, lines.size).filter { it.isNotBlank() }
        val body = lines.subList(0, tail)
        val title = if (Assistant.language(text.take(600)) == "English") "Related" else "Relacionado"
        return (body + listOf("", "## $title") + bullets + (if (closing.isEmpty()) emptyList() else listOf("") + closing)).joinToString("\n") + "\n"
    }

    // ---- Showing a change ----

    enum class Change { SAME, ADDED, REMOVED }

    /** The lines of [before] and [after] in order, each marked as kept, added or removed. */
    fun diff(before: String, after: String): List<Pair<Change, String>> {
        val a = before.trimEnd().lines()
        val b = after.trimEnd().lines()
        // Longest common subsequence of lines; notes are short, so the table is small.
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
        val out = ArrayList<Pair<Change, String>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out += Change.SAME to a[i]; i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> { out += Change.REMOVED to a[i]; i++ }
                else -> { out += Change.ADDED to b[j]; j++ }
            }
        }
        while (i < a.size) out += Change.REMOVED to a[i++]
        while (j < b.size) out += Change.ADDED to b[j++]
        return out
    }

    private const val MIN_NAME = 3
    private const val MAX_NEW_TAGS = 4
    private const val MAX_RELATED = 3
    private const val SHRINK = 0.6
    private const val ALIGN_CELLS = 4_000_000L
}
