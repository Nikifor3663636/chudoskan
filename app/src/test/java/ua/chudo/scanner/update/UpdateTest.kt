package ua.chudo.scanner.update

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ua.chudo.scanner.terminal.Cart
import ua.chudo.scanner.terminal.CartItem
import ua.chudo.scanner.terminal.CartStore
import ua.chudo.scanner.terminal.Discount

class UpdateTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sha = "0123456789abcdef".repeat(4)
    private fun info(code: Long, min: Long = 0) = UpdateInfo(code, "3.$code", "https://x/a.apk", sha, min)

    @Test fun `рішення — оновлення, обов'язкове, нічого`() {
        assertEquals(UpdateDecision.None, UpdatePolicy.decide(1042, info(1042)))
        assertEquals(UpdateDecision.None, UpdatePolicy.decide(1042, null))
        assertTrue(UpdatePolicy.decide(1042, info(1050)) is UpdateDecision.Available)
        // minVersionCode у version.json
        val r = UpdatePolicy.decide(1042, info(1050, min = 1050)) as UpdateDecision.Required
        assertEquals(1050, r.info!!.versionCode)
        // meta/app піднімає мінімальну версію без нового релізу, зі своїм текстом
        val m = UpdatePolicy.decide(1042, info(1050), metaMin = 1043, metaMessage = "Оновіться до 3.1") as UpdateDecision.Required
        assertEquals("Оновіться до 3.1", m.message)
        assertEquals(UpdatePolicy.REQUIRED_MESSAGE, (UpdatePolicy.decide(1, null, metaMin = 2) as UpdateDecision.Required).message)
    }

    @Test fun `мобільна мережа — лише APK до 50 МБ`() {
        assertTrue(UpdatePolicy.mayDownload(unmetered = true, sizeBytes = null))
        assertTrue(UpdatePolicy.mayDownload(false, 30L * 1024 * 1024))
        assertFalse(UpdatePolicy.mayDownload(false, 60L * 1024 * 1024))
        assertFalse(UpdatePolicy.mayDownload(false, null))
    }

    @Test fun `version json з CI`() {
        val json = """{"versionCode":1042,"versionName":"3.1","sha256":"${sha.uppercase()}","minVersionCode":0,"notes":"Термінал і \"оплата\"","publishedAt":"2026-11-24T10:00:00Z"}"""
        val i = VersionJson.parse(json, GitHubSource.APK_URL)!!
        assertEquals(1042, i.versionCode)
        assertEquals("3.1", i.versionName)
        assertEquals(sha, i.sha256)
        assertEquals(GitHubSource.APK_URL, i.apkUrl)
        assertEquals("Термінал і \"оплата\"", i.notes)
        assertNull(VersionJson.parse("""{"versionCode":1,"sha256":"abc"}""", "u"))
        assertNull(VersionJson.parse("<html>", "u"))
    }

    @Test fun `meta app — Диск або GitHub`() = runBlocking {
        val m = AppMeta(1050, "3.2", "https://drive.google.com/uc?export=download&id=X", sha)
        val github = UpdateSource { info(1042) }
        assertEquals(1050, UpdateSources(DriveSource { m }, github).latest()!!.versionCode)
        assertEquals(1042, UpdateSources(DriveSource { m.copy(source = AppMeta.GITHUB) }, github).latest()!!.versionCode)
        assertEquals(1042, UpdateSources(DriveSource { null }, github).latest()!!.versionCode)
        assertEquals(1042, UpdateSources(DriveSource { error("немає мережі") }, github).latest()!!.versionCode)
    }

    @Test fun `ID файлу з будь-якого посилання Диска`() {
        val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz_-012"
        assertEquals(id, DriveLinks.fileId("https://drive.google.com/file/d/$id/view?usp=sharing"))
        assertEquals(id, DriveLinks.fileId("https://drive.google.com/open?id=$id"))
        assertEquals(id, DriveLinks.fileId("https://drive.google.com/uc?export=download&id=$id"))
        assertEquals(id, DriveLinks.fileId("https://drive.usercontent.google.com/download?id=$id&export=download"))
        assertEquals(id, DriveLinks.fileId(" $id "))
        assertNull(DriveLinks.fileId("https://example.com/file/d/$id/view"))
        assertNull(DriveLinks.fileId("абв"))
        assertEquals("https://drive.google.com/uc?export=download&id=$id", DriveLinks.directUrl(id))
    }

    private val confirmPage = """
        <html><body><form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
        <input type="submit" value="Завантажити все одно">
        <input type="hidden" name="id" value="FILEID12345678901234567">
        <input type="hidden" name="export" value="download">
        <input type="hidden" name="confirm" value="t">
        <input type="hidden" name="uuid" value="a1b2-c3">
        </form></body></html>
    """.trimIndent()

    @Test fun `сторінки Диска замість файлу`() {
        val c = DriveLinks.classify(confirmPage) as DriveLinks.Page.Confirm
        assertEquals("https://drive.usercontent.google.com/download?id=FILEID12345678901234567&export=download&confirm=t&uuid=a1b2-c3", c.url)
        assertEquals(DriveLinks.Page.NoAccess, DriveLinks.classify("<a href=\"https://accounts.google.com/ServiceLogin\">Увійти</a>"))
        assertEquals(DriveLinks.Page.Quota, DriveLinks.classify("<p>Sorry, you can't view or download this file at this time. Too many users have viewed or downloaded this file recently.</p>"))
        assertEquals(DriveLinks.Page.Unknown, DriveLinks.classify("<html>щось інше</html>"))
    }

    /** «Google Диск» без мережі: відповіді за URL. */
    private fun drive(routes: (String) -> Pair<Int, Pair<String, ByteArray>>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = routes(chain.request().url.toString())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .header("Content-Type", body.first).body(body.second.toResponseBody(body.first.toMediaType())).build()
    }.build()

    private val apk = ByteArray(10_000) { (it % 251).toByte() }

    @Test fun `завантаження з Диска через сторінку підтвердження`() = runBlocking {
        val http = drive { url ->
            when {
                url.startsWith("https://drive.google.com/uc") -> 200 to ("text/html; charset=utf-8" to confirmPage.toByteArray())
                url.startsWith("https://drive.usercontent.google.com/download") && "confirm=t" in url -> 200 to ("application/vnd.android.package-archive" to apk)
                else -> 404 to ("text/plain" to ByteArray(0))
            }
        }
        val f = ApkDownloader(http).download("https://drive.google.com/file/d/FILEID12345678901234567/view", tmp.newFile("a.apk"))
        assertTrue(apk.contentEquals(f.readBytes()))
        assertEquals(ApkChecks.sha256(apk.inputStream()), ApkChecks.sha256(f))
    }

    @Test fun `без доступу за посиланням — зрозуміла помилка`() = runBlocking {
        val http = drive { 200 to ("text/html" to "<a href=\"https://accounts.google.com/ServiceLogin\">".toByteArray()) }
        try {
            ApkDownloader(http).download("https://drive.google.com/file/d/FILEID12345678901234567/view", tmp.newFile("b.apk"))
            fail()
        } catch (e: UpdateException) {
            assertTrue(e.message!!.startsWith("Відкрийте доступ до файлу за посиланням"))
        }
    }

    @Test fun `ліміт Диска і великий файл на мобільній мережі`() = runBlocking {
        val quota = drive { 200 to ("text/html" to "Too many users have viewed or downloaded this file recently".toByteArray()) }
        val e = runCatching { ApkDownloader(quota).download("https://drive.google.com/uc?id=FILEID12345678901234567", tmp.newFile("c.apk")) }.exceptionOrNull()
        assertEquals(DriveLinks.QUOTA, e?.message)
        val github = drive { 200 to ("application/octet-stream" to apk) }
        val big = runCatching { ApkDownloader(github).download("https://github.com/x/ChudoSkan.apk", tmp.newFile("d.apk"), maxBytes = 5_000) }.exceptionOrNull()
        assertEquals(ApkDownloader.WAIT_WIFI, big?.message)
    }

    @Test fun `підмінений APK — інший sha256 або підпис`() {
        assertTrue(ApkChecks.sameSha(sha, sha.uppercase()))
        assertFalse(ApkChecks.sameSha(sha, "f" + sha.drop(1)))
        assertFalse(ApkChecks.sameSha("", ""))
        assertTrue(ApkChecks.sameSigner(setOf("aa"), setOf("aa")))
        assertFalse(ApkChecks.sameSigner(setOf("aa"), setOf("bb")))
        assertFalse(ApkChecks.sameSigner(emptySet(), emptySet()))
    }

    @Test fun `публікація — інший підпис або старша версія відхиляються з поясненням`() {
        val ok = ApkFacts("com.chudoostriv.app", 1050, "3.2", sha, sameSigner = true)
        assertNull(PublishCheck.problem("com.chudoostriv.app", ok, published = 1042))
        assertTrue(PublishCheck.problem("com.chudoostriv.app", ok.copy(sameSigner = false), 1042)!!.contains("іншим ключем"))
        assertTrue(PublishCheck.problem("com.chudoostriv.app", ok, 1050)!!.contains("не новіша"))
        assertTrue(PublishCheck.problem("com.chudoostriv.app", ok.copy(packageName = "x.y"), 0)!!.contains("інший застосунок"))
    }

    @Test fun `кошик Термінала переживає оновлення`() {
        val store = CartStore(tmp.root.resolve("cart.txt"))
        val cart = Cart()
            .add(CartItem("4820000000017", "4820000000017", "М'яч\tгумовий", "BUK000123", "MB-22", 2, 24_990, false))
            .add(CartItem("2000999154010", "2000999154010", "Пакет", "BOC022446", "", 1, 12_900, true))
            .withDiscount(Discount.Percent(10))
        store.save(cart, "sale1")
        val (back, id) = store.load()
        assertEquals("sale1", id)
        assertEquals(cart.totalKop, back.totalKop)
        assertEquals("М'яч гумовий", back.items.first().name)
        assertTrue(back.items.last().manualPrice)
        store.save(Cart(), null)
        assertTrue(store.load().first.items.isEmpty())
    }
}
