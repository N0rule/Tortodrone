package org.tortodrone.server;

import java.awt.*;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.jfree.chart.*;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.labels.XYToolTipGenerator;
import org.jfree.data.time.*;
import org.jfree.data.xy.XYDataset;
import org.tortodrone.admin.DesktopTheme;

/** Bounded, in-memory performance history rendered only by the optional desktop UI. */
final class PerformanceCharts extends JPanel implements AutoCloseable {
    private static final int MAX_SAMPLES = 3600;
    private record Sample(long timestamp, double cpuPercent, double heapMiB, int players) { }
    private final ArrayDeque<Sample> samples = new ArrayDeque<>(MAX_SAMPLES);
    private final TimeSeries cpu = series("Server process CPU", MAX_SAMPLES);
    private final TimeSeries heap = series("JVM heap", MAX_SAMPLES);
    private final TimeSeries players = series("Authenticated players", MAX_SAMPLES);
    private final DateAxis[] axes = new DateAxis[3];
    private final ChartPanel[] charts = new ChartPanel[3];
    private final JComboBox<String> range = new JComboBox<>(new String[]{"1m", "5m", "30m", "1h"});
    private final JCheckBox pause = new JCheckBox("Pause graphs");
    private final Runnable themeListener = this::applyTheme;

    PerformanceCharts() {
        super(new BorderLayout(8, 8));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        controls.add(new JLabel("Time range"));
        controls.add(range);
        controls.add(pause);
        add(controls, BorderLayout.NORTH);
        JPanel plots = new JPanel(new GridLayout(0, 1, 8, 8));
        plots.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 2));
        String[] titles = {"Server process CPU", "JVM heap usage", "Connected authenticated players"};
        String[] units = {"CPU (%)", "Heap (MiB)", "Players"};
        TimeSeries[] data = {cpu, heap, players};
        for (int i = 0; i < charts.length; i++) {
            JFreeChart chart = ChartFactory.createTimeSeriesChart(titles[i], "Time", units[i],
                    new TimeSeriesCollection(data[i]), false, true, false);
            XYPlot plot = chart.getXYPlot();
            plot.setDomainGridlinePaint(new Color(100, 110, 125, 70));
            plot.setRangeGridlinePaint(new Color(100, 110, 125, 70));
            plot.setDomainGridlinesVisible(true);
            plot.setRangeGridlinesVisible(true);
            plot.setRenderer(renderer(i));
            if (plot.getDomainAxis() instanceof DateAxis dateAxis) {
                axes[i] = dateAxis;
                dateAxis.setDateFormatOverride(DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault()));
                dateAxis.setAutoRange(true);
            }
            ((NumberAxis) plot.getRangeAxis()).setAutoRangeIncludesZero(true);
            charts[i] = new ChartPanel(chart, true, true, false, true, true);
            charts[i].setPreferredSize(new Dimension(700, 230));
            charts[i].setMinimumDrawWidth(200);
            charts[i].setMinimumDrawHeight(120);
            plots.add(charts[i]);
        }
        JScrollPane scroll = new JScrollPane(plots);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
        range.addActionListener(event -> updateRange());
        pause.addActionListener(event -> {
            pause.setText(pause.isSelected() ? "Resume graphs" : "Pause graphs");
            if (!pause.isSelected()) refreshSeries();
        });
        DesktopTheme.addListener(themeListener);
        updateRange();
        applyTheme();
    }

    private static TimeSeries series(String name, int limit) {
        TimeSeries series = new TimeSeries(name);
        series.setMaximumItemCount(limit);
        return series;
    }

    int plottedSamples(int chart) {
        return switch (chart) { case 0 -> cpu.getItemCount(); case 1 -> heap.getItemCount(); case 2 -> players.getItemCount(); default -> throw new IllegalArgumentException("Unknown chart"); };
    }

    int historySize() { return samples.size(); }

    void sample(RuntimeMetrics metrics, int authenticatedPlayers, long timestamp) {
        samples.addLast(new Sample(timestamp, metrics.processCpu() < 0 ? Double.NaN : metrics.processCpu() * 100,
                metrics.heapUsed() / 1048576d, authenticatedPlayers));
        if (samples.size() > MAX_SAMPLES) samples.removeFirst();
        if (!pause.isSelected()) {
            Millisecond time = new Millisecond(new Date(timestamp));
            cpu.addOrUpdate(time, metrics.processCpu() < 0 ? null : metrics.processCpu() * 100);
            heap.addOrUpdate(time, metrics.heapUsed() / 1048576d);
            players.addOrUpdate(time, authenticatedPlayers);
        }
    }

    private void refreshSeries() {
        cpu.clear();
        heap.clear();
        players.clear();
        for (Sample sample : samples) {
            Millisecond time = new Millisecond(new Date(sample.timestamp()));
            cpu.addOrUpdate(time, Double.isFinite(sample.cpuPercent()) ? sample.cpuPercent() : null);
            heap.addOrUpdate(time, sample.heapMiB());
            players.addOrUpdate(time, sample.players());
        }
    }

    private void updateRange() {
        long millis = switch ((String) range.getSelectedItem()) {
            case "5m" -> TimeUnit.MINUTES.toMillis(5);
            case "30m" -> TimeUnit.MINUTES.toMillis(30);
            case "1h" -> TimeUnit.HOURS.toMillis(1);
            default -> TimeUnit.MINUTES.toMillis(1);
        };
        for (DateAxis axis : axes) if (axis != null) axis.setFixedAutoRange(millis);
    }

    private static XYLineAndShapeRenderer renderer(int index) {
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        renderer.setSeriesPaint(0, new Color[]{new Color(89, 166, 255), new Color(83, 201, 153), new Color(242, 177, 75)}[index]);
        renderer.setSeriesStroke(0, new BasicStroke(2f));
        renderer.setDefaultToolTipGenerator(new ExactTooltip(index));
        return renderer;
    }

    private void applyTheme() {
        Color background = UIManager.getColor("Panel.background");
        Color foreground = UIManager.getColor("Label.foreground");
        if (background == null || foreground == null) return;
        for (ChartPanel panel : charts) {
            if (panel == null) continue;
            JFreeChart chart = panel.getChart();
            chart.setBackgroundPaint(background);
            chart.getTitle().setPaint(foreground);
            XYPlot plot = chart.getXYPlot();
            plot.setBackgroundPaint(background);
            plot.setOutlinePaint(UIManager.getColor("Component.borderColor"));
            plot.getDomainAxis().setLabelPaint(foreground);
            plot.getDomainAxis().setTickLabelPaint(foreground);
            plot.getRangeAxis().setLabelPaint(foreground);
            plot.getRangeAxis().setTickLabelPaint(foreground);
            panel.setBackground(background);
        }
        repaint();
    }

    @Override public void close() { DesktopTheme.removeListener(themeListener); }

    private static final class ExactTooltip implements XYToolTipGenerator {
        private final int chart;
        private final DateFormat date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault());
        ExactTooltip(int chart) { this.chart = chart; }
        @Override public String generateToolTip(XYDataset dataset, int series, int item) {
            return date.format(new Date((long) dataset.getXValue(series, item))) + " · "
                    + Double.toString(dataset.getYValue(series, item)) + switch (chart) {
                case 0 -> "% CPU";
                case 1 -> " MiB";
                default -> " players";
            };
        }
    }
}
