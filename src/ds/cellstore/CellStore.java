package ds.cellstore;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote interface of one partition of the cell store.
 *
 * The store holds cells indexed by integers. Cell c is owned by partition
 * floorMod(c, PARTITIONS). A server refuses cells it does not own, so a
 * client that routes requests to the wrong server fails loudly.
 */
public interface CellStore extends Remote {

    /** Stores value in cell. Returns which partition/server handled it. */
    Reply put(int cell, String value) throws RemoteException, WrongPartitionException;

    /** Reads cell (value is null if the cell was never written). */
    Reply get(int cell) throws RemoteException, WrongPartitionException;

    /** The partition index this server believes it owns. */
    int partition() throws RemoteException;
}
