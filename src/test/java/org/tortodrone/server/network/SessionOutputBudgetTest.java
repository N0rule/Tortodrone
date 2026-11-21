package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.tortodrone.support.TestWire.check;

/** Bounded per-session output: optional frames drop before encoding, reliable frames never drop. */
public final class SessionOutputBudgetTest {
    public static void main(String[] args)throws Exception {
        optionalFramesDropBeforeEncoding();
        reliableOverflowFails();
        sustainedOverBudgetDisconnects();
        System.out.println("PASS: optional frame drop, reliable overflow and sustained budget disconnect");
    }

    private static void optionalFramesDropBeforeEncoding()throws Exception {
        var socket=new BlockingSocket();
        try(var output=new SessionOutput(socket,4,1024,60_000)) {
            check(output.droppedOptional()==0,"no frame dropped before the budget is reached");
            check(!output.sendOptional(()->new byte[2048]),"optional frame larger than the budget is skipped");
            check(output.droppedOptional()==1,"skipped optional frame is counted");
        }
    }

    private static void reliableOverflowFails()throws Exception {
        var socket=new BlockingSocket();
        try(var output=new SessionOutput(socket,4,1024,60_000)) {
            boolean failed=false;
            try { output.send(()->new byte[2048]); }
            catch(IOException expected){failed=true;}
            check(failed,"reliable frame beyond the byte budget fails the connection");
        }
    }

    private static void sustainedOverBudgetDisconnects()throws Exception {
        var socket=new BlockingSocket();
        try(var output=new SessionOutput(socket,4,1024,50)) {
            check(!output.sendOptional(()->new byte[2048]),"over-budget optional frame is dropped");
            Thread.sleep(80);
            boolean failed=false;
            try { output.send(()->new byte[1]); }
            catch(IOException expected){failed=true;}
            check(failed,"a sustained over-budget reader is disconnected");
        }
    }

    /** A socket whose writer blocks until closed, so the pending queue can be observed. */
    private static final class BlockingSocket extends Socket {
        private final CountDownLatch closed=new CountDownLatch(1);
        private final OutputStream out=new OutputStream(){
            @Override public void write(int value)throws IOException{awaitClose();}
            @Override public void write(byte[] bytes,int offset,int length)throws IOException{awaitClose();}
            @Override public void flush(){}
            private void awaitClose()throws IOException{
                try{closed.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
        };
        @Override public OutputStream getOutputStream(){return out;}
        @Override public boolean isClosed(){return closed.getCount()==0;}
        @Override public void close(){closed.countDown();}
    }
}
