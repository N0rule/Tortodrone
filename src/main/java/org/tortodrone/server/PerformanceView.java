package org.tortodrone.server;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.Locale;
import javax.swing.*;
import org.tortodrone.admin.DesktopWidgets;
import org.tortodrone.storage.Database;

/** Stable read-only statistics and charts share a single scrolling page. */
final class PerformanceView extends JPanel implements AutoCloseable {
    private final PerformanceCharts charts=new PerformanceCharts();
    private final DesktopWidgets.Details runtime=new DesktopWidgets.Details(2,"Heap used / maximum","TCP sessions","Heap committed","Scene instances","Logical CPUs","Resources","JVM uptime","Recent heap range");
    private final DesktopWidgets.Details diagnostics=new DesktopWidgets.Details("Database operations","Slow operations / failures","Rollbacks / maximum time","Recent significant failure");
    private final JLabel cpu=new JLabel("—"),heap=new JLabel("—"),players=new JLabel("—");
    private final ArrayDeque<RuntimeMetrics> recent=new ArrayDeque<>();
    private final java.util.List<JPanel> cards=new java.util.ArrayList<>();
    private final Runnable themeListener=this::applyTheme;

    PerformanceView() {
        super(new BorderLayout(8,8));
        add(charts.controls(),BorderLayout.NORTH);
        DesktopWidgets.Page page=new DesktopWidgets.Page(new BorderLayout(0,16));
        page.setBorder(BorderFactory.createEmptyBorder(8,8,12,8));
        JPanel summary=new JPanel(new BorderLayout(0,10));
        JPanel cardRow=new JPanel(new GridLayout(1,3,12,0));for(JPanel card:new JPanel[]{card("Process CPU",cpu),card("JVM heap",heap),card("Authenticated players",players)}){cards.add(card);cardRow.add(card);}summary.add(cardRow,BorderLayout.NORTH);
        summary.add(runtime,BorderLayout.CENTER);
        JPanel advanced=new JPanel(new BorderLayout(0,4));JToggleButton toggle=new JToggleButton("Show diagnostics");toggle.setHorizontalAlignment(SwingConstants.LEFT);
        diagnostics.setVisible(false);advanced.add(toggle,BorderLayout.NORTH);advanced.add(diagnostics);
        toggle.addActionListener(e->{diagnostics.setVisible(toggle.isSelected());toggle.setText(toggle.isSelected()?"Hide diagnostics":"Show diagnostics");});
        summary.add(advanced,BorderLayout.SOUTH);page.add(summary,BorderLayout.NORTH);page.add(charts,BorderLayout.CENTER);
        JScrollPane scroll=new JScrollPane(page);scroll.setBorder(BorderFactory.createEmptyBorder());scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);add(scroll);
        org.tortodrone.admin.DesktopTheme.addListener(themeListener);applyTheme();
    }
    private static JPanel card(String title,JLabel value) {
        JPanel card=new JPanel(new BorderLayout(0,8));card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")),BorderFactory.createEmptyBorder(12,14,12,14)));
        card.add(new JLabel(title),BorderLayout.NORTH);value.setFont(value.getFont().deriveFont(Font.BOLD,24f));card.add(value);return card;
    }
    String statistic(String name){return runtime.value(name);}
    void update(ServerControl.Snapshot state,ServerLog.Event failure) {
        RuntimeMetrics m=state.metrics();recent.addLast(m);if(recent.size()>120)recent.removeFirst();
        long count=state.sessions().stream().filter(s->s.knightId()!=null).count();
        cpu.setText(m.processCpu()<0?"Unavailable":String.format(Locale.ROOT,"%.1f %%",100*m.processCpu()));heap.setText(RuntimeMetrics.mib(m.heapUsed())+" MiB");players.setText(Long.toString(count));
        runtime.value("Heap used / maximum",RuntimeMetrics.mib(m.heapUsed())+" / "+RuntimeMetrics.mib(m.heapMax())+" MiB");runtime.value("Heap committed",RuntimeMetrics.mib(m.heapCommitted())+" MiB");runtime.value("Logical CPUs",m.processors());runtime.value("JVM uptime",m.processUptime()/1000+" s");
        runtime.value("TCP sessions",state.sessions().size());runtime.value("Scene instances",state.scenes().size());runtime.value("Resources",state.maps()+" scene templates · "+state.items()+" items");
        long min=recent.stream().mapToLong(RuntimeMetrics::heapUsed).min().orElse(0),max=recent.stream().mapToLong(RuntimeMetrics::heapUsed).max().orElse(0);
        runtime.value("Recent heap range",RuntimeMetrics.mib(min)+"–"+RuntimeMetrics.mib(max)+" MiB · "+recent.size()+"/120 samples");
        var db=Database.diagnostics();diagnostics.value("Database operations",db.operations());diagnostics.value("Slow operations / failures",db.slowOperations()+" / "+db.failures());diagnostics.value("Rollbacks / maximum time",db.rollbacks()+" / "+db.maximumMillis()+" ms");diagnostics.value("Recent significant failure",failure==null?"None":failure.message());
        charts.sample(m,(int)count,System.currentTimeMillis());
    }
    private void applyTheme(){Color border=UIManager.getColor("Separator.foreground");if(border==null)border=Color.GRAY;for(JPanel card:cards)card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(border),BorderFactory.createEmptyBorder(12,14,12,14)));}
    public void close(){charts.close();org.tortodrone.admin.DesktopTheme.removeListener(themeListener);}
}
