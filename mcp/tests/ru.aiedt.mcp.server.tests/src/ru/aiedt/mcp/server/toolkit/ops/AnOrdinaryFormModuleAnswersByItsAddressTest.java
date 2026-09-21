/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.oform.HandlerBindings;
import ru.aiedt.mcp.server.support.oform.OrdinaryFormCoverage;
import ru.aiedt.mcp.server.support.oform.OrdinaryFormFile;
import ru.aiedt.mcp.server.support.oform.OrdinaryFormModule;
import ru.aiedt.mcp.server.support.oform.V8Container;

/**
 * The module of an ordinary form answers by the address every form module has -
 * {@code <object>/Forms/<form>/Module.bsl} - though no such file exists: the readers read it out
 * of the container, the writer writes it back with the layout untouched, the lister and the text
 * search find it, and the guard names a bound procedure a write would take away.
 *
 * <p>The project is a workspace project over a temporary directory holding two containers built
 * by the server's own writer and one managed form with a real {@code Module.bsl}, so every answer
 * here is decided by the files.</p>
 */
public class AnOrdinaryFormModuleAnswersByItsAddressTest
{
    private static final String PROJECT = "AiEdtOformModuleProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String ITEM_FORM = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static final String ITEM_CONTAINER = "Catalogs/Products/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String LAYOUT = "{27," + CRLF //$NON-NLS-1$
        + "{3,\"ПриОткрытии\"," + CRLF //$NON-NLS-1$
        + "{1,\"ПриОткрытии\"}" + CRLF //$NON-NLS-1$
        + "}," + CRLF //$NON-NLS-1$
        + "{3,\"КнопкаНажатие\"," + CRLF //$NON-NLS-1$
        + "{1,\"Нажатие\"}" + CRLF //$NON-NLS-1$
        + "}" + CRLF //$NON-NLS-1$
        + "}"; //$NON-NLS-1$

    private static final String MODULE = "Процедура ПриОткрытии()" + CRLF //$NON-NLS-1$
        + "\tПодключитьОбработчикОжидания(\"Обновить\", 5);" + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF //$NON-NLS-1$
        + CRLF
        + "Процедура КнопкаНажатие(Элемент)" + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF //$NON-NLS-1$
        + CRLF
        + "Процедура Обновить()" + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF //$NON-NLS-1$
        + CRLF
        + "Функция Служебная() Экспорт" + CRLF //$NON-NLS-1$
        + "\tВозврат 1;" + CRLF //$NON-NLS-1$
        + "КонецФункции" + CRLF; //$NON-NLS-1$

    private static Path root;

    private static Path itemContainer;

    private static IProject project;

