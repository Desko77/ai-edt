/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
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
 * The calls a module's text writes by name are read off its model, and only when the name sits in
 * the code as a string literal.
 * <p>
 * The models are built in memory with the model factory, the way the parser would leave them: a
 * string literal's stored lines carry their quotes, and the walker strips them itself. The line of
 * a call is read off the node model, which a built-in-memory model does not carry, so the lines
 * here are all zero - the address of a site is proven by the walk, not by these tests.
 * </p>
 */
public class ANameCallIsReadFromTheLiteralsOfAModuleTest
{
    private static final Map<String, String> MODULES = Map.of("длительныеподготовки", //$NON-NLS-1$
        "ДлительныеПодготовки"); //$NON-NLS-1$

    /** A notify description naming a method of its own module is a call into that module. */
    @Test
    public void aNotifyDescriptionNamingThisObjectCallsItsOwnModule()
    {
        Module module = moduleWithStatement(notifyDescription("\"ПослеЗагрузки\"", "ЭтотОбъект")); //$NON-NLS-1$ //$NON-NLS-2$

        List<BslCallGraphHelper.NameCall> calls = BslCallGraphHelper.literalNameCallsOf(module, MODULES);

        assertEquals(List.of("ПослеЗагрузки"), names(calls)); //$NON-NLS-1$
        assertEquals(null, calls.get(0).targetModuleFqn);
    }

    /** A notify description whose source is a common module calls that module. */
    @Test
    public void aNotifyDescriptionNamingAModuleCallsIt()
    {
        Module module = moduleWithStatement(notifyDescription("\"ПослеЗагрузки\"",
            "ДлительныеПодготовки")); //$NON-NLS-1$ //$NON-NLS-2$

        List<BslCallGraphHelper.NameCall> calls = BslCallGraphHelper.literalNameCallsOf(module, MODULES);

        assertEquals(1, calls.size());
        assertEquals("CommonModule.ДлительныеПодготовки.Module", calls.get(0).targetModuleFqn); //$NON-NLS-1$
        assertEquals("ПослеЗагрузки", calls.get(0).methodName); //$NON-NLS-1$
    }

    /** An English notify description over an English ThisObject is read the same way. */
    @Test
    public void theEnglishSpellingsAreReadAsTheirOwn()
    {
        Module module = moduleWithStatement(notifyDescription("\"AfterLoad\"", "ThisObject")); //$NON-NLS-1$ //$NON-NLS-2$
        Type type = (Type)((OperatorStyleCreator)lastStatement(module).getLeft()).getType();
        type.setName("NotifyDescription"); //$NON-NLS-1$

        List<BslCallGraphHelper.NameCall> calls = BslCallGraphHelper.literalNameCallsOf(module, MODULES);

        assertEquals(List.of("AfterLoad"), names(calls)); //$NON-NLS-1$
    }

    /** A source of another kind leaves the notify description without a target. */
    @Test
    public void aSourceOfAnotherKindIsNoCall()
    {
        DynamicFeatureAccess throughVariable = BslFactory.eINSTANCE.createDynamicFeatureAccess();
        throughVariable.setSource(feature("Получатель")); //$NON-NLS-1$
        throughVariable.setName("Источник"); //$NON-NLS-1$
        Module module = moduleWithStatement(notifyDescription2(literal("\"ПослеЗагрузки\""), throughVariable)); //$NON-NLS-1$

        assertTrue(BslCallGraphHelper.literalNameCallsOf(module, MODULES).isEmpty());
    }

    /** A notify description without its second parameter names no module. */
    @Test
    public void aNotifyDescriptionWithoutItsSourceIsNoCall()
    {
        OperatorStyleCreator creator = notifyDescription("\"ПослеЗагрузки\"", null); //$NON-NLS-1$
        Module module = moduleWithStatement(creator);

        assertTrue(BslCallGraphHelper.literalNameCallsOf(module, MODULES).isEmpty());
    }

    /** The idle handler attachment names a method of its own module. */
    @Test
    public void anIdleHandlerNamesAMethodOfItsOwnModule()
    {
        Module module = moduleWithStatement(
            invocation(null, "ПодключитьОбработчикОжидания", literal("\"ПроверитьОжидание\""))); //$NON-NLS-1$ //$NON-NLS-2$

        List<BslCallGraphHelper.NameCall> calls = BslCallGraphHelper.literalNameCallsOf(module, MODULES);

        assertEquals(List.of("ПроверитьОжидание"), names(calls)); //$NON-NLS-1$
        assertEquals(null, calls.get(0).targetModuleFqn);
    }

