package rj.qmce.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val UnreadBadgeShape = RoundedCornerShape(50)

private val timeFormatToday = SimpleDateFormat("HH:mm", Locale.getDefault())
private val timeFormatOther = SimpleDateFormat("MM/dd", Locale.getDefault())

@Volatile
private var cachedMidnightMillis: Long = 0L

private fun getMidnightMillis(): Long {
    val now = System.currentTimeMillis()
    if (now >= cachedMidnightMillis + 86_400_000L || now < cachedMidnightMillis) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        cachedMidnightMillis = cal.timeInMillis
    }
    return cachedMidnightMillis
}

/** Recent-contact row for the chat list (Wear M3 Button). */
@Composable
fun ChatItem(
    contact: RecentContactInfo,
    reportParams: Map<String, *> = emptyMap<String, Any?>(),
    reuseIdentifier: String? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val isGroup = contact.chatType == 2
    val pinned = contact.topFlag.toInt() == 1
    val muted = contact.isMsgDisturb ||
        (contact.shieldFlag != 0L && contact.shieldFlag != 1L)
    val unreadCount = contact.unreadCnt.coerceAtLeast(0L)

    val name = remember(contact.chatType, contact.peerName, contact.remark, contact.memberName, contact.id) {
        if (isGroup) {
            contact.peerName?.takeIf { it.isNotBlank() } ?: contact.id ?: "未知群"
        } else {
            contact.remark?.takeIf { it.isNotBlank() }
                ?: contact.peerName?.takeIf { it.isNotBlank() }
                ?: contact.memberName?.takeIf { it.isNotBlank() }
                ?: contact.id ?: "未知"
        }
    }

    val preview = remember(contact.chatType, contact.abstractContent, contact.sendRemarkName, contact.sendMemberName, contact.sendNickName, contact.senderUin) {
        val abstract = contact.abstractContent
            ?.joinToString("") { it.content ?: "" }
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        buildMessagePreview(isGroup = isGroup, contact = contact, abstract = abstract)
    }

    val timeStr = remember(contact.msgTime) {
        formatTime(contact.msgTime)
    }

    val avatarModel = remember(contact.chatType, contact.id, contact.avatarPath, contact.avatarUrl) {
        val rawPath = contact.avatarPath
        when {
            !rawPath.isNullOrBlank() -> {
                val clean = rawPath.removePrefix("file://")
                File(clean)
            }
            !contact.avatarUrl.isNullOrBlank() -> contact.avatarUrl
            isGroup -> "https://p.qlogo.cn/gh/${contact.id}/${contact.id}/100"
            else -> "https://q1.qlogo.cn/g?b=qq&nk=${contact.id}&s=100"
        }
    }

    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pinned) {
                scheme.primaryContainer
            } else {
                scheme.surfaceContainerHigh
            },
            contentColor = if (pinned) scheme.onPrimaryContainer else scheme.onSurface,
            secondaryContentColor = if (pinned) {
                scheme.onPrimaryContainer.copy(alpha = 0.8f)
            } else {
                scheme.onSurfaceVariant
            },
        ),
        contentPadding = ButtonDefaults.ButtonWithExtraLargeIconContentPadding,
        icon = {
            Box {
                AsyncImage(
                    model = avatarModel,
                    contentDescription = null,
                    modifier = Modifier
                        .size(ButtonDefaults.ExtraLargeIconSize)
                        .clip(CircleShape)
                        .background(scheme.surfaceContainer, CircleShape),
                    contentScale = ContentScale.Crop,
                )
                if (unreadCount > 0L) {
                    val badgeText = if (unreadCount > 99L) "99+" else unreadCount.toString()
                    val badgeBg = if (muted) scheme.outline else scheme.primary
                    val badgeFg = if (muted) scheme.surfaceContainerHigh else scheme.onPrimary
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .heightIn(min = 14.dp)
                            .widthIn(min = 14.dp)
                            .background(badgeBg, UnreadBadgeShape)
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeFg,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        secondaryLabel = {
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pinned) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.Flag,
                    contentDescription = "置顶",
                    tint = scheme.primary,
                    modifier = Modifier.size(12.dp),
                )
            }
            if (timeStr.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timeStr,
                    color = scheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Group preview sender order aligns with official MsgSummaryUtil (NT order):
 * sendRemarkName → sendMemberName → sendNickName → senderUin.
 * C2C shows abstract only.
 */
private fun buildMessagePreview(
    isGroup: Boolean,
    contact: RecentContactInfo,
    abstract: String,
): String {
    if (abstract.isBlank()) return ""
    if (!isGroup) return abstract
    val sender = contact.sendRemarkName?.takeIf { it.isNotBlank() }
        ?: contact.sendMemberName?.takeIf { it.isNotBlank() }
        ?: contact.sendNickName?.takeIf { it.isNotBlank() }
        ?: contact.senderUin.takeIf { it > 0L }?.toString()
    return if (sender.isNullOrBlank()) abstract else "$sender: $abstract"
}

private fun formatTime(msgTime: Long): String {
    if (msgTime <= 0) return ""
    val timeMs = msgTime * 1000L
    val midnight = getMidnightMillis()
    val isToday = timeMs >= midnight && timeMs < midnight + 86_400_000L
    val formatter = if (isToday) timeFormatToday else timeFormatOther
    return synchronized(formatter) {
        formatter.format(Date(timeMs))
    }
}
