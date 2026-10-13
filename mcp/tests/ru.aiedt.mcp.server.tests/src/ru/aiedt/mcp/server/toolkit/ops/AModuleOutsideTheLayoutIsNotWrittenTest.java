/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.MetadataTypeCatalog;

/**
 * A write whose modulePath sits outside the project's layout is refused, and a write inside it
 * behaves as before.
 * <p>
 * A path that spelled the kind rather than its folder - {@code CommonModule} for
 * {@code CommonModules} - used to answer success and leave a file no object reads. The write now
 * judges the path before anything is read or written, a dry run included, and names the canonical
 * path or the missing object. A new module file of an existing object stays allowed, the
 * configuration root's own modules keep their place, and a read that misses the file names the
 * canonical path beside the refusal it already gave.
 * </p>
 * <p>
 * The module file name is judged against the place the path reaches, not against the kind it opens
 * with: a form of a catalog takes its module, a table of an external data source takes its object,
 * record set and manager modules, and a recalculation of a calculation register takes its record set
 * module alone.
 * </p>
 */
public class AModuleOutsideTheLayoutIsNotWrittenTest
{
    private static final String PROJECT = "AiEdtLayoutProbe"; //$NON-NLS-1$

    private static final String OBJECT_MODULE_TEXT = "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 \u041F\u0435\u0440\u0435\u0434\u0417\u0430\u043F\u0438\u0441\u044C\u044E(\u041E\u0442\u043A\u0430\u0437)\r\n" //$NON-NLS-1$
        + "\t\u0412\u043E\u0437\u0432\u0440\u0430\u0442;\r\n" //$NON-NLS-1$
        + "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B\r\n"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    private static Path projectDir;

