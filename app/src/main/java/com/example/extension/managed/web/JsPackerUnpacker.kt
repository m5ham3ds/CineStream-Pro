package com.example.extension.managed.web

import java.util.regex.Pattern

/**
 * Robust, safe, non-evaluating unpacker for Dean Edwards p.a.c.k.e.r obfuscated JavaScript.
 * Commonly used by web video embeds (e.g. Playerjs, JWPlayer embeds).
 * Unpacks the payload using pure Kotlin string manipulation without executing any code.
 */
object JsPackerUnpacker {

    private val PACKER_REGEX = Regex(
        """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)\s*\{[\s\S]*?\}\s*\(\s*(['"])([\s\S]*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])([\s\S]*?)\5\.split\(\s*['"]\|['"]\s*\)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val HEADER_PATTERN = Pattern.compile(
        """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)[\s\S]*?\}\s*\(""",
        Pattern.CASE_INSENSITIVE
    )

    private const val BASE62_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /**
     * Checks if the given text contains at least one packed script block.
     */
    fun isPacked(text: String): Boolean {
        return text.contains("eval(function(p,a,c,k,e,", ignoreCase = true) ||
               text.contains("eval(function(p,a,c,k,e,r", ignoreCase = true) ||
               text.contains("eval(function(p,a,c,k,e,d", ignoreCase = true)
    }

    /**
     * Encodes a base-10 number [c] into radix [radix] string.
     * Supports radix <= 36 (standard alphanumeric) and up to 62 (case-sensitive base62).
     */
    fun encodeRadix(c: Int, radix: Int): String {
        if (radix <= 36) {
            return c.toString(radix)
        }
        if (c == 0) return "0"
        var n = c
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(BASE62_ALPHABET[n % radix])
            n /= radix
        }
        return sb.reverse().toString()
    }

    /**
     * Deterministically parses and unpacks Dean Edwards packed JavaScript blocks.
     * Avoids regex backtracking and properly handles single/double quote escaping (\', \").
     */
    fun unpackDeterministic(text: String): List<String> {
        val results = mutableListOf<String>()
        val matcher = HEADER_PATTERN.matcher(text)
        while (matcher.find()) {
            var idx = matcher.end()
            while (idx < text.length && text[idx].isWhitespace()) idx++
            if (idx >= text.length) break

            val pQuote = text[idx]
            if (pQuote != '\'' && pQuote != '"') continue
            idx++ // consume opening quote

            val payloadSb = StringBuilder()
            var escaped = false
            while (idx < text.length) {
                val ch = text[idx]
                if (escaped) {
                    payloadSb.append(ch)
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                    payloadSb.append(ch)
                } else if (ch == pQuote) {
                    idx++ // consumed closing quote
                    break
                } else {
                    payloadSb.append(ch)
                }
                idx++
            }

            while (idx < text.length && text[idx].isWhitespace()) idx++
            if (idx >= text.length || text[idx] != ',') continue
            idx++ // skip comma

            // Read radix a
            while (idx < text.length && text[idx].isWhitespace()) idx++
            val radixSb = StringBuilder()
            while (idx < text.length && text[idx].isDigit()) {
                radixSb.append(text[idx])
                idx++
            }
            val radix = radixSb.toString().toIntOrNull() ?: continue

            while (idx < text.length && text[idx].isWhitespace()) idx++
            if (idx >= text.length || text[idx] != ',') continue
            idx++ // skip comma

            // Read count c
            while (idx < text.length && text[idx].isWhitespace()) idx++
            val countSb = StringBuilder()
            while (idx < text.length && text[idx].isDigit()) {
                countSb.append(text[idx])
                idx++
            }
            val count = countSb.toString().toIntOrNull() ?: continue

            while (idx < text.length && text[idx].isWhitespace()) idx++
            if (idx >= text.length || text[idx] != ',') continue
            idx++ // skip comma

            // Read words string
            while (idx < text.length && text[idx].isWhitespace()) idx++
            if (idx >= text.length) continue
            val wordsQuote = text[idx]
            if (wordsQuote != '\'' && wordsQuote != '"') continue
            idx++ // consume opening words quote

            val wordsSb = StringBuilder()
            escaped = false
            while (idx < text.length) {
                val ch = text[idx]
                if (escaped) {
                    wordsSb.append(ch)
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                    wordsSb.append(ch)
                } else if (ch == wordsQuote) {
                    idx++ // consumed closing words quote
                    break
                } else {
                    wordsSb.append(ch)
                }
                idx++
            }

            val words = wordsSb.toString().split('|')
            val unpacked = unpackPayload(payloadSb.toString(), radix, count, words)
            if (unpacked.isNotBlank()) {
                results.add(unpacked)
            }
        }
        return results
    }

    /**
     * Unpacks a single packed JavaScript script block if present.
     */
    fun unpack(packedScript: String): String? {
        val deterministic = unpackDeterministic(packedScript).firstOrNull()
        if (deterministic != null) {
            return deterministic
        }

        val match = PACKER_REGEX.find(packedScript) ?: return null
        val payload = match.groupValues[2]
        val radix = match.groupValues[3].toIntOrNull() ?: return null
        val count = match.groupValues[4].toIntOrNull() ?: return null
        val wordsString = match.groupValues[6]
        val words = wordsString.split("|")

        return unpackPayload(payload, radix, count, words)
    }

    /**
     * Unpacks all packed script blocks in [htmlOrJsContent] and returns the unpacked blocks.
     */
    fun unpackAll(htmlOrJsContent: String): List<String> {
        val deterministic = unpackDeterministic(htmlOrJsContent)
        if (deterministic.isNotEmpty()) {
            return deterministic
        }

        val results = mutableListOf<String>()
        val matches = PACKER_REGEX.findAll(htmlOrJsContent)
        for (m in matches) {
            val payload = m.groupValues[2]
            val radix = m.groupValues[3].toIntOrNull() ?: continue
            val count = m.groupValues[4].toIntOrNull() ?: continue
            val wordsString = m.groupValues[6]
            val words = wordsString.split("|")

            val unpacked = unpackPayload(payload, radix, count, words)
            if (unpacked.isNotBlank()) {
                results.add(unpacked)
            }
        }
        return results
    }

    private fun unpackPayload(payload: String, radix: Int, count: Int, words: List<String>): String {
        val totalCount = maxOf(count, words.size)
        val dict = HashMap<String, String>(totalCount)
        for (c in 0 until totalCount) {
            val word = if (c < words.size) words[c] else ""
            if (word.isNotEmpty()) {
                val token = encodeRadix(c, radix)
                dict[token] = word
            }
        }

        // Match all alphanumeric word tokens \b[0-9a-zA-Z]+\b in the payload and substitute from dictionary
        val wordPattern = Pattern.compile("\\b([0-9a-zA-Z]+)\\b")
        val matcher = wordPattern.matcher(payload)
        val sb = StringBuffer()
        while (matcher.find()) {
            val token = matcher.group(1)
            val replacement = dict[token]
            if (replacement != null) {
                // Matcher.quoteReplacement escapes backslashes and dollar signs
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement))
            } else {
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(token))
            }
        }
        matcher.appendTail(sb)
        return sb.toString()
    }
}
