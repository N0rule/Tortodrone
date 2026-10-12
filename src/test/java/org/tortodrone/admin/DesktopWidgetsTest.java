package org.tortodrone.admin;

import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import static org.tortodrone.support.TestWire.check;

public final class DesktopWidgetsTest {
    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            DefaultTableModel model=new DefaultTableModel(new String[]{"ID","Name"},0);JTable table=new JTable(model);
            TableRowSorter<DefaultTableModel> sorter=new TableRowSorter<>(model);table.setRowSorter(sorter);sorter.setSortKeys(List.of(new RowSorter.SortKey(1,SortOrder.ASCENDING)));
            DesktopWidgets.updateRows(table,model,List.of(new Object[]{1,"Bob"},new Object[]{2,"Alice"}),0);
            table.setRowSelectionInterval(0,0);
            int[] resets={0};model.addTableModelListener(e->{if(e.getLastRow()==Integer.MAX_VALUE)resets[0]++;});
            DesktopWidgets.updateRows(table,model,List.of(new Object[]{1,"Bob updated"},new Object[]{2,"Alice"}),0);
            check(resets[0]==0,"live values update without resetting the table");
            check(model.getValueAt(table.convertRowIndexToModel(table.getSelectedRow()),0).equals(2),"sorted selection retains identity");
            DesktopWidgets.updateRows(table,model,List.of(new Object[]{3,"Aaron"},new Object[]{2,"Alice"}),0);
            check(model.getValueAt(table.convertRowIndexToModel(table.getSelectedRow()),0).equals(2),"membership changes preserve the selected identity");
            sorter.setRowFilter(RowFilter.regexFilter("Aaron",1));
            DesktopWidgets.updateRows(table,model,List.of(new Object[]{3,"Aaron"},new Object[]{2,"Alice"}),0);
            check(table.getSelectedRow()==-1,"a filtered-out identity does not select another row");
            var details=new DesktopWidgets.Details("Name");details.value("Name","<html>Literal value");check(details.value("Name").equals("<html>Literal value"),"read-only values retain literal text");
        });
        System.out.println("PASS: live table updates preserve sorted identity and filtering without resets");
    }
}
