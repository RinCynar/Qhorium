package rj.qmce.lite.data.qzone

import android.content.Context
import android.os.Build
import android.util.Log
import com.qq.jce.wup.UniAttribute
import com.qq.taf.jce.JceInputStream
import com.qq.taf.jce.JceOutputStream
import com.tencent.mobileqq.msf.sdk.MsfSdkUtils
import com.tencent.qphone.base.BaseConstants
import com.tencent.qphone.base.remote.FromServiceMsg
import com.tencent.qphone.base.util.BaseApplication
import com.tencent.watch.qzone_impl.feed.model.BusinessFeedData
import com.tencent.watch.qzone_impl.utils.UinUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mqq.app.AppRuntime
import mqq.app.MobileQQ
import NS_MOBILE_FEEDS.mobile_feeds_req
import NS_MOBILE_FEEDS.mobile_feeds_rsp
import NS_MOBILE_FEEDS.single_feed
import QMF_PROTOCAL.QmfBusiControl
import QMF_PROTOCAL.QmfClientIpInfo
import QMF_PROTOCAL.QmfDownstream
import QMF_PROTOCAL.QmfTokenInfo
import QMF_PROTOCAL.QmfUpstream
import rj.qmce.lite.QmceApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.coroutines.resume

class QZoneFeedRepository {

    sealed interface RefreshResult {
        data object Success : RefreshResult
        data class Unavailable(val reason: String) : RefreshResult
        data object Cancelled : RefreshResult
    }

    private var lastAttachInfo: String = ""
    private var hasMore: Boolean = true
    private val currentFeeds = ArrayList<BusinessFeedData>()

    suspend fun refresh(
        isCurrent: () -> Boolean,
        onFeeds: (List<BusinessFeedData>, finishLoading: Boolean) -> Unit,
    ): RefreshResult = withContext(Dispatchers.IO) {
        val runtime = QmceApplication.ensureRuntime()
            ?: return@withContext RefreshResult.Unavailable("AppRuntime 不可用")
        val uin = runtime.longAccountUin.takeIf { it > 0 }
            ?: runCatching { UinUtils.b() }.getOrDefault(0L)
        if (uin <= 0L) {
            return@withContext RefreshResult.Unavailable("当前账号 UIN 无效")
        }
        val context = BaseApplication.getContext() ?: MobileQQ.sMobileQQ
            ?: return@withContext RefreshResult.Unavailable("Context 不可用")

        Log.d(TAG, "refresh: constructing getActiveFeeds packet for uin=$uin (WearQQ Next protocol)")
        val packet = runCatching {
            buildGetActiveFeedsPacket(context, uin, isRefresh = true, attachInfo = "")
        }.getOrElse { error ->
            Log.e(TAG, "failed to build packet", error)
            return@withContext RefreshResult.Unavailable("请求封包构造失败: ${error.message}")
        }

        if (!isCurrent()) return@withContext RefreshResult.Cancelled

        Log.d(TAG, "refresh: sending SQQzoneSvc.getActiveFeeds (size=${packet.size})")
        val response = sendPacket(runtime, "SQQzoneSvc.getActiveFeeds", packet, 20_000L)
        if (!isCurrent()) return@withContext RefreshResult.Cancelled
        if (response == null) {
            return@withContext RefreshResult.Unavailable("空间动态加载超时，请重试")
        }

        val parseResult = parseGetActiveFeedsResponse(response)
        if (!isCurrent()) return@withContext RefreshResult.Cancelled

        when (parseResult) {
            is ParseResult.Success -> {
                lastAttachInfo = parseResult.attachInfo
                hasMore = parseResult.hasMore
                currentFeeds.clear()
                currentFeeds.addAll(parseResult.feeds)
                Log.d(TAG, "refresh: success parsed ${parseResult.feeds.size} feeds, hasMore=$hasMore")
                onFeeds(parseResult.feeds, true)
                RefreshResult.Success
            }
            is ParseResult.Failure -> {
                Log.w(TAG, "refresh error from server: ${parseResult.reason}")
                RefreshResult.Unavailable(parseResult.reason)
            }
        }
    }

