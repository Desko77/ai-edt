/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool.ResponseType;

/**
 * Tests for {@link InfobaseAdminFacadeTool} and {@link ConfigIoFacadeTool}.
 * <p>
 * Both facades route an operation straight to an already-registered standalone tool, so calling
 * them with a REAL operation name reaches EDT-dependent code (Activator.getDefault(), the
 * workspace, the BM) that a plain JUnit run cannot exercise. What a headless test CAN pin down
 * without that runtime is the routing table itself: which operation names the facade recognizes,
 * that an unrecognized one is rejected together with the full allowed list, and that help works.
 * Live equivalence - that operation=X on the facade produces the same result as calling the
 * standalone X directly - is verified separately, against a running EDT workspace.
 * <p>
 * One exception is exercised further here: {@code infobase_admin}'s sync_control routing remaps a
 * separate {@code syncOperation} parameter onto {@code operation} before forwarding, because
 * {@code SyncControlTool} has its own {@code operation} concept that would otherwise collide with
 * this facade's routing operation. {@link SyncControlTool#execute} checks for a missing
 * {@code projectName} before it touches the workspace, and its error message echoes back the
 * operation value it actually received - so a params map with operation=sync_control,
 * syncOperation=status and no projectName reaches that check headless and proves the remap ran,
 * without needing EDT.
 */
public class FacadeF3bTest
{
    // -- infobase_admin: tool metadata --

    @Test
    public void infobaseAdminName()
    {
        assertEquals("infobase_admin", new InfobaseAdminFacadeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void infobaseAdminResponseTypeIsMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new InfobaseAdminFacadeTool().getResponseType());
    }

    @Test
    public void infobaseAdminDescriptionNotEmpty()
    {
        String description = new InfobaseAdminFacadeTool().getDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
    }

    @Test
    public void infobaseAdminSchemaDeclaresOperationAndKeyParams()
    {
        String schema = new InfobaseAdminFacadeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"operation\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"syncOperation\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    // -- infobase_admin: routing table --

    @Test
    public void infobaseAdminMissingOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        String result = new InfobaseAdminFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void infobaseAdminEmptyOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InfobaseAdminFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void infobaseAdminUnknownOperationIsRejectedWithAllowedList()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "not_a_real_operation"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InfobaseAdminFacadeTool().execute(params);

        assertTrue(result.contains("Unknown operation")); //$NON-NLS-1$
        assertTrue(result.contains("not_a_real_operation")); //$NON-NLS-1$
        // every absorbed standalone name must be listed in the allowed set
        assertTrue(result.contains("get_applications")); //$NON-NLS-1$
        assertTrue(result.contains("create_infobase")); //$NON-NLS-1$
        assertTrue(result.contains("delete_infobase")); //$NON-NLS-1$
        assertTrue(result.contains("set_infobase_credentials")); //$NON-NLS-1$
        assertTrue(result.contains("create_launch_config")); //$NON-NLS-1$
        assertTrue(result.contains("update_database")); //$NON-NLS-1$
        assertTrue(result.contains("sync_control")); //$NON-NLS-1$
        assertTrue(result.contains("help")); //$NON-NLS-1$
    }

