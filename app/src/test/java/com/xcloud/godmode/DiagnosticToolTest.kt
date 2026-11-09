package com.xcloud.godmode

import com.xcloud.godmode.network.CloudSessionDiagnosticTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticToolTest {

    @Test
    fun testNoEntitlementCapture() {
        val rawResponse = """
            {
                "authStatusCode": "NoEntitlement",
                "code": "BadRequest",
                "message": "User is not allowed to play title THELEGENDOFTIANDING. Auth Status Code: NoEntitlement",
                "correlationId": "test-correlation-123"
            }
        """.trimIndent()

        val report = CloudSessionDiagnosticTool.captureAndLogSessionFailure(
            httpStatusCode = 400,
            requestUrl = "https://uks.core.gssv-play-prod.xboxlive.com/v5/sessions/cloud/play",
            requestedTitleId = "THELEGENDOFTIANDING",
            sentTitleId = "THELEGENDOFTIANDING",
            offeringId = "xgpuweb",
            region = "BrazilSouth",
            rawResponseBody = rawResponse,
            correlationId = "header-corr-id"
        )

        assertEquals("NoEntitlement", report.authStatusCode)
        assertEquals(400, report.httpStatusCode)
        assertEquals("header-corr-id", report.correlationId)
        assertTrue(report.formattedDiagnosticLog.contains("[!] Se envió un nombre/slug ('THELEGENDOFTIANDING')"))
    }

    @Test
    fun testRecursiveJsonExtraction() {
        val json = JSONObject("""
            {
                "root": {
                    "nested": {
                        "target": "found_me"
                    }
                }
            }
        """)
        
        val value = CloudSessionDiagnosticTool.findStringRecursively(json, "target")
        assertEquals("found_me", value)
    }

    @Test
    fun testExtractionPriority() {
        val json = JSONObject("""
            {
                "code": "GenericCode",
                "authStatusCode": "SpecificStatus"
            }
        """)
        
        // When searching for multiple keys, it should respect order or we test specific logic
        val authStatus = CloudSessionDiagnosticTool.findStringRecursively(json, "authStatusCode", "code")
        assertEquals("SpecificStatus", authStatus)
    }
}
