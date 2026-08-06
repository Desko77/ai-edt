/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import ru.aiedt.mcp.server.Activator;

/**
 * Utility class for form manipulation via reflection on EDT's internal EMF model.
 * <p>
 * All EDT form classes are loaded via {@link Class#forName(String)} to avoid
 * compile-time dependencies on {@code com._1c.g5.v8.dt.form.model} package.
 * EMF singleton factories ({@code FormFactory.eINSTANCE}, {@code MdClassFactory.eINSTANCE})
 * are obtained through reflection.
 * <p>
 * BM transactions use {@link Proxy} for {@code IBmSingleNamespaceTask}.
 * <p>
 * Version-guard: {@link #init()} catches {@link ClassNotFoundException}, logs a warning,
 * and returns {@code false} if EDT form model classes are not available.
 * <p>
 * Based on a proven BmFormHelper pattern for real EDT compatibility.
 */
public class BmFormHelper
{
    /**
     * Behaviour properties a wizard-created table carries, applied by
     * {@link #applyTableRenderDefaults}. Values are text; setScalarProperty
     * coerces to boolean / int / enum.
     * <p>
     * The list is bounded by what the infobase accepts, which is narrower than
     * what the EDT model accepts. Two rules follow from that, and both were paid
     * for in broken imports:
     * <ul>
     * <li>no {@code Auto} where the platform enum has no such literal -
     * {@code TableRowSelectionMode} declares AUTO, the XDTO schema does not, and
     * the mismatch surfaces only when the infobase is updated;</li>
     * <li>no property newer than the compatibility mode of the configuration
     * being edited - {@code autoMaxCardHeight} and
     * {@code showCommandBarNeedDereferenced} were removed for that reason.</li>
     * </ul>
     * Measured over 5419 forms of two real configurations, every pair below
     * occurs as written and {@code rowSelectionMode} is never anything but
     * {@code Row}. Check a new entry the same way before adding it: EDT
     * validation stays green either way, so it proves nothing here.
     */
    static final String[][] TABLE_RENDER_DEFAULTS = {
        {"changeRowSet", "true"}, {"changeRowOrder", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"autoMaxWidth", "true"}, {"autoMaxHeight", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"autoMaxRowsCount", "true"}, {"selectionMode", "MultiRow"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"rowSelectionMode", "Row"}, {"header", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"headerHeight", "1"}, {"footerHeight", "1"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"horizontalScrollBar", "AutoUse"}, {"verticalScrollBar", "AutoUse"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"horizontalLines", "true"}, {"verticalLines", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"autoInsertNewRow", "true"}, {"searchOnInput", "Auto"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"initialListView", "Auto"}, {"horizontalStretch", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"verticalStretch", "true"}, {"enableStartDrag", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"enableDrag", "true"}, {"fileDragMode", "AsFileRef"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    };

    /**
     * Grouping properties a usual group carries, so the property palette is not
     * blank where the editor shows a value.
     * <p>
     * Same constraint as {@link #TABLE_RENDER_DEFAULTS}: {@code FormChildrenGroup}
     * declares AUTO and the platform does not accept it, so {@code group} takes
     * the value the corpus overwhelmingly uses. {@code representation} is absent
     * from this list on purpose - the platform enum has no Auto either, and an
     * editor-created group carries no representation at all (2376 of 3192 groups
     * measured), so the right move is to leave the property untouched.
     */
    static final String[][] USUAL_GROUP_DEFAULTS = {
        {"group", "Vertical"}, {"behavior", "Auto"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"showLeftMargin", "true"}, {"united", "true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {"throughAlign", "Auto"}, {"currentRowUse", "Auto"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    };

    // EDT form model classes (loaded via reflection)
    private Class<?> txIface;           // com._1c.g5.v8.bm.core.IBmTransaction
    private Class<?> ffClass;           // com._1c.g5.v8.dt.form.model.FormFactory
    private Class<?> mdFactoryClass;    // com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory
    private Class<?> formIface;         // com._1c.g5.v8.dt.form.model.Form
    private Class<?> formCommandIface;  // com._1c.g5.v8.dt.form.model.FormCommand
    private Class<?> formItemIface;     // com._1c.g5.v8.dt.form.model.FormItem
    private Class<?> formFieldIface;    // com._1c.g5.v8.dt.form.model.FormField
    private Class<?> formGroupIface;    // com._1c.g5.v8.dt.form.model.FormGroup
    private Class<?> buttonIface;       // com._1c.g5.v8.dt.form.model.Button
    private Class<?> tableIface;        // com._1c.g5.v8.dt.form.model.Table
    private Class<?> decorationIface;   // com._1c.g5.v8.dt.form.model.Decoration
    private Class<?> namedIface;        // com._1c.g5.v8.dt.mcore.NamedElement
    private Class<?> titledIface;       // com._1c.g5.v8.dt.form.model.Titled
    private Class<?> visibleIface;      // com._1c.g5.v8.dt.form.model.Visible
    private Class<?> containerIface;    // com._1c.g5.v8.dt.form.model.FormItemContainer
    private Class<?> commandIface;      // com._1c.g5.v8.dt.mcore.Command
    private Class<?> extTooltipHolderIface; // com._1c.g5.v8.dt.form.model.ExtendedTooltipHolder
    private Class<?> adjBoolClass;      // com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean
    private Class<?> taskIface;         // com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask

    // EMF singleton factories
    private Object formFactory;         // FormFactory.eINSTANCE
    private Object mdFactory;           // MdClassFactory.eINSTANCE

    private boolean initialized = false;
    private int idCounter = 0;

    /**
     * Functional interface for form transaction actions.
     * Executed inside a BM read-write transaction with access to the transaction
     * and the resolved form object.
     */
    @FunctionalInterface
    public interface FormTransactionAction
    {
        /**
         * Executes the form operation inside a BM transaction.
         *
         * @param transaction the BM transaction object
         * @param form the form object resolved by FQN
         * @return result of the operation (typically a description string)
         * @throws Exception if the operation fails
         */
        Object execute(Object transaction, Object form) throws Exception;
    }

    /**
     * Initializes all EDT form model classes and EMF factories via reflection.
     * <p>
     * This method is safe to call multiple times - subsequent calls return immediately
     * if already initialized. If any required class is not found (e.g. incompatible EDT
     * version), logs a warning and returns {@code false}.
     *
     * @return {@code true} if initialization succeeded, {@code false} otherwise
     */
    public boolean init()
    {
        if (initialized)
        {
            return true;
        }
        try
        {
            txIface = Class.forName("com._1c.g5.v8.bm.core.IBmTransaction"); //$NON-NLS-1$
            ffClass = Class.forName("com._1c.g5.v8.dt.form.model.FormFactory"); //$NON-NLS-1$
            mdFactoryClass = Class.forName("com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory"); //$NON-NLS-1$
            formIface = Class.forName("com._1c.g5.v8.dt.form.model.Form"); //$NON-NLS-1$
            formCommandIface = Class.forName("com._1c.g5.v8.dt.form.model.FormCommand"); //$NON-NLS-1$
            formItemIface = Class.forName("com._1c.g5.v8.dt.form.model.FormItem"); //$NON-NLS-1$
            formFieldIface = Class.forName("com._1c.g5.v8.dt.form.model.FormField"); //$NON-NLS-1$
            formGroupIface = Class.forName("com._1c.g5.v8.dt.form.model.FormGroup"); //$NON-NLS-1$
            buttonIface = Class.forName("com._1c.g5.v8.dt.form.model.Button"); //$NON-NLS-1$
            tableIface = Class.forName("com._1c.g5.v8.dt.form.model.Table"); //$NON-NLS-1$
            decorationIface = Class.forName("com._1c.g5.v8.dt.form.model.Decoration"); //$NON-NLS-1$
            namedIface = Class.forName("com._1c.g5.v8.dt.mcore.NamedElement"); //$NON-NLS-1$
            titledIface = Class.forName("com._1c.g5.v8.dt.form.model.Titled"); //$NON-NLS-1$
            visibleIface = Class.forName("com._1c.g5.v8.dt.form.model.Visible"); //$NON-NLS-1$
            containerIface = Class.forName("com._1c.g5.v8.dt.form.model.FormItemContainer"); //$NON-NLS-1$
            commandIface = Class.forName("com._1c.g5.v8.dt.mcore.Command"); //$NON-NLS-1$
            extTooltipHolderIface = Class.forName("com._1c.g5.v8.dt.form.model.ExtendedTooltipHolder"); //$NON-NLS-1$
            adjBoolClass = Class.forName("com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean"); //$NON-NLS-1$
            taskIface = Class.forName("com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask"); //$NON-NLS-1$

            formFactory = ffClass.getField("eINSTANCE").get(null); //$NON-NLS-1$
            mdFactory = mdFactoryClass.getField("eINSTANCE").get(null); //$NON-NLS-1$

            initialized = true;
            return true;
        }
        catch (Exception e)
        {
            Activator.logWarning("BmFormHelper init failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Executes a form operation inside a BM read-write transaction.
     * <p>
     * Steps:
     * <ol>
     * <li>Get {@code IBmModelManager} via {@link Activator}</li>
     * <li>Get {@code IBmModel} for the project</li>
     * <li>Create a {@link Proxy} for {@code IBmSingleNamespaceTask}</li>
     * <li>Inside the proxy: resolve form by FQN via {@code transaction.getTopObjectByFqn()}</li>
     * <li>Call the action with transaction and form</li>
     * <li>Execute the task via {@code bmModel.executeReadWriteTask()} found by reflection</li>
     * </ol>
     *
     * @param project the workspace project
     * @param formFqn the BM top-object FQN of the form, including the trailing
     *            {@code .Form} segment that comes from the {@code Form.form}
     *            file name (e.g. "Catalog.Products.Form.ItemForm.Form"). Use
     *            the diagnostic hint returned on "form not found" to discover
     *            the canonical FQN for borrowed forms in extensions.
     * @param action the action to execute inside the transaction
     * @return result string from the action, or an error message
     */
    public String executeFormOperation(IProject project, String formFqn, FormTransactionAction action)
    {
        // Backward-compatible delegate for legacy / read-only callers (dryRun=false).
        return executeFormOperation(project, formFqn, false, action);
    }

    /**
     * Resolves the metadata object that OWNS a form (e.g. the Catalog of
     * {@code Catalog.Goods.Form.ItemForm}) via the BM transaction, so dataPath
     * resolution ({@code Object.<attr>}) can reach the owner's attributes /
     * tabular sections.
     *
     * <p>The form model object handed to a {@link FormTransactionAction} is a BM
     * <em>top object</em> ({@code ...Form.<name>.Form}); its {@code eContainer()}
     * is null, so the owner cannot be reached by walking up the containment tree.
     * Instead the owner FQN is the form FQN truncated at the {@code .Form.}
     * segment, resolved here with {@code transaction.getTopObjectByFqn}.
     *
     * @param transaction the BM transaction passed to the action's first argument
     * @param formFqn      the form FQN (with or without the trailing {@code .Form})
     * @return the owner MdObject, or {@code null} for a form with no owning object
     *     (a CommonForm) or on any resolve miss - callers fall back to no
     *     {@code Object.}-path resolution
     */
    public Object resolveFormOwnerObject(Object transaction, String formFqn)
    {
        try
        {
            if (transaction == null || formFqn == null)
            {
                return null;
            }
            int idx = formFqn.indexOf(".Form."); //$NON-NLS-1$
            if (idx <= 0)
            {
                // No owning object (e.g. CommonForm.<name>[.Form]).
                return null;
            }
            String ownerFqn = formFqn.substring(0, idx);
            Method getByFqn = txIface.getMethod("getTopObjectByFqn", String.class); //$NON-NLS-1$
            return getByFqn.invoke(transaction, ownerFqn);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Unwraps a resolved BM object to the {@code com._1c.g5.v8.dt.form.model.Form}
     * the form actions operate on ({@code getAttributes()} / {@code getItems()}).
     * <p>
     * A {@code CommonForm} is a root-level {@code MdObject}, so its plain FQN
     * ({@code CommonForm.X}) is itself a resolvable BM top-object - the mdclass
     * {@code BasicForm} wrapper - rather than the inner form model. Every other
     * owner's wrapper FQN resolves to {@code null} (the sub-form is a contained
     * child), so the {@code .Form}-suffix retry repairs it; for a CommonForm the
     * wrapper resolves non-null and the retry never fires. {@code BasicForm.form}
     * is a non-containment reference to the inner form-model top-object, reached
     * here via {@code getForm()}.
     *
     * @param obj the object returned by {@code getTopObjectByFqn}
     * @return the inner {@code Form} when {@code obj} is a {@code BasicForm}
     *     wrapper carrying one; {@code null} when {@code obj} is not a BasicForm
     *     or has no inner form attached yet
     */
    private Object unwrapBasicFormToFormModel(Object obj)
    {
        try
        {
            Class<?> basicFormClass =
                Class.forName("com._1c.g5.v8.dt.metadata.mdclass.BasicForm"); //$NON-NLS-1$
            if (!basicFormClass.isInstance(obj))
            {
                return null;
            }
            Object inner = basicFormClass.getMethod("getForm").invoke(obj); //$NON-NLS-1$
            return (inner != null && formIface.isInstance(inner)) ? inner : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * dryRun-aware form operation. When {@code dryRun} is true the action runs
     * inside the BM transaction and is then rolled back (DryRunAbort), and the
     * changes are NOT persisted to the Form.form file - so a preview leaves no
     * garbage behind. When false, behaviour is identical to the legacy method
     * (commit + forceExport to disk).
     */
    public String executeFormOperation(IProject project, String formFqn, boolean dryRun,
        FormTransactionAction action)
    {
        try
        {
            // Get BM model manager from Activator
            IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
            if (bmModelManager == null)
            {
                return "Error: object model manager is not published as a service"; //$NON-NLS-1$
            }

            IBmModel bmModel = bmModelManager.getModel(project);
            if (bmModel == null)
            {
                return "Error: no BM model available for project: " + project.getName(); //$NON-NLS-1$
            }

            // Bug B: the form-model top-object is registered at
            // <Type>.<Name>.Form.<FormName>.Form (trailing ".Form"). Native
            // ops pass the natural FQN <Type>.<Name>.Form.<FormName> (no
            // trailing ".Form"), which resolves to null. Retry with the ".Form"
            // suffix and capture whichever FQN actually resolved so the later
            // persistFormChanges(...) targets the correct top-object (delegated
            // ops already normalize via toBmFormFqn in EditMetadataTool, but
            // native ops pass formFqn raw).
            final java.util.concurrent.atomic.AtomicReference<String> resolvedFqn =
                new java.util.concurrent.atomic.AtomicReference<>(formFqn);
            // Capture the action's result before a dryRun rollback so the response can report
            // what the action would have done - and surface an action-level Error - instead of
            // a canned "previewed and rolled back" that masks a failure (hindsight C4).
            final java.util.concurrent.atomic.AtomicReference<Object> dryRunPreview =
                new java.util.concurrent.atomic.AtomicReference<>();

            // Create proxy for IBmSingleNamespaceTask
            Object taskProxy = Proxy.newProxyInstance(
                taskIface.getClassLoader(),
                new Class<?>[] { taskIface },
                (proxy, method, args) ->
                {
                    if ("execute".equals(method.getName())) //$NON-NLS-1$
                    {
                        Object transaction = args[0];

                        // Resolve form by FQN: transaction.getTopObjectByFqn(formFqn)
                        Method getByFqn = txIface.getMethod("getTopObjectByFqn", String.class); //$NON-NLS-1$
                        Object form = getByFqn.invoke(transaction, formFqn);
                        if (form == null && !formFqn.endsWith(".Form")) //$NON-NLS-1$
                        {
                            // Retry with the trailing ".Form" segment that the
                            // form-model top-object is actually registered under.
                            String withSuffix = formFqn + ".Form"; //$NON-NLS-1$
                            Object retry = getByFqn.invoke(transaction, withSuffix);
                            if (retry != null)
                            {
                                form = retry;
                                resolvedFqn.set(withSuffix);
                            }
                        }
                        if (form == null)
                        {
                            return "Error: Form not found by FQN: " + formFqn //$NON-NLS-1$
                                + suggestSimilarFqns(transaction, formFqn);
                        }
                        // A CommonForm's plain FQN resolves to the mdclass BasicForm
                        // wrapper, not the form.model.Form the actions call
                        // getAttributes()/getItems() on (see unwrapBasicFormToFormModel).
                        // Unwrap it; inert for a regular form (already a form.model.Form).
                        if (!formIface.isInstance(form))
                        {
                            Object innerForm = unwrapBasicFormToFormModel(form);
                            if (innerForm == null)
                            {
                                return "Error: " + formFqn + " resolves to a form container " //$NON-NLS-1$ //$NON-NLS-2$
                                    + "with no inner Form attached - create the form first."; //$NON-NLS-1$
                            }
                            form = innerForm;
                            // Persist must target the inner Form top-object FQN.
                            if (!formFqn.endsWith(".Form")) //$NON-NLS-1$
                            {
                                resolvedFqn.set(formFqn + ".Form"); //$NON-NLS-1$
                            }
                        }

                        // L68: seed the id counter from the form's current max ID before the
                        // action runs, so a freshly-created element (a form command via
                        // add_form_command, a parameter, ...) gets a unique id instead of reusing
                        // id=1 - which collided across separately-added commands and made every
                        // button call the first one. EditFormTool seeds its own addButton/Field
                        // flow; this covers the FormItemsOps path through executeFormOperation.
                        // For a borrowed form in an extension, inherited ids live on the separate
                        // BaseForm top-object, so scan both - scanning only the override form
                        // would let a new id collide with an inherited one (codex review).
                        // On scan failure, REFUSE rather than continue from an unknown id-space
                        // (idCounter stays 0 -> duplicate ids); logging-and-continuing would
                        // re-introduce the L68 collision silently.
                        try
                        {
                            Object baseForm = findBaseForm(transaction, resolvedFqn.get());
                            resetIdCounter(form, baseForm);
                        }
                        catch (Exception idEx)
                        {
                            return "Error: could not seed the form id counter (" //$NON-NLS-1$
                                + (idEx.getMessage() != null ? idEx.getMessage()
                                    : idEx.getClass().getSimpleName())
                                + ") - refusing to allocate ids that may collide. " //$NON-NLS-1$
                                + "Re-run; if it persists, the form model may be stale (clean_project)."; //$NON-NLS-1$
                        }

                        Object actionResult = action.execute(transaction, form);
                        if (dryRun)
                        {
                            // Surface what the action would have done before rolling back.
                            dryRunPreview.set(actionResult);
                            // Roll back: executeReadWriteTask aborts on this and
                            // commits nothing, leaving Form.form untouched.
                            throw new BmDcsHelper.DryRunAbort();
                        }
                        return actionResult;
                    }
                    return null;
                });

            // executeReadWriteTask lives on IBmModelManager (com._1c.g5.v8.dt.core.platform),
            // not on IBmModel (com._1c.g5.v8.bm.integration). The overload we want:
            // executeReadWriteTask(IProject, IBmSingleNamespaceTask<T>)
            Method executeMethod = findExecuteMethod(bmModelManager, "executeReadWriteTask", IProject.class); //$NON-NLS-1$
            if (executeMethod == null)
            {
                // Fallback: try with generic parameter types
                executeMethod = findExecuteMethod(bmModelManager, "executeReadWriteTask", null); //$NON-NLS-1$
            }
            if (executeMethod == null)
            {
                return "Error: Cannot find executeReadWriteTask method on IBmModelManager"; //$NON-NLS-1$
            }

            Object result;
            try
            {
                result = executeMethod.invoke(bmModelManager, project, taskProxy);
            }
            catch (Exception invokeEx)
            {
                // dryRun rolls back by throwing DryRunAbort inside the task; the
                // reflective invoke wraps it (InvocationTargetException / UTE).
                if (unwrapsTo(invokeEx, BmDcsHelper.DryRunAbort.class))
                {
                    Object preview = dryRunPreview.get();
                    if (preview instanceof String && ((String) preview).startsWith("Error:")) //$NON-NLS-1$
                    {
                        // Prefix with "Error:" so callers' startsWith("Error:") checks
                        // treat a dry-run that previews a FAILURE as an error, not as
                        // success with a buried message (was a false-success across all
                        // form ops). A dry-run that previews SUCCESS keeps the non-Error
                        // branch below and stays success.
                        return "Error: dry run - action would FAIL: " + preview //$NON-NLS-1$
                            + " (rolled back, no changes written to Form.form)."; //$NON-NLS-1$
                    }
                    String note = preview == null ? "" : " Preview result: " + preview; //$NON-NLS-1$ //$NON-NLS-2$
                    return "Dry run: form operation previewed inside a BM transaction " //$NON-NLS-1$
                        + "and rolled back - no changes written to Form.form. Post-commit EDT validation " //$NON-NLS-1$
                        + "is NOT run in a dry run, so a clean preview does not by itself guarantee the real " //$NON-NLS-1$
                        + "operation validates clean." + note; //$NON-NLS-1$
                }
                throw invokeEx;
            }

            // Error case: the proxy / action returns a String prefixed with
            // "Error:" to surface a fatal condition (form not found, etc.).
            if (result instanceof String && ((String) result).startsWith("Error:")) //$NON-NLS-1$
            {
                return (String) result;
            }

            // Persist BM changes to disk: forceExport(IDtProject, formFqn).
            // Without this step changes remain in the BM in-memory namespace
            // and the Form.form file on disk is never updated. Bug B: use the
            // FQN that actually resolved (may carry the ".Form" suffix added on
            // retry above) so persistence targets the correct top-object.
            BmExportHelper.Result persist = persistFormChanges(bmModelManager, project,
                resolvedFqn.get());

            // Success-with-message: the action returns a non-error String
            // describing what it did (e.g. "added attribute X"). Row 42: if the
            // disk flush is pending (or the persist warned), append a plain
            // human-readable note to the message so it surfaces uniformly
            // wherever the message is shown. A structured tag would only reach
            // the few form ops that route through the central formatter; the
            // message string is emitted by all of them.
            String msg = (result instanceof String) ? (String) result : null;
            String note = persistNote(persist);
            if (note != null)
            {
                msg = (msg != null && !msg.isEmpty()) ? msg + " " + note : note; //$NON-NLS-1$
            }
            return msg; // message (with optional note) or null (success, no message)
        }
        catch (Exception e)
        {
            Activator.logError("BM form operation failed", e); //$NON-NLS-1$
            // 1.41: defensive unwrap so InvocationTargetException /
            // UndeclaredThrowableException do not strip the original
            // formApiNotFound: marker.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root)
            {
                root = root.getCause();
            }
            String rootMsg = root.getMessage() != null ? root.getMessage()
                : root.getClass().getSimpleName();
            return "Error: BM API error: " + rootMsg; //$NON-NLS-1$
        }
    }

    /**
     * True when {@code t} or any cause in its chain is an instance of
     * {@code target}. Used to detect a DryRunAbort wrapped by the reflective
     * invoke (InvocationTargetException / UndeclaredThrowableException).
     */
    private static boolean unwrapsTo(Throwable t, Class<?> target)
    {
        for (int i = 0; i < 16 && t != null; i++)
        {
            if (target.isInstance(t))
            {
                return true;
            }
            Throwable next = t.getCause();
            if (next == t)
            {
                break;
            }
            t = next;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Element creation methods
    // -----------------------------------------------------------------------

    /**
     * Creates a form field element with the specified properties.
     *
     * @param name field name
     * @param title field title (displayed to user)
     * @param fieldType field type (InputField, CheckBox, RadioButton, Label, Image)
     * @return the created field object
     * @throws Exception if creation fails
     */
    public Object createFormField(String name, String title, String fieldType) throws Exception
    {
        return createFormField(name, title, fieldType, false);
    }

    /**
     * Hyperlink-aware overload of {@link #createFormField(String, String, String)}.
     * When {@code hyperlink} is true and {@code fieldType} is a Label field,
     * sets {@code LabelFieldExtInfo.hyperlink = true} so the (data-bound) label
     * renders as a clickable hyperlink. Ignored for every other field type -
     * only {@code LabelFieldExtInfo} exposes the property (see {@link #applyHyperlink}).
     */
    public Object createFormField(String name, String title, String fieldType,
        boolean hyperlink) throws Exception
    {
        Object field = ffClass.getMethod("createFormField").invoke(formFactory); //$NON-NLS-1$
        setBasicProperties(field, name, nextId());
        setTitle(field, title);
        setVisibility(field);
        createExtendedTooltip(field, name + "\u0420\u0430\u0441\u0448\u0438\u0440\u0435\u043d\u043d\u0430\u044f\u041f\u043e\u0434\u0441\u043a\u0430\u0437\u043a\u0430", nextId()); //$NON-NLS-1$
        if (fieldType != null && !fieldType.isEmpty())
        {
            setFieldType(field, fieldType);
            setFieldExtInfo(field, fieldType, hyperlink);
        }
        return field;
    }

    /**
     * 1.43.x forms-completeness: applies the table-column properties that 1C Designer
     * emits on a FormField placed INSIDE a Table but that {@link #createFormField}
     * alone does not - so a column added via add_field is visible in the header and
     * editable, matching a Designer-produced column. Sets a (auto-filled) contextMenu,
     * editMode=Enter, showInHeader, headerHorizontalAlign=Left, showInFooter; for an
     * InputField column also fills the InputFieldExtInfo (chooseType / typeDomainEnabled
     * / textEdit). Call ONLY when the field's parent is a Table. Best-effort: a property
     * absent on the runtime field type is skipped silently (never fails the op).
     * <p>
     * A hyperlink column is wired differently from a standalone hyperlink label.
     * {@code LabelFieldExtInfo.hyperlink} alone renders a plain cell: the text is
     * there, but there is no underline, no hand cursor and no click affordance.
     * Inside a table the platform reads three FormField-level properties instead -
     * {@code readOnly}, {@code editMode = EnterOnInput} and {@code cellHyperlink} -
     * so {@code hyperlink} on a column applies those as well.
     *
     * @param field     the freshly created column field
     * @param fieldType the field kind, e.g. {@code InputField} or {@code LabelField}
     * @param hyperlink whether the caller asked for a hyperlink column
     */
    public void applyTableColumnDefaults(Object field, String fieldType, boolean hyperlink)
    {
        if (field == null)
        {
            return;
        }
        // Column context menu, auto-filled with the standard commands (Designer does this).
        Object cm = ensureContextMenu(field);
        if (cm != null)
        {
            setScalarProperty(cm, "autoFill", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // Column-level display / edit properties (apply to any column field type).
        setScalarProperty(field, "editMode", hyperlink ? "EnterOnInput" : "Enter"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        setScalarProperty(field, "showInHeader", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        setScalarProperty(field, "headerHorizontalAlign", "Left"); //$NON-NLS-1$ //$NON-NLS-2$
        setScalarProperty(field, "showInFooter", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        if (hyperlink)
        {
            setScalarProperty(field, "readOnly", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            setScalarProperty(field, "cellHyperlink", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // ExtInfo defaults. Designer auto-sizes every column (LabelField and InputField alike);
        // an InputField column additionally needs the type-choice trio or the cell is not editable.
        Object extInfo = null;
        try
        {
            extInfo = field.getClass().getMethod("getExtInfo").invoke(field); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // No ExtInfo accessor on this field kind - skip the ExtInfo defaults silently.
        }
        if (extInfo != null)
        {
            setScalarProperty(extInfo, "autoMaxWidth", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            setScalarProperty(extInfo, "autoMaxHeight", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            if (fieldType == null || fieldType.isEmpty()
                || fieldType.toLowerCase().contains("input")) //$NON-NLS-1$
            {
                setScalarProperty(extInfo, "chooseType", "true"); //$NON-NLS-1$ //$NON-NLS-2$
                setScalarProperty(extInfo, "typeDomainEnabled", "true"); //$NON-NLS-1$ //$NON-NLS-2$
                setScalarProperty(extInfo, "textEdit", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * Creates a form group element with the specified properties.
     *
     * @param name group name
     * @param title group title
     * @param groupType group type (UsualGroup, Pages, Page, Column, CommandBar)
     * @return the created group object
     * @throws Exception if creation fails
     */
    public Object createFormGroup(String name, String title, String groupType) throws Exception
    {
        Object group = ffClass.getMethod("createFormGroup").invoke(formFactory); //$NON-NLS-1$
        setBasicProperties(group, name, nextId());
        setTitle(group, title);
        setVisibility(group);
        createExtendedTooltip(group, name + "\u0420\u0430\u0441\u0448\u0438\u0440\u0435\u043d\u043d\u0430\u044f\u041f\u043e\u0434\u0441\u043a\u0430\u0437\u043a\u0430", nextId()); //$NON-NLS-1$
        // A UsualGroup is the default when no explicit type is requested.
        // Always build the ext-info: the "New Form" wizard fills the grouping
        // defaults (group / behavior / representation / showLeftMargin / united
        // / throughAlign / currentRowUse); a group created without them shows a
        // blank grouping property in the palette (reported 2026-07-03).
        if (groupType != null && !groupType.isEmpty())
        {
            setGroupType(group, groupType);
            setGroupExtInfo(group, groupType);
        }
        else
        {
            setGroupType(group, "UsualGroup"); //$NON-NLS-1$
            setGroupExtInfo(group, "UsualGroup"); //$NON-NLS-1$
        }
        return group;
    }

    /**
     * Creates a button element with the specified properties.
     *
     * @param name button name
     * @param title button title
     * @return the created button object
     * @throws Exception if creation fails
     */
    public Object createButton(String name, String title) throws Exception
    {
        Object button = ffClass.getMethod("createButton").invoke(formFactory); //$NON-NLS-1$
        setBasicProperties(button, name, nextId());
        setTitle(button, title);
        setVisibility(button);
        createExtendedTooltip(button, name + "\u0420\u0430\u0441\u0448\u0438\u0440\u0435\u043d\u043d\u0430\u044f\u041f\u043e\u0434\u0441\u043a\u0430\u0437\u043a\u0430", nextId()); //$NON-NLS-1$
        setRepresentation(button, "Auto"); //$NON-NLS-1$
        // A Button without an explicit type serializes without <type>, which EDT/the platform
        // do not accept for a rendered button - default it to UsualButton (the common case).
        setButtonType(button, "UsualButton"); //$NON-NLS-1$
        return button;
    }

    /**
     * 1.43.x batch 4: ensures a {@code ContextMenuHolder} (FormField / Table /
     * Decoration / Addition) has a {@code ContextMenu}, creating and attaching one
     * via {@code FormFactory.createContextMenu()} + {@code setContextMenu(...)} when
     * the holder has none yet ({@code getContextMenu()} is a settable containment,
     * null until first set). Lets {@code add_button parentName=<holder>ContextMenu}
     * target a real attached menu instead of silently falling back to the form root.
     * Returns the context menu (existing or freshly created), or {@code null} when
     * the holder is not a ContextMenuHolder / reflection fails.
     */
    public Object ensureContextMenu(Object holder)
    {
        if (holder == null)
        {
            return null;
        }
        try
        {
            Object existing = holder.getClass().getMethod("getContextMenu").invoke(holder); //$NON-NLS-1$
            if (existing != null)
            {
                return existing;
            }
            Object cm = ffClass.getMethod("createContextMenu").invoke(formFactory); //$NON-NLS-1$
            String holderName = null;
            try
            {
                Object n = namedIface.getMethod("getName").invoke(holder); //$NON-NLS-1$
                if (n != null && !n.toString().isEmpty())
                {
                    holderName = n.toString();
                }
            }
            catch (Exception ignored)
            {
                // holder may be unnamed - fall back to a unique suffix name
            }
            int cmId = nextId();
            // Unique name: <holder>\u041a\u043e\u043d\u0442\u0435\u043a\u0441\u0442\u043d\u043e\u0435\u041c\u0435\u043d\u044e, or \u041a\u043e\u043d\u0442\u0435\u043a\u0441\u0442\u043d\u043e\u0435\u041c\u0435\u043d\u044e<id> when the holder
            // is (defensively) unnamed, to avoid a name collision between two such menus.
            String cmName = holderName != null
                ? holderName + "\u041a\u043e\u043d\u0442\u0435\u043a\u0441\u0442\u043d\u043e\u0435\u041c\u0435\u043d\u044e" //$NON-NLS-1$
                : "\u041a\u043e\u043d\u0442\u0435\u043a\u0441\u0442\u043d\u043e\u0435\u041c\u0435\u043d\u044e" + cmId; //$NON-NLS-1$
            setBasicProperties(cm, cmName, cmId);
            // Attach via setContextMenu(ContextMenu). invokeSingleParamSetter THROWS when the
            // setter is absent (e.g. an older runtime) -> caught below -> returns null -> the
            // caller surfaces a clear "parent not found" error, instead of silently adding the
            // button to a DETACHED menu that is dropped on commit (the conditionalAppearance
            // silent-loss failure mode).
            invokeSingleParamSetter(holder, "setContextMenu", cm); //$NON-NLS-1$
            Object reread = holder.getClass().getMethod("getContextMenu").invoke(holder); //$NON-NLS-1$
            return reread != null ? reread : cm;
        }
        catch (Exception e)
        {
            Activator.logWarning("ensureContextMenu failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Creates a table element with the specified properties.
     *
     * @param name table name
     * @param title table title
     * @return the created table object
     * @throws Exception if creation fails
     */
    public Object createTable(String name, String title) throws Exception
    {
        Object table = ffClass.getMethod("createTable").invoke(formFactory); //$NON-NLS-1$
        setBasicProperties(table, name, nextId());
        setTitle(table, title);
        setVisibility(table);
        createExtendedTooltip(table, name + "\u0420\u0430\u0441\u0448\u0438\u0440\u0435\u043d\u043d\u0430\u044f\u041f\u043e\u0434\u0441\u043a\u0430\u0437\u043a\u0430", nextId()); //$NON-NLS-1$
        return table;
    }

    /**
     * Editor parity for {@code add_table}. A Table created with name/id/title
     * only does NOT render in the EDT WYSIWYG editor - the form opens but the
     * table area stays blank (verified 2026-07-03 on a DataProcessor form).
     * The "New Form" wizard / drop-an-attribute path additionally gives the
     * table its own command bar, context menu and ~two dozen behaviour
     * properties (selection mode, header, scroll bars, grid lines, stretch,
     * drag). This method reproduces that default set so a table added through
     * the MCP renders identically to one built in the editor.
     * <p>
     * Every property is best-effort via {@link #setScalarProperty}: a setter
     * absent on the current runtime is skipped, never fatal. Call right after
     * {@link #createTable}, before columns are generated, so the command bar /
     * context menu ids precede the column ids (as the wizard assigns them).
     * <p>
     * The default set is bounded by what the infobase accepts, not by what the
     * EDT model accepts - the two differ. {@code TableRowSelectionMode} declares
     * an AUTO literal that the XDTO schema rejects on import, and
     * {@code autoMaxCardHeight} / {@code showCommandBarNeedDereferenced} are
     * newer than the compatibility mode of a typical configuration. Measured
     * over 5419 forms of two real configurations: {@code rowSelectionMode} only
     * ever holds {@code Row}, and neither of the other two tags occurs once.
     * Adding a property here means checking it against a real configuration
     * first; EDT validation stays green either way.
     *
     * @param table     the freshly created {@code form:Table}
     * @param tableName the table name (used to name the auto command bar / menu)
     */
    public void applyTableRenderDefaults(Object table, String tableName)
    {
        if (table == null)
        {
            return;
        }
        int skipped = 0;
        for (String[] pair : TABLE_RENDER_DEFAULTS)
        {
            if (setScalarProperty(table, pair[0], pair[1]) != null)
            {
                skipped++;
            }
        }
        if (skipped > 0)
        {
            Activator.logWarning("applyTableRenderDefaults: " + skipped + " of " //$NON-NLS-1$ //$NON-NLS-2$
                + TABLE_RENDER_DEFAULTS.length + " table defaults not applied on this runtime"); //$NON-NLS-1$
        }
        // The command bar and context menu are containment children a wizard
        // table always carries; both have dedicated best-effort helpers.
        getOrCreateAutoCommandBar(table, tableName);
        ensureContextMenu(table);
    }

    /**
     * Creates a decoration element with the specified properties.
     *
     * @param name decoration name
     * @param title decoration title
     * @param decorationType decoration type ("Label" or "Picture")
     * @return the created decoration object
     * @throws Exception if creation fails
     */
    public Object createDecoration(String name, String title, String decorationType) throws Exception
    {
        return createDecoration(name, title, decorationType, null);
    }

    /**
     * 1.42: extended overload that also applies a picture reference to a
     * Picture-type decoration. The picture string is passed to
     * {@code PictureDecorationExtInfo.setPicture} (typed setter when the
     * EDT runtime exposes one). Picture validation against
     * {@code StandardPictures} / project's {@code CommonPictures} happens
     * earlier in {@code EditFormTool.executeAddDecoration} via
     * {@link PictureValidator}; this method only applies the validated
     * value.
     *
     * <p>{@code picture} is ignored for non-Picture decorations.
     */
    public Object createDecoration(String name, String title, String decorationType,
        String picture) throws Exception
    {
        return createDecoration(name, title, decorationType, picture, false);
    }

    /**
     * Hyperlink-aware overload of {@link #createDecoration(String, String, String, String)}.
     * When {@code hyperlink} is true and this is a Label decoration, sets
     * {@code LabelDecorationExtInfo.hyperlink = true} so the decoration renders
     * as clickable hyperlink text - the canonical 1C form hyperlink element.
     * Ignored for Picture decorations (their ext-info exposes no hyperlink
     * property); the flag is applied best-effort via {@link #applyHyperlink}.
     */
    public Object createDecoration(String name, String title, String decorationType,
        String picture, boolean hyperlink) throws Exception
    {
        Object decoration = ffClass.getMethod("createDecoration").invoke(formFactory); //$NON-NLS-1$
        setBasicProperties(decoration, name, nextId());
        setTitle(decoration, title);
        setVisibility(decoration);

        Class<?> decoTypeClass = Class.forName("com._1c.g5.v8.dt.form.model.ManagedFormDecorationType"); //$NON-NLS-1$
        Class<?> decoExtInfoClass = Class.forName("com._1c.g5.v8.dt.form.model.DecorationExtInfo"); //$NON-NLS-1$

        if ("Picture".equalsIgnoreCase(decorationType)) //$NON-NLS-1$
        {
            // Set type to PICTURE
            for (Object constant : decoTypeClass.getEnumConstants())
            {
                if ("PICTURE".equals(constant.toString())) //$NON-NLS-1$
                {
                    decorationIface.getMethod("setType", decoTypeClass).invoke(decoration, constant); //$NON-NLS-1$
                    break;
                }
            }
            Object extInfo = ffClass.getMethod("createPictureDecorationExtInfo").invoke(formFactory); //$NON-NLS-1$
            decorationIface.getMethod("setExtInfo", decoExtInfoClass).invoke(decoration, extInfo); //$NON-NLS-1$
            // 1.42: apply the picture reference when supplied. Probe a String
            // setter first (modern EDT) and fall back silently when only an
            // EMF-typed setter exists - the agent can still set picture later
            // via setProperty.
            if (picture != null && !picture.isEmpty())
            {
                applyPictureReferenceOnExtInfo(extInfo, picture);
            }
        }
        else
        {
            // Default to LABEL
            for (Object constant : decoTypeClass.getEnumConstants())
            {
                if ("LABEL".equals(constant.toString())) //$NON-NLS-1$
                {
                    decorationIface.getMethod("setType", decoTypeClass).invoke(decoration, constant); //$NON-NLS-1$
                    break;
                }
            }
            Object extInfo = ffClass.getMethod("createLabelDecorationExtInfo").invoke(formFactory); //$NON-NLS-1$
            decorationIface.getMethod("setExtInfo", decoExtInfoClass).invoke(decoration, extInfo); //$NON-NLS-1$
            // A Label decoration can render as a hyperlink (clickable text).
            applyHyperlink(extInfo, hyperlink);
        }

        decorationIface.getMethod("setAutoMaxWidth", Boolean.TYPE).invoke(decoration, true); //$NON-NLS-1$
        decorationIface.getMethod("setAutoMaxHeight", Boolean.TYPE).invoke(decoration, true); //$NON-NLS-1$
        return decoration;
    }

    /**
     * 1.42 helper: applies a picture reference to a
     * {@code PictureDecorationExtInfo} via reflection. Tries
     * {@code setPicture(String)} first; older EDT may use a typed setter
     * accepting a {@code Picture} EMF object - in that case the call is
     * silently skipped (the decoration is still valid, the agent can set
     * the picture via {@code setProperty} as a follow-up).
     */
    private static void applyPictureReferenceOnExtInfo(Object extInfo, String picture)
    {
        for (java.lang.reflect.Method m : extInfo.getClass().getMethods())
        {
            if (!"setPicture".equals(m.getName()) || m.getParameterCount() != 1) //$NON-NLS-1$
            {
                continue;
            }
            Class<?> p = m.getParameterTypes()[0];
            if (p == String.class)
            {
                try
                {
                    m.invoke(extInfo, picture);
                    return;
                }
                catch (Exception ignored)
                {
                    // Try next overload.
                }
            }
        }
        // No String overload; the typed Picture-EMF path requires
        // MdClassFactory.eINSTANCE.createPicture() and is not stable across
        // EDT versions. Leave the decoration created without an icon - the
        // agent can apply the picture via setProperty.
    }

    /**
     * Best-effort: sets {@code hyperlink = true} on a Label decoration's
     * {@code LabelDecorationExtInfo} or a Label field's
     * {@code LabelFieldExtInfo} via reflection, so the element renders as a
     * clickable hyperlink. Only those two ext-info types expose
     * {@code setHyperlink(boolean)}; for any other ext-info (Picture, Input,
     * CheckBox, Image, ...) the setter is absent and the call is skipped
     * silently - the element is still valid and the agent can set the flag
     * later via {@code setFormItemProperty}. No-op when {@code hyperlink} is
     * false or {@code extInfo} is null.
     *
     * @param extInfo   the field/decoration ext-info EObject (may be null)
     * @param hyperlink whether to flag the element as a hyperlink
     */
    private static void applyHyperlink(Object extInfo, boolean hyperlink)
    {
        if (!hyperlink || extInfo == null)
        {
            return;
        }
        for (java.lang.reflect.Method m : extInfo.getClass().getMethods())
        {
            if ("setHyperlink".equals(m.getName()) && m.getParameterCount() == 1 //$NON-NLS-1$
                && m.getParameterTypes()[0] == boolean.class)
            {
                try
                {
                    m.invoke(extInfo, Boolean.TRUE);
                }
                catch (Exception e)
                {
                    // InvocationTargetException carries no message of its own -
                    // unwrap to the cause so the log entry is diagnostic.
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    Activator.logWarning("applyHyperlink failed: " + cause); //$NON-NLS-1$
                }
                return;
            }
        }
    }

    /**
     * Creates a form command with the specified properties.
     *
     * @param name command name
     * @param title command title
     * @return the created command object
     * @throws Exception if creation fails
     */
    public Object createFormCommand(String name, String title) throws Exception
    {
        Object command = ffClass.getMethod("createFormCommand").invoke(formFactory); //$NON-NLS-1$
        namedIface.getMethod("setName", String.class).invoke(command, name); //$NON-NLS-1$
        formCommandIface.getMethod("setId", Integer.TYPE).invoke(command, nextId()); //$NON-NLS-1$
        setTitle(command, title);
        return command;
    }

    /**
     * Bug C: wires a {@code FormCommand} to its BSL handler procedure by
     * building the command's {@code action}. Without an action the command is
     * non-functional (no {@code <action>} in Form.form), so a button bound to
     * it does nothing.
     * <p>
     * Reflection chain (all types javap-confirmed in
     * {@code com._1c.g5.v8.dt.form.model}):
     * <ol>
     *   <li>{@code FormFactory.createFormCommandHandlerContainer()} - the
     *       CONCRETE container ({@code CommandHandlerContainer} is abstract and
     *       cannot be instantiated, which is why this must be the
     *       {@code FormCommandHandlerContainer} factory method);</li>
     *   <li>{@code FormFactory.createCommandHandler()} -&gt; handler;</li>
     *   <li>{@code CommandHandler.setName(String)} - the BSL procedure name;</li>
     *   <li>{@code FormCommandHandlerContainer.setHandler(CommandHandler)};</li>
     *   <li>{@code FormCommand.setAction(CommandHandlerContainer)}.</li>
     * </ol>
     * Best-effort: any reflective failure is logged and swallowed so the
     * command stays created (the caller surfaces a hint to add the BSL body).
     *
     * @param command the FormCommand object
     * @param handlerName the BSL handler procedure name
     */
    public void setFormCommandAction(Object command, String handlerName)
    {
        if (command == null || handlerName == null || handlerName.isEmpty())
        {
            return;
        }
        try
        {
            // Concrete container - createFormCommandHandlerContainer, NOT
            // createCommandHandlerContainer (the latter type is abstract).
            Object container =
                ffClass.getMethod("createFormCommandHandlerContainer").invoke(formFactory); //$NON-NLS-1$
            Object handler = ffClass.getMethod("createCommandHandler").invoke(formFactory); //$NON-NLS-1$

            invokeSingleParamSetter(handler, "setName", handlerName); //$NON-NLS-1$
            invokeSingleParamSetter(container, "setHandler", handler); //$NON-NLS-1$
            invokeSingleParamSetter(command, "setAction", container); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logWarning("setFormCommandAction(" + handlerName //$NON-NLS-1$
                + ") failed (command created without action): " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Creates a form attribute with the given name and optional title via
     * {@code FormFactory.createFormAttribute()}. The caller adds the result
     * to the form's attributes collection via {@link #addAttributeToForm}.
     *
     * @param name attribute name (mandatory)
     * @param title attribute title (optional)
     * @return the created FormAttribute object
     * @throws Exception if creation fails
     */
    public Object createFormAttribute(String name, String title) throws Exception
    {
        Object attribute = ffClass.getMethod("createFormAttribute").invoke(formFactory); //$NON-NLS-1$
        namedIface.getMethod("setName", String.class).invoke(attribute, name); //$NON-NLS-1$
        if (title != null && !title.isEmpty())
        {
            try
            {
                setTitle(attribute, title);
            }
            catch (Exception ignored)
            {
                // FormAttribute may not implement Titled in every EDT version
            }
        }
        return attribute;
    }

    /**
     * Applies the mandatory id and {@code view}/{@code edit} Use flags to a
     * FormAttribute (AbstractFormAttribute). Without an id plus
     * {@code view=AdjustableBoolean(common=true)} /
     * {@code edit=AdjustableBoolean(common=true)} the platform does not
     * materialise a form variable for the attribute - a DynamicList table bound
     * to it renders empty and BSL referencing it fails with
     * "Переменная X не определена".
     *
     * @param attribute the FormAttribute EObject
     * @param id the unique id to assign (form-wide id space)
     */
    public void applyFormAttributeIdAndUse(Object attribute, int id)
    {
        try
        {
            attribute.getClass().getMethod("setId", Integer.TYPE).invoke(attribute, id); //$NON-NLS-1$
        }
        catch (Exception ex)
        {
            Activator.logWarning("FormAttribute setId not applied: " + ex.getMessage()); //$NON-NLS-1$
        }
        setAdjustableCommon(attribute, "setView"); //$NON-NLS-1$
        setAdjustableCommon(attribute, "setEdit"); //$NON-NLS-1$
    }

    /**
     * Next free id in the form-attribute id space. Form attributes have their
     * own id space (separate from items / commands / columns - a stock form has
     * attribute id=1 coexisting with item id=1), so max-over-attributes + 1 is
     * sufficient and mirrors EDT's 1,2,3.. output. Returns 1 for a form with no
     * attributes. Without an assigned id a new attribute defaults to id=0, so a
     * second attribute added the same way collides -> "Duplicate id '0'"
     * (form-legacy-emf-check) and the form fails to load.
     *
     * @param form the Form EObject
     * @return the next unused attribute id (>= 1)
     * @throws Exception on reflective access failure
     */
    public int nextFormAttributeId(Object form) throws Exception
    {
        int max = 0;
        Object attributes = formIface.getMethod("getAttributes").invoke(form); //$NON-NLS-1$
        int size = (Integer) attributes.getClass().getMethod("size").invoke(attributes); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object attr = attributes.getClass().getMethod("get", Integer.TYPE).invoke(attributes, i); //$NON-NLS-1$
            Object idVal = attr.getClass().getMethod("getId").invoke(attr); //$NON-NLS-1$
            if (idVal instanceof Integer && (Integer) idVal > max)
            {
                max = (Integer) idVal;
            }
        }
        return max < 1 ? 1 : max + 1;
    }

    /**
     * Reads a FormAttribute's current id (0 when unset). Lets a caller verify
     * that {@link #applyFormAttributeIdAndUse} actually persisted the id -
     * that method only logs a warning when the setId reflection fails, so a
     * caller that must not ship an id=0 attribute (which the platform rejects
     * as "Duplicate id '0'") checks the outcome here and fails loudly.
     *
     * @param attribute the FormAttribute EObject
     * @return the attribute id, or 0 when unset / not an Integer
     * @throws Exception on reflective access failure
     */
    public int readFormAttributeId(Object attribute) throws Exception
    {
        Object idVal = attribute.getClass().getMethod("getId").invoke(attribute); //$NON-NLS-1$
        return (idVal instanceof Integer) ? (Integer) idVal : 0;
    }

    /**
     * Best-effort {@code setXxx(AdjustableBoolean(common=true))} on a target
     * (used for FormAttribute {@code view} / {@code edit}). Silently does
     * nothing when the factory or setter is unavailable on this EDT runtime.
     *
     * @param target the object exposing the AdjustableBoolean setter
     * @param setter the setter name ({@code setView} / {@code setEdit})
     */
    private void setAdjustableCommon(Object target, String setter)
    {
        try
        {
            Object adjBool = mdFactoryClass.getMethod("createAdjustableBoolean").invoke(mdFactory); //$NON-NLS-1$
            adjBoolClass.getMethod("setCommon", Boolean.TYPE).invoke(adjBool, true); //$NON-NLS-1$
            target.getClass().getMethod(setter, adjBoolClass).invoke(target, adjBool);
        }
        catch (Exception ignored)
        {
            // view/edit are best-effort - never fail the whole op over them
        }
    }

    // -----------------------------------------------------------------------
    // 1.41: Forms 3 deferred ops (addFormAttributeColumn,
    //       addDynamicListTable, setupSettingsComposer)
    // -----------------------------------------------------------------------

    /**
     * 1.41: probes a factory method on either {@link #formFactory} or
     * {@link #mdFactory} by name, returning the freshly-created EObject
     * or {@code null} when no candidate exists.
     */
    private Object probeFactoryCreate(String... methodCandidates)
    {
        for (String mname : methodCandidates)
        {
            try
            {
                return ffClass.getMethod(mname).invoke(formFactory);
            }
            catch (NoSuchMethodException ignored)
            {
                // try next candidate / factory
            }
            catch (Exception ignored)
            {
                // factory exists but threw - move on
            }
            try
            {
                return mdFactoryClass.getMethod(mname).invoke(mdFactory);
            }
            catch (NoSuchMethodException ignored)
            {
                // try next candidate
            }
            catch (Exception ignored)
            {
                // method exists but threw - move on
            }
        }
        return null;
    }

    /**
     * Public accessor: returns the top-level FormAttribute with the given
     * name (case-insensitive), or {@code null} when absent. Wraps the
     * internal {@link #findFormAttributeByName} so callers can inspect an
     * existing attribute (e.g. its valueType) on the idempotent path.
     */
    public Object getFormAttribute(Object form, String name) throws Exception
    {
        return findFormAttributeByName(form, name);
    }

    /**
     * Resolves a form ATTRIBUTE by name, descending into ValueTable columns
     * ({@code FormAttributeColumn}, which also extends AbstractFormAttribute
     * and so carries the {@code functionalOptions} feature) when the name is
     * not a top-level attribute. Accepts:
     * <ul>
     *   <li>a bare top-level attribute name (same as
     *       {@link #getFormAttribute});</li>
     *   <li>a dotted {@code parent.column} path - resolves the parent as a
     *       top-level attribute, then the named column in its
     *       {@code getColumns()};</li>
     *   <li>a bare column name - scans every top-level attribute that carries
     *       columns and resolves only on a unique match. An ambiguous match
     *       throws, listing the candidate {@code parent.column} pairs and
     *       asking for the dotted form.</li>
     * </ul>
     * Returns {@code null} when nothing matches. Intended for ops whose target
     * may legitimately be a column (form-item functional options); the
     * top-level-only accessors ({@link #getFormAttribute},
     * {@link #hasFormAttribute}) intentionally keep their original semantics.
     */
    public Object getFormAttributeOrColumn(Object form, String name) throws Exception
    {
        Object top = findFormAttributeByName(form, name);
        if (top != null)
        {
            return top;
        }
        int dot = name.indexOf('.');
        if (dot > 0 && dot < name.length() - 1)
        {
            String parentName = name.substring(0, dot);
            String columnName = name.substring(dot + 1);
            Object parent = findFormAttributeByName(form, parentName);
            if (parent != null)
            {
                return findColumnInAttribute(parent, columnName);
            }
            return null;
        }
        // Bare column name: collect matches across all column-bearing attributes.
        List<Object> matches = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Object attributes = formIface.getMethod("getAttributes").invoke(form); //$NON-NLS-1$
        int size = (Integer) attributes.getClass().getMethod("size").invoke(attributes); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object attr = attributes.getClass().getMethod("get", Integer.TYPE).invoke(attributes, i); //$NON-NLS-1$
            Object col = findColumnInAttribute(attr, name);
            if (col != null)
            {
                matches.add(col);
                labels.add(attributeColumnName(attr, name));
            }
        }
        if (matches.size() == 1)
        {
            return matches.get(0);
        }
        if (matches.size() > 1)
        {
            throw new IllegalStateException("Ambiguous form attribute name '" + name //$NON-NLS-1$
                + "': matches " + matches.size() + " columns (" + String.join(", ", labels) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "). Use the dotted 'parent.column' form to disambiguate."); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Finds a column by name (case-insensitive) in a parent attribute's
     * {@code getColumns()} list. Returns {@code null} when the parent has no
     * {@code getColumns()} (not a Table-typed attribute) or no column matches.
     */
    private Object findColumnInAttribute(Object parentAttr, String columnName) throws Exception
    {
        Method getColumns;
        try
        {
            getColumns = parentAttr.getClass().getMethod("getColumns"); //$NON-NLS-1$
        }
        catch (NoSuchMethodException notTableTyped)
        {
            return null;
        }
        Object columns = getColumns.invoke(parentAttr);
        int size = (Integer) columns.getClass().getMethod("size").invoke(columns); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object col = columns.getClass().getMethod("get", Integer.TYPE).invoke(columns, i); //$NON-NLS-1$
            try
            {
                String n = (String) namedIface.getMethod("getName").invoke(col); //$NON-NLS-1$
                if (columnName.equalsIgnoreCase(n))
                {
                    return col;
                }
            }
            catch (Exception ignored)
            {
                // unnamed column, skip
            }
        }
        return null;
    }

    /**
     * Builds a {@code parent.column} label for an ambiguous-match diagnostic.
     * Best-effort: falls back to the bare column name when the parent name
     * cannot be read.
     */
    private String attributeColumnName(Object parentAttr, String columnName)
    {
        try
        {
            String parentName = (String) namedIface.getMethod("getName").invoke(parentAttr); //$NON-NLS-1$
            return parentName + "." + columnName; //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            return columnName;
        }
    }

    /**
     * Sets a property on a form attribute's {@code extInfo} (e.g. a DynamicList
     * attribute's {@code DynamicListExtInfo}: {@code queryText},
     * {@code customQuery}, {@code dynamicDataRead}). This closes the gap where
     * set_property only worked on form ITEMS, not on a form attribute's extInfo
     * (the data source of a dynamic list lives on the attribute, not on the UI
     * table). {@code mainTable} needs a {@code DbViewDef} and is not settable
     * from a string here - use a customQuery + queryText instead, or the EDT
     * editor.
     *
     * @param form the form object
     * @param attributeName the FormAttribute name
     * @param propertyName the extInfo property (e.g. {@code queryText})
     * @param propertyValue the value (parsed to boolean for boolean setters)
     * @return a success message, or an {@code "Error: ..."} string
     * @throws Exception if scanning fails
     */
    public String setAttributeExtInfoProperty(Object form, String attributeName,
        String propertyName, String propertyValue) throws Exception
    {
        if (propertyName == null || propertyName.isEmpty())
        {
            return "Error: propertyName is required"; //$NON-NLS-1$
        }
        Object attr = findFormAttributeByName(form, attributeName);
        if (attr == null)
        {
            return "Error: form attribute '" + attributeName + "' not found. " //$NON-NLS-1$ //$NON-NLS-2$
                + "set_property with attributeName targets a form attribute's extInfo " //$NON-NLS-1$
                + "(e.g. a DynamicList's queryText / customQuery)."; //$NON-NLS-1$
        }
        Object extInfo;
        try
        {
            extInfo = attr.getClass().getMethod("getExtInfo").invoke(attr); //$NON-NLS-1$
        }
        catch (Exception ex)
        {
            return "Error: attribute '" + attributeName + "' exposes no extInfo"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (extInfo == null)
        {
            return "Error: attribute '" + attributeName + "' has no extInfo " //$NON-NLS-1$ //$NON-NLS-2$
                + "(not a DynamicList / typed attribute)."; //$NON-NLS-1$
        }
        String setterName = "set" + Character.toUpperCase(propertyName.charAt(0)) //$NON-NLS-1$
            + propertyName.substring(1);
        for (Method m : extInfo.getClass().getMethods())
        {
            if (!m.getName().equals(setterName) || m.getParameterCount() != 1)
            {
                continue;
            }
            Class<?> pt = m.getParameterTypes()[0];
            try
            {
                if (pt == Boolean.TYPE || pt == Boolean.class)
                {
                    boolean b = Boolean.parseBoolean(propertyValue);
                    m.invoke(extInfo, b);
                    return attributeName + " extInfo." + propertyName + " = " + b; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (pt == String.class)
                {
                    m.invoke(extInfo, propertyValue);
                    return attributeName + " extInfo." + propertyName + " set"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                return "Error: property '" + propertyName + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                    + extInfo.getClass().getSimpleName() + " expects " //$NON-NLS-1$
                    + pt.getSimpleName() + ", which is not settable from a string here. " //$NON-NLS-1$
                    + "For a DynamicList source, set customQuery=true + queryText, " //$NON-NLS-1$
                    + "or wire mainTable in the EDT form editor."; //$NON-NLS-1$
            }
            catch (Exception ex)
            {
                return "Error: setting " + propertyName + " failed: " + ex.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return "Error: property '" + propertyName + "' is absent on " //$NON-NLS-1$ //$NON-NLS-2$
            + extInfo.getClass().getSimpleName() + ". Common DynamicList props: " //$NON-NLS-1$
            + "queryText (String), customQuery (Boolean), dynamicDataRead (Boolean), " //$NON-NLS-1$
            + "autoFillAvailableFields (Boolean)."; //$NON-NLS-1$
    }

    /**
     * 1.41: searches the form's top-level attributes list for a FormAttribute
     * by name. Case-insensitive. Returns {@code null} when not found.
     */
    private Object findFormAttributeByName(Object form, String name) throws Exception
    {
        Object attributes = formIface.getMethod("getAttributes").invoke(form); //$NON-NLS-1$
        int size = (Integer) attributes.getClass().getMethod("size").invoke(attributes); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object attr = attributes.getClass().getMethod("get", Integer.TYPE).invoke(attributes, i); //$NON-NLS-1$
            try
            {
                String attrName = (String) namedIface.getMethod("getName").invoke(attr); //$NON-NLS-1$
                if (name.equalsIgnoreCase(attrName))
                {
                    return attr;
                }
            }
            catch (Exception ignored)
            {
                // unnamed entry, skip
            }
        }
        return null;
    }

    /**
     * 1.41: invokes a single-parameter setter on a target by name. Used for
     * {@code setExtInfo} where the parameter type varies across attribute
     * subtypes (DynamicListExtInfo, DataCompositionSettingsComposerExtInfo,
     * etc.).
     */
    private void invokeSingleParamSetter(Object target, String setterName, Object value) throws Exception
    {
        for (Method m : target.getClass().getMethods())
        {
            if (m.getName().equals(setterName) && m.getParameterCount() == 1)
            {
                m.invoke(target, value);
                return;
            }
        }
        throw new NoSuchMethodException(setterName + "(...) on " + target.getClass().getName()); //$NON-NLS-1$
    }

    /**
     * 1.41: adds a column to a parent FormAttribute of type Table.
     * <p>
     * Idempotent: a column with the same name already attached to the
     * parent attribute returns a propertyMismatch-style notice instead of
     * duplicating. When the EDT factory does not expose
     * {@code createFormAttributeColumn}, throws an
     * {@link UnsupportedOperationException} prefixed with {@code formApiNotFound:}
     * so the caller can surface a structured error tag.
     *
     * @return descriptive success message
     */
    public String addFormAttributeColumn(Object form, String parentAttributeName,
        String name, String title, String dataPath, String type,
        org.eclipse.core.resources.IProject project,
        com._1c.g5.v8.dt.metadata.mdclass.Configuration config,
        BmDefinedTypeHelper.QualifierOptions qualifiers) throws Exception
    {
        if (parentAttributeName == null || parentAttributeName.isEmpty())
        {
            throw new IllegalArgumentException("parentAttributeName is required"); //$NON-NLS-1$
        }
        if (name == null || name.isEmpty())
        {
            throw new IllegalArgumentException("name is required"); //$NON-NLS-1$
        }
        Object parent = findFormAttributeByName(form, parentAttributeName);
        if (parent == null)
        {
            throw new IllegalStateException("FormAttribute not found: " + parentAttributeName); //$NON-NLS-1$
        }

        // Idempotency: check existing columns. Also track the max existing column
        // id - column ids are LOCAL to the attribute (1, 2, 3 ...), not form-global.
        Object columns;
        try
        {
            columns = parent.getClass().getMethod("getColumns").invoke(parent); //$NON-NLS-1$
        }
        catch (NoSuchMethodException e)
        {
            throw new UnsupportedOperationException(
                "formApiNotFound: parent FormAttribute has no getColumns() - " //$NON-NLS-1$
                    + "is the parent of type Table?"); //$NON-NLS-1$
        }
        int size = (Integer) columns.getClass().getMethod("size").invoke(columns); //$NON-NLS-1$
        int maxColId = 0;
        for (int i = 0; i < size; i++)
        {
            Object existing = columns.getClass().getMethod("get", Integer.TYPE).invoke(columns, i); //$NON-NLS-1$
            try
            {
                String existingName = (String) namedIface.getMethod("getName").invoke(existing); //$NON-NLS-1$
                if (name.equalsIgnoreCase(existingName))
                {
                    return "addFormAttributeColumn idempotent: column '" + name //$NON-NLS-1$
                        + "' already exists in '" + parentAttributeName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            catch (Exception ignored)
            {
                // skip unnamed
            }
            try
            {
                Object cid = existing.getClass().getMethod("getId").invoke(existing); //$NON-NLS-1$
                if (cid instanceof Integer && (Integer) cid > maxColId)
                {
                    maxColId = (Integer) cid;
                }
            }
            catch (Exception ignored)
            {
                // column may not expose getId on this EDT version
            }
        }

        Object column = probeFactoryCreate("createFormAttributeColumn"); //$NON-NLS-1$
        if (column == null)
        {
            throw new UnsupportedOperationException(
                "formApiNotFound: createFormAttributeColumn (tried FormFactory, MdClassFactory)"); //$NON-NLS-1$
        }
        namedIface.getMethod("setName", String.class).invoke(column, name); //$NON-NLS-1$
        // 1.43.x C4: every column needs a distinct <id> - without it the platform
        // renders the first column's header in every column. Ids are attribute-local.
        try
        {
            column.getClass().getMethod("setId", Integer.TYPE).invoke(column, maxColId + 1); //$NON-NLS-1$
        }
        catch (Exception idEx)
        {
            Activator.logWarning("addFormAttributeColumn: setId not applied: " + idEx.getMessage()); //$NON-NLS-1$
        }
        // A ValueTable column carries the same view/edit=Common(true) use flags
        // as a form attribute - every stock column has <view>/<edit>, and without
        // them a column (e.g. a Boolean checkbox) is not editable at runtime.
        // Best-effort: setAdjustableCommon silently skips when the setter is absent.
        setAdjustableCommon(column, "setView"); //$NON-NLS-1$
        setAdjustableCommon(column, "setEdit"); //$NON-NLS-1$
        if (title != null && !title.isEmpty())
        {
            try
            {
                setTitle(column, title);
            }
            catch (Exception ignored)
            {
                // column may not implement Titled in every EDT version
            }
        }
        if (dataPath != null && !dataPath.isEmpty())
        {
            try
            {
                setDataPath(column, dataPath);
            }
            catch (Exception ignored)
            {
                // column may not be a DataItem
            }
        }
        // 1.43.x: apply the column's valueType so it is not typeless (a ValueTable
        // column with no type is a MAJOR md-legacy-emf-check, like a typeless attr).
        // FormAttributeColumn exposes a valueType TypeDescription - the same machinery
        // that types form attributes (setFormAttributeTypes) works here.
        String typeApplyNote = ""; //$NON-NLS-1$
        if (type != null && !type.isEmpty() && config != null)
        {
            try
            {
                BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setFormAttributeTypes(
                    column, project, config,
                    java.util.Collections.singletonList(type), qualifiers);
                typeApplyNote = tr.ok ? " (type " + type + ")" //$NON-NLS-1$ //$NON-NLS-2$
                    : " (type NOT applied: " + tr.error + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Exception typeEx)
            {
                typeApplyNote = " (type threw: " + typeEx.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                Activator.logWarning("addFormAttributeColumn: type='" + type //$NON-NLS-1$
                    + "' threw: " + typeEx.getMessage()); //$NON-NLS-1$
            }
        }
        else if (type != null && !type.isEmpty())
        {
            // config unavailable - surface it rather than silently dropping the type.
            typeApplyNote = " (type '" + type + "' NOT applied: configuration unavailable)"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        columns.getClass().getMethod("add", Object.class).invoke(columns, column); //$NON-NLS-1$
        return "added column '" + name + "' (id " + (maxColId + 1) + ") to FormAttribute '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + parentAttributeName + "'" + typeApplyNote; //$NON-NLS-1$
    }

    /**
     * 1.41: creates a FormAttribute of type DynamicList plus a UI Table item
     * bound to it. Sets wizard booleans on the dynamic-list ExtInfo
     * (autoFillAvailableFields, autoSaveCustomization, dynamicDataRead,
     * customQuery=false). When a
     * {@code mainTable} FQN is given, binds the list to that table via
     * {@code DynamicListExtInfo.setMainTable} - resolved as the target object's
     * derived {@code dbViewDefs.getMainView()} (1.43.x M1, the same path EDT's
     * PropertyInfoManager uses). That binding works only for an already-existing,
     * indexed object; for one whose db-view is not yet computed it is skipped with
     * {@link DynamicListResult#mainTableNote} (never fatal).
     * <p>
     * The caller is responsible for adding the result Table to a container
     * via {@link #addToContainer}; this method only returns it through the
     * {@link DynamicListResult} struct.
     *
     * @param transaction the active BM transaction (resolves mainTable by FQN)
     */
    public DynamicListResult addDynamicListAttributeAndTable(Object form, Object transaction,
        String attributeName, String tableName, String mainTable, String title) throws Exception
    {
        if (attributeName == null || attributeName.isEmpty())
        {
            throw new IllegalArgumentException("attributeName is required"); //$NON-NLS-1$
        }
        if (tableName == null || tableName.isEmpty())
        {
            throw new IllegalArgumentException("tableName is required"); //$NON-NLS-1$
        }
        // Idempotency: existing attribute with the same name
        Object existing = findFormAttributeByName(form, attributeName);
        if (existing != null)
        {
            DynamicListResult r = new DynamicListResult();
            r.attribute = existing;
            r.idempotent = true;
            r.message = "addDynamicListTable idempotent: FormAttribute '" //$NON-NLS-1$
                + attributeName + "' already exists"; //$NON-NLS-1$
            return r;
        }

        // Share one id space across the new attribute and the bound table so
        // neither collides with an existing element (the form id counter is
        // global - items, attributes and commands all draw from it).
        resetIdCounter(form);

        Object attribute = createFormAttribute(attributeName, title);
        // A DynamicList FormAttribute needs an id plus view/edit=Common(true);
        // without them the attribute creates no form variable
        // ("Переменная X не определена") and the bound table renders empty.
        applyFormAttributeIdAndUse(attribute, nextId());

        Object extInfo = probeFactoryCreate(
            "createDynamicListExtInfo", //$NON-NLS-1$
            "createDynamicListAttributeExtInfo"); //$NON-NLS-1$
        if (extInfo == null)
        {
            throw new UnsupportedOperationException(
                "formApiNotFound: createDynamicListExtInfo " //$NON-NLS-1$
                    + "(tried FormFactory and MdClassFactory)"); //$NON-NLS-1$
        }
        invokeSingleParamSetter(attribute, "setExtInfo", extInfo); //$NON-NLS-1$

        // Wizard default booleans (best-effort, ignore individual failures).
        // autoFillAvailableFields=true: the EDT wizard always sets it on a
        // table-bound (customQuery=false) dynamic list so the platform auto-exposes
        // the main table's fields. Without it a freshly generated list exposes no
        // available fields and the platform fails at runtime with
        // "Неверный путь к данным: <Список>.<Поле>". (Hand-curated customQuery lists
        // may legitimately omit it - confirmed on ZUP demo - but the list generated
        // here is table-bound, so true is the correct default.)
        // customQuery=false: a table-bound (mainTable) list is not a custom-query
        // list. The mainTable itself is wired after this loop (needs a DbViewDef).
        for (String[] booleanProp : new String[][] {
            { "setAutoFillAvailableFields", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "setAutoSaveCustomization", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "setDynamicDataRead", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "setCustomQuery", "false" } //$NON-NLS-1$ //$NON-NLS-2$
        })
        {
            try
            {
                Method setter = null;
                for (Method m : extInfo.getClass().getMethods())
                {
                    if (m.getName().equals(booleanProp[0]) && m.getParameterCount() == 1
                        && (m.getParameterTypes()[0] == Boolean.TYPE
                            || m.getParameterTypes()[0] == Boolean.class))
                    {
                        setter = m;
                        break;
                    }
                }
                if (setter != null)
                {
                    setter.invoke(extInfo, Boolean.parseBoolean(booleanProp[1]));
                }
            }
            catch (Exception ignored)
            {
                // best-effort
            }
        }

        // Bind the dynamic list to a metadata table (ОсновнаяТаблица) when a
        // mainTable FQN is given. DynamicListExtInfo.setMainTable takes a DbViewDef
        // (derived data), resolved as the target object's dbViewDefs.getMainView() -
        // the same path EDT's PropertyInfoManager.updateDbViewElement uses. Works
        // only for an ALREADY-EXISTING, indexed object; for one created in this same
        // uncommitted transaction the derived data is null -> skip with a note (the
        // customQuery path stays available). Best-effort: never fails the op.
        boolean mainTableBound = false;
        String mainTableNote = null;
        if (mainTable != null && !mainTable.isEmpty())
        {
            try
            {
                Object mdObject = txIface.getMethod("getTopObjectByFqn", String.class) //$NON-NLS-1$
                    .invoke(transaction, mainTable);
                if (!(mdObject instanceof org.eclipse.emf.ecore.EObject))
                {
                    // null (not found) or a non-EObject (defensive) - same outcome.
                    mainTableNote = "mainTable '" + mainTable //$NON-NLS-1$
                        + "' not found - skipped (check the FQN; English form, e.g. Catalog.X)"; //$NON-NLS-1$
                }
                else
                {
                    // Typed EMF (not reflective) for eClass / getEStructuralFeature /
                    // eGet - avoids the OSGi split-package classloader trap that a
                    // Class.forName("...EStructuralFeature") + getMethod("eGet", ...)
                    // would risk. org.eclipse.emf.ecore is in this bundle's imports.
                    org.eclipse.emf.ecore.EObject mdEObject = (org.eclipse.emf.ecore.EObject) mdObject;
                    org.eclipse.emf.ecore.EStructuralFeature dbViewFeature =
                        mdEObject.eClass().getEStructuralFeature("dbViewDefs"); //$NON-NLS-1$
                    if (dbViewFeature == null)
                    {
                        mainTableNote = "mainTable '" + mainTable //$NON-NLS-1$
                            + "' has no db-view (this type cannot back a dynamic list) - skipped"; //$NON-NLS-1$
                    }
                    else
                    {
                        Object dbViewDefs = mdEObject.eGet(dbViewFeature);
                        if (dbViewDefs == null)
                        {
                            mainTableNote = "mainTable '" + mainTable //$NON-NLS-1$
                                + "' db-view not yet computed (object newly created or project not" //$NON-NLS-1$
                                + " fully indexed) - skipped; bind via the EDT editor or retry after" //$NON-NLS-1$
                                + " indexing"; //$NON-NLS-1$
                        }
                        else
                        {
                            // getMainView() is a zero-arg getter on the EDT-internal
                            // BasicDbViewDefs (not in our imports) - reflective, but no
                            // argument-type lookup so no classloader conflict possible.
                            Object mainView = dbViewDefs.getClass()
                                .getMethod("getMainView").invoke(dbViewDefs); //$NON-NLS-1$
                            if (mainView == null)
                            {
                                mainTableNote = "mainTable '" + mainTable //$NON-NLS-1$
                                    + "' main view unavailable - skipped"; //$NON-NLS-1$
                            }
                            else
                            {
                                invokeSingleParamSetter(extInfo, "setMainTable", mainView); //$NON-NLS-1$
                                mainTableBound = true;
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                String em = e.getMessage();
                if (em != null && em.length() > 200)
                {
                    em = em.substring(0, 200); // keep MCP responses bounded
                }
                mainTableNote = "mainTable '" + mainTable + "' binding failed: " //$NON-NLS-1$ //$NON-NLS-2$
                    + (em != null ? em : e.getClass().getSimpleName());
            }
        }

        addAttributeToForm(form, attribute);

        // Create the bound UI Table (draws its id from the same counter)
        Object table = createTable(tableName, title);
        try
        {
            setDataPath(table, attributeName);
        }
        catch (Exception ignored)
        {
            // best-effort: dataPath wiring may need explicit setup later
        }

        DynamicListResult r = new DynamicListResult();
        r.attribute = attribute;
        r.table = table;
        r.idempotent = false;
        r.mainTableBound = mainTableBound;
        r.mainTableNote = mainTableNote;
        r.message = "added DynamicList FormAttribute '" + attributeName //$NON-NLS-1$
            + "' and UI Table '" + tableName + "'" //$NON-NLS-1$ //$NON-NLS-2$
            + (mainTableBound ? " bound to mainTable " + mainTable //$NON-NLS-1$
                : (mainTable != null && !mainTable.isEmpty()
                    ? " (mainTable NOT bound - see mainTableNote)" : "")); //$NON-NLS-1$ //$NON-NLS-2$
        return r;
    }

    /**
     * 1.41: result of {@link #addDynamicListAttributeAndTable}.
     */
    public static final class DynamicListResult
    {
        public Object attribute;
        public Object table;
        public boolean idempotent;
        public String message;
        /** Non-null when applying the DynamicList valueType failed (caller surfaces it). */
        public String typeNote;
        /** True when a requested mainTable was resolved and setMainTable applied. */
        public boolean mainTableBound;
        /** Non-null when a requested mainTable could not be bound (the reason). */
        public String mainTableNote;
    }

    /**
     * 1.41: creates a FormAttribute of type DataCompositionSettingsComposer
     * plus two UI tables (Settings + UserSettings) wired via dataPath.
     * Returns the composer attribute, both tables, and BSL initialization
     * snippets in both RU and EN dialects.
     */
    public SettingsComposerResult setupSettingsComposer(Object form, String composerName,
        String settingsTableName, String userSettingsTableName) throws Exception
    {
        if (composerName == null || composerName.isEmpty())
        {
            composerName = "Composer"; //$NON-NLS-1$
        }
        if (settingsTableName == null || settingsTableName.isEmpty())
        {
            settingsTableName = "SettingsTable"; //$NON-NLS-1$
        }
        if (userSettingsTableName == null || userSettingsTableName.isEmpty())
        {
            userSettingsTableName = "UserSettingsTable"; //$NON-NLS-1$
        }

        // Idempotency
        Object existing = findFormAttributeByName(form, composerName);
        if (existing != null)
        {
            SettingsComposerResult r = new SettingsComposerResult();
            r.composer = existing;
            r.idempotent = true;
            r.message = "setupSettingsComposerOnForm idempotent: FormAttribute '" //$NON-NLS-1$
                + composerName + "' already exists"; //$NON-NLS-1$
            populateSettingsComposerSnippets(r, composerName);
            return r;
        }

        Object composer = createFormAttribute(composerName, null);

        Object extInfo = probeFactoryCreate(
            "createDataCompositionSettingsComposerExtInfo", //$NON-NLS-1$
            "createSettingsComposerExtInfo"); //$NON-NLS-1$
        if (extInfo == null)
        {
            throw new UnsupportedOperationException(
                "formApiNotFound: createDataCompositionSettingsComposerExtInfo " //$NON-NLS-1$
                    + "(SettingsComposer ExtInfo factory not exposed)"); //$NON-NLS-1$
        }
        invokeSingleParamSetter(composer, "setExtInfo", extInfo); //$NON-NLS-1$
        addAttributeToForm(form, composer);

        Object settingsTable = createTable(settingsTableName, null);
        try
        {
            setDataPath(settingsTable, composerName + ".Settings"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        Object userSettingsTable = createTable(userSettingsTableName, null);
        try
        {
            setDataPath(userSettingsTable, composerName + ".UserSettings"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // best-effort
        }

        SettingsComposerResult r = new SettingsComposerResult();
        r.composer = composer;
        r.settingsTable = settingsTable;
        r.userSettingsTable = userSettingsTable;
        r.idempotent = false;
        populateSettingsComposerSnippets(r, composerName);
        r.message = "setupSettingsComposerOnForm: created '" + composerName //$NON-NLS-1$
            + "' + UI tables '" + settingsTableName + "', '" + userSettingsTableName + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return r;
    }

    /**
     * 1.41: fills RU and EN BSL snippets on the result struct so both
     * idempotent and non-idempotent paths surface the example to the AI.
     */
    private void populateSettingsComposerSnippets(SettingsComposerResult r, String composerName)
    {
        r.bslSnippetRu = "// 1.41: paste into ProcedureOnCreateAtServer (ru)\n" //$NON-NLS-1$
            + composerName + ".Инициализировать(" //$NON-NLS-1$ // Инициализировать
            + "Новый ИсточникДоступныхНастроекКомпоновкиДанных(СхемаКД));\n" //$NON-NLS-1$
            + composerName + ".ЗагрузитьНастройки(СхемаКД.НастройкиПоУмолчанию);"; //$NON-NLS-1$
        r.bslSnippetEn = "// 1.41: paste into ProcedureOnCreateAtServer (en)\n" //$NON-NLS-1$
            + composerName + ".Initialize(New DataCompositionAvailableSettingsSource(Schema));\n" //$NON-NLS-1$
            + composerName + ".LoadSettings(Schema.DefaultSettings);"; //$NON-NLS-1$
    }

    /**
     * 1.41: result of {@link #setupSettingsComposer}.
     */
    public static final class SettingsComposerResult
    {
        public Object composer;
        public Object settingsTable;
        public Object userSettingsTable;
        public boolean idempotent;
        public String message;
        public String bslSnippetRu;
        public String bslSnippetEn;
    }

    /**
     * Sets a property on the form item identified by {@code itemName} inside
     * {@code container} (form or sub-group). Uses reflection on
     * {@code setXxx(...)} setters with best-effort coercion for booleans
     * and the title pseudo-property (delegates to {@link #setTitle}).
     * <p>
     * Supported property names (case-sensitive against EMF setters):
     * {@code title}, {@code visible}, {@code enabled}, {@code readOnly},
     * {@code dataPath}, plus any EMF feature exposed as {@code setXxx}.
     *
     * @return {@code null} on success, an error description otherwise
     */
    public String setItemProperty(Object container, String itemName, String property, String value)
        throws Exception
    {
        Object item = findItemByName(container, itemName);
        if (item == null)
        {
            return "No form item named: " + itemName; //$NON-NLS-1$
        }
        if (property == null || property.isEmpty())
        {
            return "property is required"; //$NON-NLS-1$
        }
        // Pseudo-property: title -> setTitle (handles the localized map)
        if ("title".equalsIgnoreCase(property)) //$NON-NLS-1$
        {
            try
            {
                setTitle(item, value);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set title: " + e.getMessage(); //$NON-NLS-1$
            }
        }
        if ("dataPath".equalsIgnoreCase(property)) //$NON-NLS-1$
        {
            try
            {
                setDataPath(item, value);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set dataPath: " + e.getMessage(); //$NON-NLS-1$
            }
        }
        // Data-path-valued properties take an AbstractDataPath, not a scalar
        // string: footerDataPath / rowPictureDataPath live on a FormField item,
        // while headerDataPath / titleDataPath / multipleValue*DataPath live on a
        // group's or field's extInfo. setScalarProperty cannot coerce a dotted
        // string into an AbstractDataPath, so these were unsettable via MCP (they
        // fell through to the scalar path and failed with a type mismatch). Detect
        // the AbstractDataPath setter reflectively - on the item first, then on its
        // extInfo, mirroring the scalar item-then-extInfo fallback below - and
        // build the path the same way setDataPath does.
        Method itemDataPathSetter = findDataPathSetter(item, property);
        if (itemDataPathSetter != null)
        {
            try
            {
                setDataPathProperty(item, itemDataPathSetter, value);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set " + property + ": " + e.getMessage(); //$NON-NLS-1$
            }
        }
        Object dataPathExtInfo = tryGetExtInfo(item);
        if (dataPathExtInfo != null)
        {
            Method extDataPathSetter = findDataPathSetter(dataPathExtInfo, property);
            if (extDataPathSetter != null)
            {
                try
                {
                    setDataPathProperty(dataPathExtInfo, extDataPathSetter, value);
                    return null;
                }
                catch (Exception e)
                {
                    return "Failed to set " + property + ": " + e.getMessage(); //$NON-NLS-1$
                }
            }
        }
        // Prefer a setter on the item itself; fall back to the item's extInfo.
        // Many group / field properties (a UsualGroup's behavior / collapsed /
        // representation / childrenAlign, an input field's chooseType, ...) live
        // on the item's extInfo (UsualGroupExtInfo / InputFieldExtInfo / ...),
        // NOT on the FormGroup / FormField item. Without this fallback,
        // set_property behavior=Collapsible reported "not found on
        // UsualGroupImpl" and the only workaround was a manual Form.form edit.
        // Priority: the item-level setter ALWAYS wins; if a property of the same
        // name exists on both the item and its extInfo, the item one is used
        // (extInfo is reached only when the item itself has no such setter).
        if (hasSingleArgSetter(item, property))
        {
            return setScalarProperty(item, property, value);
        }
        Object itemExtInfo = tryGetExtInfo(item);
        if (itemExtInfo != null && hasSingleArgSetter(itemExtInfo, property))
        {
            return setScalarProperty(itemExtInfo, property, value);
        }
        // Neither the item nor its extInfo has the setter - let
        // setScalarProperty produce the canonical "not found" message against
        // the item (keeps the error consistent with the pre-fallback behaviour).
        return setScalarProperty(item, property, value);
    }

    /**
     * Returns the item's {@code extInfo} EObject when it exposes a non-null
     * {@code getExtInfo()} accessor, else {@code null}. Used by
     * {@link #setItemProperty} to reach group / field properties that live on
     * the extInfo rather than the item itself.
     */
    private static Object tryGetExtInfo(Object item)
    {
        try
        {
            return item.getClass().getMethod("getExtInfo").invoke(item); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Whether {@code obj} exposes a single-argument {@code set<Property>} setter
     * (the shape {@link #setScalarProperty} drives). Lets {@link #setItemProperty}
     * decide between the item and its extInfo before attempting a write.
     */
    private static boolean hasSingleArgSetter(Object obj, String property)
    {
        if (property == null || property.isEmpty())
        {
            return false;
        }
        String setter = "set" + Character.toUpperCase(property.charAt(0)) //$NON-NLS-1$
            + property.substring(1);
        for (Method m : obj.getClass().getMethods())
        {
            if (setter.equals(m.getName()) && m.getParameterCount() == 1)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 1.43.x: sets a form FIELD's {@code choiceParameters} (fixed choice-filter
     * values, e.g. {@code Отбор.ЭтоГруппа=false} for "only groups") on the field's
     * extInfo (InputFieldExtInfo). Mirror of the attribute-side choiceParameters
     * (closes the one RSV 4.7 form gap); reuses
     * {@link BmDefinedTypeHelper#applyChoiceParameters} which infers each value's
     * type from the string, so no bound-attribute type lookup is needed.
     *
     * <p>{@code choiceParameterLinks} are intentionally NOT handled here: a form
     * field's link resolves against form items, not a metadata FieldSource, so the
     * attribute-side resolver does not apply.
     *
     * @return {@code null} on success, or a plain error message (the caller throws
     *     it to roll the form transaction back)
     */
    public String applyFieldChoiceParameters(Object form, String itemName,
        List<java.util.Map<String, String>> items, List<String> applied, List<String> diag)
        throws Exception
    {
        Object item = findItemByName(form, itemName);
        if (item == null)
        {
            return "form item not found: " + itemName; //$NON-NLS-1$
        }
        Object extInfo;
        try
        {
            extInfo = item.getClass().getMethod("getExtInfo").invoke(item); //$NON-NLS-1$
        }
        catch (NoSuchMethodException nsme)
        {
            return "item '" + itemName //$NON-NLS-1$
                + "' is not a field with extInfo (choiceParameters apply to input fields)"; //$NON-NLS-1$
        }
        if (!(extInfo instanceof org.eclipse.emf.ecore.EObject))
        {
            return "field '" + itemName //$NON-NLS-1$
                + "' has no extInfo - bind it to a reference/choice attribute first"; //$NON-NLS-1$
        }
        return BmDefinedTypeHelper.applyChoiceParameters(
            (org.eclipse.emf.ecore.EObject) extInfo, items, applied, diag);
    }

    /**
     * 1.43.x: reflectively sets a single-arg scalar property (boolean / int / enum /
     * String) on a form item by setter name, coercing the string value to the
     * setter's parameter type via {@link #coerceFormValue}. Returns {@code null} on
     * success, an error description otherwise (setter absent / coercion failed).
     */
    String setScalarProperty(Object item, String property, String value)
    {
        String setter = "set" + Character.toUpperCase(property.charAt(0)) //$NON-NLS-1$
            + property.substring(1);
        for (Method m : item.getClass().getMethods())
        {
            if (!setter.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            Class<?> paramType = m.getParameterTypes()[0];
            try
            {
                Object converted = coerceFormValue(value, paramType);
                m.invoke(item, converted);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set " + property + ": " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return "Property '" + property + "' is absent on " //$NON-NLS-1$ //$NON-NLS-2$
            + item.getClass().getSimpleName();
    }

    /**
     * Best-effort coercion to a setter's parameter type: boolean, int, enum,
     * String. Other types are returned as-is and may throw downstream.
     */
    private static Object coerceFormValue(String value, Class<?> targetType)
    {
        if (value == null || targetType == String.class)
        {
            return value;
        }
        if (targetType == boolean.class || targetType == Boolean.class)
        {
            return Boolean.valueOf(value);
        }
        if (targetType == int.class || targetType == Integer.class)
        {
            return Integer.valueOf(value);
        }
        if (targetType == long.class || targetType == Long.class)
        {
            return Long.valueOf(value);
        }
        if (targetType.isEnum())
        {
            try
            {
                Method get = targetType.getMethod("get", String.class); //$NON-NLS-1$
                Object r = get.invoke(null, value);
                if (r != null)
                {
                    return r;
                }
            }
            catch (Exception ignored)
            {
                // fall through
            }
            try
            {
                Method getByName = targetType.getMethod("getByName", String.class); //$NON-NLS-1$
                Object r = getByName.invoke(null, value);
                if (r != null)
                {
                    return r;
                }
            }
            catch (Exception ignored)
            {
                // fall through
            }
            // Constant scan as the final fallback.
            for (Object c : targetType.getEnumConstants())
            {
                if (c.toString().equalsIgnoreCase(value)
                    || ((Enum<?>) c).name().equalsIgnoreCase(value))
                {
                    return c;
                }
            }
            throw new IllegalArgumentException("Unknown enum value '" + value //$NON-NLS-1$
                + "' for type " + targetType.getSimpleName()); //$NON-NLS-1$
        }
        return value;
    }

    // -----------------------------------------------------------------------
    // Property setters
    // -----------------------------------------------------------------------

    /**
     * Sets name and ID on a form item.
     *
     * @param item the form item
     * @param name the name to set
     * @param id the ID to set
     * @throws Exception if setting fails
     */
    public void setBasicProperties(Object item, String name, int id) throws Exception
    {
        namedIface.getMethod("setName", String.class).invoke(item, name); //$NON-NLS-1$
        formItemIface.getMethod("setId", Integer.TYPE).invoke(item, id); //$NON-NLS-1$
    }

    /**
     * Sets the title on a titled element via {@code getTitle().put("ru", title)}.
     *
     * @param item the titled element
     * @param title the title text
     * @throws Exception if setting fails
     */
    public void setTitle(Object item, String title) throws Exception
    {
        if (title == null || title.isEmpty())
        {
            return;
        }
        Object titleMap = titledIface.getMethod("getTitle").invoke(item); //$NON-NLS-1$
        titleMap.getClass().getMethod("put", Object.class, Object.class) //$NON-NLS-1$
            .invoke(titleMap, "ru", title); //$NON-NLS-1$
    }

    /**
     * Sets visibility and enabled state on a visible element.
     * Sets {@code visible=true}, {@code enabled=true},
     * {@code userVisible=AdjustableBoolean(common=true)}.
     *
     * @param item the visible element
     * @throws Exception if setting fails
     */
    public void setVisibility(Object item) throws Exception
    {
        visibleIface.getMethod("setVisible", Boolean.TYPE).invoke(item, true); //$NON-NLS-1$
        visibleIface.getMethod("setEnabled", Boolean.TYPE).invoke(item, true); //$NON-NLS-1$

        Object adjBool = mdFactoryClass.getMethod("createAdjustableBoolean").invoke(mdFactory); //$NON-NLS-1$
        adjBoolClass.getMethod("setCommon", Boolean.TYPE).invoke(adjBool, true); //$NON-NLS-1$
        visibleIface.getMethod("setUserVisible", adjBoolClass).invoke(item, adjBool); //$NON-NLS-1$
    }

    /**
     * Creates and attaches an extended tooltip to a form element.
     * Required for every form element in EDT.
     *
     * @param parent the parent element
     * @param name tooltip name
     * @param id tooltip ID
     * @throws Exception if creation fails
     */
    public void createExtendedTooltip(Object parent, String name, int id) throws Exception
    {
        Object tooltip = ffClass.getMethod("createExtendedTooltip").invoke(formFactory); //$NON-NLS-1$
        namedIface.getMethod("setName", String.class).invoke(tooltip, name); //$NON-NLS-1$
        formItemIface.getMethod("setId", Integer.TYPE).invoke(tooltip, id); //$NON-NLS-1$

        // Set type to LABEL (ordinal 1)
        Class<?> decoClass = Class.forName("com._1c.g5.v8.dt.form.model.Decoration"); //$NON-NLS-1$
        Class<?> decoTypeClass = Class.forName("com._1c.g5.v8.dt.form.model.ManagedFormDecorationType"); //$NON-NLS-1$
        Method getMethod = decoTypeClass.getMethod("get", Integer.TYPE); //$NON-NLS-1$
        Object labelType = getMethod.invoke(null, 1);
        decoClass.getMethod("setType", decoTypeClass).invoke(tooltip, labelType); //$NON-NLS-1$

        decoClass.getMethod("setAutoMaxWidth", Boolean.TYPE).invoke(tooltip, true); //$NON-NLS-1$
        decoClass.getMethod("setAutoMaxHeight", Boolean.TYPE).invoke(tooltip, true); //$NON-NLS-1$

        // Create LabelDecorationExtInfo with horizontalAlign=Left
        Object extInfo = ffClass.getMethod("createLabelDecorationExtInfo").invoke(formFactory); //$NON-NLS-1$
        try
        {
            Class<?> labelExtClass = Class.forName("com._1c.g5.v8.dt.form.model.LabelDecorationExtInfo"); //$NON-NLS-1$
            Class<?> hAlignClass = Class.forName("com._1c.g5.v8.dt.form.model.ItemHorizontalAlignment"); //$NON-NLS-1$
            for (Object constant : hAlignClass.getEnumConstants())
            {
                if ("LEFT".equals(constant.toString())) //$NON-NLS-1$
                {
                    labelExtClass.getMethod("setHorizontalAlign", hAlignClass) //$NON-NLS-1$
                        .invoke(extInfo, constant);
                    break;
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to set horizontalAlign on tooltip: " + e.getMessage()); //$NON-NLS-1$
        }

        Class<?> decoExtInfoClass = Class.forName("com._1c.g5.v8.dt.form.model.DecorationExtInfo"); //$NON-NLS-1$
        decoClass.getMethod("setExtInfo", decoExtInfoClass).invoke(tooltip, extInfo); //$NON-NLS-1$

        Class<?> extTooltipClass = Class.forName("com._1c.g5.v8.dt.form.model.ExtendedTooltip"); //$NON-NLS-1$
        extTooltipHolderIface.getMethod("setExtendedTooltip", extTooltipClass) //$NON-NLS-1$
            .invoke(parent, tooltip);
    }

    /**
     * Sets the data path binding on a form item.
     *
     * @param item the form item (must implement DataItem)
     * @param dataPath the data path string (e.g. "Object.Name"), segments split by "."
     * @throws Exception if setting fails
     */
    public void setDataPath(Object item, String dataPath) throws Exception
    {
        if (dataPath == null || dataPath.isEmpty())
        {
            return;
        }

        Object pathObj = ffClass.getMethod("createDataPath").invoke(formFactory); //$NON-NLS-1$
        Class<?> abstractDataPathClass = Class.forName("com._1c.g5.v8.dt.form.model.AbstractDataPath"); //$NON-NLS-1$
        Object segments = abstractDataPathClass.getMethod("getSegments").invoke(pathObj); //$NON-NLS-1$

        for (String segment : dataPath.split("\\.")) //$NON-NLS-1$
        {
            segments.getClass().getMethod("add", Object.class).invoke(segments, segment); //$NON-NLS-1$
        }

        Class<?> dataItemClass = Class.forName("com._1c.g5.v8.dt.form.model.DataItem"); //$NON-NLS-1$
        dataItemClass.getMethod("setDataPath", abstractDataPathClass).invoke(item, pathObj); //$NON-NLS-1$
    }

    /**
     * Returns the {@code set<Property>(AbstractDataPath)} setter on {@code item}
     * when the property is data-path-valued (its single setter argument is
     * exactly {@code AbstractDataPath}), else {@code null}. Lets
     * {@link #setItemProperty} recognise data-path properties such as
     * {@code footerDataPath} / {@code headerDataPath} generically instead of
     * hard-coding each name.
     *
     * @param item the form item, or its extInfo for extInfo-hosted data-path
     *            properties (headerDataPath / titleDataPath / multipleValue*DataPath)
     * @param property the property name (camelCase, e.g. {@code footerDataPath})
     * @return the matching setter, or {@code null} when none takes an
     *         AbstractDataPath
     */
    private static Method findDataPathSetter(Object item, String property)
    {
        if (property == null || property.isEmpty())
        {
            return null;
        }
        String setter = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1); //$NON-NLS-1$
        try
        {
            Class<?> abstractDataPathClass =
                Class.forName("com._1c.g5.v8.dt.form.model.AbstractDataPath"); //$NON-NLS-1$
            for (Method m : item.getClass().getMethods())
            {
                if (m.getName().equals(setter) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].equals(abstractDataPathClass))
                {
                    return m;
                }
            }
        }
        catch (ClassNotFoundException e)
        {
            // Form model not on the classpath - impossible inside the EDT runtime.
        }
        return null;
    }

    /**
     * Sets a data-path-valued property (footerDataPath, headerDataPath, ...) on a
     * form item. Builds an {@code AbstractDataPath} from the dotted string exactly
     * like {@link #setDataPath} and invokes the discovered setter. An empty value
     * clears the path (sets {@code null}).
     *
     * @param item the form item or its extInfo (whichever owns the setter)
     * @param setter the {@code set<Property>(AbstractDataPath)} setter from
     *            {@link #findDataPathSetter}
     * @param value the dotted data path (e.g. "Object.Items.Sum"); empty clears it
     * @throws Exception if building or setting the path fails
     */
    private void setDataPathProperty(Object item, Method setter, String value) throws Exception
    {
        Class<?> abstractDataPathClass = Class.forName("com._1c.g5.v8.dt.form.model.AbstractDataPath"); //$NON-NLS-1$
        if (value == null || value.isEmpty())
        {
            setter.invoke(item, (Object)null);
            return;
        }
        Object pathObj = ffClass.getMethod("createDataPath").invoke(formFactory); //$NON-NLS-1$
        Object segments = abstractDataPathClass.getMethod("getSegments").invoke(pathObj); //$NON-NLS-1$
        for (String segment : value.split("\\.")) //$NON-NLS-1$
        {
            segments.getClass().getMethod("add", Object.class).invoke(segments, segment); //$NON-NLS-1$
        }
        setter.invoke(item, pathObj);
    }

    // -----------------------------------------------------------------------
    // Container operations
    // -----------------------------------------------------------------------

    /**
     * Adds an item to a container's items list.
     *
     * @param container the container (form or group)
     * @param item the item to add
     * @throws Exception if adding fails
     */
    public void addToContainer(Object container, Object item) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        items.getClass().getMethod("add", Object.class).invoke(items, item); //$NON-NLS-1$
    }

    /**
     * Adds an item to a container before the element with the specified name.
     * If the element is not found, adds to the end.
     *
     * @param container the container
     * @param item the item to add
     * @param beforeName the name of the element to insert before
     * @throws Exception if adding fails
     */
    public void addToContainerBefore(Object container, Object item, String beforeName) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer) items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$
        int insertIndex = size; // Default: end of list

        for (int i = 0; i < size; i++)
        {
            Object existing = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            try
            {
                String existingName = (String) namedIface.getMethod("getName").invoke(existing); //$NON-NLS-1$
                if (beforeName.equals(existingName))
                {
                    insertIndex = i;
                    break;
                }
            }
            catch (Exception e)
            {
                // Element may not be named, skip
            }
        }

        items.getClass().getMethod("add", Integer.TYPE, Object.class) //$NON-NLS-1$
            .invoke(items, insertIndex, item);
    }

    /**
     * Moves an existing form item into another container on the same form,
     * optionally before a named sibling. The item keeps its identity, id, data
     * path, title and every other property - only its parent changes.
     * <p>
     * Moving to a different container is one list insertion: a containment list
     * takes the item away from whatever container currently holds it, including
     * an auto command bar or a context menu. Naming the container the item is
     * already in reorders it there instead, which the list has to do itself -
     * see {@link #reorderWithin}. On top of that come the refusals: moving an
     * item into itself or into one of its own children would detach a subtree
     * from the form, and a target that holds no items at all cannot receive one.
     *
     * @param form       the form owning both the item and the target container
     * @param itemName   the name of the item to move
     * @param targetName the name of the destination container, or {@code null} /
     *                       empty to move the item to the form root
     * @param beforeName the name of the sibling to insert before, or {@code null}
     *                       to append at the end of the destination
     * @return a short description of what moved where
     * @throws Exception if the model rejects the insertion
     */
    public String moveItemToContainer(Object form, String itemName, String targetName,
        String beforeName) throws Exception
    {
        Object item = findItemByName(form, itemName);
        if (item == null)
        {
            throw new RuntimeException("Form item not found: " + itemName); //$NON-NLS-1$
        }
        boolean toRoot = targetName == null || targetName.isEmpty();
        Object target = toRoot ? form : findItemByName(form, targetName);
        if (target == null)
        {
            throw new RuntimeException("Target container not found: " + targetName); //$NON-NLS-1$
        }
        if (target == item)
        {
            throw new RuntimeException("Cannot move '" + itemName + "' into itself."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (isInsideOf(target, item))
        {
            throw new RuntimeException("Cannot move '" + itemName + "' into '" + targetName //$NON-NLS-1$ //$NON-NLS-2$
                + "': the target is inside the item being moved."); //$NON-NLS-1$
        }
        if (!containerIface.isInstance(target))
        {
            throw new RuntimeException("'" + targetName + "' holds no items, so it cannot " //$NON-NLS-1$ //$NON-NLS-2$
                + "receive one. Group, page, command bar and table can; a field cannot."); //$NON-NLS-1$
        }
        String from = describeParentOf(item);
        Object items = containerIface.getMethod("getItems").invoke(target); //$NON-NLS-1$
        int currentIndex = (Integer)items.getClass().getMethod("indexOf", Object.class) //$NON-NLS-1$
            .invoke(items, item);
        if (currentIndex >= 0)
        {
            // The item is already here, so this is a reorder rather than a move. It has to go
            // through the list's own move: a containment list holds no duplicates, so indexed add
            // throws on an element it already has and plain add quietly does nothing - which would
            // report a reorder that never happened.
            reorderWithin(items, item, currentIndex, beforeName);
            return "moved '" + itemName + "' within " + from; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (beforeName != null && !beforeName.isEmpty())
        {
            addToContainerBefore(target, item, beforeName);
        }
        else
        {
            addToContainer(target, item);
        }
        return "moved '" + itemName + "' from " + from + " to " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (toRoot ? "the form root" : "'" + targetName + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Reorders an item that already sits in this list, placing it in front of a
     * named sibling or at the end.
     *
     * @param items        the container's item list
     * @param item         the item to reposition
     * @param currentIndex where the item sits now
     * @param beforeName   the sibling to land in front of, or {@code null} for the end
     * @throws Exception if the list refuses the move
     */
    private void reorderWithin(Object items, Object item, int currentIndex, String beforeName)
        throws Exception
    {
        int size = (Integer)items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$
        int wanted = size - 1;
        if (beforeName != null && !beforeName.isEmpty())
        {
            int siblingIndex = indexOfNamed(items, beforeName, size);
            wanted = siblingIndex >= 0 ? siblingIndex : size - 1;
        }
        int targetIndex = reorderTargetIndex(currentIndex, wanted);
        if (targetIndex == currentIndex)
        {
            return;
        }
        items.getClass().getMethod("move", Integer.TYPE, Object.class) //$NON-NLS-1$
            .invoke(items, targetIndex, item);
    }

    /**
     * Converts "put it where that sibling stands" into the index the list move
     * expects. A move does not shorten the list, so an item travelling forwards
     * lands one place earlier than the slot it aimed at - everything between the
     * two positions has already shifted back by one.
     *
     * @param currentIndex where the item sits now
     * @param wantedIndex  the index of the sibling it should precede
     * @return the index to pass to the list move
     */
    static int reorderTargetIndex(int currentIndex, int wantedIndex)
    {
        return wantedIndex > currentIndex ? wantedIndex - 1 : wantedIndex;
    }

    /**
     * Finds the position of a named element in a container's item list.
     *
     * @param items the item list
     * @param name  the element name to look for
     * @param size  the list size, already read by the caller
     * @return the index, or -1 when no element carries that name
     * @throws Exception if the list cannot be read
     */
    private int indexOfNamed(Object items, String name, int size) throws Exception
    {
        for (int i = 0; i < size; i++)
        {
            Object existing = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            try
            {
                if (name.equals(namedIface.getMethod("getName").invoke(existing))) //$NON-NLS-1$
                {
                    return i;
                }
            }
            catch (Exception e)
            {
                // An unnamed element cannot be the anchor - keep looking.
            }
        }
        return -1;
    }

    /**
     * Tells whether {@code node} sits anywhere below {@code possibleAncestor} in
     * the containment tree.
     *
     * @param node            the node to test
     * @param possibleAncestor the container to test against
     * @return true when node is possibleAncestor or one of its descendants
     */
    static boolean isInsideOf(Object node, Object possibleAncestor)
    {
        Object current = node;
        while (current != null)
        {
            if (current == possibleAncestor)
            {
                return true;
            }
            current = eContainerOf(current);
        }
        return false;
    }

    /**
     * Names the container an item currently sits in, for a human-readable move
     * report.
     *
     * @param item the item whose parent to describe
     * @return the parent name, or a fallback phrase when it has none
     */
    private String describeParentOf(Object item)
    {
        Object parent = eContainerOf(item);
        if (parent == null)
        {
            return "the form root"; //$NON-NLS-1$
        }
        try
        {
            String name = (String)namedIface.getMethod("getName").invoke(parent); //$NON-NLS-1$
            if (name != null && !name.isEmpty())
            {
                return "'" + name + "'"; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (Exception e)
        {
            // An unnamed parent (the form itself, an auto command bar) - fall through.
        }
        return "the form root"; //$NON-NLS-1$
    }

    /**
     * Reflective {@code EObject.eContainer()} - this helper deliberately holds no
     * compile-time dependency on the EDT form model.
     *
     * @param obj the model object
     * @return its container, or {@code null} when it has none or the call fails
     */
    private static Object eContainerOf(Object obj)
    {
        try
        {
            return obj.getClass().getMethod("eContainer").invoke(obj); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Links a button to a command by setting the button's commandName property.
     *
     * @param button the button object
     * @param command the command object
     * @throws Exception if linking fails
     */
    public void linkButtonToCommand(Object button, Object command) throws Exception
    {
        buttonIface.getMethod("setCommandName", commandIface).invoke(button, command); //$NON-NLS-1$
    }

    /**
     * Adds an attribute to a form's attributes list.
     *
     * @param form the form object
     * @param attr the attribute to add
     * @throws Exception if adding fails
     */
    public void addAttributeToForm(Object form, Object attr) throws Exception
    {
        Object attributes = formIface.getMethod("getAttributes").invoke(form); //$NON-NLS-1$
        attributes.getClass().getMethod("add", Object.class).invoke(attributes, attr); //$NON-NLS-1$
    }

    /**
     * 1.42 (RSV 4.2 parity): removes a {@code FormAttribute} from
     * {@code form.getAttributes()} by name. Does not touch UI items - the
     * caller is responsible for choosing whether to delete dependent items
     * via {@link #removeFormItemsBoundToAttribute} or to keep them with their
     * data paths intact (the deleteDataItems=false branch).
     *
     * @return {@code true} when the attribute was found and removed
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public boolean removeFormAttributeByName(Object form, String name) throws Exception
    {
        Object attributes = formIface.getMethod("getAttributes").invoke(form); //$NON-NLS-1$
        Iterator iterator = (Iterator) attributes.getClass().getMethod("iterator") //$NON-NLS-1$
            .invoke(attributes);
        while (iterator.hasNext())
        {
            Object attr = iterator.next();
            try
            {
                String n = (String) namedIface.getMethod("getName").invoke(attr); //$NON-NLS-1$
                // Case-insensitive to match findFormAttributeByName / addFormAttribute
                // idempotency check - users get the same outcome regardless of casing.
                if (name.equalsIgnoreCase(n))
                {
                    iterator.remove();
                    return true;
                }
            }
            catch (Exception ignored)
            {
                // Skip unnamed entries.
            }
        }
        return false;
    }

    /**
     * 1.42: case-insensitive existence check for a FormAttribute by name.
     * Mirrors {@link #findFormAttributeByName} but exposes a boolean
     * directly so callers (e.g. opRemoveFormAttribute) can validate the
     * attribute exists before mutating UI items.
     */
    public boolean hasFormAttribute(Object form, String name) throws Exception
    {
        return findFormAttributeByName(form, name) != null;
    }

    // ---- 1.43.x audit C (forms-advanced): FormParameter -------------------
    // Form.getParameters() -> EList<FormParameter>; FormParameter is a
    // mcore.NamedElement with getValueType()/setValueType(TypeDescription),
    // isKeyParameter()/setKeyParameter(boolean) and getComment()/setComment().
    // The valueType is applied through the shared BmDefinedTypeHelper
    // setFormAttributeTypes (it targets the generic 'valueType' feature).

    /**
     * Creates a {@code FormParameter} via {@code FormFactory.createFormParameter()}
     * and sets its name, optional comment and key-parameter flag. The caller adds
     * the result to the form via {@link #addParameterToForm} and applies the
     * value type separately (BmDefinedTypeHelper.setFormAttributeTypes).
     */
    public Object createFormParameter(String name, String comment, boolean keyParameter)
        throws Exception
    {
        Object param = ffClass.getMethod("createFormParameter").invoke(formFactory); //$NON-NLS-1$
        namedIface.getMethod("setName", String.class).invoke(param, name); //$NON-NLS-1$
        if (comment != null && !comment.isEmpty())
        {
            try
            {
                param.getClass().getMethod("setComment", String.class).invoke(param, comment); //$NON-NLS-1$
            }
            catch (Exception ignored)
            {
                // comment is optional metadata - never fail the op over it
            }
        }
        if (keyParameter)
        {
            try
            {
                param.getClass().getMethod("setKeyParameter", boolean.class).invoke(param, //$NON-NLS-1$
                    Boolean.TRUE);
            }
            catch (Exception ignored)
            {
                // keyParameter is optional - never fail the op over it
            }
        }
        return param;
    }

    /** Appends a FormParameter to {@code form.getParameters()}. */
    public void addParameterToForm(Object form, Object param) throws Exception
    {
        Object parameters = formIface.getMethod("getParameters").invoke(form); //$NON-NLS-1$
        parameters.getClass().getMethod("add", Object.class).invoke(parameters, param); //$NON-NLS-1$
    }

    /** Case-insensitive lookup of a FormParameter by name, or {@code null}. */
    @SuppressWarnings("rawtypes")
    public Object findFormParameterByName(Object form, String name) throws Exception
    {
        Object parameters = formIface.getMethod("getParameters").invoke(form); //$NON-NLS-1$
        Iterator iterator = (Iterator) parameters.getClass().getMethod("iterator") //$NON-NLS-1$
            .invoke(parameters);
        while (iterator.hasNext())
        {
            Object p = iterator.next();
            try
            {
                String n = (String) namedIface.getMethod("getName").invoke(p); //$NON-NLS-1$
                if (name.equalsIgnoreCase(n))
                {
                    return p;
                }
            }
            catch (Exception ignored)
            {
                // skip unnamed entries
            }
        }
        return null;
    }

    /** Case-insensitive existence check for a FormParameter by name. */
    public boolean hasFormParameter(Object form, String name) throws Exception
    {
        return findFormParameterByName(form, name) != null;
    }

    /**
     * Removes a FormParameter from {@code form.getParameters()} by name
     * (case-insensitive). Returns {@code true} when one was found and removed.
     */
    @SuppressWarnings("rawtypes")
    public boolean removeFormParameterByName(Object form, String name) throws Exception
    {
        Object parameters = formIface.getMethod("getParameters").invoke(form); //$NON-NLS-1$
        Iterator iterator = (Iterator) parameters.getClass().getMethod("iterator") //$NON-NLS-1$
            .invoke(parameters);
        while (iterator.hasNext())
        {
            Object p = iterator.next();
            try
            {
                String n = (String) namedIface.getMethod("getName").invoke(p); //$NON-NLS-1$
                if (name.equalsIgnoreCase(n))
                {
                    iterator.remove();
                    return true;
                }
            }
            catch (Exception ignored)
            {
                // skip unnamed entries
            }
        }
        return false;
    }

    /**
     * 1.42 (RSV 4.2 parity): walks the form's UI tree and collects every item
     * whose {@code dataPath} starts with the given attribute name (i.e. is
     * bound to the attribute itself or to one of its nested fields like
     * {@code <attr>/<column>}). Used both to enumerate
     * {@code preservedDataPaths} when {@code deleteDataItems=false} and to
     * locate items for deletion when {@code deleteDataItems=true}.
     *
     * <p>Each entry is a String formatted as
     * {@code "<itemKind>:<itemName> -> <dataPath>"}, e.g.
     * {@code "Table:ТаблицаСводка -> /Сводка"} or
     * {@code "FormField:Колонка1 -> /Сводка/Колонка1"}.
     */
    public List<String> collectFormItemsBoundToAttribute(Object form, String attributeName)
        throws Exception
    {
        List<String> result = new ArrayList<>();
        collectBoundItemsRecursive(form, attributeName, result, null);
        return result;
    }

    /**
     * 1.42: removes every UI item whose dataPath starts with the given
     * attribute name. Returns the count of removed items. Does not touch the
     * FormAttribute itself - callers pair this with
     * {@link #removeFormAttributeByName}.
     */
    public int removeFormItemsBoundToAttribute(Object form, String attributeName) throws Exception
    {
        List<Object[]> toRemove = new ArrayList<>();
        collectBoundItemsRecursive(form, attributeName, null, toRemove);
        int removed = 0;
        for (Object[] entry : toRemove)
        {
            Object container = entry[0];
            Object item = entry[1];
            try
            {
                Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
                @SuppressWarnings("rawtypes")
                java.util.Collection itemsCol = (java.util.Collection) items;
                if (itemsCol.remove(item))
                {
                    removed++;
                }
            }
            catch (Exception ignored)
            {
                // Item may have been unparented during the cascade - skip.
            }
        }
        return removed;
    }

    /**
     * 1.42 helper: shared depth-first walk for the two
     * {@link #collectFormItemsBoundToAttribute} /
     * {@link #removeFormItemsBoundToAttribute} variants. When {@code presPaths}
     * is non-null, presentation strings are appended; when {@code removalList}
     * is non-null, {@code [container, item]} pairs are appended for later
     * removal (avoids ConcurrentModificationException during iteration).
     */
    private void collectBoundItemsRecursive(Object container, String attributeName,
        List<String> presPaths, List<Object[]> removalList) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer) items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object item = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            String dataPath = readDataPath(item);
            boolean itemMatches = dataPath != null
                && pathStartsWithAttribute(dataPath, attributeName);
            if (itemMatches)
            {
                if (presPaths != null)
                {
                    String kind = item.getClass().getSimpleName();
                    int implIdx = kind.indexOf("Impl"); //$NON-NLS-1$
                    if (implIdx > 0)
                    {
                        kind = kind.substring(0, implIdx);
                    }
                    String itemName = "<unnamed>"; //$NON-NLS-1$
                    try
                    {
                        Object n = namedIface.getMethod("getName").invoke(item); //$NON-NLS-1$
                        if (n != null)
                        {
                            itemName = n.toString();
                        }
                    }
                    catch (Exception ignored)
                    {
                        // No name on this item - keep placeholder.
                    }
                    presPaths.add(kind + ":" + itemName + " -> " + dataPath); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (removalList != null)
                {
                    removalList.add(new Object[] { container, item });
                }
                // For removal walks: do NOT descend into a matched container.
                // EMF cascades removal of its children, and adding their pairs
                // would inflate the removed counter and trigger remove-after-
                // detach attempts. presPaths still descends so the caller can
                // see the full set of paths that will disappear.
                if (removalList != null && containerIface.isInstance(item))
                {
                    if (presPaths != null)
                    {
                        collectBoundItemsRecursive(item, attributeName, presPaths, null);
                    }
                    continue;
                }
            }
            if (containerIface.isInstance(item))
            {
                collectBoundItemsRecursive(item, attributeName, presPaths, removalList);
            }
        }
    }

    private static boolean pathStartsWithAttribute(String dataPath, String attributeName)
    {
        if (dataPath.equals(attributeName))
        {
            return true;
        }
        if (dataPath.startsWith(attributeName + "/") //$NON-NLS-1$
            || dataPath.startsWith(attributeName + ".") //$NON-NLS-1$
            || dataPath.startsWith("/" + attributeName + "/") //$NON-NLS-1$ //$NON-NLS-2$
            || dataPath.equals("/" + attributeName)) //$NON-NLS-1$
        {
            return true;
        }
        return false;
    }

    /**
     * 1.42 helper: reads a form item's dataPath in slash-segment form
     * (e.g. {@code "/Сводка/Колонка1"}). Returns {@code null} when the item
     * is not a DataItem or the path is empty.
     */
    private String readDataPath(Object item)
    {
        try
        {
            Class<?> dataItemClass = Class.forName("com._1c.g5.v8.dt.form.model.DataItem"); //$NON-NLS-1$
            if (!dataItemClass.isInstance(item))
            {
                return null;
            }
            Object pathObj = dataItemClass.getMethod("getDataPath").invoke(item); //$NON-NLS-1$
            if (pathObj == null)
            {
                return null;
            }
            Class<?> abstractDataPathClass = Class.forName(
                "com._1c.g5.v8.dt.form.model.AbstractDataPath"); //$NON-NLS-1$
            Object segments = abstractDataPathClass.getMethod("getSegments").invoke(pathObj); //$NON-NLS-1$
            int sz = (Integer) segments.getClass().getMethod("size").invoke(segments); //$NON-NLS-1$
            if (sz == 0)
            {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sz; i++)
            {
                Object seg = segments.getClass().getMethod("get", Integer.TYPE) //$NON-NLS-1$
                    .invoke(segments, i);
                sb.append('/').append(seg != null ? seg.toString() : ""); //$NON-NLS-1$
            }
            return sb.toString();
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * Adds a command to a form's commands list.
     *
     * @param form the form object
     * @param cmd the command to add
     * @throws Exception if adding fails
     */
    public void addCommandToForm(Object form, Object cmd) throws Exception
    {
        Object commands = formIface.getMethod("getFormCommands").invoke(form); //$NON-NLS-1$
        commands.getClass().getMethod("add", Object.class).invoke(commands, cmd); //$NON-NLS-1$
    }

    /**
     * Removes a command from {@code form.getFormCommands()}. Symmetric to
     * {@link #addCommandToForm}. Returns false when the command was not in the
     * collection. Backs {@code remove_form_command} (delete an orphan or stale
     * form command a button no longer references).
     *
     * @param form the form object
     * @param cmd the FormCommand to remove (found via {@link #findFormCommandByName})
     * @return true when an element was removed
     */
    public boolean removeCommandFromForm(Object form, Object cmd) throws Exception
    {
        if (cmd == null)
        {
            return false;
        }
        Object commands = formIface.getMethod("getFormCommands").invoke(form); //$NON-NLS-1$
        Object removed = commands.getClass().getMethod("remove", Object.class).invoke(commands, cmd); //$NON-NLS-1$
        return Boolean.TRUE.equals(removed);
    }

    /**
     * L65: sets a display property of an existing {@code FormCommand} - the
     * three properties a command carries beyond name/id/action that
     * {@code set_form_item_property} cannot reach (it addresses form items,
     * not the {@code formCommands} block).
     * <ul>
     *   <li>{@code title} - via {@code Titled.setTitle} (same path as
     *       {@link #createFormCommand});</li>
     *   <li>{@code representation} - {@code DefaultRepresentation} enum
     *       (Auto / Text / Picture / TextPicture): how the bound button renders
     *       the command;</li>
     *   <li>{@code picture} - best-effort. {@code FormCommand.setPicture} takes
     *       a typed {@code mcore.Picture} on current EDT builds, and building a
     *       named StdPicture / CommonPicture reference through EMF needs EDT's
     *       build-unstable picture resolution. When no String overload is
     *       exposed the property is REFUSED (not silently dropped) so the agent
     *       applies the picture in the EDT UI instead of emitting an invalid
     *       {@code <picture>} that loads in EDT but breaks in the infobase.</li>
     * </ul>
     *
     * @param command the FormCommand (found via {@link #findFormCommandByName})
     * @param propertyName one of title / representation / picture
     * @param propertyValue the new value (a name for picture)
     * @return a short status string, prefixed with {@code Error:} on failure
     * @throws Exception on unexpected reflective failure
     */
    public String setFormCommandProperty(Object command, String propertyName, String propertyValue)
        throws Exception
    {
        String prop = propertyName.toLowerCase();
        if ("title".equals(prop)) //$NON-NLS-1$
        {
            if (propertyValue == null || propertyValue.isEmpty())
            {
                return "Error: title requires a non-empty propertyValue"; //$NON-NLS-1$
            }
            setTitle(command, propertyValue);
            return "set title"; //$NON-NLS-1$
        }
        if ("representation".equals(prop)) //$NON-NLS-1$
        {
            Class<?> reprClass = Class.forName("com._1c.g5.v8.dt.form.model.DefaultRepresentation"); //$NON-NLS-1$
            String wanted = propertyValue == null ? "" : propertyValue.replace("_", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            for (Object constant : reprClass.getEnumConstants())
            {
                String litName = ((Enum<?>) constant).name().replace("_", ""); //$NON-NLS-1$ //$NON-NLS-2$
                String litStr = constant.toString().replace("_", ""); //$NON-NLS-1$ //$NON-NLS-2$
                if (!wanted.isEmpty()
                    && (wanted.equalsIgnoreCase(litName) || wanted.equalsIgnoreCase(litStr)))
                {
                    formCommandIface.getMethod("setRepresentation", reprClass).invoke(command, constant); //$NON-NLS-1$
                    return "set representation=" + constant.toString(); //$NON-NLS-1$
                }
            }
            return "Error: unknown representation '" + propertyValue //$NON-NLS-1$
                + "'. Allowed: Auto, Text, Picture, TextPicture."; //$NON-NLS-1$
        }
        if ("picture".equals(prop)) //$NON-NLS-1$
        {
            // Probe a String-parameter setPicture (mirrors the decoration helper).
            // Current builds expose only setPicture(mcore.Picture), so this refuses
            // honestly rather than emit an empty/invalid <picture>.
            for (Method mth : command.getClass().getMethods())
            {
                if ("setPicture".equals(mth.getName()) && mth.getParameterCount() == 1 //$NON-NLS-1$
                    && mth.getParameterTypes()[0] == String.class)
                {
                    mth.invoke(command, propertyValue);
                    return "set picture=" + propertyValue; //$NON-NLS-1$
                }
            }
            return "Error: picture is not settable via MCP on this EDT build - " //$NON-NLS-1$
                + "FormCommand.setPicture expects a typed mcore.Picture and named-picture " //$NON-NLS-1$
                + "resolution is build-unstable. Apply the picture in the EDT UI " //$NON-NLS-1$
                + "(command property sheet: Picture), or use representation=Text."; //$NON-NLS-1$
        }
        return "Error: unknown propertyName '" + propertyName //$NON-NLS-1$
            + "'. Allowed: title, representation, picture."; //$NON-NLS-1$
    }

    /**
     * Finds a form command by name (case-insensitive) in
     * {@code form.getFormCommands()}. Returns {@code null} when absent. Used to
     * bind a button to an existing command (add_button commandName) and to
     * attach a handler to an existing command (add_command_handler) instead of
     * creating a duplicate.
     *
     * @param form the form object
     * @param name the command name to find
     * @return the FormCommand, or {@code null} when not found
     * @throws Exception if scanning fails
     */
    public Object findFormCommandByName(Object form, String name) throws Exception
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }
        Object commands = formIface.getMethod("getFormCommands").invoke(form); //$NON-NLS-1$
        int size = (Integer) commands.getClass().getMethod("size").invoke(commands); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object cmd = commands.getClass().getMethod("get", Integer.TYPE).invoke(commands, i); //$NON-NLS-1$
            try
            {
                String cmdName = (String) namedIface.getMethod("getName").invoke(cmd); //$NON-NLS-1$
                if (name.equalsIgnoreCase(cmdName))
                {
                    return cmd;
                }
            }
            catch (Exception ignored)
            {
                // unnamed command, skip
            }
        }
        return null;
    }

    /**
     * Returns a comma-separated list of the form's command names (for error
     * messages when a requested commandName is not found).
     *
     * @param form the form object
     * @return command names joined by ", ", or "(none)" when the form has none
     */
    public String listFormCommandNames(Object form)
    {
        try
        {
            Object commands = formIface.getMethod("getFormCommands").invoke(form); //$NON-NLS-1$
            int size = (Integer) commands.getClass().getMethod("size").invoke(commands); //$NON-NLS-1$
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < size; i++)
            {
                Object cmd = commands.getClass().getMethod("get", Integer.TYPE).invoke(commands, i); //$NON-NLS-1$
                String cmdName = safeName(cmd);
                if (cmdName == null)
                {
                    continue; // unnamed command - skip rather than abort the list
                }
                if (sb.length() > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(cmdName);
            }
            return sb.length() > 0 ? sb.toString() : "(none)"; //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            return "(unavailable)"; //$NON-NLS-1$
        }
    }

    /**
     * Returns the AutoCommandBar of a CommandBarHolder (Table / FormGroup),
     * creating and attaching one when absent. A command button targeted at a
     * table must live in that table's AutoCommandBar - a Button placed directly
     * in the table's {@code getItems()} is an "Unsupported child element type"
     * that breaks the form at load. The created bar gets a unique id, a derived
     * name and {@code autoFill=true}, matching the EDT-wizard default.
     *
     * @param holder a CommandBarHolder (Table, FormGroup, ...)
     * @param holderName the holder's name, used to derive the bar name
     * @return the existing or freshly-created AutoCommandBar container, or
     *     {@code null} when this runtime exposes no AutoCommandBar accessor
     */
    public Object getOrCreateAutoCommandBar(Object holder, String holderName)
    {
        if (holder == null)
        {
            return null;
        }
        try
        {
            Object existing = holder.getClass().getMethod("getAutoCommandBar").invoke(holder); //$NON-NLS-1$
            if (existing != null)
            {
                return existing;
            }
        }
        catch (Exception noAccessor)
        {
            // Holder is not a CommandBarHolder on this runtime - cannot delegate.
            return null;
        }
        try
        {
            Object bar = ffClass.getMethod("createAutoCommandBar").invoke(formFactory); //$NON-NLS-1$
            try
            {
                bar.getClass().getMethod("setId", Integer.TYPE).invoke(bar, nextId()); //$NON-NLS-1$
            }
            catch (Exception ignored)
            {
                // id best-effort
            }
            try
            {
                String barName = (holderName != null && !holderName.isEmpty()
                    ? holderName : "Элемент") + "КоманднаяПанель"; //$NON-NLS-1$ //$NON-NLS-2$
                namedIface.getMethod("setName", String.class).invoke(bar, barName); //$NON-NLS-1$
            }
            catch (Exception ignored)
            {
                // name best-effort
            }
            try
            {
                invokeSingleParamSetter(bar, "setAutoFill", Boolean.TRUE); //$NON-NLS-1$
            }
            catch (Exception ignored)
            {
                // autoFill best-effort
            }
            invokeSingleParamSetter(holder, "setAutoCommandBar", bar); //$NON-NLS-1$
            return bar;
        }
        catch (Exception createEx)
        {
            Activator.logWarning("getOrCreateAutoCommandBar failed: " + createEx.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Search and removal
    // -----------------------------------------------------------------------

    /**
     * 1.42 (B2): {@code true} when the given form item is a {@code Table} -
     * needed by callers that decide whether to auto-delegate a button into
     * the table's command bar instead of placing it as a child of the table
     * itself (which the platform rejects with "button not supported in
     * context object").
     */
    public boolean isTable(Object item)
    {
        return item != null && tableIface.isInstance(item);
    }

    /**
     * 1.42 (B1): checks whether {@code name} is already used by any visual
     * element on the form - including elements nested in tables' command bars
     * and context menus, which are stored as separate properties of the table
     * rather than in the regular {@code getItems()} chain.
     *
     * <p>Closes the RSV 4.2 fix where {@code addTable} with auto-generated
     * columns produced names colliding with header attributes (form had
     * "Организация" in the header AND a "Организация" column in a summary
     * table - the 1C client crashed on render with no journal entry). The EDT
     * wizard prefixes auto-generated columns with the parent table name; we do
     * not auto-prefix but block the collision up-front with a clear error.
     *
     * @param form root form object
     * @param name candidate element name
     * @return {@code true} when the name is taken anywhere on the form
     */
    public boolean isNameUsedAnywhere(Object form, String name) throws Exception
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        return findItemByName(form, name) != null
            || isNameUsedInTableSubcontainers(form, name);
    }

    /**
     * 1.42 (B1): walks the form's regular item tree and, for each table, also
     * scans its command bar and context menu - those live on the table as
     * separate references, not as children inside getItems().
     */
    private boolean isNameUsedInTableSubcontainers(Object container, String name) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer) items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object item = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            if (tableIface.isInstance(item))
            {
                for (String accessor : new String[] { "getCommandBar", "getContextMenu" }) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    try
                    {
                        Object sub = item.getClass().getMethod(accessor).invoke(item);
                        if (sub != null && containerIface.isInstance(sub)
                            && findItemByName(sub, name) != null)
                        {
                            return true;
                        }
                    }
                    catch (NoSuchMethodException ignored)
                    {
                        // Accessor absent on this EDT version - skip.
                    }
                }
            }
            if (containerIface.isInstance(item) && isNameUsedInTableSubcontainers(item, name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds an item by name recursively in a container hierarchy.
     *
     * @param container the container to search in
     * @param name the item name to find
     * @return the found item, or {@code null} if not found
     * @throws Exception if search fails
     */
    public Object findItemByName(Object container, String name) throws Exception
    {
        Object found = findItemByNameGetItems(container, name);
        if (found != null)
        {
            return found;
        }
        // L66: a button placed in a table's AutoCommandBar (or a context menu) lives in a
        // separate EMF reference, not in getItems(), so the walk above misses it - which is why
        // set_form_item_property could not reach a button that remove_form_item (eAllContents)
        // happily found. Fall back to an eAllContents() subtree scan, the same reach
        // removeItemByName uses, so the resolvers agree.
        if (container instanceof org.eclipse.emf.ecore.EObject)
        {
            org.eclipse.emf.ecore.EObject root = (org.eclipse.emf.ecore.EObject) container;
            for (java.util.Iterator<org.eclipse.emf.ecore.EObject> it = root.eAllContents(); it.hasNext();)
            {
                org.eclipse.emf.ecore.EObject obj = it.next();
                if (formItemIface.isInstance(obj) && name.equals(safeName(obj)))
                {
                    return obj;
                }
            }
        }
        return null;
    }

    /**
     * Original getItems()-recursive name lookup; kept as the fast primary path of
     * {@link #findItemByName}. Recurses into child FormItemContainers but does NOT
     * reach AutoCommandBar / context-menu buttons (held in separate references),
     * which is why {@link #findItemByName} adds the eAllContents() fallback.
     */
    private Object findItemByNameGetItems(Object container, String name) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer) items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$

        for (int i = 0; i < size; i++)
        {
            Object item = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            try
            {
                String itemName = (String) namedIface.getMethod("getName").invoke(item); //$NON-NLS-1$
                if (name.equals(itemName))
                {
                    return item;
                }
            }
            catch (Exception e)
            {
                // Element may not be named
            }
            // Recurse into child containers
            if (containerIface.isInstance(item))
            {
                Object found = findItemByNameGetItems(item, name);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Removes an item by name from a container (non-recursive, direct children only).
     *
     * @param container the container to remove from
     * @param name the item name to remove
     * @return {@code true} if the item was found and removed
     * @throws Exception if removal fails
     */
    public boolean removeItemByName(Object container, String name) throws Exception
    {
        // #8: find the named visual item ANYWHERE in the form tree - groups,
        // tables, and a table's AutoCommandBar / context menu (which are stored
        // as separate references, not in the parent's getItems()). The old code
        // scanned only the top getItems() list and returned "Element not found"
        // for a button inside a table command bar. eAllContents() reaches every
        // contained element; EcoreUtil.remove unlinks it from its container.
        if (!(container instanceof org.eclipse.emf.ecore.EObject))
        {
            return removeTopLevelItemByName(container, name);
        }
        org.eclipse.emf.ecore.EObject root = (org.eclipse.emf.ecore.EObject) container;
        org.eclipse.emf.ecore.EObject target = null;
        for (java.util.Iterator<org.eclipse.emf.ecore.EObject> it = root.eAllContents();
            it.hasNext();)
        {
            org.eclipse.emf.ecore.EObject obj = it.next();
            if (formItemIface.isInstance(obj) && name.equals(safeName(obj)))
            {
                target = obj;
                break;
            }
        }
        if (target == null)
        {
            return false;
        }
        org.eclipse.emf.ecore.util.EcoreUtil.remove(target);
        // L67: the button's bound FormCommand is intentionally LEFT in place. Removing the
        // item no longer cascades to its command - that cascade was surprising (deleting a
        // few buttons silently wiped the form's commands, and a recreate-against-a-name-still-
        // held-by-the-leftover command then produced orphan duplicates with a numeric suffix).
        // Delete a command explicitly with remove_form_command; the old orphan auto-prevention
        // is now the caller's explicit choice.
        return true;
    }

    /**
     * Legacy top-level-only removal (fallback when the container is not an
     * EObject, which should not happen for a real form).
     */
    private boolean removeTopLevelItemByName(Object container, String name) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer) items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object item = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            if (name.equals(safeName(item)))
            {
                items.getClass().getMethod("remove", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
                return true;
            }
        }
        return false;
    }

    /** Returns the element's name via getName(), or {@code null} when unnamed. */
    private String safeName(Object element)
    {
        try
        {
            return (String) namedIface.getMethod("getName").invoke(element); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * {@code true} when any button in the form tree still references the given form command.
     * Used by {@code remove_form_command} to refuse deleting a command a live button is bound
     * to (which would leave a dangling button reference and an invalid Form.form) - the caller
     * must remove the button first. {@code Button.getCommandName()} returns the bound
     * {@code mcore.Command} object, so an identity check is correct.
     *
     * @param form the form object (form.model.Form, an EObject)
     * @param command the FormCommand to test
     * @return true when at least one button references the command
     */
    public boolean isCommandReferenced(Object form, Object command)
    {
        if (form instanceof org.eclipse.emf.ecore.EObject)
        {
            return isCommandReferenced((org.eclipse.emf.ecore.EObject) form, command);
        }
        return false;
    }

    private boolean isCommandReferenced(org.eclipse.emf.ecore.EObject root, Object command)
    {
        for (java.util.Iterator<org.eclipse.emf.ecore.EObject> it = root.eAllContents(); it.hasNext();)
        {
            org.eclipse.emf.ecore.EObject obj = it.next();
            if (!buttonIface.isInstance(obj))
            {
                continue;
            }
            try
            {
                if (buttonIface.getMethod("getCommandName").invoke(obj) == command) //$NON-NLS-1$
                {
                    return true;
                }
            }
            catch (Exception ignored)
            {
                // button without a command accessor - skip
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // ID management
    // -----------------------------------------------------------------------

    /**
     * Resets the ID counter to the maximum ID found in the form.
     * Must be called before adding new elements to avoid ID collisions.
     *
     * @param form the form object
     * @throws Exception if scanning fails
     */
    public void resetIdCounter(Object form) throws Exception
    {
        idCounter = findMaxId(form);
    }

    /**
     * Resets the ID counter considering both the form and its BaseForm.
     * Borrowed forms in extensions only expose override items via
     * {@code getItems()}, so scanning the BaseForm top-object is required
     * to pick up IDs inherited from the main configuration.
     *
     * @param form the form object
     * @param baseForm the BaseForm top-object, or {@code null} if not applicable
     * @throws Exception if scanning fails
     */
    public void resetIdCounter(Object form, Object baseForm) throws Exception
    {
        int max = findMaxId(form);
        if (baseForm != null)
        {
            int baseMax = findMaxId(baseForm);
            if (baseMax > max)
            {
                max = baseMax;
            }
        }
        idCounter = max;
    }

    /**
     * Resolves the BaseForm top-object for a borrowed form in an extension.
     * <p>
     * For a form FQN like {@code "Document.X.Form.Y.Form"}, the BaseForm (if
     * any) is a separate top-object with FQN {@code "Document.X.Form.Y.Form.BaseForm"}.
     * Returns {@code null} for forms in the main configuration or when no
     * BaseForm exists.
     *
     * @param transaction the active BM transaction
     * @param formFqn the main form FQN (including trailing {@code .Form})
     * @return the BaseForm object, or {@code null} if it does not exist
     * @throws Exception if the lookup fails
     */
    public Object findBaseForm(Object transaction, String formFqn) throws Exception
    {
        return txIface.getMethod("getTopObjectByFqn", String.class) //$NON-NLS-1$
            .invoke(transaction, formFqn + ".BaseForm"); //$NON-NLS-1$
    }

    /**
     * Returns the next available ID and increments the counter.
     *
     * @return next ID
     */
    public int nextId()
    {
        return ++idCounter;
    }

    /**
     * Finds the maximum ID across all items and commands in the form.
     *
     * @param form the form object
     * @return the maximum ID found
     * @throws Exception if scanning fails
     */
    public int findMaxId(Object form) throws Exception
    {
        // Deep EMF walk: every form element AND its non-item children
        // (extendedTooltip, contextMenu, autoCommandBar, table columns,
        // searchString/viewStatus/searchControl additions, form commands, ...)
        // carries an `id`. The legacy getItems()-only walk missed the non-item
        // children, so a freshly added field reused the previous field's
        // extendedTooltip id - duplicate item ids that break the form
        // (form-invalid-item-id plus a render crash). eAllContents() visits the
        // entire containment tree, so no id-bearing element is skipped.
        if (form instanceof org.eclipse.emf.ecore.EObject)
        {
            int maxId = idValue(form);
            java.util.Iterator<org.eclipse.emf.ecore.EObject> it =
                ((org.eclipse.emf.ecore.EObject) form).eAllContents();
            while (it.hasNext())
            {
                int id = idValue(it.next());
                if (id > maxId)
                {
                    maxId = id;
                }
            }
            return Math.max(maxId, 0);
        }

        // Fallback: legacy reflective walk for a non-EObject input.
        int maxId = findMaxIdRecursive(form);
        Object commands = formIface.getMethod("getFormCommands").invoke(form); //$NON-NLS-1$
        int cmdSize = (Integer) commands.getClass().getMethod("size").invoke(commands); //$NON-NLS-1$
        for (int i = 0; i < cmdSize; i++)
        {
            Object cmd = commands.getClass().getMethod("get", Integer.TYPE).invoke(commands, i); //$NON-NLS-1$
            int cmdId = (Integer) formCommandIface.getMethod("getId").invoke(cmd); //$NON-NLS-1$
            if (cmdId > maxId)
            {
                maxId = cmdId;
            }
        }
        return Math.max(maxId, 0);
    }

    /**
     * Best-effort read of a form element's integer {@code id} via its
     * {@code getId()} accessor. Returns 0 when the element has no id accessor,
     * the id is null / non-integer, or it is the autoCommandBar sentinel (-1).
     * Used by {@link #findMaxId} so the allocator never reuses an id already
     * taken anywhere in the form tree (including non-item children such as a
     * field's extendedTooltip).
     *
     * @param element any object in the form containment tree (may be null)
     * @return the element's positive id, or 0 when it has none
     */
    private static int idValue(Object element)
    {
        if (element == null)
        {
            return 0;
        }
        try
        {
            Object v = element.getClass().getMethod("getId").invoke(element); //$NON-NLS-1$
            if (v instanceof Integer)
            {
                int id = (Integer) v;
                return id > 0 ? id : 0;
            }
        }
        catch (Exception ignored)
        {
            // Many EObjects in the tree have no getId() (LocalString entries,
            // ext-info objects, type descriptions) - they contribute 0.
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private int findMaxIdRecursive(Object container) throws Exception
    {
        int maxId = 0;
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer) items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$

        for (int i = 0; i < size; i++)
        {
            Object item = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            try
            {
                int itemId = (Integer) formItemIface.getMethod("getId").invoke(item); //$NON-NLS-1$
                if (itemId > maxId)
                {
                    maxId = itemId;
                }
            }
            catch (Exception e)
            {
                // Item may not have getId
            }
            if (containerIface.isInstance(item))
            {
                int childMax = findMaxIdRecursive(item);
                if (childMax > maxId)
                {
                    maxId = childMax;
                }
            }
        }
        return maxId;
    }

    /**
     * Sets {@code FormField.type} from a caller-supplied field kind, accepting the
     * spellings a caller reasonably uses: the literal ({@code LabelField}), the
     * Java constant ({@code LABEL_FIELD}) and the short form ({@code Label}).
     * <p>
     * The short form has to work because {@link #setFieldExtInfo} accepts it - it
     * matches on a substring. When the two disagree the field ends up with a
     * label's ext-info and an input field's type, which serializes as a form the
     * model reads one way and the platform another.
     */
    private void setFieldType(Object field, String fieldType) throws Exception
    {
        Class<?> fieldTypeClass = Class.forName("com._1c.g5.v8.dt.form.model.ManagedFormFieldType"); //$NON-NLS-1$
        String wanted = normalizeFieldKind(fieldType);
        Object matched = null;
        for (Object constant : fieldTypeClass.getEnumConstants())
        {
            if (wanted.equals(normalizeFieldKind(constant.toString())))
            {
                matched = constant;
                break;
            }
        }

        if (matched != null)
        {
            formFieldIface.getMethod("setType", fieldTypeClass).invoke(field, matched); //$NON-NLS-1$
        }
    }

    /**
     * Reduces a field-kind spelling to its comparable core: lower case, no
     * underscores, no trailing {@code field}. So {@code Label}, {@code LabelField}
     * and {@code LABEL_FIELD} all reduce to {@code label}.
     *
     * @param fieldKind the spelling to reduce, never {@code null}
     * @return the normalized form
     */
    static String normalizeFieldKind(String fieldKind)
    {
        String s = fieldKind.replace("_", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
        if (s.length() > "field".length() && s.endsWith("field")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            s = s.substring(0, s.length() - "field".length()); //$NON-NLS-1$
        }
        return s;
    }

    private void setFieldExtInfo(Object field, String fieldType) throws Exception
    {
        setFieldExtInfo(field, fieldType, false);
    }

    private void setFieldExtInfo(Object field, String fieldType, boolean hyperlink) throws Exception
    {
        String lower = fieldType.toLowerCase();
        Object extInfo;

        if (lower.contains("checkbox") || lower.contains("check_box")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createCheckBoxFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("radio") || lower.contains("radiobutton")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createRadioButtonsFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("label")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createLabelFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("image") || lower.contains("picture")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createImageFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        // Additional field KINDS (factory method names javap-verified against
        // FormFactory). Compound names (gantt/flowchart) are matched BEFORE the
        // bare "chart" branch because "ganttchart"/"flowchart" also contain "chart".
        else if (lower.contains("spreadsheet")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createSpreadSheetDocFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("html")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createHtmlFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("formatted")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createFormattedDocFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("textdoc") || lower.contains("text_doc") //$NON-NLS-1$ //$NON-NLS-2$
            || lower.contains("textdocument")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createTextDocFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("calendar")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createCalendarFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("progress")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createProgressBarFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("track")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createTrackBarFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("period")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createPeriodFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("planner")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createPlannerFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("pdf")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createPDFDocumentFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("dendrogram")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createDendrogramFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("gantt")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createGanttChartFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("flowchart") || lower.contains("flow_chart")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createFlowchartFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("geograph")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createGeographicalMapFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("chart")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createChartFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("html")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createHtmlFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("picture") || lower.contains("image")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createImageFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("spreadsheet")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createSpreadSheetDocFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("formatted")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createFormattedDocFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("textdoc") || (lower.contains("text") && lower.contains("doc"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            extInfo = ffClass.getMethod("createTextDocFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("radio")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createRadioButtonsFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else
        {
            extInfo = ffClass.getMethod("createInputFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }

        if (extInfo != null)
        {
            Class<?> fieldExtInfoClass = Class.forName("com._1c.g5.v8.dt.form.model.FieldExtInfo"); //$NON-NLS-1$
            formFieldIface.getMethod("setExtInfo", fieldExtInfoClass).invoke(field, extInfo); //$NON-NLS-1$
            // A Label field can render as a hyperlink (LabelFieldExtInfo only).
            applyHyperlink(extInfo, hyperlink);
        }
    }

    private void setGroupType(Object group, String groupType) throws Exception
    {
        Class<?> groupTypeClass = Class.forName("com._1c.g5.v8.dt.form.model.ManagedFormGroupType"); //$NON-NLS-1$
        for (Object constant : groupTypeClass.getEnumConstants())
        {
            if (groupType.equalsIgnoreCase(constant.toString()))
            {
                formGroupIface.getMethod("setType", groupTypeClass).invoke(group, constant); //$NON-NLS-1$
                return;
            }
        }
    }

    private void setGroupExtInfo(Object group, String groupType) throws Exception
    {
        String lower = groupType.toLowerCase();
        Object extInfo;

        if (lower.contains("pages") && !lower.contains("page")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createPagesGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("page")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createPageGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("column")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createColumnGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("command") || lower.contains("commandbar")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createCommandBarExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("button")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createButtonGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("popup")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createPopupGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else
        {
            extInfo = ffClass.getMethod("createUsualGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
            for (String[] pair : USUAL_GROUP_DEFAULTS)
            {
                setScalarProperty(extInfo, pair[0], pair[1]);
            }
        }

        if (extInfo != null)
        {
            Class<?> groupExtInfoClass = Class.forName("com._1c.g5.v8.dt.form.model.GroupExtInfo"); //$NON-NLS-1$
            formGroupIface.getMethod("setExtInfo", groupExtInfoClass).invoke(group, extInfo); //$NON-NLS-1$
        }
    }

    private void setRepresentation(Object button, String representation) throws Exception
    {
        try
        {
            Class<?> reprClass = Class.forName("com._1c.g5.v8.dt.mcore.ButtonRepresentation"); //$NON-NLS-1$
            for (Object constant : reprClass.getEnumConstants())
            {
                if (representation.equalsIgnoreCase(constant.toString()))
                {
                    buttonIface.getMethod("setRepresentation", reprClass) //$NON-NLS-1$
                        .invoke(button, constant);
                    return;
                }
            }
        }
        catch (Exception e)
        {
            // Non-fatal - representation is optional
        }
    }

    /**
     * Best-effort sets the button {@code type} (e.g. {@code UsualButton}) via reflection, mirroring
     * {@link #setRepresentation}. A Button serialized without a {@code <type>} is not accepted by EDT /
     * the platform for a rendered button, so {@link #createButton} defaults it to UsualButton.
     */
    private void setButtonType(Object button, String type) throws Exception
    {
        try
        {
            Class<?> typeClass = Class.forName("com._1c.g5.v8.dt.form.model.ManagedFormButtonType"); //$NON-NLS-1$
            String wanted = type.replace("_", ""); //$NON-NLS-1$
            for (Object constant : typeClass.getEnumConstants())
            {
                // literal toString() is "UsualButton", name() is "USUAL_BUTTON" - accept either form
                if (wanted.equalsIgnoreCase(constant.toString().replace("_", "")) //$NON-NLS-1$ //$NON-NLS-2$
                    || wanted.equalsIgnoreCase(((Enum<?>) constant).name().replace("_", ""))) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    buttonIface.getMethod("setType", typeClass).invoke(button, constant); //$NON-NLS-1$
                    return;
                }
            }
        }
        catch (Exception e)
        {
            // Non-fatal - type defaults on the platform side if the model differs
        }
    }

    /**
     * Persists BM changes for the given form top-object to its backing
     * {@code .form} file and waits (bounded) for the disk flush. Delegates to
     * {@link BmExportHelper#forceExportAndWait} - the same bounded persist path
     * every metadata mutation uses - which resolves the IDtProject, runs
     * forceExport, and bounds {@code waitModelSynchronization} by the wait
     * budget (Row 42: the old fire-and-forget forceExport returned before the
     * {@code .form} hit disk and could not detect a pending flush; the fallback
     * wait was moreover unbounded and could pin an HTTP worker thread).
     * <p>
     * A shorter 5s budget (vs the 10s metadata default) bounds the UI-thread
     * stall: form ops run inside {@code Display.syncExec}, so this blocking wait
     * freezes the EDT UI on a stuck sync manager; the daemon finishes the save
     * in the background regardless. Returns the {@link BmExportHelper.Result} so
     * the caller can note a pending or failed flush; failures are non-fatal and
     * logged here.
     */
    private BmExportHelper.Result persistFormChanges(IBmModelManager bmModelManager,
        IProject project, String formFqn)
    {
        BmExportHelper.Result r = BmExportHelper.forceExportAndWait(bmModelManager, project,
            java.util.Collections.singletonList(formFqn), 5_000L);
        if (r != null && r.syncFlushPending)
        {
            Activator.logWarning("persistFormChanges: " + formFqn //$NON-NLS-1$
                + " committed to BM, disk flush pending"); //$NON-NLS-1$
        }
        else if (r != null && !r.isOk() && r.error != null)
        {
            Activator.logWarning("persistFormChanges: forceExport for " + formFqn //$NON-NLS-1$
                + " did not complete cleanly: " + r.error); //$NON-NLS-1$
        }
        return r;
    }

    /**
     * Row 42: builds a plain-text note appended to a form-op success message
     * when the disk flush is pending or the persist warned; {@code null} when
     * the flush is clean. Kept human-readable (not a machine tag) because the
     * ~21 form ops surface their message string through many different response
     * builders - the note rides that one common channel. The token
     * {@code diskFlushPending} is included so an agent can still detect it.
     */
    private static String persistNote(BmExportHelper.Result r)
    {
        if (r == null)
        {
            return null;
        }
        if (r.syncFlushPending)
        {
            return "[diskFlushPending: change committed to the BM model but the .form disk " //$NON-NLS-1$
                + "flush did not confirm within the wait budget - run resync_to_disk to force it]"; //$NON-NLS-1$
        }
        if (!r.isOk() && r.error != null)
        {
            return "[persistWarning: forceExport did not complete cleanly (" + r.error //$NON-NLS-1$
                + ") - the .form on disk may be stale]"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Builds a diagnostic suffix listing BM top-object FQNs that contain the
     * object name or form name from the requested FQN. Helps the caller discover
     * the actual FQN used by the BM namespace (e.g. for borrowed forms in
     * extensions where the FQN format may differ from the main configuration).
     */
    private String suggestSimilarFqns(Object transaction, String requestedFqn)
    {
        try
        {
            String[] parts = requestedFqn.split("\\."); //$NON-NLS-1$
            String objectName = parts.length >= 2 ? parts[1] : null;
            String formName = parts.length >= 1 ? parts[parts.length - 1] : null;

            @SuppressWarnings("unchecked")
            Iterator<Object> iter = (Iterator<Object>) txIface.getMethod("getTopObjectIterator") //$NON-NLS-1$
                .invoke(transaction);

            List<String> objectMatches = new ArrayList<>();
            List<String> formMatches = new ArrayList<>();
            int scanned = 0;
            while (iter.hasNext() && objectMatches.size() + formMatches.size() < 30)
            {
                Object obj = iter.next();
                scanned++;
                String fqn = (String) obj.getClass().getMethod("bmGetFqn").invoke(obj); //$NON-NLS-1$
                if (fqn == null)
                {
                    continue;
                }
                if (objectName != null && fqn.contains(objectName) && objectMatches.size() < 15)
                {
                    objectMatches.add(fqn);
                }
                else if (formName != null && fqn.contains(formName) && formMatches.size() < 15)
                {
                    formMatches.add(fqn);
                }
            }

            StringBuilder hint = new StringBuilder();
            if (!objectMatches.isEmpty())
            {
                hint.append("\n\nTop-objects containing '").append(objectName).append("':\n"); //$NON-NLS-1$ //$NON-NLS-2$
                for (String fqn : objectMatches)
                {
                    hint.append("  - ").append(fqn).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            if (!formMatches.isEmpty())
            {
                hint.append("\n\nTop-objects containing '").append(formName).append("':\n"); //$NON-NLS-1$ //$NON-NLS-2$
                for (String fqn : formMatches)
                {
                    hint.append("  - ").append(fqn).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            if (hint.length() == 0)
            {
                hint.append("\n\nNo matching top-objects found (scanned ").append(scanned).append(")."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return hint.toString();
        }
        catch (Exception e)
        {
            return "\n\n(diagnostic scan failed: " + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Finds a method by name on an object where the first parameter is assignable
     * from the given class and the method has exactly 2 parameters.
     *
     * @param target the object to search on
     * @param methodName the method name
     * @param firstParamType the expected first parameter type, or {@code null} to skip the check
     * @return the found method, or {@code null}
     */
    private Method findExecuteMethod(Object target, String methodName, Class<?> firstParamType)
    {
        for (Method method : target.getClass().getMethods())
        {
            if (methodName.equals(method.getName()) && method.getParameterCount() == 2)
            {
                if (firstParamType == null
                    || method.getParameterTypes()[0].isAssignableFrom(firstParamType))
                {
                    return method;
                }
            }
        }
        return null;
    }
}
