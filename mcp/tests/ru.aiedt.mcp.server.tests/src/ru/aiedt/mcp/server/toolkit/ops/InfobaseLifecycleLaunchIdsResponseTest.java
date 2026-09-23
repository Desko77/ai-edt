/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper.CreateResult;
import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper.DeleteResult;

/** Lifecycle operation responses expose what their infobase-list guard repaired. */
public class InfobaseLifecycleLaunchIdsResponseTest
{
    @Test
    public void createInfobaseResponseCarriesLaunchApplicationIds()
    {
        CreateResult result = new CreateResult();
        result.ok = true;
        result.infobaseName = "new-base"; //$NON-NLS-1$
        result.path = "C:/bases/new-base"; //$NON-NLS-1$
        result.launchApplicationIds = "restored both foreign configurations"; //$NON-NLS-1$

        String json = InfobaseCreator.response("new-base", result); //$NON-NLS-1$

        assertTrue(json.contains("\"launchApplicationIds\":\"restored both foreign configurations\"")); //$NON-NLS-1$
    }

    @Test
    public void deleteInfobaseResponseCarriesLaunchApplicationIds()
    {
        DeleteResult result = new DeleteResult();
        result.ok = true;
        result.launchApplicationIds = "left deleted unbound; restored foreign"; //$NON-NLS-1$

        String json = InfobaseRemover.response("deleted-base", result); //$NON-NLS-1$

        assertTrue(json.contains("\"launchApplicationIds\":\"left deleted unbound; restored foreign\"")); //$NON-NLS-1$
    }
}
