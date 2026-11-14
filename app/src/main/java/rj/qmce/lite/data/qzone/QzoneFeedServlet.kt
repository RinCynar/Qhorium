package rj.qmce.lite.data.qzone

import android.content.Context
import android.content.Intent
import com.tencent.qphone.base.remote.FromServiceMsg
import mqq.app.MSFServlet
import mqq.app.NewIntent
import mqq.app.Packet

class QzoneFeedIntent(
    context: Context,
    val ssoCmd: String,
    val data: ByteArray,
    val callback: (FromServiceMsg) -> Unit,
) : NewIntent(context, QzoneFeedServlet::class.java)

class QzoneFeedServlet : MSFServlet() {
    override fun onSend(intent: Intent?, packet: Packet?) {
        val qzoneIntent = intent as? QzoneFeedIntent ?: return
        packet?.setTimeout(30000L)
        packet?.setSSOCommand(qzoneIntent.ssoCmd)
        packet?.putSendData(qzoneIntent.data)
    }

    override fun onReceive(intent: Intent?, response: FromServiceMsg?) {
        val qzoneIntent = intent as? QzoneFeedIntent ?: return
        if (response != null) {
            qzoneIntent.callback.invoke(response)
        }
    }
}
