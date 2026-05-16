package com.example.otg_picture_reader.feature.liveview

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Live View 监视器全屏界面。
 *
 * 布局结构：
 * ┌─────────────────────────┐
 * │     (实时取景画面)        │
 * │                         │
 * │  ┌──────────┐           │
 * │  │ 参数 HUD │           │
 * │  └──────────┘           │
 * │                    ┌───┐│
 * │                    │FPS││
 * │                    └───┘│
 * │   [停止] [关闭]         │
 * └─────────────────────────┘
 */
@Composable
fun LiveViewScreen(
    state: LiveViewState,
    formattedParams: FormattedParams,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAutofocus: () -> Unit,
    onCapture: () -> Unit,
    onClose: () -> Unit,
    onDismissError: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // -- 主画面：展示当前帧 --
        if (state.currentFrame != null) {
            val bitmap = remember(state.frameCount) {
                runCatching {
                    BitmapFactory.decodeByteArray(
                        state.currentFrame, 0, state.currentFrame!!.size
                    )?.asImageBitmap()
                }.getOrNull()
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Live View 实时取景",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                FrameDecodeError(modifier = Modifier.fillMaxSize())
            }
        } else if (!state.isActive && !state.isConnecting) {
            // 空闲状态：提示文字
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "📷",
                    fontSize = 64.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Nikon Live View 监视器",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "连接相机后点击「开始监视」",
                    color = Color.White.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "⚠ ${state.errorMessage}",
                        color = Color(0xFFFF6B6B),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
        }

        // -- 错误弹窗 --
        if (state.errorMessage != null && !state.isConnecting) {
            AlertDialog(
                onDismissRequest = onDismissError,
                title = {
                    Text("无法启动监视器")
                },
                text = {
                    Text(state.errorMessage!!)
                },
                confirmButton = {
                    TextButton(onClick = onDismissError) {
                        Text("确定")
                    }
                }
            )
        }

        // -- 连接中指示器 --
        if (state.isConnecting) {
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "正在连接相机...",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // -- 参数 HUD（左上角） --
        AnimatedVisibility(
            visible = state.isActive && state.params != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            ParamsHudCard(formattedParams)
        }

        // -- FPS 指示器（右上角） --
        AnimatedVisibility(
            visible = state.isActive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            FpsBadge(fps = state.fps, frameCount = state.frameCount)
        }

        // -- 底部控制栏 --
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.isActive) {
                // 运行中：显示相机控制与停止按钮
                Button(
                    onClick = onAutofocus,
                    enabled = !state.isCommandBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF455A64)
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("AF")
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = onCapture,
                    enabled = !state.isCommandBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    shape = CircleShape,
                    modifier = Modifier.size(58.dp)
                ) {
                    Text("●", fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935)
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("⏹ 停止监视")
                }
            } else if (!state.isConnecting) {
                // 未运行：显示开始按钮
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF43A047)
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("▶ 开始监视")
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            TextButton(
                onClick = onClose,
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    "关闭",
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun FrameDecodeError(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            "无法解码图像帧",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 相机参数 HUD 卡片 */
@Composable
private fun ParamsHudCard(params: FormattedParams) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            HudRow("快门", params.shutterSpeed)
            HudRow("光圈", params.aperture)
            HudRow("ISO", params.iso)
            HudRow("曝光补偿", params.exposureComp)
            HudRow("模式", params.shootingMode)
            HudRow("白平衡", params.whiteBalance)
            HudSpacer()
            HudRow("电池", params.battery)
            HudRow("剩余", params.remainingShots)
        }
    }
}

@Composable
private fun HudRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(52.dp)
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun HudSpacer() {
    Spacer(modifier = Modifier.height(4.dp))
}

/** FPS 角标 */
@Composable
private fun FpsBadge(fps: Float, frameCount: Long) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${"%.1f".format(fps)}",
                color = if (fps >= 10f) Color(0xFF4CAF50) else Color(0xFFFFC107),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "fps",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
