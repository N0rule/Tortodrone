package org.tortodrone.admin;

import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import org.tortodrone.storage.AuctionHouse;
import org.tortodrone.town.AuctionState;

/** Local trusted auction administration over the same escrow ledger as the stock client. */
final class AuctionPanel extends JPanel {
    private final AdminWindow window;
    private final Model model=new Model();
    private final JTable table=new JTable(model);
    private final List<JButton> actions=new ArrayList<>();
    private final JTextField search=new JTextField();
    private final JComboBox<String> state=new JComboBox<>(new String[]{"All statuses","Active","Ended","Cancelled"});
    private final JComboBox<String> ownership=new JComboBox<>(new String[]{"All sellers","Player-owned","Featured / system"});
    private boolean busy;
    AuctionPanel(AdminWindow window){
        super(new BorderLayout(8,8));this.window=window;
        JPanel filters=new JPanel(new BorderLayout(8,0));DesktopWidgets.searchField(search,"Find item, seller or auction ID");filters.add(search);
        JPanel choices=new JPanel(new FlowLayout(FlowLayout.RIGHT));choices.add(state);choices.add(ownership);filters.add(choices,BorderLayout.EAST);add(filters,BorderLayout.NORTH);
        DesktopWidgets.configureTable(table);table.setAutoCreateRowSorter(true);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);int[] widths={70,220,80,150,100,100,150,170,170,100,80,90};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);add(new JScrollPane(table));
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));button(controls,"Refresh",this::refresh);button(controls,"Create featured",this::create);button(controls,"Inspect / deliveries",this::inspect);button(controls,"Cancel listing",this::cancel);button(controls,"Settle expired",()->window.execute(()->{window.accounts().auctions().settleExpired();return window.accounts().auctions().overview();},this::replace));add(controls,BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});state.addActionListener(e->filter());ownership.addActionListener(e->filter());table.getSelectionModel().addListSelectionListener(e->enableActions());busy(true);
    }
    private void button(JPanel panel,String label,Runnable run){var b=new JButton(label);actions.add(b);panel.add(b);b.addActionListener(e->{if(!busy&&window.accounts()!=null)run.run();});}
    void busy(boolean value){busy=value;table.setEnabled(!value);search.setEnabled(!value);state.setEnabled(!value);ownership.setEnabled(!value);enableActions();}
    private void enableActions(){var row=selected();for(int i=0;i<actions.size();i++)actions.get(i).setEnabled(!busy&&(i!=2&&i!=3||row!=null)&&(i!=3||row!=null&&!row.listing().ended()));}
    void refresh(){if(window.accounts()!=null)window.execute(()->window.accounts().auctions().overview(),this::replace);}
    private void replace(List<AuctionHouse.Overview> rows){model.rows=List.copyOf(rows);model.fireTableDataChanged();filter();enableActions();}
    @SuppressWarnings("unchecked") private void filter(){
        ((TableRowSorter<Model>)table.getRowSorter()).setRowFilter(new RowFilter<>(){
            public boolean include(Entry<? extends Model,? extends Integer> entry){
                var r=model.rows.get(entry.getIdentifier());var a=r.listing();String status=status(a);String query=search.getText().strip().toLowerCase(Locale.ROOT);
                return (state.getSelectedIndex()==0||state.getSelectedItem().equals(status))&&(ownership.getSelectedIndex()==0||(ownership.getSelectedIndex()==2)==a.featured())&&(a.id()+" "+a.item().config()+" "+r.seller()).toLowerCase(Locale.ROOT).contains(query);
            }});
    }
    private AuctionHouse.Overview selected(){int row=table.getSelectedRow();if(row<0||row>=table.getRowCount())return null;int index=table.convertRowIndexToModel(row);return index<model.rows.size()?model.rows.get(index):null;}
    private void create(){
        ItemPicker.choose(window,"Featured auction item",9999,(item,quantity)->{
            if(!item.stackable()&&quantity!=1){window.message("Equipment listings contain one item per offer.",true);return;}
            var type=new JComboBox<>(new String[]{"Bidding / optional buyout","Fixed price stock"});var bid=number(100,1,Integer.MAX_VALUE);var buy=number(0,0,Integer.MAX_VALUE);var stock=number(2,2,32767);stock.setEnabled(false);
            var duration=new JComboBox<>(new String[]{"4 hours","12 hours","24 hours","48 hours","72 hours","96 hours","120 hours","144 hours","168 hours"});
            type.addActionListener(e->{boolean fixed=type.getSelectedIndex()==1;stock.setEnabled(fixed);bid.setEnabled(!fixed);});
            JPanel form=new JPanel(new GridLayout(0,2,8,8));for(Object v:new Object[]{"Item",new JLabel(item.displayName()+" × "+quantity),"Listing type",type,"Starting bid",bid,"Buyout / price per offer",buy,"Number of offers",stock,"Duration",duration})form.add(v instanceof String label?new JLabel(label):(Component)v);
            if(JOptionPane.showConfirmDialog(window,form,"Create system-owned featured auction",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
            try{bid.commitEdit();buy.commitEdit();stock.commitEdit();}catch(java.text.ParseException invalid){window.message("Enter valid whole-number prices and stock.",true);return;}
            boolean fixed=type.getSelectedIndex()==1;int price=(Integer)buy.getValue(),start=fixed?price:(Integer)bid.getValue(),offers=fixed?(Integer)stock.getValue():1,d=duration.getSelectedIndex();
            window.execute(()->{window.accounts().auctions().createFeatured(item.config(),quantity,start,price,d,offers);return window.accounts().auctions().overview();},this::replace);
        });
    }
    private static JSpinner number(int initial,int min,int max){return new JSpinner(new SpinnerNumberModel(initial,min,max,1));}
    private void cancel(){var row=selected();if(row==null||row.listing().ended())return;
        if(JOptionPane.showConfirmDialog(window,"Cancel auction #"+row.listing().id()+"? Escrow and bids will be returned through mail.","Cancel auction",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        window.execute(()->{window.accounts().auctions().cancel(row.listing().id());return window.accounts().auctions().overview();},this::replace);
    }
    private void inspect(){var row=selected();if(row==null)return;var a=row.listing();
        window.execute(()->window.accounts().auctions().deliveries(a.id()),deliveries->{
            StringBuilder text=new StringBuilder("Auction #"+a.id()+"\n"+a.item().config()+" × "+a.item().count()+"\nSeller: "+row.seller()+"; highest bidder: "+row.bidder()+"\nStatus: "+status(a)+"; bid: "+a.bid()+"; buyout: "+a.buy()+"\nCreated: "+date(a.created())+"; expires: "+date(a.expires())+"\nFeatured stock: "+a.featuredRemaining()+" / "+a.featuredTotal()+"\nEscrow item instance: "+a.item().id()+"\n\nDeliveries (older schema-9 mail has no auction association):\n");
            for(var d:deliveries)text.append("Mail #").append(d.id()).append(" → world #").append(d.recipient()).append(": ").append(d.crowns()).append(" crowns, item=").append(d.item()).append(", claimed=").append(d.claimed()).append('\n');
            var area=new JTextArea(text.toString(),18,75);area.setEditable(false);JOptionPane.showMessageDialog(window,new JScrollPane(area),"Auction details",JOptionPane.INFORMATION_MESSAGE);
        });
    }
    private static String date(long value){return value==0?"Unknown":Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString();}
    private static String status(AuctionState.Listing a){return a.cancelled()?"Cancelled":a.ended()||a.expires()<=System.currentTimeMillis()?"Ended":"Active";}
    private static final class Model extends AbstractTableModel {
        List<AuctionHouse.Overview> rows=List.of();
        final String[] columns={"ID","Item","Quantity","Seller","Current bid","Buyout","Highest bidder","Created","Expires","Status","Featured","Stock"};
        public int getRowCount(){return rows.size();}public int getColumnCount(){return columns.length;}public String getColumnName(int c){return columns[c];}
        public Class<?> getColumnClass(int c){return c==0?Long.class:c==2||c==4||c==5?Integer.class:c==10?Boolean.class:String.class;}
        public Object getValueAt(int r,int c){var row=rows.get(r);var a=row.listing();return switch(c){case 0->a.id();case 1->a.item().config().substring(a.item().config().lastIndexOf('/')+1);case 2->a.item().count();case 3->row.seller();case 4->a.bid();case 5->a.buy();case 6->row.bidder();case 7->date(a.created());case 8->date(a.expires());case 9->status(a);case 10->a.featured();case 11->a.featured()?a.featuredRemaining()+" / "+a.featuredTotal():"—";default->throw new IndexOutOfBoundsException();};}
    }
}
