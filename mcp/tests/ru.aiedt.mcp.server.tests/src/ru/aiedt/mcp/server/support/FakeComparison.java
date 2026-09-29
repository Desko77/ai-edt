/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.ComparisonFlags;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.MergeRule;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;

/**
 * A comparison tree and session made of proxies, for the behaviour that has to hold without a
 * live EDT.
 * <p>
 * The real nodes are BM objects the environment builds; what the walk and the decisions read from
 * them is names, flags, ids and rules, all of which a proxy answers. The session records the rules
 * set on it - to the subtree, like the environment's own setter, so a rule set on a module root
 * reaches the methods under it in the fake exactly as it does in the real one. That is the
 * behaviour several defects here turned on, and a fake that did not reproduce it would pass tests
 * the real thing fails.
 * </p>
 */
final class FakeComparison
{
    /**
     * One node of a synthetic tree, before it is materialised.
     * <p>
     * A node with a name becomes a {@code TopComparisonNode}; one without stays a plain
     * {@code ComparisonNode}, which is what the parts of a module are that the environment did not
     * name.
     * </p>
     */
    static final class Shape
    {
        final long id;
        final String name;
        final ComparisonFlags flags;
        final List<Shape> children = new ArrayList<>();

        Shape(long id, String name, ComparisonFlags flags)
        {
            this.id = id;
            this.name = name;
            this.flags = flags;
        }

        /**
         * Adds a child beneath this node.
         *
         * @param child the child to add.
         * @return this shape, for chaining
         */
        Shape child(Shape child)
        {
            children.add(child);
            return this;
        }
    }

    /** What each node id carries as its merge rule; shared by the session and its nodes. */
    private final Map<Long, MergeRule> rules = new HashMap<>();

    /** Every shape by id, so the session can answer {@code getNode}. */
    private final Map<Long, Shape> shapes = new HashMap<>();

    /** Every named shape by name, so the session can answer {@code getTopNode}. */
    private final Map<String, Shape> named = new HashMap<>();

    /** The tree the session hands out as its root. */
    private Shape root;

    private FakeComparison()
    {
        // Built through over().
    }

    /**
     * Builds a fake comparison over one tree.
     *
     * @param root the root shape; may be <code>null</code> for a session that offers no tree.
     * @return the fake, whose session and nodes share one set of rules
     */
    static FakeComparison over(Shape root)
    {
        FakeComparison fake = new FakeComparison();
        fake.root = root;
        if (root != null)
        {
            fake.index(root);
        }
        return fake;
    }

