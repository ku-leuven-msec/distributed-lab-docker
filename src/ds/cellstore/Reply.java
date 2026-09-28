package ds.cellstore;

import java.io.Serializable;

/**
 * Result of a put/get. Records which partition and which host served the
 * request, so the logs show where every call actually went.
 */
public record Reply(int partition, String server, int cell, String value) implements Serializable {
}
