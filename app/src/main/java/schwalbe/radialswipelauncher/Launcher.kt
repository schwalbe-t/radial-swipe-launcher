
package schwalbe.radialswipelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextMeasurer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

class Launcher(val context: Context) {
    val configFile = File(this.context.filesDir, Config.PATH)
    var config: Config = Config.read(this.configFile) ?: Config()

    var width: Float = 0f
    var height: Float = 0f
    val vmin: Float
        get() = minOf(this.width, this.height)

    @Volatile
    var liveActiveTouches: List<Offset> = listOf()
    var activeTouches: List<Offset> = listOf()

    @Volatile
    var pinnedIcons: Map<String, Drawable> = mapOf()
    val folderTextPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    @Volatile
    var installedApps: List<AppInfo> = listOf()

    var currentFolder: Item.Folder = this.config.root
    var touchAnchor: Offset? = null
    var selectedItemIdx: Int? = null
    var selectedSinceTime: Long? = null

    val actionPicker = mutableStateOf<ActionPickerSettings?>(null)
    val appPicker = mutableStateOf<AppPickerSettings?>(null)
    val textPrompt = mutableStateOf<TextPromptSettings?>(null)

    companion object {
        val APP_LOAD_SCOPE = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    init {
        this.loadPinnedIcons()
        this.loadInstalledApps()
    }
}

private fun Launcher.onConfigChanged() {
    this.config.write(this.configFile)
}


private fun Launcher.loadPinnedIcons() {
    val appIds = mutableSetOf<String>()
    this.config.root.collectAppIds(out = appIds)
    val packageManager = this.context.packageManager
    val launcher: Launcher = this
    Launcher.APP_LOAD_SCOPE.launch(Dispatchers.IO) {
        val result = mutableMapOf<String, Drawable>()
        for (appId in appIds) {
            try {
                result[appId] = packageManager.getApplicationIcon(appId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        withContext(Dispatchers.Main) {
            launcher.pinnedIcons = result
        }
    }
}

private fun Launcher.loadInstalledApps() {
    val packageManager = this.context.packageManager
    val launcher: Launcher = this
    Launcher.APP_LOAD_SCOPE.launch(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos: List<ResolveInfo> = packageManager
            .queryIntentActivities(intent, 0)
        val apps = resolveInfos
            .map { info -> AppInfo(
                label = info.loadLabel(packageManager).toString(),
                packageName = info.activityInfo.packageName
            ) }
            .sortedBy { it.label.lowercase() }
        withContext(Dispatchers.Main) {
            launcher.installedApps = apps
        }
    }
}


// rotate everything 90 degrees counter-clockwise so that the first element
// is at the top
private const val FOLDER_ITEM_BASE_ANGLE: Float = -PI.toFloat() / 2f

private fun Item.Folder.angleOfItem(i: Int): Float
    = FOLDER_ITEM_BASE_ANGLE + i * (2 * PI / this.items.size).toFloat()

private const val MIN_REQ_SELECTION_DIST: Float = 0.1f

private fun Launcher.selectFolderItem(folder: Item.Folder, time: Long) {
    if (this.activeTouches.size != 1) {
        val selectedItemIdx: Int? = this.selectedItemIdx
        if (selectedItemIdx != null) {
            this.openSelectedItem(selectedItemIdx)
        }
        this.selectedItemIdx = null
        this.touchAnchor = null
        return
    }
    val activeTouch: Offset = this.activeTouches[0]
    val anchor: Offset? = this.touchAnchor
    if (anchor == null) {
        this.selectedItemIdx = null
        this.touchAnchor = activeTouch
        return
    }
    val dx: Float = activeTouch.x - anchor.x
    val dy: Float = activeTouch.y - anchor.y
    val dist: Float = hypot(dx, dy)
    val minReqDist: Float = this.vmin * MIN_REQ_SELECTION_DIST
    if (dist <= minReqDist) {
        this.selectedItemIdx = null
        return
    }
    val ndx: Float = dx / dist
    val ndy: Float = dy / dist
    val newSelected: Int? = folder.items.indices.maxByOrNull { i ->
        val itemAngle: Float = folder.angleOfItem(i)
        val itemNdx: Float = cos(itemAngle)
        val itemNdy: Float = sin(itemAngle)
        val dot: Float = ndx * itemNdx + ndy * itemNdy
        dot
    }
    if (newSelected != null && this.selectedItemIdx != newSelected) {
        this.selectedSinceTime = time
    }
    this.selectedItemIdx = newSelected
}

private fun Launcher.openSelectedItem(selectedIdx: Int) {
    val folder: Item.Folder = this.currentFolder
    val selectedItem: Item = folder.items.getOrNull(selectedIdx) ?: return
    this.selectedItemIdx = null
    when (selectedItem) {
        is Item.App -> {
            this.currentFolder = this.config.root
            val appId: String? = selectedItem.appId
            if (appId != null) {
                this.launchApp(appId)
            }
        }
        is Item.Folder -> {
            this.currentFolder = selectedItem
        }
    }
}

private fun Launcher.launchApp(appId: String) {
    try {
        val intent: Intent = this.context.packageManager
            .getLaunchIntentForPackage(appId)
            ?: return System.err.println("App '$appId' cannot be launched")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        this.context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

const val EDIT_SELECT_DELAY_MS: Long = 500

private fun Launcher.editSelected(time: Long) {
    val selectedIdx: Int? = this.selectedItemIdx
    if (selectedIdx == null) {
        this.selectedSinceTime = null
        return
    }
    val item: Item = this.currentFolder.items.getOrNull(selectedIdx) ?: return
    val selectedSinceTime: Long? = this.selectedSinceTime
    if (selectedSinceTime == null) {
        this.selectedSinceTime = time
        return
    }
    val selectedDuration: Long = time - selectedSinceTime
    if (selectedDuration < EDIT_SELECT_DELAY_MS) return
    fun onDismiss() {
        this.actionPicker.value = null
        this.appPicker.value = null
        this.textPrompt.value = null
    }
    fun onAppSelect(onSelected: (String) -> Unit) {
        this.appPicker.value = AppPickerSettings(
            "Select App", this.installedApps,
            onAppSelect = {
                onSelected(it)
                this.loadPinnedIcons()
            },
            onDismiss = ::onDismiss
        )
    }
    fun onFolderText(onSelected: (String) -> Unit) {
        this.textPrompt.value = TextPromptSettings(
            "Enter Folder Text", initialText = "",
            onSubmit = onSelected, onDismiss = ::onDismiss
        )
    }
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    when (item) {
        is Item.App -> actions.add("Change App" to {
            onAppSelect { appId ->
                item.appId = appId
                this.onConfigChanged()
            }
        })
        is Item.Folder -> actions.add("Change Text" to {
            onFolderText { text ->
                item.text = text
                this.onConfigChanged()
            }
        })
    }
    fun onInsert(onSelected: (Item) -> Unit) {
        this.actionPicker.value = ActionPickerSettings(
            title = "Select Type",
            actions = listOf(
                "App" to {
                    onAppSelect { appId -> onSelected(Item.App(appId)) }
                },
                "Folder" to {
                    onFolderText { text -> onSelected(Item.Folder(
                        text, mutableListOf(Item.App.settings())
                    )) }
                }
            ),
            onDismiss = ::onDismiss
        )
    }
    actions.add("Move Left" to {
        val items = this.currentFolder.items
        var leftIdx: Int = selectedIdx - 1
        if (leftIdx < 0) { leftIdx = items.size - 1 }
        items[selectedIdx] = items[leftIdx]
        items[leftIdx] = item
        this.onConfigChanged()
    })
    actions.add("Move Right" to {
        val items = this.currentFolder.items
        var rightIdx: Int = selectedIdx + 1
        if (rightIdx >= items.size) { rightIdx = 0 }
        items[selectedIdx] = items[rightIdx]
        items[rightIdx] = item
        this.onConfigChanged()
    })
    actions.add("Insert Left" to { onInsert { createdItem ->
        this.currentFolder.items.add(selectedIdx, createdItem)
        this.onConfigChanged()
    } })
    actions.add("Insert Right" to { onInsert { createdItem ->
        this.currentFolder.items.add(selectedIdx + 1, createdItem)
        this.onConfigChanged()
    } })
    actions.add("Delete" to {
        val items = this.currentFolder.items
        items.removeAt(selectedIdx)
        if (items.isEmpty()) {
            // fallback - insert random app so folders are never empty
            items.add(Item.App.settings())
        }
        this.onConfigChanged()
    })
    this.actionPicker.value = ActionPickerSettings(
        title = "Edit Item",
        actions = actions,
        onDismiss = ::onDismiss
    )
    this.selectedItemIdx = null
    this.selectedSinceTime = null
    this.touchAnchor = null
}


private fun Launcher.drawFolderItem(
    item: Item, size: Float, centerX: Float, centerY: Float,
    drawScope: DrawScope
) {
    val nativeCanvas = drawScope.drawContext.canvas.nativeCanvas
    when (item) {
        is Item.App -> {
            val sharedIcon: Drawable = this.pinnedIcons[item.appId] ?: return
            val icon: Drawable = sharedIcon.constantState
                ?.newDrawable()?.mutate() ?: sharedIcon
            val left: Int = (centerX - size / 2f).toInt()
            val top: Int = (centerY - size / 2f).toInt()
            val right: Int = left + size.toInt()
            val bottom: Int = top + size.toInt()
            icon.setBounds(left, top, right, bottom)
            icon.draw(nativeCanvas)
        }
        is Item.Folder -> {
            val paint = this.folderTextPaint
            paint.textSize = size
            val fontMetrics = paint.fontMetrics
            val verticalOffset = (fontMetrics.descent + fontMetrics.ascent) / 2f
            val destY = centerY - verticalOffset
            nativeCanvas.drawText(item.text, centerX, destY, paint)
        }
    }
}

private const val FOLDER_ITEM_DIST: Float = 0.75f
private const val FOLDER_ITEM_SIZE: Float = 0.1f
private const val FOLDER_ITEM_SELECTED_SIZE: Float = 2f // multiplier

private fun Launcher.drawFolder(
    folder: Item.Folder, drawScope: DrawScope
) {
    if (folder.items.isEmpty()) return
    val baseItemSize: Float = this.vmin * FOLDER_ITEM_SIZE
    val itemRadius: Float = baseItemSize / 2f
    val radius = (this.vmin / 2f - baseItemSize) * FOLDER_ITEM_DIST + itemRadius
    val centerX: Float = this.width / 2f
    val centerY: Float = this.height / 2f
    for (i in folder.items.indices) {
        val itemSize: Float = when (i) {
            this.selectedItemIdx -> baseItemSize * FOLDER_ITEM_SELECTED_SIZE
            else -> baseItemSize
        }
        val angle: Float = folder.angleOfItem(i)
        this.drawFolderItem(
            item = folder.items[i],
            size = itemSize,
            centerX = centerX + cos(angle) * radius,
            centerY = centerY + sin(angle) * radius,
            drawScope
        )
    }
}

fun Launcher.onDraw(drawScope: DrawScope, time: Long) {
    this.selectFolderItem(this.currentFolder, time)
    this.editSelected(time)
    this.drawFolder(this.currentFolder, drawScope)
}
