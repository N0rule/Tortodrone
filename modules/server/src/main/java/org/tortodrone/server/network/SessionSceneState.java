package org.tortodrone.server.network;

import java.util.Map;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.SceneStatus;

/** The last scene-derived state published for this connection: occupant client and role maps,
 *  the health/status pair and the arsenal equip policy. Publication writes and reads stay under
 *  the session monitor so a stale delta cannot rewind a newer publication. */
final class SessionSceneState {
    private Map<Integer,Integer> occupantClients=Map.of();
    private Map<Integer,Boolean> occupantRoles=Map.of();
    private SceneHealth.State health;
    private SceneStatus.State status;
    private Boolean arsenal;

    Map<Integer,Integer> occupantClients(){return occupantClients;}
    void occupantClients(Map<Integer,Integer> value){occupantClients=value;}

    Map<Integer,Boolean> occupantRoles(){return occupantRoles;}
    void occupantRoles(Map<Integer,Boolean> value){occupantRoles=value;}

    SceneHealth.State health(){return health;}
    SceneStatus.State status(){return status;}
    void publishedHealthStatus(SceneHealth.State health,SceneStatus.State status){this.health=health;this.status=status;}

    Boolean arsenal(){return arsenal;}
    void arsenal(Boolean value){arsenal=value;}
}
