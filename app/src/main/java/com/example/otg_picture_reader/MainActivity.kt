package com.example.otg_picture_reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.otg_picture_reader.data.model.ImageExifInfo
import com.example.otg_picture_reader.data.model.OtgImage
import com.example.otg_picture_reader.data.model.ScanState
import com.example.otg_picture_reader.feature.liveview.LiveViewScreen
import com.example.otg_picture_reader.feature.liveview.LiveViewViewModel
import com.example.otg_picture_reader.ui.theme.OTG_Picture_ReaderTheme
import com.example.otg_picture_reader.util.AppLog
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

private const val ACTION_USB_PERMISSION = "com.example.otg_picture_reader.USB_PERMISSION"
private const val USB_ATTACH_DEBOUNCE_MS = 1_500L

private data class AppDialogState(
    val title: String,
    val message: String
)

private enum class PendingUsbAction {
    ScanImages,
    StartLiveView
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(application)
    }
    private val liveViewViewModel: LiveViewViewModel by viewModels()
    private var lastAutoScanRequestMs = 0L
    private var pendingUsbAction: PendingUsbAction = PendingUsbAction.ScanImages

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            AppLog.i("Activity", "usb permission result granted=$granted")
            if (granted || viewModel.hasMtpDeviceAccess()) {
                when (pendingUsbAction) {
                    PendingUsbAction.ScanImages -> viewModel.scanMtpImages()
                    PendingUsbAction.StartLiveView -> liveViewViewModel.startLiveView()
                }
            } else {
                Toast.makeText(context, "未获得 USB 设备访问权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
            AppLog.i("Activity", "usb device attached, auto scan")
            requestMtpScan(showNoDeviceToast = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            usbPermissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            usbAttachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        enableEdgeToEdge()
        setContent {
            OTG_Picture_ReaderTheme {
                OtgPictureReaderApp(
                    viewModel = viewModel,
                    onShareImage = ::shareImage,
                    onShareImages = ::shareImages,
                    onScanMtpDevice = ::requestMtpScan,
                    liveViewViewModel = liveViewViewModel,
                    onStartLiveView = ::requestLiveViewStart,
                    onStopCameraScan = viewModel::stopCameraScan
                )
            }
        }
        handleUsbAttachIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbAttachReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent(intent)
    }

    private fun handleUsbAttachIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            AppLog.i("Activity", "launched from usb attach intent")
            requestMtpScan(showNoDeviceToast = false)
        }
    }

    private fun requestMtpScan(showNoDeviceToast: Boolean = true) {
        requestUsbDeviceAccess(
            action = PendingUsbAction.ScanImages,
            showNoDeviceToast = showNoDeviceToast
        )
    }

    private fun requestLiveViewStart() {
        if (viewModel.uiState.value.scanState.isScanning) {
            AppLog.w("Activity", "requestLiveViewStart ignored: camera scan is running")
            Toast.makeText(this, "相机扫描正在进行，请先停止扫描后再打开监视器", Toast.LENGTH_SHORT).show()
            return
        }
        requestUsbDeviceAccess(
            action = PendingUsbAction.StartLiveView,
            showNoDeviceToast = true
        )
    }

    private fun requestUsbDeviceAccess(
        action: PendingUsbAction,
        showNoDeviceToast: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        if (!showNoDeviceToast && now - lastAutoScanRequestMs < USB_ATTACH_DEBOUNCE_MS) {
            AppLog.i("Activity", "requestUsbDeviceAccess auto request debounced")
            return
        }
        if (!showNoDeviceToast) {
            lastAutoScanRequestMs = now
        }
        val scanner = (application as OtgPictureReaderApp).appContainer.mtpImageScanner
        val device = scanner.findCandidateDevice()
        if (device == null) {
            AppLog.w("Activity", "requestMtpScan no candidate device")
            if (showNoDeviceToast) {
                Toast.makeText(this, "没有发现可读取的 PTP/MTP 设备", Toast.LENGTH_SHORT).show()
            }
            return
        }
        pendingUsbAction = action
        if (scanner.hasPermission(device)) {
            AppLog.i("Activity", "requestUsbDeviceAccess permission already granted action=$action")
            when (action) {
                PendingUsbAction.ScanImages -> viewModel.scanMtpImages()
                PendingUsbAction.StartLiveView -> liveViewViewModel.startLiveView()
            }
            return
        }

        AppLog.i("Activity", "requestUsbDeviceAccess request permission device=${device.deviceName} action=$action")
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(packageName)
            putExtra(UsbManager.EXTRA_DEVICE, device)
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        usbManager.requestPermission(device, pendingIntent)
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
    onShareImages: (List<Uri>) -> Unit,
    onScanMtpDevice: () -> Unit,
    liveViewViewModel: LiveViewViewModel,
    onStartLiveView: () -> Unit,
    onStopCameraScan: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val galleryGridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var activeDialog by remember { mutableStateOf<AppDialogState?>(null) }
    var isSettingsMenuOpen by remember { mutableStateOf(false) }
    var showLogLocationPrompt by rememberSaveable {
        mutableStateOf(AppLog.isEnabled && AppLog.shouldPromptForLogLocation(context))
    }
    var showLogLimitPrompt by remember { mutableStateOf(false) }
    var showLiveView by remember { mutableStateOf(false) }
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

    BackHandler(enabled = uiState.selectedImage != null || uiState.isSelectionMode) {
        when {
            uiState.selectedImage != null -> viewModel.closePreview()
            uiState.isSelectionMode -> viewModel.clearSelection()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            val isOtgSource = uiState.rootUri != null
            TopAppBar(
                title = {
                    Text(
                        when {
                            uiState.selectedImage != null -> "${uiState.previewIndex + 1}/${uiState.images.size}"
                            uiState.isSelectionMode -> "已选择 ${uiState.selectedCount} 张"
                            else -> rememberSourceName(uiState.rootUri, uiState.scanState.isScanning)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    val previewImage = uiState.selectedImage
                    if (previewImage != null) {
                        val isCurrentSelected = previewImage.id in uiState.selectedImageIds
                        TextButton(onClick = viewModel::toggleSelectionFromPreview) {
                            Text(if (isCurrentSelected) "取消选择" else "选择")
                        }
                    } else {
                        Box {
                            TextButton(onClick = { isSettingsMenuOpen = true }) {
                                Text("设置")
                            }
                            DropdownMenu(
                                expanded = isSettingsMenuOpen,
                                onDismissRequest = { isSettingsMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when {
                                                uiState.scanState.isScanning && !isOtgSource -> "停止扫描"
                                                isOtgSource -> "重新扫描 OTG"
                                                else -> "扫描相机"
                                            }
                                        )
                                    },
                                    onClick = {
                                        isSettingsMenuOpen = false
                                        if (uiState.scanState.isScanning && !isOtgSource) {
                                            onStopCameraScan()
                                        } else if (isOtgSource) {
                                            viewModel.scanImages()
                                        } else {
                                            onScanMtpDevice()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("选择 OTG 目录") },
                                    onClick = {
                                        isSettingsMenuOpen = false
                                        treePicker.launch(null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isOtgSource) "扫描相机" else "选择 OTG 后扫描") },
                                    onClick = {
                                        isSettingsMenuOpen = false
                                        if (isOtgSource) onScanMtpDevice() else treePicker.launch(null)
                                    },
                                    enabled = !uiState.scanState.isScanning
                                )
                                DropdownMenuItem(
                                    text = { Text(if (uiState.sortDescending) "排序：最早在前" else "排序：最新在前") },
                                    onClick = {
                                        isSettingsMenuOpen = false
                                        viewModel.toggleSortOrder()
                                    }
                                )
                                if (AppLog.isEnabled) {
                                    DropdownMenuItem(
                                        text = { Text("日志位置") },
                                        onClick = {
                                            isSettingsMenuOpen = false
                                            showLogLocationPrompt = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("日志条数") },
                                        onClick = {
                                            isSettingsMenuOpen = false
                                            showLogLimitPrompt = true
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("监视器 (Live View)") },
                                    onClick = {
                                        isSettingsMenuOpen = false
                                        viewModel.closePreview()
                                        viewModel.clearSelection()
                                        showLiveView = true
                                    },
                                    enabled = !uiState.scanState.isScanning
                                )
                                DropdownMenuItem(
                                    text = { Text("清除当前来源") },
                                    onClick = {
                                        isSettingsMenuOpen = false
                                        viewModel.clearRootSelection()
                                        scope.launch {
                                            snackbarHostState.showSnackbar("已清除当前缓存")
                                        }
                                    }
                                )
                            }
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
                onSaveCurrent = viewModel::saveSelectedImage,
                onShareCurrent = viewModel::shareSelectedImage,
                onSaveSelected = viewModel::saveSelectedImages,
                onShareSelected = viewModel::shareSelectedImages,
                onReportError = { message -> activeDialog = AppDialogState("预览异常", message) }
            )
        } else {
            GalleryScreen(
                state = uiState,
                gridState = galleryGridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onPickRoot = { treePicker.launch(null) },
                onScanMtpDevice = onScanMtpDevice,
                onStopCameraScan = onStopCameraScan,
                onRescanOtg = viewModel::scanImages,
                onImageClick = { image ->
                    AppLog.d("UI", "gallery image click id=${image.id} selectionMode=${uiState.isSelectionMode}")
                    if (uiState.isSelectionMode) {
                        viewModel.toggleImageSelection(image.id)
                    } else {
                        viewModel.openImage(image.id)
                    }
                },
                onImageLongClick = { image ->
                    AppLog.d("UI", "gallery image longClick id=${image.id} selectionMode=${uiState.isSelectionMode}")
                    if (!uiState.isSelectionMode) {
                        viewModel.enterSelectionMode()
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

    if (showLogLimitPrompt) {
        LogLimitDialog(
            currentLimit = AppLog.currentMaxLogEntries(),
            onSave = { value ->
                val normalized = AppLog.setMaxLogEntries(context, value)
                showLogLimitPrompt = false
                AppLog.i("UI", "log max entries changed to $normalized")
                scope.launch {
                    snackbarHostState.showSnackbar("日志最多保留 $normalized 条")
                }
            },
            onDismiss = { showLogLimitPrompt = false }
        )
    }

    activeDialog?.let { dialog ->
        AppDialog(
            title = dialog.title,
            message = dialog.message,
            onDismiss = { activeDialog = null }
        )
    }

    // -- Live View 监视器全屏 --
    if (showLiveView) {
        val liveViewVm = liveViewViewModel
        val liveViewState by liveViewVm.state.collectAsStateWithLifecycle()
        val formattedParams = remember(liveViewState.params) {
            liveViewVm.formatParams(liveViewState.params)
        }
        val closeLiveView = {
            liveViewVm.stopLiveView()
            showLiveView = false
        }

        BackHandler(enabled = true) {
            closeLiveView()
        }

        DisposableEffect(Unit) {
            onDispose {
                liveViewVm.stopLiveView()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LiveViewScreen(
                state = liveViewState,
                formattedParams = formattedParams,
                onStart = onStartLiveView,
                onStop = { liveViewVm.stopLiveView() },
                onAutofocus = { liveViewVm.autofocus() },
                onCapture = { liveViewVm.autofocusAndCapture() },
                onClose = closeLiveView,
                onDismissError = { liveViewVm.dismissError() }
            )
        }
    }
}

@Composable
private fun rememberSourceName(rootUri: String?, isScanning: Boolean): String {
    val context = LocalContext.current
    return remember(rootUri, isScanning) {
        rootUri?.let { uri ->
            DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name ?: "OTG 目录"
        } ?: if (isScanning) {
            "相机正在读取"
        } else {
            "相机/PTP/MTP 设备"
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun GalleryScreen(
    state: MainUiState,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    onPickRoot: () -> Unit,
    onScanMtpDevice: () -> Unit,
    onStopCameraScan: () -> Unit,
    onRescanOtg: () -> Unit,
    onImageClick: (OtgImage) -> Unit,
    onImageLongClick: (OtgImage) -> Unit,
    onSaveSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onClearSelection: () -> Unit
) {
    if (state.rootUri == null && state.images.isEmpty()) {
        AppLog.w("UI", "gallery return: rootUri is null")
        EmptyState(
            modifier = modifier,
            title = "还没有连接到图片来源",
            description = if (state.scanState.isScanning) {
                state.scanState.statusMessage
            } else {
                "可以选择 OTG 目录，也可以直接扫描已连接的相机/PTP/MTP 设备。"
            },
            actionLabel = "选择 OTG 目录",
            onAction = onPickRoot,
            isLoading = state.scanState.isScanning,
            secondaryActionLabel = if (state.scanState.isScanning) "停止扫描" else "扫描相机",
            onSecondaryAction = {
                if (state.scanState.isScanning) onStopCameraScan() else onScanMtpDevice()
            }
        )
        return
    }

    if (state.rootUri != null && state.images.isEmpty() && !state.scanState.isScanning) {
        EmptyState(
            modifier = modifier,
            title = "当前来源还没有图片",
            description = "可以重新扫描这个 OTG 目录，也可以切换到相机或其他目录。",
            actionLabel = "重新扫描 OTG",
            onAction = onRescanOtg,
            secondaryActionLabel = "扫描相机",
            onSecondaryAction = onScanMtpDevice
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
            isSelectionMode = state.isSelectionMode,
            selectedCount = state.selectedCount,
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
                actionLabel = if (state.rootUri != null) "重新扫描 OTG" else "扫描相机",
                onAction = if (state.rootUri != null) onRescanOtg else onScanMtpDevice
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 132.dp),
                state = gridState,
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
    rootUri: String?,
    scanState: ScanState,
    isSelectionMode: Boolean,
    selectedCount: Int,
    onSaveSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onClearSelection: () -> Unit
) {
    val context = LocalContext.current
    val rootName = remember(rootUri) {
        rootUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name } ?: "相机/PTP/MTP 设备"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large
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
        shape = MaterialTheme.shapes.large
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
    onSaveCurrent: () -> Unit,
    onShareCurrent: () -> Unit,
    onSaveSelected: () -> Unit,
    onShareSelected: () -> Unit,
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
    var isCurrentImageZoomed by remember(currentImage.id) { mutableStateOf(false) }
    val bottomSheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val bottomSheetScaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = bottomSheetState)

    BottomSheetScaffold(
        scaffoldState = bottomSheetScaffoldState,
        modifier = modifier.background(Color(0xFF0D1117)),
        sheetPeekHeight = 76.dp,
        sheetShape = MaterialTheme.shapes.extraLarge,
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
                userScrollEnabled = !isCurrentImageZoomed,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                state.images.getOrNull(page)?.let { image ->
                    AppLog.d("Preview", "compose page=$page imageId=${image.id}")
                    ZoomableImage(
                        image = image,
                        modifier = Modifier.fillMaxSize(),
                        onZoomChanged = { zoomed ->
                            if (page == pagerState.currentPage) {
                                isCurrentImageZoomed = zoomed
                            }
                        },
                        onError = { message -> onReportError(message) }
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 86.dp),
                color = Color.Black.copy(alpha = 0.42f),
                shape = MaterialTheme.shapes.extraLarge
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
    onZoomChanged: (Boolean) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val imageModel = remember(image.thumbnailPath, image.documentUri) {
        image.thumbnailPath?.let { File(it) } ?: Uri.parse(image.documentUri)
    }
    val imageRequest = remember(context, imageModel) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .allowHardware(false)
            .crossfade(false)
            .build()
    }
    var scale by rememberSaveable(image.id) { mutableFloatStateOf(1f) }
    var offset by remember(image.id) { mutableStateOf(Offset.Zero) }
    val imageAspectRatio = remember(image.width, image.height, image.exifInfo) {
        val width = image.width ?: image.exifInfo?.width
        val height = image.height ?: image.exifInfo?.height
        if (width != null && height != null && width > 0 && height > 0) {
            width.toFloat() / height.toFloat()
        } else {
            null
        }
    }
    AppLog.d("Preview", "ZoomableImage model imageId=${image.id} model=$imageModel")

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(image.id, imageAspectRatio) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val shouldHandleGesture = pressedPointers > 1 || scale > 1f

                        if (shouldHandleGesture) {
                            val previousScale = scale
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            val nextOffset = if (nextScale == 1f) {
                                Offset.Zero
                            } else {
                                val scaledOffset = offset * (nextScale / scale)
                                coerceImageOffset(
                                    offset = scaledOffset + pan,
                                    scale = nextScale,
                                    containerWidth = size.width.toFloat(),
                                    containerHeight = size.height.toFloat(),
                                    imageAspectRatio = imageAspectRatio
                                )
                            }

                            if ((previousScale > 1f) != (nextScale > 1f)) {
                                onZoomChanged(nextScale > 1f)
                            }
                            scale = nextScale
                            offset = nextOffset
                            event.changes.forEach { change -> change.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (scale == 1f) {
                        offset = Offset.Zero
                        onZoomChanged(false)
                    }
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

private fun coerceImageOffset(
    offset: Offset,
    scale: Float,
    containerWidth: Float,
    containerHeight: Float,
    imageAspectRatio: Float?
): Offset {
    if (scale <= 1f || containerWidth <= 0f || containerHeight <= 0f) {
        return Offset.Zero
    }

    val fittedImageWidth: Float
    val fittedImageHeight: Float
    if (imageAspectRatio != null) {
        val containerAspectRatio = containerWidth / containerHeight
        if (imageAspectRatio > containerAspectRatio) {
            fittedImageWidth = containerWidth
            fittedImageHeight = containerWidth / imageAspectRatio
        } else {
            fittedImageHeight = containerHeight
            fittedImageWidth = containerHeight * imageAspectRatio
        }
    } else {
        fittedImageWidth = containerWidth
        fittedImageHeight = containerHeight
    }

    val maxOffsetX = max(0f, (fittedImageWidth * scale - containerWidth) / 2f)
    val maxOffsetY = max(0f, (fittedImageHeight * scale - containerHeight) / 2f)

    return Offset(
        x = offset.x.coerceIn(-maxOffsetX, maxOffsetX),
        y = offset.y.coerceIn(-maxOffsetY, maxOffsetY)
    )
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
private fun LogLimitDialog(
    currentLimit: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(currentLimit) { mutableStateOf(currentLimit.toString()) }
    val parsed = text.toIntOrNull()
    val isValid = parsed != null && parsed >= 100

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("日志条数上限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("日志会持续写入同一个 app.log，只保留最新的记录。")
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        text = value.filter { it.isDigit() }.take(5)
                    },
                    singleLine = true,
                    label = { Text("最多保留条数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = text.isNotBlank() && !isValid
                )
                Text(
                    text = "建议 1000-5000，最低 100，最高 20000。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { parsed?.let(onSave) },
                enabled = isValid
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
                detailLine("实际焦距", exif.focalLengthMm),
                detailLine("35mm 等效焦距", exif.focalLength35Mm),
                detailLine("镜头规格", exif.lensSpecification),
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
        shape = MaterialTheme.shapes.medium
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
        shape = MaterialTheme.shapes.extraLarge
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
    onAction: () -> Unit,
    isLoading: Boolean = false,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
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
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    Button(onClick = onSecondaryAction) {
                        Text(secondaryActionLabel)
                    }
                }
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
