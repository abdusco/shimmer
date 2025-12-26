package dev.abdus.apps.shimmer.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
fun rememberFolderPicker(): FolderPicker {
    val context = LocalContext.current
    val picker = remember { FolderPickerImpl() }
    picker.launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        picker.onResult(uri)
    }
    return picker
}

interface FolderPicker {
    suspend fun pick(): Uri?
}

private class FolderPickerImpl : FolderPicker {
    lateinit var launcher: androidx.activity.result.ActivityResultLauncher<Uri?>
    private var continuation: CancellableContinuation<Uri?>? = null

    fun onResult(uri: Uri?) {
        continuation?.resume(uri)
        continuation = null
    }

    override suspend fun pick(): Uri? = suspendCancellableCoroutine { cont ->
        continuation = cont
        launcher.launch(null)
        cont.invokeOnCancellation { continuation = null }
    }
}
