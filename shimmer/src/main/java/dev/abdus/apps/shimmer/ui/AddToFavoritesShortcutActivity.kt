package dev.abdus.apps.shimmer.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.abdus.apps.shimmer.Actions
import dev.abdus.apps.shimmer.FavoritesRepository
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.R
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.WallpaperUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddToFavoritesShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Shortcut requested add to favorites")

        if (WallpaperUtil.isActiveWallpaper(this)) {
            saveFavoriteFromShortcut()
        } else {
            val intent = Intent(this, SplashActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun saveFavoriteFromShortcut() {
        val repository = ImageFolderRepository(this)
        lifecycleScope.launch {
            val sourceUri = repository.getCurrentImageUri()
            if (sourceUri == null) {
                finish()
                return@launch
            }

            val preferences = WallpaperPreferences.create(this@AddToFavoritesShortcutActivity)
            val favoritesRepo = FavoritesRepository(this@AddToFavoritesShortcutActivity, preferences, repository)
            
            val result = withContext(Dispatchers.IO) { favoritesRepo.saveFavorite(sourceUri) }
            if (result.isSuccess) {
                val saved = result.getOrNull()!!
                Actions.broadcastFavoriteAdded(this@AddToFavoritesShortcutActivity, result = saved)
                Toast.makeText(
                    this@AddToFavoritesShortcutActivity,
                    getString(R.string.toast_favorite_saved, saved.displayName),
                    Toast.LENGTH_SHORT
                ).show()
            }
            finish()
        }
    }

    companion object {
        private const val TAG = "AddFavShortcutAct"
    }
}