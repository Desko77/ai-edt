/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.session.FormEditingSession;
import ru.aiedt.mcp.oform.session.OpenFormSessions;

/**
 * The write guard itself, apart from any write path: the work a path hands it runs under the
 * guard's rule and lock and answers what the work answered, and the check every path asks at its
 * point of writing refuses what a connected dirty text buffer holds, refuses the changed entry a
 * live session of the form editor holds - the other entry is no obstacle - and lets the session
 * named as the initiator replace its own entry, which is what its save does.
 */
public class AContainerWriteGuardTest
{
    private static final String PROJECT = "AiEdtOformGuardProbe"; //$NON-NLS-1$

    private static final String ITEM_CONTAINER = "Catalogs/Products/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String MODULE = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    private static final String EDITOR_MODULE = "Процедура ПриОткрытии()" + CRLF //$NON-NLS-1$
        + "\tСообщить(\"Из редактора\");" + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF; //$NON-NLS-1$

    private static String layoutText;

    private static Path root;

    private static Path container;

    private static IFile containerFile;

    private static IProject project;

    private FormEditingSession session;

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-guard"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/Catalogs/Products/Forms/ItemForm")); //$NON-NLS-1$
        container = projectDir.resolve("src").resolve(ITEM_CONTAINER); //$NON-NLS-1$
        Files.write(container, containerOf(MODULE));
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        containerFile = project.getFile("src/" + ITEM_CONTAINER); //$NON-NLS-1$
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /** Every test starts from the container as the suite wrote it, holding nothing open. */
    @Before
    public void theContainerIsWhatTheSuiteWrote() throws Exception
    {
        if (containerFile != null)
        {
            disconnect();
        }
        if (session != null)
        {
            OpenFormSessions.release(session);
            session = null;
        }
        Files.write(container, containerOf(MODULE));
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /** Nothing is left connected or held for the next test. */
    @After
    public void nothingIsHeldOpen() throws Exception
    {
        if (containerFile != null)
        {
            disconnect();
        }
        if (session != null)
        {
            OpenFormSessions.release(session);
            session = null;
        }
    }

    /** The work a path hands the guard runs under its rule and lock and answers what it answered. */
    @Test
    public void theWorkAnswersWhatItAnswered() throws Exception
    {
        String answered = ContainerWriteGuard.write(containerFile, () -> "answered under the guard"); //$NON-NLS-1$
        assertEquals("answered under the guard", answered); //$NON-NLS-1$
    }

    /** A connected dirty text buffer refuses the check, as it refused the writes before the guard. */
    @Test
    public void aDirtyBufferRefusesTheCheck() throws Exception
    {
        byte[] asWritten = Files.readAllBytes(container);
        ITextFileBuffer buffer = connect();
        buffer.getDocument().set(EDITOR_MODULE);
        assertTrue(buffer.isDirty());
        try
        {
            ContainerWriteGuard.refuseUnlessAllowed(containerFile, null, true, true);
            throw new AssertionError("a dirty buffer let the check pass"); //$NON-NLS-1$
        }
        catch (IOException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("unsaved changes")); //$NON-NLS-1$
            assertTrue(refused.getMessage(), refused.getMessage().contains(ITEM_CONTAINER)); //$NON-NLS-1$
        }
        assertArrayEquals("the file keeps its bytes", asWritten, Files.readAllBytes(container)); //$NON-NLS-1$
        assertEquals(EDITOR_MODULE, buffer.getDocument().get());
    }

    /** The changed entry a live session holds refuses the check; the entry it does not hold passes. */
    @Test
    public void aChangedEntryOfASessionRefusesItsReplacement() throws Exception
    {
        byte[] asWritten = Files.readAllBytes(container);
        session = OpenFormSessions.open(containerFile);
        FormEditingSession.Edit edit = session.edit("set_property", //$NON-NLS-1$
            Map.of("property", "title", "value", "Заголовок из редактора")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(edit.message, edit.done);
        assertTrue("the session holds its layout changed", session.formChanged()); //$NON-NLS-1$
        assertFalse("the session holds no module change", session.moduleChanged()); //$NON-NLS-1$

        try
        {
            ContainerWriteGuard.refuseUnlessAllowed(containerFile, null, true, false);
            throw new AssertionError("a write replacing the held layout entry let the check pass"); //$NON-NLS-1$
        }
        catch (IOException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("unsaved changes")); //$NON-NLS-1$
            assertTrue(refused.getMessage(), refused.getMessage().contains(ITEM_CONTAINER)); //$NON-NLS-1$
        }
        assertArrayEquals("the file keeps its bytes", asWritten, Files.readAllBytes(container)); //$NON-NLS-1$

        ContainerWriteGuard.refuseUnlessAllowed(containerFile, null, false, true);
    }

    /** The session named as the initiator replaces its own entry: its save is no obstacle to itself. */
    @Test
    public void theSessionsOwnInitiatorReplacesItsEntry() throws Exception
    {
        session = OpenFormSessions.open(containerFile);
        FormEditingSession.Edit edit = session.edit("set_property", //$NON-NLS-1$
            Map.of("property", "title", "value", "Заголовок из редактора")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(edit.message, edit.done);
        assertTrue(session.isDirty());

        ContainerWriteGuard.refuseUnlessAllowed(containerFile, session.id(), true, true);
    }

    /** A session that holds nothing changed is no obstacle: the check passes for any writer. */
    @Test
    public void aCleanSessionIsNoObstacle() throws Exception
    {
        session = OpenFormSessions.open(containerFile);
        assertFalse(session.isDirty());

        ContainerWriteGuard.refuseUnlessAllowed(containerFile, null, true, true);
    }

    private static ITextFileBuffer connect() throws Exception
    {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        manager.connect(containerFile.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        ITextFileBuffer buffer = manager.getTextFileBuffer(containerFile.getFullPath(), LocationKind.IFILE);
        assertTrue("the container's buffer is connected", buffer != null); //$NON-NLS-1$
        return buffer;
    }

    private static void disconnect() throws CoreException
    {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        if (containerFile == null || manager == null)
        {
            return;
        }
        if (manager.getTextFileBuffer(containerFile.getFullPath(), LocationKind.IFILE) != null)
        {
            manager.disconnect(containerFile.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        }
    }

    private static byte[] containerOf(String module)
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(layoutText))); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", withBom(module))); //$NON-NLS-1$
        return container.write();
    }

    private static byte[] withBom(String text)
    {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte)0xEF;
        out[1] = (byte)0xBB;
        out[2] = (byte)0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }
}
