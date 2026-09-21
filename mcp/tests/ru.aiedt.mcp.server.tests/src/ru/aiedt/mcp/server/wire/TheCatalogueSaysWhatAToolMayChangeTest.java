/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ops.GitCommitTool;
import ru.aiedt.mcp.server.toolkit.ops.GitTool;
import ru.aiedt.mcp.server.toolkit.ops.ModulesLister;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter;
import ru.aiedt.mcp.server.wire.jsonrpc.ToolsListResult;

/**
 * The catalogue says what class of tool each entry is.
 *
 * <p>MCP lets a tool carry {@code annotations} - read-only, idempotent, open-world - and a client
 * that reads them can skip a confirmation for a read and ask before a write. The class is not a
 * second opinion kept beside the presets: the Read-only preset is the authority on what writes,
 * and the hints are read off it, so the two cannot disagree. Only a hint that differs from the
 * spec's default is published.</p>
 */
public class TheCatalogueSaysWhatAToolMayChangeTest
{
    @After
    public void theCatalogIsCleared()
    {
        McpToolCatalog.getInstance().clear();
    }

    /**
     * A tool the Read-only preset leaves on reads only; a tool it switches off writes. The read
     * carries the two hints that differ from the defaults, the write carries none of them.
     */
    @Test
    public void aReadIsReadOnlyAndAWriteIsNot()
    {
        McpToolCatalog registry = McpToolCatalog.getInstance();
        Map<String, Object> read = ToolAnnotations.of(new ModulesLister(), registry);
        assertEquals(Boolean.TRUE, read.get("readOnlyHint")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, read.get("idempotentHint")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, read.get("openWorldHint")); //$NON-NLS-1$

        Map<String, Object> write = ToolAnnotations.of(new ModuleSourceWriter(), registry);
        assertNull(write.get("readOnlyHint")); //$NON-NLS-1$
        assertNull(write.get("idempotentHint")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, write.get("openWorldHint")); //$NON-NLS-1$
        assertTrue(ToolProfile.READ_ONLY.getDisabledTools().contains("write_module_source")); //$NON-NLS-1$
    }

    /**
     * The git facade is a reader while its write doors are off and a writer once one is on: the
     * hint follows the preset rather than the tool's widest reach.
     */
    @Test
    public void aFacadeWithGatedWritesFollowsItsDoors()
    {
        McpToolCatalog registry = McpToolCatalog.getInstance();
        GitTool git = new GitTool();
        registry.register(git);
        assertEquals(Boolean.TRUE, ToolAnnotations.of(git, registry).get("readOnlyHint")); //$NON-NLS-1$

        registry.register(new GitCommitTool());
        assertNull(ToolAnnotations.of(git, registry).get("readOnlyHint")); //$NON-NLS-1$
    }

    /**
     * A read that lives in a group the preset takes off wholesale is still published as a read.
     */
    @Test
    public void aReadInsideAWriteGroupIsStillARead()
    {
        for (String name : ToolAnnotations.READS_INSIDE_WRITE_GROUPS)
        {
            assertTrue(name + " is not disabled by Read-only, so it needs no exception", //$NON-NLS-1$
                ToolProfile.READ_ONLY.getDisabledTools().contains(name));
        }
        IMcpTool applications = new IMcpTool()
        {
            @Override
            public String getName()
            {
                return "get_applications"; //$NON-NLS-1$
            }

            @Override
            public String getDescription()
            {
                return ""; //$NON-NLS-1$
            }

            @Override
            public String getInputSchema()
            {
                return "{}"; //$NON-NLS-1$
            }

            @Override
            public String execute(Map<String, String> params)
            {
                return ""; //$NON-NLS-1$
            }
        };
        assertEquals(Boolean.TRUE, ToolAnnotations.of(applications, McpToolCatalog.getInstance()).get("readOnlyHint")); //$NON-NLS-1$
    }

    /**
     * On the wire the hints sit under {@code annotations}, and a tool with only default hints
     * carries no such member.
     */
    @Test
    public void onTheWireTheHintsSitUnderAnnotations()
    {
        ToolsListResult catalogue = new ToolsListResult();
        catalogue.addTool("read", "", JsonParser.parseString("{}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Map.of("readOnlyHint", Boolean.TRUE)); //$NON-NLS-1$
        catalogue.addTool("plain", "", JsonParser.parseString("{}"), Map.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject document = JsonParser.parseString(ToolResult.toJsonStatic(catalogue)).getAsJsonObject();
        List<JsonObject> tools = document.getAsJsonArray("tools").asList().stream() //$NON-NLS-1$
            .map(e -> e.getAsJsonObject()).toList();
        assertTrue(tools.get(0).getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(tools.get(1).has("annotations")); //$NON-NLS-1$
    }
}
