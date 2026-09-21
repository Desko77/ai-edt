/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code git_commit} - the write door of the git facade's commit operation, kept as a real tool
 * so a preset can switch it by name and the group table can list it.
 *
 * <p>The {@code git} facade folds it in and the Canonical preset hides it, so a client sees one
 * entry point per job; the old name stays callable the way every facade-covered alias does. The
 * operation itself lives in {@link GitTool} - this only names the door.</p>
 */
public class GitCommitTool
    extends GitTool
{
    @Override
    public String getName()
    {
        return "git_commit"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Stage named paths and commit them, inside the IDE. Alias of " //$NON-NLS-1$
            + "git operation=commit - the facade is the way to call it."; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        Map<String, String> call = new LinkedHashMap<>(params);
        call.put("operation", "commit"); //$NON-NLS-1$ //$NON-NLS-2$
        return super.execute(call);
    }
}
