package org.tortodrone.server.network;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.support.TestWire;
import org.tortodrone.world.SceneRegistry;
import static org.tortodrone.support.TestWire.check;

/** A party scene change must encode attribute values polymorphically.
 *
 * <p>The stock client reads {@code AttributeChangedEvent._value} with a class code before the
 * payload. Writing the bare primitive instead desynchronises the object stream: the zone change
 * that leaves the tutorial produced a raw {@code int 1}, which the client read as a SecureResponse
 * class code and then failed with "no registered class metadata" while entering Haven.
 */
public final class PartySceneChangeWireTest {
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));
        var registry=SceneRegistry.create(content);
        var previous=SceneRegistry.system();SceneRegistry.install(registry);
        try {
            var route=registry.tutorial();
            var before=registry.destination(route.source().id(),1);
            var after=registry.destination(route.destination().id(),1);
            int party=4242;
            var wire=new TestWire();
            var in=wire.frame(new SessionCodec(content.items()).partySceneChanges(party,before,after));
            check(wire.type(in).equals(TestWire.NET+"EventNotification"),"party scene change is a native event notification");
            check(in.readShort()==-1,"party scene change is not a response");
            check(wire.type(in).equals(TestWire.DOB+"CompoundEvent"),"party scene change compounds attributes");
            check(in.readInt()==party,"compound targets the party object");
            check(wire.type(in).equals("com.threerings.util.StreamableArrayList"),"compound carries a streamable array list");
            int count=in.readInt();check(count>0,"leaving the tutorial changes party attributes");
            Map<String,Object> values=new LinkedHashMap<>();
            for(int i=0;i<count;i++){
                check(wire.type(in).equals(TestWire.DOB+"AttributeChangedEvent"),"party attribute event");
                check(in.readInt()==party,"attribute targets the party object");
                String name=TestWire.string(in);
                check(name!=null&&!name.isEmpty(),"party attribute name");
                // The native event stores the value with a class code; a bare primitive would
                // consume the next mapping as metadata and fail the stock client.
                values.put(name,wire.object(in));
            }
            TestWire.end(in);
            check(Integer.valueOf(after.map().zone()).equals(values.get("zoneId")),"zone attribute is a boxed Integer");
            check(Integer.valueOf(after.oid()).equals(values.get("instanceId")),"town instance attribute is a boxed Integer");
            check(Boolean.FALSE.equals(values.get("hasPickups")),"pickup flag is a boxed Boolean");
            check(values.containsKey("levelType")&&values.get("levelType") instanceof Integer level&&level==3,"town level type keeps the native enum class code");
            System.out.println("PASS: party scene changes encode every attribute value with its native class code, including the tutorial-to-Haven zone change");
        } finally {SceneRegistry.install(previous);}
    }
}
