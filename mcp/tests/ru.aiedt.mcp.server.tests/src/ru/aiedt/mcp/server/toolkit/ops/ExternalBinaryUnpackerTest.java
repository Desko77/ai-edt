/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.settings.ToolCategory;
import ru.aiedt.mcp.server.settings.ToolProfile;

/**
 * Covers the decisions {@code unpack_external_binary} makes before it ever starts a Designer: which
 * files it will take, and which it refuses on purpose.
 * <p>
 * The refusal of {@code .cf} and {@code .cfe} is the part worth pinning. Converting one of those
 * means loading it into an infobase, which replaces that infobase's configuration - a destructive
 * act that must not happen as a side effect of asking to read a file. A future change that quietly
 * makes it "just work" would be a data-loss bug wearing the shape of a convenience.
 * </p>
 */
public class ExternalBinaryUnpackerTest
{
    @Test
    public void awholeConfigurationIsRefusedWithAReasonNotJustRejected()
    {
        String out = run("C:/incoming/ЗарплатаКадры.cf"); //$NON-NLS-1$
        assertTrue("a .cf must be refused", out.contains("whole configuration")); //$NON-NLS-1$ //$NON-NLS-2$
        // Gson escapes the apostrophe, so match a stretch that has none.
        assertTrue("the refusal has to say what it would have overwritten", //$NON-NLS-1$
            out.contains("loaded into an infobase first")); //$NON-NLS-1$
        assertTrue("and point somewhere useful", out.contains("install_extension")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anExtensionFileIsRefusedForTheSameReason()
    {
        // .cfe travels the same destructive path as .cf; treating it as "just a smaller file" is
        // exactly the mistake this guard exists to stop.
        assertTrue(run("C:/incoming/Расш1.cfe").contains("whole configuration")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnrelatedFileIsRefusedBeforeAnyDesignerRuns()
    {
        String out = run("C:/incoming/notes.txt"); //$NON-NLS-1$
        assertTrue("only external objects are handled", //$NON-NLS-1$
            out.contains("not an external object")); //$NON-NLS-1$
    }

    @Test
    public void theExtensionCheckIgnoresCase()
    {
        // Files arrive from Windows and from mail clients; ".EPF" is the same file.
        String out = run("C:/incoming/Обработка.EPF"); //$NON-NLS-1$
        assertTrue("an upper-case .EPF must not be rejected as unrelated", //$NON-NLS-1$
            !out.contains("not an external object")); //$NON-NLS-1$
    }

    @Test
    public void everyPathArgumentIsRequired()
    {
        Map<String, String> params = new HashMap<>();
        assertTrue(new ExternalBinaryUnpacker().execute(params).contains("projectName is required")); //$NON-NLS-1$
        params.put("projectName", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(new ExternalBinaryUnpacker().execute(params).contains("sourcePath is required")); //$NON-NLS-1$
        params.put("sourcePath", "C:/x.epf"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(new ExternalBinaryUnpacker().execute(params).contains("targetPath is required")); //$NON-NLS-1$
    }

    @Test
    public void theToolPointsAtTheStepThatFollowsIt()
    {
        // On its own the conversion is half a job. The description has to name the other half, or an
        // agent produces a directory of XML and stops there.
        String description = new ExternalBinaryUnpacker().getDescription();
        assertTrue("the description must name the follow-up tool", //$NON-NLS-1$
            description.contains("import_configuration_from_xml")); //$NON-NLS-1$
    }

    @Test
    public void unpackingIsNotAReadOnlyAction()
    {
        // It starts a Designer process against a live infobase and writes a directory.
        assertTrue("read-only must not allow unpack_external_binary", //$NON-NLS-1$
            ToolProfile.READ_ONLY.getDisabledTools().contains(ExternalBinaryUnpacker.NAME));
        assertEquals(ToolCategory.APPLICATIONS,
            ToolCategory.getGroupForTool(ExternalBinaryUnpacker.NAME));
    }

    private static String run(String sourcePath)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "aiedt-tests-no-such-project"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("sourcePath", sourcePath); //$NON-NLS-1$
        params.put("targetPath", "C:/incoming/out"); //$NON-NLS-1$ //$NON-NLS-2$
        return new ExternalBinaryUnpacker().execute(params);
    }
}
