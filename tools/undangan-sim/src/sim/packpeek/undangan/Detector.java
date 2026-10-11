package sim.packpeek.undangan;

/**
 * TEST SAMPLE ONLY. A security scanner's signature list: the same texts as the scam code, but no
 * query and no network call. PackPeek must not report this as a data flow or a hidden destination.
 */
final class Detector {
    static boolean suspicious(String text) {
        return text.startsWith("content://sms") || text.contains("api.telegram.org") || text.contains("discord.com/api/webhooks");
    }
}
