/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * <b>Defensive layer 3.8.4</b>: auto-creates the inner {@code Form} object
 * for a freshly created {@code CommonForm} container. <p>
 *
 * Problem: creating a CommonForm via {@code createObject CommonForm.X}
 * leaves the metadata-level wrapper but no inner form, so subsequent
 * operations fail with "Form not found: CommonForm.X.Form.Form". The visual
 * EDT wizard does both steps; the headless path only did the first. <p>
 *
 * 1.40 fix: after the wrapper is added to {@code config.getCommonForms()},
 * generate the inner form (with 11 base properties applied via
 * {@link FormBaseSetup}) and ensure on-disk layout
 * ({@code Form.form} / {@code Module.bsl} live next to the {@code .mdo},
 * <b>without</b> a nested {@code Forms/Form/} folder).
 *
 * <p>Implementation note: the inner Form is built via the EDT form-generator
 * service (probe-pattern - several candidate package names checked). When the
 * service is missing, returns a graceful {@code formGeneratorNotFound} hint.
 */
public final class BmCommonFormPostCreate
{
    private BmCommonFormPostCreate()
    {
        // utility
    }

    /**
     * Result of the post-create step.
     */
    public static final class PostCreateResult
    {
        public boolean ok;
        public String innerFormFqn;
        /**
         * The inner {@code Form} model object (an {@code IBmObject}). The caller
         * must register it as a top-object via
         * {@code IBmTransaction.attachTopObject(innerForm, innerFormFqn)} inside
         * the same transaction, else an extension-project commit fails with
         * "Failed to persist reference value FormImpl" (issue #15).
         */
        public Object innerForm;
        public String error;
        public final Map<String, Object> tags = new LinkedHashMap<>();
    }

    /**
     * Creates the inner form for the given CommonForm wrapper.
     * <p>
     * Should be called from inside the same BM read-write transaction that
     * created the wrapper - persistence is then atomic.
     *
     * @param commonForm the CommonForm MdObject just added to the configuration
     * @return result with FQN of the created inner form, or error message
     */
    public static PostCreateResult createInnerForm(MdObject commonForm)
    {
        PostCreateResult r = new PostCreateResult();
        if (commonForm == null)
        {
            r.error = "commonForm is null";
            return r;
        }
        // Probe several known accessor names for the inner-form attribute
        for (String getter : new String[] { "getFormAttachedForm", "getForm", "getRootContainer" })
        {
            try
            {
                Method m = commonForm.getClass().getMethod(getter);
                Object existing = m.invoke(commonForm);
                if (existing != null)
                {
                    r.ok = true;
                    r.innerForm = existing;
                    r.innerFormFqn = "CommonForm." + commonForm.getName() + ".Form";
                    r.tags.put("alreadyHadInnerForm", Boolean.TRUE);
                    return r;
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // try next
            }
            catch (Exception e)
            {
                Activator.logWarning("createInnerForm probe " + getter //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        // No inner form yet - delegate to FormBaseSetup which knows how to
        // build the empty container with 11 base properties (defensive layer 3.8.3).
        Object form = FormBaseSetup.buildEmptyForm(commonForm);
        if (form == null)
        {
            r.error = "FormBaseSetup.buildEmptyForm returned null - inner form generator unavailable";
            r.tags.put("formGeneratorNotFound", Boolean.TRUE);
            return r;
        }
        boolean attached = attachInnerForm(commonForm, form);
        if (!attached)
        {
            r.error = "Inner form built but could not be attached to CommonForm wrapper";
            return r;
        }
        r.ok = true;
        r.innerForm = form;
        r.innerFormFqn = "CommonForm." + commonForm.getName() + ".Form";
        r.tags.put("innerFormCreated", r.innerFormFqn);
        return r;
    }

    /**
     * Attaches the built inner form to the CommonForm wrapper via the
     * matching setter (probe several known names).
     */
    private static boolean attachInnerForm(MdObject commonForm, Object innerForm)
    {
        if (innerForm == null)
        {
            return false;
        }
        for (String setter : new String[] { "setFormAttachedForm", "setForm", "setRootContainer" })
        {
            for (Method m : commonForm.getClass().getMethods())
            {
                if (!setter.equals(m.getName()) || m.getParameterCount() != 1)
                {
                    continue;
                }
                if (!m.getParameterTypes()[0].isInstance(innerForm))
                {
                    continue;
                }
                try
                {
                    m.invoke(commonForm, innerForm);
                    return true;
                }
                catch (Exception e)
                {
                    Activator.logWarning("attachInnerForm " + setter //$NON-NLS-1$
                        + " failed: " + e.getMessage()); //$NON-NLS-1$
                }
            }
        }
        return false;
    }
}
