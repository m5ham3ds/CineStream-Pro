package com.example.domain.search

/**
 * Result of analyzing a search query for variant triggers according to Phase 10B & Phase 19.5.
 */
data class SearchTriggerResult(
    val isArabicDubbedIntent: Boolean,
    val cleanQuery: String,
    val rawQuery: String,
    val hasValidMediaTitle: Boolean,
    val normalizedQuery: String = SearchTriggerParser.normalizeWhitespace(rawQuery)
) {
    val effectiveQuery: String
        get() = if (isArabicDubbedIntent && cleanQuery.isNotBlank()) {
            cleanQuery
        } else if (isArabicDubbedIntent) {
            "مدبلج"
        } else {
            cleanQuery.ifBlank { rawQuery.trim() }
        }
}

/**
 * Authoritative Centralized Search Trigger Parser for CineStream.
 *
 * Implements Phase 10B, Phase 11 & Phase 19.5 Requirements:
 * 1. Activates Dubbed intent when query contains any of the canonical trigger tokens:
 *    - 'مدبلج'
 *    - 'مدبلجة'
 *    - 'مدبلجين' / 'مدبلجون'
 *    - 'دبلجة'
 *    - 'dubbed' (case-insensitive: 'dubbed', 'Dubbed', 'DUBBED')
 * 2. Normalizes spaces, Arabic diacritics (Tashkeel), and surrounding punctuation.
 * 3. Enforces standalone word boundaries so sub-words do NOT accidentally trigger dubbed intent.
 * 4. Extracts the clean media title by stripping ONLY the trigger modifier tokens and surrounding punctuation.
 * 5. Preserves legitimate title words like 'الملك', 'الأنمي', 'الأبطال', numbers, and part names.
 * 6. Accurately determines hasValidMediaTitle (false when user queries trigger word alone without title).
 * 7. Normalizes query whitespace for robust state comparison and cancellation prevention.
 */
object SearchTriggerParser {

    /**
     * Normalizes multiple consecutive spaces, leading/trailing whitespace into a canonical single-space form.
     */
    fun normalizeWhitespace(query: String): String {
        return query.trim().replace(Regex("\\s+"), " ")
    }

    // Regex for Arabic diacritics (Tashkeel: Fatha, Damma, Kasra, Sukun, Tanween, Shadda, Dagger Alif)
    private val TASHKEEL_REGEX = Regex("[\u064B-\u065F\u0670]")

    // Punctuation characters to normalize around tokens
    private const val PUNCTUATION_CHARS = ":،,؛;.\\-–—_!?؟\"'()\\[\\]{}~/\\\\"
    private val PUNCT_REGEX = Regex("[$PUNCTUATION_CHARS]+")

    // Standalone word regex for 'دبلجة', 'مدبلجة', 'مدبلجين', 'مدبلج', 'dubbed'
    // Matches canonical trigger words bounded by start/end, whitespace, or punctuation
    private val DUBBED_TRIGGER_REGEX = Regex(
        "(?i)(?:^|[\\s$PUNCTUATION_CHARS])(دبلجة|مدبلجة|مدبلجين|مدبلجون|مدبلج|dubbed)(?:[\\s$PUNCTUATION_CHARS]|$)"
    )

    // Regex matching any of the canonical trigger words with optional embedded Tashkeel diacritics
    private val WORD_REGEX_WITH_OPTIONAL_TASHKEEL = Regex(
        "(?i)(?:^|[\\s$PUNCTUATION_CHARS])[\u064B-\u065F\u0670]*(?:د[\u064B-\u065F\u0670]*ب[\u064B-\u065F\u0670]*ل[\u064B-\u065F\u0670]*ج[\u064B-\u065F\u0670]*ة|م[\u064B-\u065F\u0670]*د[\u064B-\u065F\u0670]*ب[\u064B-\u065F\u0670]*ل[\u064B-\u065F\u0670]*ج[\u064B-\u065F\u0670]*(?:ة|ين|ون)?|dubbed)[\u064B-\u065F\u0670]*(?:[\\s$PUNCTUATION_CHARS]|$)"
    )

    /**
     * Parses the raw user query.
     * Activates Dubbed intent if query contains any of 'مدبلج', 'مدبلجة', 'مدبلجين', 'دبلجة', or 'dubbed'.
     * Normalizes spaces, diacritics, and punctuation.
     */
    fun parse(rawQuery: String): SearchTriggerResult {
        val trimmed = rawQuery.trim()
        if (trimmed.isBlank()) {
            return SearchTriggerResult(
                isArabicDubbedIntent = false,
                cleanQuery = "",
                rawQuery = rawQuery,
                hasValidMediaTitle = false
            )
        }

        // 1. Normalize diacritics for detection
        val normalizedDiacritics = trimmed.replace(TASHKEEL_REGEX, "")

        // 2. Check for standalone trigger words
        val isDubbed = DUBBED_TRIGGER_REGEX.containsMatchIn(normalizedDiacritics)

        if (!isDubbed) {
            return SearchTriggerResult(
                isArabicDubbedIntent = false,
                cleanQuery = trimmed,
                rawQuery = rawQuery,
                hasValidMediaTitle = trimmed.isNotBlank()
            )
        }

        // 3. Strip trigger word (with any diacritics) and surrounding punctuation to extract clean work title
        var cleaned = trimmed.replace(WORD_REGEX_WITH_OPTIONAL_TASHKEEL, " ")

        // In case multiple trigger tokens or boundary artifacts were present
        while (DUBBED_TRIGGER_REGEX.containsMatchIn(cleaned.replace(TASHKEEL_REGEX, ""))) {
            cleaned = cleaned.replace(WORD_REGEX_WITH_OPTIONAL_TASHKEEL, " ")
        }

        // Clean leading/trailing punctuation and extra spaces
        cleaned = cleaned.trim().trim { it.toString().matches(PUNCT_REGEX) || it.isWhitespace() }
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()

        val hasValidTitle = cleaned.isNotBlank() && !cleaned.matches(PUNCT_REGEX)

        return SearchTriggerResult(
            isArabicDubbedIntent = true,
            cleanQuery = cleaned,
            rawQuery = rawQuery,
            hasValidMediaTitle = hasValidTitle
        )
    }
}
