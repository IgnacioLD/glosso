package com.glosso.app.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each course has its own catalogue, so its content is fetched with its own version. */
class CourseSwitchVersionTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Scope(override var language: String) : ContentScope {
        override val accent get() = if (language == "en") "en-GB" else language
        override val authoredAccent get() = accent
        override val uiLanguage = "en"
    }

    private class Store : AssetVersionStore {
        val versions = mutableMapOf<String, String>()
        override fun load(language: String): String? = versions[language]
        override fun save(language: String, version: String) {
            versions[language] = version
        }
    }

    private val served = mapOf("en" to "en-v1", "ru" to "ru-v1", "es" to "es-v1")

    private fun api(scope: Scope, store: Store, drills: MutableList<String>): GlossoApi {
        val engine = MockEngine { request ->
            val language = request.url.parameters["language"].orEmpty()
            when (request.url.encodedPath) {
                "/v1/content/manifest" -> respond("""{"assetVersion":"${served[language]}"}""", headers = json)
                else -> {
                    drills += "$language@${request.url.parameters["v"]}"
                    respond("[]", headers = json)
                }
            }
        }
        return GlossoApi({ "http://test" }, engine, null, store, "", { null }, { false }, scope)
    }

    @Test
    fun theFirstDrillsAfterASwitchCarryTheNewCoursesVersion() = runTest {
        val scope = Scope("en")
        val drills = mutableListOf<String>()
        val api = api(scope, Store(), drills)
        api.manifest()
        api.drills()

        scope.language = "ru"
        api.drills()
        assertEquals("ru-v1", api.contentVersion)

        scope.language = "es"
        api.drills()

        assertEquals(listOf("en@en-v1", "ru@ru-v1", "es@es-v1"), drills)
    }

    @Test
    fun aCourseSeenBeforeUsesItsPersistedVersionAtOnce() = runTest {
        val store = Store().apply { versions["en"] = "en-v1"; versions["ru"] = "ru-v1" }
        val scope = Scope("en")
        val drills = mutableListOf<String>()
        val api = api(scope, store, drills)
        api.drills()

        scope.language = "ru"
        api.drills()

        assertEquals(listOf("en@en-v1", "ru@ru-v1"), drills)
        assertEquals("http://test/assets/content/ru/audio/x.ogg?v=ru-v1", api.assetUrl("audio/x.ogg"))
    }
}
