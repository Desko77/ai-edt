/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code update_cluster} - the write door of the cluster facade's update operation, kept as a real
 * tool so a preset can switch it by name and the group table can list it.
 *
 * <p>The {@code cluster_admin} facade folds it in and the Canonical preset hides it, so a client
 * sees one entry point per job; the old name stays callable the way every facade-covered alias
 * does. The operation itself lives in {@link ClusterAdminFacadeTool} - this only names the door.</p>
 */
public class ClusterUpdateTool
    extends ClusterAdminFacadeTool
{
    @Override
    public String getName()
    {
        return DOOR_UPDATE;
    }

    @Override
    public String getDescription()
    {
        return "Rename a cluster and set its description. Alias of " //$NON-NLS-1$
            + "cluster_admin operation=update_cluster - the facade is the way to call it."; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        Map<String, String> call = new LinkedHashMap<>(params);
        call.put("operation", DOOR_UPDATE); //$NON-NLS-1$
        return super.execute(call);
    }
}
