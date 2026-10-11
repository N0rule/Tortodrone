package org.tortodrone.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shutdown must wait for session cleanup, including interrupted/concurrent callers. */
public final class ClientConnectionsTest {
    public static void main(String[] args) throws Exception {
        drain(false);
        drain(true);
        System.out.println("PASS: socket shutdown, handler drain, late-client refusal, concurrent close and interrupt preservation");
    }

    private static void drain(boolean interrupt) throws Exception {
        var connections = new ClientConnections();
        var entered = new CountDownLatch(1);
        var cleaning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new AtomicBoolean();
        try (var listener = new ServerSocket(0);
             var peer = new Socket("127.0.0.1", listener.getLocalPort());
             var socket = listener.accept()) {
            connections.start(socket, "owned-client-test", () -> {
                entered.countDown();
                try { socket.getInputStream().read(); }
                catch (IOException closed) { /* Closing the owned socket unblocks the read. */ }
                finally {
                    cleaning.countDown();
                    await(release);
                    finished.set(true);
                }
            });
            check(entered.await(10, TimeUnit.SECONDS), "handler entered");
            var stopped = new CountDownLatch(1);
            var first = new FutureTask<>(() -> {
                if (interrupt) Thread.currentThread().interrupt();
                connections.close();
                stopped.countDown();
                return Thread.currentThread().isInterrupted();
            });
            new Thread(first, "client-close-test").start();
            try {
                check(cleaning.await(10, TimeUnit.SECONDS), "close unblocks socket read");
                check(socket.isClosed(), "owned socket closed before cleanup finishes");
                check(stopped.getCount() == 1 && !first.isDone(), "close waits for blocked cleanup");
                try (var late = new Socket()) {
                    try {
                        connections.start(late, "late-client-test", () -> { throw new AssertionError("late handler started"); });
                        throw new AssertionError("late client accepted during shutdown");
                    } catch (IOException stopping) {
                        check(late.isClosed(), "refused client socket closed");
                    }
                }
                var secondStarted = new CountDownLatch(1);
                var second = new FutureTask<>(() -> {
                    secondStarted.countDown();
                    connections.close();
                    return finished.get();
                });
                new Thread(second, "concurrent-client-close-test").start();
                try {
                    check(secondStarted.await(10, TimeUnit.SECONDS), "concurrent close entered");
                    check(!second.isDone(), "concurrent close cannot bypass blocked cleanup");
                } finally { release.countDown(); }
                check(second.get(10, TimeUnit.SECONDS), "concurrent close sees finished cleanup");
                check(first.get(10, TimeUnit.SECONDS) == interrupt, "close preserves interrupt status");
                check(finished.get(), "handler finished before shutdown returned");
                connections.close(); // Repeated close after completion is harmless.
            } finally {
                release.countDown();
                connections.close();
                first.get(10, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            connections.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("cleanup was not released");
        } catch (InterruptedException unexpected) {
            Thread.currentThread().interrupt();
            throw new AssertionError("handler interrupted instead of drained", unexpected);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
