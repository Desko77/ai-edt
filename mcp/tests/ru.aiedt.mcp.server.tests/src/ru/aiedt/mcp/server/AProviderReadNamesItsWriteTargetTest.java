/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.ops.ACommonSourceOrderAnswersBothReadersTest;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceReader;

/** Checks that a provider read identifies the target chosen by writing and diff tools. */
public class AProviderReadNamesItsWriteTargetTest
{
    /**
     * Creates an isolated provider with one physical-file address and one provider-only address.
     *
     * @throws Exception when the isolated project cannot be created
     */
    @BeforeClass
    public static void createProviderFixture() throws Exception
    {
        ACommonSourceOrderAnswersBothReadersTest.aProjectWithOneFileOneProviderAndOneMissingAddress();
    }

    /**
     * Removes only the project and provider created by this suite.
     *
     * @throws Exception when the isolated project cannot be removed
     */
    @AfterClass
    public static void removeProviderFixture() throws Exception
    {
        ACommonSourceOrderAnswersBothReadersTest.theProjectGoes();
    }

    /** Verifies that an existing physical file is named as the separate write and diff target. */
    @Test
    public void aPhysicalFileIsNamedAsTheWriteTarget()
    {
        String answer = read("CommonModules/ProviderAndFile/Module.bsl"); //$NON-NLS-1$
        assertTrue(answer, answer.contains("**Source:** probe")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("write_module_source and diff_module use the physical file")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("not the provided source")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("writes to the same place")); //$NON-NLS-1$
    }

    /** Verifies that the provider-only address keeps its existing write-target note. */
    @Test
    public void aProviderOnlyAddressKeepsItsWriteTargetNote()
    {
        String answer = read("CommonModules/ProviderOnly/Module.bsl"); //$NON-NLS-1$
        assertTrue(answer, answer.contains("**Source:** probe")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("write_module_source by this address writes to the same place")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("not the provided source")); //$NON-NLS-1$
    }

    /**
     * Reads one fixture address through the public module reader.
     *
     * @param modulePath the fixture's src-relative module address
     * @return the module reader's unchanged response string
     */
    private static String read(String modulePath)
    {
        return new ModuleSourceReader().execute(Map.of(
            "projectName", "AiEdtCommonSourceOrderProbe", //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", modulePath)); //$NON-NLS-1$
    }
}
