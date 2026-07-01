package org.edusharing.app

import android.content.Intent
import android.os.Build
import android.os.Parcelable

/**
 * Back-compatible [Intent.getParcelableExtra]: uses the typed API on Android 13+ and the
 * deprecated overload below it.
 */
inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key) as? T
    }
