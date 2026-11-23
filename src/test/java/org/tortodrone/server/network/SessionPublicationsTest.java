package org.tortodrone.server.network;

import static org.tortodrone.support.TestWire.check;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.tortodrone.core.party.PartyView;

/** The delivery worker stops on request and the shutdown wait is bounded even mid-frame. */
public final class SessionPublicationsTest {
    public static void main(String[] args) throws Exception {
        idleWorkerStops();
        busyWorkerWaitIsBounded();
        System.out.println("PASS: delivery worker stop, idle exit and bounded in-frame wait");
    }

    private static void idleWorkerStops() throws Exception {
        var publications = new SessionPublications();
        publications.start("idle-delivery-test", publication -> {}, event -> {}, failure -> {});
        publications.stop();
        publications.awaitStop();
        check(!publications.worker().isAlive(), "idle delivery worker exited");
    }

    private static void busyWorkerWaitIsBounded() throws Exception {
        var publications = new SessionPublications();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        publications.start(
                "busy-delivery-test",
                publication -> {},
                event -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Delivery interrupted", interrupted);
                    }
                },
                failure -> {});
        publications.offerParty(new PartyView.Refresh());
        check(entered.await(5, TimeUnit.SECONDS), "delivery worker entered the frame");
        publications.stop();
        long started = System.nanoTime();
        publications.awaitStop();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        check(elapsedMillis < 5_000L, "in-frame wait returned within its deadline");
        release.countDown();
        publications.worker().join(5_000L);
        check(!publications.worker().isAlive(), "delivery worker exited after the frame returned");
    }
}
