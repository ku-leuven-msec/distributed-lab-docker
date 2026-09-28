# CellStore: a partitioned key-value store over Java RMI

CellStore stores string values in integer-indexed *cells*. The cells are split
over `PARTITIONS` servers: cell `c` belongs to partition `c mod PARTITIONS`.
Every server runs its own RMI registry and refuses cells it does not own, so a
client that sends a request to the wrong server gets a `WrongPartitionException`.

The code works on one machine. Making it work in containers is your job.

## Build

Needs a JDK 21.

```sh
./build.sh          # produces cellstore.jar
```

## Run on localhost

Open four terminals in this directory.

```sh
# terminals 1-3: one server per partition, each with its own registry port
ROLE=server PARTITION=0 REGISTRY_PORT=1099 java -jar cellstore.jar
ROLE=server PARTITION=1 REGISTRY_PORT=1100 java -jar cellstore.jar
ROLE=server PARTITION=2 REGISTRY_PORT=1101 java -jar cellstore.jar

# terminal 4: a client (defaults to localhost:1099,localhost:1100,localhost:1101)
ROLE=client MODE=verify      java -jar cellstore.jar   # self-test, exits 0 on success
ROLE=client MODE=auto        java -jar cellstore.jar   # automated client, runs forever
ROLE=client MODE=interactive java -jar cellstore.jar   # manual client
```

## Configuration

Everything is configured through environment variables.

| Variable        | Role   | Default                                          | Meaning |
|-----------------|--------|--------------------------------------------------|---------|
| `ROLE`          | both   | `server`                                         | `server` or `client` |
| `PARTITION`     | server | (required)                                       | partition this server owns, 0-based |
| `PARTITIONS`    | server | `3`                                              | total number of partitions |
| `REGISTRY_PORT` | server | `1099`                                           | port of the RMI registry the server starts |
| `SERVERS`       | client | `localhost:1099,localhost:1100,localhost:1101`   | registries in partition order: entry *i* is partition *i* |
| `MODE`          | client | `auto`                                           | `auto`, `interactive` or `verify` |
| `INTERVAL_MS`   | client | `500`                                            | auto mode: pause between operations |
| `CELLS`         | client | `1000`                                           | auto mode: pick cells from `[0, CELLS)` |
| `CONNECT_TIMEOUT_MS` | client | `5000`                                      | give up opening a connection after this many ms |

## Log format

Every line is `<timestamp> <hostname> <message>`. Auto clients log one line per
put+get pair: `OK ...`, `WRONG ...` (answered by the wrong partition) or
`ERROR ...` (the call failed). Servers print the stub they hand out at startup
and a request count every 10 seconds.

## Files

```
src/ds/cellstore/
  Main.java                    entry point, dispatches on ROLE
  CellStore.java               remote interface
  CellServer.java              one partition
  CellClient.java              client: auto / interactive / verify
  Reply.java, WrongPartitionException.java, Config.java, Log.java
build.sh                       compile + package
compose.yaml                   the application in Docker Compose
k8s/                           the application in Kubernetes
verify.sh                      end-to-end test of the Kubernetes deployment
kind-config.yaml               two-node kind cluster for the Kubernetes part
```
