package org.tortodrone.admin;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Font;
import java.awt.Window;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.*;

/** Shared theme setup and live switching for the local Swing applications. */
public final class DesktopTheme {
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<WeakReference<JRadioButtonMenuItem>> selectors = new CopyOnWriteArrayList<>();
    private static boolean dark = true;
    private static boolean initialized;

    private DesktopTheme() { }

    public static void installDefault() { if (!initialized) setDark(true); }
    public static boolean isDark() { return dark; }
    public static void addListener(Runnable listener) { listeners.add(listener); }
    public static void removeListener(Runnable listener) { listeners.remove(listener); }

    public static void setDark(boolean useDark) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setDark(useDark));
            return;
        }
        try {
            if (useDark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
            dark = useDark;
            initialized = true;
            UIManager.put("defaultFont", new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            UIManager.put("Table.rowHeight", 28);
            UIManager.put("Component.arc", 10);
            UIManager.put("Button.arc", 10);
            UIManager.put("TextComponent.arc", 8);
            for (Window window : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(window);
                window.invalidate();
                window.validate();
                window.repaint();
            }
            for (WeakReference<JRadioButtonMenuItem> reference : selectors) {
                JRadioButtonMenuItem item = reference.get();
                if (item == null) selectors.remove(reference);
                else if ("dark".equals(item.getActionCommand())) item.setSelected(useDark);
                else item.setSelected(!useDark);
            }
            for (Runnable listener : listeners) listener.run();
        } catch (Exception failure) {
            System.err.println("Could not apply desktop theme: " + failure.getMessage());
        }
    }

    public static JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu view = new JMenu("View");
        ButtonGroup themes = new ButtonGroup();
        JRadioButtonMenuItem darkItem = new JRadioButtonMenuItem("Dark theme", dark);
        JRadioButtonMenuItem lightItem = new JRadioButtonMenuItem("Light theme", !dark);
        darkItem.setActionCommand("dark");
        lightItem.setActionCommand("light");
        selectors.add(new WeakReference<>(darkItem));
        selectors.add(new WeakReference<>(lightItem));
        themes.add(darkItem);
        themes.add(lightItem);
        darkItem.addActionListener(event -> setDark(true));
        lightItem.addActionListener(event -> setDark(false));
        view.add(darkItem);
        view.add(lightItem);
        bar.add(view);
        return bar;
    }
}
