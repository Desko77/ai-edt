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
        return "Tags - the named, colored labels a user attaches to metadata objects, stored per " //$NON-NLS-1$
            + "project in .settings/aiedt-markers.yaml; reading them stays in workspace_marks. " //$NON-NLS-1$
            + "Operations: create_tag (tag, optional color #RRGGBB and description), update_tag " //$NON-NLS-1$
            + "(newName, color, description; a rename carries the assignments), delete_tag (takes " //$NON-NLS-1$
            + "the tag off every object; dryRun answers the assignment count without writing), " //$NON-NLS-1$
            + "assign_tag (objectFqn checked against the model, tags array; one write for the whole " //$NON-NLS-1$
            + "list), unassign_tag (tags array; answers removed and skipped). The five writes are " //$NON-NLS-1$
            + "gated by their own names, so a write-blocking preset refuses them before the file " //$NON-NLS-1$
            + "is read."; //$NON-NLS-1$
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
                KNOWN + " (required; snake_case canonical, camelCase like createTag is also " //$NON-NLS-1$
                    + "accepted). Pass operation=help without other params for the operation " //$NON-NLS-1$
                    + "catalog.") //$NON-NLS-1$
            .stringProperty("projectName",
                "Name of the EDT project (required for every operation but help).") //$NON-NLS-1$
            .stringProperty("tag",
                "create_tag, update_tag, delete_tag: the tag name (required). Must not be empty " //$NON-NLS-1$
                    + "once trimmed; names that differ only in case name different tags.") //$NON-NLS-1$
            .stringProperty("color",
                "create_tag, update_tag: the tag color as #RRGGBB (optional; create keeps the " //$NON-NLS-1$
                    + "default gray when omitted).") //$NON-NLS-1$
            .stringProperty("description",
                "create_tag, update_tag: the tag description (optional; omitted leaves it as it " //$NON-NLS-1$
                    + "is, an empty string clears it).") //$NON-NLS-1$
            .stringProperty("newName",
                "update_tag: the new tag name (optional; omitted keeps the name). A rename " //$NON-NLS-1$
                    + "carries every assignment to the new name.") //$NON-NLS-1$
            .booleanProperty("dryRun",
                "delete_tag: true answers the number of assignments the delete would take off " //$NON-NLS-1$
                    + "and writes nothing; the call still passes the write gate, because the " //$NON-NLS-1$
                    + "operation is a write (default false).") //$NON-NLS-1$
            .stringProperty("objectFqn",
                "assign_tag, unassign_tag: the fully qualified name of the object, e.g. " //$NON-NLS-1$
                    + "'Catalog.Products' or 'Catalog.Products.Attribute.Code'. assign_tag " //$NON-NLS-1$
                    + "checks it against the configuration and refuses an object the model does " //$NON-NLS-1$
                    + "not hold.") //$NON-NLS-1$
            .stringArrayProperty("tags",
                "assign_tag, unassign_tag: tag names the call works with (required). One unknown " //$NON-NLS-1$
                    + "tag refuses the whole call; a tag the object already carries - or does " //$NON-NLS-1$
                    + "not carry - is reported in skipped.") //$NON-NLS-1$
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
        switch (operation)
        {
            case "create_tag": //$NON-NLS-1$
            {
                // A create writes the markers file, so it is gated as a write: under a
                // write-blocking preset it is refused before the file is read.
                String gate = ToolGate.gateOrNull(DOOR_CREATE);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doCreateTag(params);
            }
            case "update_tag": //$NON-NLS-1$
            {
                // A rename, a new color or a new description writes the file - gated the same way.
                String gate = ToolGate.gateOrNull(DOOR_UPDATE);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doUpdateTag(params);
            }
            case "delete_tag": //$NON-NLS-1$
            {
                // A delete takes the tag off every object that carries it. A dry run writes
                // nothing, but the operation is a write and passes the same door.
                String gate = ToolGate.gateOrNull(DOOR_DELETE);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doDeleteTag(params);
            }
            case "assign_tag": //$NON-NLS-1$
            {
                // Assigning writes the file - gated before the model is asked about the object.
                String gate = ToolGate.gateOrNull(DOOR_ASSIGN);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doAssignTag(params);
            }
            case "unassign_tag": //$NON-NLS-1$
            {
                // Taking tags off an object writes the file - gated the same way.
                String gate = ToolGate.gateOrNull(DOOR_UNASSIGN);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doUnassignTag(params);
            }
            default:
                return ToolResult.error("Unhandled operation: " + operation).toJson(); //$NON-NLS-1$
        }
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
     * nothing.
     *
     * @param params the call arguments
     * @return the names; empty when the call brought none
     */
    private static List<String> requestedTags(Map<String, String> params)
    {
        List<String> raw = JsonUtils.extractArrayArgument(params, "tags"); //$NON-NLS-1$
        if (raw == null)
        {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String name : raw)
        {
            if (name != null && !name.isBlank())
            {
                unique.add(name.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * Creates a tag.
     *
     * @param params the call arguments
     * @return the answer JSON
     */
    private String doCreateTag(Map<String, String> params)
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
     * Renames a tag, or changes its color and description, in one edit.
     *
     * @param params the call arguments
     * @return the answer JSON
     */
    private String doUpdateTag(Map<String, String> params)
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
        Marker existing = context.service.getMarkerStorage(context.project).getMarkerByName(tag);
        if (existing == null)
        {
            return tagNotFound(tag);
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
     * @return the answer JSON
     */
    private String doDeleteTag(Map<String, String> params)
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
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (dryRun)
        {
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
     * Assigns tags to an object in one write.
     *
     * @param params the call arguments
     * @return the answer JSON
     */
    private String doAssignTag(Map<String, String> params)
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
     * Takes tags off an object in one write.
     *
     * @param params the call arguments
     * @return the answer JSON
     */
    private String doUnassignTag(Map<String, String> params)
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
     * The refusal an unreadable marker file answers with, checked before the tags themselves are
     * read: a pre-check that cannot see the definitions would otherwise answer {@code tagNotFound}
     * for a tag the file does define.
     *
     * @param service the marker service
     * @param project the project
     * @return the refusal, or {@code null} when the file is readable or absent
     */
    private static ToolResult fileRefusalOf(MarkerManager service, IProject project)
    {
        String refusal = service.markerFileRefusal(project);
        if (refusal == null)
        {
            return null;
        }
        return ToolResult.error(refusal + " No tag was written.") //$NON-NLS-1$
            .put("reason", MarkerWriteOutcome.UNREADABLE_FILE); //$NON-NLS-1$
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
            sb.append("- **delete_tag** - delete a tag and take it off every object; dryRun " //$NON-NLS-1$
                + "answers the assignment count without writing.\n"); //$NON-NLS-1$
            sb.append("- **assign_tag** - put tags on an object (objectFqn, tags array); the " //$NON-NLS-1$
                + "object is checked against the model and the list writes as one.\n"); //$NON-NLS-1$
            sb.append("- **unassign_tag** - take tags off an object (tags array).\n"); //$NON-NLS-1$
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
