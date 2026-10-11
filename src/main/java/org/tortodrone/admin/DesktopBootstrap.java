package org.tortodrone.admin;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Apply niri's AWT compatibility environment before the desktop toolkit initializes. */
public final class DesktopBootstrap {
    static final String NON_REPARENTING = "_JAVA_AWT_WM_NONREPARENTING";

    private DesktopBootstrap() { }

    static boolean needsRelaunch(String os, Map<String, String> environment, boolean headless) {
        if (headless || !os.toLowerCase(Locale.ROOT).startsWith("linux")
                || environment.containsKey(NON_REPARENTING)
                || environment.getOrDefault("DISPLAY", "").isBlank()) return false;
        if (!environment.getOrDefault("NIRI_SOCKET", "").isBlank()) return true;
        return List.of("XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP").stream().anyMatch(key ->
            List.of(environment.getOrDefault(key, "").split(":")).stream().anyMatch("niri"::equalsIgnoreCase));
    }

    static List<String> command(Path location, String[] arguments) {return command(location,arguments,AdminApplication.class.getName());}
    static List<String> command(Path location, String[] arguments,String mainClass) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        if (Files.isDirectory(location)) {
            command.add("-cp"); command.add(System.getProperty("java.class.path"));
            command.add(mainClass);
        } else {
            command.add("-jar"); command.add(location.toString());
        }
        command.addAll(List.of(arguments));
        return command;
    }

    /** Null means ordinary startup. Only the local GUI entry point calls this method. */
    static Integer relaunchIfNeeded(Path location, String[] arguments) throws IOException, InterruptedException {return relaunchIfNeeded(location,arguments,AdminApplication.class.getName());}
    public static Integer relaunchIfNeeded(Path location,String[] arguments,String mainClass) throws IOException,InterruptedException {
        if (!needsRelaunch(System.getProperty("os.name"), System.getenv(), Boolean.getBoolean("java.awt.headless"))) return null;
        ProcessBuilder builder = new ProcessBuilder(command(location, arguments,mainClass)).inheritIO();
        builder.environment().put(NON_REPARENTING, "1");
        Process child = builder.start();
        Thread shutdown = new Thread(()->stopChild(child), "AdminDesktopShutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            return child.waitFor();
        } finally {
            stopChild(child);
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { /* JVM shutdown owns child cleanup. */ }
        }
    }
    private static void stopChild(Process child) {
        boolean interrupted=Thread.interrupted(),finished=false;child.destroy();
        while(!finished)try{if(!child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor();}finished=true;}
        catch(InterruptedException ignored){interrupted=true;child.destroyForcibly();}
        if(interrupted)Thread.currentThread().interrupt();
    }
}
