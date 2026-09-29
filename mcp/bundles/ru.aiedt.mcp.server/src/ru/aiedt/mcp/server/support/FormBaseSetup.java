/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import ru.aiedt.mcp.server.Activator;

/**
 * <b>Defensive layer 3.8.3</b>: gives a form created without the EDT form
 * generator the base root properties the generator itself writes, so a form
 * built by {@code create_form layout=empty} carries the same root as a
 * generated one. <p>
 *
 * The generator puts nine scalar attributes on the form root
 * ({@code saveWindowSettings}, {@code autoTitle}, {@code autoUrl},
 * {@code group=Vertical}, {@code autoFillCheck}, {@code allowFormCustomize},
 * {@code enabled}, {@code showTitle}, {@code showCloseButton} - all
 * {@code true}/{@code Vertical}) plus two containers: an
 * {@code autoCommandBar} with {@code horizontalAlign=Left} and
 * {@code autoFill=true}, and an empty {@code commandInterface} holding a
 * {@code navigationPanel} and a {@code commandBar}. A form without them keeps
 * the model defaults of those features - {@code false} for the booleans - so
 * the client sees a disabled form with no title and no close button. <p>
 *
 * Every property is applied by reflective EMF setters, because the plugin
 * never compiles against the form model. A property that has no matching
 * setter on the current EDT build is silently skipped - the form still gets
 * the rest, and a create never fails over a renamed feature.
 *
 * <p>Also exposes {@link #buildEmptyForm(Object)} - a stub that creates an
 * empty {@code Form} root suitable for attaching to a CommonForm wrapper
 * (see {@link BmCommonFormPostCreate}).
 */
public final class FormBaseSetup
{
    /**
     * The scalar base properties of a form root, as the EDT form generator
     * writes them. Names are EMF feature names (capital first letter form:
     * {@code Foo} for the {@code setFoo}/{@code getFoo} pair); values are the
     * enum constant names and boolean literals the helper coerces to the
     * setter type. Beside these, every form root receives the two container
     * properties an empty form is born without: an {@code autoCommandBar}
     * ({@code horizontalAlign=Left}, {@code autoFill=true}) and a
     * {@code commandInterface} with empty navigation and command bar panels.
     */
    private static final Map<String, String> BASE_PROPERTIES = buildBaseProperties();

    /** The container properties applied beside {@link #BASE_PROPERTIES}: autoCommandBar, commandInterface. */
    private static final int CONTAINER_PROPERTIES = 2;

    /**
     * Candidate class names of the form model factory, tried in this order.
     */
    private static final String[] FORM_FACTORY_CLASSES = {
        "com._1c.g5.v8.dt.form.model.FormFactory", //$NON-NLS-1$
        "com._1c.g5.v8.dt.form.FormFactory" //$NON-NLS-1$
    };

    /**
     * Accessors a metadata form wrapper uses for the {@code form.model.Form} it holds, in the order
     * they are tried. EDT names this attribute differently across releases, which is why all three
     * are probed.
     */
    private static final String[] INNER_FORM_GETTERS = {
        "getFormAttachedForm", "getForm", "getRootContainer" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };

    private FormBaseSetup()
    {
        // utility
    }

