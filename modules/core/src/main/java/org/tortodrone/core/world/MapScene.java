package org.tortodrone.core.world;

import java.util.Objects;

/** Immutable scene template snapshot shared by loaders, world instances and the wire codec. */
public record MapScene(
        int id,
        int zone,
        String zoneName,
        String name,
        String image,
        String kind,
        int version,
        float spawnX,
        float spawnY,
        float spawnRotation,
        int instances,
        CollisionMap collision,
        byte[] layout,
        ScenePortals portals,
        SceneContent content,
        SceneCollision staticCollision,
        MapCollision exactCollision,
        MonsterCatalog monsters) {
    public MapScene(
            int id,
            int zone,
            String zoneName,
            String name,
            String image,
            String kind,
            int version,
            float spawnX,
            float spawnY,
            float spawnRotation,
            int instances,
            CollisionMap collision,
            byte[] layout,
            ScenePortals portals,
            SceneContent content,
            SceneCollision staticCollision) {
        this(
                id,
                zone,
                zoneName,
                name,
                image,
                kind,
                version,
                spawnX,
                spawnY,
                spawnRotation,
                instances,
                collision,
                layout,
                portals,
                content,
                staticCollision,
                null,
                MonsterCatalog.empty());
    }

    public MapScene(
            int id,
            int zone,
            String zoneName,
            String name,
            String image,
            String kind,
            int version,
            float spawnX,
            float spawnY,
            float spawnRotation,
            int instances,
            CollisionMap collision,
            byte[] layout,
            ScenePortals portals,
            SceneContent content) {
        this(
                id,
                zone,
                zoneName,
                name,
                image,
                kind,
                version,
                spawnX,
                spawnY,
                spawnRotation,
                instances,
                collision,
                layout,
                portals,
                content,
                SceneCollision.empty(),
                null,
                MonsterCatalog.empty());
    }

    public MapScene(
            int id,
            int zone,
            String zoneName,
            String name,
            String image,
            String kind,
            int version,
            float spawnX,
            float spawnY,
            float spawnRotation,
            int instances,
            CollisionMap collision,
            byte[] layout,
            ScenePortals portals) {
        this(
                id,
                zone,
                zoneName,
                name,
                image,
                kind,
                version,
                spawnX,
                spawnY,
                spawnRotation,
                instances,
                collision,
                layout,
                portals,
                SceneContent.empty(),
                SceneCollision.empty(),
                null,
                MonsterCatalog.empty());
    }

    public MapScene {
        layout = layout.clone();
        Objects.requireNonNull(monsters, "monsters");
    }

    @Override
    public byte[] layout() {
        return layout.clone();
    }

    public boolean town() {
        return kind.equals("town");
    }
    /** TownSummary names a town-bundle key; ZoneSummary carries a qualified Name. */
    public String townName() {
        return zoneName.startsWith("%town:") ? zoneName.substring(6) : zoneName;
    }
    // The retail Training controller has no Spark panel. Use the existing level HUD
    // for our once-per-entry Emergency Revive and subsequent paid revival flow.
    public String configType() {
        return "com.threerings.projectx." + (town() ? "town.data.TownSceneConfig" : "dungeon.data.LevelSceneConfig");
    }
}
