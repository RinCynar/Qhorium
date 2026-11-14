package rj.qmce.lite.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * Compact, non-intrusive network failure banner card for list tops on Wear OS.
 * Appears when offline or when background refresh fails, while preserving cached list contents underneath.
 */
@Composable
fun NetworkErrorBannerCard(
    message: String = "网络异常，点击重试",
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onRetry,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .height(36.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.errorContainer.copy(alpha = 0.88f),
            contentColor = scheme.onErrorContainer,
        ),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.WifiOff,
                contentDescription = "网络异常",
                modifier = Modifier.size(16.dp),
                tint = scheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onErrorContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
