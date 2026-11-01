package com.snapgpt.ai

import com.snapgpt.data.UserSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class JeePromptsTest {
    @Test fun strictJeeModeKeepsSolutionsWithinSyllabus() {
        val prompt = JeePrompts.system(UserSettings(syllabusRestriction = true, examMode = "JEE Advanced"))
        assertTrue(prompt.contains("official JEE Advanced syllabus"))
        assertTrue(prompt.contains("university-level mathematics"))
    }

    @Test fun stageOneRequestsConciseJsonOnly() {
        val prompt = JeePrompts.stageOne(UserSettings(maxQuestions = 5))
        assertTrue(prompt.contains("valid JSON only"))
        assertTrue(prompt.contains("at most 5 questions"))
    }
}
