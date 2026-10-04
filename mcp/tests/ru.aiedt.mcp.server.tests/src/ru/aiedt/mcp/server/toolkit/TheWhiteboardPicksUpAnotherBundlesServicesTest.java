/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.wire.ToolAnnotations;

/**
 * A tool or a module source provider another bundle publishes as an OSGi service is picked up by
 * the whiteboard for as long as it is registered: the tool is callable, survives a clear of the
 * catalog, and is switched off by a write-blocking preset when its bundle says it writes; the
 * provider answers the module registry.
 */
public class TheWhiteboardPicksUpAnotherBundlesServicesTest
{
    private static final String READER = "whiteboard_probe_reader"; //$NON-NLS-1$

    private static final String WRITER = "whiteboard_probe_writer"; //$NON-NLS-1$

    private static final String SILENT = "whiteboard_probe_silent"; //$NON-NLS-1$

    private static final String OWNED = "whiteboard_probe_owned"; //$NON-NLS-1$

    private BundleContext context;

    private ToolWhiteboard whiteboard;

    private final List<ServiceRegistration<?>> registrations = new ArrayList<>();

    private IPreferenceStore store;

    private String presetBefore;

    @Before
    public void aWhiteboardOnTheLiveContext()
    {
        context = FrameworkUtil.getBundle(ToolWhiteboard.class).getBundleContext();
        whiteboard = new ToolWhiteboard();
        whiteboard.open(context);
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
    }

    @After
    public void theServicesAndThePresetGo()
    {
        for (ServiceRegistration<?> registration : registrations)
        {
            try
            {
                registration.unregister();
            }
            catch (IllegalStateException alreadyGone)
            {
                // unregistered by the test itself
            }
        }
        whiteboard.close();
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    /** A registered tool is callable by name, kept across a clear, and gone when unregistered. */
    @Test
    public void aToolServiceIsCallableWhileRegistered()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        Probe reader = new Probe(READER);
        ServiceRegistration<IMcpTool> registration = publishTool(reader, Boolean.FALSE);

        assertSame(reader, catalog.getTool(READER));
        assertTrue(catalog.getExternalTools().containsKey(READER));
        assertTrue(catalog.isToolEnabled(READER));

        catalog.clear();
        assertSame("a clear forgets this server's tools, not another bundle's", reader, catalog.getTool(READER)); //$NON-NLS-1$

        registration.unregister();
        assertNull(catalog.getTool(READER));
        assertFalse(catalog.getExternalTools().containsKey(READER));
    }

    /** A write-blocking preset switches off the tool that writes and leaves the reader on. */
    @Test
    public void aWriteBlockingPresetSwitchesOffTheWriter()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        Probe reader = new Probe(READER);
        Probe writer = new Probe(WRITER);
        Probe silent = new Probe(SILENT);
        publishTool(reader, Boolean.FALSE);
        publishTool(writer, Boolean.TRUE);
        publishTool(silent, null);

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        assertTrue(catalog.isToolEnabled(WRITER));
        assertTrue(catalog.isToolEnabled(SILENT));
        assertTrue(names(catalog.getEnabledTools()).contains(WRITER));

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        assertTrue(catalog.isToolEnabled(READER));
        assertFalse(catalog.isToolEnabled(WRITER));
        assertTrue(catalog.isDisabledByPreset(WRITER));
        assertFalse("a tool that does not say whether it writes counts as a writer", catalog.isToolEnabled(SILENT)); //$NON-NLS-1$
        Collection<String> enabled = names(catalog.getEnabledTools());
        assertTrue(enabled.contains(READER));
        assertFalse(enabled.contains(WRITER));

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        assertTrue(catalog.isToolEnabled(WRITER));
    }

    /**
     * The catalogue classes a tool from another bundle by the bundle's own word: a declared
     * writer - and a tool that said nothing, which counts as a writer - carries no read hints.
     */
    @Test
    public void theCatalogueSaysAnExternalWriterWrites()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        Probe writer = new Probe(WRITER);
        Probe silent = new Probe(SILENT);
        publishTool(writer, Boolean.TRUE);
        publishTool(silent, null);

        assertNull(ToolAnnotations.of(writer, catalog).get("readOnlyHint")); //$NON-NLS-1$
        assertNull(ToolAnnotations.of(writer, catalog).get("idempotentHint")); //$NON-NLS-1$
        assertNull(ToolAnnotations.of(silent, catalog).get("readOnlyHint")); //$NON-NLS-1$
    }

    /** A declared reader keeps its read hints. */
    @Test
    public void theCatalogueSaysAnExternalReaderReads()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        Probe reader = new Probe(READER);
        publishTool(reader, Boolean.FALSE);

        assertSame(reader, catalog.getTool(READER));
        assertEquals(Boolean.TRUE, ToolAnnotations.of(reader, catalog).get("readOnlyHint")); //$NON-NLS-1$
    }

    /**
     * A tool another bundle publishes under a name that is already taken is refused, and its
     * declared write flag is not applied to the tool that owns the name; withdrawing the refused
     * service leaves the owner in place.
     */
    @Test
    public void aRefusedExternalToolNeitherReclassifiesNorRemovesTheOwner()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        Probe owner = new Probe(OWNED);
        catalog.register(owner);
        try
        {
            Probe intruder = new Probe(OWNED);
            ServiceRegistration<IMcpTool> registration = publishTool(intruder, Boolean.FALSE);

            assertSame("the owner keeps the name", owner, catalog.getTool(OWNED)); //$NON-NLS-1$
            assertNull("the refused declaration is not recorded", //$NON-NLS-1$
                catalog.externalWritesDeclared(OWNED));

            registration.unregister();
            registrations.remove(registration);
            assertSame("withdrawing the refused service leaves the owner", owner, //$NON-NLS-1$
                catalog.getTool(OWNED));
        }
        finally
        {
            catalog.unregister(OWNED);
        }
    }

    /** A module source provider service answers the registry while registered. */
    @Test
    public void aProviderServiceAnswersTheRegistryWhileRegistered()
    {
        IModuleSourceProvider provider = new IModuleSourceProvider()
        {
            @Override
            public String kind()
            {
                return "WhiteboardProbe"; //$NON-NLS-1$
            }

            @Override
            public IModuleSource locate(IProject project, String modulePath)
            {
                return null;
            }

            @Override
            public List<IModuleSource> list(IProject project)
            {
                return List.of();
            }

            @Override
            public String coverage(Collection<IProject> projects, String what)
            {
                return null;
            }
        };
        ServiceRegistration<IModuleSourceProvider> registration =
            context.registerService(IModuleSourceProvider.class, provider, null);
        registrations.add(registration);
        assertTrue(ModuleSources.providers().contains(provider));

        registration.unregister();
        assertFalse(ModuleSources.providers().contains(provider));
    }

    private ServiceRegistration<IMcpTool> publishTool(IMcpTool tool, Boolean writes)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        if (writes != null)
        {
            properties.put(ToolWhiteboard.WRITES, writes);
        }
        ServiceRegistration<IMcpTool> registration = context.registerService(IMcpTool.class, tool, properties);
        registrations.add(registration);
        return registration;
    }

    private static List<String> names(Collection<IMcpTool> tools)
    {
        List<String> names = new ArrayList<>();
        for (IMcpTool tool : tools)
        {
            names.add(tool.getName());
        }
        return names;
    }

    /** The least a tool can be. */
    private static final class Probe implements IMcpTool
    {
        private final String name;

        Probe(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "a probe"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "{}"; //$NON-NLS-1$
        }
    }
}
