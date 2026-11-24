package ua.chudo.scanner.update

/**
 * Посилання Google Диска на APK («усі, хто має посилання»): ID файлу, пряме завантаження
 * і сторінки, які Диск показує замість файлу (підтвердження, немає доступу, ліміт).
 */
object DriveLinks {
    private val ID = Regex("^[A-Za-z0-9_-]{20,}$")
    private val PATTERNS = listOf(
        Regex("/file/d/([A-Za-z0-9_-]{20,})"),
        Regex("[?&]id=([A-Za-z0-9_-]{20,})"),
        Regex("/d/([A-Za-z0-9_-]{20,})"),
    )

    /** «https://drive.google.com/file/d/<ID>/view?usp=sharing», «…open?id=<ID>», «…uc?id=<ID>&export=download» або сам ID. */
    fun fileId(link: String): String? {
        val t = link.trim()
        if (ID.matches(t)) return t
        if (!t.contains("google.com")) return null
        return PATTERNS.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1) }
    }

    fun directUrl(id: String) = "https://drive.google.com/uc?export=download&id=$id"

    sealed interface Page {
        /** Диск не може перевірити великий файл на віруси — треба натиснути «Завантажити все одно». */
        data class Confirm(val url: String) : Page
        data object NoAccess : Page
        data object Quota : Page
        data object Unknown : Page
    }

    const val NO_ACCESS = "Відкрийте доступ до файлу за посиланням («Усі, хто має посилання»)."
    const val QUOTA = "Google Диск: перевищено ліміт завантажень. Спробуйте пізніше або перемкніть джерело на GitHub."

    /** HTML замість APK: що саме показав Диск. */
    fun classify(html: String): Page {
        val low = html.lowercase()
        if ("quota exceeded" in low || "too many users have viewed or downloaded" in low || "перевищено квоту" in low) return Page.Quota
        confirmUrl(html)?.let { return Page.Confirm(it) }
        if ("accounts.google.com" in low || "servicelogin" in low || "you need access" in low || "потрібен доступ" in low ||
            "request access" in low || "запитати доступ" in low
        ) return Page.NoAccess
        return Page.Unknown
    }

    /** Форма «Завантажити все одно»: action + приховані поля (id, export, confirm, uuid). */
    fun confirmUrl(html: String): String? {
        val form = Regex("<form[^>]+id=\"download-form\"[^>]*>([\\s\\S]*?)</form>").find(html) ?: return legacyConfirm(html)
        val action = Regex("action=\"([^\"]+)\"").find(form.value)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return null
        val inputs = Regex("<input[^>]+type=\"hidden\"[^>]*>").findAll(form.groupValues[1]).mapNotNull { m ->
            val name = Regex("name=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
            val value = Regex("value=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1)?.replace("&amp;", "&").orEmpty()
            "$name=" + java.net.URLEncoder.encode(value, "UTF-8")
        }.toList()
        if (inputs.isEmpty()) return null
        return action + (if ('?' in action) "&" else "?") + inputs.joinToString("&")
    }

    /** Старіший варіант сторінки: посилання з confirm=XXXX. */
    private fun legacyConfirm(html: String): String? {
        val href = Regex("href=\"(/uc\\?export=download[^\"]*confirm=[^\"]+)\"").find(html)?.groupValues?.get(1) ?: return null
        return "https://drive.google.com" + href.replace("&amp;", "&")
    }
}
