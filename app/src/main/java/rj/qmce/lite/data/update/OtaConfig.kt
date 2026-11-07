package rj.qmce.lite.data.update

import rj.qmce.lite.BuildConfig

/**
 * OTA 更新源集中配置与安全控制中心。
 *
 * 核心设计目标：
 * 1. 安全阻断（Safety Cut-Off）：将旧版 QMCE 的硬编码服务器 IP (8.217.8.106:8080) 与旧 GitHub 仓库 (QMCE/qmce) 彻底切断，
 *    杜绝手表端在点击“检查更新”或后台探测时访问旧源，防止误下载老版应用覆盖当前 Qhorium (top.rincynar.qhr)。
 * 2. 接口抽象（Qhorium Migration）：标准化定义 Qhorium 官方更新通道（GitHub: RinCynar/Qhorium 与自定义独立服务器），
 *    便于后续平滑部署与下发新构建。
 */
object OtaConfig {
    /**
     * 安全熔断开关。
     * 当为 true 时，旧版 QMCE 的服务器请求与探测完全停止，不向外网发送任何网络包。
     */
    const val LEGACY_ENDPOINTS_BLOCKED: Boolean = true

    /**
     * 旧版 QMCE 自建更新服务器地址（已熔断安全休眠，保留仅供协议与归档审计）。
     */
    const val LEGACY_SERVER_BASE: String = "http://8.217.8.106:8080"

    /**
     * 旧版 QMCE 官方 GitHub 仓库地址（已熔断安全休眠）。
     */
    const val LEGACY_GITHUB_RELEASES_URL: String =
        "https://api.github.com/repos/QMCE/qmce/releases/latest"

    /** Qhorium 官方 GitHub 仓库所有者 */
    const val QHORIUM_GITHUB_OWNER: String = "RinCynar"

    /** Qhorium 官方 GitHub 仓库名 */
    const val QHORIUM_GITHUB_REPO: String = "Qhorium"

    /** Qhorium 官方 GitHub Releases 接口地址 */
    val qhoriumGithubReleasesUrl: String
        get() = "https://api.github.com/repos/$QHORIUM_GITHUB_OWNER/$QHORIUM_GITHUB_REPO/releases/latest"

    /**
     * 自定义或私有部署的 Qhorium OTA 更新服务器基础 URL。
     * 待后续服务端部署后可直接赋值或通过本地配置动态覆写。
     */
    @Volatile
    var customServerBaseUrl: String? = null

    /**
     * 获取当前生效的服务器更新 API 基地址。
     * 若未配置自定义新源且旧源已被阻断，则返回 null。
     */
    fun activeServerBaseUrl(): String? {
        val custom = customServerBaseUrl?.trim()?.takeIf { it.isNotBlank() }
        if (custom != null) return custom
        return if (!LEGACY_ENDPOINTS_BLOCKED) LEGACY_SERVER_BASE else null
    }

    /**
     * 获取检查更新请求的 User-Agent 标识。
     */
    val userAgent: String
        get() = "Qhorium/${BuildConfig.VERSION_NAME} (Android; WearOS)"
}
