package com.codehub.app

import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import coil3.load

/** Shared image loading for native catalog cards; supports the PNG/SVG logos from CodeHub. */
fun ImageView.loadCodeHubImage(path: String?) {
    val source = path?.trim().orEmpty()
    val url = when {
        source.startsWith("https://", ignoreCase = true) -> source
        source.startsWith("/") -> "https://wilson360-labs.vercel.app$source"
        source.isNotBlank() -> "https://wilson360-labs.vercel.app/${source.trimStart('/')}"
        else -> null
    }
    if (url == null) {
        setImageDrawable(ColorDrawable(0xFF1D2C2A.toInt()))
        return
    }
    load(url) {
        crossfade(true)
        placeholder(ColorDrawable(0xFF1D2C2A.toInt()))
        error(ColorDrawable(0xFF1D2C2A.toInt()))
    }
}
