/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * declares, in any letter case, is left alone and reported as already present. A module that does
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
