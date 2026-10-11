package org.tortodrone.admin;

import java.util.List;
import java.util.Map;
import java.time.*;
import java.time.format.DateTimeFormatter;
import javax.swing.table.AbstractTableModel;
import org.tortodrone.account.AccountStore;

/** No password or verifier can enter the account-list model. */
public final class AccountTableModel extends AbstractTableModel {
    private List<AccountStore.Summary> accounts = List.of();
    private static final String[] COLUMNS = {"ID","Username","Game Master","Knights","Created","Knight count"};
    private Map<Integer, List<String>> knightNames = Map.of();
    public void replace(List<AccountStore.Summary> accounts) { replace(accounts, Map.of()); }
    public void replace(List<AccountStore.Summary> accounts, Map<Integer, List<String>> knightNames) {
        this.accounts = List.copyOf(accounts);
        this.knightNames = knightNames.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        fireTableDataChanged();
    }
    public AccountStore.Summary account(int row) { return accounts.get(row); }
    @Override public int getRowCount() { return accounts.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }
    @Override public Class<?> getColumnClass(int column) { return column == 2 ? Boolean.class : column == 0 || column == 5 ? Integer.class : String.class; }
    @Override public Object getValueAt(int row,int column) {
        var a = accounts.get(row);
        return switch (column) {
            case 0 -> a.id(); case 1 -> a.username(); case 2 -> a.gameMaster(); case 3 -> String.join(", ", knightNames.getOrDefault(a.id(), List.of()));
            case 4 -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(a.createdAt()).atZone(ZoneId.systemDefault()));
            case 5 -> knightNames.getOrDefault(a.id(), List.of()).size();
            default -> throw new IndexOutOfBoundsException();
        };
    }
}
