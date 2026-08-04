/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.BmSubsystemHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Content-collection operations extracted from {@link EditMetadataTool} as the sixth cluster of the
 * god-class split (Inc4). Three flavors of "content" collections, each mutated atomically inside a BM
 * transaction:
 * <ul>
 * <li><b>Wrapper-item content</b> ({@code ExchangePlan.content} of {@code ExchangePlanContentItem},
 * {@code CommonAttribute.content} of {@code CommonAttributeContentItem}) - each entry wraps the
 * referenced object plus per-item metadata ({@code autoRecord} / {@code use}). Built via
 * {@link BmObjectHelper#createMdClassEObject} and matched by reference identity.</li>
 * <li><b>Plain reference list</b> ({@code FunctionalOption.content} /
 * {@code FunctionalOptionsParameter.use}) - the referenced object / attribute is stored directly,
 * no wrapper.</li>
 * <li><b>Subsystem content</b> ({@code Subsystem.content}) - delegated to
 * {@link BmSubsystemHelper}.</li>
 * </ul>
 * The cluster ships its own reflection helpers ({@link #getContentList}, {@link #getRefListByGetter},
 * {@link #findContentItem}) plus the shared {@code addContentEntry} / {@code removeContentEntry}
 * cores, used nowhere else. The plain-list EMF mechanics ({@code addToRawList},
 * {@code assertAssignableToRef}, {@code listContainsResolved}, {@code removeResolved}) are shared with
 * the form-item functional-options cluster and the generic setter, so they live on
 * {@link EditMetadataTool} and are called statically from here.
 */
final class ContentOps
{
    /**
     * Adds (or updates) an ExchangePlanContentItem on an ExchangePlan. Optional
     * {@code autoRecord} (Deny/Allow, default Deny) sets per-item auto change
     * registration.
     */
    String opAddExchangePlanContent(Map<String, String> params)
    {
        String auto = JsonUtils.extractStringArgument(params, "autoRecord"); //$NON-NLS-1$
        if (auto == null || auto.isEmpty())
        {
            auto = "Deny"; //$NON-NLS-1$
        }
        else if (!"Deny".equals(auto) && !"Allow".equals(auto)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("autoRecord must be Deny or Allow (got '" + auto + "').").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return addContentEntry(params, "ExchangePlan", "ExchangePlanContentItem", //$NON-NLS-1$ //$NON-NLS-2$
            "getMdObject", "setMdObject", "autoRecord", auto, "add_exchange_plan_content"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** Removes an object from an ExchangePlan's content. */
    String opRemoveExchangePlanContent(Map<String, String> params)
    {
        return removeContentEntry(params, "ExchangePlan", "getMdObject", //$NON-NLS-1$ //$NON-NLS-2$
            "remove_exchange_plan_content"); //$NON-NLS-1$
    }

    /**
     * Adds (or updates) a CommonAttributeContentItem on a CommonAttribute.
     * Optional {@code use} (Auto/Use/DontUse, default Auto) sets per-object
     * usage of the common attribute.
     */
    String opAddCommonAttributeContent(Map<String, String> params)
    {
        String use = JsonUtils.extractStringArgument(params, "use"); //$NON-NLS-1$
        if (use == null || use.isEmpty())
        {
            use = "Auto"; //$NON-NLS-1$
        }
        else if (!"Auto".equals(use) && !"Use".equals(use) && !"DontUse".equals(use)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return ToolResult.error("use must be Auto, Use or DontUse (got '" + use + "').").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return addContentEntry(params, "CommonAttribute", "CommonAttributeContentItem", //$NON-NLS-1$ //$NON-NLS-2$
            "getMetadata", "setMetadata", "use", use, "add_common_attribute_content"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** Removes an object from a CommonAttribute's content. */
    String opRemoveCommonAttributeContent(Map<String, String> params)
    {
        return removeContentEntry(params, "CommonAttribute", "getMetadata", //$NON-NLS-1$ //$NON-NLS-2$
            "remove_common_attribute_content"); //$NON-NLS-1$
    }

    /**
     * J2 (Functional Options): adds or removes an object/attribute reference in
     * a FunctionalOption's {@code content} collection - the metadata whose
     * availability the option controls. The target may be a whole top-level
     * object ({@code Catalog.X}) or a child element ({@code Document.X.Attribute.Y},
     * {@code InformationRegister.X.Resource.Z}, ...), resolved via
     * {@link EditMetadataTool#resolveReferenceTarget}. Idempotent.
     *
     * @param add true to add the reference, false to remove it
     */
    String opFunctionalOptionContent(Map<String, String> params, boolean add)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        final String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String valueFqn = JsonUtils.extractStringArgument(params, "valueFqn"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(valueFqn, "valueFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        final String opLabel = add ? "add_functional_option_content" //$NON-NLS-1$
            : "remove_functional_option_content"; //$NON-NLS-1$
        final String fValueFqn = valueFqn;
        final String normValueFqn = MetadataTypeCatalog.normalizeFqn(valueFqn);
        final boolean[] idempotentSkip = { false };

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                // FunctionalOption.content (the metadata whose availability the option
                // controls) OR FunctionalOptionsParameter.use (the objects a parameterized
                // option draws its parameter values from). Same reference-list mechanics,
                // different EReference. Both are EList<MdObject> mutated via the live list.
                String ownerType = owner.eClass().getName();
                String getterName;
                String featureName;
                if ("FunctionalOption".equals(ownerType)) //$NON-NLS-1$
                {
                    getterName = "getContent"; //$NON-NLS-1$
                    featureName = "content"; //$NON-NLS-1$
                }
                else if ("FunctionalOptionsParameter".equals(ownerType)) //$NON-NLS-1$
                {
                    getterName = "getUse"; //$NON-NLS-1$
                    featureName = "use"; //$NON-NLS-1$
                }
                else
                {
                    throw new RuntimeException(opLabel + " applies to a FunctionalOption (content) " //$NON-NLS-1$
                        + "or a FunctionalOptionsParameter (use), not " + ownerType //$NON-NLS-1$
                        + " (ownerFqn=" + ownerFqn + ")."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                @SuppressWarnings("rawtypes")
                EList content = getRefListByGetter(owner, getterName);
                if (content == null)
                {
                    throw new RuntimeException(ownerType + " '" + ownerFqn //$NON-NLS-1$
                        + "' has no " + featureName + " collection (" + getterName + "())."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                Object value = EditMetadataTool.resolveReferenceTarget(tx, normValueFqn);
                if (!(value instanceof EObject))
                {
                    throw new RuntimeException(featureName + " target not found: " + normValueFqn //$NON-NLS-1$
                        + " (valueFqn=" + fValueFqn + "). Expected a top-level object FQN " //$NON-NLS-1$ //$NON-NLS-2$
                        + "(e.g. Catalog.X) or a child attribute FQN (e.g. Document.X.Attribute.Y)."); //$NON-NLS-1$
                }
                EditMetadataTool.assertAssignableToRef(owner, featureName, value, normValueFqn);
                boolean present = EditMetadataTool.listContainsResolved(content, value);
                if (add)
                {
                    if (present)
                    {
                        idempotentSkip[0] = true;
                        return fValueFqn + " (already in " + featureName + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    EditMetadataTool.addToRawList(content, (EObject) value);
                    return fValueFqn + " (added)"; //$NON-NLS-1$
                }
                if (!present)
                {
                    idempotentSkip[0] = true;
                    return fValueFqn + " (not in " + featureName + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                EditMetadataTool.removeResolved(content, value);
                return fValueFqn + " (removed)"; //$NON-NLS-1$
            });

        if (idempotentSkip[0] && r.error == null)
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("valueFqn", valueFqn); //$NON-NLS-1$
            idem.put("action", add ? "already-present" : "not-present"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            r.tags.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, opLabel);
    }

    String opAddSubsystemContent(Map<String, String> params)
    {
        return opSubsystemContent(params, true, "add_subsystem_content"); //$NON-NLS-1$
    }

    String opRemoveSubsystemContent(Map<String, String> params)
    {
        return opSubsystemContent(params, false, "remove_subsystem_content"); //$NON-NLS-1$
    }

    /**
     * 1.40: addSubsystemContent / removeSubsystemContent via {@link BmSubsystemHelper}.
     * Resolves the target object by FQN through the configuration and mutates
     * the subsystem's content EList atomically inside a BM transaction.
     */
    String opSubsystemContent(Map<String, String> params, boolean add, String opName)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String contentFqn = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (contentFqn == null || contentFqn.isEmpty())
        {
            contentFqn = JsonUtils.extractStringArgument(params, "targetFqn"); //$NON-NLS-1$
        }
        if (contentFqn == null || contentFqn.isEmpty())
        {
            // valueFqn alias mirrors add_object_reference, so a caller building a
            // subsystem composition by analogy does not have to learn a new param name.
            contentFqn = JsonUtils.extractStringArgument(params, "valueFqn"); //$NON-NLS-1$
        }
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (contentFqn == null || contentFqn.isEmpty())
        {
            return ToolResult.error(opName + " requires 'name' (or 'targetFqn' / 'valueFqn' alias) parameter").toJson(); //$NON-NLS-1$
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson(); //$NON-NLS-1$
        }
        final String resolvedContentFqn = contentFqn;
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, subsystem) -> {
                if (add)
                {
                    Configuration config = Activator.getDefault().getConfigurationProvider()
                        .getConfiguration(project);
                    MdObject target = BmSubsystemHelper.resolveByFqn(config, resolvedContentFqn);
                    if (target == null)
                    {
                        throw BmSubsystemHelper.targetNotFound(resolvedContentFqn);
                    }
                    boolean added = BmSubsystemHelper.addContent(subsystem, target);
                    return added
                        ? "added " + resolvedContentFqn
                        : resolvedContentFqn + " (already in subsystem - idempotent skip)";
                }
                boolean removed = BmSubsystemHelper.removeContent(subsystem, resolvedContentFqn);
                if (!removed)
                {
                    throw BmObjectHelper.notFound(resolvedContentFqn, ownerFqn, "content");
                }
                return "removed " + resolvedContentFqn;
            });
        return EditMetadataTool.formatResult(r, opName);
    }

    // ---- Cluster-local helpers --------------------------------------------

    private String addContentEntry(Map<String, String> params, String expectedOwner, String itemType,
        String refGetter, String refSetter, String enumProp, String enumValue, String opLabel)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        final String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String valueFqn = JsonUtils.extractStringArgument(params, "valueFqn"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(valueFqn, "valueFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        final String fValueFqn = valueFqn;
        final String normValueFqn = MetadataTypeCatalog.normalizeFqn(valueFqn);
        final boolean[] idempotentUpdate = { false };

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                if (!expectedOwner.equals(owner.eClass().getName()))
                {
                    throw new RuntimeException(opLabel + " applies to " + expectedOwner //$NON-NLS-1$
                        + ", not " + owner.eClass().getName() + " (ownerFqn=" + ownerFqn + ")."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                @SuppressWarnings("rawtypes")
                EList content = getContentList(owner);
                if (content == null)
                {
                    throw new RuntimeException("Owner '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no content collection (expected getContent())."); //$NON-NLS-1$
                }
                IBmObject value = tx.getTopObjectByFqn(normValueFqn);
                if (value == null)
                {
                    throw new RuntimeException("Referenced object not found: " + normValueFqn //$NON-NLS-1$
                        + " (valueFqn=" + fValueFqn + "). Content targets must be top-level objects."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                EObject item = findContentItem(content, refGetter, value);
                if (item == null)
                {
                    Object created = BmObjectHelper.createMdClassEObject(itemType);
                    if (!(created instanceof EObject))
                    {
                        throw new RuntimeException("Could not create " + itemType //$NON-NLS-1$
                            + " (MdClassFactory.create" + itemType //$NON-NLS-1$
                            + " unavailable on this EDT runtime)."); //$NON-NLS-1$
                    }
                    item = (EObject) created;
                    java.lang.reflect.Method setter = EditMetadataTool.findSingleArgSetter(item.getClass(), refSetter);
                    if (setter == null)
                    {
                        throw new RuntimeException(itemType + " has no " + refSetter + "(<object>)."); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (!setter.getParameterTypes()[0].isInstance(value))
                    {
                        String vt = (value instanceof EObject)
                            ? ((EObject) value).eClass().getName() : value.getClass().getSimpleName();
                        throw new RuntimeException("Referenced object " + normValueFqn + " (type " + vt //$NON-NLS-1$ //$NON-NLS-2$
                            + ") is not assignable to " + refSetter + " (expects " //$NON-NLS-1$ //$NON-NLS-2$
                            + setter.getParameterTypes()[0].getSimpleName() + ")."); //$NON-NLS-1$
                    }
                    EditMetadataTool.invokeSetterClearly(setter, item, value, refGetter);
                    EditMetadataTool.addToRawList(content, item);
                }
                else
                {
                    idempotentUpdate[0] = true;
                }
                if (enumProp != null && enumValue != null && !enumValue.isEmpty())
                {
                    String setErr = BmObjectHelper.setProperty(item, enumProp, enumValue);
                    if (setErr != null)
                    {
                        throw new RuntimeException("Entry " //$NON-NLS-1$
                            + (idempotentUpdate[0] ? "updated" : "created") //$NON-NLS-1$ //$NON-NLS-2$
                            + " but " + enumProp + " not set: " + setErr); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                return fValueFqn + (idempotentUpdate[0] ? " (updated)" : " (added)"); //$NON-NLS-1$ //$NON-NLS-2$
            });

        if (idempotentUpdate[0] && r.error == null)
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("valueFqn", valueFqn); //$NON-NLS-1$
            idem.put("action", "updated-existing-entry"); //$NON-NLS-1$ //$NON-NLS-2$
            r.tags.put("idempotentUpdate", idem); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, opLabel);
    }

    /**
     * Removes a content-item wrapper (matched by its referenced object) from a
     * metadata object's {@code getContent()} collection. Idempotent: a missing
     * entry yields a success response with an {@code idempotentSkip} tag.
     */
    private String removeContentEntry(Map<String, String> params, String expectedOwner,
        String refGetter, String opLabel)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        final String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String valueFqn = JsonUtils.extractStringArgument(params, "valueFqn"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(valueFqn, "valueFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        final String fValueFqn = valueFqn;
        final String normValueFqn = MetadataTypeCatalog.normalizeFqn(valueFqn);
        final boolean[] notPresent = { false };

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                if (!expectedOwner.equals(owner.eClass().getName()))
                {
                    throw new RuntimeException(opLabel + " applies to " + expectedOwner //$NON-NLS-1$
                        + ", not " + owner.eClass().getName() + " (ownerFqn=" + ownerFqn + ")."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                @SuppressWarnings("rawtypes")
                EList content = getContentList(owner);
                if (content == null)
                {
                    throw new RuntimeException("Owner '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no content collection (expected getContent())."); //$NON-NLS-1$
                }
                IBmObject value = tx.getTopObjectByFqn(normValueFqn);
                if (value == null)
                {
                    // A missing target is a user error (typo in valueFqn), not an
                    // idempotent no-op - surface it instead of a silent success.
                    throw new RuntimeException("Object not found in model: " + normValueFqn //$NON-NLS-1$
                        + " (valueFqn=" + fValueFqn + "). Cannot remove a non-existent object " //$NON-NLS-1$ //$NON-NLS-2$
                        + "from content - check the FQN."); //$NON-NLS-1$
                }
                EObject item = findContentItem(content, refGetter, value);
                if (item == null)
                {
                    notPresent[0] = true;
                    return fValueFqn + " (not in content)"; //$NON-NLS-1$
                }
                content.remove(item);
                return fValueFqn + " (removed)"; //$NON-NLS-1$
            });

        if (notPresent[0] && r.error == null)
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("valueFqn", valueFqn); //$NON-NLS-1$
            idem.put("action", "not-present"); //$NON-NLS-1$ //$NON-NLS-2$
            r.tags.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, opLabel);
    }

    /**
     * Reads an object's {@code getContent()} collection reflectively, returning
     * the raw {@link EList} or {@code null} when the object has no such getter.
     */
    @SuppressWarnings("rawtypes")
    private static EList getContentList(EObject owner)
    {
        try
        {
            Object res = owner.getClass().getMethod("getContent").invoke(owner); //$NON-NLS-1$
            if (res instanceof EList)
            {
                return (EList) res;
            }
        }
        catch (ReflectiveOperationException e)
        {
            // no getContent() / inaccessible -> caller reports a clear error.
            // Log the cause so an access failure is not silently mistaken for a
            // genuinely missing collection.
            Activator.logWarning("getContent() reflection failed for " //$NON-NLS-1$
                + owner.eClass().getName() + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Reflectively reads a no-arg {@code EList} getter (e.g. {@code getContent} on a
     * FunctionalOption, {@code getUse} on a FunctionalOptionsParameter), returning the
     * raw list or {@code null} when the getter is absent / inaccessible. Generic version
     * of {@link #getContentList} used where the collection getter varies by owner type.
     */
    @SuppressWarnings("rawtypes")
    private static EList getRefListByGetter(EObject owner, String getterName)
    {
        try
        {
            Object res = owner.getClass().getMethod(getterName).invoke(owner);
            if (res instanceof EList)
            {
                return (EList) res;
            }
        }
        catch (ReflectiveOperationException e)
        {
            Activator.logWarning(getterName + "() reflection failed for " //$NON-NLS-1$
                + owner.eClass().getName() + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Finds the content-item wrapper in {@code content} whose {@code refGetter}
     * resolves (by identity within the transaction) to {@code value}. Mirrors
     * the {@code current == value} idempotency check used by
     * {@code opSetObjectReference}.
     */
    @SuppressWarnings("rawtypes")
    private static EObject findContentItem(EList content, String refGetter, Object value)
    {
        for (Object o : content)
        {
            if (!(o instanceof EObject))
            {
                continue;
            }
            EObject item = (EObject) o;
            try
            {
                Object ref = item.getClass().getMethod(refGetter).invoke(item);
                // A still-unresolved cross-reference proxy never == the resolved
                // tx object; resolve it (against the resolved value's resource
                // set) so idempotency holds and we do not add a duplicate entry.
                if (ref instanceof EObject && ((EObject) ref).eIsProxy() && value instanceof EObject)
                {
                    ref = org.eclipse.emf.ecore.util.EcoreUtil.resolve((EObject) ref, (EObject) value);
                }
                if (ref == value)
                {
                    return item;
                }
            }
            catch (ReflectiveOperationException ignore)
            {
                // wrapper without the expected getter -> skip
            }
        }
        return null;
    }
}
