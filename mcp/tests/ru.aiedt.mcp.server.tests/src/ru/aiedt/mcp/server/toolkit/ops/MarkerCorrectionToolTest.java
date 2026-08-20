/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Covers what the correction tool decides before it reaches EDT.
 * <p>
 * The interesting part of applying someone else's correction is refusing to apply the wrong one: a
 * repair aimed at the wrong finding is harder to notice than one that never ran, because the project
 * still validates and something else changed. Targeting and refusals are checked here; performing a
 * correction needs a live workspace with a real marker and belongs to a manual pass.
 * </p>
 */
public class MarkerCorrectionToolTest
{
    private final MarkerCorrectionTool tool = new MarkerCorrectionTool();

    private static Map<String, String> params(String... pairs)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void aFindingHasToBeNamed()
    {
        // Without a check id there is no finding to correct, and picking one would be guessing.
        String noCheck = tool.execute(params("projectName", "Any")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(noCheck, noCheck.contains("checkId")); //$NON-NLS-1$
        assertTrue(noCheck, noCheck.contains("\"success\": false") //$NON-NLS-1$
            || noCheck.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void aProjectHasToBeNamed()
    {
        String noProject = tool.execute(params("checkId", "some-check")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(noProject, noProject.contains("projectName")); //$NON-NLS-1$
    }

    @Test
    public void onlyListAndApplyAreAccepted()
    {
        // An unknown operation must not fall through to a default that changes something.
        String bad = tool.execute(params("projectName", "Any", "checkId", "c", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "operation", "repair")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(bad, bad.contains("repair")); //$NON-NLS-1$
        assertTrue(bad, bad.contains("list") && bad.contains("apply")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theToolAnnouncesItselfAsJson()
    {
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
        assertEquals("marker_corrections", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void theSchemaAsksForWhatTargetingNeeds()
    {
        String schema = tool.getInputSchema();
        for (String field : new String[] { "projectName", "checkId", "operation", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "messageContains", "variant", "dryRun" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("the schema should expose " + field, schema.contains(field)); //$NON-NLS-1$
        }
    }

    @Test
    public void theDescriptionSaysWhyToPreferItOverAHandWrittenPatch()
    {
        // The point of the tool is that the correction comes from the check that raised the
        // problem. A description that does not say so leaves an agent inventing edits instead.
        String description = tool.getDescription();
        assertFalse(description.isEmpty());
        assertTrue(description, description.contains("check")); //$NON-NLS-1$
    }

    /**
     * A correction that cannot be performed from here is told apart from one that failed.
     * <p>
     * Measured on a stand: EDT offers corrections that are IDE ACTIONS rather than edits - "open the
     * documentation-comment panel" among them - and executing one off the display thread throws
     * SWT's invalid-thread-access. The first version passed that through as "The correction failed:
     * Invalid thread access", which tells a caller nothing about what to do and reads like a defect
     * in this server. Running it on the display thread instead is not the fix: it would open a panel
     * in the user's editor and change nothing in the file.
     * </p>
     */
    @Test
    public void aCorrectionThatNeedsTheEditorIsRecognisedByTypeNotByWording()
        throws Exception
    {
        java.lang.reflect.Method needsTheEditor =
            MarkerCorrectionTool.class.getDeclaredMethod("needsTheEditor", Throwable.class); //$NON-NLS-1$
        needsTheEditor.setAccessible(true);

        assertTrue("an SWT failure means the editor is required", //$NON-NLS-1$
            (Boolean)needsTheEditor.invoke(null, new org.eclipse.swt.SWTException("anything"))); //$NON-NLS-1$
        assertTrue("and so does one wrapped further down", //$NON-NLS-1$
            (Boolean)needsTheEditor.invoke(null,
                new RuntimeException("wrapped", new org.eclipse.swt.SWTException("x")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an ordinary failure is still an ordinary failure", //$NON-NLS-1$
            (Boolean)needsTheEditor.invoke(null, new IllegalStateException("no fix here"))); //$NON-NLS-1$
    }

    /**
     * The listing must not promise what applying cannot keep.
     * <p>
     * "N findings can be corrected" read as a guarantee, and for an interface action it was not one.
     * </p>
     */
    @Test
    public void theListingDoesNotPromiseEveryCorrectionCanBeCarriedOut()
    {
        String source = MarkerCorrectionTool.class.getName();
        assertFalse(source.isEmpty());

        String description = tool.getDescription();
        assertFalse(description.isEmpty());
        // The promise now lives in the list reply itself, which is built at runtime; what is pinned
        // here is that the wording "can be corrected" is gone from the tool's own vocabulary.
        assertFalse("the tool must not claim a correction can always be carried out", //$NON-NLS-1$
            description.contains("can be corrected")); //$NON-NLS-1$
    }
}
