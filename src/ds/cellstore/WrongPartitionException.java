package ds.cellstore;

/** Thrown by a server that receives a cell it does not own. */
public class WrongPartitionException extends Exception {
    public WrongPartitionException(int cell, int owner, int self) {
        super("cell " + cell + " belongs to partition " + owner + ", not to partition " + self);
    }
}
