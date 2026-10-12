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
import org.tortodrone.admin.DesktopWidgets;

/** Optional Swing view. State comes from snapshots; log text is never treated as server state. */
final class ServerWindow extends JFrame implements AutoCloseable {
    private static final int HISTORY=1500;
    private final ServerControl control;
    private final ServerLog log;
    private final JLabel overview=new JLabel("Starting…"),warning=new JLabel(" ");
    private final PerformanceView performance=new PerformanceView();
    private final DesktopWidgets.Details sessionDetail=new DesktopWidgets.Details("Account / knight","Scene / instance","Endpoint / state","Connected / last request","IDs / protocol");
    private final DefaultTableModel playerModel=model("Session","Account ID","Username","Knight","Knight ID","Scene","Instance ID","Endpoint","Duration","State");
    private final DefaultTableModel sceneModel=model("Template ID","Instance ID","Name","Players","State");
    private final JTable players=new JTable(playerModel),scenes=new JTable(sceneModel);
    private final DefaultListModel<ServerLog.Event> output=new DefaultListModel<>();
    private final JList<ServerLog.Event> console=new JList<>(output);
    private final JScrollPane consoleScroll=new JScrollPane(console);
    private final JTextField command=new JTextField(),filter=new JTextField(16);
    private final JCheckBox debug=new JCheckBox("Debug"),pause=new JCheckBox("Pause"),scroll=new JCheckBox("Auto-scroll",true);
    private final JButton start=new JButton("Start"),stop=new JButton("Stop"),restart=new JButton("Restart"),admin=new JButton("Open knight in account manager");
    private final ArrayBlockingQueue<ServerLog.Event> incoming=new ArrayBlockingQueue<>(4096);
    private final ArrayDeque<ServerLog.Event> history=new ArrayDeque<>();
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
        DesktopWidgets.configureTable(players);DesktopWidgets.configureTable(scenes);
        players.getColumnModel().getColumn(8).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer(){public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(t,value,selected,focus,row,column);setText(value+" s");return this;}});
        for(int c:new int[]{7,6,4,1})players.removeColumn(players.getColumnModel().getColumn(c));
        JTextField playerSearch=new JTextField();DesktopWidgets.searchField(playerSearch,"Find username, knight or scene");playerTab.add(playerSearch,BorderLayout.NORTH);
        playerSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){private void changed(){((javax.swing.table.TableRowSorter<?>)players.getRowSorter()).setRowFilter(playerSearch.getText().isBlank()?null:RowFilter.regexFilter("(?i)"+Pattern.quote(playerSearch.getText()),2,3,5));selected();}public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}});
        playerTab.add(new JScrollPane(players));JPanel detail=new JPanel(new BorderLayout(0,6));detail.add(sessionDetail);detail.add(admin,BorderLayout.SOUTH);playerTab.add(detail,BorderLayout.SOUTH);tabs.addTab("Players",playerTab);
        players.getSelectionModel().addListSelectionListener(e->selected());admin.addActionListener(e->{var session=selectedSession();if(session!=null&&session.knight()!=null)org.tortodrone.admin.AdminApplication.openKnight(control.dataDirectory(),session.knight());});
        scenes.setAutoCreateRowSorter(true);scenes.moveColumn(2,0);tabs.addTab("Scenes",new JScrollPane(scenes));tabs.addTab("Performance",performance);
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
        JPanel consolePanel=new JPanel(new BorderLayout(6,6));consolePanel.add(consoleScroll);
        JPanel tools=new JPanel(new BorderLayout(8,0));DesktopWidgets.searchField(filter,"Filter logs");tools.add(filter);JPanel logControls=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));logControls.add(debug);logControls.add(pause);logControls.add(scroll);
        JButton clear=new JButton("Clear"),copy=new JButton("Copy"),logs=new JButton("Logs folder");logControls.add(clear);logControls.add(copy);tools.add(logControls,BorderLayout.EAST);consolePanel.add(tools,BorderLayout.NORTH);
        consoleScroll.addMouseWheelListener(e->{if(e.getWheelRotation()<0)scroll.setSelected(false);});
        consoleScroll.getVerticalScrollBar().addAdjustmentListener(e->{if(e.getValueIsAdjusting())scroll.setSelected(false);});
        scroll.addActionListener(e->{if(scroll.isSelected()&&!output.isEmpty())console.ensureIndexIsVisible(output.size()-1);});
        clear.addActionListener(e->{history.clear();output.clear();});copy.addActionListener(e->{String text=console.getSelectedValuesList().stream().map(ServerLog.Event::line).collect(java.util.stream.Collectors.joining(System.lineSeparator()));if(text.isEmpty())text=java.util.Collections.list(output.elements()).stream().map(ServerLog.Event::line).collect(java.util.stream.Collectors.joining(System.lineSeparator()));Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text),null);});
        logs.addActionListener(e->submit(()->{try{if(!Desktop.isDesktopSupported()||!Desktop.getDesktop().isSupported(Desktop.Action.OPEN))throw new java.io.IOException("Opening directories is unavailable; logs: "+log.directory());Desktop.getDesktop().open(log.directory().toFile());}catch(Exception failed){log.warn(failed.getMessage());}}));
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){private void changed(){render();}public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}});
        debug.addActionListener(e->render());pause.addActionListener(e->{if(!pause.isSelected())render();});
        JPanel input=new JPanel(new BorderLayout(6,0));input.add(new JLabel("Command"),BorderLayout.WEST);input.add(command);DesktopWidgets.searchField(command,"Enter a server command · Up/Down history · Tab completes");JPanel inputActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));JButton run=new JButton("Run");run.addActionListener(e->execute());inputActions.add(run);inputActions.add(logs);input.add(inputActions,BorderLayout.EAST);consolePanel.add(input,BorderLayout.SOUTH);
        command.addActionListener(e->execute());command.addKeyListener(new KeyAdapter(){public void keyPressed(KeyEvent e){
            if(e.getKeyCode()==KeyEvent.VK_UP){if(historyIndex>0)command.setText(commands.get(--historyIndex));e.consume();}
            else if(e.getKeyCode()==KeyEvent.VK_DOWN){if(historyIndex<commands.size())historyIndex++;command.setText(historyIndex==commands.size()?"":commands.get(historyIndex));e.consume();}
            else if(e.getKeyCode()==KeyEvent.VK_TAB){String text=command.getText().stripLeading();var matches=List.of("help","status","stop","players","session","inspect","items","giveitem","grant","set","account","knight","currency","gm").stream().filter(c->c.startsWith(text.replaceFirst("^/",""))).toList();if(matches.size()==1)command.setText(matches.get(0)+" ");e.consume();}
        }});command.setFocusTraversalKeysEnabled(false);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,tabs,consolePanel);split.setResizeWeight(1);split.setDividerLocation(520);root.add(split);
        JToggleButton consoleToggle=new JToggleButton("Console",true);controls.add(consoleToggle);int[] divider={520};consoleToggle.addActionListener(e->{if(!consoleToggle.isSelected()){divider[0]=split.getDividerLocation();split.setBottomComponent(null);split.setDividerSize(0);}else{split.setBottomComponent(consolePanel);split.setDividerSize(UIManager.getInt("SplitPane.dividerSize"));split.setDividerLocation(divider[0]);}});
        subscription=log.subscribe(event->{if(!incoming.offer(event)){incoming.poll();incoming.offer(event);}});
        timer=new javax.swing.Timer(1000,e->update());timer.start();update();
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(confirm("Stop server and exit")){control.exit();close();}}});
    }
    private static DefaultTableModel model(String... columns){return new DefaultTableModel(columns,0){public boolean isCellEditable(int r,int c){return false;}public Class<?> getColumnClass(int c){return switch(getColumnName(c)){case "Session","Duration"->Long.class;case "Account ID","Knight ID","Template ID","Instance ID","Players"->Integer.class;default->String.class;};}};}
    private boolean confirm(String action){int count=control.sessions().snapshots().size();return JOptionPane.showConfirmDialog(this,action+"?"+(count==0?"":"\n"+count+" connected sessions will disconnect."),"Tortodrone",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)==JOptionPane.OK_OPTION;}
    private void submit(Runnable action){try{worker.execute(action);}catch(RejectedExecutionException full){log.warn("Desktop command queue unavailable; wait for pending operations to finish");}}
    private void execute(){String text=command.getText().strip();if(text.isEmpty())return;commands.add(text);if(commands.size()>100)commands.remove(0);historyIndex=commands.size();command.setText("");submit(()->{try{control.command(text);}catch(Exception failure){log.warn(failure.getMessage());}});}
    private void update(){
        if(closed)return;boolean changed=false;ServerLog.Event event;while((event=incoming.poll())!=null){history.add(event);if(history.size()>HISTORY)history.removeFirst();changed=true;}if(changed&&!pause.isSelected())render();
        if(sampling)return;sampling=true;
        new SwingWorker<ServerControl.Snapshot,Void>(){protected ServerControl.Snapshot doInBackground(){return control.snapshot();}protected void done(){sampling=false;if(closed)return;try{show(get());}catch(Exception failure){warning.setText("Status unavailable: "+failure.getClass().getSimpleName());}}}.execute();
    }
    private void render(){
        if(pause.isSelected())return;
        String query=filter.getText().toLowerCase(Locale.ROOT);
        var visible=history.stream().filter(event->(debug.isSelected()||!event.level().equals("DEBUG"))&&event.line().toLowerCase(Locale.ROOT).contains(query)).toList();
        var selected=console.getSelectedValuesList();int first=console.getFirstVisibleIndex();
        ServerLog.Event anchor=first<0?null:output.get(first);Point position=consoleScroll.getViewport().getViewPosition();
        Rectangle bounds=first<0?null:console.getCellBounds(first,first);int offset=bounds==null?0:position.y-bounds.y;
        int retained=visible.isEmpty()?-1:output.indexOf(visible.getFirst());
        if(retained>=0)for(int i=0;i<retained;i++)output.remove(0);
        boolean prefix=output.size()<=visible.size();
        if(prefix)for(int i=0;i<output.size();i++)if(!output.get(i).equals(visible.get(i))){prefix=false;break;}
        if(!prefix)output.clear();
        for(int i=output.size();i<visible.size();i++)output.addElement(visible.get(i));
        int[] selection=selected.stream().mapToInt(output::indexOf).filter(i->i>=0).toArray();console.setSelectedIndices(selection);
        if(scroll.isSelected()&&!output.isEmpty())console.ensureIndexIsVisible(output.size()-1);
        else if(anchor!=null){int index=output.indexOf(anchor);Rectangle cell=index<0?null:console.getCellBounds(index,index);if(cell!=null)consoleScroll.getViewport().setViewPosition(new Point(position.x,Math.max(0,cell.y+offset)));}
    }
    private void show(ServerControl.Snapshot state){
        if(restartPending&&(state.state()==ServerControl.State.STOPPED||state.state()==ServerControl.State.FAILED)){restartPending=false;control.start();}
        start.setEnabled(state.state()==ServerControl.State.STOPPED||state.state()==ServerControl.State.FAILED);stop.setEnabled(state.state()==ServerControl.State.RUNNING||state.state()==ServerControl.State.STARTING);restart.setEnabled(state.state()==ServerControl.State.RUNNING);
        overview.setText("<html>Tortodrone "+state.version()+" · "+state.state()+" · "+state.endpoint()+"<br>"+state.sessions().size()+" sessions · "+state.sessions().stream().filter(s->s.knightId()!=null).count()+" players · "+state.uptimeSeconds()+"s</html>");
        warning.setText(!state.failure().isEmpty()?state.failure():log.recentFailure()==null?" ":"Recent warning/error: "+log.recentFailure().message());
        sessions=state.sessions();var playerRows=new ArrayList<Object[]>();
        for(var s:sessions)playerRows.add(new Object[]{s.id(),s.accountId(),s.username(),s.knight(),s.knightId(),s.scene(),s.instanceId(),s.remote(),duration(s.connectedAt()),s.state()});
        DesktopWidgets.updateRows(players,playerModel,playerRows,0);selected();
        var sceneRows=new ArrayList<Object[]>();for(var template:state.templates())sceneRows.add(new Object[]{template.id(),null,template.name(),null,"Loaded template ("+template.kind()+")"});
        for(var s:state.scenes())sceneRows.add(new Object[]{s.templateId(),s.instanceId(),s.name(),s.players(),s.state()});
        DesktopWidgets.updateRows(scenes,sceneModel,sceneRows,0,1);
        performance.update(state,log.recentFailure());
    }
    private SessionRegistry.Snapshot selectedSession(){int row=players.getSelectedRow();if(row<0)return null;long id=((Number)playerModel.getValueAt(players.convertRowIndexToModel(row),0)).longValue();return sessions.stream().filter(s->s.id()==id).findFirst().orElse(null);}
    private void selected(){var s=selectedSession();admin.setEnabled(s!=null&&s.knight()!=null);
        sessionDetail.value("Account / knight",s==null?"Select a connection":s.username()==null?"Not authenticated":s.username()+" / "+(s.knight()==null?"No knight selected":s.knight()));
        sessionDetail.value("Scene / instance",s==null?null:s.scene()==null?"Not in a scene":s.scene()+" / "+s.instanceId());
        sessionDetail.value("Endpoint / state",s==null?null:s.remote()+" · "+s.state());
        sessionDetail.value("Connected / last request",s==null?null:timestamp(s.connectedAt())+" / "+timestamp(s.lastActivity()));
        sessionDetail.value("IDs / protocol",s==null?null:"session "+s.id()+" · account "+display(s.accountId())+" · knight "+display(s.knightId())+" · roster "+display(s.rosterId())+" · template "+display(s.sceneId())+" · build "+display(s.clientVersion()));
    }
    private static String display(Object value){return value==null?"—":value.toString();}
    private static String timestamp(Instant time){return time==null?"—":java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(time.atZone(java.time.ZoneId.systemDefault()));}
    private static long duration(Instant time){return time==null?0:Math.max(0,Duration.between(time,Instant.now()).toSeconds());}
    @Override public void close(){if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::close);return;}if(closed)return;closed=true;timer.stop();performance.close();worker.shutdown();try{subscription.close();}catch(Exception ignored){ }for(Window window:Window.getWindows())if(window!=this)window.dispose();dispose();}
}
