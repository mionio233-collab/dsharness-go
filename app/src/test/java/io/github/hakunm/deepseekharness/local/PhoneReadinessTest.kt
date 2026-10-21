package io.github.hakunm.deepseekharness.local

import org.junit.Assert.*
import org.junit.Test

class PhoneReadinessTest {
    @Test fun allThirtyTwoCombinationsRequireEveryGate() {
        for (bits in 0 until 32) {
            val state = PhoneReadiness(bits and 1 != 0, bits and 2 != 0,
                bits and 4 != 0, bits and 8 != 0, bits and 16 != 0)
            assertEquals("gates=$bits", bits == 31, state.ready)
            assertEquals(state.ready, state.issue == null)
        }
    }

    @Test fun serviceTransitionsPreserveConsentAndReportDistinctIssues() {
        val ready = PhoneReadiness(true, true, true, true, true)
        val disabled = ready.copy(accessibilityEnabled = false)
        val binding = ready.copy(connected = false)
        assertTrue(disabled.consent)
        assertTrue(binding.consent)
        assertEquals(PhoneReadiness.Issue.ACCESSIBILITY_DISABLED, disabled.issue)
        assertEquals(PhoneReadiness.Issue.BINDING, binding.issue)
        assertEquals(PhoneReadiness.Issue.CONSENT, ready.copy(consent = false).issue)
        assertTrue(binding.copy(connected = true).ready)
    }

    @Test fun providerAndPermissionKeepExistingIssuePriority() {
        assertEquals(PhoneReadiness.Issue.PROVIDER, PhoneReadiness(false, false, false, false, false).issue)
        assertEquals(PhoneReadiness.Issue.PERMISSION, PhoneReadiness(false, false, false, false, true).issue)
    }
}
