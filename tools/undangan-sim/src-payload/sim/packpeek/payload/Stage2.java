package sim.packpeek.payload;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;

/**
 * TEST SAMPLE ONLY. Second-stage code stored as assets/payload.bin to imitate droppers. It is never
 * loaded: the sample's manifest has hasCode="false" and nothing references this file at runtime.
 */
public final class Stage2 {
    public static int countSms(Context context) {
        Cursor cursor = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, null, null, null, null);
        return cursor == null ? 0 : cursor.getCount();
    }
}
