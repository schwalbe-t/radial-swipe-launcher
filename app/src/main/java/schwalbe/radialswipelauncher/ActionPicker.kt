
package schwalbe.radialswipelauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

data class ActionPickerSettings(
    val title: String,
    val actions: List<Pair<String, () -> Unit>>,
    val onDismiss: () -> Unit
)

@Composable
fun ActionPicker(settings: ActionPickerSettings) {
    Dialog(onDismissRequest = settings.onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1C1B1F)
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = settings.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn {
                    items(settings.actions) { (label, actionCallback) ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settings.onDismiss()
                                    actionCallback()
                                }
                                .padding(vertical = 16.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = label,
                                color = Color.LightGray,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
