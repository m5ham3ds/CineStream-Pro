package com.example.domain.search

import com.example.domain.models.Movie
import com.example.domain.models.Series

/**
 * Authoritative Centralized Media Title Matcher for CineStream.
 *
 * Implements Phase 11 & Phase 12 Requirements:
 * 1. Robust normalization of Arabic diacritics (Tashkeel), common prefixes/tags, punctuation, and casing.
 * 2. Cross-language matching between Arabic and Latin/English titles (matching against title and originalTitle).
 * 3. Prevention of false-positive containment matches (e.g., short queries or unrelated titles matching substrings).
 * 4. Year-compatibility verification when release years are present on both entities.
 */
object MediaTitleMatcher {

    /**
     * Authoritative normalization for title matching across scrapers and TMDB.
     * Removes diacritics, common prefixes, punctuation, extra spaces, and lowercases.
     */
    fun normalize(title: String?): String {
        if (title.isNullOrBlank()) return ""
        return title
            .replace(Regex("""[\u064B-\u065F\u0670]"""), "") // Arabic Tashkeel
            // Convert Eastern Arabic numerals to standard digits
            .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3').replace('٤', '4')
            .replace('٥', '5').replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')
            // Normalize Arabic letter variants
            .replace(Regex("[إأآٱ]"), "ا")
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace(Regex("""(?i)\b(مدبلج|مدبلجة|دبلجة|مترجم|مترجمة|ترجمة|فيلم|فلم|مسلسل|سلسلة|انمي|أنمي|season|الموسم|part|الجزء|بالمصري|بالمصرية|المصري|مصري|بالفصحى|فصحى|بالعربي|عربي|مشاهدة|تحميل|اونلاين|اون لاين|كامل|كاملة|حلقة|الحلقة|للعربية|للعربيه|كرتون|انيميشن|أنيميشن|web-dl|bluray|hdrip|1080p|720p|480p|4k)\b"""), " ")
            .replace(Regex("""(?i)(مدبلج|مدبلجة|دبلجة|مترجم|مترجمة|فيلم|فلم|مسلسل|سلسلة|انمي|أنمي|season|الموسم|part|الجزء|بالمصري|بالمصرية|المصري|مصري|بالفصحى|فصحى|بالعربي|عربي|مشاهدة|تحميل|اونلاين|اون لاين|كامل|كاملة|حلقة|الحلقة|للعربية|للعربيه|كرتون|انيميشن|أنيميشن)"""), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val normalizedDigits = text
            .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3').replace('٤', '4')
            .replace('٥', '5').replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')
        return Regex("""\b(19\d\d|20\d\d)\b""").find(normalizedDigits)?.value?.toIntOrNull()
    }

    fun cleanYearTokens(normalized: String): String {
        return normalized.replace(Regex("""\b(19\d\d|20\d\d)\b"""), "").trim().replace(Regex("\\s+"), " ")
    }

    fun extractNumberTokens(cleanTitle: String): Set<String> {
        val normalized = cleanTitle.lowercase()
            .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3').replace('٤', '4')
            .replace('٥', '5').replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')
        return Regex("""\b(\d+|ii|iii|iv|v|vi|vii|viii|ix|x)\b""").findAll(normalized)
            .map { it.value }
            .toSet()
    }

    /**
     * Checks if a scraper/managed item matches a TMDB movie.
     * Prevents false positives by strictly verifying year compatibility, sequence numbers, and title tokens.
     */
    fun matchesMovie(
        managedTitle: String,
        movie: Movie,
        managedYear: String? = null
    ): Boolean {
        val mNorm = normalize(managedTitle)
        val tNorm = normalize(movie.title)
        val oNorm = normalize(movie.originalTitle)

        if (mNorm.isBlank()) return false

        val titleYear = extractYear(managedTitle)
        val mYear = titleYear ?: managedYear?.toIntOrNull()
        val cYear = if (movie.year > 0) movie.year else extractYear(movie.releaseDate)

        // Year compatibility gate: if both have explicit 4-digit years, difference cannot exceed 1
        val hasBothYears = mYear != null && cYear != null && cYear > 0
        val yearMatches = !hasBothYears || kotlin.math.abs(mYear!! - cYear!!) <= 1
        if (!yearMatches) return false

        val mNormClean = cleanYearTokens(mNorm)
        val tNormClean = cleanYearTokens(tNorm)
        val oNormClean = cleanYearTokens(oNorm)

        val mNums = extractNumberTokens(mNormClean)
        val tNums = extractNumberTokens(tNormClean)
        val oNums = extractNumberTokens(oNormClean)

        // 1. Exact match against translated title (if sequence numbers match)
        if (mNums == tNums) {
            if (tNorm.isNotBlank() && mNorm == tNorm) return true
            if (tNormClean.isNotBlank() && mNormClean == tNormClean) return true
        }

        // 2. Exact match against original title (if sequence numbers match)
        if (mNums == oNums) {
            if (oNorm.isNotBlank() && mNorm == oNorm) return true
            if (oNormClean.isNotBlank() && mNormClean == oNormClean) return true
        }

        // 3. Token-based containment with strict word boundaries and sequence number check
        if (mNums == tNums && tNormClean.length >= 3 && mNormClean.length >= 3) {
            val mWords = mNormClean.split(" ").filter { it.length >= 2 }
            val tWords = tNormClean.split(" ").filter { it.length >= 2 }
            if (isSafeTokenMatch(mWords, tWords, hasBothYears)) {
                return true
            }
        }

        if (mNums == oNums && oNormClean.length >= 3 && mNormClean.length >= 3) {
            val mWords = mNormClean.split(" ").filter { it.length >= 2 }
            val oWords = oNormClean.split(" ").filter { it.length >= 2 }
            if (isSafeTokenMatch(mWords, oWords, hasBothYears)) {
                return true
            }
        }

        // 4. Cross-language Arabic-Latin transliteration & phonetic fallback matching
        if (mNums == tNums || mNums == oNums) {
            val hasArabicInManaged = mNormClean.any { it in '\u0600'..'\u06FF' }
            if (hasArabicInManaged) {
                val mTrans = transliterateArabicToLatin(mNormClean)
                val mPhonetic = phoneticNormalize(mTrans)

                if (tNormClean.isNotBlank()) {
                    val tPhonetic = phoneticNormalize(tNormClean)
                    if (mTrans == tNormClean || mPhonetic == tPhonetic) return true
                    val maxLen = maxOf(mPhonetic.length, tPhonetic.length)
                    if (maxLen >= 5 && hasBothYears && levenshteinDistance(mPhonetic, tPhonetic) <= 1) return true
                    if (maxLen >= 6 && levenshteinDistance(mPhonetic, tPhonetic) <= 1) return true
                }

                if (oNormClean.isNotBlank()) {
                    val oPhonetic = phoneticNormalize(oNormClean)
                    if (mTrans == oNormClean || mPhonetic == oPhonetic) return true
                    val maxLen = maxOf(mPhonetic.length, oPhonetic.length)
                    if (maxLen >= 5 && hasBothYears && levenshteinDistance(mPhonetic, oPhonetic) <= 1) return true
                    if (maxLen >= 6 && levenshteinDistance(mPhonetic, oPhonetic) <= 1) return true
                }
            }
        }

        return false
    }

    /**
     * Checks if a scraper/managed item matches a TMDB series.
     */
    fun matchesSeries(
        managedTitle: String,
        series: Series,
        managedYear: String? = null
    ): Boolean {
        val mNorm = normalize(managedTitle)
        val tNorm = normalize(series.title)
        val oNorm = normalize(series.originalTitle)

        if (mNorm.isBlank()) return false

        val titleYear = extractYear(managedTitle)
        val mYear = titleYear ?: managedYear?.toIntOrNull()
        val cYear = if (series.year > 0) series.year else extractYear(series.firstAirDate)

        val hasBothYears = mYear != null && cYear != null && cYear > 0
        val yearMatches = !hasBothYears || kotlin.math.abs(mYear!! - cYear!!) <= 1
        if (!yearMatches) return false

        val mNormClean = cleanYearTokens(mNorm)
        val tNormClean = cleanYearTokens(tNorm)
        val oNormClean = cleanYearTokens(oNorm)

        val mNums = extractNumberTokens(mNormClean)
        val tNums = extractNumberTokens(tNormClean)
        val oNums = extractNumberTokens(oNormClean)

        if (mNums == tNums) {
            if (tNorm.isNotBlank() && mNorm == tNorm) return true
            if (tNormClean.isNotBlank() && mNormClean == tNormClean) return true
        }

        if (mNums == oNums) {
            if (oNorm.isNotBlank() && mNorm == oNorm) return true
            if (oNormClean.isNotBlank() && mNormClean == oNormClean) return true
        }

        if (mNums == tNums && tNormClean.length >= 3 && mNormClean.length >= 3) {
            val mWords = mNormClean.split(" ").filter { it.length >= 2 }
            val tWords = tNormClean.split(" ").filter { it.length >= 2 }
            if (isSafeTokenMatch(mWords, tWords, hasBothYears)) {
                return true
            }
        }

        if (mNums == oNums && oNormClean.length >= 3 && mNormClean.length >= 3) {
            val mWords = mNormClean.split(" ").filter { it.length >= 2 }
            val oWords = oNormClean.split(" ").filter { it.length >= 2 }
            if (isSafeTokenMatch(mWords, oWords, hasBothYears)) {
                return true
            }
        }

        // 4. Cross-language Arabic-Latin transliteration & phonetic fallback matching
        if (mNums == tNums || mNums == oNums) {
            val hasArabicInManaged = mNormClean.any { it in '\u0600'..'\u06FF' }
            if (hasArabicInManaged) {
                val mTrans = transliterateArabicToLatin(mNormClean)
                val mPhonetic = phoneticNormalize(mTrans)

                if (tNormClean.isNotBlank()) {
                    val tPhonetic = phoneticNormalize(tNormClean)
                    if (mTrans == tNormClean || mPhonetic == tPhonetic) return true
                    val maxLen = maxOf(mPhonetic.length, tPhonetic.length)
                    if (maxLen >= 5 && hasBothYears && levenshteinDistance(mPhonetic, tPhonetic) <= 1) return true
                    if (maxLen >= 6 && levenshteinDistance(mPhonetic, tPhonetic) <= 1) return true
                }

                if (oNormClean.isNotBlank()) {
                    val oPhonetic = phoneticNormalize(oNormClean)
                    if (mTrans == oNormClean || mPhonetic == oPhonetic) return true
                    val maxLen = maxOf(mPhonetic.length, oPhonetic.length)
                    if (maxLen >= 5 && hasBothYears && levenshteinDistance(mPhonetic, oPhonetic) <= 1) return true
                    if (maxLen >= 6 && levenshteinDistance(mPhonetic, oPhonetic) <= 1) return true
                }
            }
        }

        return false
    }

    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "and", "in", "to", "for", "on", "with", "at", "by", "from",
        "ال", "و", "في", "من", "عن", "مع", "علي", "على", "الى", "إلى", "لا"
    )

