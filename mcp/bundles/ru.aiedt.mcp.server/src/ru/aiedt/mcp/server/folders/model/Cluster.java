/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A single virtual folder in the Navigator.
 * <p>
 * A cluster has a name, the collection path it hangs under (for example {@code CommonModule}), an
 * optional multi-line description, a sort order among its siblings, and the list of metadata objects
 * it holds - each held object being kept as its fully qualified name rather than a live reference, so
 * that the cluster survives serialization and the objects it names being renamed underneath it.
 * </p>
 * <p>
 * This is a plain data holder that SnakeYAML reads and writes by its JavaBean properties. The
 * property accessors are therefore deliberately conventional. Two are not: {@link #getChildren()}
 * hands back a copy so that a caller cannot reach in and change the cluster behind its back, and
 * {@link #setChildren(List)} copies what it is given for the same reason.
 * </p>
 */
public class Cluster
{
    private String name;

    private String path;

    private String description;

    private int order;

    private List<String> children;

    /**
     * Creates an empty cluster: no name, no path, no description, order zero, no children.
     * <p>
     * Present because SnakeYAML instantiates the bean through it before applying the setters.
     * </p>
     */
    public Cluster()
    {
        this.children = new ArrayList<>();
        this.order = 0;
    }

    /**
     * Creates a named cluster at a path, otherwise empty.
     *
     * @param name the cluster name
     * @param path the collection path the cluster hangs under; may be <code>null</code> for a root cluster
     */
    public Cluster(String name, String path)
    {
        this();
        this.name = name;
        this.path = path;
    }

    /**
     * Returns the cluster name.
     *
     * @return the name; may be <code>null</code>
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the cluster name.
     *
     * @param name the new name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns the collection path the cluster hangs under.
     *
     * @return the path; may be <code>null</code> or empty for a root cluster
     */
    public String getPath()
    {
        return path;
    }

    /**
     * Sets the collection path.
     *
     * @param path the new path; may be <code>null</code>
     */
    public void setPath(String path)
    {
        this.path = path;
    }

    /**
     * Returns the description.
     *
     * @return the description; may be <code>null</code>
     */
    public String getDescription()
    {
        return description;
    }

    /**
     * Sets the description.
     *
     * @param description the new description; may be <code>null</code>
     */
    public void setDescription(String description)
    {
        this.description = description;
    }

    /**
     * Returns the sort order among sibling clusters at the same path.
     *
     * @return the order
     */
    public int getOrder()
    {
        return order;
    }

    /**
     * Sets the sort order.
     *
     * @param order the new order
     */
    public void setOrder(int order)
    {
        this.order = order;
    }

    /**
     * Returns the fully qualified names of the objects held by this cluster.
     * <p>
     * The returned list is a copy. Changing it does not change the cluster; use {@link #addChild(String)},
     * {@link #removeChild(String)} and {@link #renameChild(String, String)} for that.
     * </p>
     *
     * @return a fresh list of the child FQNs, never <code>null</code>
     */
    public List<String> getChildren()
    {
        return new ArrayList<>(children);
    }

    /**
     * Replaces the held objects with a copy of the given list.
     *
     * @param children the new child FQNs; <code>null</code> is taken to mean none
     */
    public void setChildren(List<String> children)
    {
        this.children = children == null ? new ArrayList<>() : new ArrayList<>(children);
    }

    /**
     * Returns the full path of this cluster, by which it is uniquely known.
     * <p>
     * That is the path and name joined with a slash, or just the name when the cluster sits at the root
     * of a collection and has no path.
     * </p>
     *
     * @return the full path
     */
    public String getFullPath()
    {
        if (path == null || path.isEmpty())
        {
            return name;
        }
        return path + "/" + name; //$NON-NLS-1$
    }

    /**
     * Adds an object to the cluster, unless it is already there.
     *
     * @param objectFqn the fully qualified name of the object
     * @return <code>true</code> if it was added, <code>false</code> if the cluster already held it
     */
    public boolean addChild(String objectFqn)
    {
        if (children.contains(objectFqn))
        {
            return false;
        }
        return children.add(objectFqn);
    }

    /**
     * Removes an object from the cluster.
     *
     * @param objectFqn the fully qualified name of the object
     * @return <code>true</code> if it was held and is now gone
     */
    public boolean removeChild(String objectFqn)
    {
        return children.remove(objectFqn);
    }

    /**
     * Renames a held object in place, keeping its position in the list.
     *
     * @param oldFqn the current fully qualified name
     * @param newFqn the fully qualified name to give it
     * @return <code>true</code> if the old name was held and has been replaced
     */
    public boolean renameChild(String oldFqn, String newFqn)
    {
        int index = children.indexOf(oldFqn);
        if (index < 0)
        {
            return false;
        }
        children.set(index, newFqn);
        return true;
    }

    /**
     * Tells whether the cluster holds the given object.
     *
     * @param objectFqn the fully qualified name of the object
     * @return <code>true</code> if it is held
     */
    public boolean containsChild(String objectFqn)
    {
        return children.contains(objectFqn);
    }

    /**
     * Tells whether the cluster holds nothing.
     *
     * @return <code>true</code> when there are no children
     */
    public boolean isEmpty()
    {
        return children.isEmpty();
    }

    /**
     * A cluster's identity is its path and name; order, description and contents are not part of it.
     *
     * @return a hash over path and name
     */
    @Override
    public int hashCode()
    {
        return Objects.hash(path, name);
    }

    /**
     * Two clusters are equal when they are of the same class and share a path and a name.
     *
     * @param obj the object to compare with
     * @return <code>true</code> when equal
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null || getClass() != obj.getClass())
        {
            return false;
        }
        Cluster other = (Cluster)obj;
        return Objects.equals(path, other.path) && Objects.equals(name, other.name);
    }

    /**
     * Returns a short description naming the cluster and its child count.
     *
     * @return a diagnostic string
     */
    @Override
    public String toString()
    {
        return "Cluster[" + getFullPath() + ", children=" + children.size() + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
