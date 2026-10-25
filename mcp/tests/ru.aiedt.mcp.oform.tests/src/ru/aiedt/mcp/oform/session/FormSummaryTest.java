/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.V8Container;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * The summary of a form container counts what the layout reader counts: the version token, the
 * recognised elements with nested ones, the records of unrecognised kinds. A version outside the
 * measured set is read and flagged, and bytes that are not a form give a stated problem instead
 * of an exception.
 */
public class FormSummaryTest
{
    private static final String PROJECT = "AiEdtOformSummaryProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    /** A measured version-27 layout is counted as the reader counts it. */
    @Test
    public void aMeasuredLayoutIsCounted() throws IOException
    {
        String layoutText = fixture("item-form-v27.form"); //$NON-NLS-1$
        FormSummary summary = FormSummary.of(container(layoutText), "Catalog"); //$NON-NLS-1$

        FormLayout layout = LayoutReader.read(BraceTree.parse(layoutText), "Catalog"); //$NON-NLS-1$
        int unread = layout.unread.values().stream().mapToInt(Integer::intValue).sum();
        assertNull(summary.problem);
        assertEquals("27", summary.version); //$NON-NLS-1$
        assertTrue(summary.versionSupported);
        assertEquals(8, summary.elementCount);
        assertEquals(unread, summary.unreadRecords);
    }

    /** A version-25 layout is within the measured set. */
    @Test
    public void aVersion25LayoutIsSupported() throws IOException
    {
        FormSummary summary = FormSummary.of(container(fixture("settings-form-v25.form")), "DataProcessor"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(summary.problem);
        assertEquals("25", summary.version); //$NON-NLS-1$
        assertTrue(summary.versionSupported);
    }

    /** A version the writers were not measured on is still read, and flagged. */
    @Test
    public void anUnmeasuredVersionIsReadAndFlagged() throws IOException
    {
        String layoutText = fixture("item-form-v27.form"); //$NON-NLS-1$
        assertTrue(layoutText.startsWith("{27,")); //$NON-NLS-1$
        FormSummary summary = FormSummary.of(container("{19," + layoutText.substring(4)), "Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(summary.problem);
        assertEquals("19", summary.version); //$NON-NLS-1$
        assertFalse(summary.versionSupported);
        assertEquals(8, summary.elementCount);
    }

    /** Bytes that are not a container give a problem, not an exception. */
    @Test
    public void bytesThatAreNotAContainerGiveAProblem()
    {
        FormSummary summary = FormSummary.of("not a container".getBytes(StandardCharsets.UTF_8), null); //$NON-NLS-1$
        assertNotNull(summary.problem);
        assertNull(summary.version);
        assertFalse(summary.versionSupported);
        assertEquals(0, summary.elementCount);
    }

    /** A container without a layout entry gives a problem. */
    @Test
    public void aContainerWithoutALayoutGivesAProblem()
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("module", withBom("// empty"))); //$NON-NLS-1$ //$NON-NLS-2$
        FormSummary summary = FormSummary.of(container.write(), null);
        assertNotNull(summary.problem);
        assertNull(summary.version);
    }

    /** A file in the workspace is read through its resource, with the owner kind from its path. */
    @Test
    public void aWorkspaceFileIsRead() throws Exception
    {
        String layoutText = fixture("item-form-v27.form"); //$NON-NLS-1$
        Path root = Files.createTempDirectory("aiedt-oform-summary"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path form = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        Files.write(form.resolve("Form.oform"), container(layoutText)); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(PROJECT);
        try
        {
            IProjectDescription description = workspace.newProjectDescription(PROJECT);
            description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
            project.create(description, new NullProgressMonitor());
            project.open(new NullProgressMonitor());
            IFile file = project.getFile("src/Catalogs/Products/Forms/ItemForm/Form.oform"); //$NON-NLS-1$

            FormSummary summary = FormSummary.read(file);
            assertNull(summary.problem);
            assertEquals("27", summary.version); //$NON-NLS-1$
            assertEquals(8, summary.elementCount);

            FormSummary missing = FormSummary.read(project.getFile("src/Catalogs/Products/Forms/None/Form.oform")); //$NON-NLS-1$
            assertNotNull(missing.problem);
        }
        finally
        {
            if (project.exists())
            {
                project.delete(false, true, new NullProgressMonitor());
            }
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    private static byte[] container(String layoutText)
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(layoutText))); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", withBom("Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return container.write();
    }

    private static String fixture(String name) throws IOException
    {
        try (InputStream in = FormSummaryTest.class.getResourceAsStream("/ru/aiedt/mcp/oform/fixtures/" + name)) //$NON-NLS-1$
        {
            if (in == null)
            {
                throw new IOException("no fixture " + name); //$NON-NLS-1$
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
