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
 * <b>Defensive layer 3.8.3</b>: applies the 11 base properties that the EDT
 * "New Form" wizard sets on a generic+empty form, so the form opens in the
 * editor and tables don't collapse to zero height at runtime. <p>
 *
 * Root cause: a generic form (no main attribute, layout=empty) goes
 * through the EDT form generator with an empty field description. The
 * generator does not auto-populate the 11 baseline properties (group layout,
 * command bar, command interface, item placement, etc.), so the editor
 * refuses to open the form and a table dropped on it collapses. The visual
 * "New Form" wizard does the right thing; the headless path does not. <p>
 *
 * 1.40 fix: this helper sets all 11 properties via reflection-based EMF
 * setters. The list is hard-coded (matches upstream defaults). When a property is
 * not exposed on the current EDT build (older API), it is silently skipped -
 * the form still gets the "best effort" 9-10 properties.
 *
 * <p>Also exposes {@link #buildEmptyForm(Object)} - a stub that creates an
 * empty {@code Form} root suitable for attaching to a CommonForm wrapper
 * (see {@link BmCommonFormPostCreate}).
 */
public final class FormBaseSetup
{
    /**
     * The 11 base properties applied to a generic+empty form.
     * Names are EMF feature names (capital first letter form: {@code Foo} for
     * the {@code setFoo}/{@code getFoo} pair).
     * Values are constants - {@code "Auto"}, {@code "Vertical"}, {@code "Horizontal"},
     * {@code "true"}, etc. - the helper coerces to the setter type.
     */
    private static final Map<String, String> BASE_PROPERTIES = buildBaseProperties();

    private FormBaseSetup()
    {
        // utility
    }

    private static Map<String, String> buildBaseProperties()
    {
        // Hard-coded upstream defaults (see RELEASE-3.8.0.md and tools.html § Forms).
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ChildrenAlign", "ItemsCenter");
        m.put("VerticalAlign", "Top");
        m.put("HorizontalAlign", "Left");
        m.put("ItemsGroup", "Vertical");
        m.put("ChildrenWidth", "MaximalWidth");
        m.put("EnableContentChange", "true");
        m.put("AutoCommandBar", "true");
        m.put("CommandBarLocation", "Top");
        m.put("ScalingMode", "Normal");
        m.put("AutoFillCheck", "true");
        m.put("Use75Percent", "false");
        return m;
    }

    /**
     * Applies the 11 base properties to the given form root. Properties that
     * have no matching setter on the form class are silently skipped.
     *
     * @param formRoot the root {@code Form} object (the one exposing
     *                 {@code getItems()} etc.)
     * @return number of properties successfully applied
     */
    public static int applyDefaults(Object formRoot)
    {
        if (formRoot == null)
        {
            return 0;
        }
        int applied = 0;
        for (Map.Entry<String, String> e : BASE_PROPERTIES.entrySet())
        {
            if (applyOne(formRoot, e.getKey(), e.getValue()))
            {
                applied++;
            }
        }
        Activator.logInfo("FormBaseSetup applied " + applied + "/" //$NON-NLS-1$ //$NON-NLS-2$
            + BASE_PROPERTIES.size() + " base properties to " + formRoot.getClass().getSimpleName());
        return applied;
    }

    private static boolean applyOne(Object obj, String propertyName, String value)
    {
        String setterName = "set" + propertyName;
        for (Method m : obj.getClass().getMethods())
        {
            if (!setterName.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            try
            {
                Object coerced = coerce(m.getParameterTypes()[0], value);
                if (coerced == null && !m.getParameterTypes()[0].isPrimitive())
                {
                    return false;
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
     * wrapper. Uses the EDT form-factory via reflection (probe several
     * candidate package names). Returns null when the factory is missing.
     */
    public static Object buildEmptyForm(Object owningContext)
    {
        for (String factoryClass : new String[] {
            "com._1c.g5.v8.dt.form.model.FormFactory",
            "com._1c.g5.v8.dt.form.FormFactory"
        })
        {
            try
            {
                Class<?> clazz = Class.forName(factoryClass);
                java.lang.reflect.Field eInstance = clazz.getField("eINSTANCE"); //$NON-NLS-1$
                Object factory = eInstance.get(null);
                Method create = factory.getClass().getMethod("createForm"); //$NON-NLS-1$
                Object form = create.invoke(factory);
                if (form != null)
                {
                    applyDefaults(form);
                    return form;
                }
            }
            catch (ClassNotFoundException ignored)
            {
                // try next factory class
            }
            catch (Exception e)
            {
                Activator.logWarning("buildEmptyForm " + factoryClass //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }
}
