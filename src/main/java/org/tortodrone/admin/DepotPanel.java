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
        var footer=new JPanel(new BorderLayout(0,8));footer.add(summary,BorderLayout.NORTH);var actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));button(actions,"Refresh",this::refresh);button(actions,"Add offer",this::create);button(actions,"Edit offer",this::edit);button(actions,"Enable / disable",this::toggle);button(actions,"Schedule special",this::schedule);button(actions,"Promotions",this::promotions);button(actions,"Player preview",this::preview);footer.add(actions);footer.add(new JLabel("Edits invalidate quotes. Limits count lifetime purchases per knight and item, shared by offers; zero is unlimited."),BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});state.addActionListener(e->filter());
        table.getSelectionModel().addListSelectionListener(e->enableActions());table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&!busy)edit();}});busy(true);
    }
    private void button(JPanel panel,String label,Runnable action){var b=new JButton(label);buttons.add(b);panel.add(b);b.addActionListener(e->{if(!busy)action.run();});}
    void busy(boolean value){busy=value;table.setEnabled(!value);search.setEnabled(!value);state.setEnabled(!value);enableActions();}
    private void enableActions(){for(int i=0;i<buttons.size();i++)buttons.get(i).setEnabled(!busy&&(i==0||snapshot!=null)&&(i<2||i>4||selected()!=null));}
    void refresh(){if(window.accounts()!=null)window.execute(()->window.accounts().depot().snapshot(),this::replace);}
    private void replace(DepotStore.Snapshot value){snapshot=value;model.rows=value.offers();model.items=window.accounts().catalog();model.rules=window.accounts().townRules();model.fireTableDataChanged();summary.setText(value.offers().stream().filter(DepotStore.Offer::enabled).count()+" enabled · "+value.offers().size()+" offers · Revision "+value.revision());filter();enableActions();}
    @SuppressWarnings("unchecked") private void filter(){((TableRowSorter<Model>)table.getRowSorter()).setRowFilter(new RowFilter<>(){public boolean include(Entry<? extends Model,? extends Integer> entry){var o=model.rows.get(entry.getIdentifier());String query=search.getText().strip().toLowerCase(Locale.ROOT);return (o.id()+" "+o.good().item()+" "+(model.items.find(o.good().item())==null?"":model.items.displayName(model.items.find(o.good().item())))).toLowerCase(Locale.ROOT).contains(query)&&switch(state.getSelectedIndex()){case 1->o.enabled();case 2->!o.enabled();case 3->o.featured();default->true;};}});}
    private DepotStore.Offer selected(){int row=table.getSelectedRow();if(row<0||row>=table.getRowCount())return null;int index=table.convertRowIndexToModel(row);return index<model.rows.size()?model.rows.get(index):null;}
    private void create(){ItemPicker.choose(window,"Supply Depot item",9999,(item,quantity)->{if(!item.stackable()&&quantity!=1){window.message("Equipment offers contain one item.",true);return;}form(new DepotStore.Offer(-1,new TownCatalog.Good(item.config(),quantity,0,0,0,"",0),false,true));});}
    private void edit(){var offer=selected();if(offer!=null)form(offer);}
    private void toggle(){var offer=selected();if(offer==null)return;if(offer.enabled()&&JOptionPane.showConfirmDialog(window,"Disable offer #"+offer.id()+" immediately, including active promotions?","Disable offer",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;var g=offer.good();var item=window.accounts().catalog().find(g.item());if(item==null)return;int price=g.energy()>0?0:g.price()<0?window.accounts().townRules().price(g.item(),item.rarity(),true):g.price();save(new DepotStore.Offer(offer.id(),new TownCatalog.Good(g.item(),g.count(),price,g.energy(),g.limit(),g.token(),g.tokenCount()),offer.featured(),!offer.enabled(),offer.promotionOnly()));}
    private void save(DepotStore.Offer offer){long revision=snapshot.revision();window.execute(()->window.accounts().depot().save(revision,offer),this::replace);}
    private void form(DepotStore.Offer offer){
        var good=offer.good();var item=window.accounts().catalog().find(good.item());if(item==null){window.message("This offer refers to an unavailable item.",true);return;}
        int resolved=good.price()<0?window.accounts().townRules().price(good.item(),item.rarity(),true):good.price();
        var quantity=number(good.count(),1,item.stackable()?9999:1);var currency=new JComboBox<>(new String[]{"Crowns","Energy"});currency.setSelectedIndex(good.energy()>0?1:0);var price=number(good.energy()>0?good.energy():resolved,0,Integer.MAX_VALUE);var limit=number(good.limit(),0,Integer.MAX_VALUE);var featured=new JCheckBox("Show in featured offers",offer.featured());var enabled=new JCheckBox("Available to players",offer.enabled());var promotionOnly=new JCheckBox("Only available during promotions",offer.promotionOnly());
        var fields=new JPanel(new GridLayout(0,2,12,10));for(Object value:new Object[]{"Item",new JLabel(window.accounts().catalog().displayName(item)),"Quantity per offer",quantity,"Currency",currency,"Price per offer",price,"Purchase limit per knight",limit,"Featured",featured,"Enabled",enabled,"Availability",promotionOnly})fields.add(value instanceof String text?new JLabel(text):(Component)value);
        if(JOptionPane.showConfirmDialog(window,fields,offer.id()<0?"Add Supply Depot offer":"Edit Supply Depot offer #"+offer.id(),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
        try{quantity.commitEdit();price.commitEdit();limit.commitEdit();}catch(java.text.ParseException invalid){window.message("Enter valid whole-number quantities, prices and limits.",true);return;}
        int amount=(Integer)price.getValue();save(new DepotStore.Offer(offer.id(),new TownCatalog.Good(item.config(),(Integer)quantity.getValue(),currency.getSelectedIndex()==0?amount:0,currency.getSelectedIndex()==1?amount:0,(Integer)limit.getValue(),"",0),featured.isSelected(),enabled.isSelected(),promotionOnly.isSelected()));
    }
    private void schedule(){
        var offer=selected();if(offer==null)return;
        var currency=new JComboBox<>(new String[]{"Crowns","Energy"});currency.setSelectedIndex(offer.good().energy()>0?1:0);
        var price=number(offer.good().energy()>0?offer.good().energy():offer.good().price(),0,Integer.MAX_VALUE);
        var regular=number(0,0,Integer.MAX_VALUE);var start=new JSpinner(new SpinnerDateModel(new Date(),null,null,Calendar.MINUTE));
        var end=new JSpinner(new SpinnerDateModel(new Date(System.currentTimeMillis()+86400000L),null,null,Calendar.MINUTE));
        for(var spinner:List.of(start,end))spinner.setEditor(new JSpinner.DateEditor(spinner,"yyyy-MM-dd HH:mm:ss"));
        var featured=new JCheckBox("Temporarily show in Specials",true);var form=new JPanel(new GridLayout(0,2,10,8));
        for(Object v:new Object[]{"Offer",new JLabel("#"+offer.id()+" "+offer.good().item()),"Currency",currency,"Effective price",price,"Regular price (0 = no comparison)",regular,"Starts ("+java.time.ZoneId.systemDefault()+")",start,"Ends (exclusive)",end,"Specials",featured})form.add(v instanceof String label?new JLabel(label):(Component)v);
        if(JOptionPane.showConfirmDialog(window,form,"Schedule Supply Depot special",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
        try{price.commitEdit();regular.commitEdit();start.commitEdit();end.commitEdit();}catch(java.text.ParseException invalid){window.message("Enter valid prices and local date/time values.",true);return;}
        int amount=(Integer)price.getValue(),reference=(Integer)regular.getValue();long revision=snapshot.revision();
        var promotion=new DepotStore.Promotion(0,offer.id(),((Date)start.getValue()).getTime(),((Date)end.getValue()).getTime(),currency.getSelectedIndex()==0?amount:0,currency.getSelectedIndex()==1?amount:0,reference==0?null:reference,featured.isSelected(),false);
        window.execute(()->window.accounts().depot().schedule(revision,promotion),this::replace);
    }
    private static String date(long value){return java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString();}
    private void promotions(){
        window.execute(()->window.accounts().depot().promotions(),rows->{
            var data=new Object[rows.size()][9];long now=System.currentTimeMillis();for(int i=0;i<rows.size();i++){var p=rows.get(i);data[i]=new Object[]{p.id(),p.offerId(),p.status(now),date(p.starts()),date(p.ends()),p.crowns(),p.energy(),p.referencePrice(),p.featured()};}
            var model=new DefaultTableModel(data,new String[]{"Promotion","Offer","Status","Starts (local)","Ends (local)","Crowns","Energy","Regular price","Specials"}){public boolean isCellEditable(int r,int c){return false;}};
            var view=new JTable(model);DesktopWidgets.configureTable(view);view.setAutoCreateRowSorter(true);
            var states=new JComboBox<>(new String[]{"All","Active","Scheduled","Expired","Cancelled"});states.addActionListener(e->{@SuppressWarnings("unchecked") var sorter=(TableRowSorter<DefaultTableModel>)view.getRowSorter();sorter.setRowFilter(states.getSelectedIndex()==0?null:RowFilter.regexFilter("^"+states.getSelectedItem()+"$",2));});
            var panel=new JPanel(new BorderLayout(8,8));panel.add(states,BorderLayout.NORTH);panel.add(new JScrollPane(view));panel.setPreferredSize(new Dimension(950,380));
            if(JOptionPane.showOptionDialog(window,panel,"Depot promotions — "+java.time.ZoneId.systemDefault(),JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,new String[]{"Close","Cancel selected promotion"},"Close")!=1)return;
            int selected=view.getSelectedRow();if(selected<0)return;var promotion=rows.get(view.convertRowIndexToModel(selected));
            if(JOptionPane.showConfirmDialog(window,"Cancel promotion #"+promotion.id()+" immediately?","Cancel promotion",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            long revision=snapshot.revision();window.execute(()->window.accounts().depot().cancelPromotion(revision,promotion.id()),this::replace);
        });
    }
    private void preview(){window.execute(()->window.accounts().depot().catalog(),value->{
        var text=new StringBuilder("Effective catalog at "+date(System.currentTimeMillis())+". Stock client refreshes on its next catalog request.\nAutomatic sales use captured dates when available; other selections and winter noon Pacific timing are reconstructed.\nManual promotions override automatic sales. Disabling or repricing an offer suppresses its automatic sale.\n");
        for(var o:value.offers())if(o.enabled())text.append("#").append(o.id()).append(" ").append(o.good().item()).append(" × ").append(o.good().count()).append(" — ").append(o.good().energy()>0?o.good().energy()+" Energy":o.good().price()+" crowns").append(o.sale()?" (regular "+o.originalPrice()+")":"").append(o.featured()||o.sale()?" [Specials]":"").append(org.tortodrone.storage.DepotSpecials.automatic(o)?" [automatic; ends "+date(o.promotionEnds())+"]":"").append('\n');
        var area=new JTextArea(text.toString(),24,90);area.setEditable(false);JOptionPane.showMessageDialog(window,new JScrollPane(area),"What players can request now",JOptionPane.INFORMATION_MESSAGE);
    });}
    private static JSpinner number(int value,int min,int max){return new JSpinner(new SpinnerNumberModel(value,min,max,1));}
    private static final class Model extends AbstractTableModel{
        List<DepotStore.Offer> rows=List.of();ItemCatalog items;TownCatalog rules;
        final String[] columns={"Offer ID","Item","Quantity","Crowns","Energy","Purchase limit","Featured","Enabled","Support"};
        public int getRowCount(){return rows.size();}public int getColumnCount(){return columns.length;}public String getColumnName(int c){return columns[c];}public Class<?> getColumnClass(int c){return c==6||c==7?Boolean.class:c==1||c==8?String.class:Integer.class;}
        public Object getValueAt(int row,int column){var o=rows.get(row);var g=o.good();return switch(column){case 0->o.id();case 1->{var item=items.find(g.item());yield item==null?g.item():items.displayName(item);}case 2->g.count();case 3->{var item=items.find(g.item());yield g.energy()>0?0:g.price()<0&&item!=null?rules.price(g.item(),item.rarity(),true):g.price();}case 4->g.energy();case 5->g.limit();case 6->o.featured();case 7->o.enabled();case 8->{var item=items.find(g.item());yield item!=null?(item.grantable()?(o.promotionOnly()?"Promotion only":"Available"):item.unavailableReason()):"Missing item";}default->throw new IndexOutOfBoundsException();};}
    }
}
