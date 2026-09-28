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
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CalculationRegister;
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
     * A member missing from a collection that could be read is absent. A collection kind the
     * owner's class does not have is absent too, and the write is refused; a sweep leaves such an
     * address in place.
     *
     * @throws Exception when the rights file cannot be read
     */
    @Test
    public void aMissingMemberAndAnUnknownKindAreRefused() throws Exception
    {
        BmRightsHelper.LocatedObject missing =
            BmRightsHelper.locateObject(configuration, "Catalog.Goods.Attribute.Missing"); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, missing.present);
        assertNull(missing.missingKind);
        assertEquals(Boolean.FALSE, missing.presenceForRemoval());

        BmRightsHelper.LocatedObject unknown =
            BmRightsHelper.locateObject(configuration, "Catalog.Goods.Atribute.Foo"); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, unknown.present);
        assertEquals("Atribute", unknown.missingKind); //$NON-NLS-1$
        assertNull(unknown.presenceForRemoval());

        BmRightsHelper.RightsGate gate = modelGate();
        BmRightsHelper.FileRightResult refused = BmRightsHelper.applyRightToFile(project, "FullRights", //$NON-NLS-1$
            "Catalog.Goods.Attribute.Missing", "Read", true, false, gate); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(refused.ok);
        assertEquals("notFound", refused.failureKind); //$NON-NLS-1$
        assertEquals("object", refused.subject); //$NON-NLS-1$

        BmRightsHelper.FileRightResult typo = BmRightsHelper.applyRightToFile(project, "FullRights", //$NON-NLS-1$
            "Catalog.Goods.Atribute.Foo", "Read", true, false, gate); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(typo.ok);
        assertEquals("notFound", typo.failureKind); //$NON-NLS-1$
        Path file = rightsFile("FullRights"); //$NON-NLS-1$
        assertFalse(Files.exists(file)
            && Files.readString(file, StandardCharsets.UTF_8).contains("Atribute")); //$NON-NLS-1$
    }

    /**
     * The configuration root is stored as {@code Configuration.<name>}, the way EDT names it, in
     * whichever language and case the caller wrote it.
     *
     * @throws Exception when the written file cannot be read
     */
    @Test
    public void theConfigurationRootIsStoredWithItsName() throws Exception
    {
        assertEquals("Configuration.Cfg", //$NON-NLS-1$
            BmRightsHelper.locateObject(configuration, "Configuration").canonicalFqn); //$NON-NLS-1$
        assertEquals("Configuration.Cfg", //$NON-NLS-1$
            BmRightsHelper.locateObject(configuration, "Конфигурация.Cfg").canonicalFqn); //$NON-NLS-1$
        assertEquals("Configuration.Cfg", //$NON-NLS-1$
            BmRightsHelper.locateObject(configuration, "configuration.cfg").canonicalFqn); //$NON-NLS-1$
        assertEquals("Configuration.Cfg", //$NON-NLS-1$
            BmRightsHelper.locateObject(configuration, "Cfg").canonicalFqn); //$NON-NLS-1$
        assertEquals("Configuration.Cfg", //$NON-NLS-1$
            BmRightsHelper.locateObject(configuration, "Конфигурация").canonicalFqn); //$NON-NLS-1$

        BmRightsHelper.RightsGate gate = modelGate();
        BmRightsHelper.FileRightResult written = BmRightsHelper.applyRightToFile(project, "Keeper", //$NON-NLS-1$
            "Конфигурация.Cfg", "ThinClient", true, false, gate); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(written.error == null ? "written" : written.error, written.ok); //$NON-NLS-1$
        String text = Files.readString(rightsFile("Keeper"), StandardCharsets.UTF_8); //$NON-NLS-1$
        assertTrue(text, text.contains("<name>Configuration.Cfg</name>")); //$NON-NLS-1$
    }

    /**
     * A block stored with the Russian type is the block the English address updates: no second
     * block of the same object is added.
     *
     * @throws Exception when the rights file cannot be read or written
     */
    @Test
    public void aBlockStoredInRussianIsTheSameBlock() throws Exception
    {
        BmRightsHelper.RightsGate gate = modelGate();
        BmRightsHelper.FileRightResult first = BmRightsHelper.applyRightToFile(project, "Keeper", //$NON-NLS-1$
            "Catalog.Товары", "Read", true, false, gate); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(first.error == null ? "written" : first.error, first.ok); //$NON-NLS-1$
        Path file = rightsFile("Keeper"); //$NON-NLS-1$
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, text.replace("<name>Catalog.Товары</name>", "<name>Справочник.Товары</name>"), //$NON-NLS-1$ //$NON-NLS-2$
            StandardCharsets.UTF_8);

        BmRightsHelper.FileRightResult second = BmRightsHelper.applyRightToFile(project, "Keeper", //$NON-NLS-1$
            "Catalog.Товары", "Update", true, false, gate); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(second.error == null ? "written" : second.error, second.ok); //$NON-NLS-1$
        assertFalse(second.objectCreated);
        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(after, after.contains("<name>Catalog.Товары</name>")); //$NON-NLS-1$
        assertEquals(BmRightsHelper.spellingKey("Catalog.Товары.StandardAttribute.Description"), //$NON-NLS-1$
            BmRightsHelper.spellingKey("Справочник.товары.СтандартныйРеквизит.Description")); //$NON-NLS-1$
    }

    /**
     * An address that only starts with the configuration type is not the root. The write is
     * refused and the role file gains no root block.
     *
     * @throws Exception when the rights file cannot be read
     */
    @Test
    public void aMisstatedConfigurationAddressDoesNotWriteTheRoot() throws Exception
    {
        BmRightsHelper.RightsGate gate = modelGate();
        String[] addresses = {
            "Configuration.Опечатка", //$NON-NLS-1$
            "Configuration.Cfg.Subsystem.Foo", //$NON-NLS-1$
            "Конфигурация.Чужое.Имя" //$NON-NLS-1$
        };
        for (String address : addresses)
        {
            BmRightsHelper.LocatedObject located = BmRightsHelper.locateObject(configuration, address);
            assertEquals(address, Boolean.FALSE, located.present);
            assertNull(address, located.canonicalFqn);
            BmRightsHelper.FileRightResult refused = BmRightsHelper.applyRightToFile(project, "RootGuard", //$NON-NLS-1$
                address, "ThinClient", true, false, gate); //$NON-NLS-1$
            assertFalse(refused.ok);
            assertEquals(address, "notFound", refused.failureKind); //$NON-NLS-1$
        }
        Path file = rightsFile("RootGuard"); //$NON-NLS-1$
        assertFalse(Files.exists(file)
            && Files.readString(file, StandardCharsets.UTF_8).contains("<name>Configuration")); //$NON-NLS-1$
    }

    /**
     * A root block stored as {@code Configuration} is the same block as {@code Configuration.Cfg}.
     * Either address rewrites the name and does not add a second block.
     *
     * @throws Exception when the rights file cannot be read or written
     */
    @Test
    public void anOldConfigurationBlockBecomesTheNamedRoot() throws Exception
    {
        theOldRootBlockIsRenamed("Configuration.Cfg"); //$NON-NLS-1$
        theOldRootBlockIsRenamed("Configuration"); //$NON-NLS-1$
    }

    /**
     * {@code Перерасчет} on a register that has {@code recalculations} is not refused. A Latin
     * typo {@code Atribute} still is.
     *
     * @throws Exception when the rights file cannot be read
     */
    @Test
    public void aRussianKindOutsideTheTablesIsNotRefused() throws Exception
    {
        CalculationRegister payroll = configuration.getCalculationRegisters().get(0);
        StringBuilder features = new StringBuilder();
        for (EStructuralFeature feature : payroll.eClass().getEAllStructuralFeatures())
        {
            if (feature.isMany())
            {
                features.append(feature.getName()).append(' ');
            }
        }
        assertTrue(features.toString(), features.toString().contains("recalculations")); //$NON-NLS-1$

        String address = "CalculationRegister.Payroll.Перерасчет.Bonus"; //$NON-NLS-1$
        BmRightsHelper.LocatedObject located = BmRightsHelper.locateObject(configuration, address);
        assertNull(located.present);
        assertNull(located.missingKind);
        assertNull(located.presenceForRemoval());

        BmRightsHelper.RightsGate gate = modelGate();
        BmRightsHelper.FileRightResult written = BmRightsHelper.applyRightToFile(project, "RussianKind", //$NON-NLS-1$
            address, "Read", true, false, gate); //$NON-NLS-1$
        assertTrue(written.error == null ? "written" : written.error, written.ok); //$NON-NLS-1$
        assertNull(written.failureKind);

        BmRightsHelper.OrphanSweep sweep = BmRightsHelper.sweepOrphanedRights(project, "RussianKind", //$NON-NLS-1$
            fqn -> BmRightsHelper.locateObject(configuration, fqn).presenceForRemoval(), true);
        assertTrue(sweep.error == null ? "swept" : sweep.error, sweep.ok); //$NON-NLS-1$
        assertFalse(sweep.orphaned.contains(address));
        assertTrue(Files.readString(rightsFile("RussianKind"), StandardCharsets.UTF_8) //$NON-NLS-1$
            .contains("Перерасчет")); //$NON-NLS-1$

        BmRightsHelper.LocatedObject typo =
            BmRightsHelper.locateObject(configuration, "Catalog.Goods.Atribute.Foo"); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, typo.present);
        assertEquals("Atribute", typo.missingKind); //$NON-NLS-1$
        BmRightsHelper.FileRightResult refused = BmRightsHelper.applyRightToFile(project, "RussianKind", //$NON-NLS-1$
            "Catalog.Goods.Atribute.Foo", "Read", true, false, gate); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(refused.ok);
        assertEquals("notFound", refused.failureKind); //$NON-NLS-1$
        assertFalse(Files.readString(rightsFile("RussianKind"), StandardCharsets.UTF_8) //$NON-NLS-1$
            .contains("Atribute")); //$NON-NLS-1$
    }

    /**
     * {@code Attribut} is not the {@code attributes} collection. The member is refused as an
     * unknown kind, and a sweep leaves the block in place.
     *
     * @throws Exception when the rights file cannot be read or written
     */
    @Test
    public void aShortenedKindIsLeftByTheSweep() throws Exception
    {
        String address = "Catalog.Goods.Attribut.Price"; //$NON-NLS-1$
        BmRightsHelper.LocatedObject located = BmRightsHelper.locateObject(configuration, address);
        assertEquals(Boolean.FALSE, located.present);
        assertEquals("Attribut", located.missingKind); //$NON-NLS-1$
        assertNull(located.presenceForRemoval());

        Path file = rightsFile("AttributRole"); //$NON-NLS-1$
        Files.createDirectories(file.getParent());
        Files.writeString(file, rightsDocument(address), StandardCharsets.UTF_8);
        BmRightsHelper.OrphanSweep sweep = BmRightsHelper.sweepOrphanedRights(project, "AttributRole", //$NON-NLS-1$
            fqn -> BmRightsHelper.locateObject(configuration, fqn).presenceForRemoval(), true);
        assertTrue(sweep.error == null ? "swept" : sweep.error, sweep.ok); //$NON-NLS-1$
        assertFalse(sweep.changed);
        assertFalse(sweep.orphaned.contains(address));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("<name>" + address + "</name>")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The feature name {@code urlTemplates} is stored as {@code URLTemplate}, the spelling from
     * the kind table.
     */
    @Test
    public void urlTemplatesIsStoredAsUrlTemplate()
    {
        BmRightsHelper.LocatedObject method = BmRightsHelper.locateObject(configuration,
            "HTTPService.Api.urlTemplates.Items.Method.Get"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, method.present);
        assertEquals("HTTPService.Api.URLTemplate.Items.Method.Get", method.canonicalFqn); //$NON-NLS-1$
    }

    /**
     * A read that fails with a filesystem exception does not put the file path into the answer.
     *
     * @throws Exception when the blocking directory cannot be created
     */
    @Test
    public void aDeniedReadDoesNotNameThePath() throws Exception
    {
        Path role = projectDir.resolve("src").resolve("Roles").resolve("Unreadable"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Files.createDirectories(role.resolve("Rights.rights")); //$NON-NLS-1$

        BmRightsHelper.FileRightResult refused = BmRightsHelper.applyRightToFile(project, "Unreadable", //$NON-NLS-1$
            "Catalog.Goods", "Read", true, false, //$NON-NLS-1$ //$NON-NLS-2$
            modelGate());

        assertNotNull(refused.error);
        assertFalse(refused.ok);
        assertFalse(refused.error, refused.error.contains(projectDir.getFileName().toString()));
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
        BmRightsHelper.RightsGate gate = modelGate();
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
        assertFalse(error, error.contains(projectDir.getFileName().toString()));
    }

    /**
     * A gate over the in-memory configuration whose applicable rights come from the static table
     * straight away. The headless runtime has no platform version for the rights registry, so
     * {@code IRightInfosService.getRights} waits 120 seconds per call and then answers nothing;
     * the gate then falls back to this same table for a top-level object and to "not decided" for
     * a child. Everything else is the model gate's answer.
     *
     * @return the gate
     */
    private static BmRightsHelper.RightsGate modelGate()
    {
        BmRightsHelper.RightsGate model = BmRightsHelper.RightsGate.forConfiguration(configuration);
        return new BmRightsHelper.RightsGate()
        {
            @Override
            public Boolean roleExists(String roleName)
            {
                return model.roleExists(roleName);
            }

            @Override
            public Boolean objectExists(String targetFqn)
            {
                return model.objectExists(targetFqn);
            }

            @Override
            public Set<String> applicableRights(String targetFqn)
            {
                if (targetFqn == null || targetFqn.chars().filter(c -> c == '.').count() > 1)
                {
                    return null;
                }
                return ApplicableRightsResolver.knownRights(ApplicableRightsResolver.kindOf(targetFqn));
            }

            @Override
            public String roleDirectoryName(String roleName)
            {
                return model.roleDirectoryName(roleName);
            }

            @Override
            public String objectFqnToWrite(String targetFqn)
            {
                return model.objectFqnToWrite(targetFqn);
            }
        };
    }

    /**
     * A configuration with catalogs {@code Goods} and {@code Товары}, a calculation register
     * {@code Payroll}, roles used by the rights writes, and an HTTP service method.
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

        Role keeper = MdClassFactory.eINSTANCE.createRole();
        keeper.setName("Keeper"); //$NON-NLS-1$
        configuration.getRoles().add(keeper);

        Role rootGuard = MdClassFactory.eINSTANCE.createRole();
        rootGuard.setName("RootGuard"); //$NON-NLS-1$
        configuration.getRoles().add(rootGuard);

        Role legacyRoot = MdClassFactory.eINSTANCE.createRole();
        legacyRoot.setName("LegacyRoot"); //$NON-NLS-1$
        configuration.getRoles().add(legacyRoot);

        Role russianKind = MdClassFactory.eINSTANCE.createRole();
        russianKind.setName("RussianKind"); //$NON-NLS-1$
        configuration.getRoles().add(russianKind);

        CalculationRegister payroll = MdClassFactory.eINSTANCE.createCalculationRegister();
        payroll.setName("Payroll"); //$NON-NLS-1$
        configuration.getCalculationRegisters().add(payroll);

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
     * Writes {@code address} onto a role file that already stores the root as {@code Configuration}
     * and checks that the file holds one block named {@code Configuration.Cfg}.
     *
     * @param address the address of the write
     * @throws Exception when the file cannot be written or read
     */
    private static void theOldRootBlockIsRenamed(String address) throws Exception
    {
        Path file = rightsFile("LegacyRoot"); //$NON-NLS-1$
        Files.createDirectories(file.getParent());
        Files.writeString(file, rightsDocument("Configuration"), StandardCharsets.UTF_8); //$NON-NLS-1$
        BmRightsHelper.RightsGate gate = modelGate();
        BmRightsHelper.FileRightResult written = BmRightsHelper.applyRightToFile(project, "LegacyRoot", //$NON-NLS-1$
            address, "ThinClient", true, false, gate); //$NON-NLS-1$
        assertTrue(address + (written.error == null ? "" : " " + written.error), written.ok); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(written.objectCreated);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(1, countOf(text, "<name>Configuration.Cfg</name>")); //$NON-NLS-1$
        assertFalse(text, text.contains("<name>Configuration</name>")); //$NON-NLS-1$
        assertEquals(1, countOf(text, "<object>")); //$NON-NLS-1$
    }

    /**
     * A rights document with one object block.
     *
     * @param objectName the object name to store
     * @return the document text
     */
    private static String rightsDocument(String objectName)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Rights>\n  <object><name>" //$NON-NLS-1$
            + objectName
            + "</name><right><name>Read</name><value>true</value></right></object>\n</Rights>\n"; //$NON-NLS-1$
    }

    /**
     * How many times {@code token} occurs in {@code text}.
     *
     * @param text the text
     * @param token the token
     * @return the count
     */
    private static int countOf(String text, String token)
    {
        int count = 0;
        int from = 0;
        while (true)
        {
            int at = text.indexOf(token, from);
            if (at < 0)
            {
                return count;
            }
            count++;
            from = at + token.length();
        }
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
