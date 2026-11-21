package com.ali.files.ui

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.ali.files.browser.LongPress
import com.ali.files.download.DownloadRequest

/** App-wide UI state shared between the activity (intents, clipboard) and the screens. */
class UiState {
    val tab = mutableIntStateOf(0)
    val pendingDownload = mutableStateOf<DownloadRequest?>(null)
    val clipLink = mutableStateOf<String?>(null)
    val sharedUris = mutableStateOf<List<Uri>>(emptyList())
    val longPress = mutableStateOf<LongPress?>(null)
    val snackbar = SnackbarHostState()

    companion object {
        const val TAB_BROWSER = 0
        const val TAB_DOWNLOADS = 1
        const val TAB_VAULT = 2
        const val TAB_SETTINGS = 3
    }
}
