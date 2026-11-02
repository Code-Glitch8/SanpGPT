package com.snapgpt

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.AiException
import com.snapgpt.ai.BoxNormalizer
import com.snapgpt.ai.JsonExtract
import com.snapgpt.ai.QuestionTypes
import com.snapgpt.ai.Stage1Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Stage1ParserTest {
    private val numerical = """
        {"questions":[{"id":"q1","question_text":"Calculate the acceleration.","subject":"Physics","chapter":"Kinematics",
        "topic":"Acceleration","exam_level":"JEE Main","question_type":"numerical","answer":"9.8 m/s²","answer_type":"numerical",
        "confidence":0.96,"bounding_box":{"x":0.10,"y":0.20,"width":0.80,"height":0.20},"needs_clarification":false}]}
    """.trimIndent()

    @Test
    fun parsesSpecExample() {
        val r = Stage1Parser.parse(numerical, 5, 1080, 2400)
        assertEquals(1, r.questions.size)
        val q = r.questions[0]
        assertEquals("q1", q.id)
        assertEquals("Physics", q.subject)
        assertEquals("9.8 m/s²", q.answer)
        assertEquals(QuestionTypes.NUMERICAL, q.questionType)
        assertEquals(0.96f, q.confidence, 0.0001f)
        assertEquals(0.10f, q.boundingBox!!.x, 0.0001f)
        assertEquals(0.20f, q.boundingBox!!.height, 0.0001f)
        assertFalse(q.needsClarification)
    }

    @Test
    fun parsesMcqWithOptionsInsideFencesAndThinkBlock() {
        val raw = "<think>let me solve this first {not json}</think>\n```json\n" + """
            {"questions":[{"id":"q1","question_text":"Which is prime?","subject":"maths","question_type":"MCQ single correct",
            "options":[{"label":"A","text":"4"},{"label":"B","text":"7"},"C) 9"],"answer":"B","confidence":0.9,
            "bounding_box":{"x":0.05,"y":0.1,"width":0.9,"height":0.3},"has_figure":false}]}
        """.trimIndent() + "\n```"
        val r = Stage1Parser.parse(raw, 5, 1000, 2000)
        val q = r.questions.single()
        assertEquals("Mathematics", q.subject)
        assertEquals(QuestionTypes.MCQ, q.questionType)
        assertEquals(3, q.options.size)
        assertEquals("C", q.options[2].label)
        assertEquals("9", q.options[2].text)
        assertTrue(q.isMultipleChoice)
    }

    @Test
    fun scalesZeroToThousandPercentAndPixelBoxes() {
        // Qwen-style 0-1000 grid, corner notation.
        val b1 = BoxNormalizer.normalize(100f, 200f, 900f, 400f, true, 1080, 2400)!!
        assertEquals(0.1f, b1.x, 0.001f); assertEquals(0.2f, b1.y, 0.001f)
        assertEquals(0.8f, b1.width, 0.001f); assertEquals(0.2f, b1.height, 0.001f)
        // percent
        val b2 = BoxNormalizer.normalize(10f, 20f, 80f, 20f, false, 1080, 2400)!!
        assertEquals(0.1f, b2.x, 0.001f); assertEquals(0.8f, b2.width, 0.001f)
        // pixels
        val b3 = BoxNormalizer.normalize(108f, 480f, 864f, 960f, false, 1080, 2400)!! // right edge 972, bottom 1440 > 1000 => pixels
        assertEquals(0.1f, b3.x, 0.001f); assertEquals(0.2f, b3.y, 0.001f); assertEquals(0.8f, b3.width, 0.001f); assertEquals(0.4f, b3.height, 0.001f)
    }

    @Test
    fun clampsAndRejectsBadBoxes() {
        val c = BoxNormalizer.normalize(0.9f, 0.9f, 0.5f, 0.5f, false, 1, 1)!!
        assertEquals(0.1f, c.width, 0.0001f)
        assertNull(BoxNormalizer.normalize(0.2f, 0.2f, 0f, 0.3f, false, 1, 1))
        assertNull(BoxNormalizer.normalize(-1f, 0.2f, 0.3f, 0.3f, false, 1, 1))
    }

    @Test
    fun arrayBoxIsTreatedAsCorners() {
        val raw = """{"questions":[{"id":"a","question_text":"t","answer":"1","bounding_box":[0.1,0.2,0.9,0.5]}]}"""
        val b = Stage1Parser.parse(raw, 5, 100, 100).questions[0].boundingBox!!
        assertEquals(0.8f, b.width, 0.0001f)
        assertEquals(0.3f, b.height, 0.0001f)
    }

    @Test
    fun dropsEmptyItemsDedupesIdsAndMarksMissingAnswers() {
        val raw = """{"questions":[
            {"id":"q1","question_text":"A","answer":"1"},
            {"id":"q1","question_text":"B","answer":""},
            {"foo":"bar"},
            "junk"
        ]}"""
        val r = Stage1Parser.parse(raw, 5, 100, 100)
        assertEquals(2, r.questions.size)
        assertEquals(2, r.dropped)
        assertEquals(setOf("q1", "q2"), r.questions.map { it.id }.toSet())
        val unanswered = r.questions.first { it.questionText == "B" }
        assertTrue(unanswered.needsClarification)
        assertEquals("", unanswered.answer) // never invents an answer
        assertNull(unanswered.boundingBox)
    }

    @Test
    fun enforcesMaxQuestionsPerScan() {
        val items = (1..8).joinToString(",") { """{"id":"q$it","question_text":"Q$it","answer":"$it"}""" }
        val r = Stage1Parser.parse("""{"questions":[$items]}""", 3, 100, 100)
        assertEquals(3, r.questions.size)
        assertTrue(r.warnings.any { it.contains("first 3") })
    }

    @Test
    fun emptyListIsNotAnError() {
        assertEquals(0, Stage1Parser.parse("""{"questions": []}""", 5, 10, 10).questions.size)
    }

    @Test
    fun garbageRaisesUnderstandableError() {
        try {
            Stage1Parser.parse("Sorry, I cannot help with that.", 5, 10, 10)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.INVALID_RESPONSE, e.code)
        }
        try {
            Stage1Parser.parse("{\"questions\": [", 5, 10, 10)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorCode.INVALID_RESPONSE, e.code)
        }
    }

    @Test
    fun extractHandlesBracesInsideStrings() {
        val s = JsonExtract.extract("noise {\"a\":\"}{\",\"b\":{\"c\":1}} trailing")
        assertEquals("{\"a\":\"}{\",\"b\":{\"c\":1}}", s)
        assertNotNull(JsonExtract.extract("[1,2,3]"))
        assertNull(JsonExtract.extract("no json at all"))
        assertEquals("answer", JsonExtract.stripThinking("<think>hidden</think>answer"))
        assertEquals("before", JsonExtract.stripThinking("before<think>never closed"))
    }

    @Test
    fun acceptsNumbersAsStringsAndCamelFreeKeys() {
        val raw = """{"questions":[{"question_text":"x","answer":"2","confidence":"0.8","needs_clarification":"true",
            "bounding_box":{"x":"0.1","y":"0.1","width":"0.5","height":"0.2"}}]}"""
        val q = Stage1Parser.parse(raw, 5, 10, 10).questions.single()
        assertEquals(0.8f, q.confidence, 0.0001f)
        assertTrue(q.needsClarification)
        assertEquals(0.5f, q.boundingBox!!.width, 0.0001f)
        assertEquals("q1", q.id)
    }
}
