package dev.janakhpon.monocr.ui

import androidx.annotation.StringRes
import org.robolectric.RuntimeEnvironment

/** The app's own string, so a test fails on the real copy rather than on a duplicate literal. */
internal fun str(@StringRes id: Int, vararg args: Any): String =
    RuntimeEnvironment.getApplication().getString(id, *args)
