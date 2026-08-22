/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.runtime.Platform;

import ru.aiedt.mcp.server.support.BmSupportRegistryHelper;
import ru.aiedt.mcp.server.support.SupportSnapshot;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Reads what a configuration on vendor support is allowed to have done to it.
 * <p>
 * Four of the five operations only read. {@code status} says which vendor configurations this one
 * descends from and how its objects are distributed across support modes; {@code list_objects}
 * names the objects in a given mode; {@code object_mode} answers for one object, including the
 * objects the environment would require to change alongside it; {@code snapshot_modes} writes the
 * whole per-object map to a file. {@code restore_modes} is the one that changes the configuration.
 * </p>
 * <p>
 * <b>A mode is a declared setting, not a measurement.</b> {@code CHANGES_ALLOWED} records that the
 * setting was changed for the object, not that the object itself was modified;
 * {@code CHANGES_NOT_ALLOWED} carries no statement about whether it was modified anyway. Whether an
 * object differs from the vendor's copy is reported by {@code compare_three_way}. The two settings
 * disagreeing has a definite consequence: an object modified while still set to
 * {@code CHANGES_NOT_ALLOWED} is overwritten by the next vendor update.
 * </p>
 * <p>
 * <b>One operation writes, and only when asked twice.</b> {@code restore_modes} puts modes back the
 * way a snapshot recorded them, and reports what it would do unless {@code apply=true}. It exists
 * because a merge takes the support model from the delivery and no merge rule prevents that -
 * measured on a stand - so the modes a person set are lost by an ordinary update. A snapshot taken
 * beforehand is the only thing that makes that reversible, which is why {@code compare_three_way}
 * writes one into the project before every merge.
 * </p>
 */
