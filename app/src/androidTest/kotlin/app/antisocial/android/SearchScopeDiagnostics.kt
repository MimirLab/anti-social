package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import com.google.gson.GsonBuilder
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI

/** Opt-in native-screen availability evidence; upstream refusals are observations. */
@RunWith(AndroidJUnit4::class)
class SearchScopeDiagnostics {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun auditNativeScopedSearches() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveSearchScopes") == "true")
        val repo = (ui.activity.application as AntiSocialApplication).repo
        runBlocking { repo.awaitReady() }
        val prefs = ui.activity.getSharedPreferences("antisocial", Context.MODE_PRIVATE)
        val savedPreferences = prefs.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
        val savedHistory = runBlocking { repo.dao.allHistory().associateBy { it.key } }
        val touched = mutableSetOf<String>()
        val models = mutableListOf<AntiSocialViewModel>()
        val directory = File(requireNotNull(ui.activity.getExternalFilesDir("validation")), "search-scopes-live").apply { mkdirs() }
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        val measurements = mutableListOf<Map<String, Any?>>()
        try {
            ui.runOnIdle {
                repo.prefs.intervalMinutes = 0; repo.prefs.archiveChecks = false
                repo.prefs.tor = false; repo.prefs.originalFallback = true
                repo.prefs.searchProvider = SearchProvider.DARWIN; repo.prefs.onboarding = true
                repo.prefs.selected = listOf(Platform.REDDIT, Platform.BLUESKY, Platform.TELEGRAM)
                Platform.entries.forEach { repo.prefs.lastUrl(it, "") }
                repo.applyNetworkSettings()
            }
            val cases = listOf(
                Triple(Platform.REDDIT, "battery", SearchScope(SearchScopeKind.COMMUNITY, "android")),
                Triple(Platform.BLUESKY, "Bluesky", SearchScope(SearchScopeKind.ACCOUNT, "bsky.app")),
                Triple(Platform.TELEGRAM, "Telegram", SearchScope(SearchScopeKind.CHANNEL, "durov"))
            )
            for ((platform, query, scope) in cases) {
                val target = repo.routes.search(platform, query, scope)
                lateinit var vm: AntiSocialViewModel
                val started = android.os.SystemClock.elapsedRealtime()
                ui.runOnUiThread {
                    vm = AntiSocialViewModel(repo).also { models += it; it.following() }
                    ui.activity.setContent { AntiSocialApp(vm) }
                    vm.open(target, true)
                }
                var timedOut = false
                try {
                    ui.waitUntil(90_000) {
                        val state = vm.screen.value
                        state.page?.posts.orEmpty().forEach { touched += repo.key(it) }
                        state.target == target && !state.loading && (state.searchFeed != null || state.error != null)
                    }
                } catch (_: androidx.compose.ui.test.ComposeTimeoutException) { timedOut = true }
                val state = vm.screen.value
                val page = state.page ?: state.searchFeed?.page ?: Page(emptyList())
                page.posts.forEach { touched += repo.key(it) }
                val container = if (SearchScopes.nativeSearch(target)) null else SearchScopes.container(repo.routes, target)
                val checks = page.posts.map { post ->
                    val boundary = SearchScopes.accepts(target, post, container)
                    val telegram = platform != Platform.TELEGRAM || runCatching {
                        URI(post.canonicalUrl).let { it.host == "t.me" && it.path.trim('/').substringBefore('/').equals("durov", true) }
                    }.getOrDefault(false)
                    val readable = post.body.isNotBlank() || post.title.isNotBlank() || post.media.isNotEmpty()
                    mapOf("url" to post.canonicalUrl, "scopeMatch" to (boundary && telegram), "readable" to readable)
                }
                val record = mapOf(
                    "platform" to platform.id, "query" to query, "scope" to scope, "target" to target,
                    "elapsedMs" to android.os.SystemClock.elapsedRealtime() - started, "timedOut" to timedOut,
                    "error" to state.error, "posts" to page.posts.size, "accounts" to page.accounts.size,
                    "notice" to page.notice, "hasMore" to state.searchFeed?.hasMore,
                    "provenance" to page.provenance, "checks" to checks,
                    "connection" to "Direct content, Darwin search routes, public original fallback enabled"
                )
                measurements += record
                File(directory, "${platform.id}-page.json").writeText(gson.toJson(page))
                File(directory, "measurements.json").writeText(gson.toJson(measurements))
                android.util.Log.i("AntiSocialSearchScopeAudit", gson.toJson(record))
                ui.waitForIdle()
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(directory, "${platform.id}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                assertTrue("Returned posts crossed the selected scope: $checks", checks.all { it["scopeMatch"] == true && it["readable"] == true })
                assertTrue(page.posts.all { it.platform == platform })
                ui.runOnIdle { vm.viewModelScope.cancel() }
            }
        } finally {
            ui.runOnIdle {
                models.forEach { it.discussions.close(); it.viewModelScope.cancel() }
                ui.activity.setContent { Text("Scoped search diagnostic complete") }
            }
            ui.waitForIdle()
            runBlocking {
                for (key in touched) {
                    val old = savedHistory[key]
                    if (old != null) repo.dao.putHistory(old)
                    else repo.db.openHelper.writableDatabase.execSQL("DELETE FROM history WHERE `key`=?", arrayOf(key))
                }
            }
            ui.runOnIdle {
                val editor = prefs.edit().clear()
                savedPreferences.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                check(editor.commit())
                repo.applyNetworkSettings(); scheduleRefresh(ui.activity, repo.prefs)
            }
        }
    }
}
