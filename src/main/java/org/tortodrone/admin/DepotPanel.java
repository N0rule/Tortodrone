package org.tortodrone.admin;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.storage.DepotStore;
import org.tortodrone.town.TownCatalog;

/** Persistent Supply Depot offers; changes take effect on the next client catalogue request. */
final class DepotPanel extends JPanel {
    private final AdminWindow window;
    private final Model model=new Model();
    private final JTable table=new JTable(model);
    private final JTextField search=new JTextField();
    private final JComboBox<String> state=new JComboBox<>(new String[]{"All offers","Enabled","Disabled","Featured"});
    private final JLabel summary=new JLabel("Open the Supply Depot to load offers.");
    private final List<JButton> buttons=new ArrayList<>();
    private DepotStore.Snapshot snapshot;
    private boolean busy=true;
    DepotPanel(AdminWindow window){
        super(new BorderLayout(8,12));this.window=window;
        var header=new JPanel(new BorderLayout(8,0));DesktopWidgets.searchField(search,"Find an item or offer ID");header.add(search);header.add(state,BorderLayout.EAST);add(header,BorderLayout.NORTH);
        DesktopWidgets.configureTable(table);table.setAutoCreateRowSorter(true);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);int[] widths={70,290,80,100,100,120,85,85,170};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);add(new JScrollPane(table));
        var footer=new JPanel(new BorderLayout(0,8));footer.add(summary,BorderLayout.NORTH);var actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));button(actions,"Refresh",this::refresh);button(actions,"Add offer",this::create);button(actions,"Edit offer",this::edit);button(actions,"Enable / disable",this::toggle);footer.add(actions);footer.add(new JLabel("Edits invalidate open quotes. Purchase limits count offers per knight; zero is unlimited."),BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});state.addActionListener(e->filter());
        table.getSelectionModel().addListSelectionListener(e->enableActions());table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&!busy)edit();}});busy(true);
    }
    private void button(JPanel panel,String label,Runnable action){var b=new JButton(label);buttons.add(b);panel.add(b);b.addActionListener(e->{if(!busy)action.run();});}
    void busy(boolean value){busy=value;table.setEnabled(!value);search.setEnabled(!value);state.setEnabled(!value);enableActions();}
    private void enableActions(){for(int i=0;i<buttons.size();i++)buttons.get(i).setEnabled(!busy&&(i==0||snapshot!=null)&&(i<2||selected()!=null));}
    void refresh(){if(window.accounts()!=null)window.execute(()->window.accounts().depot().snapshot(),this::replace);}
    private void replace(DepotStore.Snapshot value){snapshot=value;model.rows=value.offers();model.items=window.accounts().catalog();model.rules=window.accounts().townRules();model.fireTableDataChanged();summary.setText(value.offers().stream().filter(DepotStore.Offer::enabled).count()+" enabled · "+value.offers().size()+" offers · Revision "+value.revision());filter();enableActions();}
    @SuppressWarnings("unchecked") private void filter(){((TableRowSorter<Model>)table.getRowSorter()).setRowFilter(new RowFilter<>(){public boolean include(Entry<? extends Model,? extends Integer> entry){var o=model.rows.get(entry.getIdentifier());String query=search.getText().strip().toLowerCase(Locale.ROOT);return (o.id()+" "+o.good().item()).toLowerCase(Locale.ROOT).contains(query)&&switch(state.getSelectedIndex()){case 1->o.enabled();case 2->!o.enabled();case 3->o.featured();default->true;};}});}
    private DepotStore.Offer selected(){int row=table.getSelectedRow();if(row<0||row>=table.getRowCount())return null;int index=table.convertRowIndexToModel(row);return index<model.rows.size()?model.rows.get(index):null;}
    private void create(){ItemPicker.choose(window,"Supply Depot item",9999,(item,quantity)->{if(!item.stackable()&&quantity!=1){window.message("Equipment offers contain one item.",true);return;}form(new DepotStore.Offer(-1,new TownCatalog.Good(item.config(),quantity,0,0,0,"",0),false,true));});}
    private void edit(){var offer=selected();if(offer!=null)form(offer);}
    private void toggle(){var offer=selected();if(offer==null)return;var g=offer.good();var item=window.accounts().catalog().find(g.item());if(item==null)return;int price=g.energy()>0?0:g.price()<0?window.accounts().townRules().price(g.item(),item.rarity(),true):g.price();save(new DepotStore.Offer(offer.id(),new TownCatalog.Good(g.item(),g.count(),price,g.energy(),g.limit(),g.token(),g.tokenCount()),offer.featured(),!offer.enabled()));}
    private void save(DepotStore.Offer offer){long revision=snapshot.revision();window.execute(()->window.accounts().depot().save(revision,offer),this::replace);}
    private void form(DepotStore.Offer offer){
        var good=offer.good();var item=window.accounts().catalog().find(good.item());if(item==null){window.message("This offer refers to an unavailable item.",true);return;}
        int resolved=good.price()<0?window.accounts().townRules().price(good.item(),item.rarity(),true):good.price();
        var quantity=number(good.count(),1,item.stackable()?9999:1);var currency=new JComboBox<>(new String[]{"Crowns","Energy"});currency.setSelectedIndex(good.energy()>0?1:0);var price=number(good.energy()>0?good.energy():resolved,0,Integer.MAX_VALUE);var limit=number(good.limit(),0,9999);var featured=new JCheckBox("Show in featured offers",offer.featured());var enabled=new JCheckBox("Available to players",offer.enabled());
        var fields=new JPanel(new GridLayout(0,2,12,10));for(Object value:new Object[]{"Item",new JLabel(item.displayName()),"Quantity per offer",quantity,"Currency",currency,"Price per offer",price,"Purchase limit per knight",limit,"Featured",featured,"Enabled",enabled})fields.add(value instanceof String text?new JLabel(text):(Component)value);
        if(JOptionPane.showConfirmDialog(window,fields,offer.id()<0?"Add Supply Depot offer":"Edit Supply Depot offer #"+offer.id(),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
        try{quantity.commitEdit();price.commitEdit();limit.commitEdit();}catch(java.text.ParseException invalid){window.message("Enter valid whole-number quantities, prices and limits.",true);return;}
        int amount=(Integer)price.getValue();save(new DepotStore.Offer(offer.id(),new TownCatalog.Good(item.config(),(Integer)quantity.getValue(),currency.getSelectedIndex()==0?amount:0,currency.getSelectedIndex()==1?amount:0,(Integer)limit.getValue(),"",0),featured.isSelected(),enabled.isSelected()));
    }
    private static JSpinner number(int value,int min,int max){return new JSpinner(new SpinnerNumberModel(value,min,max,1));}
    private static final class Model extends AbstractTableModel{
        List<DepotStore.Offer> rows=List.of();ItemCatalog items;TownCatalog rules;
        final String[] columns={"Offer ID","Item","Quantity","Crowns","Energy","Purchase limit","Featured","Enabled","Support"};
        public int getRowCount(){return rows.size();}public int getColumnCount(){return columns.length;}public String getColumnName(int c){return columns[c];}public Class<?> getColumnClass(int c){return c==6||c==7?Boolean.class:c==1||c==8?String.class:Integer.class;}
        public Object getValueAt(int row,int column){var o=rows.get(row);var g=o.good();return switch(column){case 0->o.id();case 1->{var item=items.find(g.item());yield item==null?g.item():item.displayName();}case 2->g.count();case 3->{var item=items.find(g.item());yield g.energy()>0?0:g.price()<0&&item!=null?rules.price(g.item(),item.rarity(),true):g.price();}case 4->g.energy();case 5->g.limit();case 6->o.featured();case 7->o.enabled();case 8->{var item=items.find(g.item());yield item!=null&&item.grantable()?"Available":"Unsupported record";}default->throw new IndexOutOfBoundsException();};}
    }
}
