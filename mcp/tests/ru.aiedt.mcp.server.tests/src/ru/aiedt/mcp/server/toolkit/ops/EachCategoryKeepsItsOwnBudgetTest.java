/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.support.ProjectScopeResolver;
import ru.aiedt.mcp.server.support.WatchForCancel;

/**
 * Holds the reference report to a budget per category.
 * <p>
 * The collection cap used to be one number for the whole list, and the list was filled by the
 * metadata phases before the code phase ran, so a modest limit printed only metadata. The report
 * also printed every hit it had kept: the cap the schema promises is per category, and a zero
 * limit stopped the walk before the first hit and then said nothing references the object.
 * </p>
 */
public class EachCategoryKeepsItsOwnBudgetTest
{
    private static final int LIMIT = 8;

    /** A category that has more hits than the limit does not take rows from the next one. */
    @Test
    public void aFatMetadataCategoryDoesNotTakeTheCodeRows() throws Exception
    {
        Object harvester = harvester(LIMIT, null, false, false);
        addMetadata(harvester, "back", 15);
        addCode(harvester, 12);

        String report = format(harvester);
        int codeAt = report.indexOf("### BSL code references"); //$NON-NLS-1$
        int metadataAt = report.indexOf("### Metadata references"); //$NON-NLS-1$
        assertTrue("the code section is present: " + report, codeAt >= 0); //$NON-NLS-1$
        assertTrue("metadata follows code: " + report, metadataAt > codeAt); //$NON-NLS-1$

        String codePart = report.substring(codeAt, metadataAt);
        String metadataPart = report.substring(metadataAt);
        assertEquals("code keeps its own rows", LIMIT, rowsIn(codePart)); //$NON-NLS-1$
        assertEquals("metadata keeps its own rows", LIMIT, rowsIn(metadataPart)); //$NON-NLS-1$
        assertTrue(codePart.contains("... and 4 more")); //$NON-NLS-1$
        assertTrue(metadataPart.contains("... and 7 more")); //$NON-NLS-1$
        assertTrue(report.contains("**Total references located:** 27")); //$NON-NLS-1$
        assertTrue(report.contains("**Shown:** 16 rows")); //$NON-NLS-1$
        assertTrue(report.contains("truncated")); //$NON-NLS-1$
    }

    /** Code alone, and metadata alone, do not grow the other section. */
    @Test
    public void aSingleCategoryDoesNotGrowTheOtherSection() throws Exception
    {
        Object codeOnly = harvester(LIMIT, "bsl", false, false); //$NON-NLS-1$
        addCode(codeOnly, 3);
        String codeReport = format(codeOnly);
        assertTrue(codeReport.contains("### BSL code references")); //$NON-NLS-1$
        assertFalse(codeReport.contains("### Metadata references")); //$NON-NLS-1$
        assertFalse(codeReport.contains("truncated")); //$NON-NLS-1$

        Object metadataOnly = harvester(LIMIT, null, true, false);
        addMetadata(metadataOnly, "back", 3); //$NON-NLS-1$
        String metadataReport = format(metadataOnly);
        assertTrue(metadataReport.contains("### Metadata references")); //$NON-NLS-1$
        assertFalse(metadataReport.contains("### BSL code references")); //$NON-NLS-1$
    }

    /**
     * Five and five sit under a limit of eight, so neither category is cut. The old cap treated
     * the sum as the reason to call the walk truncated.
     */
    @Test
    public void twoShortCategoriesAreNotATruncatedWalk() throws Exception
    {
        Object harvester = harvester(LIMIT, null, false, false);
        addMetadata(harvester, "back", 5); //$NON-NLS-1$
        addCode(harvester, 5);
        String report = format(harvester);
        assertFalse(report.contains("truncated")); //$NON-NLS-1$
        assertFalse(report.contains("... and ")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, field(outcome(harvester), "capped")); //$NON-NLS-1$
    }