    private static Map<String, String> buildBaseProperties()
    {
        // The EDT form generator's root attribute set (form.model.Form features).
        Map<String, String> m = new LinkedHashMap<>();
        m.put("SaveWindowSettings", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("AutoTitle", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("AutoUrl", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Group", "VERTICAL"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("AutoFillCheck", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("AllowFormCustomize", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Enabled", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ShowTitle", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ShowCloseButton", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        return m;
    }

    /**
     * Applies the base properties to the given form root - the nine scalar
     * attributes the EDT form generator writes, the {@code autoCommandBar}
     * container and the empty {@code commandInterface}. Properties that have
     * no matching setter on the form class are silently skipped.
     * <p>
     * The properties live on the {@code form.model.Form}, not on the
     * {@code mdclass} wrapper the configuration holds it under, so a wrapper
     * given here is unwrapped first and the properties reach the form behind it.
     *
     * @param formRoot the root {@code Form} object (the one exposing
     *                 {@code getItems()} etc.), or the metadata wrapper around it
     * @return number of properties successfully applied
     */
    public static int applyDefaults(Object formRoot)
    {
        if (formRoot == null)
        {
            return 0;
        }
        Object target = formRoot;
        int applied = applyAll(target);
        if (applied == 0)
        {
            Object inner = innerFormOf(formRoot);
            if (inner != null)
            {
                target = inner;
                applied = applyAll(target);
            }
        }
        Activator.logInfo("FormBaseSetup applied " + applied + "/" //$NON-NLS-1$ //$NON-NLS-2$
            + totalCount() + " base properties to " + target.getClass().getSimpleName());
        return applied;
    }

    /** The full property count the helper tries to apply: scalar plus container properties. */
    private static int totalCount()
    {
        return BASE_PROPERTIES.size() + CONTAINER_PROPERTIES;
    }

    /**
     * Applies every base property to one object, counting the ones that reached a setter.
     *
     * @param formRoot the object to set the properties on
     * @return how many of the base properties were applied
     */
    private static int applyAll(Object formRoot)
    {
        int applied = 0;
        for (Map.Entry<String, String> e : BASE_PROPERTIES.entrySet())
        {
            if (applyOne(formRoot, e.getKey(), e.getValue()))
            {
                applied++;
            }
        }
        if (configureAutoCommandBar(formRoot))
        {
            applied++;
        }
        if (ensureCommandInterface(formRoot))
        {
            applied++;
        }
        return applied;
    }

    /**
     * Gives the form its {@code autoCommandBar}: the container the form already holds is
     * configured, and a form without one receives a new container from the model factory. Either
     * way the container ends up with {@code horizontalAlign=Left} and {@code autoFill=true},
     * matching the generator's bar.
     *
     * @param form the form root
     * @return true when the form holds a command bar carrying both settings
     */
    private static boolean configureAutoCommandBar(Object form)
    {
        Object bar = invokeNoArgGetter(form, "getAutoCommandBar"); //$NON-NLS-1$
        if (bar == null)
        {
            Object factory = formFactory();
            if (factory == null || !hasOneArgMethod(form, "setAutoCommandBar")) //$NON-NLS-1$
            {
                return false;
            }
            bar = createViaFactory(factory, "createAutoCommandBar"); //$NON-NLS-1$
            if (bar == null || !invokeObjectSetter(form, "setAutoCommandBar", bar)) //$NON-NLS-1$
            {
                return false;
            }
        }
        boolean aligned = applyOne(bar, "HorizontalAlign", "LEFT"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean filled = applyOne(bar, "AutoFill", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        return aligned && filled;
    }

    /**
     * Gives the form its {@code commandInterface}: the interface the form already holds is kept,
     * and a form without one receives a new interface from the model factory. Either way the
     * interface carries its two empty panels - {@code navigationPanel} and {@code commandBar} -
     * the way the generator's empty interface does.
     *
     * @param form the form root
     * @return true when the form holds a command interface with both panels
     */
    private static boolean ensureCommandInterface(Object form)
    {
        Object commandInterface = invokeNoArgGetter(form, "getCommandInterface"); //$NON-NLS-1$
        if (commandInterface == null)
        {
            Object factory = formFactory();
            if (factory == null || !hasOneArgMethod(form, "setCommandInterface")) //$NON-NLS-1$
            {
                return false;
            }
            commandInterface = createViaFactory(factory, "createFormCommandInterface"); //$NON-NLS-1$
            if (commandInterface == null
                || !invokeObjectSetter(form, "setCommandInterface", commandInterface)) //$NON-NLS-1$
            {
                return false;
            }
        }
        boolean navigation = ensureCommandInterfacePanel(commandInterface,
            "NavigationPanel"); //$NON-NLS-1$
        boolean commands = ensureCommandInterfacePanel(commandInterface, "CommandBar"); //$NON-NLS-1$
        return navigation && commands;
    }

    /**
     * Ensures one panel of a command interface exists, creating it from the model factory when the
     * interface was born without it.
     *
     * @param commandInterface the form's command interface
     * @param panelName the panel's EMF feature name ({@code NavigationPanel} or {@code CommandBar})
     * @return true when the interface holds that panel
     */
    private static boolean ensureCommandInterfacePanel(Object commandInterface, String panelName)
    {
        if (invokeNoArgGetter(commandInterface, "get" + panelName) != null) //$NON-NLS-1$
        {
            return true;
        }
        Object factory = formFactory();
        if (factory == null || !hasOneArgMethod(commandInterface, "set" + panelName)) //$NON-NLS-1$
        {
            return false;
        }
        Object panel = createViaFactory(factory, "createFormCommandInterfaceItems"); //$NON-NLS-1$
        return panel != null && invokeObjectSetter(commandInterface, "set" + panelName, panel); //$NON-NLS-1$
    }

    /**
     * The {@code form.model.Form} behind a metadata form wrapper, or {@code null} when the object
     * given is the form itself or carries none.
     * <p>
     * An object that answers {@code getItems()} is a form root already and is left alone: the
     * accessors probed below are the metadata wrapper's, and probing them on a real form could
     * return an object that is not this form at all.
     *
     * @param wrapper the candidate metadata wrapper
     * @return the inner form, or {@code null} when there is none to reach
     */
    private static Object innerFormOf(Object wrapper)
    {
        if (hasNoArgMethod(wrapper, "getItems")) //$NON-NLS-1$
        {
            return null;
        }
        for (String getter : INNER_FORM_GETTERS)
        {
            if (!hasNoArgMethod(wrapper, getter))
            {
                continue;
            }
            try
            {
                Object inner = wrapper.getClass().getMethod(getter).invoke(wrapper);
                if (inner != null && inner != wrapper)
                {
                    return inner;
                }
            }
            catch (Exception e)
            {
                Activator.logWarning("FormBaseSetup " + getter //$NON-NLS-1$
                    + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    /** True when the object exposes a no-argument method of that name. */
    private static boolean hasNoArgMethod(Object obj, String name)
    {
        for (Method m : obj.getClass().getMethods())
        {
            if (name.equals(m.getName()) && m.getParameterCount() == 0)
            {
                return true;
            }
        }
        return false;
    }

    /** True when the object exposes a one-argument method of that name. */
    private static boolean hasOneArgMethod(Object obj, String name)
    {
        for (Method m : obj.getClass().getMethods())
        {
            if (name.equals(m.getName()) && m.getParameterCount() == 1)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The value of a no-argument getter, or {@code null} when the object exposes no such method or
     * the call refuses.
     *
     * @param obj the object to read
     * @param name the getter name
     * @return the getter's value, or {@code null}
     */
    private static Object invokeNoArgGetter(Object obj, String name)
    {
        if (obj == null || !hasNoArgMethod(obj, name))
        {
            return null;
        }
        try
        {
            return obj.getClass().getMethod(name).invoke(obj);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Invokes the single-argument setter of that name whose parameter accepts the value.
     *
     * @param obj the object to set the value on
     * @param name the setter name
     * @param value the value to set
     * @return true when a setter was invoked
     */
    private static boolean invokeObjectSetter(Object obj, String name, Object value)
    {
        for (Method m : obj.getClass().getMethods())
        {
            if (name.equals(m.getName()) && m.getParameterCount() == 1
                && m.getParameterTypes()[0].isInstance(value))
            {
                try
                {
                    m.invoke(obj, value);
                    return true;
                }
                catch (Exception e)
                {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * The form model factory singleton, or {@code null} when no candidate factory class resolves
     * on this runtime.
     *
     * @return the {@code FormFactory} singleton, or {@code null}
     */
    private static Object formFactory()
    {
        for (String factoryClass : FORM_FACTORY_CLASSES)
        {
            try
            {
                Class<?> clazz = Class.forName(factoryClass);
                Object factory = clazz.getField("eINSTANCE").get(null); //$NON-NLS-1$
                if (factory != null)
                {
                    return factory;
                }
            }
            catch (ClassNotFoundException ignored)
            {
                // try next factory class
            }
            catch (Exception e)
            {
                Activator.logWarning("FormBaseSetup factory " + factoryClass //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Creates a model object by calling a no-argument create method of the factory.
     *
     * @param factory the form model factory
     * @param createMethod the factory method name
     * @return the created object, or {@code null} when the call refuses
     */
    private static Object createViaFactory(Object factory, String createMethod)
    {
        try
        {
            return factory.getClass().getMethod(createMethod).invoke(factory);
        }
        catch (Exception e)
        {
            Activator.logWarning("FormBaseSetup " + createMethod //$NON-NLS-1$
                + " failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static boolean applyOne(Object obj, String propertyName, String value)
    {
        String setterName = "set" + propertyName;
        java.util.List<Method> candidates = new java.util.ArrayList<>();
        for (Method m : obj.getClass().getMethods())
        {
            if (setterName.equals(m.getName()) && m.getParameterCount() == 1)
            {
                candidates.add(m);
            }
        }
        return applyFirstThatTakes(obj, candidates, value);
    }

    /**
     * Invokes the first of the given setters whose parameter takes the value, and answers whether
     * one of them did.
     * <p>
     * A setter the value does not fit is passed over rather than ending the search, because a form
     * carries the same property more than once - as the model type and as text - and an overload
     * whose parameter the text cannot be coerced to says nothing about the rest. The candidates are
     * tried in the order given.
     *
     * @param obj the object to set the property on
     * @param candidates the setters of that property name, in the order to try them
     * @param value the property value, as text
     * @return true when one of the candidates was invoked
     */
    static boolean applyFirstThatTakes(Object obj, java.util.List<Method> candidates, String value)
    {
        for (Method m : candidates)
        {
            try
            {
                Object coerced = coerce(m.getParameterTypes()[0], value);
                if (coerced == null && !m.getParameterTypes()[0].isPrimitive())
                {
                    // The value does not fit this parameter type - try the next overload.
                    continue;
                }
                m.invoke(obj, coerced);
                return true;
            }
            catch (Exception e)
            {
                // Try next overload
            }
        }
        return false;
    }

    /**
     * Best-effort coercion: boolean -&gt; Boolean, enum -&gt; matching constant,
     * String -&gt; verbatim. Numeric coercions added when needed.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object coerce(Class<?> targetType, String value)
    {
        if (value == null)
        {
            return null;
        }
        if (targetType == String.class)
        {
            return value;
        }
        if (targetType == boolean.class || targetType == Boolean.class)
        {
            return Boolean.parseBoolean(value);
        }
        if (targetType == int.class || targetType == Integer.class)
        {
            try
            {
                return Integer.parseInt(value);
            }
            catch (NumberFormatException nfe)
            {
                return null;
            }
        }
        if (targetType.isEnum())
        {
            try
            {
                return Enum.valueOf((Class<? extends Enum>) targetType, value);
            }
            catch (IllegalArgumentException iae)
            {
                // Try a case-insensitive search across the enum's constants
                for (Object constant : targetType.getEnumConstants())
                {
                    if (constant.toString().equalsIgnoreCase(value)
                        || ((Enum<?>) constant).name().equalsIgnoreCase(value))
                    {
                        return constant;
                    }
                }
                return null;
            }
        }
        return null;
    }

    /**
     * Builds an empty {@code Form} root suitable for attaching to a CommonForm
     * wrapper: the form the model factory creates, with the base properties of
     * a generated form applied. Returns null when the factory is missing.
     *
     * @param owningContext the object the form will be attached to (unused -
     *                      the factory needs no context)
     * @return the empty form root, or {@code null} when no form factory resolves
     */
    public static Object buildEmptyForm(Object owningContext)
    {
        Object factory = formFactory();
        if (factory == null)
        {
            return null;
        }
        Object form = createViaFactory(factory, "createForm"); //$NON-NLS-1$
        if (form == null)
        {
            return null;
        }
        applyDefaults(form);
        return form;
    }
}
