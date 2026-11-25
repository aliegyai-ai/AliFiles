package com.ali.files.library

import com.ali.files.util.FileKind

/**
 * «اسأل مكتبتك»: understands a plain Egyptian-Arabic sentence ("أغاني عمرو دياب اللي نزلتها الأسبوع ده")
 * with fixed patterns — kinds, dates, sizes, lengths, sites, «ما كملتهوش»… — on the phone, no internet.
 * What it doesn't understand stays as words to look for in the names.
 */
object AskParser {

    /** one thing it understood (shown as a gold chip; ✕ gives the words back to the text) */
    data class Chip(val id: String, val label: String, val test: Test)

    sealed interface Test {
        data class Kind(val kinds: Set<FileKind>) : Test
        data class Since(val ms: Long, val until: Long = Long.MAX_VALUE) : Test
        data class Size(val min: Long = 0, val max: Long = Long.MAX_VALUE) : Test
        data class Length(val min: Long = 0, val max: Long = Long.MAX_VALUE) : Test
        data class Site(val names: List<String>) : Test
        data object Separated : Test
        data object MusicOnly : Test
        data object Unfinished : Test
        data object Favorite : Test
        data object Played : Test
        data class Sort(val by: String) : Test
    }

    data class Query(val words: List<String>, val chips: List<Chip>)

