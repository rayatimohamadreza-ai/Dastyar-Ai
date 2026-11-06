package ir.smmammad.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandRouterTest {
    @Test
    fun parsesGreeting() {
        assertEquals("greeting", VoiceCommandRouter.parse("سلام")?.type)
    }

    @Test
    fun parsesNoteWithPersianSpacing() {
        val action = VoiceCommandRouter.parse("یادداشت کن خرید قطعه")
        assertEquals("save_note", action?.type)
        assertEquals("خرید قطعه", action?.argument)
    }

    @Test
    fun parsesQuestionAfterWakeWord() {
        assertEquals("greeting", VoiceCommandRouter.parse("چه خبر")?.type)
    }

    @Test
    fun normalizesArabicCharactersToPersian() {
        val action = VoiceCommandRouter.parse("يادداشت کن تست")
        assertEquals("save_note", action?.type)
        assertEquals("تست", action?.argument)
    }

    @Test
    fun unknownCommandFallsThroughToAssistant() {
        assertNull(VoiceCommandRouter.parse("برایم درباره مدار تغذیه توضیح بده"))
    }
}
