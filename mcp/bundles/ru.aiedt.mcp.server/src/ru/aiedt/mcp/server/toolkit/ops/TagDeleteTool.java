/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code delete_tag} - the write door of the tag facade's delete_tag operation, kept as a real
 * tool so a preset can switch it by name and the group table can list it.
 *
 * <p>The {@code tag_admin} facade folds it in and the Canonical preset hides it, so a client
 * sees one entry point per job; the old name stays callable the way every facade-covered alias
 * does. The operation itself lives in {@link TagAdminFacadeTool} - this only names the door.</p>
 */
public class TagDeleteTool
    extends TagAdminFacadeTool
{
    @Override
    public String getName()
    {
        return DOOR_DELETE;
    }

    @Override
    public String getDescription()
    {
        return "Delete a tag and take it off every object; dryRun answers the assignment count. Alias of tag_admin operation=delete_tag - the facade is the way to call it.";
    }

    @Override
    public String execute(Map<String, String> params)
    {
        Map<String, String> call = new LinkedHashMap<>(params);
        call.put("operation", DOOR_DELETE); //$NON-NLS-1$
        return super.execute(call);
    }
}
