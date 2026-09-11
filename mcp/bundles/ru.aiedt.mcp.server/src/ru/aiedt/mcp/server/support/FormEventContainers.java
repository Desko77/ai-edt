/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ru.aiedt.mcp.server.Activator;

/**
 * Which of a form's two handler containers holds a given event.
 * <p>
 * A form root carries handlers in two places, and both are {@code EventHandlerContainer}: the form
 * itself, and the {@code FormExtInfo} that says what KIND of form it is. Which one an event belongs
 * to is not a matter of taste - the platform reads one of them - and writing an event into the
 * wrong one leaves a form with the same event bound twice, where which handler runs is undefined.
 * </p>
 * <p>
 * Measured over 1157 forms of a working configuration: the split is clean, and no event appears in
 * both containers. The events of the extInfo are the ones that depend on what the form is FOR -
 * writing and reading the object, keeping settings and variants. Everything else sits on the form.
 * </p>
 * <p>
 * The model is asked first and the measured set is only the fallback, because a list written down
 * here is a list that goes stale: a later platform adds an event and nobody edits this file. The
 * census that produced the set is the test that the rule still agrees with reality.
 * </p>
 */
public final class FormEventContainers
{
    /**
     * The events a form's {@code extInfo} holds, measured over 1157 forms.
     * <p>
     * Used only when the runtime cannot be asked. Kept lowercase for a case-insensitive match:
     * callers arrive with the English name from {@code FormEventRegistry}, which is already
     * canonical, but a form file read from disk is not guaranteed to be.
     * </p>
     */
    private static final Set<String> EXT_INFO_EVENTS = new HashSet<>(Arrays.asList(
        "onwriteatserver", //$NON-NLS-1$
        "beforewriteatserver", //$NON-NLS-1$
        "afterwriteatserver", //$NON-NLS-1$
        "onreadatserver", //$NON-NLS-1$
        "beforewrite", //$NON-NLS-1$
        "afterwrite", //$NON-NLS-1$
        "beforeloadusersettingsatserver", //$NON-NLS-1$
        "onloadusersettingsatserver", //$NON-NLS-1$
        "onsaveusersettingsatserver", //$NON-NLS-1$
        "onupdateusersettingsetatserver", //$NON-NLS-1$
        "beforeloadvariantatserver", //$NON-NLS-1$
        "onloadvariantatserver", //$NON-NLS-1$
        "onsavevariantatserver")); //$NON-NLS-1$

    private FormEventContainers()
    {
        // Static entry points only.
    }

    /**
     * Tells whether an event belongs to the form's {@code extInfo} rather than to the form.
     *
     * @param englishEvent the canonical English event name
     * @return <code>true</code> when the extInfo is its container
     */
    public static boolean belongsToExtInfo(String englishEvent)
    {
        return englishEvent != null && EXT_INFO_EVENTS.contains(englishEvent.toLowerCase(Locale.ROOT));
    }

    /**
     * The container that holds a given event on a form root.
     *
     * @param form the form root
     * @param englishEvent the canonical English event name
     * @return the {@code extInfo} when that is where the event lives and the form has one,
     *         otherwise the form itself; never <code>null</code> when the form is not
     */
    public static Object containerFor(Object form, String englishEvent)
    {
        if (form == null)
        {
            return null;
        }
        if (!belongsToExtInfo(englishEvent))
        {
            return form;
        }
        Object extInfo = extInfoOf(form);
        // A form whose extInfo is absent, or carries no handler list, keeps its handler on the
        // form: a handler nowhere is worse than a handler in the second-best place.
        return extInfo != null && handlersOf(extInfo) != null ? extInfo : form;
    }

