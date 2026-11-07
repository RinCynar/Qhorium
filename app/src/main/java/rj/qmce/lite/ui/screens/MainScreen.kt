package rj.qmce.lite.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.HorizontalPagerScaffold
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rj.qmce.lite.QmceApplication
import rj.qmce.lite.kernel.KernelBridge
import rj.qmce.lite.viewmodel.ChatDetailViewModel
import rj.qmce.lite.viewmodel.ChatListViewModel
import rj.qmce.lite.viewmodel.ContactsViewModel
import rj.qmce.lite.viewmodel.MyViewModel
import rj.qmce.lite.viewmodel.QZoneViewModel
@OptIn(
    androidx.wear.compose.foundation.ExperimentalWearFoundationApi::class,
)
@Composable
fun MainScreen(
    chatListVm: ChatListViewModel,
    chatDetailVm: ChatDetailViewModel,
    contactsVm: ContactsViewModel,
    qZoneVm: QZoneViewModel,
    myVm: MyViewModel,
    uin: String,
    runtime: mqq.app.AppRuntime?,
    showTimeText: Boolean,
    showPageIndicator: Boolean,
    onPageChanged: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogoutConfirmation: () -> Unit,
    onForceExit: () -> Unit,
    onOpenQZoneComposer: () -> Unit,
    onOpenQZoneDetail: (rj.qmce.lite.viewmodel.QZoneViewModel.FeedItem) -> Unit,
    onLogout: () -> Unit,
    onOpenChat: (RecentContactInfo) -> Unit,
    onOpenChatFromContacts: (String, String, String, Int) -> Unit, // uid, uin, name, chatType
    onOpenGroupFromContacts: (ContactsViewModel.UiGroup) -> Unit,
    onOpenContactProfile: (ContactsViewModel.UiBuddy) -> Unit,
    onOpenNotificationCenter: () -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    var kernelRetryNonce by remember(uin) { mutableIntStateOf(0) }
    var contactsLoaded by remember(uin) { mutableStateOf(false) }
    var qzoneLoaded by remember(uin) { mutableStateOf(false) }
    var kernelReady by remember(uin) { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    LaunchedEffect(uin, runtime) {
        val activeRuntime = QmceApplication.ensureRuntime() ?: runtime ?: return@LaunchedEffect
        qZoneVm.init(activeRuntime)
    }

    LaunchedEffect(uin, runtime, kernelRetryNonce) {
        val activeRuntime = QmceApplication.ensureRuntime() ?: runtime ?: return@LaunchedEffect
        val timeouts = listOf(30_000L, 10_000L, 6_000L)
        timeouts.forEachIndexed { index, timeoutMillis ->
            val ready = withContext(Dispatchers.IO) {
                if (kernelRetryNonce == 0 && index == 0) {
                    KernelBridge.awaitCoreServices(timeoutMillis, activeRuntime)
                } else {
                    KernelBridge.retryCoreServices(timeoutMillis, activeRuntime)
                }
            }
            android.util.Log.d("QMCE", "MainScreen: core services ready=$ready attempt=${index + 1}")
            if (ready) {
                kernelReady = true
                chatListVm.loadContacts(activeRuntime)
                // Contacts / QZone load lazily when their tab is first opened.
                return@LaunchedEffect
            }
            if (index < timeouts.lastIndex) {
                chatListVm.markWaitingForKernel()
                contactsVm.markWaitingForKernel()
                kotlinx.coroutines.delay(2_000L)
            }
        }
        chatListVm.markKernelInitFailed()
        contactsVm.markKernelInitFailed()
        // Kernel 失败时仍允许首次进入空间页时加载
        kernelReady = true
    }

    LaunchedEffect(pagerState.currentPage, kernelReady, uin, runtime) {
        if (!kernelReady) return@LaunchedEffect
        val activeRuntime = QmceApplication.ensureRuntime() ?: runtime ?: return@LaunchedEffect
        when (pagerState.currentPage) {
            1 -> if (!contactsLoaded) {
                contactsLoaded = true
                contactsVm.loadBuddies(activeRuntime, forceRefresh = true)
            }
            2 -> {
                val failedEmpty = qZoneVm.feeds.value.isEmpty() &&
                    qZoneVm.feedError.value != null &&
                    !qZoneVm.loading.value
                if (!qzoneLoaded || failedEmpty) {
                    qzoneLoaded = true
                    qZoneVm.loadFeeds(forceRefresh = true)
                }
            }
        }
    }

    val indicatorPagerState = rememberPagerState(
        initialPage = pagerState.currentPage,
        pageCount = { 4 },
    )
    LaunchedEffect(pagerState.currentPage) {
        if (indicatorPagerState.currentPage != pagerState.currentPage) {
            indicatorPagerState.scrollToPage(pagerState.currentPage)
        }
    }

    HorizontalPagerScaffold(
        pagerState = pagerState,
        pageIndicator = if (showPageIndicator) {
            {
                HorizontalPageIndicator(
                    pagerState = indicatorPagerState,
                )
            }
        } else null,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ChatListScreen(
                    uin = uin,
                    runtime = runtime,
                    isPageVisible = page == pagerState.currentPage,
                    onLogout = onLogout,
                    onOpenChat = onOpenChat,
                    onRetryKernel = { kernelRetryNonce++ },
                    onOpenNotificationCenter = onOpenNotificationCenter,
                    vm = chatListVm,
                )

                1 -> ContactsScreen(
                    vm = contactsVm,
                    onOpenChat = { uid, uin, name ->
                        onOpenChatFromContacts(uid, uin, name, 1)
                    },
                    onOpenGroup = onOpenGroupFromContacts,
                    onOpenProfile = onOpenContactProfile,
                    onRetryKernel = { kernelRetryNonce++ },
                )

                2 -> QZoneScreen(
                    vm = qZoneVm,
                    onOpenComposer = onOpenQZoneComposer,
                    onOpenDetail = onOpenQZoneDetail,
                )

                3 -> MyScreen(
                    uin = uin,
                    onOpenSettings = onOpenSettings,
                    onOpenLogoutConfirmation = onOpenLogoutConfirmation,
                    onForceExit = onForceExit,
                    vm = myVm,
                )
            }
        }
    }
}
