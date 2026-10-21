package io.github.hakunm.deepseekharness.agent

import android.content.Context
import io.github.hakunm.deepseekharness.R

/** Resource completeness is tested; a stale translation must not kill the service. */
internal fun phoneOverlayActionTitle(context: Context, action: PhoneStatusAction): String =
    context.resources.getStringArray(R.array.phone_overlay_actions).getOrNull(action.ordinal) ?: action.label
