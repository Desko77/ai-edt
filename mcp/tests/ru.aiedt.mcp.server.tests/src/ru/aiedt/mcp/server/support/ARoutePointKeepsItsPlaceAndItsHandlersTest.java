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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A route map carries back what the caller asked for: the place of every point and the
 * handler of every event.
 * <p>
 * The write path emits {@code Location}, {@code TaskDescription}, the addressing references
 * and the {@code Event} elements of a point; the read path has to answer every one of them.
 * </p>
 * <p>
 * The tests drive the two pure halves against each other - {@code buildRouteMap} makes
 * the document, {@code parseRouteMap} reads it back - so a field one side writes and the
 * other drops shows up as a disagreement rather than as two separately green halves.
 * </p>
 */
public class ARoutePointKeepsItsPlaceAndItsHandlersTest
{
    private static Map<String, String> point(String type, String name)
    {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("type", type); //$NON-NLS-1$
        p.put("name", name); //$NON-NLS-1$
        return p;
    }

    private static Map<String, String> transition(String from, String to)
    {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("from", from); //$NON-NLS-1$
        t.put("to", to); //$NON-NLS-1$
        return t;
    }

    /** A Start - Action - Completion route, the shortest map the writer accepts. */
    private static List<Map<String, String>> threePoints()
    {
        List<Map<String, String>> points = new ArrayList<>();
        points.add(point("Start", "Старт")); //$NON-NLS-1$ //$NON-NLS-2$
        points.add(point("Action", "Выполнить")); //$NON-NLS-1$ //$NON-NLS-2$
        points.add(point("Completion", "Завершение")); //$NON-NLS-1$ //$NON-NLS-2$
        return points;
    }

    private static List<Map<String, String>> twoTransitions()
    {
        List<Map<String, String>> transitions = new ArrayList<>();
        transitions.add(transition("Старт", "Выполнить")); //$NON-NLS-1$ //$NON-NLS-2$
        transitions.add(transition("Выполнить", "Завершение")); //$NON-NLS-1$ //$NON-NLS-2$
        return transitions;
    }

    private static BmRouteMapHelper.RouteMap readBack(BmRouteMapHelper.WritePlan plan)
    {
        assertNull("the plan has to be buildable: " + plan.error, plan.error); //$NON-NLS-1$
        assertNotNull("the plan has to carry a document", plan.xml); //$NON-NLS-1$
        BmRouteMapHelper.RouteMap read =
            BmRouteMapHelper.parseRouteMap(plan.xml.getBytes(StandardCharsets.UTF_8));
        assertNull("the document the writer produced has to parse: " + read.error, read.error); //$NON-NLS-1$
        assertTrue("a parsed document exists", read.exists); //$NON-NLS-1$
        return read;
    }

    private static Map<String, Object> pointNamed(BmRouteMapHelper.RouteMap read, String name)
    {
        for (Map<String, Object> p : read.points)
        {
            if (name.equals(p.get("name"))) //$NON-NLS-1$
            {
                return p;
            }
        }
        throw new AssertionError("no point named " + name + " in " + read.points); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> locationOf(Map<String, Object> point)
    {
        return (Map<String, Object>)point.get("location"); //$NON-NLS-1$
    }

    /** The coordinates the caller gave come back out of the written document. */
    @Test
    public void thePlaceTheCallerGaveIsWrittenAndRead()
    {
        List<Map<String, String>> points = threePoints();
        points.get(1).put("location", //$NON-NLS-1$
            "{\"top\":300,\"left\":140,\"bottom\":360,\"right\":260}"); //$NON-NLS-1$
        BmRouteMapHelper.WritePlan plan = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, twoTransitions(), null, null); //$NON-NLS-1$
        BmRouteMapHelper.RouteMap read = readBack(plan);

        Map<String, Object> location = locationOf(pointNamed(read, "Выполнить")); //$NON-NLS-1$
        assertNotNull("the point was read back without its coordinates", location); //$NON-NLS-1$
        assertEquals(Integer.valueOf(300), location.get("top")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(140), location.get("left")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(360), location.get("bottom")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(260), location.get("right")); //$NON-NLS-1$
    }

    /** A point the caller placed is answered with the coordinates it was written at. */
    @Test
    public void theAnswerNamesThePlaceEveryPointWasWrittenAt()
    {
        List<Map<String, String>> points = threePoints();
        points.get(1).put("location", //$NON-NLS-1$
            "{\"top\":300,\"left\":140,\"bottom\":360,\"right\":260}"); //$NON-NLS-1$
        BmRouteMapHelper.WritePlan plan = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, twoTransitions(), null, null); //$NON-NLS-1$
        assertNull(plan.error);
        assertEquals(3, plan.points.size());

