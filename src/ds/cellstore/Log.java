package ds.cellstore;

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** One-line log messages on stdout: "<timestamp> <host> <message>". */
final class Log {
    private Log() {}

    static final String HOST = hostname();

    static void info(String fmt, Object... args) {
        System.out.println(Instant.now().truncatedTo(ChronoUnit.MILLIS) + " " + HOST + " " + String.format(fmt, args));
        System.out.flush();
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}
