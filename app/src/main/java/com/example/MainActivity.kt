package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.capture.ScreenCaptureOverlayService
import com.example.capture.TrainerSessionOrchestrator
import com.example.model.AnalysisReport
import com.example.model.BoardOrientationMode
import com.example.model.CastlingRights
import com.example.model.ComponentRuntimeStatus
import com.example.model.ManualCalibrationNormalizedRect
import com.example.model.PerformanceMode
import com.example.model.PieceColor
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var orchestrator: TrainerSessionOrchestrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        orchestrator = TrainerSessionOrchestrator.getInstance(applicationContext)
        refreshOverlayPermissionState()

        setContent {
            MyApplicationTheme {
                ChessVisionTrainerApp(
                    orchestrator = orchestrator,
                    onRefreshOverlayPermission = { refreshOverlayPermissionState() }
                )
            }
        }
    }

    private fun refreshOverlayPermissionState() {
        val granted = Settings.canDrawOverlays(this)
        orchestrator.updateRuntimeStatus { current ->
            current.copy(
                overlayPermissionGranted = granted,
                overlayStatusText = when {
                    current.overlayActive && granted -> "运行中 (单击 ♟ 分析，点击 ⊞ 校准 在游戏界面对齐棋盘，长按清除箭头)"
                    granted -> "已授权 (待启动)"
                    else -> "未授权 (点击一键启动将引导开启悬浮窗)"
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChessVisionTrainerApp(
    orchestrator: TrainerSessionOrchestrator,
    onRefreshOverlayPermission: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val runtimeStatus by orchestrator.runtimeStatus.collectAsState()
    val performanceMode by orchestrator.performanceMode.collectAsState()
    val userColor by orchestrator.userColor.collectAsState()
    val orientationMode by orchestrator.boardOrientationMode.collectAsState()
    val manualCalibration by orchestrator.manualCalibration.collectAsState()
    val preferManualCalibration by orchestrator.preferManualCalibration.collectAsState()
    val configuredCastling by orchestrator.configuredCastling.collectAsState()
    val configuredEnPassant by orchestrator.configuredEnPassant.collectAsState()
    val activeColorOverride by orchestrator.activeColorOverride.collectAsState()
    val midgameConfirmed by orchestrator.midgameConfirmed.collectAsState()
    val latestReport by orchestrator.latestAnalysisReport.collectAsState()

    var pendingStartAfterOverlayGrant by rememberSaveable { mutableStateOf(false) }
    var showLicensesDialog by rememberSaveable { mutableStateOf(false) }
    var analysisExpanded by rememberSaveable { mutableStateOf(true) }
    var stateConfirmExpanded by rememberSaveable { mutableStateOf(false) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenCaptureOverlayService::class.java).apply {
                action = ScreenCaptureOverlayService.ACTION_START_CAPTURE
                putExtra(ScreenCaptureOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            orchestrator.updateRuntimeStatus {
                it.copy(
                    captureActive = false,
                    captureStatusText = "用户取消了屏幕捕捉授权"
                )
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshOverlayPermission()
        if (Settings.canDrawOverlays(context) && pendingStartAfterOverlayGrant) {
            pendingStartAfterOverlayGrant = false
            requestNotificationAndMediaProjection(
                context = context,
                notificationLauncher = { perm -> notificationPermissionLauncher.launch(perm) },
                projectionLauncher = { intent -> mediaProjectionLauncher.launch(intent) }
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshOverlayPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Banner
                HeaderBannerCard(runtimeStatus = runtimeStatus)

                // Primary Action Row: One-Click Start & Stop All
                PrimaryActionButtonsCard(
                    captureActive = runtimeStatus.captureActive,
                    onOneClickStart = {
                        onRefreshOverlayPermission()
                        if (!Settings.canDrawOverlays(context)) {
                            pendingStartAfterOverlayGrant = true
                            val overlayIntent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayPermissionLauncher.launch(overlayIntent)
                        } else {
                            requestNotificationAndMediaProjection(
                                context = context,
                                notificationLauncher = { perm -> notificationPermissionLauncher.launch(perm) },
                                projectionLauncher = { intent -> mediaProjectionLauncher.launch(intent) }
                            )
                        }
                    },
                    onStopAll = {
                        orchestrator.stopAllAndRelease()
                    }
                )

                // Real-time System Status Card
                ComponentStatusCard(
                    status = runtimeStatus,
                    onPreloadCheckClicked = { orchestrator.warmUpModelAndEngine() }
                )

                // Performance Tier Card (Low / Medium / High)
                PerformanceModeCard(
                    selectedMode = performanceMode,
                    onSelectMode = { orchestrator.setPerformanceMode(it) }
                )

                // Player Color & Board Orientation Card
                ColorAndOrientationCard(
                    userColor = userColor,
                    orientationMode = orientationMode,
                    onSelectColor = { orchestrator.setUserColor(it) },
                    onSelectOrientation = { orchestrator.setBoardOrientationMode(it) }
                )

                // Manual Board Calibration Card
                ManualCalibrationCard(
                    manualCalibration = manualCalibration,
                    preferManual = preferManualCalibration,
                    overlayActive = runtimeStatus.overlayActive,
                    onOpenCalibrationOverlay = {
                        if (!Settings.canDrawOverlays(context)) {
                            val overlayIntent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayPermissionLauncher.launch(overlayIntent)
                        } else {
                            orchestrator.serviceBridge?.openManualCalibrationOverlay()
                        }
                    },
                    onTogglePreferManual = { orchestrator.setPreferManualCalibration(it) },
                    onClearCalibration = { orchestrator.setManualCalibration(null) }
                )

                // Position State Confirmation & Resync Card (FEN completion)
                PositionStateConfirmationCard(
                    expanded = stateConfirmExpanded || (latestReport?.requiresUserConfirmation == true),
                    onToggleExpand = { stateConfirmExpanded = !stateConfirmExpanded },
                    castlingRights = configuredCastling,
                    enPassantSquare = configuredEnPassant,
                    activeSideOverride = activeColorOverride,
                    userColor = userColor,
                    midgameConfirmed = midgameConfirmed,
                    onUpdateSettings = { castling, ep, sideOverride, confirmed ->
                        orchestrator.updateMidgameConfirmationSettings(castling, ep, sideOverride, confirmed)
                    },
                    onResyncClicked = {
                        orchestrator.resyncPositionState(confirmMidgameNow = true)
                    }
                )

                // Collapsible Latest Analysis Result Card
                LatestAnalysisResultCard(
                    report = latestReport,
                    expanded = analysisExpanded,
                    onToggleExpand = { analysisExpanded = !analysisExpanded }
                )

                // Open-Source Licenses & Provenance Button
                OutlinedButton(
                    onClick = { showLicensesDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_licenses_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = "开源许可")
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.licenses_section_title), fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showLicensesDialog) {
        LicensesAndProvenanceDialog(
            context = context,
            onDismiss = { showLicensesDialog = false }
        )
    }
}

private fun requestNotificationAndMediaProjection(
    context: Context,
    notificationLauncher: (String) -> Unit,
    projectionLauncher: (Intent) -> Unit
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!notifGranted) {
            notificationLauncher(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
    }
    val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    projectionLauncher(mpManager.createScreenCaptureIntent())
}

@Composable
private fun HeaderBannerCard(runtimeStatus: ComponentRuntimeStatus) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("header_banner_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ChessVisionTrainer",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondary)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "100% 离线",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondary
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "屏幕捕捉 → YOLOv8m 棋盘识别 → 合法性校验 → Stockfish 18 → 悬浮走法箭头",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = runtimeStatus.overlayButtonSymbol.symbol,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PrimaryActionButtonsCard(
    captureActive: Boolean,
    onOneClickStart: () -> Unit,
    onStopAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onOneClickStart,
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .testTag("one_click_start_button"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "一键启动")
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (captureActive) "重新授权启动" else stringResource(R.string.btn_one_click_start),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        Button(
            onClick = onStopAll,
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .testTag("stop_all_button"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Stop, contentDescription = "全部停止")
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.btn_stop_all),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun ComponentStatusCard(
    status: ComponentRuntimeStatus,
    onPreloadCheckClicked: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("component_status_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.status_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onPreloadCheckClicked,
                    modifier = Modifier.testTag("verify_components_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "自检加载", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("预加载自检", fontSize = 12.sp)
                }
            }

            StatusRowItem("屏幕捕捉", status.captureActive, status.captureStatusText)
            StatusRowItem(
                "悬浮窗层",
                status.overlayActive || status.overlayPermissionGranted,
                "${status.overlayStatusText} | 按钮状态: ${status.overlayButtonSymbol.symbol} (${status.overlayButtonSymbol.description})"
            )
            StatusRowItem("视觉模型", status.modelLoaded, "${status.modelStatusText}\n${status.modelDetailsText}")
            StatusRowItem("棋力引擎", status.engineReady, "${status.engineStatusText}\n${status.engineDetailsText}")
        }
    }
}

@Composable
private fun StatusRowItem(label: String, ready: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = label,
            tint = if (ready) Color(0xFF10B981) else Color(0xFFF59E0B),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PerformanceModeCard(
    selectedMode: PerformanceMode,
    onSelectMode: (PerformanceMode) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("performance_mode_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.perf_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PerformanceMode.entries.forEach { mode ->
                    val selected = mode == selectedMode
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectMode(mode) },
                        label = {
                            Text(
                                text = when (mode) {
                                    PerformanceMode.LOW -> "低 (720p)"
                                    PerformanceMode.MEDIUM -> "中 (1080p)"
                                    PerformanceMode.HIGH -> "高 (1440p)"
                                },
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("perf_mode_${mode.name.lowercase()}")
                    )
                }
            }
            Text(
                text = "当前档位参数：最大捕捉长边 ${selectedMode.maxCaptureLongEdgePx}px | 视觉线程 ${selectedMode.initialVisionCpuThreads} | 引擎线程 ${selectedMode.initialEngineThreads} | Hash ${selectedMode.initialEngineHashMb}MB | 思考时间 ${selectedMode.searchTimeMs}ms (自动按设备核心与源分辨率封顶)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorAndOrientationCard(
    userColor: PieceColor,
    orientationMode: BoardOrientationMode,
    onSelectColor: (PieceColor) -> Unit,
    onSelectOrientation: (BoardOrientationMode) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("color_orientation_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.side_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "我执颜色（点击 ♟ 请求提示时，默认分析您所执的一方）：",
                style = MaterialTheme.typography.labelMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PieceColor.entries.forEach { color ->
                    val selected = color == userColor
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectColor(color) },
                        label = { Text("我执${color.displayName}") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("user_color_${color.name.lowercase()}")
                    )
                }
            }

            Text(
                text = "棋盘方向（绝不凭所执颜色盲猜方向；残局自动不确定时请明确指定）：",
                style = MaterialTheme.typography.labelMedium
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BoardOrientationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mode == orientationMode,
                        onClick = { onSelectOrientation(mode) },
                        label = { Text(mode.displayName) },
                        modifier = Modifier.testTag("orientation_mode_${mode.name.lowercase()}")
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualCalibrationCard(
    manualCalibration: ManualCalibrationNormalizedRect?,
    preferManual: Boolean,
    overlayActive: Boolean,
    onOpenCalibrationOverlay: () -> Unit,
    onTogglePreferManual: (Boolean) -> Unit,
    onClearCalibration: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("manual_calibration_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.calibration_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (manualCalibration != null) {
                    TextButton(onClick = onClearCalibration) {
                        Text("清除校准", fontSize = 12.sp)
                    }
                }
            }

            Text(
                text = if (manualCalibration != null) {
                    "已保存归一化棋盘区域: [${"%.2f".format(manualCalibration.leftRatio)}, ${"%.2f".format(manualCalibration.topRatio)}, ${"%.2f".format(manualCalibration.rightRatio)}, ${"%.2f".format(manualCalibration.bottomRatio)}] (校准分辨率 ${manualCalibration.calibratedScreenWidth}×${manualCalibration.calibratedScreenHeight})\n提示：在游戏界面可随时点击悬浮窗下方的「⊞ 已校」重新微调。"
                } else {
                    "启动后切换到国际象棋游戏界面，直接点击悬浮窗下方的「⊞ 校准」按钮，即可在游戏画面上呼出透明 8×8 网格框（支持「AI自动贴合」、「铺满宽」、四角拖动与微调），对齐棋盘后点击「保存并分析」即可精准识别！"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (manualCalibration != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("优先使用游戏内校准区域进行局部高精度识别", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = preferManual,
                        onCheckedChange = onTogglePreferManual,
                        modifier = Modifier.testTag("prefer_manual_calibration_switch")
                    )
                }
            }

            OutlinedButton(
                onClick = onOpenCalibrationOverlay,
                enabled = overlayActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("manual_calibrate_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.CropFree, contentDescription = "手动校准棋盘")
                Spacer(Modifier.width(8.dp))
                Text(
                    if (overlayActive) "打开悬浮棋盘校准框 (也可在游戏内点悬浮窗 ⊞ 校准)"
                    else "游戏界面校准棋盘 (请先点击一键启动)"
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PositionStateConfirmationCard(
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    castlingRights: CastlingRights,
    enPassantSquare: String,
    activeSideOverride: PieceColor?,
    userColor: PieceColor,
    midgameConfirmed: Boolean,
    onUpdateSettings: (CastlingRights, String, PieceColor?, Boolean) -> Unit,
    onResyncClicked: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("position_state_confirmation_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.state_confirm_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "展开或收起"
                )
            }

            Text(
                text = "说明：单张截图不能始终确定易位权与过路兵。从标准初始局面开始时自动跟踪合法走法；中途接入时在此确认状态（半回合=0、回合=1 初始化）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()

                    Text("允许的王车易位权 (仅当王与车仍在初始格时生效)：", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = castlingRights.whiteKingSide,
                            onClick = {
                                onUpdateSettings(
                                    castlingRights.copy(whiteKingSide = !castlingRights.whiteKingSide),
                                    enPassantSquare,
                                    activeSideOverride,
                                    midgameConfirmed
                                )
                            },
                            label = { Text("白王翼 (K)") },
                            modifier = Modifier.testTag("castling_wk")
                        )
                        FilterChip(
                            selected = castlingRights.whiteQueenSide,
                            onClick = {
                                onUpdateSettings(
                                    castlingRights.copy(whiteQueenSide = !castlingRights.whiteQueenSide),
                                    enPassantSquare,
                                    activeSideOverride,
                                    midgameConfirmed
                                )
                            },
                            label = { Text("白后翼 (Q)") },
                            modifier = Modifier.testTag("castling_wq")
                        )
                        FilterChip(
                            selected = castlingRights.blackKingSide,
                            onClick = {
                                onUpdateSettings(
                                    castlingRights.copy(blackKingSide = !castlingRights.blackKingSide),
                                    enPassantSquare,
                                    activeSideOverride,
                                    midgameConfirmed
                                )
                            },
                            label = { Text("黑王翼 (k)") },
                            modifier = Modifier.testTag("castling_bk")
                        )
                        FilterChip(
                            selected = castlingRights.blackQueenSide,
                            onClick = {
                                onUpdateSettings(
                                    castlingRights.copy(blackQueenSide = !castlingRights.blackQueenSide),
                                    enPassantSquare,
                                    activeSideOverride,
                                    midgameConfirmed
                                )
                            },
                            label = { Text("黑后翼 (q)") },
                            modifier = Modifier.testTag("castling_bq")
                        )
                    }

                    OutlinedTextField(
                        value = enPassantSquare,
                        onValueChange = { newEp ->
                            onUpdateSettings(castlingRights, newEp, activeSideOverride, midgameConfirmed)
                        },
                        label = { Text("吃过路兵目标格 (例如 e3 或 d6，无则留空)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("en_passant_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onUpdateSettings(castlingRights, enPassantSquare, activeSideOverride, true)
                                onResyncClicked()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("confirm_midgame_state_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (midgameConfirmed) "已确认并重新同步" else "确认中途状态并同步")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LatestAnalysisResultCard(
    report: AnalysisReport?,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("latest_analysis_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.last_analysis_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "折叠或展开分析结果"
                )
            }

            if (report == null) {
                Text(
                    text = "暂无分析记录。启动后切换到国际象棋 App，单击悬浮按钮 ♟ 即可执行一次内存截图识别与本地引擎分析。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = if (report.success) {
                        "✓ 推荐走法: ${report.moveDescription ?: report.bestMoveUci} | 评估: ${report.evaluationText ?: "-"}"
                    } else {
                        "! 分析未输出箭头: ${report.errorOrWarningMessage ?: "未知原因"}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (report.success) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                )

                AnimatedVisibility(visible = expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HorizontalDivider()
                        AnalysisDetailLine("请求编号与档位", "#${report.requestId} | ${report.performanceMode.displayName}")
                        AnalysisDetailLine("分析行棋方", report.analyzedColor.displayName)
                        AnalysisDetailLine("棋盘方向与来源", "${report.resolvedOrientation?.displayName ?: "未确定"} (${report.orientationSource})")
                        AnalysisDetailLine("棋盘定位与棋子数", "${report.boardSource} | 识别棋子 ${report.detectedPiecesCount} 枚")
                        if (report.bestMoveUci != null) {
                            AnalysisDetailLine("UCI 最佳着法", "${report.bestMoveUci} (${report.moveSpecialType?.label ?: "走法"})")
                        }
                        if (report.promotionPieceLabel != null) {
                            AnalysisDetailLine("升变棋种提示", report.promotionPieceLabel)
                        }
                        if (report.fen != null) {
                            AnalysisDetailLine("完整 FEN", report.fen, monospace = true)
                        }
                        AnalysisDetailLine("状态恢复说明", report.fenStateProvenance)
                        AnalysisDetailLine("耗时统计", "视觉推理 ${report.visionLatencyMs}ms | 引擎搜索 ${report.engineLatencyMs}ms")
                        report.arrowOverlay?.let { arr ->
                            AnalysisDetailLine(
                                "悬浮箭头坐标",
                                "${arr.fromSquare}(${arr.fromX.toInt()},${arr.fromY.toInt()}) → ${arr.toSquare}(${arr.toX.toInt()},${arr.toY.toInt()})"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisDetailLine(label: String, value: String, monospace: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun LicensesAndProvenanceDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val licenseSummary = remember {
        val metaJson = runCatching {
            context.assets.open("model_metadata.json").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val yoloLic = runCatching {
            context.assets.open("licenses/YOLOV8_CHESS_AGPLv3.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val sfAuthors = runCatching {
            context.assets.open("licenses/STOCKFISH_AUTHORS.txt").bufferedReader().use { it.readText().take(900) }
        }.getOrDefault("")
        buildString {
            appendLine("=== 1. 离线视觉模型 (YOLOv8m Chess Detection) ===")
            appendLine(yoloLic.trim())
            appendLine()
            appendLine("=== 2. 本地国际象棋引擎 (Stockfish 18) ===")
            appendLine("Source: https://github.com/official-stockfish/Stockfish/releases/tag/sf_18")
            appendLine("Binary: stockfish-android-armv8 (libstockfish.so)")
            appendLine("SHA-256: cc3b3b74466b6d85cdc0f8cfdad4111c6a44bd0698f4eab96fea7b359a54d3d4")
            appendLine("Embedded NNUE Big: nn-c288c895ea92.nnue")
            appendLine("Embedded NNUE Small: nn-37f18f62d772.nnue")
            appendLine("License: GNU General Public License v3.0 (GPL-3.0-or-later)")
            appendLine("Authors excerpt:\n$sfAuthors")
            appendLine()
            appendLine("=== 3. 推理运行时 (Google AI Edge LiteRT 1.4.2) ===")
            appendLine("Artifact: com.google.ai.edge.litert:litert:1.4.2")
            appendLine("License: Apache License 2.0")
            appendLine()
            appendLine("=== 4. 验证元数据 (assets/model_metadata.json) ===")
            appendLine(metaJson)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型、引擎来源与开源许可") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = licenseSummary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
