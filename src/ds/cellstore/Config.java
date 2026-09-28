package ds.cellstore;

/** All configuration comes from environment variables, with defaults for running on localhost. */
final class Config {
    private Config() {}

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    static int envInt(String key, int def) {
        return Integer.parseInt(env(key, Integer.toString(def)));
    }

    static String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            System.err.println("missing required environment variable " + key);
            System.exit(2);
        }
        return v.trim();
    }
}
