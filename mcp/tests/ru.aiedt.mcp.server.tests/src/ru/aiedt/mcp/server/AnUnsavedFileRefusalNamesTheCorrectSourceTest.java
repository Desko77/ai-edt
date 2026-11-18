/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceReader;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter;

/** Keeps a dirty-file write refusal accurate when a provider owns the same address. */
public class AnUnsavedFileRefusalNamesTheCorrectSourceTest
{
    /**
     * Creates the suite's isolated provider and physical file.
     *
     * @throws Exception when the fixture cannot be created
     */
    @BeforeClass
    public static void createFixture() throws Exception
    {
        AProviderReadNamesItsWriteTargetTest.createProviderFixture();
    }

    /**
     * Removes only the provider and project created by this suite.
     *
     * @throws Exception when the fixture cannot be removed
     */
    @AfterClass
    public static void removeFixture() throws Exception
    {
        AProviderReadNamesItsWriteTargetTest.removeProviderFixture();
    }

    /**
     * Checks that a refused write does not claim the provider read contains editor text.
     *
     * @throws Exception when the temporary text buffer cannot be connected or disconnected
     */
    @Test
    public void aDirtyFileRefusalDoesNotMisidentifyTheReaderSource() throws Exception
    {
        String projectName = "AiEdtCommonSourceOrderProbe"; //$NON-NLS-1$
        String modulePath = "CommonModules/ProviderAndFile/Module.bsl"; //$NON-NLS-1$
        var file = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName)
            .getFile("src/" + modulePath); //$NON-NLS-1$
        var manager = FileBuffers.getTextFileBufferManager();
        assertNotNull(manager);
        manager.connect(file.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        try
        {
            var buffer = manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            assertNotNull(buffer);
            buffer.getDocument().set("// Unsaved physical text"); //$NON-NLS-1$
            assertTrue(buffer.isDirty());
            String read = new ModuleSourceReader().execute(Map.of(
                "projectName", projectName, "modulePath", modulePath)); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(read, read.contains("**Source:** probe")); //$NON-NLS-1$
            assertFalse(read, read.contains("Unsaved physical text")); //$NON-NLS-1$
            String refused = new ModuleSourceWriter().execute(Map.of(
                "projectName", projectName, "modulePath", modulePath, //$NON-NLS-1$ //$NON-NLS-2$
                "mode", "append", "source", "// Writer probe", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            assertTrue(refused, refused.startsWith("Error:")); //$NON-NLS-1$
            assertTrue(refused, refused.contains("unsaved changes")); //$NON-NLS-1$
            assertTrue(refused, refused.contains("Save or revert the editor first")); //$NON-NLS-1$
            assertFalse(refused, refused.contains("What the editor holds is what read_module_source returns")); //$NON-NLS-1$
        }
        finally
        {
            manager.disconnect(file.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        }
    }
}
