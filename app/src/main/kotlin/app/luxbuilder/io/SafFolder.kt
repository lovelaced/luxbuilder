package app.luxbuilder.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Persisted Storage Access Framework directory for LUT exports.
 *
 * On first export the app uses `OpenDocumentTree` to ask the user for a
 * folder (e.g. `Documents/luxbuilder/`); the resulting URI is granted
 * persistent read/write via `takePersistableUriPermission` and stored in
 * DataStore so we can write subsequent exports without re-prompting.
 */
private val Context.dataStore by preferencesDataStore(name = "saf_folder")
private val FOLDER_KEY = stringPreferencesKey("lut_folder_uri")

object SafFolder {

    fun folderUriFlow(context: Context): Flow<Uri?> =
        context.dataStore.data.map { it[FOLDER_KEY]?.let(Uri::parse) }

    suspend fun setFolder(context: Context, uri: Uri) {
        context.dataStore.edit { it[FOLDER_KEY] = uri.toString() }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some SAF providers don't allow persistence — fall back to per-write grants.
        }
    }

    suspend fun writeFile(
        context: Context,
        folderUri: Uri,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): Uri = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("Could not resolve SAF folder $folderUri")
        // Delete an existing file with the same name (overwrite semantics)
        tree.findFile(filename)?.delete()
        val file = tree.createFile(mimeType, filename)
            ?: error("Could not create $filename in SAF folder")
        context.contentResolver.openOutputStream(file.uri, "w").use { os ->
            requireNotNull(os) { "Could not open OutputStream for ${file.uri}" }
            os.write(bytes)
            os.flush()
        }
        file.uri
    }

    suspend fun currentFolder(context: Context): Uri? = folderUriFlow(context).first()
}
