package org.offeringprotocol.odp.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** A JSON tree value independent of the selected JSON provider. */
public abstract class OdpJsonNode implements Iterable<OdpJsonNode> {
    public abstract OdpJsonNode at(String pointer);

    public abstract boolean asBoolean(boolean defaultValue);

    public abstract int asInt();

    public abstract String asString();

    public abstract OdpJsonNode deepCopy();

    public abstract Set<String> fieldNames();

    public abstract void forEachEntry(BiConsumer<String, OdpJsonNode> consumer);

    public abstract OdpJsonNode get(String name);

    public abstract boolean has(String name);

    public abstract boolean isArray();

    public abstract boolean isEmpty();

    public abstract boolean isNull();

    public abstract boolean isObject();

    public abstract boolean isString();

    @Override
    public abstract Iterator<OdpJsonNode> iterator();

    public abstract OdpJsonNode path(String name);

    public abstract OdpJsonNode put(String name, String value);

    public abstract OdpJsonNode putObject(String name);

    public abstract OdpJsonNode remove(String name);

    public abstract void remove(Collection<String> names);

    public abstract boolean removeIf(Predicate<OdpJsonNode> predicate);

    public abstract OdpJsonNode set(String name, OdpJsonNode value);

    public abstract int size();
}
