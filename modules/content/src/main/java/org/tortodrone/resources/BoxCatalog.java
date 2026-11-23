package org.tortodrone.resources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.loot.BoxDefinition;

/** Explicit native key bindings and fail-closed, readable loot definitions. */
public final class BoxCatalog {
    private final Map<String,BoxDefinition> boxes;
    private final Map<String,String> tables;
    BoxCatalog(Map<String,BoxDefinition> boxes){this(boxes,Map.of());}
    BoxCatalog(Map<String,BoxDefinition> boxes,Map<String,String> tables){this.boxes=Map.copyOf(boxes);this.tables=Map.copyOf(tables);}
    public static BoxCatalog empty(){return new BoxCatalog(Map.of());}
    public BoxDefinition find(String config){return boxes.get(config);}
    public String spawnTable(String config){return tables.get(config);}
    public Collection<BoxDefinition> entries(){return boxes.values();}
    /** Read the box bindings and readable loot YAML; the reader owns format and validation. */
    public static BoxCatalog load(Path content,ItemCatalog items)throws IOException {
        return BoxCatalogReader.load(content,items);
    }
}
