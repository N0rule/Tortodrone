package org.tortodrone.account;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

/** Separate local tool and live server lease share durable, immediately revocable roles. */
public final class GameMasterTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-gm-");
        try {
            try(var server=new AccountStore(home);var tool=new AccountStore(home)) {
                server.create("Staff","staff-password".toCharArray(),true);
                server.create("Player","player-password".toCharArray());
                var identity=AccountStoreTest.login(server,"Staff","staff-password");
                try(var lease=server.attach(identity,new Socket())) {
                    check(lease.gameMaster(),"existing stored role grants GM access");
                    tool.setGameMaster("Staff",false,identity.id());
                    check(!lease.gameMaster()&&lease.current(),"cross-process removal revokes immediately without disconnecting");
                    tool.setGameMaster("Staff",true,identity.id());
                    check(lease.gameMaster(),"cross-process role restoration is visible");
                    try{tool.setGameMaster("Player",true,identity.id());throw new AssertionError("Stale target accepted");}catch(IOException expected){}
                    check(!AccountStoreTest.login(server,"Player","player-password").gameMaster(),"guarded role update cannot promote another account");
                    tool.setPassword("Staff","new-password".toCharArray());
                    try{lease.gameMaster();throw new AssertionError("Old auth revision retained GM access");}catch(IOException expected){}
                }
            }
            try(var restored=new AccountStore(home)) {
                check(AccountStoreTest.login(restored,"Staff","new-password").gameMaster(),"role persists through restart and password change");
            }
            System.out.println("PASS: durable GM roles, separate-tool grant/revocation, target guards and stale authenticated privilege denial");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
