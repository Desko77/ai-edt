/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit coverage for the marker domain model: {@link Marker} bean semantics (name-keyed identity,
 * default color) and the {@link MarkerStore} operations that back the marker service - defining,
 * assigning, ordering, renaming and removing markers and objects.
 */
public class MarkerModelTest
{
    private static final String GRAY = "#808080"; //$NON-NLS-1$

    private MarkerStore storage;

    @Before
    public void freshStorage()
    {
        storage = new MarkerStore();
    }

    // ------ Marker construction ------

    @Test
    public void nameOnlyConstructorAppliesDefaultColorAndBlankDescription()
    {
        Marker marker = new Marker("alpha");
        assertEquals("alpha", marker.getName());
        assertEquals(GRAY, marker.getColor());
        assertEquals("", marker.getDescription());
    }

    @Test
    public void nameAndColorConstructorLeavesDescriptionBlank()
    {
        Marker marker = new Marker("beta", "#FF0000");
        assertEquals("beta", marker.getName());
        assertEquals("#FF0000", marker.getColor());
        assertEquals("", marker.getDescription());
    }

    @Test
    public void fullConstructorKeepsEveryArgument()
    {
        Marker marker = new Marker("gamma", "#00FF00", "a note");
        assertEquals("gamma", marker.getName());
        assertEquals("#00FF00", marker.getColor());
        assertEquals("a note", marker.getDescription());
    }

    @Test
    public void noArgConstructorProducesBlankGrayMarker()
    {
        Marker marker = new Marker();
        assertEquals("", marker.getName());
        assertEquals(GRAY, marker.getColor());
        assertEquals("", marker.getDescription());
    }

    @Test
    public void nullColorFallsBackToGray()
    {
        assertEquals(GRAY, new Marker("x", null, "d").getColor());
    }

    @Test
    public void nullDescriptionBecomesEmpty()
    {
        assertEquals("", new Marker("x", "#FFFFFF", null).getDescription());
    }

    @Test(expected = NullPointerException.class)
    public void nullNameIsRejected()
    {
        new Marker(null);
    }

    // ------ Marker mutators ------

    @Test
    public void settersUpdateEachField()
    {
        Marker marker = new Marker("orig");
        marker.setName("changed");
        marker.setColor("#123456");
        marker.setDescription("desc");
        assertEquals("changed", marker.getName());
        assertEquals("#123456", marker.getColor());
        assertEquals("desc", marker.getDescription());
    }

    // ------ Marker identity (keyed on name only) ------

    @Test
    public void markersSharingANameAreEqualRegardlessOfColor()
    {
        Marker red = new Marker("same", "#FF0000");
        Marker green = new Marker("same", "#00FF00");
        assertEquals(red, green);
        assertEquals(red.hashCode(), green.hashCode());
    }

    @Test
    public void differentNamesAreNotEqual()
    {
        assertNotEquals(new Marker("one"), new Marker("two"));
    }

    @Test
    public void markerEqualsItself()
    {
        Marker marker = new Marker("z");
        assertEquals(marker, marker);
    }

    @Test
    public void markerIsNotEqualToNull()
    {
        assertNotEquals(new Marker("z"), null);
    }

    @Test
    public void markerIsNotEqualToUnrelatedType()
    {
        assertNotEquals(new Marker("z"), "z");
    }

    @Test
    public void toStringYieldsTheName()
    {
        assertEquals("visible", new Marker("visible").toString());
    }

    // ------ MarkerStore: initial state ------

    @Test
    public void newStorageHasNoMarkersAndNoAssignments()
    {
        assertNotNull(storage.getTags());
        assertTrue(storage.getTags().isEmpty());
        assertNotNull(storage.getAssignments());
        assertTrue(storage.getAssignments().isEmpty());
    }

    // ------ MarkerStore: defining markers ------

    @Test
    public void addMarkerInsertsIntoTheList()
    {
        assertTrue(storage.addMarker(new Marker("first", "#FF0000")));
        assertEquals(1, storage.getTags().size());
    }

    @Test
    public void addMarkerRefusesADuplicateName()
    {
        storage.addMarker(new Marker("dup"));
        assertFalse(storage.addMarker(new Marker("dup")));
        assertEquals(1, storage.getTags().size());
    }