    /**
     * The session this fake answers as.
     *
     * @return a session whose tree is this fake's root and whose rules this fake records
     */
    IComparisonSession session()
    {
        return (IComparisonSession)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{IComparisonSession.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getRootNode": //$NON-NLS-1$
                        return materialised(root, true);
                    case "getNode": //$NON-NLS-1$
                        // The id is the last argument on both overloads of this call.
                        return materialised(shapes.get(((Number)args[args.length - 1]).longValue()));
                    case "getTopNode": //$NON-NLS-1$
                        return named.containsKey((String)args[0])
                            ? materialised(named.get((String)args[0])) : null;
                    case "setMergeRuleToSubtree": //$NON-NLS-1$
                        setRuleToSubtree(((Number)args[0]).longValue(), (MergeRule)args[1]);
                        return true;
                    case "toString": //$NON-NLS-1$
                        return "fake session"; //$NON-NLS-1$
                    default:
                        return null;
                }
            });
    }

    /**
     * The rule recorded for one node.
     *
     * @param id the node.
     * @return the rule, or <code>null</code> when none was set on it
     */
    MergeRule ruleOn(long id)
    {
        return rules.get(id);
    }

    /**
     * A shape for a named top node.
     *
     * @param id the node id.
     * @param name the node's qualified name on every side.
     * @param flags what the environment would say about it; may be <code>null</code>.
     * @return the shape
     */
    static Shape top(long id, String name, ComparisonFlags flags)
    {
        return new Shape(id, name, flags);
    }

    /**
     * A shape for an unnamed node below a top one.
     *
     * @param id the node id.
     * @param flags what the environment would say about it; may be <code>null</code>.
     * @return the shape
     */
    static Shape plain(long id, ComparisonFlags flags)
    {
        return new Shape(id, null, flags);
    }

    /**
     * Flags for a node both sides changed since the ancestor.
     *
     * @return flags that attribute to BOTH and differ between the sides
     */
    static ComparisonFlags changedOnBothSides()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasChanged(ComparisonSide.MAIN, ComparisonSide.COMMON_ANCESTOR);
        flags.setHasChanged(ComparisonSide.OTHER, ComparisonSide.COMMON_ANCESTOR);
        flags.setHasChanged(ComparisonSide.MAIN, ComparisonSide.OTHER);
        flags.setHasDoubleChanges();
        return flags;
    }

    /**
     * Flags for a node only this side changed since the ancestor.
     *
     * @return flags that attribute to OURS and differ between the sides
     */
    static ComparisonFlags changedByUs()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasChanged(ComparisonSide.MAIN, ComparisonSide.COMMON_ANCESTOR);
        flags.setHasChanged(ComparisonSide.MAIN, ComparisonSide.OTHER);
        return flags;
    }

    /**
     * Flags for a node the sides disagree about while neither moved from the ancestor.
     *
     * @return flags that differ between the sides and attribute to UNKNOWN
     */
    static ComparisonFlags changedBetweenTheSidesOnly()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasChanged(ComparisonSide.MAIN, ComparisonSide.OTHER);
        return flags;
    }

    private void index(Shape shape)
    {
        shapes.put(shape.id, shape);
        if (shape.name != null)
        {
            named.put(shape.name, shape);
        }
        for (Shape child : shape.children)
        {
            index(child);
        }
    }

    private void setRuleToSubtree(long id, MergeRule rule)
    {
        Shape shape = shapes.get(id);
        if (shape == null)
        {
            rules.put(id, rule);
            return;
        }
        setRuleToShape(shape, rule);
    }

    private void setRuleToShape(Shape shape, MergeRule rule)
    {
        rules.put(shape.id, rule);
        for (Shape child : shape.children)
        {
            setRuleToShape(child, rule);
        }
    }

    private ComparisonNode materialised(Shape shape)
    {
        return materialised(shape, false);
    }

    private ComparisonNode materialised(Shape shape, boolean asRoot)
    {
        if (shape == null)
        {
            return null;
        }
        // The session's root is the one node the environment types RootComparisonNode, and the
        // declared return of getRootNode casts to it; a proxy that does not carry the interface
        // fails that cast before anything else can happen.
        boolean top = shape.name != null || asRoot;
        java.util.List<Class<?>> faces = new ArrayList<>();
        if (asRoot)
        {
            faces.add(com._1c.g5.v8.dt.compare.model.RootComparisonNode.class);
        }
        faces.add(top ? TopComparisonNode.class : ComparisonNode.class);
        return (ComparisonNode)Proxy.newProxyInstance(getClass().getClassLoader(),
            faces.toArray(new Class<?>[0]),
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "bmGetId": //$NON-NLS-1$
                        return shape.id;
                    case "getSymlink": //$NON-NLS-1$
                    case "getMainSymlink": //$NON-NLS-1$
                    case "getOtherSymlink": //$NON-NLS-1$
                    case "getCommonAncestorSymlink": //$NON-NLS-1$
                        return shape.name;
                    case "isOneSideNode": //$NON-NLS-1$
                        return false;
                    case "getNodeSide": //$NON-NLS-1$
                        return null;
                    case "isAncestorObjectExists": //$NON-NLS-1$
                        return true;
                    case "getComparisonFlags": //$NON-NLS-1$
                        return shape.flags;
                    case "getMergeSettings": //$NON-NLS-1$
                        return mergeSettingsOf(shape.id);
                    case "hasChildren": //$NON-NLS-1$
                        return !shape.children.isEmpty();
                    case "getChildren": //$NON-NLS-1$
                    case "getTopChildren": //$NON-NLS-1$
                    case "getContainmentChildren": //$NON-NLS-1$
                        return materialised(shape.children);
                    case "getParent": //$NON-NLS-1$
                        return null;
                    case "toString": //$NON-NLS-1$
                        return String.valueOf(shape.name);
                    case "hashCode": //$NON-NLS-1$
                        return Long.hashCode(shape.id);
                    case "equals": //$NON-NLS-1$
                        return args[0] instanceof ComparisonNode
                            && ((ComparisonNode)args[0]).bmGetId() == shape.id;
                    default:
                        return null;
                }
            });
    }

    private org.eclipse.emf.common.util.EList<ComparisonNode> materialised(List<Shape> shapes)
    {
        // EList, because that is what the node interfaces declare - an ordinary ArrayList fails
        // the cast inside the walk before a single child is reached.
        org.eclipse.emf.common.util.BasicEList<ComparisonNode> nodes =
            new org.eclipse.emf.common.util.BasicEList<>();
        for (Shape shape : shapes)
        {
            nodes.add(materialised(shape));
        }
        return nodes;
    }

    private Object mergeSettingsOf(long id)
    {
        return Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{com._1c.g5.v8.dt.compare.model.MergeSettings.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getMergeRule": //$NON-NLS-1$
                        return rules.get(id);
                    case "getDefaultMergeRule": //$NON-NLS-1$
                        return null;
                    case "isMustBeMerged": //$NON-NLS-1$
                        return true;
                    case "isCanBeMerged": //$NON-NLS-1$
                        return true;
                    case "getAvailableMergeRules": //$NON-NLS-1$
                        return new ArrayList<MergeRule>();
                    default:
                        return null;
                }
            });
    }
}
