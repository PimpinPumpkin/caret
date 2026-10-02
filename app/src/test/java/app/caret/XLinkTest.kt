package app.caret

import androidx.test.core.app.ApplicationProvider
import app.caret.latin.App
import app.caret.latin.BuildConfig
import app.caret.latin.common.Links
import app.caret.latin.common.LocaleUtils.constructLocale
import app.caret.latin.utils.getKnownDictionariesForLocale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class XLinkTest { // Without the X, SubtypeTests fail with ClassCastException. WTF?
    @Test fun knownDictionaries() {
        if (System.getenv("CARET_LINK_TESTS") == null) return // needs network, and upstream links can go stale
        val context = ApplicationProvider.getApplicationContext<App>()
        val urls = mutableSetOf<String>()
        context.assets.open("dictionaries_in_dict_repo.csv").reader().readLines().forEach { line ->
            getKnownDictionariesForLocale(line.split(",")[1].constructLocale(), context).forEach {
                urls.add(it.second)
            }
        }
        // can't check everything at once, this will trigger some rate limit
        val typeToCheck = listOf("/dictionaries_experimental/", "/emoji_cldr_signal_dictionaries/", "/dictionaries/").random()
        urls.forEach {
            if (it.contains(typeToCheck))
            checkLink(it)
        }
    }

    @Test fun readmeLinks() {
        if (System.getenv("CARET_LINK_TESTS") == null) return // needs network, and upstream links can go stale
        val file = File("../README.md")
        val linkRegex = "(?:https?:\\/\\/.)?(?:www\\.)?[-a-zA-Z0-9@%._\\+~#=]{2,256}\\.[a-z]{2,6}\\b(?:[-a-zA-Z0-9@:%_\\+.~#?&\\/\\/=]*)".toRegex()
        val links = linkRegex.findAll(file.readText())
        links.forEach {
            if (it.value.contains("heli", true))
                checkLink(it.value.trim('.'))
        }
    }

    @Test fun layoutsLinks() {
        if (System.getenv("CARET_LINK_TESTS") == null) return // needs network, and upstream links can go stale
        val file = File("../layouts.md")
        val linkRegex = "(?:https?:\\/\\/.)?(?:www\\.)?[-a-zA-Z0-9@%._\\+~#=]{2,256}\\.[a-z]{2,6}\\b(?:[-a-zA-Z0-9@:%_\\+.~#?&\\/\\/=]*)".toRegex()
        val links = linkRegex.findAll(file.readText())
        links.forEach {
            if (it.value.contains("heli", true))
                checkLink(it.value)
        }
    }

    @Test fun layoutsLinksInternal() {
        val file = File("../layouts.md")
        val internalLinkRegex = "app/src/\\b(?:[-a-zA-Z0-9@:%_\\+.~#?&\\/\\/=]*)".toRegex()
        val links = internalLinkRegex.findAll(file.readText())
        links.forEach {
            assert(File("../" + it.value.substringBefore('#')).exists()) { "layouts.md points to missing ${it.value}" }
        }
    }

    @Test fun otherLinks() {
        if (System.getenv("CARET_LINK_TESTS") == null) return // needs network, and upstream links can go stale
        // Links.LICENSE is left out while the Caret repository is private
        listOf(Links.LAYOUT_WIKI_URL, Links.WIKI_URL, Links.CUSTOM_LAYOUTS, Links.CUSTOM_COLORS).forEach {
            checkLink(it)
        }
    }

    private fun checkLink(link: String) {
        if (link.contains("wiki/"))
            return checkWikiLink(link)
        val url = URL(link)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        if (connection.responseCode != 200)
            println("error checking $link")
        assertEquals(200, connection.responseCode)
    }

    private fun checkWikiLink(link: String) {
        val url = URL(link)
        val connection = url.openConnection() as HttpURLConnection
        if (connection.responseCode != 200)
            println("error checking $link")
        assertEquals(200, connection.responseCode)
        val text = connection.getInputStream().reader().readText()
        if ("Create new page" in text)
            println("error checking wiki $link")
        assert("Create new page" !in text)
    }
}
