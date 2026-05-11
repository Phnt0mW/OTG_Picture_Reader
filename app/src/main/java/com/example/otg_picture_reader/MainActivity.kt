package com.example.otg_picture_reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.otg_picture_reader.data.model.ImageExifInfo
import com.example.otg_picture_reader.data.model.OtgImage
import com.example.otg_picture_reader.data.model.ScanState
import com.example.otg_picture_reader.ui.theme.OTG_Picture_ReaderTheme
import com.example.otg_picture_reader.util.AppLog
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

private data class AppDialogState(
    val title: String,
    val message: String
)

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OTG_Picture_ReaderTheme {
                OtgPictureReaderApp(
                    viewModel = viewModel,
                    onShareImage = ::shareImage,
                    onShareImages = ::shareImages
                )
            }
        }
    }

    private fun shareImage(uri: Uri) {
        AppLog.i("Activity", "shareImage uri=$uri")
        startActivity(Intent.createChooser(MainViewModel.buildShareIntent(uri), "导入到其他应用"))
    }

    private fun shareImages(uris: List<Uri>) {
        AppLog.i("Activity", "shareImages count=${uris.size}")
        startActivity(Intent.createChooser(MainViewModel.buildShareMultipleIntent(uris), "批量导入到其他应用"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtgPictureReaderApp(
    viewModel: MainViewModel,
    onShareImage: (Uri) -> Unit,
    onShareImages: (List<Uri>) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var activeDialog by remember { mutableStateOf<AppDialogState?>(null) }
    var showLogLocationPrompt by rememberSaveable {
        mutableStateOf(AppLog.shouldPromptForLogLocation(context))
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            AppLog.w("UI", "treePicker cancelled")
            return@rememberLauncherForActivityResult
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        AppLog.i("UI", "treePicker selected uri=$uri")
        viewModel.onRootSelected(uri)
    }
    val logDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            AppLog.w("UI", "logDirectoryPicker cancelled")
            return@rememberLauncherForActivityResult
        }
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, readWriteFlags)
        } catch (_: SecurityException) {
        }
        AppLog.useExternalDirectory(context, uri)
            .onSuccess { path ->
                showLogLocationPrompt = false
                AppLog.i("UI", "log directory selected path=$path")
                scope.launch {
                    snackbarHostState.showSnackbar("日志将写入：$path")
                }
            }
            .onFailure {
                AppLog.e("UI", "failed to set log directory", it)
                scope.launch {
                    snackbarHostState.showSnackbar("日志目录设置失败：${it.message ?: "请重试"}")
                }
            }
    }

    LaunchedEffect(Unit) {
        AppLog.i("UI", "OtgPictureReaderApp started")
        AppLog.consumePendingCrashReport(context)?.let { report ->
            AppLog.w("UI", "showing pending crash report")
            activeDialog = AppDialogState(
                title = "上次启动发生了崩溃",
                message = report
            )
        }
        viewModel.events.collect { event ->
            when (event) {
                is MainUiEvent.ShowMessage -> {
                    AppLog.i("UI", "ShowMessage message=${event.message}")
                    snackbarHostState.showSnackbar(event.message)
                }
                is MainUiEvent.ShowDialog -> {
                    AppLog.w("UI", "ShowDialog title=${event.title} message=${event.message}")
                    activeDialog = AppDialogState(event.title, event.message)
                }
                is MainUiEvent.ShareImage -> {
                    AppLog.i("UI", "ShareImage uri=${event.uri}")
                    onShareImage(event.uri)
                }
                is MainUiEvent.ShareImages -> {
                    AppLog.i("UI", "ShareImages count=${event.uris.size}")
                    onShareImages(event.uris)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when {
                            uiState.selectedImage != null -> "${uiState.previewIndex + 1}/${uiState.images.size}"
                            uiState.isSelectionMode -> "已选择 ${uiState.selectedCount} 张"
                            else -> "OTG 图片浏览"
                        }
                    )
                },
                navigationIcon = {
                    when {
                        uiState.selectedImage != null -> {
                            TextButton(onClick = viewModel::closePreview) { Text("返回") }
                        }

                        uiState.isSelectionMode -> {
                            TextButton(onClick = viewModel::clearSelection) { Text("取消") }
                        }
                    }
                },
                actions = {
                    if (uiState.selectedImage == null) {
                        TextButton(onClick = { showLogLocationPrompt = true }) {
                            Text("日志")
                        }
                        TextButton(onClick = { treePicker.launch(null) }) {
                            Text("选择 OTG")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        val selectedImage = uiState.selectedImage
        if (selectedImage != null) {
            PreviewScreen(
                state = uiState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onClose = viewModel::closePreview,
                onOpenIndex = viewModel::openImageAt,
                onToggleCurrentSelection = viewModel::toggleSelectionFromPreview,
                onSaveCurrent = viewModel::saveSelectedImage,
                onShareCurrent = viewModel::shareSelectedImage,
                onSaveSelected = viewModel::saveSelectedImages,
                onShareSelected = viewModel::shareSelectedImages,
                onClearSelection = viewModel::clearSelection,
                onReportError = { message -> activeDialog = AppDialogState("预览异常", message) }
            )
        } else {
            GalleryScreen(
                state = uiState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onPickRoot = { treePicker.launch(null) },
                onRescan = viewModel::scanImages,
                onToggleSort = viewModel::toggleSortOrder,
                onImageClick = { image ->
                    AppLog.d("UI", "gallery image click id=${image.id} selectionMode=${uiState.isSelectionMode}")
                    viewModel.openImage(image.id)
                },
                onImageLongClick = { image ->
                    AppLog.d("UI", "gallery image longClick id=${image.id} selectionMode=${uiState.isSelectionMode}")
                    if (!uiState.isSelectionMode) {
                        viewModel.selectOnly(image.id)
                    } else {
                        viewModel.toggleImageSelection(image.id)
                    }
                },
                onClearRoot = {
                    AppLog.i("UI", "clearRoot clicked")
                    viewModel.clearRootSelection()
                    scope.launch {
                        snackbarHostState.showSnackbar("已清除当前 OTG 目录缓存")
                    }
                },
                onSaveSelected = viewModel::saveSelectedImages,
                onShareSelected = viewModel::shareSelectedImages,
                onClearSelection = viewModel::clearSelection
            )
        }
    }

    if (showLogLocationPrompt) {
        LogLocationDialog(
            onUseInternal = {
                val path = AppLog.useInternalDirectory(context)
                showLogLocationPrompt = false
                AppLog.i("UI", "log directory fallback to internal path=$path")
                scope.launch {
                    snackbarHostState.showSnackbar("日志将写入应用内部目录")
                }
            },
            onChooseDirectory = { logDirectoryPicker.launch(null) },
            onDismiss = { showLogLocationPrompt = false }
        )
    }

    activeDialog?.let { dialog ->
        AppDialog(
            title = dialog.title,
            message = dialog.message,
            onDismiss = { activeDialog = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun GalleryScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onPickRoot: () -> Unit,
    onRescan: () -> Unit,
    onToggleSort: () -> Unit,
    onImageClick: (OtgImage) -> Unit,
    onImageLongClick: (OtgImage) -> Unit,
    onClearRoot: () -> Unit,
    onSaveSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onClearSelection: () -> Unit
) {
    if (state.rootUri == null) {
        AppLog.w("UI", "gallery return: rootUri is null")
        EmptyState(
            modifier = modifier,
            title = "还没有连接到 OTG 目录",
            description = "先选择 OTG 设备目录，应用会扫描全部图片并优先生成缩略图。",
            actionLabel = "选择 OTG 目录",
            onAction = onPickRoot
        )
        return
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StatusCard(
            rootUri = state.rootUri,
            scanState = state.scanState,
            sortDescending = state.sortDescending,
            isSelectionMode = state.isSelectionMode,
            selectedCount = state.selectedCount,
            onRescan = onRescan,
            onToggleSort = onToggleSort,
            onClearRoot = onClearRoot,
            onSaveSelected = onSaveSelected,
            onShareSelected = onShareSelected,
            onClearSelection = onClearSelection
        )

        if (state.images.isEmpty() && !state.scanState.isScanning) {
            AppLog.w("UI", "gallery return: images empty and not scanning")
            EmptyState(
                modifier = Modifier.fillMaxSize(),
                title = "还没有图片",
                description = "当前目录还没扫到图片。你可以重新扫描，或者换一个 OTG 根目录。",
                actionLabel = "重新扫描",
                onAction = onRescan
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 132.dp),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.images, key = { it.id }) { image ->
                    ImageCard(
                        image = image,
                        isSelected = image.id in state.selectedImageIds,
                        selectionMode = state.isSelectionMode,
                        onClick = { onImageClick(image) },
                        onLongClick = { onImageLongClick(image) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    rootUri: String,
    scanState: ScanState,
    sortDescending: Boolean,
    isSelectionMode: Boolean,
    selectedCount: Int,
    onRescan: () -> Unit,
    onToggleSort: () -> Unit,
    onClearRoot: () -> Unit,
    onSaveSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onClearSelection: () -> Unit
) {
    val context = LocalContext.current
    val rootName = remember(rootUri) {
        DocumentFile.fromTreeUri(context, Uri.parse(rootUri))?.name ?: "OTG 根目录"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = rootName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (isSelectionMode) "当前已选择 $selectedCount 张图片" else scanState.statusMessage,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "已遍历 ${scanState.scannedCount} 个文件，图片 ${scanState.indexedCount} 张",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (scanState.isScanning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在生成索引和缩略图", style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = onToggleSort,
                    label = { Text(if (sortDescending) "时间: 最新在前" else "时间: 最早在前") }
                )
                AssistChip(onClick = onRescan, label = { Text("重新扫描") })
                AssistChip(onClick = onClearRoot, label = { Text("清除目录") })
                if (isSelectionMode) {
                    AssistChip(onClick = onSaveSelected, label = { Text("批量保存") })
                    AssistChip(onClick = onShareSelected, label = { Text("批量分享") })
                    AssistChip(onClick = onClearSelection, label = { Text("清空选择") })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageCard(
    image: OtgImage,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column {
            Box {
                val model = image.thumbnailPath?.let { File(it) } ?: Uri.parse(image.documentUri)
                AsyncImage(
                    model = model,
                    contentDescription = image.displayName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
                if (selectionMode) {
                    SelectionBadge(
                        selected = isSelected,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = image.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatTime(image.sortTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatSize(image.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onOpenIndex: (Int) -> Unit,
    onToggleCurrentSelection: () -> Unit,
    onSaveCurrent: () -> Unit,
    onShareCurrent: () -> Unit,
    onSaveSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onClearSelection: () -> Unit,
    onReportError: (String) -> Unit
) {
    AppLog.d("Preview", "enter preview size=${state.images.size} previewIndex=${state.previewIndex}")
    if (state.images.isEmpty()) {
        AppLog.w("Preview", "return to fallback: images empty")
        PreviewUnavailableState(
            modifier = modifier,
            onClose = onClose,
            message = "预览页无法打开：当前没有可显示的图片",
            onReport = onReportError
        )
        return
    }

    val safeLastIndex = state.images.lastIndex
    val initialPage = state.previewIndex.takeIf { it in 0..safeLastIndex } ?: 0
    val pagerState = rememberPagerState(initialPage = initialPage) { state.images.size }

    LaunchedEffect(pagerState, state.images.size) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page in state.images.indices && page != state.previewIndex) {
                    onOpenIndex(page)
                }
            }
    }

    LaunchedEffect(state.selectedImage?.id) {
        if (state.previewIndex in state.images.indices && pagerState.currentPage != state.previewIndex) {
            pagerState.scrollToPage(state.previewIndex)
        }
    }

    val currentImage = state.images.getOrNull(pagerState.currentPage) ?: run {
        AppLog.w("Preview", "currentImage missing currentPage=${pagerState.currentPage} size=${state.images.size}")
        PreviewUnavailableState(
            modifier = modifier,
            onClose = onClose,
            message = "预览页暂时无法定位当前图片，请返回后重新打开",
            onReport = onReportError
        )
        return
    }
    val currentSelected = currentImage.id in state.selectedImageIds
    val bottomSheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val bottomSheetScaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = bottomSheetState)

    BottomSheetScaffold(
        scaffoldState = bottomSheetScaffoldState,
        modifier = modifier.background(Color(0xFF0D1117)),
        sheetPeekHeight = 76.dp,
        sheetShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetDragHandle = {
            BottomSheetDefaults.DragHandle(
                width = 34.dp,
                height = 4.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        },
        sheetContent = {
            PreviewDetailPanel(
                image = currentImage,
                isSelected = currentSelected,
                selectionMode = state.isSelectionMode,
                selectionCount = state.selectedCount,
                onToggleCurrentSelection = onToggleCurrentSelection,
                onSaveCurrent = onSaveCurrent,
                onShareCurrent = onShareCurrent,
                onSaveSelected = onSaveSelected,
                onShareSelected = onShareSelected
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                state.images.getOrNull(page)?.let { image ->
                    AppLog.d("Preview", "compose page=$page imageId=${image.id}")
                    ZoomableImage(
                        image = image,
                        modifier = Modifier.fillMaxSize(),
                        onError = { message -> onReportError(message) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onClose) { Text("返回", color = Color.White) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isSelectionMode) {
                        TextButton(onClick = onClearSelection) { Text("清空", color = Color.White) }
                    }
                    TextButton(onClick = onToggleCurrentSelection) {
                        Text(if (currentSelected) "取消选择" else "选择", color = Color.White)
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 86.dp),
                color = Color.Black.copy(alpha = 0.42f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = "${pagerState.currentPage + 1}/${state.images.size}",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    image: OtgImage,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val imageUri = remember(image.documentUri) { Uri.parse(image.documentUri) }
    val imageRequest = remember(context, imageUri) {
        ImageRequest.Builder(context)
            .data(imageUri)
            .allowHardware(false)
            .crossfade(false)
            .build()
    }
    var scale by rememberSaveable(image.id) { mutableFloatStateOf(1f) }
    var offset by remember(image.id) { mutableStateOf(Offset.Zero) }
    AppLog.d("Preview", "ZoomableImage model imageId=${image.id} uri=$imageUri")

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(image.id, scale) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()

                        if (pressedPointers > 1 || scale > 1f) {
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = nextScale
                            offset = if (nextScale == 1f) {
                                Offset.Zero
                            } else {
                                offset + pan
                            }
                            event.changes.forEach { change -> change.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = image.displayName,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
            onError = { state ->
                AppLog.e(
                    "Preview",
                    "image load error imageId=${image.id} message=${state.result.throwable.message ?: "unknown"}",
                    state.result.throwable
                )
                onError("图片加载失败：${image.displayName}")
            }
        )
    }
}
@Composable
private fun PreviewUnavailableState(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    message: String,
    onReport: (String) -> Unit
) {
    AppLog.w("Preview", "unavailable: $message")
    LaunchedEffect(message) {
        onReport(message)
    }
    Column(
        modifier = modifier
            .background(Color(0xFF0D1117))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "预览异常",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onClose) {
            Text("返回列表")
        }
    }
}

@Composable
private fun AppDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(message)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    )
}

@Composable
private fun LogLocationDialog(
    onUseInternal: () -> Unit,
    onChooseDirectory: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择日志保存位置") },
        text = {
            Text(
                "为了方便排查问题，建议在首次启动时选择一个可访问的日志目录。你也可以先使用应用内部目录作为默认位置。"
            )
        },
        confirmButton = {
            Button(onClick = onChooseDirectory) {
                Text("选择外部目录")
            }
        },
        dismissButton = {
            TextButton(onClick = onUseInternal) {
                Text("使用内部目录")
            }
        }
    )
}

@Composable
private fun PreviewDetailPanel(
    image: OtgImage,
    isSelected: Boolean,
    selectionMode: Boolean,
    selectionCount: Int,
    onToggleCurrentSelection: () -> Unit,
    onSaveCurrent: () -> Unit,
    onShareCurrent: () -> Unit,
    onSaveSelected: () -> Unit,
    onShareSelected: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = image.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = image.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onToggleCurrentSelection) {
                Text(if (isSelected) "取消选择" else "选择", style = MaterialTheme.typography.labelMedium)
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DetailChip("时间", formatTime(image.sortTime))
            DetailChip("大小", formatSize(image.sizeBytes))
            DetailChip("分辨率", formatResolution(image))
            if (selectionMode) {
                DetailChip("已选择", "$selectionCount 张")
            }
        }

        val exif = image.exifInfo
        if (exif != null) {
            DetailSection("设备信息", listOfNotNull(
                detailLine("相机型号", formatCameraModel(exif)),
                detailLine("镜头型号", exif.lensModel),
                detailLine("拍摄时间", exif.takenTime?.let(::formatTime))
            ))
            DetailSection("摄影参数", listOfNotNull(
                detailLine("焦距", exif.focalLengthMm),
                detailLine("光圈", exif.aperture),
                detailLine("快门", exif.shutterSpeed),
                detailLine("ISO", exif.iso)
            ))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSaveCurrent,
                modifier = Modifier.weight(1f)
            ) { Text("保存当前", style = MaterialTheme.typography.labelMedium) }
            Button(
                onClick = onShareCurrent,
                modifier = Modifier.weight(1f)
            ) { Text("分享当前", style = MaterialTheme.typography.labelMedium) }
        }

        if (selectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSaveSelected,
                    modifier = Modifier.weight(1f)
                ) { Text("批量保存", style = MaterialTheme.typography.labelMedium) }
                Button(
                    onClick = onShareSelected,
                    modifier = Modifier.weight(1f)
                ) { Text("批量分享", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    lines: List<Pair<String, String>>
) {
    if (lines.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            lines.forEach { (label, value) ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailChip(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = "$label  $value",
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun SelectionBadge(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f)
    ) {
        Text(
            text = if (selected) "ON" else "...",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

private fun formatTime(timeMillis: Long): String {
    if (timeMillis <= 0L) return "未知时间"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(timeMillis))
}

private fun formatSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = sizeBytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index += 1
    }
    return "${DecimalFormat("0.#").format(value)} ${units[index]}"
}

private fun formatResolution(image: OtgImage): String {
    val width = image.width ?: image.exifInfo?.width
    val height = image.height ?: image.exifInfo?.height
    return if (width != null && height != null) "$width x $height" else "未知"
}

private fun formatCameraModel(exifInfo: ImageExifInfo): String? {
    val make = exifInfo.cameraMake.orEmpty().trim()
    val model = exifInfo.cameraModel.orEmpty().trim()
    return when {
        make.isBlank() -> model.ifBlank { null }
        model.isBlank() -> make
        model.startsWith(make, ignoreCase = true) -> model
        else -> "$make $model"
    }
}

private fun detailLine(label: String, value: String?): Pair<String, String>? {
    return value?.takeIf { it.isNotBlank() }?.let { label to it }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    OTG_Picture_ReaderTheme {
        EmptyState(
            title = "还没有连接到 OTG 目录",
            description = "先选择 OTG 设备目录，应用会扫描全部图片并优先生成缩略图。",
            actionLabel = "选择 OTG 目录",
            onAction = {}
        )
    }
}
