package io.github.elkelvinx.sashabridge.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which option of the language selector the tags Android keeps stand for. */
class AppLanguageTest {

    @Test
    fun noTagsFollowThePhone() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.of(""))
    }

    @Test
    fun eachLanguageByItsTag() {
        assertEquals(AppLanguage.SPANISH, AppLanguage.of("es"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of("en"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.of("zh-CN"))
    }

    /** A region the app has no texts for still reads as its language; the first tag decides. */
    @Test
    fun regionsAndListsReadAsTheirLanguage() {
        assertEquals(AppLanguage.SPANISH, AppLanguage.of("es-MX"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of("en-GB,es"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.of("zh-Hans-CN"))
    }

    /** A language the app does not speak is no option of the selector. */
    @Test
    fun unknownLanguagesFollowThePhone() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.of("fr"))
    }
}
