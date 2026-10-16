package org.tortodrone.admin;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.TableRowSorter;
import org.tortodrone.account.AccountStore;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.Currency;
import org.tortodrone.character.Knight;

/** Desktop-only view; all expensive or blocking account work happens outside the Swing thread. */
final class AdminWindow extends JFrame {
    private record AccountRows(List<AccountStore.Summary> accounts, Map<Integer, List<String>> knightNames) { }
    private volatile AccountStore accounts;
    private final AccountTableModel model = new AccountTableModel();
    private final JTable table = new JTable(model);
    private final JTextField accountSearch=new JTextField();
    private final JLabel status = new JLabel("Opening accounts…");
    private final JLabel accountCount=new JLabel(" ");
    private final DesktopWidgets.Details accountDetails=new DesktopWidgets.Details("Username","Account ID","Created");
    private final List<JButton> buttons = new ArrayList<>();
    private final List<JButton> selectedButtons = new ArrayList<>();
    private boolean busy, closed, replacing;
    private AccountRows rows;
    private String requestedKnight;
    private String requestedUsername;
    private final KnightPanel knightPanel=new KnightPanel(this);
    private final AuctionPanel auctionPanel=new AuctionPanel(this);
    private final DepotPanel depotPanel=new DepotPanel(this);
    private final JList<String> navigation=new JList<>(new String[]{"Accounts & knights","Supply Depot","Auction House"});

