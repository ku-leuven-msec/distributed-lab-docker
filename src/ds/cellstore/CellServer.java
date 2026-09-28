package ds.cellstore;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One partition of the cell store.
 *
 * Environment:
 *   PARTITION      index of the partition this server owns (required, 0-based)
 *   PARTITIONS     total number of partitions (default 3)
 *   REGISTRY_PORT  port of the RMI registry this server starts (default 1099)
 *
 * Every server starts its own RMI registry in its own JVM and binds its
 * remote object under the name "cellstore".
 */
public final class CellServer implements CellStore {

    static final String BINDING = "cellstore";

    // Strong references: an exported object that is garbage collected stops answering.
    private static Registry registry;
    private static CellServer instance;

    private final int partition;
    private final int partitions;
    private final ConcurrentHashMap<Integer, String> cells = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();

    private CellServer(int partition, int partitions) {
        this.partition = partition;
        this.partitions = partitions;
    }

    @Override
    public Reply put(int cell, String value) throws WrongPartitionException {
        checkOwner(cell);
        requests.incrementAndGet();
        cells.put(cell, value);
        return new Reply(partition, Log.HOST, cell, value);
    }

    @Override
    public Reply get(int cell) throws WrongPartitionException {
        checkOwner(cell);
        requests.incrementAndGet();
        return new Reply(partition, Log.HOST, cell, cells.get(cell));
    }

    @Override
    public int partition() {
        return partition;
    }

    private void checkOwner(int cell) throws WrongPartitionException {
        int owner = Math.floorMod(cell, partitions);
        if (owner != partition) {
            throw new WrongPartitionException(cell, owner, partition);
        }
    }

    static void run() throws Exception {
        int partition = Integer.parseInt(Config.require("PARTITION"));
        int partitions = Config.envInt("PARTITIONS", 3);
        int registryPort = Config.envInt("REGISTRY_PORT", 1099);
        if (partition < 0 || partition >= partitions) {
            throw new IllegalArgumentException("PARTITION must be in [0, " + partitions + ")");
        }

        instance = new CellServer(partition, partitions);
        registry = LocateRegistry.createRegistry(registryPort);

        // Export the remote object so it can receive calls.
        // Port 0 means: let the operating system pick any free port.
        CellStore stub = (CellStore) UnicastRemoteObject.exportObject(instance, 0);
        registry.rebind(BINDING, stub);

        Log.info("partition %d/%d ready, registry on port %d", partition, partitions, registryPort);
        // The stub is what clients receive from the registry. Look at what it contains.
        Log.info("stub handed to clients: %s", stub);

        // Print a short status line every 10 seconds. RMI threads keep the JVM alive.
        while (true) {
            Thread.sleep(10_000);
            Log.info("partition %d: %d requests served, %d cells stored",
                    partition, instance.requests.get(), instance.cells.size());
        }
    }
}
