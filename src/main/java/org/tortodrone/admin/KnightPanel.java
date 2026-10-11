package org.tortodrone.admin;

import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;

/** Local character inspector. All storage work is dispatched through the window worker. */
final class KnightPanel extends JPanel {
    private final AdminWindow window;
    private final JComboBox<String> knights=new JComboBox<>();
    private final JLabel owner=new JLabel("Select an account"), balances=new JLabel(" ");
    private final InventoryModel inventory=new InventoryModel();
    private final JTable table=new JTable(inventory);
    private final java.util.List<JButton> actions=new java.util.ArrayList<>();
    private AccountStore.Summary account;
    private WorldProfile profile;
    private String name;
    private boolean loading, busy;

    KnightPanel(AdminWindow window) {
        super(new BorderLayout(8,8));this.window=window;
        JPanel heading=new JPanel(new GridLayout(0,1,4,4));heading.add(new JLabel("Knights and inventory"));heading.add(knights);heading.add(owner);heading.add(balances);
        JTextField search=new JTextField();JPanel filter=new JPanel(new BorderLayout(6,0));filter.add(new JLabel("Find inventory:"),BorderLayout.WEST);filter.add(search);heading.add(filter);add(heading,BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);table.setFillsViewportHeight(true);
        TableRowSorter<InventoryModel> sorter=new TableRowSorter<>(inventory);table.setRowSorter(sorter);
        for(int i=0;i<table.getColumnCount();i++)table.getColumnModel().getColumn(i).setPreferredWidth(i==8?350:i==0?180:100);
        search.getDocument().addDocumentListener(new DocumentListener(){private void update(){sorter.setRowFilter(search.getText().isEmpty()?null:RowFilter.regexFilter("(?i)"+Pattern.quote(search.getText())));}public void insertUpdate(DocumentEvent e){update();}public void removeUpdate(DocumentEvent e){update();}public void changedUpdate(DocumentEvent e){update();}});
        add(new JScrollPane(table));
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT));button(buttons,"Add item",this::addItem);button(buttons,"Set quantity",()->quantity(false));button(buttons,"Remove item",()->quantity(true));button(buttons,"Currency",this::currency);button(buttons,"Refresh knight",this::refresh);add(buttons,BorderLayout.SOUTH);
        knights.addActionListener(e->{if(!loading&&!busy)refresh();});table.getSelectionModel().addListSelectionListener(e->enableActions());enableActions();
    }
    private void button(JPanel parent,String label,Runnable action){JButton button=new JButton(label);button.addActionListener(e->{if(!busy)action.run();});actions.add(button);parent.add(button);}
    void busy(boolean value){busy=value;knights.setEnabled(!value&&account!=null);table.setEnabled(!value);enableActions();}
    private void enableActions(){for(var b:actions)b.setEnabled(!busy&&profile!=null);if(actions.size()>2){actions.get(1).setEnabled(!busy&&selectedItem()!=null);actions.get(2).setEnabled(!busy&&selectedItem()!=null);}}
    void clear(){loading=true;account=null;profile=null;name=null;knights.removeAllItems();inventory.replace(List.of(),null);owner.setText("Select an account");balances.setText(" ");loading=false;enableActions();}
    void account(AccountStore.Summary selected,List<String> names,String requested){
        if(requested==null&&account!=null&&account.id()==selected.id())requested=name;
        clear();account=selected;loading=true;for(String n:names)knights.addItem(n);if(requested!=null)for(String saved:names)if(saved.equalsIgnoreCase(requested)){knights.setSelectedItem(saved);break;}loading=false;
        owner.setText("Account "+selected.username()+" · ID "+selected.id()+(names.isEmpty()?" · No knights":""));refresh();
    }
    private void refresh(){
        if(account==null||knights.getSelectedItem()==null)return;
        var selected=account;String knight=(String)knights.getSelectedItem();profile=null;name=knight;inventory.replace(List.of(),null);owner.setText("Account "+selected.username()+" · "+knight);balances.setText("Balances unavailable until refresh completes");enableActions();
        window.execute(()->window.accounts().worldProfile(selected.username(),knight),result->{
            if(account!=null&&account.id()==selected.id()&&Objects.equals(name,knight)&&window.selectedAccount(selected.id()))show(result);
        });
    }
    private void show(WorldProfile result){if(account==null||account.id()!=result.accountId()||!window.selectedAccount(result.accountId()))return;profile=result;inventory.replace(result.items(),window.accounts().catalog());owner.setText("Account "+account.username()+" · account ID "+result.accountId()+" · roster ID "+result.knightId()+" · world ID "+result.worldId());balances.setText("Crowns: "+result.crowns()+"    Energy: "+result.energy());enableActions();}
    private WorldProfile.Item selectedItem(){int row=table.getSelectedRow();return row<0?null:inventory.item(table.convertRowIndexToModel(row));}
    private JPanel form(Object... fields){JPanel p=new JPanel(new GridLayout(0,2,8,8));for(int i=0;i<fields.length;i+=2){p.add(new JLabel(fields[i].toString()));p.add((Component)fields[i+1]);}return p;}
    private boolean confirm(Component content,String title){return JOptionPane.showConfirmDialog(window,content,title,JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION;}
    private void quantity(boolean remove){
        var item=selectedItem();if(item==null||profile==null)return;var target=profile;var selected=account;String knight=name;
        JTextField count=new JTextField(Integer.toString(item.count()));
        if(!confirm(form("Item",new JLabel(inventory.friendly(item)),"Instance ID",new JLabel(Long.toString(item.id())),remove?"Remove":"New quantity",remove?new JLabel("Delete this item instance"):count),(remove?"Remove item":"Set quantity")+" — "+knight))return;
        int value=0;try{if(!remove)value=Integer.parseInt(count.getText().strip());if(value<0||value>9999)throw new NumberFormatException();}catch(NumberFormatException invalid){window.message("Quantity must be between 0 and 9999; zero removes the instance.",true);return;}
        if(!remove&&value==0&&!confirm(new JLabel("Remove item instance "+item.id()+" from "+knight+"?"),"Confirm removal"))return;
        int quantity=value;window.execute(()->window.accounts().itemQuantity(selected.username(),knight,item.id(),quantity,selected.id(),target.worldId()),this::show);
    }
    void currency(){
        if(profile==null)return;var target=profile;var selected=account;String knight=name;
        JComboBox<Currency> currency=new JComboBox<>(Currency.values());JComboBox<String> operation=new JComboBox<>(new String[]{"Add","Remove","Set"});JTextField amount=new JTextField("1");
        if(!confirm(form("Balances",new JLabel("Crowns: "+target.crowns()+" · Energy: "+target.energy()),"Currency",currency,"Operation",operation,"Amount",amount),"Currency — "+knight))return;
        long value;try{value=Long.parseLong(amount.getText().strip());if(value<0||value>Integer.MAX_VALUE||(operation.getSelectedIndex()!=2&&value==0))throw new NumberFormatException();}catch(NumberFormatException invalid){window.message("Amount must be 1–2147483647; Set also accepts zero.",true);return;}
        Currency kind=(Currency)currency.getSelectedItem();int mode=operation.getSelectedIndex();
        window.execute(()->mode==2?window.accounts().setCurrency(selected.username(),knight,kind,value,selected.id(),target.worldId()):window.accounts().currency(selected.username(),knight,kind,mode==0?value:-value,selected.id(),target.worldId()),this::show);
    }
    private void addItem(){
        if(profile==null)return;var target=profile;var selected=account;String knight=name;var catalog=window.accounts().catalog();
        JDialog dialog=new JDialog(window,"Add item — "+knight,true);dialog.setSize(850,480);dialog.setLocationRelativeTo(window);
        JPanel root=new JPanel(new BorderLayout(8,8));root.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));dialog.setContentPane(root);
        JTextField query=new JTextField();JComboBox<String> category=new JComboBox<>();category.addItem("All categories");catalog.entries().stream().map(ItemCatalog.Entry::category).distinct().sorted().forEach(category::addItem);
        JPanel search=new JPanel(new BorderLayout(8,0));search.add(query);search.add(category,BorderLayout.EAST);root.add(search,BorderLayout.NORTH);
        DefaultListModel<ItemCatalog.Entry> rows=new DefaultListModel<>();JList<ItemCatalog.Entry> list=new JList<>(rows);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean s,boolean f){super.getListCellRendererComponent(l,v,i,s,f);var e=(ItemCatalog.Entry)v;setText(e.displayName()+" ["+e.category()+"] "+catalog.alias(e)+(e.grantable()?"":" — "+e.unavailableReason()));return this;}});root.add(new JScrollPane(list));
        JLabel detail=new JLabel("Select a catalogue item"),paging=new JLabel();JSpinner count=new JSpinner(new SpinnerNumberModel(1,1,20,1));JButton previous=new JButton("Previous"),next=new JButton("Next"),add=new JButton("Grant"),cancel=new JButton("Cancel");add.setEnabled(false);
        JPanel footer=new JPanel(new GridLayout(0,1));footer.add(detail);JPanel controls=new JPanel(new FlowLayout());controls.add(previous);controls.add(paging);controls.add(next);controls.add(new JLabel("Quantity:"));controls.add(count);controls.add(add);controls.add(cancel);footer.add(controls);root.add(footer,BorderLayout.SOUTH);
        int[] page={1};Runnable update=()->{String type=category.getSelectedIndex()==0?null:(String)category.getSelectedItem();var results=catalog.page(query.getText(),type,page[0],25);rows.clear();results.entries().forEach(rows::addElement);paging.setText("Page "+page[0]+" · "+results.total()+" matches");previous.setEnabled(page[0]>1);next.setEnabled((long)page[0]*25<results.total());add.setEnabled(false);};
        query.getDocument().addDocumentListener(new DocumentListener(){private void change(){page[0]=1;update.run();}public void insertUpdate(DocumentEvent e){change();}public void removeUpdate(DocumentEvent e){change();}public void changedUpdate(DocumentEvent e){change();}});category.addActionListener(e->{page[0]=1;update.run();});previous.addActionListener(e->{page[0]--;update.run();});next.addActionListener(e->{page[0]++;update.run();});
        list.addListSelectionListener(e->{var entry=list.getSelectedValue();add.setEnabled(entry!=null&&entry.grantable());detail.setText(entry==null?"Select a catalogue item":entry.config()+" · ID "+entry.stockId()+" · "+entry.unavailableReason());});
        cancel.addActionListener(e->dialog.dispose());add.addActionListener(e->{var entry=list.getSelectedValue();if(entry==null||!entry.grantable())return;try{count.commitEdit();}catch(java.text.ParseException invalid){window.message("Quantity must be between 1 and 20.",true);return;}int quantity=(Integer)count.getValue();dialog.dispose();window.execute(()->window.accounts().grantItems(selected.username(),knight,entry.config(),quantity,selected.id(),target.worldId()),this::show);});update.run();dialog.setVisible(true);
    }
    static final class InventoryModel extends AbstractTableModel {
        private static final String[] COLUMNS={"Name","Category","Quantity","Slot","Equipped","Instance ID","Bound","Permanent","Canonical path"};
        private List<WorldProfile.Item> items=List.of();private ItemCatalog catalog;
        void replace(List<WorldProfile.Item> rows,ItemCatalog catalogue){items=List.copyOf(rows);catalog=catalogue;fireTableDataChanged();}
        WorldProfile.Item item(int row){return items.get(row);}
        String friendly(WorldProfile.Item item){var entry=catalog==null?null:catalog.find(item.config());return entry==null?item.config().substring(item.config().lastIndexOf('/')+1):entry.displayName();}
        public int getRowCount(){return items.size();}public int getColumnCount(){return COLUMNS.length;}public String getColumnName(int c){return COLUMNS[c];}
        public Class<?> getColumnClass(int c){return switch(c){case 2->Integer.class;case 5->Long.class;case 6,7->Boolean.class;default->String.class;};}
        public Object getValueAt(int r,int c){var i=item(r);var entry=catalog==null?null:catalog.find(i.config());return switch(c){case 0->friendly(i);case 1->entry==null?"Unknown":entry.category();case 2->i.count();case 3->i.slot().name();case 4->i.equippedSlot()==null?"Unequipped":i.equippedSlot().toString();case 5->i.id();case 6->i.bound();case 7->i.permanent();case 8->i.config();default->throw new IndexOutOfBoundsException();};}
    }
}
