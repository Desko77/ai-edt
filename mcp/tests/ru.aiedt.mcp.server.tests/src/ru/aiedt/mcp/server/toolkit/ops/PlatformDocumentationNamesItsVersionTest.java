/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Which platform version a documentation answer describes, and whether the answer says so.
 * <p>
 * Without a project named the call promises the newest version the workspace uses, and it took the
 * first project the workspace listed instead. A workspace holds configurations of different
 * versions, so the answer could describe an older platform than the one being written against -
 * and nothing in it said which one it had read. The refusal for a named project that cannot be
 * resolved carries the same promise, so the two are pinned together here.
 * </p>
 */
public class PlatformDocumentationNamesItsVersionTest
{
    @Test
    public void theNewestVersionOfTheWorkspaceIsTheOneTaken()
    {
        assertEquals(Version.V8_3_22, PlatformDocReader.newestOf(
            List.of(Version.V8_3_22, Version.V8_3_13, Version.V8_3_18)));
    }

    /** The order the workspace lists its projects in says nothing about their versions. */
    @Test
    public void theOrderOfTheProjectsDoesNotDecideIt()
    {
        assertEquals(Version.V8_3_27, PlatformDocReader.newestOf(
            List.of(Version.V8_3_27, Version.V8_3_22, Version.V8_3_13)));
        assertEquals(Version.V8_3_27, PlatformDocReader.newestOf(
            List.of(Version.V8_3_13, Version.V8_3_22, Version.V8_3_27)));
    }

    /** A project with no version is not a candidate, and a workspace of only those has none. */
    @Test
    public void aProjectWithNoVersionIsPassedOver()
    {
        assertEquals(Version.V8_3_22, PlatformDocReader.newestOf(
            Arrays.asList(null, Version.EMPTY_VERSION, Version.V8_3_22)));

        assertNull(PlatformDocReader.newestOf(List.of(Version.EMPTY_VERSION)));
        assertNull(PlatformDocReader.newestOf(new ArrayList<Version>()));
        assertNull(PlatformDocReader.newestOf(null));
    }

    /** The answer says which platform it came from, so a caller can tell an old answer from a new one. */
    @Test
    public void theAnswerNamesTheVersionItWasBuiltFrom()
    {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName("Array"); //$NON-NLS-1$

        String answer = new PlatformDocReader().buildTypeDocumentation(
            type, null, "all", 50, false, Version.V8_3_27); //$NON-NLS-1$

        assertTrue(answer.startsWith("# Array")); //$NON-NLS-1$
        assertTrue("the answer does not name the platform version: " + answer, //$NON-NLS-1$
            answer.contains("8.3.27")); //$NON-NLS-1$
    }

    @Test
    public void noVersionIsNamedWhenThereIsNoneToName()
    {
        assertEquals("", PlatformDocReader.platformLine(null)); //$NON-NLS-1$
        assertTrue(PlatformDocReader.platformLine(Version.V8_3_22).contains("8.3.22")); //$NON-NLS-1$
    }
}
