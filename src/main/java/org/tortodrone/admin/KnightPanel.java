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
    private final DesktopWidgets.Details itemDetail=new DesktopWidgets.Details("Selected item","Instance / flags","Canonical path");
    private final JLabel itemHint=new JLabel("Select an item to see details and available actions.");
    private final JTextField search=new JTextField();
    private final java.util.List<JButton> actions=new java.util.ArrayList<>();
    private AccountStore.Summary account;
    private WorldProfile profile;
    private String name;
    private boolean loading, busy;

    KnightPanel(AdminWindow window) {
        super(new BorderLayout(8,8));this.window=window;
        JPanel heading=new JPanel(new GridLayout(0,1,6,6));heading.add(new JLabel("Knights and inventory"));JPanel knightPicker=new JPanel(new BorderLayout(6,0));knightPicker.add(knights);JPanel knightActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));button(knightActions,"Refresh knight",this::refresh);button(knightActions,"Rank / prestige",this::progress);button(knightActions,"Delete knight",window::deleteKnight);knightPicker.add(knightActions,BorderLayout.EAST);heading.add(knightPicker);heading.add(owner);heading.add(balances);
        DesktopWidgets.searchField(search,"Find an inventory item");heading.add(search);add(heading,BorderLayout.NORTH);
        DesktopWidgets.configureTable(table);
        TableRowSorter<InventoryModel> sorter=new TableRowSorter<>(inventory);table.setRowSorter(sorter);
        for(int i=8;i>=5;i--)table.removeColumn(table.getColumnModel().getColumn(i));
        table.removeColumn(table.getColumnModel().getColumn(3));table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);int[] widths={200,100,70,90,55,60,210};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer(){public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(t,value,selected,focus,row,column);setText("Unequipped".equals(value)?"Unequipped":"Equipped");setToolTipText("Unequipped".equals(value)?null:"Equipment slot "+value);return this;}});
        search.getDocument().addDocumentListener(new DocumentListener(){private void update(){sorter.setRowFilter(search.getText().isEmpty()?null:RowFilter.regexFilter("(?i)"+Pattern.quote(search.getText())));}public void insertUpdate(DocumentEvent e){update();}public void removeUpdate(DocumentEvent e){update();}public void changedUpdate(DocumentEvent e){update();}});
        add(new JScrollPane(table));
        JPanel footer=new JPanel(new BorderLayout(0,8));footer.add(itemDetail,BorderLayout.NORTH);footer.add(itemHint);JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));button(buttons,"Add item",this::addItem);button(buttons,"Level / heat / UVs",this::modifiers);button(buttons,"Set quantity",()->quantity(false));button(buttons,"Remove item",()->quantity(true));button(buttons,"Currency",this::currency);footer.add(buttons,BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
        knights.addActionListener(e->{if(!loading&&!busy)refresh();});table.getSelectionModel().addListSelectionListener(e->enableActions());enableActions();
    }
    private void button(JPanel parent,String label,Runnable action){JButton button=new JButton(label);button.addActionListener(e->{if(!busy)action.run();});actions.add(button);parent.add(button);}
    void busy(boolean value){busy=value;knights.setEnabled(!value&&knights.getItemCount()>0);table.setEnabled(!value);search.setEnabled(!value);enableActions();}
    private void enableActions(){
        var item=selectedItem();boolean editable=item!=null&&!item.starter()&&!item.permanent()&&item.equippedSlot()==null;
        for(var b:actions){boolean itemAction=b.getText().equals("Set quantity")||b.getText().equals("Remove item");b.setEnabled(!busy&&profile!=null&&(!itemAction||editable)&&(!b.getText().equals("Level / heat / UVs")||item!=null));if(itemAction)b.setToolTipText(editable?null:"Select an ordinary unequipped item; starter, permanent and equipped items are protected.");}
        itemDetail.value("Selected item",item==null?"No item selected":inventory.friendly(item));itemDetail.value("Instance / flags",item==null?null:item.id()+" · "+(item.bound()?"Bound":"Unbound")+(item.starter()?" · Starter":"")+(item.permanent()?" · Permanent":""));itemDetail.value("Canonical path",item==null?null:item.config());
        itemHint.setText(item==null?"Select an item to see details and available actions.":"Level "+(item.level()+1)+" · Heat "+Math.round(item.heatProgress()*100)+"% · UVs: "+(item.variants().isEmpty()?"None":String.join(", ",item.variants().stream().map(v->v.name().substring(v.name().lastIndexOf('/')+1)).toList())));
    }
    String selectedKnight(){return profile==null?null:name;}
    void clear(){loading=true;account=null;profile=null;name=null;knights.removeAllItems();knights.setEnabled(false);search.setText("");inventory.replace(List.of(),null);owner.setText("Select an account");balances.setText(" ");loading=false;enableActions();}
    void account(AccountStore.Summary selected,List<String> names,String requested){
        if(requested==null&&account!=null&&account.id()==selected.id())requested=name;
        if(names.isEmpty()){clear();account=selected;owner.setText(selected.username()+" has no knights.");return;}
        if(account==null||account.id()!=selected.id())clear();account=selected;loading=true;knights.removeAllItems();for(String n:names)knights.addItem(n);if(requested!=null)for(String saved:names)if(saved.equalsIgnoreCase(requested)){knights.setSelectedItem(saved);break;}loading=false;knights.setEnabled(!busy&&!names.isEmpty());
        owner.setText("Account "+selected.username()+" · ID "+selected.id()+(names.isEmpty()?" · No knights":""));refresh();
    }
    private void refresh(){
        if(account==null||knights.getSelectedItem()==null)return;
        var selected=account;String knight=(String)knights.getSelectedItem();if(!Objects.equals(name,knight)){profile=null;inventory.replace(List.of(),null);owner.setText("Loading "+knight+"…");balances.setText(" ");}name=knight;enableActions();
        window.execute(()->window.accounts().worldProfile(selected.username(),knight),result->{
            if(account!=null&&account.id()==selected.id()&&Objects.equals(name,knight)&&window.selectedAccount(selected.id()))show(result);
        });
    }
    private void show(WorldProfile result){if(account==null||account.id()!=result.accountId()||!window.selectedAccount(result.accountId()))return;
        var selected=selectedItem();Long id=selected==null?null:selected.id();profile=result;inventory.replace(result.items(),window.accounts().catalog());
        if(id!=null){table.clearSelection();for(int r=0;r<inventory.getRowCount();r++)if(inventory.item(r).id()==id){int view=table.convertRowIndexToView(r);if(view>=0)table.setRowSelectionInterval(view,view);break;}}
        owner.setText(name+" · "+account.username());owner.setToolTipText("Account ID "+result.accountId()+" · roster ID "+result.knightId()+" · world ID "+result.worldId());balances.setText("Crowns: "+result.crowns()+"    Energy: "+result.energy()+"    Rank: "+result.progress().rankPair()+"    Prestige: "+result.progress().prestige());enableActions();}
    private WorldProfile.Item selectedItem(){int row=table.getSelectedRow();return row<0?null:inventory.item(table.convertRowIndexToModel(row));}
    private JPanel form(Object... fields){JPanel p=new JPanel(new GridLayout(0,2,8,8));for(int i=0;i<fields.length;i+=2){p.add(new JLabel(fields[i].toString()));p.add((Component)fields[i+1]);}return p;}
    private boolean confirm(Component content,String title){return JOptionPane.showConfirmDialog(window,content,title,JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION;}
    private void quantity(boolean remove){
        var item=selectedItem();if(item==null||profile==null)return;var target=profile;var selected=account;String knight=name;
        var definition=window.accounts().catalog().find(item.config());int maximum=definition!=null&&definition.stackable()?9999:1;
        JSpinner count=new JSpinner(new SpinnerNumberModel(Math.min(item.count(),maximum),1,maximum,1));
        if(!confirm(form("Item",new JLabel(inventory.friendly(item)),"Instance ID",new JLabel(Long.toString(item.id())),remove?"Remove":"New quantity",remove?new JLabel("Delete this item instance"):count),(remove?"Remove item":"Set quantity")+" — "+knight))return;
        int value=0;try{if(!remove){count.commitEdit();value=(Integer)count.getValue();}}catch(java.text.ParseException invalid){window.message("Enter a valid quantity between 1 and "+maximum+".",true);return;}
        int quantity=value;window.execute(()->window.accounts().itemQuantity(selected.username(),knight,item.id(),quantity,selected.id(),target.worldId()),this::show);
    }
    private void progress(){
        if(profile==null)return;var target=profile;var selected=account;String knight=name;
        JSpinner rank=new JSpinner(new SpinnerNumberModel(target.progress().rank(),1,10,1));
        JSpinner subrank=new JSpinner(new SpinnerNumberModel(target.progress().subrank(),1,4,1));
        JSpinner prestige=new JSpinner(new SpinnerNumberModel(target.progress().prestige(),0,Integer.MAX_VALUE,1000));
        boolean hasSprite=!target.town().sprites().isEmpty();
        JCheckBox grantSprite=new JCheckBox("Grant first battle sprite (testing)");grantSprite.setEnabled(!hasSprite);
        JComboBox<String> sprite=new JComboBox<>(new String[]{"Drakon","Seraphynx","Maskeraith"});sprite.setEnabled(false);
        grantSprite.addActionListener(e->sprite.setEnabled(grantSprite.isSelected()));
        grantSprite.setToolTipText(hasSprite?"This knight already owns a sprite; pod hatching is unlocked.":"Unlocks the An Eternal Bond pod gate by granting and equipping one level 1 starter sprite.");
        JButton vanguard=new JButton("Vanguard preset");vanguard.addActionListener(e->{rank.setValue(10);subrank.setValue(3);prestige.setValue(45000);});
        if(!confirm(form("Rank",rank,"Subrank",subrank,"Prestige total",prestige,"Testing preset",vanguard,"An Eternal Bond",grantSprite,"Starter sprite",sprite),"Rank / prestige — "+knight))return;
        try{rank.commitEdit();subrank.commitEdit();prestige.commitEdit();}catch(java.text.ParseException invalid){window.message("Enter rank 1–10, subrank 1–4 and a nonnegative prestige total.",true);return;}
        int nextRank=((Number)rank.getValue()).intValue(),nextSubrank=((Number)subrank.getValue()).intValue(),nextPrestige=((Number)prestige.getValue()).intValue();
        String starterSprite=grantSprite.isSelected()?(String)sprite.getSelectedItem():null;
        window.execute(()->window.accounts().progress(selected.username(),knight,nextRank,nextSubrank,nextPrestige,selected.id(),target.worldId(),starterSprite),this::show);
    }
    void currency(){
        if(profile==null)return;var target=profile;var selected=account;String knight=name;
        JComboBox<Currency> currency=new JComboBox<>(Currency.values());JComboBox<String> operation=new JComboBox<>(new String[]{"Add","Remove","Set"});JSpinner amount=new JSpinner(new SpinnerNumberModel(Long.valueOf(1),Long.valueOf(1),Long.valueOf(Integer.MAX_VALUE),Long.valueOf(1)));
        operation.addActionListener(e->{var model=(SpinnerNumberModel)amount.getModel();long minimum=operation.getSelectedIndex()==2?0:1;model.setMinimum(minimum);if(((Number)model.getValue()).longValue()<minimum)model.setValue(minimum);});
        if(!confirm(form("Balances",new JLabel("Crowns: "+target.crowns()+" · Energy: "+target.energy()),"Currency",currency,"Operation",operation,"Amount",amount),"Currency — "+knight))return;
        long value;try{amount.commitEdit();value=((Number)amount.getValue()).longValue();if(value<0||value>Integer.MAX_VALUE||(operation.getSelectedIndex()!=2&&value==0))throw new java.text.ParseException("Invalid amount",0);}catch(java.text.ParseException invalid){window.message("Amount must be 1–2147483647; Set also accepts zero.",true);return;}
        Currency kind=(Currency)currency.getSelectedItem();int mode=operation.getSelectedIndex();
        window.execute(()->mode==2?window.accounts().setCurrency(selected.username(),knight,kind,value,selected.id(),target.worldId()):window.accounts().currency(selected.username(),knight,kind,mode==0?value:-value,selected.id(),target.worldId()),this::show);
    }
    private void addItem(){
        if(profile==null)return;var target=profile;var selected=account;String knight=name;
        ItemPicker.choose(window,"Add item — "+knight,20,(entry,quantity)->ItemEditor.choose(window,"Grant "+entry.displayName()+" × "+quantity+" — "+knight,entry,ItemModifiers.NONE,modifiers->window.execute(()->window.accounts().grantItems(selected.username(),knight,entry.config(),quantity,selected.id(),target.worldId(),modifiers),this::show)));
    }
    private void modifiers(){
        var item=selectedItem();if(item==null||profile==null)return;var target=profile;var selected=account;String knight=name;
        var entry=window.accounts().catalog().find(item.config());if(entry==null)return;
        ItemEditor.choose(window,"Edit gear — "+inventory.friendly(item),entry,new ItemModifiers(item.level(),item.heatProgress(),item.variants().stream().map(StarterCatalog.Model::name).toList()),value->window.execute(()->window.accounts().itemModifiers(selected.username(),knight,item.id(),value,selected.id(),target.worldId(),target.revision()),this::show));
    }
    static final class InventoryModel extends AbstractTableModel {
        private static final String[] COLUMNS={"Name","Category","Quantity","Slot","Equipped","Instance ID","Bound","Permanent","Canonical path","Level","Heat","Unique Variants"};
        private List<WorldProfile.Item> items=List.of();private ItemCatalog catalog;
        void replace(List<WorldProfile.Item> rows,ItemCatalog catalogue){
            if(items.equals(rows)&&catalog==catalogue)return;
            boolean same=items.size()==rows.size();if(same)for(int i=0;i<items.size();i++)if(items.get(i).id()!=rows.get(i).id())same=false;
            items=List.copyOf(rows);catalog=catalogue;if(same&&!items.isEmpty())fireTableRowsUpdated(0,items.size()-1);else fireTableDataChanged();
        }
        WorldProfile.Item item(int row){return items.get(row);}
        String friendly(WorldProfile.Item item){var entry=catalog==null?null:catalog.find(item.config());return entry==null?item.config().substring(item.config().lastIndexOf('/')+1):entry.displayName();}
        public int getRowCount(){return items.size();}public int getColumnCount(){return COLUMNS.length;}public String getColumnName(int c){return COLUMNS[c];}
        public Class<?> getColumnClass(int c){return switch(c){case 2,9->Integer.class;case 5->Long.class;case 6,7->Boolean.class;default->String.class;};}
        public Object getValueAt(int r,int c){var i=item(r);var entry=catalog==null?null:catalog.find(i.config());return switch(c){case 0->friendly(i);case 1->entry==null?"Unknown":entry.category();case 2->i.count();case 3->i.slot().name();case 4->i.equippedSlot()==null?"Unequipped":i.equippedSlot().toString();case 5->i.id();case 6->i.bound();case 7->i.permanent();case 8->i.config();case 9->i.level()+1;case 10->Math.round(i.heatProgress()*100)+"%";case 11->String.join(", ",i.variants().stream().map(v->v.name().substring(v.name().lastIndexOf('/')+1)).toList());default->throw new IndexOutOfBoundsException();};}
    }
}
