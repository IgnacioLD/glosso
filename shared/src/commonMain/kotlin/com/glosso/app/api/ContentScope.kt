package com.glosso.app.api

/**
 * The learner's choices that scope every content request, read at call time so
 * a change of language or accent applies to the next request.
 */
interface ContentScope {
    /** The target language code every request and asset URL is scoped to. */
    val language: String

    /** The accent the learner is mastering, which picks the content's wording. */
    val accent: String

    /** The accent the language's bank is written in. */
    val authoredAccent: String

    /** The interface language, which picks the translation of lesson text. */
    val uiLanguage: String
}
