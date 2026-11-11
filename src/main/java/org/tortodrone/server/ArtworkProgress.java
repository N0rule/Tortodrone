package org.tortodrone.server;

import java.io.PrintWriter;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** One updating terminal line; redirected output gets only measured ten-percent milestones. */
final class ArtworkProgress implements IntConsumer, AutoCloseable {
    private final Consumer<String> info;
    private final PrintWriter terminal;
    private int previous = -1, milestone = -1;
    private boolean open;
    ArtworkProgress(Consumer<String> info, PrintWriter terminal) { this.info = info; this.terminal = terminal; }
    @Override public void accept(int percent) {
        if (percent < 0 || percent > 100 || percent <= previous) return;
        previous = percent;
        if (terminal != null) {
            int filled = percent / 5;
            terminal.printf("\rWebUI extracting: [%s%s] %3d%% extracted", "=".repeat(filled), " ".repeat(20 - filled), percent);
            terminal.flush(); open = true;
            if (percent == 100) close();
        } else if (percent / 10 > milestone) {
            milestone = percent / 10;
            info.accept("WebUI extracting: " + percent + "% extracted");
        }
    }
    @Override public void close() { if (open) { terminal.println(); terminal.flush(); open = false; } }
}
