package rj.qmce.lite.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import kotlinx.coroutines.delay
import mqq.app.AppRuntime
import rj.qmce.lite.data.reporting.OfficialReportBridge
import rj.qmce.lite.ui.components.ChatItem
import rj.qmce.lite.ui.wear.QmceEmptyOrErrorState
import rj.qmce.lite.ui.wear.QmceLoadingState
import rj.qmce.lite.ui.wear.QmceScreenScaffold
import rj.qmce.lite.util.PerfTrace
import rj.qmce.lite.viewmodel.ChatListViewModel

private const val KERNEL_INIT_ACTION = "com.tencent.mobileqq.action.ON_KERNEL_INIT_COMPLETE"

@Composable
fun ChatListScreen(
    uin: String,
    runtime: AppRuntime?,
    isPageVisible: Boolean,
    onLogout: () -> Unit,
    onOpenChat: (RecentContactInfo) -> Unit,
    onRetryKernel: () -> Unit,
    onOpenNotificationCenter: () -> Unit = {},
    vm: ChatListViewModel = viewModel()
) {
    val context = LocalContext.current
    val contactsSnapshot by vm.contacts.collectAsState()
    val contacts = contactsSnapshot.contacts
    val statusText by vm.statusText.collectAsState()
    val listState = rememberScalingLazyListState()
    val reportUid = runCatching { runtime?.currentUid.orEmpty() }.getOrDefault("")

    LaunchedEffect(Unit) {
        PerfTrace.mark("ChatListScreen", "ChatListScreen first composed")
    }

    // Scroll-gating: freeze ViewModel emission during gesture
    LaunchedEffect(listState.isScrollInProgress) {
        vm.onScrollStateChanged(listState.isScrollInProgress)
    }

    DisposableEffect(runtime) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                Log.d("QMCE", "ChatList: ON_KERNEL_INIT_COMPLETE received, loading contacts")
                vm.loadContacts(runtime)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(KERNEL_INIT_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // Exposure tracking executed strictly when scroll is stationary
    LaunchedEffect(listState.isScrollInProgress, isPageVisible, contacts) {
        if (!isPageVisible || listState.isScrollInProgress || contacts.isEmpty()) return@LaunchedEffect
        delay(400L)
        if (listState.isScrollInProgress) return@LaunchedEffect
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        for (item in visibleItems) {
            val contactIndex = item.index - 1
            val contact = contacts.getOrNull(contactIndex) ?: continue
            OfficialReportBridge.reportChatListItemExposure(
                contact = contact,
                homeUin = uin,
                uid = reportUid,
                firstExposure = false,
            )
        }
    }

    when {
        contacts.isNotEmpty() -> {
            val listBody: @Composable BoxScope.(androidx.compose.foundation.layout.PaddingValues) -> Unit =
                { contentPadding ->
                    ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = contentPadding,
                        autoCentering = AutoCenteringParams(itemIndex = 1),
                    ) {
                        item(key = "notification-bell") {
                            Button(
                                onClick = onOpenNotificationCenter,
                                modifier = Modifier.fillMaxWidth(),
                                icon = {
                                    Icon(
                                        Icons.Outlined.Notifications,
                                        contentDescription = "通知中心",
                                    )
                                },
                            ) {
                                Text("通知中心")
                            }
                        }
                        items(
                            items = contacts,
                            key = { contact ->
                                "chat:${chatListExposureKey(contact) ?: contact.hashCode()}"
                            },
                        ) { contact ->
                            val exposureKey = remember(contact) { chatListExposureKey(contact) }
                            val elementParams = remember(contact, uin, reportUid) {
                                OfficialReportBridge.chatListItemElementParams(
                                    contact = contact,
                                    homeUin = uin,
                                    uid = reportUid,
                                )
                            }
                            ChatItem(
                                contact = contact,
                                reportParams = elementParams,
                                reuseIdentifier = exposureKey,
                                isScrolling = listState.isScrollInProgress,
                                onClick = {
                                    OfficialReportBridge.reportChatListItemClick(
                                        target = null,
                                        contact = contact,
                                        homeUin = uin,
                                        uid = reportUid,
                                    )
                                    onOpenChat(contact)
                                },
                            )
                        }
                    }
                }
            QmceScreenScaffold(
                scrollState = listState,
                content = listBody,
            )
        }

        statusText.contains("失败") || statusText.contains("不可用") -> {
            QmceEmptyOrErrorState(
                message = statusText.ifBlank { "会话加载失败" },
                actionLabel = "重试",
                onAction = onRetryKernel,
                isError = true,
            )
        }

        statusText.isBlank() || statusText.contains("加载") || statusText.contains("等待") -> {
            QmceLoadingState(message = statusText.ifBlank { "加载会话…" })
        }

        else -> {
            QmceEmptyOrErrorState(
                message = statusText.ifBlank { "暂无会话" },
                actionLabel = "刷新",
                onAction = { vm.refreshContacts() },
            )
        }
    }
}

private fun chatListExposureKey(contact: RecentContactInfo): String? {
    return contact.contactId.takeIf { it > 0L }?.toString()
        ?: contact.id?.takeIf { it.isNotBlank() }
        ?: contact.peerUid?.takeIf { it.isNotBlank() }
}
