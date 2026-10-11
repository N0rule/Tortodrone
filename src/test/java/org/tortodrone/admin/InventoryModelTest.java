package org.tortodrone.admin;

import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.table.TableRowSorter;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

public final class InventoryModelTest {
    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var model=new KnightPanel.InventoryModel();
            var catalogue=new ItemCatalog(List.of(new ItemCatalog.Entry("Weapon/Calibur",-1,"Sword","m.name",0,false,WorldProfile.Slot.SWORD,true,Map.of("Model","verified"))));
            var owned=new WorldProfile.Item(4294967297L,WorldProfile.Slot.SWORD,"Weapon/Calibur",null,0,6,true,true,false,1,false);
            var unknown=new WorldProfile.Item(2,WorldProfile.Slot.OTHER,"Material/Unknown",null,0,null,false,false,false,7,false);
            model.replace(List.of(owned,unknown),catalogue);
            check(model.getValueAt(0,0).equals("Calibur")&&model.getValueAt(0,1).equals("Sword"),"friendly name and category come from catalogue");
            check(model.getValueAt(0,5).equals(4294967297L)&&model.getColumnClass(5)==Long.class,"instance IDs retain full precision");
            check(model.getValueAt(0,4).equals("6")&&model.getValueAt(1,4).equals("Unequipped"),"actual equipped state");
            check(model.getValueAt(1,0).equals("Unknown")&&model.getValueAt(1,1).equals("Unknown"),"uncatalogued instance is visible without invented category");
            check(model.getValueAt(1,2).equals(7)&&model.getValueAt(0,8).equals("Weapon/Calibur"),"quantity and canonical path retained");
            JTable table=new JTable(model);var sorter=new TableRowSorter<>(model);table.setRowSorter(sorter);sorter.setSortKeys(List.of(new RowSorter.SortKey(5,SortOrder.ASCENDING)));
            check(model.item(table.convertRowIndexToModel(0)).id()==2,"sorted selection resolves instance identity");
            check(!model.isCellEditable(0,2),"mutations use validated service dialogs");model.replace(List.of(),catalogue);check(model.getRowCount()==0,"selection clearing removes old instances");
        });
        System.out.println("PASS: inventory display, full-width IDs, sorted identity, actual equipment and immutable rows");
    }
}
