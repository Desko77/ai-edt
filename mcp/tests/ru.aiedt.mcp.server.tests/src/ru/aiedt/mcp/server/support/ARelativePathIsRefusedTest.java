/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A relative path in a thick-client export or conversion is refused by name.
 * <p>
 * A relative path resolves against whatever directory the EDT process happens to run in,
 * which no caller can rely on: the same argument would read or write a different place on
 * another machine, or on the same machine after a restart. The refusal is checked before
 * the launcher is resolved, so it does not depend on a project being there.
 * </p>
 */
public class ARelativePathIsRefusedTest
{
    private static final String ANY_PROJECT = "aiedt-no-such-project"; //$NON-NLS-1$

    /** A relative sourcePath in the binary-to-XML conversion is refused as input. */
    @Test
    public void aRelativeSourceIsRefused()
    {
        BmInfobaseExtensionHelper.ExportResult r = BmInfobaseExtensionHelper.convertExternalToXml(
            ANY_PROJECT, null, "relative-source.epf", absoluteOnThisHost("absolute-target")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(r.ok);
        assertTrue("the refusal names what is wrong: " + r.error, r.error.contains("absolute")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(r.error, r.error.contains("relative-source.epf")); //$NON-NLS-1$
        assertTrue(ErrorTags.INVALID_INPUT_PATH.wire().equals(r.failureKind));
    }

    /** A relative targetPath in the binary-to-XML conversion is refused as input. */
    @Test
    public void aRelativeTargetIsRefused()
    {
        String target = "relative/target"; //$NON-NLS-1$
        BmInfobaseExtensionHelper.ExportResult r = BmInfobaseExtensionHelper.convertExternalToXml(
            ANY_PROJECT, null, absoluteOnThisHost("absolute-source.epf"), target); //$NON-NLS-1$

        assertFalse(r.ok);
        assertTrue("the refusal names what is wrong: " + r.error, r.error.contains("absolute")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(r.error, r.error.contains(java.nio.file.Paths.get(target).toString()));
    }

    /** A relative outputPath in a .cf export is refused before anything is resolved. */
    @Test
    public void aRelativeOutputIsRefused()
    {
        String output = "relative/export.cf"; //$NON-NLS-1$
        BmInfobaseExtensionHelper.ExportResult r = BmInfobaseExtensionHelper.exportConfigurationCf(
            ANY_PROJECT, null, output);

        assertFalse(r.ok);
        assertTrue("the refusal names what is wrong: " + r.error, r.error.contains("absolute")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(r.error, r.error.contains(java.nio.file.Paths.get(output).toString()));
        assertTrue(ErrorTags.INVALID_OUTPUT_PATH.wire().equals(r.failureKind));
    }

    /** An absolute outputPath reaches the next refusal - the launcher's - and is not bounced. */
    @Test
    public void anAbsoluteOutputIsNotRefusedForBeingAbsolute()
    {
        BmInfobaseExtensionHelper.ExportResult r = BmInfobaseExtensionHelper.exportConfigurationCf(
            ANY_PROJECT, null, absoluteOnThisHost("absolute-export.cf")); //$NON-NLS-1$

        assertFalse(r.ok);
        assertFalse("an absolute path is not refused as relative: " + r.error, //$NON-NLS-1$
            r.error.contains("absolute path")); //$NON-NLS-1$
    }

    /**
     * An absolute path under this host's temporary directory, without creating anything there.
     * <p>
     * The path tests reach for has to be absolute for the check under test to be the one that
     * answers. A path carrying a Windows drive letter is absolute on Windows alone: a host that
     * has no such drive reads it as a relative path, the call refuses it as relative, and the
     * assertion about what is NOT refused never sees an absolute path at all.
     * The file is never created, because no test here needs it to exist.
     * </p>
     *
     * @param name the file name under the temporary directory
     * @return the absolute path as text
     */
    private static String absoluteOnThisHost(String name)
    {
        return java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), name) //$NON-NLS-1$
            .toAbsolutePath().toString();
    }
}
