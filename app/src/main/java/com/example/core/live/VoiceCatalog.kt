package com.example.core.live

data class VoiceItem(
    val name: String, // Gemini voice name e.g. "Aoede"
    val hinglishTitle: String, // e.g. "Halki-phulki"
    val gender: String, // "Female", "Male"
    val previewText: String,
    val pitch: Float = 1.0f,
    val rate: Float = 1.0f
)

data class VoiceInfo(
    val name: String,
    val gender: String,
    val styleNote: String
)

object VoiceCatalog {

    val FEMALE_VOICES = listOf(
        VoiceItem("Aoede", "Halki-phulki", "Female", "Haan Lucky, main Maya hu, batao kya karna hai?", pitch = 1.15f, rate = 1.02f),
        VoiceItem("Kore", "Damdaar", "Female", "Haan Lucky, main ready hu, bolo kya kaam hai.", pitch = 0.95f, rate = 0.98f),
        VoiceItem("Leda", "Jawaan", "Female", "Suno Lucky, sab ready hai, kya karein?", pitch = 1.25f, rate = 1.08f),
        VoiceItem("Zephyr", "Chamakdaar", "Female", "Hello Lucky! Sab badhiya chal raha hai, batao kya plan hai?", pitch = 1.2f, rate = 1.05f),
        VoiceItem("Laomedeia", "Josheeli", "Female", "Haanji Lucky! Ekdum full energy me hu, shuru karein?", pitch = 1.1f, rate = 1.12f),
        VoiceItem("Despina", "Mulaayam", "Female", "Aapka din kaisa raha Lucky? Main yahin hu aapke saath.", pitch = 1.05f, rate = 0.95f),
        VoiceItem("Erinome", "Saaf", "Female", "Haan Lucky, bilkul clear hu. Bataiye kya task hai.", pitch = 1.0f, rate = 1.0f),
        VoiceItem("Callirrhoe", "Befikar", "Female", "Arrey Lucky! Tension mat lo, main sambhaal lungi.", pitch = 1.18f, rate = 1.04f),
        VoiceItem("Autonoe", "Pyaar bhari", "Female", "Lucky, aapka khayal rakhna meri zimmedari hai.", pitch = 1.08f, rate = 0.98f),
        VoiceItem("Algieba", "Tez taraar", "Female", "Quick and ready! Seconds me ho jayega kaam.", pitch = 1.12f, rate = 1.15f),
        VoiceItem("Schedar", "Ghamand bina", "Female", "Haan Lucky, hamesha ki tarah aapki madad ke liye tayyar.", pitch = 1.02f, rate = 1.0f),
        VoiceItem("Pulcherrima", "Khushmizaj", "Female", "Wah Lucky! Aaj ka din kamaal ka hone wala hai!", pitch = 1.22f, rate = 1.06f),
        VoiceItem("Vindemiatrix", "Suljhi hui", "Female", "Professional tareeqe se saare tasks complete kar denge.", pitch = 0.98f, rate = 1.0f),
        VoiceItem("Sadachbia", "Nateeja dene wali", "Female", "Result chahiye? Bas ek command dijiye, abhi karti hu.", pitch = 1.14f, rate = 1.08f),
        VoiceItem("Sulafat", "Shaant", "Female", "Aaram se Lucky, shanti se ek-ek karke sab kar lenge.", pitch = 0.92f, rate = 0.94f)
    )

    val MALE_VOICES = listOf(
        VoiceItem("Algenib", "Khurdari", "Male", "Haan Lucky, bold aur gritty style me tayyar hu.", pitch = 0.85f, rate = 0.96f),
        VoiceItem("Charon", "Jaankaari bhari", "Male", "Namaste Lucky. Sabhi calculations aur system status theek hai.", pitch = 0.82f, rate = 0.94f),
        VoiceItem("Fenrir", "Utsaahi", "Male", "Chalo Lucky! Full throttle me target achieve karte hain!", pitch = 0.92f, rate = 1.08f),
        VoiceItem("Puck", "Josheeli", "Male", "Arrey bhai Lucky! Kya scene hai, bolo kya phodna hai?", pitch = 1.08f, rate = 1.12f),
        VoiceItem("Orus", "Damdaar", "Male", "Grounded aur confident. Command do Lucky.", pitch = 0.78f, rate = 0.95f),
        VoiceItem("Enceladus", "Saanson bhari", "Male", "Authoritative aur deep. Main sun raha hu Lucky.", pitch = 0.72f, rate = 0.92f),
        VoiceItem("Iapetus", "Saaf", "Male", "Ekdum clear voice me Lucky, hamesha available.", pitch = 0.90f, rate = 1.0f),
        VoiceItem("Umbriel", "Befikar", "Male", "Relax raho Lucky, koi jaldi nahi, aaram se karte hain.", pitch = 0.88f, rate = 0.92f),
        VoiceItem("Rasalgethi", "Ghabrahat bina", "Male", "Steady and smooth. Kuch bhi ho, handle ho jayega.", pitch = 0.80f, rate = 0.98f),
        VoiceItem("Alnilam", "Gehri awaaz", "Male", "Gehri baritone me, focused automation ready hai.", pitch = 0.75f, rate = 0.94f),
        VoiceItem("Gacrux", "Robdaar", "Male", "Pura control aapke haath me hai Lucky.", pitch = 0.70f, rate = 0.90f),
        VoiceItem("Achird", "Dostana", "Male", "Haan Lucky mere bhai, bata kya help chahiye.", pitch = 0.96f, rate = 1.02f),
        VoiceItem("Zubenelgenubi", "Sahaj", "Male", "Calm, deliberate, aur direct response tayyar hai.", pitch = 0.84f, rate = 0.96f),
        VoiceItem("Sadaltager", "Aadarniya", "Male", "Sir, I am standing by for your next operational instruction.", pitch = 0.78f, rate = 0.95f),
        VoiceItem("Achernar", "Fauladi", "Male", "Strong, crisp, fast action guaranteed Lucky.", pitch = 0.86f, rate = 1.05f)
    )

    val ALL_VOICES = FEMALE_VOICES + MALE_VOICES

    // Backwards compatibility for VoiceInfo
    val VOICES: List<VoiceInfo> = ALL_VOICES.map {
        VoiceInfo(it.name, it.gender, "${it.hinglishTitle} (${it.name})")
    }

    private val voiceMap = ALL_VOICES.associateBy { it.name.lowercase() }

    fun findVoice(nameOrQuery: String): VoiceInfo? {
        val q = nameOrQuery.trim().lowercase()
        val found = voiceMap[q] ?: ALL_VOICES.firstOrNull {
            it.name.lowercase().contains(q) ||
            it.hinglishTitle.lowercase().contains(q) ||
            it.gender.lowercase() == q
        }
        return found?.let { VoiceInfo(it.name, it.gender, "${it.hinglishTitle} (${it.name})") }
    }

    fun getRandomVoice(): VoiceInfo {
        return VOICES.random()
    }

    fun getVoiceItem(name: String): VoiceItem {
        return voiceMap[name.trim().lowercase()] ?: FEMALE_VOICES.first()
    }

    fun getVoicesForTab(tab: String): List<VoiceItem> {
        return when (tab.lowercase()) {
            "maya" -> FEMALE_VOICES
            "friday" -> FEMALE_VOICES.reversed()
            "venom" -> MALE_VOICES
            "jarvis" -> MALE_VOICES.reversed()
            else -> ALL_VOICES
        }
    }
}
