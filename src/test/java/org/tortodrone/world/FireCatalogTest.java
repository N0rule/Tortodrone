package org.tortodrone.world;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

public final class FireCatalogTest {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("tortodrone-fire-catalog-");
        try {
            var bulletModel=new StarterCatalog.Model("Projectile/Bullet",Map.of("Shot",new StarterCatalog.Model("Projectile/Blaster",Map.of("Speed",8.5f))));
            var detonation=new FireCatalog.Action(FireCatalog.Kind.UNSUPPORTED,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(),"DamageRegion");
            var actor=new FireCatalog.ActorSpec("BULLET",bulletModel,1200,8.5f,-1,4,0.25f,0,0.4f,detonation);
            var orbit=new FireCatalog.OrbitSpec(1.25f,0.33f,0.2f,8f,750,1700,"NONE",true,null,null,null);
            var orbital=new FireCatalog.ActorSpec("ORBITAL_BULLET",bulletModel,1700,0.33f,-1,4,0,0,0.4f,null,orbit);
            var compoundOrbit=new FireCatalog.OrbitSpec(2f,-1.5f,0.4f,8f,750,1700,"NONE",false,0.667f,-1.5f,0f);
            var compoundActor=new FireCatalog.ActorSpec("COMPOUND_ORBITAL_BULLET",bulletModel,1700,-1.5f,-1,4,0,0,0.4f,null,compoundOrbit);
            var modifiedSpawn=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,orbital,"Source","","",1,0,0.1f,0,true,0,0,0,0,false,List.of(),"","Color",List.of("Red","Blue"));
            var orbitalEmission=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,compoundActor,"Source","","",1,0,0.1f,0,true,0,0,0,0,false,List.of(),"");
            var emission=new FireCatalog.Action(FireCatalog.Kind.COMPOUND,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(modifiedSpawn,orbitalEmission),"");
            var compound=new FireCatalog.Action(FireCatalog.Kind.COMPOUND,null,null,"","","",0,0,0,0,false,0,0,0,0,true,List.of(emission),"");
            var ref=new FireCatalog.FireRef(new StarterCatalog.Model("Fire/Shot",Map.of("Damage",7)),25,100,false,0,0,0,compound,null);
            var attackArgs=Map.<String,Object>of("Tier",2,"Nested",new FireCatalog.DataValue("StockValue",Map.of("absent",FireCatalog.NullValue.INSTANCE,
                "kind",new FireCatalog.EnumValue("StockKind","PIERCING"))),"Values",new FireCatalog.ListValue("java.lang.Float",List.of(1.25f,2f)));
            var attack=new FireCatalog.AttackFire("Weapon/Gun/Test",292,new StarterCatalog.Model("Attack/Test",attackArgs),1f,List.of(ref));
            Path file=root.resolve("fires.json");FireCatalog.write(file,List.of(attack),Map.of("DamageRegion",1));
            FireCatalog loaded=FireCatalog.load(file);
            var result=loaded.find("weapon/gun/test",292);
            check(result!=null&&result.equals(attack),"attack refs, arguments, nested projectile and action metadata round trip");
            check(loaded.unsupportedTypes().get("DamageRegion")==1,"unsupported action types are retained and reported");
            Path deployment=root.resolve("server");Files.createDirectories(deployment.resolve("defaults/combat"));Files.copy(file,deployment.resolve("defaults/combat/fires.json"));
            check(FireCatalog.deployment(deployment.resolve("data.sqlite")).find("Weapon/Gun/Test",292)!=null,"defaults fire catalogue resolution");
            Path malformed=root.resolve("bad.json");Files.writeString(malformed,"{\"format\":\"tortodrone-fires\",\"version\":1,\"entries\":[],\"unsupported\":{\"bad\":-1}}");
            try{FireCatalog.load(malformed);throw new AssertionError("Negative unsupported count accepted");}catch(java.io.IOException expected){}
            check(result.fireRefs().getFirst().action().children().getFirst().children().getFirst().values().equals(List.of("Red","Blue")),"prespawned actor argument modifications round trip");
            check(result.fireRefs().getFirst().action().children().getFirst().children().getFirst().actor().orbit().equals(orbit),"orbital actor metadata round trips");
            check(result.fireRefs().getFirst().action().children().getFirst().children().get(1).actor().orbit().equals(compoundOrbit),"compound orbital metadata round trips");
            var oldAction=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,actor,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
            var oldRef=new FireCatalog.FireRef(new StarterCatalog.Model("Fire/Legacy",Map.of()),0,0,true,0,0,0,oldAction,null);
            Path legacy=root.resolve("legacy.json");FireCatalog.write(legacy,List.of(new FireCatalog.AttackFire("Weapon/Legacy",292,new StarterCatalog.Model("Attack/Legacy",Map.of()),1,List.of(oldRef))),Map.of());
            Object oldDocument=legacyFields(org.tortodrone.resources.Json.parse(Files.readString(legacy)));
            @SuppressWarnings("unchecked") var oldRoot=(Map<String,Object>)oldDocument;oldRoot.put("version",1);
            Files.writeString(legacy,org.tortodrone.resources.Json.stringify(oldRoot));
            check(FireCatalog.load(legacy).find("Weapon/Legacy",292).fireRefs().getFirst().action().actor().equals(actor),"previous fire format remains readable without orbital or argument-change fields");
            oldRoot.put("version",2);Files.writeString(legacy,org.tortodrone.resources.Json.stringify(oldRoot));
            try{FireCatalog.load(legacy);throw new AssertionError("Missing v2 fields accepted");}catch(java.io.IOException expected){}
            System.out.println("PASS: bounded fire graph JSON, argument-preserving reference lookup, orbital metadata, unsupported action reporting and deployment resolution");
        } finally {
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
    private static Object legacyFields(Object value){
        if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();for(var entry:map.entrySet())if(!Set.of("parameter","values","orbit").contains(entry.getKey()))result.put((String)entry.getKey(),legacyFields(entry.getValue()));return result;}
        if(value instanceof List<?> list)return list.stream().map(FireCatalogTest::legacyFields).toList();
        return value;
    }
}
