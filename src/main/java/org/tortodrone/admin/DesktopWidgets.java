package org.tortodrone.admin;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Small presentation helpers shared by the two local desktops. */
public final class DesktopWidgets {
    private DesktopWidgets() { }

    public static final class Details extends JPanel {
        private final Map<String,JLabel> values=new LinkedHashMap<>();
        public Details(String... names) { this(1,names); }
        public Details(int columns,String... names) {
            super(new GridBagLayout());
            int index=0;
            for(String name:names) {
                GridBagConstraints c=new GridBagConstraints();c.gridy=index/columns;c.gridx=(index++%columns)*2;c.anchor=GridBagConstraints.NORTHWEST;c.insets=new Insets(3,0,3,12);
                add(new JLabel(name),c);c.gridx++;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;
                JLabel value=new JLabel("—");value.putClientProperty("html.disable",true);value.setMinimumSize(new Dimension(0,value.getPreferredSize().height));values.put(name,value);add(value,c);
            }
        }
        public void value(String name,Object text) {
            JLabel label=values.get(name);String value=text==null?"—":text.toString();
            if(!label.getText().equals(value))label.setText(value);
            label.setToolTipText(value);
        }
        public String value(String name) { return values.get(name).getText(); }
    }

    public static final class Page extends JPanel implements Scrollable {
        public Page(LayoutManager layout) { super(layout); }
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 24;}
        public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(24,r.height-24);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }

    /** Update values in place; preserve selection by identity even when rows enter or leave. */
    public static void updateRows(JTable table,DefaultTableModel model,List<Object[]> rows,int... keys) {
        Object[] selected=null;
        if(table.getSelectedRow()>=0)selected=key(model,table.convertRowIndexToModel(table.getSelectedRow()),keys);
        boolean same=model.getRowCount()==rows.size();
        if(same)for(int r=0;r<rows.size();r++)for(int k:keys)if(!Objects.equals(model.getValueAt(r,k),rows.get(r)[k]))same=false;
        table.getSelectionModel().setValueIsAdjusting(true);
        try {
            if(!same){model.setRowCount(0);for(Object[] row:rows)model.addRow(row);}
            else for(int r=0;r<rows.size();r++)for(int c=0;c<model.getColumnCount();c++)
                if(!Objects.equals(model.getValueAt(r,c),rows.get(r)[c]))model.setValueAt(rows.get(r)[c],r,c);
            if(selected!=null){int view=-1;for(int r=0;r<model.getRowCount();r++)if(Arrays.equals(selected,key(model,r,keys))){view=table.convertRowIndexToView(r);break;}if(view<0)table.clearSelection();else if(table.getSelectedRow()!=view)table.setRowSelectionInterval(view,view);}
        }finally{table.getSelectionModel().setValueIsAdjusting(false);}
    }
    private static Object[] key(DefaultTableModel model,int row,int[] columns){Object[] key=new Object[columns.length];for(int i=0;i<columns.length;i++)key[i]=model.getValueAt(row,columns[i]);return key;}

    public static void configureTable(JTable table) {
        table.setFillsViewportHeight(true);table.setRowHeight(30);table.setShowGrid(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class,new javax.swing.table.DefaultTableCellRenderer(){
            public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(t,value,selected,focus,row,column);putClientProperty("html.disable",true);setToolTipText(value==null?null:value.toString());return this;}
        });
    }
    public static void searchField(JTextField field,String hint) {
        field.putClientProperty("JTextField.placeholderText",hint);field.putClientProperty("JTextField.showClearButton",true);
        field.getAccessibleContext().setAccessibleName(hint);
    }
}
