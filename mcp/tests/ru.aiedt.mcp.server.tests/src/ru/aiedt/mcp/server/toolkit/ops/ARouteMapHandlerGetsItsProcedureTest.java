/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmRouteMapHelper;

/**
 * A handler a route map names gets a procedure in the object module, with the parameters of its
 * event. The flowchart validator reports a handler name the module does not declare.
 */
public class ARouteMapHandlerGetsItsProcedureTest
{
    /** Every event the route-map writer emits, across the five point kinds. */
    private static final List<String> WRITTEN_EVENTS = Arrays.asList("BeforeStart", "OnComplete", //$NON-NLS-1$ //$NON-NLS-2$
        "InteractiveActivationProcessing", "BeforeCreateTasks", "OnCreateTask", "OnExecute", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "CheckExecutionProcessing", "BeforeExecute", "BeforeExecuteInteractively", "ConditionCheck", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "OnCreateSubBusinessProcesses", "BeforeCreateSubBusinessProcesses"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * One {point, event, handler} entry as the writer reports it.
     *
     * @param point the point name
     * @param event the event name
     * @param handler the handler name
     * @return the entry
     */
    private static Map<String, String> handler(String point, String event, String handler)
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("point", point); //$NON-NLS-1$
        m.put("event", event); //$NON-NLS-1$
        m.put("handler", handler); //$NON-NLS-1$
        return m;
    }

    /**
     * Every event the writer can put into a scheme has a parameter list, and a name that is not a
     * route-point event has none.
     */
    @Test
    public void everyWrittenEventHasItsParameters()
    {
        for (String event : WRITTEN_EVENTS)
        {
            assertNotNull(event, BmRouteMapHelper.handlerParameters(event));
        }
        assertNull(BmRouteMapHelper.handlerParameters("BeforeWrite")); //$NON-NLS-1$
    }

    /**
     * The procedure carries the handler name and the parameters of the event in platform order.
     */
    @Test
    public void theProcedureCarriesTheEventParameters()
    {
        String stub = BmRouteMapHelper.handlerStub("ДействиеПриВыполнении", "onexecute"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stub, stub.startsWith(
            "Процедура ДействиеПриВыполнении(ТочкаМаршрутаБизнесПроцесса, Задача, Отказ)\n")); //$NON-NLS-1$
        assertTrue(stub, stub.endsWith("КонецПроцедуры")); //$NON-NLS-1$
        assertEquals("ТочкаМаршрутаБизнесПроцесса, Результат", //$NON-NLS-1$
            BmRouteMapHelper.handlerParameters("ConditionCheck")); //$NON-NLS-1$
        assertEquals("ТочкаМаршрутаБизнесПроцесса, ФормируемыеЗадачи, СтандартнаяОбработка", //$NON-NLS-1$
            BmRouteMapHelper.handlerParameters("BeforeCreateTasks")); //$NON-NLS-1$
    }

