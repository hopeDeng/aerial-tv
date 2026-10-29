package app.orchard.tv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * The one badge for live TV messages: the channel name, a channel change, or an error. It always
 * sits in the same bottom-left spot so a transient message never draws a second label elsewhere.
 */
@Composable
fun TvInfoBadge(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = style.copy(color = Color.White.copy(alpha = 0.85f)),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
