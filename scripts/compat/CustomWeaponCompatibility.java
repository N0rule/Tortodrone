import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import com.threerings.config.*;
import com.threerings.config.dist.data.*;
import com.threerings.opengl.model.config.ModelConfig;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.projectx.item.data.*;
import com.threerings.tudey.data.TudeySceneModel;

/** Read-only retail delivery investigation; all prototype definitions exist only in this JVM. */
public final class CustomWeaponCompatibility {
    private static final String BASE="Weapon/Handgun/Slicer/Mandolin";

    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Path game=Path.of(args[0]);
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        long version=com.threerings.projectx.util.a.rs();
        check(version>0&&!com.threerings.projectx.util.a.aaQ(),"installed release disables developer mode");
        verifyDirectorGate(game);
        System.out.println("RETAIL build="+version+": native distributed-config director is developer-only");

        var base=manager.d(ItemConfig.class,BASE);
        check(base!=null&&base.w(manager) instanceof ItemConfig.Weapon,"retained Mandolin weapon definition");
        var weapon=(ItemConfig.Weapon)base.w(manager);
        check(weapon.attackValues.length>0,"retained attack stats");
        var originalModel=weapon.model;
        int originalDamage=weapon.attackValues[0].value,originalCharge=weapon.chargeTime;
        String missing="Weapon/Handgun/Custom/Probe Mandolin";
        check(manager.d(ItemConfig.class,missing)==null,"new name is absent in installed configs");
        var absent=roundTrip(new LevelItem(-2000000000,new ConfigReference<ItemConfig>(missing)));
        check(absent.w(manager)==ItemConfig.bvD,"new inventory definition falls back rather than becoming a weapon");
        var alias=roundTrip(new LevelItem(-1999999999,new ConfigReference<ItemConfig>(BASE)));
        check(alias.w(manager)==weapon&&!alias.o(new LevelItem(base.getConfigId(),new ConfigReference<ItemConfig>(BASE))),"fresh ID is accepted but still resolves the original model/stats");
        System.out.println("ITEM: unknown name uses fallback; a fresh ID with the stock name retains stock definition");

        // Reproduce deserialized scene groups before normal scene initialization. Never mutate
        // the application's root manager to make a scene-only injection appear to work.
        var scene=new TudeySceneModel();var child=scene.getConfigManager();
        var group=new ConfigGroup<ItemConfig>(ItemConfig.class);
        var sceneConfig=prototype(weapon,missing,-2000000000,"fir",3);
        group.a(sceneConfig);
        var field=ConfigManager.class.getDeclaredField("_groups");field.setAccessible(true);
        @SuppressWarnings("unchecked") var groups=(Map<Class<?>,ConfigGroup<?>>)field.get(child);
        groups.put(ItemConfig.class,group);
        scene=roundTrip(scene);child=scene.getConfigManager();
        check(child.bd(ItemConfig.class)!=null,"native scene stream retains the test item group before initialization");
        scene.init(manager);
        check(child.bd(ItemConfig.class)==null,"scene initialization discards the unsupported item config group");
        check(manager.d(ItemConfig.class,missing)==null&&child.d(ItemConfig.class,missing)==null,"scene payload does not install a root inventory definition");
        System.out.println("SCENE: embedded ItemConfig group is discarded; root inventory lookup remains unchanged");

        // Positive control: the original ConfigEntry codec and updater can carry a distinct
        // cloned weapon. Calling the updater here is explicit test harness work, not proof
        // that the retail application subscribes or enables this developer-only path.
        var configs=new DConfigObject();var prototypes=new ArrayList<ItemConfig>();int number=0;
        for(String family:List.of("fir","fre","poi","sho"))for(int rank=3;rank<=5;rank++) {
            String name="Weapon/Handgun/Custom/Probe Mandolin "+family+" "+rank;
            int id=-1999999900+number++;
            check(manager.a(ItemConfig.class,id)==null,"prototype ID does not shadow installed content");
            var config=prototype(weapon,name,id,family,rank);
            var model=((ItemConfig.Weapon)config.implementation).model;
            check(manager.a(ModelConfig.class,model)!=null,"retained elemental model loads");
            prototypes.add(config);
        }
        configs.added=new com.threerings.presents.dobj.DSet<>(prototypes.stream().map(ConfigEntry::new).toList());
        var received=roundTrip(configs);
        check(received.added.size()==12&&manager.d(ItemConfig.class,prototypes.getFirst().getName())==null,"decoding DConfigObject alone does not install definitions");
        new com.threerings.config.dist.b.a(manager).b(received);
        for(var expected:prototypes) {
            var item=roundTrip(new LevelItem(expected.getConfigId(),new ConfigReference<ItemConfig>(expected.getName())));
            check(item.w(manager) instanceof ItemConfig.Weapon,"explicit updater installs usable weapon definition");
            var actual=(ItemConfig.Weapon)item.w(manager);var wanted=(ItemConfig.Weapon)expected.implementation;
            check(actual.model.equals(wanted.model)&&actual.chargeTime==originalCharge+100&&actual.attackValues[0].value==originalDamage+1,"prototype model and different stats survive native config delivery");
            check(Arrays.equals(actual.attacks,weapon.attacks)&&Objects.equals(actual.chargedAttack,weapon.chargedAttack),"prototype retains Mandolin attack references");
        }
        check(weapon.model.equals(originalModel)&&weapon.chargeTime==originalCharge&&weapon.attackValues[0].value==originalDamage,"base Mandolin remains unchanged");
        System.out.println("CONTROL: native ConfigEntry roundtrip + explicitly invoked updater accepts 12 distinct model/stat prototypes");
        System.out.println("PASS: normal retail delivery is unavailable through DConfig or scene groups; prototype definitions work only after explicit harness installation. No weapon shipped or gameplay/visual parity claimed.");
    }

    private static ItemConfig prototype(ItemConfig.Weapon base,String name,int id,String family,int rank) {
        var weapon=(ItemConfig.Weapon)base.clone();
        weapon.model=new ConfigReference<ModelConfig>("item/weapon/handgun/orbit/model_"+family+"_r"+rank+".dat");
        // Deliberately arbitrary, visible test changes; these are not recovered elemental tuning.
        weapon.chargeTime=base.chargeTime+100;weapon.attackValues[0].value=base.attackValues[0].value+1;
        var config=new ItemConfig();config.setName(name);config.setConfigId(id);config.implementation=weapon;
        return config;
    }

    @SuppressWarnings("unchecked") private static <T> T roundTrip(T value)throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var out=new com.threerings.io.w(bytes)){out.bf(value);}
        try(var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()))) {
            T result=(T)in.qe();check(in.available()==0,"exact native stream framing");return result;
        }
    }

    private static void verifyDirectorGate(Path game)throws Exception {
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javap").toString(),"-p","-c","-classpath",game.resolve("code/projectx-pcode.jar").toString(),"com.threerings.projectx.client.ProjectXApp").redirectErrorStream(true).start();
        String code=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        check(process.waitFor()==0,"installed ProjectXApp bytecode is readable");
        var gate=Pattern.compile("invokestatic[^\\n]+com/threerings/projectx/util/a\\.aaQ:\\(\\)Z\\s+\\d+: ifeq\\s+(\\d+)\\s+\\d+: aload_0\\s+(\\d+): new[^\\n]+com/threerings/config/dist/a/a\\s").matcher(code);
        check(gate.find()&&Integer.parseInt(gate.group(1))>Integer.parseInt(gate.group(2)),"installed app guards distributed-config director construction with developer flag");
    }

    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
