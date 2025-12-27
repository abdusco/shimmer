package dev.abdus.apps.shimmer.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.abdus.apps.shimmer.Actions
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.database.ImageEntry
import dev.abdus.apps.shimmer.ui.ShimmerTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class FolderDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val folderId = intent.getLongExtra(EXTRA_FOLDER_ID, -1L)
        val folderName = intent.getStringExtra(EXTRA_FOLDER_NAME) ?: "Images"

        if (folderId == -1L) {
            finish()
            return
        }

        val repo = ImageFolderRepository(this)
        val viewModel = ViewModelProvider(this, FolderDetailViewModelFactory(repo, folderId))[FolderDetailViewModel::class.java]

        setContent {
            ShimmerTheme {
                FolderDetailScreen(
                    title = folderName,
                    viewModel = viewModel,
                    onBackClick = { finish() },
                    onImageClick = { uri ->
                        Actions.broadcastSetWallpaper(this, uri)
                        Toast.makeText(this, "Wallpaper updated", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_FOLDER_ID = "folder_id"
        private const val EXTRA_FOLDER_NAME = "folder_name"

        fun createIntent(context: Context, folderId: Long, folderName: String): Intent {
            return Intent(context, FolderDetailActivity::class.java).apply {
                putExtra(EXTRA_FOLDER_ID, folderId)
                putExtra(EXTRA_FOLDER_NAME, folderName)
            }
        }
    }
}

class FolderDetailViewModel(
    repository: ImageFolderRepository,
    folderId: Long,
) : ViewModel() {
    val images: StateFlow<List<ImageEntry>> = repository.getImagesForFolderFlow(folderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

class FolderDetailViewModelFactory(
    private val repository: ImageFolderRepository,
    private val folderId: Long,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FolderDetailViewModel(repository, folderId) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    title: String,
    viewModel: FolderDetailViewModel,
    onBackClick: () -> Unit,
    onImageClick: (Uri) -> Unit,
) {
    val images by viewModel.images.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalItemSpacing = 16.dp,
            ) {
                items(images, key = { it.uri }, contentType = { "image" }) { entry ->
                    ImageItem(entry = entry, onClick = { onImageClick(entry.uri.toUri()) })
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    "Total images: ${images.size}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ImageItem(entry: ImageEntry, onClick: () -> Unit) {
    var retryHash by remember { mutableStateOf(0) }

    val context = LocalContext.current
    val uri = entry.uri.toUri()
    val aspectRatio = if (entry.width != null && entry.height != null && entry.width > 0 && entry.height > 0) {
        entry.width.toFloat() / entry.height.toFloat()
    } else {
        null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp)
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (aspectRatio != null) {
                        Modifier.aspectRatio(aspectRatio)
                    } else {
                        // Provide a min-height so the grid doesn't collapse
                        Modifier.heightIn(min = 120.dp)
                    },
                )
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    .setParameter("retry", retryHash) // Force cache key change
                    .crossfade(true)
                    .size(400)
                    .precision(coil.size.Precision.INEXACT)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .allowHardware(true)
                    .build(),
                onError = {
                    // Retry after a delay when error occurs
                    retryHash++
                },
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}