    /**
     * Every container a form root can hold handlers in, the form first.
     * <p>
     * What a search has to cover. A duplicate that sits in the other container is exactly the one
     * a single-container search misses, and it is the one that leaves the event bound twice.
     * </p>
     *
     * @param form the form root
     * @return the containers, never <code>null</code>
     */
    public static List<Object> containersOf(Object form)
    {
        List<Object> containers = new ArrayList<>(2);
        if (form == null)
        {
            return containers;
        }
        containers.add(form);
        Object extInfo = extInfoOf(form);
        if (extInfo != null && handlersOf(extInfo) != null)
        {
            containers.add(extInfo);
        }
        return containers;
    }

    /**
     * Names a container for a message a person reads.
     *
     * @param form the form root
     * @param container one of its containers
     * @return a short description
     */
    public static String describe(Object form, Object container)
    {
        return container == form ? "the form" : "the form's extInfo"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The handler list of a container, through whichever accessor this EDT exposes.
     *
     * @param container a form, an extInfo or a form item
     * @return the list, or <code>null</code> when this object carries none
     */
    public static Collection<?> handlersOf(Object container)
    {
        if (container == null)
        {
            return null;
        }
        for (String accessor : new String[] { "getHandlers", "getEventHandlers" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            try
            {
                Object handlers = container.getClass().getMethod(accessor).invoke(container);
                if (handlers instanceof Collection)
                {
                    return (Collection<?>)handlers;
                }
            }
            catch (NoSuchMethodException absent)
            {
                // This runtime spells it the other way; try that.
            }
            catch (Exception failed)
            {
                Activator.logWarning("form handler accessor " + accessor + " failed on " //$NON-NLS-1$ //$NON-NLS-2$
                    + container.getClass().getSimpleName() + ": " + failed); //$NON-NLS-1$
                return null;
            }
        }
        return null;
    }

    /**
     * The name of the handler already bound to an event on a form root, in any container.
     *
     * @param form the form root
     * @param englishEvent the canonical English event name
     * @return the handler name and the container that holds it, or <code>null</code> when the
     *         event is free
     */
    public static String boundHandlerFor(Object form, String englishEvent)
    {
        if (form == null || englishEvent == null)
        {
            return null;
        }
        for (Object container : containersOf(form))
        {
            Collection<?> handlers = handlersOf(container);
            if (handlers == null)
            {
                continue;
            }
            for (Object handler : handlers)
            {
                if (englishEvent.equalsIgnoreCase(eventNameOf(handler)))
                {
                    String name = handlerNameOf(handler);
                    return (name == null || name.isEmpty() ? "an unnamed handler" : "'" + name + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + " on " + describe(form, container); //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    /**
     * The event name a handler is bound to.
     *
     * @param handler an event handler
     * @return the English event name, or <code>null</code> when it carries none
     */
    public static String eventNameOf(Object handler)
    {
        try
        {
            Object event = handler.getClass().getMethod("getEvent").invoke(handler); //$NON-NLS-1$
            if (event == null)
            {
                return null;
            }
            Object name = event.getClass().getMethod("getName").invoke(event); //$NON-NLS-1$
            return name != null ? name.toString() : null;
        }
        catch (Exception unreadable)
        {
            // A handler whose event cannot be read is not a handler for the event being asked
            // about; saying so is better than guessing it is.
            return null;
        }
    }

    /**
     * The procedure name of a handler.
     *
     * @param handler an event handler
     * @return the name, or <code>null</code>
     */
    public static String handlerNameOf(Object handler)
    {
        try
        {
            Object name = handler.getClass().getMethod("getName").invoke(handler); //$NON-NLS-1$
            return name != null ? name.toString() : null;
        }
        catch (Exception unreadable)
        {
            return null;
        }
    }

    private static Object extInfoOf(Object form)
    {
        try
        {
            return form.getClass().getMethod("getExtInfo").invoke(form); //$NON-NLS-1$
        }
        catch (NoSuchMethodException absent)
        {
            // A form item, or an EDT that does not model it: there is no second container.
            return null;
        }
        catch (Exception failed)
        {
            Activator.logWarning("getExtInfo failed on " + form.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + failed); //$NON-NLS-1$
            return null;
        }
    }
}