    /** The background execution entry points name a method of the common module the string spells. */
    @Test
    public void aBackgroundExecutionNamesTheCommonModuleAndItsMethod()
    {
        Module inBackground = moduleWithStatement(invocation("ДлительныеОперации", "ВыполнитьВФоне", //$NON-NLS-1$ //$NON-NLS-2$
            literal("\"ДлительныеПодготовки.РассчитатьВсе\""))); //$NON-NLS-1$
        Module byJobs = moduleWithStatement(invocation("ФоновыеЗадания", "Выполнить", //$NON-NLS-1$ //$NON-NLS-2$
            literal("\"ДлительныеПодготовки.РассчитатьВсе\""))); //$NON-NLS-1$
        Module inEnglish = moduleWithStatement(invocation("BackgroundJobs", "Execute", //$NON-NLS-1$ //$NON-NLS-2$
            literal("\"ДлительныеПодготовки.РассчитатьВсе\""))); //$NON-NLS-1$

        for (Module module : List.of(inBackground, byJobs, inEnglish))
        {
            List<BslCallGraphHelper.NameCall> calls =
                BslCallGraphHelper.literalNameCallsOf(module, MODULES);
            assertEquals(1, calls.size());
            assertEquals("CommonModule.ДлительныеПодготовки.Module", calls.get(0).targetModuleFqn); //$NON-NLS-1$
            assertEquals("РассчитатьВсе", calls.get(0).methodName); //$NON-NLS-1$
        }
    }

    /** A string naming no common module of the project is not a call. */
    @Test
    public void aStringNamingNoCommonModuleIsNoCall()
    {
        Module module = moduleWithStatement(invocation("ДлительныеОперации", "ВыполнитьВФоне", //$NON-NLS-1$ //$NON-NLS-2$
            literal("\"НесуществующийМодуль.РассчитатьВсе\""))); //$NON-NLS-1$

        assertTrue(BslCallGraphHelper.literalNameCallsOf(module, MODULES).isEmpty());
    }

    /** A method name held in a variable or built at runtime is not a literal and is not read. */
    @Test
    public void aNameInAVariableIsNotRead()
    {
        StaticFeatureAccess variable = feature("ИмяОбработчика"); //$NON-NLS-1$
        Module handler = moduleWithStatement(
            invocation(null, "ПодключитьОбработчикОжидания", variable)); //$NON-NLS-1$
        Module description = moduleWithStatement(notifyDescription2(variable, feature("ЭтотОбъект"))); //$NON-NLS-1$
        Module background = moduleWithStatement(invocation("ДлительныеОперации", "ВыполнитьВФоне", //$NON-NLS-1$ //$NON-NLS-2$
            variable));

        for (Module module : List.of(handler, description, background))
        {
            assertTrue(BslCallGraphHelper.literalNameCallsOf(module, MODULES).isEmpty());
        }
    }

    /** A call whose named method does not exist is dropped; one that exists is kept. */
    @Test
    public void aCallWithoutItsTargetMethodIsNotKept()
    {
        Module caller = moduleWithStatement(
            invocation("ДлительныеОперации", "ВыполнитьВФоне", //$NON-NLS-1$ //$NON-NLS-2$
                literal("\"ДлительныеПодготовки.НесуществующийМетод\""))); //$NON-NLS-1$
        Module callee = moduleDeclaring("ДругойМетод"); //$NON-NLS-1$
        Module withTheMethod = moduleDeclaring("НесуществующийМетод"); //$NON-NLS-1$

        List<BslCallGraphHelper.NameCall> read = BslCallGraphHelper.literalNameCallsOf(caller, MODULES);
        BslCallGraphHelper.MethodNameCache none = new BslCallGraphHelper.MethodNameCache(
            fqn -> null);
        BslCallGraphHelper.MethodNameCache wrong = new BslCallGraphHelper.MethodNameCache(
            fqn -> callee);
        BslCallGraphHelper.MethodNameCache right = new BslCallGraphHelper.MethodNameCache(
            fqn -> withTheMethod);

        assertTrue(BslCallGraphHelper.withExistingTargets(read, caller, none).isEmpty());
        assertTrue(BslCallGraphHelper.withExistingTargets(read, caller, wrong).isEmpty());
        assertEquals(1, BslCallGraphHelper.withExistingTargets(read, caller, right).size());
    }

    /** A method is matched without regard to how the call site spells its case. */
    @Test
    public void aMethodNameIsMatchedWithoutRegardToCase()
    {
        Module caller = moduleWithStatement(
            invocation("ДлительныеОперации", "ВыполнитьВФоне", //$NON-NLS-1$ //$NON-NLS-2$
                literal("\"ДлительныеПодготовки.РАССЧИТАТЬВСЕ\""))); //$NON-NLS-1$
        Module callee = moduleDeclaring("РассчитатьВсе"); //$NON-NLS-1$

        List<BslCallGraphHelper.NameCall> read = BslCallGraphHelper.literalNameCallsOf(caller, MODULES);
        assertEquals(1, BslCallGraphHelper.withExistingTargets(read, caller,
            new BslCallGraphHelper.MethodNameCache(fqn -> callee)).size());
    }

