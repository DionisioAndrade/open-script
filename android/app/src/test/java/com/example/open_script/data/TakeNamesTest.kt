package com.example.open_script.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TakeNamesTest {
    @Test fun trimsOnlyTheOutsideAndPreservesThePersonsChosenName() {
        assertEquals("Vídeo de apresentação 🎥  02", TakeNames.normalize("  Vídeo de apresentação 🎥  02 \n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyNameCannotReplaceTheExistingTitle() {
        TakeNames.normalize(" \t\n ")
    }

    @Test fun theLimitCountsCharactersInsteadOfSplittingEmojiSurrogatePairs() {
        val name = "🎥".repeat(TakeNames.MAX_LENGTH)
        assertEquals(name, TakeNames.normalize(name))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNameLongerThanTheLimitIsRejectedRatherThanSilentlyTruncated() {
        TakeNames.normalize("a".repeat(TakeNames.MAX_LENGTH + 1))
    }
}
