package org.tortodrone.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/** Loopback observation, optionally advancing one secure-handshake step. */
public final class ConnectionLogger {
    // Verified in the installed client's config.jar/deployment.properties.
    private static final int DEFAULT_PORT = 47624;
    private static final int MAX_BYTES = 65536;

    public static void main(String[] args) throws Exception {
        String bind = "127.0.0.1";
        int port = DEFAULT_PORT;
        Path keyPath = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--bind") && i + 1 < args.length) {
                bind = args[++i];
            } else if (args[i].equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--handshake-key") && i + 1 < args.length) {
                keyPath = Path.of(args[++i]);
            } else {
                throw new IllegalArgumentException("Usage: ConnectionLogger [--bind loopback-address] [--port 0..65535] [--handshake-key local-private.pk8]");
            }
        }
        InetAddress address = InetAddress.getByName(bind);
        if (!address.isLoopbackAddress() || port < 0 || port > 65535) {
            throw new IllegalArgumentException("Use a loopback address and port 0..65535");
        }
        Set<Socket> clients = ConcurrentHashMap.newKeySet();
        PrivateKey key = keyPath == null ? null : HandshakeKeys.load(keyPath);
        Semaphore slots = new Semaphore(16);
        AtomicLong ids = new AtomicLong();
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(address, port));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { listener.close(); } catch (IOException ignored) { }
                for (Socket client : clients) {
                    try { client.close(); } catch (IOException ignored) { }
                }
            }, "listener-shutdown"));
            log("Listening on " + address.getHostAddress() + ":" + listener.getLocalPort()
                + (key == null ? " (TCP; receive only; 64 KiB/connection; 15 s idle timeout)"
                    : " (TCP; secure handshake only; 64 KiB/frame; 15 s idle timeout; no payload logging)"));
            while (!listener.isClosed()) {
                Socket client;
                try {
                    client = listener.accept();
                } catch (IOException e) {
                    if (listener.isClosed()) break;
                    throw e;
                }
                if (!slots.tryAcquire()) {
                    client.close();
                    log("Connection limit reached");
                    continue;
                }
                long id = ids.incrementAndGet();
                clients.add(client);
                new Thread(() -> {
                    try {
                        receive(client, id, key);
                    } finally {
                        clients.remove(client);
                        slots.release();
                    }
                }, "connection-" + id).start();
            }
        }
    }

    private static void receive(Socket client, long id, PrivateKey key) {
        int total = 0;
        log("Connection #" + id + " from " + client.getRemoteSocketAddress());
        try (client) {
            client.setSoTimeout(15000);
            if (key != null) {
                SecureHandshake.run(client, key, message -> log("Connection #" + id + " " + message));
                return;
            }
            byte[] buffer = new byte[4096];
            while (total < MAX_BYTES) {
                int count = client.getInputStream().read(buffer, 0, Math.min(buffer.length, MAX_BYTES - total));
                if (count < 0) break;
                dump(id, total, buffer, count);
                total += count;
            }
            if (total == MAX_BYTES) log("Connection #" + id + " reached capture limit");
        } catch (SocketTimeoutException e) {
            log("Connection #" + id + " idle timeout");
        } catch (IOException e) {
            log("Connection #" + id + " I/O: " + e.getClass().getSimpleName());
        } catch (Exception e) {
            log("Connection #" + id + " handshake rejected: " + e.getClass().getSimpleName());
        } finally {
            log("Connection #" + id + (key == null ? " disconnected; received " + total + " bytes"
                : " handshake connection closed"));
        }
    }

    private static synchronized void dump(long id, int offset, byte[] data, int count) {
        log("Connection #" + id + " RX " + count + " bytes at stream offset " + offset
            + " (read chunk, not a protocol frame)");
        for (int row = 0; row < count; row += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int column = 0; column < 16; column++) {
                int index = row + column;
                if (index < count) {
                    int value = data[index] & 0xff;
                    hex.append(String.format("%02x ", value));
                    ascii.append(value >= 32 && value <= 126 ? (char) value : '.');
                } else {
                    hex.append("   ");
                }
            }
            System.out.printf("%08x  %s |%s|%n", offset + row, hex, ascii);
        }
        System.out.flush();
    }

    private static synchronized void log(String message) {
        System.out.println("[" + Instant.now() + "] " + message);
        System.out.flush();
    }
}
