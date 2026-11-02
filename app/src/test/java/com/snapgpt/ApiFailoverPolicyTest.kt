package com.snapgpt

import com.snapgpt.ai.AiErrorCode
import com.snapgpt.ai.ApiFailoverPolicy
import com.snapgpt.ai.GroqErrorMapper
import com.snapgpt.settings.ApiAccount
import com.snapgpt.settings.ApiAccountKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFailoverPolicyTest {
    @Test fun quotaAndTokenLimitMoveToNextAccount() {
        assertTrue(ApiFailoverPolicy.shouldTryNext(AiErrorCode.QUOTA))
        assertTrue(ApiFailoverPolicy.shouldTryNext(AiErrorCode.TOKEN_LIMIT))
        assertTrue(ApiFailoverPolicy.shouldTryNext(AiErrorCode.RATE_LIMIT))
        assertTrue(ApiFailoverPolicy.shouldTryNext(AiErrorCode.COOLDOWN))
    }

    @Test fun invalidCredentialsAndTransientFailuresMoveToNextAccount() {
        assertTrue(ApiFailoverPolicy.shouldTryNext(AiErrorCode.INVALID_KEY))
        assertTrue(ApiFailoverPolicy.shouldTryNext(AiErrorCode.NETWORK))
        assertTrue(ApiFailoverPolicy.shouldTryNext(AiErrorCode.SERVER))
        assertTrue(ApiFailoverPolicy.shouldTryNext(AiErrorCode.MODEL_UNAVAILABLE))
    }

    @Test fun malformedRequestsAndLocalImageErrorsDoNotTriggerFailover() {
        assertFalse(ApiFailoverPolicy.shouldTryNext(AiErrorCode.BAD_REQUEST))
        assertFalse(ApiFailoverPolicy.shouldTryNext(AiErrorCode.IMAGE_TOO_LARGE))
        assertFalse(ApiFailoverPolicy.shouldTryNext(AiErrorCode.IMAGE_UNSUPPORTED))
    }

    @Test fun quotaResponseIsClassifiedAsQuota() {
        val error = GroqErrorMapper.map(429, """{"error":{"message":"Insufficient quota: credits exhausted"}}""", null, "vision-model", "Groq key 1")
        assertEquals(AiErrorCode.QUOTA, error.code)
        assertTrue(ApiFailoverPolicy.shouldTryNext(error.code))
    }

    @Test fun billingQuotaCodeWithoutHelpfulMessageStillTriggersFailover() {
        val error = GroqErrorMapper.map(429, """{"error":{"message":"Request limit reached","code":"insufficient_quota"}}""", null, "vision-model", "Provider A")
        assertEquals(AiErrorCode.QUOTA, error.code)
        assertTrue(ApiFailoverPolicy.shouldTryNext(error.code))
    }

    @Test fun customAccountRequiresHttpsAndHasNormalisedBaseUrl() {
        val account = ApiAccount.create("Backup", "https://example.test/v1/", "vision-model", ApiAccountKind.OPENAI_COMPATIBLE)
        assertTrue(account.isConfigurationValid())
        assertEquals("https://example.test/v1", account.normalizedBaseUrl())
        assertFalse(account.copy(baseUrl = "http://example.test/v1").isConfigurationValid())
        assertFalse(account.copy(baseUrl = "https://").isConfigurationValid())
        assertFalse(account.copy(baseUrl = "https://").isConfigurationValid())
    }
}