    @Test
    public void infobaseAdminHelpListsEveryOperation()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InfobaseAdminFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("get_applications")); //$NON-NLS-1$
        assertTrue(result.contains("create_infobase")); //$NON-NLS-1$
        assertTrue(result.contains("delete_infobase")); //$NON-NLS-1$
        assertTrue(result.contains("set_infobase_credentials")); //$NON-NLS-1$
        assertTrue(result.contains("create_launch_config")); //$NON-NLS-1$
        assertTrue(result.contains("update_database")); //$NON-NLS-1$
        assertTrue(result.contains("sync_control")); //$NON-NLS-1$
        assertFalse(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void infobaseAdminHelpAcceptsCamelCaseOperationToken()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "Help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InfobaseAdminFacadeTool().execute(params);
        assertTrue(result.contains("get_applications")); //$NON-NLS-1$
    }

    @Test
    public void infobaseAdminHelpWorkflowTopic()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", "workflow"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InfobaseAdminFacadeTool().execute(params);
        assertTrue(result.contains("operation picker")); //$NON-NLS-1$
    }

    // -- infobase_admin: sync_control operation remap --

    @Test
    public void infobaseAdminSyncControlWithoutSyncOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "sync_control"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InfobaseAdminFacadeTool().execute(params);
        assertTrue(result.contains("syncOperation")); //$NON-NLS-1$
    }

    @Test
    public void infobaseAdminSyncControlWithBlankSyncOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "sync_control"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("syncOperation", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InfobaseAdminFacadeTool().execute(params);
        assertTrue(result.contains("syncOperation")); //$NON-NLS-1$
    }

    @Test
    public void infobaseAdminSyncControlRemapsSyncOperationOntoOperationBeforeForwarding()
    {
        // No projectName: SyncControlTool.execute rejects on that before it ever touches the
        // workspace, and its error text echoes the operation value it received - so this proves
        // the remap happened without needing a live EDT.
        Map<String, String> params = new HashMap<>();
        params.put("operation", "sync_control"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("syncOperation", "status"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new InfobaseAdminFacadeTool().execute(params);

        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        // The '=' in SyncControlTool's echoed hint is JSON-escaped to = by the serializer,
        // so match either form. The point is operation=status reached it, not operation=sync_control.
        assertTrue("SyncControlTool must have received operation=status (the syncOperation " //$NON-NLS-1$
            + "value), not operation=sync_control (this facade's own routing operation): " //$NON-NLS-1$
            + result, result.contains("operation\\u003dstatus") //$NON-NLS-1$
                || result.contains("operation=status")); //$NON-NLS-1$
    }

    // -- config_io: tool metadata --

    @Test
    public void configIoName()
    {
        assertEquals("config_io", new ConfigIoFacadeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void configIoResponseTypeIsMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new ConfigIoFacadeTool().getResponseType());
    }

    @Test
    public void configIoDescriptionNotEmpty()
    {
        String description = new ConfigIoFacadeTool().getDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
    }

    @Test
    public void configIoSchemaDeclaresOperationAndKeyParams()
    {
        String schema = new ConfigIoFacadeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"operation\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"outputPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    // -- config_io: routing table --

    @Test
    public void configIoMissingOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ConfigIoFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void configIoEmptyOperationIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ConfigIoFacadeTool().execute(params);
        assertTrue(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void configIoUnknownOperationIsRejectedWithAllowedList()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "not_a_real_operation"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ConfigIoFacadeTool().execute(params);

        assertTrue(result.contains("Unknown operation")); //$NON-NLS-1$
        assertTrue(result.contains("not_a_real_operation")); //$NON-NLS-1$
        // every absorbed standalone name must be listed in the allowed set
        assertTrue(result.contains("export_configuration_to_xml")); //$NON-NLS-1$
        assertTrue(result.contains("import_configuration_from_xml")); //$NON-NLS-1$
        assertTrue(result.contains("export_object")); //$NON-NLS-1$
        assertTrue(result.contains("export_common_picture")); //$NON-NLS-1$
        assertTrue(result.contains("help")); //$NON-NLS-1$
    }

    @Test
    public void configIoHelpListsEveryOperation()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ConfigIoFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("export_configuration_to_xml")); //$NON-NLS-1$
        assertTrue(result.contains("import_configuration_from_xml")); //$NON-NLS-1$
        assertTrue(result.contains("export_object")); //$NON-NLS-1$
        assertTrue(result.contains("export_common_picture")); //$NON-NLS-1$
        assertFalse(result.contains("operation is required")); //$NON-NLS-1$
    }

    @Test
    public void configIoHelpAcceptsCamelCaseOperationToken()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "Help"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ConfigIoFacadeTool().execute(params);
        assertTrue(result.contains("export_configuration_to_xml")); //$NON-NLS-1$
    }

    @Test
    public void configIoHelpWorkflowTopic()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", "workflow"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ConfigIoFacadeTool().execute(params);
        assertTrue(result.contains("operation picker")); //$NON-NLS-1$
    }

    // -- cross-facade sanity --

    @Test
    public void facadeNamesAreDistinctFromEachOther()
    {
        assertFalse(InfobaseAdminFacadeTool.NAME.equals(ConfigIoFacadeTool.NAME));
    }
}