    /** Two calls into one module read that module once. */
    @Test
    public void twoCallsIntoOneModuleReadItOnce()
    {
        int[] loads = {0};
        Module target = moduleDeclaring("РассчитатьВсе"); //$NON-NLS-1$
        BslCallGraphHelper.MethodNameCache cache = new BslCallGraphHelper.MethodNameCache(fqn -> {
            loads[0]++;
            return target;
        });
        List<BslCallGraphHelper.NameCall> calls = List.of(
            new BslCallGraphHelper.NameCall("CommonModule.ДлительныеПодготовки.Module", //$NON-NLS-1$
                "РассчитатьВсе", 1), //$NON-NLS-1$
            new BslCallGraphHelper.NameCall("CommonModule.ДлительныеПодготовки.Module", //$NON-NLS-1$
                "НичемНеНазван", 2)); //$NON-NLS-1$
        Module caller = moduleDeclaring("Чтоугодно"); //$NON-NLS-1$

        List<BslCallGraphHelper.NameCall> kept = BslCallGraphHelper.withExistingTargets(calls,
            caller, cache);

        assertEquals(1, kept.size());
        assertEquals("РассчитатьВсе", kept.get(0).methodName); //$NON-NLS-1$
        assertEquals(1, loads[0]);
    }

    /** The method names of the calls, in their order. */
    private static List<String> names(List<BslCallGraphHelper.NameCall> calls)
    {
        return calls.stream().map(call -> call.methodName).collect(Collectors.toList());
    }

    /** A module holding one statement. */
    private static Module moduleWithStatement(com._1c.g5.v8.dt.bsl.model.Expression expression)
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

    /** A module declaring one method of the given name. */
    private static Module moduleDeclaring(String methodName)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Module module = factory.createModule();
        Method method = factory.createProcedure();
        method.setName(methodName);
        module.getMethods().add(method);
        return module;
    }

    /** The expression of the single statement of a module built by {@link #moduleWithStatement}. */
    private static SimpleStatement lastStatement(Module module)
    {
        return (SimpleStatement)module.getMethods().get(0).getStatements().get(0);
    }

    /**
     * A notify description constructor: a method name literal and a source, each of which may be
     * {@code null} to leave the parameter out.
     */
    private static OperatorStyleCreator notifyDescription(String methodNameLiteral, String sourceName)
    {
        return notifyDescription2(literal(methodNameLiteral),
            sourceName == null ? null : feature(sourceName));
    }

    /**
     * A notify description constructor over given parameter expressions, either of which may be
     * {@code null} to leave the parameter out.
     */
    private static OperatorStyleCreator notifyDescription2(
        com._1c.g5.v8.dt.bsl.model.Expression methodName,
        com._1c.g5.v8.dt.bsl.model.Expression source)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        OperatorStyleCreator creator = factory.createOperatorStyleCreator();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName("ОписаниеОповещения"); //$NON-NLS-1$
        creator.setType(type);
        if (methodName != null)
        {
            creator.getParams().add(methodName);
        }
        if (source != null)
        {
            creator.getParams().add(source);
        }
        return creator;
    }

    /**
     * An invocation: over a module name and a method name when both are given, or over a plain
     * global function name when the module name is {@code null}.
     */
    private static Invocation invocation(String ownerName, String methodName,
        com._1c.g5.v8.dt.bsl.model.Expression firstParam)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Invocation call = factory.createInvocation();
        if (ownerName == null)
        {
            call.setMethodAccess(feature(methodName));
        }
        else
        {
            DynamicFeatureAccess access = factory.createDynamicFeatureAccess();
            access.setSource(feature(ownerName));
            access.setName(methodName);
            call.setMethodAccess(access);
        }
        if (firstParam != null)
        {
            call.getParams().add(firstParam);
        }
        return call;
    }

    /** A string literal whose stored line carries its quotes, the way the parser leaves it. */
    private static StringLiteral literal(String quotedText)
    {
        StringLiteral literal = BslFactory.eINSTANCE.createStringLiteral();
        literal.getLines().add(quotedText);
        return literal;
    }

    /** A plain name. */
    private static StaticFeatureAccess feature(String name)
    {
        StaticFeatureAccess feature = BslFactory.eINSTANCE.createStaticFeatureAccess();
        feature.setName(name);
        return feature;
    }
}
