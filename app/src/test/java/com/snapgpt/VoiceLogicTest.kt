package com.snapgpt

import com.snapgpt.voice.RecognitionErrors
import com.snapgpt.voice.TranscriptBuffer
import com.snapgpt.voice.VoicePhase
import com.snapgpt.voice.VoiceProblem
import com.snapgpt.voice.VoiceTime
import com.snapgpt.voice.VoiceUiState
import com.snapgpt.voice.WaveformLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLogicTest {

    // ---------------------------------------------------------------- transcript

    @Test fun appendJoinsWithOneSpaceAndIgnoresBlankAdditions() {
        assertEquals("what is  ".trimEnd() + " the derivative", TranscriptBuffer.append("what is  ", "  the derivative "))
        assertEquals("hello", TranscriptBuffer.append("", "  hello "))
        assertEquals("hello ", TranscriptBuffer.append("hello ", "   "))
    }

    @Test fun transcriptIsBoundedToTheMaximumLength() {
        val long = "word ".repeat(2000)
        assertEquals(TranscriptBuffer.MAX_CHARS, TranscriptBuffer.append("", long).length)
        assertTrue(TranscriptBuffer.append("x".repeat(3999), "yyyyyyyy").length <= TranscriptBuffer.MAX_CHARS)
    }

    @Test fun sendNeedsAtLeastOneLetterOrDigit() {
        assertFalse(TranscriptBuffer.isUsable(""))
        assertFalse(TranscriptBuffer.isUsable("   \n "))
        assertFalse(TranscriptBuffer.isUsable("?!..."))
        assertTrue(TranscriptBuffer.isUsable("x"))
        assertTrue(TranscriptBuffer.isUsable("2 + 2"))
    }

    @Test fun finalTextIsExactlyWhatATypedQuestionWouldBe() {
        assertEquals("find the limit", TranscriptBuffer.finalText("  find the limit \n"))
    }

    // ---------------------------------------------------------------- state rules

    @Test fun sendIsDisabledWhileRecordingAndWithoutUsableText() {
        assertFalse(VoiceUiState(phase = VoicePhase.LISTENING, text = "hello").canSend)
        assertFalse(VoiceUiState(phase = VoicePhase.PROCESSING, text = "hello").canSend)
        assertFalse(VoiceUiState(phase = VoicePhase.REVIEW, text = "  ").canSend)
        assertFalse(VoiceUiState(phase = VoicePhase.IDLE).canSend)
        assertTrue(VoiceUiState(phase = VoicePhase.REVIEW, text = "hello").canSend)
        // Text that was recognised before an error can still be sent.
        assertTrue(VoiceUiState(phase = VoicePhase.ERROR, text = "hello").canSend)
    }

    @Test fun displayTextShowsThePartialOnlyWhileListening() {
        assertEquals("what is the", VoiceUiState(phase = VoicePhase.LISTENING, text = "what is", partial = "the").displayText)
        assertEquals("what is", VoiceUiState(phase = VoicePhase.REVIEW, text = "what is", partial = "the").displayText)
    }

    // ---------------------------------------------------------------- waveform

    @Test fun silenceGivesFlatBarsAndLoudSpeechGivesTallBars() {
        assertEquals(0f, WaveformLevels.normalize(-2f), 0f)
        assertEquals(0f, WaveformLevels.normalize(-10f), 0f)
        assertEquals(0f, WaveformLevels.normalize(Float.NaN), 0f)
        assertEquals(1f, WaveformLevels.normalize(10f), 0f)
        assertEquals(1f, WaveformLevels.normalize(30f), 0f)
        assertTrue(WaveformLevels.normalize(7f) > WaveformLevels.normalize(3f))
        assertTrue(WaveformLevels.normalize(3f) > WaveformLevels.normalize(0f))
    }

    @Test fun waveformBarsStayFixedAndGrowSymmetricallyFromTheCentre() {
        val quiet = WaveformLevels.push(emptyList(), 0.25f)
        val loud = WaveformLevels.push(quiet, 0.9f)
        assertEquals(WaveformLevels.BAR_COUNT, loud.size)
        assertEquals(loud.first(), loud.last(), 0f)
        assertEquals(loud[1], loud[loud.lastIndex - 1], 0f)
        assertTrue(loud[loud.size / 2] >= loud[0])
        assertTrue(loud[loud.size / 2] > quiet[quiet.size / 2])
        assertTrue(loud.count { it > 0f } >= quiet.count { it > 0f })
        val p = WaveformLevels.padded(listOf(0.5f, 0.6f))
        assertEquals(WaveformLevels.BAR_COUNT, p.size)
        assertEquals(p[0], p.last(), 0f)
        assertEquals(0.5f, p[15], 0f)
        assertEquals(0.6f, p[16], 0f)
    }

    // ---------------------------------------------------------------- timer

    @Test fun timerFormat() {
        assertEquals("0:00", VoiceTime.format(0))
        assertEquals("0:07", VoiceTime.format(7_400))
        assertEquals("1:05", VoiceTime.format(65_000))
        assertEquals("12:30", VoiceTime.format(750_000))
        assertEquals("0:00", VoiceTime.format(-5))
    }

    // ---------------------------------------------------------------- recognition errors

    @Test fun silenceTimeoutsAreRecoverableAndNotErrorsOfTheService() {
        assertTrue(RecognitionErrors.isSilence(RecognitionErrors.SPEECH_TIMEOUT))
        assertTrue(RecognitionErrors.isSilence(RecognitionErrors.NO_MATCH))
        assertFalse(RecognitionErrors.isSilence(RecognitionErrors.NETWORK))
        val e = RecognitionErrors.map(RecognitionErrors.SPEECH_TIMEOUT)
        assertEquals(VoiceProblem.NO_SPEECH, e.problem); assertTrue(e.retryable)
    }

    @Test fun networkErrorsDoNotClaimEverythingIsOffline() {
        for (code in listOf(1, 2, 4, 10, 11)) {
            val e = RecognitionErrors.map(code)
            assertEquals(VoiceProblem.NETWORK, e.problem)
            assertTrue(e.retryable)
            assertFalse(e.message.contains("offline", ignoreCase = true))
        }
    }

    @Test fun everyDocumentedErrorCodeMapsToAMessage() {
        for (code in 1..13) assertTrue(RecognitionErrors.map(code).message.isNotBlank())
        assertEquals(VoiceProblem.OTHER, RecognitionErrors.map(999).problem)
        assertEquals(VoiceProblem.AUDIO_BUSY, RecognitionErrors.map(RecognitionErrors.AUDIO).problem)
        assertEquals(VoiceProblem.PERMISSION_DENIED, RecognitionErrors.map(RecognitionErrors.INSUFFICIENT_PERMISSIONS).problem)
        assertTrue(RecognitionErrors.isLanguageProblem(12)); assertTrue(RecognitionErrors.isLanguageProblem(13))
    }

    @Test fun unavailableAndPermissionMessagesAreNotRetryableWhereRetryCannotHelp() {
        assertFalse(RecognitionErrors.unavailable.retryable)
        assertFalse(RecognitionErrors.permissionBlocked.retryable)
        assertEquals(VoiceProblem.PERMISSION_BLOCKED, RecognitionErrors.permissionBlocked.problem)
        assertTrue(RecognitionErrors.permissionDenied.retryable)
    }
}
