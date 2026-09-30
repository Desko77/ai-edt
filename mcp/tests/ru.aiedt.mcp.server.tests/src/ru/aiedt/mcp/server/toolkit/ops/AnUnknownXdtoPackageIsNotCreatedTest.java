/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A write against an XDTO package the configuration does not contain creates no schema file, a
 * foreign owner prefix is refused, and a schema that does not parse is not described as absent.
 * <p>
 * The configuration lookup is the tool's own seam, so the test does not need a metadata model. What
 * stays real is the path from the operation to the refusal, and the project directory the file would
 * have been written into.
 * </p>
 */
public class AnUnknownXdtoPackageIsNotCreatedTest
{
    private static final String PROJECT = "AiEdtXdtoPathProbe"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    /** A project with a location and no configuration. */
    @BeforeClass
    public static void aBareProject() throws Exception
    {
        root = Files.createTempDirectory("aiedt-xdto-path-"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject existing = workspace.getRoot().getProject(PROJECT);
        if (existing.exists())
        {
            existing.delete(true, true, new NullProgressMonitor());
        }
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    /** Deletes the project and the directory it lived in, and puts the real lookup back. */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        XdtoWorkshopTool.usePackageObjectLookupForTest(null);
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /** Every write in this class is aimed at a package the lookup says is missing. */
    @Before
    public void thePackageIsMissing()
    {
        XdtoWorkshopTool.usePackageObjectLookupForTest(
            (asked, name) -> "Owner not found: XDTOPackage." + name); //$NON-NLS-1$
    }

    /** The next class in this JVM sees the real lookup. */
    @After
    public void theRealLookupReturns()
    {
        XdtoWorkshopTool.usePackageObjectLookupForTest(null);
    }

    /** Each writing operation refuses, and none of them creates Package.xdto. */
    @Test
    public void aWriteRefusesAnUnknownPackageAndCreatesNothing()
    {
        XdtoWorkshopTool tool = new XdtoWorkshopTool();
        for (String op : new String[] {
            "add_object_type", "add_value_type", "add_property", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "remove_object_type", "remove_value_type", "remove_property" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String json = tool.execute(args(
                "operation", op, //$NON-NLS-1$
                "projectName", PROJECT, //$NON-NLS-1$
                "packageName", "Missing", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "tOrder")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(op + ": " + json, json.contains("Owner not found")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(op + ": " + json, json.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(op + " created a schema", Files.exists(schema("Missing"))); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** set_namespace asks before it writes, so a missing package leaves no schema behind. */
    @Test
    public void setNamespaceRefusesBeforeWriting()
    {
        String json = new XdtoWorkshopTool().execute(args(
            "operation", "set_namespace", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, //$NON-NLS-1$
            "packageName", "Missing", //$NON-NLS-1$ //$NON-NLS-2$
            "namespace", "http://example.org/missing")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(json, json.contains("Owner not found")); //$NON-NLS-1$
        assertFalse(json, json.contains("schema was written")); //$NON-NLS-1$
        assertFalse(schema("Missing").toString(), Files.exists(schema("Missing"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A preview of the same write is refused the same way and creates nothing. */
    @Test
    public void aPreviewRefusesAnUnknownPackage()
    {
        String json = new XdtoWorkshopTool().execute(args(
            "operation", "add_object_type", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, //$NON-NLS-1$
            "packageName", "Missing", //$NON-NLS-1$ //$NON-NLS-2$
            "name", "tOrder", //$NON-NLS-1$ //$NON-NLS-2$
            "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(json, json.contains("Owner not found")); //$NON-NLS-1$
        assertFalse(json, json.contains("applied")); //$NON-NLS-1$
        assertFalse(Files.exists(schema("Missing"))); //$NON-NLS-1$
    }

    /** An owner that is not an XDTO package is refused before any name is taken from it. */
    @Test
    public void aForeignOwnerPrefixIsRefused()
    {
        String json = new XdtoWorkshopTool().execute(args(
            "operation", "add_object_type", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, //$NON-NLS-1$
            "ownerFqn", "Catalog.Foo", //$NON-NLS-1$ //$NON-NLS-2$
            "name", "tOrder")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(json, json.contains("XDTOPackage.")); //$NON-NLS-1$
        assertTrue(json, json.contains("\u041f\u0430\u043a\u0435\u0442XDTO.")); //$NON-NLS-1$
        assertFalse(json, json.contains("applied")); //$NON-NLS-1$
        assertFalse(Files.exists(schema("Foo"))); //$NON-NLS-1$
    }

    /** The plural type name is not an owner prefix. */
    @Test
    public void aPluralTypePrefixIsRefused()
    {
        String json = new XdtoWorkshopTool().execute(args(
            "operation", "add_object_type", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, //$NON-NLS-1$
            "ownerFqn", "XDTOPackages.Foo", //$NON-NLS-1$ //$NON-NLS-2$
            "name", "tOrder")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(json, json.contains("XDTOPackage.")); //$NON-NLS-1$
        assertFalse(json, json.contains("applied")); //$NON-NLS-1$
        assertFalse(Files.exists(schema("Foo"))); //$NON-NLS-1$
    }

    /** The Russian prefix is accepted, and the missing package is then refused. */
    @Test
    public void aRussianOwnerPrefixReachesTheExistenceCheck()
    {
        String json = new XdtoWorkshopTool().execute(args(
            "operation", "add_object_type", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, //$NON-NLS-1$
            "ownerFqn", "\u041f\u0430\u043a\u0435\u0442XDTO.\u0418\u043c\u044f", //$NON-NLS-1$ //$NON-NLS-2$
            "name", "tOrder")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(json, json.contains("Owner not found")); //$NON-NLS-1$
        assertTrue(json, json.contains("\u0418\u043c\u044f")); //$NON-NLS-1$
        assertFalse(Files.exists(schema("\u0418\u043c\u044f"))); //$NON-NLS-1$
    }

    /** A lower-case English prefix is the same type, and still requires the object. */
    @Test
    public void aLowerCasePrefixReachesTheExistenceCheck()
    {
        String json = new XdtoWorkshopTool().execute(args(
            "operation", "add_value_type", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, //$NON-NLS-1$
            "ownerFqn", "xdtopackage.Foo", //$NON-NLS-1$ //$NON-NLS-2$
            "name", "tOrder")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(json, json.contains("Owner not found")); //$NON-NLS-1$
        assertTrue(json, json.contains("Foo")); //$NON-NLS-1$
        assertFalse(Files.exists(schema("Foo"))); //$NON-NLS-1$
    }

    /** A package name that leaves the collection is refused and the outside file is not created. */
    @Test
    public void aPackageNameThatLeavesTheCollectionIsRefused()
    {
        String json = new XdtoWorkshopTool().execute(args(
            "operation", "add_object_type", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, //$NON-NLS-1$
            "packageName", "..", //$NON-NLS-1$ //$NON-NLS-2$
            "name", "tOrder")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(json, json.contains("packageName")); //$NON-NLS-1$
        assertTrue(json, json.contains("..")); //$NON-NLS-1$
        assertFalse(json, json.contains("applied")); //$NON-NLS-1$
        Path escaped = project.getLocation().toFile().toPath().resolve("src").resolve("Package.xdto"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(escaped.toString(), Files.exists(escaped));
    }

    /** read of a name that leaves the collection is an error, not an absent schema. */
    @Test
    public void aReadOfANameThatLeavesTheCollectionIsAnError()
    {
        String json = new XdtoWorkshopTool().execute(args(
            "operation", "read", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, //$NON-NLS-1$
            "packageName", "..")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(json, json.contains("packageName")); //$NON-NLS-1$
        assertFalse(json, json.contains("absent or empty")); //$NON-NLS-1$
    }

    /** A schema that is present and does not parse is an error from read, not an absent file. */
    @Test
    public void aBrokenSchemaIsNotReportedAbsent() throws Exception
    {
        Path file = schema("Broken"); //$NON-NLS-1$
        Files.createDirectories(file.getParent());
        Files.writeString(file, "this is not a package"); //$NON-NLS-1$

        String json = new XdtoWorkshopTool().execute(args(
            "operation", "read", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, //$NON-NLS-1$
            "packageName", "Broken")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(json, json.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse(json, json.contains("absent or empty")); //$NON-NLS-1$
        assertTrue(json, json.contains("Failed to load") || json.contains("not an XDTO Package")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An empty schema is still the absent answer. */
    @Test
    public void anEmptySchemaIsAbsent() throws Exception
    {
        Path file = schema("Empty"); //$NON-NLS-1$
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[0]);

        String json = new XdtoWorkshopTool().execute(args(
            "operation", "read", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, //$NON-NLS-1$
            "packageName", "Empty")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(json, json.contains("absent or empty")); //$NON-NLS-1$
        assertTrue(json, json.contains("\"success\":true")); //$NON-NLS-1$
    }

    /** A picture name that leaves the collection is refused before any directory is described. */
    @Test
    public void aPictureNameThatLeavesTheCollectionIsRefused()
    {
        Path out = root.resolve("out.png"); //$NON-NLS-1$
        String json = new CommonPictureExporter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$
            "name", "..", //$NON-NLS-1$ //$NON-NLS-2$
            "outputPath", out.toString())); //$NON-NLS-1$

        assertTrue(json, json.contains("name")); //$NON-NLS-1$
        assertTrue(json, json.contains("..")); //$NON-NLS-1$
        assertTrue(json, json.contains("must not contain")); //$NON-NLS-1$
        assertFalse(json, json.contains("not found")); //$NON-NLS-1$
        assertFalse(json, json.contains("exists but has no")); //$NON-NLS-1$
        assertFalse(Files.exists(out));
    }

    private static Path schema(String packageName)
    {
        return project.getLocation().toFile().toPath()
            .resolve("src").resolve("XDTOPackages").resolve(packageName).resolve("Package.xdto"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
