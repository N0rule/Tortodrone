package org.tortodrone.web;

import java.io.IOException;
import java.util.*;

/** Small remote command vocabulary over the existing typed administration operations. */
final class AdminConsole {
    private static final List<String> READ=List.of("help","status","players","scenes","accounts");
    private static final List<String> WRITE=List.of("start","stop","restart","kick","shutdown");
    static Object commands(){return Map.of("read",READ,"actions",WRITE,"help","help | status | players | scenes | accounts | start | stop | restart | kick <session-id> | shutdown");}
    static String auditTarget(String line){
        String command=line.strip().split("\\s+",2)[0];
        return READ.contains(command)||WRITE.contains(command)?command:"unsupported";
    }
    static Object execute(AdminService service,AdminService.Input input)throws IOException {
        String line=input.text("command").strip();
        if(line.length()>128||line.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid command");
        String[] words=line.split("\\s+");String command=words[0];
        if(!(READ.contains(command)||WRITE.contains(command))||words.length!=(command.equals("kick")?2:1))throw new IllegalArgumentException("Unsupported command; use help");
        var empty=new AdminService.Input(Map.of());Object result;
        if(WRITE.contains(command)) {
            if(!line.equals(input.text("commandConfirmation")))throw new IllegalArgumentException("Repeat the exact command to confirm");
            var values=new HashMap<String,String>();values.put("confirmation",command);
            if(command.equals("kick")){long id=Long.parseLong(words[1]);if(id<=0)throw new IllegalArgumentException("Invalid session");values.put("session",Long.toString(id));}
            if(command.equals("shutdown"))values.put("shutdown",input.text("shutdown"));
            service.write(command,new AdminService.Input(values));result=command+" requested.";
        }else if(command.equals("help"))result=((Map<?,?>)commands()).get("help");
        else if(command.equals("accounts"))result=service.read("accounts",empty);
        else {
            var status=(Map<?,?>)service.read("status",empty);
            result=command.equals("players")?status.get("sessions"):command.equals("scenes")?status.get("scenes"):status;
        }
        String output=result instanceof String text?text:WebJson.encode(result);
        if(output.length()>60_000)output=output.substring(0,60_000)+"\nOutput truncated; use the corresponding administration page.";
        return Map.of("output",output);
    }
}
