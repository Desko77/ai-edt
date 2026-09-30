/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.ClusterWriteOutcome;
import ru.aiedt.mcp.server.folders.IClusterChangeObserver;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;
import ru.aiedt.mcp.server.folders.repository.ClusterSaveOutcome;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * What each {@code cluster_admin} operation answers, and every reason it can refuse with.
 * <p>
 * The facade runs against a recording manager that holds a real {@link ClusterStore} in memory and
 * can be told to refuse a save, so the refusals that come from the file are produced the same way
 * the service produces them - as a {@link ClusterWriteOutcome} carrying the code - without
 * depending on filesystem permissions. The model check of {@code add_to_cluster} is a seam the
 * test fills per case, because a unit test cannot stand a configuration model up.
 * </p>
 */
public class AClusterAdminFacadeAnswersItsOperationsTest
{
    private ClusterWorkspaceProbe probe;

    private RecordingManager manager;

    private ClusterAdminFacadeTool facade;

    private ToolResult modelRefusal;

    /**
     * Opens a project and a facade wired to a recording manager; registers the five write doors so
     * the facade's gates find them enabled, as the live server registers them at startup.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aProjectAndAFacade() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterFacade"); //$NON-NLS-1$
        manager = new RecordingManager();
        modelRefusal = null;
        facade = new ClusterAdminFacadeTool()
        {
            @Override
            IClusterManager clusterService()
            {
                return manager;
            }

            @Override
            ToolResult objectModelRefusal(IProject project, String objectFqn, String collectionPath)
            {
                return modelRefusal;
            }
        };
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.register(facade);
        catalog.register(new ClusterCreateTool());
        catalog.register(new ClusterUpdateTool());
        catalog.register(new ClusterDeleteTool());
        catalog.register(new ClusterAddTool());
        catalog.register(new ClusterRemoveTool());
    }

    /**
     * Takes the doors back out and closes the project.
     *
     * @throws Exception when the project cannot be deleted
     */
    @After
    public void theDoorsAndTheProjectGo() throws Exception
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.unregister(ClusterAdminFacadeTool.NAME);
        catalog.unregister(ClusterAdminFacadeTool.DOOR_CREATE);
        catalog.unregister(ClusterAdminFacadeTool.DOOR_UPDATE);
        catalog.unregister(ClusterAdminFacadeTool.DOOR_DELETE);
        catalog.unregister(ClusterAdminFacadeTool.DOOR_ADD);
        catalog.unregister(ClusterAdminFacadeTool.DOOR_REMOVE);
        if (probe != null)
        {
            probe.close();
        }
    }

    /**
     * @return the facade's answer as parsed JSON
     */
    private JsonObject call(Map<String, String> params)
    {
        return JsonParser.parseString(facade.execute(params)).getAsJsonObject();
    }

    /**
     * @param operation the operation to call
     * @param arguments the arguments beside it
     * @return the answer as parsed JSON
     */
    private JsonObject call(String operation, String... arguments)
    {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("operation", operation); //$NON-NLS-1$
        params.put("projectName", probe.project.getName());
        for (int i = 0; i + 1 < arguments.length; i += 2)
        {
            params.put(arguments[i], arguments[i + 1]);
        }
        return call(params);
    }

    /**
     * Creates a cluster through the facade, so a refusal about creating it can only be about the
     * state the earlier create left.
     *
     * @param name the cluster name
     * @param path the collection path, or the parent cluster's full path
     * @param asNested whether to pass the path as parentClusterPath
     * @return the answer
     */
    private JsonObject create(String name, String path, boolean asNested)
    {
        return asNested
            ? call("create_cluster", "name", name, "parentClusterPath", path) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            : call("create_cluster", "name", name, "collectionPath", path); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The tree names every collection, the nested clusters and the held objects. */
    @Test
    public void theTreeNamesEveryCollectionWithItsClusters()
    {
        assertTrue(create("Shelf", "Catalog", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(create("Sub", "Catalog/Shelf", true).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(create("Util", "CommonModule", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("add_to_cluster", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "clusterPath", "Catalog/Shelf").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject answer = call("get_clusters"); //$NON-NLS-1$
        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        JsonArray collections = answer.getAsJsonArray("collections"); //$NON-NLS-1$
        assertEquals(2, collections.size());
        JsonObject catalogs = collections.get(0).getAsJsonObject();
        assertEquals("Catalog", catalogs.get("collectionPath").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject shelf = catalogs.getAsJsonArray("clusters").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("Catalog/Shelf", shelf.get("fullPath").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, shelf.get("order").getAsInt()); //$NON-NLS-1$
        JsonArray objects = shelf.getAsJsonArray("objects"); //$NON-NLS-1$
        assertEquals(1, objects.size());
        assertEquals("Catalog.Products", objects.get(0).getAsString()); //$NON-NLS-1$
        JsonArray children = shelf.getAsJsonArray("children"); //$NON-NLS-1$
        assertEquals(1, children.size());
        assertEquals("Catalog/Shelf/Sub", children.get(0).getAsJsonObject().get("fullPath") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsString());
        assertEquals("CommonModule", collections.get(1).getAsJsonObject() //$NON-NLS-1$
            .get("collectionPath").getAsString()); //$NON-NLS-1$
    }

    /** A collectionPath limits the answer to that collection. */
    @Test
    public void aCollectionPathLimitsTheAnswer()
    {
        assertTrue(create("Shelf", "Catalog", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(create("Util", "CommonModule", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject answer = call("get_clusters", "collectionPath", "Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Catalog", answer.get("collectionPath").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, answer.getAsJsonArray("clusters").size()); //$NON-NLS-1$
    }

    /** A create answers the created cluster, orders siblings after one another, and nests. */
    @Test
    public void aCreateAnswersTheCreatedCluster()
    {
        JsonObject first = create("Shelf", "Catalog", false);
        assertTrue(first.get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject cluster = first.getAsJsonObject("cluster"); //$NON-NLS-1$
        assertEquals("Catalog/Shelf", cluster.get("fullPath").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, cluster.get("order").getAsInt()); //$NON-NLS-1$

        JsonObject second = create("Rack", "Catalog", false);
        assertEquals(1, second.getAsJsonObject("cluster").get("order").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject nested = create("Sub", "Catalog/Shelf", true);
        assertEquals("Catalog/Shelf/Sub", nested.getAsJsonObject("cluster").get("fullPath") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsString());
    }

    /** A taken path, a missing parent and an unusable name are each refused by their code. */
    @Test
    public void aCreateRefusesByReason()
    {
        assertTrue(create("Shelf", "Catalog", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject taken = create("Shelf", "Catalog", false);
        assertFalse(taken.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("clusterExists", taken.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject orphan = create("Sub", "Catalog/Nowhere", true);
        assertFalse(orphan.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("clusterNotFound", orphan.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject blank = create("   ", "Catalog", false);
        assertFalse(blank.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("invalidName", blank.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject slashed = create("A/B", "Catalog", false);
        assertEquals("invalidName", slashed.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An update renames, answers the new path, and refuses a taken name or a missing cluster. */
    @Test
    public void anUpdateRenamesAndRefusesByReason()
    {
        assertTrue(create("Shelf", "Catalog", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(create("Rack", "Catalog", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject renamed = call("update_cluster", "clusterPath", "Catalog/Shelf", //$NON-NLS-1$ //$NON-NLS-2$
            "newName", "Case", "description", "holds the case objects"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(renamed.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Catalog/Case", renamed.get("clusterPath").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog/Case", renamed.getAsJsonObject("cluster").get("fullPath") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsString());

        JsonObject taken = call("update_cluster", "clusterPath", "Catalog/Rack", //$NON-NLS-1$ //$NON-NLS-2$
            "newName", "Case"); //$NON-NLS-1$
        assertFalse(taken.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("nameTaken", taken.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject missing = call("update_cluster", "clusterPath", "Catalog/Nowhere", //$NON-NLS-1$ //$NON-NLS-2$
            "newName", "Case"); //$NON-NLS-1$
        assertEquals("clusterNotFound", missing.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        // The same state again, description included: omitted description would mean "clear it",
        // which is a change, not the no change this asserts.
        JsonObject same = call("update_cluster", "clusterPath", "Catalog/Case", //$NON-NLS-1$ //$NON-NLS-2$
            "newName", "Case", "description", "holds the case objects"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(same.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(same.get("noChange").getAsBoolean()); //$NON-NLS-1$
    }

    /** A dry run counts the nested clusters and held objects and changes nothing. */
    @Test
    public void aDryRunCountsAndWritesNothing()
    {
        assertTrue(create("Shelf", "Catalog", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(create("Sub", "Catalog/Shelf", true).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("add_to_cluster", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "clusterPath", "Catalog/Shelf").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("add_to_cluster", "objectFqn", "Catalog.Groups", //$NON-NLS-1$ //$NON-NLS-2$
            "clusterPath", "Catalog/Shelf/Sub").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject preview = call("delete_cluster", "clusterPath", "Catalog/Shelf", "dryRun", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "true"); //$NON-NLS-1$
        assertTrue(preview.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(preview.get("deleted").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, preview.get("nestedClusters").getAsInt()); //$NON-NLS-1$
        assertEquals(2, preview.get("objects").getAsInt()); //$NON-NLS-1$
        assertNotNull(manager.store.getClusterByFullPath("Catalog/Shelf")); //$NON-NLS-1$

        JsonObject deleted = call("delete_cluster", "clusterPath", "Catalog/Shelf"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(deleted.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(deleted.get("deleted").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, deleted.get("nestedClusters").getAsInt()); //$NON-NLS-1$
        assertEquals(2, deleted.get("objects").getAsInt()); //$NON-NLS-1$
        assertEquals(0, manager.store.getGroups().size());
    }

    /** A move answers the cluster it came from, and a move onto the same cluster is no change. */
    @Test
    public void aMoveNamesWhereTheObjectCameFrom()
    {
        assertTrue(create("Shelf", "Catalog", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(create("Rack", "Catalog", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject first = call("add_to_cluster", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "clusterPath", "Catalog/Shelf"); //$NON-NLS-1$
        assertTrue(first.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(first.get("movedFrom").isJsonNull()); //$NON-NLS-1$

        JsonObject moved = call("add_to_cluster", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "clusterPath", "Catalog/Rack"); //$NON-NLS-1$
        assertEquals("Catalog/Shelf", moved.get("movedFrom").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject again = call("add_to_cluster", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "clusterPath", "Catalog/Rack"); //$NON-NLS-1$
        assertTrue(again.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(again.get("noChange").getAsBoolean()); //$NON-NLS-1$
        assertTrue(again.get("movedFrom").isJsonNull()); //$NON-NLS-1$

        JsonObject missing = call("add_to_cluster", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "clusterPath", "Catalog/Nowhere"); //$NON-NLS-1$
        assertFalse(missing.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("clusterNotFound", missing.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The model check of a move is answered as given: an unknown object, a foreign collection. */
    @Test
    public void aMoveRefusesWhatTheModelRefuses()
    {
        assertTrue(create("Shelf", "Catalog", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$

        modelRefusal = ToolResult.error("No object 'Document.Order' in the configuration.") //$NON-NLS-1$
            .put("reason", "objectNotFound"); //$NON-NLS-1$
        JsonObject unknown = call("add_to_cluster", "objectFqn", "Document.Order", //$NON-NLS-1$ //$NON-NLS-2$
            "clusterPath", "Catalog/Shelf"); //$NON-NLS-1$
        assertFalse(unknown.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("objectNotFound", unknown.get("reason").getAsString()); //$NON-NLS-1$
        assertTrue(manager.store.findClusterForObject("Document.Order") == null); //$NON-NLS-1$

        modelRefusal = ToolResult.error("The cluster hangs under 'Catalog'.") //$NON-NLS-1$
            .put("reason", "outsideCollection"); //$NON-NLS-1$
        JsonObject foreign = call("add_to_cluster", "objectFqn", "Document.Order", //$NON-NLS-1$ //$NON-NLS-2$
            "clusterPath", "Catalog/Shelf"); //$NON-NLS-1$
        assertEquals("outsideCollection", foreign.get("reason").getAsString()); //$NON-NLS-1$
    }

    /** Taking an object out names every cluster that held it; an unclustered object is refused. */
    @Test
    public void aRemovalNamesTheHolders()
    {
        assertTrue(create("Shelf", "Catalog", false).get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(call("add_to_cluster", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            "clusterPath", "Catalog/Shelf").get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject removed = call("remove_from_cluster", "objectFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(removed.get("success").getAsBoolean()); //$NON-NLS-1$
        JsonArray holders = removed.getAsJsonArray("removedFrom"); //$NON-NLS-1$
        assertEquals(1, holders.size());
        assertEquals("Catalog/Shelf", holders.get(0).getAsString()); //$NON-NLS-1$

        JsonObject unclustered = call("remove_from_cluster", "objectFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(unclustered.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("notClustered", unclustered.get("reason").getAsString()); //$NON-NLS-1$
    }

    /** A save the manager refused reaches the caller as the outcome's own code. */
    @Test
    public void aRefusedSaveAnswersTheOutcomeCode()
    {
        manager.refuseSaves = true;
        JsonObject refused = create("Shelf", "Catalog", false);
        assertFalse(refused.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("writeFailed", refused.get("reason").getAsString()); //$NON-NLS-1$
        assertEquals(0, manager.store.getGroups().size());
    }

    /** A missing service is named on the read and on every write. */
    @Test
    public void aMissingServiceIsNamedOnEveryOperation()
    {
        manager = null;
        JsonObject read = call("get_clusters"); //$NON-NLS-1$
        assertFalse(read.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("serviceUnavailable", read.get("reason").getAsString()); //$NON-NLS-1$

        JsonObject write = create("Shelf", "Catalog", false);
        assertEquals("serviceUnavailable", write.get("reason").getAsString()); //$NON-NLS-1$
    }

    /** A project that is not there is refused by name, not by a stack trace. */
    @Test
    public void aMissingProjectIsNamed()
    {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("operation", "get_clusters"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "no-such-project-aiedt-cluster-test"); //$NON-NLS-1$
        JsonObject answer = JsonParser.parseString(facade.execute(params)).getAsJsonObject();
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("projectNotFound", answer.get("reason").getAsString()); //$NON-NLS-1$
    }

    /** An unknown operation is refused with the catalog beside it. */
    @Test
    public void anUnknownOperationIsRefusedWithTheCatalog()
    {
        JsonObject answer = call("rename_cluster"); //$NON-NLS-1$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString().contains("Unknown operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("error").getAsString().contains("update_cluster")); //$NON-NLS-1$
    }

    /** The help answers the catalog, one operation's parameters, and a search. */
    @Test
    public void theHelpAnswersTheCatalogTheTopicAndTheSearch()
    {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String catalog = facade.execute(params);
        assertTrue(catalog.contains("# cluster_admin - operations")); //$NON-NLS-1$
        assertTrue(catalog.contains("**get_clusters**")); //$NON-NLS-1$

        params.put("topic", "delete_cluster"); //$NON-NLS-1$ //$NON-NLS-2$
        String topic = facade.execute(params);
        assertFalse(topic, topic.contains("Unknown topic")); //$NON-NLS-1$

        params.remove("topic"); //$NON-NLS-1$
        params.put("find", "nested"); //$NON-NLS-1$ //$NON-NLS-2$
        String found = facade.execute(params);
        assertFalse(found, found.contains("Unknown topic")); //$NON-NLS-1$
    }

    /** A manager over a real store whose saves can be refused on demand. */
    private static final class RecordingManager
        implements IClusterManager
    {
        final ClusterStore store = new ClusterStore();

        boolean refuseSaves;

        @Override
        public ClusterStore getClusterStorage(IProject project)
        {
            return store;
        }

        @Override
        public List<Cluster> getClustersAtPath(IProject project, String path)
        {
            return store.getClustersAtPath(path);
        }

        @Override
        public List<Cluster> getAllClusters(IProject project)
        {
            return store.getGroups();
        }

        @Override
        public ClusterWriteOutcome createCluster(IProject project, String name, String path,
            String description)
        {
            String fullPath = path == null || path.isEmpty() ? name : path + "/" + name; //$NON-NLS-1$
            if (store.getClusterByFullPath(fullPath) != null)
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.CLUSTER_EXISTS);
            }
            Cluster cluster = new Cluster(name, path);
            cluster.setDescription(description);
            cluster.setOrder(nextOrder(path));
            if (saveRefused())
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.refused(ClusterSaveOutcome.WRITE_FAILED));
            }
            store.addCluster(cluster);
            return ClusterWriteOutcome.of(ClusterSaveOutcome.ok(), cluster);
        }

        @Override
        public ClusterWriteOutcome renameCluster(IProject project, String oldFullPath,
            String newName)
        {
            return updateCluster(project, oldFullPath, newName, null);
        }

        @Override
        public ClusterWriteOutcome updateCluster(IProject project, String oldFullPath,
            String newName, String description)
        {
            Cluster existing = store.getClusterByFullPath(oldFullPath);
            if (existing == null)
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.CLUSTER_NOT_FOUND);
            }
            boolean sameName = Objects.equals(existing.getName(), newName);
            boolean sameDescription = Objects.equals(existing.getDescription(), description);
            if (sameName && sameDescription)
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.noChange());
            }
            String newPath = existing.getPath() == null || existing.getPath().isEmpty()
                ? newName
                : existing.getPath() + "/" + newName; //$NON-NLS-1$
            if (store.getClusterByFullPath(newPath) != null)
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.NAME_TAKEN);
            }
            if (saveRefused())
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.refused(ClusterSaveOutcome.WRITE_FAILED));
            }
            store.updateCluster(oldFullPath, newName, description);
            return ClusterWriteOutcome.of(ClusterSaveOutcome.ok());
        }

        @Override
        public ClusterWriteOutcome deleteCluster(IProject project, String fullPath)
        {
            if (store.getClusterByFullPath(fullPath) == null)
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.CLUSTER_NOT_FOUND);
            }
            if (saveRefused())
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.refused(ClusterSaveOutcome.WRITE_FAILED));
            }
            store.removeCluster(fullPath);
            return ClusterWriteOutcome.of(ClusterSaveOutcome.ok());
        }

        @Override
        public ClusterWriteOutcome addObjectToCluster(IProject project, String objectFqn,
            String clusterFullPath)
        {
            Cluster target = store.getClusterByFullPath(clusterFullPath);
            if (target == null)
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.CLUSTER_NOT_FOUND);
            }
            if (store.findClusterForObject(objectFqn) == target)
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.noChange());
            }
            if (saveRefused())
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.refused(ClusterSaveOutcome.WRITE_FAILED));
            }
            store.moveObjectToCluster(objectFqn, clusterFullPath);
            return ClusterWriteOutcome.of(ClusterSaveOutcome.ok());
        }

        @Override
        public ClusterWriteOutcome removeObjectFromCluster(IProject project, String objectFqn)
        {
            if (store.findClusterForObject(objectFqn) == null)
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.noChange());
            }
            if (saveRefused())
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.refused(ClusterSaveOutcome.WRITE_FAILED));
            }
            store.removeObjectFromAllClusters(objectFqn);
            return ClusterWriteOutcome.of(ClusterSaveOutcome.ok());
        }

        @Override
        public Cluster findClusterForObject(IProject project, String objectFqn)
        {
            return store.findClusterForObject(objectFqn);
        }

        @Override
        public Set<String> getClusteredObjectsAtPath(IProject project, String path)
        {
            return store.getClusteredObjectsAtPath(path);
        }

        @Override
        public boolean hasClustersAtPath(IProject project, String path)
        {
            return store.hasClustersAtPath(path);
        }

        @Override
        public boolean holdsObjectOrDescendant(IProject project, String objectFqn)
        {
            return store.holdsObjectOrDescendant(objectFqn);
        }

        @Override
        public void refresh(IProject project)
        {
            // nothing cached
        }

        @Override
        public ClusterWriteOutcome renameObject(IProject project, String oldFqn, String newFqn)
        {
            boolean renamed = store.renameObject(oldFqn, newFqn);
            return ClusterWriteOutcome.of(renamed ? ClusterSaveOutcome.ok()
                : ClusterSaveOutcome.noChange());
        }

        @Override
        public ClusterWriteOutcome removeObject(IProject project, String objectFqn)
        {
            boolean removed = store.removeObjectFromAllClusters(objectFqn);
            return ClusterWriteOutcome.of(removed ? ClusterSaveOutcome.ok()
                : ClusterSaveOutcome.noChange());
        }

        @Override
        public void addClusterChangeListener(IClusterChangeObserver listener)
        {
            // no listeners in a recording manager
        }

        @Override
        public void removeClusterChangeListener(IClusterChangeObserver listener)
        {
            // no listeners in a recording manager
        }

        /**
         * @return whether the next save is refused
         */
        private boolean saveRefused()
        {
            return refuseSaves;
        }

        /**
         * The order a new cluster at a path takes: one past the highest already there.
         *
         * @param path the collection path
         * @return the next order
         */
        private int nextOrder(String path)
        {
            int next = 0;
            for (Cluster cluster : store.getClustersAtPath(path))
            {
                next = Math.max(next, cluster.getOrder() + 1);
            }
            return next;
        }
    }
}
