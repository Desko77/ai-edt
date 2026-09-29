/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Holds the YAxUnit surface to what it says it accepts.
 * <p>
 * Markdown descriptions and schemas are what a caller builds its call from, so a promise there is
 * a promise the server has to keep. Measured: the facade, both runners and two help topics offered
 * {@code suites=}, {@code tags=} and {@code contexts=} as filters long after the launch
 * configuration stopped carrying them, and a call built from that advice ran every test in the
 * suite while reading as a narrowed run. The arguments are refused now, and these tests keep the
 * offer from coming back in either direction - in the text a reader copies from, and in the schema
 * a client generates its call from.
 * </p>
 * <p>
 * These read descriptions and schemas only: nothing here launches a client, so it runs in the
 * headless test runtime.
 * </p>
 */
public class WhatTheYaxunitSurfacePromisesTest
{
    private static final String[] UNAPPLIED = {"suites", "tags", "contexts"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String[] APPLIED = {"extensions", "modules", "tests"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static IMcpTool[] theSurface()
    {
        return new IMcpTool[] {
            new YaxunitTestsTool(), new YaxunitTestRunner(), new YaxunitDebugRunner() };
    }

    private static JsonObject properties(IMcpTool tool)
    {
        return JsonParser.parseString(tool.getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
    }

    /**
     * The facade describes the filters a caller may use and says what happens to the others; none
     * of the three tools offers one it does not apply.
     * <p>
     * The facade is the entry point whose description a caller reads first, so the three filters
     * and the refusal are promised there. The two runners declare the same three in their schemas
     * (asserted below) without enumerating them in prose, and neither may offer the others.
     */
    @Test
    public void theFacadeDescribesTheThreeFiltersAndRefusesTheRest()
    {
        String description = new YaxunitTestsTool().getDescription();
        for (String applied : APPLIED)
        {
            assertTrue("the facade has to name the filter " + applied, description.contains(applied)); //$NON-NLS-1$
        }
        assertTrue("and say that a filter it does not apply is refused: " + description, //$NON-NLS-1$
            description.contains("refused")); //$NON-NLS-1$

        for (IMcpTool tool : theSurface())
        {
            String text = tool.getDescription();
            for (String refused : UNAPPLIED)
            {
                assertFalse(tool.getName() + " still offers " + refused + " as a filter", //$NON-NLS-1$ //$NON-NLS-2$
                    text.contains(refused + "=")); //$NON-NLS-1$
            }
        }
    }

    /**
     * The schema a client builds its call from declares the three filters and neither of the
     * others, so an unapplied one cannot be constructed from it.
     */
    @Test
    public void theSchemaDeclaresNoFilterTheLaunchDoesNotApply()
    {
        for (IMcpTool tool : theSurface())
        {
            JsonObject properties = properties(tool);
            for (String applied : APPLIED)
            {
                assertTrue(tool.getName() + " must declare " + applied, //$NON-NLS-1$
                    properties.has(applied));
            }
            for (String refused : UNAPPLIED)
            {
                assertFalse(tool.getName() + " declares " + refused, properties.has(refused)); //$NON-NLS-1$
            }
        }
    }

    /**
     * The flags that decide whether anything is launched are declared as the booleans they are:
     * a client generates its call from the declared type, and a schema calling a flag a string
     * invites a spelling the reader has to guess at.
     */
    @Test
    public void theLaunchDecidingFlagsAreDeclaredAsBooleans()
    {
        for (IMcpTool tool : theSurface())
        {
            JsonObject properties = properties(tool);
            assertEquals(tool.getName(), "boolean", //$NON-NLS-1$
                properties.getAsJsonObject("updateBeforeLaunch").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            if (!(tool instanceof YaxunitDebugRunner))
            {
                // Debug mode starts a launch and never reads a report, so the flag that asks for
                // one is not part of that mode's surface.
                assertEquals(tool.getName(), "boolean", //$NON-NLS-1$
                    properties.getAsJsonObject("reuseRecent").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                assertFalse(tool.getName(), properties.has("reuseRecent")); //$NON-NLS-1$
            }
        }
    }
}
