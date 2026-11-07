package rj.qmce.lite.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import rj.qmce.lite.ui.wear.QmceScreenScaffold
import rj.qmce.lite.ui.wear.SettingsListHeader
import rj.qmce.lite.ui.wear.SettingsNavButton
import rj.qmce.lite.ui.wear.SettingsSubHeader
import rj.qmce.lite.ui.wear.SettingsSwitch
import rj.qmce.lite.viewmodel.SettingsViewModel

@Composable
fun AboutHubScreen(
    settingsVm: SettingsViewModel,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val settings by settingsVm.settings.collectAsState()
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    QmceScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "about-hub-header") {
                SettingsListHeader(
                    text = "关于",
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                )
            }
            item(key = "sec-diagnostics") {
                SettingsSubHeader(
                    text = "诊断",
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                )
            }
            item(key = "qmce-verbose-log") {
                SettingsSwitch(
                    checked = settings.qmceVerboseLog,
                    onCheckedChange = settingsVm::setQmceVerboseLog,
                    label = "详细日志",
                    secondaryLabel = "打开后 logcat 输出 v/d/i，可能刷屏",
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                )
            }
            item(key = "qlog-local-write") {
                SettingsSwitch(
                    checked = settings.qlogLocalWriteEnabled,
                    onCheckedChange = settingsVm::setQlogLocalWriteEnabled,
                    label = "QQ QLog 写本地",
                    secondaryLabel = "默认关闭；打开后 SDK 可写本地日志文件",
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                )
            }
            item(key = "sec-about") {
                SettingsSubHeader(
                    text = "应用",
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                )
            }
            item(key = "about-detail") {
                SettingsNavButton(
                    icon = Icons.Default.Info,
                    title = "版本与开源信息",
                    subtitle = "查看完整关于页",
                    onClick = onOpenAbout,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                )
            }
        }
    }
}
