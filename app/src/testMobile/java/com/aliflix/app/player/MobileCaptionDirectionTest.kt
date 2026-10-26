package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import java.text.Bidi

class MobileCaptionDirectionTest {
    @Test fun arabicNeutralPunctuationAndMixedNamesHaveAnRtlParagraphWithoutReversingText() {
        for (line in listOf("هذه نهاية الجملة.", "- قال John: مرحباً!", "(هل أنت هنا؟)", "123، هذا صحيح.")) {
            val rendered = mobileCaptionText(line)
            assertTrue(rendered.startsWith("\u202b"))
            assertEquals(line, rendered.removePrefix("\u202b").removeSuffix("\u202c"))
            assertEquals(1, Bidi(rendered, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).getLevelAt(rendered.indexOf('.').takeIf { it >= 0 } ?: 1).toInt() % 2)
            assertEquals(rendered, mobileCaptionText(rendered))
        }
        assertEquals("English sentence.", mobileCaptionText("English sentence."))
    }
    @Test fun eachArabicLineKeepsItsOwnParagraphDirection() {
        assertEquals("\u202bأهلاً.\u202c\nEnglish.\n\u202bمرحباً!\u202c", mobileCaptionText("أهلاً.\nEnglish.\nمرحباً!"))
    }
}
