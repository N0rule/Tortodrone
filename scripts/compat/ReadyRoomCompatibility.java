import java.io.*;
import org.tortodrone.protocol.*;
import org.tortodrone.world.ReadyRoom;
import com.threerings.presents.net.EventNotification;
import com.threerings.presents.dobj.InvocationResponseEvent;
import com.threerings.projectx.data.ReadyRoomSceneConfig;
import com.threerings.whirled.zone.data.ZoneSummary;

/** Read-only installed-client oracle; no game process, account or preferences required. */
public final class ReadyRoomCompatibility {
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var bytes=new ByteArrayOutputStream();var codec=new SessionCodec();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic-session","RoomUser"));
        append(bytes,codec.readyRoomMove(1001,(short)7));
        append(bytes,codec.readyRoomMove(1002,(short)8));
        append(bytes,codec.player((short)3,1001,1,"RoomUser"));
        append(bytes,codec.readyRoomLocation(1001));
        append(bytes,codec.forceDestination(1001,(short)4,ReadyRoom.ZONE,ReadyRoom.SCENE));
        var input=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));input.ql();input.ql();
        for(int request=7;request<=8;request++){
            var event=(InvocationResponseEvent)((EventNotification)input.ql()).yn();
            var values=event.yh();
            if(event.yj()!=request||event.yg()!=2||values.length!=3||!values[0].equals(0)
                    ||!(values[1] instanceof ReadyRoomSceneConfig))throw new AssertionError("Native callback mismatch");
            var zone=(ZoneSummary)values[2];
            if(zone.zoneId!=83886080||zone.zoneId!=ReadyRoom.ZONE||!zone.name.toString().equals("")||zone.scenes.length!=1)
                throw new AssertionError("Native zone summary mismatch");
            var scene=zone.scenes[0];
            if(scene.sceneId!=Integer.MAX_VALUE||!scene.name.equals("~")||scene.neighbors.length!=0||scene.neighborDirs.length!=0)
                throw new AssertionError("Native scene summary mismatch");
        }
        var player=(com.threerings.projectx.data.PlayerObject)((com.threerings.presents.net.ObjectResponse)input.ql()).yo();
        var location=(com.threerings.presents.dobj.CompoundEvent)((EventNotification)input.ql()).yn();
        for(var child:location.xI())child.b(player);
        if(player.zoneId!=ReadyRoom.ZONE||player.instanceId!=0||player.location!=null||player.zoneGeneralOid!=0||player.zoneTradeOid!=0)
            throw new AssertionError("Ready Room presence leaked a world place/channel");
        var forced=(com.threerings.presents.dobj.InvocationNotificationEvent)((EventNotification)input.ql()).yn();
        if(forced.yf()!=4||forced.yg()!=1||!java.util.Arrays.equals(forced.yh(),new Object[]{83886080,Integer.MAX_VALUE}))
            throw new AssertionError("Forced destination mismatch");
        if(input.available()!=0)throw new AssertionError("Unexpected scene payload");
        System.out.println("PASS: stock reader decodes Ready Room method 2, place 0, exact zone/scene summaries, and no scene payload");
    }
}
