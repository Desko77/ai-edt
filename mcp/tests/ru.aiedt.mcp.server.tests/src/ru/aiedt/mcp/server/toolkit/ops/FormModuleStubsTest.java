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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * What {@link FormModuleStubs} writes into the module of a form.
 * <p>
 * A procedure the module does not declare is appended in the module's own line delimiters; one it
 * declares, in any letter case, is left alone and reported as already present, with the difference
 * when its directive or parameters are not what the event calls. A procedure with a region goes before
 * the end of that region, or into the region created at the end of the module. A module that does
 * not exist yet is created. A preview writes nothing.
 * </p>
 */
public class FormModuleStubsTest
{
    private static final String PROJECT = "AiEdtFormModuleStubsProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String FORM = "Catalog.Products.Form.ItemForm"; //$NON-NLS-1$

    private static final String ORIGINAL = "&НаСервере" + CRLF + "Процедура Другая()" + CRLF //$NON-NLS-1$ //$NON-NLS-2$
        + "КонецПроцедуры" + CRLF; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    @BeforeClass
    public static void aProjectWithAFormModule() throws Exception
    {
        root = Files.createTempDirectory("aiedt-form-module-stubs"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/Catalogs/Products/Forms/ItemForm")); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("src/Catalogs/Products/Forms/ListForm")); //$NON-NLS-1$
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

    @Before
    public void theModuleHoldsOneProcedure() throws Exception
    {
        Files.write(itemFormModule(), ORIGINAL.getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(listFormModule());
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    @Test
    public void theModuleOfAFormIsNamedFromItsAddress()
    {
        assertEquals("Catalogs/Products/Forms/ItemForm/Module.bsl", FormModuleStubs.modulePathOf(FORM)); //$NON-NLS-1$
        assertEquals("Catalogs/Products/Forms/ItemForm/Module.bsl", //$NON-NLS-1$
            FormModuleStubs.modulePathOf(FORM + ".Form")); //$NON-NLS-1$
        assertEquals("Catalogs/Товары/Forms/ФормаЭлемента/Module.bsl", //$NON-NLS-1$
            FormModuleStubs.modulePathOf("Справочник.Товары.Форма.ФормаЭлемента")); //$NON-NLS-1$
        assertEquals("CommonForms/Settings/Module.bsl", FormModuleStubs.modulePathOf("CommonForm.Settings")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(FormModuleStubs.modulePathOf("Catalog.Products")); //$NON-NLS-1$
        assertNull(FormModuleStubs.modulePathOf("Catalog.Products.Attribute.Name")); //$NON-NLS-1$
    }

    @Test
    public void aProcedureTheModuleLacksIsAppendedInItsLineDelimiters() throws Exception
    {
        FormModuleStubs.Outcome outcome = FormModuleStubs.append(project, FORM, "ПриОткрытии", //$NON-NLS-1$
            FormModuleStubs.commandHandlerStub("ПриОткрытии"), false); //$NON-NLS-1$

        assertTrue(String.valueOf(outcome.error), outcome.written);
        String text = read(itemFormModule());
        assertTrue(text, text.contains("Процедура ПриОткрытии(Команда)")); //$NON-NLS-1$
        assertTrue(text, text.contains("Процедура Другая()")); //$NON-NLS-1$
        assertFalse("a bare LF among CRLF: " + text, text.replace(CRLF, "").contains("\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aProcedureTheModuleDeclaresIsLeftAloneInAnyCase() throws Exception
    {
        FormModuleStubs.Outcome outcome = FormModuleStubs.append(project, FORM, "другая", //$NON-NLS-1$
            FormModuleStubs.commandHandlerStub("другая"), false); //$NON-NLS-1$

        assertFalse(outcome.written);
        assertEquals(FormModuleStubs.ALREADY_PRESENT, outcome.skippedReason);
        assertEquals(ORIGINAL, read(itemFormModule()));
    }

    @Test
    public void aModuleThatDoesNotExistIsCreated() throws Exception
    {
        FormModuleStubs.Outcome outcome = FormModuleStubs.append(project, "Catalog.Products.Form.ListForm", //$NON-NLS-1$
            "Обновить", FormModuleStubs.commandHandlerStub("Обновить"), false); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(String.valueOf(outcome.error), outcome.written);
        assertTrue(read(listFormModule()).contains("Процедура Обновить(Команда)")); //$NON-NLS-1$
    }

    @Test
    public void aPreviewWritesNothing() throws Exception
    {
        FormModuleStubs.Outcome outcome = FormModuleStubs.append(project, FORM, "ПриОткрытии", //$NON-NLS-1$
            FormModuleStubs.commandHandlerStub("ПриОткрытии"), true); //$NON-NLS-1$

        assertFalse(outcome.written);
        assertEquals(FormModuleStubs.DRY_RUN, outcome.skippedReason);
        assertEquals(ORIGINAL, read(itemFormModule()));
    }

    @Test
    public void aHandlerGoesIntoItsRegionBeforeItsEnd() throws Exception
    {
        String module = String.join(CRLF, "#Область ОбработчикиСобытийФормы", "", //$NON-NLS-1$ //$NON-NLS-2$
            "&НаСервере", "Процедура ПриСозданииНаСервере(Отказ, СтандартнаяОбработка)", //$NON-NLS-1$ //$NON-NLS-2$
            "КонецПроцедуры", "", "#КонецОбласти", "", "#Область СлужебныеПроцедурыИФункции", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "#КонецОбласти", ""); //$NON-NLS-1$ //$NON-NLS-2$
        writeItemForm(module);

        FormModuleStubs.Outcome outcome = FormModuleStubs.append(project, FORM, "ПриОткрытии", //$NON-NLS-1$
            "\n&НаКлиенте\nПроцедура ПриОткрытии(Отказ)\n    \nКонецПроцедуры\n", //$NON-NLS-1$
            FormModuleStubs.FORM_EVENTS, false);

        assertTrue(String.valueOf(outcome.error), outcome.written);
        assertEquals("ОбработчикиСобытийФормы", outcome.region); //$NON-NLS-1$
        assertFalse(outcome.regionCreated);
        String text = read(itemFormModule());
        int added = text.indexOf("Процедура ПриОткрытии(Отказ)"); //$NON-NLS-1$
        assertTrue(text, added > text.indexOf("Процедура ПриСозданииНаСервере")); //$NON-NLS-1$
        assertTrue(text, added < text.indexOf("#КонецОбласти")); //$NON-NLS-1$
        assertTrue(text, text.contains("КонецПроцедуры" + CRLF + CRLF + "#КонецОбласти" + CRLF + CRLF //$NON-NLS-1$ //$NON-NLS-2$
            + "#Область СлужебныеПроцедурыИФункции")); //$NON-NLS-1$
        assertFalse("a bare LF among CRLF: " + text, text.replace(CRLF, "").contains("\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aModuleWithoutTheRegionGetsItAtTheEnd() throws Exception
    {
        FormModuleStubs.Outcome outcome = FormModuleStubs.append(project, FORM, "Обновить", //$NON-NLS-1$
            FormModuleStubs.commandHandlerStub("Обновить"), FormModuleStubs.COMMANDS, false); //$NON-NLS-1$

        assertTrue(String.valueOf(outcome.error), outcome.written);
        assertEquals("ОбработчикиКомандФормы", outcome.region); //$NON-NLS-1$
        assertTrue(outcome.regionCreated);
        String text = read(itemFormModule());
        assertTrue(text, text.startsWith(ORIGINAL));
        int opens = text.indexOf("#Область ОбработчикиКомандФормы"); //$NON-NLS-1$
        assertTrue(text, opens > 0 && opens < text.indexOf("Процедура Обновить(Команда)")); //$NON-NLS-1$
        assertTrue(text, text.trim().endsWith("#КонецОбласти")); //$NON-NLS-1$
    }

    @Test
    public void aModuleWithEnglishRegionsGetsAnEnglishOne() throws Exception
    {
        writeItemForm(String.join(CRLF, "#Region FormEventHandlers", "", "#EndRegion", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        FormModuleStubs.Outcome outcome = FormModuleStubs.append(project, FORM, "Обновить", //$NON-NLS-1$
            FormModuleStubs.commandHandlerStub("Обновить"), FormModuleStubs.COMMANDS, false); //$NON-NLS-1$

        assertTrue(String.valueOf(outcome.error), outcome.written);
        assertEquals("FormCommandsEventHandlers", outcome.region); //$NON-NLS-1$
        String text = read(itemFormModule());
        assertTrue(text, text.contains("#Region FormCommandsEventHandlers")); //$NON-NLS-1$
        assertTrue(text, text.trim().endsWith("#EndRegion")); //$NON-NLS-1$
    }

    @Test
    public void theEndOfARegionCountsTheRegionsInsideIt()
    {
        String text = String.join("\n", "#Область ОбработчикиКомандФормы", "#Область Печать", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "#КонецОбласти", "", "#КонецОбласти", "#region formcommandseventhandlers", "#endregion"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals(5, FormModuleStubs.regionEndLine(text, FormModuleStubs.COMMANDS));
        assertEquals(-1, FormModuleStubs.regionEndLine(text, FormModuleStubs.FORM_EVENTS));
        assertEquals(-1, FormModuleStubs.regionEndLine("#Область ОбработчикиКомандФормы\n", //$NON-NLS-1$
            FormModuleStubs.COMMANDS));
    }

    @Test
    public void aDeclaredProcedureOfAnotherShapeIsReported() throws Exception
    {
        FormModuleStubs.Outcome outcome = FormModuleStubs.append(project, FORM, "Другая", //$NON-NLS-1$
            FormModuleStubs.commandHandlerStub("Другая"), FormModuleStubs.COMMANDS, false); //$NON-NLS-1$

        assertEquals(FormModuleStubs.ALREADY_PRESENT, outcome.skippedReason);
        assertTrue(String.valueOf(outcome.existingMismatch), outcome.existingMismatch != null
            && outcome.existingMismatch.contains("&НаСервере") && outcome.existingMismatch.contains("&НаКлиенте")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ORIGINAL, read(itemFormModule()));
    }

    @Test
    public void aDeclaredProcedureOfTheSameShapeIsNotReported()
    {
        String stub = FormModuleStubs.commandHandlerStub("Обновить"); //$NON-NLS-1$

        assertNull(FormModuleStubs.shapeMismatch("&НаКлиенте\nПроцедура обновить(Кнопка)\nКонецПроцедуры\n", //$NON-NLS-1$
            "Обновить", stub)); //$NON-NLS-1$
        assertNull(FormModuleStubs.shapeMismatch("&AtClient\nProcedure Обновить(Command)\nEndProcedure\n", //$NON-NLS-1$
            "Обновить", stub)); //$NON-NLS-1$
        assertNotNull(FormModuleStubs.shapeMismatch("&НаКлиенте\nПроцедура Обновить()\nКонецПроцедуры\n", //$NON-NLS-1$
            "Обновить", stub)); //$NON-NLS-1$
        assertNotNull(FormModuleStubs.shapeMismatch("Процедура Обновить(Команда)\nКонецПроцедуры\n", //$NON-NLS-1$
            "Обновить", stub)); //$NON-NLS-1$
    }

    private static void writeItemForm(String text) throws Exception
    {
        Files.write(itemFormModule(), text.getBytes(StandardCharsets.UTF_8));
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    private static Path itemFormModule()
    {
        return root.resolve(PROJECT).resolve("src/Catalogs/Products/Forms/ItemForm/Module.bsl"); //$NON-NLS-1$
    }

    private static Path listFormModule()
    {
        return root.resolve(PROJECT).resolve("src/Catalogs/Products/Forms/ListForm/Module.bsl"); //$NON-NLS-1$
    }

    private static String read(Path file) throws Exception
    {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text; //$NON-NLS-1$
    }
}
