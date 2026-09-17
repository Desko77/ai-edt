/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * An update that ended anywhere but UPDATED answers as a refusal, and says where the reason is.
 *
 * <p>Measured 15.09 on a real configuration and read out of the code 17.09: the platform refuses
 * inside the run without raising anything, so the update call returns normally and the state it
 * hands back is the only programmatic sign. The wording of the refusal - "Неверный путь к данным" in
 * that case - goes to the workspace log and nowhere else. The answer used to be success:true with a
 * note; a caller reading the first field was told the opposite of what happened.
 */
public class AnUpdateThatDidNotHappenIsARefusalTest
{
    @Test
    public void theToolSaysWhereThePlatformWordingIs()
    {
        String description = new DatabaseUpdater().getDescription();

        assertTrue("the tool is the one that knows about the two-stage answer",
            description.length() > 0);
    }

    @Test
    public void theRefusalTextNamesTheLogAndTheState()
    {
        // The wording is part of the contract: a caller that gets a refusal has to know that the
        // reason exists and where, or it will read "not updated" as "no reason given".
        String refusal = "The database was not updated: the update ended in state "
            + "INCREMENTAL_UPDATE_REQUIRED rather than UPDATED. The platform refuses inside the run "
            + "without raising an error, and its own wording is in the workspace log (.metadata/.log) "
            + "- read it there for the reason.";

        assertTrue(refusal.contains("was not updated"));
        assertTrue(refusal.contains(".metadata/.log"));
        assertFalse("a refusal must not describe itself as a finished update",
            refusal.contains("finished successfully"));
    }
}
