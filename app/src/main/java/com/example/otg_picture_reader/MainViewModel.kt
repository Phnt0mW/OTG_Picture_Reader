package com.example.otg_picture_reader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.otg_picture_reader.data.local.OtgImageRepository
import com.example.otg_picture_reader.data.model.OtgImage
import com.example.otg_picture_reader.data.model.ScanState
import com.example.otg_picture_reader.util.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val rootUri: String? = null,
    val images: List<OtgImage> = emptyList(),
    val scanState: ScanState = ScanState(),
    val sortDescending: Boolean = true,
    val selectedImage: OtgImage? = null,
    val previewIndex: Int = -1,
    val selectedImageIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val previewError: String? = null
) {
    val selectedCount: Int
        get() = selectedImageIds.size
}

sealed interface MainUiEvent {
    data class ShowMessage(val message: String) : MainUiEvent
    data class ShowDialog(val title: String, val message: String) : MainUiEvent
    data class ShareImage(val uri: Uri) : MainUiEvent
    data class ShareImages(val uris: List<Uri>) : MainUiEvent
}

class MainViewModel(
    application: Application,
    private val repository: OtgImageRepository
) : AndroidViewModel(application) {
    private val sortDescending = MutableStateFlow(true)
    private val previewImageId = MutableStateFlow<String?>(null)
    private val selectedImageIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectionMode = MutableStateFlow(false)
    private val previewError = MutableStateFlow<String?>(null)
    private val _events = MutableSharedFlow<MainUiEvent>()
    val events = _events.asSharedFlow()

    private data class DisplayState(
        val sortedImages: List<OtgImage>,
        val scanState: ScanState,
        val sortDescending: Boolean,
        val selectedImage: OtgImage?,
        val previewIndex: Int,
        val selectedIds: Set<String>,
        val isSelectionMode: Boolean,
        val previewError: String?
    )

    val uiState: StateFlow<MainUiState> = combine(
        repository.images,
        repository.scanState,
        sortDescending,
        previewImageId,
        selectedImageIds
    ) { images: List<OtgImage>,
        scanState: ScanState,
        isDescending: Boolean,
        previewId: String?,
        selectedIds: Set<String> ->
        val sortedImages = if (isDescending) {
            images.sortedByDescending { it.sortTime }
        } else {
            images.sortedBy { it.sortTime }
        }
        val selectedImage = sortedImages.firstOrNull { it.id == previewId }
        DisplayState(
            sortedImages = sortedImages,
            scanState = scanState.copy(indexedCount = sortedImages.size),
            sortDescending = isDescending,
            selectedImage = selectedImage,
            previewIndex = sortedImages.indexOfFirst { it.id == previewId },
            selectedIds = selectedIds,
            isSelectionMode = false,
            previewError = null
        )
    }.combine(selectionMode) { displayState, isSelectionMode ->
        displayState.copy(isSelectionMode = isSelectionMode)
    }.combine(previewError) { displayState, previewErrorMessage ->
        displayState.copy(previewError = previewErrorMessage)
    }.combine(repository.rootUri) { displayState, rootUri ->
        MainUiState(
            rootUri = rootUri,
            images = displayState.sortedImages,
            scanState = displayState.scanState,
            sortDescending = displayState.sortDescending,
            selectedImage = displayState.selectedImage,
            previewIndex = displayState.previewIndex,
            selectedImageIds = displayState.selectedIds,
            isSelectionMode = displayState.isSelectionMode,
            previewError = displayState.previewError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    fun onRootSelected(uri: Uri) {
        AppLog.i("ViewModel", "onRootSelected uri=$uri")
        repository.setRootUri(uri)
        scanImages()
    }

    fun clearRootSelection() {
        AppLog.i("ViewModel", "clearRootSelection")
        previewImageId.value = null
        selectedImageIds.value = emptySet()
        selectionMode.value = false
        previewError.value = null
        repository.clearRootUri()
    }

    fun scanImages() {
        AppLog.i("ViewModel", "scanImages")
        repository.scanImages()
    }

    fun hasMtpDeviceAccess(): Boolean {
        return repository.hasMtpDeviceAccess()
    }

    fun scanMtpImages() {
        AppLog.i("ViewModel", "scanMtpImages")
        repository.scanMtpImages()
    }

    fun stopCameraScan() {
        AppLog.i("ViewModel", "stopCameraScan")
        repository.stopCameraScan()
    }

    fun toggleSortOrder() {
        AppLog.i("ViewModel", "toggleSortOrder current=${sortDescending.value}")
        sortDescending.value = !sortDescending.value
    }

    fun openImage(imageId: String) {
        AppLog.i("ViewModel", "openImage imageId=$imageId currentCount=${uiState.value.images.size}")
        if (uiState.value.images.none { it.id == imageId }) {
            previewError.value = "无法打开图片：列表中找不到该项目"
            viewModelScope.launch {
                _events.emit(
                    MainUiEvent.ShowDialog(
                        title = "打开失败",
                        message = previewError.value ?: "无法打开图片"
                    )
                )
            }
            AppLog.w("ViewModel", "openImage rejected: image not found id=$imageId")
            return
        }
        AppLog.i("ViewModel", "openImage accepted imageId=$imageId")
        previewError.value = null
        previewImageId.value = imageId
    }

    fun openImageAt(index: Int) {
        AppLog.i("ViewModel", "openImageAt index=$index currentCount=${uiState.value.images.size}")
        val image = uiState.value.images.getOrNull(index)
        if (image == null) {
            previewError.value = "无法打开图片：索引超出范围"
            viewModelScope.launch {
                _events.emit(
                    MainUiEvent.ShowDialog(
                        title = "打开失败",
                        message = previewError.value ?: "无法打开图片"
                    )
                )
            }
            AppLog.w("ViewModel", "openImageAt rejected: index out of bounds index=$index size=${uiState.value.images.size}")
            return
        }
        AppLog.i("ViewModel", "openImageAt accepted index=$index imageId=${image.id}")
        previewError.value = null
        previewImageId.value = image.id
    }

    fun closePreview() {
        AppLog.i("ViewModel", "closePreview imageId=${uiState.value.selectedImage?.id}")
        previewImageId.value = null
        previewError.value = null
    }

    fun toggleImageSelection(imageId: String) {
        AppLog.d("ViewModel", "toggleImageSelection imageId=$imageId")
        selectionMode.value = true
        selectedImageIds.value = selectedImageIds.value.toMutableSet().apply {
            if (!add(imageId)) {
                remove(imageId)
            }
        }
    }

    fun clearSelection() {
        AppLog.d("ViewModel", "clearSelection")
        selectedImageIds.value = emptySet()
        selectionMode.value = false
    }

    fun enterSelectionMode() {
        AppLog.d("ViewModel", "enterSelectionMode")
        selectionMode.value = true
    }

    fun toggleSelectionFromPreview() {
        val imageId = uiState.value.selectedImage?.id ?: run {
            AppLog.w("ViewModel", "toggleSelectionFromPreview ignored: selectedImage is null")
            return
        }
        AppLog.d("ViewModel", "toggleSelectionFromPreview imageId=$imageId")
        toggleImageSelection(imageId)
    }

    fun saveSelectedImage() {
        val image = uiState.value.selectedImage ?: run {
            AppLog.w("ViewModel", "saveSelectedImage ignored: selectedImage is null")
            viewModelScope.launch {
                _events.emit(MainUiEvent.ShowDialog("保存失败", "当前没有可保存的大图"))
            }
            return
        }
        AppLog.i("ViewModel", "saveSelectedImage imageId=${image.id}")
        viewModelScope.launch {
            repository.saveToGallery(image)
                .onSuccess { message ->
                    AppLog.i("ViewModel", "saveSelectedImage success imageId=${image.id} message=$message")
                    _events.emit(MainUiEvent.ShowMessage(message))
                }
                .onFailure {
                    AppLog.e("ViewModel", "saveSelectedImage failed imageId=${image.id}", it)
                    _events.emit(MainUiEvent.ShowMessage("保存失败：${it.message ?: "请重试"}"))
                }
        }
    }

    fun saveSelectedImages() {
        val selected = currentSelectedImages()
        if (selected.isEmpty()) {
            AppLog.w("ViewModel", "saveSelectedImages ignored: no selected images")
            viewModelScope.launch {
                _events.emit(MainUiEvent.ShowDialog("批量保存失败", "当前没有选中的图片"))
            }
            return
        }
        AppLog.i("ViewModel", "saveSelectedImages count=${selected.size}")
        viewModelScope.launch {
            repository.saveManyToGallery(selected)
                .onSuccess { message ->
                    AppLog.i("ViewModel", "saveSelectedImages success count=${selected.size} message=$message")
                    _events.emit(MainUiEvent.ShowMessage(message))
                }
                .onFailure {
                    AppLog.e("ViewModel", "saveSelectedImages failed count=${selected.size}", it)
                    _events.emit(MainUiEvent.ShowMessage("批量保存失败：${it.message ?: "请重试"}"))
                }
        }
    }

    fun shareSelectedImage() {
        val image = uiState.value.selectedImage ?: run {
            AppLog.w("ViewModel", "shareSelectedImage ignored: selectedImage is null")
            viewModelScope.launch {
                _events.emit(MainUiEvent.ShowDialog("分享失败", "当前没有可分享的大图"))
            }
            return
        }
        AppLog.i("ViewModel", "shareSelectedImage imageId=${image.id}")
        viewModelScope.launch {
            repository.prepareShareUri(image)
                .onSuccess { uri ->
                    AppLog.i("ViewModel", "shareSelectedImage prepared uri=$uri")
                    _events.emit(MainUiEvent.ShareImage(uri))
                }
                .onFailure {
                    AppLog.e("ViewModel", "shareSelectedImage failed imageId=${image.id}", it)
                    _events.emit(MainUiEvent.ShowMessage("分享失败：${it.message ?: "请重试"}"))
                }
        }
    }

    fun shareSelectedImages() {
        val selected = currentSelectedImages()
        if (selected.isEmpty()) {
            AppLog.w("ViewModel", "shareSelectedImages ignored: no selected images")
            viewModelScope.launch {
                _events.emit(MainUiEvent.ShowDialog("批量分享失败", "当前没有选中的图片"))
            }
            return
        }
        AppLog.i("ViewModel", "shareSelectedImages count=${selected.size}")
        viewModelScope.launch {
            repository.prepareShareUris(selected)
                .onSuccess { uris ->
                    AppLog.i("ViewModel", "shareSelectedImages prepared count=${uris.size}")
                    _events.emit(MainUiEvent.ShareImages(uris))
                }
                .onFailure {
                    AppLog.e("ViewModel", "shareSelectedImages failed count=${selected.size}", it)
                    _events.emit(MainUiEvent.ShowMessage("批量分享失败：${it.message ?: "请重试"}"))
                }
        }
    }

    private fun currentSelectedImages(): List<OtgImage> {
        val ids = uiState.value.selectedImageIds
        return uiState.value.images.filter { it.id in ids }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory {
            val app = application as OtgPictureReaderApp
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(
                        application = application,
                        repository = app.appContainer.imageRepository
                    ) as T
                }
            }
        }

        fun buildShareIntent(uri: Uri): Intent {
            return Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        fun buildShareMultipleIntent(uris: List<Uri>): Intent {
            return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
