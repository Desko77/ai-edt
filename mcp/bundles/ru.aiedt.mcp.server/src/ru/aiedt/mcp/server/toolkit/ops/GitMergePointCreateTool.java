/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code git_create_merge_restore_point} - the write door of the git facade's
 * create_merge_restore_point operation, kept as a real tool so a preset can switch it by name and
 * the group table can list it.
 *
 * <p>Taking a point does not touch the work tree, but it writes elsewhere: a commit on a ref in the
 * repository, or a copy of the project directory beside the workspace. That is why the door exists
 * - a read-only preset refuses the call before a project file is listed.</p>
 *
 * <p>The {@code git} facade folds it in and the Canonical preset hides it; the operation itself
 * lives in {@link GitTool} - this only names the door.</p>
 */
public class GitMergePointCreateTool
    extends GitTool
{
    /** The name a preset switches this write off under. */
    public static final String DOOR = "git_create_merge_restore_point"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return DOOR;
    }

    @Override
    public String getDescription()
    {
        return "Record the project files as a merge restore point, inside the IDE. Alias of " //$NON-NLS-1$
            + "git operation=create_merge_restore_point - the facade is the way to call it."; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        Map<String, String> call = new LinkedHashMap<>(params);
        call.put("operation", "create_merge_restore_point"); //$NON-NLS-1$ //$NON-NLS-2$
        return super.execute(call);
    }
}
