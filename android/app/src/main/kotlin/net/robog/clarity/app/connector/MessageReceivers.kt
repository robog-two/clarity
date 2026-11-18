package net.robog.clarity.app.connector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.robog.clarity.app.call.graph

/**
 * A text or an MMS notification came in. The broadcast is only a nudge: the connector syncs a few
 * seconds later, once the messaging app has written the row, and reads it from the provider.
 */
abstract class MessageArrivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.graph().phoneConnector.messageArrived(intent.action, intent.type)
    }
}

/** `SMS_RECEIVED`, sent by the system (RECEIVE_SMS). */
class SmsArrivedReceiver : MessageArrivedReceiver()

/** `WAP_PUSH_RECEIVED` for an MMS notification, sent by the system (RECEIVE_MMS). */
class MmsArrivedReceiver : MessageArrivedReceiver()
