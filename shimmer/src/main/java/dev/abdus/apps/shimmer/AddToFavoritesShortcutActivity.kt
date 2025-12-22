package dev.abdus.apps.shimmer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
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
        val preferences = WallpaperPreferences.create(this)
        val sourceUri = preferences.getLastImageUri()
        if (sourceUri == null) {
            finish()
            return
        }

        val repository = FavoritesRepository(this, preferences)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.saveFavorite(sourceUri) }
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
