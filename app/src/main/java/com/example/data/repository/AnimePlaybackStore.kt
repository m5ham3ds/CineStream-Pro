package com.example.data.repository

import android.content.Context

/**
 * Stores and identifies Anime items in history so that Anime and Series
 * can be cleanly separated in Continue Watching sections across screens.
 */
object AnimePlaybackStore {
    private const val PREFS_NAME = "anime_history_store"
    private const val KEY_ANIME_IDS = "anime_ids"

    private val ANIME_KEYWORDS = listOf(
        // English / Romaji
        "anime", "one piece", "attack on titan", "shingeki", "naruto", "bleach", "demon slayer",
        "kimetsu", "yaiba", "jujutsu", "kaisen", "my hero academia", "boku no hero", "hero academia",
        "hunter x hunter", "hunterxhunter", "dragon ball", "death note", "tokyo ghoul", "tokyo revengers",
        "solo leveling", "haikyuu", "chainsaw man", "boruto", "detective conan", "conan", "black clover",
        "gintama", "fairy tail", "sword art online", "sword art", "one punch man", "one punch",
        "overlord", "frieren", "sousou no frieren", "vinland saga", "vinland", "blue lock", "dandadan",
        "kaiju no. 8", "kaiju no 8", "dr. stone", "dr stone", "rezero", "re:zero",
        "that time i got reincarnated as a slime", "slime datta ken", "evangelion", "fullmetal alchemist",
        "cowboy bebop", "mob psycho", "spy x family", "hell's paradise", "jojo's bizarre",
        "classroom of the elite", "bungo stray dogs", "wind breaker", "mashle", "fate/stay",
        "parasyte", "noragami", "akame ga kill", "oshi no ko", "steins;gate", "code geass",
        // Arabic keywords
        "أنمي", "انمي", "أوتاكو", "اوتاكو", "ون بيس", "هجوم العمالقة", "قاتل الشياطين", "ناروتو",
        "بليتش", "سولو ليفلينج", "هنتر اكس هنتر", "هنتر", "دراجون بول", "دراغون بول", "ديث نوت",
        "المحقق كونان", "جوجوتسو كايسن", "جوجوتسو", "ماي هيرو اكاديميا", "اكاديمية بطلي",
        "طوكيو غول", "طوكيو ريفينجرز", "جينتاما", "فيري تيل", "بلو لوك", "داندادان", "فينلاند ساجا",
        "فينلاند", "كايجو", "أوفرلورد", "سيف النار", "كيميتسو", "مذكرة الموت", "هايكيو",
        "بلاك كلوفر", "فول ميتال", "ديث باريد", "شتاينز جيت", "كود جياس", "سوار أرت أونلاين",
        "ون بنش مان", "موب سايكو", "دكتور ستون", "تشينسو مان", "رجل المنشار", "بوروتو",
        "فريرين", "روروني كنشين", "إيفانجيليون", "سباي اكس فاميلي", "فصل النخبة", "ويند بريكر",
        "ماشيل", "نادي أورين", "سلة كوروكو", "هاجيمي نو إيبو"
    )

    fun markAsAnime(context: Context, id: String) {
        if (id.isBlank()) return
        val baseId = if (id.contains("_")) id.substringBeforeLast("_") else id
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_ANIME_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(id)
        set.add(baseId)
        prefs.edit().putStringSet(KEY_ANIME_IDS, set).apply()
    }

    fun isAnime(context: Context, id: String, title: String? = null): Boolean {
        val baseId = if (id.contains("_")) id.substringBeforeLast("_") else id
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_ANIME_IDS, emptySet()) ?: emptySet()
        if (set.contains(id) || set.contains(baseId)) return true
        if (!title.isNullOrBlank()) {
            val lower = title.lowercase()
            val containsJapanese = title.any { ch -> ch in '\u3040'..'\u30FF' || ch in '\u4E00'..'\u9FFF' }
            if (containsJapanese || ANIME_KEYWORDS.any { lower.contains(it) }) {
                markAsAnime(context, id)
                return true
            }
        }
        return false
    }
}
