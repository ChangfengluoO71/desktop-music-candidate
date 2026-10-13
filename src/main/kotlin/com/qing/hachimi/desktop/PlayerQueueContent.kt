package com.qing.hachimi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun PlayerQueueContent(
    playback: PlaybackState,
    onPlayIndex: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (playback.queue.isEmpty()) {
            Text("队列为空。", modifier = Modifier.testTag("player-queue-empty"))
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("now-playing-queue"),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(playback.queue, key = { _, item -> item.pathKey }) { index, item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .zIndex(if (draggingIndex == index) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (draggingIndex == index) dragOffsetY else 0f
                                alpha = if (draggingIndex == index) 0.82f else 1f
                            }
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (index == playback.queueIndex) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MiuixTheme.colorScheme.background,
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size(32.dp)
                                .testTag("queue-drag-handle-${item.id}")
                                .pointerInput(playback.queue, index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingIndex = index
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            val source = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                                            val targetCenter = source?.let {
                                                it.offset + it.size / 2 + dragOffsetY.roundToInt()
                                            }
                                            if (source != null && targetCenter != null && abs(dragOffsetY) >= source.size / 2f) {
                                                val target = listState.layoutInfo.visibleItemsInfo
                                                    .asSequence()
                                                    .filter { it.index != index }
                                                    .minByOrNull { abs(it.offset + it.size / 2 - targetCenter) }
                                                if (target != null) onMoveQueueItem(index, target.index)
                                            }
                                            draggingIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffsetY += amount.y
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("⠿", color = MiuixTheme.colorScheme.onBackground)
                        }
                        Column(Modifier.weight(1f).testTag("queue-item-${item.id}")) {
                            Text(item.title, maxLines = 1)
                            Text(item.artists.joinToString().ifBlank { item.album }, style = MiuixTheme.textStyles.body2, maxLines = 1)
                        }
                        Button(
                            modifier = Modifier.testTag("queue-move-up-${item.id}"),
                            enabled = index > 0,
                            onClick = { onMoveQueueItem(index, index - 1) },
                        ) { Text("上移") }
                        Button(
                            modifier = Modifier.testTag("queue-move-down-${item.id}"),
                            enabled = index < playback.queue.lastIndex,
                            onClick = { onMoveQueueItem(index, index + 1) },
                        ) { Text("下移") }
                        Button(onClick = { onPlayIndex(index) }) { Text("播放") }
                        Button(onClick = { onRemove(index) }) { Text("移除") }
                    }
                }
            }
            Button(
                modifier = Modifier.testTag("queue-clear"),
                enabled = playback.queue.isNotEmpty(),
                onClick = onClear,
            ) { Text("清空队列") }
        }
    }
}
