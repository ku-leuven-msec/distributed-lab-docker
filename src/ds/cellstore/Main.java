package ds.cellstore;

/**
 * Single entry point for both roles, so one image can run as either.
 *
 *   ROLE=server  -> CellServer
 *   ROLE=client  -> CellClient (MODE=auto | interactive | verify)
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String role = Config.env("ROLE", "server");
        switch (role) {
            case "server" -> CellServer.run();
            case "client" -> CellClient.run();
            default -> {
                System.err.println("unknown ROLE '" + role + "' (expected server or client)");
                System.exit(2);
            }
        }
    }
}
