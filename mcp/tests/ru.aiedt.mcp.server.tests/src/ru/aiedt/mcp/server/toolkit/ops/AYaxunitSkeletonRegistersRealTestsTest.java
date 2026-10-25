/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What the public {@code yaxunit_test_skeleton} template produces through {@code code_template}.
 * <p>
 * The skeleton is the only YAxUnit sample many agents ever copy, and every claim it makes has to
 * hold on the engine it targets (25.12): registration takes no parameters and names every test,
 * assertions are the engine's own API, and the snippet runs on its own instead of calling an
 * application module the caller has not written. Everything here goes through
 * {@link CodeTemplateTool#execute(Map)} and the JSON it answers - the rendered body is the
 * product, the private renderer behind it is not the contract.
 * </p>
 */
public class AYaxunitSkeletonRegistersRealTestsTest
{
    private static final String TEMPLATE = "yaxunit_test_skeleton"; //$NON-NLS-1$

    /**
     * Renders the skeleton and returns its body from the tool's JSON answer.
     *
     * @param params the call arguments, with the template name already filled in
     * @return the rendered BSL body
     */
    private String renderBody(Map<String, String> params)
    {
        String json = new CodeTemplateTool().execute(params);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertTrue("the call failed: " + json, root.get("success").getAsBoolean()); //$NON-NLS-1$
        JsonElement body = root.get("body"); //$NON-NLS-1$
        assertNotNull("no body in " + json, body); //$NON-NLS-1$
        return body.getAsString();
    }

    /**
     * The test procedures the body declares, other than the registration and lifecycle hooks.
     *
     * @param body the rendered skeleton
     * @param namePrefix the caller's name the test names are built from
     * @return the declared test procedure names, in body order
     */
    private List<String> declaredTests(String body, String namePrefix)
    {
        List<String> names = new ArrayList<>();
        for (String line : body.split("\n")) //$NON-NLS-1$
        {
            String trimmed = line.trim();
            String prefix = "Процедура "; //$NON-NLS-1$
            String suffix = "() Экспорт"; //$NON-NLS-1$
            if (trimmed.startsWith(prefix) && trimmed.endsWith(suffix))
            {
                String name = trimmed.substring(prefix.length(), trimmed.length() - suffix.length());
                boolean isHook = name.equals("ИсполняемыеСценарии") || name.equals("ПередКаждымТестом"); //$NON-NLS-1$ //$NON-NLS-2$
                if (!isHook && name.startsWith(namePrefix))
                {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * The test names the body registers through the engine's registration API.
     *
     * @param body the rendered skeleton
     * @return the registered test names, in body order
     */
    private List<String> registeredTests(String body)
    {
        List<String> names = new ArrayList<>();
        String marker = "ЮТТесты.ДобавитьТест(\""; //$NON-NLS-1$
        int idx = 0;
        while ((idx = body.indexOf(marker, idx)) >= 0)
        {
            int start = idx + marker.length();
            int end = body.indexOf('"', start);
            names.add(body.substring(start, end));
            idx = end;
        }
        return names;
    }

    /**
     * A call's arguments carrying just the template name.
     *
     * @return the arguments map
     */
    private Map<String, String> bareCall()
    {
        Map<String, String> params = new HashMap<>();
        params.put("template", TEMPLATE); //$NON-NLS-1$
        return params;
    }

    /**
     * Registration takes no parameters and every generated test is registered by name.
     * <p>
     * Measured: the old skeleton used the legacy {@code ИсполняемыеСценарии(СписокТестов)}
     * signature, which 25.12 answers with a warning and a retry passing Неопределено - its
     * СписокТестов.Добавить then fails and the module registers nothing.
     * </p>
     */
    @Test
    public void registrationTakesNoParametersAndNamesEveryTest()
    {
        String body = renderBody(bareCall());

        assertTrue("the no-parameter registration is missing", body.contains("ИсполняемыеСценарии() Экспорт")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the legacy signature is still there", body.contains("ИсполняемыеСценарии(СписокТестов)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the legacy list filling is still there", body.contains("СписокТестов.Добавить")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no test is registered", registeredTests(body).isEmpty()); //$NON-NLS-1$
        assertEquals("declared and registered tests differ", declaredTests(body, "ИмяМетода"), registeredTests(body)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Every assertion the skeleton makes is the engine's own 25.12 API.
     * <p>
     * Measured: the old skeleton asserted through {@code Новый ВызовФункции(...)} and chained
     * {@code .НеНеопределено()} - neither exists in 25.12, so the sample did not compile. The
     * engine's chain is ЮТест.ОжидаетЧто(...).Равно / ИмеетДлину.
     * </p>
     */
    @Test
    public void assertionsGoThroughApiTheEngineHas()
    {
        String body = renderBody(bareCall());

        assertTrue("the assertion facade is missing", body.contains("ЮТест.ОжидаетЧто(")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a real equality check is missing", body.contains(".Равно(")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the invented call object is still there", body.contains("Новый ВызовФункции")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the invented assertion is still there", body.contains(".НеНеопределено()")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an unsubstituted placeholder leaked", body.contains("{{")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The skeleton stands on its own: no call into an application module the caller has not
     * written, and its two tests hold a safe observable result each.
     * <p>
     * Measured: the old sample called the subsystem module by name - a module that exists only
     * after the caller writes it - so the registered tests failed on any fresh project.
     * </p>
     */
    @Test
    public void theTestsRunWithoutAnApplicationModule()
    {
        Map<String, String> params = bareCall();
        params.put("name", "РасчетСуммы"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("subsystem", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$
        String body = renderBody(params);

        assertFalse("the sample still calls the caller's module", body.contains("Продажи.РасчетСуммы(")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("two tests were expected", 2, declaredTests(body, "РасчетСуммы").size()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("declared and registered tests differ", declaredTests(body, "РасчетСуммы"), registeredTests(body)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The module header says where the engine looks for the tests: a new common module of a
     * configuration extension with Global and Server call off.
     */
    @Test
    public void theHeaderPlacesTheModuleInTheExtension()
    {
        String body = renderBody(bareCall());

        assertTrue("the extension placement is missing", body.contains("extension")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the Global flag rule is missing", body.contains("Global")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the Server call flag rule is missing", body.contains("Server call")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The caller's name, description and subsystem reach the rendered body and the answer's echo.
     */
    @Test
    public void theSubstitutionsReachTheBody()
    {
        Map<String, String> params = bareCall();
        params.put("name", "РасчетСуммы"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("description", "Проверка расчета суммы"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("subsystem", "Продажи"); //$NON-NLS-1$ //$NON-NLS-2$
        String body = renderBody(params);

        assertTrue("the name did not reach the body", body.contains("РасчетСуммы")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the description did not reach the body", body.contains("Проверка расчета суммы")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the subsystem did not reach the body", body.contains("Продажи")); //$NON-NLS-1$ //$NON-NLS-2$

        String json = new CodeTemplateTool().execute(params);
        assertEquals("РасчетСуммы", JsonParser.parseString(json).getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
