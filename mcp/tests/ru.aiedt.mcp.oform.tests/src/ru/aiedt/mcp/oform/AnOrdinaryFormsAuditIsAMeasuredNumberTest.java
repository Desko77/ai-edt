/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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


/**
 * The audit of a project's ordinary forms is a measured number: the forms it found, how many
 * came back byte for byte, and the ones that did not, by name.
 *
 * <p>The project here is a workspace project over a temporary directory with two containers
 * built by the server's own writer - one sound, one whose layout is not a list - so the count,
 * the version table and the named mismatch are all decided by the files and not by a stub.</p>
 *
 * <p>Two more projects are built inside their own tests, both over a temporary directory: one
 * where more containers refuse to open than the limit names, and one where every container
 * refuses. Both exist to hold the count of unreadable forms apart from the list that names
 * them.</p>
 */
public class AnOrdinaryFormsAuditIsAMeasuredNumberTest
{
    private static final String PROJECT = "AiEdtOformProbe"; //$NON-NLS-1$

    private static final String UNREADABLE_PROJECT = "AiEdtOformUnreadableProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    @BeforeClass
    public static void aProjectWithTwoOrdinaryForms() throws Exception
    {
        root = Files.createTempDirectory("aiedt-oform-probe"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path sound = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Path broken = projectDir.resolve("src/CommonForms/Broken"); //$NON-NLS-1$
        Files.createDirectories(sound);
        Files.createDirectories(broken);
        Files.write(sound.resolve("Form.oform"), container("{27," + CRLF + "{1,\"ru\"}" + CRLF + "}", "// module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        Files.write(broken.resolve("Form.oform"), container("{26,{1}", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        project = openProject(PROJECT, projectDir);
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        closeProject(project, root);
        project = null;
    }

    /**
     * The count of unreadable containers is how many the audit met, not how many the bounded
     * list holds.
     *
     * <p>Five containers refuse to open while the limit is three, so the answer names three of
     * them by name and has to count five. One more form has a readable container whose layout
     * text does not survive the round trip, and one is sound: the three counts together are
     * every form the audit found.</p>
     */
    @Test
    public void theUnreadableCountIsNotTheListLength() throws Exception
    {
        Path dir = Files.createTempDirectory("aiedt-oform-unreadable"); //$NON-NLS-1$
        IProject probe = null;
        try
        {
            Path projectDir = dir.resolve(UNREADABLE_PROJECT);
            for (int i = 1; i <= 5; i++)
            {
                Path form = projectDir.resolve("src/CommonForms/Unreadable" + i); //$NON-NLS-1$
                Files.createDirectories(form);
                Files.write(form.resolve("Form.oform"), corrupt(i)); //$NON-NLS-1$
            }
            Path broken = projectDir.resolve("src/CommonForms/NotAList"); //$NON-NLS-1$
            Files.createDirectories(broken);
            Files.write(broken.resolve("Form.oform"), container("{26,{1}", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Path sound = projectDir.resolve("src/CommonForms/Sound"); //$NON-NLS-1$
            Files.createDirectories(sound);
            Files.write(sound.resolve("Form.oform"), container("{27," + CRLF + "{1,\"ru\"}" + CRLF + "}", "// module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            probe = openProject(UNREADABLE_PROJECT, projectDir);
            String answer = new OrdinaryFormsTool()
                .execute(Map.of("operation", "audit", "projectName", UNREADABLE_PROJECT, "limit", "3")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            JsonObject audit = JsonParser.parseString(answer).getAsJsonObject();
            assertTrue(answer, audit.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(answer, 7, audit.get("forms").getAsInt()); //$NON-NLS-1$
            assertEquals(answer, 5, audit.get("unreadable").getAsInt()); //$NON-NLS-1$
            assertEquals(answer, 1, audit.get("mismatched").getAsInt()); //$NON-NLS-1$
            assertEquals(answer, 1, audit.get("roundTripped").getAsInt()); //$NON-NLS-1$
            assertEquals(answer, 3, audit.getAsJsonArray("unreadableForms").size()); //$NON-NLS-1$
            assertEquals(answer, audit.get("forms").getAsInt(), audit.get("unreadable").getAsInt() //$NON-NLS-1$
                + audit.get("mismatched").getAsInt() + audit.get("roundTripped").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
            for (var row : audit.getAsJsonArray("unreadableForms"))
            {
                assertTrue(answer, row.getAsJsonObject().get("error").getAsString().contains("Not a container")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        finally
        {
            closeProject(probe, dir);
        }
    }

    /**
     * A project whose containers all refuse to open has no mismatching form: the audit counts a
     * refusal as a refusal, not as a layout that read back wrong.
     */
    @Test
    public void aProjectOfUnreadableContainersOnlyHasNoMismatches() throws Exception
    {
        Path dir = Files.createTempDirectory("aiedt-oform-refused"); //$NON-NLS-1$
        IProject probe = null;
        try
        {
            Path projectDir = dir.resolve(UNREADABLE_PROJECT);
            for (int i = 1; i <= 4; i++)
            {
                Path form = projectDir.resolve("src/CommonForms/Unreadable" + i); //$NON-NLS-1$
                Files.createDirectories(form);
                Files.write(form.resolve("Form.oform"), corrupt(i)); //$NON-NLS-1$
            }
            probe = openProject(UNREADABLE_PROJECT, projectDir);
            String answer = new OrdinaryFormsTool()
                .execute(Map.of("operation", "audit", "projectName", UNREADABLE_PROJECT, "limit", "2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            JsonObject audit = JsonParser.parseString(answer).getAsJsonObject();
            assertTrue(answer, audit.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(answer, 4, audit.get("forms").getAsInt()); //$NON-NLS-1$
            assertEquals(answer, 4, audit.get("unreadable").getAsInt()); //$NON-NLS-1$
            assertEquals(answer, 0, audit.get("mismatched").getAsInt()); //$NON-NLS-1$
            assertEquals(answer, 0, audit.get("roundTripped").getAsInt()); //$NON-NLS-1$
            assertEquals(answer, 2, audit.getAsJsonArray("unreadableForms").size()); //$NON-NLS-1$
        }
        finally
        {
            closeProject(probe, dir);
        }
    }

    /**
     * The audit counts both forms, round-trips the sound one, and names the broken one.
     */
    @Test
    public void theAuditCountsAndNames()
    {
        String answer = new OrdinaryFormsTool().execute(Map.of("operation", "audit", "projectName", PROJECT, "details", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        JsonObject audit = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(answer, audit.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(2, audit.get("forms").getAsInt()); //$NON-NLS-1$
        assertEquals(1, audit.get("roundTripped").getAsInt()); //$NON-NLS-1$
        assertEquals(1, audit.get("mismatched").getAsInt()); //$NON-NLS-1$
        assertEquals(1, audit.getAsJsonObject("byVersion").get("27").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, audit.getAsJsonObject("byVersion").get("26").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, audit.getAsJsonObject("byEntries").get("form+module").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        String mismatches = audit.getAsJsonArray("mismatches").toString(); //$NON-NLS-1$
        assertTrue(mismatches, mismatches.contains("CommonForm.Broken")); //$NON-NLS-1$
        assertFalse(mismatches, mismatches.contains("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
        assertEquals(2, audit.getAsJsonArray("details").size()); //$NON-NLS-1$
    }

    /**
     * A call without an operation, with an unknown one, or without a project is refused by
     * name; help names the operations.
     */
    @Test
    public void refusalsAndHelpAreNamed()
    {
        OrdinaryFormsTool tool = new OrdinaryFormsTool();
        assertTrue(tool.execute(Map.of()).contains("operation is required")); //$NON-NLS-1$
        assertTrue(tool.execute(Map.of("operation", "repaint")).contains("Unknown operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(tool.execute(Map.of("operation", "layout")).contains("projectName must be provided")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(tool.execute(Map.of("operation", "audit")).contains("projectName must be provided")); //$NON-NLS-1$ //$NON-NLS-2$
        String help = tool.execute(Map.of("operation", "help")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(help, help.contains("audit")); //$NON-NLS-1$
        assertTrue(help, help.contains("Form.oform")); //$NON-NLS-1$
        String missing = tool.execute(Map.of("operation", "audit", "projectName", "NoSuchProjectAnywhere")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(missing, missing.contains("helpHint")); //$NON-NLS-1$
    }

    private static byte[] container(String form, String module)
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(form))); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", withBom(module))); //$NON-NLS-1$
        return container.write();
    }

    private static IProject openProject(String name, Path projectDir) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject created = workspace.getRoot().getProject(name);
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        created.create(description, new NullProgressMonitor());
        created.open(new NullProgressMonitor());
        return created;
    }

    private static void closeProject(IProject probe, Path dir) throws Exception
    {
        if (probe != null && probe.exists())
        {
            probe.delete(false, true, new NullProgressMonitor());
        }
        if (dir != null)
        {
            try (var walk = Files.walk(dir))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /**
     * Bytes that are not a container: an odd index gives a file too short to hold a header, an
     * even index gives one long enough that the header itself is refused.
     *
     * @param index the form's number
     * @return the bytes
     */
    private static byte[] corrupt(int index)
    {
        if (index % 2 == 1)
        {
            return new byte[] { 1, (byte)0x7F, 0x2A };
        }
        byte[] bytes = new byte[64];
        Arrays.fill(bytes, (byte)0x5A);
        return bytes;
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
