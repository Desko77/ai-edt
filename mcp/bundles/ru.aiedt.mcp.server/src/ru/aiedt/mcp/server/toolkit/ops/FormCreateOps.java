package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmCommonFormPostCreate;
import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;
import ru.aiedt.mcp.server.support.BmExportHelper;
import ru.aiedt.mcp.server.support.FormBaseSetup;
import ru.aiedt.mcp.server.support.BmFormGeneratorHelper;
import ru.aiedt.mcp.server.support.BmFormResourceHelper;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.BmSubsystemHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Form-creation cluster of {@code edit_metadata}: {@code create_form} and its
 * supporting machinery (form-type/purpose derivation, inner-form content build,
 * generated-form attach, owner-type mapping). Extracted verbatim from
 * {@link EditMetadataTool} (Inc4 god-class split); the handler is package-visible
 * and dispatched through the single-source op-registry. Shared stateless helpers
 * live on {@link EditMetadataTool} (qualified calls); the 19 cluster-local creation
 * helpers (normalizeFormType, extractCommonFormName, isCommonFormType,
 * generatorRunsFor, generatorRefusalText, purposeToken, formPurposeFor, defaultFormSetterFor,
 * deriveFormPurpose, equalsAny, nameContains, isObjectOwningType, isRegisterType,
 * mainTypeFqnForOwner, ensureObjectFormContent, resolveFormFactory, applyAdjustableCommon,
 * createAdjustableBooleanCommon, invokeNoArg, isBoxedMatch, attachGeneratedForm) are private
 * here. The CommonForm
 * redirect delegates to {@link ObjectOps#opCreateObject} via a local ObjectOps ref.
 */
final class FormCreateOps
{
    private final ObjectOps objectOps = new ObjectOps();

    // -----------------------------------------------------------------------
    // Form constructor operations (Phase 6.1)
    // -----------------------------------------------------------------------

    /**
     * Bug A: normalizes the {@code formType} create_form parameter to a value
     * the EMF {@code FormType} enum accepts. {@code FormType} has only two
     * literals - {@code ORDINARY} and {@code MANAGED} - whereas callers (and
     * earlier docs) used "purpose" values like {@code Generic},
     * {@code DocumentForm}, {@code CatalogForm}, {@code ListForm},
     * {@code ItemForm}, {@code ChoiceForm}. Those are not enum constants, so
     * {@code BmObjectHelper.setProperty(form,"formType",...)} threw
     * "No enum constant FormType.Generic".
     * <p>
     * Rule: {@code null}/blank or any legacy purpose -&gt; {@code "MANAGED"};
     * explicit {@code ORDINARY} (case-insensitive) -&gt; {@code "ORDINARY"};
     * explicit {@code MANAGED} -&gt; {@code "MANAGED"}. The returned literal is
     * the Java constant name, which {@code coerceValue} resolves via its
     * {@code Enum.valueOf} fallback when the EMF {@code get(literal)} lookup
     * returns null.
     *
     * @param raw the caller-supplied formType (may be null/empty/legacy)
     * @return {@code "MANAGED"} or {@code "ORDINARY"} (never null/empty)
     */
    private static String normalizeFormType(String raw)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return "MANAGED"; //$NON-NLS-1$
        }
        String v = raw.trim();
        if ("ORDINARY".equalsIgnoreCase(v) || "Ordinary".equalsIgnoreCase(v)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "ORDINARY"; //$NON-NLS-1$
        }
        // MANAGED, Managed, and every legacy purpose value (Generic /
        // DocumentForm / CatalogForm / ListForm / ItemForm / ChoiceForm / ...)
        // collapse to the only managed-form literal.
        return "MANAGED"; //$NON-NLS-1$
    }

    /**
     * Creates a new form on a metadata owner (Catalog / Document / Report / etc.).
     * <p>
     * A managed form without an explicit {@code layout=empty} is built by EDT's own
     * {@code IFormGenerator} - the engine the New Form wizard drives - inside the same BM
     * read-write transaction that creates the wrapper: main attribute, default field layout,
     * command interface. {@code formType=ORDINARY} and an explicit {@code layout=empty} skip
     * the generator; the form is then created by the empty path (inner form, base properties,
     * deterministic main attribute).
     * <p>
     * When the generator was asked for and did not deliver a form - no generator on this
     * runtime, no field tree, an exception, a form that would not attach - the whole operation
     * is refused: throwing from inside the BM task rolls the transaction back (the same abort
     * {@code dryRun} and the guards use), so no wrapper, no owner .mdo entry and no Form.form
     * are left behind, and the response names the reason. The response then carries
     * {@code formGeneratorNotFound} or {@code formGeneratorFailed} plus the generator facts
     * ({@code formFieldsOverloadArgs}, {@code formFieldTreeSize}, {@code formCoercionMismatches},
     * {@code formFieldsError}) and the {@code layout=empty} retry that creates a form without
     * the generator. A successful generation answers {@code formGenerated=true} with
     * {@code formLayout=auto}; an explicit {@code layout=empty} answers
     * {@code formLayout=empty}.
     * <p>
     * The Form.form file is force-exported after the transaction commits, so the form is on
     * disk and discoverable by FQN, not only in the in-session BM.
     */
    String opCreateForm(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        // 1.42.3: accept `name` as a fallback for `formName` so the unified
        // schema (which exposes `name` as the generic element-name parameter)
        // works for createForm out of the box. Single final assignment keeps
        // the lambda capture below effectively-final.
        String formNameRaw = JsonUtils.extractStringArgument(params, "formName"); //$NON-NLS-1$
        final String formName = (formNameRaw != null && !formNameRaw.isEmpty())
            ? formNameRaw
            : JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String formTypeRaw = JsonUtils.extractStringArgument(params, "formType"); //$NON-NLS-1$
        // Bug A: the EMF FormType enum has only ORDINARY / MANAGED. Legacy
        // "purpose" values (Generic / DocumentForm / CatalogForm / ListForm /
        // ItemForm / ChoiceForm / ...) were never valid enum constants and made
        // setProperty throw "No enum constant FormType.Generic". Normalize here:
        // null/empty or any legacy purpose -> MANAGED; explicit ORDINARY ->
        // ORDINARY (Java constant name, resolved by coerceValue's Enum.valueOf
        // fallback). Managed forms are the only ones EDT 2026.1 creates anyway.
        final String formType = normalizeFormType(formTypeRaw);
        String layout = JsonUtils.extractStringArgument(params, "layout"); //$NON-NLS-1$
        // Optional form purpose (ItemForm / ListForm / ChoiceForm / ...). Drives
        // the EDT form-generator FormType when present; when omitted the purpose
        // is derived from the owner metadata type and the form name (see
        // deriveFormPurpose). Captured into the lambda below.
        final String purposeRaw = JsonUtils.extractStringArgument(params, "purpose"); //$NON-NLS-1$
        boolean setAsDefault = JsonUtils.extractBooleanArgument(params, "setAsDefault", false); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        // projectName + ownerFqn are required for every path; formName is
        // validated separately below because the CommonForm overload accepts
        // the name through ownerFqn (CommonForm.<Name>) instead.
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        // CommonForm overload: a common form is itself a top-level form and has
        // no Forms collection, so create_form cannot attach a child form to it
        // (the bare owner.getForms() lookup below would fail with "Owner type
        // 'CommonForm' has no Forms collection"). The canonical creation path is
        // create_object objectType=CommonForm, which also builds the inner Form
        // (defensive layer 3.8.4). When the caller addressed CommonForm through
        // create_form, route to that path instead of failing.
        if (isCommonFormType(ownerFqn))
        {
            return createCommonFormViaCreateForm(params, project, ownerFqn, formName);
        }
        // Regular owner path: formName names the child form and is mandatory.
        String formNameErr = EditMetadataTool.requireNonEmpty(formName, "formName"); //$NON-NLS-1$
        if (!formNameErr.isEmpty())
        {
            return ToolResult.error(formNameErr.trim()).toJson();
        }
        // Audit W06 F3: a name that is not one path element cannot address the form's folder on
        // disk. Writing the .mdo first and discovering this afterwards left a form the object no
        // longer resolved (measured: formName=../TraversalProbe).
        if (!MetadataGuards.isPlainName(formName))
        {
            return ToolResult.error("formName must be a plain name, got '" + formName + "'") //$NON-NLS-1$ //$NON-NLS-2$
                .put("operation", "create_form") //$NON-NLS-1$ //$NON-NLS-2$
                .put("hint", "A form name is a single path element: no separator, no '..'.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        // The form's purpose, resolved once and used by every step that depends on it: the
        // generator's FormType, the deterministic main attribute, and the default-form property
        // setAsDefault points at. An explicit `purpose` states it; a legacy `formType` value that
        // names a purpose (ItemForm / ListForm / ...) still does; without either, the owner type and
        // the form name decide.
        final String purposeConstant = formPurposeFor(purposeRaw, formTypeRaw, ownerFqn, formName);
        // 3.8.3: track scaffold tags for response
        AtomicReference<Integer> scaffoldedProps = new AtomicReference<>(0);
        // An empty managed form is born without the base properties the EDT wizard sets. Ordinary
        // forms carry their own layout, so the scaffolding applies to managed forms only.
        final boolean isManagedEmpty = "empty".equalsIgnoreCase(layout) //$NON-NLS-1$
            && !"ORDINARY".equalsIgnoreCase(formType); //$NON-NLS-1$
        // Audit B2/G10: capture setter / inner-form-attach failures that were
        // previously only logged, so the JSON response tells the agent that part
        // of create_form silently did not apply (otherwise a follow-up form op
        // fails later with a confusing "not found"). Thread-safe holders because
        // the BM write task may run off the calling thread.
        java.util.List<String> formSetterWarnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<String> innerFormAttach = new AtomicReference<>(null);
        // Form-generator path (renderable form, identical to EDT "New Form"
        // wizard) outcome holders. formGenerated / formPurpose carry success;
        // formGeneratorNotFound / formGeneratorFailed carry the refusal the
        // operation answers with when the generator did not deliver (no form
        // is created then - the transaction rolls back). The full generator
        // Result is kept too: it names the layout the generator actually
        // produced (item count, fields-overload arity, field-tree size,
        // argument type mismatches, the exception the field computation threw),
        // which is what tells an impoverished tree from a wizard-grade one
        // without opening the form.
        AtomicReference<String> formGeneratedRef = new AtomicReference<>(null);
        AtomicReference<String> formGeneratorMiss = new AtomicReference<>(null);
        AtomicReference<BmFormGeneratorHelper.Result> generatorOutcomeRef =
            new AtomicReference<>(null);
        // Deterministic-content holders. Whatever path produces the inner Form
        // (generator or empty fallback), an OBJECT/RECORD purpose form still
        // needs a main attribute (so it has a data context and renders) plus an
        // autoCommandBar. mainAttrAddedRef / autoCmdBarAddedRef carry the
        // outcome; innerFormModelRef hands the inner form.model.Form to the
        // post-commit forceExport (step C). All are best-effort - failures are
        // logged + tagged, never fatal.
        AtomicReference<String> mainAttrAddedRef = new AtomicReference<>(null);
        AtomicReference<Boolean> autoCmdBarAddedRef = new AtomicReference<>(Boolean.FALSE);
        AtomicReference<Object> innerFormModelRef = new AtomicReference<>(null);
        // True once the inner Form was successfully registered as a BM
        // top-object (either path). The post-commit forceExport (step C) can
        // only serialize a form that was attached; when this is false we fall
        // back to writing an empty Form.form stub so the form is at least
        // discoverable on disk.
        AtomicReference<Boolean> innerAttachOkRef = new AtomicReference<>(Boolean.FALSE);
        java.util.List<String> contentWarnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        // Configuration is needed by the generator for scriptVariant /
        // interfaceCompatibilityMode; resolve it once outside the BM task.
        IConfigurationProvider formGenConfigProvider = Activator.getDefault().getConfigurationProvider();
        final Configuration formGenConfig = formGenConfigProvider != null
            ? formGenConfigProvider.getConfiguration(project) : null;
        final IProject formGenProject = project;
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                @SuppressWarnings("unchecked")
                EList<MdObject> forms = (EList<MdObject>) EditMetadataTool.invokeListGetter(owner, "getForms"); //$NON-NLS-1$
                if (forms == null)
                {
                    throw new RuntimeException("Unsupported owner type '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no Forms collection."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(forms, formName) != null)
                {
                    throw BmObjectHelper.alreadyExists(formName, ownerFqn, "form"); //$NON-NLS-1$
                }
                MdObject form = BmObjectHelper.createOwnerScopedObject(owner, "Form"); //$NON-NLS-1$
                if (form == null)
                {
                    throw new RuntimeException("Cannot create form under '" //$NON-NLS-1$
                        + owner.eClass().getName()
                        + "': no compatible MdClassFactory method found " //$NON-NLS-1$
                        + "(tried create" + owner.eClass().getName() + "Form, " //$NON-NLS-1$ //$NON-NLS-2$
                        + "MdClassPackage EClass lookup)."); //$NON-NLS-1$
                }
                form.setName(formName);
                if (formType != null && !formType.isEmpty())
                {
                    String setErr = BmObjectHelper.setProperty(form, "formType", formType); //$NON-NLS-1$
                    if (setErr != null)
                    {
                        Activator.logWarning("createForm: " + setErr); //$NON-NLS-1$
                        formSetterWarnings.add("formType: " + setErr); //$NON-NLS-1$
                    }
                }
                forms.add(form);

                // Fix 3 (2026-07-03): give the new form the .mdo defaults the
                // wizard writes - application use-purposes, and (for single-slot
                // owners like DataProcessor / Report) the object's default form.
                // Without usePurposes the form is not offered on any client;
                // without defaultForm a DataProcessor / Report opens with no main
                // form. Both best-effort - a miss is warned, never fatal.
                try
                {
                    Object formPurposes = form.getClass().getMethod("getUsePurposes").invoke(form); //$NON-NLS-1$
                    if (formPurposes instanceof EList && ((EList<?>) formPurposes).isEmpty()
                        && formGenConfig != null)
                    {
                        Object cfgPurposes = formGenConfig.getUsePurposes();
                        if (cfgPurposes instanceof java.util.Collection)
                        {
                            @SuppressWarnings("unchecked")
                            EList<Object> fp = (EList<Object>) formPurposes;
                            // enum singletons - reuse the config's own constants
                            for (Object p : (java.util.Collection<?>) cfgPurposes)
                            {
                                fp.add(p);
                            }
                        }
                    }
                }
                catch (Exception ue)
                {
                    formSetterWarnings.add("usePurposes: " + ue.getMessage()); //$NON-NLS-1$
                }
                // Owner-aware default form: DataProcessor / Report expose a single
                // getDefaultForm/setDefaultForm slot; Catalog/Document instead use
                // defaultObjectForm / defaultListForm (the setAsDefault path below).
                // Only fill a NULL single-slot default here - never override one the
                // owner already has, and never touch owners without that slot.
                try
                {
                    java.lang.reflect.Method getDef = owner.getClass().getMethod("getDefaultForm"); //$NON-NLS-1$
                    if (getDef.invoke(owner) == null)
                    {
                        String defErr = BmObjectHelper.setProperty(owner, "defaultForm", form); //$NON-NLS-1$
                        if (defErr != null)
                        {
                            formSetterWarnings.add("defaultForm: " + defErr); //$NON-NLS-1$
                        }
                    }
                }
                catch (NoSuchMethodException noDefSlot)
                {
                    // owner has no single defaultForm slot (Catalog/Document/...) - skip
                }
                catch (Exception de)
                {
                    formSetterWarnings.add("defaultForm: " + de.getMessage()); //$NON-NLS-1$
                }

                // setAsDefault - point the owner's default-list / default-object / ... property at
                // this form. Which property that is follows from the form's purpose (audit W05 F3:
                // the choice used to be made from the raw formType, so a caller who gave a purpose
                // and no formType got no setter at all and an answer of success).
                if (setAsDefault)
                {
                    String setterName = defaultFormPropertyFor(purposeConstant, owner);
                    if (setterName == null)
                    {
                        // Nothing to point at: the purpose names no default-form property. Say so
                        // instead of answering success.
                        formSetterWarnings.add("setAsDefault: the " + purposeConstant //$NON-NLS-1$
                            + " purpose names no default-form property of " + ownerFqn); //$NON-NLS-1$
                    }
                    else
                    {
                        String setErr = BmObjectHelper.setProperty(owner, setterName, form);
                        if (setErr != null)
                        {
                            Activator.logWarning("createForm setAsDefault: " + setErr); //$NON-NLS-1$
                            formSetterWarnings.add("setAsDefault: " + setErr); //$NON-NLS-1$
                        }
                    }
                }

                // PREFERRED PATH: invoke EDT's IFormGenerator to build a
                // RENDERABLE managed form (owner main attribute + default
                // layout), identical to the "New Form" wizard. The headless
                // empty path below only builds a bare Form root, so a form
                // created that way opens "empty". The generator runs inside
                // this same BM read-write transaction (the EMF objects it
                // builds belong to the model graph). An ORDINARY form and an
                // explicit layout=empty never reach it.
                boolean generatedAttached = false;
                if (generatorRunsFor(formType, layout))
                {
                    BmFormGeneratorHelper.Result genResult = BmFormGeneratorHelper.generate(
                        owner, form, purposeConstant, formGenConfig, formGenProject);
                    generatorOutcomeRef.set(genResult);
                    if (genResult.ok && genResult.generatedForm != null)
                    {
                        // Attach the generated Form root to the BasicForm
                        // wrapper (wrapper.setForm(AbstractForm)) - reuse the
                        // name-probe attach so the exact setter name does not
                        // need to be hard-coded.
                        boolean attachedToWrapper =
                            attachGeneratedForm(form, genResult.generatedForm);
                        if (attachedToWrapper
                            && genResult.generatedForm instanceof IBmObject)
                        {
                            String formFqn = ownerFqn + ".Form." + formName + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
                            try
                            {
                                tx.attachTopObject((IBmObject) genResult.generatedForm, formFqn);
                                innerFormAttach.set("attached " + formFqn //$NON-NLS-1$
                                    + " (generated)"); //$NON-NLS-1$
                                formGeneratedRef.set(genResult.formPurpose);
                                // The generated Form root IS the inner
                                // form.model.Form - hand it to the deterministic
                                // A/B step and the post-commit forceExport.
                                innerFormModelRef.set(genResult.generatedForm);
                                innerAttachOkRef.set(Boolean.TRUE);
                                generatedAttached = true;
                            }
                            catch (Exception attachEx)
                            {
                                Activator.logWarning("createForm attachTopObject(" //$NON-NLS-1$
                                    + formFqn + ") for generated form failed: " //$NON-NLS-1$
                                    + attachEx.getMessage());
                                innerFormAttach.set("generated-form attach failed (" //$NON-NLS-1$
                                    + formFqn + "): " + attachEx.getMessage()); //$NON-NLS-1$
                                formGeneratorMiss.set("generated-form top-object attach failed: " //$NON-NLS-1$
                                    + attachEx.getMessage()); //$NON-NLS-1$
                            }
                        }
                        else if (!attachedToWrapper)
                        {
                            Activator.logWarning("createForm: generated form could not " //$NON-NLS-1$
                                + "be attached to wrapper"); //$NON-NLS-1$
                            formGeneratorMiss.set("generated form not attachable to wrapper"); //$NON-NLS-1$
                        }
                        else
                        {
                            // Attached to the wrapper but not an IBmObject: it cannot be
                            // registered as a BM top-object, so no follow-up operation would
                            // resolve the form by FQN - a refusal, not an undiscoverable form.
                            formGeneratorMiss.set("generated form is not a BM object: " //$NON-NLS-1$
                                + genResult.generatedForm.getClass().getName()); //$NON-NLS-1$
                        }
                    }
                    else if (genResult.generatorNotFound)
                    {
                        formGeneratorMiss.set("not-found"); //$NON-NLS-1$
                    }
                    else if (genResult.error != null)
                    {
                        Activator.logWarning("createForm form generator: " //$NON-NLS-1$
                            + genResult.error);
                        formGeneratorMiss.set("failed: " + genResult.error); //$NON-NLS-1$
                    }
                }
                // A form that asked for the generated layout and did not get one is not
                // created: throwing here aborts the BM task (the same abort dryRun and the
                // guards use), the transaction rolls back and nothing reaches disk - the
                // caller is told why and offered the layout=empty retry, which creates the
                // form without asking the generator. ORDINARY and layout=empty never reach
                // this: they skip the generator by design, so they cannot be refused over
                // its miss.
                if (generatorRunsFor(formType, layout) && !generatedAttached)
                {
                    throw new RuntimeException(generatorRefusalText(formGeneratorMiss.get()));
                }

                // EMPTY PATH (an ORDINARY form or an explicit layout=empty; a
                // generator miss was already refused above): attach the inner
                // Form as a BM top-object so subsequent edit_form /
                // get_form_structure / add_field operations can resolve the
                // form by FQN
                // <ownerFqn>.Form.<formName>.Form. Without this the form
                // exists in the wrapper's containment list but is not
                // discoverable via tx.getTopObjectByFqn(...) - every
                // follow-up form call fails with "Form not found by FQN".
                // The createInnerForm helper builds an empty inner Form when
                // the wrapper does not yet have one (FormBaseSetup-backed)
                // and is safe to call for both CommonForm and object-owned
                // form wrappers.
                BmCommonFormPostCreate.PostCreateResult formPcr = generatedAttached
                    ? null : BmCommonFormPostCreate.createInnerForm(form);
                if (formPcr != null && formPcr.ok)
                {
                    Object innerForm = null;
                    for (String getter : new String[] { "getFormAttachedForm", //$NON-NLS-1$
                        "getForm", "getRootContainer" }) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        try
                        {
                            java.lang.reflect.Method m = form.getClass().getMethod(getter);
                            Object res = m.invoke(form);
                            if (res != null)
                            {
                                innerForm = res;
                                break;
                            }
                        }
                        catch (NoSuchMethodException ignored)
                        {
                            // try next getter
                        }
                        catch (Exception getterEx)
                        {
                            Activator.logWarning("createForm getter " + getter //$NON-NLS-1$
                                + " threw: " + getterEx.getMessage()); //$NON-NLS-1$
                        }
                    }
                    // Hand the empty inner form to the deterministic A/B step
                    // (and the post-commit forceExport) so an OBJECT/RECORD form
                    // gets a main attribute + autoCommandBar even when the
                    // generator was unavailable.
                    if (innerForm != null)
                    {
                        innerFormModelRef.set(innerForm);
                    }
                    if (innerForm instanceof IBmObject)
                    {
                        String formFqn = ownerFqn + ".Form." + formName + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
                        try
                        {
                            tx.attachTopObject((IBmObject) innerForm, formFqn);
                            innerFormAttach.set("attached " + formFqn); //$NON-NLS-1$
                            innerAttachOkRef.set(Boolean.TRUE);
                        }
                        catch (Exception attachEx)
                        {
                            Activator.logWarning("createForm attachTopObject(" //$NON-NLS-1$
                                + formFqn + ") failed: " + attachEx.getMessage()); //$NON-NLS-1$
                            innerFormAttach.set("attach failed (" + formFqn + "): " //$NON-NLS-1$ //$NON-NLS-2$
                                + attachEx.getMessage());
                        }
                    }
                    else
                    {
                        Activator.logWarning("createForm: inner Form object not " //$NON-NLS-1$
                            + "obtainable via getFormAttachedForm/getForm - " //$NON-NLS-1$
                            + "follow-up operations may not find this form by FQN"); //$NON-NLS-1$
                        innerFormAttach.set("inner Form object not obtainable - " //$NON-NLS-1$
                            + "follow-up form operations may not resolve this form by FQN"); //$NON-NLS-1$
                    }
                }
                else if (formPcr != null && formPcr.error != null)
                {
                    Activator.logWarning("createForm inner-form post-create: " //$NON-NLS-1$
                        + formPcr.error);
                    innerFormAttach.set("inner-form post-create failed: " + formPcr.error); //$NON-NLS-1$
                }

                // DETERMINISTIC CONTENT (steps A + B). Whatever produced the
                // inner Form, guarantee an OBJECT/RECORD form has a main
                // attribute (so it carries a data context and renders) plus an
                // autoCommandBar. The full wizard auto-layout is not required -
                // the main attribute + valueType are what make the designer
                // render the form and what keep the IB load from failing. This
                // mutates the model so it MUST stay inside this transaction. For
                // non-OBJECT purposes (LIST / GENERIC / register RECORD_SET) we
                // skip the main attribute and just ensure the autoCommandBar.
                Object innerFormForContent = innerFormModelRef.get();
                if (innerFormForContent != null && isManagedEmpty)
                {
                    // 3.8.3 defensive layer: the base root properties of a generated form
                    // (enabled, titles, window settings, autoCommandBar, commandInterface).
                    // A form born without them keeps the model defaults of those features -
                    // the booleans default to false, so the client would see a disabled
                    // form with no title and no close button. They live on the inner
                    // form.model.Form, so they can only be applied once that object
                    // exists - applying them to the metadata wrapper reached no setter at all and
                    // reported zero (audit W06 F1).
                    scaffoldedProps.set(FormBaseSetup.applyDefaults(innerFormForContent));
                }
                if (innerFormForContent != null && !"ORDINARY".equalsIgnoreCase(formType)) //$NON-NLS-1$
                {
                    ensureObjectFormContent(innerFormForContent, owner, ownerFqn,
                        purposeConstant, formGenProject, formGenConfig,
                        mainAttrAddedRef, autoCmdBarAddedRef, contentWarnings);
                    if (carriesAutoCommandBar(innerFormForContent))
                    {
                        autoCmdBarAddedRef.set(Boolean.TRUE);
                    }
                }
                return formName;
            });
        // ---- Step C: persist the inner Form to disk (CRITICAL) --------------
        // executeWriteOnObject force-exports the OWNER FQN (writes the .mdo with
        // its <forms> reference), but NOT the inner Form top-object - so without
        // an explicit forceExport on the inner form FQN the Form.form file is
        // never written and the form lives only in the in-session BM (lost on
        // reload, not renderable by the designer). Reuse the exact mechanism the
        // working add_field path uses (IBmModelManager.forceExport via
        // BmExportHelper). The serialized content is whatever the BM inner form
        // holds: generator output, the deterministic main attribute +
        // autoCommandBar, or (worst case) an empty FormBaseSetup root.
        //
        // Two on-disk outcomes:
        //  (a) ATTACHED (innerAttachOkRef): forceExport(innerFormFqn) serializes
        //      the populated inner form to Form.form, then ensure Module.bsl
        //      exists. We must NOT write an empty Form.form stub here - it would
        //      clobber the BM content.
        //  (b) NOT ATTACHED: the inner Form is not a discoverable top-object, so
        //      forceExport cannot find it. Fall back to writing an empty
        //      Form.form + Module.bsl (1.42.5 BUG-1424-B) so the form is at
        //      least present on disk and follow-up edit_form ops can populate it.
        boolean persistedToDisk = false;
        if (r.ok && !dryRun)
        {
            boolean innerAttached = Boolean.TRUE.equals(innerAttachOkRef.get());
            if (innerAttached)
            {
                String innerFormFqn = ownerFqn + ".Form." + formName + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
                try
                {
                    IBmModelManager bmm = Activator.getDefault().getBmModelManager();
                    if (bmm != null)
                    {
                        ru.aiedt.mcp.server.support.BmExportHelper.Result exp =
                            ru.aiedt.mcp.server.support.BmExportHelper.forceExportAndWait(
                                bmm, project, innerFormFqn);
                        if (exp != null && exp.isOk() && !exp.syncFlushPending)
                        {
                            persistedToDisk = true;
                        }
                        else if (exp != null && exp.syncFlushPending)
                        {
                            // Row 42: committed to BM but the disk flush did not
                            // confirm within the budget - do NOT claim
                            // persistedToDisk; the .form save finishes in the
                            // background.
                            r.tags.put("diskFlushPending", Boolean.TRUE); //$NON-NLS-1$
                            Activator.logWarning("createForm forceExport(" + innerFormFqn //$NON-NLS-1$
                                + ") committed to BM, disk flush pending"); //$NON-NLS-1$
                        }
                        else
                        {
                            String detail = exp != null && exp.error != null
                                ? exp.error : "forceExport returned not-ok"; //$NON-NLS-1$
                            Activator.logWarning("createForm forceExport(" + innerFormFqn //$NON-NLS-1$
                                + ") did not complete cleanly: " + detail); //$NON-NLS-1$
                            r.tags.put("persistFailed", detail); //$NON-NLS-1$
                        }
                    }
                    else
                    {
                        r.tags.put("persistFailed", "object model manager is not published as a service"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                catch (Exception persistEx)
                {
                    Activator.logWarning("createForm forceExport(" + innerFormFqn //$NON-NLS-1$
                        + ") threw: " + persistEx.getMessage()); //$NON-NLS-1$
                    r.tags.put("persistFailed", persistEx.getMessage()); //$NON-NLS-1$
                }
                // Ensure Module.bsl exists next to the (now serialized) Form.form
                // without writing an empty Form.form stub over the BM content.
                String moduleErr =
                    BmFormResourceHelper.writeModuleResourceOnly(project, ownerFqn, formName);
                if (moduleErr != null)
                {
                    Activator.logWarning("createForm Module.bsl write for " //$NON-NLS-1$
                        + ownerFqn + "/" + formName + ": " + moduleErr); //$NON-NLS-1$ //$NON-NLS-2$
                    r.tags.put("formResourceInitWarning", moduleErr); //$NON-NLS-1$
                }
            }
            else
            {
                // Inner form not attached - forceExport cannot target it. Write
                // a minimal Form.form + Module.bsl so the form exists on disk.
                String resourceErr =
                    BmFormResourceHelper.writeEmptyFormResources(project, ownerFqn, formName);
                if (resourceErr != null)
                {
                    Activator.logWarning("createForm Form.form/Module.bsl write for " //$NON-NLS-1$
                        + ownerFqn + "/" + formName + ": " + resourceErr); //$NON-NLS-1$ //$NON-NLS-2$
                    r.tags.put("formResourceInitWarning", resourceErr); //$NON-NLS-1$
                }
                else
                {
                    persistedToDisk = true;
                }
            }
        }
        ToolResult result = r.ok ? ToolResult.success() : ToolResult.error(r.error != null ? r.error : "createForm failed");
        result.put("operation", "create_form")
            .put("ownerFqn", r.fqn);
        if (r.message != null || r.ok)
        {
            result.put("message", r.message != null ? r.message : "ok");
        }
        if (scaffoldedProps.get() > 0)
        {
            result.put("formScaffolded", scaffoldedProps.get());
        }
        // Audit B2/G10: surface previously-silent setter / inner-form-attach
        // outcomes so success:true does not hide a partial create_form.
        if (!formSetterWarnings.isEmpty())
        {
            r.tags.put("formSetterWarnings", String.join("; ", formSetterWarnings)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (innerFormAttach.get() != null)
        {
            r.tags.put("innerFormAttach", innerFormAttach.get()); //$NON-NLS-1$
        }
        // Deterministic-content + persistence tags so the live test can confirm
        // the form is renderable (main attribute) and on disk (Form.form).
        if (mainAttrAddedRef.get() != null)
        {
            r.tags.put("mainAttributeAdded", Boolean.TRUE); //$NON-NLS-1$
            r.tags.put("mainAttributeType", mainAttrAddedRef.get()); //$NON-NLS-1$
        }
        if (Boolean.TRUE.equals(autoCmdBarAddedRef.get()))
        {
            r.tags.put("autoCommandBarAdded", Boolean.TRUE); //$NON-NLS-1$
        }
        if (!contentWarnings.isEmpty())
        {
            r.tags.put("mainAttributeFailed", String.join("; ", contentWarnings)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (r.ok && !dryRun)
        {
            r.tags.put("persistedToDisk", Boolean.valueOf(persistedToDisk)); //$NON-NLS-1$
        }
        // Form-generator outcome tags. formGenerated=true means a renderable
        // form (main attribute + default layout) was produced - the agent can
        // open it immediately. The miss tags carry the refusal the operation
        // answered with: no form exists, and layout=empty is the retry that
        // creates one without the generator.
        BmFormGeneratorHelper.Result generatorOutcome = generatorOutcomeRef.get();
        if (generatorOutcome != null)
        {
            // What the generator was actually given and built, success or miss:
            // the getFormGeneratorFields overload that answered and the size of
            // its field tree, the item count of the produced layout, every
            // generateForm argument whose value failed its parameter type, and
            // the exception the field computation threw, if it threw one.
            r.tags.put("formFieldsOverloadArgs", Integer.valueOf(generatorOutcome.fieldsOverloadArgs)); //$NON-NLS-1$
            if (generatorOutcome.fieldTreeSize >= 0)
            {
                r.tags.put("formFieldTreeSize", Integer.valueOf(generatorOutcome.fieldTreeSize)); //$NON-NLS-1$
            }
            if (generatorOutcome.itemCount >= 0)
            {
                r.tags.put("formItemCount", Integer.valueOf(generatorOutcome.itemCount)); //$NON-NLS-1$
            }
            if (!generatorOutcome.coercionMismatches.isEmpty())
            {
                r.tags.put("formCoercionMismatches", //$NON-NLS-1$
                    String.join("; ", generatorOutcome.coercionMismatches)); //$NON-NLS-1$
            }
            if (generatorOutcome.fieldsError != null)
            {
                r.tags.put("formFieldsError", generatorOutcome.fieldsError); //$NON-NLS-1$
            }
        }
        if (formGeneratedRef.get() != null)
        {
            r.tags.put("formGenerated", Boolean.TRUE); //$NON-NLS-1$
            r.tags.put("formPurpose", formGeneratedRef.get()); //$NON-NLS-1$
            // The generated layout is the generator's automatic one (the same
            // engine the New Form wizard drives), named the way a caller
            // comparing layouts expects.
            r.tags.put("formLayout", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (formGeneratorMiss.get() != null)
        {
            String miss = formGeneratorMiss.get();
            if ("not-found".equals(miss)) //$NON-NLS-1$
            {
                r.tags.put("formGeneratorNotFound", Boolean.TRUE); //$NON-NLS-1$
                r.tags.put("hint", "EDT form generator unavailable on this runtime - no " //$NON-NLS-1$
                    + "form was created; retry with layout=empty to create the form " //$NON-NLS-1$
                    + "without the generator."); //$NON-NLS-1$
            }
            else
            {
                r.tags.put("formGeneratorFailed", miss); //$NON-NLS-1$
                r.tags.put("hint", "Form generator did not complete - no form was created; " //$NON-NLS-1$
                    + "retry with layout=empty to create the form without the generator."); //$NON-NLS-1$
            }
        }
        if (isManagedEmpty)
        {
            // The caller asked for the empty layout by name and got it: the
            // generator was never asked.
            r.tags.put("formLayout", "empty"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        EditMetadataTool.applyTags(result, r.tags);
        return result.toJson();
    }

    /**
     * CommonForm overload for {@link #opCreateForm}: a common form has no Forms
     * collection (it <em>is</em> the form), so we create it as a top-level
     * object via {@link #opCreateObject} - which already builds the inner Form
     * (defensive layer 3.8.4), fills the synonym, attaches the top-object and
     * honours {@code dryRun} - instead of failing. The common form name is taken
     * from {@code ownerFqn=CommonForm.<Name>} when present, otherwise from
     * {@code formName}. A pre-check rejects an already existing common form with
     * an actionable hint, because {@code create_object} does not guard against
     * duplicates.
     * <p>
     * TODO (form generator): this path still routes through
     * {@code create_object -> BmCommonFormPostCreate.createInnerForm}, i.e. the
     * empty inner form. A CommonForm has no owner metadata, so the natural
     * generator purpose is {@code GENERIC}; wiring the generator here is a
     * separate concern from the object-owned form path fixed above and is left
     * as a follow-up to keep this change focused.
     */
    private String createCommonFormViaCreateForm(Map<String, String> params, IProject project,
        String ownerFqn, String formName)
    {
        String commonFormName = extractCommonFormName(ownerFqn, formName);
        if (commonFormName == null || commonFormName.isEmpty())
        {
            return ToolResult.error("CommonForm create_form: provide the common form name " //$NON-NLS-1$
                + "via ownerFqn=CommonForm.<Name> or formName=<Name>.") //$NON-NLS-1$
                .put("operation", "create_form") //$NON-NLS-1$ //$NON-NLS-2$
                .put("redirectedFrom", "create_form->create_object(CommonForm)") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        String innerFormFqn = "CommonForm." + commonFormName + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
        // create_object does not reject duplicates - guard here so create_form
        // on an existing common form returns a clear already-exists hint instead
        // of a low-level BM top-object attach failure.
        IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
        Configuration config = cp != null ? cp.getConfiguration(project) : null;
        if (config != null
            && MetadataTypeCatalog.findObject(config, "CommonForm", commonFormName) != null) //$NON-NLS-1$
        {
            return ToolResult.error("CommonForm." + commonFormName + " already exists.") //$NON-NLS-1$ //$NON-NLS-2$
                .put("operation", "create_form") //$NON-NLS-1$ //$NON-NLS-2$
                .put(ErrorTags.ALREADY_EXISTS.wire(), true)
                .put("innerFormFqn", innerFormFqn) //$NON-NLS-1$
                .put("hint", "The common form already exists; edit its layout via " //$NON-NLS-1$
                    + "add_field / add_group / edit_form using ownerFqn=" + innerFormFqn) //$NON-NLS-1$
                .toJson();
        }
        // Delegate to create_object (objectType=CommonForm). Copy the incoming
        // params so projectName / synonym / dryRun carry over verbatim.
        Map<String, String> redirect = new LinkedHashMap<>(params);
        redirect.put("objectType", "CommonForm"); //$NON-NLS-1$ //$NON-NLS-2$
        redirect.put("name", commonFormName); //$NON-NLS-1$
        // Drop create_form-only keys so they cannot be silently picked up if a
        // future opCreateObject starts reading a key of the same name.
        redirect.remove("ownerFqn"); //$NON-NLS-1$
        redirect.remove("formName"); //$NON-NLS-1$
        redirect.remove("formType"); //$NON-NLS-1$
        redirect.remove("layout"); //$NON-NLS-1$
        redirect.remove("setAsDefault"); //$NON-NLS-1$
        String json = objectOps.opCreateObject(redirect);
        // Augment the create_object response so the agent sees the redirect and
        // learns the inner form FQN to target with the follow-up layout edits.
        try
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = GsonHolder.fromJson(json, Map.class);
            if (m != null)
            {
                m.put("redirectedFrom", "create_form"); //$NON-NLS-1$ //$NON-NLS-2$
                m.put("note", "CommonForm has no Forms collection - created as a top-level " //$NON-NLS-1$
                    + "common form via create_object."); //$NON-NLS-1$
                if (Boolean.TRUE.equals(m.get("success"))) //$NON-NLS-1$
                {
                    m.put("innerFormFqn", innerFormFqn); //$NON-NLS-1$
                }
                return GsonHolder.toJson(m);
            }
        }
        catch (Exception ex)
        {
            Activator.logWarning("create_form CommonForm redirect: could not augment " //$NON-NLS-1$
                + "create_object response: " + ex.getMessage()); //$NON-NLS-1$
        }
        return json;
    }

    /**
     * Resolves the common form name for the {@link #opCreateForm} CommonForm
     * overload. Prefers the name segment of {@code ownerFqn=CommonForm.<Name>}
     * (tolerating a full inner FQN like {@code CommonForm.X.Form.Form} by keeping
     * only {@code X}); falls back to {@code formName} when the owner FQN carries
     * no name segment (e.g. {@code ownerFqn=CommonForm}).
     */
    private static String extractCommonFormName(String ownerFqn, String formName)
    {
        if (ownerFqn != null)
        {
            int dot = ownerFqn.indexOf('.');
            if (dot > 0 && dot < ownerFqn.length() - 1)
            {
                String tail = ownerFqn.substring(dot + 1).trim();
                int sub = tail.indexOf('.');
                if (sub >= 0)
                {
                    // Keep only the first segment (handles a full inner FQN like
                    // CommonForm.X.Form.Form and rejects a leading-dot value such
                    // as CommonForm..X, which collapses to empty -> formName fallback).
                    tail = tail.substring(0, sub).trim();
                }
                if (!tail.isEmpty())
                {
                    return tail;
                }
            }
        }
        return formName != null ? formName.trim() : null;
    }

    /**
     * True when {@code ownerFqn} addresses the CommonForm type (English
     * {@code CommonForm} or Russian {@code ОбщаяФорма}), with or without a name
     * segment. Used to route {@link #opCreateForm} to the create_object path.
     */
    private static boolean isCommonFormType(String ownerFqn)
    {
        if (ownerFqn == null || ownerFqn.isEmpty())
        {
            return false;
        }
        int dot = ownerFqn.indexOf('.');
        String typePart = dot > 0 ? ownerFqn.substring(0, dot) : ownerFqn;
        return "CommonForm".equals(MetadataTypeCatalog.toEnglishSingular(typePart.trim())); //$NON-NLS-1$
    }

    /**
     * Whether {@code create_form} asks EDT's form generator to build this form's layout: a
     * managed form without an explicit empty-layout request. An ORDINARY form carries its own
     * layout, and {@code layout=empty} names the empty path - neither reaches the generator, so
     * neither can be refused over a generator miss.
     *
     * @param formType the normalized form-type literal ({@code MANAGED} / {@code ORDINARY});
     *            comparison is case-insensitive
     * @param layout  the caller's {@code layout} argument; may be null/empty
     * @return true when the generator runs for this call
     */
    static boolean generatorRunsFor(String formType, String layout)
    {
        return !"ORDINARY".equalsIgnoreCase(formType) //$NON-NLS-1$
            && !"empty".equalsIgnoreCase(layout); //$NON-NLS-1$
    }

    /**
     * The refusal {@code create_form} answers when the generator was asked for and did not
     * deliver a form: the miss reason, the fact that nothing was created, and the empty-layout
     * retry that creates a form without the generator.
     *
     * @param missReason the recorded generator miss ("not-found" or the failure text); may be
     *            null when no reason was captured
     * @return the refusal message, never null
     */
    static String generatorRefusalText(String missReason)
    {
        String reason = "not-found".equals(missReason) //$NON-NLS-1$
            ? "the form generator is not available on this runtime" //$NON-NLS-1$
            : missReason != null ? missReason : "the generator produced no form"; //$NON-NLS-1$
        return "create_form refused: the form generator did not deliver a layout (" + reason //$NON-NLS-1$
            + "). No form was created - the transaction was rolled back. Retry with " //$NON-NLS-1$
            + "layout=empty to create the form without the generator."; //$NON-NLS-1$
    }

    /**
     * The purpose the created form is being given, as a token the purpose table accepts.
     * <p>
     * An explicit {@code purpose} states it. A {@code formType} does too when it names one of the
     * legacy purpose values ({@code ItemForm} / {@code ListForm} / ...), which is what callers of
     * that older spelling meant; {@code MANAGED} and {@code ORDINARY} name the form model, not a
     * purpose, and leave the question to the owner type and the form name.
     *
     * @param purposeRaw the caller's {@code purpose}, may be null/empty
     * @param legacyFormTypeRaw the caller's {@code formType}, may be null/empty
     * @return the purpose token, or null when the caller named none
     */
    static String purposeToken(String purposeRaw, String legacyFormTypeRaw)
    {
        if (purposeRaw != null && !purposeRaw.trim().isEmpty())
        {
            return purposeRaw;
        }
        String legacy = legacyFormTypeRaw == null ? null : legacyFormTypeRaw.trim();
        if (legacy == null || legacy.isEmpty()
            || "MANAGED".equalsIgnoreCase(legacy) || "ORDINARY".equalsIgnoreCase(legacy)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        return legacy;
    }

    /**
     * The EDT {@code FormType} constant the created form's purpose resolves to, from the caller's
     * purpose (or a legacy formType that names one) and, when neither is given, from the owner type
     * and the form name.
     *
     * @param purposeRaw the caller's {@code purpose}, may be null/empty
     * @param legacyFormTypeRaw the caller's {@code formType}, may be null/empty
     * @param ownerFqn FQN of the owning metadata object
     * @param formName the form name (used by the name heuristic)
     * @return a FormType constant name, never null
     */
    static String formPurposeFor(String purposeRaw, String legacyFormTypeRaw, String ownerFqn,
        String formName)
    {
        return deriveFormPurpose(purposeToken(purposeRaw, legacyFormTypeRaw), ownerFqn, formName);
    }

    /**
     * The owner property that declares a form of this purpose as the owner's default one, or null
     * when the owner has no such property for that purpose.
     * <p>
     * {@code GENERIC} and {@code RECORD_SET} name no default-form property: a caller who asks for
     * {@code setAsDefault} with one of those purposes has nothing to set, and the caller of this
     * method reports that rather than answering success.
     *
     * @param purposeConstant a {@code FormType} constant name, as {@link #deriveFormPurpose} returns
     * @return the property name ({@code defaultListForm}, ...), or null when there is none
     */
    static String defaultFormSetterFor(String purposeConstant)
    {
        if (purposeConstant == null)
        {
            return null;
        }
        switch (purposeConstant)
        {
            case "OBJECT": return "defaultObjectForm"; //$NON-NLS-1$ //$NON-NLS-2$
            case "LIST": return "defaultListForm"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CHOICE": return "defaultChoiceForm"; //$NON-NLS-1$ //$NON-NLS-2$
            case "FOLDER": return "defaultFolderForm"; //$NON-NLS-1$ //$NON-NLS-2$
            case "FOLDER_CHOICE": return "defaultFolderChoiceForm"; //$NON-NLS-1$ //$NON-NLS-2$
            case "RECORD": return "defaultRecordForm"; //$NON-NLS-1$ //$NON-NLS-2$
            default: return null;
        }
    }

    /**
     * Whether the created form holds an auto command bar, which is what the
     * {@code autoCommandBarAdded} tag of the response reports.
     * <p>
     * The bar reaches the form from either half of the deterministic content: the form generator
     * builds one into the layout it produces, and a form that came out without one receives the
     * container from its base properties. Reading the finished form answers what the caller gets;
     * asking the content step alone missed the empty layout, because a form created for it is built
     * with those base properties already applied and so holds its bar before that step looks.
     * </p>
     *
     * @param innerForm the form the operation built, or <code>null</code>
     * @return true when the form holds an auto command bar
     */
    static boolean carriesAutoCommandBar(Object innerForm)
    {
        return innerForm != null && invokeNoArg(innerForm, "getAutoCommandBar") != null; //$NON-NLS-1$
    }

    /**
     * The property {@code setAsDefault} writes for a form of this purpose on this owner.
     * <p>
     * A data processor and a report keep one default form for every purpose of the forms they
     * declare, so a form made the default is named by {@code defaultForm} whatever its purpose is.
     * The purpose-derived names - {@code defaultObjectForm}, {@code defaultListForm} and the rest -
     * belong to the types that keep a slot per purpose, and asking one of those owners for a name it
     * does not have is how {@code setAsDefault} came to report a failed write over a slot that had
     * just been filled.
     * </p>
     *
     * @param purposeConstant the form's purpose, as {@link #deriveFormPurpose} returns
     * @param owner the metadata object the form belongs to
     * @return the property name to write, or null when there is none to write
     */
    static String defaultFormPropertyFor(String purposeConstant, Object owner)
    {
        if (hasSingleDefaultFormSlot(owner))
        {
            return "defaultForm"; //$NON-NLS-1$
        }
        return defaultFormSetterFor(purposeConstant);
    }

    /**
     * Tells whether an owner keeps one default form for all purposes.
     * <p>
     * DataProcessor and Report expose a single {@code getDefaultForm} / {@code setDefaultForm} pair;
     * Catalog, Document and the register types instead keep one slot per purpose
     * ({@code defaultObjectForm}, {@code defaultListForm}, ...). Both accessors have to be there: a
     * getter alone names a slot that cannot be written, and the write below would report the same
     * failure it is meant to avoid.
     * </p>
     *
     * @param owner the metadata object the form was created on
     * @return true when the owner has the single-slot accessors
     */
    static boolean hasSingleDefaultFormSlot(Object owner)
    {
        if (owner == null)
        {
            return false;
        }
        try
        {
            owner.getClass().getMethod("getDefaultForm"); //$NON-NLS-1$
        }
        catch (NoSuchMethodException noGetter)
        {
            // The owner type does not declare the slot at all - absence is the answer here, not a
            // failure to read: this owner keeps its default forms one per purpose.
            return false;
        }
        for (java.lang.reflect.Method m : owner.getClass().getMethods())
        {
            if ("setDefaultForm".equals(m.getName()) && m.getParameterCount() == 1) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Derives the EDT {@code FormType} constant NAME used by the form generator
     * for {@link #opCreateForm}. <p>
     *
     * Explicit {@code purpose} param (case-insensitive, EN + RU synonyms) wins:
     * <ul>
     *   <li>ItemForm / ObjectForm / Объект -&gt; {@code OBJECT}</li>
     *   <li>ListForm / Список -&gt; {@code LIST}</li>
     *   <li>ChoiceForm / ВыборФормы / Выбор -&gt; {@code CHOICE}</li>
     *   <li>FolderForm -&gt; {@code FOLDER}; FolderChoiceForm -&gt; {@code FOLDER_CHOICE}</li>
     *   <li>RecordSetForm -&gt; {@code RECORD_SET}; RecordForm -&gt; {@code RECORD}</li>
     *   <li>Generic -&gt; {@code GENERIC}</li>
     * </ul>
     * When {@code purpose} is omitted, derive from the owner metadata type and
     * the form name: object-owning types (Catalog / Document /
     * ChartOfCharacteristicTypes / BusinessProcess / Task / ExchangePlan /
     * ChartOfAccounts / ChartOfCalculationTypes) default to {@code OBJECT} for
     * an element/item form, but a "Список"/"List" name -&gt; {@code LIST} and a
     * "Выбор"/"Choice" name -&gt; {@code CHOICE}. Register types
     * (InformationRegister / AccumulationRegister / AccountingRegister /
     * CalculationRegister) default to {@code RECORD_SET}, with a "Список"/"List"
     * name -&gt; {@code LIST}. DataProcessor / Report and the external variants
     * ExternalDataProcessor / ExternalReport default to {@code OBJECT} - matching
     * the EDT wizard's "Форма обработки"/"Форма отчёта", so the main form carries
     * the Объект attribute and renders (a custom form is opt-in via
     * {@code purpose=Generic}). CommonForm and anything else -&gt; {@code GENERIC}.
     *
     * @param purposeRaw caller-supplied purpose (may be null/empty)
     * @param ownerFqn   FQN of the owning metadata object
     * @param formName   the form name (used for the name heuristic)
     * @return a FormType constant name (never null)
     */
    private static String deriveFormPurpose(String purposeRaw, String ownerFqn, String formName)
    {
        // 1. Explicit purpose wins.
        if (purposeRaw != null && !purposeRaw.trim().isEmpty())
        {
            String p = purposeRaw.trim();
            if (equalsAny(p, "ItemForm", "ObjectForm", "Объект", "ФормаЭлемента", "ФормаОбъекта")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            {
                return "OBJECT"; //$NON-NLS-1$
            }
            if (equalsAny(p, "ListForm", "Список", "ФормаСписка")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                return "LIST"; //$NON-NLS-1$
            }
            if (equalsAny(p, "ChoiceForm", "ВыборФормы", "Выбор", "ФормаВыбора")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {
                return "CHOICE"; //$NON-NLS-1$
            }
            if (equalsAny(p, "FolderChoiceForm", "ФормаВыбораГруппы")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return "FOLDER_CHOICE"; //$NON-NLS-1$
            }
            if (equalsAny(p, "FolderForm", "ФормаГруппы")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return "FOLDER"; //$NON-NLS-1$
            }
            if (equalsAny(p, "RecordSetForm", "ФормаНабораЗаписей")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return "RECORD_SET"; //$NON-NLS-1$
            }
            if (equalsAny(p, "RecordForm", "ФормаЗаписи")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return "RECORD"; //$NON-NLS-1$
            }
            if (equalsAny(p, "Generic", "Обычная", "Произвольная")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                return "GENERIC"; //$NON-NLS-1$
            }
            // Unknown purpose token: assume it already names a FormType constant
            // (e.g. caller passed "SEARCH" / "REPORT_VARIANT"); upper-case it and
            // let the generator resolve it (it falls back to GENERIC on a miss).
            return p.toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        }
        // 2. Derive from owner type + form-name heuristic.
        boolean nameLooksList = nameContains(formName, "Список", "List"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean nameLooksChoice = nameContains(formName, "Выбор", "Choice"); //$NON-NLS-1$ //$NON-NLS-2$
        String typePart = ownerFqn != null && ownerFqn.indexOf('.') > 0
            ? ownerFqn.substring(0, ownerFqn.indexOf('.')) : ownerFqn;
        String type = typePart != null
            ? MetadataTypeCatalog.toEnglishSingular(typePart.trim()) : null;
        if (type == null)
        {
            // Unrecognized owner type - safest default is GENERIC.
            return "GENERIC"; //$NON-NLS-1$
        }
        if (isObjectOwningType(type))
        {
            if (nameLooksList)
            {
                return "LIST"; //$NON-NLS-1$
            }
            if (nameLooksChoice)
            {
                return "CHOICE"; //$NON-NLS-1$
            }
            return "OBJECT"; //$NON-NLS-1$
        }
        if (isRegisterType(type))
        {
            if (nameLooksList)
            {
                return "LIST"; //$NON-NLS-1$
            }
            return "RECORD_SET"; //$NON-NLS-1$
        }
        // DataProcessor / Report: the EDT "New Form" wizard defaults these to
        // the OBJECT form (Форма обработки / Форма отчёта) - a main attribute
        // Объект typed <type>Object.<Name> plus the default layout - NOT an
        // empty generic form. Match that default so a data processor's /
        // report's main form renders and is IB-loadable out of the box; a truly
        // custom form stays available via explicit purpose=Generic.
        // mainTypeFqnForOwner maps these owners to their <type>Object value
        // type, and ensureObjectFormContent adds the main attribute for OBJECT.
        //
        // The external .epf/.erf variants (ExternalDataProcessor / ExternalReport)
        // get the same OBJECT default. Their DT project has no Configuration, but
        // the primary path - EDT's IFormGenerator (the wizard engine create_form
        // invokes) - resolves the produced object type from the OWNER's own
        // DT-project model, so it types the main attribute without a
        // Configuration. Verified end-to-end: the generated form validates clean
        // with the main attribute typed ExternalDataProcessor.<Name> (the type
        // EDT's own wizard writes - for an external processor the bare FQN is the
        // object type, there is no separate ref type). ensureObjectFormContent
        // then finds that main attribute and skips, so the config-less
        // deterministic fallback never has to resolve the type in the success
        // path.
        if ("DataProcessor".equals(type) || "Report".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "ExternalDataProcessor".equals(type) || "ExternalReport".equals(type)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "OBJECT"; //$NON-NLS-1$
        }
        // CommonForm / unknown -> GENERIC.
        return "GENERIC"; //$NON-NLS-1$
    }

    /** True when {@code candidate} equals (ignoring case) any of {@code options}. */
    private static boolean equalsAny(String candidate, String... options)
    {
        for (String o : options)
        {
            if (o.equalsIgnoreCase(candidate))
            {
                return true;
            }
        }
        return false;
    }

    /** True when {@code name} contains (ignoring case) any of {@code needles}. */
    private static boolean nameContains(String name, String... needles)
    {
        if (name == null)
        {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String n : needles)
        {
            if (lower.contains(n.toLowerCase(java.util.Locale.ROOT)))
            {
                return true;
            }
        }
        return false;
    }

    /** Object-owning metadata types whose default element form is an OBJECT form. */
    private static boolean isObjectOwningType(String englishType)
    {
        switch (englishType)
        {
            case "Catalog": //$NON-NLS-1$
            case "Document": //$NON-NLS-1$
            case "ChartOfCharacteristicTypes": //$NON-NLS-1$
            case "BusinessProcess": //$NON-NLS-1$
            case "Task": //$NON-NLS-1$
            case "ExchangePlan": //$NON-NLS-1$
            case "ChartOfAccounts": //$NON-NLS-1$
            case "ChartOfCalculationTypes": //$NON-NLS-1$
                return true;
            default:
                return false;
        }
    }

    /** Register types whose default form is a RECORD_SET form. */
    private static boolean isRegisterType(String englishType)
    {
        switch (englishType)
        {
            case "InformationRegister": //$NON-NLS-1$
            case "AccumulationRegister": //$NON-NLS-1$
            case "AccountingRegister": //$NON-NLS-1$
            case "CalculationRegister": //$NON-NLS-1$
                return true;
            default:
                return false;
        }
    }

    /**
     * Maps an object-owning metadata type to the FQN of the &lt;type&gt;Object
     * value type used by the main attribute of its OBJECT form
     * (Catalog -&gt; {@code CatalogObject.<Name>},
     * Document -&gt; {@code DocumentObject.<Name>},
     * DataProcessor -&gt; {@code DataProcessorObject.<Name>},
     * ExternalDataProcessor -&gt; {@code ExternalDataProcessorObject.<Name>}, etc.).
     * Returns {@code null} for registers and unknown types (no single-object
     * main attribute). For external objects (.epf/.erf) the mapping is present
     * but the object value type resolves only when a Configuration is available.
     *
     * @param englishType bare English type name (e.g. {@code "Catalog"})
     * @param objectName  the owning object's short name
     * @return the {@code <type>Object.<Name>} FQN, or {@code null}
     */
    private static String mainTypeFqnForOwner(String englishType, String objectName)
    {
        if (englishType == null || objectName == null || objectName.isEmpty())
        {
            return null;
        }
        String objectType;
        switch (englishType)
        {
            case "Catalog": //$NON-NLS-1$
                objectType = "CatalogObject"; break; //$NON-NLS-1$
            case "Document": //$NON-NLS-1$
                objectType = "DocumentObject"; break; //$NON-NLS-1$
            case "ChartOfCharacteristicTypes": //$NON-NLS-1$
                objectType = "ChartOfCharacteristicTypesObject"; break; //$NON-NLS-1$
            case "BusinessProcess": //$NON-NLS-1$
                objectType = "BusinessProcessObject"; break; //$NON-NLS-1$
            case "Task": //$NON-NLS-1$
                objectType = "TaskObject"; break; //$NON-NLS-1$
            case "ChartOfAccounts": //$NON-NLS-1$
                objectType = "ChartOfAccountsObject"; break; //$NON-NLS-1$
            case "ChartOfCalculationTypes": //$NON-NLS-1$
                objectType = "ChartOfCalculationTypesObject"; break; //$NON-NLS-1$
            case "ExchangePlan": //$NON-NLS-1$
                objectType = "ExchangePlanObject"; break; //$NON-NLS-1$
            case "DataProcessor": //$NON-NLS-1$
                objectType = "DataProcessorObject"; break; //$NON-NLS-1$
            case "Report": //$NON-NLS-1$
                objectType = "ReportObject"; break; //$NON-NLS-1$
            case "ExternalDataProcessor": //$NON-NLS-1$
                objectType = "ExternalDataProcessorObject"; break; //$NON-NLS-1$
            case "ExternalReport": //$NON-NLS-1$
                objectType = "ExternalReportObject"; break; //$NON-NLS-1$
            default:
                return null;
        }
        return objectType + "." + objectName; //$NON-NLS-1$
    }

    /**
     * Deterministically gives an inner {@code form.model.Form} the content that
     * makes an OBJECT form renderable and IB-loadable: a {@code main} attribute
     * (named {@code Объект}, typed {@code <type>Object.<Name>}, so the form has
     * a data context) and an {@code autoCommandBar}. The full wizard auto-layout
     * is intentionally NOT reproduced - the main attribute + its valueType are
     * what the designer needs to render the form and what keeps the IB load from
     * failing on a contextless form.
     * <p>
     * MUST be called inside the BM read-write transaction that created the form
     * (it mutates the model graph). Idempotent and best-effort: each step is
     * guarded (skipped when already present) and never throws out - failures are
     * collected into {@code warnings} and surfaced as response tags so the form
     * still gets created.
     * <p>
     * All {@code form.model} types ({@code FormFactory}, {@code FormAttribute},
     * {@code AutoCommandBar}, {@code Form.getAttributes()},
     * {@code CommandBarHolder.setAutoCommandBar()}) are accessed reflectively
     * via the form bundle ({@code com._1c.g5.v8.dt.form.model} is imported).
     *
     * @param innerForm        the inner {@code form.model.Form} EObject
     * @param owner            the owning {@code MdObject} (for type + name)
     * @param ownerFqn         the owner FQN (for the type prefix)
     * @param purposeConst     the resolved {@code FormType} constant name
     *     ({@code OBJECT} / {@code LIST} / {@code GENERIC} / ...)
     * @param project          owning project (for the main-attribute valueType)
     * @param config           owning configuration (for ref-type resolution)
     * @param mainAttrAddedRef set to the main type FQN when the attribute is added
     * @param autoCmdBarAddedRef set to true when the autoCommandBar is added
     * @param warnings         best-effort failure messages (never fatal)
     */
    private static void ensureObjectFormContent(Object innerForm, MdObject owner, String ownerFqn,
        String purposeConst, IProject project, Configuration config,
        AtomicReference<String> mainAttrAddedRef, AtomicReference<Boolean> autoCmdBarAddedRef,
        java.util.List<String> warnings)
    {
        if (!(innerForm instanceof EObject))
        {
            return;
        }
        Object formFactory = resolveFormFactory();
        if (formFactory == null)
        {
            warnings.add("FormFactory unavailable - cannot add main attribute / command bar"); //$NON-NLS-1$
            return;
        }
        // ---- B: autoCommandBar (every purpose) ------------------------------
        try
        {
            if (invokeNoArg(innerForm, "getAutoCommandBar") == null) //$NON-NLS-1$
            {
                Object bar = invokeNoArg(formFactory, "createAutoCommandBar"); //$NON-NLS-1$
                if (bar != null)
                {
                    invokeSetter(bar, "setId", int.class, Integer.valueOf(-1)); //$NON-NLS-1$
                    invokeSetter(bar, "setName", String.class, "ФормаКоманднаяПанель"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (invokeSetter(innerForm, "setAutoCommandBar", bar.getClass(), bar)) //$NON-NLS-1$
                    {
                        autoCmdBarAddedRef.set(Boolean.TRUE);
                    }
                }
            }
        }
        catch (Exception e)
        {
            warnings.add("autoCommandBar: " + e.getMessage()); //$NON-NLS-1$
        }

        // ---- A: main attribute (OBJECT purpose only, per this fix) ----------
        if (!"OBJECT".equalsIgnoreCase(purposeConst)) //$NON-NLS-1$
        {
            return;
        }
        try
        {
            @SuppressWarnings("unchecked")
            EList<EObject> attributes = (EList<EObject>) invokeNoArg(innerForm, "getAttributes"); //$NON-NLS-1$
            if (attributes == null)
            {
                warnings.add("inner form exposes no getAttributes() - main attribute skipped"); //$NON-NLS-1$
                return;
            }
            // Idempotency guard: the generator may already have produced a main
            // attribute - do not stack a second one. While scanning, track the
            // highest existing id so the new attribute gets a free id (the EDT
            // convention is id=1 for the main attribute, but if the generator
            // already used id=1 for another attribute we pick max+1 to avoid a
            // duplicate-id validation error).
            int maxId = 0;
            for (EObject existing : attributes)
            {
                if (Boolean.TRUE.equals(invokeNoArg(existing, "isMain")) //$NON-NLS-1$
                    || "Объект".equals(invokeNoArg(existing, "getName"))) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    return;
                }
                Object idVal = invokeNoArg(existing, "getId"); //$NON-NLS-1$
                if (idVal instanceof Integer && (Integer) idVal > maxId)
                {
                    maxId = (Integer) idVal;
                }
            }
            int newId = maxId < 1 ? 1 : maxId + 1;
            String typePart = ownerFqn != null && ownerFqn.indexOf('.') > 0
                ? ownerFqn.substring(0, ownerFqn.indexOf('.')) : ownerFqn;
            String englishType = typePart != null
                ? MetadataTypeCatalog.toEnglishSingular(typePart.trim()) : null;
            String mainTypeFqn = mainTypeFqnForOwner(englishType, owner.getName());
            if (mainTypeFqn == null)
            {
                // Not a single-object owner - nothing to type the attribute with.
                return;
            }
            if (config == null)
            {
                // The main attribute's type is a produced/object type
                // (<Type>Object.<Name>), resolvable only through a Configuration.
                // External .epf/.erf DT projects have none. The PRIMARY path -
                // EDT's IFormGenerator - resolves it from the owner's own model and
                // adds this main attribute itself; this deterministic fallback runs
                // only when the generator was unavailable. Without a Configuration
                // setFormAttributeTypes cannot resolve the produced type and would
                // fabricate an unresolvable Type that gets reported as a success -
                // so fail closed instead: skip the main attribute and warn. The
                // form is still created (empty, renderable); the caller adds the
                // attribute in EDT.
                warnings.add("main attribute not added: typing " + mainTypeFqn //$NON-NLS-1$
                    + " needs a Configuration (an external DT project has none) and " //$NON-NLS-1$
                    + "the form generator that resolves it from the owner model was " //$NON-NLS-1$
                    + "unavailable"); //$NON-NLS-1$
                return;
            }
            Object attr = invokeNoArg(formFactory, "createFormAttribute"); //$NON-NLS-1$
            if (!(attr instanceof EObject))
            {
                warnings.add("FormFactory.createFormAttribute returned no EObject"); //$NON-NLS-1$
                return;
            }
            invokeSetter(attr, "setName", String.class, "Объект"); //$NON-NLS-1$ //$NON-NLS-2$
            invokeSetter(attr, "setId", int.class, Integer.valueOf(newId)); //$NON-NLS-1$
            invokeSetter(attr, "setMain", boolean.class, Boolean.TRUE); //$NON-NLS-1$
            invokeSetter(attr, "setSavedData", boolean.class, Boolean.TRUE); //$NON-NLS-1$
            // valueType: <type>Object.<Name> via the object-attribute machinery
            // (handles produced/object types and the qualifier shape EDT writes).
            BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setFormAttributeTypes(
                attr, project, config, java.util.Collections.singletonList(mainTypeFqn), null);
            if (tr != null && tr.error != null)
            {
                warnings.add("main attribute valueType (" + mainTypeFqn + "): " + tr.error); //$NON-NLS-1$ //$NON-NLS-2$
            }
            // Best-effort view/edit AdjustableBoolean(common=true). The main
            // attribute renders without these; skip silently if awkward.
            applyAdjustableCommon(attr, "setView"); //$NON-NLS-1$
            applyAdjustableCommon(attr, "setEdit"); //$NON-NLS-1$
            attributes.add((EObject) attr);
            mainAttrAddedRef.set(mainTypeFqn);
        }
        catch (Exception e)
        {
            warnings.add("main attribute: " + (e.getMessage() != null //$NON-NLS-1$
                ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Resolves the {@code com._1c.g5.v8.dt.form.model.FormFactory} singleton
     * ({@code eINSTANCE}). The package is imported, so {@code Class.forName}
     * resolves it through this bundle's classloader. Returns {@code null} when
     * the form model is not on the runtime classpath.
     */
    private static Object resolveFormFactory()
    {
        try
        {
            Class<?> clazz = Class.forName("com._1c.g5.v8.dt.form.model.FormFactory"); //$NON-NLS-1$
            return clazz.getField("eINSTANCE").get(null); //$NON-NLS-1$
        }
        catch (Throwable t)
        {
            Activator.logWarning("resolveFormFactory failed: " + t.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Sets an {@code AdjustableBoolean(common=true)} on the given form-attribute
     * feature ({@code setView} / {@code setEdit}) when the
     * {@code MdClassFactory.createAdjustableBoolean()} chain and the matching
     * setter are both available. Best-effort: silently does nothing on any miss
     * (the main attribute is renderable without view/edit set explicitly).
     */
    private static void applyAdjustableCommon(Object attr, String setterName)
    {
        try
        {
            Object adjustable = createAdjustableBooleanCommon();
            if (adjustable == null)
            {
                return;
            }
            invokeSetter(attr, setterName, adjustable.getClass(), adjustable);
        }
        catch (Exception ignored)
        {
            // view/edit are optional - never fail create_form over them
        }
    }

    /**
     * Builds an {@code AdjustableBoolean} with {@code common=true} via
     * {@code MdClassFactory.eINSTANCE.createAdjustableBoolean()}. Returns
     * {@code null} when the factory or {@code setCommon} is unavailable.
     */
    private static Object createAdjustableBooleanCommon()
    {
        try
        {
            Class<?> clazz =
                Class.forName("com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory"); //$NON-NLS-1$
            Object factory = clazz.getField("eINSTANCE").get(null); //$NON-NLS-1$
            Object ab = invokeNoArg(factory, "createAdjustableBoolean"); //$NON-NLS-1$
            if (ab == null)
            {
                return null;
            }
            invokeSetter(ab, "setCommon", boolean.class, Boolean.TRUE); //$NON-NLS-1$
            return ab;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** Invokes a no-arg method by name; returns its result or null on any miss. */
    private static Object invokeNoArg(Object target, String method)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        }
        catch (Exception ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
            return null;
        }
    }

    /**
     * Invokes a one-arg setter by name, picking the overload whose parameter
     * accepts {@code value}: a boxed {@code Boolean}/{@code Integer}/{@code Long}
     * matches the corresponding primitive parameter, and an object value matches
     * any parameter type that {@link Class#isInstance(Object) isInstance} accepts.
     * The {@code declaredType} hint is reserved for callers that want to document
     * the expected parameter shape; the actual match is value-driven. Returns
     * true when a setter was invoked.
     */
    private static boolean invokeSetter(Object target, String setterName, Class<?> declaredType,
        Object value)
    {
        if (target == null)
        {
            return false;
        }
        for (java.lang.reflect.Method m : target.getClass().getMethods())
        {
            if (!setterName.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            Class<?> p = m.getParameterTypes()[0];
            boolean compatible = p.isPrimitive()
                ? isBoxedMatch(p, value)
                : (value == null || p.isInstance(value));
            if (!compatible)
            {
                continue;
            }
            try
            {
                m.invoke(target, value);
                return true;
            }
            catch (Exception e)
            {
                Activator.logWarning("invokeSetter " + setterName //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return false;
    }

    /** True when {@code value} is the boxed form of the primitive {@code prim}. */
    private static boolean isBoxedMatch(Class<?> prim, Object value)
    {
        if (value == null)
        {
            return false;
        }
        if (prim == boolean.class)
        {
            return value instanceof Boolean;
        }
        if (prim == int.class)
        {
            return value instanceof Integer;
        }
        if (prim == long.class)
        {
            return value instanceof Long;
        }
        // Other primitives are not used by this helper.
        return false;
    }

    /**
     * Attaches a generated {@code Form} root to its {@code BasicForm} wrapper
     * via the matching one-arg setter ({@code setForm} / {@code setFormAttachedForm}
     * / {@code setRootContainer}), probing by name + assignability. Mirrors
     * {@code BmCommonFormPostCreate.attachInnerForm}.
     *
     * @param wrapper       the BasicForm wrapper (an MdObject)
     * @param generatedForm the Form root produced by the generator
     * @return true when the setter was invoked successfully
     */
    private static boolean attachGeneratedForm(Object wrapper, Object generatedForm)
    {
        if (wrapper == null || generatedForm == null)
        {
            return false;
        }
        for (String setter : new String[] { "setForm", "setFormAttachedForm", //$NON-NLS-1$ //$NON-NLS-2$
            "setRootContainer" }) //$NON-NLS-1$
        {
            for (java.lang.reflect.Method m : wrapper.getClass().getMethods())
            {
                if (!setter.equals(m.getName()) || m.getParameterCount() != 1)
                {
                    continue;
                }
                if (!m.getParameterTypes()[0].isInstance(generatedForm))
                {
                    continue;
                }
                try
                {
                    m.invoke(wrapper, generatedForm);
                    return true;
                }
                catch (Exception e)
                {
                    Activator.logWarning("attachGeneratedForm " + setter //$NON-NLS-1$
                        + " failed: " + e.getMessage()); //$NON-NLS-1$
                }
            }
        }
        return false;
    }
}
