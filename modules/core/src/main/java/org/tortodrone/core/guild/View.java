package org.tortodrone.core.guild;

import java.util.List;

/** A guild row plus its roster, the shape the wire object is built from. */
public record View(Guild guild, List<Member> members, List<Integer> expansions) {
    public View {
        members = List.copyOf(members);
        expansions = List.copyOf(expansions);
    }
}
