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
            var emission=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,actor,"Source","","",1,0,0.1f,0,true,0,0,0,0,false,List.of(),"");
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
            System.out.println("PASS: bounded fire graph JSON, argument-preserving reference lookup, unsupported action reporting and deployment resolution");
        } finally {
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
}
