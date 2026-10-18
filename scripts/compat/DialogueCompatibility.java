import java.util.*;

/** Installed MessageBundle evidence: ConversationDialog always supplies the knight name. */
public final class DialogueCompatibility {
    public static void main(String[] args)throws Exception {
        Locale.setDefault(Locale.ENGLISH);
        var manager=new com.threerings.util.R("rsrc.i18n");var bundle=manager.gw("conversation");
        if(!bundle.f("r.atg_greeter_2a","FixtureKnight").equals("Thanks! I'm off to explore now."))throw new AssertionError("Verified stock dialogue localization changed");
        if(!bundle.cP("~Literal sentence").equals("Literal sentence"))throw new AssertionError("Separate literal decoder changed");
        String actual=bundle.f("~Literal sentence","FixtureKnight");
        if(actual.equals("Literal sentence")||!actual.contains("FixtureKnight"))throw new AssertionError("Conversation literal restriction changed; re-investigate the installed client");
        System.out.println("PASS: verified dialogue localization and installed literal restriction: "+actual);
    }
}
