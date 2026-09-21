/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code git_checkout} - the write door of the git facade's checkout operation, kept as a real
 * tool so a preset can switch it by name and the group table can list it.
 *
 * <p>The {@code git} facade folds it in and the Canonical preset hides it; the operation itself
 * lives in {@link GitTool} - this only names the door.</p>
 */
public class GitCheckoutTool
    extends GitTool
{
    @Override
    public String getName()
    {
        return "git_checkout"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Switch to a branch or create it and switch, inside the IDE. Alias of " //$NON-NLS-1$
            + "git operation=checkout - the facade is the way to call it."; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        Map<String, String> call = new LinkedHashMap<>(params);
        call.put("operation", "checkout"); //$NON-NLS-1$ //$NON-NLS-2$
        return super.execute(call);
    }
}