    /** Zero and below are refused before a project is opened. */
    @Test
    public void aNonPositiveLimitIsRefusedBeforeAnySearch() throws Exception
    {
        Method readLimit = ReferenceLocator.class.getDeclaredMethod("readLimit", String.class); //$NON-NLS-1$
        readLimit.setAccessible(true);
        assertEquals(Integer.valueOf(-1), readLimit.invoke(null, "0")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(-1), readLimit.invoke(null, "-3")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(-1), readLimit.invoke(null, "0.4")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(100), readLimit.invoke(null, "nope")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(LIMIT), readLimit.invoke(null, "8")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(500), readLimit.invoke(null, "900")); //$NON-NLS-1$

        ReferenceLocator tool = new ReferenceLocator();
        for (String limit : new String[] {"0", "-3", "0.4"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String answer = tool.execute(Map.of("objectFqn", "Catalog.Products", "limit", limit)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(answer, answer.contains("for example 100")); //$NON-NLS-1$
            assertFalse(answer, answer.contains("Nothing references this object.")); //$NON-NLS-1$
            assertFalse(answer, answer.contains("projectName was omitted")); //$NON-NLS-1$
        }
    }

    /** A metadata phase that has filled the old shared cap still leaves room for a code hit. */
    @Test
    public void aFullMetadataPhaseDoesNotDropACodeHit() throws Exception
    {
        Object harvester = harvester(LIMIT, null, false, false);
        addMetadata(harvester, "back", LIMIT * 10); //$NON-NLS-1$
        assertEquals(0, codeHits(harvester));
        acceptCodeHit(harvester, "CommonModules/LateCall/Module.bsl"); //$NON-NLS-1$
        assertEquals("the code phase still accepts a hit", 1, codeHits(harvester)); //$NON-NLS-1$
    }

    /** The code phase stops at its own budget, not at someone else's. */
    @Test
    public void theCodePhaseStopsAtItsOwnBudget() throws Exception
    {
        Object harvester = harvester(LIMIT, null, false, false);
        addCode(harvester, LIMIT * 10);
        acceptCodeHit(harvester, "CommonModules/OnePastTheCap/Module.bsl"); //$NON-NLS-1$
        assertEquals(LIMIT * 10, codeHits(harvester));
    }

    /** Sisters stay open while any enabled phase still has room, and close once every one is full. */
    @Test
    public void aSisterStaysOpenWhileAnyPhaseStillHasRoom() throws Exception
    {
        Object open = harvester(2, null, false, false);
        addMetadata(open, "back", 20); //$NON-NLS-1$
        assertFalse("metadata filling the old shared cap must not close the code phase", //$NON-NLS-1$
            everyPhaseFull(open));

        Object both = harvester(2, "back,bsl", false, false); //$NON-NLS-1$
        addMetadata(both, "back", 20); //$NON-NLS-1$
        assertFalse(everyPhaseFull(both));
        addCode(both, 20);
        assertTrue(everyPhaseFull(both));
    }

    /** A walk that leaves sister projects closed says so, and names them. */
    @Test
    public void sistersLeftClosedAreNamedAndTheAnswerSaysTruncated() throws Exception
    {
        List<String> named = sistersNotOpened(List.of(
            projectNamed("Owner"), projectNamed("SisterExtension"), projectNamed("SisterExternal")), 1); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of("SisterExtension", "SisterExternal"), named); //$NON-NLS-1$ //$NON-NLS-2$

        Object harvester = harvester(LIMIT, null, false, false);
        addMetadata(harvester, "back", 1); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>)field(harvester, "projectsSkippedByCap"); //$NON-NLS-1$
        skipped.add("SisterExtension"); //$NON-NLS-1$
        skipped.add("SisterExternal"); //$NON-NLS-1$

        String report = format(harvester);
        assertTrue(report.contains("truncated")); //$NON-NLS-1$
        assertTrue(report.contains("SisterExtension")); //$NON-NLS-1$
        assertTrue(report.contains("SisterExternal")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, field(outcome(harvester), "capped")); //$NON-NLS-1$
    }

    private static Object harvester(int limit, String categories, boolean skipBsl, boolean bslOnly)
        throws Exception
    {
        Class<?> filterType = Class.forName(
            "ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$CategoryFilter"); //$NON-NLS-1$
        Method from = filterType.getDeclaredMethod("from", String.class, boolean.class, boolean.class); //$NON-NLS-1$
        from.setAccessible(true);
        Object filter = from.invoke(null, categories, Boolean.valueOf(skipBsl), Boolean.valueOf(bslOnly));

        Class<?> type = Class.forName(
            "ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$BmReferenceHarvester"); //$NON-NLS-1$
        Constructor<?> constructor = type.getDeclaredConstructor(IBmModel.class, MdObject.class, int.class,
            boolean.class, filterType, WatchForCancel.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, metadataObject(), Integer.valueOf(limit), Boolean.FALSE, filter,
            WatchForCancel.begin());
    }

