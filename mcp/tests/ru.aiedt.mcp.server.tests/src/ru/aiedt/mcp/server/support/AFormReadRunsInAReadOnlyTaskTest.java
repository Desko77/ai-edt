/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;

/**
 * A form read runs in a read-only task of the model and asks the model for nothing else: no write
 * task, no export, so reading a form leaves its file as it was.
 * <p>
 * The model and its transaction are stand-ins that record what the read called; any call other than
 * the read-only task and the lookups a read makes fails the test.
 * </p>
 */
public class AFormReadRunsInAReadOnlyTaskTest
{
    private final List<String> modelCalls = new ArrayList<>();

    private final Map<String, Object> topObjects = new HashMap<>();

    private BmFormHelper helper;

    /** A helper with the form model classes loaded, and an empty model. */
    @Before
    public void aHelperAndAnEmptyModel()
    {
        helper = new BmFormHelper();
        assertTrue("the form model classes load in the test runtime", helper.init()); //$NON-NLS-1$
        modelCalls.clear();
        topObjects.clear();
    }

    /**
     * @return a model that runs a read-only task against a transaction answering from
     *         {@link #topObjects}, and refuses every other call
     */
    private IBmModel recordingModel()
    {
        IBmTransaction transaction = (IBmTransaction)Proxy.newProxyInstance(
            IBmTransaction.class.getClassLoader(), new Class<?>[] {IBmTransaction.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getTopObjectByFqn": //$NON-NLS-1$
                        return topObjects.get(args[0]);
                    case "getTopObjectIterator": //$NON-NLS-1$
                        return Collections.emptyIterator();
                    default:
                        throw new UnsupportedOperationException("a read called " + method.getName()); //$NON-NLS-1$
                }
            });
        return (IBmModel)Proxy.newProxyInstance(IBmModel.class.getClassLoader(),
            new Class<?>[] {IBmModel.class}, (proxy, method, args) -> {
                modelCalls.add(method.getName());
                if ("executeReadonlyTask".equals(method.getName())) //$NON-NLS-1$
                {
                    return ((IBmTask<?>)args[0]).execute(transaction, new NullProgressMonitor());
                }
                throw new UnsupportedOperationException("a read called " + method.getName()); //$NON-NLS-1$
            });
    }

    /** The action gets the form under the suffixed address, inside the one read-only task. */
    @Test
    public void theReadRunsInOneReadOnlyTask()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        topObjects.put("Catalog.Products.Form.ItemForm.Form", form); //$NON-NLS-1$
        Object[] seen = new Object[1];

        String answer = helper.readForm(recordingModel(), "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
            (transaction, resolved) -> {
                seen[0] = resolved;
                return "walked"; //$NON-NLS-1$
            });

        assertEquals("walked", answer); //$NON-NLS-1$
        assertSame(form, seen[0]);
        assertEquals(List.of("executeReadonlyTask"), modelCalls); //$NON-NLS-1$
    }

    /** A form that is not there is refused, and the action never runs. */
    @Test
    public void aMissingFormIsRefusedWithoutTheAction()
    {
        boolean[] ran = new boolean[1];

        String answer = helper.readForm(recordingModel(), "Catalog.Products.Form.Gone", //$NON-NLS-1$
            (transaction, resolved) -> {
                ran[0] = true;
                return null;
            });

        assertTrue(answer, answer.startsWith("Error: Form not found by FQN: Catalog.Products.Form.Gone")); //$NON-NLS-1$
        assertFalse(ran[0]);
        assertEquals(List.of("executeReadonlyTask"), modelCalls); //$NON-NLS-1$
    }

    /** An action that throws is answered as an error naming the cause. */
    @Test
    public void aFailingActionIsAnswered()
    {
        topObjects.put("Catalog.Products.Form.ItemForm.Form", FormFactory.eINSTANCE.createForm()); //$NON-NLS-1$

        String answer = helper.readForm(recordingModel(), "Catalog.Products.Form.ItemForm.Form", //$NON-NLS-1$
            (transaction, resolved) -> {
                throw new IllegalArgumentException("walk broke"); //$NON-NLS-1$
            });

        assertEquals("Error: BM API error: walk broke", answer); //$NON-NLS-1$
    }
}
