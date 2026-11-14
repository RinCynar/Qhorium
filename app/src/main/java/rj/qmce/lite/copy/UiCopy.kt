package rj.qmce.lite.copy

import java.util.Locale

object UiCopy {
    fun keepAliveTitle(): String = when (language()) {
        "zh" -> "Qhorium 运行中"
        "ja" -> "Qhorium 実行中"
        else -> "Qhorium running"
    }

    fun keepAliveText(): String = when (language()) {
        "zh" -> "保持消息连接"
        "ja" -> "メッセージ接続を維持"
        else -> "Keep message connection"
    }

    private fun language(): String {
        val lang = Locale.getDefault().language.lowercase(Locale.ROOT)
        return if (lang == "zh" || lang == "ja") lang else "en"
    }
}
