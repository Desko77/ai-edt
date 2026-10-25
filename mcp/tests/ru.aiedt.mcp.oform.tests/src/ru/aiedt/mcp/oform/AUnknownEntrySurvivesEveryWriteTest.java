/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * A container entry this server knows nothing about survives every write: a read that writes
 * nothing leaves the whole container byte for byte, a module write leaves the layout entry and the
 * unknown entry on their own bytes, and a layout write - an element added through the operation -
 * leaves the module entry and the unknown entry on theirs.
 *
 * <p>The container is the read-test's fixture plus the unknown entry, so the layout the writes
 * work on is a measured one, and the module entry carries the one procedure the fixture binds.</p>
 */
public class AUnknownEntrySurvivesEveryWriteTest
{
    private static final String PROJECT = "AiEdtOformUnknownProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    /** An entry no operation of this server reads or writes. */
    private static final String UNKNOWN_ENTRY = "metadata"; //$NON-NLS-1$

    private static final String MODULE = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /** Bytes no code of this server interprets, with bytes no text encoding round-trips. */
    private static final byte[] UNKNOWN_BYTES = new byte[] { 0x00, 0x01, 0x02, (byte)0xFF, (byte)0xFE, 'm', 'e',
        't', 'a', 0x0A, 0x0D, 0x00 };

    private static String layoutText;

    private static Path container;

    private static Path root;

    private static IProject project;

    private static byte[] original;

    @BeforeClass
    public static void aProjectWithAContainerOfThreeEntries() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-unknown"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path form = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        V8Container v8 = V8Container.empty();
        v8.add(V8Container.Entry.of("form", withBom(layoutText))); //$NON-NLS-1$
        v8.add(V8Container.Entry.of("module", withBom(MODULE))); //$NON-NLS-1$
        v8.add(V8Container.Entry.of(UNKNOWN_ENTRY, UNKNOWN_BYTES));
        original = v8.write();
        container = form.resolve("Form.oform"); //$NON-NLS-1$
        Files.write(container, original);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
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

    /** A read that writes nothing leaves the container byte for byte, unknown entry included. */
    @Test
    public void aWriteOfNothingChangesNoByte() throws IOException
    {
        OrdinaryFormFile read = OrdinaryFormFile.read(container);
        read.save();
        assertArrayEquals(original, Files.readAllBytes(container));
        assertEquals(3, read.entryNames().size());
        assertArrayEquals(UNKNOWN_BYTES, read.getContainer().find(UNKNOWN_ENTRY).getData());
    }

    /** A module write leaves the layout entry and the unknown entry on their own bytes. */
    @Test
    public void aModuleWriteKeepsTheLayoutAndTheUnknownEntry() throws Exception
    {
        OrdinaryFormFile read = OrdinaryFormFile.read(container);
        read.setModuleText("Процедура Новая()" + CRLF + "КонецПроцедуры" + CRLF); //$NON-NLS-1$ //$NON-NLS-2$
        read.save();
        OrdinaryFormFile again = OrdinaryFormFile.read(container);
        assertEquals(layoutText, again.formText());
        assertTrue(again.moduleText().startsWith("Процедура Новая()")); //$NON-NLS-1$
        assertArrayEquals("the unknown entry keeps its bytes", UNKNOWN_BYTES, //$NON-NLS-1$
            again.getContainer().find(UNKNOWN_ENTRY).getData());
        theContainerIsBackToItsOriginal();
    }

    /**
     * A layout write through the operation leaves the module entry and the unknown entry on their
     * bytes, and the element it names is there beside everything the form held.
     */
    @Test
    public void aLayoutWriteKeepsTheModuleAndTheUnknownEntry() throws Exception
    {
        byte[] moduleBefore = OrdinaryFormFile.read(container).getContainer()
            .find(OrdinaryFormFile.MODULE_ENTRY).getData();
        String added = new OrdinaryFormsTool().execute(Map.of("operation", "add_element", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", "Catalog.Products.Form.ItemForm", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "type", "Button", "name", "КнопкаДобавить", "caption", "Действие", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "left", "220", "top", "90", "width", "100", "height", "23")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        JsonObject answer = JsonParser.parseString(added).getAsJsonObject();
        assertTrue(added, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("written").getAsBoolean()); //$NON-NLS-1$
        OrdinaryFormFile again = OrdinaryFormFile.read(container);
        assertEquals(MODULE, again.moduleText());
        assertArrayEquals(moduleBefore, again.getContainer().find(OrdinaryFormFile.MODULE_ENTRY).getData());
        assertArrayEquals("the unknown entry keeps its bytes", UNKNOWN_BYTES, //$NON-NLS-1$
            again.getContainer().find(UNKNOWN_ENTRY).getData());
        FormLayout layout = LayoutReader.read(again.formTree(), "Catalog"); //$NON-NLS-1$
        assertNotNull(layout.elementNamed("КнопкаДобавить")); //$NON-NLS-1$
        assertNotNull("the field the form held is still there", layout.elementNamed("Наименование")); //$NON-NLS-1$ //$NON-NLS-2$
        theContainerIsBackToItsOriginal();
    }

    /** Writes the original container back, so one test does not feed the next. */
    private static void theContainerIsBackToItsOriginal() throws Exception
    {
        Files.write(container, original);
        project.getFile(new org.eclipse.core.runtime.Path("src/Catalogs/Products/Forms/ItemForm/Form.oform")) //$NON-NLS-1$
            .refreshLocal(org.eclipse.core.resources.IResource.DEPTH_ZERO, null);
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
