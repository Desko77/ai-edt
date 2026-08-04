/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit coverage for the cluster domain model: {@link Cluster} bean semantics (path-and-name identity,
 * defensive child copies, in-place child mutators) and the {@link ClusterStore} queries and edits -
 * lookup, ordered path listing, rename cascade to nested clusters, and moving or renaming objects
 * across every cluster that holds them.
 */
public class ClusterModelTest
{
    private ClusterStore storage;

    @Before
    public void freshStorage()
    {
        storage = new ClusterStore();
    }

    // ------ Cluster construction ------

    @Test
    public void noArgConstructorLeavesEverythingBlank()
    {
        Cluster cluster = new Cluster();
        assertNull(cluster.getName());
        assertNull(cluster.getPath());
        assertNull(cluster.getDescription());
        assertEquals(0, cluster.getOrder());
        assertNotNull(cluster.getChildren());
        assertTrue(cluster.getChildren().isEmpty());
    }

    @Test
    public void namePathConstructorSetsNameAndPath()
    {
        Cluster cluster = new Cluster("Server", "CommonModules");
        assertEquals("Server", cluster.getName());
        assertEquals("CommonModules", cluster.getPath());
        assertEquals(0, cluster.getOrder());
        assertTrue(cluster.isEmpty());
    }

    // ------ Cluster full path ------

    @Test
    public void fullPathJoinsPathAndNameWithSlash()
    {
        assertEquals("CommonModules/Server", new Cluster("Server", "CommonModules").getFullPath());
    }

    @Test
    public void fullPathIsJustTheNameWhenPathIsNull()
    {
        assertEquals("Root", new Cluster("Root", null).getFullPath());
    }

    @Test
    public void fullPathIsJustTheNameWhenPathIsEmpty()
    {
        assertEquals("Root", new Cluster("Root", "").getFullPath());
    }

    // ------ Cluster mutators ------

    @Test
    public void settersUpdateFieldsIndividually()
    {
        Cluster cluster = new Cluster();
        cluster.setName("MyCluster");
        cluster.setPath("Catalogs");
        cluster.setDescription("a cluster");
        cluster.setOrder(5);
        cluster.setChildren(Arrays.asList("Catalog.A", "Catalog.B"));
        assertEquals("MyCluster", cluster.getName());
        assertEquals("Catalogs", cluster.getPath());
        assertEquals("a cluster", cluster.getDescription());
        assertEquals(5, cluster.getOrder());
        assertEquals(2, cluster.getChildren().size());
    }

    @Test
    public void setChildrenTreatsNullAsEmpty()
    {
        Cluster cluster = new Cluster();
        cluster.setChildren(null);
        assertNotNull(cluster.getChildren());
        assertTrue(cluster.getChildren().isEmpty());
    }

    @Test
    public void getChildrenReturnsADefensiveCopy()
    {
        Cluster cluster = new Cluster("G", "P");
        cluster.addChild("Catalog.A");
        List<String> snapshot = cluster.getChildren();
        snapshot.add("Catalog.B");
        assertEquals(1, cluster.getChildren().size());
    }

    // ------ Cluster child mutators ------

    @Test
    public void addChildRegistersANewObject()
    {
        Cluster cluster = new Cluster("G", "P");
        assertTrue(cluster.addChild("CommonModule.MyModule"));
        assertTrue(cluster.containsChild("CommonModule.MyModule"));
        assertEquals(1, cluster.getChildren().size());
    }

    @Test
    public void addChildRefusesDuplicates()
    {
        Cluster cluster = new Cluster("G", "P");
        assertTrue(cluster.addChild("Catalog.A"));
        assertFalse(cluster.addChild("Catalog.A"));
        assertEquals(1, cluster.getChildren().size());
    }

    @Test
    public void removeChildTakesAnObjectOut()
    {
        Cluster cluster = new Cluster("G", "P");
        cluster.addChild("Catalog.A");
        assertTrue(cluster.removeChild("Catalog.A"));
        assertFalse(cluster.containsChild("Catalog.A"));
    }

    @Test
    public void removeChildReturnsFalseWhenAbsent()
    {
        assertFalse(new Cluster("G", "P").removeChild("Catalog.X"));
    }

    @Test
    public void containsChildReflectsMembership()
    {
        Cluster cluster = new Cluster("G", "P");
        cluster.addChild("Doc.Order");
        assertTrue(cluster.containsChild("Doc.Order"));
        assertFalse(cluster.containsChild("Doc.Invoice"));
    }

    @Test
    public void isEmptyTracksChildCount()
    {
        Cluster cluster = new Cluster("G", "P");
        assertTrue(cluster.isEmpty());
        cluster.addChild("X");
        assertFalse(cluster.isEmpty());
    }

