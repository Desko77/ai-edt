/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Map;

import org.junit.Test;

/**
 * The name a call site starts with is matched against the project's common modules.
 */
public class ACallSiteNamesTheCommonModuleItCallsTest
{
    private static final Map<String, String> MODULES = Map.of("общегоназначения", //$NON-NLS-1$
        "ОбщегоНазначения"); //$NON-NLS-1$

    /** The name is matched without regard to case and answers the module's own spelling. */
    @Test
    public void theNameIsMatchedWithoutRegardToCase()
    {
        assertEquals("CommonModule.ОбщегоНазначения.Module", //$NON-NLS-1$
            BslCallGraphHelper.commonModuleFqn("ОБЩЕГОНАЗНАЧЕНИЯ", MODULES)); //$NON-NLS-1$
    }

    /** A name that is no common module is not an edge. */
    @Test
    public void aNameThatIsNoCommonModuleIsNotAnEdge()
    {
        assertNull(BslCallGraphHelper.commonModuleFqn("Запрос", MODULES)); //$NON-NLS-1$
        assertNull(BslCallGraphHelper.commonModuleFqn(null, MODULES));
    }

    /** A call through a name the module declares as a parameter is not an edge; the same call is one without it. */
    @Test
    public void aDeclaredNameIsNotReadAsACommonModule()
    {
        com._1c.g5.v8.dt.bsl.model.BslFactory factory = com._1c.g5.v8.dt.bsl.model.BslFactory.eINSTANCE;
        com._1c.g5.v8.dt.bsl.model.Module module = factory.createModule();
        com._1c.g5.v8.dt.bsl.model.Procedure method = factory.createProcedure();
        module.getMethods().add(method);
        com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess source = factory.createStaticFeatureAccess();
        source.setName("ОбщегоНазначения"); //$NON-NLS-1$
        com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess access = factory.createDynamicFeatureAccess();
        access.setSource(source);
        access.setName("Метод"); //$NON-NLS-1$
        com._1c.g5.v8.dt.bsl.model.Invocation call = factory.createInvocation();
        call.setMethodAccess(access);
        com._1c.g5.v8.dt.bsl.model.SimpleStatement statement = factory.createSimpleStatement();
        statement.setLeft(call);
        method.getStatements().add(statement);

        assertEquals(java.util.List.of("CommonModule.ОбщегоНазначения.Module"), //$NON-NLS-1$
            BslCallGraphHelper.calleesByCommonModuleName(module, MODULES));

        com._1c.g5.v8.dt.bsl.model.FormalParam param = factory.createFormalParam();
        param.setName("ОбщегоНазначения"); //$NON-NLS-1$
        method.getFormalParams().add(param);

        assertEquals(java.util.List.of(), BslCallGraphHelper.calleesByCommonModuleName(module, MODULES));

        // The same name declared in a sibling method hides nothing here.
        method.getFormalParams().clear();
        com._1c.g5.v8.dt.bsl.model.Procedure sibling = factory.createProcedure();
        module.getMethods().add(sibling);
        sibling.getFormalParams().add(param);

        assertEquals(java.util.List.of("CommonModule.ОбщегоНазначения.Module"), //$NON-NLS-1$
            BslCallGraphHelper.calleesByCommonModuleName(module, MODULES));
    }

    /** Common modules that could not be listed answer null, not an empty list. */
    @Test
    public void unknownCommonModulesAnswerNull()
    {
        assertNull(BslCallGraphHelper.calleesByCommonModuleName(
            com._1c.g5.v8.dt.bsl.model.BslFactory.eINSTANCE.createModule(), null));
    }
}
