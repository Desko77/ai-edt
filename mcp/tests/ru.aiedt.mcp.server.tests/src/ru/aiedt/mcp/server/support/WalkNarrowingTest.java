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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Every row of the scope-by-selector matrix, for each selector profile the scans use.
 * <p>
 * A row that accepts names an area. A row that refuses names the selector or the scope word, and
 * never answers with an area a scan could walk. The scans themselves are covered against a
 * fixture; what is pinned here is that the decision does not depend on which tool asks.
 * </p>
 */
public class WalkNarrowingTest
{
    private static final List<String> MODULE_SCOPES = List.of("project", "module", "method"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final List<String> METRICS_SCOPES = List.of("project", "subsystem"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final List<String> SENSITIVE_SCOPES = List.of("project", "subsystem", "module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String MODULE = "CommonModule.NarrowInside"; //$NON-NLS-1$

    private static final String METHOD = "NarrowIn"; //$NON-NLS-1$

    private static final String SUBSYSTEM = "NarrowParent"; //$NON-NLS-1$

    @Test
    public void moduleToolsAbsentSelectorsWalkTheProject()
    {
        accepts(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, WalkNarrowing.PROJECT);
    }

    @Test
    public void moduleToolsAbsentModuleFqnWalksThatModule()
    {
        WalkNarrowing.Decision decision = accepts(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD,
            WalkNarrowing.MODULE, "moduleFqn", MODULE); //$NON-NLS-1$
        assertEquals(MODULE, decision.moduleFqn());
    }

    @Test
    public void moduleToolsAbsentModuleAndMethodWalkThatMethod()
    {
        WalkNarrowing.Decision decision = accepts(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD,
            WalkNarrowing.METHOD, "moduleFqn", MODULE, "methodName", METHOD); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(MODULE, decision.moduleFqn());
        assertEquals(METHOD, decision.methodName());
    }

    @Test
    public void moduleToolsAbsentMethodWithoutModuleRefuses()
    {
        refuses(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "methodName '" + METHOD + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "methodName", METHOD); //$NON-NLS-1$
    }

    @Test
    public void moduleToolsScopeProjectWalksTheProject()
    {
        accepts(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, WalkNarrowing.PROJECT,
            "scope", "project"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void moduleToolsScopeProjectWithModuleFqnRefuses()
    {
        refuses(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "moduleFqn '" + MODULE + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "scope", "project", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void moduleToolsScopeProjectWithMethodNameRefuses()
    {
        refuses(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "methodName '" + METHOD + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "scope", "project", "methodName", METHOD); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void moduleToolsScopeProjectWithBothSelectorsRefuses()
    {
        refuses(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "moduleFqn '" + MODULE + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "scope", "project", "moduleFqn", MODULE, "methodName", METHOD); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void moduleToolsScopeModuleWithModuleFqnWalksThatModule()
    {
        accepts(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, WalkNarrowing.MODULE,
            "scope", "module", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void moduleToolsScopeModuleWithoutModuleFqnRefuses()
    {
        refuses(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "scope=module requires moduleFqn", //$NON-NLS-1$
            "scope", "module"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void moduleToolsScopeModuleWithMethodNameRefuses()
    {
        refuses(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "methodName '" + METHOD + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "scope", "module", "moduleFqn", MODULE, "methodName", METHOD); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void moduleToolsScopeModuleMissingModuleAndCarryingMethodRefuses()
    {
        refuses(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "scope=module requires moduleFqn", //$NON-NLS-1$
            "scope", "module", "methodName", METHOD); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void moduleToolsScopeMethodWithBothWalksThatMethod()
    {
        accepts(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, WalkNarrowing.METHOD,
            "scope", "method", "moduleFqn", MODULE, "methodName", METHOD); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void moduleToolsScopeMethodWithoutModuleFqnRefuses()
    {
        refuses(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "scope=method requires moduleFqn", //$NON-NLS-1$
            "scope", "method", "methodName", METHOD); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void moduleToolsScopeMethodWithoutMethodNameRefuses()
    {
        refuses(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "scope=method requires methodName", //$NON-NLS-1$
            "scope", "method", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void moduleToolsScopeMethodWithoutEitherRefuses()
    {
        refuses(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "scope=method requires moduleFqn", //$NON-NLS-1$
            "scope", "method"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void moduleToolsScopeSubsystemIsUnsupported()
    {
        unsupported(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "subsystem"); //$NON-NLS-1$
    }

    @Test
    public void moduleToolsUnknownScopeListsSupportedValues()
    {
        unsupported(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, "widget"); //$NON-NLS-1$
    }

    @Test
    public void moduleToolsBlankScopeIsAbsent()
    {
        accepts(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, WalkNarrowing.PROJECT,
            "scope", "   "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void moduleToolsBlankMethodNameIsAbsent()
    {
        accepts(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, WalkNarrowing.MODULE,
            "moduleFqn", MODULE, "methodName", "  "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void moduleToolsScopeMethodIgnoresCase()
    {
        accepts(MODULE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_METHOD, WalkNarrowing.METHOD,
            "scope", "METHOD", "moduleFqn", MODULE, "methodName", METHOD); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void metricsAbsentSelectorsWalkTheProject()
    {
        accepts(METRICS_SCOPES, WalkNarrowing.Selectors.SUBSYSTEM_ONLY, WalkNarrowing.PROJECT);
    }

    @Test
    public void metricsAbsentSubsystemNameWalksThatSubsystem()
    {
        WalkNarrowing.Decision decision = accepts(METRICS_SCOPES, WalkNarrowing.Selectors.SUBSYSTEM_ONLY,
            WalkNarrowing.SUBSYSTEM, "subsystemName", SUBSYSTEM); //$NON-NLS-1$
        assertEquals(SUBSYSTEM, decision.subsystemName());
    }

    @Test
    public void metricsScopeProjectWalksTheProject()
    {
        accepts(METRICS_SCOPES, WalkNarrowing.Selectors.SUBSYSTEM_ONLY, WalkNarrowing.PROJECT,
            "scope", "project"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void metricsScopeProjectWithSubsystemNameRefuses()
    {
        refuses(METRICS_SCOPES, WalkNarrowing.Selectors.SUBSYSTEM_ONLY,
            "subsystemName '" + SUBSYSTEM + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "scope", "project", "subsystemName", SUBSYSTEM); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void metricsScopeSubsystemWithNameWalksThatSubsystem()
    {
        accepts(METRICS_SCOPES, WalkNarrowing.Selectors.SUBSYSTEM_ONLY, WalkNarrowing.SUBSYSTEM,
            "scope", "subsystem", "subsystemName", SUBSYSTEM); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void metricsScopeSubsystemWithoutNameRefuses()
    {
        refuses(METRICS_SCOPES, WalkNarrowing.Selectors.SUBSYSTEM_ONLY,
            "scope=subsystem requires subsystemName", //$NON-NLS-1$
            "scope", "subsystem"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void metricsScopeModuleIsUnsupported()
    {
        unsupported(METRICS_SCOPES, WalkNarrowing.Selectors.SUBSYSTEM_ONLY, "module"); //$NON-NLS-1$
    }

    @Test
    public void metricsScopeMethodIsUnsupported()
    {
        unsupported(METRICS_SCOPES, WalkNarrowing.Selectors.SUBSYSTEM_ONLY, "method"); //$NON-NLS-1$
    }

    @Test
    public void metricsUnknownScopeListsSupportedValues()
    {
        unsupported(METRICS_SCOPES, WalkNarrowing.Selectors.SUBSYSTEM_ONLY, "widget"); //$NON-NLS-1$
    }

    @Test
    public void metricsIgnoresMethodNameBecauseItIsNotASelector()
    {
        accepts(METRICS_SCOPES, WalkNarrowing.Selectors.SUBSYSTEM_ONLY, WalkNarrowing.PROJECT,
            "methodName", METHOD); //$NON-NLS-1$
    }

    @Test
    public void sensitiveAbsentSelectorsWalkTheProject()
    {
        accepts(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, WalkNarrowing.PROJECT);
    }

    @Test
    public void sensitiveAbsentModuleFqnWalksThatModule()
    {
        accepts(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, WalkNarrowing.MODULE,
            "moduleFqn", MODULE); //$NON-NLS-1$
    }

    @Test
    public void sensitiveAbsentSubsystemNameWalksThatSubsystem()
    {
        accepts(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, WalkNarrowing.SUBSYSTEM,
            "subsystemName", SUBSYSTEM); //$NON-NLS-1$
    }

    @Test
    public void sensitiveAbsentModuleAndSubsystemConflict()
    {
        refuses(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, "conflicts", //$NON-NLS-1$
            "moduleFqn", MODULE, "subsystemName", SUBSYSTEM); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void sensitiveScopeProjectWalksTheProject()
    {
        accepts(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, WalkNarrowing.PROJECT,
            "scope", "project"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void sensitiveScopeProjectWithModuleFqnRefuses()
    {
        refuses(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, "moduleFqn '" + MODULE + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "scope", "project", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void sensitiveScopeProjectWithSubsystemNameRefuses()
    {
        refuses(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM,
            "subsystemName '" + SUBSYSTEM + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "scope", "project", "subsystemName", SUBSYSTEM); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void sensitiveScopeModuleWithModuleFqnWalksThatModule()
    {
        accepts(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, WalkNarrowing.MODULE,
            "scope", "module", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void sensitiveScopeModuleWithoutModuleFqnRefuses()
    {
        refuses(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM,
            "scope=module requires moduleFqn", //$NON-NLS-1$
            "scope", "module"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void sensitiveScopeModuleWithSubsystemNameRefuses()
    {
        refuses(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM,
            "subsystemName '" + SUBSYSTEM + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "scope", "module", "subsystemName", SUBSYSTEM); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void sensitiveScopeModuleWithBothConflicts()
    {
        refuses(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, MODULE,
            "scope", "module", "moduleFqn", MODULE, "subsystemName", SUBSYSTEM); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void sensitiveScopeSubsystemWithNameWalksThatSubsystem()
    {
        accepts(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, WalkNarrowing.SUBSYSTEM,
            "scope", "subsystem", "subsystemName", SUBSYSTEM); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void sensitiveScopeSubsystemWithoutNameRefuses()
    {
        refuses(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM,
            "scope=subsystem requires subsystemName", //$NON-NLS-1$
            "scope", "subsystem"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void sensitiveScopeSubsystemWithModuleFqnRefuses()
    {
        refuses(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, "moduleFqn '" + MODULE + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "scope", "subsystem", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void sensitiveScopeMethodIsUnsupported()
    {
        unsupported(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, "method"); //$NON-NLS-1$
    }

    @Test
    public void sensitiveUnknownScopeListsSupportedValues()
    {
        unsupported(SENSITIVE_SCOPES, WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM, "widget"); //$NON-NLS-1$
    }

    @Test
    public void locateMethodKeepsTheNamedMethodAndDropsTheNext()
    {
        String module = "Процедура NarrowIn()\n" //$NON-NLS-1$
            + "\tЗапрос.Текст = \"ВЫБРАТЬ Код ИЗ Catalog.InsideTable\";\n" //$NON-NLS-1$
            + "КонецПроцедуры\n" //$NON-NLS-1$
            + "Процедура NarrowSibling()\n" //$NON-NLS-1$
            + "\tЗапрос.Текст = \"ВЫБРАТЬ Код ИЗ Catalog.SiblingTable\";\n" //$NON-NLS-1$
            + "КонецПроцедуры\n"; //$NON-NLS-1$
        WalkNarrowing.MethodSpan span = WalkNarrowing.locateMethod(module, "NarrowIn"); //$NON-NLS-1$
        assertNotNull(span);
        assertTrue(span.contains(2));
        assertFalse(span.contains(5));
    }

    @Test
    public void locateMethodMatchesIgnoringCase()
    {
        String module = "Процедура NarrowIn()\n\tА = 1;\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertNotNull(WalkNarrowing.locateMethod(module, "narrowin")); //$NON-NLS-1$
    }

    @Test
    public void locateMethodFindsACyrillicName()
    {
        String module = "Процедура Узкий()\n\tА = 1;\nКонецПроцедуры\n" //$NON-NLS-1$
            + "Процедура Сосед()\n\tБ = 2;\nКонецПроцедуры\n"; //$NON-NLS-1$
        WalkNarrowing.MethodSpan span = WalkNarrowing.locateMethod(module, "узкий"); //$NON-NLS-1$
        assertNotNull(span);
        assertTrue(span.contains(2));
        assertFalse(span.contains(5));
    }

    @Test
    public void locateMethodStopsBeforeModuleCodeThatFollowsTheMethod()
    {
        String module = "Процедура NarrowIn()\n" //$NON-NLS-1$
            + "\tА = 1;\n" //$NON-NLS-1$
            + "КонецПроцедуры\n" //$NON-NLS-1$
            + "Запрос.Текст = \"ВЫБРАТЬ Код ИЗ Catalog.AfterMethod\";\n" //$NON-NLS-1$
            + "Процедура NarrowSibling()\n" //$NON-NLS-1$
            + "\tБ = 2;\n" //$NON-NLS-1$
            + "КонецПроцедуры\n"; //$NON-NLS-1$
        WalkNarrowing.MethodSpan span = WalkNarrowing.locateMethod(module, "NarrowIn"); //$NON-NLS-1$
        assertNotNull(span);
        assertTrue(span.contains(2));
        assertTrue("the closing line is still the method", span.contains(3)); //$NON-NLS-1$
        assertFalse("module code between methods is not the method", span.contains(4)); //$NON-NLS-1$
        assertFalse(span.contains(6));
    }

    @Test
    public void locateMethodStopsBeforeModuleCodeAfterTheLastMethod()
    {
        String module = "Procedure LastOne()\n" //$NON-NLS-1$
            + "\tA = 1;\n" //$NON-NLS-1$
            + "EndProcedure\n" //$NON-NLS-1$
            + "Query.Text = \"SELECT Code FROM Catalog.AfterLast\";\n"; //$NON-NLS-1$
        WalkNarrowing.MethodSpan span = WalkNarrowing.locateMethod(module, "LastOne"); //$NON-NLS-1$
        assertNotNull(span);
        assertTrue(span.contains(2));
        assertTrue(span.contains(3));
        assertFalse("module code after the last method is not the method", span.contains(4)); //$NON-NLS-1$
    }

    @Test
    public void locateMethodReturnsNullWhenTheMethodIsMissing()
    {
        assertNull(WalkNarrowing.locateMethod("Процедура NarrowIn()\nКонецПроцедуры\n", "NoSuchMethod")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void methodNotFoundNamesTheMethodAndTheModule()
    {
        String message = WalkNarrowing.methodNotFound("NoSuchMethod", MODULE); //$NON-NLS-1$
        assertTrue(message, message.contains("NoSuchMethod")); //$NON-NLS-1$
        assertTrue(message, message.contains(MODULE));
    }

    private static WalkNarrowing.Decision accepts(List<String> scopes, WalkNarrowing.Selectors selectors,
        String area, String... pairs)
    {
        WalkNarrowing.Decision decision = WalkNarrowing.decide(args(pairs), scopes, selectors);
        assertFalse(String.valueOf(decision.refusal()), decision.refused());
        assertEquals(area, decision.area());
        return decision;
    }

    private static void refuses(List<String> scopes, WalkNarrowing.Selectors selectors, String fragment,
        String... pairs)
    {
        WalkNarrowing.Decision decision = WalkNarrowing.decide(args(pairs), scopes, selectors);
        assertTrue(String.valueOf(decision.refusal()), decision.refused());
        assertTrue(decision.refusal(), decision.refusal().contains(fragment));
        assertNull(decision.area());
    }

    private static void unsupported(List<String> scopes, WalkNarrowing.Selectors selectors, String word)
    {
        WalkNarrowing.Decision decision = WalkNarrowing.decide(args("scope", word), scopes, selectors); //$NON-NLS-1$
        assertTrue(decision.refusal(), decision.refused());
        assertTrue(decision.refusal(), decision.refusal().contains(word));
        for (String supported : scopes)
        {
            assertTrue(decision.refusal(), decision.refusal().contains(supported));
        }
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            params.put(pairs[i], pairs[i + 1]);
        }
        return params;
    }
}
