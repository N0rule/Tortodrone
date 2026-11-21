package org.tortodrone.core.world;

import java.io.IOException;
import java.util.Objects;

/** Process-local scene source installed by the composition root; protocol never reaches into world. */
public final class SceneSources {
    private static volatile SceneSource current;
    private SceneSources() { }
    public static void install(SceneSource source){current=Objects.requireNonNull(source);}
    public static SceneSource current()throws IOException{
        SceneSource source=current;
        if(source==null)throw new IOException("Scene source is not installed");
        return source;
    }
}
