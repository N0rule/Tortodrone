package org.tortodrone.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jline.reader.LineReader;
import org.jline.terminal.Sized;
import org.jline.terminal.impl.DumbTerminal;
import static org.tortodrone.support.TestWire.check;

public final class CommandHistoryTest {
    public static void main(String[] args) throws Exception {
        for(String line:List.of("account", "accout list", "account creat Alice", "gm Alice secret-password",
                "unknown", "giveitem Jane 0 Bad/Item", "help account", "/account", "/")) {
            check(CommandHistory.allowed(line),"all nonempty command input is recallable: "+line);
        }
        check(!CommandHistory.allowed(null)&&!CommandHistory.allowed("")&&!CommandHistory.allowed(" ")
            &&!CommandHistory.allowed("x".repeat(4097)),"history skips blank and oversized input");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var terminal = new DumbTerminal("test","xterm",new ByteArrayInputStream(new byte[0]),output,StandardCharsets.UTF_8)) {
            LineReader reader = ConsoleInput.commandReader(terminal);
            var history = reader.getHistory(); history.attach(reader);
            for (int i=0;i<120;i++) history.add("account check User"+i);
            check(history.size()==100 && history.get(history.first()).equals("account check User20") && history.get(history.last()).equals("account check User119"),"bounded history evicts oldest commands");
            history.add("account check User119");
            check(history.size()==100 && history.get(history.last()-1).equals("account check User119"),"repeated commands are retained within bound");
            history.add("accout list");history.add("account");
            history.moveToEnd();check(history.previous()&&history.current().equals("account"),"Up recalls incomplete command");
            check(history.previous()&&history.current().equals("accout list"),"Up recalls typo");
            check(history.next()&&history.current().equals("account"),"Down moves forward");
            check(history.next()&&history.current().isEmpty(),"Down returns to new command");
            check(reader.getVariable(LineReader.HISTORY_FILE)==null,"history never writes a file");
            var prompts = ConsoleInput.promptReader(terminal); prompts.getHistory().attach(prompts);
            prompts.getHistory().add("status"); prompts.getHistory().add("hidden-password");
            check(prompts.getHistory().isEmpty(),"password and confirmation prompts have no history, even for command-shaped passwords");
        }
        String keys="accout list\n\033[A\001\033[C\033[C\033[C\033[C\033[Cn\naccount\n\033[A list\n";
        try (var terminal = new DumbTerminal("keys","xterm",new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)),new ByteArrayOutputStream(),StandardCharsets.UTF_8)) {
            terminal.setSize(new Sized() { public int getColumns() { return 80; } public int getRows() { return 24; } });
            var reader = ConsoleInput.commandReader(terminal);
            check(reader.readLine().equals("accout list"),"typed typo retained");
            check(reader.readLine().equals("account list"),"Up recalls typo for actual cursor correction");
            check(reader.readLine().equals("account"),"incomplete command retained");
            check(reader.readLine().equals("account list"),"Up recalls incomplete command for argument addition");
        }
        String secret="quote'!\\password";
        output = new ByteArrayOutputStream();
        try (var terminal = new DumbTerminal("secret","xterm",new ByteArrayInputStream(("\033[A\033OB"+secret+"\n").getBytes(StandardCharsets.UTF_8)),output,StandardCharsets.UTF_8)) {
            terminal.setSize(new Sized() { public int getColumns() { return 80; } public int getRows() { return 24; } });
            var prompts = ConsoleInput.promptReader(terminal);
            check(prompts.readLine("Password: ",Character.valueOf('\0')).equals(secret),"password quotes, exclamation marks and backslashes remain literal");
            check(prompts.getHistory().isEmpty() && !output.toString(StandardCharsets.UTF_8).contains(secret),"masked password is neither echoed nor recorded");
        }
        System.out.println("PASS: 100 command history entries, eviction, duplicates, Up/Down editing, literal hidden passwords and prompt isolation");
    }
}
