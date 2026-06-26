# JavRedis

JavRedis is a production-ready, in-memory key-value store built with Java and Netty. It aims to provide a robust, high-performance caching layer with leader-follower replication.

## Features

*   **High Performance**: Built on top of Netty's asynchronous, event-driven network application framework.
*   **LRU Eviction**: Thread-safe Least Recently Used (LRU) cache implementation with O(1) time complexity for `GET`, `SET`, and `DEL` operations.
*   **Leader-Follower Replication**: Asynchronous replication from Leader to multiple Followers using persistent connection pools and auto-reconnect logic.
*   **Loop Protection**: Replication commands are safely prefixed to prevent infinite loops between nodes.
*   **Graceful Shutdown**: Properly handles JVM termination signals to close connections and thread pools cleanly.
*   **Connection Management**: Automatically drops idle/zombie connections.
*   **Observability**: Integrated metrics (hits, misses, evictions) and structured logging (SLF4J + Logback).
*   **Comprehensive Testing**: Covered by unit and integration tests using JUnit 5 and Netty's `EmbeddedChannel`.

## Prerequisites

*   Java 17 or higher
*   Maven 3.8+

## Building the Project

To build the project and run the tests, execute:

```bash
mvn clean package
```

## Running the Server

The application uses `config.properties` for default settings, which can be found in `src/main/resources/`. 

### Running as Leader

By default, running the application without any arguments starts it in **Leader** mode on port `8080` (as defined in `config.properties`).

```bash
java -jar target/in-memory-store-1.0-SNAPSHOT.jar
```

### Running as Follower

To start a **Follower** node, provide the port number as a command-line argument. This will override the configuration and start the server as a Follower.

```bash
# Start Follower 1 on port 8081
java -jar target/in-memory-store-1.0-SNAPSHOT.jar 8081

# Start Follower 2 on port 8082
java -jar target/in-memory-store-1.0-SNAPSHOT.jar 8082
```

*Note: Ensure the leader's `config.properties` has `replication.followers` configured with these ports.*

## Supported Commands

You can connect to the server using tools like `telnet` or `nc` (netcat).

```bash
nc localhost 8080
```

Once connected, you can use the following case-insensitive commands:

| Command | Description | Example | Response |
| :--- | :--- | :--- | :--- |
| **`SET <key> <value>`** | Stores a key-value pair. | `SET name Acar` | `OK` |
| **`GET <key>`** | Retrieves the value for a key. | `GET name` | `Acar` (or `NIL` if not found) |
| **`DEL <key>`** | Deletes a key. | `DEL name` | `1` (if deleted), `0` (if not found) |
| **`SIZE`** | Returns the current number of keys. | `SIZE` | `1` |
| **`STATS`** | Returns hit/miss/eviction metrics. | `STATS` | `hits=1 misses=0 hit_rate=100.0% evictions=0 sets=1 deletes=0 size=1/10000` |
| **`PING`** | Tests connection vitality. | `PING` | `PONG` |

## Configuration

The `src/main/resources/config.properties` file controls the server's behavior:

```properties
server.port=8080
server.role=leader
server.capacity=10000
replication.followers=localhost:8081,localhost:8082
replication.connect.timeout.ms=3000
server.idle.timeout.seconds=60
```

## Architecture

*   **`StoreServer`**: The main entry point that bootstraps the Netty server.
*   **`StoreServerHandler`**: The channel handler that processes incoming commands, interacts with the store, and triggers replication.
*   **`InMemoryStore`**: The core thread-safe LRU cache using a `HashMap` and a doubly-linked list protected by a `ReentrantLock`.
*   **`ReplicationManager`**: Manages the connection pool to followers and handles asynchronous replication of `SET` commands.
*   **`IdleConnectionHandler`**: Closes connections that have been idle for too long.