    @Test
    public void getMarkerByNameReturnsTheStoredInstance()
    {
        Marker marker = new Marker("target");
        storage.addMarker(marker);
        Marker found = storage.getMarkerByName("target");
        assertNotNull(found);
        assertSame(marker, found);
    }

    @Test
    public void getMarkerByNameReturnsNullForUnknownName()
    {
        assertNull(storage.getMarkerByName("nope"));
    }

    @Test
    public void removeMarkerDeletesItFromTheList()
    {
        storage.addMarker(new Marker("kill"));
        assertTrue(storage.removeMarker("kill"));
        assertNull(storage.getMarkerByName("kill"));
    }

    @Test
    public void removeMarkerReportsFalseWhenAbsent()
    {
        assertFalse(storage.removeMarker("ghost"));
    }

    @Test
    public void removeMarkerScrubsItFromEveryAssignment()
    {
        storage.addMarker(new Marker("cleanup"));
        storage.assignMarker("Catalog.A", "cleanup");
        storage.removeMarker("cleanup");
        assertFalse(storage.getMarkerNames("Catalog.A").contains("cleanup"));
    }

    // ------ MarkerStore: assignment ------

    @Test
    public void assignMarkerLinksMarkerToObject()
    {
        storage.addMarker(new Marker("bug"));
        assertTrue(storage.assignMarker("Document.Order", "bug"));
        assertTrue(storage.getMarkerNames("Document.Order").contains("bug"));
    }

    @Test
    public void assignMarkerFailsForUndefinedMarker()
    {
        assertFalse(storage.assignMarker("Document.Order", "missing"));
    }

    @Test
    public void assignMarkerIsIdempotentPerObject()
    {
        storage.addMarker(new Marker("bug"));
        storage.assignMarker("Doc.A", "bug");
        assertFalse(storage.assignMarker("Doc.A", "bug"));
    }

    @Test
    public void unassignMarkerRemovesTheLink()
    {
        storage.addMarker(new Marker("temp"));
        storage.assignMarker("Obj.X", "temp");
        assertTrue(storage.unassignMarker("Obj.X", "temp"));
        assertTrue(storage.getMarkerNames("Obj.X").isEmpty());
    }

    @Test
    public void unassignMarkerDropsTheObjectEntryWhenLastMarkerGoes()
    {
        storage.addMarker(new Marker("only"));
        storage.assignMarker("Obj.Y", "only");
        storage.unassignMarker("Obj.Y", "only");
        assertFalse(storage.getAssignments().containsKey("Obj.Y"));
    }

    @Test
    public void unassignMarkerReturnsFalseWhenNothingAssigned()
    {
        assertFalse(storage.unassignMarker("Obj.Z", "none"));
    }

    @Test
    public void getObjectMarkersResolvesNamesToMarkerInstances()
    {
        Marker bug = new Marker("bug", "#FF0000");
        Marker feat = new Marker("feat", "#00FF00");
        storage.addMarker(bug);
        storage.addMarker(feat);
        storage.assignMarker("Doc.A", "bug");
        storage.assignMarker("Doc.A", "feat");
        Set<Marker> markers = storage.getObjectMarkers("Doc.A");
        assertEquals(2, markers.size());
        assertTrue(markers.contains(bug));
        assertTrue(markers.contains(feat));
    }

    @Test
    public void getObjectMarkersIsEmptyForUnknownObject()
    {
        assertTrue(storage.getObjectMarkers("Unknown.X").isEmpty());
    }

    @Test
    public void getMarkerNamesReturnsACopyOfAssignedNames()
    {
        storage.addMarker(new Marker("a"));
        storage.addMarker(new Marker("b"));
        storage.assignMarker("Obj", "a");
        storage.assignMarker("Obj", "b");
        Set<String> names = storage.getMarkerNames("Obj");
        assertEquals(2, names.size());
        assertTrue(names.contains("a"));
        assertTrue(names.contains("b"));
    }

    @Test
    public void getMarkerNamesIsEmptyForUnknownObject()
    {
        assertTrue(storage.getMarkerNames("NoObj").isEmpty());
    }

    @Test
    public void getObjectsByMarkerListsEveryObjectCarryingIt()
    {
        storage.addMarker(new Marker("shared"));
        storage.assignMarker("Catalog.A", "shared");
        storage.assignMarker("Doc.B", "shared");
        Set<String> objects = storage.getObjectsByMarker("shared");
        assertEquals(2, objects.size());
        assertTrue(objects.contains("Catalog.A"));
        assertTrue(objects.contains("Doc.B"));
    }

