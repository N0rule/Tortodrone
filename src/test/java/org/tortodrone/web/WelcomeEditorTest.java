package org.tortodrone.web;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.config.ServerSettings;
import static org.tortodrone.support.TestWire.check;

/** Only the welcome setting is replaced, preserving the rest of the active settings file. */
public final class WelcomeEditorTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-welcome-editor-");
        try {
            Path legacy=root.resolve("data/server.properties");
            Files.createDirectories(legacy.getParent());
            Files.writeString(legacy,"# legacy fixture\nserver-port=0\nwebui-enabled=false\n");
            var editor=new WelcomeEditor(legacy);
            var initial=editor.read();
            check(initial.get("message").equals(ServerSettings.DEFAULT_WELCOME_MESSAGE),"missing key falls back to the bundled welcome");
            String message="Line #1 = 100% \\ ready — café ☃";
            var saved=editor.save(message,(String)initial.get("revision"));
            check(saved.get("message").equals(message)&&ServerSettings.read(legacy).welcomeMessage().equals(message),"properties escape literals exactly");
            check(Files.readString(legacy).contains("# legacy fixture")&&Files.readString(legacy).contains("server-port=0"),"legacy comments and settings are preserved");
            refused(()->editor.save("Other",(String)initial.get("revision")));
            check(ServerSettings.read(legacy).welcomeMessage().equals(message),"stale updates are refused");

            Path yaml=root.resolve("config/server.yml");
            var missing=new WelcomeEditor(yaml);
            var absent=missing.read();
            check(absent.get("message").equals(ServerSettings.DEFAULT_WELCOME_MESSAGE)&&absent.get("revision").equals("absent")&&!Files.exists(yaml),"missing configuration is not created by a read");
            missing.save("Welcome!",(String)absent.get("revision"));
            String configured=Files.readString(yaml);
            check(ServerSettings.read(yaml).welcomeMessage().equals("Welcome!")&&configured.contains("welcome-message: 'Welcome!'")&&configured.contains("# Folder name under content/maps"),"creating settings keeps bundled comments and quotes the value");
            System.out.println("PASS: welcome editor preserves settings files, escapes properties, rejects stale updates and creates safe defaults");
        }finally{try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Stale welcome update accepted");}catch(IOException expected){}}
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
