package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.support.BmExtensionHelper;
import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Misc cluster of {@code edit_metadata}: the residual handlers that did not belong to a
 * domain cluster - form-item functional options (add/remove_form_item_functional_option),
 * the universal item move/remove (Common: routes by container FQN to form / DCS / metadata),
 * and the Extensions adopt dispatcher (adoptObject/adoptObjects/adoptChild/adoptFormItem/
 * adoptModule). Extracted verbatim from {@link EditMetadataTool} (Inc4 god-class split,
 * final extraction); handlers are package-visible and dispatched through the single-source
 * op-registry. Shared stateless helpers live on {@link EditMetadataTool} (qualified calls);
 * getFunctionalOptionsList is a private cluster-local helper here (used only by
 * opFormItemFunctionalOption).
 */
final class MiscOps
{
    private static EList getFunctionalOptionsList(EObject owner)
    {
        try
        {
            Object res = owner.getClass().getMethod("getFunctionalOptions").invoke(owner); //$NON-NLS-1$
            if (res instanceof EList)
            {
                return (EList) res;
            }
        }
        catch (ReflectiveOperationException e)
        {
            Activator.logWarning("getFunctionalOptions() reflection failed for " //$NON-NLS-1$
                + owner.eClass().getName() + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /** Adds {@code item} to a raw {@link EList} (unchecked, EMF validates element type). */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    /**
     * J2b (Functional Options on form items): adds or removes a FunctionalOption
     * reference in a form ATTRIBUTE's or form COMMAND's {@code functionalOptions}
     * list. Target exactly one of {@code attributeName} / {@code commandName}.
     * Visual items are rejected (they do not carry the feature). Idempotent.
     * <p>
     * {@code attributeName} resolves a top-level form attribute, or a
     * {@code FormAttributeColumn} via a dotted {@code parent.column} path
     * (columns also extend AbstractFormAttribute and carry the feature). A bare
     * column name resolves when it is unique across the form's column-bearing
     * attributes; an ambiguous bare name throws with the candidate
     * {@code parent.column} pairs.
     * <p>
     * This is a FORM operation, so it runs through
     * {@link BmFormHelper#executeFormOperation} (which persists the Form.form on
     * commit), not the config-level {@code executeWriteOnObject}. Every failure is
     * raised as a thrown exception rather than an {@code "Error: ..."} return so it
     * is not swallowed by the form helper's dryRun-abort path.
     *
     * @param add true to add the option reference, false to remove it
     */
    String opFormItemFunctionalOption(Map<String, String> params, boolean add)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        final String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        String valueFqn = JsonUtils.extractStringArgument(params, "valueFqn"); //$NON-NLS-1$
        final boolean formDryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        final String opLabel = add ? "add_form_item_functional_option" //$NON-NLS-1$
            : "remove_form_item_functional_option"; //$NON-NLS-1$

        final boolean hasAttr = attributeName != null && !attributeName.isEmpty();
        final boolean hasCmd = commandName != null && !commandName.isEmpty();

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(valueFqn, "valueFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        if (hasAttr == hasCmd)
        {
            return ToolResult.error(opLabel + " requires exactly one of attributeName or " //$NON-NLS-1$
                + "commandName. Functional options live only on form attributes and form " //$NON-NLS-1$
                + "commands, never on visual items (fields, tables, buttons, groups, " //$NON-NLS-1$
                + "decorations).").toJson(); //$NON-NLS-1$
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

        final String fAttributeName = attributeName;
        final String fCommandName = commandName;
        final String fValueFqn = valueFqn;
        final String normValueFqn = MetadataTypeCatalog.normalizeFqn(valueFqn);
        final boolean[] idempotentSkip = { false };
        final BmFormHelper fHelper = helper;

        String result = fHelper.executeFormOperation(project, formFqn, formDryRun, (tx, form) -> {
            Object item = hasAttr ? fHelper.getFormAttributeOrColumn(form, fAttributeName)
                : fHelper.findFormCommandByName(form, fCommandName);
            if (item == null)
            {
                throw new RuntimeException((hasAttr ? "Form attribute '" + fAttributeName //$NON-NLS-1$
                    : "Form command '" + fCommandName) //$NON-NLS-1$
                    + "' not found on form " + formFqn + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!(item instanceof EObject))
            {
                throw new RuntimeException("Resolved form item is not an EObject."); //$NON-NLS-1$
            }
            @SuppressWarnings("rawtypes")
            EList fo = getFunctionalOptionsList((EObject) item);
            if (fo == null)
            {
                throw new RuntimeException("This form item has no functionalOptions collection " //$NON-NLS-1$
                    + "(only form attributes and form commands support functional options)."); //$NON-NLS-1$
            }
            Object value = EditMetadataTool.resolveReferenceTarget((IBmTransaction) tx, normValueFqn);
            if (!(value instanceof EObject))
            {
                throw new RuntimeException("FunctionalOption not found: " + normValueFqn //$NON-NLS-1$
                    + " (valueFqn=" + fValueFqn + "). Expected a FunctionalOption FQN, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "e.g. FunctionalOption.ИспользоватьСклады."); //$NON-NLS-1$
            }
            // Rejects a mistargeted valueFqn (e.g. Catalog.X) - the EReference type is FunctionalOption.
            EditMetadataTool.assertAssignableToRef((EObject) item, "functionalOptions", value, normValueFqn); //$NON-NLS-1$
            boolean present = EditMetadataTool.listContainsResolved(fo, value);
            if (add)
            {
                if (present)
                {
                    idempotentSkip[0] = true;
                    return fValueFqn + " (already set)"; //$NON-NLS-1$
                }
                EditMetadataTool.addToRawList(fo, (EObject) value);
                return fValueFqn + " (added)"; //$NON-NLS-1$
            }
            if (!present)
            {
                idempotentSkip[0] = true;
                return fValueFqn + " (not set)"; //$NON-NLS-1$
            }
            EditMetadataTool.removeResolved(fo, value);
            return fValueFqn + " (removed)"; //$NON-NLS-1$
        });

        if (result != null && result.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResult(result, opLabel, formFqn);
        }
        ToolResult ok = ToolResult.success()
            .put("operation", opLabel) //$NON-NLS-1$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put(hasAttr ? "attributeName" : "commandName", hasAttr ? attributeName : commandName) //$NON-NLS-1$ //$NON-NLS-2$
            .put("valueFqn", valueFqn) //$NON-NLS-1$
            .put("message", result != null ? result : "ok"); //$NON-NLS-1$ //$NON-NLS-2$
        if (idempotentSkip[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("valueFqn", valueFqn); //$NON-NLS-1$
            idem.put("action", add ? "already-present" : "not-present"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ok.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        return ok.toJson();
    }

    /**
     * Finds the first single-argument setter method named {@code setterName} on
     * {@code clazz}, scanning public methods (EMF reference setters take the
     * referenced interface type, which the caller does not know ahead of time,
     * so {@code getMethod(name, exactType)} is not usable). Returns {@code null}
     * when no such method exists.
     */
    // -----------------------------------------------------------------------
    // Common operations (1.37)
    // -----------------------------------------------------------------------

    /**
     * Moves a form item into another container of the same form: {@code name} is
     * the item, {@code parentName} the destination (absent means the form root),
     * {@code beforeName} an optional sibling to land in front of. The form is
     * taken from {@code formFqn}, or from {@code containerFqn} when only that is
     * given.
     * <p>
     * Only the form scope moves anything. DCS settings and metadata collections
     * have their own ordered-collection operations, and this returns an error
     * naming them rather than a success that moved nothing - the shape this
     * operation had before, which left rebuilding a form the one editing job that
     * had to go around the plugin.
     */
    String opMoveItem(Map<String, String> params)
    {
        String containerFqn = JsonUtils.extractStringArgument(params, "containerFqn"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        final String target = formFqn != null && !formFqn.isEmpty() ? formFqn : containerFqn;
        if (target == null || target.isEmpty() || itemName == null || itemName.isEmpty())
        {
            return ToolResult.error("move_item requires name plus formFqn (or containerFqn) " //$NON-NLS-1$
                + "naming the form that holds it.").toJson(); //$NON-NLS-1$
        }
        if (target.contains(".Template") || target.contains(".DCS")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("move_item works inside a form. For a composition schema " //$NON-NLS-1$
                + "use dcs_workshop (moveSchemaParameter / moveSettingsItem).").toJson(); //$NON-NLS-1$
        }
        if (!target.contains(".Forms.") && !target.contains(".Form.") //$NON-NLS-1$ //$NON-NLS-2$
            && !target.startsWith("CommonForm.")) //$NON-NLS-1$
        {
            return ToolResult.error("move_item works inside a form, and '" + target //$NON-NLS-1$
                + "' is not one. To reorder the content of a metadata object use the " //$NON-NLS-1$
                + "typed operation for that collection.").toJson(); //$NON-NLS-1$
        }
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName"); //$NON-NLS-1$
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

        final BmFormHelper fHelper = helper;
        final String fItemName = itemName;
        final String parentName = JsonUtils.extractStringArgument(params, "parentName"); //$NON-NLS-1$
        final String beforeName = JsonUtils.extractStringArgument(params, "beforeName"); //$NON-NLS-1$
        final boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        final String[] moved = { null };

        String result = fHelper.executeFormOperation(project, target, dryRun, (tx, form) -> {
            moved[0] = fHelper.moveItemToContainer(form, fItemName, parentName, beforeName);
            return moved[0];
        });

        if (result == null || result.startsWith("Error:")) //$NON-NLS-1$
        {
            return EditMetadataTool.formatFormResult(result, "move_item", target); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("operation", "move_item") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", target) //$NON-NLS-1$
            .put("name", fItemName) //$NON-NLS-1$
            .put("message", result) //$NON-NLS-1$
            .toJson();
    }

    /**
     * 1.40: universal {@code removeItem} - routes by container FQN shape
     * the same way {@link #opMoveItem(Map)} does. For metadata-objects
     * (Catalog/Document/etc.) it forwards to {@code removeObjectAttribute}
     * or {@code removeTabularSection} based on container shape.
     */
    String opRemoveItem(Map<String, String> params)
    {
        String containerFqn = JsonUtils.extractStringArgument(params, "containerFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (containerFqn == null || containerFqn.isEmpty() || itemName == null || itemName.isEmpty())
        {
            return ToolResult.error("removeItem requires containerFqn and name parameters.").toJson(); //$NON-NLS-1$
        }
        // Form scope - advisory router: form / DCS / typed-metadata contexts have
        // dedicated remove operations, so surface the right one instead of mutating
        // here (the previous formParams map was built and never forwarded - dead code).
        if (containerFqn.contains(".Forms.") || containerFqn.contains(".Form."))
        {
            return ToolResult.success()
                .put("message", "removeItem routed to form context - call edit_form operation=removeItem with formFqn="
                    + containerFqn + " name=" + itemName)
                .put("removeItemRouting", java.util.Collections.singletonMap("scope", "form"))
                .toJson();
        }
        // DCS scope
        if (containerFqn.contains(".Template") || containerFqn.contains(".DCS"))
        {
            return ToolResult.success()
                .put("message", "removeItem routed to DCS context - call dcs_workshop operation=remove_item")
                .put("removeItemRouting", java.util.Collections.singletonMap("scope", "dcs"))
                .toJson();
        }
        // Metadata-object scope - try to route to attribute or TS removal
        // by checking whether the parent owner has a TS with this name first.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("containerFqn", containerFqn);
        data.put("itemName", itemName);
        data.put("hint", "For attributes use removeObjectAttribute, for tabular sections use removeTabularSection.");
        return ToolResult.success()
            .put("message", "removeItem in metadata-object context - use the typed remove operation")
            .put("removeItemRouting", data)
            .toJson();
    }
    // -----------------------------------------------------------------------
    // 1.40: Extensions group (5 ops via BmExtensionHelper)
    // -----------------------------------------------------------------------

    /**
     * 1.40: dispatcher for the 5 Extensions ops (adoptObject, adoptObjects,
     * adoptChild, adoptFormItem, adoptModule). Probes the underlying adopt
     * service via {@link BmExtensionHelper}; surfaces a graceful
     * {@code adoptServiceNotFound} tag when the API is missing.
     */
    String opExtensionAdopt(String op, Map<String, String> params)
    {
        if (!BmExtensionHelper.isAvailable())
        {
            return ToolResult.error(BmExtensionHelper.deferredMessage(op))
                .put("operation", op)
                .put(ErrorTags.ADOPT_SERVICE_NOT_FOUND.wire(), true)
                .toJson();
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        // 1.42.4 BUG-T8: accept multiple FQN aliases. The unified
        // edit_metadata schema exposes `ownerFqn` for every operation, but
        // the legacy adopt API uses `targetFqn`. Without this fallback the
        // call fails with "requires projectName and targetFqn" even when
        // the caller followed the documented parameter pattern. Try each
        // alias in order: targetFqn (canonical), objectFqn (Catalog FQN-style),
        // ownerFqn (unified element-FQN).
        String targetFqn = JsonUtils.extractStringArgument(params, "targetFqn"); //$NON-NLS-1$
        if (targetFqn == null || targetFqn.isEmpty())
        {
            targetFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        }
        if (targetFqn == null || targetFqn.isEmpty())
        {
            targetFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        }
        String baseProject = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty()
            || targetFqn == null || targetFqn.isEmpty())
        {
            return ToolResult.error(op + " requires projectName and targetFqn " //$NON-NLS-1$
                + "(also accepts objectFqn or ownerFqn as aliases)").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        // adoptObjects accepts comma-separated FQN list; rest take a single FQN
        if ("adopt_objects".equals(op))
        {
            String[] fqns = targetFqn.split("\\s*,\\s*");
            java.util.List<Map<String, Object>> perResult = new java.util.ArrayList<>();
            for (String fqn : fqns)
            {
                if (fqn.isEmpty())
                {
                    continue;
                }
                BmExtensionHelper.BorrowResult br = BmExtensionHelper.attemptBorrow(
                    project, baseProject != null ? baseProject : "", fqn, null);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("targetFqn", fqn);
                entry.put("ok", br.ok);
                if (br.error != null)
                {
                    entry.put("error", br.error);
                }
                if (br.alreadyBorrowed)
                {
                    entry.put("alreadyBorrowed", true);
                }
                // 1.43.x: surface BorrowResult tags (partialBorrowDetected,
                // adoptInvocationFailed, ...) on each entry so callers can
                // tell stale state from genuine idempotent success.
                if (br.tags != null && !br.tags.isEmpty())
                {
                    for (Map.Entry<String, Object> tag : br.tags.entrySet())
                    {
                        entry.put(tag.getKey(), tag.getValue());
                    }
                }
                perResult.add(entry);
            }
            return ToolResult.success()
                .put("operation", op)
                .put("results", perResult)
                .put("totalCount", perResult.size())
                .toJson();
        }
        // Single-target ops
        String childKind = JsonUtils.extractStringArgument(params, "childKind"); //$NON-NLS-1$
        // 1.43.1: adopt_child / adopt_form_item compose the canonical
        // child-FQN from (ownerFqn, childKind, name) when the caller passes
        // them separately. Without this, BmExtensionHelper.resolveSourceEObject
        // would receive only the parent FQN and return the parent object
        // itself - the form / attribute / etc. would never get borrowed.
        // Callers that already pass a full child-FQN through targetFqn
        // (e.g. "Catalog.Users.Form.UserForm") keep working unchanged.
        if (("adopt_child".equals(op) || "adopt_form_item".equals(op)) //$NON-NLS-1$ //$NON-NLS-2$
            && countSegments(targetFqn) <= 2)
        {
            String childName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
            if (childName == null || childName.isEmpty())
            {
                childName = JsonUtils.extractStringArgument(params, "childName"); //$NON-NLS-1$
            }
            if ("adopt_form_item".equals(op) && (childKind == null || childKind.isEmpty())) //$NON-NLS-1$
            {
                childKind = "Form"; //$NON-NLS-1$
            }
            if (childKind == null || childKind.isEmpty() || childName == null || childName.isEmpty())
            {
                return ToolResult.error(op + " requires (ownerFqn|targetFqn) plus childKind and name. " //$NON-NLS-1$
                    + "Examples: ownerFqn=Catalog.Users childKind=Form name=UserForm, " //$NON-NLS-1$
                    + "or pass the full child-FQN directly via targetFqn=Catalog.Users.Form.UserForm.") //$NON-NLS-1$
                    .toJson();
            }
            targetFqn = targetFqn + "." + childKind + "." + childName; //$NON-NLS-1$ //$NON-NLS-2$
        }
        BmExtensionHelper.BorrowResult result = BmExtensionHelper.attemptBorrow(
            project, baseProject != null ? baseProject : "", targetFqn, childKind);
        if (result.ok)
        {
            ToolResult tr = ToolResult.success()
                .put("operation", op)
                .put("targetFqn", targetFqn);
            if (result.alreadyBorrowed)
            {
                tr.put("alreadyBorrowed", true);
            }
            // 1.43.x: surface BorrowResult tags (borrowed, alreadyBorrowed,
            // ...) on success so callers see the same diagnostics that
            // extension_workshop already returns.
            if (result.tags != null && !result.tags.isEmpty())
            {
                for (Map.Entry<String, Object> tag : result.tags.entrySet())
                {
                    tr.put(tag.getKey(), tag.getValue());
                }
            }
            return tr.toJson();
        }
        ToolResult err = ToolResult
            .error(op + " failed: " + (result.error != null ? result.error : "unknown"))
            .put("operation", op)
            .put("targetFqn", targetFqn);
        // 1.43.x: propagate tags on error too. The partialBorrowDetected
        // diagnostic (EDT said "already" but the target is not attached -
        // stale files on disk) lands here, and without this tag the AI
        // agent only sees an opaque error message.
        if (result.tags != null && !result.tags.isEmpty())
        {
            for (Map.Entry<String, Object> tag : result.tags.entrySet())
            {
                err.put(tag.getKey(), tag.getValue());
            }
        }
        return err.toJson();
    }

    private static int countSegments(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < fqn.length(); i++)
        {
            if (fqn.charAt(i) == '.')
            {
                n++;
            }
        }
        return n;
    }

    // -----------------------------------------------------------------------
    // 1.40: DCS group (27 ops) - delegated to DcsWorkshopTool
    // -----------------------------------------------------------------------

    /**
     * 1.40: routes DCS ops (camelCase) to the existing
     * {@link DcsWorkshopTool} (snake_case). Names are mapped via the
     * {@link #DCS_OP_ALIASES} table; unmapped names are passed through
     * unchanged.
     * <p>
     * 22 of 27 DCS ops are already implemented in DcsWorkshopTool spike;
     * the remaining 5 (addUserField, addSettingsTable, addSettingsChart,
     * removeConditionalAppearance, addSettingsFilterGroup) surface a graceful
     * deferred message until DcsWorkshopTool extension lands in 1.40.x.
     */
    String delegateToDcsWorkshop(String op, Map<String, String> params)
    {
        String snakeOp = DCS_OP_ALIASES.getOrDefault(op, op);
        Map<String, String> forwarded = new LinkedHashMap<>(params);
        forwarded.put("operation", snakeOp); //$NON-NLS-1$
        // DcsWorkshopTool reads "objectName" (Report.X / Catalog.Y), while the
        // edit_metadata DCS ops are advertised with "ownerFqn" - map it across so
        // the delegation does not fail with "projectName and objectName are
        // required". An explicit objectName from the caller wins.
        if (!forwarded.containsKey("objectName") && forwarded.containsKey("ownerFqn")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            forwarded.put("objectName", forwarded.get("ownerFqn")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        DcsWorkshopTool dcs = new DcsWorkshopTool();
        try
        {
            return dcs.execute(forwarded);
        }
        catch (Exception e)
        {
            Activator.logWarning("DCS delegation for " + op + " failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("DCS operation '" + op + "' failed: " + e.getMessage())
                .put("delegatedTo", "DcsWorkshopTool")
                .put("snakeOp", snakeOp)
                .toJson();
        }
    }

    /** Maps camelCase DCS op names to DcsWorkshopTool snake_case names. */
    private static final Map<String, String> DCS_OP_ALIASES = buildDcsAliases();

    private static Map<String, String> buildDcsAliases()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("create_report_schema", "create_schema");
        m.put("repair_report_schema", "repair_schema");
        m.put("add_data_set", "add_dataset");
        m.put("remove_data_set", "remove_dataset");
        m.put("add_data_set_field", "add_field");
        m.put("add_schema_parameter", "add_parameter");
        m.put("set_schema_parameter", "set_parameter");
        m.put("remove_schema_parameter", "remove_parameter");
        m.put("move_schema_parameter", "move_parameter");
        m.put("add_calculated_field", "add_calculated_field");
        m.put("add_total_field", "add_total");
        m.put("add_conditional_appearance", "add_appearance");
        m.put("add_settings_group", "add_grouping");
        m.put("add_settings_filter", "add_filter");
        // 1.41: 13 deferred ops landed natively in DcsWorkshopTool
        m.put("add_user_field", "add_user_field");
        m.put("add_settings_table", "add_settings_table");
        m.put("add_settings_chart", "add_settings_chart");
        // add_order: short-name alias, same DcsWorkshopTool op both sides
        m.put("add_order", "add_order");
        m.put("add_settings_order", "add_settings_order");
        m.put("add_settings_selected_field", "add_settings_selected_field");
        m.put("remove_settings_selected_field", "remove_settings_selected_field");
        m.put("add_settings_variant", "add_settings_variant");
        m.put("set_settings_parameter", "set_settings_parameter");
        m.put("remove_settings_item", "remove_settings_item");
        m.put("remove_conditional_appearance", "remove_conditional_appearance");
        m.put("set_data_set_field_appearance", "set_data_set_field_appearance");
        m.put("set_output_parameter", "set_output_parameter");
        m.put("add_settings_filter_group", "add_settings_filter_group");
        // 1.43.x DCS catch-up wave 2: 14 new ops (edit_metadata name -> DcsWorkshop internal name)
        m.put("add_data_set_link", "add_dataset_link");
        m.put("set_data_set_link_property", "set_dataset_link_property");
        m.put("remove_data_set_link", "remove_dataset_link");
        m.put("set_data_set_property", "set_dataset_property");
        // Row 35: replace the whole query of an existing Query dataset (validated)
        m.put("set_data_set_query", "set_dataset_query");
        m.put("remove_data_set_field", "remove_dataset_field");
        m.put("set_calculated_field", "set_calculated_field");
        m.put("remove_calculated_field", "remove_calculated_field");
        m.put("set_total_field", "set_total_field");
        m.put("remove_total_field", "remove_total_field");
        m.put("clear_settings_selected_fields", "clear_settings_selected_fields");
        m.put("remove_settings_filter", "remove_settings_filter");
        m.put("remove_settings_order", "remove_settings_order");
        m.put("set_settings_item_user_mode", "set_settings_item_user_mode");
        m.put("remove_settings_variant", "remove_settings_variant");
        // 1.43.x DCS batch 4a: clone an existing settings variant
        m.put("clone_settings_variant", "clone_settings_variant");
        // 1.43.x DCS batch 4b: heuristic query editing (edit_metadata name == internal name)
        m.put("add_query_field", "add_query_field");
        m.put("remove_query_field", "remove_query_field");
        m.put("add_query_condition", "add_query_condition");
        m.put("remove_query_condition", "remove_query_condition");
        return m;
    }

    /**
     * Wraps a {@link BmFormHelper#executeFormOperation} return value into our
     * standard JSON response shape. The helper returns {@code null} on
     * success, or an "Error: ..." string otherwise.
     */
}
