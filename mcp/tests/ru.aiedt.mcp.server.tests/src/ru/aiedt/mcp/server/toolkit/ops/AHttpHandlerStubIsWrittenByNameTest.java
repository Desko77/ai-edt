/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;

/**
 * The handler stub of an HTTP service module is decided by the methods the module declares: a
 * module that cannot be parsed is refused with its file untouched, and a handler the module
 * already declares - by its whole name - is recognized rather than written a second time.
 */
public class AHttpHandlerStubIsWrittenByNameTest
{
    private ClusterWorkspaceProbe probe;

    private Path modulePath;

    /**
     * Creates a project holding the module of one HTTP service.
     *
     * @throws Exception when the project or the file cannot be created
     */
    @Before
    public void createProject() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("HttpHandlerStub"); //$NON-NLS-1$
        modulePath = probe.project.getLocation().toFile().toPath()
            .resolve("src/HTTPServices/Заказы/Module.bsl"); //$NON-NLS-1$
        writeModule("Функция GetAll(Запрос)\n\tВозврат Новый HTTPСервисОтвет(200);\nКонецФункции\n"); //$NON-NLS-1$
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

    /**
     * A handler the module already declares is answered as present, and the module keeps exactly
     * the bytes it had; a stub written next to a longer name lands once and is recognized on the
     * next call.
     */
    @Test
    public void aDeclaredHandlerIsRecognizedAndTheModuleKeepsItsBytes() throws Exception
    {
        byte[] before = Files.readAllBytes(modulePath);

        String path = appendStub("GetAll"); //$NON-NLS-1$

        assertTrue(path, path.endsWith("HTTPServices/Заказы/Module.bsl")); //$NON-NLS-1$
        assertArrayEquals("a declared handler writes nothing", before, Files.readAllBytes(modulePath)); //$NON-NLS-1$

        appendStub("Get"); //$NON-NLS-1$
        String afterGet = new String(Files.readAllBytes(modulePath), StandardCharsets.UTF_8);
        assertTrue("a name the module does not declare gets its stub", //$NON-NLS-1$
            afterGet.contains("Функция Get(Запрос)")); //$NON-NLS-1$

        byte[] afterGetBytes = Files.readAllBytes(modulePath);
        appendStub("Get"); //$NON-NLS-1$
        assertArrayEquals("the next call recognizes the stub it wrote", //$NON-NLS-1$
            afterGetBytes, Files.readAllBytes(modulePath));
    }

    /**
     * A module whose text cannot be parsed is refused and its file stays as it was.
     */
    @Test
    public void aBrokenModuleIsRefusedWithItsFileUntouched() throws Exception
    {
        writeModule("Функция GetAll(Запрос)\n\tВозврат \"незакрытая строка;\nКонецФункции\n"); //$NON-NLS-1$
        byte[] before = Files.readAllBytes(modulePath);

        try
        {
            appendStub("Get"); //$NON-NLS-1$
            fail("a module that does not parse has to refuse the stub"); //$NON-NLS-1$
        }
        catch (InvocationTargetException refused)
        {
            String reason = String.valueOf(refused.getCause().getMessage());
            assertTrue(reason, reason.contains("was not written")); //$NON-NLS-1$
            assertTrue(reason, reason.contains("never closed")); //$NON-NLS-1$
        }
        assertArrayEquals("the refused write touches nothing", before, Files.readAllBytes(modulePath)); //$NON-NLS-1$
    }

    private void writeModule(String text) throws Exception
    {
        Files.createDirectories(modulePath.getParent());
        Files.write(modulePath, text.getBytes(StandardCharsets.UTF_8));
        probe.project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    private String appendStub(String handler) throws Exception
    {
        Method append = ServiceOps.class.getDeclaredMethod("appendHandlerStubIfMissing", //$NON-NLS-1$
            IProject.class, String.class, String.class);
        append.setAccessible(true);
        return (String)append.invoke(null, probe.project, "Заказы", handler); //$NON-NLS-1$
    }
}
