package rj.qmce.lite.data.qzone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tencent.qqnt.account.login.api.ITicketRuntimeService
import com.tencent.watch.qzone_impl.common.result.IResult
import com.tencent.watch.qzone_impl.common.result.QZoneResult
import com.tencent.watch.qzone_impl.event.Event
import com.tencent.watch.qzone_impl.event.EventCenter
import com.tencent.watch.qzone_impl.event.EventSource
import com.tencent.watch.qzone_impl.event.IObserver
import com.tencent.watch.qzone_impl.feed.BaseResponseWrapper
import com.tencent.watch.qzone_impl.feed.IFeedManager
import com.tencent.watch.qzone_impl.feed.QZoneFeedService
import com.tencent.watch.qzone_impl.feed.ResultWrapper
import com.tencent.watch.qzone_impl.feed.ServiceCallbackWrapper
import com.tencent.watch.qzone_impl.feed.TaskWrapper
import com.tencent.watch.qzone_impl.feed.manage.FeedManager
import com.tencent.watch.qzone_impl.feed.model.BusinessFeedData
import com.tencent.watch.qzone_impl.utils.UinUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import mqq.app.MobileQQ
import mqq.manager.TicketManager
import oicq.wlogin_sdk.request.Ticket
import oicq.wlogin_sdk.request.WtTicketPromise
import oicq.wlogin_sdk.tools.ErrMsg
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class QZoneFeedRepository {

    sealed interface RefreshResult {
        data object Success : RefreshResult

        data class Unavailable(val reason: String) : RefreshResult

        data object Cancelled : RefreshResult
    }

    private var feedObserver: IObserver? = null
    private var observedFeedService: QZoneFeedService? = null
    private var feedService: QZoneFeedService? = null
    private val lastRequestFailure = AtomicReference<String?>(null)
    private val lastNetworkSucceeded = AtomicBoolean(false)
    private var refreshCompletion: CompletableDeferred<Unit>? = null

    suspend fun refresh(
        isCurrent: () -> Boolean,
        onFeeds: (List<BusinessFeedData>, finishLoading: Boolean) -> Unit,
    ): RefreshResult {
        when (val readiness = awaitQZoneReady(isCurrent)) {
            RefreshResult.Cancelled -> return RefreshResult.Cancelled
            is RefreshResult.Unavailable -> return readiness
            RefreshResult.Success -> Unit
        }

        val service = awaitFeedService(isCurrent)
            ?: return if (isCurrent()) {
                RefreshResult.Unavailable("QZoneFeedService 不可用")
            } else {
                RefreshResult.Cancelled
            }
        feedService = service

        val uin = runCatching { UinUtils.b() }.getOrDefault(0L)
        if (uin <= 0L) {
            return RefreshResult.Unavailable("账号未就绪")
        }
        service.m(uin, uin)
        runCatching { service.q() }
        Log.d(TAG, "feed service initialized, uin=$uin")
        attachRequestCallback(service, uin)
        registerFeedObserver(service, onFeeds)

        val feedManager = service.i ?: return RefreshResult.Unavailable("FeedManager 不可用")
        lastRequestFailure.set(null)
        lastNetworkSucceeded.set(false)
        val cached = feedManager.n()
        if (!cached.isNullOrEmpty()) {
            Log.d(TAG, "loaded ${cached.size} cached feeds")
            onFeeds(cached, false)
        }

        val deferred = CompletableDeferred<Unit>()
        refreshCompletion = deferred

        requestRefresh(feedManager, force = false)
        Log.d(TAG, "requested network feed refresh force=false")

        withTimeoutOrNull(6_000L) {
            deferred.await()
        }
        refreshCompletion = null

        if (!isCurrent()) return RefreshResult.Cancelled

        val fresh = feedManager.n()
        val finalSize = fresh?.size ?: 0
        Log.d(
            TAG,
            "feed refresh settled size=$finalSize " +
                "networkOk=${lastNetworkSucceeded.get()} failure=${lastRequestFailure.get()}",
        )
        if (!fresh.isNullOrEmpty()) {
            onFeeds(fresh, true)
            return RefreshResult.Success
        }

        val failure = lastRequestFailure.get()
        if (failure != null) {
            return RefreshResult.Unavailable(failure)
        }
        if (!lastNetworkSucceeded.get()) {
            return RefreshResult.Unavailable("空间动态加载超时，请重试")
        }

        onFeeds(emptyList(), true)
        return RefreshResult.Success
    }

    private fun requestRefresh(feedManager: IFeedManager?, force: Boolean) {
        if (feedManager == null) return
        val callback = ServiceCallbackWrapper().apply {
            a = WeakReference(Handler(Looper.getMainLooper()))
        }
        feedManager.j(0, callback, force)
    }

    private fun attachRequestCallback(service: QZoneFeedService, uin: Long) {
        val previousListener = service.j ?: (service.i as? IFeedManager.RequestCallbackListener)
        service.j = object : IFeedManager.RequestCallbackListener {
            override fun k(
                task: TaskWrapper?,
                result: ResultWrapper?,
                response: BaseResponseWrapper?,
                code: Int,
            ) {
                runCatching { previousListener?.k(task, result, response, code) }
                val respCode = runCatching { response?.a() }.getOrNull()
                val respMsg = runCatching { response?.c() }.getOrNull().orEmpty()
                Log.d(
                    TAG,
                    "feed callback k code=$code respCode=$respCode msg=$respMsg " +
                        "size=${runCatching { response?.b()?.size }.getOrNull()}",
                )

                if (code != 0) {
                    lastRequestFailure.set(
                        if (respMsg.isNotBlank()) "空间请求失败：$respMsg (code=$code)" else "空间请求失败 code=$code",
                    )
                } else if (respCode != null && respCode != 0) {
                    lastRequestFailure.set(
                        if (respMsg.isNotBlank()) "空间请求失败：$respMsg (ret=$respCode)" else "空间请求失败 ret=$respCode",
                    )
                } else {
                    lastRequestFailure.set(null)
                    lastNetworkSucceeded.set(true)
                }
                refreshCompletion?.complete(Unit)
            }

            override fun o(task: TaskWrapper?, result: ResultWrapper?) {
                runCatching { previousListener?.o(task, result) }
                val qzResult = result?.b as? QZoneResult
                val reason = qzResult?.message.orEmpty()
                val code = qzResult?.returnCode ?: 0
                Log.d(TAG, "feed callback o code=$code reason=$reason")
                if (code != 0 || reason.isNotBlank()) {
                    lastRequestFailure.set(if (reason.isNotBlank()) reason else "空间请求失败 code=$code")
                }
                refreshCompletion?.complete(Unit)
            }
        }
    }

    private suspend fun awaitQZoneReady(isCurrent: () -> Boolean): RefreshResult {
        repeat(READY_POLL_COUNT) {
            if (!isCurrent()) return RefreshResult.Cancelled
            val runtime = runCatching { MobileQQ.sMobileQQ?.peekAppRuntime() }.getOrNull()
            val uin = runCatching { UinUtils.b() }.getOrDefault(0L)
            val a2 = runCatching { UinUtils.a() }.getOrNull()
            if (runtime != null && uin > 0L && a2 != null && a2.isNotEmpty()) {
                Log.d(TAG, "qzone ready: uin=$uin, a2Bytes=${a2.size}, ensuring pskey...")
                ensureQZonePskey(uin)
                return RefreshResult.Success
            }
            delay(READY_POLL_INTERVAL_MILLIS)
        }
        val runtime = runCatching { MobileQQ.sMobileQQ?.peekAppRuntime() }.getOrNull()
        val uin = runCatching { UinUtils.b() }.getOrDefault(0L)
        val a2 = runCatching { UinUtils.a() }.getOrNull()
        return when {
            runtime == null -> RefreshResult.Unavailable("Runtime 未就绪")
            uin <= 0L -> RefreshResult.Unavailable("账号未就绪")
            a2 == null || a2.isEmpty() -> RefreshResult.Unavailable("A2 票据未就绪")
            else -> {
                ensureQZonePskey(uin)
                RefreshResult.Success
            }
        }
    }

    private suspend fun ensureQZonePskey(uin: Long): Boolean {
        val uinStr = uin.toString()
        val runtime = runCatching { MobileQQ.sMobileQQ?.peekAppRuntime() }.getOrNull()
        val ticketManager = runtime?.getManager(mqq.app.AppRuntime.TICKET_MANAGER) as? TicketManager

        val localPskey = runCatching { ticketManager?.getPskey(uinStr, "qzone.qq.com") }.getOrNull()
        if (!localPskey.isNullOrBlank()) {
            Log.d(TAG, "qzone pskey already available locally (len=${localPskey.length})")
            return true
        }

        val ticketSvc = runCatching {
            runtime?.getRuntimeService(ITicketRuntimeService::class.java, "")
        }.getOrNull()
        val svcLocalPskey = runCatching { ticketSvc?.getLocalPskey(uinStr, "qzone.qq.com") }.getOrNull()
        if (!svcLocalPskey.isNullOrBlank()) {
            Log.d(TAG, "qzone pskey already available via ticketSvc (len=${svcLocalPskey.length})")
            return true
        }

        Log.d(TAG, "requesting qzone pskey from WTLogin for uin=$uinStr")
        val acquired = withTimeoutOrNull(8_000L) {
            val deferred = CompletableDeferred<Boolean>()
            try {
                val reqResult = ticketManager?.getPskey(
                    uinStr,
                    16L,
                    arrayOf("qzone.qq.com", "qzone.com"),
                    object : WtTicketPromise {
                        override fun Done(ticket: Ticket?) {
                            val k = ticket?.getPSkey("qzone.qq.com")
                            val success = !k.isNullOrBlank()
                            Log.d(TAG, "WTLogin Done: qzone pskey acquired=$success")
                            deferred.complete(success)
                        }

                        override fun Failed(err: ErrMsg?) {
                            Log.w(TAG, "WTLogin Failed: qzone pskey failed: ${err?.message}")
                            deferred.complete(false)
                        }

                        override fun Timeout(err: ErrMsg?) {
                            Log.w(TAG, "WTLogin Timeout: qzone pskey timeout: ${err?.message}")
                            deferred.complete(false)
                        }
                    },
                )
                if (reqResult == null && ticketSvc != null) {
                    ticketSvc.getPskey(
                        uinStr,
                        16L,
                        arrayOf("qzone.qq.com", "qzone.com"),
                        object : WtTicketPromise {
                            override fun Done(ticket: Ticket?) {
                                val k = ticket?.getPSkey("qzone.qq.com")
                                val success = !k.isNullOrBlank()
                                Log.d(TAG, "ticketSvc Done: qzone pskey acquired=$success")
                                deferred.complete(success)
                            }

                            override fun Failed(err: ErrMsg?) {
                                Log.w(TAG, "ticketSvc Failed: qzone pskey failed: ${err?.message}")
                                deferred.complete(false)
                            }

                            override fun Timeout(err: ErrMsg?) {
                                Log.w(TAG, "ticketSvc Timeout: qzone pskey timeout: ${err?.message}")
                                deferred.complete(false)
                            }
                        },
                    )
                }
                deferred.await()
            } catch (e: Throwable) {
                Log.w(TAG, "error requesting qzone pskey", e)
                false
            }
        } ?: false

        return acquired || !ticketManager?.getPskey(uinStr, "qzone.qq.com").isNullOrBlank()
    }

    private suspend fun awaitFeedService(isCurrent: () -> Boolean): QZoneFeedService? {
        ensureFeedService()?.let { return it }
        repeat(16) {
            if (!isCurrent()) return null
            ensureFeedService()?.let { return it }
            delay(250)
        }
        return ensureFeedService() ?: run {
            Log.w(TAG, "QZoneFeedService still unavailable after wait")
            null
        }
    }

    private fun ensureFeedService(): QZoneFeedService? {
        QZoneFeedService.h()?.let { return it }
        feedService?.let { return it }
        val created = runCatching {
            val clazz = QZoneFeedService::class.java
            clazz.declaredConstructors
                .firstOrNull { it.parameterCount == 0 }
                ?.let { constructor ->
                    constructor.isAccessible = true
                    constructor.newInstance() as? QZoneFeedService
                }
        }.onFailure { error ->
            Log.w(TAG, "failed to construct QZoneFeedService", error)
        }.getOrNull()
        if (created != null) {
            feedService = created
            Log.d(TAG, "constructed local QZoneFeedService instance")
        }
        return QZoneFeedService.h() ?: created
    }

    suspend fun loadMore(
        previousSize: Int,
        onFeeds: (List<BusinessFeedData>, finishLoading: Boolean) -> Unit,
    ): Int? {
        val service = feedService ?: return null
        service.n(Handler(Looper.getMainLooper()))

        var fresh: List<BusinessFeedData>? = null
        var lastFingerprint = feedFingerprint(service.i?.n().orEmpty())
        repeat(POLL_COUNT) {
            delay(POLL_INTERVAL_MILLIS)
            val candidate = service.i?.n()
            if (!candidate.isNullOrEmpty()) {
                fresh = candidate
                val fingerprint = feedFingerprint(candidate)
                if (fingerprint != lastFingerprint || candidate.size > previousSize) {
                    onFeeds(candidate, true)
                    lastFingerprint = fingerprint
                    if (candidate.size > previousSize) return candidate.size
                }
            }
        }
        return fresh?.size ?: 0
    }

    fun close() {
        feedObserver?.let { observer ->
            runCatching { EventCenter.b().g(observer) }
        }
        feedObserver = null
        observedFeedService = null
        feedService?.j = null
        feedService = null
        lastRequestFailure.set(null)
    }

    private fun registerFeedObserver(
        service: QZoneFeedService,
        onFeeds: (List<BusinessFeedData>, finishLoading: Boolean) -> Unit,
    ) {
        if (feedObserver != null && observedFeedService === service) return
        runCatching {
            feedObserver?.let { observer ->
                runCatching { EventCenter.b().g(observer) }
            }
            val observer = object : IObserver.main {
                override fun n(event: Event) {
                    Log.d(TAG, "feed event: type=${event.a}")
                    if (event.a == FEED_EVENT_FAILED) {
                        Log.d(TAG, "feed event failed (777) received")
                        if (lastRequestFailure.get() == null) {
                            lastRequestFailure.set("空间请求失败，请稍后重试")
                        }
                        lastNetworkSucceeded.set(false)
                        refreshCompletion?.complete(Unit)
                        return
                    }
                    if (event.a != FEED_EVENT_UPDATED && event.a != FEED_EVENT_REFRESHED) return
                    lastNetworkSucceeded.set(true)
                    lastRequestFailure.set(null)
                    val feedsFromEvent = runCatching {
                        (event.c as? Array<*>)?.getOrNull(0) as? List<BusinessFeedData>
                    }.getOrNull()
                    val feeds = feedsFromEvent ?: service.i?.n()
                    Log.d(TAG, "feed event type=${event.a} size=${feeds?.size ?: 0}")
                    if (!feeds.isNullOrEmpty()) onFeeds(feeds, true)
                    refreshCompletion?.complete(Unit)
                }
            }
            feedObserver = observer
            observedFeedService = service
            EventCenter.b()
                .a(observer, 0, EventSource("Feed", null), FEED_EVENT_UPDATED, FEED_EVENT_REFRESHED, FEED_EVENT_FAILED)
            Log.d(TAG, "registered feed observer")
        }.onFailure { error ->
            Log.e(TAG, "failed to register feed observer", error)
        }
    }

    private fun feedFingerprint(list: List<BusinessFeedData>): String =
        list.joinToString("|") { data ->
            val id = runCatching { data.cellIdInfo?.cellId }.getOrNull().orEmpty()
            val time = runCatching { data.cellFeedCommInfo?.time }.getOrNull() ?: 0L
            val summary = runCatching { data.getCellSummaryV2()?.summary }.getOrNull().orEmpty()
            val title = runCatching { data.cellTitleInfo?.title }.getOrNull().orEmpty()
            val nick = runCatching { data.cellUserInfo?.user?.nickName }.getOrNull().orEmpty()
            val likes = runCatching { data.cellLikeInfo?.likeNum }.getOrNull() ?: 0
            val comments = runCatching {
                data.cellCommentInfo?.c.orEmpty().joinToString(",") { comment ->
                    "${comment.commentid}:${comment.user?.uin}:${comment.comment}:${comment.replies?.size ?: 0}"
                }
            }.getOrNull().orEmpty()
            val pictures = runCatching {
                data.cellPictureInfo?.pics.orEmpty().joinToString(",") {
                    it.currentUrl?.url ?: it.bigUrl?.url ?: it.originUrl?.url.orEmpty()
                }
            }.getOrNull().orEmpty()
            val video = runCatching { data.cellVideoInfo?.videoUrl?.url }.getOrNull().orEmpty()
            val original = runCatching {
                data.originalInfo?.let { originalData ->
                    "${originalData.cellUserInfo?.user?.nickName}:${originalData.getCellSummaryV2()?.summary}:${originalData.cellTitleInfo?.title}"
                }
            }.getOrNull().orEmpty()
            "$id:$time:$nick:$summary:$title:$likes:$comments:$pictures:$video:$original"
        }

    private companion object {
        private const val TAG = "QMCE-QZoneFeed"
        private const val FEED_EVENT_UPDATED = 1
        private const val FEED_EVENT_REFRESHED = 4
        private const val FEED_EVENT_FAILED = 777
        private const val POLL_COUNT = 24
        private const val POLL_INTERVAL_MILLIS = 500L
        private const val FORCE_REFRESH_AFTER_ROUND = 6
        private const val READY_POLL_COUNT = 20
        private const val READY_POLL_INTERVAL_MILLIS = 250L
    }
}
