/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

import ru.aiedt.mcp.server.support.oform.V8Container;

/**
 * The audit of a project's ordinary forms is a measured number: the forms it found, how many
 * came back byte for byte, and the ones that did not, by name.
 *
 * <p>The project here is a workspace project over a temporary directory with two containers
 * built by the server's own writer - one sound, one whose layout is not a list - so the count,
 * the version table and the named mismatch are all decided by the files and not by a stub.</p>
 */
public class AnOrdinaryFormsAuditIsAMeasuredNumberTest
{
    private static final String PROJECT = "AiEdtOformProbe"; //$NON-NLS-1$

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
        assertTrue(tool.execute(Map.of("operation", "layout")).contains("Unknown operation")); //$NON-NLS-1$ //$NON-NLS-2$
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
