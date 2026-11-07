package rj.qmce.lite.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import rj.qmce.lite.notify.QmceMessageRefreshScheduler
import rj.qmce.lite.service.QmceKeepAliveService
import rj.qmce.lite.ui.wear.QmceScreenScaffold
import rj.qmce.lite.ui.wear.SettingsListHeader
import rj.qmce.lite.ui.wear.SettingsSubHeader
import rj.qmce.lite.ui.wear.SettingsSwitch
import rj.qmce.lite.util.QmceDevice
import rj.qmce.lite.viewmodel.SettingsViewModel

@Composable
fun BackgroundSettingsScreen(
    settingsVm: SettingsViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings by settingsVm.settings.collectAsState()
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val isWear = QmceDevice.isWear(context)
    val showLiveUpdates = isWear && Build.VERSION.SDK_INT >= 37

    QmceScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "bg-header") {
                SettingsListHeader(
                    text = "后台",
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                )
            }
            item(key = "sec-keepalive") {
                SettingsSubHeader(
                    text = "保活",
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                )
            }
            item(key = "notify-keepalive") {
                SettingsSwitch(
                    checked = settings.keepAlive,
                    onCheckedChange = { enabled ->
                        settingsVm.setKeepAlive(enabled)
                        QmceKeepAliveService.sync(context, loggedIn = true)
                        if (!enabled) QmceKeepAliveService.stop(context)
                        else QmceKeepAliveService.startIfEnabled(context)
                    },
                    label = "后台保活",
                    secondaryLabel = "前台服务维持连接",
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                )
            }
            item(key = "sec-refresh") {
                SettingsSubHeader(
                    text = "刷新",
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                )
            }
            item(key = "notify-refresh") {
                Button(
                    onClick = {
                        val next = nextRefreshMode(settings.messageRefreshMode)
                        settingsVm.setMessageRefreshMode(next)
                        QmceMessageRefreshScheduler.onSettingsChanged(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(
                            ButtonDefaults.minimumVerticalListContentPadding,
                        ),
                    transformation = SurfaceTransformation(transformationSpec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = {
                        Text(refreshLabel(settings.messageRefreshMode), maxLines = 2)
                    },
                ) {
                    Text("消息刷新频率")
                }
            }
            if (isWear) {
                item(key = "sec-wear") {
                    SettingsSubHeader(
                        text = "Wear",
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                    )
                }
                if (showLiveUpdates) {
                    item(key = "notify-live-updates") {
                        SettingsSwitch(
                            checked = settings.liveUpdates,
                            onCheckedChange = settingsVm::setLiveUpdates,
                            label = "Live Updates",
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.transformedHeight(this, transformationSpec),
                        )
                    }
                }
                item(key = "notify-voice-bg") {
                    SettingsSwitch(
                        checked = settings.voiceBackground,
                        onCheckedChange = settingsVm::setVoiceBackground,
                        label = "语音可后台",
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                    )
                }
                item(key = "notify-voice-ongoing") {
                    SettingsSwitch(
                        checked = settings.voiceOngoingSurface,
                        onCheckedChange = settingsVm::setVoiceOngoingSurface,
                        label = "语音后台持续展示",
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                    )
                }
            }
        }
    }
}

internal fun refreshLabel(mode: String): String = when (mode) {
    SettingsViewModel.REFRESH_15S -> "当前：15 秒"
    SettingsViewModel.REFRESH_30S -> "当前：30 秒"
    SettingsViewModel.REFRESH_1M -> "当前：1 分钟"
    SettingsViewModel.REFRESH_5M -> "当前：5 分钟"
    else -> "当前：仅推送（点击切换）"
}

internal fun nextRefreshMode(mode: String): String = when (mode) {
    SettingsViewModel.REFRESH_PUSH_ONLY -> SettingsViewModel.REFRESH_15S
    SettingsViewModel.REFRESH_15S -> SettingsViewModel.REFRESH_30S
    SettingsViewModel.REFRESH_30S -> SettingsViewModel.REFRESH_1M
    SettingsViewModel.REFRESH_1M -> SettingsViewModel.REFRESH_5M
    else -> SettingsViewModel.REFRESH_PUSH_ONLY
}