    /**
     * A handler the module declares, in any letter case, is left alone; one it lacks is written;
     * a name given to two events is written once.
     */
    @Test
    public void onlyTheMissingHandlersAreWrittenAndEachOnce()
    {
        List<Map<String, String>> handlers = new ArrayList<>();
        handlers.add(handler("Старт", "BeforeStart", "СтартПередСтартом")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        handlers.add(handler("Выполнить", "OnExecute", "ОбщийОбработчик")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        handlers.add(handler("Проверить", "BeforeExecute", "общийобработчик")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        handlers.add(handler("Условие", "ConditionCheck", "УсловиеПроверкаУсловия")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String module = "Процедура стартпередстартом(ТочкаМаршрутаБизнесПроцесса, Отказ)\n" //$NON-NLS-1$
            + "КонецПроцедуры\n"; //$NON-NLS-1$

        RouteMapOps.HandlerStubs plan = RouteMapOps.planHandlerStubs(handlers, module);

        assertEquals(Arrays.asList("СтартПередСтартом"), plan.alreadyPresent); //$NON-NLS-1$
        assertEquals(Arrays.asList("ОбщийОбработчик", "УсловиеПроверкаУсловия"), plan.names); //$NON-NLS-1$ //$NON-NLS-2$
        String text = plan.text.toString();
        assertTrue(text, text.contains(
            "Процедура ОбщийОбработчик(ТочкаМаршрутаБизнесПроцесса, Задача, Отказ)")); //$NON-NLS-1$
        assertTrue(text, text.contains(
            "Процедура УсловиеПроверкаУсловия(ТочкаМаршрутаБизнесПроцесса, Результат)")); //$NON-NLS-1$
        assertEquals("a name given to two events is one procedure", //$NON-NLS-1$
            text.indexOf("ОбщийОбработчик("), text.lastIndexOf("ОбщийОбработчик(")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Without a module every handler is written.
     */
    @Test
    public void withoutAModuleEveryHandlerIsWritten()
    {
        List<Map<String, String>> handlers = new ArrayList<>();
        handlers.add(handler("Завершение", "OnComplete", "ЗавершениеПриЗавершении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        RouteMapOps.HandlerStubs plan = RouteMapOps.planHandlerStubs(handlers, null);

        assertEquals(Arrays.asList("ЗавершениеПриЗавершении"), plan.names); //$NON-NLS-1$
        assertTrue(plan.alreadyPresent.isEmpty());
    }

    /**
     * The plan carries bare procedures; where they go in the module, blank lines included, is the
     * placement's to say.
     */
    @Test
    public void thePlanCarriesBareProceduresWhateverTheModule()
    {
        List<Map<String, String>> handlers = new ArrayList<>();
        handlers.add(handler("Завершение", "OnComplete", "ЗавершениеПриЗавершении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(RouteMapOps.planHandlerStubs(handlers,
            "Процедура Другая()\r\nКонецПроцедуры").text.toString() //$NON-NLS-1$
                .startsWith("Процедура ЗавершениеПриЗавершении(")); //$NON-NLS-1$
        assertTrue("an empty module needs no line before the first procedure either", //$NON-NLS-1$
            RouteMapOps.planHandlerStubs(handlers, null).text.toString().startsWith("Процедура")); //$NON-NLS-1$
    }

    /**
     * A name given to two events whose handlers take different numbers of parameters is refused,
     * and the refusal names both events with their signatures.
     */
    @Test
    public void aNameOnEventsOfDifferentArityIsRefused()
    {
        List<Map<String, String>> handlers = new ArrayList<>();
        handlers.add(handler("Выполнить", "OnExecute", "ОбщийОбработчик")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        handlers.add(handler("Старт", "BeforeStart", "ОбщийОбработчик")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        RouteMapOps.HandlerStubs plan = RouteMapOps.planHandlerStubs(handlers, null);

        assertTrue(plan.error, plan.error.contains("OnExecute")); //$NON-NLS-1$
        assertTrue(plan.error, plan.error.contains("BeforeStart")); //$NON-NLS-1$
        assertTrue(plan.error, plan.error.contains("ТочкаМаршрутаБизнесПроцесса, Задача, Отказ")); //$NON-NLS-1$
        assertTrue(plan.error, plan.error.contains("ТочкаМаршрутаБизнесПроцесса, Отказ")); //$NON-NLS-1$
        assertTrue("nothing is written for a refused name", plan.names.isEmpty()); //$NON-NLS-1$
        assertEquals(0, plan.text.length());
    }

    /**
     * A name given to two events whose handlers take the same number of parameters is one
     * procedure, written once.
     */
    @Test
    public void aNameOnEventsOfEqualArityIsOneProcedure()
    {
        List<Map<String, String>> handlers = new ArrayList<>();
        handlers.add(handler("Выполнить", "OnExecute", "ОбщийОбработчик")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        handlers.add(handler("Проверить", "BeforeExecute", "ОБЩИЙОБРАБОТЧИК")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        RouteMapOps.HandlerStubs plan = RouteMapOps.planHandlerStubs(handlers, null);

        assertNull(plan.error);
        assertEquals(Arrays.asList("ОбщийОбработчик"), plan.names); //$NON-NLS-1$
        String text = plan.text.toString();
        assertEquals("one name of equal arity is one procedure", //$NON-NLS-1$
            text.indexOf("ОбщийОбработчик("), text.lastIndexOf("ОбщийОбработчик(")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A function of the handler's name in the module does not make the handler present: the module
     * still gets its procedure.
     */
    @Test
    public void aFunctionOfTheHandlerNameDoesNotMakeItPresent()
    {
        List<Map<String, String>> handlers = new ArrayList<>();
        handlers.add(handler("Завершение", "OnComplete", "ЗавершениеПриЗавершении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        RouteMapOps.HandlerStubs plan = RouteMapOps.planHandlerStubs(handlers,
            "Функция ЗавершениеПриЗавершении()\n\tВозврат 1;\nКонецФункции"); //$NON-NLS-1$

        assertTrue(plan.alreadyPresent.isEmpty());
        assertEquals(Arrays.asList("ЗавершениеПриЗавершении"), plan.names); //$NON-NLS-1$
    }

    /**
     * A module that declares every handler gets nothing written.
     */
    @Test
    public void aModuleDeclaringEveryHandlerGetsNothing()
    {
        List<Map<String, String>> handlers = new ArrayList<>();
        handlers.add(handler("Завершение", "OnComplete", "ЗавершениеПриЗавершении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        RouteMapOps.HandlerStubs plan = RouteMapOps.planHandlerStubs(handlers,
            "Процедура ЗавершениеПриЗавершении(ТочкаМаршрутаБизнесПроцесса)\r\nКонецПроцедуры\r\n"); //$NON-NLS-1$

        assertTrue(plan.names.isEmpty());
        assertEquals(0, plan.text.length());
        assertEquals(Arrays.asList("ЗавершениеПриЗавершении"), plan.alreadyPresent); //$NON-NLS-1$
    }
}
