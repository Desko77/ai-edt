/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.jsonrpc.ToolCallResult;

/**
 * A refusal from a markdown tool has to arrive in a block the client will render.
 * <p>
 * A markdown answer is delivered as an embedded resource. That is right for a result and wrong for a
 * refusal: the content array then holds a resource and nothing else, so a client handed
 * {@code isError: true} finds no text to show and prints its own words instead. Claude Code prints
 * "Unknown error".
 * </p>
 * <p>
 * Measured on the stand 2026-08-28. The server answered
 * {@code {"content":[{"type":"resource", ...}], "isError":true}} with the real sentence - it named
 * the argument and the shape it wanted - inside the resource, and the caller read "Unknown error".
 * That is what made three modes of {@code write_module_source} look like they threw on a large
 * module: they were refusing, and every refusal was invisible.
 * </p>
 */
public class ARefusalCarriesATextBlockTest
{
    /** A markdown tool, which is the shape the defect lived in. */
    private static final class MarkdownTool implements IMcpTool
    {
        @Override
        public String getName()
        {
            return "write_module_source"; //$NON-NLS-1$
        }

        @Override
        public String getDescription()
        {
            return "a tool whose answers are markdown"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return EMPTY;
        }

        @Override
        public ResponseType getResponseType()
        {
            return ResponseType.MARKDOWN;
        }
    }

    private static final String EMPTY = ""; //$NON-NLS-1$

    private static final String REFUSAL =
        "Error: modulePath is a path to a .bsl file inside the project"; //$NON-NLS-1$

    private static Map<String, String> arguments()
    {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        return map;
    }

    private static String typeOfSoleBlock(ToolCallResult result)
    {
        assertEquals("one block is what this shape produces", 1, result.getContent().size()); //$NON-NLS-1$
        return result.getContent().get(0).getType();
    }

    @Test
    public void aRefusalArrivesAsText()
    {
        ToolCallResult shaped = McpRequestRouter.shapeResult(new MarkdownTool(), arguments(),
            REFUSAL, null, false);

        assertEquals("a resource alone leaves the caller with nothing to read", //$NON-NLS-1$
            "text", typeOfSoleBlock(shaped)); //$NON-NLS-1$
        assertEquals("and the answer still says it failed", Boolean.TRUE, shaped.getIsError()); //$NON-NLS-1$
    }

    @Test
    public void theRefusalTextIsTheOneTheToolWrote()
    {
        ToolCallResult shaped = McpRequestRouter.shapeResult(new MarkdownTool(), arguments(),
            REFUSAL, null, false);

        String carried = shaped.getContent().get(0).getText();
        assertTrue(carried, carried.contains("modulePath")); //$NON-NLS-1$
        assertTrue("the caller has to learn what shape was wanted", //$NON-NLS-1$
            carried.contains(".bsl")); //$NON-NLS-1$
    }

    @Test
    public void anAnswerThatSucceededStillTravelsAsAResource()
    {
        // The resource is what a markdown result is for - only the failing case changes.
        ToolCallResult shaped = McpRequestRouter.shapeResult(new MarkdownTool(), arguments(),
            "# Written\n\nThe module now has the method.", null, false); //$NON-NLS-1$

        assertEquals("resource", typeOfSoleBlock(shaped)); //$NON-NLS-1$
    }

    @Test
    public void plainTextModeIsUnchanged()
    {
        ToolCallResult shaped = McpRequestRouter.shapeResult(new MarkdownTool(), arguments(),
            "# Written", null, true); //$NON-NLS-1$

        assertEquals("text", typeOfSoleBlock(shaped)); //$NON-NLS-1$
    }
}
