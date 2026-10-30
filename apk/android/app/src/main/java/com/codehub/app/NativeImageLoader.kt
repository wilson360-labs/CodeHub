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
    // Coil 3's Android request extensions are separate APIs; the view background
    // remains visible while loading and when a catalog image cannot be fetched.
    load(url)
}