        Map<String, Object> placed = plan.points.get(1);
        assertEquals("Выполнить", placed.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(300), placed.get("top")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(260), placed.get("right")); //$NON-NLS-1$
        assertEquals("the coordinates were the caller's", //$NON-NLS-1$
            Boolean.TRUE, placed.get("locationFromCaller")); //$NON-NLS-1$

        Map<String, Object> laidOut = plan.points.get(0);
        assertEquals("the layout placed this one", //$NON-NLS-1$
            Boolean.FALSE, laidOut.get("locationFromCaller")); //$NON-NLS-1$
        assertNotNull("a laid-out point still reports its place", laidOut.get("top")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A centre with no size takes the point kind's own box. */
    @Test
    public void aCentreWithoutASizeTakesTheKindsOwnBox()
    {
        List<Map<String, String>> points = threePoints();
        points.get(1).put("location", "{\"x\":200,\"y\":400}"); //$NON-NLS-1$ //$NON-NLS-2$
        BmRouteMapHelper.WritePlan plan = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, twoTransitions(), null, null); //$NON-NLS-1$
        BmRouteMapHelper.RouteMap read = readBack(plan);

        Map<String, Object> location = locationOf(pointNamed(read, "Выполнить")); //$NON-NLS-1$
        assertEquals("the centre is the middle of the box", //$NON-NLS-1$
            Integer.valueOf(370), location.get("top")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(430), location.get("bottom")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(140), location.get("left")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(260), location.get("right")); //$NON-NLS-1$
        assertEquals("an Action keeps its own height", //$NON-NLS-1$
            60, (Integer)location.get("bottom") - (Integer)location.get("top")); //$NON-NLS-1$
    }

    /** A location that is neither shape is refused rather than quietly ignored. */
    @Test
    public void aLocationThatIsNotAShapeIsRefused()
    {
        List<Map<String, String>> points = threePoints();
        points.get(1).put("location", "{\"top\":10}"); //$NON-NLS-1$ //$NON-NLS-2$
        BmRouteMapHelper.WritePlan plan = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, twoTransitions(), null, null); //$NON-NLS-1$
        assertNotNull("a half-given corner set is not a place", plan.error); //$NON-NLS-1$
        assertTrue(plan.error, plan.error.contains("Выполнить")); //$NON-NLS-1$ //$NON-NLS-2$

