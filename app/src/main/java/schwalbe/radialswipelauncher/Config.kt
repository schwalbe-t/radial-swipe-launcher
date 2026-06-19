@file:SuppressLint("UnsafeOptInUsageError")

package schwalbe.radialswipelauncher

import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import schwalbe.radialswipelauncher.Item.App
import java.io.File

@Serializable
sealed interface Item {

    @Serializable
    data class App(
        var appId: String
    ) : Item {
        companion object {
            fun settings() = App("com.android.settings")
        }
    }

    @Serializable
    data class Folder(
        var text: String,
        val items: MutableList<Item>
    ) : Item
}

@Serializable
data class Config(
    val root: Item.Folder = Item.Folder(
        "", mutableListOf(
            App.settings()
        )
    )
) {
    companion object {
        const val PATH: String = "config.json"
        val SERIALIZER = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
        val WRITE_SCOPE = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}

fun Config.Companion.read(file: File): Config? {
    try {
        if (!file.exists()) { return null }
        val raw: String = file.readText()
        val config: Config = Config.SERIALIZER.decodeFromString(raw)
        return config
    } catch(e: Exception) {
        e.printStackTrace()
        return null
    }
}

fun Config.write(file: File) {
    val raw: String = try {
        Config.SERIALIZER.encodeToString(this)
    } catch (e: Exception) {
        return e.printStackTrace()
    }
    Config.WRITE_SCOPE.launch(Dispatchers.IO) {
        try {
            file.writeText(raw)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun Item.collectAppIds(out: MutableSet<String>) {
    when (this) {
        is Item.App -> out.add(this.appId)
        is Item.Folder -> this.items.forEach { it.collectAppIds(out) }
    }
}
