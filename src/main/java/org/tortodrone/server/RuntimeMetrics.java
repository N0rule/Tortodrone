package org.tortodrone.server;

import java.lang.management.ManagementFactory;

/** Portable measured host/process information; negative optional values mean unavailable. */
public record RuntimeMetrics(long heapUsed, long heapCommitted, long heapMax, int processors,
        double processCpu, long physicalTotal, long physicalFree, long processUptime) {
    public static RuntimeMetrics capture() {
        var memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var os=ManagementFactory.getOperatingSystemMXBean();
        double cpu=-1;long total=-1,free=-1;
        if(os instanceof com.sun.management.OperatingSystemMXBean extended) {
            cpu=extended.getProcessCpuLoad();total=extended.getTotalMemorySize();free=extended.getFreeMemorySize();
        }
        return new RuntimeMetrics(memory.getUsed(),memory.getCommitted(),memory.getMax(),os.getAvailableProcessors(),cpu,total,free,ManagementFactory.getRuntimeMXBean().getUptime());
    }
    public static long mib(long bytes) { return bytes/1048576; }
}
