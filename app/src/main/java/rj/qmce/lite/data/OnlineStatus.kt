package rj.qmce.lite.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tencent.qqnt.kernel.api.IProfileService
import com.tencent.qqnt.kernel.api.impl.ProfileService
import com.tencent.qqnt.kernel.nativeinterface.CoreInfo
import com.tencent.qqnt.kernel.nativeinterface.IKernelProfileListener
import com.tencent.qqnt.kernel.nativeinterface.IKernelProfileService
import com.tencent.qqnt.kernel.nativeinterface.StatusInfo
import com.tencent.qqnt.kernel.nativeinterface.UserDetailInfo
import com.tencent.qqnt.kernel.nativeinterface.UserSimpleInfo
import rj.qmce.lite.kernel.KernelBridge
import rj.qmce.lite.kernel.SdkCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "QMCE"

/**
 * 在线状态缓存。管理当前登录账号的自身在线状态与长连接状态。
 *
 * 1. start() 注册 IKernelProfileListener + 启动状态轮询与自身状态拉取
 * 2. 监听 MSF 连接状态与 IKernelProfileListener 回调
 * 3. UI 通过 addObserver 注册监听，在主线程响应式刷新
 */
object OnlineStatus {
    enum class TermKind {
        Phone,
        Computer,
        Tablet,
        Watch,
        Unknown,
    }

    private val cache = ConcurrentHashMap<String, StatusInfo>()
    private val observers = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false
    @Volatile
    private var selfUid: String = ""
    @Volatile
    private var selfUin: Long = 0L
    @Volatile
    private var currentSelfStatus: StatusInfo? = null
    @Volatile
    private var isMsfConnected: Boolean = false
    @Volatile
    private var nativeService: IKernelProfileService? = null

    fun addObserver(cb: () -> Unit) {
        if (cb !in observers) observers.add(cb)
    }

    fun removeObserver(cb: () -> Unit) {
        observers.remove(cb)
    }

    /** 是否已确定在线状态（区分未初始化和已就绪） */
    fun known(): Boolean = currentSelfStatus != null ||
        (selfUid.isNotEmpty() && cache.containsKey(selfUid)) ||
        isMsfConnected ||
        KernelBridge.areCoreServicesReady()

    fun isOnline(): Boolean {
        currentSelfStatus?.let { s ->
            return s.status != 0 && s.status != 20
        }
        if (selfUid.isNotEmpty()) {
            cache[selfUid]?.let { s ->
                return s.status != 0 && s.status != 20
            }
        }
        return isMsfConnected || KernelBridge.areCoreServicesReady()
    }

    fun statusInfo(): StatusInfo? = currentSelfStatus ?: if (selfUid.isNotEmpty()) cache[selfUid] else null

    /** "手机在线" / "在线" / "离线" / null（未知） */
    fun describe(): String? {
        val s = statusInfo()
        val d = s?.termDesc
        if (!d.isNullOrEmpty()) return d
        return if (isOnline()) "在线" else "离线"
    }

    /** 主终端类型；离线返回 null。 */
    fun termKind(): TermKind? {
        val s = statusInfo()
        if (s != null) {
            if (s.status == 0 || s.status == 20) return null
            return kindForTerm(s.termType, s.iconType, s.termDesc)
        }
        return if (isOnline()) TermKind.Watch else null
    }

    fun onMsfConnectionChanged(connected: Boolean) {
        isMsfConnected = connected
        notifyObservers()
        if (connected) {
            refreshStatusInfo()
        }
    }

    fun start(profileService: IProfileService, uinOrUid: String) {
        val numericUin = uinOrUid.toLongOrNull()
        if (numericUin != null && numericUin > 0L) {
            selfUin = numericUin
        } else if (uinOrUid.isNotEmpty()) {
            selfUid = uinOrUid
        }

        try {
            SdkCompat.addProfileListener(profileService, Listener)
            val native = readNativeProfileService(profileService)
            nativeService = native
            native?.startStatusPolling(true)
            started = true

            // 若持有 UIN 但无 UID，尝试直接解析
            if (selfUid.isEmpty() && numericUin != null && native != null) {
                runCatching {
                    val uidMap = native.getUidByUin("qmce", arrayListOf(numericUin))
                    val resolvedUid = uidMap?.get(numericUin)
                    if (!resolvedUid.isNullOrEmpty()) {
                        selfUid = resolvedUid
                        Log.d(TAG, "OnlineStatus: resolved uid=$resolvedUid for uin=$numericUin")
                    }
                }
            }

            refreshStatusInfo()
            // 登录后多阶重试，加速首屏状态同步
            mainHandler.postDelayed({ refreshStatusInfo() }, 500L)
            mainHandler.postDelayed({ refreshStatusInfo() }, 1_500L)
            mainHandler.postDelayed({ refreshStatusInfo() }, 3_000L)
            Log.d(TAG, "OnlineStatus: started, uinOrUid=$uinOrUid selfUid=$selfUid selfUin=$selfUin")
        } catch (e: Throwable) {
            Log.w(TAG, "OnlineStatus: start failed", e)
        }
    }

