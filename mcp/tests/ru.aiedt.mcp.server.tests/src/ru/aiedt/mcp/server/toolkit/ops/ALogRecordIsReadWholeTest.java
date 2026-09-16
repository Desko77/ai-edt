/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.junit.Test;

/**
 * A log-record call is judged whole, however many lines it is written across.
 * <p>
 * The scan reads a file line by line and used to ask each line on its own whether it held a whole
 * call. The usual shape - an event name, a level and a comment, one per line - never matched, so
 * exactly the calls that carry the most went unseen while the one-line ones were reported.
 * </p>
 */
public class ALogRecordIsReadWholeTest
{
    /** A stand-in for a file: it answers what a finding needs, which is its path. */
    private static IFile file()
    {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName())
            {
            case "getProjectRelativePath": //$NON-NLS-1$
                return new Path("src/CommonModules/Обмен/Module.bsl"); //$NON-NLS-1$
            case "hashCode": //$NON-NLS-1$
                return Integer.valueOf(System.identityHashCode(proxy));
            case "equals": //$NON-NLS-1$
                return Boolean.valueOf(proxy == args[0]);
            case "toString": //$NON-NLS-1$
                return "a file"; //$NON-NLS-1$
            default:
                return null;
            }
        };
        return (IFile)Proxy.newProxyInstance(ALogRecordIsReadWholeTest.class.getClassLoader(),
            new Class<?>[] {IFile.class}, handler);
    }

    /** Runs the tracker over a whole module, the way the scan walks a file. */
    private static List<Map<String, Object>> scan(String... lines) throws Exception
    {
        Method track = SensitiveDataScanTool.class.getDeclaredMethod("trackLogRecord", //$NON-NLS-1$
            Class.forName("ru.aiedt.mcp.server.toolkit.ops.SensitiveDataScanTool$OpenLogRecord"), //$NON-NLS-1$
            String.class, int.class, IFile.class, List.class);
        track.setAccessible(true);
        SensitiveDataScanTool tool = new SensitiveDataScanTool();
        List<Map<String, Object>> findings = new ArrayList<>();
        Object open = null;
        IFile file = file();
        for (int at = 0; at < lines.length; at++)
        {
            open = track.invoke(tool, open, lines[at], Integer.valueOf(at + 1), file, findings);
        }
        if (open != null)
        {
            Method report = SensitiveDataScanTool.class.getDeclaredMethod("reportSensitiveNames", //$NON-NLS-1$
                Class.forName("ru.aiedt.mcp.server.toolkit.ops.SensitiveDataScanTool$OpenLogRecord"), //$NON-NLS-1$
                IFile.class, List.class);
            report.setAccessible(true);
            report.invoke(null, open, file, findings);
        }
        return findings;
    }

    /** The shape that used to be missed: the call opens on one line and closes on a later one. */
    @Test
    public void aCallWrittenAcrossLinesIsSeen() throws Exception
    {
        List<Map<String, Object>> findings = scan(
            "Процедура ЗаписатьВЖурнал(Пользователь)", //$NON-NLS-1$
            "\tЗаписьЖурналаРегистрации(ИмяСобытия,", //$NON-NLS-1$
            "\t\tУровеньЖурналаРегистрации.Информация,", //$NON-NLS-1$
            "\t\t, ,", //$NON-NLS-1$
            "\t\t\"Вход по token \" + Пользователь.Token);", //$NON-NLS-1$
            "КонецПроцедуры"); //$NON-NLS-1$
        assertEquals(findings.toString(), 1, findings.size());
        assertEquals("named by the line the call starts on", Integer.valueOf(2), //$NON-NLS-1$
            findings.get(0).get("line")); //$NON-NLS-1$
        assertEquals("token", findings.get(0).get("matchedTerm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A call written on one line still reports, as it always did. */
    @Test
    public void aCallOnOneLineIsStillSeen() throws Exception
    {
        List<Map<String, Object>> findings = scan(
            "ЗаписьЖурналаРегистрации(\"Вход\", УровеньЖурналаРегистрации.Информация, , , password);"); //$NON-NLS-1$
        assertEquals(findings.toString(), 1, findings.size());
        assertEquals(Integer.valueOf(1), findings.get(0).get("line")); //$NON-NLS-1$
    }

    /** A call carrying nothing sensitive is not reported, on one line or on five. */
    @Test
    public void aCallWithNothingSensitiveIsNotReported() throws Exception
    {
        List<Map<String, Object>> findings = scan(
            "\tЗаписьЖурналаРегистрации(ИмяСобытия,", //$NON-NLS-1$
            "\t\tУровеньЖурналаРегистрации.Информация,", //$NON-NLS-1$
            "\t\t, , \"Документ проведен\");"); //$NON-NLS-1$
        assertTrue(findings.toString(), findings.isEmpty());
    }

    /** A bracket inside a message does not end the call early. */
    @Test
    public void aBracketInsideAMessageDoesNotEndTheCall() throws Exception
    {
        List<Map<String, Object>> findings = scan(
            "\tЗаписьЖурналаРегистрации(ИмяСобытия,", //$NON-NLS-1$
            "\t\t\"Ошибка (код 5) при проверке\",", //$NON-NLS-1$
            "\t\tПользователь.Password);"); //$NON-NLS-1$
        assertEquals(findings.toString(), 1, findings.size());
        assertEquals("password", findings.get(0).get("matchedTerm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A name inside a longer one is not that name.
     * <p>
     * The first argument of a log record is conventionally ИмяСобытия, and reading the call whole
     * would otherwise have reported every call in a configuration as carrying "имя".
     * </p>
     */
    @Test
    public void aNameInsideALongerNameDoesNotCount() throws Exception
    {
        assertTrue("ИмяСобытия is not a person's name", //$NON-NLS-1$
            scan("ЗаписьЖурналаРегистрации(ИмяСобытия, Уровень.Информация, , , \"готово\");") //$NON-NLS-1$
                .isEmpty());
        assertEquals("and the field itself still counts", 1, //$NON-NLS-1$
            scan("ЗаписьЖурналаРегистрации(\"Вход\", Уровень.Информация, , , Клиент.Имя);") //$NON-NLS-1$
                .size());
    }

    /** Two calls in a row are two findings, not one gathering that swallowed the second. */
    @Test
    public void twoCallsAreTwoFindings() throws Exception
    {
        List<Map<String, Object>> findings = scan(
            "ЗаписьЖурналаРегистрации(\"Вход\", Уровень.Информация, , , Пользователь.Login);", //$NON-NLS-1$
            "ЗаписьЖурналаРегистрации(\"Выход\",", //$NON-NLS-1$
            "\tУровень.Информация, , , Пользователь.Token);"); //$NON-NLS-1$
        assertEquals(findings.toString(), 2, findings.size());
        assertEquals(Integer.valueOf(1), findings.get(0).get("line")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), findings.get(1).get("line")); //$NON-NLS-1$
    }
}
