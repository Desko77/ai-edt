/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.List;

/**
 * A method's signature as an extension has to see it, and what changed between two of them.
 * <p>
 * <b>Why a signature and not the whole method.</b> An extension that intercepts a method of the base
 * configuration is coupled to that method's signature and nothing else about it: rename a parameter
 * and the interceptor still runs, add one and it does not. So the thing to compare across a release
 * is the signature, and comparing method text would report every reformatting as a break.
 * </p>
 * <p>
 * Free of every environment type, so the rule for what counts as a breaking change is provable
 * without a configuration to load. The caller reads the model; this decides what the difference
 * means.
 * </p>
 */
public final class MethodSignature
{
    /** The method's name, as the base configuration spells it. */
    public final String name;

    /** Whether the method is available to other modules at all. */
    public final boolean exported;

    /** The parameters, in order. */
    public final List<Param> params = new ArrayList<>();

    /** One parameter of a method. */
    public static final class Param
    {
        /** Its name. */
        public final String name;

        /** Whether it is passed by value, which changes what a handler may do with it. */
        public final boolean byValue;

        /** Whether it has a default, which decides if a call may omit it. */
        public final boolean hasDefault;

        /**
         * Records one parameter.
         *
         * @param name its name.
         * @param byValue whether it is passed by value.
         * @param hasDefault whether it has a default value.
         */
        public Param(String name, boolean byValue, boolean hasDefault)
        {
            this.name = name;
            this.byValue = byValue;
            this.hasDefault = hasDefault;
        }
    }

    /**
     * Records a signature.
     *
     * @param name the method's name.
     * @param exported whether it is exported.
     */
    public MethodSignature(String name, boolean exported)
    {
        this.name = name;
        this.exported = exported;
    }

    /**
     * Renders the signature the way a person would write it, for a report.
     *
     * @return the method name with its parameter list
     */
    public String render()
    {
        StringBuilder out = new StringBuilder(name).append('(');
        for (int i = 0; i < params.size(); i++)
        {
            Param param = params.get(i);
            if (i > 0)
            {
                out.append(", "); //$NON-NLS-1$
            }
            if (param.byValue)
            {
                out.append("Знач "); //$NON-NLS-1$
            }
            out.append(param.name);
            if (param.hasDefault)
            {
                out.append(" = ..."); //$NON-NLS-1$
            }
        }
        return out.append(')').append(exported ? " Экспорт" : "").toString(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Says what an extension would break on, comparing the signature it was written against with
     * the one the new delivery has.
     * <p>
     * <b>Not every difference is a break, and saying so matters.</b> A renamed parameter keeps the
     * interceptor working - the handler is bound by position. A parameter that gained a default
     * keeps it working too. What breaks it is a parameter appearing or disappearing, or one that
     * stopped being passed by value, because then the handler is called with something other than
     * what it was written for. Reporting a rename as a break would bury the real ones.
     * </p>
     *
     * @param was the signature the extension was written against.
     * @param now the signature in the new delivery.
     * @return what breaks, empty when the extension still fits
     */
    public static List<String> whatBreaks(MethodSignature was, MethodSignature now)
    {
        List<String> breaks = new ArrayList<>();
        if (was == null || now == null)
        {
            return breaks;
        }
        if (was.params.size() != now.params.size())
        {
            breaks.add("the parameter count changed: " + was.params.size() + " -> " //$NON-NLS-1$ //$NON-NLS-2$
                + now.params.size());
        }
        int shared = Math.min(was.params.size(), now.params.size());
        for (int i = 0; i < shared; i++)
        {
            Param before = was.params.get(i);
            Param after = now.params.get(i);
            if (before.byValue != after.byValue)
            {
                breaks.add("parameter " + (i + 1) + " (" + after.name //$NON-NLS-1$ //$NON-NLS-2$
                    + ") changed how it is passed: " //$NON-NLS-1$
                    + (before.byValue ? "by value -> by reference" //$NON-NLS-1$
                        : "by reference -> by value")); //$NON-NLS-1$
            }
        }
        if (was.exported && !now.exported)
        {
            breaks.add("the method is no longer exported, so nothing outside its module can " //$NON-NLS-1$
                + "reach it"); //$NON-NLS-1$
        }
        return breaks;
    }

    /**
     * Says what changed without breaking anything, so a person can see it and judge for themselves.
     *
     * @param was the signature the extension was written against.
     * @param now the signature in the new delivery.
     * @return the differences that do not break the binding
     */
    public static List<String> whatChangedHarmlessly(MethodSignature was, MethodSignature now)
    {
        List<String> changed = new ArrayList<>();
        if (was == null || now == null)
        {
            return changed;
        }
        int shared = Math.min(was.params.size(), now.params.size());
        for (int i = 0; i < shared; i++)
        {
            Param before = was.params.get(i);
            Param after = now.params.get(i);
            if (!before.name.equalsIgnoreCase(after.name))
            {
                changed.add("parameter " + (i + 1) + " was renamed: " + before.name + " -> " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + after.name);
            }
            if (before.hasDefault != after.hasDefault)
            {
                changed.add("parameter " + (i + 1) + " (" + after.name + ") " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + (after.hasDefault ? "gained a default" : "lost its default")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return changed;
    }
}
