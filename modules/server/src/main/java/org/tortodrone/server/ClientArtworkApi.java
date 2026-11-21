package org.tortodrone.server;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;

/** Strict reflection boundary for offline artwork only; never a protocol compatibility adapter. */
public final class ClientArtworkApi {
    private ClientArtworkApi() { }

    public static Method method(Class<?> owner, String operation, boolean isStatic, Class<?> result, Class<?>... parameters) throws IOException {
        return unique(owner, operation, isStatic, m -> m.getReturnType() == result && Arrays.equals(m.getParameterTypes(), parameters));
    }

    /** Requires <T extends B> G<T> operation(Class<T>), not an array, raw group or unrelated T. */
    public static Method groupAccessor(Class<?> owner, Class<?> group, Class<?> bound) throws IOException {
        return genericLookup(owner, "exact configuration group", group, bound, false);
    }

    /** Requires <T extends B> T operation(Class<T>, String), not a base-typed lookup. */
    public static Method namedConfig(Class<?> owner, Class<?> bound) throws IOException {
        return genericLookup(owner, "named configuration", null, bound, true);
    }

    private static Method genericLookup(Class<?> owner, String operation, Class<?> group, Class<?> bound, boolean named) throws IOException {
        return unique(owner, operation, false, m -> {
            var variables = m.getTypeParameters();
            if (variables.length != 1 || !Arrays.equals(variables[0].getBounds(), new Type[]{bound})) return false;
            var variable = variables[0];
            var parameters = m.getGenericParameterTypes();
            if (parameters.length != (named ? 2 : 1) || !parameterized(parameters[0], Class.class, variable)) return false;
            return named ? parameters[1] == String.class && m.getGenericReturnType().equals(variable)
                : parameterized(m.getGenericReturnType(), group, variable);
        });
    }

    /** Iterable<T> tied to the group's own type variable; Iterable<Base> is a different operation. */
    public static Method typedValues(Class<?> group, Class<?> bound) throws IOException {
        var variables = group.getTypeParameters();
        if (variables.length != 1 || !Arrays.equals(variables[0].getBounds(), new Type[]{bound}))
            throw new IOException("Unsupported client artwork group type: " + group.getName());
        return unique(group, "typed configuration iteration", false,
            m -> m.getParameterCount() == 0 && parameterized(m.getGenericReturnType(), Iterable.class, variables[0]));
    }

    private static boolean parameterized(Type type, Class<?> raw, Type argument) {
        return type instanceof ParameterizedType p && p.getRawType() == raw
            && Arrays.equals(p.getActualTypeArguments(), new Type[]{argument});
    }

    private static Method unique(Class<?> owner, String operation, boolean isStatic, Predicate<Method> signature) throws IOException {
        var matches = Arrays.stream(owner.getMethods())
            .filter(m -> !m.isBridge() && !m.isSynthetic() && Modifier.isStatic(m.getModifiers()) == isStatic)
            .filter(signature).sorted(Comparator.comparing(Method::toGenericString)).toList();
        if (matches.size() != 1) throw new IOException("Cannot identify client artwork " + operation + " on " + owner.getName()
            + ": expected one checked signature, found " + matches.size() + (matches.isEmpty() ? "" : " " + matches));
        return matches.getFirst();
    }

    public static Object invoke(Method method, Object receiver, Object... arguments) throws IOException {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failed) { throw new IOException("Client artwork operation failed: " + method.toGenericString(), failed.getCause()); }
        catch (ReflectiveOperationException | IllegalArgumentException failed) { throw new IOException("Cannot call client artwork operation: " + method.toGenericString(), failed); }
    }
}