    AdminWindow(Path directory) { this(directory,null); }
    AdminWindow(Path directory,String initialKnight) {
        super("Tortodrone — Administration");
        setJMenuBar(DesktopTheme.menuBar());
        requestedKnight=initialKnight;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE); setMinimumSize(new Dimension(1200,640)); setSize(1400,760); setLocationByPlatform(true);
        JPanel content = new JPanel(new BorderLayout(12,12)); content.setBorder(BorderFactory.createEmptyBorder(16,16,16,16)); setContentPane(content);
        JPanel heading = new JPanel(new BorderLayout(8,8));
        JLabel title = new JLabel("Tortodrone administration"); title.setFont(title.getFont().deriveFont(Font.BOLD,24f)); heading.add(title,BorderLayout.NORTH);
        JLabel location=new JLabel("Accounts, gear and Town services");location.setToolTipText(directory.toAbsolutePath().normalize().resolve("tortodrone.db").toString());heading.add(location);content.add(heading,BorderLayout.NORTH);
        JTextField search = accountSearch;DesktopWidgets.searchField(search,"Find username or knight");
        DesktopWidgets.configureTable(table);
        var sorter = new TableRowSorter<>(model); table.setRowSorter(sorter); table.getColumnModel().getColumn(0).setMaxWidth(80); table.getColumnModel().getColumn(2).setMaxWidth(80);
        for(int c:new int[]{5,4,0})table.removeColumn(table.getColumnModel().getColumn(c));table.moveColumn(1,2);table.getColumnModel().getColumn(2).setHeaderValue("GM");table.getColumnModel().getColumn(2).setPreferredWidth(45);
        JPanel accountPane=new JPanel(new BorderLayout(0,10));accountPane.setMinimumSize(new Dimension(310,0));
        JPanel accountHeading=new JPanel(new BorderLayout(0,8));accountHeading.add(new JLabel("Accounts"),BorderLayout.NORTH);accountHeading.add(search);JPanel listActions=new JPanel(new GridLayout(1,2,6,0));button(listActions,"Create account",false,this::create);button(listActions,"Refresh",false,this::refresh);accountHeading.add(listActions,BorderLayout.SOUTH);accountPane.add(accountHeading,BorderLayout.NORTH);
        accountPane.add(new JScrollPane(table));
        JPanel accountFooter=new JPanel(new BorderLayout(0,8));accountFooter.add(accountDetails,BorderLayout.NORTH);JPanel actions=new JPanel(new GridLayout(0,2,6,6));
        button(actions,"Change password",true,this::password);button(actions,"Check password",true,this::check);button(actions,"Set Game Master",true,this::gameMaster);button(actions,"Delete account",true,this::delete);accountFooter.add(actions);accountFooter.add(accountCount,BorderLayout.SOUTH);accountPane.add(accountFooter,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,accountPane,knightPanel);split.setResizeWeight(.28);split.setDividerLocation(330);split.setContinuousLayout(true);
        var cards=new JPanel(new CardLayout(12,0));cards.add(split,"Accounts & knights");cards.add(depotPanel,"Supply Depot");cards.add(auctionPanel,"Auction House");
        navigation.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);navigation.setFixedCellHeight(46);navigation.setBorder(BorderFactory.createEmptyBorder(4,4,4,4));navigation.setSelectedIndex(0);navigation.setPreferredSize(new Dimension(175,0));navigation.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&navigation.getSelectedValue()!=null){String selected=navigation.getSelectedValue();((CardLayout)cards.getLayout()).show(cards,selected);if(selected.equals("Supply Depot"))depotPanel.refresh();else if(selected.equals("Auction House"))auctionPanel.refresh();}});
        var workspace=new JPanel(new BorderLayout(8,0));workspace.add(navigation,BorderLayout.WEST);workspace.add(cards);content.add(workspace);
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void update() { sorter.setRowFilter(search.getText().isEmpty() ? null : RowFilter.regexFilter("(?i)"+Pattern.quote(search.getText()),1,3));accountCount.setText(table.getRowCount()+" of "+model.getRowCount()+" accounts"); }
            public void insertUpdate(DocumentEvent event) { update(); } public void removeUpdate(DocumentEvent event) { update(); } public void changedUpdate(DocumentEvent event) { update(); }
        });
        table.getSelectionModel().addListSelectionListener(event -> { enableActions(); if(!event.getValueIsAdjusting()&&!replacing&&!busy)selectAccount(); });
        status.setBorder(BorderFactory.createEmptyBorder(6,0,0,0));content.add(status,BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent event) { closed=true; if (!busy && accounts != null) accounts.close(); } });
        execute(() -> { accounts = new AccountStore(directory); return loadRows(); },this::loaded);
    }
    private void button(JPanel panel,String text,boolean selection,Runnable action) {
        JButton button = new JButton(text); button.addActionListener(event -> { if (!busy) action.run(); }); panel.add(button); buttons.add(button); if (selection) selectedButtons.add(button);
    }
    private AccountStore.Summary selected() {
        int row = table.getSelectedRow(); return row < 0 || row >= table.getRowCount() ? null : model.account(table.convertRowIndexToModel(row));
    }
    private void enableActions() {
        for (JButton button : buttons) button.setEnabled(!busy && accounts != null);
        for (JButton button : selectedButtons) button.setEnabled(!busy && selected() != null);
        var selected=selected();accountDetails.value("Username",selected==null?"Select an account":selected.username());accountDetails.value("Account ID",selected==null?null:selected.id());accountDetails.value("Created",selected==null?null:java.time.Instant.ofEpochMilli(selected.createdAt()).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0));
    }
    private AccountRows loadRows() throws IOException {
        var rows = accounts.list(); var names = new HashMap<Integer, List<String>>();
        for (var account : rows) names.put(account.id(), accounts.knights(account.username()).stream().map(Knight::name).toList());
        return new AccountRows(rows, names);
    }
    private void loaded(AccountRows rows) {
        var previous=selected();this.rows=rows;replacing=true;
        if(requestedUsername!=null)accountSearch.setText("");
        model.replace(rows.accounts(), rows.knightNames());
        int wanted=previous==null?-1:previous.id();
        if(requestedUsername!=null)for(var account:rows.accounts())if(account.username().equals(requestedUsername))wanted=account.id();requestedUsername=null;
        if(requestedKnight!=null) for(var entry:rows.knightNames().entrySet())if(entry.getValue().stream().anyMatch(n->n.equalsIgnoreCase(requestedKnight)))wanted=entry.getKey();
        for(int i=0;i<model.getRowCount();i++)if(model.account(i).id()==wanted){int view=table.convertRowIndexToView(i);if(view>=0)table.setRowSelectionInterval(view,view);break;}
        if(table.getSelectedRow()<0&&table.getRowCount()>0)table.setRowSelectionInterval(0,0);
        replacing=false;
        accountCount.setText(table.getRowCount()+" of "+model.getRowCount()+" accounts");status.setText(rows.accounts().isEmpty() ? "No accounts. Create an account to enable normal login." : "Select an account to manage its knights."); enableActions();selectAccount();
    }
    private void selectAccount() {
        var selected=selected();if(selected==null||rows==null){knightPanel.clear();return;}
        String requested=requestedKnight;requestedKnight=null;
        knightPanel.account(selected,rows.knightNames().getOrDefault(selected.id(),List.of()),requested);
    }
    private void refresh() { execute(this::loadRows,this::loaded); }
    private JPanel form(Object... rows) {
        JPanel form = new JPanel(new GridLayout(0,2,10,10));
        for (int i=0;i<rows.length;i+=2) { form.add(new JLabel((String)rows[i])); form.add((Component)rows[i+1]); } return form;
    }
    private void create() {
        JTextField username = new JTextField(20); JPasswordField password = new JPasswordField(20), confirm = new JPasswordField(20); JCheckBox gameMaster = new JCheckBox("Game Master");
        if (JOptionPane.showConfirmDialog(this,form("Username",username,"Password",password,"Confirm password",confirm,"Game Master",gameMaster),"Create account",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        char[] first = password.getPassword(), second = confirm.getPassword(); password.setText(""); confirm.setText("");
        try { AccountStore.validateUsername(username.getText()); Passwords.validate(first); if (!Arrays.equals(first,second)) throw new IOException("Passwords do not match"); }
        catch (IOException failure) { Arrays.fill(first,'\0'); message(failure.getMessage(),true); return; }
        finally { Arrays.fill(second,'\0'); }
        String name = username.getText(); boolean flag = gameMaster.isSelected();
        execute(() -> { try { accounts.create(name,first,flag); return loadRows(); } finally { Arrays.fill(first,'\0'); } },rows -> { requestedUsername=name;loaded(rows); status.setText("Account created: "+name); });
    }
    private void password() {
        var account = selected(); if (account == null) return;
        JPasswordField password = new JPasswordField(20), confirm = new JPasswordField(20);
        if (JOptionPane.showConfirmDialog(this,form("New password",password,"Confirm password",confirm),"Change password — "+account.username(),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        char[] first = password.getPassword(), second = confirm.getPassword(); password.setText(""); confirm.setText("");
        try { Passwords.validate(first); if (!Arrays.equals(first,second)) throw new IOException("Passwords do not match"); }
        catch (IOException failure) { Arrays.fill(first,'\0'); message(failure.getMessage(),true); return; }
        finally { Arrays.fill(second,'\0'); }
        execute(() -> { try { accounts.setPassword(account.username(),first,account.id()); return loadRows(); } finally { Arrays.fill(first,'\0'); } },rows -> { loaded(rows); status.setText("Password changed. Existing sessions will disconnect."); });
    }
    private void check() {
        var account = selected(); if (account == null) return;
        JPasswordField field = new JPasswordField(20);
        if (JOptionPane.showConfirmDialog(this,form("Password to check",field),"Check password — "+account.username(),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        char[] password = field.getPassword(); field.setText("");
        execute(() -> { try { return accounts.checkPassword(account.username(),password); } finally { Arrays.fill(password,'\0'); } },matches -> {
            status.setText("Password check completed."); message(matches ? "Password matches." : "Password does not match.",false);
        });
    }
    private void gameMaster() {
        var account = selected(); if (account == null) return;
        JCheckBox flag = new JCheckBox("Game Master",account.gameMaster());
        flag.setToolTipText("Enables stock staff commands and extra item actions, including Trash on ordinary inventory items.");
        if (JOptionPane.showConfirmDialog(this,flag,"Game Master status — "+account.username(),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        boolean gameMaster = flag.isSelected();
        execute(() -> { accounts.setGameMaster(account.username(),gameMaster,account.id()); return loadRows(); },rows -> { loaded(rows); status.setText("Game Master status updated."); });
    }
    private void currency() { knightPanel.currency(); }

    private void delete() {
        var account = selected(); if (account == null) return;
        String typed = JOptionPane.showInputDialog(this,"Delete "+account.username()+" and "+account.knights()+" character(s)?\nType the username to confirm:","Delete account",JOptionPane.WARNING_MESSAGE);
        if (typed == null) return; if (!typed.equals(account.username())) { message("Username did not match. Account was not deleted.",true); return; }
        execute(() -> { accounts.delete(account.username(),account.id()); return loadRows(); },rows -> { loaded(rows); status.setText("Account and its characters deleted. Existing sessions will disconnect."); });
    }
    void deleteKnight() {
        var account=selected();if(account==null)return;
        String name=knightPanel.selectedKnight();if(name==null)return;
            execute(()->accounts.worldProfile(account.username(),name),profile->{
                String typed=JOptionPane.showInputDialog(this,"Delete "+name+" and its items/balances?\nThis account's sessions will disconnect. Type the exact knight name:","Delete knight",JOptionPane.WARNING_MESSAGE);
                if(typed==null)return;if(!typed.equals(name)){message("Knight name did not match. Nothing was deleted.",true);return;}
                execute(()->{accounts.deleteKnight(account.username(),name,account.id(),profile.worldId());return loadRows();},rows->{loaded(rows);status.setText("Knight deleted: "+name);});
            });
    }
    void message(String text,boolean error) { JOptionPane.showMessageDialog(this,text,"Tortodrone",error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE); }
    @FunctionalInterface interface Work<T> { T run() throws Exception; }
    AccountStore accounts() { return accounts; }
    boolean selectedAccount(int id) { var current=selected();return current!=null&&current.id()==id; }
    <T> void execute(Work<T> work,Consumer<T> success) {
        if (busy || closed) return; busy = true; navigation.setEnabled(false);table.setEnabled(false); accountSearch.setEnabled(false); knightPanel.busy(true); auctionPanel.busy(true);depotPanel.busy(true); enableActions(); status.setText("Working…");
        new SwingWorker<T,Void>() {
            @Override protected T doInBackground() throws Exception { return work.run(); }
            @Override protected void done() {
                busy = false; navigation.setEnabled(true);table.setEnabled(true); accountSearch.setEnabled(true); knightPanel.busy(false); auctionPanel.busy(accounts==null);depotPanel.busy(accounts==null);
                try { T value=get(); if (!closed) { status.setText("Ready."); success.accept(value); } }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); status.setText("Operation interrupted."); }
                catch (ExecutionException failure) {
                    String reason = failure.getCause() instanceof IOException ? failure.getCause().getMessage() : "Operation failed.";
                    if (!closed) { status.setText(reason); if (isDisplayable()) message(reason,true); }
                } finally { if (closed && accounts != null) accounts.close(); else enableActions(); }
            }
        }.execute();
    }
}
