
package schwalbe.radialswipelauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val rawState = remember { Launcher(context = this) }
            val frameTrigger = remember { mutableLongStateOf(0L) }

            LaunchedEffect(Unit) {
                while (true) {
                    withFrameMillis { time ->
                        frameTrigger.longValue = time
                    }
                }
            }

            Canvas(modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            rawState.liveActiveTouches = event.changes
                                .filter { it.pressed }
                                .map { it.position }
                        }
                    }
                }
            ) {
                rawState.width = this.size.width
                rawState.height = this.size.height
                rawState.activeTouches = rawState.liveActiveTouches
                rawState.onDraw(
                    drawScope = this,
                    time = frameTrigger.longValue
                )
            }

            rawState.actionPicker.value?.let {
                ActionPicker(it)
            }
            rawState.appPicker.value?.let {
                AppPicker(it)
            }
            rawState.textPrompt.value?.let {
                TextPrompt(it)
            }
        }
    }
}
