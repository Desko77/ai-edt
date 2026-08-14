/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.hover.IEObjectHoverProvider;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess;

/**
 * Reads the hover text EDT would show over a BSL element, without opening an editor to get it.
 * <p>
 * The hover is the good answer: it carries the element's signature, its documentation and the
 * execution contexts, already assembled by the environment. Until now the only way to reach it was to
 * open the module in a real editor, ask the source viewer for its text hover, and close the editor
 * again. That works, and a person sitting in the same EDT sees every bit of it - the tab appearing,
 * the focus moving, the workbench pausing - hundreds of times over a long survey.
 * </p>
 * <p>
 * None of it is necessary. EDT's hover provider is a plain service in the language's injector, and the
 * method that builds the text takes the model element and a text region - a region being a pair of
 * numbers, not a widget. So the whole thing can be asked and answered with no editor, no viewer and
 * nothing on the screen.
 * </p>
 * <p>
 * The method is protected, so it is called by reflection. That is the price of using it from outside
 * the class that declares it, and it is paid once here rather than at each call site.
 * </p>
 */
public final class BslHoverAccess
{
    private static final String HOVER_METHOD = "getHoverInfo"; //$NON-NLS-1$

    private static final String HTML_METHOD = "getHtml"; //$NON-NLS-1$

    private BslHoverAccess()
    {
    }

    /**
     * Asks EDT what it would show when hovering over an element.
     *
     * @param element the resolved model element; <code>null</code> yields <code>null</code>
     * @param offset the character offset the element was resolved at
     * @return the hover HTML, or <code>null</code> when the environment offers none
     */
    public static String hoverHtml(EObject element, int offset)
    {
        if (element == null)
        {
            return null;
        }
        try
        {
            IEObjectHoverProvider provider = hoverProvider();
            if (provider == null)
            {
                return null;
            }
            Method builder = findHoverBuilder(provider.getClass());
            if (builder == null)
            {
                return null;
            }
            builder.setAccessible(true);
            IRegion region = new Region(Math.max(0, offset), 0);
            Object input = builder.invoke(provider, element, region, null);
            return readHtml(input);
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not read the hover for a BSL element: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * The language's hover provider, taken from the same registry the rest of the plugin uses to reach
     * BSL services.
     *
     * @return the provider, or <code>null</code> when the language contributes none
     */
    private static IEObjectHoverProvider hoverProvider()
    {
        IResourceServiceProvider services = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(BslModuleAccess.BSL_LOOKUP_URI);
        if (services == null)
        {
            return null;
        }
        return services.get(IEObjectHoverProvider.class);
    }

    /**
     * Finds the three-argument hover builder on the provider or one of its ancestors.
     * <p>
     * Matched by name and shape rather than by exact parameter types: the third parameter is the
     * provider's own input class, which differs between the environment's provider and the Xtext one
     * it extends, and naming either of them here would tie this to whichever is in front today.
     * </p>
     *
     * @param type the provider class
     * @return the method, or <code>null</code> when no such method exists
     */
    private static Method findHoverBuilder(Class<?> type)
    {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
        {
            for (Method m : c.getDeclaredMethods())
            {
                if (!HOVER_METHOD.equals(m.getName()) || m.getParameterCount() != 3)
                {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params[0].isAssignableFrom(EObject.class) || EObject.class.isAssignableFrom(params[0]))
                {
                    if (IRegion.class.isAssignableFrom(params[1]))
                    {
                        return m;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Takes the HTML out of whatever the builder returned.
     *
     * @param input the builder's result
     * @return the HTML, or <code>null</code>
     * @throws Exception when the input will not answer for its contents
     */
    private static String readHtml(Object input) throws Exception
    {
        if (input == null)
        {
            return null;
        }
        Object html = ReflectionAccess.invokeMethod(input, HTML_METHOD);
        return html instanceof String && !((String)html).isEmpty() ? (String)html : null;
    }
}
