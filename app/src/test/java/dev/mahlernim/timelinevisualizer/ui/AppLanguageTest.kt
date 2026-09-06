package dev.mahlernim.timelinevisualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun mapsSupportedLanguageTagsToVisibleSelectionIndexes() {
        assertEquals(0, AppLanguage.selectionIndex("en-US"))
        assertEquals(1, AppLanguage.selectionIndex("ko"))
        assertEquals(3, AppLanguage.selectionIndex("zh-Hans-CN"))
        assertEquals(4, AppLanguage.selectionIndex("zh-Hant-TW"))
        assertEquals(8, AppLanguage.selectionIndex("pt-BR"))
        assertEquals(9, AppLanguage.selectionIndex("in-ID"))
        assertEquals(9, AppLanguage.selectionIndex("id-ID"))
        assertEquals(10, AppLanguage.selectionIndex("vi-VN"))
    }

    @Test
    fun emptyOrUnsupportedLanguageUsesTheResolvedDeviceLanguageOrEnglish() {
        assertEquals(1, AppLanguage.selectionIndex("", "ko-KR"))
        assertEquals(9, AppLanguage.selectionIndex("", "in-ID"))
        assertEquals(0, AppLanguage.selectionIndex("", "it-IT"))
        assertEquals(0, AppLanguage.selectionIndex("it-IT"))
        assertEquals(0, AppLanguage.selectionIndex("pt-PT"))
        assertEquals(0, AppLanguage.selectionIndex("invalid"))
    }

    @Test
    fun everySelectionProducesTheExpectedLocaleList() {
        AppLanguage.supportedTags.forEachIndexed { index, tag ->
            assertEquals(tag, AppLanguage.localesForSelection(index).toLanguageTags())
        }
    }
}