    @Test
    public void getObjectsByMarkerIsEmptyWhenMarkerIsUnused()
    {
        assertTrue(storage.getObjectsByMarker("unused").isEmpty());
    }

    // ------ MarkerStore: object lifecycle ------

    @Test
    public void renameObjectCarriesAssignmentsToNewFqn()
    {
        storage.addMarker(new Marker("t"));
        storage.assignMarker("Old.Name", "t");
        assertTrue(storage.renameObject("Old.Name", "New.Name"));
        assertTrue(storage.getMarkerNames("New.Name").contains("t"));
        assertTrue(storage.getMarkerNames("Old.Name").isEmpty());
    }

    @Test
    public void renameObjectReturnsFalseWhenOldFqnIsAbsent()
    {
        assertFalse(storage.renameObject("Missing", "New"));
    }

    @Test
    public void removeObjectWipesItsAssignments()
    {
        storage.addMarker(new Marker("t"));
        storage.assignMarker("Obj.Del", "t");
        assertTrue(storage.removeObject("Obj.Del"));
        assertTrue(storage.getMarkerNames("Obj.Del").isEmpty());
    }

    @Test
    public void removeObjectReturnsFalseWhenAbsent()
    {
        assertFalse(storage.removeObject("Not.Found"));
    }

    // ------ MarkerStore: ordering ------

    @Test
    public void moveMarkerUpSwapsWithPredecessor()
    {
        storage.addMarker(new Marker("first"));
        storage.addMarker(new Marker("second"));
        storage.addMarker(new Marker("third"));
        assertTrue(storage.moveMarkerUp("second"));
        assertEquals(0, storage.getMarkerIndex("second"));
        assertEquals(1, storage.getMarkerIndex("first"));
    }

    @Test
    public void moveMarkerUpStaysAtTop()
    {
        storage.addMarker(new Marker("top"));
        assertFalse(storage.moveMarkerUp("top"));
    }

    @Test
    public void moveMarkerUpFailsForUnknownMarker()
    {
        assertFalse(storage.moveMarkerUp("missing"));
    }

    @Test
    public void moveMarkerDownSwapsWithSuccessor()
    {
        storage.addMarker(new Marker("first"));
        storage.addMarker(new Marker("second"));
        storage.addMarker(new Marker("third"));
        assertTrue(storage.moveMarkerDown("second"));
        assertEquals(2, storage.getMarkerIndex("second"));
        assertEquals(1, storage.getMarkerIndex("third"));
    }

    @Test
    public void moveMarkerDownStaysAtBottom()
    {
        storage.addMarker(new Marker("bot"));
        assertFalse(storage.moveMarkerDown("bot"));
    }

    @Test
    public void moveMarkerDownFailsForUnknownMarker()
    {
        assertFalse(storage.moveMarkerDown("missing"));
    }

    @Test
    public void getMarkerIndexReportsPosition()
    {
        storage.addMarker(new Marker("a"));
        storage.addMarker(new Marker("b"));
        storage.addMarker(new Marker("c"));
        assertEquals(0, storage.getMarkerIndex("a"));
        assertEquals(1, storage.getMarkerIndex("b"));
        assertEquals(2, storage.getMarkerIndex("c"));
        assertEquals(-1, storage.getMarkerIndex("z"));
    }

    // ------ MarkerStore: bulk setters ------

    @Test
    public void setMarkersReplacesTheList()
    {
        storage.setTags(Arrays.asList(new Marker("a"), new Marker("b")));
        assertEquals(2, storage.getTags().size());
    }

    @Test
    public void setMarkersTreatsNullAsEmpty()
    {
        storage.addMarker(new Marker("x"));
        storage.setTags(null);
        assertNotNull(storage.getTags());
        assertTrue(storage.getTags().isEmpty());
    }

    @Test
    public void setAssignmentsReplacesTheMap()
    {
        Map<String, List<String>> map = new HashMap<>();
        map.put("Obj.A", List.of("marker1"));
        storage.setAssignments(map);
        assertEquals(1, storage.getAssignments().size());
    }

    @Test
    public void setAssignmentsTreatsNullAsEmpty()
    {
        storage.setAssignments(null);
        assertNotNull(storage.getAssignments());
        assertTrue(storage.getAssignments().isEmpty());
    }
}