    private fun isSafeTokenMatch(
        mWords: List<String>,
        tWords: List<String>,
        hasExplicitMatchingYear: Boolean
    ): Boolean {
        if (mWords.isEmpty() || tWords.isEmpty()) return false
        val mSet = mWords.toSet()
        val tSet = tWords.toSet()
        if (mSet == tSet) return true

        val diff = (mSet - tSet) + (tSet - mSet)
        // If differing tokens are purely recognized stop-words, it is safe
        if (diff.all { it in STOP_WORDS }) return true

        // If there are significant distinguishing words, require explicit matching year and strong mutual overlap
        if (hasExplicitMatchingYear) {
            val intersectionSize = (mSet intersect tSet).size
            val minSize = minOf(mSet.size, tSet.size)
            val maxSize = maxOf(mSet.size, tSet.size)
            if (minSize >= 2 && intersectionSize.toDouble() / minSize >= 0.75 && intersectionSize.toDouble() / maxSize >= 0.6) {
                return true
            }
        }

        return false
    }

    /**
     * Transliterates Arabic text into Latin phonetic characters for cross-script title matching.
     */
    fun transliterateArabicToLatin(arabicText: String): String {
        val sb = StringBuilder()
        for (char in arabicText) {
            when (char) {
                'ا', 'أ', 'إ', 'آ', 'ٱ', 'ء', 'ئ', 'ؤ' -> sb.append('a')
                'ب' -> sb.append('b')
                'ت', 'ة' -> sb.append('t')
                'ث' -> sb.append("th")
                'ج' -> sb.append('j')
                'ح' -> sb.append('h')
                'خ' -> sb.append("kh")
                'د' -> sb.append('d')
                'ذ' -> sb.append("th")
                'ر' -> sb.append('r')
                'ز' -> sb.append('z')
                'س' -> sb.append('s')
                'ش' -> sb.append("sh")
                'ص' -> sb.append('s')
                'ض' -> sb.append('d')
                'ط' -> sb.append('t')
                'ظ' -> sb.append('z')
                'ع' -> sb.append('a')
                'غ' -> sb.append("gh")
                'ف' -> sb.append('f')
                'ق' -> sb.append('k')
                'ك' -> sb.append('k')
                'ل' -> sb.append('l')
                'م' -> sb.append('m')
                'ن' -> sb.append('n')
                'ه' -> sb.append('h')
                'و' -> sb.append('o')
                'ي', 'ى' -> sb.append('y')
                ' ' -> sb.append(' ')
                else -> if (char in '0'..'9' || char in 'a'..'z') sb.append(char)
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Normalizes Latin text phonetically to bridge spelling variances between Arabic transliterations and English names.
     */
    fun phoneticNormalize(latinText: String): String {
        return latinText.lowercase()
            .replace(Regex("""(?i)tion\b"""), "shn")
            .replace(Regex("""(?i)sion\b"""), "shn")
            .replace(Regex("""(?i)c(?=[eiy])"""), "s")
            .replace("ph", "f")
            .replace("gh", "g")
            .replace("kh", "k")
            .replace("sh", "s")
            .replace("th", "t")
            .replace('c', 'k')
            .replace('q', 'k')
            .replace('p', 'b')
            .replace('v', 'f')
            .replace('w', 'o')
            .replace('u', 'o')
            .replace(Regex("""^[ie]"""), "a")
            .replace('i', 'y')
            .replace('e', 'a')
            .replace(Regex("""(.)\1+"""), "$1")
            .trim()
    }

    /**
     * Standard Levenshtein distance for fuzzy title matching.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
