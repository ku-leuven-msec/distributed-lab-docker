package ds.cellstore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMISocketFactory;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Client of the partitioned cell store.
 *
 * Environment:
 *   SERVERS      comma-separated host:port list of the servers' RMI registries,
 *                in partition order: entry i must be the server of partition i
 *                (default localhost:1099,localhost:1100,localhost:1101)
 *   MODE         auto | interactive | verify (default auto)
 *   INTERVAL_MS  auto mode: pause between two operations (default 500)
 *   CELLS        auto mode: cells are picked at random from [0, CELLS) (default 1000)
 *   CONNECT_TIMEOUT_MS  give up opening a connection after this long (default 5000)
 */
public final class CellClient {

    /** One server (partition) as seen by the client. */
    static final class ServerHandle {
        final String host;
        final int port;
        private CellStore stub;

        ServerHandle(String hostPort) throws Exception {
            String[] parts = hostPort.trim().split(":");
            this.host = parts[0];
            this.port = Integer.parseInt(parts[1]);
            // NOTE: the stub is looked up once, here, and then used forever.
            Registry registry = LocateRegistry.getRegistry(host, port);
            this.stub = (CellStore) registry.lookup(CellServer.BINDING);
        }

        CellStore stub() {
            return stub;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private final List<ServerHandle> servers = new ArrayList<>();

    private CellClient(String serverList) throws Exception {
        for (String s : serverList.split(",")) {
            servers.add(new ServerHandle(s));
        }
    }

    int partitionOf(int cell) {
        return Math.floorMod(cell, servers.size());
    }

    Reply put(int cell, String value) throws Exception {
        return servers.get(partitionOf(cell)).stub().put(cell, value);
    }

    Reply get(int cell) throws Exception {
        return servers.get(partitionOf(cell)).stub().get(cell);
    }

    static void run() throws Exception {
        String serverList = Config.env("SERVERS", "localhost:1099,localhost:1100,localhost:1101");
        String mode = Config.env("MODE", "auto");
        // Print the configuration first: a misspelled variable silently falls back to the default.
        Log.info("mode=%s SERVERS=%s%s", mode, serverList,
                System.getenv("SERVERS") == null ? "  (default: SERVERS is not set)" : "");
        installConnectTimeout(Config.envInt("CONNECT_TIMEOUT_MS", 5000));
        CellClient client = new CellClient(serverList);
        Log.info("connected to %d servers: %s", client.servers.size(), client.servers);
        switch (mode) {
            case "auto" -> client.auto(Config.envInt("INTERVAL_MS", 500), Config.envInt("CELLS", 1000));
            case "interactive" -> client.interactive();
            case "verify" -> System.exit(client.verify() ? 0 : 1);
            default -> {
                System.err.println("unknown MODE '" + mode + "' (expected auto, interactive or verify)");
                System.exit(2);
            }
        }
    }

    /**
     * Without this, a connection to an address that never answers (for example a
     * container address seen from the host) hangs for minutes before it fails.
     */
    private static void installConnectTimeout(int timeoutMs) throws IOException {
        RMISocketFactory.setSocketFactory(new RMISocketFactory() {
            @Override
            public Socket createSocket(String host, int port) throws IOException {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), timeoutMs);
                return socket;
            }

            @Override
            public ServerSocket createServerSocket(int port) throws IOException {
                return new ServerSocket(port);
            }
        });
    }

    /** Automated client: forever write a random cell, read it back, log the outcome. */
    private void auto(int intervalMs, int cellCount) throws InterruptedException {
        Random random = new Random();
        long seq = 0;
        while (true) {
            int cell = random.nextInt(cellCount);
            int expected = partitionOf(cell);
            String value = Log.HOST + "#" + (seq++);
            long t0 = System.nanoTime();
            try {
                put(cell, value);
                Reply r = get(cell);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (r.partition() != expected) {
                    Log.info("WRONG cell=%d expected-partition=%d got-partition=%d server=%s",
                            cell, expected, r.partition(), r.server());
                } else {
                    Log.info("OK cell=%d partition=%d server=%s rtt=%dms", cell, r.partition(), r.server(), ms);
                }
            } catch (Exception e) {
                Log.info("ERROR cell=%d partition=%d via=%s %s: %s",
                        cell, expected, servers.get(expected), e.getClass().getName(), e.getMessage());
            }
            Thread.sleep(intervalMs);
        }
    }

    /** Manual client: read commands from stdin. */
    private void interactive() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("commands: put <cell> <value> | get <cell> | quit");
        while (true) {
            System.out.print("> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null || line.trim().equals("quit")) {
                return;
            }
            String[] w = line.trim().split("\\s+", 3);
            try {
                if (w[0].equals("put") && w.length == 3) {
                    System.out.println(put(Integer.parseInt(w[1]), w[2]));
                } else if (w[0].equals("get") && w.length == 2) {
                    System.out.println(get(Integer.parseInt(w[1])));
                } else if (!w[0].isEmpty()) {
                    System.out.println("commands: put <cell> <value> | get <cell> | quit");
                }
            } catch (Exception e) {
                System.out.println("error: " + e);
            }
        }
    }

    /**
     * Self-test used by verify.sh. Checks that every configured server is
     * reachable, owns the partition it is listed for, stores and returns
     * values, and rejects cells of other partitions.
     */
    private boolean verify() {
        int n = servers.size();
        boolean ok = true;
        String nonce = UUID.randomUUID().toString();
        for (int p = 0; p < n; p++) {
            ServerHandle h = servers.get(p);
            try {
                int claimed = h.stub().partition();
                if (claimed != p) {
                    Log.info("VERIFY FAIL: %s is listed as partition %d but says it owns partition %d", h, p, claimed);
                    ok = false;
                    continue;
                }
                int cell = p + n * 7;
                put(cell, nonce);
                Reply r = get(cell);
                if (!nonce.equals(r.value()) || r.partition() != p) {
                    Log.info("VERIFY FAIL: partition %d returned %s", p, r);
                    ok = false;
                    continue;
                }
                try {
                    h.stub().put(p + 1, "wrong");   // cell p+1 belongs to partition (p+1) mod n
                    if (n > 1) {
                        Log.info("VERIFY FAIL: partition %d accepted a cell of another partition", p);
                        ok = false;
                        continue;
                    }
                } catch (WrongPartitionException expectedException) {
                    // correct: the server refused a cell it does not own
                }
                Log.info("VERIFY partition %d OK (server=%s)", p, r.server());
            } catch (Exception e) {
                Log.info("VERIFY FAIL: partition %d via %s: %s", p, h, e);
                ok = false;
            }
        }
        Log.info(ok ? "VERIFY OK: %d partitions reachable, routing correct" : "VERIFY FAILED (%d partitions configured)", n);
        return ok;
    }
}
