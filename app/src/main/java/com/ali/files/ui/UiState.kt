package com.ali.files.ui

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.ali.files.browser.LongPress
import com.ali.files.download.DownloadRequest
import com.ali.files.download.VideoRequest

/** App-wide UI state shared between the activity (intents, clipboard) and the screens. */
class UiState {
    val tab = mutableIntStateOf(0)
    val pendingDownload = mutableStateOf<DownloadRequest?>(null)
    val videoRequest = mutableStateOf<VideoRequest?>(null)
    val clipLink = mutableStateOf<String?>(null)
    val sharedUris = mutableStateOf<List<Uri>>(emptyList())
    val longPress = mutableStateOf<LongPress?>(null)
    val snackbar = SnackbarHostState()
    /** the bottom bar slides away while a list is scrolled down, and comes back on the way up */
    val navHidden = mutableStateOf(false)
    val navScroll = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        private var acc = 0f
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
            val dy = available.y
            if (dy == 0f) return androidx.compose.ui.geometry.Offset.Zero
            if ((dy < 0) != (acc < 0)) acc = 0f           // changed direction: count again
            acc += dy
            if (acc < -48f) navHidden.value = true        // finger moving up = scrolling down the list
            else if (acc > 24f) navHidden.value = false
            return androidx.compose.ui.geometry.Offset.Zero
        }
    }
    /** «check for update» was tapped in settings */
    val updateManual = mutableStateOf(false)

    companion object {
        const val TAB_BROWSER = 0
        const val TAB_DOWNLOADS = 1
        const val TAB_SETTINGS = 3
    }
}