    suspend fun loadMore(
        previousSize: Int,
        onFeeds: (List<BusinessFeedData>, finishLoading: Boolean) -> Unit,
    ): Int? = withContext(Dispatchers.IO) {
        if (!hasMore || lastAttachInfo.isBlank()) {
            Log.d(TAG, "loadMore: no more data or empty attachInfo")
            return@withContext currentFeeds.size
        }
        val runtime = QmceApplication.ensureRuntime() ?: return@withContext null
        val uin = runtime.longAccountUin.takeIf { it > 0 }
            ?: runCatching { UinUtils.b() }.getOrDefault(0L)
        if (uin <= 0L) return@withContext null
        val context = BaseApplication.getContext() ?: MobileQQ.sMobileQQ ?: return@withContext null

        Log.d(TAG, "loadMore: request next page with attachInfo='$lastAttachInfo'")
        val packet = runCatching {
            buildGetActiveFeedsPacket(context, uin, isRefresh = false, attachInfo = lastAttachInfo)
        }.getOrNull() ?: return@withContext null

        val response = sendPacket(runtime, "SQQzoneSvc.getActiveFeeds", packet, 15_000L)
            ?: return@withContext null

        when (val parseResult = parseGetActiveFeedsResponse(response)) {
            is ParseResult.Success -> {
                lastAttachInfo = parseResult.attachInfo
                hasMore = parseResult.hasMore
                val existingIds = currentFeeds.mapNotNull { it.cellIdInfo?.cellId }.toSet()
                val newFeeds = parseResult.feeds.filter { feed ->
                    val id = feed.cellIdInfo?.cellId
                    id.isNullOrBlank() || id !in existingIds
                }
                currentFeeds.addAll(newFeeds)
                Log.d(TAG, "loadMore: appended ${newFeeds.size} new feeds, total=${currentFeeds.size}")
                onFeeds(currentFeeds.toList(), true)
                currentFeeds.size
            }
            is ParseResult.Failure -> {
                Log.w(TAG, "loadMore error: ${parseResult.reason}")
                null
            }
        }
    }

    fun close() {
        currentFeeds.clear()
        lastAttachInfo = ""
        hasMore = true
    }