    @BeforeClass
    public static void aProjectWithTwoOrdinaryFormsAndAManagedOne() throws Exception
    {
        root = Files.createTempDirectory("aiedt-oform-module"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path item = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Path picker = projectDir.resolve("src/CommonForms/Picker"); //$NON-NLS-1$
        Path managed = projectDir.resolve("src/Catalogs/Products/Forms/Managed"); //$NON-NLS-1$
        Files.createDirectories(item);
        Files.createDirectories(picker);
        Files.createDirectories(managed);
        itemContainer = item.resolve("Form.oform"); //$NON-NLS-1$
        Files.write(itemContainer, container(LAYOUT, MODULE));
        Files.write(picker.resolve("Form.oform"), container("{27," + CRLF + "{1}" + CRLF + "}", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Процедура Выбрать()" + CRLF + "КонецПроцедуры" + CRLF)); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(managed.resolve("Module.bsl"), //$NON-NLS-1$
            withBom("&НаКлиенте" + CRLF + "Процедура Управляемая()" + CRLF + "КонецПроцедуры" + CRLF)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        OrdinaryFormCoverage.reset();
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
     * The address resolves to the container; a real file beside a form wins; an address that is
     * not a form module's answers nothing.
     */
    @Test
    public void theAddressLocatesTheContainer()
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, ITEM_FORM);
        assertNotNull(module);
        assertEquals(ITEM_CONTAINER, module.containerPath());
        assertEquals("Catalog.Products.Form.ItemForm", module.fqn()); //$NON-NLS-1$
        assertNotNull(OrdinaryFormModule.locate(project, "src/CommonForms/Picker/Module.bsl")); //$NON-NLS-1$
        assertNull(OrdinaryFormModule.locate(project, "Catalogs/Products/Forms/Managed/Module.bsl")); //$NON-NLS-1$
        assertNull(OrdinaryFormModule.locate(project, "Catalogs/Products/Forms/Nowhere/Module.bsl")); //$NON-NLS-1$
        assertNull(OrdinaryFormModule.locate(project, "Catalogs/Products/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals("CommonForms/Picker/Module.bsl", OrdinaryFormModule.moduleAddressOf("CommonForms/Picker/Form.oform")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * read_module_source answers by the path and by the FQN, and says where the text came from.
     */
    @Test
    public void theSourceIsReadOutOfTheContainer()
    {
        String byPath = new ModuleSourceReader().execute(args("projectName", PROJECT, "modulePath", ITEM_FORM)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(byPath, byPath.contains("Процедура ПриОткрытии()")); //$NON-NLS-1$
        assertTrue(byPath, byPath.contains("**Source:** " + OrdinaryFormModule.SOURCE)); //$NON-NLS-1$
        assertTrue(byPath, byPath.contains("src/" + ITEM_CONTAINER)); //$NON-NLS-1$
        assertTrue(byPath, byPath.contains("**Lines:** 1-13 of 13")); //$NON-NLS-1$

        String byFqn = new ModuleSourceReader().execute(args("projectName", PROJECT, "modulePath", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
        assertTrue(byFqn, byFqn.contains("## " + ITEM_FORM)); //$NON-NLS-1$
        assertTrue(byFqn, byFqn.contains("Функция Служебная() Экспорт")); //$NON-NLS-1$

        String managed = new ModuleSourceReader().execute(args("projectName", PROJECT, "modulePath", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalogs/Products/Forms/Managed/Module.bsl")); //$NON-NLS-1$
        assertTrue(managed, managed.contains("Управляемая")); //$NON-NLS-1$
        assertFalse(managed, managed.contains("**Source:**")); //$NON-NLS-1$
    }

    /**
     * get_module_structure lists the methods from the container without asking the model.
     */
    @Test
    public void theStructureIsParsedFromTheContainer()
    {
        String outline = new ModuleOutlineReader().execute(args("projectName", PROJECT, "modulePath", ITEM_FORM)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(outline, outline.contains("3 procedures, 1 functions")); //$NON-NLS-1$
        assertTrue(outline, outline.contains("Служебная")); //$NON-NLS-1$
        assertTrue(outline, outline.contains("source: " + OrdinaryFormModule.SOURCE)); //$NON-NLS-1$
        assertTrue(outline, outline.contains("src/" + ITEM_CONTAINER)); //$NON-NLS-1$
    }

    /**
     * read_method_source finds a method in the container and marks the answer.
     */
    @Test
    public void aMethodIsReadByName()
    {
        String method = new MethodSourceReader().execute(args("projectName", PROJECT, "modulePath", ITEM_FORM, //$NON-NLS-1$ //$NON-NLS-2$
            "methodName", "Служебная")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(method, method.contains("Возврат 1;")); //$NON-NLS-1$
        assertTrue(method, method.contains("source: " + OrdinaryFormModule.SOURCE)); //$NON-NLS-1$
        assertTrue(method, method.contains("container: " + ITEM_CONTAINER)); //$NON-NLS-1$
        assertTrue(method, method.contains("type: Function")); //$NON-NLS-1$

        String missing = new MethodSourceReader().execute(args("projectName", PROJECT, "modulePath", ITEM_FORM, //$NON-NLS-1$ //$NON-NLS-2$
            "methodName", "НетТакой")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(missing, missing.contains("no method called 'НетТакой'")); //$NON-NLS-1$
        assertTrue(missing, missing.contains("- Обновить")); //$NON-NLS-1$
    }

    /**
     * A write lands in the container, the layout entry keeps its bytes, and the answer says
     * where the write went and that there was no index to flush.
     */
    @Test
    public void aWriteLandsInTheContainerAndTheLayoutStays() throws Exception
    {
        byte[] layoutBefore = OrdinaryFormFile.read(itemContainer).getContainer().find("form").getData(); //$NON-NLS-1$
        String answer = new ModuleSourceWriter().execute(args("projectName", PROJECT, "modulePath", ITEM_FORM, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "searchReplace", "oldSource", "Возврат 1;", "source", "Возврат 2;")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        try
        {
            assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
            assertTrue(answer, answer.contains("source: " + OrdinaryFormModule.SOURCE)); //$NON-NLS-1$
            assertTrue(answer, answer.contains("container: " + ITEM_CONTAINER)); //$NON-NLS-1$
            assertTrue(answer, answer.contains("persistenceSyncOk: true")); //$NON-NLS-1$
            assertTrue(answer, answer.contains("not applicable")); //$NON-NLS-1$
            assertFalse(answer, answer.contains("unboundProcedures")); //$NON-NLS-1$
            assertTrue(answer, answer.contains("linesBefore: 13")); //$NON-NLS-1$
            assertFalse(answer, answer.contains("newFile")); //$NON-NLS-1$

            OrdinaryFormFile written = OrdinaryFormFile.read(itemContainer);
            assertTrue(written.moduleText(), written.moduleText().contains("Возврат 2;")); //$NON-NLS-1$
            assertTrue(written.moduleText(), written.moduleText().contains(CRLF));
            assertFalse(written.moduleText(), written.moduleText().replace(CRLF, "").contains("\n")); //$NON-NLS-1$ //$NON-NLS-2$
            assertArrayEquals(layoutBefore, written.getContainer().find("form").getData()); //$NON-NLS-1$
            assertFalse(Files.exists(itemContainer.resolveSibling("Module.bsl"))); //$NON-NLS-1$

            String again = new ModuleSourceReader().execute(args("projectName", PROJECT, "modulePath", ITEM_FORM)); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(again, again.contains("Возврат 2;")); //$NON-NLS-1$
        }
        finally
        {
            Files.write(itemContainer, container(LAYOUT, MODULE));
            project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());
        }
    }

    /**
     * A write that removes a procedure the layout binds, or one the module names in a string,
     * is previewed with the names, refused on request with the container unchanged, and
     * written with the names in the answer by default.
     */
    @Test
    public void aBoundProcedureRemovedIsNamedAndRefusedOnRequest() throws Exception
    {
        byte[] before = Files.readAllBytes(itemContainer);
        Map<String, String> removal = args("projectName", PROJECT, "modulePath", ITEM_FORM, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "searchReplace", //$NON-NLS-1$ //$NON-NLS-2$
            "oldSource", "Процедура КнопкаНажатие(Элемент)\nКонецПроцедуры\n\nПроцедура Обновить()\nКонецПроцедуры\n", //$NON-NLS-1$ //$NON-NLS-2$
            "source", "// two procedures gone\n"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Map<String, String> preview = new HashMap<>(removal);
            preview.put("dryRun", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            String previewed = new ModuleSourceWriter().execute(preview);
            assertTrue(previewed, previewed.contains("status: preview")); //$NON-NLS-1$
            assertTrue(previewed, previewed.contains("unboundProcedures: \"КнопкаНажатие, Обновить\"")); //$NON-NLS-1$
            assertTrue(previewed, previewed.contains("handlerChanges: warn")); //$NON-NLS-1$
            assertArrayEquals(before, Files.readAllBytes(itemContainer));

            Map<String, String> refusing = new HashMap<>(removal);
            refusing.put("handlerChanges", "refuse"); //$NON-NLS-1$ //$NON-NLS-2$
            String refused = new ModuleSourceWriter().execute(refusing);
            assertTrue(refused, refused.startsWith("Error: the write was refused")); //$NON-NLS-1$
            assertTrue(refused, refused.contains("КнопкаНажатие, Обновить")); //$NON-NLS-1$
            assertArrayEquals(before, Files.readAllBytes(itemContainer));

            Map<String, String> wrong = new HashMap<>(removal);
            wrong.put("handlerChanges", "ignore"); //$NON-NLS-1$ //$NON-NLS-2$
            String invalid = new ModuleSourceWriter().execute(wrong);
            assertTrue(invalid, invalid.startsWith("Error:") && invalid.contains("handlerChanges")); //$NON-NLS-1$ //$NON-NLS-2$

            String warned = new ModuleSourceWriter().execute(removal);
            assertTrue(warned, warned.contains("status: success")); //$NON-NLS-1$
            assertTrue(warned, warned.contains("unboundProcedures: \"КнопкаНажатие, Обновить\"")); //$NON-NLS-1$
            assertTrue(warned, warned.contains("WARNING: the write removes 2 procedure(s)")); //$NON-NLS-1$
            String module = OrdinaryFormFile.read(itemContainer).moduleText();
            assertFalse(module, module.contains("КнопкаНажатие")); //$NON-NLS-1$
            assertTrue(module, module.contains("two procedures gone")); //$NON-NLS-1$
        }
        finally
        {
            Files.write(itemContainer, before);
            project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());
        }
    }

    /**
     * list_modules lists the module by its address with its own kind, the managed form by its
     * file, and says what the kind means.
     */
    @Test
    public void theListerMarksTheModule()
    {
        String listing = ModulesLister.listModules(PROJECT, "all", null, null, 100); //$NON-NLS-1$
        assertTrue(listing, listing.contains("| " + ITEM_FORM + " | " + OrdinaryFormModule.KIND + " | Catalog | Products |")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(listing, listing.contains("| CommonForms/Picker/Module.bsl | " + OrdinaryFormModule.KIND + " | CommonForm | Picker |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(listing, listing.contains("| Catalogs/Products/Forms/Managed/Module.bsl | FormModule | Catalog | Products |")); //$NON-NLS-1$
        assertTrue(listing, listing.contains("**Module count:** 3 modules")); //$NON-NLS-1$
        assertTrue(listing, listing.contains("2 modules of kind " + OrdinaryFormModule.KIND)); //$NON-NLS-1$
        assertFalse(listing, listing.contains("Form.oform |")); //$NON-NLS-1$
    }

    /**
     * text_search reports a hit inside a container under the module's address.
     */
    @Test
    public void theTextSearchScansTheContainer()
    {
        String found = new CodeTextSearcher().execute(args("projectName", PROJECT, "query", "Возврат 1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(found, found.contains(ITEM_FORM));
        assertFalse(found, found.contains("Form.oform")); //$NON-NLS-1$
        String counted = new CodeTextSearcher().execute(args("projectName", PROJECT, "query", "Процедура", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "outputMode", "files")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(counted, counted.contains(ITEM_FORM));
        assertTrue(counted, counted.contains("CommonForms/Picker/Module.bsl")); //$NON-NLS-1$
        assertTrue(counted, counted.contains("Catalogs/Products/Forms/Managed/Module.bsl")); //$NON-NLS-1$
    }

    /**
     * An index-built answer over this project carries the count of what the index does not see;
     * over nothing it carries nothing; an error is left alone.
     */
    @Test
    public void theCoverageStatementCountsTheForms()
    {
        OrdinaryFormCoverage.reset();
        assertEquals(2, OrdinaryFormCoverage.count(project));
        String statement = OrdinaryFormCoverage.statement(project, "callers"); //$NON-NLS-1$
        assertNotNull(statement);
        assertTrue(statement, statement.startsWith("Coverage: 2 ordinary form modules of this project are outside the BSL index")); //$NON-NLS-1$
        assertTrue(statement, statement.contains("no callers from them are counted")); //$NON-NLS-1$
        String decorated = OrdinaryFormCoverage.appendTo(project, "references", "| a | b |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(decorated, decorated.startsWith("| a | b |\n\n**Coverage: 2 ordinary form modules")); //$NON-NLS-1$
        assertEquals("Error: x", OrdinaryFormCoverage.appendTo(project, "markers", "Error: x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(OrdinaryFormCoverage.statement(null, "markers")); //$NON-NLS-1$
        assertEquals("plain", OrdinaryFormCoverage.appendTo(null, "markers", "plain")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The bindings are read off the layout with doubled quotes undone, string literals are read
     * outside comments, and a procedure that stays is never endangered.
     */
    @Test
    public void theBindingsAreReadOffTheLayoutAndTheModule()
    {
        assertEquals(List.of("КнопкаНажатие", "ПриОткрытии"), //$NON-NLS-1$ //$NON-NLS-2$
            List.copyOf(HandlerBindings.boundProcedures(LAYOUT)));
        assertTrue(HandlerBindings.boundProcedures("{3,\"Имя \"\"в\"\" кавычках\",{1}}").contains("Имя \"в\" кавычках")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(HandlerBindings.boundProcedures(null).isEmpty());

        List<String> lines = List.of("Процедура А()", //$NON-NLS-1$
            "\tПодключитьОбработчикОжидания(\"Обновить\", 5); // \"Закомментировано\"", //$NON-NLS-1$
            "КонецПроцедуры"); //$NON-NLS-1$
        assertTrue(HandlerBindings.literalValues(lines).contains("Обновить")); //$NON-NLS-1$
        assertFalse(HandlerBindings.literalValues(lines).contains("Закомментировано")); //$NON-NLS-1$
        assertEquals(List.of("А"), List.copyOf(HandlerBindings.declaredProcedures(lines))); //$NON-NLS-1$

        List<String> before = OrdinaryFormModule.splitLines(MODULE);
        assertEquals(13, before.size());
        assertTrue(HandlerBindings.endangered(LAYOUT, before, before).isEmpty());
        List<String> without = List.of("Процедура ПриОткрытии()", "КонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("КнопкаНажатие"), HandlerBindings.endangered(LAYOUT, before, without)); //$NON-NLS-1$
        List<String> renamed = List.of("Процедура ПриОткрытии()", "\tОбработать(\"Обновить\");", "КонецПроцедуры", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Процедура кнопканажатие(Элемент)", "КонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Обновить"), HandlerBindings.endangered(LAYOUT, before, renamed)); //$NON-NLS-1$
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
