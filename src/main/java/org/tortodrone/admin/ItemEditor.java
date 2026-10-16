package org.tortodrone.admin;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;
import org.tortodrone.character.*;
import org.tortodrone.town.TownCatalog;

/** One gear form for new grants and existing inventory instances. */
final class ItemEditor {
    private ItemEditor() { }
    static void choose(AdminWindow window,String title,ItemCatalog.Entry item,ItemModifiers initial,Consumer<ItemModifiers> save){
        var rules=window.accounts().townRules();var gear=rules.gear().gear(item.config());var variants=rules.availableVariants(TownCatalog.itemGroup(item));
        int maximum=gear==null?1:Math.max(1,gear.levels());
        var level=new JSpinner(new SpinnerNumberModel(initial.level()+1,1,Math.max(maximum,initial.level()+1),1));
        var heat=new JSpinner(new SpinnerNumberModel(Math.round(initial.heat()*100000d)/1000d,0d,100d,1d));
        level.setEnabled(maximum>1);heat.setEnabled(gear!=null&&!gear.maximum(initial.level()));
        level.addChangeListener(e->{boolean available=gear!=null&&!gear.maximum((Integer)level.getValue()-1);heat.setEnabled(available);if(!available)heat.setValue(0d);});
        var fields=new JPanel(new GridBagLayout());int row=0;
        field(fields,row++,"Item",new JLabel(item.displayName()));field(fields,row++,"Level (displayed)",level);field(fields,row++,"Heat (%)",heat);
        var choices=new ArrayList<JComboBox<String>>();
        for(int i=0;i<3;i++){
            var choice=new JComboBox<String>();choice.addItem("None");variants.forEach(choice::addItem);choice.setMaximumRowCount(15);choice.setPrototypeDisplayValue("Weapon/Attack Speed Increase Very High");
            choice.setRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){super.getListCellRendererComponent(list,value,index,selected,focus);String path=String.valueOf(value);setText(path.substring(path.lastIndexOf('/')+1));setToolTipText(path);return this;}});
            if(i<initial.variants().size()){String selected=initial.variants().get(i);if(!variants.contains(selected))choice.addItem(selected);choice.setSelectedItem(selected);}choice.setEnabled(!variants.isEmpty());choices.add(choice);field(fields,row++,"Unique Variant "+(i+1),choice);
        }
        var hint=new JLabel(maximum>1?"Heat resets to zero at maximum level. Choose up to three UV families.":"This item has no heat progression. Only supported modifiers can be applied.");hint.setBorder(BorderFactory.createEmptyBorder(12,0,0,0));
        var content=new JPanel(new BorderLayout());content.add(fields);content.add(hint,BorderLayout.SOUTH);
        while(JOptionPane.showConfirmDialog(window,content,title,JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION){
            try{level.commitEdit();heat.commitEdit();var selected=choices.stream().filter(c->c.getSelectedIndex()>0).map(c->(String)c.getSelectedItem()).toList();var modifiers=new ItemModifiers((Integer)level.getValue()-1,((Number)heat.getValue()).floatValue()/100,selected);modifiers.validate(item,rules);save.accept(modifiers);return;}
            catch(java.text.ParseException|IOException invalid){window.message(invalid.getMessage(),true);}
        }
    }
    private static void field(JPanel panel,int row,String name,Component value){var c=new GridBagConstraints();c.gridy=row;c.gridx=0;c.anchor=GridBagConstraints.WEST;c.insets=new Insets(5,0,5,20);panel.add(new JLabel(name),c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;c.insets=new Insets(5,0,5,0);panel.add(value,c);}
}
