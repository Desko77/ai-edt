/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IExpressionManager;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IWatchExpressionDelegate;
import org.eclipse.debug.core.model.IWatchExpressionListener;
import org.eclipse.debug.core.model.IWatchExpressionResult;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.DebugValueSerializer;

/**
 * Evaluates a BSL expression against a suspended 1C frame through Eclipse's watch-expression pipeline.
 * The evaluation is asynchronous; this tool blocks on it with a fixed timeout and reports the value (or
 * the first error the model returned).
 */
public final class ExpressionEvaluator implements IMcpTool
{
    private static final String NAME = "evaluate_expression"; //$NON-NLS-1$

    private static final String DESC = "Back-compat alias of `launch_debugger` `action=evaluate`; prefer the facade for new prompts. " //$NON-NLS-1$
        + "Runs a BSL expression against a stack frame that is currently suspended. " //$NON-NLS-1$
        + "Supply the frameRef returned by wait_for_break together with the expression text. CAUTION: the " //$NON-NLS-1$
        + "expression executes as real BSL code inside the live 1C application."; //$NON-NLS-1$

    private static final long EVAL_TIMEOUT_MS = 15_000L;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return DESC;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .integerProperty("frameRef", "Frame handle obtained from wait_for_break (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("expression", "The BSL expression text to run (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        long frameRef = JsonUtils.extractLongArgument(params, "frameRef", -1L); //$NON-NLS-1$
        String expression = JsonUtils.extractStringArgument(params, "expression"); //$NON-NLS-1$

        if (frameRef <= 0)
        {
            return ToolResult.error("frameRef must be supplied").toJson(); //$NON-NLS-1$
        }
        if (expression == null || expression.isEmpty())
        {
            return ToolResult.error("expression must be supplied").toJson(); //$NON-NLS-1$
        }

        DebugSessionBook registry = DebugSessionBook.get();
        IStackFrame frame = registry.getFrame(frameRef);
        if (frame == null)
        {
            return ToolResult.error("frameRef is no longer valid - call wait_for_break again").toJson(); //$NON-NLS-1$
        }

        try
        {
            String modelId = ((IDebugElement)frame).getModelIdentifier();

            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            if (debugPlugin == null)
            {
                return ToolResult.error("The debug plugin cannot be reached (the EDT debug runtime is shutting down)") //$NON-NLS-1$
                    .toJson();
            }
            IExpressionManager expressionManager = debugPlugin.getExpressionManager();
            if (expressionManager == null)
            {
                return ToolResult.error("The expression manager cannot be reached (the EDT debug runtime is shutting down)") //$NON-NLS-1$
                    .toJson();
            }

            IWatchExpressionDelegate delegate = expressionManager.newWatchExpressionDelegate(modelId);
            if (delegate == null)
            {
                return ToolResult.error("No watch-expression delegate is registered for model: " + modelId //$NON-NLS-1$
                    + ". This 1C debug model may not support evaluating expressions.").toJson(); //$NON-NLS-1$
            }

            final AtomicReference<IWatchExpressionResult> resultRef = new AtomicReference<>();
            final CountDownLatch latch = new CountDownLatch(1);

            delegate.evaluateExpression(expression, frame, new IWatchExpressionListener()
            {
                @Override
                public void watchEvaluationFinished(IWatchExpressionResult result)
                {
                    resultRef.set(result);
                    latch.countDown();
                }
            });

            if (!latch.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            {
                return ToolResult.error("expression evaluation did not finish within " + EVAL_TIMEOUT_MS + "ms").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }

            IWatchExpressionResult result = resultRef.get();
            if (result == null)
            {
                return ToolResult.error("the delegate returned no result").toJson(); //$NON-NLS-1$
            }

            if (result.hasErrors())
            {
                StringBuilder errs = new StringBuilder();
                for (String e : result.getErrorMessages())
                {
                    if (errs.length() > 0)
                    {
                        errs.append("; "); //$NON-NLS-1$
                    }
                    errs.append(e);
                }
                return ToolResult.error(errs.toString()).toJson();
            }

            IValue value = result.getValue();
            String stringValue;
            String type;
            try
            {
                stringValue = value != null ? value.getValueString() : null;
                type = value != null ? value.getReferenceTypeName() : "Undefined"; //$NON-NLS-1$
            }
            catch (DebugException de)
            {
                return ToolResult.error("Could not read the value: " + de.getMessage()).toJson(); //$NON-NLS-1$
            }

            ToolResult res = ToolResult.success().put("type", type); //$NON-NLS-1$
            if (stringValue != null && stringValue.length() > DebugValueSerializer.MAX_VALUE_LENGTH)
            {
                res.put("value", stringValue.substring(0, DebugValueSerializer.MAX_VALUE_LENGTH)); //$NON-NLS-1$
                res.put("truncated", true); //$NON-NLS-1$
                res.put("fullLength", stringValue.length()); //$NON-NLS-1$
            }
            else
            {
                res.put("value", stringValue); //$NON-NLS-1$
            }
            return res.toJson();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("the wait was interrupted").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("evaluate_expression tool raised an exception", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
    }
}