    private static MdObject metadataObject()
    {
        ClassLoader loader = MdObject.class.getClassLoader();
        if (loader == null)
        {
            loader = EachCategoryKeepsItsOwnBudgetTest.class.getClassLoader();
        }
        return (MdObject)Proxy.newProxyInstance(loader, new Class<?>[] {MdObject.class},
            (proxy, method, args) -> "getName".equals(method.getName()) ? "Products" : plain(method)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void addMetadata(Object harvester, String phase, int count) throws Exception
    {
        Class<?> hitType = Class.forName("ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$UsageHit"); //$NON-NLS-1$
        Method factory = hitType.getDeclaredMethod("metadata", String.class, String.class, String.class); //$NON-NLS-1$
        factory.setAccessible(true);
        Method add = harvester.getClass().getDeclaredMethod("addReference", String.class, hitType); //$NON-NLS-1$
        add.setAccessible(true);
        for (int index = 0; index < count; index++)
        {
            Object hit = factory.invoke(null, "Catalogs", //$NON-NLS-1$
                String.format("Catalogs/Meta%03d", Integer.valueOf(index)), "ref"); //$NON-NLS-1$ //$NON-NLS-2$
            invoke(add, harvester, phase, hit);
        }
    }

    private static void addCode(Object harvester, int count) throws Exception
    {
        Class<?> hitType = Class.forName("ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$UsageHit"); //$NON-NLS-1$
        Method factory = hitType.getDeclaredMethod("bsl", String.class, String.class, int.class); //$NON-NLS-1$
        factory.setAccessible(true);
        Method add = harvester.getClass().getDeclaredMethod("addReference", String.class, hitType); //$NON-NLS-1$
        add.setAccessible(true);
        for (int index = 0; index < count; index++)
        {
            Object hit = factory.invoke(null, "BSL modules", //$NON-NLS-1$
                String.format("CommonModules/Mod%03d/Module.bsl", Integer.valueOf(index)), //$NON-NLS-1$
                Integer.valueOf(index + 1));
            invoke(add, harvester, "bsl", hit); //$NON-NLS-1$
        }
    }

    private static void acceptCodeHit(Object harvester, String modulePath) throws Exception
    {
        URI uri = URI.createURI("platform:/resource/Probe/src/" + modulePath); //$NON-NLS-1$
        ClassLoader loader = IReferenceDescription.class.getClassLoader();
        Object description = Proxy.newProxyInstance(loader, new Class<?>[] {IReferenceDescription.class},
            (proxy, method, args) -> "getSourceEObjectUri".equals(method.getName()) ? uri : plain(method)); //$NON-NLS-1$
        Method accept = harvester.getClass().getDeclaredMethod("onBslRefHit", IReferenceDescription.class); //$NON-NLS-1$
        accept.setAccessible(true);
        invoke(accept, harvester, description);
    }

    private static String format(Object harvester) throws Exception
    {
        Class<?> filterType = Class.forName(
            "ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$CategoryFilter"); //$NON-NLS-1$
        Method format = ReferenceLocator.class.getDeclaredMethod("formatOutput", String.class, //$NON-NLS-1$
            harvester.getClass(), filterType, ProjectScopeResolver.ScopeResult.class, List.class,
            WatchForCancel.class);
        format.setAccessible(true);
        Field filter = harvester.getClass().getDeclaredField("filter"); //$NON-NLS-1$
        filter.setAccessible(true);
        return (String)invoke(format, null, "Catalog.Products", harvester, filter.get(harvester), null, //$NON-NLS-1$
            List.of(), WatchForCancel.begin());
    }

    private static Object outcome(Object harvester) throws Exception
    {
        Class<?> sinkType = Class.forName("ru.aiedt.mcp.server.toolkit.ops.ReferenceLocator$Sink"); //$NON-NLS-1$
        Constructor<?> constructor = sinkType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object sink = constructor.newInstance();
        Method record = ReferenceLocator.class.getDeclaredMethod("recordOutcome", sinkType, //$NON-NLS-1$
            harvester.getClass(), WatchForCancel.class);
        record.setAccessible(true);
        invoke(record, null, sink, harvester, WatchForCancel.begin());
        return sink;
    }

    private static boolean everyPhaseFull(Object harvester) throws Exception
    {
        Method method = harvester.getClass().getDeclaredMethod("everyEnabledPhaseIsFull"); //$NON-NLS-1$
        method.setAccessible(true);
        return ((Boolean)invoke(method, harvester)).booleanValue();
    }

    private static List<String> sistersNotOpened(List<IProject> scope, int from) throws Exception
    {
        List<String> skipped = new ArrayList<>();
        Method method = ReferenceLocator.class.getDeclaredMethod("nameSistersNotOpened", List.class, //$NON-NLS-1$
            int.class, List.class);
        method.setAccessible(true);
        invoke(method, null, scope, Integer.valueOf(from), skipped);
        return skipped;
    }

    private static IProject projectNamed(String name)
    {
        ClassLoader loader = IProject.class.getClassLoader();
        return (IProject)Proxy.newProxyInstance(loader, new Class<?>[] {IProject.class},
            (proxy, method, args) -> "getName".equals(method.getName()) ? name : plain(method)); //$NON-NLS-1$
    }

    private static int codeHits(Object harvester) throws Exception
    {
        @SuppressWarnings("unchecked")
        List<Object> references = (List<Object>)field(harvester, "references"); //$NON-NLS-1$
        int code = 0;
        for (Object hit : references)
        {
            if (((Boolean)field(hit, "isBslReference")).booleanValue()) //$NON-NLS-1$
            {
                code++;
            }
        }
        return code;
    }

    private static int rowsIn(String section)
    {
        int rows = 0;
        for (String line : section.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith("- ")) //$NON-NLS-1$
            {
                rows++;
            }
        }
        return rows;
    }

    private static Object field(Object target, String name) throws Exception
    {
        Field declared = target.getClass().getDeclaredField(name);
        declared.setAccessible(true);
        return declared.get(target);
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception
    {
        try
        {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            throw e;
        }
    }

    private static Object plain(Method method)
    {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive())
        {
            return null;
        }
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == void.class)
        {
            return null;
        }
        if (type == long.class)
        {
            return Long.valueOf(0);
        }
        return Integer.valueOf(0);
    }
}
