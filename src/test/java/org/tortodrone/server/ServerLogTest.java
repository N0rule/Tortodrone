package org.tortodrone.server;

import java.nio.file.*;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

public final class ServerLogTest {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("tortodrone-log-");
        try {
            try(var log=new ServerLog(root)) {
                log.setConsoleOutput(line->{});log.configure(1024,2);
                var observed=new ArrayList<ServerLog.Event>();try(var subscription=log.subscribe(observed::add)){
                    log.info("Normal 雪");log.debug("hidden diagnostic");check(observed.size()==1,"normal suppresses debug for file and observers");
                    log.setDebugEnabled(true);log.debug("debug diagnostic");log.warn("one\ntwo\rthree");
                    check(observed.get(1).level().equals("DEBUG")&&observed.get(2).message().equals("one two three"),"typed severity and newline sanitization");
                    for(int n=0;n<100;n++)log.info("Rotating line "+n+" "+"x".repeat(100));
                }
                Files.writeString(root.resolve("server-99.log"),"old rotation");Files.writeString(root.resolve("server-owner.log"),"unrelated");log.configure(1024,2);check(!Files.exists(root.resolve("server-99.log"))&&Files.exists(root.resolve("server-owner.log")),"reduced retention removes only owned numbered rotations");Files.delete(root.resolve("server-owner.log"));
                check(log.recentFailure().level().equals("WARN"),"significant failures exposed structurally");
            }
            try(var files=Files.list(root)) {check(files.count()==3,"bounded rotation retention");}
            check(Files.readString(root.resolve("latest.log")).contains("Rotating line 99"),"latest persisted UTF-8 log remains writable after rotation");
            for(int n=1;n<=2;n++)check(Files.size(root.resolve("server-"+n+".log"))<=1024,"rotated file size bounded");
            System.out.println("PASS: normal/debug structured events, sanitization, UTF-8, significant failures and bounded log rotation");
        } finally {try(var files=Files.walk(root)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
}
