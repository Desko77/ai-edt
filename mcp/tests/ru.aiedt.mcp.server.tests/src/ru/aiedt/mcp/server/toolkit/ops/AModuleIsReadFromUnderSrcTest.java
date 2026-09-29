/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;

/**
 * A module named by its path or its FQN is read from the file under the project's {@code src/}
 * folder, and a module with no file answers nothing.
 * <p>
 * The path {@code resolveModulePath} answers is relative to {@code src/}. Read from the project
 * root it names no file, the module reads as absent, and a handler the module already declares is
 * written into it a second time.
 * </p>
 */
public class AModuleIsReadFromUnderSrcTest
{
    private static final String MODULE_TEXT = "Процедура МаршрутПередСтартом(ТочкаМаршрутаБизнесПроцесса, Отказ)\r\n" //$NON-NLS-1$
        + "КонецПроцедуры\r\n"; //$NON-NLS-1$

    private ClusterWorkspaceProbe probe;

    /**
     * Creates a project holding the object module of one business process.
     *
     * @throws Exception when the project or the file cannot be created
     */
    @Before
    public void createProject() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("ModuleRead"); //$NON-NLS-1$
        Path module = probe.project.getLocation().toFile().toPath()
            .resolve("src/BusinessProcesses/Маршрут/ObjectModule.bsl"); //$NON-NLS-1$
        Files.createDirectories(module.getParent());
        Files.write(module, MODULE_TEXT.getBytes(StandardCharsets.UTF_8));
        probe.project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Removes the project and its directory.
     *
     * @throws Exception when the project cannot be removed
     */
    @After
    public void removeProject() throws Exception
    {
        probe.close();
    }

    @Test
    public void aModuleNamedByItsPathIsRead() throws Exception
    {
        String text = BslModuleAccess.readModuleIfPresent(probe.project,
            "BusinessProcesses/Маршрут/ObjectModule.bsl"); //$NON-NLS-1$
        assertNotNull("the module under src/ was not found by its path", text); //$NON-NLS-1$
        assertTrue(text, GenerateEventHandlersTool.declares(text, "МаршрутПередСтартом")); //$NON-NLS-1$
    }

    @Test
    public void aModuleNamedByItsFqnIsRead() throws Exception
    {
        String text = BslModuleAccess.readModuleIfPresent(probe.project,
            "BusinessProcess.Маршрут.ObjectModule"); //$NON-NLS-1$
        assertNotNull("the module under src/ was not found by its FQN", text); //$NON-NLS-1$
        assertTrue(text, GenerateEventHandlersTool.declares(text, "маршрутпередстартом")); //$NON-NLS-1$
    }

    @Test
    public void aModuleWithNoFileAnswersNothing() throws Exception
    {
        assertNull(BslModuleAccess.readModuleIfPresent(probe.project,
            "BusinessProcesses/Другой/ObjectModule.bsl")); //$NON-NLS-1$
    }
}
