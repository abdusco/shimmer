package dev.abdus.apps.shimmer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.SharedImagesRepository
import dev.abdus.apps.shimmer.WallpaperPreferences
import kotlinx.coroutines.launch

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val action = intent.action
        val type = intent.type

        if (type?.startsWith("image/") == true) {
            when (action) {
                Intent.ACTION_SEND -> {
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        saveSharedImages(listOf(uri))
                    } else {
                        finish()
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uris != null) {
                        saveSharedImages(uris)
                    } else {
                        finish()
                    }
                }
                else -> finish()
            }
        } else {
            finish()
        }
    }

    private fun saveSharedImages(uris: List<Uri>) {
        val folderRepo = ImageFolderRepository(this, lifecycleScope)
        val preferences = WallpaperPreferences.create(this)
        val shareRepo = SharedImagesRepository(this, folderRepo, preferences)
        
        lifecycleScope.launch {
            val result = shareRepo.saveSharedImages(uris)
            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                val message = if (count == 1) "Added 1 image" else "Added $count images"
                Toast.makeText(this@ShareActivity, message, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ShareActivity, "Failed to add shared images", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
