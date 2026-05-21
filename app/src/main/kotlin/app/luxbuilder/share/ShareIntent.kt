package app.luxbuilder.share

import android.content.Intent
import android.net.Uri
import android.os.Build

fun parseIncomingUris(intent: Intent?): List<Uri> {
    if (intent == null) return emptyList()
    val type = intent.type ?: ""
    val mimeOk = type.startsWith("image/") || type.isEmpty()

    return when (intent.action) {
        Intent.ACTION_VIEW -> intent.data?.takeIf { mimeOk }?.let(::listOf) ?: emptyList()
        Intent.ACTION_SEND -> extraStream(intent)?.takeIf { mimeOk }?.let(::listOf) ?: emptyList()
        Intent.ACTION_SEND_MULTIPLE -> extraStreamList(intent).filter { mimeOk }
        else -> emptyList()
    }
}

@Suppress("DEPRECATION")
private fun extraStream(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    else
        intent.getParcelableExtra(Intent.EXTRA_STREAM)

@Suppress("DEPRECATION")
private fun extraStreamList(intent: Intent): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
