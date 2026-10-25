package org.tortodrone.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.config.ServerSettings;
import org.tortodrone.resources.ContentFiles;

/** Only the welcome-message line of the active server settings file can be replaced. */
final class WelcomeEditor {
    private final Path file;
    WelcomeEditor(Path file){this.file=file.toAbsolutePath().normalize();}
    private String revision()throws IOException{return Files.exists(file)?ContentFiles.digest(file):"absent";}
    synchronized Map<String,Object> read()throws IOException {
        String message=Files.exists(file)?ServerSettings.read(file).welcomeMessage():ServerSettings.DEFAULT_WELCOME_MESSAGE;
        return Map.of("message",message,"revision",revision());
    }
    synchronized Map<String,Object> save(String value,String expected)throws IOException {
        String message=ServerSettings.welcomeMessage(value);
        if(!expected.equals(revision()))throw new IOException("Server settings changed; refresh before saving");
        boolean existed=Files.exists(file);
        if(!existed)ServerSettings.load(file);
        boolean yaml=file.toString().endsWith(".yml");
        String updated=update(Files.readString(file),yaml,message);
        Path parent=file.getParent(),candidate=Files.createTempFile(parent,".welcome-",yaml?".yml":".properties");
        try {
            Files.writeString(candidate,updated,StandardCharsets.UTF_8);
            ServerSettings.read(candidate);
            if(existed&&!expected.equals(revision()))throw new IOException("Server settings changed; refresh before saving");
            Files.move(candidate,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(candidate);}
        return read();
    }
    private static String update(String text,boolean yaml,String message)throws IOException {
        String newline=text.contains("\r\n")?"\r\n":"\n";
        var lines=new ArrayList<>(Arrays.asList(text.split("\r?\n",-1)));
        int found=-1;
        for(int i=0;i<lines.size();i++)if(lines.get(i).matches("^welcome-message[ \\t]*[:=].*")){found=i;break;}
        String line="welcome-message"+(yaml?": ":"=")+(yaml?yaml(message):properties(message));
        if(found<0) {
            if(!lines.isEmpty()&&lines.getLast().isEmpty())lines.removeLast();
            lines.add("# Shown once in chat after a knight enters the world. Set to \"\" to disable.");
            lines.add(line);
            if(text.isEmpty()||text.endsWith(newline))lines.add("");
        }else {
            if(yaml) {
                String value=lines.get(found).substring(lines.get(found).indexOf(':')+1).strip();
                if((value.startsWith("|")||value.startsWith(">"))&&!value.startsWith("'")&&!value.startsWith("\"")) {
                    int end=found+1;
                    while(end<lines.size()&&(lines.get(end).isBlank()||Character.isWhitespace(lines.get(end).charAt(0))))end++;
                    lines.subList(found+1,end).clear();
                }
            }
            lines.set(found,line);
        }
        return String.join(newline,lines);
    }
    /** YAML single-quoted scalar: only the quote itself needs doubling. */
    private static String yaml(String value){return "'"+value.replace("'","''")+"'";}
    /** Properties values escape literals and non-ASCII so Java's ISO-8859-1 loader round-trips them. */
    private static String properties(String value){
        var out=new StringBuilder(value.length()+8);
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c<' '||c>0x7e)out.append(String.format("\\u%04x",(int)c));
            else {
                if(c=='\\'||c=='='||c==':'||c=='#'||c=='!')out.append('\\');
                out.append(c);
            }
        }
        return out.toString();
    }
}
