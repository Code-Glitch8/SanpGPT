package com.snapgpt

import com.snapgpt.ai.BoundingBox
import com.snapgpt.ai.DetectedQuestion
import com.snapgpt.ai.QuestionTypes
import com.snapgpt.overlay.AnswerFormatter
import com.snapgpt.overlay.BubblePlacer
import com.snapgpt.overlay.BubbleRequest
import com.snapgpt.overlay.CoordinateMapper
import com.snapgpt.overlay.FRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementTest {
    private val usable = FRect(0f, 80f, 1080f, 2300f)

    private fun q(type: String, answer: String, conf: Float = 0.9f, clar: Boolean = false) = DetectedQuestion(
        "q", "text", "Physics", "Kinematics", "t", "JEE Main", type, answer, "numerical", conf, null, clar,
    )

    @Test
    fun fullScreenMappingUsesScreenPixels() {
        val r = CoordinateMapper.toScreen(BoundingBox(0.10f, 0.20f, 0.80f, 0.20f), FRect(0f, 0f, 1080f, 2400f))
        assertEquals(108f, r.left, 0.01f); assertEquals(480f, r.top, 0.01f)
        assertEquals(972f, r.right, 0.01f); assertEquals(960f, r.bottom, 0.01f)
    }

    @Test
    fun areaMappingAddsSelectionOffset() {
        val region = FRect(200f, 600f, 800f, 1000f) // user selected 600 x 400 px
        val r = CoordinateMapper.toScreen(BoundingBox(0.5f, 0.25f, 0.5f, 0.5f), region)
        assertEquals(500f, r.left, 0.01f); assertEquals(700f, r.top, 0.01f)
        assertEquals(800f, r.right, 0.01f); assertEquals(900f, r.bottom, 0.01f)
    }

    @Test
    fun placesBubbleBesideQuestionWhenThereIsRoom() {
        val qr = FRect(40f, 400f, 700f, 700f)
        val p = BubblePlacer.place(listOf(BubbleRequest("a", qr, 120f, 56f)), usable, 8f).single().rect
        assertEquals(0f, p.intersectionArea(qr), 0.001f)
        assertTrue(p.left >= qr.right) // right of the question
    }

    @Test
    fun keepsEverythingInsideScreenAndAvoidsOverlaps() {
        val reqs = (0 until 6).map { i ->
            val top = 150f + i * 110f // tightly stacked questions that span nearly the whole width
            BubbleRequest("q$i", FRect(30f, top, 1040f, top + 100f), 110f, 56f)
        }
        val placed = BubblePlacer.place(reqs, usable, 8f)
        assertEquals(6, placed.size)
        for (b in placed) {
            assertTrue("inside left", b.rect.left >= usable.left)
            assertTrue("inside right", b.rect.right <= usable.right)
            assertTrue("inside top", b.rect.top >= usable.top)
            assertTrue("inside bottom", b.rect.bottom <= usable.bottom)
        }
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            assertEquals("bubbles ${placed[i].id}/${placed[j].id} overlap", 0f, placed[i].rect.intersectionArea(placed[j].rect), 0.001f)
        }
        assertEquals(reqs.map { it.id }, placed.map { it.id }) // order of the request list is preserved
    }

    @Test
    fun questionWithoutBoxIsStackedAtScreenEdge() {
        val placed = BubblePlacer.place(
            listOf(BubbleRequest("a", null, 100f, 50f), BubbleRequest("b", null, 100f, 50f)), usable, 8f,
        )
        assertEquals(0f, placed[0].rect.intersectionArea(placed[1].rect), 0.001f)
        assertTrue(placed.all { it.rect.right <= usable.right })
    }

    @Test
    fun mcqBubbleShowsOptionLetters() {
        assertEquals("B", AnswerFormatter.bubbleText(q(QuestionTypes.MCQ, "B")))
        assertEquals("B", AnswerFormatter.bubbleText(q(QuestionTypes.MCQ, "(B) 42")))
        assertEquals("B", AnswerFormatter.bubbleText(q(QuestionTypes.MCQ, "Option B")))
        assertEquals("A, C", AnswerFormatter.bubbleText(q(QuestionTypes.MCQ_MULTIPLE, "A, C")))
        assertEquals("A, C", AnswerFormatter.bubbleText(q(QuestionTypes.MCQ_MULTIPLE, "AC")))
        assertEquals("2", AnswerFormatter.bubbleText(q(QuestionTypes.MCQ, "2")))
    }

    @Test
    fun numericalAnswersAreKeptAndLongOnesTruncated() {
        assertEquals("9.8 m/s²", AnswerFormatter.bubbleText(q(QuestionTypes.NUMERICAL, "9.8 m/s²")))
        assertEquals("x = 5", AnswerFormatter.bubbleText(q(QuestionTypes.OTHER, "x = 5")))
        val long = AnswerFormatter.bubbleText(q(QuestionTypes.OTHER, "a".repeat(80)))
        assertTrue(long.length <= 26 && long.endsWith("…"))
        assertEquals("?", AnswerFormatter.bubbleText(q(QuestionTypes.NUMERICAL, "  ")))
    }

    @Test
    fun roundVersusPillAndUncertainty() {
        assertTrue(AnswerFormatter.isRound("B"))
        assertTrue(AnswerFormatter.isRound("42"))
        assertFalse(AnswerFormatter.isRound("9.8 m/s²"))
        assertTrue(AnswerFormatter.isUncertain(q(QuestionTypes.MCQ, "A", conf = 0.4f)))
        assertTrue(AnswerFormatter.isUncertain(q(QuestionTypes.MCQ, "A", clar = true)))
        assertFalse(AnswerFormatter.isUncertain(q(QuestionTypes.MCQ, "A", conf = 0.95f)))
    }
}
