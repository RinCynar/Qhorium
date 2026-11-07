package rj.qmce.lite.data.update

enum class OtaSourceMode(val pref: String) {
    Auto("auto"),
    GitHub("github"),
    Server("server"),
    ;

    companion object {
        fun fromPref(raw: String?): OtaSourceMode =
            entries.firstOrNull { it.pref == raw } ?: Auto
    }
}

enum class OtaDownloadMode(val pref: String) {
    WatchBrowser("watch_browser"),
    Phone("phone"),
    InApp("in_app"),
    ;

    companion object {
        fun fromPref(raw: String?): OtaDownloadMode =
            entries.firstOrNull { it.pref == raw } ?: WatchBrowser
    }
}

data class OtaLatencyReport(
    val githubMs: Long?,
    val serverMs: Long?,
    val autoWouldUse: OtaSourceMode,
) {
    fun summary(): String {
        val gh = githubMs?.let { "GitHub ${it}ms" } ?: "GitHub 超时"
        val sv = if (OtaConfig.activeServerBaseUrl() == null) {
            "旧服务器 已安全切断"
        } else {
            serverMs?.let { "服务器 ${it}ms" } ?: "服务器 超时"
        }
        val pick = when (autoWouldUse) {
            OtaSourceMode.GitHub -> "将使用 Qhorium GitHub 通道"
            OtaSourceMode.Server -> "将使用服务器通道"
            OtaSourceMode.Auto -> "自动通道"
        }
        return "$gh / $sv；$pick"
    }
}
