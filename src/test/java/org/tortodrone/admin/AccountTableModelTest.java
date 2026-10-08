package org.tortodrone.admin;

import java.util.List;
import javax.swing.*;
import javax.swing.table.TableRowSorter;
import org.tortodrone.account.AccountStore;
import static org.tortodrone.support.TestWire.check;

public final class AccountTableModelTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = new AccountTableModel(); var table = new JTable(model); var sorter = new TableRowSorter<>(model); table.setRowSorter(sorter);
            model.replace(List.of(new AccountStore.Summary(2,"Player",false,1,1),new AccountStore.Summary(1,"Owner",true,0,1)));
            check(model.getValueAt(0,2).equals(false) && model.getColumnClass(2) == Boolean.class && !model.isCellEditable(0,2),"Game Master uses a noneditable checkbox renderer");
            sorter.setSortKeys(List.of(new RowSorter.SortKey(0,SortOrder.ASCENDING)));
            check(model.account(table.convertRowIndexToModel(0)).username().equals("Owner"),"selected sorted row maps to correct account");
            sorter.setRowFilter(RowFilter.regexFilter("(?i)player",1)); check(table.getRowCount() == 1,"username search");
            model.replace(List.of()); check(table.getRowCount() == 0,"refresh after deletion removes old rows");
            check(model.getColumnCount() == 5,"account list has no password/hash fields");
        });
        System.out.println("PASS: desktop account table, boolean Game Master renderer, search, sorted selection and refresh");
    }
}
