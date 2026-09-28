/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.HTTPService;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;

/**
 * Child addresses are resolved on a configuration built in memory, by the same locator a rights
 * write and an orphan sweep both call.
 */
public class AChildAddressIsResolvedOnTheModelTest
{
    private static final String PROJECT = "AiEdtChildLocatorProbe"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    private static Configuration configuration;

    /**
     * Opens a plain project and builds a configuration the locator can walk without EDT.
     *
     * @throws Exception when the workspace or the model cannot be created
     */
    @BeforeClass
    public static void aModelAndAProject() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-child-locator"); //$NON-NLS-1$
        project = openProject(PROJECT, projectDir);
        configuration = model();
    }

    /**
     * Removes the project and the directory under it.
     *
     * @throws Exception when the workspace cannot delete the project
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        deleteTree(projectDir);
    }

    /**
     * {@code catalog.goods} and {@code Справочник.Товары} are the names stored on the model, with
     * an English type.
     */
    @Test
    public void aTopLevelNameIsTheNameStoredOnTheModel()
    {
        BmRightsHelper.LocatedObject goods = BmRightsHelper.locateObject(configuration, "catalog.goods"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, goods.present);
        assertEquals("Catalog.Goods", goods.canonicalFqn); //$NON-NLS-1$

        BmRightsHelper.LocatedObject items =
            BmRightsHelper.locateObject(configuration, "Справочник.Товары"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, items.present);
        assertEquals("Catalog.Товары", items.canonicalFqn); //$NON-NLS-1$
    }

    /**
     * A standard attribute is not an {@code MdObject}. It is still found, in either language, and
     * the address uses the English collection kind and the name stored on the attribute.
     */
    @Test
    public void aStandardAttributeIsFound()
    {
        BmRightsHelper.LocatedObject english = BmRightsHelper.locateObject(configuration,
            "Catalog.Товары.StandardAttribute.Description"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, english.present);
        assertEquals("Catalog.Товары.StandardAttribute.Description", english.canonicalFqn); //$NON-NLS-1$

        BmRightsHelper.LocatedObject russian = BmRightsHelper.locateObject(configuration,
            "Справочник.Товары.СтандартныйРеквизит.Description"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, russian.present);
        assertEquals("Catalog.Товары.StandardAttribute.Description", russian.canonicalFqn); //$NON-NLS-1$
    }

    /**
     * A URL template's method is found. The collection feature is {@code urlTemplates}, not a
     * getter named {@code getURLTemplates}.
     */
    @Test
    public void aUrlTemplateMethodIsFound()
    {
        BmRightsHelper.LocatedObject method = BmRightsHelper.locateObject(configuration,
            "HTTPService.Api.URLTemplate.Items.Method.Get"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, method.present);
        assertEquals("HTTPService.Api.URLTemplate.Items.Method.Get", method.canonicalFqn); //$NON-NLS-1$
    }

    /**
     * A member missing from a collection that could be read is absent. A collection this model
     * does not have is not decided, and a write of that child is not refused.
     *
     * @throws Exception when the written file cannot be read
     */
    @Test
    public void aMissingMemberIsAbsentAndAnUnknownKindIsNotRefused() throws Exception
    {
        BmRightsHelper.LocatedObject missing =
            BmRightsHelper.locateObject(configuration, "Catalog.Goods.Attribute.Missing"); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, missing.present);

        BmRightsHelper.LocatedObject unknown =
            BmRightsHelper.locateObject(configuration, "Catalog.Goods.NoSuchKind.Foo"); //$NON-NLS-1$
        assertNull(unknown.present);

        BmRightsHelper.RightsGate gate = BmRightsHelper.RightsGate.forConfiguration(configuration);
        BmRightsHelper.FileRightResult refused = BmRightsHelper.applyRightToFile(project, "FullRights", //$NON-NLS-1$
            "Catalog.Goods.Attribute.Missing", "Read", true, false, gate); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(refused.ok);
        assertEquals("notFound", refused.failureKind); //$NON-NLS-1$
        assertEquals("object", refused.subject); //$NON-NLS-1$

        BmRightsHelper.FileRightResult written = BmRightsHelper.applyRightToFile(project, "FullRights", //$NON-NLS-1$
            "Catalog.Goods.NoSuchKind.Foo", "Read", true, false, gate); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(written.error == null ? "written" : written.error, written.ok); //$NON-NLS-1$
        String text = Files.readString(rightsFile("FullRights"), StandardCharsets.UTF_8); //$NON-NLS-1$
        assertTrue(text.contains("Catalog.Goods.NoSuchKind.Foo")); //$NON-NLS-1$
    }

    /**
     * The file stores the model's role directory, the English object address and the right's
     * spelling from the applicable set, not the spelling the caller used.
     *
     * @throws Exception when the written file cannot be read
     */
    @Test
    public void theWriterStoresTheModelsSpelling() throws Exception
    {
        BmRightsHelper.RightsGate gate = BmRightsHelper.RightsGate.forConfiguration(configuration);
        BmRightsHelper.FileRightResult written = BmRightsHelper.applyRightToFile(project, "fullrights", //$NON-NLS-1$
            "catalog.goods", "read", true, false, gate); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(written.error == null ? "written" : written.error, written.ok); //$NON-NLS-1$

        BmRightsHelper.FileRightResult child = BmRightsHelper.applyRightToFile(project, "fullrights", //$NON-NLS-1$
            "Справочник.Товары.StandardAttribute.Description", "View", true, false, gate); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(child.error == null ? "written" : child.error, child.ok); //$NON-NLS-1$

        Path roles = projectDir.resolve("src").resolve("Roles"); //$NON-NLS-1$ //$NON-NLS-2$
        try (var listed = Files.list(roles))
        {
            assertTrue(listed.map(path -> path.getFileName().toString()).anyMatch("FullRights"::equals)); //$NON-NLS-1$
        }
        String text = Files.readString(rightsFile("FullRights"), StandardCharsets.UTF_8); //$NON-NLS-1$
        assertTrue(text.contains("<name>Catalog.Goods</name>")); //$NON-NLS-1$
        assertTrue(text.contains("<name>Read</name>")); //$NON-NLS-1$
        assertFalse(text.contains("<name>read</name>")); //$NON-NLS-1$
        assertFalse(text.contains("catalog.goods")); //$NON-NLS-1$
        assertTrue(text.contains("<name>Catalog.Товары.StandardAttribute.Description</name>")); //$NON-NLS-1$
    }

    /**
     * A write that cannot move the temporary file into place deletes that file.
     *
     * @throws Exception when the fixture directory cannot be created
     */
    @Test
    public void aFailedWriteRemovesTheTemporaryFile() throws Exception
    {
        Path role = projectDir.resolve("src").resolve("Roles").resolve("RoleTmp"); //$NON-NLS-1$ //$NON-NLS-2$
        Path blocking = role.resolve("Rights.rights").resolve("occupied"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(blocking);

        String error = BmRightsHelper.writeRightsFile(project, "RoleTmp", "<Rights></Rights>"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(error);
        assertFalse(Files.exists(role.resolve("Rights.rights.tmp"))); //$NON-NLS-1$
    }

    /**
     * A configuration with one catalog named {@code Goods}, one named {@code Товары} that owns a
     * standard attribute, a role, and an HTTP service method.
     *
     * @return the model
     * @throws Exception when a child cannot be created or attached
     */
    private static Configuration model() throws Exception
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setName("Cfg"); //$NON-NLS-1$

        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        configuration.getCatalogs().add(goods);

        Catalog items = MdClassFactory.eINSTANCE.createCatalog();
        items.setName("Товары"); //$NON-NLS-1$
        configuration.getCatalogs().add(items);
        assertTrue("the standard attribute was not created", addStandardAttribute(items)); //$NON-NLS-1$

        Role role = MdClassFactory.eINSTANCE.createRole();
        role.setName("FullRights"); //$NON-NLS-1$
        configuration.getRoles().add(role);

        MdObject service = BmObjectHelper.createGenericObject("HTTPService"); //$NON-NLS-1$
        assertNotNull("HTTPService was not created", service); //$NON-NLS-1$
        service.setName("Api"); //$NON-NLS-1$
        MdObject template = BmObjectHelper.createGenericObject("URLTemplate"); //$NON-NLS-1$
        assertNotNull("URLTemplate was not created", template); //$NON-NLS-1$
        template.setName("Items"); //$NON-NLS-1$
        MdObject method = BmObjectHelper.createGenericObject("Method"); //$NON-NLS-1$
        assertNotNull("Method was not created", method); //$NON-NLS-1$
        method.setName("Get"); //$NON-NLS-1$
        addTo(service, "getUrlTemplates", template); //$NON-NLS-1$
        addTo(template, "getMethods", method); //$NON-NLS-1$
        configuration.getHttpServices().add((HTTPService)service);
        return configuration;
    }

    /**
     * Puts a {@code Description} standard attribute on {@code catalog}.
     *
     * @param catalog the catalog
     * @return {@code false} when this runtime cannot create one
     */
    @SuppressWarnings("unchecked")
    private static boolean addStandardAttribute(Catalog catalog)
    {
        EList<? extends EObject> list = BmObjectHelper.getChildListByKind(catalog, "StandardAttribute"); //$NON-NLS-1$
        if (list == null)
        {
            return false;
        }
        try
        {
            Object factory = MdClassFactory.eINSTANCE;
            Method create = factory.getClass().getMethod("createStandardAttribute"); //$NON-NLS-1$
            Object attribute = create.invoke(factory);
            attribute.getClass().getMethod("setName", String.class).invoke(attribute, "Description"); //$NON-NLS-1$ //$NON-NLS-2$
            ((EList<EObject>)list).add((EObject)attribute);
            return true;
        }
        catch (ReflectiveOperationException | ClassCastException notThisBuild)
        {
            return false;
        }
    }

    /**
     * Appends {@code child} to the list {@code getter} returns.
     *
     * @param owner the parent
     * @param getter the collection getter
     * @param child the child
     * @throws Exception when the getter is missing or the list rejects the child
     */
    @SuppressWarnings("unchecked")
    private static void addTo(EObject owner, String getter, EObject child) throws Exception
    {
        Object list = owner.getClass().getMethod(getter).invoke(owner);
        ((EList<EObject>)list).add(child);
    }

    /**
     * The rights file of one role.
     *
     * @param roleName the simple role name
     * @return the path
     */
    private static Path rightsFile(String roleName)
    {
        return projectDir.resolve("src").resolve("Roles").resolve(roleName).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Creates and opens a project at {@code location}.
     *
     * @param name the project name
     * @param location the directory
     * @return the opened project
     * @throws Exception when the workspace refuses the project
     */
    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        if (opened.exists())
        {
            opened.delete(true, true, new NullProgressMonitor());
        }
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        opened.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        return opened;
    }

    /**
     * Deletes a directory tree.
     *
     * @param root the directory
     * @throws IOException when a walk cannot be opened
     */
    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