    private suspend fun sendPacket(
        runtime: AppRuntime,
        ssoCmd: String,
        data: ByteArray,
        timeoutMs: Long,
    ): FromServiceMsg? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val context = BaseApplication.getContext() ?: MobileQQ.sMobileQQ
            val intent = QzoneFeedIntent(context, ssoCmd, data) { fromServiceMsg ->
                if (cont.isActive) {
                    cont.resume(fromServiceMsg)
                }
            }
            runtime.startServlet(intent)
        }
    }

    companion object {
        private const val TAG = "QMCE-QZoneFeed"
        private const val QUA = "V1_WAT_SQ_2.0.1_001_IDC_B"
        private const val APP_ID = 1000027

        fun buildGetActiveFeedsPacket(
            context: Context,
            uin: Long,
            isRefresh: Boolean,
            attachInfo: String = "",
        ): ByteArray {
            val displayMetrics = context.resources.displayMetrics
            val req = mobile_feeds_req().apply {
                i = if (isRefresh) 1 else 2
                j = 3
                k = 10
                l = attachInfo
                m = "screen_width=${displayMetrics.widthPixels}&screen_height=${displayMetrics.heightPixels}"
                n = hashMapOf(
                    19 to "0",
                    29 to "1",
                    42 to "0",
                    99 to "",
                    133 to "0",
                    145 to "1",
                )
                t = hashMapOf("HasGetSecretFeeds" to "0")
            }

            val uniAttribute = UniAttribute().apply {
                setEncodeName("utf-8")
                put("hostuin", uin)
                put("getActiveFeeds", req)
            }
            val encodedBusi = uniAttribute.encode()

            // Deflater compression
            val deflater = Deflater()
            deflater.setInput(encodedBusi)
            deflater.finish()
            val baos = ByteArrayOutputStream(encodedBusi.size)
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                baos.write(buffer, 0, count)
            }
            val deflatedBytes = baos.toByteArray()
            deflater.end()

            // busiCompCtl
            val ctlAttr = UniAttribute().apply {
                setEncodeName("utf-8")
                val busiControl = QmfBusiControl().apply {
                    b = 1
                    c = encodedBusi.size.toLong()
                    d = 1
                }
                put("busiCompCtl", busiControl)
            }
            val ctlBytes = ctlAttr.encode()

            val nextAppSeq = MsfSdkUtils.getNextAppSeq()
            val rawOffset = TimeZone.getDefault().rawOffset / 3600000
            val tzOffset = if (rawOffset > 0) "+$rawOffset" else "$rawOffset"
            val tzId = TimeZone.getDefault().id
            val deviceInfo = "i=null&imsi=&mac=null&m=${Build.MODEL}&o=${Build.VERSION.RELEASE}&a=${Build.VERSION.SDK_INT}&sc=1&sd=0&p=${displayMetrics.widthPixels}*${displayMetrics.heightPixels}&f=${Build.MANUFACTURER}&mm=1024&cf=0&cc=4&sharpP=0&qadid=&n=wan&longitude=&latitude=&coordtype=0&timezone=$tzOffset,id:$tzId"

            val tokenInfo = QmfTokenInfo(64, ByteArray(0), hashMapOf(1 to byteArrayOf(0)))
            val clientIp = QmfClientIpInfo(0.toByte(), 0.toShort(), 0, ByteArray(6))

            val qmfUpstream = QmfUpstream(
                nextAppSeq,
                APP_ID,
                uin,
                QUA,
                "QzoneNewService.getActiveFeeds",
                deviceInfo,
                tokenInfo,
                clientIp,
                deflatedBytes,
                ctlBytes,
                0L,
                0L,
                null,
            )

            val jceOutputStream = JceOutputStream().apply {
                setServerEncoding("UTF-8")
            }
            qmfUpstream.writeTo(jceOutputStream)
            val upstreamBytes = jceOutputStream.toByteArray()

            val out = ByteArrayOutputStream(upstreamBytes.size + 4)
            val dos = DataOutputStream(out)
            dos.writeInt(upstreamBytes.size + 4)
            dos.write(upstreamBytes)
            dos.flush()
            return out.toByteArray()
        }

        sealed interface ParseResult {
            data class Success(
                val feeds: List<BusinessFeedData>,
                val attachInfo: String,
                val hasMore: Boolean,
            ) : ParseResult

            data class Failure(val reason: String) : ParseResult
        }

        fun parseGetActiveFeedsResponse(response: FromServiceMsg): ParseResult {
            if (!response.isSuccess) {
                val code = response.resultCode
                val msg = response.businessFailMsg.orEmpty()
                return ParseResult.Failure("MSF 通信失败 ($code): $msg")
            }

            val wupBuffer = response.wupBuffer ?: return ParseResult.Failure("响应数据为空 (wupBuffer is null)")
            val dis = DataInputStream(ByteArrayInputStream(wupBuffer))
            val totalLen = dis.readInt()
            val bodyLen = totalLen - 4
            if (bodyLen <= 0 || bodyLen > wupBuffer.size - 4) {
                return ParseResult.Failure("QZone 响应包长度异常: $totalLen / ${wupBuffer.size}")
            }
            val bodyBytes = ByteArray(bodyLen)
            dis.readFully(bodyBytes)

            val qmfDownstream = QmfDownstream()
            qmfDownstream.readFrom(JceInputStream(bodyBytes))

            if (qmfDownstream.f != 0.toShort()) {
                return ParseResult.Failure("QZone WNS 返回码: ${qmfDownstream.f}")
            }

            val isCompressed = if (qmfDownstream.k != null && qmfDownstream.k.isNotEmpty()) {
                val ctlAttr = UniAttribute().apply {
                    setEncodeName("utf-8")
                    decode(qmfDownstream.k)
                }
                val ctl = runCatching { ctlAttr.get<QmfBusiControl?>("busiCompCtl") }.getOrNull()
                ctl?.b == 1
            } else false

            val payloadBytes = if (isCompressed && qmfDownstream.j != null && qmfDownstream.j.isNotEmpty()) {
                val inflater = Inflater()
                inflater.setInput(qmfDownstream.j)
                val baos = ByteArrayOutputStream(qmfDownstream.j.size * 2)
                val buf = ByteArray(1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        baos.write(buf, 0, count)
                    }
                }
                inflater.end()
                baos.toByteArray()
            } else {
                qmfDownstream.j ?: ByteArray(0)
            }

            val rspAttr = UniAttribute().apply {
                setEncodeName("utf-8")
                decode(payloadBytes)
            }

            val retNumber = runCatching { rspAttr.get<Number?>(BaseConstants.ATTR_KET_RET) }.getOrNull()
                ?: runCatching { rspAttr.get<Number?>("ret") }.getOrNull()
            val ret = retNumber?.toInt() ?: qmfDownstream.h.toInt()
            if (ret != 0) {
                val msg = runCatching { rspAttr.get<String?>("msg") }.getOrNull().orEmpty()
                return ParseResult.Failure("QZone 业务返回错误: ret=$ret msg=$msg")
            }

            val feedsRsp = runCatching {
                rspAttr.get<mobile_feeds_rsp?>("getActiveFeeds")
                    ?: rspAttr.getJceStruct("getActiveFeeds", mobile_feeds_rsp())
            }.getOrNull() ?: return ParseResult.Failure("未解析到 mobile_feeds_rsp 结构体")

            val rawFeeds = feedsRsp.k ?: arrayListOf()
            val attachInfo = feedsRsp.j.orEmpty()
            val hasMore = feedsRsp.i != 0

            val feedDataList = rawFeeds.mapNotNull { singleFeed ->
                runCatching {
                    val data = BusinessFeedData.createFrom(singleFeed, 0)
                        ?: BusinessFeedData.createFrom(singleFeed.e, singleFeed.j, singleFeed.h)
                    if (data != null) {
                        data.attachInfo = attachInfo
                        if (data.feedInfo.isNullOrBlank()) {
                            data.feedInfo = singleFeed.j?.takeIf { it.isNotBlank() } ?: singleFeed.h.orEmpty()
                        }
                    }
                    data
                }.getOrNull()
            }

            return ParseResult.Success(feedDataList, attachInfo, hasMore)
        }
    }
}
