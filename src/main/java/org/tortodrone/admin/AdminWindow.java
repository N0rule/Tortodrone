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
    private volatile AccountStore accounts;
    private final AccountTableModel model = new AccountTableModel();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel("Opening accounts…");
    private final List<JButton> buttons = new ArrayList<>();
    private final List<JButton> selectedButtons = new ArrayList<>();
    private boolean busy;

    AdminWindow(Path directory) {
        super("Tortodrone — Account Manager");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE); setMinimumSize(new Dimension(760,420)); setSize(960,560); setLocationByPlatform(true);
        JPanel content = new JPanel(new BorderLayout(12,12)); content.setBorder(BorderFactory.createEmptyBorder(16,16,16,16)); setContentPane(content);
        JPanel heading = new JPanel(new BorderLayout(8,8));
        JLabel title = new JLabel("Accounts"); title.setFont(title.getFont().deriveFont(Font.BOLD,24f)); heading.add(title,BorderLayout.NORTH);
        heading.add(new JLabel(directory.toAbsolutePath().normalize().resolve("tortodrone.db").toString()),BorderLayout.CENTER);
        JPanel searchPanel = new JPanel(new BorderLayout(8,0)); searchPanel.add(new JLabel("Search username:"),BorderLayout.WEST);
        JTextField search = new JTextField(); searchPanel.add(search); heading.add(searchPanel,BorderLayout.SOUTH); content.add(heading,BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setRowHeight(28); table.setFillsViewportHeight(true);
        var sorter = new TableRowSorter<>(model); table.setRowSorter(sorter); table.getColumnModel().getColumn(0).setMaxWidth(80); table.getColumnModel().getColumn(2).setMaxWidth(80);
        content.add(new JScrollPane(table));
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void update() { sorter.setRowFilter(search.getText().isEmpty() ? null : RowFilter.regexFilter("(?i)"+Pattern.quote(search.getText()),1)); }
            public void insertUpdate(DocumentEvent event) { update(); } public void removeUpdate(DocumentEvent event) { update(); } public void changedUpdate(DocumentEvent event) { update(); }
        });
        table.getSelectionModel().addListSelectionListener(event -> enableActions());
        JPanel footer = new JPanel(new BorderLayout(0,10)); JPanel actions = new JPanel(new GridLayout(2,4,6,6));
        button(actions,"Create account",false,this::create); button(actions,"Change password",true,this::password);
        button(actions,"Check password",true,this::check); button(actions,"Set Admin",true,this::admin);
        button(actions,"Currency",true,this::currency); button(actions,"Delete account",true,this::delete); button(actions,"Refresh",false,this::refresh);
        button(actions,"Delete knight",true,this::deleteKnight);
        footer.add(actions,BorderLayout.NORTH); footer.add(status,BorderLayout.SOUTH); content.add(footer,BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent event) { if (accounts != null) accounts.close(); } });
        execute(() -> { accounts = new AccountStore(directory); return accounts.list(); },this::loaded);
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
    }
    private void loaded(List<AccountStore.Summary> rows) {
        model.replace(rows); status.setText(rows.isEmpty() ? "No accounts. Create an account to enable normal login." : rows.size()+" accounts"); enableActions();
    }
    private void refresh() { execute(() -> accounts.list(),this::loaded); }
    private JPanel form(Object... rows) {
        JPanel form = new JPanel(new GridLayout(0,2,10,10));
        for (int i=0;i<rows.length;i+=2) { form.add(new JLabel((String)rows[i])); form.add((Component)rows[i+1]); } return form;
    }
    private void create() {
        JTextField username = new JTextField(20); JPasswordField password = new JPasswordField(20), confirm = new JPasswordField(20); JCheckBox admin = new JCheckBox("Administrator");
        if (JOptionPane.showConfirmDialog(this,form("Username",username,"Password",password,"Confirm password",confirm,"Admin",admin),"Create account",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        char[] first = password.getPassword(), second = confirm.getPassword(); password.setText(""); confirm.setText("");
        try { AccountStore.validateUsername(username.getText()); Passwords.validate(first); if (!Arrays.equals(first,second)) throw new IOException("Passwords do not match"); }
        catch (IOException failure) { Arrays.fill(first,'\0'); message(failure.getMessage(),true); return; }
        finally { Arrays.fill(second,'\0'); }
        String name = username.getText(); boolean flag = admin.isSelected();
        execute(() -> { try { accounts.create(name,first,flag); return accounts.list(); } finally { Arrays.fill(first,'\0'); } },rows -> { loaded(rows); status.setText("Account created: "+name); });
    }
    private void password() {
        var account = selected(); if (account == null) return;
        JPasswordField password = new JPasswordField(20), confirm = new JPasswordField(20);
        if (JOptionPane.showConfirmDialog(this,form("New password",password,"Confirm password",confirm),"Change password — "+account.username(),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        char[] first = password.getPassword(), second = confirm.getPassword(); password.setText(""); confirm.setText("");
        try { Passwords.validate(first); if (!Arrays.equals(first,second)) throw new IOException("Passwords do not match"); }
        catch (IOException failure) { Arrays.fill(first,'\0'); message(failure.getMessage(),true); return; }
        finally { Arrays.fill(second,'\0'); }
        execute(() -> { try { accounts.setPassword(account.username(),first,account.id()); return accounts.list(); } finally { Arrays.fill(first,'\0'); } },rows -> { loaded(rows); status.setText("Password changed. Existing sessions will disconnect."); });
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
    private void admin() {
        var account = selected(); if (account == null) return;
        JCheckBox flag = new JCheckBox("Administrator",account.admin());
        if (JOptionPane.showConfirmDialog(this,flag,"Admin status — "+account.username(),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        boolean admin = flag.isSelected();
        execute(() -> { accounts.setAdmin(account.username(),admin,account.id()); return accounts.list(); },rows -> { loaded(rows); status.setText("Admin status updated."); });
    }
    private void currency() {
        var account = selected(); if (account == null) return;
        execute(() -> accounts.knights(account.username()),knights -> {
            if (knights.isEmpty()) { message("This account has no characters.",false); return; }
            JComboBox<String> knight = new JComboBox<>(knights.stream().map(Knight::name).toArray(String[]::new));
            if (JOptionPane.showConfirmDialog(this,form("Character",knight),"Character balances — "+account.username(),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String name = (String)knight.getSelectedItem();
            execute(() -> accounts.worldProfile(account.username(),name),profile -> {
                JComboBox<Currency> currency = new JComboBox<>(Currency.values());
                JComboBox<String> operation = new JComboBox<>(new String[]{"Give","Withdraw"});
                JTextField amount = new JTextField("1",12);
                JPanel form = form("Current balances",new JLabel("Crowns: "+profile.crowns()+"  Energy: "+profile.energy()),"Currency",currency,"Operation",operation,"Amount",amount);
                if (JOptionPane.showConfirmDialog(this,form,"Currency — "+name,JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
                long value;
                try { value=Long.parseLong(amount.getText().trim()); if(value<=0 || value>Integer.MAX_VALUE)throw new NumberFormatException(); }
                catch(NumberFormatException invalid){message("Amount must be between 1 and 2147483647.",true);return;}
                long delta = operation.getSelectedIndex()==0 ? value : -value;
                Currency selectedCurrency=(Currency)currency.getSelectedItem();
                execute(() -> accounts.currency(account.username(),name,selectedCurrency,delta,account.id(),profile.worldId()),result -> status.setText(name+" — Crowns: "+result.crowns()+" | Energy: "+result.energy()));
            });
        });
    }

    private void delete() {
        var account = selected(); if (account == null) return;
        String typed = JOptionPane.showInputDialog(this,"Delete "+account.username()+" and "+account.knights()+" character(s)?\nType the username to confirm:","Delete account",JOptionPane.WARNING_MESSAGE);
        if (typed == null) return; if (!typed.equals(account.username())) { message("Username did not match. Account was not deleted.",true); return; }
        execute(() -> { accounts.delete(account.username(),account.id()); return accounts.list(); },rows -> { loaded(rows); status.setText("Account and its characters deleted. Existing sessions will disconnect."); });
    }
    private void deleteKnight() {
        var account=selected();if(account==null)return;
        execute(()->accounts.knights(account.username()),knights->{
            if(knights.isEmpty()){message("This account has no characters.",false);return;}
            var choice=new JComboBox<>(knights.stream().map(Knight::name).toArray(String[]::new));
            if(JOptionPane.showConfirmDialog(this,form("Character",choice),"Delete knight — "+account.username(),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            String name=(String)choice.getSelectedItem();
            execute(()->accounts.worldProfile(account.username(),name),profile->{
                String typed=JOptionPane.showInputDialog(this,"Delete "+name+" and its items/balances?\nThis account's sessions will disconnect. Type the exact knight name:","Delete knight",JOptionPane.WARNING_MESSAGE);
                if(typed==null)return;if(!typed.equals(name)){message("Knight name did not match. Nothing was deleted.",true);return;}
                execute(()->{accounts.deleteKnight(account.username(),name,account.id(),profile.worldId());return accounts.list();},rows->{loaded(rows);status.setText("Knight deleted: "+name);});
            });
        });
    }
    private void message(String text,boolean error) { JOptionPane.showMessageDialog(this,text,"Tortodrone",error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE); }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> void execute(Work<T> work,Consumer<T> success) {
        if (busy) return; busy = true; table.setEnabled(false); enableActions(); status.setText("Working…");
        new SwingWorker<T,Void>() {
            @Override protected T doInBackground() throws Exception { return work.run(); }
            @Override protected void done() {
                busy = false; table.setEnabled(true);
                try { success.accept(get()); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); status.setText("Operation interrupted."); }
                catch (ExecutionException failure) {
                    String reason = failure.getCause() instanceof IOException ? failure.getCause().getMessage() : "Operation failed.";
                    status.setText(reason); if (isDisplayable()) message(reason,true);
                } finally { enableActions(); }
            }
        }.execute();
    }
}
