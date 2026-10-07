/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;

/**
 * The handler stub of a web service module is decided by the functions the module declares, the
 * way the HTTP service one is: whole name, whatever the case. A name that only opens a longer one,
 * a mention in a comment, a mention in a string literal and a procedure of that name are not the
 * handler; a module that cannot be parsed is refused with its file untouched.
 */
public class AWebServiceHandlerStubIsWrittenByNameTest
{
    private ClusterWorkspaceProbe probe;

    private Path modulePath;

    /**
     * Creates a project holding the module of one web service.
     *
     * @throws Exception when the project or the file cannot be created
     */
    @Before
    public void createProject() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("WebServiceHandlerStub"); //$NON-NLS-1$
        modulePath = probe.project.getLocation().toFile().toPath()
            .resolve("src/WebServices/Заказы/Module.bsl"); //$NON-NLS-1$
        writeModule("Функция GetAll() Экспорт\n\tВозврат \"\";\nКонецФункции\n"); //$NON-NLS-1$
    }

    /**
     * Removes the project and its directory.
     *
     * @throws Exception when the project cannot be removed
     */
    @After
    public void removeProject() throws Exception
    {
        probe.close();
    }

    /**
     * A handler whose name only opens a longer function's name is not declared, so its stub is
     * written once, the longer function keeps its place, and the next call recognizes the stub.
     */
    @Test
    public void aLongerFunctionNameDoesNotCoverTheRequestedHandler() throws Exception
    {
        String path = appendStub("Get"); //$NON-NLS-1$

        assertTrue(path, path.endsWith("WebServices/Заказы/Module.bsl")); //$NON-NLS-1$
        String text = Files.readString(modulePath, StandardCharsets.UTF_8);
        assertTrue("the requested handler gets its stub", //$NON-NLS-1$
            text.contains("Функция Get() Экспорт")); //$NON-NLS-1$
        assertTrue("the longer function keeps its place", //$NON-NLS-1$
            text.contains("Функция GetAll() Экспорт")); //$NON-NLS-1$
        assertEquals("the stub lands once", text.indexOf("Функция Get()"), //$NON-NLS-1$ //$NON-NLS-2$
            text.lastIndexOf("Функция Get()")); //$NON-NLS-1$

        byte[] once = Files.readAllBytes(modulePath);
        appendStub("Get"); //$NON-NLS-1$
        assertArrayEquals("the next call recognizes the stub it wrote", once, //$NON-NLS-1$
            Files.readAllBytes(modulePath));
    }

    /**
     * A handler named in a comment is not declared by it.
     */
    @Test
    public void aNameInACommentIsNotAHandler() throws Exception
    {
        writeModule("// Обработчик Функция GetOrderById вызывается платформой.\n" //$NON-NLS-1$
            + "Функция GetAll() Экспорт\n\tВозврат \"\";\nКонецФункции\n"); //$NON-NLS-1$

        appendStub("GetOrderById"); //$NON-NLS-1$

        String text = Files.readString(modulePath, StandardCharsets.UTF_8);
        assertTrue("a name in a comment declares nothing", //$NON-NLS-1$
            text.contains("Функция GetOrderById() Экспорт")); //$NON-NLS-1$
    }

    /**
     * A handler named inside a string literal is not declared by it.
     */
    @Test
    public void aNameInAStringLiteralIsNotAHandler() throws Exception
    {
        writeModule("Функция GetAll() Экспорт\n" //$NON-NLS-1$
            + "\tОписание = \"Функция GetOrderById вызывается снаружи\";\n" //$NON-NLS-1$
            + "\tВозврат Описание;\nКонецФункции\n"); //$NON-NLS-1$

        appendStub("GetOrderById"); //$NON-NLS-1$

        String text = Files.readString(modulePath, StandardCharsets.UTF_8);
        assertTrue("a name in a string literal declares nothing", //$NON-NLS-1$
            text.contains("Функция GetOrderById() Экспорт")); //$NON-NLS-1$
    }

    /**
     * A handler the module declares in another case is the same handler, and nothing is written.
     */
    @Test
    public void aHandlerSpelledInAnotherCaseIsRecognized() throws Exception
    {
        writeModule("Функция ПолучитьЗаказы() Экспорт\n\tВозврат \"\";\nКонецФункции\n"); //$NON-NLS-1$
        byte[] before = Files.readAllBytes(modulePath);

        String path = appendStub("получитьзаказы"); //$NON-NLS-1$

        assertTrue(path, path.endsWith("WebServices/Заказы/Module.bsl")); //$NON-NLS-1$
        assertArrayEquals("a handler of another case is the same handler", before, //$NON-NLS-1$
            Files.readAllBytes(modulePath));
    }

    /**
     * A procedure of that name is not the function the operation calls, and a longer function name
     * is not the name either, so the stub is written.
     */
    @Test
    public void aSameNamedProcedureIsNotAFunction() throws Exception
    {
        writeModule("Процедура Get()\nКонецПроцедуры\n\n" //$NON-NLS-1$
            + "Функция GetAll() Экспорт\n\tВозврат \"\";\nКонецФункции\n"); //$NON-NLS-1$

        appendStub("Get"); //$NON-NLS-1$

        String text = Files.readString(modulePath, StandardCharsets.UTF_8);
        assertTrue("a procedure is not the handler the operation calls", //$NON-NLS-1$
            text.contains("Функция Get() Экспорт")); //$NON-NLS-1$
        assertTrue("the procedure keeps its place", text.contains("Процедура Get()")); //$NON-NLS-1$
    }

    /**
     * A module whose text cannot be parsed is refused and its file stays as it was.
     */
    @Test
    public void aBrokenModuleIsRefusedWithItsFileUntouched() throws Exception
    {
        writeModule("Функция GetAll() Экспорт\n\tВозврат \"незакрытая строка;\nКонецФункции\n"); //$NON-NLS-1$
        byte[] before = Files.readAllBytes(modulePath);

        try
        {
            appendStub("GetOrderById"); //$NON-NLS-1$
            fail("a module that does not parse has to refuse the stub"); //$NON-NLS-1$
        }
        catch (InvocationTargetException refused)
        {
            String reason = String.valueOf(refused.getCause().getMessage());
            assertTrue(reason, reason.contains("was not written")); //$NON-NLS-1$
            assertTrue(reason, reason.contains("never closed")); //$NON-NLS-1$
        }
        assertArrayEquals("the refused write touches nothing", before, Files.readAllBytes(modulePath)); //$NON-NLS-1$
    }

    /**
     * A handler the module declares is left alone on every call.
     */
    @Test
    public void aDeclaredHandlerIsLeftAloneOnEveryCall() throws Exception
    {
        byte[] before = Files.readAllBytes(modulePath);

        appendStub("GetAll"); //$NON-NLS-1$
        appendStub("GetAll"); //$NON-NLS-1$

        assertArrayEquals("a declared handler writes nothing", before, //$NON-NLS-1$
            Files.readAllBytes(modulePath));
    }

    /**
     * Both service branches read the same module text the same way: a procedure of the name is not
     * the handler and gets its stub, a function of the name is the handler and is left alone.
     */
    @Test
    public void bothBranchesReadTheSameModuleTheSameWay() throws Exception
    {
        Path httpModule = probe.project.getLocation().toFile().toPath()
            .resolve("src/HTTPServices/Заказы/Module.bsl"); //$NON-NLS-1$
        String procedureOnly = "Процедура Get()\nКонецПроцедуры\n"; //$NON-NLS-1$
        writeModule(modulePath, procedureOnly);
        writeModule(httpModule, procedureOnly);

        appendStub("Get"); //$NON-NLS-1$
        appendHttpStub("Get"); //$NON-NLS-1$

        assertTrue(Files.readString(modulePath, StandardCharsets.UTF_8)
            .contains("Функция Get() Экспорт")); //$NON-NLS-1$
        assertTrue(Files.readString(httpModule, StandardCharsets.UTF_8)
            .contains("Функция Get(Запрос)")); //$NON-NLS-1$

        String functionDeclared = "Функция Get() Экспорт\n\tВозврат \"\";\nКонецФункции\n"; //$NON-NLS-1$
        writeModule(modulePath, functionDeclared);
        writeModule(httpModule, functionDeclared);
        byte[] webServiceBefore = Files.readAllBytes(modulePath);
        byte[] httpBefore = Files.readAllBytes(httpModule);

        appendStub("Get"); //$NON-NLS-1$
        appendHttpStub("Get"); //$NON-NLS-1$

        assertArrayEquals("the web service branch leaves a declared handler alone", //$NON-NLS-1$
            webServiceBefore, Files.readAllBytes(modulePath));
        assertArrayEquals("the HTTP branch leaves a declared handler alone", //$NON-NLS-1$
            httpBefore, Files.readAllBytes(httpModule));
    }

    private void writeModule(String text) throws Exception
    {
        writeModule(modulePath, text);
    }

    private void writeModule(Path file, String text) throws Exception
    {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        probe.project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    private String appendStub(String handler) throws Exception
    {
        Method append = ServiceOps.class.getDeclaredMethod("appendWebServiceHandlerStubIfMissing", //$NON-NLS-1$
            IProject.class, String.class, String.class);
        append.setAccessible(true);
        return (String)append.invoke(null, probe.project, "Заказы", handler); //$NON-NLS-1$
    }

    private String appendHttpStub(String handler) throws Exception
    {
        Method append = ServiceOps.class.getDeclaredMethod("appendHandlerStubIfMissing", //$NON-NLS-1$
            IProject.class, String.class, String.class);
        append.setAccessible(true);
        return (String)append.invoke(null, probe.project, "Заказы", handler); //$NON-NLS-1$
    }
}
