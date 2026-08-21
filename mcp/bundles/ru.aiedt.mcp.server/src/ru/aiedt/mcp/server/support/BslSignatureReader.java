/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess;

/**
 * Reads a method's signature out of the BSL model.
 * <p>
 * <b>Why not out of the text.</b> Whether a method still exists can be answered with a search
 * through the source; what shape it has cannot. A parameter list read by pattern gets the easy
 * cases and quietly mangles continuation lines, comments between parameters and defaults that are
 * expressions - and an extension's fitness is decided precisely by the cases a pattern gets wrong.
 * </p>
 */
public final class BslSignatureReader
{
    private BslSignatureReader()
    {
        // Static reader.
    }

    /**
     * Reads one method's signature.
     *
     * @param project the project holding the module.
     * @param modulePath the module, as a path under the project.
     * @param methodName the method.
     * @return the signature, or <code>null</code> when the module or the method is not there
     */
    public static MethodSignature read(IProject project, String modulePath, String methodName)
    {
        if (project == null || modulePath == null || methodName == null || methodName.isEmpty())
        {
            return null;
        }
        try
        {
            Module module = BslModuleAccess.loadModule(project, modulePath);
            if (module == null)
            {
                return null;
            }
            Method method = BslModuleAccess.findMethod(module, methodName);
            if (method == null)
            {
                return null;
            }
            return signatureOf(method);
        }
        catch (RuntimeException | LinkageError cannotRead)
        {
            Activator.logDebug("signature could not be read for " + methodName + " in " //$NON-NLS-1$ //$NON-NLS-2$
                + modulePath + ": " + cannotRead); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Turns a model method into the signature an extension is coupled to.
     *
     * @param method the method.
     * @return its signature
     */
    public static MethodSignature signatureOf(Method method)
    {
        MethodSignature signature = new MethodSignature(method.getName(), method.isExport());
        for (FormalParam param : method.getFormalParams())
        {
            if (param == null)
            {
                continue;
            }
            signature.params.add(new MethodSignature.Param(String.valueOf(param.getName()),
                param.isByValue(), param.getDefaultValue() != null));
        }
        return signature;
    }
}
