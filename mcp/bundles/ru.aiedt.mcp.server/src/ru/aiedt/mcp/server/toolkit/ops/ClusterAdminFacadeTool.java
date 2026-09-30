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

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.folders.ClusterWriteOutcome;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;
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
 * {@code cluster_admin} - the custom folder hierarchy of the Navigator, read and written.
 *
 * <p>A cluster is a virtual folder hanging under a metadata collection ({@code Catalog},
 * {@code CommonModule} and so on); the objects it holds are kept in the project's
 * {@code .settings/aiedt-clusters.yaml} and hidden from their normal place in the tree. This facade
 * reads that hierarchy ({@code get_clusters}) and edits it through the cluster service, one door per
 * write: {@code create_cluster}, {@code update_cluster}, {@code delete_cluster},
 * {@code add_to_cluster} and {@code remove_from_cluster} are each gated by the standalone name of
 * that operation, so a write-blocking preset refuses the call before the file is read.</p>
 *
 * <p>The service itself is not an OSGi service and may be absent - before the plugin starts, after
 * it stops, in a headless workbench - and every operation answers {@code serviceUnavailable} then.
 * A write that reached the file and was refused answers with the outcome's own code
 * ({@code changedOnDisk}, {@code unreadableFile}, {@code lockRefused} and the rest) beside a
 * sentence for a person. Before any write runs, its own arguments are checked: an empty name, a
 * missing cluster, an object the model does not hold and an object of another collection are each
 * refused with their reason and change nothing.</p>
 */
