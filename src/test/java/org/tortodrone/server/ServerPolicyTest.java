package org.tortodrone.server;

import static org.tortodrone.support.TestWire.check;

/** Feature switches stay enabled until a deployment installs its own policy. */
public final class ServerPolicyTest {
    public static void main(String[] args) {
        check(
                ServerPolicy.tradesEnabled() && ServerPolicy.chatEnabled(),
                "isolated tools and tests start with trading and chat enabled");
        ServerPolicy.install(false, true);
        check(ServerPolicy.tradesEnabled() == false && ServerPolicy.chatEnabled(), "trading can be refused alone");
        ServerPolicy.install(true, false);
        check(ServerPolicy.tradesEnabled() && !ServerPolicy.chatEnabled(), "chat can be refused alone");
        ServerPolicy.install(true, true);
        check(ServerPolicy.tradesEnabled() && ServerPolicy.chatEnabled(), "policy is restorable");
        System.out.println("PASS: config/server.yml feature switches default on, switch independently and restore");
    }
}
