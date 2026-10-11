package org.tortodrone.server;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Optional Swing view. State comes from snapshots; log text is never treated as server state. */
final class ServerWindow extends JFrame implements AutoCloseable {
    private static final int HISTORY=1500;
    private final ServerControl control;
    private final ServerLog log;
    private final JLabel overview=new JLabel("Starting…"),warning=new JLabel(" ");
    private final JTextArea performance=new JTextArea(),sessionDetail=new JTextArea();
    private final PerformanceCharts charts=new PerformanceCharts();
    private final DefaultTableModel playerModel=model("Session","Account ID","Username","Knight","Knight ID","Scene","Instance ID","Endpoint","Duration","State");
    private final DefaultTableModel sceneModel=model("Template ID","Instance ID","Name","Players","State");
    private final JTable players=new JTable(playerModel),scenes=new JTable(sceneModel);
    private final DefaultListModel<ServerLog.Event> output=new DefaultListModel<>();
    private final JList<ServerLog.Event> console=new JList<>(output);
    private final JTextField command=new JTextField(),filter=new JTextField(16);
    private final JCheckBox debug=new JCheckBox("Debug"),pause=new JCheckBox("Pause"),scroll=new JCheckBox("Auto-scroll",true);
    private final JButton start=new JButton("Start"),stop=new JButton("Stop"),restart=new JButton("Restart"),admin=new JButton("Open knight in account manager");
    private final ArrayBlockingQueue<ServerLog.Event> incoming=new ArrayBlockingQueue<>(4096);
    private final ArrayDeque<ServerLog.Event> history=new ArrayDeque<>();
    private final ArrayDeque<RuntimeMetrics> samples=new ArrayDeque<>();
    private final List<String> commands=new ArrayList<>();
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),task->{var t=new Thread(task,"DesktopCommands");t.setDaemon(true);return t;});
    private final javax.swing.Timer timer;
    private AutoCloseable subscription;
    private List<SessionRegistry.Snapshot> sessions=List.of();
    private boolean sampling,closed,restartPending;
    private int historyIndex;

    static ServerWindow open(ServerControl control,ServerLog log) throws Exception {
        ServerWindow[] result=new ServerWindow[1];
        try {
            SwingUtilities.invokeAndWait(()->{
                org.tortodrone.admin.DesktopTheme.installDefault();
                result[0]=new ServerWindow(control,log);result[0].setVisible(true);
            });
        } catch(Exception error) {if(result[0]!=null)SwingUtilities.invokeLater(result[0]::close);throw error;}
        return result[0];
    }
    private ServerWindow(ServerControl control,ServerLog log) {
        super("Tortodrone — Server control");this.control=control;this.log=log;setJMenuBar(org.tortodrone.admin.DesktopTheme.menuBar());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);setMinimumSize(new Dimension(950,640));setSize(1200,850);setLocationByPlatform(true);
        JPanel root=new JPanel(new BorderLayout(10,10));root.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));setContentPane(root);
        JPanel heading=new JPanel(new BorderLayout());overview.setFont(overview.getFont().deriveFont(Font.BOLD,17));heading.add(overview,BorderLayout.NORTH);heading.add(warning);
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.RIGHT));controls.add(start);controls.add(stop);controls.add(restart);JButton accounts=new JButton("Account manager");controls.add(accounts);heading.add(controls,BorderLayout.SOUTH);root.add(heading,BorderLayout.NORTH);
        start.addActionListener(e->control.start());stop.addActionListener(e->{if(confirm("Stop server"))control.stop();});restart.addActionListener(e->{if(confirm("Restart server")){restartPending=true;control.stop();}});
        accounts.addActionListener(e->org.tortodrone.admin.AdminApplication.openKnight(control.dataDirectory(),null));
        JTabbedPane tabs=new JTabbedPane();
        JPanel playerTab=new JPanel(new BorderLayout(6,6));players.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);players.setAutoCreateRowSorter(true);
        playerTab.add(new JScrollPane(players));sessionDetail.setEditable(false);sessionDetail.setRows(5);JPanel detail=new JPanel(new BorderLayout());detail.add(new JScrollPane(sessionDetail));detail.add(admin,BorderLayout.SOUTH);playerTab.add(detail,BorderLayout.SOUTH);tabs.addTab("Players",playerTab);
        players.getSelectionModel().addListSelectionListener(e->selected());admin.addActionListener(e->{var session=selectedSession();if(session!=null&&session.knight()!=null)org.tortodrone.admin.AdminApplication.openKnight(control.dataDirectory(),session.knight());});
        scenes.setAutoCreateRowSorter(true);tabs.addTab("Scenes",new JScrollPane(scenes));performance.setEditable(false);performance.setLineWrap(true);performance.setWrapStyleWord(true);performance.setBorder(BorderFactory.createEmptyBorder(8,10,8,10));
        JScrollPane stats=new JScrollPane(performance);stats.setPreferredSize(new Dimension(750,145));JPanel performanceTab=new JPanel(new BorderLayout(8,8));performanceTab.add(stats,BorderLayout.NORTH);performanceTab.add(charts,BorderLayout.CENTER);tabs.addTab("Performance",performanceTab);
        console.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));console.setCellRenderer(new DefaultListCellRenderer(){
            public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                super.getListCellRendererComponent(list,value,index,selected,focus);var event=(ServerLog.Event)value;setText(event.line());
                if(!selected)setForeground(switch(event.level()){
                    case "ERROR"->org.tortodrone.admin.DesktopTheme.isDark()?new Color(255,125,125):new Color(180,35,35);
                    case "WARN"->org.tortodrone.admin.DesktopTheme.isDark()?new Color(245,195,105):new Color(135,82,0);
                    case "DEBUG"->UIManager.getColor("Label.disabledForeground");
                    default->UIManager.getColor("List.foreground");});return this;
            }
        });
        JPanel consolePanel=new JPanel(new BorderLayout(6,6));consolePanel.add(new JScrollPane(console));
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEFT));tools.add(new JLabel("Logs:"));tools.add(filter);tools.add(debug);tools.add(pause);tools.add(scroll);
        JButton clear=new JButton("Clear view"),copy=new JButton("Copy"),logs=new JButton("Open log directory");tools.add(clear);tools.add(copy);tools.add(logs);consolePanel.add(tools,BorderLayout.NORTH);
        clear.addActionListener(e->{history.clear();output.clear();});copy.addActionListener(e->{String text=console.getSelectedValuesList().stream().map(ServerLog.Event::line).collect(java.util.stream.Collectors.joining(System.lineSeparator()));if(text.isEmpty())text=java.util.Collections.list(output.elements()).stream().map(ServerLog.Event::line).collect(java.util.stream.Collectors.joining(System.lineSeparator()));Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text),null);});
        logs.addActionListener(e->submit(()->{try{if(!Desktop.isDesktopSupported()||!Desktop.getDesktop().isSupported(Desktop.Action.OPEN))throw new java.io.IOException("Opening directories is unavailable; logs: "+log.directory());Desktop.getDesktop().open(log.directory().toFile());}catch(Exception failed){log.warn(failed.getMessage());}}));
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){private void changed(){render();}public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}});
        debug.addActionListener(e->render());pause.addActionListener(e->{if(!pause.isSelected())render();});
        JPanel input=new JPanel(new BorderLayout(6,0));input.add(new JLabel("Command >"),BorderLayout.WEST);input.add(command);consolePanel.add(input,BorderLayout.SOUTH);
        command.addActionListener(e->execute());command.addKeyListener(new KeyAdapter(){public void keyPressed(KeyEvent e){
            if(e.getKeyCode()==KeyEvent.VK_UP){if(historyIndex>0)command.setText(commands.get(--historyIndex));e.consume();}
            else if(e.getKeyCode()==KeyEvent.VK_DOWN){if(historyIndex<commands.size())historyIndex++;command.setText(historyIndex==commands.size()?"":commands.get(historyIndex));e.consume();}
            else if(e.getKeyCode()==KeyEvent.VK_TAB){String text=command.getText().stripLeading();var matches=List.of("help","status","stop","players","session","inspect","items","giveitem","grant","set","account","knight","currency","gm").stream().filter(c->c.startsWith(text.replaceFirst("^/",""))).toList();if(matches.size()==1)command.setText(matches.get(0)+" ");e.consume();}
        }});command.setFocusTraversalKeysEnabled(false);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,tabs,consolePanel);split.setResizeWeight(.62);split.setDividerLocation(390);root.add(split);
        subscription=log.subscribe(event->{if(!incoming.offer(event)){incoming.poll();incoming.offer(event);}});
        timer=new javax.swing.Timer(1000,e->update());timer.start();update();
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(confirm("Stop server and exit")){control.exit();close();}}});
    }
    private static DefaultTableModel model(String... columns){return new DefaultTableModel(columns,0){public boolean isCellEditable(int r,int c){return false;}public Class<?> getColumnClass(int c){return Object.class;}};}
    private boolean confirm(String action){int count=control.sessions().snapshots().size();return JOptionPane.showConfirmDialog(this,action+"?"+(count==0?"":"\n"+count+" connected sessions will disconnect."),"Tortodrone",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)==JOptionPane.OK_OPTION;}
    private void submit(Runnable action){try{worker.execute(action);}catch(RejectedExecutionException full){log.warn("Desktop command queue unavailable; wait for pending operations to finish");}}
    private void execute(){String text=command.getText().strip();if(text.isEmpty())return;commands.add(text);if(commands.size()>100)commands.remove(0);historyIndex=commands.size();command.setText("");submit(()->{try{control.command(text);}catch(Exception failure){log.warn(failure.getMessage());}});}
    private void update(){
        if(closed)return;boolean changed=false;ServerLog.Event event;while((event=incoming.poll())!=null){history.add(event);if(history.size()>HISTORY)history.removeFirst();changed=true;}if(changed&&!pause.isSelected())render();
        if(sampling)return;sampling=true;
        new SwingWorker<ServerControl.Snapshot,Void>(){protected ServerControl.Snapshot doInBackground(){return control.snapshot();}protected void done(){sampling=false;if(closed)return;try{show(get());}catch(Exception failure){warning.setText("Status unavailable: "+failure.getClass().getSimpleName());}}}.execute();
    }
    private void render(){if(pause.isSelected())return;output.clear();String query=filter.getText().toLowerCase(Locale.ROOT);for(var event:history)if((debug.isSelected()||!event.level().equals("DEBUG"))&&event.line().toLowerCase(Locale.ROOT).contains(query))output.addElement(event);if(scroll.isSelected()&&!output.isEmpty())console.ensureIndexIsVisible(output.size()-1);}
    private void show(ServerControl.Snapshot state){
        if(restartPending&&(state.state()==ServerControl.State.STOPPED||state.state()==ServerControl.State.FAILED)){restartPending=false;control.start();}
        start.setEnabled(state.state()==ServerControl.State.STOPPED||state.state()==ServerControl.State.FAILED);stop.setEnabled(state.state()==ServerControl.State.RUNNING||state.state()==ServerControl.State.STARTING);restart.setEnabled(state.state()==ServerControl.State.RUNNING);
        overview.setText("<html>Tortodrone "+state.version()+" · "+state.state()+" · "+state.endpoint()+"<br>"+state.sessions().size()+" sessions · "+state.sessions().stream().filter(s->s.knightId()!=null).count()+" players · "+state.uptimeSeconds()+"s</html>");
        warning.setText(!state.failure().isEmpty()?state.failure():log.recentFailure()==null?" ":"Recent warning/error: "+log.recentFailure().message());
        Long selected=selectedSession()==null?null:selectedSession().id();sessions=state.sessions();playerModel.setRowCount(0);
        for(var s:sessions)playerModel.addRow(new Object[]{s.id(),s.accountId(),s.username(),s.knight(),s.knightId(),s.scene(),s.instanceId(),s.remote(),duration(s.connectedAt()),s.state()});
        if(selected!=null)for(int r=0;r<playerModel.getRowCount();r++)if(selected.equals(playerModel.getValueAt(r,0))){int view=players.convertRowIndexToView(r);players.setRowSelectionInterval(view,view);break;}selected();
        sceneModel.setRowCount(0);for(var template:state.templates())sceneModel.addRow(new Object[]{template.id(),null,template.name(),null,"Loaded template ("+template.kind()+")"});
        for(var s:state.scenes())sceneModel.addRow(new Object[]{s.templateId(),s.instanceId(),s.name(),s.players(),s.state()});
        samples.add(state.metrics());if(samples.size()>120)samples.removeFirst();var m=state.metrics();
        charts.sample(m,(int)state.sessions().stream().filter(s->s.knightId()!=null).count(),System.currentTimeMillis());
        long min=samples.stream().mapToLong(RuntimeMetrics::heapUsed).min().orElse(0),max=samples.stream().mapToLong(RuntimeMetrics::heapUsed).max().orElse(0);
        performance.setText("Heap used / max: "+RuntimeMetrics.mib(m.heapUsed())+" / "+RuntimeMetrics.mib(m.heapMax())+" MiB\nHeap committed: "+RuntimeMetrics.mib(m.heapCommitted())+" MiB\nLogical CPUs: "+m.processors()+"\nProcess CPU: "+(m.processCpu()<0?"Unavailable":String.format(Locale.ROOT,"%.1f%%",100*m.processCpu()))+"\nJVM uptime: "+m.processUptime()/1000+"s\nSessions: "+state.sessions().size()+"\nInstances: "+state.scenes().size()+"\nResources: "+state.maps()+" templates, "+state.items()+" items\nRecent heap range ("+samples.size()+" samples, maximum 120): "+RuntimeMetrics.mib(min)+"–"+RuntimeMetrics.mib(max)+" MiB\nDatabase diagnostics: "+org.tortodrone.storage.Database.diagnostics()+"\nRecent significant failure: "+(log.recentFailure()==null?"None":log.recentFailure().message()));
    }
    private SessionRegistry.Snapshot selectedSession(){int row=players.getSelectedRow();if(row<0)return null;long id=((Number)playerModel.getValueAt(players.convertRowIndexToModel(row),0)).longValue();return sessions.stream().filter(s->s.id()==id).findFirst().orElse(null);}
    private void selected(){var s=selectedSession();admin.setEnabled(s!=null&&s.knight()!=null);sessionDetail.setText(s==null?"Select a connection":s.remote()+" · "+s.state()+"\nAccount: "+s.username()+" (#"+s.accountId()+") · Knight: "+s.knight()+" (#"+s.knightId()+") · roster ID: "+s.rosterId()+"\nScene: "+s.scene()+" · template ID: "+s.sceneId()+" · instance ID: "+s.instanceId()+"\nConnected: "+s.connectedAt()+" · last non-heartbeat request: "+s.lastActivity()+"\nClient protocol build: "+s.clientVersion());}
    private static String duration(Instant time){return time==null?"0s":Duration.between(time,Instant.now()).toSeconds()+"s";}
    @Override public void close(){if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::close);return;}if(closed)return;closed=true;timer.stop();charts.close();worker.shutdown();try{subscription.close();}catch(Exception ignored){ }for(Window window:Window.getWindows())if(window!=this)window.dispose();dispose();}
}
