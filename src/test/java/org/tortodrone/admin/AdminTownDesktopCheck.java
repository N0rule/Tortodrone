package org.tortodrone.admin;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.storage.Database;
import org.tortodrone.support.TestDeployment;
import static org.tortodrone.support.TestWire.check;

/** Opt-in single desktop scenario. Never starts a server or opens the owner's database. */
public final class AdminTownDesktopCheck {
    public static void main(String[] args)throws Exception{
        if(args.length!=1||GraphicsEnvironment.isHeadless())throw new IllegalArgumentException("A display and fresh output directory are required");
        Path root=Files.createDirectory(Path.of(args[0]));TestDeployment.copyServer(Path.of("build/tortodrone.jar"),root.resolve("tortodrone.jar"));
        try(var accounts=new AccountStore(root.resolve("data"))){accounts.create("Alice","desktop-password".toCharArray());new CharacterStore(new Database(root.resolve("data"),false),1,"Alice",accounts.catalog()).create(new Knight(0,"Admin-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));}
        AdminWindow window=edt(()->{DesktopTheme.installDefault();var w=new AdminWindow(root.resolve("data"),"Admin-Knight");w.setVisible(true);return w;});
        try{
            var panel=(KnightPanel)field(window,"knightPanel");var inventory=(JTable)field(panel,"table");
            await(()->edt(()->inventory.getRowCount()==5&&!busy(window)),"initial inventory");capture(window,root.resolve("accounts.png"));
            SwingUtilities.invokeLater(()->button(panel,"Add item").doClick());var picker=dialog("Add item");
            edt(()->{components(picker,JTextField.class).getFirst().setText("Proto Sword");components(picker,JList.class).getFirst().setSelectedIndex(0);components(picker,JSpinner.class).getFirst().setValue(2);return null;});
            SwingUtilities.invokeLater(()->button(picker,"Select").doClick());var grant=dialog("Grant Proto Sword");
            edt(()->{var spins=components(grant,JSpinner.class);spins.get(0).setValue(3);spins.get(1).setValue(60d);components(grant,JComboBox.class).getFirst().setSelectedIndex(1);return null;});capture(grant,root.resolve("item-modifiers.png"));accept(grant);
            await(()->edt(()->inventory.getRowCount()==7&&!busy(window)),"grant completion");
            var profile=window.accounts().worldProfile("Alice","Admin-Knight");check(profile.items().stream().filter(i->!i.starter()).allMatch(i->i.level()==2&&i.heatProgress()==.6f&&i.variants().size()==1),"UI grants exact modifiers to both items");
            edt(()->{inventory.setRowSelectionInterval(inventory.getRowCount()-1,inventory.getRowCount()-1);return null;});SwingUtilities.invokeLater(()->button(panel,"Level / heat / UVs").doClick());var edit=dialog("Edit gear");edt(()->{components(edit,JSpinner.class).getFirst().setValue(6);return null;});accept(edit);
            await(()->edt(()->!busy(window))&&window.accounts().worldProfile("Alice","Admin-Knight").items().stream().anyMatch(i->i.level()==5),"existing item edit");
            var navigation=(JList<?>)field(window,"navigation");edt(()->{navigation.setSelectedIndex(1);return null;});var depot=(DepotPanel)field(window,"depotPanel");await(()->edt(()->field(depot,"snapshot")!=null&&!busy(window)),"depot overview");
            SwingUtilities.invokeLater(()->button(depot,"Add offer").doClick());var depotPicker=dialog("Supply Depot item");edt(()->{components(depotPicker,JTextField.class).getFirst().setText("Material/Gel Drop");components(depotPicker,JList.class).getFirst().setSelectedIndex(0);components(depotPicker,JSpinner.class).getFirst().setValue(5);return null;});SwingUtilities.invokeLater(()->button(depotPicker,"Select").doClick());var offer=dialog("Add Supply Depot offer");edt(()->{components(offer,JSpinner.class).get(1).setValue(30);components(offer,JCheckBox.class).getFirst().setSelected(true);return null;});accept(offer);
            await(()->edt(()->!busy(window))&&window.accounts().depot().snapshot().revision()>0,"depot save");var saved=window.accounts().depot().snapshot().offers().getLast();check(saved.good().count()==5&&saved.good().price()==30&&saved.featured(),"UI saves configured depot offer");capture(window,root.resolve("supply-depot.png"));
            edt(()->{navigation.setSelectedIndex(2);return null;});var auction=(AuctionPanel)field(window,"auctionPanel");await(()->edt(()->!busy(window)),"auction overview");SwingUtilities.invokeLater(()->button(auction,"Create featured").doClick());var auctionPicker=dialog("Featured auction item");edt(()->{components(auctionPicker,JTextField.class).getFirst().setText("Weapon/Sword/Calibur");components(auctionPicker,JList.class).getFirst().setSelectedIndex(0);return null;});SwingUtilities.invokeLater(()->button(auctionPicker,"Select").doClick());var featured=dialog("Create system-owned featured auction");edt(()->{components(featured,JSpinner.class).get(1).setValue(500);return null;});accept(featured);
            await(()->edt(()->!busy(window))&&window.accounts().auctions().overview().size()==1,"featured auction creation");check(window.accounts().auctions().overview().getFirst().listing().featured(),"UI creates system-owned listing");capture(window,root.resolve("auction-house.png"));
            System.out.println("PASS: isolated admin navigation, gear grant/edit forms, Supply Depot offer creation and featured auction creation; captures: "+root);
        }finally{edt(()->{for(Window w:Window.getWindows())w.dispose();return null;});}
    }
    private static boolean busy(AdminWindow window)throws Exception{return (Boolean)field(window,"busy");}
    private static Object field(Object object,String name)throws Exception{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static JButton button(Container root,String text){return components(root,JButton.class).stream().filter(b->text.equals(b.getText())).findFirst().orElseThrow();}
    private static <T extends Component> List<T> components(Container root,Class<T> type){var found=new ArrayList<T>();if(type.isInstance(root))found.add(type.cast(root));for(Component c:root.getComponents()){if(type.isInstance(c))found.add(type.cast(c));if(c instanceof Container child)for(var nested:components(child,type))if(!found.contains(nested))found.add(nested);}return found;}
    private static JDialog dialog(String title)throws Exception{await(()->edt(()->Arrays.stream(Window.getWindows()).anyMatch(w->w instanceof JDialog d&&d.isShowing()&&d.getTitle().startsWith(title))),title);return edt(()->Arrays.stream(Window.getWindows()).filter(w->w instanceof JDialog d&&d.isShowing()&&d.getTitle().startsWith(title)).map(JDialog.class::cast).findFirst().orElseThrow());}
    private static void accept(JDialog dialog)throws Exception{edt(()->{components(dialog,JOptionPane.class).getFirst().setValue(JOptionPane.OK_OPTION);return null;});}
    @FunctionalInterface private interface Condition{boolean value()throws Exception;}
    private static void await(Condition condition,String label)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<until){if(condition.value())return;Thread.sleep(40);}throw new AssertionError("Timed out: "+label);}
    private static <T>T edt(Callable<T> work)throws Exception{if(SwingUtilities.isEventDispatchThread())return work.call();var future=new FutureTask<T>(work);SwingUtilities.invokeAndWait(future);return future.get();}
    private static void capture(Window window,Path path)throws Exception{ImageIO.write(edt(()->{var content=((RootPaneContainer)window).getContentPane();var image=new BufferedImage(content.getWidth(),content.getHeight(),BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{content.printAll(g);}finally{g.dispose();}return image;}),"png",path.toFile());}
}
