import java.io.*;
import java.util.*;
import org.tortodrone.protocol.*;
import org.tortodrone.character.KnightObjective;
import com.threerings.presents.net.*;
import com.threerings.presents.dobj.*;
import com.threerings.projectx.data.*;
import com.threerings.projectx.config.*;

/** Stock Objective/ObjectiveConfig/Persistence reader, repeated mappings and native set application. */
public final class ObjectiveCompatibility {
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var bytes=new ByteArrayOutputStream();var codec=new SessionCodec();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic-session","StoryUser"));
        append(bytes,codec.player((short)3,1001,1,"StoryUser"));
        append(bytes,codec.knightObjectives(1001,Map.of("findRhendon",new KnightObjective.State(0,"findRhendon","m.find_rhendon"))));
        append(bytes,codec.knightObjectives(1001,Map.of("findRhendon",new KnightObjective.State(0,"findRhendon","m.find_rhendon"))));
        append(bytes,codec.knightObjectives(1001,Map.of()));
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var player=(PlayerObject)((ObjectResponse)in.qe()).yk();
        for(int i=0;i<2;i++){
            var event=((EventNotification)in.qe()).yj();event.b(player);
            if(player.objectives.size()!=1)throw new AssertionError("Native objective was not applied");
            var objective=player.objectives.iterator().next();var config=(ObjectiveConfig.Simple)objective.config;
            if(objective.jz()!=0||objective.persistence!=Persistence.KNIGHT||!config.text.equals("m.find_rhendon")||config.childX!=.5f||config.childY!=.5f||config.parentX!=.5f||config.parentY!=.5f||config.userInterface!=null)throw new AssertionError("Native objective fields differ");
            var name=Objective.class.getDeclaredField("_name");name.setAccessible(true);if(!name.get(objective).equals("findRhendon"))throw new AssertionError("Objective identity lost");
        }
        ((EventNotification)in.qe()).yj().b(player);
        if(!player.objectives.isEmpty()||in.available()!=0)throw new AssertionError("Objective clear/trailing bytes");
        System.out.println("PASS: stock Objective/Simple config/Persistence, exact message/layout defaults, stable index, repeated mappings and set clearing");
    }
}
