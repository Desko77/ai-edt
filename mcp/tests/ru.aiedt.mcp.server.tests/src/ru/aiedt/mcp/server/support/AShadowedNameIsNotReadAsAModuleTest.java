/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.DeclareStatement;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.ExplicitVariable;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.Procedure;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;

/**
 * A name a method declares as its parameter or its variable hides the common module of that name,
 * in the literal readers of the written-by-name calls the same way as in the reader of the plain
 * call sites.
 */
public class AShadowedNameIsNotReadAsAModuleTest
{
    private static final Map<String, String> MODULES = Map.of("длительныеподготовки", //$NON-NLS-1$
        "ДлительныеПодготовки"); //$NON-NLS-1$

    /** A notify description whose source the method declares as a parameter names no module. */
    @Test
    public void aNotifyDescriptionThroughAParameterIsNoCall()
    {
        Module module = moduleWith(notifyDescription("ПослеЗагрузки", "ДлительныеПодготовки")); //$NON-NLS-1$ //$NON-NLS-2$
        paramOf(firstMethodOf(module), "ДлительныеПодготовки"); //$NON-NLS-1$

        assertTrue(BslCallGraphHelper.literalNameCallsOf(module, MODULES).isEmpty());
    }

    /** A background entry point whose owner the method declares as a variable names no module. */
    @Test
    public void aBackgroundCallThroughAVariableIsNoCall()
    {
        Module module = moduleWith(invocation("ДлительныеОперации", "ВыполнитьВФоне", //$NON-NLS-1$ //$NON-NLS-2$
            literal("\"ДлительныеПодготовки.РассчитатьВсе\""))); //$NON-NLS-1$
        variableOf(firstMethodOf(module), "ДлительныеОперации"); //$NON-NLS-1$

        assertTrue(BslCallGraphHelper.literalNameCallsOf(module, MODULES).isEmpty());
    }

    /** A name declared in a sibling method hides nothing in the method that calls. */
    @Test
    public void aNameDeclaredInASiblingMethodHidesNothing()
    {
        Module module = moduleWith(invocation("ДлительныеОперации", "ВыполнитьВФоне", //$NON-NLS-1$ //$NON-NLS-2$
            literal("\"ДлительныеПодготовки.РассчитатьВсе\""))); //$NON-NLS-1$
        Method sibling = BslFactory.eINSTANCE.createProcedure();
        module.getMethods().add(sibling);
        variableOf(sibling, "ДлительныеОперации"); //$NON-NLS-1$

        assertEquals(1, BslCallGraphHelper.literalNameCallsOf(module, MODULES).size());
    }

    /** The first method of a module built by {@link #moduleWith}. */
    private static Method firstMethodOf(Module module)
    {
        return module.getMethods().get(0);
    }

    /** A module holding one statement. */
    private static Module moduleWith(com._1c.g5.v8.dt.bsl.model.Expression expression)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Module module = factory.createModule();
        Procedure method = factory.createProcedure();
        module.getMethods().add(method);
        SimpleStatement statement = factory.createSimpleStatement();
        statement.setLeft(expression);
        method.getStatements().add(statement);
        return module;
    }

    /**
     * Declares a parameter of the given name on a method.
     *
     * @param method the method
     * @param name the parameter's name
     */
    private static void paramOf(Method method, String name)
    {
        FormalParam param = BslFactory.eINSTANCE.createFormalParam();
        param.setName(name);
        method.getFormalParams().add(param);
    }

    /**
     * Declares a variable of the given name inside a method.
     *
     * @param method the method
     * @param name the variable's name
     */
    private static void variableOf(Method method, String name)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        ExplicitVariable variable = factory.createExplicitVariable();
        variable.setName(name);
        DeclareStatement declare = factory.createDeclareStatement();
        declare.getVariables().add(variable);
        method.getDeclareStatements().add(declare);
    }

    /**
     * A notify description constructor naming a method of the named source.
     *
     * @param methodName the method name the first parameter spells
     * @param sourceName the name the second parameter spells
     * @return the constructor expression
     */
    private static OperatorStyleCreator notifyDescription(String methodName, String sourceName)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        OperatorStyleCreator creator = factory.createOperatorStyleCreator();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName("ОписаниеОповещения"); //$NON-NLS-1$
        creator.setType(type);
        creator.getParams().add(literal("\"" + methodName + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        StaticFeatureAccess source = factory.createStaticFeatureAccess();
        source.setName(sourceName);
        creator.getParams().add(source);
        return creator;
    }

    /**
     * An invocation: over a module name and a method name.
     *
     * @param ownerName the name the invocation starts with
     * @param methodName the invoked method's name
     * @param firstParam the first parameter expression
     * @return the invocation
     */
    private static Invocation invocation(String ownerName, String methodName,
        com._1c.g5.v8.dt.bsl.model.Expression firstParam)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Invocation call = factory.createInvocation();
        DynamicFeatureAccess access = factory.createDynamicFeatureAccess();
        StaticFeatureAccess owner = factory.createStaticFeatureAccess();
        owner.setName(ownerName);
        access.setSource(owner);
        access.setName(methodName);
        call.setMethodAccess(access);
        call.getParams().add(firstParam);
        return call;
    }

    /** A string literal whose stored line carries its quotes, the way the parser leaves it. */
    private static StringLiteral literal(String quotedText)
    {
        StringLiteral literal = BslFactory.eINSTANCE.createStringLiteral();
        literal.getLines().add(quotedText);
        return literal;
    }
}
