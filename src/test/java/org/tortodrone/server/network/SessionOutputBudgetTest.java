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
        slowReaderQueueIsBoundedAndMeasured();
        System.out.println("PASS: optional frame drop, reliable overflow, sustained budget disconnect and bounded slow-reader queue");
    }

    private static void optionalFramesDropBeforeEncoding()throws Exception {
        var socket=new BlockingSocket();
        var before=SessionOutput.Metrics.snapshot();
        try(var output=new SessionOutput(socket,4,1024,60_000)) {
            check(output.droppedOptional()==0,"no frame dropped before the budget is reached");
            check(!output.sendOptional(()->new byte[2048]),"optional frame larger than the budget is skipped");
            check(output.droppedOptional()==1,"skipped optional frame is counted");
        }
        var after=SessionOutput.Metrics.snapshot();
        check(after.droppedOptional()-before.droppedOptional()==1,"global optional-drop counter advances");
    }

    private static void reliableOverflowFails()throws Exception {
        var socket=new BlockingSocket();
        var before=SessionOutput.Metrics.snapshot();
        try(var output=new SessionOutput(socket,4,1024,60_000)) {
            boolean failed=false;
            try { output.send(()->new byte[2048]); }
            catch(IOException expected){failed=true;}
            check(failed,"reliable frame beyond the byte budget fails the connection");
        }
        var after=SessionOutput.Metrics.snapshot();
        check(after.reliableOverflows()-before.reliableOverflows()==1,"global reliable-overflow counter advances");
    }

    private static void sustainedOverBudgetDisconnects()throws Exception {
        var socket=new BlockingSocket();
        var before=SessionOutput.Metrics.snapshot();
        try(var output=new SessionOutput(socket,4,1024,50)) {
            check(!output.sendOptional(()->new byte[2048]),"over-budget optional frame is dropped");
            Thread.sleep(80);
            boolean failed=false;
            try { output.send(()->new byte[1]); }
            catch(IOException expected){failed=true;}
            check(failed,"a sustained over-budget reader is disconnected");
        }
        var after=SessionOutput.Metrics.snapshot();
        check(after.budgetDisconnects()-before.budgetDisconnects()==1,"sustained over-budget disconnect is counted");
    }

    private static void slowReaderQueueIsBoundedAndMeasured()throws Exception {
        var socket=new BlockingSocket();
        var before=SessionOutput.Metrics.snapshot();
        long heapBefore=usedHeap();
        try(var output=new SessionOutput(socket,64,1<<20,60_000_000_000L)) {
            for(int i=0;i<100;i++)output.sendOptional(()->new byte[4096]);
            long heapAfter=usedHeap();
            var after=SessionOutput.Metrics.snapshot();
            long frames=after.frames()-before.frames();
            long dropped=after.droppedOptional()-before.droppedOptional();
            // The writer thread may already have taken one frame out of the queue and be
            // blocked writing it, so the retained set is the 64-frame budget plus that one.
            check(frames==64||frames==65,"a slow reader retains only the frame budget: "+frames);
            check(dropped==100-frames,"the remaining optional frames are dropped before encoding: "+dropped);
            check(after.peakPendingFrames()>=63,"peak pending frames is observed");
            check(after.peakPendingBytes()>=63L*4096,"peak pending bytes is observed");
            check(after.largestFrame()>=4096,"largest queued frame is observed");
            System.out.printf("SLOW_READER retainedFrames=%d retainedBytes>=%d largestFrame=%d peakPendingFrames=%d peakPendingBytes=%d heapDeltaBytes=%d%n",
                frames,64L*4096,after.largestFrame(),after.peakPendingFrames(),after.peakPendingBytes(),heapAfter-heapBefore);
        }
    }

    private static long usedHeap() {
        var memory=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return memory.getUsed();
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
