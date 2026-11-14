package rj.qmce.lite.ui.components

import android.content.Context
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.io.File

/**
 * Unified avatar resolution and caching utilities across Wear OS screens.
 * Eliminates redundant string manipulations and provides optimal caching parameters.
 */
object AvatarUtils {
    /**
     * Resolves a local avatar file path safely without redundant regex or substring allocations.
     * Returns a valid existing File, or null if the path is invalid or non-existent on disk.
     */
    fun resolveLocalAvatarFile(rawPath: String?): File? {
        if (rawPath.isNullOrBlank()) return null
        val clean = if (rawPath.startsWith("file://")) {
            rawPath.substring(7)
        } else {
            rawPath
        }
        if (clean.isBlank()) return null
        val file = File(clean)
        return if (file.isFile) file else null
    }

    /**
     * Standard URL fallback for QQ avatar CDN when local cache is unavailable.
     */
    fun fallbackAvatarUrl(id: String, isGroup: Boolean): String {
        return if (isGroup) {
            "https://p.qlogo.cn/gh/$id/$id/100"
        } else {
            "https://q1.qlogo.cn/g?b=qq&nk=$id&s=100"
        }
    }

    /**
     * Builds standard optimized Coil ImageRequest for Wear OS avatars:
     * limits decoding size to 96x96 to save RAM on low-tier Wear devices,
     * enables memory and disk cache, disables crossfade to save GPU layers on Wear.
     */
    fun buildAvatarImageRequest(context: Context, model: Any): ImageRequest {
        return ImageRequest.Builder(context)
            .data(model)
            .size(96, 96)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    /**
     * Builds standard optimized Coil ImageRequest for Wear OS feeds/dynamic images:
     * strictly limits decoding resolution to 360x360 to avoid OOM from full 4K phone photos.
     */
    fun buildFeedImageRequest(context: Context, model: Any): ImageRequest {
        return ImageRequest.Builder(context)
            .data(model)
            .size(360, 360)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }
}
