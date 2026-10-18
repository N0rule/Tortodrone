package org.tortodrone.admin;

import java.awt.*;
import java.util.function.BiConsumer;
import javax.swing.*;
import javax.swing.event.*;
import org.tortodrone.character.ItemCatalog;

/** Shared paginated catalogue selection for item grants and featured auctions. */
final class ItemPicker {
    private ItemPicker() { }
    static void choose(AdminWindow window,String title,int maximum,BiConsumer<ItemCatalog.Entry,Integer> chosen){
        var catalog=window.accounts().catalog();
        JDialog dialog=new JDialog(window,title,true);dialog.setSize(850,480);dialog.setLocationRelativeTo(window);
        JPanel root=new JPanel(new BorderLayout(8,8));root.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));dialog.setContentPane(root);
        dialog.setMinimumSize(new Dimension(700,420));
        JTextField query=new JTextField();DesktopWidgets.searchField(query,"Search catalogue by name, ID or path");JComboBox<String> category=new JComboBox<>();category.addItem("All categories");catalog.entries().stream().map(ItemCatalog.Entry::category).distinct().sorted().forEach(category::addItem);
        JPanel search=new JPanel(new BorderLayout(8,0));search.add(query);search.add(category,BorderLayout.EAST);root.add(search,BorderLayout.NORTH);
        DefaultListModel<ItemCatalog.Entry> rows=new DefaultListModel<>();JList<ItemCatalog.Entry> list=new JList<>(rows);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(34);list.setCellRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean s,boolean f){super.getListCellRendererComponent(l,v,i,s,f);var e=(ItemCatalog.Entry)v;putClientProperty("html.disable",true);setText(catalog.displayName(e)+" · "+e.category()+" · "+catalog.alias(e)+(e.grantable()?"":" · Unavailable"));setToolTipText(e.grantable()?e.config():e.unavailableReason());if(!s&&!e.grantable())setForeground(UIManager.getColor("Label.disabledForeground"));return this;}});root.add(new JScrollPane(list));
        JLabel detail=new JLabel("Select a catalogue item"),paging=new JLabel();JSpinner count=new JSpinner(new SpinnerNumberModel(1,1,maximum,1));JButton previous=new JButton("Previous"),next=new JButton("Next"),add=new JButton("Select"),cancel=new JButton("Cancel");add.setEnabled(false);
        JPanel footer=new JPanel(new GridLayout(0,1));footer.add(detail);JPanel controls=new JPanel(new FlowLayout());controls.add(previous);controls.add(paging);controls.add(next);controls.add(new JLabel("Quantity:"));controls.add(count);controls.add(add);controls.add(cancel);footer.add(controls);root.add(footer,BorderLayout.SOUTH);
        int[] page={1};Runnable update=()->{String type=category.getSelectedIndex()==0?null:(String)category.getSelectedItem();var results=catalog.page(query.getText(),type,page[0],25);rows.clear();results.entries().forEach(rows::addElement);paging.setText("Page "+page[0]+" of "+Math.max(1,(results.total()+24)/25)+" · "+results.total()+" matches");detail.setText(results.total()==0?"No matching items. Try another name or category.":"Select an item");previous.setEnabled(page[0]>1);next.setEnabled((long)page[0]*25<results.total());add.setEnabled(false);};
        query.getDocument().addDocumentListener(new DocumentListener(){private void change(){page[0]=1;update.run();}public void insertUpdate(DocumentEvent e){change();}public void removeUpdate(DocumentEvent e){change();}public void changedUpdate(DocumentEvent e){change();}});category.addActionListener(e->{page[0]=1;update.run();});previous.addActionListener(e->{page[0]--;update.run();});next.addActionListener(e->{page[0]++;update.run();});
        list.addListSelectionListener(e->{var entry=list.getSelectedValue();add.setEnabled(entry!=null&&entry.grantable());detail.setText(entry==null?"Select a catalogue item":entry.grantable()?entry.config():entry.unavailableReason());detail.setToolTipText(entry==null?null:entry.config()+" · ID "+entry.stockId());});
        cancel.addActionListener(e->dialog.dispose());add.addActionListener(e->{var entry=list.getSelectedValue();if(entry==null||!entry.grantable())return;try{count.commitEdit();}catch(java.text.ParseException invalid){window.message("Enter a quantity between 1 and "+maximum+".",true);return;}int quantity=(Integer)count.getValue();dialog.dispose();chosen.accept(entry,quantity);});dialog.getRootPane().setDefaultButton(add);dialog.getRootPane().registerKeyboardAction(e->dialog.dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);update.run();SwingUtilities.invokeLater(query::requestFocusInWindow);dialog.setVisible(true);
    }
}
