package org.tortodrone.server;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.file.*;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.storage.Database;
import org.tortodrone.support.*;
import static org.tortodrone.support.TestWire.check;

/** Opt-in real desktop acceptance. Only its disposable JVM windows/data are controlled. */
public final class DesktopCheck {
    public static void main(String[] args) throws Exception {
        if(args.length==2&&args[0].equals("--fixture")){fixture(Path.of(args[1]));return;}
        if(GraphicsEnvironment.isHeadless())throw new IllegalStateException("A real graphical display is required");
        Path root=args.length==0?Files.createTempDirectory("tortodrone-desktop-"):Files.createDirectory(Path.of(args[0]));
        Path jar=root.resolve("tortodrone.jar");TestDeployment.copyServer(Path.of("build/tortodrone.jar"),jar);
        try(var accounts=new AccountStore(root.resolve("data"))) {
            accounts.create("Alice","desktop-password".toCharArray(),true);
            var store=new CharacterStore(new Database(root.resolve("data"),false),1,"Alice",accounts.catalog());
            store.create(new Knight(0,"Desktop-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
        }
        Files.writeString(root.resolve("data/server.properties"),"server-ip=127.0.0.1\nserver-port=0\nlog-mode=debug\n");
        Path tests=Path.of(DesktopCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"--enable-native-access=ALL-UNNAMED","-Duser.home="+root.resolve("home"),"-Djava.util.prefs.userRoot="+root.resolve("prefs"),"-cp",jar+java.io.File.pathSeparator+tests,DesktopCheck.class.getName(),"--fixture",root.toString()).redirectErrorStream(true).redirectOutput(root.resolve("desktop.log").toFile());
        builder.environment().put("_JAVA_AWT_WM_NONREPARENTING","1");Process child=builder.start();
        try {check(child.waitFor(90,TimeUnit.SECONDS),"desktop fixture deadline");check(child.exitValue()==0,"desktop acceptance: "+Files.readString(root.resolve("desktop.log")));System.out.println("PASS: real Swing server and inventory windows, snapshot/log updates, commands, grants, balances, stop/start and exit; captures: "+root);}
        finally {if(child.isAlive()){child.destroy();if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor();}}}
    }
    private static void fixture(Path root) throws Exception {
        Thread hosting=new Thread(()->Server.main(new String[]{"--gui"}),"DesktopFixtureHost");hosting.start();
        ServerWindow window=null;Socket unauth=null;CountDownLatch release=new CountDownLatch(1);ExecutorService network=Executors.newSingleThreadExecutor();
        try {
            await(()->find(ServerWindow.class)!=null,"server frame");window=find(ServerWindow.class);ServerWindow view=window;
            ServerControl control=(ServerControl)field(view,"control");await(()->control.snapshot().state()==ServerControl.State.RUNNING,"ready core");
            int port=Integer.parseInt(control.snapshot().endpoint().split(":")[1]);unauth=new Socket("127.0.0.1",port);
            JTable players=(JTable)field(view,"players");await(()->edt(()->players.getRowCount()==1),"unauthenticated player row");
            check(edt(()->players.getModel().getValueAt(0,1)==null),"unauthenticated desktop row has no account ID");unauth.close();unauth=null;
            String[] key=Files.readString(root.resolve("data/keys/public.txt")).strip().split("#");PublicKey publicKey=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(key[0],16),new BigInteger(key[1],16)));
            var authenticated=network.submit(()->{try(var socket=new Socket("127.0.0.1",port)){TestClient.authenticate(socket,publicKey,"Alice","desktop-password",true,(peer,wire,oid)->{release.await(60,TimeUnit.SECONDS);var writer=new TestWire.Writer("com.threerings.presents.net.SecureRequest","com.threerings.presents.net.PublicKeyCredentials","[Ljava.lang.String;","com.threerings.presents.net.AESAuthRequest");writer.send(peer.getOutputStream(),out->writer.message(out,"LogoffRequest",20));});}return null;});
            await(()->edt(()->players.getRowCount()==1&&Integer.valueOf(1).equals(players.getModel().getValueAt(0,1))),"authenticated desktop identity");
            edt(()->{players.setRowSelectionInterval(0,0);return null;});
            JLabel overview=(JLabel)field(view,"overview");await(()->edt(()->overview.getHeight()>=overview.getPreferredSize().height),"overview fits its measured text");capture(view,root.resolve("server.png"));
            JTabbedPane tabs=edt(()->components(view,JTabbedPane.class).getFirst());
            JTable scenes=(JTable)field(view,"scenes");var state=control.snapshot();check(edt(()->scenes.getRowCount()==state.templates().size()+state.scenes().size()),"GUI distinguishes loaded templates and active instances");
            edt(()->{tabs.setSelectedIndex(1);return null;});capture(view,root.resolve("scenes.png"));
            PerformanceView performance=(PerformanceView)field(view,"performance");check(edt(()->performance.statistic("Logical CPUs").equals(Integer.toString(Runtime.getRuntime().availableProcessors()))),"GUI performance uses actual CPU count");edt(()->{tabs.setSelectedIndex(2);return null;});
            JScrollPane performanceScroll=edt(()->components(performance,JScrollPane.class).getFirst());
            check(edt(()->components(performance,JTextArea.class).isEmpty()&&components(performance,JScrollPane.class).size()==1),"performance has read-only fields and one scrolling page");
            edt(()->{performanceScroll.getVerticalScrollBar().setValue(220);return null;});int scrollPosition=edt(()->performanceScroll.getVerticalScrollBar().getValue());Thread.sleep(2200);
            check(edt(()->Math.abs(performanceScroll.getVerticalScrollBar().getValue()-scrollPosition)<5),"live updates preserve performance scroll position");
            var cpuChart=edt(()->components(performance,org.jfree.chart.ChartPanel.class).getFirst());
            JToggleButton graphPause=edt(()->components(performance,JToggleButton.class).stream().filter(b->b.getText().equals("Pause graphs")).findFirst().orElseThrow());
            edt(()->{graphPause.doClick();return null;});double pausedAt=edt(()->{var data=cpuChart.getChart().getXYPlot().getDataset();return data.getXValue(0,data.getItemCount(0)-1);});Thread.sleep(2200);
            check(edt(()->{var data=cpuChart.getChart().getXYPlot().getDataset();return data.getXValue(0,data.getItemCount(0)-1)==pausedAt;}),"pause freezes graph display");
            edt(()->{graphPause.doClick();return null;});check(edt(()->{var data=cpuChart.getChart().getXYPlot().getDataset();return data.getXValue(0,data.getItemCount(0)-1)>pausedAt;}),"resume shows samples collected while paused");
            JToggleButton consoleToggle=edt(()->components(view,JToggleButton.class).stream().filter(b->b.getText().equals("Console")).findFirst().orElseThrow());
            edt(()->{consoleToggle.doClick();performanceScroll.getVerticalScrollBar().setValue(0);return null;});capture(view,root.resolve("performance.png"));
            edt(()->{org.tortodrone.admin.DesktopTheme.setDark(false);view.setSize(1000,740);return null;});capture(view,root.resolve("performance-light.png"));edt(()->{org.tortodrone.admin.DesktopTheme.setDark(true);view.setSize(1200,850);tabs.setSelectedIndex(0);return null;});
            edt(()->{consoleToggle.doClick();return null;});
            JTextField input=(JTextField)field(view,"command");edt(()->{input.setText("/set Desktop-Knight crowns 500");input.postActionEvent();return null;});
            try(var accounts=new AccountStore(root.resolve("data"))) {await(()->accounts.inspectKnight("Desktop-Knight").crowns()==500,"graphical command transaction");}
            DefaultListModel<?> logs=(DefaultListModel<?>)field(view,"output");await(()->edt(()->java.util.Collections.list(logs.elements()).stream().anyMatch(e->e.toString().contains("Currency set"))),"structured log display");
            JList<?> console=(JList<?>)field(view,"console");JCheckBox follow=(JCheckBox)field(view,"scroll");
            edt(()->{follow.setSelected(false);console.setSelectedIndex(0);console.ensureIndexIsVisible(0);return null;});Object selectedLog=edt(console::getSelectedValue);
            int logSize=edt(logs::size);edt(()->{input.setText("status");input.postActionEvent();return null;});await(()->edt(()->logs.size()>logSize),"new logs while browsing history");check(edt(()->selectedLog.equals(console.getSelectedValue())&&console.getFirstVisibleIndex()==0),"incoming logs preserve selection and history position");
            org.tortodrone.admin.AdminApplication.openKnight(root.resolve("data"),"desktop-knight");await(()->admin()!=null,"account manager frame");JFrame manager=admin();Object panel=field(manager,"knightPanel");JTable inventory=(JTable)field(panel,"table");
            await(()->edt(()->inventory.getRowCount()==5),"initial knight inventory and case-insensitive selection");
            edt(()->{inventory.setRowSelectionInterval(0,0);return null;});check(edt(()->!button((Container)panel,"Set quantity").isEnabled()&&!button((Container)panel,"Remove item").isEnabled()),"protected items explain disabled mutation actions");
            SwingUtilities.invokeLater(()->button((Container)panel,"Add item").doClick());await(()->dialog("Add item")!=null,"catalogue dialog");JDialog catalogue=dialog("Add item");
            edt(()->{JTextField query=components(catalogue,JTextField.class).getFirst();query.setText("Proto Sword");JList<?> list=components(catalogue,JList.class).getFirst();list.setSelectedIndex(0);components(catalogue,JSpinner.class).getFirst().setValue(2);return null;});capture(catalogue,root.resolve("catalogue.png"));
            edt(()->{button(catalogue,"Grant").doClick();return null;});await(()->edt(()->inventory.getRowCount()==7),"UI grants and refreshed inventory");
            edt(()->{inventory.setRowSelectionInterval(6,6);return null;});check(edt(()->button((Container)panel,"Set quantity").isEnabled()),"ordinary unequipped item actions are available");
            Object selectedItem=edt(()->inventory.getModel().getValueAt(inventory.convertRowIndexToModel(inventory.getSelectedRow()),5));
            SwingUtilities.invokeLater(()->button((Container)panel,"Currency").doClick());await(()->dialog("Currency")!=null,"currency dialog");JDialog currency=dialog("Currency");
            edt(()->{var boxes=components(currency,JComboBox.class);boxes.get(0).setSelectedItem(Currency.ENERGY);boxes.get(1).setSelectedItem("Set");components(currency,JTextField.class).getFirst().setText("50");components(currency,JOptionPane.class).getFirst().setValue(JOptionPane.OK_OPTION);return null;});
            JLabel balances=(JLabel)field(panel,"balances");await(()->edt(()->balances.getText().contains("Energy: 50")),"UI absolute balance operation");check(edt(()->inventory.getSelectedRow()>=0&&selectedItem.equals(inventory.getModel().getValueAt(inventory.convertRowIndexToModel(inventory.getSelectedRow()),5))),"balance refresh preserves selected inventory instance");capture(manager,root.resolve("inventory.png"));
            edt(()->{button((Container)panel,"Refresh knight").doClick();return null;});await(()->edt(()->!(boolean)field(panel,"busy")),"manual knight refresh completes");check(edt(()->inventory.getSelectedRow()>=0&&selectedItem.equals(inventory.getModel().getValueAt(inventory.convertRowIndexToModel(inventory.getSelectedRow()),5))),"manual refresh preserves selected inventory instance");
            SwingUtilities.invokeLater(()->button(manager,"Create account").doClick());await(()->dialog("Create account")!=null,"create account dialog");JDialog create=dialog("Create account");
            edt(()->{components(create,JTextField.class).getFirst().setText("Bob");for(var password:components(create,JPasswordField.class))password.setText("desktop-password");components(create,JOptionPane.class).getFirst().setValue(JOptionPane.OK_OPTION);return null;});
            JTable accountsTable=(JTable)field(manager,"table");await(()->edt(()->accountsTable.getSelectedRow()>=0&&"Bob".equals(accountsTable.getModel().getValueAt(accountsTable.convertRowIndexToModel(accountsTable.getSelectedRow()),1))),"new account becomes the selected target");
            check(edt(()->inventory.getRowCount()==0&&!button((Container)panel,"Add item").isEnabled()),"account with no knights clears old inventory and actions");
            release.countDown();authenticated.get(10,TimeUnit.SECONDS);
            try(var pending=new Socket("127.0.0.1",port)){pending.setSoTimeout(5000);await(()->control.sessions().snapshots().size()==1,"pending connection before shutdown");control.stop();await(()->control.snapshot().state()==ServerControl.State.STOPPED,"graceful stop");check(pending.getInputStream().read()==-1,"GUI stop drains pending connection");}check(hosting.isAlive(),"stopped GUI retains lifecycle host");control.start();await(()->control.snapshot().state()==ServerControl.State.RUNNING,"restart from stopped state");
            control.exit();hosting.join(15000);check(!hosting.isAlive(),"GUI exit releases listener, stores and hosting thread");
            System.out.println("PASS: native Swing acceptance complete");
        } finally {release.countDown();if(unauth!=null)unauth.close();network.shutdownNow();if(window!=null){((ServerControl)field(window,"control")).exit();window.close();}hosting.join(15000);edt(()->{for(Window w:Window.getWindows())w.dispose();return null;});}
    }
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static JFrame admin()throws Exception{return edt(()->{for(Window w:Window.getWindows())if(w instanceof JFrame frame&&frame.isShowing()&&frame.getTitle().contains("Account Manager"))return frame;return null;});}
    private static JDialog dialog(String title)throws Exception{return edt(()->{for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&d.getTitle().startsWith(title))return d;return null;});}
    private static <T extends Window>T find(Class<T> type)throws Exception{return edt(()->{for(Window w:Window.getWindows())if(type.isInstance(w)&&w.isShowing())return type.cast(w);return null;});}
    private static <T extends Component>List<T> components(Container parent,Class<T> type){var result=new java.util.ArrayList<T>();for(Component c:parent.getComponents()){if(type.isInstance(c))result.add(type.cast(c));if(c instanceof Container container)result.addAll(components(container,type));}return result;}
    private static JButton button(Container parent,String text){return components(parent,JButton.class).stream().filter(b->b.getText().equals(text)).findFirst().orElseThrow();}
    private static void capture(Window window,Path path)throws Exception{BufferedImage image=edt(()->{Component content=window instanceof RootPaneContainer pane?pane.getContentPane():window;BufferedImage rendered=new BufferedImage(content.getWidth(),content.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D graphics=rendered.createGraphics();try{content.printAll(graphics);}finally{graphics.dispose();}return rendered;});ImageIO.write(image,"png",path.toFile());}
    private static <T>T edt(Callable<T> action)throws Exception{if(SwingUtilities.isEventDispatchThread())return action.call();FutureTask<T> task=new FutureTask<>(action);SwingUtilities.invokeAndWait(task);return task.get();}
    private static void await(Check condition,String description)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<deadline){if(condition.get())return;Thread.sleep(25);}throw new AssertionError("Timed out: "+description);}
    @FunctionalInterface private interface Check{boolean get()throws Exception;}
}
