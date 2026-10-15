/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Proxy;
import java.util.List;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Procedure;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;

/**
 * The production call source of the module level reads the written-by-name calls out of the text
 * of the module it is asked about, not only the plain call sites the common-module reader sees.
 * <p>
 * The project is a proxy over the one query the source makes of it - the common modules folder -
 * and the module is built in memory with the model factory, so the walk runs without a workspace.
 * </p>
 */
public class TheProductionCallSourceReadsTheLiteralsTest
{
    /**
     * The source reads a call the module's text writes by name and no reference carries, when the
     * named method exists in the module the call names.
     */
    @Test
    public void theSourceReadsTheLiteralsOfTheModuleItIsAskedAbout()
    {
        Module module = moduleWithIdleHandler("ПроверитьОжидание"); //$NON-NLS-1$
        BslCallGraphHelper.ModuleCallSource source = BslCallGraphHelper.ModuleCallSource
            .bslModel(projectHoldingCommonModule("Подготовка"), null); //$NON-NLS-1$

        List<BslCallGraphHelper.NameCall> calls = source.nameCallsOf(module);

        assertEquals(1, calls.size());
        assertEquals("ПроверитьОжидание", calls.get(0).methodName); //$NON-NLS-1$
        assertNull(calls.get(0).targetModuleFqn);
    }

    /**
     * A module declaring an export and attaching the idle handler that names it.
     *
     * @param methodName the export the module declares and the handler names
     * @return the module
     */
    private static Module moduleWithIdleHandler(String methodName)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Module module = factory.createModule();
        Method method = factory.createProcedure();
        method.setName(methodName);
        method.setExport(true);
        module.getMethods().add(method);
        Invocation call = factory.createInvocation();
        StaticFeatureAccess function = factory.createStaticFeatureAccess();
        function.setName("ПодключитьОбработчикОжидания"); //$NON-NLS-1$
        call.setMethodAccess(function);
        StringLiteral literal = factory.createStringLiteral();
        literal.getLines().add("\"" + methodName + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        call.getParams().add(literal);
        SimpleStatement statement = factory.createSimpleStatement();
        statement.setLeft(call);
        method.getStatements().add(statement);
        return module;
    }

    /**
     * A project whose common-modules folder holds one module of the given name - the one query the
     * source makes of its project.
     *
     * @param moduleName the common module's name
     * @return the project
     */
    private static IProject projectHoldingCommonModule(String moduleName)
    {
        IFolder moduleFolder = answering(IFolder.class, "getName", moduleName); //$NON-NLS-1$
        IResource[] members = {moduleFolder};
        IFolder commonModules = (IFolder)Proxy.newProxyInstance(
            TheProductionCallSourceReadsTheLiteralsTest.class.getClassLoader(),
            new Class<?>[]{IFolder.class}, (p, method, args) -> {
                if ("exists".equals(method.getName())) //$NON-NLS-1$
                {
                    return Boolean.TRUE;
                }
                if ("members".equals(method.getName())) //$NON-NLS-1$
                {
                    return members;
                }
                return defaultOf(method.getReturnType());
            });
        return answering(IProject.class, "getFolder", commonModules); //$NON-NLS-1$
    }

    /**
     * A proxy that answers one method and a default for every other.
     *
     * @param type the interface to implement
     * @param methodName the one method to answer
     * @param answer what it answers
     * @return the proxy
     */
    private static <T> T answering(Class<T> type, String methodName, Object answer)
    {
        return type.cast(Proxy.newProxyInstance(
            TheProductionCallSourceReadsTheLiteralsTest.class.getClassLoader(),
            new Class<?>[]{type}, (p, method, args) -> methodName.equals(method.getName())
                ? answer : defaultOf(method.getReturnType())));
    }

    /**
     * The value a method of an uninteresting kind answers on the proxy.
     *
     * @param type the method's return type
     * @return null, or the zero value of a primitive
     */
    private static Object defaultOf(Class<?> type)
    {
        if (!type.isPrimitive() || type == void.class)
        {
            return null;
        }
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == long.class)
        {
            return Long.valueOf(0);
        }
        return Integer.valueOf(0);
    }
}
