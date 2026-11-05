import java.nio.file.Path;
import org.tortodrone.resources.ContentManager;

/** Read-only content preflight; does not start a server or establish gameplay acceptance. */
public final class CheckOpeningDeployment {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: java -cp build/tortodrone.jar scripts/CheckOpeningDeployment.java build");
        var route=ContentManager.load(Path.of(args[0])).createScenes().tutorial();
        if(route==null)throw new IllegalStateException("Opening route unavailable: import the verified Crash Site 398 / Rescue Camp 217 pair; see docs/content.md");
        System.out.println("PASS: verified opening content in "+Path.of(args[0]).toAbsolutePath().normalize());
        System.out.println("Crash Site: scene="+route.source().id()+", version="+route.source().version()+", entrance="+org.tortodrone.world.TutorialRoute.ENTRANCE+", spawn="+route.source().spawnX()+","+route.source().spawnY());
        System.out.println("Rescue Camp: scene="+route.camp().id()+", version="+route.camp().version()+", entrance="+route.entrance().id()+", spawn="+route.camp().spawnX()+","+route.camp().spawnY());
        System.out.println("CONTENT PREFLIGHT ONLY: follow the manual playthrough procedure to verify gameplay and reconnect.");
    }
}
