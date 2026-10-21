package org.tortodrone.character;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shipped player AnimatedAction references and native animation timing. A {@code Random}
 * action picks one of its equally weighted effect references.
 */
public final class AnimatedItems {
    /** One effect reference and its two native arguments, exactly as the catalogue spells them. */
    public record Definition(String effect,int color,String size) {
        public Definition {
            if(effect==null||effect.isBlank()||color<1||size==null||size.isBlank())
                throw new IllegalArgumentException("Invalid animated item effect");
        }
    }
    private static final Map<String,List<Definition>> DEFINITIONS=load();
    private AnimatedItems() { }
    public static boolean supports(String config){return DEFINITIONS.containsKey(config);}
    public static int land(String config){definitions(config);return config.equals("Usable/Jar O Fireflies")?800:300;}
    public static int duration(String config){return land(config)+(config.equals("Usable/Jar O Fireflies")?1600:500);}
    /** The effect(s) the stock action emits. A multi-reference action chooses one at use time. */
    public static List<StarterCatalog.Model> effects(String config){
        var definitions=definitions(config);
        var selected=definitions.size()==1?definitions.getFirst():definitions.get(ThreadLocalRandom.current().nextInt(definitions.size()));
        return List.of(model(selected));
    }
    /** Read-only catalogue data; useful to verify every shipped variant without random selection. */
    public static List<Definition> definitions(String config){
        var definitions=DEFINITIONS.get(config);
        if(definitions==null)throw new IllegalArgumentException("Unsupported animated item");
        return definitions;
    }
    private static StarterCatalog.Model model(Definition definition){
        return switch(definition.effect()) {
            case "Basic" -> new StarterCatalog.Model("Basic",Map.of(
                "Attach to Target",false,
                "Lifespan",6000,
                "Model",new StarterCatalog.Model("world/dynamic/fireworks/fx_fire.dat",Map.of(
                    "Colorization",definition.color(),
                    "Model",definition.size()))));
            case "Player/Release Fireflies" -> new StarterCatalog.Model("Player/Release Fireflies",
                Map.of("Colorization",definition.color()));
            default -> throw new IllegalArgumentException("Unsupported animated item effect");
        };
    }
    private static Map<String,List<Definition>> load(){
        var grouped=new LinkedHashMap<String,List<Definition>>();
        try(var stream=AnimatedItems.class.getResourceAsStream("/seeds/animated-items.tsv")){
            if(stream==null)throw new IOException("Missing animated item mapping");
            for(String line:new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().toList()){
                if(line.isBlank()||line.startsWith("#"))continue;
                var fields=line.split("\t",-1);
                if(fields.length!=4)throw new IOException("Invalid animated item mapping");
                if(fields[0].contains("/Parts/"))continue; // Abstract authoring bases are not playable items.
                var definition=new Definition(fields[1],Integer.parseInt(fields[3]),fields[2]);
                var definitions=grouped.computeIfAbsent(fields[0],ignored->new ArrayList<>());
                if(definitions.contains(definition))throw new IOException("Duplicate animated item mapping");
                definitions.add(definition);
            }
            var result=new LinkedHashMap<String,List<Definition>>();
            for(var entry:grouped.entrySet()){
                var definitions=List.copyOf(entry.getValue());
                if(definitions.getFirst().effect().equals("Basic")&&definitions.size()!=1)
                    throw new IOException("Multiple firework effect references");
                result.put(entry.getKey(),definitions);
            }
            return Map.copyOf(result);
        }catch(IOException|RuntimeException failure){throw new ExceptionInInitializerError(failure);}
    }
}
