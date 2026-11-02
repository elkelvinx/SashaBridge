package io.github.elkelvinx.sashabridge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The size of the landscape title, given how many lines it takes at each size. */
class TitleFitterTest {

    /** A short title keeps the largest size that fits one line. */
    @Test
    fun theLargestSizeOnOneLine() {
        assertEquals(30f, TitleFitter.sizeFor { 1 })
        assertEquals(26f, TitleFitter.sizeFor { sp -> if (sp <= 26f) 1 else 2 })
    }

    /** One line would need less than 22 sp: two lines, from 24 sp down. */
    @Test
    fun twoLinesWhenOneWouldBeTooSmall() {
        assertEquals(24f, TitleFitter.sizeFor { sp -> if (sp < 22f) 1 else 2 })
        assertEquals(20f, TitleFitter.sizeFor { sp -> if (sp <= 20f) 2 else 3 })
    }

    /** Nothing fits two lines: the smallest size, and the title ends with "...". */
    @Test
    fun theSmallestSizeWhenNothingFits() {
        assertEquals(18f, TitleFitter.sizeFor { 4 })
    }
}
