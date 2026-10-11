package org.tortodrone.server;

import java.util.concurrent.atomic.AtomicReference;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;

/** Focused in-memory regression for bounded desktop chart history. */
public final class PerformanceChartsTest {
    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try (PerformanceCharts charts = new PerformanceCharts()) {
                long timestamp = 1_800_000_000_000L;
                for (int i = 0; i < 3605; i++) {
                    charts.sample(new RuntimeMetrics(128L * 1024 * 1024, 256L * 1024 * 1024,
                            1024L * 1024 * 1024, 8, .25, -1, -1, i * 1000L), i % 20, timestamp + i * 1000L);
                }
                check(charts.historySize() == 3600, "history remains bounded at one hour of samples");
                check(charts.plottedSamples(0) == 3600, "CPU history is bounded");
                check(charts.plottedSamples(1) == 3600, "heap history is bounded");
                check(charts.plottedSamples(2) == 3600, "authenticated player history is bounded");
                renderAtSize(charts, 1000, 850);
                renderAtSize(charts, 720, 560);
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Performance chart regression failed", failure.get());
        System.out.println("Performance charts: bounded one-hour history and resized Swing rendering passed.");
    }

    private static void renderAtSize(PerformanceCharts charts, int width, int height) {
        charts.setSize(width, height);
        layoutTree(charts);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { charts.printAll(graphics); } finally { graphics.dispose(); }
    }

    private static void layoutTree(Container parent) {
        parent.doLayout();
        for (Component component : parent.getComponents()) if (component instanceof Container child) layoutTree(child);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