    /**
     * Creates a project holding one catalog with its object module and one form, a common module, a
     * register, a constant, an external data source with a table, a cube and a dimension table, a
     * calculation register with a recalculation, the common module's form folder, the configuration
     * root's folder, a bot with its module, a second bot without one, and a role whose module is in
     * place.
     *
     * @throws Exception when the project or its files cannot be created
     */
    @BeforeClass
    public static void aProjectWithOneCatalog() throws Exception
    {
        root = Files.createTempDirectory("aiedt-layout-probe"); //$NON-NLS-1$
        projectDir = root.resolve(PROJECT);
        Path objectModule = projectDir.resolve("src/Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/ObjectModule.bsl"); //$NON-NLS-1$
        Files.createDirectories(objectModule.getParent());
        Files.write(objectModule, OBJECT_MODULE_TEXT.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(projectDir.resolve("src/CommonModules/\u041F\u0440\u043E\u0431\u0430/Forms/AnyForm")); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("src/InformationRegisters/Ledger")); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("src/Constants/Counter")); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("src/ExternalDataSources/Source")); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("src/ExternalDataSources/Source/Tables/Таблица")); //$NON-NLS-1$
        Files.createDirectories(
            projectDir.resolve("src/ExternalDataSources/Source/Cubes/Куб/DimensionTables/ТаблицаИзмерения")); //$NON-NLS-1$
        Files.createDirectories(
            projectDir.resolve("src/CalculationRegisters/РегистрРасчета/Recalculations/Перерасчет")); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("src/Catalogs/Товары/Forms/ФормаТовара")); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("src/Configuration")); //$NON-NLS-1$
        Path botModule = projectDir.resolve("src/Bots/\u041F\u0440\u043E\u0431\u0430/Module.bsl"); //$NON-NLS-1$
        Files.createDirectories(botModule.getParent());
        Files.write(botModule, OBJECT_MODULE_TEXT.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(projectDir.resolve("src/Bots/\u0412\u0442\u043E\u0440\u043E\u0439")); //$NON-NLS-1$
        Path roleModule = projectDir.resolve("src/Roles/\u0411\u0430\u0437\u043E\u0432\u044B\u0435/Module.bsl"); //$NON-NLS-1$
        Files.createDirectories(roleModule.getParent());
        Files.write(roleModule, OBJECT_MODULE_TEXT.getBytes(StandardCharsets.UTF_8));
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Removes the project and its directory.
     *
     * @throws Exception when the project cannot be removed
     */
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
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
        }
    }

    /** A kind spelled by its name instead of its folder is refused with the canonical path. */
    @Test
    public void aSingularKindFolderIsRefusedWithTheCanonicalPath()
    {
        String answer = write("append", "CommonModule/\u041F\u0440\u043E\u0431\u0430/Module.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("CommonModules/\u041F\u0440\u043E\u0431\u0430/Module.bsl")); //$NON-NLS-1$
        assertTrue("no folder appeared outside the layout", //$NON-NLS-1$
            !projectDir.resolve("src/CommonModule").toFile().exists()); //$NON-NLS-1$
    }

    /** A leading src/ is stripped before the layout is judged, and the refusal is the same. */
    @Test
    public void aLeadingSrcIsStrippedBeforeTheLayoutIsJudged()
    {
        String answer = write("append", "src/CommonModule/\u041F\u0440\u043E\u0431\u0430/Module.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("CommonModules/\u041F\u0440\u043E\u0431\u0430/Module.bsl")); //$NON-NLS-1$
        assertTrue("no folder appeared outside the layout", //$NON-NLS-1$
            !projectDir.resolve("src/CommonModule").toFile().exists()); //$NON-NLS-1$
    }

    /** A dry run answers the refusal a real write would get, not a preview. */
    @Test
    public void aPreviewIsRefusedAsTheWriteWouldBe()
    {
        String answer = write("append", "CommonModule/\u041F\u0440\u043E\u0431\u0430/Module.bsl", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("CommonModules/\u041F\u0440\u043E\u0431\u0430/Module.bsl")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("status: preview")); //$NON-NLS-1$
        assertTrue("no folder appeared outside the layout", //$NON-NLS-1$
            !projectDir.resolve("src/CommonModule").toFile().exists()); //$NON-NLS-1$
    }

    /** A module of an object the configuration does not hold is refused, and no folder appears. */
    @Test
    public void anObjectThatIsNotInTheConfigurationIsNotCreated()
    {
        String answer = write("append", "CommonModules/\u041D\u0435\u0442\u0422\u0430\u043A\u043E\u0433\u043E/Module.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("CommonModules/\u041D\u0435\u0442\u0422\u0430\u043A\u043E\u0433\u043E")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("no object")); //$NON-NLS-1$
        assertTrue("no folder appeared for an absent object", //$NON-NLS-1$
            !projectDir.resolve("src/CommonModules/\u041D\u0435\u0442\u0422\u0430\u043A\u043E\u0433\u043E").toFile().exists()); //$NON-NLS-1$
    }

    /** A new module file of an existing object is written, the folders staying as they were. */
    @Test
    public void aNewModuleFileOfAnExistingObjectIsWritten()
    {
        String answer = write("append", "Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/ManagerModule.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        Path manager = projectDir.resolve("src/Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/ManagerModule.bsl"); //$NON-NLS-1$
        assertTrue("the manager module was created", manager.toFile().isFile()); //$NON-NLS-1$
        try
        {
            assertTrue(Files.readString(manager, StandardCharsets.UTF_8)
                .contains("\u041F\u0440\u043E\u0432\u0435\u0440\u043A\u0430\u0417\u0430\u043F\u0438\u0441\u0438")); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            throw new AssertionError("the written manager module could not be read back", e); //$NON-NLS-1$
        }
    }

    /** A file name no place in the layout accepts is refused with the names it accepts. */
    @Test
    public void aFileThatIsNoModuleNameIsRefusedWithTheAllowedNames()
    {
        String answer = write("append", "Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/\u0427\u0443\u0436\u043E\u0435.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Module.bsl")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("ManagerModule.bsl")); //$NON-NLS-1$
        assertTrue("no file appeared under a name of its own", //$NON-NLS-1$
            !projectDir.resolve("src/Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/\u0427\u0443\u0436\u043E\u0435.bsl").toFile().exists()); //$NON-NLS-1$
    }

    /** A canonical path of an existing module keeps writing as before. */
    @Test
    public void aCanonicalPathOfAnExistingModuleWritesAsBefore()
    {
        Map<String, String> args = args("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", "Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/ObjectModule.bsl", //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "searchReplace", //$NON-NLS-1$ //$NON-NLS-2$
            "oldSource", "\u0412\u043E\u0437\u0432\u0440\u0430\u0442;", //$NON-NLS-1$ //$NON-NLS-2$
            "source", "\u0415\u0441\u043B\u0438 \u0418\u0441\u0442\u0438\u043D\u0430 \u0422\u043E\u0433\u0434\u0430\r\n\t\u0412\u043E\u0437\u0432\u0440\u0430\u0442;\r\n\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438;"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new ModuleSourceWriter().execute(args);

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
    }

    /** The configuration root's own module names keep their place beside the kind folders. */
    @Test
    public void theConfigurationRootModulesKeepTheirPlace()
    {
        String answer = write("append", "Configuration/SessionModule.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue("the session module was created", //$NON-NLS-1$
            projectDir.resolve("src/Configuration/SessionModule.bsl").toFile().isFile()); //$NON-NLS-1$

        String refused = write("append", "Configuration/\u0427\u0443\u0436\u043E\u0435.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused, refused.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(refused, refused.contains("SessionModule.bsl")); //$NON-NLS-1$
        assertTrue("no file appeared beside the root's own modules", //$NON-NLS-1$
            !projectDir.resolve("src/Configuration/\u0427\u0443\u0436\u043E\u0435.bsl").toFile().exists()); //$NON-NLS-1$
    }

    /** A kind that owns no module files at all is refused with that said. */
    @Test
    public void aKindWithoutModulesIsRefused()
    {
        String answer = write("append", "Roles/\u041F\u0440\u043E\u0447\u0438\u0435/Module.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("owns no module files")); //$NON-NLS-1$
        assertTrue("no folder appeared under a kind without modules", //$NON-NLS-1$
            !projectDir.resolve("src/Roles/\u041F\u0440\u043E\u0447\u0438\u0435").toFile().exists()); //$NON-NLS-1$
    }

    /** A common module owns one module file, so the object module of another kind is refused. */
    @Test
    public void aCommonModuleTakesItsSingleModuleAlone()
    {
        String answer = write("append", "CommonModules/\u041F\u0440\u043E\u0431\u0430/ObjectModule.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:"));
        assertTrue(answer, answer.contains("The module files here are: Module.bsl."));
        assertTrue("no object module appeared beside a common module", //$NON-NLS-1$
            !projectDir.resolve("src/CommonModules/\u041F\u0440\u043E\u0431\u0430/ObjectModule.bsl").toFile().exists()); //$NON-NLS-1$
    }

    /** A catalog takes its object module and its manager module, and not the record set of a register. */
    @Test
    public void aCatalogDoesNotTakeARecordSetModule()
    {
        String answer = write("append", "Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/RecordSetModule.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:"));
        assertTrue(answer, answer.contains("ObjectModule.bsl"));
        assertTrue(answer, answer.contains("ManagerModule.bsl"));
        assertTrue("no record set module appeared beside a catalog", //$NON-NLS-1$
            !projectDir.resolve("src/Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/RecordSetModule.bsl").toFile().exists()); //$NON-NLS-1$
    }

    /** A register takes its record set module and refuses the object module of a catalog-like object. */
    @Test
    public void aRegisterTakesItsRecordSetModuleAndNoObjectModule()
    {
        String refused = write("append", "InformationRegisters/Ledger/ObjectModule.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(refused, refused.startsWith("Error:"));
        assertTrue(refused, refused.contains("RecordSetModule.bsl"));
        assertTrue("no object module appeared beside a register", //$NON-NLS-1$
            !projectDir.resolve("src/InformationRegisters/Ledger/ObjectModule.bsl").toFile().exists()); //$NON-NLS-1$

        String written = write("append", "InformationRegisters/Ledger/RecordSetModule.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(written, written.contains("status: success")); //$NON-NLS-1$
        assertTrue("the record set module was created", //$NON-NLS-1$
            projectDir.resolve("src/InformationRegisters/Ledger/RecordSetModule.bsl").toFile().isFile()); //$NON-NLS-1$
    }

    /** A constant takes its value manager module. */
    @Test
    public void aConstantTakesItsValueManagerModule()
    {
        String answer = write("append", "Constants/Counter/ValueManagerModule.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue("the value manager module was created", //$NON-NLS-1$
            projectDir.resolve("src/Constants/Counter/ValueManagerModule.bsl").toFile().isFile()); //$NON-NLS-1$
    }

    /**
     * A module file the project already holds is written, whatever kind its folder belongs to: the
     * folder and name questions guard what a write creates, and this write creates nothing.
     */
    @Test
    public void anExistingFileIsWrittenThoughItsKindOwnsNoModule()
    {
        String answer = write("append", "Roles/Базовые/Module.bsl", null); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
    }

    /** A kind the catalog holds no directory for keeps its own module file writeable. */
    @Test
    public void anExistingModuleOfAKindWithoutACatalogDirectoryIsWritten()
    {
        String answer = write("append", "Bots/Проба/Module.bsl", null); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        Path botModule = projectDir.resolve("src/Bots/Проба/Module.bsl"); //$NON-NLS-1$
        try
        {
            assertTrue("the source reached the module the project holds", //$NON-NLS-1$
                Files.readString(botModule, StandardCharsets.UTF_8)
                    .contains("ПроверкаЗаписи")); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            throw new AssertionError("the written bot module could not be read back", e); //$NON-NLS-1$
        }
    }

    /** A new module file of an existing object of such a kind is created. */
    @Test
    public void aNewModuleOfAKindWithoutACatalogDirectoryIsCreated()
    {
        String answer = write("append", "Bots/Второй/Module.bsl", null); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue("the bot module was created", //$NON-NLS-1$
            projectDir.resolve("src/Bots/Второй/Module.bsl").toFile().isFile()); //$NON-NLS-1$
    }

    /** Such a kind owns one module file, so the object module of another kind is refused. */
    @Test
    public void aBotTakesItsSingleModuleAlone()
    {
        String answer = write("append", "Bots/Второй/ObjectModule.bsl", null); //$NON-NLS-1$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("The module files here are: Module.bsl."));
        assertTrue("no object module appeared beside a bot", //$NON-NLS-1$
            !projectDir.resolve("src/Bots/Второй/ObjectModule.bsl").toFile().exists()); //$NON-NLS-1$
    }

    /** A kind that owns no module file of its own is refused with that said. */
    @Test
    public void aKindThatOwnsNoModuleOfItsOwnIsRefused()
    {
        String answer = write("append", "ExternalDataSources/Source/Module.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:"));
        assertTrue(answer, answer.contains("owns no module files of its own")); //$NON-NLS-1$
        assertTrue("no module file appeared under a kind that owns none", //$NON-NLS-1$
            !projectDir.resolve("src/ExternalDataSources/Source/Module.bsl").toFile().exists()); //$NON-NLS-1$
    }

    /** A form level under a kind that holds no forms is refused with that said. */
    @Test
    public void aFormLevelUnderAKindWithoutFormsIsRefused()
    {
        String answer = write("append", "CommonModules/\u041F\u0440\u043E\u0431\u0430/Forms/AnyForm/Module.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:"));
        assertTrue(answer, answer.contains("holds no forms")); //$NON-NLS-1$
        assertTrue("no file appeared under a form level the kind does not have", //$NON-NLS-1$
            !projectDir.resolve("src/CommonModules/\u041F\u0440\u043E\u0431\u0430/Forms/AnyForm/Module.bsl").toFile().exists()); //$NON-NLS-1$
    }

    /** A form of a catalog is a level that catalog holds, so the form's module is written. */
    @Test
    public void aFormOfACatalogTakesItsModule()
    {
        String answer = write("append", "Catalogs/Товары/Forms/ФормаТовара/Module.bsl", null); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue("the form's module was created", //$NON-NLS-1$
            projectDir.resolve("src/Catalogs/Товары/Forms/ФормаТовара/Module.bsl").toFile().isFile()); //$NON-NLS-1$
    }

    /**
     * A folder below an object that is no level of the layout is refused, and the refusal names the
     * levels that place holds.
     */
    @Test
    public void aFolderThatIsNoLevelOfTheObjectIsRefusedWithTheLevels()
    {
        String answer = write("append", "Catalogs/Товары/Чужое/Чужое2/Module.bsl", null); //$NON-NLS-1$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("The levels here are: Forms, Commands.")); //$NON-NLS-1$
        assertTrue("no folder appeared under a name the layout does not know", //$NON-NLS-1$
            !projectDir.resolve("src/Catalogs/Товары/Чужое").toFile().exists()); //$NON-NLS-1$
    }

    /**
     * A path that puts the file where the layout has an object folder is refused: below a kind every
     * level folder comes with the name of the object it holds.
     */
    @Test
    public void aFileWhereTheLayoutHasAnObjectFolderIsRefused()
    {
        String answer = write("append", "Catalogs/Товары/Forms/Module.bsl", null); //$NON-NLS-1$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("names no module of the layout")); //$NON-NLS-1$
        assertTrue("no file appeared inside a level folder", //$NON-NLS-1$
            !projectDir.resolve("src/Catalogs/Товары/Forms/Module.bsl").toFile().exists()); //$NON-NLS-1$
    }

    /** A table of an external data source takes its object module. */
    @Test
    public void aTableOfAnExternalDataSourceTakesItsObjectModule()
    {
        String answer =
            write("append", "ExternalDataSources/Source/Tables/Таблица/ObjectModule.bsl", null); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue("the table's object module was created", //$NON-NLS-1$
            projectDir.resolve("src/ExternalDataSources/Source/Tables/Таблица/ObjectModule.bsl").toFile() //$NON-NLS-1$
                .isFile());
    }

    /** A table refuses a file name no table of an external data source takes. */
    @Test
    public void aTableRefusesTheModuleFileOfAnotherPlace()
    {
        String answer =
            write("append", "ExternalDataSources/Source/Tables/Таблица/ValueManagerModule.bsl", null); //$NON-NLS-1$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("ObjectModule.bsl")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("RecordSetModule.bsl")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("ManagerModule.bsl")); //$NON-NLS-1$
        assertTrue("no file appeared under a name a table does not take", //$NON-NLS-1$
            !projectDir.resolve("src/ExternalDataSources/Source/Tables/Таблица/ValueManagerModule.bsl").toFile() //$NON-NLS-1$
                .exists());
    }

    /**
     * A cube of an external data source holds its dimension tables, and a dimension table takes its
     * manager module while the cube itself does not.
     */
    @Test
    public void aDimensionTableOfACubeTakesItsManagerModule()
    {
        String answer = write("append",
            "ExternalDataSources/Source/Cubes/Куб/DimensionTables/ТаблицаИзмерения/ManagerModule.bsl", null); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue("the dimension table's manager module was created", //$NON-NLS-1$
            projectDir
                .resolve("src/ExternalDataSources/Source/Cubes/Куб/DimensionTables/ТаблицаИзмерения/ManagerModule.bsl") //$NON-NLS-1$
                .toFile().isFile());

        String refused =
            write("append", "ExternalDataSources/Source/Cubes/Куб/ObjectModule.bsl", null); //$NON-NLS-1$

        assertTrue(refused, refused.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(refused, refused.contains("RecordSetModule.bsl")); //$NON-NLS-1$
        assertTrue("no object module appeared beside a cube", //$NON-NLS-1$
            !projectDir.resolve("src/ExternalDataSources/Source/Cubes/Куб/ObjectModule.bsl").toFile().exists()); //$NON-NLS-1$
    }

    /** A recalculation of a calculation register takes its record set module alone. */
    @Test
    public void aRecalculationTakesItsRecordSetModuleAlone()
    {
        String written = write("append",
            "CalculationRegisters/РегистрРасчета/Recalculations/Перерасчет/RecordSetModule.bsl", null); //$NON-NLS-1$

        assertTrue(written, written.contains("status: success")); //$NON-NLS-1$
        assertTrue("the recalculation's record set module was created", //$NON-NLS-1$
            projectDir
                .resolve("src/CalculationRegisters/РегистрРасчета/Recalculations/Перерасчет/RecordSetModule.bsl") //$NON-NLS-1$
                .toFile().isFile());

        String refused = write("append",
            "CalculationRegisters/РегистрРасчета/Recalculations/Перерасчет/ManagerModule.bsl", null); //$NON-NLS-1$

        assertTrue(refused, refused.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(refused, refused.contains("The module files here are: RecordSetModule.bsl."));
        assertTrue("no manager module appeared beside a recalculation", //$NON-NLS-1$
            !projectDir
                .resolve("src/CalculationRegisters/РегистрРасчета/Recalculations/Перерасчет/ManagerModule.bsl") //$NON-NLS-1$
                .toFile().exists());
    }

    /**
     * The kind table and the level table name only module files the addressing knows, hold a row for
     * every kind with a folder under {@code src/}, and hold no level under a folder no level table row
     * names.
     */
    @Test
    public void theKindTableNamesOnlyFilesTheAddressingKnows()
    {
        Set<String> addressable = new HashSet<>(Arrays.asList(BslModuleAccess.OBJECT_MODULE_FILE_NAMES));
        for (MetadataTypeCatalog.MetadataTypeInfo kind : MetadataTypeCatalog.MetadataTypeInfo.values())
        {
            if (BslModuleAccess.moduleFolderOf(kind) == null)
            {
                // A kind with no folder of its own is refused before the table is consulted.
                continue;
            }
            BslModuleAccess.KindModules modules = BslModuleAccess.kindModules(kind);
            assertNotNull(kind + " has a folder under src/ but no row in the kind table", modules); //$NON-NLS-1$
            assertFilesAreAddressable(kind.toString(), modules, addressable);
            assertHeldLevelsAreNamed(kind.toString(), modules);
        }
        for (Map.Entry<String, BslModuleAccess.Level> entry : BslModuleAccess.nestedLevels().entrySet())
        {
            BslModuleAccess.KindModules modules = entry.getValue().modules();
            assertFilesAreAddressable(entry.getKey(), modules, addressable);
            assertHeldLevelsAreNamed(entry.getKey(), modules);
        }
        assertTrue("an external data source owns no module file of its own", //$NON-NLS-1$
            BslModuleAccess.kindModules(MetadataTypeCatalog.MetadataTypeInfo.EXTERNAL_DATA_SOURCE)
                .objectModules().isEmpty());
    }

    /**
     * Asserts every module file of a place is a name the module addressing probes.
     *
     * @param place the place, named for the failure message
     * @param modules what the place holds
     * @param addressable the file names the addressing knows
     */
    private static void assertFilesAreAddressable(String place, BslModuleAccess.KindModules modules,
        Set<String> addressable)
    {
        for (String fileName : modules.objectModules())
        {
            assertTrue(place + " names " + fileName + ", which the module addressing does not know", //$NON-NLS-1$ //$NON-NLS-2$
                addressable.contains(fileName));
        }
    }

    /**
     * Asserts every level a place holds is spelled by a folder the level table has a row for.
     *
     * @param place the place, named for the failure message
     * @param modules what the place holds
     */
    private static void assertHeldLevelsAreNamed(String place, BslModuleAccess.KindModules modules)
    {
        for (String folder : modules.nestedFolders())
        {
            assertNotNull(place + " holds a level under '" + folder + "', which no level row names", //$NON-NLS-1$ //$NON-NLS-2$
                BslModuleAccess.nestedLevel(folder));
        }
    }

    /**
     * A kind the catalog holds no directory for is addressed by the folder of its own modules, and a
     * kind that owns none has no folder at all.
     */
    @Test
    public void theKindsWithoutACatalogDirectoryNameTheirModuleFolder()
    {
        assertEquals("Bots", //$NON-NLS-1$
            BslModuleAccess.moduleFolderOf(MetadataTypeCatalog.MetadataTypeInfo.BOT));
        assertEquals("IntegrationServices", //$NON-NLS-1$
            BslModuleAccess.moduleFolderOf(MetadataTypeCatalog.MetadataTypeInfo.INTEGRATION_SERVICE));
        assertEquals("WebSocketClients", //$NON-NLS-1$
            BslModuleAccess.moduleFolderOf(MetadataTypeCatalog.MetadataTypeInfo.WEB_SOCKET_CLIENT));
        assertEquals("Catalogs", //$NON-NLS-1$
            BslModuleAccess.moduleFolderOf(MetadataTypeCatalog.MetadataTypeInfo.CATALOG));
        assertNull("a kind that owns no module file has no module folder", //$NON-NLS-1$
            BslModuleAccess.moduleFolderOf(MetadataTypeCatalog.MetadataTypeInfo.ROLE));
        assertNull("the configuration root is no kind", BslModuleAccess.moduleFolderOf(null)); //$NON-NLS-1$
    }

    /** Backslashes and absolute paths are refused for a write, whatever the rest says. */
    @Test
    public void backslashesAndAbsolutePathsAreRefused()
    {
        String backslashes = write("append", "Catalogs\\\u0422\u043E\u0432\u0430\u0440\u044B\\ManagerModule.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(backslashes, backslashes.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(backslashes, backslashes.contains("forward slashes")); //$NON-NLS-1$

        String absolute = write("append", "C:/Temp/Module.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(absolute, absolute.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(absolute, absolute.contains("src/")); //$NON-NLS-1$

        String rooted = write("append", "/etc/Module.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(rooted, rooted.startsWith("Error:")); //$NON-NLS-1$
    }

    /** A path that climbs a parent keeps the refusal it already had. */
    @Test
    public void parentTraversalKeepsItsRefusal()
    {
        String answer = write("append", "../\u0421\u043E\u0441\u0435\u0434\u043D\u0438\u0439/Module.bsl", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("'..'")); //$NON-NLS-1$
    }

    /** A read that misses the file names the canonical path beside the refusal it already gave. */
    @Test
    public void theReaderNamesTheCanonicalPathWhenTheFileIsMissing()
    {
        String answer = new ModuleSourceReader().execute(args("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", "CommonModule/\u041F\u0440\u043E\u0431\u0430/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("no such file")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("src/CommonModules/\u041F\u0440\u043E\u0431\u0430/Module.bsl")); //$NON-NLS-1$
    }

    /** The canonical form is built from the kind the first segment names, the rest untouched. */
    @Test
    public void theCanonicalFormFollowsTheKindOfTheFirstSegment()
    {
        assertEquals("CommonModules/X/Module.bsl", //$NON-NLS-1$
            BslModuleAccess.canonicalModulePathOf("CommonModule/X/Module.bsl")); //$NON-NLS-1$
        assertEquals("Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/ObjectModule.bsl", //$NON-NLS-1$
            BslModuleAccess.canonicalModulePathOf("Catalog/\u0422\u043E\u0432\u0430\u0440\u044B/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals("Catalogs/\u0422\u043E\u0432\u0430\u0440\u044B/ObjectModule.bsl", //$NON-NLS-1$
            BslModuleAccess.canonicalModulePathOf("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A\u0438/\u0422\u043E\u0432\u0430\u0440\u044B/ObjectModule.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonModules/X/Module.bsl", //$NON-NLS-1$
            BslModuleAccess.canonicalModulePathOf("src/CommonModule/X/Module.bsl")); //$NON-NLS-1$
        assertNull("a path that already opens with its kind folder needs no correction", //$NON-NLS-1$
            BslModuleAccess.canonicalModulePathOf("CommonModules/X/Module.bsl")); //$NON-NLS-1$
        assertNull("a kind that owns no folder has no canonical path", //$NON-NLS-1$
            BslModuleAccess.canonicalModulePathOf("Roles/X/Module.bsl")); //$NON-NLS-1$
        assertNull("a segment that names no kind has no canonical path", //$NON-NLS-1$
            BslModuleAccess.canonicalModulePathOf("Martian/X/Module.bsl")); //$NON-NLS-1$
    }

    /**
     * Runs a write into the probe project.
     *
     * @param mode the write mode
     * @param modulePath the module path to write to
     * @param dryRun {@code "true"} for a preview, {@code null} for a real write
     * @return the tool's answer
     */
    private static String write(String mode, String modulePath, String dryRun)
    {
        Map<String, String> args = args("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", modulePath, //$NON-NLS-1$
            "mode", mode, //$NON-NLS-1$
            "source", "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 \u041F\u0440\u043E\u0432\u0435\u0440\u043A\u0430\u0417\u0430\u043F\u0438\u0441\u0438(\u041E\u0442\u043A\u0430\u0437)\r\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "\t\u0412\u043E\u0437\u0432\u0440\u0430\u0442;\r\n" //$NON-NLS-1$
                + "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B"); //$NON-NLS-1$
        if (dryRun != null)
        {
            args.put("dryRun", dryRun); //$NON-NLS-1$
        }
        return new ModuleSourceWriter().execute(args);
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
