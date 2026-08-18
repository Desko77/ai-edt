package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;
import ru.aiedt.mcp.server.support.BmFormGeneratorHelper;
import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.PictureValidator;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.TypeApplication;

/**
 * Form-item cluster of {@code edit_metadata}: add/remove form attributes / parameters /
 * attribute columns, dynamic-list tables, settings composer, form commands, form-item
 * property set, picture list, and the edit-form delegation bridge (add_field/group/button/
 * table/decoration/radio_button/remove_form_item). Extracted verbatim from
 * {@link EditMetadataTool} (Inc4 god-class split); handlers are package-visible and
 * dispatched through the single-source op-registry. Shared stateless helpers live on
 * {@link EditMetadataTool} (qualified calls); cluster-local helpers (collectFormatHelpTip,
 * collectPictureRepresentationWarning, convertEditFormMarkdownToJson, toBmFormFqn,
 * snakeToEditFormOp, delegateToEditForm, delegateToEditFormAsRadioButton) are private here.
 */
final class FormItemsOps
{
    /**
     * Adds a new attribute to an existing form. The form is identified by its
     * BM top-object FQN (e.g. {@code Catalog.Products.Form.ItemForm.Form}).
     * <p>
     * Optional {@code type} (e.g. {@code String} / {@code Number} /
     * {@code CatalogRef.X}) sets the attribute's {@code valueType}; without it
     * the attribute is typeless. Shared qualifier parameters
     * ({@code length} / {@code precision} / {@code fractionDigits} /
     * {@code dateFractions} / {@code nonNegative} / {@code allowedLength})
     * apply to primitive types. Writing a resolved type prevents the IB-load
     * failure "Несоответствие свойства XDTO: Type" that a typeless or
     * unresolved-type form attribute triggers.
     * <p>
     * Idempotent: a same-named attribute already on the form returns
     * {@code idempotentSkip} instead of stacking a duplicate.
     */
    String opAddFormAttribute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(attributeName, "attributeName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        // Qualifier wiring mirrors add_object_attribute so a String form
        // attribute can express length etc. An empty <stringQualifiers/>
        // matches EDT's own output for an unlimited string; the resolved
        // <types> element is what makes the form load in the IB.
        BmDefinedTypeHelper.QualifierOptions formQualifiers = new BmDefinedTypeHelper.QualifierOptions();
        formQualifiers.length = JsonUtils.extractIntegerArgument(params, "length"); //$NON-NLS-1$
        formQualifiers.precision = JsonUtils.extractIntegerArgument(params, "precision"); //$NON-NLS-1$
        formQualifiers.fractionDigits = JsonUtils.extractIntegerArgument(params, "fractionDigits"); //$NON-NLS-1$
        if (params != null && params.containsKey("nonNegative")) //$NON-NLS-1$
        {
            formQualifiers.nonNegative = JsonUtils.extractBooleanArgument(params, "nonNegative", false); //$NON-NLS-1$
        }
        formQualifiers.dateFractions = JsonUtils.extractStringArgument(params, "dateFractions"); //$NON-NLS-1$
        formQualifiers.allowedLength = JsonUtils.extractStringArgument(params, "allowedLength"); //$NON-NLS-1$

        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }

        IConfigurationProvider formConfigProvider = Activator.getDefault().getConfigurationProvider();
        final Configuration formConfig = formConfigProvider != null
            ? formConfigProvider.getConfiguration(project) : null;
        final String typeFinal = type;
        final boolean[] typeAppliedFlag = { false };
        final String[] typeApplyErrorRef = { null };
        final List<String> typeResolved = new ArrayList<>();
        final List<String> typeUnresolved = new ArrayList<>();
        final boolean[] alreadyExistsFlag = { false };
        final java.util.Set<String> existingTypeNames = new java.util.LinkedHashSet<>();

        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String result = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) -> {
            // Idempotency: do not stack a duplicate FormAttribute. The form
            // serializer would otherwise emit two same-named <attributes> and
            // the platform keeps one, silently dropping the other's bindings.
            if (helper.hasFormAttribute(form, attributeName))
            {
                alreadyExistsFlag[0] = true;
                if (typeFinal != null && !typeFinal.isEmpty())
                {
                    // Capture the existing type so the caller can tell an
                    // already-correct attribute apart from one that needs a
                    // remove + re-add to change its type.
                    Object existingAttr = helper.getFormAttribute(form, attributeName);
                    if (existingAttr != null)
                    {
                        existingTypeNames.addAll(BmDefinedTypeHelper.readValueTypeNames(existingAttr));
                    }
                }
                return "FormAttribute already exists: " + attributeName; //$NON-NLS-1$
            }
            // EDT defaults a form attribute's caption (Заголовок) to its name when none is given.
            Object attribute = helper.createFormAttribute(attributeName,
                (title != null && !title.isEmpty()) ? title : attributeName);
            // Add to the form BEFORE applying the type: addAttributeToForm puts
            // the attribute into the BM-managed containment tree, so the
            // valueType eSet inside setFormAttributeTypes is tracked by the
            // transaction. Reordering would drop the type at commit.
            helper.addAttributeToForm(form, attribute);
            // Assign a unique id in the form-attribute id space. Without it the
            // attribute defaults to id=0, so a second add_form_attribute yields
            // two id=0 attributes -> "Duplicate id '0'" (form-legacy-emf-check)
            // and the form fails to load. applyFormAttributeIdAndUse also sets
            // view/edit=Common(true), matching EDT's output for a plain
            // attribute (every stock form attribute carries id + view + edit).
            int wantAttrId = helper.nextFormAttributeId(form);
            helper.applyFormAttributeIdAndUse(attribute, wantAttrId);
            // applyFormAttributeIdAndUse swallows a setId reflection failure
            // (warning only); on an EDT runtime where that reflection breaks
            // the attribute would keep id=0 and the form would fail with
            // "Duplicate id '0'" while the tool still reported success. Read the
            // id back and abort loudly instead of shipping a corrupt form.
            int gotAttrId = helper.readFormAttributeId(attribute);
            if (gotAttrId != wantAttrId)
            {
                throw new IllegalStateException("form attribute id not applied (wanted " //$NON-NLS-1$
                    + wantAttrId + ", got " + gotAttrId //$NON-NLS-1$
                    + ") - EDT form-attribute setId reflection failed on this runtime"); //$NON-NLS-1$
            }
            if (typeFinal != null && !typeFinal.isEmpty())
            {
                // formConfig may be null for external-object projects (.epf/.erf):
                // setFormAttributeTypes resolves primitives via the project-aware proxy;
                // reference types surface as unresolved rather than being skipped.
                {
                    try
                    {
                        BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setFormAttributeTypes(
                            attribute, project, formConfig,
                            Collections.singletonList(typeFinal), formQualifiers);
                        typeAppliedFlag[0] = tr.ok;
                        if (tr.resolved != null)
                        {
                            typeResolved.addAll(tr.resolved);
                        }
                        if (tr.unresolved != null)
                        {
                            typeUnresolved.addAll(tr.unresolved);
                        }
                        if (!tr.ok)
                        {
                            typeApplyErrorRef[0] = tr.error;
                            Activator.logWarning("add_form_attribute: type='" + typeFinal //$NON-NLS-1$
                                + "' not applied: " + tr.error); //$NON-NLS-1$
                        }
                    }
                    catch (Exception typeEx)
                    {
                        typeApplyErrorRef[0] = typeEx.getClass().getSimpleName() + ": " //$NON-NLS-1$
                            + typeEx.getMessage();
                        Activator.logWarning("add_form_attribute: type='" + typeFinal //$NON-NLS-1$
                            + "' threw: " + typeEx.getMessage()); //$NON-NLS-1$
                    }
                }
            }
            return "added attribute " + attributeName; //$NON-NLS-1$
        });

        // Error path (BM failure / form not found): keep the standard
        // structured error shape.
        if (result == null || result.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResult(result, "add_form_attribute", formFqn); //$NON-NLS-1$
        }

        ToolResult response = ToolResult.success()
            .put("operation", "add_form_attribute") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("message", result); //$NON-NLS-1$
        if (alreadyExistsFlag[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("attributeName", attributeName); //$NON-NLS-1$
            idem.put("formFqn", formFqn); //$NON-NLS-1$
            if (typeFinal != null && !typeFinal.isEmpty())
            {
                idem.put("requestedType", typeFinal); //$NON-NLS-1$
                idem.put("existingType", new ArrayList<>(existingTypeNames)); //$NON-NLS-1$
                boolean typeMatches = existingTypeNames.size() == 1
                    && existingTypeNames.contains(typeFinal);
                idem.put("typeMatchesRequested", typeMatches); //$NON-NLS-1$
                if (!typeMatches)
                {
                    idem.put("hint", //$NON-NLS-1$
                        "Existing attribute has a different type. Use " //$NON-NLS-1$
                            + "remove_form_attribute then add_form_attribute to change it."); //$NON-NLS-1$
                }
            }
            response = response.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        else if (typeFinal != null && !typeFinal.isEmpty())
        {
            // Surface whether `type` actually landed on the new attribute so
            // the caller does not need a follow-up get_form_structure.
            response = response.put("typeApplication", //$NON-NLS-1$
                TypeApplication.tag(typeFinal, typeAppliedFlag[0], typeResolved,
                    typeUnresolved, typeApplyErrorRef[0]));
            if (TypeApplication.failed(typeAppliedFlag[0], typeUnresolved))
            {
                response = response.demote(TypeApplication.failureMessage(
                    "form attribute '" + attributeName + "'", typeFinal, //$NON-NLS-1$ //$NON-NLS-2$
                    typeApplyErrorRef[0], formDryRun, typeAppliedFlag[0]));
            }
        }
        return response.toJson();
    }

    /**
     * 1.42 (RSV 4.2 parity): removes a {@code FormAttribute} from a form by
     * name. Two modes:
     * <ul>
     *   <li>{@code deleteDataItems=true} (default) - also deletes every UI
     *       item whose dataPath starts with the attribute name (e.g. a Table
     *       backed by a ValueTable attribute and all its column FormFields).
     *       Closes the RSV 4.2 fix where deleteDataItems=true silently left
     *       table+columns behind with broken bindings.</li>
     *   <li>{@code deleteDataItems=false} - removes only the FormAttribute,
     *       leaves UI items in place with their existing dataPath strings.
     *       Useful for the "remove attribute then recreate with a different
     *       column set" workflow without manually rebuilding tables and
     *       fields. The response includes a {@code preservedDataPaths} array
     *       with one entry per kept item, e.g.
     *       {@code "Table:ТаблицаСводка -> /Сводка"}.</li>
     * </ul>
     */
    String opRemoveFormAttribute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        boolean deleteDataItems = JsonUtils.extractBooleanArgument(params, //$NON-NLS-1$
            "deleteDataItems", true); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(attributeName, "attributeName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        java.util.List<String> preservedDataPaths = new java.util.ArrayList<>();
        int[] removedItems = new int[1];
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String execResult = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) -> {
            try
            {
                // Validate the attribute exists BEFORE mutating UI items - if
                // it is missing we must not leave the form with bound items
                // ripped out and the attribute still in place (or vice versa).
                if (!helper.hasFormAttribute(form, attributeName))
                {
                    return "Error: FormAttribute not found: " + attributeName; //$NON-NLS-1$
                }
                if (deleteDataItems)
                {
                    removedItems[0] = helper.removeFormItemsBoundToAttribute(form, attributeName);
                }
                else
                {
                    preservedDataPaths.addAll(
                        helper.collectFormItemsBoundToAttribute(form, attributeName));
                }
                helper.removeFormAttributeByName(form, attributeName);
                return "removed FormAttribute " + attributeName; //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return "Error: removeFormAttribute failed - " //$NON-NLS-1$
                    + (cause.getMessage() != null ? cause.getMessage()
                        : cause.getClass().getSimpleName());
            }
        });
        if (execResult != null && execResult.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(execResult.substring("Error:".length()).trim()).toJson(); //$NON-NLS-1$
        }
        ToolResult result = ToolResult.success()
            .put("operation", "remove_form_attribute") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("deleteDataItems", deleteDataItems); //$NON-NLS-1$
        if (deleteDataItems)
        {
            result.put("removed", attributeName //$NON-NLS-1$
                + " (" + removedItems[0] + " data items removed)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            result.put("preservedDataPaths", preservedDataPaths); //$NON-NLS-1$
            result.put("hint", //$NON-NLS-1$
                "UI items kept with their existing dataPath strings. Recreate the " //$NON-NLS-1$
                    + "attribute via addFormAttribute to re-bind them, or call " //$NON-NLS-1$
                    + "removeFormAttribute again with deleteDataItems=true to remove."); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * 1.43.x audit C (forms-advanced): adds a {@code FormParameter} to a form
     * (Form.getParameters()). Optional {@code type} sets the parameter's
     * valueType through the shared TypeDescription helper (with the same
     * length / precision / dateFractions qualifiers as add_form_attribute);
     * {@code keyParameter}=true marks it a key parameter; {@code comment} is
     * optional. Idempotent on name.
     */
    String opAddFormParameter(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        String comment = JsonUtils.extractStringArgument(params, "comment"); //$NON-NLS-1$
        boolean keyParameter = JsonUtils.extractBooleanArgument(params, "keyParameter", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        String typeErrText = rejectUnsupportedParameterType(type);
        if (typeErrText != null)
        {
            return ToolResult.error(typeErrText).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        BmDefinedTypeHelper.QualifierOptions q = new BmDefinedTypeHelper.QualifierOptions();
        q.length = JsonUtils.extractIntegerArgument(params, "length"); //$NON-NLS-1$
        q.precision = JsonUtils.extractIntegerArgument(params, "precision"); //$NON-NLS-1$
        q.fractionDigits = JsonUtils.extractIntegerArgument(params, "fractionDigits"); //$NON-NLS-1$
        if (params != null && params.containsKey("nonNegative")) //$NON-NLS-1$
        {
            q.nonNegative = JsonUtils.extractBooleanArgument(params, "nonNegative", false); //$NON-NLS-1$
        }
        q.dateFractions = JsonUtils.extractStringArgument(params, "dateFractions"); //$NON-NLS-1$
        q.allowedLength = JsonUtils.extractStringArgument(params, "allowedLength"); //$NON-NLS-1$

        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }

        IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
        final Configuration config = cp != null ? cp.getConfiguration(project) : null;
        final String fName = name;
        final String fType = type;
        final String fComment = comment;
        final boolean fKey = keyParameter;
        final boolean[] already = { false };
        final boolean[] typeApplied = { false };
        final String[] typeErr = { null };
        final List<String> typeResolved = new ArrayList<>();
        final List<String> typeUnresolved = new ArrayList<>();
        final boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String result = helper.executeFormOperation(project, formFqn, dryRun, (tx, form) -> {
            if (helper.hasFormParameter(form, fName))
            {
                already[0] = true;
                return "FormParameter already exists: " + fName; //$NON-NLS-1$
            }
            Object param = helper.createFormParameter(fName, fComment, fKey);
            helper.addParameterToForm(form, param);
            if (fType != null && !fType.isEmpty())
            {
                // config may be null for external-object projects (.epf/.erf):
                // setFormAttributeTypes resolves primitives via the project-aware proxy;
                // reference types surface as unresolved rather than being skipped.
                {
                    try
                    {
                        BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setFormAttributeTypes(
                            param, project, config, Collections.singletonList(fType), q);
                        typeApplied[0] = tr.ok;
                        if (tr.resolved != null)
                        {
                            typeResolved.addAll(tr.resolved);
                        }
                        if (tr.unresolved != null)
                        {
                            typeUnresolved.addAll(tr.unresolved);
                        }
                        if (!tr.ok)
                        {
                            typeErr[0] = tr.error;
                        }
                    }
                    catch (Exception typeEx)
                    {
                        typeErr[0] = typeEx.getClass().getSimpleName() + ": " + typeEx.getMessage(); //$NON-NLS-1$
                    }
                }
            }
            return "added parameter " + fName; //$NON-NLS-1$
        });

        if (result == null || result.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResult(result, "add_form_parameter", formFqn); //$NON-NLS-1$
        }

        ToolResult resp = ToolResult.success()
            .put("operation", "add_form_parameter") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("name", fName) //$NON-NLS-1$
            .put("message", result); //$NON-NLS-1$
        if (already[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("name", fName); //$NON-NLS-1$
            idem.put("formFqn", formFqn); //$NON-NLS-1$
            resp = resp.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        else if (fType != null && !fType.isEmpty())
        {
            resp = resp.put("typeApplication", //$NON-NLS-1$
                TypeApplication.tag(fType, typeApplied[0], typeResolved, typeUnresolved,
                    typeErr[0]));
            if (TypeApplication.failed(typeApplied[0], typeUnresolved))
            {
                resp = resp.demote(TypeApplication.failureMessage(
                    "form parameter '" + fName + "'", fType, typeErr[0], dryRun, typeApplied[0])); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return resp.toJson();
    }

    /**
     * Guards {@code add_form_parameter} against a type the EDT model resolves but
     * a form parameter cannot carry. {@code Array} is the known case: the type
     * resolves, the form validates clean, and the parameter is then useless -
     * across 5789 form parameters of two real configurations it does not occur
     * once, while {@code ValueList} carries exactly this "pass a list of values
     * into the form" intent. Refusing costs one retry; writing it costs a form
     * that only misbehaves once the infobase is updated.
     *
     * @param type the requested parameter type, may be {@code null} or empty
     * @return the refusal text, or {@code null} when the type is acceptable
     */
    static String rejectUnsupportedParameterType(String type)
    {
        if (type == null || !"Array".equalsIgnoreCase(type.trim())) //$NON-NLS-1$
        {
            return null;
        }
        return "add_form_parameter: 'Array' is not a usable form-parameter type. " //$NON-NLS-1$
            + "Use 'ValueList' to pass a collection of values into a form, " //$NON-NLS-1$
            + "or a reference / primitive type for a single value."; //$NON-NLS-1$
    }

    /**
     * 1.43.x audit C (forms-advanced): removes a {@code FormParameter} from a
     * form by name (case-insensitive). Idempotent - a missing parameter yields
     * a success response with an {@code idempotentSkip} tag.
     */
    String opRemoveFormParameter(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }

        final String fName = name;
        final boolean[] removed = { false };
        final boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String result = helper.executeFormOperation(project, formFqn, dryRun, (tx, form) -> {
            removed[0] = helper.removeFormParameterByName(form, fName);
            return removed[0] ? "removed parameter " + fName //$NON-NLS-1$
                : "FormParameter not found: " + fName; //$NON-NLS-1$
        });

        if (result == null || result.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResult(result, "remove_form_parameter", formFqn); //$NON-NLS-1$
        }

        ToolResult resp = ToolResult.success()
            .put("operation", "remove_form_parameter") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("name", fName) //$NON-NLS-1$
            .put("message", result); //$NON-NLS-1$
        if (!removed[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("name", fName); //$NON-NLS-1$
            idem.put("formFqn", formFqn); //$NON-NLS-1$
            idem.put("action", "not-found"); //$NON-NLS-1$ //$NON-NLS-2$
            resp = resp.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        return resp.toJson();
    }

    /**
     * 1.41: adds a column to a parent FormAttribute of type Table. Idempotent.
     * Surfaces {@code formApiNotFound} structured tag when EDT does not
     * expose {@code FormFactory.createFormAttributeColumn}.
     */
    String opAddFormAttributeColumn(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        // The input schema advertises this parent as attributeName; the runtime
        // reads parentAttributeName. Accept attributeName as an alias so a client
        // that followed the schema does not fail with "parentAttributeName is required".
        // Resolve into a final variable - it is captured by the executeFormOperation lambda.
        String parentAttrResolved = JsonUtils.extractStringArgument(params, "parentAttributeName"); //$NON-NLS-1$
        if (parentAttrResolved == null || parentAttrResolved.isEmpty())
        {
            parentAttrResolved = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        }
        final String parentAttributeName = parentAttrResolved;
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        String dataPath = JsonUtils.extractStringArgument(params, "dataPath"); //$NON-NLS-1$
        // 1.43.x C4: a column needs a real valueType (else it is typeless, MAJOR
        // md-legacy-emf-check). Accept the same type + qualifier params as attributes.
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        BmDefinedTypeHelper.QualifierOptions colQualifiers = new BmDefinedTypeHelper.QualifierOptions();
        colQualifiers.length = JsonUtils.extractIntegerArgument(params, "length"); //$NON-NLS-1$
        colQualifiers.precision = JsonUtils.extractIntegerArgument(params, "precision"); //$NON-NLS-1$
        colQualifiers.fractionDigits = JsonUtils.extractIntegerArgument(params, "fractionDigits"); //$NON-NLS-1$
        if (params != null && params.containsKey("nonNegative")) //$NON-NLS-1$
        {
            colQualifiers.nonNegative = JsonUtils.extractBooleanArgument(params, "nonNegative", false); //$NON-NLS-1$
        }
        colQualifiers.dateFractions = JsonUtils.extractStringArgument(params, "dateFractions"); //$NON-NLS-1$
        colQualifiers.allowedLength = JsonUtils.extractStringArgument(params, "allowedLength"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(parentAttributeName, "parentAttributeName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        IConfigurationProvider colConfigProvider = Activator.getDefault().getConfigurationProvider();
        final Configuration colConfig = colConfigProvider != null
            ? colConfigProvider.getConfiguration(project) : null;
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String result = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) ->
            helper.addFormAttributeColumn(form, parentAttributeName, name, title, dataPath,
                type, project, colConfig, colQualifiers));
        return EditMetadataTool.formatFormResultWithApiTag(result, "add_form_attribute_column", formFqn); //$NON-NLS-1$
    }

    /**
     * 1.41: creates a FormAttribute of type DynamicList plus a UI Table
     * bound to it. Wizard properties (mainTable, autoSaveCustomization,
     * dynamicDataRead) are populated where the EDT API permits.
     */
    String opAddDynamicListTable(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        String tableName = JsonUtils.extractStringArgument(params, "tableName"); //$NON-NLS-1$
        String mainTable = JsonUtils.extractStringArgument(params, "mainTable"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(attributeName, "attributeName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(tableName, "tableName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        IConfigurationProvider dlCfgProvider = Activator.getDefault().getConfigurationProvider();
        final Configuration config = dlCfgProvider != null
            ? dlCfgProvider.getConfiguration(project) : null;
        Object[] resultHolder = new Object[1];
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String execResult = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) ->
        {
            BmFormHelper.DynamicListResult dlr = helper.addDynamicListAttributeAndTable(
                form, tx, attributeName, tableName, mainTable, title);
            // Type the new attribute as DynamicList - without
            // <valueType>DynamicList the form attribute creates no variable
            // ("Переменная X не определена") and the form fails to render.
            // Uses the same canonical-proxy machinery that types object
            // attributes (DynamicList resolves like a platform primitive).
            if (!dlr.idempotent && dlr.attribute != null)
            {
                BmDefinedTypeHelper.TypesResult typeRes = BmDefinedTypeHelper.setFormAttributeTypes(
                    dlr.attribute, project, config,
                    java.util.Collections.singletonList("DynamicList"), null); //$NON-NLS-1$
                if (typeRes != null && typeRes.error != null)
                {
                    dlr.typeNote = typeRes.error;
                }
            }
            // Attach the new UI Table to the form root (caller can move it later
            // via setFormItemProperty / move ops).
            if (!dlr.idempotent && dlr.table != null)
            {
                helper.addToContainer(form, dlr.table);
            }
            resultHolder[0] = dlr;
            return dlr.message;
        });
        if (execResult != null && execResult.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResultWithApiTag(execResult, "add_dynamic_list_table", formFqn); //$NON-NLS-1$
        }
        BmFormHelper.DynamicListResult dlr = (BmFormHelper.DynamicListResult) resultHolder[0];
        ToolResult tr = ToolResult.success()
            .put("operation", "add_dynamic_list_table") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("message", dlr != null ? dlr.message : execResult) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("tableName", tableName); //$NON-NLS-1$
        if (dlr != null)
        {
            tr.put("idempotent", dlr.idempotent); //$NON-NLS-1$
            if (dlr.typeNote != null)
            {
                tr.put("typeNote", "DynamicList valueType NOT applied: " + dlr.typeNote //$NON-NLS-1$ //$NON-NLS-2$
                    + " - the attribute may render as typeless."); //$NON-NLS-1$
            }
            if (!dlr.idempotent && mainTable != null && !mainTable.isEmpty())
            {
                // 1.43.x M1: mainTable is now bound headlessly via the target
                // object's derived dbViewDefs.getMainView() (when it exists).
                // Gated on !idempotent: a pre-existing attribute is not re-bound
                // here, so we do not emit a (misleading) mainTableBound=false.
                tr.put("mainTableBound", dlr.mainTableBound); //$NON-NLS-1$
                if (dlr.mainTableNote != null)
                {
                    tr.put("mainTableNote", dlr.mainTableNote); //$NON-NLS-1$
                }
            }
        }
        return tr.toJson();
    }

    /**
     * 1.41: creates a DataCompositionSettingsComposer FormAttribute plus two
     * UI tables (Settings + UserSettings). Returns success JSON enriched
     * with RU and EN BSL snippets ready to paste into
     * {@code ProcedureOnCreateAtServer}.
     */
    String opSetupSettingsComposerOnForm(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String composerName = JsonUtils.extractStringArgument(params, "composerName"); //$NON-NLS-1$
        String settingsTableName = JsonUtils.extractStringArgument(params, "settingsTableName"); //$NON-NLS-1$
        String userSettingsTableName = JsonUtils.extractStringArgument(params, "userSettingsTableName"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        Object[] resultHolder = new Object[1];
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String execResult = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) ->
        {
            BmFormHelper.SettingsComposerResult scr = helper.setupSettingsComposer(
                form, composerName, settingsTableName, userSettingsTableName);
            if (!scr.idempotent)
            {
                if (scr.settingsTable != null)
                {
                    helper.addToContainer(form, scr.settingsTable);
                }
                if (scr.userSettingsTable != null)
                {
                    helper.addToContainer(form, scr.userSettingsTable);
                }
            }
            resultHolder[0] = scr;
            return scr.message;
        });
        if (execResult != null && execResult.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResultWithApiTag(execResult, "setup_settings_composer_on_form", formFqn); //$NON-NLS-1$
        }
        BmFormHelper.SettingsComposerResult scr = (BmFormHelper.SettingsComposerResult) resultHolder[0];
        ToolResult tr = ToolResult.success()
            .put("operation", "setup_settings_composer_on_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("message", scr != null ? scr.message : execResult); //$NON-NLS-1$
        if (scr != null)
        {
            tr.put("idempotent", scr.idempotent) //$NON-NLS-1$
                .put("composerName", composerName != null ? composerName : "Composer") //$NON-NLS-1$ //$NON-NLS-2$
                .put("bslSnippetRu", scr.bslSnippetRu) //$NON-NLS-1$
                .put("bslSnippetEn", scr.bslSnippetEn); //$NON-NLS-1$
        }
        return tr.toJson();
    }
    /**
     * Removes a command from an existing form ({@code form.getFormCommands}). Deletes a named form
     * command - including an orphan left when a button is recreated against a name still held by a
     * stale command ({@code X1} without an action) - which {@code remove_form_item} cannot reach.
     * Symmetric to {@link #opAddFormCommand}.
     */
    String opRemoveFormCommand(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(commandName, "commandName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String result = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) -> {
            Object command = helper.findFormCommandByName(form, commandName);
            if (command == null)
            {
                return "Error: form command '" + commandName + "' not found in " + formFqn; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (helper.isCommandReferenced(form, command))
            {
                return "Error: form command '" + commandName //$NON-NLS-1$
                    + "' is still bound to a form button - remove the button " //$NON-NLS-1$
                    + "(remove_form_item) first, then remove the command."; //$NON-NLS-1$
            }
            boolean removed = helper.removeCommandFromForm(form, command);
            return removed ? "removed command " + commandName //$NON-NLS-1$
                : "Error: command '" + commandName + "' was not removable from " + formFqn; //$NON-NLS-1$ //$NON-NLS-2$
        });
        if (result != null && result.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResult(result, "remove_form_command", formFqn); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("operation", "remove_form_command") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("commandName", commandName) //$NON-NLS-1$
            .put("message", result != null ? result : "ok") //$NON-NLS-1$ //$NON-NLS-2$
            .put("hint", "Command '" + commandName + "' was unreferenced - removed cleanly; " //$NON-NLS-1$ //$NON-NLS-2$
                + "recreate via add_form_command if a button still needs it.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * L65: sets a display property (title / representation / picture) of an
     * existing form command. {@code set_form_item_property} addresses form
     * items only and answers "Form item not found" for a command name, so a
     * command's own properties need this dedicated op. {@code picture} is
     * build-limited (see {@link BmFormHelper#setFormCommandProperty}).
     */
    String opSetFormCommandProperty(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        String propertyName = JsonUtils.extractStringArgument(params, "propertyName"); //$NON-NLS-1$
        String propertyValue = JsonUtils.extractStringArgument(params, "propertyValue"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(commandName, "commandName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(propertyName, "propertyName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        final String propFinal = propertyName;
        final String valFinal = propertyValue;
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String result = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) -> {
            Object command = helper.findFormCommandByName(form, commandName);
            if (command == null)
            {
                return "Error: form command '" + commandName + "' not found in " + formFqn; //$NON-NLS-1$ //$NON-NLS-2$
            }
            try
            {
                return helper.setFormCommandProperty(command, propFinal, valFinal);
            }
            catch (Exception e)
            {
                return "Error: setFormCommandProperty failed: " //$NON-NLS-1$
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        });
        if (result != null && result.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResult(result, "set_form_command_property", formFqn); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("operation", "set_form_command_property") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("commandName", commandName) //$NON-NLS-1$
            .put("propertyName", propertyName) //$NON-NLS-1$
            .put("message", result != null ? result : "ok") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Adds a new command to an existing form.
     */
    String opAddFormCommand(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        // Bug C: the command's action (its BSL handler) used to be ignored, so
        // the command had no <action> and was non-functional. Read the handler
        // procedure name; default to the command name itself - that is EDT's own
        // convention (a stock command "X" has handler procedure "X"), not a
        // suffixed "XКоманда".
        String handlerParam = JsonUtils.extractStringArgument(params, "handler"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(commandName, "commandName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        final String handler = (handlerParam != null && !handlerParam.isEmpty())
            ? handlerParam
            : commandName; // EDT convention: handler procedure = command name
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String result = helper.executeFormOperation(project, formFqn, formDryRun, (tx, form) -> {
            // #6: bind the handler to an EXISTING command when one already
            // carries this name (e.g. a command auto-created by add_button)
            // instead of stacking a duplicate <formCommands> entry with the same
            // name+id, which breaks the form. Only create a new command when none
            // exists yet.
            Object command = helper.findFormCommandByName(form, commandName);
            boolean reused = command != null;
            if (!reused)
            {
                command = helper.createFormCommand(commandName, title);
                helper.addCommandToForm(form, command);
            }
            // Bug C: wire the command's action to the BSL handler procedure so
            // the command actually invokes something. Best-effort inside the
            // helper - a reflective failure leaves the command created.
            helper.setFormCommandAction(command, handler);
            return (reused ? "bound handler to existing command " //$NON-NLS-1$
                : "added command ") + commandName; //$NON-NLS-1$
        });
        // Error path: keep the structured error shape from formatFormResult.
        if (result != null && result.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResult(result, "add_form_command", formFqn); //$NON-NLS-1$
        }
        // Success: surface the handler name + a hint to add its BSL body (the
        // platform cannot generate a procedure body inside a BM transaction),
        // mirroring opAddFormEventHandler.
        ToolResult ok = ToolResult.success()
            .put("operation", "add_form_command") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("commandName", commandName) //$NON-NLS-1$
            .put("handler", handler) //$NON-NLS-1$
            .put("message", result != null ? result : "ok") //$NON-NLS-1$ //$NON-NLS-2$
            .put("hint", //$NON-NLS-1$
                "Command action wired to handler '" + handler //$NON-NLS-1$
                    + "'. Add the procedure to the form's Module.bsl via " //$NON-NLS-1$
                    + "write_module_source mode=append: &НаКлиенте Процедура " //$NON-NLS-1$
                    + handler + "(Команда) ... КонецПроцедуры - the platform " //$NON-NLS-1$
                    + "cannot generate procedure bodies inside a BM transaction."); //$NON-NLS-1$
        // Nudge toward EDT naming: a form command is named by its action, not by
        // a "Command"/"Команда" suffix (that reads as machine-generated). EDT
        // itself names command "X", handler "X", and the button "ФормаX".
        String lowerName = commandName.toLowerCase();
        if (lowerName.endsWith("command") || lowerName.endsWith("команда")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            ok.put("warning", //$NON-NLS-1$
                "commandName '" + commandName + "' carries a redundant suffix. Name a form " //$NON-NLS-1$ //$NON-NLS-2$
                    + "command by its action (e.g. 'ПечатьСкладскихНакладных', not " //$NON-NLS-1$
                    + "'ПечатьСкладскихНакладныхCommand'); EDT names the button 'Форма<Command>' " //$NON-NLS-1$
                    + "to avoid a same-name clash."); //$NON-NLS-1$
        }
        return ok.toJson();
    }

    /**
     * Sets a property on a named form item. Accepts {@code title}, {@code visible},
     * {@code enabled}, {@code readOnly}, {@code dataPath}, plus any EMF feature
     * exposed as {@code setXxx} on the resolved item.
     */
    String opSetFormItemProperty(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "itemName"); //$NON-NLS-1$
        // #7: attributeName routes set_property to a form ATTRIBUTE's extInfo
        // (e.g. a DynamicList attribute's queryText / customQuery), which is not
        // a form item and so cannot be addressed via itemName.
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        String propertyName = JsonUtils.extractStringArgument(params, "propertyName"); //$NON-NLS-1$
        String propertyValue = JsonUtils.extractStringArgument(params, "propertyValue"); //$NON-NLS-1$
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        final boolean targetsAttribute = attributeName != null && !attributeName.isEmpty();

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + (targetsAttribute ? "" : EditMetadataTool.requireNonEmpty(itemName, "itemName")) //$NON-NLS-1$ //$NON-NLS-2$
            + EditMetadataTool.requireNonEmpty(propertyName, "propertyName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        // 1.42 (B4): when the property is `picture`, validate the value
        // up-front (StdPicture.X / CommonPicture.X) so a typo fails before
        // anything is written. The same check is already done in
        // createObjectCommand; mirroring it here keeps the contract
        // identical across the lifecycle of the picture field.
        if ("picture".equalsIgnoreCase(propertyName) //$NON-NLS-1$
            && propertyValue != null && !propertyValue.isEmpty())
        {
            String pictureError = PictureValidator.validate(projectName, propertyValue);
            if (pictureError != null)
            {
                return ToolResult.error(pictureError).toJson();
            }
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        // #7: route to a form attribute's extInfo when attributeName is given
        // (DynamicList queryText / customQuery / dynamicDataRead live there, not
        // on a form item). mainTable needs a DbViewDef and is reported as such.
        if (targetsAttribute)
        {
            final BmFormHelper attrHelper = helper;
            String attrResult = attrHelper.executeFormOperation(project, formFqn, formDryRun,
                (tx, form) -> attrHelper.setAttributeExtInfoProperty(form, attributeName,
                    propertyName, propertyValue));
            if (attrResult != null && attrResult.startsWith("Error:")) //$NON-NLS-1$
            {
                return EditMetadataTool.formatFormResult(attrResult, "set_property", formFqn); //$NON-NLS-1$
            }
            return ToolResult.success()
                .put("operation", "set_property") //$NON-NLS-1$ //$NON-NLS-2$
                .put("formFqn", formFqn) //$NON-NLS-1$
                .put("attributeName", attributeName) //$NON-NLS-1$
                .put("propertyName", propertyName) //$NON-NLS-1$
                .put("propertyValue", propertyValue != null ? propertyValue : "") //$NON-NLS-1$ //$NON-NLS-2$
                .put("message", attrResult) //$NON-NLS-1$
                .toJson();
        }
        // 1.43.x: choiceParameters on a form FIELD live on the field's extInfo
        // (mirror of the attribute-side; closes the one RSV 4.7 form gap).
        // choiceParameterLinks are deferred - a form field's link resolves against
        // form items, not a metadata FieldSource, so the attribute-side resolver
        // does not apply.
        if (itemName != null && !itemName.isEmpty()
            && ("choiceParameters".equalsIgnoreCase(propertyName) //$NON-NLS-1$
                || "choiceParameterLinks".equalsIgnoreCase(propertyName))) //$NON-NLS-1$
        {
            if ("choiceParameterLinks".equalsIgnoreCase(propertyName)) //$NON-NLS-1$
            {
                return ToolResult.error("choiceParameterLinks on form fields is not yet supported " //$NON-NLS-1$
                    + "(a form field's link resolves against form items, not metadata FieldSource " //$NON-NLS-1$
                    + "fields). Use choiceParameters for fixed filter values.").toJson(); //$NON-NLS-1$
            }
            java.util.List<java.util.Map<String, String>> cpItems = EditMetadataTool.parseStructArray(propertyValue);
            if (cpItems.isEmpty())
            {
                return ToolResult.error("choiceParameters requires a JSON array, e.g. " //$NON-NLS-1$
                    + "[{\"name\":\"Отбор.ЭтоГруппа\",\"value\":\"false\"}]").toJson(); //$NON-NLS-1$
            }
            java.util.List<String> cpApplied = new java.util.ArrayList<>();
            java.util.List<String> cpDiag = new java.util.ArrayList<>();
            final BmFormHelper cpHelper = helper;
            String cpResult = cpHelper.executeFormOperation(project, formFqn, formDryRun, (tx, form) -> {
                String e = cpHelper.applyFieldChoiceParameters(form, itemName, cpItems, cpApplied, cpDiag);
                if (e != null)
                {
                    throw new RuntimeException(e);
                }
                return itemName + ".choiceParameters = " + cpApplied; //$NON-NLS-1$
            });
            if (cpResult != null && cpResult.startsWith("Error:")) //$NON-NLS-1$
            {
                return EditMetadataTool.formatFormResult(cpResult, "set_property", formFqn); //$NON-NLS-1$
            }
            return ToolResult.success()
                .put("operation", "set_property") //$NON-NLS-1$ //$NON-NLS-2$
                .put("formFqn", formFqn) //$NON-NLS-1$
                .put("itemName", itemName) //$NON-NLS-1$
                .put("propertyName", "choiceParameters") //$NON-NLS-1$ //$NON-NLS-2$
                .put("choiceParametersApplied", cpApplied) //$NON-NLS-1$
                .put("message", cpResult) //$NON-NLS-1$
                .toJson();
        }
        // 1.42: collect optional tip / warning tags during the transaction
        // so the response can carry them without a second BM round-trip.
        java.util.List<String> tipTags = new java.util.ArrayList<>();
        java.util.List<String> warningTags = new java.util.ArrayList<>();
        final BmFormHelper helperFinal = helper;
        String result = helperFinal.executeFormOperation(project, formFqn, formDryRun, (tx, form) -> {
            String setErr = helperFinal.setItemProperty(form, itemName, propertyName, propertyValue);
            if (setErr != null)
            {
                throw new RuntimeException(setErr);
            }
            collectFormatHelpTip(propertyName, tipTags);
            collectPictureRepresentationWarning(helperFinal, form, itemName, propertyName,
                warningTags);
            return itemName + "." + propertyName + " = " //$NON-NLS-1$ //$NON-NLS-2$
                + (propertyValue != null ? propertyValue : "(null)"); //$NON-NLS-1$
        });
        if (result != null && result.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResult(result, "set_form_item_property", formFqn); //$NON-NLS-1$
        }
        ToolResult tr = ToolResult.success()
            .put("operation", "set_form_item_property") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("itemName", itemName) //$NON-NLS-1$
            .put("propertyName", propertyName) //$NON-NLS-1$
            .put("propertyValue", propertyValue != null ? propertyValue : "") //$NON-NLS-1$ //$NON-NLS-2$
            .put("message", result); //$NON-NLS-1$
        if (!tipTags.isEmpty())
        {
            tr.put("formatHelp", tipTags.get(0)); //$NON-NLS-1$
        }
        if (!warningTags.isEmpty())
        {
            tr.put("warning", warningTags.get(0)); //$NON-NLS-1$
        }
        return tr.toJson();
    }

    /**
     * 1.42: appends a short {@code formatHelp} tip when the property being
     * set is a format string ({@code format} / {@code editFormat}). RSV 4.2
     * release notes flag the silent {@code ЧН} default as the most common
     * cause of "zero disappears in print" reports.
     */
    private static void collectFormatHelpTip(String propertyName, java.util.List<String> tips)
    {
        String lc = propertyName.toLowerCase();
        if ("format".equals(lc) || "editformat".equals(lc)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            tips.add("Format-string tip: ЧН=0 keeps zeros visible (the default trims " //$NON-NLS-1$
                + "them, which surprises users on totals). Common params: ЧДЦ " //$NON-NLS-1$
                + "(decimal places), ДФ (date format), Л (language). Full reference: " //$NON-NLS-1$
                + "get_platform_docs typeName=ФорматнаяСтрока."); //$NON-NLS-1$
        }
    }

    /**
     * 1.42: when the property being set is {@code picture} on a button whose
     * {@code representation} is still {@code Auto}, the platform renders the
     * button as text only - the icon never appears. Emits the same warning
     * RSV 4.2 surfaces for this case.
     */
    private static void collectPictureRepresentationWarning(BmFormHelper helper, Object form,
        String itemName, String propertyName, java.util.List<String> warnings)
    {
        if (!"picture".equalsIgnoreCase(propertyName)) //$NON-NLS-1$
        {
            return;
        }
        try
        {
            // Reuse the outer-scope helper - we are already inside its
            // executeFormOperation transaction, so creating a second helper
            // here would re-init OSGi service trackers in the middle of an
            // active BM read/write task.
            Object item = helper.findItemByName(form, itemName);
            if (item == null)
            {
                return;
            }
            // Probe ButtonRepresentation getter only on buttons - reflection
            // throws NoSuchMethodException for fields/groups/tables, which we
            // swallow as "not applicable".
            Object representation;
            try
            {
                representation = item.getClass().getMethod("getRepresentation").invoke(item); //$NON-NLS-1$
            }
            catch (NoSuchMethodException nsme)
            {
                return; // Not a button.
            }
            String repName = representation != null ? representation.toString() : "Auto"; //$NON-NLS-1$
            if ("Auto".equalsIgnoreCase(repName)) //$NON-NLS-1$
            {
                warnings.add("Picture set on a button whose representation is Auto - the " //$NON-NLS-1$
                    + "platform may render the button as text only. Set " //$NON-NLS-1$
                    + "representation=Picture or PictureAndText via setProperty " //$NON-NLS-1$
                    + "to make the icon visible."); //$NON-NLS-1$
            }
        }
        catch (Exception ignored)
        {
            // Best-effort tip - never fail the operation because of probe issues.
        }
    }

    // -----------------------------------------------------------------------
    // 1.40: Form ops migrated from edit_form (delegate routing)
    // -----------------------------------------------------------------------

    /**
     * 1.40: routes 6 form ops migrated from EditFormTool (addField/addGroup/
     * addButton/addTable/addDecoration/removeFormItem) directly to the
     * EditFormTool implementation. The helper class has the full reflective
     * logic for each operation; this dispatcher hands params off without
     * renaming - operation names already match camelCase convention.
     * <p>
     * Once Iter 1.7 (deprecated EditFormTool alias) lands, this delegate
     * inverts: EditFormTool calls into edit_metadata. For 1.40 we keep the
     * working logic where it is and just expose the operations through both
     * tools.
     */
    String delegateToEditForm(String op, Map<String, String> params)
    {
        // 1.42.4 BUG-T9: edit_metadata exposes snake_case ops (add_button,
        // add_decoration, ...) per the 1.42 naming policy, but EditFormTool
        // still parses the legacy camelCase form (addButton, addDecoration,
        // removeItem). Without this translation EditFormTool returns
        // "Unknown operation 'add_button'. Did you mean: add_calculated_field?".
        EditFormTool editForm = new EditFormTool();
        Map<String, String> forwarded = new LinkedHashMap<>(params);
        forwarded.put("operation", snakeToEditFormOp(op)); //$NON-NLS-1$
        // 1.42.4 BUG-T10: edit_metadata uses `ownerFqn` as the canonical
        // FQN parameter for every operation (matches schema), but
        // EditFormTool still requires the BM top-object form FQN under the
        // legacy `formFqn` name (Catalog.X.Form.Y.Form). Translate the
        // unified-schema ownerFqn (Catalog.X.Forms.Y or already-canonical
        // formFqn) into the BM form-FQN shape before delegation.
        String formFqn = forwarded.get("formFqn"); //$NON-NLS-1$
        if (formFqn == null || formFqn.isEmpty())
        {
            String ownerFqn = forwarded.get("ownerFqn"); //$NON-NLS-1$
            if (ownerFqn != null && !ownerFqn.isEmpty())
            {
                forwarded.put("formFqn", toBmFormFqn(ownerFqn)); //$NON-NLS-1$
            }
        }
        // Bug E: EditFormTool.getResponseType() == MARKDOWN and returns a
        // YamlFrontMatter string (---\ntool: edit_form\nstatus: success\n...\n---\n
        // <body>). EditMetadataTool.getResponseType() == JSON, so the protocol
        // handler JSON-parses that markdown and fails with
        // MalformedJsonException (-32603). Convert the YamlFrontMatter response into
        // the JSON shape this tool emits (mirrors formatFormResult).
        String markdown = editForm.execute(forwarded);
        return convertEditFormMarkdownToJson(markdown, op, forwarded.get("formFqn")); //$NON-NLS-1$
    }

    /**
     * Bug E: parses an {@link EditFormTool} YamlFrontMatter markdown response and
     * re-emits it as the JSON shape {@code EditMetadataTool} returns.
     * <p>
     * The YamlFrontMatter header is a {@code ---} block of {@code key: value} lines
     * (see {@link ru.aiedt.mcp.server.support.YamlFrontMatter}); EditFormTool
     * writes a {@code status: success} or {@code status: error} line into it.
     * The body is everything after the closing {@code ---}. On {@code success}
     * the body becomes the {@code message}; on {@code error} it becomes the
     * error text. When the YamlFrontMatter cannot be parsed, the raw string is
     * wrapped as a success message (or error when it looks like one) so the
     * caller always receives valid JSON.
     *
     * @param markdown the raw EditFormTool response (YamlFrontMatter + body)
     * @param op the unified (snake_case) operation name for the response
     * @param formFqn the form FQN forwarded to EditFormTool (may be null)
     * @return a JSON string in EditMetadataTool's response shape
     */
    private static String convertEditFormMarkdownToJson(String markdown, String op, String formFqn)
    {
        if (markdown == null)
        {
            return ToolResult.error(op + " failed: empty response from edit_form") //$NON-NLS-1$
                .put("operation", op) //$NON-NLS-1$
                .toJson();
        }
        String status = null;
        String body = markdown;
        // Parse a leading YamlFrontMatter block: "---\n" <lines> "---\n" <body>.
        // Strip a leading UTF-8 BOM defensively (YamlFrontMatter.build() never emits
        // one, but be robust against an upstream wrapper that might).
        String trimmed = markdown.startsWith("﻿") ? markdown.substring(1) : markdown; //$NON-NLS-1$
        if (trimmed.startsWith("---")) //$NON-NLS-1$
        {
            String[] lines = trimmed.split("\n", -1); //$NON-NLS-1$
            int closeIdx = -1;
            for (int i = 1; i < lines.length; i++)
            {
                String line = lines[i];
                if ("---".equals(line.trim())) //$NON-NLS-1$
                {
                    closeIdx = i;
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0)
                {
                    String key = line.substring(0, colon).trim();
                    if ("status".equals(key)) //$NON-NLS-1$
                    {
                        // Strip optional surrounding quotes from the YAML scalar.
                        String val = line.substring(colon + 1).trim();
                        if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
                        {
                            val = val.substring(1, val.length() - 1);
                        }
                        status = val;
                    }
                }
            }
            if (closeIdx >= 0)
            {
                StringBuilder bodyBuilder = new StringBuilder();
                for (int i = closeIdx + 1; i < lines.length; i++)
                {
                    bodyBuilder.append(lines[i]);
                    if (i < lines.length - 1)
                    {
                        bodyBuilder.append('\n');
                    }
                }
                body = bodyBuilder.toString();
            }
        }
        String message = (body == null || body.trim().isEmpty()) ? "ok" : body.trim(); //$NON-NLS-1$
        boolean isError;
        if (status != null)
        {
            isError = "error".equalsIgnoreCase(status); //$NON-NLS-1$
        }
        else
        {
            // YamlFrontMatter not parseable: fall back to a heuristic so we never
            // hand back markdown the JSON protocol handler would choke on.
            String probe = message.toLowerCase();
            isError = probe.startsWith("error") || probe.contains("\"error\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (isError)
        {
            return ToolResult.error(message)
                .put("operation", op) //$NON-NLS-1$
                .toJson();
        }
        ToolResult ok = ToolResult.success()
            .put("operation", op) //$NON-NLS-1$
            .put("message", message); //$NON-NLS-1$
        if (formFqn != null && !formFqn.isEmpty())
        {
            ok.put("formFqn", formFqn); //$NON-NLS-1$
        }
        return ok.toJson();
    }

    /**
     * Same translation as {@code GetFormStructureTool.toBmFormFqn} - kept
     * private here to avoid coupling EditMetadataTool to a UI tool's
     * private helper. Maps:
     * <ul>
     *   <li>{@code CommonForm.X} -> {@code CommonForm.X.Form}</li>
     *   <li>{@code Type.Object.Forms.Name} -> {@code Type.Object.Form.Name.Form}</li>
     *   <li>{@code <already canonical Form FQN ending in .Form>} -> as-is</li>
     * </ul>
     */
    private static String toBmFormFqn(String formPath)
    {
        if (formPath == null || formPath.isEmpty())
        {
            return formPath;
        }
        if (formPath.startsWith("CommonForm.")) //$NON-NLS-1$
        {
            return formPath.endsWith(".Form") ? formPath : formPath + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String normalized = formPath.replace(".Forms.", ".Form."); //$NON-NLS-1$ //$NON-NLS-2$
        return normalized.endsWith(".Form") ? normalized : normalized + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Maps an edit_metadata form-op to the op name EditFormTool's dispatch expects.
     * EditFormTool uses snake_case (add_field / add_group / add_button / add_table /
     * add_decoration / remove_item), so the add_* ops pass through unchanged; only
     * remove_form_item is translated to EditFormTool's remove_item. (This previously
     * camelCased them - add_field -> "addField" - which no longer matched
     * EditFormTool's snake_case cases and produced "Unknown operation: addField".)
     */
    private static String snakeToEditFormOp(String op)
    {
        if (op == null)
        {
            return null;
        }
        if ("remove_form_item".equals(op)) //$NON-NLS-1$
        {
            return "remove_item"; //$NON-NLS-1$
        }
        return op;
    }

    /**
     * 1.40.2: addRadioButton - delegates to EditFormTool addField with
     * {@code elementType=RadioButton}. BmFormHelper already understands the
     * RadioButton ext-info (see {@code createRadioButtonsFieldExtInfo}). Caller
     * may still pass {@code elementType} explicitly; we set it here only when
     * absent.
     */
    String delegateToEditFormAsRadioButton(Map<String, String> params)
    {
        Map<String, String> forwarded = new LinkedHashMap<>(params);
        forwarded.put("operation", "add_field"); //$NON-NLS-1$ //$NON-NLS-2$
        forwarded.putIfAbsent("elementType", "RadioButton"); //$NON-NLS-1$ //$NON-NLS-2$
        EditFormTool editForm = new EditFormTool();
        return editForm.execute(forwarded);
    }

    /**
     * 1.40: list available stock pictures by name. Probes
     * {@code com._1c.g5.v8.dt.platform.pictures.StandardPictures} when present
     * and falls back to the user's CommonPicture library exposed via the
     * project's configuration.
     */
    String opListPictures(Map<String, String> params)
    {
        String filter = JsonUtils.extractStringArgument(params, "filter"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        java.util.List<String> stock = listStockPictures(filter);
        java.util.List<String> common = listCommonPictures(projectName, filter);
        return ToolResult.success()
            .put("operation", "list_pictures") //$NON-NLS-1$ //$NON-NLS-2$
            .put("filter", filter == null ? "" : filter) //$NON-NLS-1$ //$NON-NLS-2$
            .put("stockPictureCount", stock.size())
            .put("stockPictures", stock)
            .put("commonPictureCount", common.size())
            .put("commonPictures", common)
            .put("hint", "Stock picture: pass to setProperty as bare name. " //$NON-NLS-1$
                + "CommonPicture: pass as 'CommonPicture.<Name>'.")
            .toJson();
    }

    private static java.util.List<String> listStockPictures(String filter)
    {
        // Probe several candidate StandardPictures classes - present on most
        // EDT builds but namespaced differently across versions.
        for (String cls : new String[] {
            "com._1c.g5.v8.dt.platform.pictures.StandardPictures",
            "com._1c.g5.v8.dt.platform.pictures.PlatformPictures",
            "com._1c.g5.v8.dt.ui.platform.PlatformPictures"
        })
        {
            try
            {
                Class<?> clazz = Class.forName(cls);
                java.util.List<String> names = new java.util.ArrayList<>();
                for (java.lang.reflect.Field f : clazz.getDeclaredFields())
                {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && java.lang.reflect.Modifier.isPublic(f.getModifiers()))
                    {
                        String n = f.getName();
                        if (filter == null || filter.isEmpty()
                            || n.toLowerCase().contains(filter.toLowerCase()))
                        {
                            names.add(n);
                        }
                    }
                }
                java.util.Collections.sort(names);
                return names;
            }
            catch (ClassNotFoundException ignored)
            {
                // try next
            }
        }
        return java.util.Collections.emptyList();
    }

    private static java.util.List<String> listCommonPictures(String projectName, String filter)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return java.util.Collections.emptyList();
        }
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists() || !project.isOpen())
            {
                return java.util.Collections.emptyList();
            }
            Configuration config = Activator.getDefault().getConfigurationProvider()
                .getConfiguration(project);
            if (config == null)
            {
                return java.util.Collections.emptyList();
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            // Configuration declares getCommonPictures(), so it is called, not looked up.
            EList<?> pictures = config.getCommonPictures();
            for (Object pic : pictures)
            {
                if (pic instanceof MdObject)
                {
                    String n = ((MdObject) pic).getName();
                    if (filter == null || filter.isEmpty()
                        || n.toLowerCase().contains(filter.toLowerCase()))
                    {
                        names.add("CommonPicture." + n);
                    }
                }
            }
            java.util.Collections.sort(names);
            return names;
        }
        catch (Exception e)
        {
            Activator.logWarning("listCommonPictures failed: " + e.getMessage()); //$NON-NLS-1$
            return java.util.Collections.emptyList();
        }
    }
}
