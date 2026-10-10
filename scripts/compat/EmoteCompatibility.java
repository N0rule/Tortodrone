import com.threerings.config.ConfigManager;
import com.threerings.config.ConfigReference;
import com.threerings.projectx.config.EmoteConfig;
import com.threerings.projectx.data.ProjectXUserMessage;
import com.threerings.math.Transform3D;
import com.threerings.opengl.model.config.ActionConfig;
import com.threerings.opengl.model.config.AnimationConfig;
import java.nio.file.Path;
import java.util.Arrays;
import java.io.*;
import org.tortodrone.world.PlayerEmotes;

/** Compares the complete server emote catalog with installed stock client configs. */
public final class EmoteCompatibility {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: EmoteCompatibility /path/to/Spiral Knights");
        Path game = Path.of(args[0]);
        Object resource = Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga", String.class).invoke(resource, game.resolve("rsrc").toString());
        ConfigManager manager = (ConfigManager) ConfigManager.class.getConstructor(
            Class.forName("com.threerings.b.f"), com.threerings.util.R.class, String.class)
            .newInstance(resource, null, "config/");
        manager.init();
        var all = manager.bd(EmoteConfig.class).kK();
        var animated = EmoteConfig.d(manager);
        int configCount = 0;
        for (EmoteConfig ignored : all) configCount++;
        if (configCount != 68 || PlayerEmotes.all().size() != configCount)
            throw new AssertionError("Emote catalog count mismatch: installed=" + configCount +
                " server=" + PlayerEmotes.all().size());
        int animations = 0;
        for (EmoteConfig config : all) {
            var descriptor = PlayerEmotes.find(config.getName());
            if (descriptor == null) throw new AssertionError("Missing descriptor: " + config.getName());
            int activity = config.animation == null ? 0 : 9 + config.e(manager);
            int endActivity = config.endAnimation == null ? 0 : 9 + animated.length + config.e(manager);
            int[] keys = config.getDefaultKeys();
            if (descriptor.activity() != activity || descriptor.duration() != config.duration ||
                descriptor.endActivity() != endActivity || descriptor.endDuration() != config.endDuration ||
                descriptor.allowsMovement() != config.allowsMovement || descriptor.targetable() != config.targetable ||
                descriptor.balloonless() != config.balloonless || descriptor.spawnsTransient() != config.spawnsTransient ||
                descriptor.defaultKey() != config.defaultKey || keys.length != 3 ||
                keys[0] != descriptor.defaultKey() || keys[1] != -1 || keys[2] != -1)
                throw new AssertionError("Config mismatch for " + config.getName() + ": " + descriptor);
            if (config.animation != null) animations++;
            System.out.printf("OK %s activity=%d duration=%d endActivity=%d endDuration=%d move=%s target=%s balloonless=%s transient=%s key=%d animation=%s endAnimation=%s keys=%s%n",
                config.getName(), activity, config.duration, endActivity, config.endDuration,
                config.allowsMovement, config.targetable, config.balloonless, config.spawnsTransient,
                config.defaultKey, config.animation == null ? "-" : config.animation.getName(),
                config.endAnimation == null ? "-" : config.endAnimation.getName(),
                Arrays.toString(keys));
        }
        if (animations != 20 || animated.length != animations) throw new AssertionError("Expected 20 animated emotes");
        // These stock animation clips carry their own local command visuals via SpawnTransient.
        assertCommandTransient(manager, "Attack", "particle/emoticons/fx_command_burst.dat", "Attack", "particle/emoticons/fx_attack.dat");
        assertCommandTransient(manager, "Follow", "particle/emoticons/fx_command_follow.dat", null, null);
        assertCommandTransient(manager, "Helpme", "particle/emoticons/fx_command_burst.dat", "Help", "particle/emoticons/fx_help.dat");
        assertCommandTransient(manager, "Fallback", "particle/emoticons/fx_command_burst.dat", "Fall Back", "particle/emoticons/fx_fallback.dat");
        if (ProjectXUserMessage.c((byte) 5) == false || ProjectXUserMessage.c((byte) 6) == false ||
            ProjectXUserMessage.c((byte) 2)) throw new AssertionError("Installed ProjectX chat modes changed");
        messages();
        System.out.println("PASS: 68 descriptors match installed configs; 20 animated; ProjectX message modes 5/6 are special.");
    }
    private static void messages()throws Exception {
        var routing=new org.tortodrone.world.SceneChat();
        var received=new java.util.ArrayList<org.tortodrone.world.SceneChat.Message>();
        try(var sender=routing.join(10,1002,"First-Knight",new org.tortodrone.world.SceneChat.RateLimit(),m->{received.add(m);return true;})) {
            var bundle=new com.threerings.util.R("rsrc.i18n").gw("chat");
            var codec=new org.tortodrone.protocol.SessionCodec();var bytes=new ByteArrayOutputStream();
            byte[] handshake=org.tortodrone.protocol.HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef");
            bytes.write(handshake,4,handshake.length-4);
            byte[] auth=org.tortodrone.protocol.HandshakeCodec.authenticationResponse((short)2,true,"fixture","Alice");
            bytes.write(auth,4,auth.length-4);
            for(var descriptor:PlayerEmotes.all()) {
                for(String target:descriptor.targetable()?new String[]{null,"Second-Knight"}:new String[]{null}) {
                    routing.emote(sender,descriptor,target);
                    if(descriptor.name().equals("Sit")){if(!received.isEmpty())throw new AssertionError("Sit must be silent");continue;}
                    var message=received.removeLast();
                    String key="m."+(descriptor.name().equals("Herp")?"derp":descriptor.name().toLowerCase(java.util.Locale.ROOT))+"_format"+(target==null?"":"_targeted");
                    String expected=target==null?com.threerings.util.Q.q(key,"First-Knight"):
                        com.threerings.util.Q.b(key,"First-Knight","Second-Knight");
                    if(!message.text().equals(expected)||!bundle.cR(expected).contains("First-Knight"))
                        throw new AssertionError("Stock localized emote mismatch: "+descriptor.name());
                    byte[] frame=codec.chat(10,message);bytes.write(frame,4,frame.length-4);
                }
            }
            var input=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));input.qe();input.qe();int count=0;
            while(input.available()>0) {
                var event=(com.threerings.presents.dobj.MessageEvent)((com.threerings.presents.net.EventNotification)input.qe()).yj();
                var message=(ProjectXUserMessage)event.yd()[0];
                if(!"chat".equals(message.bundle)||message.mC()!=null||!bundle.cR(message.message).contains("First-Knight"))
                    throw new AssertionError("Stock emote wire/localization mismatch");
                count++;
            }
            System.out.println("PASS: stock decoder and localization render "+count+" untargeted/targeted emote messages with native bubble modes.");
        }
    }

    private static void assertCommandTransient(ConfigManager manager, String name, String model,
            String bubbleColor, String variant) {
        EmoteConfig emote = manager.d(EmoteConfig.class, name);
        if (emote == null || emote.animation == null)
            throw new AssertionError("Missing stock animated emote: " + name);
        AnimationConfig animation = manager.a(AnimationConfig.class, emote.animation);
        if (animation == null || !(animation.implementation instanceof AnimationConfig.Imported imported) ||
            imported.actions == null || imported.actions.length != 1)
            throw new AssertionError("Expected one imported stock animation action for " + name);
        AnimationConfig.FrameAction frame = imported.actions[0];
        if (frame.frame != 0f || !(frame.action instanceof ActionConfig.SpawnTransient transientAction) ||
            !transientAction.moveWithOrigin || transientAction.modifyAnimSpeed ||
            !"%ROOT%".equals(transientAction.node) || !new Transform3D().equals(transientAction.transform) ||
            transientAction.model == null || !model.equals(transientAction.model.getName()))
            throw new AssertionError("Unexpected stock transient animation for " + name + ": " + frame);
        var args = transientAction.model.kW();
        if (bubbleColor == null) {
            if (!args.isEmpty()) throw new AssertionError("Unexpected stock command model args for " + name + ": " + args);
        } else {
            Object actualVariant = args.get("Variant");
            if (!bubbleColor.equals(args.get("Bubble - Color")) ||
                !(actualVariant instanceof ConfigReference<?> ref) || !variant.equals(ref.getName()) || args.size() != 2)
                throw new AssertionError("Unexpected stock command model variants for " + name + ": " + args);
        }
        System.out.printf("OK %s local transient frame=0 node=%%ROOT%% model=%s args=%s%n", name, model, args);
    }
}
