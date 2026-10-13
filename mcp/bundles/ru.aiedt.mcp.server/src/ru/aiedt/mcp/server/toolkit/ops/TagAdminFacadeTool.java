/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.MarkerWriteOutcome;
import ru.aiedt.mcp.server.labels.MarkerHelpers;
import ru.aiedt.mcp.server.labels.model.Marker;
import ru.aiedt.mcp.server.labels.model.MarkerStore;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.FacadeHelpSearch;
import ru.aiedt.mcp.server.support.FacadeParameterHelp;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * {@code tag_admin} - the project's metadata tags, written.
 *
 * <p>A tag is a named, colored label a user attaches to metadata objects; the definitions and the
 * assignments live in the project's {@code .settings/aiedt-markers.yaml} and the reads stay in
 * {@code workspace_marks}. This facade edits them through the marker service, one door per write:
 * {@code create_tag}, {@code update_tag}, {@code delete_tag}, {@code assign_tag} and
 * {@code unassign_tag} are each gated by the standalone name of that operation, so a
 * write-blocking preset refuses the call before the file is read.</p>
 *
 * <p>Every argument is checked before the first write runs: an empty name, a color that is not
 * {@code #RRGGBB}, a tag that is not defined, a name another tag already carries and an object the
 * model does not hold are each refused with their reason and change nothing. An {@code assign_tag}
 * or {@code unassign_tag} call with a list works as one write: one unknown tag refuses the whole
 * call, and a tag the object already carries - or does not carry - is reported in {@code skipped}
 * rather than refused. A tag name that differs only in case names another tag; the names, not their
 * case, tell the tags apart.</p>
 *
 * <p>Every write takes {@code dryRun=true}, which answers what the call would write and changes
 * nothing: no byte of the markers file, no cached storage, no change notification. The preview runs
 * the same checks the write runs and refuses for the same reasons, so a caller that reads a
 * successful preview and repeats the call without {@code dryRun} gets the change it was shown. A
 * preview writes nothing, so a write-blocking preset lets it through when the call is made as
 * {@code tag_admin operation=...}; the standalone names of the five writes are switched off by such
 * a preset with and without {@code dryRun}, because that switch is answered before the call reaches
 * this facade.</p>
 */
public class TagAdminFacadeTool
    implements IMcpTool
{
    /** The facade's callable name. */
    public static final String NAME = "tag_admin"; //$NON-NLS-1$

    /** The door a preset switches the create operation off under. */
    public static final String DOOR_CREATE = "create_tag"; //$NON-NLS-1$

    /** The door a preset switches the update operation off under. */
    public static final String DOOR_UPDATE = "update_tag"; //$NON-NLS-1$

    /** The door a preset switches the delete operation off under. */
    public static final String DOOR_DELETE = "delete_tag"; //$NON-NLS-1$

    /** The door a preset switches the assign operation off under. */
    public static final String DOOR_ASSIGN = "assign_tag"; //$NON-NLS-1$

    /** The door a preset switches the unassign operation off under. */
    public static final String DOOR_UNASSIGN = "unassign_tag"; //$NON-NLS-1$

    /** Every operation this facade accepts, in the order a refusal lists them. */
    private static final String KNOWN =
        "create_tag | update_tag | delete_tag | assign_tag | unassign_tag | help"; //$NON-NLS-1$

    /** The shape a color is stored in: a hash and six hex digits. */
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}"); //$NON-NLS-1$

    /** The marker service is not there: the plugin is headless, starting or stopping. */
    static final String REASON_SERVICE_UNAVAILABLE = "serviceUnavailable"; //$NON-NLS-1$

    /** No project of that name is open in the workspace. */
    static final String REASON_PROJECT_NOT_FOUND = "projectNotFound"; //$NON-NLS-1$

    /** The object the call named is not in the project's configuration. */
    static final String REASON_OBJECT_NOT_FOUND = "objectNotFound"; //$NON-NLS-1$

    /** A tag name that is empty once trimmed. */
    static final String REASON_INVALID_NAME = "invalidName"; //$NON-NLS-1$

    /** A color that is not a {@code #RRGGBB} hex value. */
    static final String REASON_INVALID_COLOR = "invalidColor"; //$NON-NLS-1$

    /** Nothing was removed because the object carries none of the named tags. */
    static final String REASON_NOT_ASSIGNED = "notAssigned"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    /** Every help topic, in the order the catalog names them: operations, then named topics. */
    private static final List<String> HELP_TOPICS = helpTopics();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Tags - named, colored labels a user attaches to metadata objects, stored per project " //$NON-NLS-1$
            + "in .settings/aiedt-markers.yaml; reads stay in workspace_marks. Operations: " //$NON-NLS-1$
            + "create_tag (tag, optional color #RRGGBB and description), update_tag (newName, " //$NON-NLS-1$
            + "color, description; a rename keeps the assignments), delete_tag (takes the tag off " //$NON-NLS-1$
            + "every object), assign_tag (objectFqn, tags array; one write for the list), " //$NON-NLS-1$
            + "unassign_tag (tags array; answers removed and skipped). dryRun=true previews any " //$NON-NLS-1$
            + "write and changes nothing, and passes a write-blocking preset when the call goes " //$NON-NLS-1$
            + "through operation=; the standalone write names stay refused there either way."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public List<String> getGatedWriteNames()
    {
        return List.of(DOOR_CREATE, DOOR_UPDATE, DOOR_DELETE, DOOR_ASSIGN, DOOR_UNASSIGN);
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation",
                KNOWN + " (required; snake_case canonical, camelCase like createTag accepted). " //$NON-NLS-1$
                    + "Pass operation=help alone for the operation catalog.") //$NON-NLS-1$
            .stringProperty("projectName",
                "Name of the EDT project (required for every operation but help).") //$NON-NLS-1$
            .stringProperty("tag",
                "create_tag, update_tag, delete_tag: the tag name (required). Must not be empty " //$NON-NLS-1$
                    + "once trimmed; names differing only in case are different tags.") //$NON-NLS-1$
            .stringProperty("color",
                "create_tag, update_tag: the tag color as #RRGGBB (optional; create keeps the " //$NON-NLS-1$
                    + "default gray).") //$NON-NLS-1$
            .stringProperty("description",
                "create_tag, update_tag: the tag description (optional; omitted leaves it, an " //$NON-NLS-1$
                    + "empty string clears it).") //$NON-NLS-1$
            .stringProperty("newName",
                "update_tag: the new tag name (optional; omitted keeps the name). A rename " //$NON-NLS-1$
                    + "carries the assignments.") //$NON-NLS-1$
            .booleanProperty("dryRun",
                "true answers what the write would do and changes nothing; delete_tag answers the " //$NON-NLS-1$
                    + "assignment count it would drop. Passed as operation=, a preview also runs " //$NON-NLS-1$
                    + "under a write-blocking preset; the standalone write names stay off there " //$NON-NLS-1$
                    + "(default false).") //$NON-NLS-1$
            .stringProperty("objectFqn",
                "assign_tag, unassign_tag: the object's fully qualified name, e.g. " //$NON-NLS-1$
                    + "'Catalog.Products' or 'Catalog.Products.Attribute.Code'. assign_tag " //$NON-NLS-1$
                    + "checks it against the model and refuses an object it does not hold.") //$NON-NLS-1$
            .stringArrayProperty("tags",
                "assign_tag, unassign_tag: the tag names (required). One unknown tag refuses " //$NON-NLS-1$
                    + "the whole call; a tag the object already - or does not - carries is " //$NON-NLS-1$
                    + "reported in skipped.") //$NON-NLS-1$
            .stringProperty("topic",
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("find", FacadeHelpSearch.FIND_DESCRIPTION)
            .build();
    }

    /**
     * Runs one tag operation.
     *
     * @param params the call arguments; {@code operation} is required
     * @return the answer JSON
     */
    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isBlank())
        {
            return ToolResult.error("operation is required. Allowed: " + KNOWN + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        operation = JsonUtils.normalizeOperationToken(operation);
        if ("help".equals(operation)) //$NON-NLS-1$
        {
            // This facade answers JSON, so the markdown help travels inside the document rather
            // than instead of it - a JSON-typed tool that answered text would reach the caller as
            // a parse error.
            return ToolResult.success()
                .put("operation", "help") //$NON-NLS-1$ //$NON-NLS-2$
                .put("text", buildHelp(JsonUtils.extractStringArgument(params, "topic"), //$NON-NLS-1$
                    JsonUtils.extractStringArgument(params, "find"), getInputSchema())) //$NON-NLS-1$
                .toJson();
        }
        if (!OPS.containsKey(operation))
        {
            return ToolResult.error("Unknown operation '" + operation + "'." //$NON-NLS-1$ //$NON-NLS-2$
                + FacadeHelpSearch.closestMatches(operation, OPS.keySet(),
                    FacadeHelpSearch.describe(buildHelp(null, null, getInputSchema())))
                + "\n\nAllowed: " + KNOWN + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        switch (operation)
        {
            case "create_tag": //$NON-NLS-1$
            {
                // A create writes the markers file, so it is gated as a write: under a
                // write-blocking preset it is refused before the file is read.
                String gate = writeGate(DOOR_CREATE, dryRun);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doCreateTag(params, dryRun);
            }
            case "update_tag": //$NON-NLS-1$
            {
                // A rename, a new color or a new description writes the file - gated the same way.
                String gate = writeGate(DOOR_UPDATE, dryRun);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doUpdateTag(params, dryRun);
            }
            case "delete_tag": //$NON-NLS-1$
            {
                // A delete takes the tag off every object that carries it - gated the same way.
                String gate = writeGate(DOOR_DELETE, dryRun);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doDeleteTag(params, dryRun);
            }
            case "assign_tag": //$NON-NLS-1$
            {
                // Assigning writes the file - gated before the model is asked about the object.
                String gate = writeGate(DOOR_ASSIGN, dryRun);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doAssignTag(params, dryRun);
            }
            case "unassign_tag": //$NON-NLS-1$
            {
                // Taking tags off an object writes the file - gated the same way.
                String gate = writeGate(DOOR_UNASSIGN, dryRun);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doUnassignTag(params, dryRun);
            }
            default:
                return ToolResult.error("Unhandled operation: " + operation).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * The gate a write passes and a preview does not.
     * <p>
     * Every operation of this facade writes the markers file, so a preset that blocks writes refuses
     * all five by their doors. A call with {@code dryRun=true} writes nothing - it answers what the
     * write would do - so the gate is not asked about it, and a caller under a read-only preset can
     * still look before asking for the write. This is the rule {@code edit_metadata} follows for its
     * own previews.
     * </p>
     *
     * @param door the door of the operation
     * @param dryRun whether the call asked for a preview
     * @return the refusal the preset answers with, or {@code null} when the call may proceed
     */
    private static String writeGate(String door, boolean dryRun)
    {
        return dryRun ? null : ToolGate.gateOrNull(door);
    }

    /**
     * The marker service this facade works through. A seam for tests: the real answer is
     * {@link MarkerManager#getInstance()}, and a test hands in {@code null} to exercise the
     * {@code serviceUnavailable} refusal.
     *
     * @return the marker service, or {@code null} when there is none
     */
    MarkerManager markerService()
    {
        return MarkerManager.getInstance();
    }

    /**
     * What the model answers about the object the call named: the address the marker file stores,
     * or why it cannot carry a tag.
     * <p>
     * A second seam for tests: the real answer asks the EDT model of the project, which a unit test
     * cannot stand up. The rule itself is {@link #checkAgainst}, which a test runs on a
     * configuration built in memory.
     * </p>
     *
     * @param project the project the object must exist in
     * @param objectFqn the fully qualified name as the caller wrote it
     * @return the address to store, or the refusal
     */
    ObjectCheck checkObject(IProject project, String objectFqn)
    {
        Configuration configuration = configurationOf(project);
        if (configuration == null)
        {
            return ObjectCheck.refused(ToolResult.error("The configuration model of the project is " //$NON-NLS-1$
                + "not available, so the object cannot be checked against it. Wait a moment and " //$NON-NLS-1$
                + "try again.").put("reason", REASON_SERVICE_UNAVAILABLE)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return checkAgainst(configuration, objectFqn);
    }

    /**
     * Checks an object address against a configuration and answers the address the marker file
     * stores.
     * <p>
     * The object must exist - a name that is not in the model would otherwise sit in the marker
     * file naming nothing. Unlike a cluster, a tag also sits on nested objects - a form, an
     * attribute - so an address past the second segment walks the containment lists of the object
     * above it, in {@code kind.name} pairs the way the marker file itself spells them. The answer
     * is the address with the case the model carries, so a later read of the same object matches.
     * </p>
     *
     * @param configuration the configuration the object must exist in
     * @param objectFqn the fully qualified name as the caller wrote it
     * @return the address to store, or the refusal
     */
    static ObjectCheck checkAgainst(Configuration configuration, String objectFqn)
    {
        String fqn = MetadataTypeCatalog.normalizeFqn(objectFqn == null ? "" : objectFqn.trim()); //$NON-NLS-1$
        if (fqn.isEmpty())
        {
            return ObjectCheck.refused(ToolResult.error("objectFqn must be provided. Expected " //$NON-NLS-1$
                + "'Type.Name', for example 'Catalog.Products'.").put("reason", REASON_OBJECT_NOT_FOUND)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String[] segments = fqn.split("\\."); //$NON-NLS-1$
        if (segments.length < 2 || segments[0].isEmpty() || segments[1].isEmpty()
            || (segments.length - 2) % 2 != 0)
        {
            return ObjectCheck.refused(ToolResult.error("The object '" + objectFqn + "' is not a " //$NON-NLS-1$ //$NON-NLS-2$
                + "metadata address. Expected 'Type.Name' or 'Type.Name.kind.name' pairs, for " //$NON-NLS-1$
                + "example 'Catalog.Products' or 'Catalog.Products.Attribute.Code'.") //$NON-NLS-1$
                .put("reason", REASON_OBJECT_NOT_FOUND)); //$NON-NLS-1$
        }
        MdObject top = MetadataTypeCatalog.findObject(configuration, segments[0], segments[1]);
        if (top == null)
        {
            List<String> similar = MetadataTypeCatalog.findSimilarObjects(configuration, segments[0],
                segments[1], 5);
            StringBuilder message = new StringBuilder("No object '" + segments[0] + "." //$NON-NLS-1$ //$NON-NLS-2$
                + segments[1] + "' in the configuration of this project."); //$NON-NLS-1$
            if (!similar.isEmpty())
            {
                List<String> full = new ArrayList<>();
                for (String name : similar)
                {
                    full.add(segments[0] + "." + name); //$NON-NLS-1$
                }
                message.append(" Did you mean " + String.join(", ", full) + "?"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return ObjectCheck.refused(ToolResult.error(message.toString())
                .put("reason", REASON_OBJECT_NOT_FOUND)); //$NON-NLS-1$
        }
        StringBuilder address = new StringBuilder(top.eClass().getName()).append('.') //$NON-NLS-1$
            .append(top.getName());
        EObject current = top;
        for (int i = 2; i + 1 < segments.length; i += 2)
        {
            EObject child = childByName(current, segments[i], segments[i + 1]);
            String kind = BmObjectHelper.canonicalChildKind(segments[i]);
            String name = MarkerHelpers.getObjectName(child);
            if (child == null || kind == null || name == null)
            {
                return ObjectCheck.refused(ToolResult.error("No '" + segments[i] + "' named '" //$NON-NLS-1$ //$NON-NLS-2$
                    + segments[i + 1] + "' under '" + address + "' in the configuration of this " //$NON-NLS-1$ //$NON-NLS-2$
                    + "project.").put("reason", REASON_OBJECT_NOT_FOUND)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            current = child;
            address.append('.').append(kind).append('.').append(name);
        }
        return ObjectCheck.of(address.toString());
    }

    /**
     * The child of an object a {@code kind.name} pair names, read without changing the model.
     *
     * @param parent the object the pair hangs under
     * @param kind the child kind, e.g. {@code Attribute} or {@code Form}
     * @param name the child name
     * @return the child, or {@code null} when the object holds none such
     */
    private static EObject childByName(EObject parent, String kind, String name)
    {
        EList<? extends EObject> children = BmObjectHelper.getChildListByKind(parent, kind);
        if (children == null)
        {
            return null;
        }
        for (EObject child : children)
        {
            String childName = MarkerHelpers.getObjectName(child);
            if (name.equalsIgnoreCase(childName))
            {
                return child;
            }
        }
        return null;
    }

    /** What the model answered about an object: the address to store, or why it cannot be. */
    static final class ObjectCheck
    {
        /** The address the marker file stores; {@code null} when refused. */
        final String fqn;

        /** Why the object cannot carry a tag; {@code null} when it can. */
        final ToolResult refusal;

        /**
         * @param fqn the address, or {@code null}
         * @param refusal the refusal, or {@code null}
         */
        private ObjectCheck(String fqn, ToolResult refusal)
        {
            this.fqn = fqn;
            this.refusal = refusal;
        }

        /**
         * An object the model knows under that address.
         *
         * @param fqn the address the marker file stores
         * @return the answer
         */
        static ObjectCheck of(String fqn)
        {
            return new ObjectCheck(fqn, null);
        }

        /**
         * An object that cannot carry a tag.
         *
         * @param refusal the refusal to answer the call with
         * @return the answer
         */
        static ObjectCheck refused(ToolResult refusal)
        {
            return new ObjectCheck(null, refusal);
        }
    }

    /**
     * The configuration of a project, or {@code null} when the model cannot be reached.
     *
     * @param project the project
     * @return its configuration, or {@code null}
     */
    private static Configuration configurationOf(IProject project)
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return null;
        }
        IConfigurationProvider provider = activator.getConfigurationProvider();
        if (provider == null)
        {
            return null;
        }
        try
        {
            return provider.getConfiguration(project);
        }
        catch (RuntimeException notAnAnswer)
        {
            // A model that will not answer is not a model saying the object is there.
            return null;
        }
    }

    /**
     * A resolved call: the marker service and the project, or the refusal naming whichever is
     * missing.
     */
    static final class ServiceProject
    {
        final MarkerManager service;

        final IProject project;

        final ToolResult refusal;

        /**
         * @param service the marker service
         * @param project the resolved project
         * @param refusal the refusal, or {@code null} when both are there
         */
        ServiceProject(MarkerManager service, IProject project, ToolResult refusal)
        {
            this.service = service;
            this.project = project;
            this.refusal = refusal;
        }
    }

    /**
     * Resolves the service and the project every operation needs.
     *
     * @param projectName the project name as the caller wrote it
     * @return the pair, with the refusal set when one of the two is missing
     */
    private ServiceProject resolveServiceAndProject(String projectName)
    {
        if (projectName == null || projectName.isBlank())
        {
            return new ServiceProject(null, null,
                ToolResult.error("projectName must be provided. Example: {projectName: " //$NON-NLS-1$
                    + "'MyProject', operation: 'create_tag', tag: 'Important'}")); //$NON-NLS-1$
        }
        MarkerManager service = markerService();
        if (service == null)
        {
            return new ServiceProject(null, null, serviceUnavailable());
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return new ServiceProject(service, null,
                ProjectResolver.notFound(projectName).put("reason", REASON_PROJECT_NOT_FOUND)); //$NON-NLS-1$
        }
        return new ServiceProject(service, project, null);
    }

    /**
     * The refusal every operation answers when the marker service is not there.
     *
     * @return the refusal
     */
    private static ToolResult serviceUnavailable()
    {
        return ToolResult.error("The marker service is not available: the plugin is starting, " //$NON-NLS-1$
            + "stopping, or running headless. No tag was read or written.") //$NON-NLS-1$
                .put("reason", REASON_SERVICE_UNAVAILABLE); //$NON-NLS-1$
    }

    /**
     * The refusal an unusable tag name answers with.
     *
     * @param argument the argument the name came in
     * @return the refusal
     */
    private static ToolResult nameRefusal(String argument)
    {
        return ToolResult.error(argument + " must not be empty.").put("reason", REASON_INVALID_NAME); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The refusal a color of the wrong shape answers with.
     *
     * @param color the color as the caller wrote it
     * @return the refusal
     */
    private static ToolResult colorRefusal(String color)
    {
        return ToolResult.error("The color '" + color + "' is not a #RRGGBB hex value.") //$NON-NLS-1$ //$NON-NLS-2$
            .put("reason", REASON_INVALID_COLOR); //$NON-NLS-1$
    }

    /**
     * The refusal a service outcome carries, with the outcome's own code beside its sentence.
     *
     * @param outcome the refused outcome
     * @param what what the call wanted to do, for the sentence
     * @return the refusal
     */
    private static ToolResult refusalOf(MarkerWriteOutcome outcome, String what)
    {
        return ToolResult.error(what + " was refused: " + outcome.getDetail()) //$NON-NLS-1$
            .put("reason", outcome.getCode()); //$NON-NLS-1$
    }

    /**
     * One tag as a JSON node.
     *
     * @param marker the tag
     * @return the node
     */
    private static Map<String, Object> tagNode(Marker marker)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", marker.getName()); //$NON-NLS-1$
        node.put("color", marker.getColor()); //$NON-NLS-1$
        node.put("description", marker.getDescription()); //$NON-NLS-1$
        return node;
    }

    /**
     * Reads the tag names a call handed in, as a list that keeps the caller's order and drops
     * nothing: a blank or null element stays in the list, so the caller refuses the whole call
     * with {@code invalidName} instead of writing the rest of the list.
     *
     * @param params the call arguments
     * @return the names; empty when the call brought none
     */
    private static List<String> requestedTags(Map<String, String> params)
    {
        return tagNames(JsonUtils.extractStringArgument(params, "tags"), //$NON-NLS-1$
            JsonUtils.extractArrayArgument(params, "tags")); //$NON-NLS-1$
    }

    /**
     * Turns the {@code tags} argument into names without losing a member.
     * <p>
     * The shared array reader keeps only primitive members, so a JSON {@code null}, an object or a
     * nested array would vanish before any check saw it and the rest of the list would be written.
     * A JSON array is therefore read here member by member: a string gives its trimmed text, and
     * anything else gives the empty name, which the caller refuses as {@code invalidName}.
     * </p>
     *
     * @param rawArgument the argument as the call carried it, or <code>null</code>
     * @param sharedReading what the shared array reader made of it, used when the argument is not a
     *     JSON array
     * @return the names in the caller's order, each once; empty when the call brought none
     */
    static List<String> tagNames(String rawArgument, List<String> sharedReading)
    {
        Set<String> unique = new LinkedHashSet<>();
        String text = rawArgument == null ? "" : rawArgument.trim(); //$NON-NLS-1$
        if (text.startsWith("[")) //$NON-NLS-1$
        {
            try
            {
                com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(text);
                if (parsed.isJsonArray())
                {
                    for (com.google.gson.JsonElement member : parsed.getAsJsonArray())
                    {
                        boolean isText = member.isJsonPrimitive() && member.getAsJsonPrimitive().isString();
                        unique.add(isText ? member.getAsString().trim() : ""); //$NON-NLS-1$
                    }
                    return new ArrayList<>(unique);
                }
            }
            catch (com.google.gson.JsonParseException malformed)
            {
                // Not JSON after all: the shared reading below decides what the text names.
            }
        }
        if (sharedReading == null)
        {
            return List.of();
        }
        for (String name : sharedReading)
        {
            unique.add(name == null ? "" : name.trim()); //$NON-NLS-1$
        }
        return new ArrayList<>(unique);
    }

    /**
     * Refuses a tag that is already defined, with the sentence the marker service answers a write
     * with: a preview reads what the write would say.
     *
     * @param tag the name that is already defined
     * @param what the operation, for the sentence
     * @return the refusal
     */
    private static ToolResult tagExists(String tag, String what)
    {
        return refusalOf(MarkerWriteOutcome.refused(MarkerWriteOutcome.TAG_EXISTS,
            "a marker named \"" + tag + "\" is already defined in this project"), what); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Refuses a rename onto a name another tag carries, with the sentence the marker service
     * answers a write with.
     *
     * @param newName the name that is taken
     * @return the refusal
     */
    private static ToolResult nameTaken(String newName)
    {
        return refusalOf(MarkerWriteOutcome.refused(MarkerWriteOutcome.NAME_TAKEN,
            "another marker is already named \"" + newName //$NON-NLS-1$
                + "\"; the color and description were left as they were"), //$NON-NLS-1$
            "update_tag"); //$NON-NLS-1$
    }

    /**
     * Creates a tag, or answers what creating it would write.
     *
     * @param params the call arguments
     * @param dryRun whether the call asked for a preview
     * @return the answer JSON
     */
    private String doCreateTag(Map<String, String> params, boolean dryRun)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        String tag = JsonUtils.extractStringArgument(params, "tag"); //$NON-NLS-1$
        if (tag == null || tag.trim().isEmpty())
        {
            return nameRefusal("tag").toJson(); //$NON-NLS-1$
        }
        tag = tag.trim();
        String color = JsonUtils.extractStringArgument(params, "color"); //$NON-NLS-1$
        if (color != null && !color.isBlank())
        {
            color = color.trim();
            if (!COLOR.matcher(color).matches())
            {
                return colorRefusal(color).toJson();
            }
        }
        else
        {
            color = null;
        }
        String description = JsonUtils.extractStringArgument(params, "description"); //$NON-NLS-1$
        if (dryRun)
        {
            ToolResult fileRefusal = fileRefusalOf(context.service, context.project);
            if (fileRefusal != null)
            {
                return fileRefusal.toJson();
            }
            if (context.service.getMarkerStorage(context.project).getMarkerByName(tag) != null)
            {
                return tagExists(tag, "create_tag").toJson(); //$NON-NLS-1$
            }
            ToolResult writeRefusal = writeRefusalOf(context.service, context.project,
                "create_tag", "the marker \"" + tag + "\" was not created"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (writeRefusal != null)
            {
                return writeRefusal.toJson();
            }
            return ToolResult.success()
                .put("created", false) //$NON-NLS-1$
                .put("dryRun", true) //$NON-NLS-1$
                .put("tag", tagNode(new Marker(tag, color, description))) //$NON-NLS-1$
                .toJson();
        }
        MarkerWriteOutcome outcome = context.service.createMarkerWithOutcome(context.project, tag,
            color, description);
        if (outcome.isRefused())
        {
            return refusalOf(outcome, "create_tag").toJson(); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("created", true) //$NON-NLS-1$
            .put("tag", tagNode(outcome.getMarker())) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Renames a tag, or changes its color and description, in one edit, or answers what the edit
     * would write.
     *
     * @param params the call arguments
     * @param dryRun whether the call asked for a preview
     * @return the answer JSON
     */
    private String doUpdateTag(Map<String, String> params, boolean dryRun)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        String tag = JsonUtils.extractStringArgument(params, "tag"); //$NON-NLS-1$
        if (tag == null || tag.trim().isEmpty())
        {
            return nameRefusal("tag").toJson(); //$NON-NLS-1$
        }
        tag = tag.trim();
        String newName = JsonUtils.extractStringArgument(params, "newName"); //$NON-NLS-1$
        if (newName != null)
        {
            if (newName.trim().isEmpty())
            {
                return nameRefusal("newName").toJson(); //$NON-NLS-1$
            }
            newName = newName.trim();
        }
        String color = JsonUtils.extractStringArgument(params, "color"); //$NON-NLS-1$
        if (color != null && !color.isBlank())
        {
            color = color.trim();
            if (!COLOR.matcher(color).matches())
            {
                return colorRefusal(color).toJson();
            }
        }
        else if (color != null)
        {
            // An empty color clears the shape check with nothing to set; the refusal names it
            // rather than dropping the argument on the floor.
            return colorRefusal(color).toJson();
        }
        String description = JsonUtils.extractStringArgument(params, "description"); //$NON-NLS-1$
        ToolResult fileRefusal = fileRefusalOf(context.service, context.project);
        if (fileRefusal != null)
        {
            return fileRefusal.toJson();
        }
        MarkerStore storage = context.service.getMarkerStorage(context.project);
        Marker existing = storage.getMarkerByName(tag);
        if (existing == null)
        {
            return tagNotFound(tag);
        }
        boolean renaming = newName != null && !newName.equals(tag);
        if (dryRun)
        {
            if (renaming && storage.getMarkerByName(newName) != null)
            {
                return nameTaken(newName).toJson();
            }
            ToolResult writeRefusal = writeRefusalOf(context.service, context.project,
                "update_tag", "the marker \"" + tag + "\" was not updated"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (writeRefusal != null)
            {
                return writeRefusal.toJson();
            }
            Marker after = new Marker(renaming ? newName : existing.getName(),
                color != null ? color : existing.getColor(),
                description != null ? description : existing.getDescription());
            ToolResult preview = ToolResult.success()
                .put("tag", tagNode(existing)) //$NON-NLS-1$
                .put("tagAfter", tagNode(after)) //$NON-NLS-1$
                .put("dryRun", true); //$NON-NLS-1$
            if (renaming)
            {
                preview = preview.put("movedAssignments", //$NON-NLS-1$
                    Integer.valueOf(storage.getObjectsByMarker(tag).size()));
            }
            return preview.toJson();
        }
        MarkerWriteOutcome outcome = context.service.updateMarkerWithOutcome(context.project, tag,
            newName, color, description);
        if (outcome.isRefused())
        {
            return refusalOf(outcome, "update_tag").toJson(); //$NON-NLS-1$
        }
        ToolResult result = ToolResult.success()
            .put("tag", tagNode(outcome.getMarker() != null ? outcome.getMarker() : existing)); //$NON-NLS-1$
        if (newName != null && !newName.equals(tag))
        {
            result = result.put("movedAssignments", Integer.valueOf(outcome.getChangedAssignments())); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * Deletes a tag and takes it off every object, or counts what that would take off.
     *
     * @param params the call arguments
     * @param dryRun whether the call asked for a preview
     * @return the answer JSON
     */
    private String doDeleteTag(Map<String, String> params, boolean dryRun)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        String tag = JsonUtils.extractStringArgument(params, "tag"); //$NON-NLS-1$
        if (tag == null || tag.trim().isEmpty())
        {
            return nameRefusal("tag").toJson(); //$NON-NLS-1$
        }
        tag = tag.trim();
        ToolResult fileRefusal = fileRefusalOf(context.service, context.project);
        if (fileRefusal != null)
        {
            return fileRefusal.toJson();
        }
        if (context.service.getMarkerStorage(context.project).getMarkerByName(tag) == null)
        {
            return tagNotFound(tag);
        }
        int assignments = context.service.getMarkerStorage(context.project)
            .getObjectsByMarker(tag).size();
        if (dryRun)
        {
            ToolResult writeRefusal = writeRefusalOf(context.service, context.project,
                "delete_tag", "the marker \"" + tag + "\" was not deleted"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (writeRefusal != null)
            {
                return writeRefusal.toJson();
            }
            return ToolResult.success()
                .put("deleted", false) //$NON-NLS-1$
                .put("dryRun", true) //$NON-NLS-1$
                .put("assignments", Integer.valueOf(assignments)) //$NON-NLS-1$
                .toJson();
        }
        MarkerWriteOutcome outcome = context.service.deleteMarkerWithOutcome(context.project, tag);
        if (outcome.isRefused())
        {
            return refusalOf(outcome, "delete_tag").toJson(); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("deleted", true) //$NON-NLS-1$
            .put("assignments", Integer.valueOf(outcome.getChangedAssignments())) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Assigns tags to an object in one write, or answers what assigning them would write.
     *
     * @param params the call arguments
     * @param dryRun whether the call asked for a preview
     * @return the answer JSON
     */
    private String doAssignTag(Map<String, String> params, boolean dryRun)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        List<String> tags = requestedTags(params);
        if (tags.isEmpty())
        {
            return ToolResult.error("tags must be provided. Example: tags: ['Important', " //$NON-NLS-1$
                + "'NeedsReview'].").toJson(); //$NON-NLS-1$
        }
        for (String tag : tags)
        {
            if (tag.trim().isEmpty())
            {
                return nameRefusal("tags").toJson(); //$NON-NLS-1$
            }
        }
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (objectFqn == null || objectFqn.isBlank())
        {
            return ToolResult.error("objectFqn must be provided. Example: 'Catalog.Products'.") //$NON-NLS-1$
                .toJson();
        }
        ToolResult fileRefusal = fileRefusalOf(context.service, context.project);
        if (fileRefusal != null)
        {
            return fileRefusal.toJson();
        }
        ObjectCheck check = checkObject(context.project, objectFqn);
        if (check.refusal != null)
        {
            return check.refusal.toJson();
        }
        objectFqn = check.fqn;
        // Every tag must be defined before the first assignment is made: one unknown tag refuses
        // the whole call and nothing is assigned. The check reads a snapshot outside the write
        // lock, so a definition racing it still meets the operation's own refusal under the lock.
        Set<String> defined = new LinkedHashSet<>();
        for (Marker marker : context.service.getMarkers(context.project))
        {
            defined.add(marker.getName());
        }
        for (String tag : tags)
        {
            if (!defined.contains(tag))
            {
                return tagNotFound(tag);
            }
        }
        if (dryRun)
        {
            List<String> before = tagsOn(context.service.getMarkerStorage(context.project),
                objectFqn);
            List<String> assigned = new ArrayList<>();
            Map<String, String> skipped = new LinkedHashMap<>();
            for (String tag : tags)
            {
                if (before.contains(tag))
                {
                    skipped.put(tag, "alreadyAssigned"); //$NON-NLS-1$
                }
                else
                {
                    assigned.add(tag);
                }
            }
            List<String> after = new ArrayList<>(before);
            after.addAll(assigned);
            if (!assigned.isEmpty())
            {
                ToolResult writeRefusal = writeRefusalOf(context.service, context.project,
                    "assign_tag", "no marker was assigned to \"" + objectFqn + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (writeRefusal != null)
                {
                    return writeRefusal.toJson();
                }
            }
            return ToolResult.success()
                .put("objectFqn", objectFqn) //$NON-NLS-1$
                .put("dryRun", true) //$NON-NLS-1$
                .put("tagsBefore", before) //$NON-NLS-1$
                .put("assigned", assigned) //$NON-NLS-1$
                .put("skipped", skippedNodes(skipped)) //$NON-NLS-1$
                .put("tagsAfter", after) //$NON-NLS-1$
                .toJson();
        }
        MarkerWriteOutcome outcome = assignViaService(context.service, context.project, objectFqn,
            tags);
        if (outcome.isRefused())
        {
            return refusalOf(outcome, "assign_tag").toJson(); //$NON-NLS-1$
        }
        // The answer names what this operation itself changed under its write lock: a tag another
        // call assigned in between reads as skipped, not as assigned here.
        return ToolResult.success()
            .put("objectFqn", objectFqn) //$NON-NLS-1$
            .put("assigned", outcome.getAppliedNames()) //$NON-NLS-1$
            .put("skipped", skippedNodes(outcome.getSkippedReasons())) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Takes tags off an object in one write, or answers what taking them off would write.
     *
     * @param params the call arguments
     * @param dryRun whether the call asked for a preview
     * @return the answer JSON
     */
    private String doUnassignTag(Map<String, String> params, boolean dryRun)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        List<String> tags = requestedTags(params);
        if (tags.isEmpty())
        {
            return ToolResult.error("tags must be provided. Example: tags: ['Important', " //$NON-NLS-1$
                + "'NeedsReview'].").toJson(); //$NON-NLS-1$
        }
        for (String tag : tags)
        {
            if (tag.trim().isEmpty())
            {
                return nameRefusal("tags").toJson(); //$NON-NLS-1$
            }
        }
        ToolResult fileRefusal = fileRefusalOf(context.service, context.project);
        if (fileRefusal != null)
        {
            return fileRefusal.toJson();
        }
        Set<String> defined = new LinkedHashSet<>();
        for (Marker marker : context.service.getMarkers(context.project))
        {
            defined.add(marker.getName());
        }
        for (String tag : tags)
        {
            if (!defined.contains(tag))
            {
                return tagNotFound(tag);
            }
        }
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (objectFqn == null || objectFqn.isBlank())
        {
            return ToolResult.error("objectFqn must be provided. Example: 'Catalog.Products'.") //$NON-NLS-1$
                .toJson();
        }
        objectFqn = objectFqn.trim();
        if (dryRun)
        {
            List<String> before = tagsOn(context.service.getMarkerStorage(context.project),
                objectFqn);
            List<String> removed = new ArrayList<>();
            Map<String, String> skipped = new LinkedHashMap<>();
            for (String tag : tags)
            {
                if (before.contains(tag))
                {
                    removed.add(tag);
                }
                else
                {
                    skipped.put(tag, "notAssigned"); //$NON-NLS-1$
                }
            }
            if (removed.isEmpty())
            {
                // A preview of a removal that would remove nothing reads the refusal the write
                // itself answers with, and still says nothing was written.
                return ToolResult.error("The object '" + objectFqn + "' carries none of the named " //$NON-NLS-1$ //$NON-NLS-2$
                    + "tags, so nothing was removed.")
                        .put("reason", REASON_NOT_ASSIGNED) //$NON-NLS-1$
                        .put("dryRun", true) //$NON-NLS-1$
                        .put("tagsBefore", before) //$NON-NLS-1$
                        .put("removed", removed) //$NON-NLS-1$
                        .put("skipped", skippedNodes(skipped)) //$NON-NLS-1$
                        .toJson();
            }
            ToolResult writeRefusal = writeRefusalOf(context.service, context.project,
                "unassign_tag", "no marker was removed from \"" + objectFqn + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (writeRefusal != null)
            {
                return writeRefusal.toJson();
            }
            List<String> after = new ArrayList<>(before);
            after.removeAll(removed);
            return ToolResult.success()
                .put("objectFqn", objectFqn) //$NON-NLS-1$
                .put("dryRun", true) //$NON-NLS-1$
                .put("tagsBefore", before) //$NON-NLS-1$
                .put("removed", removed) //$NON-NLS-1$
                .put("skipped", skippedNodes(skipped)) //$NON-NLS-1$
                .put("tagsAfter", after) //$NON-NLS-1$
                .toJson();
        }
        MarkerWriteOutcome outcome = unassignViaService(context.service, context.project,
            objectFqn, tags);
        if (outcome.isRefused())
        {
            return refusalOf(outcome, "unassign_tag").toJson(); //$NON-NLS-1$
        }
        if (outcome.getAppliedNames().isEmpty())
        {
            // This operation removed nothing: the answer is an error rather than a no-op success,
            // so a caller that mistyped the object hears about it instead of reading "done".
            return ToolResult.error("The object '" + objectFqn + "' carries none of the named " //$NON-NLS-1$ //$NON-NLS-2$
                + "tags, so nothing was removed.")
                    .put("reason", REASON_NOT_ASSIGNED) //$NON-NLS-1$
                    .put("removed", outcome.getAppliedNames()) //$NON-NLS-1$
                    .put("skipped", skippedNodes(outcome.getSkippedReasons())) //$NON-NLS-1$
                    .toJson();
        }
        // The answer names what this operation itself removed under its write lock: a tag another
        // call took off in between reads as skipped, not as removed here.
        return ToolResult.success()
            .put("objectFqn", objectFqn) //$NON-NLS-1$
            .put("removed", outcome.getAppliedNames()) //$NON-NLS-1$
            .put("skipped", skippedNodes(outcome.getSkippedReasons())) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Runs the assignment through the marker service. A seam for tests: the method sits between
     * the call's pre-checks and the operation's own write lock, the window in which another call
     * can assign to the same object first, so a test arranges that interleaving by overriding it.
     *
     * @param service the marker service
     * @param project the project
     * @param objectFqn the object the tags go on, as the model spelled it
     * @param tags the tag names, every one defined
     * @return the operation's outcome
     */
    MarkerWriteOutcome assignViaService(MarkerManager service, IProject project, String objectFqn,
        List<String> tags)
    {
        return service.assignMarkersWithOutcome(project, objectFqn, tags);
    }

    /**
     * Runs the removal through the marker service. A seam for tests, the counterpart of
     * {@link #assignViaService(MarkerManager, IProject, String, List)}: the method sits between
     * the call's pre-checks and the operation's own write lock, the window in which another call
     * can take the same tags off the object first.
     *
     * @param service the marker service
     * @param project the project
     * @param objectFqn the object the tags come off
     * @param tags the tag names, every one defined
     * @return the operation's outcome
     */
    MarkerWriteOutcome unassignViaService(MarkerManager service, IProject project,
        String objectFqn, List<String> tags)
    {
        return service.unassignMarkersWithOutcome(project, objectFqn, tags);
    }

    /**
     * The skipped part of a list answer, as the nodes the response carries.
     *
     * @param skippedReasons why each skipped name was not applied, in the order the operation
     *            recorded them
     * @return one node per name
     */
    private static List<Map<String, Object>> skippedNodes(Map<String, String> skippedReasons)
    {
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Map.Entry<String, String> skip : skippedReasons.entrySet())
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("tag", skip.getKey()); //$NON-NLS-1$
            node.put("reason", skip.getValue()); //$NON-NLS-1$
            skipped.add(node);
        }
        return skipped;
    }

    /**
     * The tags an object carries, in the order the file records them.
     * <p>
     * The object's own assignment list, not the defined tags resolved from it: a name the file
     * lists while no definition matches it keeps the place the file gave it, and the names that do
     * resolve keep theirs. The list is copied, so a caller building the after-state cannot reach
     * the store through it.
     * </p>
     *
     * @param storage the marker storage
     * @param objectFqn the object
     * @return the names, never {@code null}
     */
    private static List<String> tagsOn(MarkerStore storage, String objectFqn)
    {
        List<String> names = storage.getAssignments().get(objectFqn);
        return names == null || names.isEmpty() ? new ArrayList<>() : new ArrayList<>(names);
    }

    /**
     * The refusal an unusable marker file answers with, checked before the tags themselves are
     * read: a pre-check that cannot see the definitions would otherwise answer {@code tagNotFound}
     * for a tag the file does define. A file that does not parse answers {@code unreadableFile};
     * one whose bytes could not be read at all - refused access, a drive that went away - answers
     * {@code saveFailed}.
     *
     * @param service the marker service
     * @param project the project
     * @return the refusal, or {@code null} when the file is readable or absent
     */
    private static ToolResult fileRefusalOf(MarkerManager service, IProject project)
    {
        String refusal = service.markerFileRefusal(project);
        if (refusal != null)
        {
            return ToolResult.error(refusal + " No tag was written.") //$NON-NLS-1$
                .put("reason", MarkerWriteOutcome.UNREADABLE_FILE); //$NON-NLS-1$
        }
        if (service.markerFileReadFailed(project))
        {
            return ToolResult.error("The marker file of project " + project.getName() //$NON-NLS-1$
                + " could not be read, so its tags cannot be checked. No tag was written.") //$NON-NLS-1$
                    .put("reason", MarkerWriteOutcome.SAVE_FAILED); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The refusal a write would answer with before it touches the marker file, or {@code null}
     * when nothing refuses it yet.
     * <p>
     * The service asks the file the same question its save routes ask, so a preview refuses with
     * the code and the sentence the write itself refuses with.
     * </p>
     *
     * @param service the marker service
     * @param project the project
     * @param what what the call wanted to do, for the sentence
     * @param subject what the write was about, as the sentence the refusal carries before the
     *            file reason
     * @return the refusal, or {@code null}
     */
    private static ToolResult writeRefusalOf(MarkerManager service, IProject project, String what,
        String subject)
    {
        MarkerWriteOutcome refusal = service.fileWriteRefusal(project, subject);
        return refusal != null ? refusalOf(refusal, what) : null;
    }

    /**
     * The refusal a tag that is not defined answers with.
     *
     * @param tag the tag name that named nothing
     * @return the refusal JSON
     */
    private static String tagNotFound(String tag)
    {
        return ToolResult.error("No tag named '" + tag + "' is defined in this project. " //$NON-NLS-1$ //$NON-NLS-2$
            + "workspace_marks operation=get_tags lists the defined ones.") //$NON-NLS-1$
                .put("reason", MarkerWriteOutcome.TAG_NOT_FOUND).toJson(); //$NON-NLS-1$
    }

    /**
     * The facade's own help.
     *
     * @param topic the topic asked about, or {@code null} for the catalog
     * @param find the words to search for, or {@code null}
     * @param schema the schema this facade declares
     * @return markdown help
     */
    private static String buildHelp(String topic, String find, String schema)
    {
        if (find != null && !find.isBlank())
        {
            return FacadeHelpSearch.search(NAME, find, topic, HELP_TOPICS, OPS.keySet(),
                asked -> buildHelp(asked, null, schema));
        }
        topic = JsonUtils.normalizeOperationToken(topic);
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# tag_admin - operations\n\n"); //$NON-NLS-1$
            sb.append("- **create_tag** - define a tag, with an optional #RRGGBB color and a " //$NON-NLS-1$
                + "description.\n"); //$NON-NLS-1$
            sb.append("- **update_tag** - rename a tag (newName; the assignments carry over) or " //$NON-NLS-1$
                + "change its color and description.\n"); //$NON-NLS-1$
            sb.append("- **delete_tag** - delete a tag and take it off every object.\n"); //$NON-NLS-1$
            sb.append("- **assign_tag** - put tags on an object (objectFqn, tags array); the " //$NON-NLS-1$
                + "object is checked against the model and the list writes as one.\n"); //$NON-NLS-1$
            sb.append("- **unassign_tag** - take tags off an object (tags array).\n"); //$NON-NLS-1$
            sb.append("- **dryRun=true** - accepted by every operation: it answers what the call " //$NON-NLS-1$
                + "would write (create_tag the tag it would define, update_tag the tag before and " //$NON-NLS-1$
                + "after, delete_tag the assignment count, assign_tag and unassign_tag the " //$NON-NLS-1$
                + "object's tags before and after) and changes nothing. It runs under a write-" //$NON-NLS-1$
                + "blocking preset when the call is passed as operation= of tag_admin; the " //$NON-NLS-1$
                + "standalone names of the five writes are switched off there either way.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the operation-picker " //$NON-NLS-1$
                + "guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# tag_admin - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| See what tags exist and what carries them | workspace_marks get_tags, " //$NON-NLS-1$
                + "get_objects_by_tags |\n"); //$NON-NLS-1$
            sb.append("| Mark an object as important or needing review | create_tag, then " //$NON-NLS-1$
                + "assign_tag |\n"); //$NON-NLS-1$
            sb.append("| Rename a tag or change its look | update_tag |\n"); //$NON-NLS-1$
            sb.append("| Retire a tag | delete_tag (dryRun first to see what it touches) |\n"); //$NON-NLS-1$
            sb.append("| Take a mark off one object | unassign_tag |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return FacadeParameterHelp.answer(topic, Collections.emptyMap(), OPS.keySet(),
            "workflow", "TagAdminFacadeTool", schema, buildHelp(null, null, schema)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The topics {@code find} searches, catalog first by the caller, these after.
     *
     * @return the topic names, never {@code null}
     */
    private static List<String> helpTopics()
    {
        List<String> topics = new ArrayList<>(OPS.keySet());
        topics.add("workflow"); //$NON-NLS-1$
        return Collections.unmodifiableList(topics);
    }

    /**
     * The operations this facade dispatches, each mapped to itself: the write doors carry the same
     * name as the operation they gate.
     *
     * @return operation name to itself, unmodifiable
     */
    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "create_tag", "update_tag", "delete_tag", "assign_tag", "unassign_tag")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
