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
        check(CommandHistory.allowed("account create Alice gm")&&CommandHistory.allowed("account gm Alice false"),"Game Master commands are recallable");
        check(!CommandHistory.allowed("account gm Alice secret-password"),"misplaced GM password is not recallable");
        for (String line : List.of("ac list","ac gm Alice","gm Alice","gm Alice true","account gm Alice","currency give Alice Saved-Knight crowns 100","currency withdraw Alice Saved-Knight energy 5","help","status","stop","/status","account list","account create Alice","account create Alice gm","account password Alice","account check Alice","account delete Alice","account gm Alice true","account gm Alice false","knight list Alice","knight info Alice Saved-Knight")) {
            check(CommandHistory.allowed(line),"recognized command history: "+line);
        }
        for (String line : List.of("gm Alice secret-password","ac password Alice secret-password","gm","gm Alice false extra","currency give Alice Saved-Knight crowns 0","currency give Alice Saved-Knight crowns 2147483648","currency give Alice Saved-Knight gems 1",""," ","/","secret-password","account create Alice secret-password","account password Alice secret-password","account admin Alice secret-password","account create Alice admin","account admin Alice true","account admin Alice false","unknown","account","account create ../bad","knight list Alice secret-password","knight info Alice ../bad")) {
            check(!CommandHistory.allowed(line),"unknown or secret-bearing input excluded");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var terminal = new DumbTerminal("test","xterm",new ByteArrayInputStream(new byte[0]),output,StandardCharsets.UTF_8)) {
            LineReader reader = ConsoleInput.commandReader(terminal);
            var history = reader.getHistory(); history.attach(reader);
            for (int i=0;i<120;i++) history.add("account check User"+i);
            check(history.size()==100 && history.get(history.first()).equals("account check User20") && history.get(history.last()).equals("account check User119"),"bounded history evicts oldest commands");
            history.add("account check User119"); check(history.size()==100,"consecutive duplicates do not consume history");
            history.add("secret-password"); history.add("account create Alice misplaced-password");
            check(history.get(history.last()).equals("account check User119"),"rejected input never becomes recallable");
            history.moveToEnd(); check(history.previous() && history.current().equals("account check User119"),"Up recalls newest command");
            check(history.previous() && history.current().equals("account check User118"),"Up moves to older command");
            check(history.next() && history.current().equals("account check User119"),"Down moves forward");
            check(history.next() && history.current().isEmpty(),"Down returns to new command");
            check(reader.getVariable(LineReader.HISTORY_FILE)==null,"history never writes a file");
            var prompts = ConsoleInput.promptReader(terminal); prompts.getHistory().attach(prompts);
            prompts.getHistory().add("status"); prompts.getHistory().add("hidden-password");
            check(prompts.getHistory().isEmpty(),"password and confirmation prompts have no history, even for command-shaped passwords");
        }
        String keys="help\nstatus\n\033[A\n\033[A\033[A\nacc\033[A\033[Bount list\n";
        try (var terminal = new DumbTerminal("keys","xterm",new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)),new ByteArrayOutputStream(),StandardCharsets.UTF_8)) {
            terminal.setSize(new Sized() { public int getColumns() { return 80; } public int getRows() { return 24; } });
            var reader = ConsoleInput.commandReader(terminal);
            check(reader.readLine().equals("help") && reader.readLine().equals("status"),"typed commands");
            check(reader.readLine().equals("status"),"actual Up key recall");
            check(reader.readLine().equals("help"),"repeated Up keys");
            check(reader.readLine().equals("account list"),"Down restores unfinished command for editing");
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
