package app.luxbuilder.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.luxbuilder.color.LutBaker
import app.luxbuilder.state.LuxState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates LUT bake → write → share/save.
 *
 * For `.cube` the natural Lumix Lab path is the system share-sheet (Lab
 * registers as a share target for the file). We write the bytes to a
 * FileProvider-backed cache file and dispatch an `ACTION_SEND` with that URI.
 *
 * For `.vlt` the camera path requires manual SD-card placement under
 * `PRIVATE/PANA_GRP/PAVS/PANA_LUT/`, so we write to the persisted SAF folder
 * the user picked once and let them copy/move from there.
 */
object LutExporter {

    enum class Format(val ext: String, val mime: String) {
        CUBE("cube", "application/octet-stream"),
        VLT ("vlt",  "application/octet-stream"),
    }

    enum class Destination { SAF_FOLDER, SHARE_SHEET }

    sealed interface Result {
        data class Saved(val uri: Uri, val format: Format, val filename: String) : Result
        data class Shared(val format: Format, val filename: String) : Result
        data class NeedsFolderPick(val pendingFormat: Format, val pendingFilename: String) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun export(
        context: Context,
        state: LuxState,
        format: Format,
        filename: String,
        destination: Destination,
    ): Result = withContext(Dispatchers.Default) {
        val safeName = sanitizeFilename(filename, format)
        val bytes = bake(state, format)

        when (destination) {
            Destination.SHARE_SHEET -> {
                if (format != Format.CUBE) {
                    return@withContext Result.Failed(
                        "Lumix Lab only accepts .cube via share-sheet — .vlt must save to a folder for SD card copy."
                    )
                }
                val cacheFile = writeCacheFile(context, safeName, bytes)
                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, cacheFile)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = format.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Send LUT to…")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                Result.Shared(format, safeName)
            }
            Destination.SAF_FOLDER -> {
                val folder = SafFolder.currentFolder(context)
                    ?: return@withContext Result.NeedsFolderPick(format, safeName)
                try {
                    val uri = SafFolder.writeFile(context, folder, safeName, format.mime, bytes)
                    Result.Saved(uri, format, safeName)
                } catch (t: Throwable) {
                    Result.Failed(t.message ?: "Write failed")
                }
            }
        }
    }

    /** Bake the LUT at the right grid size for the chosen format. */
    fun bake(state: LuxState, format: Format): ByteArray {
        return when (format) {
            Format.CUBE -> CubeWriter.write(LutBaker.bake(state, 33), title = null)
            Format.VLT  -> VltWriter.write(LutBaker.bake(state, 17))
        }
    }

    private fun sanitizeFilename(input: String, format: Format): String {
        val base = when (format) {
            Format.VLT -> VltWriter.sanitizeFilename(input)
            Format.CUBE -> input.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                .take(32).ifBlank { "lut" }
        }
        return "$base.${format.ext}"
    }

    private fun writeCacheFile(context: Context, name: String, bytes: ByteArray): File {
        val dir = File(context.cacheDir, "luts").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file
    }
}
