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

import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * A refusal carries the call that answers it.
 *
 * <p>A refusal that only describes what went wrong leaves the next step to be reconstructed
 * from its wording. When the next step is known - the project is not found, so list the
 * projects - the refusal hands it over as {@code helpHint}: the tool and the arguments, ready to
 * send. What sits beside the call (a suggested name) is a detail of the hint, not a second
 * prose.</p>
 */
public class ARefusalCarriesTheNextCallTest
{
    /**
     * The hint is the tool and its arguments, under one member, in the order given.
     */
    @Test
    public void theHintIsAToolAndItsArguments()
    {
        String json = ToolResult.error("nothing here") //$NON-NLS-1$
            .hint("project_admin", Map.of("operation", "list_projects")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
        JsonObject answer = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject hint = answer.getAsJsonObject("helpHint"); //$NON-NLS-1$
        assertEquals("project_admin", hint.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("list_projects", //$NON-NLS-1$
            hint.getAsJsonObject("arguments").get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A detail lands beside the call, and a null detail leaves the hint as it was; a detail with
     * no hint to sit beside is dropped rather than invented as a hint.
     */
    @Test
    public void aDetailSitsBesideTheCall()
    {
        JsonObject answer = JsonParser.parseString(ToolResult.error("nothing here") //$NON-NLS-1$
            .hint("project_admin", Map.of("operation", "list_projects")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .hintDetail("suggestedProjectName", "Demo") //$NON-NLS-1$ //$NON-NLS-2$
            .hintDetail("nothing", null) //$NON-NLS-1$
            .toJson()).getAsJsonObject();
        JsonObject hint = answer.getAsJsonObject("helpHint"); //$NON-NLS-1$
        assertEquals("Demo", hint.get("suggestedProjectName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(hint.get("nothing")); //$NON-NLS-1$

        JsonObject bare = JsonParser.parseString(ToolResult.error("nothing here") //$NON-NLS-1$
            .hintDetail("suggestedProjectName", "Demo") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson()).getAsJsonObject();
        assertNull(bare.get("helpHint")); //$NON-NLS-1$
    }

    /**
     * A project that is not found is refused with the listing call and, when one open project
     * is close to the name, that name.
     */
    @Test
    public void aMissingProjectIsRefusedWithTheListingCall()
    {
        JsonObject answer = JsonParser.parseString(
            ProjectResolver.notFound("NoSuchProjectAnywhere").toJson()).getAsJsonObject(); //$NON-NLS-1$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString().contains("Project not found")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject hint = answer.getAsJsonObject("helpHint"); //$NON-NLS-1$
        assertEquals("project_admin", hint.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("list_projects", //$NON-NLS-1$
            hint.getAsJsonObject("arguments").get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The standalone shape is the same one the builder attaches, so a helper that reports through
     * a tag map publishes the same {@code helpHint}.
     */
    @Test
    public void theStandaloneShapeMatchesTheBuilder()
    {
        Map<String, Object> call = ToolResult.nextCall("insights", //$NON-NLS-1$
            Map.of("operation", "semantic_metadata_search")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("insights", call.get("tool")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("helpHint", ToolResult.HELP_HINT); //$NON-NLS-1$
        String viaBuilder = ToolResult.error("x") //$NON-NLS-1$
            .hint("insights", Map.of("operation", "semantic_metadata_search")).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String viaTag = ToolResult.error("x").put(ToolResult.HELP_HINT, call).toJson(); //$NON-NLS-1$
        assertEquals(viaBuilder, viaTag);
    }
}