public class SupportRegistryTool
    implements IMcpTool
{
    public static final String NAME = "support_registry"; //$NON-NLS-1$

    /**
     * Bundle that carries the support subsystem.
     * <p>
     * Named as a string and checked before anything else. The packages this tool's helper imports
     * are optional, so on an EDT without that bundle the helper class does not load at all - and
     * the failure arrives as a class-loading error, which reads like a broken plugin rather than
     * like a missing feature. Checking here, in a class that imports none of it, turns that into a
     * sentence saying what is absent.
     * </p>
     */
    private static final String DISTRIBUTION_BUNDLE = "com.e1c.g5.v8.dt.distribution"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Reads the vendor support state of a configuration: which vendor configurations " //$NON-NLS-1$
            + "it descends from, their releases, the object count per support mode, the " //$NON-NLS-1$
            + "rules the environment applies on the next update, and the mode of any single " //$NON-NLS-1$
            + "object together with the objects the environment requires to change with it. " //$NON-NLS-1$
            + "Operations: " //$NON-NLS-1$
            + "status, list_objects, object_mode, snapshot_modes, restore_modes, help. A mode " //$NON-NLS-1$
            + "records what was declared for an object, not whether the object was modified - " //$NON-NLS-1$
            + "use compare_three_way for that. snapshot_modes writes down which object held " //$NON-NLS-1$
            + "which mode, which is what makes a merge reversible: an update takes the support " //$NON-NLS-1$
            + "model from the delivery and no merge rule prevents it. restore_modes puts them " //$NON-NLS-1$
            + "back, and is the only operation here that changes anything - it reports what it " //$NON-NLS-1$
            + "would do unless apply=true."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "status / list_objects / object_mode / snapshot_modes / restore_modes / help. " //$NON-NLS-1$
                    + "Pass operation=help without other parameters for the catalog.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project to read. Required for status, list_objects and object_mode.") //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "object_mode: the object to report on, named as elsewhere in this server " //$NON-NLS-1$
                    + "(Catalog.Name, Document.Name). Pass Configuration for the configuration " //$NON-NLS-1$
                    + "root, which must be set to CHANGES_ALLOWED before any other object " //$NON-NLS-1$
                    + "can be.") //$NON-NLS-1$
            .stringProperty("userMode", //$NON-NLS-1$
                "list_objects: report only objects in this mode - CHANGES_NOT_ALLOWED, " //$NON-NLS-1$
                    + "CHANGES_ALLOWED or CANCELLED. Omit for every mode.") //$NON-NLS-1$
            .stringProperty("parentId", //$NON-NLS-1$
                "list_objects: which vendor configuration the vendor modes come from, by id or " //$NON-NLS-1$
                    + "by name. Required when the configuration descends from more than one, " //$NON-NLS-1$
                    + "because a mode belongs to a vendor rather than to the object outright.") //$NON-NLS-1$
            .integerProperty("offset", //$NON-NLS-1$
                "list_objects: how many matching objects to skip. Default 0.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "list_objects: how many to return. Default and maximum 500.") //$NON-NLS-1$
            .stringProperty("snapshotPath", //$NON-NLS-1$
                "snapshot_modes: absolute path to write the snapshot to. restore_modes: the " //$NON-NLS-1$
                    + "snapshot to restore from - required. compare_three_way writes one into " //$NON-NLS-1$
                    + "the project's .settings before every merge and names it in its answer.") //$NON-NLS-1$
            .booleanProperty("apply", //$NON-NLS-1$
                "restore_modes: write the recorded modes back. Default false, which reports " //$NON-NLS-1$
                    + "what would be written and changes nothing.") //$NON-NLS-1$
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
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isBlank())
        {
            return ToolResult.error("operation is required. Allowed: status / list_objects / " //$NON-NLS-1$
                + "object_mode / help.").toJson(); //$NON-NLS-1$
        }
        operation = JsonUtils.normalizeOperationToken(operation);
        if ("help".equals(operation)) //$NON-NLS-1$
        {
            return buildHelp(JsonUtils.extractStringArgument(params, "topic")); //$NON-NLS-1$
        }
        if (!OPS.containsKey(operation))
        {
            return ToolResult.error("Unknown operation '" + operation + "'. Allowed: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(" / ", OPS.keySet()) + " / help.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String absent = supportSubsystemAbsent();
        if (absent != null)
        {
            return ToolResult.error(absent).put("operation", operation).toJson(); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isBlank())
        {
            return ToolResult.error("projectName is required for " + operation + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            switch (operation)
            {
            case "status": //$NON-NLS-1$
                return status(projectName);
            case "list_objects": //$NON-NLS-1$
                return listObjects(projectName, params);
            case "object_mode": //$NON-NLS-1$
                return objectMode(projectName, params);
            case "snapshot_modes": //$NON-NLS-1$
                return snapshotModes(projectName, params);
            case "restore_modes": //$NON-NLS-1$
                return restoreModes(projectName, params);
            default:
                return ToolResult.error("Unhandled operation: " + operation).toJson(); //$NON-NLS-1$
            }
        }
        catch (LinkageError missing)
        {
            // The bundle answered Platform.getBundle but something it needs did not resolve. Named
            // rather than left as a stack trace: the difference between "this EDT cannot do it" and
            // "this plugin is broken" is the whole of what the caller needs to know.
            return ToolResult
                .error("the support subsystem is present but did not load: " + missing) //$NON-NLS-1$
                .put("operation", operation) //$NON-NLS-1$
                .toJson();
        }
    }

    /**
     * Says whether the support subsystem is missing from this EDT.
     *
     * @return the reason it cannot be used, or <code>null</code> when it can
     */
    private static String supportSubsystemAbsent()
    {
        try
        {
            return Platform.getBundle(DISTRIBUTION_BUNDLE) == null
                ? "this EDT install carries no support subsystem (" + DISTRIBUTION_BUNDLE //$NON-NLS-1$
                    + " is not installed), so no configuration here has a vendor support state" //$NON-NLS-1$
                : null;
        }
        catch (RuntimeException | LinkageError noPlatform)
        {
            return "the bundle registry could not be asked about the support subsystem: " //$NON-NLS-1$
                + noPlatform;
        }
    }

    /**
     * Reports the support state of a project.
     *
     * @param projectName the project.
     * @return the answer as JSON
     */
    private static String status(String projectName)
    {
        ru.aiedt.mcp.server.support.BmSupportRegistryHelper.Registry registry =
            ru.aiedt.mcp.server.support.BmSupportRegistryHelper.read(projectName);
        if (registry.cannotTell != null)
        {
            return ToolResult.error(registry.cannotTell).toJson();
        }
        return ToolResult.success()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("onSupport", registry.onSupport) //$NON-NLS-1$
            .put("updateAvailable", registry.updateAvailable) //$NON-NLS-1$
            .put("fileState", registry.fileState) //$NON-NLS-1$
            .put("version", registry.version) //$NON-NLS-1$
            .put("parents", registry.parents) //$NON-NLS-1$
            // Which route reached the service. A silent switch between routes would look like a
            // change in the answers rather than a change in how they were obtained.
            .put("serviceRoute", registry.serviceRoute) //$NON-NLS-1$
            .put("note", "a mode records what was declared for an object, not whether the " //$NON-NLS-1$ //$NON-NLS-2$
                + "object was modified; compare_three_way answers the second question") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Lists objects by declared support mode.
     *
     * @param projectName the project.
     * @param params the call parameters.
     * @return the answer as JSON
     */
    private static String listObjects(String projectName, Map<String, String> params)
    {
        String userMode = JsonUtils.extractStringArgument(params, "userMode"); //$NON-NLS-1$
        String parentId = JsonUtils.extractStringArgument(params, "parentId"); //$NON-NLS-1$
        int offset = JsonUtils.extractIntArgument(params, "offset", 0); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", 0); //$NON-NLS-1$
        ru.aiedt.mcp.server.support.BmSupportRegistryHelper.Listing listing =
            ru.aiedt.mcp.server.support.BmSupportRegistryHelper.listObjects(projectName, userMode,
                parentId, offset, limit);
        if (listing.cannotTell != null)
        {
            return ToolResult.error(listing.cannotTell).toJson();
        }
        return ToolResult.success()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("parentId", listing.parentId) //$NON-NLS-1$
            .put("parentName", listing.parentName) //$NON-NLS-1$
            .put("objects", listing.entries) //$NON-NLS-1$
            .put("matched", listing.matched) //$NON-NLS-1$
            .put("offset", listing.offset) //$NON-NLS-1$
            // Said outright rather than inferred from a short list, which is also what the last
            // page looks like.
            .put("more", listing.more) //$NON-NLS-1$
            .put("unnamed", listing.unnamed) //$NON-NLS-1$
            .put("serviceRoute", listing.serviceRoute) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Reports the support state of one object.
     *
     * @param projectName the project.
     * @param params the call parameters.
     * @return the answer as JSON
     */
    private static String objectMode(String projectName, Map<String, String> params)
    {
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (objectFqn == null || objectFqn.isBlank())
        {
            return ToolResult.error("objectFqn is required for object_mode.").toJson(); //$NON-NLS-1$
        }
        ru.aiedt.mcp.server.support.BmSupportRegistryHelper.ObjectState state =
            ru.aiedt.mcp.server.support.BmSupportRegistryHelper.objectMode(projectName, objectFqn);
        if (state.cannotTell != null)
        {
            return ToolResult.error(state.cannotTell).toJson();
        }
        return ToolResult.success()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("object", state.object) //$NON-NLS-1$
            // The environment's own answer, which folds every vendor into one value. Kept, and
            // labelled, so a caller can see it is a fold rather than an answer about one vendor.
            .put("userModeAggregated", state.userMode) //$NON-NLS-1$
            .put("perParent", state.perParent) //$NON-NLS-1$
            .put("canEdit", state.canEdit) //$NON-NLS-1$
            .put("canDelete", state.canDelete) //$NON-NLS-1$
            .put("dependents", state.dependents) //$NON-NLS-1$
            .put("serviceRoute", state.serviceRoute) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Writes down which object holds which support mode.
     *
     * @param projectName the project to read.
     * @param params the call, which must name where to write.
     * @return the answer as JSON
     */
    private static String snapshotModes(String projectName, Map<String, String> params)
    {
        String where = JsonUtils.extractStringArgument(params, "snapshotPath"); //$NON-NLS-1$
        if (where == null || where.isBlank())
        {
            return ToolResult.error("snapshotPath is required: a snapshot that is not written " //$NON-NLS-1$
                + "down dies with the call, and the modes it recorded with it.").toJson(); //$NON-NLS-1$
        }
        SupportSnapshot snapshot = BmSupportRegistryHelper.snapshot(projectName);
        if (snapshot.cannotTell != null)
        {
            return ToolResult.error(snapshot.cannotTell).toJson();
        }
        try
        {
            Path path = Paths.get(where.trim());
            snapshot.write(path);
            return ToolResult.success()
                .put("projectName", projectName) //$NON-NLS-1$
                .put("snapshotPath", path.toString()) //$NON-NLS-1$
                .put("entries", snapshot.entries()) //$NON-NLS-1$
                .put("vendorConfigurations", snapshot.parents.size()) //$NON-NLS-1$
                // Subordinate entities carry their own support records and are not indexed by
                // name, so they are counted here rather than dropped: a restore cannot set a mode
                // on an object it cannot find, and a snapshot that hid that would look complete.
                .put("notInTheConfiguration", snapshot.unresolved) //$NON-NLS-1$
                .toJson();
        }
        catch (IOException | RuntimeException cannotWrite)
        {
            return ToolResult.error("the snapshot could not be written to " + where + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + cannotWrite).toJson();
        }
    }

    /**
     * Puts support modes back the way a snapshot recorded them.
     *
     * @param projectName the project to write to.
     * @param params the call, which must name the snapshot.
     * @return the answer as JSON
     */
    private static String restoreModes(String projectName, Map<String, String> params)
    {
        String where = JsonUtils.extractStringArgument(params, "snapshotPath"); //$NON-NLS-1$
        if (where == null || where.isBlank())
        {
            return ToolResult.error("snapshotPath is required: restoring means putting back what " //$NON-NLS-1$
                + "a snapshot recorded, so there is nothing to do without one.").toJson(); //$NON-NLS-1$
        }
        boolean apply = JsonUtils.extractBooleanArgument(params, "apply", false); //$NON-NLS-1$
        if (apply)
        {
            // The preset gate works by tool name, and this tool sits in a reading group that
            // Read-only deliberately leaves on - an auditor under that preset still wants to read
            // support state. So the writing ARGUMENT is gated on a canonical writer instead.
            // Without this, "Read-only changes nothing" would stop being true through a flag.
            String forbidden = ToolGate.gateIfPresetDisabled("write_module_source"); //$NON-NLS-1$
            if (forbidden != null)
            {
                return ToolResult.error("apply=true writes the support model of the project, and " //$NON-NLS-1$
                    + "the active preset does not allow writing. " + forbidden).toJson(); //$NON-NLS-1$
            }
        }
        SupportSnapshot before;
        try
        {
            before = SupportSnapshot.read(Paths.get(where.trim()));
        }
        catch (IOException | RuntimeException cannotRead)
        {
            return ToolResult.error("the snapshot could not be read from " + where + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + cannotRead).toJson();
        }
        if (before.cannotTell != null)
        {
            return ToolResult.error(before.cannotTell).toJson();
        }
        BmSupportRegistryHelper.Restore restore =
            BmSupportRegistryHelper.restore(projectName, before, apply);
        if (restore.cannotTell != null)
        {
            return ToolResult.error(restore.cannotTell).toJson();
        }
        ToolResult result = ToolResult.success()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("snapshotPath", where.trim()) //$NON-NLS-1$
            .put("applied", restore.applied) //$NON-NLS-1$
            .put("restored", restore.restored) //$NON-NLS-1$
            .put("refused", restore.refused) //$NON-NLS-1$
            .put("notInTheConfiguration", restore.missing) //$NON-NLS-1$
            .put("writeRoute", restore.writeRoute) //$NON-NLS-1$
            // A restore is itself a write. This is where the modes it replaced were recorded, so
            // a restore that turns out to have been the wrong one has somewhere to go back to.
            .put("undoSnapshotFile", restore.undoSnapshotFile); //$NON-NLS-1$
        if (restore.drift != null)
        {
            // Three groups, because only one of them is damage. Objects that survived and lost
            // their mode are work no longer marked as ours; objects the delivery brought take the
            // default from the update rules; objects the update removed can take no mode at all.
            result.put("changed", restore.drift.changed) //$NON-NLS-1$
                .put("changedCount", restore.drift.changed.size()) //$NON-NLS-1$
                .put("arrived", restore.drift.arrived) //$NON-NLS-1$
                .put("gone", restore.drift.gone) //$NON-NLS-1$
                .put("vendorConfigurationsMatched", restore.drift.parentsMatched) //$NON-NLS-1$
                .put("vendorConfigurationsGone", restore.drift.parentsGone) //$NON-NLS-1$
                .put("vendorConfigurationsNew", restore.drift.parentsNew); //$NON-NLS-1$
        }
        // Follows what happened, not what was asked for. It used to key on the apply ARGUMENT, so
        // apply=true answered "the support model was written" while restored stood at 0 and the
        // undo file had never been created - the counters said one thing and the sentence beside
        // them said another.
        String note;
        if (!apply)
        {
            note = "nothing was written - pass apply=true to put these modes back"; //$NON-NLS-1$
        }
        else if (restore.restored > 0)
        {
            note = "the support model was written: " + restore.restored + " mode(s) put back"; //$NON-NLS-1$
        }
        else
        {
            note = "nothing was written - no mode needed putting back"; //$NON-NLS-1$
        }
        return result.put("note", note).toJson(); //$NON-NLS-1$
    }

    private static String buildHelp(String topic)
    {
        topic = JsonUtils.normalizeOperationToken(topic);
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# support_registry - operations\n\n"); //$NON-NLS-1$
            sb.append("- **status** - vendor configurations this one descends from, their " //$NON-NLS-1$
                + "releases, the object count per mode, and the rules applied on the next " //$NON-NLS-1$
                + "update.\n"); //$NON-NLS-1$
            sb.append("- **list_objects** - objects in a given mode, paged.\n"); //$NON-NLS-1$
            sb.append("- **object_mode** - one object: its mode per vendor, whether the " //$NON-NLS-1$
                + "environment lets it be edited or deleted, and which objects it requires " //$NON-NLS-1$
                + "to change with it.\n"); //$NON-NLS-1$
            sb.append("- **snapshot_modes** - writes every object and its mode to a file. " //$NON-NLS-1$
                + "Take one before any update: a merge takes the support model from the " //$NON-NLS-1$
                + "delivery, and this file is the only route back.\n"); //$NON-NLS-1$
            sb.append("- **restore_modes** - puts the recorded modes back. Reports what it " //$NON-NLS-1$
                + "would do unless apply=true. THIS ONE WRITES.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=modes for what the modes mean, " //$NON-NLS-1$
                + "topic=workflow for the operation picker.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("modes".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# support_registry - what the modes mean\n\n"); //$NON-NLS-1$
            sb.append("| Mode | Editing | Vendor updates |\n"); //$NON-NLS-1$
            sb.append("|------|---------|----------------|\n"); //$NON-NLS-1$
            sb.append("| CHANGES_NOT_ALLOWED | no | yes |\n"); //$NON-NLS-1$
            sb.append("| CHANGES_ALLOWED | yes | yes, and they can conflict |\n"); //$NON-NLS-1$
            sb.append("| CANCELLED | yes | not applied to this object |\n\n"); //$NON-NLS-1$
            sb.append("The configuration root has a mode of its own, and it must be set to " //$NON-NLS-1$
                + "CHANGES_ALLOWED before any other object can be. Request it as " //$NON-NLS-1$
                + "objectFqn=Configuration.\n\n"); //$NON-NLS-1$
            sb.append("A mode is a declared setting. Whether an object differs from the " //$NON-NLS-1$
                + "vendor's copy is a separate fact, reported by compare_three_way. When the " //$NON-NLS-1$
                + "two disagree - an object modified while still set to " //$NON-NLS-1$
                + "CHANGES_NOT_ALLOWED - the next vendor update overwrites that " //$NON-NLS-1$
                + "modification.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# support_registry - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| Is this configuration on support, and from which vendor | status |\n"); //$NON-NLS-1$
            sb.append("| How many objects are set to CHANGES_ALLOWED | status |\n"); //$NON-NLS-1$
            sb.append("| Which objects those are | list_objects " //$NON-NLS-1$
                + "(userMode=CHANGES_ALLOWED) |\n"); //$NON-NLS-1$
            sb.append("| Why this object cannot be edited | object_mode |\n"); //$NON-NLS-1$
            sb.append("| Which objects change with it | object_mode, field dependents |\n"); //$NON-NLS-1$
            sb.append("| Record the modes before an update | snapshot_modes |\n"); //$NON-NLS-1$
            sb.append("| See what an update did to the modes | restore_modes without " //$NON-NLS-1$
                + "apply - it reports the difference and writes nothing |\n"); //$NON-NLS-1$
            sb.append("| Put the modes back after an update | restore_modes apply=true |" //$NON-NLS-1$
                + "\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: modes, workflow.\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList("status", "list_objects", "object_mode", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "snapshot_modes", "restore_modes")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
