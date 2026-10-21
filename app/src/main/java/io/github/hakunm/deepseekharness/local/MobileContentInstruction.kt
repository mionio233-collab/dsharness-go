package io.github.hakunm.deepseekharness.local

/** Shared by every local text API entry point, including headless scheduled runs. */
internal const val MOBILE_CONTENT_INSTRUCTION = "When generating web pages, code, or visual content, design mobile-first for the Android phone viewing it. Use the actual CSS viewport width at runtime (window.innerWidth or responsive CSS), never assume a desktop width. HTML must include <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">. Use flexible layouts, responsive images, readable text, touch-sized controls, and touch/keyboard interaction; do not rely on hover alone or cause horizontal overflow. Adapt to orientation changes and safe areas. This presentation guidance grants no tools, file access, network access, or phone permissions. Model IDs are provider-defined and need not mention Android."

internal fun mobileContentMessages(messages: List<LocalMessage>): List<LocalMessage> =
    listOf(LocalMessage("system", MOBILE_CONTENT_INSTRUCTION)) + messages