    fun refreshStatusInfo() {
        val native = nativeService ?: return
        if (selfUid.isEmpty() && selfUin > 0L) {
            runCatching {
                val uidMap = native.getUidByUin("qmce", arrayListOf(selfUin))
                val resolvedUid = uidMap?.get(selfUin)
                if (!resolvedUid.isNullOrEmpty()) {
                    selfUid = resolvedUid
                }
            }
        }

        runCatching {
            native.getSelfStatus { err, _ ->
                if (err != 0) {
                    Log.d(TAG, "OnlineStatus: getSelfStatus err=$err")
                }
            }
        }

        if (selfUid.isNotEmpty()) {
            runCatching {
                val map = native.getStatusInfo("qmce", arrayListOf(selfUid))
                if (!map.isNullOrEmpty()) {
                    merge(map)
                    notifyObservers()
                }
            }.onFailure {
                Log.w(TAG, "OnlineStatus: getStatusInfo failed", it)
            }
        }
    }

    private fun readNativeProfileService(profileService: IProfileService): IKernelProfileService? {
        return runCatching {
            profileService.javaClass.getMethod("getService").invoke(profileService) as? IKernelProfileService
        }.getOrNull() ?: runCatching {
            (profileService as? ProfileService)?.service
        }.getOrNull()
    }

    private fun merge(map: HashMap<String, StatusInfo>) {
        for ((uid, info) in map) {
            if (!uid.isNullOrEmpty()) {
                cache[uid] = info
                if (uid == selfUid || (selfUin > 0L && info.uin == selfUin)) {
                    currentSelfStatus = info
                    if (selfUid.isEmpty()) selfUid = uid
                }
            }
        }
    }

    private fun notifyObservers() {
        mainHandler.post { observers.forEach { runCatching { it() } } }
    }

    private fun kindForTerm(termType: Int, iconType: Int, termDesc: String?): TermKind {
        val desc = termDesc.orEmpty()
        when {
            desc.contains("手表") || desc.contains("Watch", ignoreCase = true) ->
                return TermKind.Watch
            desc.contains("平板") || desc.contains("Pad", ignoreCase = true) ||
                desc.contains("iPad", ignoreCase = true) ->
                return TermKind.Tablet
            desc.contains("电脑") || desc.contains("PC", ignoreCase = true) ||
                desc.contains("Windows", ignoreCase = true) ||
                desc.contains("Mac", ignoreCase = true) ->
                return TermKind.Computer
            desc.contains("手机") || desc.contains("Phone", ignoreCase = true) ->
                return TermKind.Phone
        }
        return when (termType) {
            1, 6 -> TermKind.Computer
            2 -> TermKind.Phone
            3, 4, 5 -> TermKind.Tablet
            8, 9 -> TermKind.Watch
            else -> when (iconType) {
                1, 6 -> TermKind.Computer
                2 -> TermKind.Phone
                3, 4, 5 -> TermKind.Tablet
                8, 9 -> TermKind.Watch
                else -> TermKind.Phone
            }
        }
    }

    private object Listener : IKernelProfileListener {
        override fun onStatusUpdate(map: HashMap<String, StatusInfo>?) {
            if (!map.isNullOrEmpty()) {
                merge(map)
                notifyObservers()
            }
        }

        override fun onStatusAsyncFieldUpdate(map: HashMap<String, StatusInfo>?) {
            if (!map.isNullOrEmpty()) {
                merge(map)
                notifyObservers()
            }
        }

        override fun onSelfStatusChanged(statusInfo: StatusInfo?) {
            if (statusInfo != null) {
                currentSelfStatus = statusInfo
                val uid = statusInfo.uid
                if (!uid.isNullOrEmpty()) {
                    selfUid = uid
                    cache[uid] = statusInfo
                }
                notifyObservers()
            }
        }

        override fun onProfileSimpleChanged(map: HashMap<String, UserSimpleInfo>?) {}
        override fun onStrangerRemarkChanged(map: HashMap<String, CoreInfo>?) {}
        override fun onUserDetailInfoChanged(userDetailInfo: UserDetailInfo?) {}
    }
}