    /** same text for «أحمد / احمد», «ة / ه», «ى / ي», tashkeel, Arabic digits, upper / lower case */
    fun norm(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s.lowercase()) {
            when (c) {
                in 'ً'..'ْ', 'ـ', 'ٰ' -> {}
                'أ', 'إ', 'آ', 'ٱ' -> b.append('ا')
                'ة' -> b.append('ه')
                'ى' -> b.append('ي')
                'ؤ' -> b.append('و')
                'ئ' -> b.append('ي')
                in '٠'..'٩' -> b.append('0' + (c - '٠'))
                in '۰'..'۹' -> b.append('0' + (c - '۰'))
                '_', '-', '.', '،', ',', '(', ')', '[', ']', '|' -> b.append(' ')
                else -> b.append(c)
            }
        }
        return b.toString().replace(Regex("\\s+"), " ").trim()
    }

    private const val DAY = 86_400_000L
    private const val MIN = 60_000L
    private fun startOfToday(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private class Rule(val id: String, val re: Regex, val make: (MatchResult) -> Chip?)
    private fun r(p: String) = Regex("(?<![\\p{L}\\d])(?:$p)(?![\\p{L}\\d])")
    private fun num(m: MatchResult, g: Int) = m.groupValues[g].toDoubleOrNull()
    private fun unitBytes(u: String): Long = if (u.startsWith("ج") || u.startsWith("g")) 1L shl 30 else if (u.startsWith("ك") || u.startsWith("k")) 1L shl 10 else 1L shl 20
    private fun unitMs(u: String): Long = if (u.startsWith("س") || u.startsWith("h")) 60 * MIN else if (u.startsWith("ث") || u.startsWith("s")) 1000L else MIN

    // order matters: «بدون موسيقى» before «موسيقى»; «أكبر من 100 ميجا» before «أكبر حاجة»
    private val rules = listOf(
        Rule("sep", r("(?:من غير|بدون|منزوع(?:ه)?|مشال(?:ه)? منه(?:ا)?|شلت منه(?:ا)?|اللي شلت منه(?:ا)?) ?(?:ال)?(?:موسيقي|مزيكا|ميوزك)")) { Chip("sep", "🎙️ من غير موسيقى", Test.Separated) },
        Rule("musiconly", r("(?:ال)?(?:موسيقي|مزيكا) (?:بس|فقط|لوحدها)")) { Chip("musiconly", "🎼 الموسيقى بس", Test.MusicOnly) },
        Rule("video", r("(?:ال)?(?:فيديو|فديو|فيديوهات|فديوهات|فيلم|افلام|الافلام|مقاطع|مقطع|كليب|كليبات|حلقه|حلقات|مسلسل)")) { Chip("video", "🎬 فيديو", Test.Kind(setOf(FileKind.VIDEO))) },
        Rule("audio", r("(?:ال)?(?:اغاني|اغنيه|صوت|صوتيات|اغنيات|تسجيل|تسجيلات|بودكاست|ريكورد|مزيكا|موسيقي|قران|تلاوه|تلاوات|دروس|درس|محاضره|محاضرات)")) { m ->
            Chip("audio", "🎵 صوت", Test.Kind(setOf(FileKind.AUDIO)))
        },
        Rule("image", r("(?:ال)?(?:صور|صوره|الصور|صوري)")) { Chip("image", "🖼️ صور", Test.Kind(setOf(FileKind.IMAGE))) },
        Rule("doc", r("(?:ال)?(?:مستندات|مستند|ملفات pdf|pdf|كتب|كتاب|ورق|اوراق|وورد|word|اكسل|excel)")) { Chip("doc", "📄 مستندات", Test.Kind(setOf(FileKind.DOC))) },
        Rule("zip", r("(?:ال)?(?:مضغوط(?:ه)?|zip|rar)")) { Chip("zip", "🗜️ مضغوط", Test.Kind(setOf(FileKind.ARCHIVE))) },
        Rule("apk", r("(?:ال)?(?:برامج|تطبيقات|برنامج|تطبيق|apk)")) { Chip("apk", "📦 تطبيقات", Test.Kind(setOf(FileKind.APK))) },

        // dates
        Rule("date", r("(?:من )?(?:اخر|في اخر|خلال) (\\d+) (?:يوم|ايام)")) { m ->
            val n = num(m, 1)?.toLong() ?: return@Rule null; Chip("date", "📅 آخر $n يوم", Test.Since(startOfToday() - (n - 1) * DAY))
        },
        Rule("date", r("(?:النهارده|انهارده|النهاردا|اليوم ده|اليوم|النهار ده|today)")) { Chip("date", "📅 النهارده", Test.Since(startOfToday())) },
        Rule("date", r("(?:امبارح|امس|البارحه|yesterday)")) { Chip("date", "📅 امبارح", Test.Since(startOfToday() - DAY, startOfToday())) },
        Rule("date", r("(?:الاسبوع (?:ده|دا|دي|الحالي)|هذا الاسبوع|الاسبوع|اسبوع|week)")) { Chip("date", "📅 الأسبوع ده", Test.Since(System.currentTimeMillis() - 7 * DAY)) },
        Rule("date", r("(?:الشهر (?:ده|دا|الحالي)|هذا الشهر|الشهر|month)")) { Chip("date", "📅 الشهر ده", Test.Since(System.currentTimeMillis() - 30 * DAY)) },
        Rule("date", r("(?:السنه (?:دي|ده|دا)|هذه السنه|السنه|year)")) { Chip("date", "📅 السنة دي", Test.Since(System.currentTimeMillis() - 365 * DAY)) },

        // sizes
        Rule("size", r("(?:اكبر|اكتر|فوق|اعلي) من (\\d+(?:\\.\\d+)?) ?(ميجا|ميجه|ميغا|م|mb|m|جيجا|جيجه|جيغا|ج|gb|g|كيلو|kb|k)")) { m ->
            val n = num(m, 1) ?: return@Rule null; Chip("size", "📦 أكبر من ${m.groupValues[1]} ${m.groupValues[2]}", Test.Size(min = (n * unitBytes(m.groupValues[2])).toLong()))
        },
        Rule("size", r("(?:اصغر|اقل|تحت) من (\\d+(?:\\.\\d+)?) ?(ميجا|ميجه|ميغا|م|mb|m|جيجا|جيجه|جيغا|ج|gb|g|كيلو|kb|k)")) { m ->
            val n = num(m, 1) ?: return@Rule null; Chip("size", "📦 أصغر من ${m.groupValues[1]} ${m.groupValues[2]}", Test.Size(max = (n * unitBytes(m.groupValues[2])).toLong()))
        },
        // lengths
        Rule("len", r("(?:اطول|اكتر|فوق|اكبر) من (\\d+(?:\\.\\d+)?) ?(دقيقه|دقايق|دقائق|دقيقتين|د|min|ساعه|ساعات|س|h|ثانيه|ثواني|s)")) { m ->
            val n = num(m, 1) ?: return@Rule null; Chip("len", "⏱️ أطول من ${m.groupValues[1]} ${m.groupValues[2]}", Test.Length(min = (n * unitMs(m.groupValues[2])).toLong()))
        },
        Rule("len", r("(?:اقصر|اقل|تحت) من (\\d+(?:\\.\\d+)?) ?(دقيقه|دقايق|دقائق|دقيقتين|د|min|ساعه|ساعات|س|h|ثانيه|ثواني|s)")) { m ->
            val n = num(m, 1) ?: return@Rule null; Chip("len", "⏱️ أقصر من ${m.groupValues[1]} ${m.groupValues[2]}", Test.Length(max = (n * unitMs(m.groupValues[2])).toLong()))
        },
        Rule("len", r("(?:طويل(?:ه)?|طوال|الطويله|الطويل)")) { Chip("len", "⏱️ طويلة (أكتر من 20 دقيقة)", Test.Length(min = 20 * MIN)) },
        Rule("len", r("(?:قصير(?:ه)?|قصار|القصيره|القصير)")) { Chip("len", "⏱️ قصيرة (أقل من 5 دقايق)", Test.Length(max = 5 * MIN)) },

        // sites (by the folder a download landed in, or the name)
        Rule("site", r("(?:من |علي |بتاع(?:ه|ت)? )?(?:يوتيوب|اليوتيوب|youtube|yt)")) { Chip("site", "📺 يوتيوب", Test.Site(listOf("youtube", "يوتيوب"))) },
        Rule("site", r("(?:من |علي )?(?:فيسبوك|فيس بوك|الفيس|facebook|fb)")) { Chip("site", "📘 فيسبوك", Test.Site(listOf("facebook", "فيسبوك", "fb"))) },
        Rule("site", r("(?:من |علي )?(?:تيك توك|تيكتوك|التيك توك|tiktok)")) { Chip("site", "🎵 تيك توك", Test.Site(listOf("tiktok", "تيك توك"))) },
        Rule("site", r("(?:من |علي )?(?:انستجرام|انستا|انستغرام|instagram|insta)")) { Chip("site", "📸 إنستجرام", Test.Site(listOf("instagram", "انستجرام"))) },
        Rule("site", r("(?:من |علي )?(?:تويتر|اكس|twitter|x\\.com)")) { Chip("site", "🐦 إكس", Test.Site(listOf("twitter", "x.com", "تويتر"))) },

        // states
        Rule("unfinished", r("(?:اللي )?(?:ما ?كملته(?:وش)?|ما ?كملتهاش|مكملتهوش|مكملتهاش|مخلصتهوش|مخلصتهاش|ما ?خلصتهوش|وقفت (?:فيه|فيها|عنده|عندها)|لسه ما ?خلصتش|نصها|نصه)")) { Chip("unfinished", "⏸️ ما كملتهوش", Test.Unfinished) },
        Rule("fav", r("(?:ال)?(?:مفضله|مفضلات|المفضل|بحبها|بحبه|حبيته|حبيتها)")) { Chip("fav", "❤️ المفضلة", Test.Favorite) },
        Rule("played", r("(?:اللي )?(?:سمعته(?:ا)?|شغلته(?:ا)?|اتفرجت عليه(?:ا)?|شفته(?:ا)?)(?: قريب| مؤخرا)?")) { Chip("played", "🕘 اللي شغلته", Test.Played) },

        // order
        Rule("sort", r("(?:اكبر|اضخم|اتقل) (?:حاجه|حاجات|ملف|ملفات|فيديو|حاجه عندي)")) { Chip("sort", "⬇️ الأكبر الأول", Test.Sort("size")) },
        Rule("sort", r("(?:اصغر) (?:حاجه|حاجات|ملف|ملفات)")) { Chip("sort", "⬆️ الأصغر الأول", Test.Sort("small")) },
        Rule("sort", r("(?:اطول) (?:حاجه|حاجات|فيديو|ملف)")) { Chip("sort", "⬇️ الأطول الأول", Test.Sort("long")) },
        Rule("sort", r("(?:الاحدث|الجديد|الجديده|احدث|اخر حاجه|اخر حاجات|newest)")) { Chip("sort", "🆕 الأحدث الأول", Test.Sort("new")) },
        Rule("sort", r("(?:الاقدم|القديم|القديمه|اقدم|oldest)")) { Chip("sort", "🗓️ الأقدم الأول", Test.Sort("old")) },
    )

    /** little words that only glue the sentence */
    private val stop = setOf(
        "اللي", "الي", "التي", "الذي", "اللى", "دي", "ده", "دا", "عندي", "عندى", "بتاعي", "بتاعتي", "كل", "عاوز", "عايز", "هات",
        "وريني", "ورينى", "فين", "شوف", "دور", "دورلي", "علي", "في", "من", "و", "او", "ال", "حاجه", "حاجات", "ملف", "ملفات",
        "نزلتها", "نزلته", "نزلتهم", "نزلت", "حملتها", "حملته", "حملتهم", "حملت", "اتحملت", "اتحمل", "اتنزلت", "بتاع", "بتاعت",
        "the", "a", "of", "my",
    )

    /** what's understood, minus the chips you removed (their words come back as words to look for) */
    fun parse(text: String, removed: Set<String>): Query {
        var t = " " + norm(text) + " "
        val chips = ArrayList<Chip>()
        val back = StringBuilder()
        for (rule in rules) {
            if (chips.any { it.id == rule.id }) continue
            val m = rule.re.find(t) ?: continue
            val chip = rule.make(m) ?: continue
            if (chip.id in removed) { back.append(' ').append(m.value); t = t.replaceRange(m.range, " "); continue }
            chips += chip
            t = t.replaceRange(m.range, " ")
        }
        val words = (t + back).split(' ').map { it.trim() }.filter { it.isNotEmpty() && it !in stop }
        return Query(words, chips)
    }

    /** a word is found in [hay] (already normalised), with or without «ال» / «و» in front */
    fun has(hay: String, w: String): Boolean {
        if (hay.contains(w)) return true
        val bare = w.removePrefix("و").removePrefix("ال")
        return bare.length >= 2 && bare != w && hay.contains(bare)
    }
}
