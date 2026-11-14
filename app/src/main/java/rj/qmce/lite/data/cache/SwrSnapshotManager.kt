package rj.qmce.lite.data.cache

import android.content.Context
import android.util.Log
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.kernelpublic.nativeinterface.MsgAbstractElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import rj.qmce.lite.viewmodel.ContactsViewModel
import rj.qmce.lite.viewmodel.QZoneViewModel
import java.io.File

/**
 * Unified, non-reflective SWR persistent snapshot cache manager.
 * Stores lightweight JSON snapshots in context.filesDir isolated by logged UIN.
 * Ensures zero-wait cold startup across Chat, Contacts, and QZone screens.
 */
object SwrSnapshotManager {
    private const val TAG = "QMCE-SWR"
    private const val MAX_CHAT_SNAPSHOT_SIZE = 50
    private const val MAX_QZONE_SNAPSHOT_SIZE = 20

    private fun getFile(context: Context, prefix: String, uin: String): File {
        val safeUin = uin.ifBlank { "0" }
        return File(context.filesDir, "swr_${prefix}_${safeUin}.json")
    }

    private fun atomicWrite(targetFile: File, content: String) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            tempFile.writeText(content, Charsets.UTF_8)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
        } catch (e: Exception) {
            Log.w(TAG, "atomicWrite failed for ${targetFile.name}", e)
            tempFile.delete()
        }
    }

    // ==========================================
    // 1. Chat List SWR Snapshot
    // ==========================================

    suspend fun saveChatSnapshot(
        context: Context,
        uin: String,
        contacts: List<RecentContactInfo>,
    ) = withContext(Dispatchers.IO) {
        if (uin.isBlank() || contacts.isEmpty()) return@withContext
        runCatching {
            val array = JSONArray()
            contacts.take(MAX_CHAT_SNAPSHOT_SIZE).forEach { info ->
                val obj = JSONObject()
                obj.put("contactId", info.contactId)
                obj.put("peerUid", info.peerUid.orEmpty())
                obj.put("peerUin", info.peerUin)
                obj.put("chatType", info.chatType)
                obj.put("peerName", info.peerName.orEmpty())
                obj.put("remark", info.remark.orEmpty())
                obj.put("avatarPath", info.avatarPath.orEmpty())
                obj.put("avatarUrl", info.avatarUrl.orEmpty())
                val abs = info.abstractContent?.joinToString("") { it.content ?: "" }.orEmpty()
                obj.put("abstractText", abs)
                obj.put("msgTime", info.msgTime)
                obj.put("msgSeq", info.msgSeq)
                obj.put("unreadCnt", info.unreadCnt)
                obj.put("topFlag", info.topFlag)
                obj.put("isMsgDisturb", info.isMsgDisturb)
                array.put(obj)
            }
            atomicWrite(getFile(context, "chat", uin), array.toString())
            Log.d(TAG, "Chat snapshot saved: ${array.length()} items for uin=$uin")
        }.onFailure { Log.w(TAG, "saveChatSnapshot error", it) }
    }

    suspend fun loadChatSnapshot(
        context: Context,
        uin: String,
    ): List<RecentContactInfo> = withContext(Dispatchers.IO) {
        if (uin.isBlank()) return@withContext emptyList()
        val file = getFile(context, "chat", uin)
        if (!file.exists() || !file.isFile) return@withContext emptyList()
        runCatching {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return@withContext emptyList()
            val array = JSONArray(text)
            val list = ArrayList<RecentContactInfo>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val info = RecentContactInfo().apply {
                    contactId = obj.optLong("contactId")
                    peerUid = obj.optString("peerUid")
                    peerUin = obj.optLong("peerUin")
                    chatType = obj.optInt("chatType")
                    peerName = obj.optString("peerName")
                    remark = obj.optString("remark")
                    avatarPath = obj.optString("avatarPath")
                    avatarUrl = obj.optString("avatarUrl")
                    msgTime = obj.optLong("msgTime")
                    msgSeq = obj.optLong("msgSeq")
                    unreadCnt = obj.optLong("unreadCnt")
                    topFlag = obj.optInt("topFlag").toByte()
                    isMsgDisturb = obj.optBoolean("isMsgDisturb")
                    val abs = obj.optString("abstractText")
                    if (abs.isNotEmpty()) {
                        abstractContent = arrayListOf(MsgAbstractElement().apply { content = abs })
                    }
                }
                list.add(info)
            }
            Log.d(TAG, "Chat snapshot loaded: ${list.size} items for uin=$uin")
            list
        }.getOrElse {
            Log.w(TAG, "loadChatSnapshot failed, ignoring corrupted cache", it)
            emptyList()
        }
    }

    // ==========================================
    // 2. Contacts SWR Snapshot
    // ==========================================

    suspend fun saveContactsSnapshot(
        context: Context,
        uin: String,
        categories: List<ContactsViewModel.UiCategory>,
        groups: List<ContactsViewModel.UiGroup>,
    ) = withContext(Dispatchers.IO) {
        if (uin.isBlank() || (categories.isEmpty() && groups.isEmpty())) return@withContext
        runCatching {
            val root = JSONObject()
            val catArray = JSONArray()
            categories.forEach { cat ->
                val catObj = JSONObject()
                catObj.put("id", cat.id)
                catObj.put("name", cat.name)
                catObj.put("sortId", cat.sortId)
                catObj.put("onlineCount", cat.onlineCount)
                val buddiesArray = JSONArray()
                cat.buddies.forEach { b ->
                    val bObj = JSONObject()
                    bObj.put("uid", b.uid)
                    bObj.put("uin", b.uin)
                    bObj.put("nick", b.nick)
                    bObj.put("remark", b.remark)
                    bObj.put("avatarPath", b.avatarPath)
                    val urlsArr = JSONArray()
                    b.avatarUrls.forEach { urlsArr.put(it) }
                    bObj.put("avatarUrls", urlsArr)
                    bObj.put("categoryId", b.categoryId)
                    bObj.put("categoryName", b.categoryName)
                    buddiesArray.put(bObj)
                }
                catObj.put("buddies", buddiesArray)
                catArray.put(catObj)
            }
            root.put("categories", catArray)

            val groupArray = JSONArray()
            groups.forEach { g ->
                val gObj = JSONObject()
                gObj.put("groupCode", g.groupCode)
                gObj.put("groupName", g.groupName)
                gObj.put("memberCount", g.memberCount)
                gObj.put("avatarUrl", g.avatarUrl)
                groupArray.put(gObj)
            }
            root.put("groups", groupArray)

            atomicWrite(getFile(context, "contacts", uin), root.toString())
            Log.d(TAG, "Contacts snapshot saved: ${categories.size} categories, ${groups.size} groups for uin=$uin")
        }.onFailure { Log.w(TAG, "saveContactsSnapshot error", it) }
    }

    suspend fun loadContactsSnapshot(
        context: Context,
        uin: String,
    ): Pair<List<ContactsViewModel.UiCategory>, List<ContactsViewModel.UiGroup>> = withContext(Dispatchers.IO) {
        if (uin.isBlank()) return@withContext Pair(emptyList(), emptyList())
        val file = getFile(context, "contacts", uin)
        if (!file.exists() || !file.isFile) return@withContext Pair(emptyList(), emptyList())
        runCatching {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return@withContext Pair(emptyList(), emptyList())
            val root = JSONObject(text)
            val catArray = root.optJSONArray("categories")
            val categories = ArrayList<ContactsViewModel.UiCategory>()
            if (catArray != null) {
                for (i in 0 until catArray.length()) {
                    val catObj = catArray.optJSONObject(i) ?: continue
                    val buddiesArray = catObj.optJSONArray("buddies")
                    val buddies = ArrayList<ContactsViewModel.UiBuddy>()
                    if (buddiesArray != null) {
                        for (j in 0 until buddiesArray.length()) {
                            val bObj = buddiesArray.optJSONObject(j) ?: continue
                            val urlsArr = bObj.optJSONArray("avatarUrls")
                            val urls = ArrayList<String>()
                            if (urlsArr != null) {
                                for (k in 0 until urlsArr.length()) {
                                    val url = urlsArr.optString(k)
                                    if (url.isNotBlank()) urls.add(url)
                                }
                            }
                            buddies.add(
                                ContactsViewModel.UiBuddy(
                                    uid = bObj.optString("uid"),
                                    uin = bObj.optLong("uin"),
                                    nick = bObj.optString("nick"),
                                    remark = bObj.optString("remark"),
                                    avatarPath = bObj.optString("avatarPath"),
                                    avatarUrls = urls,
                                    categoryId = bObj.optInt("categoryId"),
                                    categoryName = bObj.optString("categoryName"),
                                )
                            )
                        }
                    }
                    categories.add(
                        ContactsViewModel.UiCategory(
                            id = catObj.optInt("id"),
                            name = catObj.optString("name"),
                            sortId = catObj.optInt("sortId"),
                            onlineCount = catObj.optInt("onlineCount"),
                            buddies = buddies,
                        )
                    )
                }
            }

            val groupArray = root.optJSONArray("groups")
            val groups = ArrayList<ContactsViewModel.UiGroup>()
            if (groupArray != null) {
                for (i in 0 until groupArray.length()) {
                    val gObj = groupArray.optJSONObject(i) ?: continue
                    groups.add(
                        ContactsViewModel.UiGroup(
                            groupCode = gObj.optLong("groupCode"),
                            groupName = gObj.optString("groupName"),
                            memberCount = gObj.optInt("memberCount"),
                            avatarUrl = gObj.optString("avatarUrl"),
                        )
                    )
                }
            }
            Log.d(TAG, "Contacts snapshot loaded: ${categories.size} categories, ${groups.size} groups for uin=$uin")
            Pair(categories, groups)
        }.getOrElse {
            Log.w(TAG, "loadContactsSnapshot failed", it)
            Pair(emptyList<ContactsViewModel.UiCategory>(), emptyList<ContactsViewModel.UiGroup>())
        }
    }

    // ==========================================
    // 3. QZone SWR Snapshot
    // ==========================================

    suspend fun saveQZoneSnapshot(
        context: Context,
        uin: String,
        feeds: List<QZoneViewModel.FeedItem>,
    ) = withContext(Dispatchers.IO) {
        if (uin.isBlank() || feeds.isEmpty()) return@withContext
        runCatching {
            val array = JSONArray()
            feeds.take(MAX_QZONE_SNAPSHOT_SIZE).forEach { item ->
                val obj = JSONObject()
                obj.put("feedId", item.feedId)
                obj.put("uin", item.uin)
                obj.put("nick", item.nick)
                obj.put("content", item.content)
                obj.put("time", item.time)
                obj.put("displayTime", item.displayTime)
                val pics = JSONArray()
                item.picUrls.forEach { pics.put(it) }
                obj.put("picUrls", pics)
                item.videoUrl?.let { obj.put("videoUrl", it) }
                obj.put("likeCount", item.likeCount)
                obj.put("commentCount", item.commentCount)
                obj.put("isLiked", item.isLiked)
                item.forward?.let { fw ->
                    val fwObj = JSONObject()
                    fwObj.put("author", fw.author)
                    fwObj.put("content", fw.content)
                    fwObj.put("isUnavailable", fw.isUnavailable)
                    obj.put("forward", fwObj)
                }
                array.put(obj)
            }
            atomicWrite(getFile(context, "qzone", uin), array.toString())
            Log.d(TAG, "QZone snapshot saved: ${array.length()} feeds for uin=$uin")
        }.onFailure { Log.w(TAG, "saveQZoneSnapshot error", it) }
    }

    suspend fun loadQZoneSnapshot(
        context: Context,
        uin: String,
    ): List<QZoneViewModel.FeedItem> = withContext(Dispatchers.IO) {
        if (uin.isBlank()) return@withContext emptyList()
        val file = getFile(context, "qzone", uin)
        if (!file.exists() || !file.isFile) return@withContext emptyList()
        runCatching {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return@withContext emptyList()
            val array = JSONArray(text)
            val list = ArrayList<QZoneViewModel.FeedItem>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val pics = ArrayList<String>()
                val picsArr = obj.optJSONArray("picUrls")
                if (picsArr != null) {
                    for (j in 0 until picsArr.length()) {
                        val p = picsArr.optString(j)
                        if (p.isNotBlank()) pics.add(p)
                    }
                }
                val fwObj = obj.optJSONObject("forward")
                val forward = if (fwObj != null) {
                    QZoneViewModel.ForwardInfo(
                        author = fwObj.optString("author"),
                        content = fwObj.optString("content"),
                        isUnavailable = fwObj.optBoolean("isUnavailable"),
                    )
                } else null

                list.add(
                    QZoneViewModel.FeedItem(
                        feedId = obj.optString("feedId"),
                        uin = obj.optString("uin"),
                        nick = obj.optString("nick"),
                        content = obj.optString("content"),
                        forward = forward,
                        time = obj.optLong("time"),
                        displayTime = obj.optString("displayTime"),
                        picUrls = pics,
                        videoUrl = if (obj.has("videoUrl")) obj.optString("videoUrl") else null,
                        likeCount = obj.optInt("likeCount"),
                        commentCount = obj.optInt("commentCount"),
                        isLiked = obj.optBoolean("isLiked"),
                    )
                )
            }
            Log.d(TAG, "QZone snapshot loaded: ${list.size} feeds for uin=$uin")
            list
        }.getOrElse {
            Log.w(TAG, "loadQZoneSnapshot failed", it)
            emptyList()
        }
    }
}