public class ClusterAdminFacadeTool
    implements IMcpTool
{
    /** The facade's callable name. */
    public static final String NAME = "cluster_admin"; //$NON-NLS-1$

    /** The door a preset switches the create operation off under. */
    public static final String DOOR_CREATE = "create_cluster"; //$NON-NLS-1$

    /** The door a preset switches the update operation off under. */
    public static final String DOOR_UPDATE = "update_cluster"; //$NON-NLS-1$

    /** The door a preset switches the delete operation off under. */
    public static final String DOOR_DELETE = "delete_cluster"; //$NON-NLS-1$

    /** The door a preset switches the move-in operation off under. */
    public static final String DOOR_ADD = "add_to_cluster"; //$NON-NLS-1$

    /** The door a preset switches the take-out operation off under. */
    public static final String DOOR_REMOVE = "remove_from_cluster"; //$NON-NLS-1$

    /** Every operation this facade accepts, in the order a refusal lists them. */
    private static final String KNOWN =
        "get_clusters | create_cluster | update_cluster | delete_cluster | add_to_cluster | " //$NON-NLS-1$
            + "remove_from_cluster | help"; //$NON-NLS-1$

    /** The cluster service is not there: the plugin is headless, starting or stopping. */
    static final String REASON_SERVICE_UNAVAILABLE = "serviceUnavailable"; //$NON-NLS-1$

    /** No project of that name is open in the workspace. */
    static final String REASON_PROJECT_NOT_FOUND = "projectNotFound"; //$NON-NLS-1$

    /** The object the call named is not in the project's configuration. */
    static final String REASON_OBJECT_NOT_FOUND = "objectNotFound"; //$NON-NLS-1$

    /** The object belongs to another collection than the cluster hangs under. */
    static final String REASON_OUTSIDE_COLLECTION = "outsideCollection"; //$NON-NLS-1$

    /** The object the call wanted taken out of its clusters is in none. */
    static final String REASON_NOT_CLUSTERED = "notClustered"; //$NON-NLS-1$

    /** A cluster name that is empty once trimmed, or carries a slash. */
    static final String REASON_INVALID_NAME = "invalidName"; //$NON-NLS-1$

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
        return "Clusters - the custom folder hierarchy of the Navigator, stored per project in " //$NON-NLS-1$
            + ".settings/aiedt-clusters.yaml. Operations: get_clusters (the clusters of one " //$NON-NLS-1$
            + "collection or of every collection, as a tree with the held objects), create_cluster " //$NON-NLS-1$
            + "(on a collectionPath, or nested under parentClusterPath), update_cluster (newName, " //$NON-NLS-1$
            + "description), delete_cluster (removes the nested clusters too; dryRun counts what " //$NON-NLS-1$
            + "would go without writing), add_to_cluster (moves the object out of its current " //$NON-NLS-1$
            + "cluster; the object is checked against the model and must belong to the cluster's " //$NON-NLS-1$
            + "collection), remove_from_cluster (out of every cluster). The five writes are gated " //$NON-NLS-1$
            + "by their own names, so a write-blocking preset refuses them before the file is read."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public List<String> getGatedWriteNames()
    {
        return List.of(DOOR_CREATE, DOOR_UPDATE, DOOR_DELETE, DOOR_ADD, DOOR_REMOVE);
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                KNOWN + " (required; snake_case canonical, camelCase like getClusters is also " //$NON-NLS-1$
                    + "accepted). Pass operation=help without other params for the operation " //$NON-NLS-1$
                    + "catalog.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project (required for every operation but help).") //$NON-NLS-1$
            .stringProperty("collectionPath", //$NON-NLS-1$
                "get_clusters: only this collection, e.g. 'Catalog'; omitted lists every " //$NON-NLS-1$
                    + "collection that has clusters. create_cluster: the collection to hang the " //$NON-NLS-1$
                    + "cluster under, required unless parentClusterPath is given.") //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "create_cluster: the cluster name (required). Must not be empty or contain a " //$NON-NLS-1$
                    + "slash.") //$NON-NLS-1$
            .stringProperty("parentClusterPath", //$NON-NLS-1$
                "create_cluster: nest the new cluster under this cluster's full path instead of " //$NON-NLS-1$
                    + "a collection path.") //$NON-NLS-1$
            .stringProperty("description", //$NON-NLS-1$
                "create_cluster and update_cluster: the cluster description; omitted clears it " //$NON-NLS-1$
                    + "on update.") //$NON-NLS-1$
            .stringProperty("clusterPath", //$NON-NLS-1$
                "update_cluster, delete_cluster, add_to_cluster: the full path of the cluster, " //$NON-NLS-1$
                    + "e.g. 'Catalog/Shelf' (required).") //$NON-NLS-1$
            .stringProperty("newName", //$NON-NLS-1$
                "update_cluster: the new cluster name (required).") //$NON-NLS-1$
            .booleanProperty("dryRun", //$NON-NLS-1$
                "delete_cluster: true answers what the delete would remove (nestedClusters, " //$NON-NLS-1$
                    + "objects) and writes nothing; the call still passes the write gate, because " //$NON-NLS-1$
                    + "the operation is a write (default false).") //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "add_to_cluster, remove_from_cluster: the fully qualified name of the object, " //$NON-NLS-1$
                    + "e.g. 'Catalog.Products' (required). add_to_cluster checks it against the " //$NON-NLS-1$
                    + "configuration and refuses an object of another collection.") //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("find", FacadeHelpSearch.FIND_DESCRIPTION)
            .build();
    }

    /**
     * Runs one cluster operation.
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
            case "get_clusters": //$NON-NLS-1$
                return doGetClusters(params);
            case "create_cluster": //$NON-NLS-1$
            {
                // A create writes the clusters file, so it is gated as a write: under a
                // write-blocking preset it is refused before the file is read.
                String gate = ToolGate.gateOrNull(DOOR_CREATE);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doCreateCluster(params);
            }
            case "update_cluster": //$NON-NLS-1$
            {
                // A rename or a new description writes the file - gated the same way.
                String gate = ToolGate.gateOrNull(DOOR_UPDATE);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doUpdateCluster(params);
            }
            case "delete_cluster": //$NON-NLS-1$
            {
                // A delete removes the cluster with everything nested under it. A dry run writes
                // nothing, but the operation is a write and passes the same door.
                String gate = ToolGate.gateOrNull(DOOR_DELETE);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doDeleteCluster(params);
            }
            case "add_to_cluster": //$NON-NLS-1$
            {
                // Moving an object between clusters writes the file - gated before the model
                // is asked about the object.
                String gate = ToolGate.gateOrNull(DOOR_ADD);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doAddToCluster(params);
            }
            case "remove_from_cluster": //$NON-NLS-1$
            {
                // Taking an object out of every cluster writes the file - gated the same way.
                String gate = ToolGate.gateOrNull(DOOR_REMOVE);
                if (gate != null)
                {
                    return ToolResult.error(gate).toJson();
                }
                return doRemoveFromCluster(params);
            }
            default:
                return ToolResult.error("Unhandled operation: " + operation).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * The cluster service this facade works through. A seam for tests: the real answer is
     * {@link Activator#getClusterServiceStatic()}, which is {@code null} before the plugin starts
     * and after it stops, and a test hands in its own manager instead.
     *
     * @return the cluster service, or {@code null} when there is none
     */
    IClusterManager clusterService()
    {
        return Activator.getClusterServiceStatic();
    }

    /**
     * Why the object the call named cannot be moved into a cluster of that collection, or
     * {@code null} when it can.
     * <p>
     * The object must exist in the project's configuration - a name that is not in the model would
     * otherwise sit in the clusters file naming nothing - and its type must be the collection the
     * cluster hangs under. A second seam for tests: the real answer asks the EDT model, which a
     * unit test cannot stand up.
     * </p>
     *
     * @param project the project the object must exist in
     * @param objectFqn the fully qualified name as the caller wrote it
     * @param collectionPath the collection the target cluster hangs under
     * @return the refusal, or {@code null} when the object may be clustered
     */
    ToolResult objectModelRefusal(IProject project, String objectFqn, String collectionPath)
    {
        String fqn = MetadataTypeCatalog.normalizeFqn(objectFqn == null ? "" : objectFqn.trim()); //$NON-NLS-1$
        String[] segments = fqn.split("\\."); //$NON-NLS-1$
        if (segments.length < 2 || segments[0].isEmpty() || segments[1].isEmpty())
        {
            return ToolResult.error("The object '" + objectFqn + "' is not a metadata address. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Expected 'Type.Name', for example 'Catalog.Products'.") //$NON-NLS-1$
                .put("reason", REASON_OBJECT_NOT_FOUND); //$NON-NLS-1$
        }
        Configuration configuration = configurationOf(project);
        if (configuration == null)
        {
            return ToolResult.error("The configuration model of the project is not available, so " //$NON-NLS-1$
                + "the object cannot be checked against it. Wait a moment and try again.") //$NON-NLS-1$
                .put("reason", REASON_SERVICE_UNAVAILABLE); //$NON-NLS-1$
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
            return ToolResult.error(message.toString()).put("reason", REASON_OBJECT_NOT_FOUND); //$NON-NLS-1$
        }
        if (collectionPath != null && !segments[0].equalsIgnoreCase(collectionPath.trim()))
        {
            return ToolResult.error("The cluster hangs under the collection '" + collectionPath //$NON-NLS-1$
                + "' and cannot hold '" + segments[0] + "." + segments[1] + "'.") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .put("reason", REASON_OUTSIDE_COLLECTION); //$NON-NLS-1$
        }
        return null;
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
     * A resolved call: the cluster service and the project, or the refusal naming whichever is
     * missing.
     */
    static final class ServiceProject
    {
        final IClusterManager service;

        final IProject project;

        final ToolResult refusal;

        /**
         * @param service the cluster service
         * @param project the resolved project
         * @param refusal the refusal, or {@code null} when both are there
         */
        ServiceProject(IClusterManager service, IProject project, ToolResult refusal)
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
                    + "'MyProject', operation: 'get_clusters'}")); //$NON-NLS-1$
        }
        IClusterManager service = clusterService();
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
     * The refusal every operation answers when the cluster service is not there.
     *
     * @return the refusal
     */
    private static ToolResult serviceUnavailable()
    {
        return ToolResult.error("The cluster service is not available: the plugin is starting, " //$NON-NLS-1$
            + "stopping, or running headless. No cluster was read or written.") //$NON-NLS-1$
                .put("reason", REASON_SERVICE_UNAVAILABLE); //$NON-NLS-1$
    }

    /**
     * The refusal a refused cluster edit answers with: the outcome's own code beside its sentence.
     *
     * @param outcome the refused outcome
     * @return the refusal
     */
    private static ToolResult refusalOf(ClusterWriteOutcome outcome)
    {
        ToolResult refusal = ToolResult.error(outcome.explanation())
            .put("reason", outcome.getCode()); //$NON-NLS-1$
        if (outcome.getDetail() != null)
        {
            refusal = refusal.put("detail", outcome.getDetail()); //$NON-NLS-1$
        }
        return refusal;
    }

    /**
     * The refusal an unusable cluster name answers with.
     *
     * @param name the name as the caller wrote it
     * @param slash whether the name carries a slash
     * @return the refusal
     */
    private static ToolResult nameRefusal(String name, boolean slash)
    {
        String message = slash
            ? "The cluster name must not contain a slash: '" + name + "'." //$NON-NLS-1$ //$NON-NLS-2$
            : "The cluster name must not be empty."; //$NON-NLS-1$
        return ToolResult.error(message).put("reason", REASON_INVALID_NAME); //$NON-NLS-1$
    }

    /**
     * Reads the clusters of a project, one collection or all of them, as a tree.
     *
     * @param params the call arguments
     * @return the answer JSON
     */
    private String doGetClusters(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        ToolResult result = ToolResult.success().put("projectName", context.project.getName()); //$NON-NLS-1$
        String collectionPath = JsonUtils.extractStringArgument(params, "collectionPath"); //$NON-NLS-1$
        if (collectionPath != null && !collectionPath.isBlank())
        {
            collectionPath = collectionPath.trim();
            List<Object> clusters = new ArrayList<>();
            for (Cluster cluster : context.service.getClustersAtPath(context.project, collectionPath))
            {
                clusters.add(clusterNode(context.service, context.project, cluster));
            }
            return result.put("collectionPath", collectionPath).put("clusters", clusters).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<Object> collections = new ArrayList<>();
        for (String collection : collectionsOf(context.service, context.project))
        {
            Map<String, Object> entry = new LinkedHashMap<>();
            List<Object> clusters = new ArrayList<>();
            for (Cluster cluster : context.service.getClustersAtPath(context.project, collection))
            {
                clusters.add(clusterNode(context.service, context.project, cluster));
            }
            entry.put("collectionPath", collection); //$NON-NLS-1$
            entry.put("clusters", clusters); //$NON-NLS-1$
            collections.add(entry);
        }
        return result.put("collections", collections).toJson(); //$NON-NLS-1$
    }

    /**
     * The collections a project's clusters hang under, in the order the clusters name them.
     *
     * @param service the cluster service
     * @param project the project
     * @return the collection paths, never {@code null}; empty when there are no clusters
     */
    private static List<String> collectionsOf(IClusterManager service, IProject project)
    {
        Set<String> collections = new LinkedHashSet<>();
        for (Cluster cluster : service.getAllClusters(project))
        {
            String fullPath = cluster.getFullPath();
            if (fullPath == null || fullPath.isEmpty())
            {
                continue;
            }
            collections.add(collectionOf(fullPath));
        }
        return new ArrayList<>(collections);
    }

    /**
     * The collection a full cluster path hangs under: its first slash-separated segment.
     *
     * @param clusterFullPath the cluster's full path
     * @return the collection path
     */
    static String collectionOf(String clusterFullPath)
    {
        int slash = clusterFullPath.indexOf('/');
        return slash < 0 ? clusterFullPath : clusterFullPath.substring(0, slash);
    }

    /**
     * One cluster as a JSON node: its address, its held objects, and its nested clusters as nodes.
     *
     * @param service the cluster service
     * @param project the project
     * @param cluster the cluster to render
     * @return the node
     */
    private static Map<String, Object> clusterNode(IClusterManager service, IProject project,
        Cluster cluster)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("fullPath", cluster.getFullPath()); //$NON-NLS-1$
        node.put("name", cluster.getName()); //$NON-NLS-1$
        node.put("description", cluster.getDescription()); //$NON-NLS-1$
        node.put("order", Integer.valueOf(cluster.getOrder())); //$NON-NLS-1$
        node.put("objects", cluster.getChildren()); //$NON-NLS-1$
        List<Object> children = new ArrayList<>();
        for (Cluster nested : service.getClustersAtPath(project, cluster.getFullPath()))
        {
            children.add(clusterNode(service, project, nested));
        }
        node.put("children", children); //$NON-NLS-1$
        return node;
    }

    /**
     * Creates a cluster on a collection path, or nested under another cluster.
     *
     * @param params the call arguments
     * @return the answer JSON
     */
    private String doCreateCluster(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (name == null || name.trim().isEmpty())
        {
            return nameRefusal(name, false).toJson();
        }
        name = name.trim();
        if (name.contains("/")) //$NON-NLS-1$
        {
            return nameRefusal(name, true).toJson();
        }
        String collectionPath = JsonUtils.extractStringArgument(params, "collectionPath"); //$NON-NLS-1$
        String parentClusterPath = JsonUtils.extractStringArgument(params, "parentClusterPath"); //$NON-NLS-1$
        String path;
        if (parentClusterPath != null && !parentClusterPath.isBlank())
        {
            path = parentClusterPath.trim();
            if (context.service.getClusterStorage(context.project).getClusterByFullPath(path) == null)
            {
                return ToolResult.error("There is no cluster '" + path + "' in this project.") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("reason", ClusterWriteOutcome.CLUSTER_NOT_FOUND).toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            if (collectionPath == null || collectionPath.isBlank())
            {
                return ToolResult.error("collectionPath must be provided when parentClusterPath " //$NON-NLS-1$
                    + "is not. Example: {operation: 'create_cluster', collectionPath: 'Catalog', " //$NON-NLS-1$
                    + "name: 'Shelf'}.").toJson(); //$NON-NLS-1$
            }
            path = collectionPath.trim();
        }
        String description = JsonUtils.extractStringArgument(params, "description"); //$NON-NLS-1$
        ClusterWriteOutcome outcome = context.service.createCluster(context.project, name, path,
            description);
        if (outcome.isRefused())
        {
            return refusalOf(outcome).toJson();
        }
        Cluster created = outcome.getCluster();
        return ToolResult.success()
            .put("clusterPath", path) //$NON-NLS-1$
            .put("cluster", clusterSummary(created)) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Renames a cluster and sets its description in one step.
     *
     * @param params the call arguments
     * @return the answer JSON
     */
    private String doUpdateCluster(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        String clusterPath = JsonUtils.extractStringArgument(params, "clusterPath"); //$NON-NLS-1$
        if (clusterPath == null || clusterPath.isBlank())
        {
            return ToolResult.error("clusterPath must be provided. Example: 'Catalog/Shelf'.") //$NON-NLS-1$
                .toJson();
        }
        clusterPath = clusterPath.trim();
        Cluster existing = context.service.getClusterStorage(context.project)
            .getClusterByFullPath(clusterPath);
        if (existing == null)
        {
            return clusterNotFound(clusterPath);
        }
        String newName = JsonUtils.extractStringArgument(params, "newName"); //$NON-NLS-1$
        if (newName == null || newName.trim().isEmpty())
        {
            return nameRefusal(newName, false).toJson();
        }
        newName = newName.trim();
        if (newName.contains("/")) //$NON-NLS-1$
        {
            return nameRefusal(newName, true).toJson();
        }
        String description = JsonUtils.extractStringArgument(params, "description"); //$NON-NLS-1$
        ClusterWriteOutcome outcome = context.service.updateCluster(context.project, clusterPath,
            newName, description);
        if (outcome.isRefused())
        {
            return refusalOf(outcome).toJson();
        }
        String newPath = existing.getPath() == null || existing.getPath().isEmpty()
            ? newName
            : existing.getPath() + "/" + newName; //$NON-NLS-1$
        Cluster updated = context.service.getClusterStorage(context.project)
            .getClusterByFullPath(newPath);
        ToolResult result = ToolResult.success().put("clusterPath", newPath); //$NON-NLS-1$
        if (outcome.isNoChange())
        {
            result = result.put("noChange", true); //$NON-NLS-1$
        }
        if (updated != null)
        {
            result = result.put("cluster", clusterSummary(updated)); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * Deletes a cluster with everything nested under it, or counts what that would remove.
     *
     * @param params the call arguments
     * @return the answer JSON
     */
    private String doDeleteCluster(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        String clusterPath = JsonUtils.extractStringArgument(params, "clusterPath"); //$NON-NLS-1$
        if (clusterPath == null || clusterPath.isBlank())
        {
            return ToolResult.error("clusterPath must be provided. Example: 'Catalog/Shelf'.") //$NON-NLS-1$
                .toJson();
        }
        clusterPath = clusterPath.trim();
        List<Cluster> doomed = doomedClusters(context.service.getClusterStorage(context.project),
            clusterPath);
        if (doomed.isEmpty())
        {
            return clusterNotFound(clusterPath);
        }
        int objects = 0;
        for (Cluster cluster : doomed)
        {
            objects += cluster.getChildren().size();
        }
        int nested = doomed.size() - 1;
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (dryRun)
        {
            return ToolResult.success()
                .put("deleted", false) //$NON-NLS-1$
                .put("dryRun", true) //$NON-NLS-1$
                .put("nestedClusters", nested) //$NON-NLS-1$
                .put("objects", objects) //$NON-NLS-1$
                .toJson();
        }
        ClusterWriteOutcome outcome = context.service.deleteCluster(context.project, clusterPath);
        if (outcome.isRefused())
        {
            return refusalOf(outcome).toJson();
        }
        return ToolResult.success()
            .put("deleted", true) //$NON-NLS-1$
            .put("nestedClusters", nested) //$NON-NLS-1$
            .put("objects", objects) //$NON-NLS-1$
            .toJson();
    }

    /**
     * The clusters a delete of that full path would take: the cluster itself, its duplicates, and
     * every cluster nested under the path. Mirrors the storage's own removal rule.
     *
     * @param storage the project's clusters
     * @param fullPath the full path of the cluster to delete
     * @return the clusters that would go, empty when the path names nothing
     */
    private static List<Cluster> doomedClusters(ClusterStore storage, String fullPath)
    {
        String nestedPrefix = fullPath + "/"; //$NON-NLS-1$
        List<Cluster> doomed = new ArrayList<>();
        for (Cluster cluster : storage.getGroups())
        {
            if (fullPath.equals(cluster.getFullPath()))
            {
                doomed.add(cluster);
                continue;
            }
            String path = cluster.getPath();
            if (path != null && (path.equals(fullPath) || path.startsWith(nestedPrefix)))
            {
                doomed.add(cluster);
            }
        }
        return doomed;
    }

    /**
     * Moves an object into a cluster, out of whatever cluster held it.
     *
     * @param params the call arguments
     * @return the answer JSON
     */
    private String doAddToCluster(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (objectFqn == null || objectFqn.isBlank())
        {
            return ToolResult.error("objectFqn must be provided. Example: 'Catalog.Products'.") //$NON-NLS-1$
                .toJson();
        }
        objectFqn = objectFqn.trim();
        String clusterPath = JsonUtils.extractStringArgument(params, "clusterPath"); //$NON-NLS-1$
        if (clusterPath == null || clusterPath.isBlank())
        {
            return ToolResult.error("clusterPath must be provided. Example: 'Catalog/Shelf'.") //$NON-NLS-1$
                .toJson();
        }
        clusterPath = clusterPath.trim();
        Cluster target = context.service.getClusterStorage(context.project)
            .getClusterByFullPath(clusterPath);
        if (target == null)
        {
            return clusterNotFound(clusterPath);
        }
        ToolResult modelRefusal = objectModelRefusal(context.project, objectFqn,
            collectionOf(clusterPath));
        if (modelRefusal != null)
        {
            return modelRefusal.toJson();
        }
        Cluster previous = context.service.getClusterStorage(context.project)
            .findClusterForObject(objectFqn);
        ClusterWriteOutcome outcome = context.service.addObjectToCluster(context.project, objectFqn,
            clusterPath);
        if (outcome.isRefused())
        {
            return refusalOf(outcome).toJson();
        }
        // Built as a tree and not through the builder because the answer owes the caller an
        // explicit movedFrom: null - the builder's map drops a null member, and an absent member
        // reads as "never had one" rather than "had none". No move happened on a no-change call
        // either, so the cluster the object already sits in is not named as where it came from.
        JsonObject payload = new JsonObject();
        payload.addProperty("success", true); //$NON-NLS-1$
        payload.addProperty("clusterPath", clusterPath); //$NON-NLS-1$
        payload.add("movedFrom", previous == null || outcome.isNoChange() ? JsonNull.INSTANCE //$NON-NLS-1$
            : new JsonPrimitive(previous.getFullPath()));
        if (outcome.isNoChange())
        {
            payload.addProperty("noChange", true); //$NON-NLS-1$
        }
        return payload.toString();
    }

    /**
     * Takes an object out of every cluster that holds it.
     *
     * @param params the call arguments
     * @return the answer JSON
     */
    private String doRemoveFromCluster(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        ServiceProject context = resolveServiceAndProject(projectName);
        if (context.refusal != null)
        {
            return context.refusal.toJson();
        }
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (objectFqn == null || objectFqn.isBlank())
        {
            return ToolResult.error("objectFqn must be provided. Example: 'Catalog.Products'.") //$NON-NLS-1$
                .toJson();
        }
        objectFqn = objectFqn.trim();
        List<String> holders = new ArrayList<>();
        for (Cluster cluster : context.service.getClusterStorage(context.project).getGroups())
        {
            if (cluster.containsChild(objectFqn))
            {
                holders.add(cluster.getFullPath());
            }
        }
        if (holders.isEmpty())
        {
            return ToolResult.error("The object '" + objectFqn + "' is not in any cluster.") //$NON-NLS-1$ //$NON-NLS-2$
                .put("reason", REASON_NOT_CLUSTERED).toJson(); //$NON-NLS-1$
        }
        ClusterWriteOutcome outcome = context.service.removeObjectFromCluster(context.project,
            objectFqn);
        if (outcome.isRefused())
        {
            return refusalOf(outcome).toJson();
        }
        return ToolResult.success().put("removedFrom", holders).toJson(); //$NON-NLS-1$
    }

    /**
     * The refusal a missing cluster answers with.
     *
     * @param clusterPath the path that named nothing
     * @return the refusal JSON
     */
    private static String clusterNotFound(String clusterPath)
    {
        return ToolResult.error("There is no cluster '" + clusterPath + "' in this project.") //$NON-NLS-1$ //$NON-NLS-2$
            .put("reason", ClusterWriteOutcome.CLUSTER_NOT_FOUND).toJson(); //$NON-NLS-1$
    }

    /**
     * The short shape a created or updated cluster answers with.
     *
     * @param cluster the cluster
     * @return the summary node
     */
    private static Map<String, Object> clusterSummary(Cluster cluster)
    {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fullPath", cluster.getFullPath()); //$NON-NLS-1$
        summary.put("name", cluster.getName()); //$NON-NLS-1$
        summary.put("order", Integer.valueOf(cluster.getOrder())); //$NON-NLS-1$
        return summary;
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
            sb.append("# cluster_admin - operations\n\n"); //$NON-NLS-1$
            sb.append("- **get_clusters** - the clusters of one collection (collectionPath) or of " //$NON-NLS-1$
                + "every collection, as a tree with the held objects.\n"); //$NON-NLS-1$
            sb.append("- **create_cluster** - create a cluster on a collectionPath, or nested " //$NON-NLS-1$
                + "under a parentClusterPath.\n"); //$NON-NLS-1$
            sb.append("- **update_cluster** - rename a cluster (newName) and set its " //$NON-NLS-1$
                + "description.\n"); //$NON-NLS-1$
            sb.append("- **delete_cluster** - delete a cluster and its nested clusters; dryRun " //$NON-NLS-1$
                + "counts them first.\n"); //$NON-NLS-1$
            sb.append("- **add_to_cluster** - move an object into a cluster, out of the cluster " //$NON-NLS-1$
                + "that held it.\n"); //$NON-NLS-1$
            sb.append("- **remove_from_cluster** - take an object out of every cluster.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the operation-picker " //$NON-NLS-1$
                + "guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# cluster_admin - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| See what clusters exist and what they hold | get_clusters |\n"); //$NON-NLS-1$
            sb.append("| Group objects under a new folder | create_cluster, then add_to_cluster |\n"); //$NON-NLS-1$
            sb.append("| Rename a folder or describe it | update_cluster |\n"); //$NON-NLS-1$
            sb.append("| Remove a folder and see what goes with it | delete_cluster (dryRun first) |\n"); //$NON-NLS-1$
            sb.append("| Put an object back to its normal place | remove_from_cluster |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return FacadeParameterHelp.answer(topic, Collections.emptyMap(), OPS.keySet(),
            "workflow", "ClusterAdminFacadeTool", schema, buildHelp(null, null, schema)); //$NON-NLS-1$ //$NON-NLS-2$
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
            "get_clusters", "create_cluster", "update_cluster", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "delete_cluster", "add_to_cluster", "remove_from_cluster")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
