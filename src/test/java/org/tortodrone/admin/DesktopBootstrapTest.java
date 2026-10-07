package org.tortodrone.admin;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.tortodrone.support.TestWire.check;

public final class DesktopBootstrapTest {
    public static void main(String[] args) {
        Map<String,String> niri = Map.of("DISPLAY",":1","XDG_CURRENT_DESKTOP","niri");
        check(DesktopBootstrap.needsRelaunch("Linux",niri,false),"niri requires AWT compatibility before GUI initialization");
        check(DesktopBootstrap.needsRelaunch("Linux",Map.of("DISPLAY",":1","XDG_CURRENT_DESKTOP","other:NIRI"),false),"desktop token matching");
        check(DesktopBootstrap.needsRelaunch("Linux",Map.of("DISPLAY",":1","NIRI_SOCKET","/run/user/test/niri.sock"),false),"niri socket identifies sessions without desktop name");
        check(!DesktopBootstrap.needsRelaunch("Windows 11",niri,false),"Windows startup untouched");
        check(!DesktopBootstrap.needsRelaunch("Mac OS X",niri,false),"macOS startup untouched");
        check(!DesktopBootstrap.needsRelaunch("Linux",niri,true),"headless startup never relaunches");
        check(!DesktopBootstrap.needsRelaunch("Linux",Map.of("XDG_CURRENT_DESKTOP","niri"),false),"no X display never relaunches");
        check(!DesktopBootstrap.needsRelaunch("Linux",Map.of("DISPLAY",":1","XDG_CURRENT_DESKTOP","KDE"),false),"other desktops untouched");
        Map<String,String> overridden = new HashMap<>(niri); overridden.put(DesktopBootstrap.NON_REPARENTING,"1");
        check(!DesktopBootstrap.needsRelaunch("Linux",overridden,false),"child environment prevents recursive relaunch");
        overridden.put(DesktopBootstrap.NON_REPARENTING,"0");
        check(!DesktopBootstrap.needsRelaunch("Linux",overridden,false),"existing environment override respected");
        Path jar = Path.of("/tmp/server pc/tortodrone-admin.jar");
        var command = DesktopBootstrap.command(jar,new String[] {"--data","/tmp/server pc/data"});
        int entry = command.indexOf("-jar");
        check(entry > 0 && command.subList(entry,command.size()).equals(List.of("-jar",jar.toString(),"--data","/tmp/server pc/data")),"JAR path and application arguments remain separate including spaces");
        check(command.get(0).equals(Path.of(System.getProperty("java.home"),"bin","java").toString()),"reuse current Java runtime");
        var classes = DesktopBootstrap.command(Path.of("."),new String[0]);
        int cp = classes.indexOf("-cp");
        check(cp > 0 && classes.get(cp+1).equals(System.getProperty("java.class.path")) && classes.get(cp+2).equals(AdminApplication.class.getName()),"class-directory launches preserve classpath");
        System.out.println("PASS: niri desktop compatibility detection, no relaunch loop, overrides, runtime and argument preservation");
    }
}
