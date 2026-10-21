package io.github.hakunm.deepseekharness.local

/** Consent is a user choice; service availability must never rewrite it. */
data class PhoneReadiness(
    val consent: Boolean,
    val accessibilityEnabled: Boolean,
    val connected: Boolean,
    val permissionAllowed: Boolean,
    val providerSupported: Boolean,
) {
    enum class Issue { PROVIDER, PERMISSION, CONSENT, ACCESSIBILITY_DISABLED, BINDING }

    val issue: Issue? get() = when {
        !providerSupported -> Issue.PROVIDER
        !permissionAllowed -> Issue.PERMISSION
        !consent -> Issue.CONSENT
        !accessibilityEnabled -> Issue.ACCESSIBILITY_DISABLED
        !connected -> Issue.BINDING
        else -> null
    }
    val ready: Boolean get() = issue == null
}
