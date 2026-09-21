/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;

/**
 * What another bundle can plug into this server, and how: it publishes an OSGi service, and
 * the service is picked up here for as long as it is registered.
 *
 * <ul>
 * <li>An {@link IMcpTool} service becomes a callable tool. The property {@value #WRITES}
 * ({@code true} / {@code false}) says whether the tool changes anything; a write-blocking preset
 * switches a writing tool off with this server's own writers. Absent, the tool counts as one that
 * writes.</li>
 * <li>An {@link IModuleSourceProvider} service supplies modules the module tools read and write by
 * address though no file backs them.</li>
 * </ul>
 */
public final class ToolWhiteboard
{
    /** Service property of an {@link IMcpTool}: whether the tool writes. */
    public static final String WRITES = "ru.aiedt.mcp.tool.writes"; //$NON-NLS-1$

    private ServiceTracker<IMcpTool, IMcpTool> tools;

    private ServiceTracker<IModuleSourceProvider, IModuleSourceProvider> providers;

    /**
     * Starts watching the services.
     *
     * @param context this bundle's context
     */
    public void open(BundleContext context)
    {
        tools = new ServiceTracker<>(context, IMcpTool.class, new ServiceTrackerCustomizer<IMcpTool, IMcpTool>()
        {
            @Override
            public IMcpTool addingService(ServiceReference<IMcpTool> reference)
            {
                IMcpTool tool = context.getService(reference);
                if (tool != null)
                {
                    McpToolCatalog.getInstance().registerExternal(tool, writes(reference));
                    Activator.logInfo("tool from " + reference.getBundle().getSymbolicName() + ": " + tool.getName()); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return tool;
            }

            @Override
            public void modifiedService(ServiceReference<IMcpTool> reference, IMcpTool tool)
            {
                McpToolCatalog.getInstance().registerExternal(tool, writes(reference));
            }

            @Override
            public void removedService(ServiceReference<IMcpTool> reference, IMcpTool tool)
            {
                McpToolCatalog.getInstance().unregisterExternal(tool);
                context.ungetService(reference);
            }
        });
        tools.open();
        providers = new ServiceTracker<>(context, IModuleSourceProvider.class,
            new ServiceTrackerCustomizer<IModuleSourceProvider, IModuleSourceProvider>()
            {
                @Override
                public IModuleSourceProvider addingService(ServiceReference<IModuleSourceProvider> reference)
                {
                    IModuleSourceProvider provider = context.getService(reference);
                    ModuleSources.register(provider);
                    return provider;
                }

                @Override
                public void modifiedService(ServiceReference<IModuleSourceProvider> reference,
                    IModuleSourceProvider provider)
                {
                    // the provider's identity is what the registry keys on; a property change is nothing to it
                }

                @Override
                public void removedService(ServiceReference<IModuleSourceProvider> reference,
                    IModuleSourceProvider provider)
                {
                    ModuleSources.unregister(provider);
                    context.ungetService(reference);
                }
            });
        providers.open();
    }

    /**
     * Stops watching; the tools and providers picked up are let go.
     */
    public void close()
    {
        if (tools != null)
        {
            tools.close();
            tools = null;
        }
        if (providers != null)
        {
            providers.close();
            providers = null;
        }
    }

    private static boolean writes(ServiceReference<?> reference)
    {
        Object value = reference.getProperty(WRITES);
        if (value instanceof Boolean)
        {
            return ((Boolean)value).booleanValue();
        }
        // A tool that does not say counts as a writer: the safe side of a preset that blocks writes.
        return value == null || !"false".equalsIgnoreCase(String.valueOf(value)); //$NON-NLS-1$
    }
}