    @Test
    public void renameChildSwapsTheFqnInPlace()
    {
        Cluster cluster = new Cluster("G", "P");
        cluster.addChild("CommonModule.Old");
        assertTrue(cluster.renameChild("CommonModule.Old", "CommonModule.New"));
        assertTrue(cluster.containsChild("CommonModule.New"));
        assertFalse(cluster.containsChild("CommonModule.Old"));
    }

    @Test
    public void renameChildReturnsFalseWhenAbsent()
    {
        Cluster cluster = new Cluster("G", "P");
        cluster.addChild("Catalog.A");
        assertFalse(cluster.renameChild("Missing.Fqn", "New.Fqn"));
        assertTrue(cluster.containsChild("Catalog.A"));
    }

    @Test
    public void renameChildKeepsThePosition()
    {
        Cluster cluster = new Cluster("G", "P");
        cluster.addChild("First");
        cluster.addChild("Second");
        cluster.addChild("Third");
        assertTrue(cluster.renameChild("Second", "Renamed"));
        List<String> children = cluster.getChildren();
        assertEquals("First", children.get(0));
        assertEquals("Renamed", children.get(1));
        assertEquals("Third", children.get(2));
    }

    // ------ Cluster identity ------

    @Test
    public void clustersEqualByPathAndName()
    {
        Cluster a = new Cluster("Name", "Path");
        Cluster b = new Cluster("Name", "Path");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void clustersDifferWhenPathDiffers()
    {
        assertNotEquals(new Cluster("Name", "Path1"), new Cluster("Name", "Path2"));
    }

    @Test
    public void toStringMentionsFullPathAndChildCount()
    {
        Cluster cluster = new Cluster("MyCluster", "CommonModules");
        cluster.addChild("A");
        String str = cluster.toString();
        assertTrue(str.contains("CommonModules/MyCluster"));
        assertTrue(str.contains("children=1"));
    }

    // ------ ClusterStore: initial state ------

    @Test
    public void newStorageIsEmpty()
    {
        assertTrue(storage.isEmpty());
        assertEquals(0, storage.getClusterCount());
        assertNotNull(storage.getGroups());
    }

    // ------ ClusterStore: add / lookup / remove ------

    @Test
    public void addClusterInsertsIt()
    {
        assertTrue(storage.addCluster(new Cluster("Server", "CommonModules")));
        assertEquals(1, storage.getClusterCount());
    }

    @Test
    public void addClusterRejectsDuplicateFullPath()
    {
        assertTrue(storage.addCluster(new Cluster("Server", "CommonModules")));
        assertFalse(storage.addCluster(new Cluster("Server", "CommonModules")));
        assertEquals(1, storage.getClusterCount());
    }

    @Test
    public void getClusterByFullPathReturnsTheRealInstance()
    {
        Cluster cluster = new Cluster("Server", "CommonModules");
        storage.addCluster(cluster);
        Cluster found = storage.getClusterByFullPath("CommonModules/Server");
        assertNotNull(found);
        assertSame(cluster, found);
    }

    @Test
    public void getClusterByFullPathReturnsNullWhenMissing()
    {
        assertNull(storage.getClusterByFullPath("No/Such"));
    }

    @Test
    public void removeClusterByFullPathDropsIt()
    {
        storage.addCluster(new Cluster("G", "P"));
        assertTrue(storage.removeCluster("P/G"));
        assertEquals(0, storage.getClusterCount());
    }

    @Test
    public void removeClusterReturnsFalseWhenMissing()
    {
        assertFalse(storage.removeCluster("X/Y"));
    }

    @Test
    public void getClustersReturnsACopy()
    {
        storage.addCluster(new Cluster("G", "P"));
        List<Cluster> snapshot = storage.getGroups();
        snapshot.clear();
        assertEquals(1, storage.getClusterCount());
    }

    @Test
    public void setClustersReplacesTheList()
    {
        storage.setGroups(Arrays.asList(new Cluster("A", "P"), new Cluster("B", "P")));
        assertEquals(2, storage.getClusterCount());
    }

    @Test
    public void setClustersTreatsNullAsEmpty()
    {
        storage.addCluster(new Cluster("G", "P"));
        storage.setGroups(null);
        assertEquals(0, storage.getClusterCount());
    }

    // ------ ClusterStore: path listing ------

    @Test
    public void getClustersAtPathSortsByOrderThenNameCaseInsensitively()
    {
        Cluster alpha = new Cluster("Alpha", "CommonModules");
        alpha.setOrder(2);
        Cluster beta = new Cluster("Beta", "CommonModules");
        beta.setOrder(1);
        Cluster other = new Cluster("Other", "Catalogs");
        storage.addCluster(alpha);
        storage.addCluster(beta);
        storage.addCluster(other);
        List<Cluster> atPath = storage.getClustersAtPath("CommonModules");
        assertEquals(2, atPath.size());
        assertEquals("Beta", atPath.get(0).getName());
        assertEquals("Alpha", atPath.get(1).getName());
    }

    @Test
    public void getClustersAtPathEmptyWhenNoneMatch()
    {
        assertTrue(storage.getClustersAtPath("Nowhere").isEmpty());
    }

    @Test
    public void getClustersAtPathNullMatchesRootClusters()
    {
        storage.addCluster(new Cluster("Root", null));
        assertEquals(1, storage.getClustersAtPath(null).size());
    }

    @Test
    public void hasClustersAtPathReflectsPresence()
    {
        storage.addCluster(new Cluster("G", "CommonModules"));
        assertTrue(storage.hasClustersAtPath("CommonModules"));
        assertFalse(storage.hasClustersAtPath("Catalogs"));
    }

    // ------ ClusterStore: rename + cascade ------

    @Test
    public void renameClusterChangesTheName()
    {
        storage.addCluster(new Cluster("OldName", "CommonModules"));
        assertTrue(storage.renameCluster("CommonModules/OldName", "NewName"));
        assertNotNull(storage.getClusterByFullPath("CommonModules/NewName"));
        assertNull(storage.getClusterByFullPath("CommonModules/OldName"));
    }

    @Test
    public void renameClusterRefusesAConflict()
    {
        storage.addCluster(new Cluster("A", "Path"));
        storage.addCluster(new Cluster("B", "Path"));
        assertFalse(storage.renameCluster("Path/A", "B"));
    }

    @Test
    public void renameClusterReturnsFalseWhenMissing()
    {
        assertFalse(storage.renameCluster("X/Y", "Z"));
    }

    @Test
    public void renameClusterRewiresNestedChildPaths()
    {
        storage.addCluster(new Cluster("Parent", "Root"));
        storage.addCluster(new Cluster("Child", "Root/Parent"));
        storage.renameCluster("Root/Parent", "NewParent");
        assertNotNull(storage.getClusterByFullPath("Root/NewParent/Child"));
    }

    @Test
    public void renameClusterLeavesSiblingSharingANamePrefixUntouched()
    {
        // Whole-segment matching: a descendant of "ParentX" must NOT be rewired when the sibling
        // "Parent" is renamed, even though the two names share a prefix.
        storage.addCluster(new Cluster("Parent", "Root"));
        storage.addCluster(new Cluster("Deep", "Root/ParentX"));
        storage.renameCluster("Root/Parent", "NewParent");
        assertNotNull(storage.getClusterByFullPath("Root/ParentX/Deep"));
    }

    // ------ ClusterStore: updateCluster ------

    @Test
    public void updateClusterRenamesAndSetsDescription()
    {
        storage.addCluster(new Cluster("G", "P"));
        assertTrue(storage.updateCluster("P/G", "NewG", "desc"));
        Cluster updated = storage.getClusterByFullPath("P/NewG");
        assertNotNull(updated);
        assertEquals("desc", updated.getDescription());
    }

    @Test
    public void updateClusterAppliesDescriptionEvenWhenNameStays()
    {
        Cluster g = new Cluster("G", "P");
        storage.addCluster(g);
        assertTrue(storage.updateCluster("P/G", "G", "new desc"));
        assertEquals("new desc", g.getDescription());
    }

    @Test
    public void updateClusterRefusesAConflict()
    {
        storage.addCluster(new Cluster("A", "P"));
        storage.addCluster(new Cluster("B", "P"));
        assertFalse(storage.updateCluster("P/A", "B", "whatever"));
    }

    // ------ ClusterStore: object location ------

    @Test
    public void findClusterForObjectReturnsHoldingCluster()
    {
        Cluster g = new Cluster("Server", "CommonModules");
        g.addChild("CommonModule.MyMod");
        storage.addCluster(g);
        Cluster found = storage.findClusterForObject("CommonModule.MyMod");
        assertNotNull(found);
        assertEquals("Server", found.getName());
    }

    @Test
    public void findClusterForObjectReturnsNullWhenUnclustered()
    {
        assertNull(storage.findClusterForObject("Catalog.Unknown"));
    }

    @Test
    public void moveObjectToClusterRelocatesIt()
    {
        Cluster from = new Cluster("G1", "P");
        Cluster to = new Cluster("G2", "P");
        from.addChild("Obj.A");
        storage.addCluster(from);
        storage.addCluster(to);
        assertTrue(storage.moveObjectToCluster("Obj.A", "P/G2"));
        assertFalse(from.containsChild("Obj.A"));
        assertTrue(to.containsChild("Obj.A"));
    }

    @Test
    public void moveObjectToClusterFailsWhenTargetMissing()
    {
        assertFalse(storage.moveObjectToCluster("Obj.A", "No/Such"));
    }

    @Test
    public void moveObjectToClusterReturnsFalseWhenAlreadyThere()
    {
        Cluster target = new Cluster("T", "P");
        target.addChild("Obj.A");
        storage.addCluster(target);
        assertFalse(storage.moveObjectToCluster("Obj.A", "P/T"));
    }

    @Test
    public void removeObjectFromAllClustersClearsEveryHolder()
    {
        Cluster g1 = new Cluster("G1", "P");
        Cluster g2 = new Cluster("G2", "P");
        g1.addChild("Obj.X");
        g2.addChild("Obj.X");
        storage.addCluster(g1);
        storage.addCluster(g2);
        assertTrue(storage.removeObjectFromAllClusters("Obj.X"));
        assertFalse(g1.containsChild("Obj.X"));
        assertFalse(g2.containsChild("Obj.X"));
    }

    @Test
    public void removeObjectFromAllClustersReturnsFalseWhenAbsent()
    {
        assertFalse(storage.removeObjectFromAllClusters("Obj.Missing"));
    }

    // ------ ClusterStore: aggregated object listing ------

    @Test
    public void getClusteredObjectsAtPathCollectsChildrenAtThatPath()
    {
        Cluster modules = new Cluster("G1", "CommonModules");
        modules.addChild("CommonModule.A");
        modules.addChild("CommonModule.B");
        Cluster catalogs = new Cluster("G2", "Catalogs");
        catalogs.addChild("Catalog.X");
        storage.addCluster(modules);
        storage.addCluster(catalogs);
        Set<String> objects = storage.getClusteredObjectsAtPath("CommonModules");
        assertTrue(objects.contains("CommonModule.A"));
        assertTrue(objects.contains("CommonModule.B"));
        assertFalse(objects.contains("Catalog.X"));
    }

    @Test
    public void getClusteredObjectsAtPathIncludesNestedClusters()
    {
        // Whole-segment nesting: a cluster below the queried path is also collected.
        Cluster top = new Cluster("Top", "CommonModules");
        top.addChild("CommonModule.Top");
        Cluster nested = new Cluster("Sub", "CommonModules/Sub");
        nested.addChild("CommonModule.Sub");
        storage.addCluster(top);
        storage.addCluster(nested);
        Set<String> objects = storage.getClusteredObjectsAtPath("CommonModules");
        assertTrue(objects.contains("CommonModule.Top"));
        assertTrue(objects.contains("CommonModule.Sub"));
    }

    @Test
    public void getClusteredObjectsAtPathExcludesPrefixSibling()
    {
        // CommonModulesExtra must NOT be collected when querying CommonModules.
        Cluster sibling = new Cluster("S", "CommonModulesExtra");
        sibling.addChild("Catalog.Sneaky");
        storage.addCluster(sibling);
        assertFalse(storage.getClusteredObjectsAtPath("CommonModules").contains("Catalog.Sneaky"));
    }

    // ------ ClusterStore: object rename across clusters ------

    @Test
    public void renameObjectCarriesThroughEveryHoldingCluster()
    {
        Cluster g = new Cluster("Server", "CommonModules");
        g.addChild("CommonModule.Old");
        g.addChild("CommonModule.Other");
        storage.addCluster(g);
        assertTrue(storage.renameObject("CommonModule.Old", "CommonModule.New"));
        Cluster found = storage.findClusterForObject("CommonModule.New");
        assertNotNull(found);
        assertEquals("Server", found.getName());
        assertNull(storage.findClusterForObject("CommonModule.Old"));
        assertTrue(found.containsChild("CommonModule.Other"));
    }

    @Test
    public void renameObjectSpansMultipleClusters()
    {
        Cluster g1 = new Cluster("G1", "P");
        Cluster g2 = new Cluster("G2", "P");
        g1.addChild("Obj.Old");
        g2.addChild("Obj.Old");
        storage.addCluster(g1);
        storage.addCluster(g2);
        assertTrue(storage.renameObject("Obj.Old", "Obj.New"));
        assertTrue(g1.containsChild("Obj.New"));
        assertTrue(g2.containsChild("Obj.New"));
        assertFalse(g1.containsChild("Obj.Old"));
        assertFalse(g2.containsChild("Obj.Old"));
    }

    @Test
    public void renameObjectReturnsFalseWhenNotHeld()
    {
        assertFalse(storage.renameObject("Missing", "NewName"));
    }
}
