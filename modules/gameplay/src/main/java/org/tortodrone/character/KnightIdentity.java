package org.tortodrone.character;

import java.util.Locale;
import org.tortodrone.core.character.Knight;

/** Stock knight identity policy: the name rules and appearance choices the roster commit,
 *  auction and town-name checks share.
 *
 * <p>Stateless, so a caller can refuse a name or appearance before taking the roster lock
 *  and the same rules stay available without a {@link CharacterStore} instance. */
public final class KnightIdentity {
    private KnightIdentity() {}

    /** Stock knight-name policy: 4-18 ASCII letters, at most two single hyphens between
     *  two-letter parts, no run of more than two identical letters. */
    public static String nameError(String name) {
        if (name == null || name.length() < 4) return "Knight names need at least four letters";
        if (name.length() > 18) return "Knight names may contain at most eighteen characters";
        int hyphens = 0, letters = 0, repeats = 0;
        char previous = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') c = (char) (c + ('a' - 'A'));
            if (c >= 'a' && c <= 'z') {
                repeats = c == previous ? repeats + 1 : 1;
                if (repeats > 2) return "Knight names cannot repeat a letter more than twice";
                letters++;
            } else if (c == '-') {
                if (++hyphens > 2 || letters < 2 || i + 2 >= name.length())
                    return "Use single hyphens between parts with at least two letters";
                letters = 0;
            } else return "Use only letters and single hyphens in the knight name";
            previous = c;
        }
        return null;
    }

    /** Creation/rename appearance must be one the stock client can render. */
    static void validate(Knight knight) {
        if (nameError(knight.name()) != null
                || !asset(knight.helm(), false)
                || !asset(knight.armor(), false)
                || !asset(knight.helmAccessory(), true)
                || !asset(knight.armorAccessory(), true)
                || !asset(knight.costume(), false)
                || knight.color() < 0
                || knight.color() > 255
                || knight.height() < 0
                || knight.height() >= 5
                || knight.eye() < 0
                || knight.eye() >= 47) {
            throw new IllegalArgumentException("Invalid character choices");
        }
    }

    private static boolean asset(String value, boolean optional) {
        if (value == null) return optional;
        if (value.isEmpty()) return optional;
        return value.length() <= 256
                && value.chars().noneMatch(c -> Character.isISOControl(c))
                && !value.contains("..")
                && !value.contains("\\")
                && !value.startsWith("/");
    }

    /** Case-insensitive comparison form; the unique index stores the same key. */
    static String canonical(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