        points.get(1).put("location", "{\"top\":10,\"left\":10,\"bottom\":20,\"right\":20,\"x\":5}"); //$NON-NLS-1$ //$NON-NLS-2$
        BmRouteMapHelper.WritePlan both = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, twoTransitions(), null, null); //$NON-NLS-1$
        assertNotNull("both shapes at once name two different places", both.error); //$NON-NLS-1$

        points.get(1).put("location", "{\"top\":20,\"left\":10,\"bottom\":20,\"right\":40}"); //$NON-NLS-1$ //$NON-NLS-2$
        BmRouteMapHelper.WritePlan flat = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, twoTransitions(), null, null); //$NON-NLS-1$
        assertNotNull("a box of no height is not a place", flat.error); //$NON-NLS-1$
    }

    /** The handler of an event reaches the document and comes back out of it. */
    @Test
    public void theHandlerOfAnEventIsWrittenAndRead()
    {
        List<Map<String, String>> points = threePoints();
        points.get(1).put("handlers", //$NON-NLS-1$
            "{\"onexecute\":\"ВыполнитьЗадание\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        BmRouteMapHelper.WritePlan plan = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, twoTransitions(), null, null); //$NON-NLS-1$
        BmRouteMapHelper.RouteMap read = readBack(plan);

        assertEquals("one handler was written", 1, plan.handlers.size()); //$NON-NLS-1$
        assertEquals("Выполнить", plan.handlers.get(0).get("point")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OnExecute", plan.handlers.get(0).get("event")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ВыполнитьЗадание", plan.handlers.get(0).get("handler")); //$NON-NLS-1$ //$NON-NLS-2$

        @SuppressWarnings("unchecked")
        List<Map<String, String>> events =
            (List<Map<String, String>>)pointNamed(read, "Выполнить").get("events"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("the point was read back without its events", events); //$NON-NLS-1$
        assertEquals("only the handled event carries a name", 1, events.size()); //$NON-NLS-1$
        assertEquals("OnExecute", events.get(0).get("event")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ВыполнитьЗадание", events.get(0).get("handler")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An event the point kind does not declare is refused, and the refusal names its own. */
    @Test
    public void anEventTheKindDoesNotHaveIsRefusedWithItsOwn()
    {
        List<Map<String, String>> points = threePoints();
        points.get(0).put("handlers", "{\"OnExecute\":\"Начать\"}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmRouteMapHelper.WritePlan plan = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, twoTransitions(), null, null); //$NON-NLS-1$
        assertNotNull("a Start has no OnExecute", plan.error); //$NON-NLS-1$
        assertTrue(plan.error, plan.error.contains("BeforeStart")); //$NON-NLS-1$

        points.get(1).put("handlers", "{\"OnExecute\":\"\"}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmRouteMapHelper.WritePlan empty = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, twoTransitions(), null, null); //$NON-NLS-1$
        assertNotNull("a handler name has to name something", empty.error); //$NON-NLS-1$
    }

    /** Names that differ only by letter case are one name, on both sides of the call. */
    @Test
    public void aNameDifferingOnlyByCaseIsTheSameName()
    {
        List<Map<String, String>> points = threePoints();
        points.add(point("Completion", "ЗАВЕРШЕНИЕ")); //$NON-NLS-1$ //$NON-NLS-2$
        BmRouteMapHelper.WritePlan plan = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, twoTransitions(), null, null); //$NON-NLS-1$
        assertNotNull("1C identifiers do not distinguish case", plan.error); //$NON-NLS-1$
        assertTrue(plan.error, plan.error.contains("case")); //$NON-NLS-1$

        // The same reading on the transition side: a name spelled in another case is that point.
        List<Map<String, String>> transitions = new ArrayList<>();
        transitions.add(transition("старт", "ВЫПОЛНИТЬ")); //$NON-NLS-1$ //$NON-NLS-2$
        transitions.add(transition("выполнить", "завершение")); //$NON-NLS-1$ //$NON-NLS-2$
        BmRouteMapHelper.WritePlan cased = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", threePoints(), transitions, null, null); //$NON-NLS-1$
        assertNull("a transition finds its points whatever the case: " + cased.error, cased.error); //$NON-NLS-1$
        assertEquals(2, cased.transitionCount);
    }

    /** A point's title, task text and subprocess survive the round trip. */
    @Test
    public void theTaskTextAndSubprocessAreWrittenAndRead()
    {
        List<Map<String, String>> points = new ArrayList<>();
        points.add(point("Start", "Старт")); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> action = point("Action", "Выполнить"); //$NON-NLS-1$ //$NON-NLS-2$
        action.put("title", "Выполнить заказ"); //$NON-NLS-1$ //$NON-NLS-2$
        action.put("taskDescription", "Проверить и провести"); //$NON-NLS-1$ //$NON-NLS-2$
        points.add(action);
        Map<String, String> nested = point("NestedBusinessProcess", "Вложенный"); //$NON-NLS-1$ //$NON-NLS-2$
        nested.put("subprocess", "BusinessProcess.Подпроцесс"); //$NON-NLS-1$ //$NON-NLS-2$
        points.add(nested);
        points.add(point("Completion", "Завершение")); //$NON-NLS-1$ //$NON-NLS-2$
        List<Map<String, String>> transitions = new ArrayList<>();
        transitions.add(transition("Старт", "Выполнить")); //$NON-NLS-1$ //$NON-NLS-2$
        transitions.add(transition("Выполнить", "Вложенный")); //$NON-NLS-1$ //$NON-NLS-2$
        transitions.add(transition("Вложенный", "Завершение")); //$NON-NLS-1$ //$NON-NLS-2$

        BmRouteMapHelper.RouteMap read = readBack(BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", points, transitions, null, null)); //$NON-NLS-1$

        Map<String, Object> readAction = pointNamed(read, "Выполнить"); //$NON-NLS-1$
        assertEquals("Выполнить заказ", readAction.get("title")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Проверить и провести", readAction.get("taskDescription")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("BusinessProcess.Подпроцесс", //$NON-NLS-1$
            pointNamed(read, "Вложенный").get("subprocess")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The addressing references of a linked Task are written into the point and read back. */
    @Test
    public void theAddressingAttributesOfTheLinkedTaskAreReadBack()
    {
        BmRouteMapHelper.WritePlan plan = BmRouteMapHelper.buildRouteMap(
            "BusinessProcess.Order", threePoints(), twoTransitions(), //$NON-NLS-1$
            "Task.Задача", Arrays.asList("Исполнитель", "Срок")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmRouteMapHelper.RouteMap read = readBack(plan);

        @SuppressWarnings("unchecked")
        List<String> refs = (List<String>)pointNamed(read, "Выполнить") //$NON-NLS-1$ //$NON-NLS-2$
            .get("addressingAttributes"); //$NON-NLS-1$
        assertNotNull("the action point carries the Task's addressing", refs); //$NON-NLS-1$
        assertEquals(Arrays.asList("Task.Задача.AddressingAttribute.Исполнитель", //$NON-NLS-1$
            "Task.Задача.AddressingAttribute.Срок"), refs); //$NON-NLS-1$
        assertFalse("a Start point has no addressing", //$NON-NLS-1$
            pointNamed(read, "Старт").containsKey("addressingAttributes")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
