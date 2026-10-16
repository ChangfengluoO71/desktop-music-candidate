package com.qing.hachimi.desktop.update

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun UpdateStatusIndicator(state: UpdateState, onOpen: () -> Unit) {
    val label = when (state) {
        UpdateState.Idle, UpdateState.UpToDate -> return
        is UpdateState.Checking -> "正在检查更新…"
        is UpdateState.Available -> "有新版本 ${state.release.version}"
        is UpdateState.Downloading -> "更新 ${percent(state.bytesReceived, state.totalBytes)}%"
        is UpdateState.Verifying -> "正在校验更新包…"
        is UpdateState.ReadyToInstall -> "更新已就绪"
        is UpdateState.Failed -> "更新检查失败"
    }
    Button(
        modifier = Modifier.widthIn(max = 210.dp).testTag("update-status"),
        onClick = onOpen,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label)
            if (state is UpdateState.Downloading) {
                val progressColor = MiuixTheme.colorScheme.primary
                Canvas(
                    Modifier.fillMaxWidth().height(2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .testTag("update-progress"),
                ) {
                    drawRect(progressColor.copy(alpha = 0.22f))
                    val fraction = if (state.totalBytes <= 0L) 0f
                    else (state.bytesReceived.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                    drawRect(progressColor, size = size.copy(width = size.width * fraction))
                }
            }
        }
    }
}

@Composable
internal fun UpdateDetailsContent(
    state: UpdateState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    onCheck: () -> Unit,
) {
    Column(
        Modifier.widthIn(min = 360.dp, max = 560.dp).padding(22.dp).testTag("update-details"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DMC 更新", style = MiuixTheme.textStyles.headline2)
                Text(updateStatusDescription(state))
            }
            Button(modifier = Modifier.testTag("update-later"), onClick = onLater) { Text("稍后") }
        }
        when (state) {
            is UpdateState.Available -> {
                Text("${state.release.tagName}  ·  ${formatBytes(state.release.asset.size)}")
                if (state.release.notes.isNotBlank()) Text(state.release.notes.take(MAX_VISIBLE_NOTES))
                Button(
                    modifier = Modifier.testTag("update-download"),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = onDownload,
                ) { Text("下载更新") }
            }
            is UpdateState.Downloading -> {
                Text("${formatBytes(state.bytesReceived)} / ${formatBytes(state.totalBytes)}")
                Button(modifier = Modifier.testTag("update-cancel"), onClick = onCancel) { Text("取消下载") }
            }
            is UpdateState.Verifying -> Text("正在验证文件大小和 SHA-256。")
            is UpdateState.ReadyToInstall -> {
                Text("安装包已校验并保存在本机。安装时会打开标准 Inno Setup 安装界面。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.testTag("update-install"),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = onInstall,
                    ) { Text("立即安装") }
                    Button(onClick = onLater) { Text("稍后") }
                }
            }
            is UpdateState.Failed -> Button(
                modifier = Modifier.testTag("update-retry"),
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = onRetry,
            ) { Text("重试") }
            is UpdateState.Idle, UpdateState.UpToDate -> Button(
                modifier = Modifier.testTag("update-check"),
                onClick = onCheck,
            ) { Text("检查更新") }
            is UpdateState.Checking -> Text("检查不会影响当前播放和页面。")
        }
    }
}

@Composable
internal fun UpdateDetailsDialog(
    state: UpdateState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    onCheck: () -> Unit,
) {
    DialogWindow(onCloseRequest = onLater, title = "DMC 更新", resizable = false) {
        UpdateDetailsContent(state, onDownload, onCancel, onRetry, onInstall, onLater, onCheck)
    }
}

internal fun updateStatusDescription(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "检查公开 GitHub Releases 中是否有更新。"
    is UpdateState.Checking -> "正在连接 GitHub 检查版本。"
    UpdateState.UpToDate -> "当前已是最新版本。"
    is UpdateState.Available -> "发现可用更新。下载将在后台进行，不会暂停音乐。"
    is UpdateState.Downloading -> "正在后台下载；播放和当前页面保持不变。"
    is UpdateState.Verifying -> "下载已结束，正在核对长度和 SHA-256。"
    is UpdateState.ReadyToInstall -> "安装包已完成校验。"
    is UpdateState.Failed -> "${failureLabel(state.category)}。"
}

private fun failureLabel(category: UpdateFailureCategory): String = when (category) {
    UpdateFailureCategory.NETWORK -> "网络连接失败"
    UpdateFailureCategory.TIMEOUT -> "连接超时"
    UpdateFailureCategory.HTTP_ERROR -> "GitHub 返回错误"
    UpdateFailureCategory.RATE_LIMITED -> "GitHub 请求频率受限"
    UpdateFailureCategory.INVALID_RESPONSE -> "GitHub 响应无法解析"
    UpdateFailureCategory.INVALID_METADATA -> "更新信息不完整或无效"
    UpdateFailureCategory.MISSING_INSTALLER_ASSET -> "发布内容缺少安装程序"
    UpdateFailureCategory.AMBIGUOUS_INSTALLER_ASSET -> "发布内容包含重复安装程序"
    UpdateFailureCategory.REDIRECT_POLICY_REJECTED -> "更新地址跳转被安全策略拒绝"
    UpdateFailureCategory.TLS_FAILED -> "TLS 证书校验失败"
    UpdateFailureCategory.SIZE_MISMATCH -> "安装包大小校验失败"
    UpdateFailureCategory.DIGEST_MISMATCH -> "安装包 SHA-256 校验失败"
    UpdateFailureCategory.INSUFFICIENT_SPACE -> "磁盘空间不足"
    UpdateFailureCategory.WRITE_FAILED -> "无法保存更新包"
    UpdateFailureCategory.INSTALLER_LAUNCH_DENIED -> "Windows 拒绝启动安装程序"
    UpdateFailureCategory.INSTALLER_FAILED -> "安装程序退出时报告失败"
    UpdateFailureCategory.VERSION_MISMATCH -> "重启后的版本与目标版本不一致"
}

private fun percent(received: Long, total: Long): Int =
    if (total <= 0L) 0 else ((received.coerceIn(0, total) * 100L) / total).toInt()

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private const val MAX_VISIBLE_NOTES = 8_000
