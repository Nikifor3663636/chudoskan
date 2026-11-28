package ua.chudo.scanner.data

/** Посада в магазині (users.job_title). Лише підпис для колективу — права дають approved / admin. */
enum class JobTitle(val code: String, val title: String, val group: String) {
    MANAGER("manager", "Керуючий", "Керуючий магазином"),
    SENIOR("senior", "Старший продавець", "Старші продавці"),
    SELLER("seller", "Продавець", "Продавці"),
    ;

    companion object {
        fun of(code: String?): JobTitle? = entries.firstOrNull { it.code == code }
    }
}

/** Колега з public.shop_team(): без телефону — лише ім'я, посада й позначка адміна. */
data class TeamMember(val uid: String, val name: String, val job: JobTitle?, val admin: Boolean)

/** Розділ екрана «Колектив»: заголовок і люди в ньому. */
data class TeamGroup(val title: String, val members: List<TeamMember>)

object Team {
    const val NO_JOB = "Посаду не вказано"

    private val UK: Comparator<String> = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("uk"))
        .let { c -> Comparator { a, b -> c.compare(a, b) } }

    /** Керуючий → старші продавці → продавці → без посади; усередині — за абеткою, порожні розділи не показуємо. */
    fun groups(members: List<TeamMember>): List<TeamGroup> {
        val byJob = members.groupBy { it.job }
        return (JobTitle.entries.map { it as JobTitle? } + null).mapNotNull { job ->
            byJob[job]?.sortedWith(compareBy(UK) { it.name })?.let { TeamGroup(job?.group ?: NO_JOB, it) }
        }
    }

    /** Підпис під ім'ям: «Старший продавець · Адмін магазину», без посади — «Співробітник». */
    fun subtitle(job: JobTitle?, admin: Boolean): String =
        listOfNotNull(job?.title, "Адмін магазину".takeIf { admin }).joinToString(" · ").ifEmpty { "Співробітник" }

    /** Посаду ставить адмін магазину / мережі: підтвердженим, крім адмінів мережі (собі — теж). */
    fun canSetJob(m: StaffMember): Boolean = m.approved && !m.superAdmin

    /** Колектив поточного магазину (Supabase). У резервному Firestore колективу немає — порожній список. */
    suspend fun load(): List<TeamMember> = if (Backend.isSupabase) SupabaseUsers.team() else emptyList()
}
