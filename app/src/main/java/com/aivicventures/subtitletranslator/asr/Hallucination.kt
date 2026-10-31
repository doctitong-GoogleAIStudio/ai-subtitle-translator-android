package com.aivicventures.subtitletranslator.asr

import java.text.Normalizer

/**
 * Known Whisper hallucinations (port of the Windows app's
 * app/speech_recognition/hallucination.py).
 *
 * Whisper was trained on subtitled video, so during silence or music it tends to "hear"
 * credit lines and sign-offs. These phrases are dropped unless they are part of longer
 * speech. With the translate task most come out in English, but the other languages are
 * kept because Whisper sometimes leaves a line untranslated.
 */
object Hallucination {
    private val PHRASES = listOf(
        // English
        "thank you for watching", "thanks for watching", "please subscribe", "subscribe to my channel",
        "like and subscribe", "see you next time", "see you in the next video", "subtitles by",
        "transcribed by", "translated by", "captions by", "amara.org",
        // Chinese
        "字幕由", "字幕提供", "請不吝點贊", "请不吝点赞", "訂閱", "订阅", "感謝觀看", "感谢观看", "謝謝觀看", "谢谢观看",
        "明镜与点点栏目", "優優獨播劇場", "优优独播剧场", "中文字幕", "字幕組", "字幕组",
        // Japanese
        "ご視聴ありがとうございました", "チャンネル登録", "字幕作成",
        // Korean
        "시청해주셔서 감사합니다", "구독과 좋아요", "자막 제공", "한글자막", "다음 영상에서 만나요",
        // Spanish / Portuguese / French / German / Russian / others
        "gracias por ver", "subtítulos realizados por", "suscríbete", "obrigado por assistir",
        "legendas pela comunidade", "sous-titrage", "sous-titres réalisés", "merci d'avoir regardé",
        "untertitel im auftrag", "untertitel der amara", "vielen dank fürs zuschauen",
        "редактор субтитров", "субтитры сделал", "субтитры создавал", "продолжение следует",
        "altyazı", "terima kasih telah menonton", "cảm ơn các bạn đã theo dõi", "hãy subscribe",
    )

    // Subtitle credits: never real dialogue, so a sentence containing one is dropped whatever
    // else it says.
    private val CREDITS = listOf(
        "amara.org", "subtitles by", "subtitles created by", "captions by", "transcribed by",
        "subtítulos realizados por", "sous-titres réalisés", "sous-titrage", "untertitel im auftrag",
        "untertitel der amara", "legendas pela comunidade", "редактор субтитров", "субтитры сделал",
        "субтитры создавал", "字幕由", "字幕提供", "字幕作成", "자막 제공", "明镜与点点栏目",
        "優優獨播劇場", "优优独播剧场",
    )

    private val PUNCT = Regex("""[\s.,!?。！？、…\-—~]+""")
    private val REPEAT = Regex("""(.{2,}?)\1{7,}""")
    // One letter three times in a row. \p{L} instead of [^\W\d_]: Java needs (?U) for a
    // Unicode \w, and Android's ICU regex rejects (?U).
    private val DRAWN_OUT = Regex("""(\p{L})\1\1""")
    private val SENTENCE_END = Regex("""(?<=[.!?])\s+|(?<=[。！？])\s*""") // not inside "amara.org"
    private val normPhrases = PHRASES.map { norm(it) }
    private val normCredits = CREDITS.map { norm(it) }

    fun norm(text: String): String =
        PUNCT.replace(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(), " ").trim()

    fun isHallucination(text: String, durationSec: Double): Boolean {
        val n = norm(text)
        if (n.isEmpty()) return true
        for (p in normPhrases) {
            if (p.isNotEmpty() && p in n && n.length <= p.length + 25) return true
        }
        if (REPEAT.containsMatchIn(n.replace(" ", ""))) return true // "ha ha ha ha ha ha..." loops
        // Free-standing combining marks ("ۛ ۜ ۚ ۛ") never occur in real text.
        val markTokens = n.split(" ").count { tok -> tok.isNotEmpty() && tok.all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() } }
        if (markTokens >= 3) return true
        // A drawn-out scream or laugh ("으아아아아아", "ahhh", "哈哈哈") is not dialogue.
        val letters = n.filter { it.isLetter() }
        if (DRAWN_OUT.containsMatchIn(n) && letters.toSet().size <= 2) return true
        // Physically implausible speaking rate (e.g. 60 characters in 0.3 s).
        if (durationSec > 0 && n.length / durationSec > 60) return true
        return false
    }

    /** [text] without sentences that are sign-offs or credits (checked per sentence, because
     *  Whisper often appends "Thanks for watching" to a real line under background noise). */
    fun stripHallucinations(text: String): String {
        val parts = SENTENCE_END.split(text).filter { it.isNotBlank() }
        val kept = parts.filter { !hasCredit(it) && !isHallucination(it, 0.0) }
        return if (kept.size != parts.size) kept.joinToString(" ") else text
    }

    private fun hasCredit(text: String): Boolean {
        val n = norm(text)
        return normCredits.any { it in n }
    }

    /** Sound descriptions Whisper writes instead of dialogue: "[Music]", "(upbeat music)", "♪". */
    fun isSoundTag(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.all { it == '♪' || it == '♫' || it.isWhitespace() || it == '*' || it == '-' }) return true
        val bracketed = (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")")) ||
            (t.startsWith("（") && t.endsWith("）")) || (t.startsWith("【") && t.endsWith("】"))
        return bracketed || (t.startsWith("♪") && t.endsWith("♪"))
    }
}
