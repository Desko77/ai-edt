/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The session's own reading of the form for the editor's drawing and its property panel: the
 * version, the elements with the branch that draws their kind and the properties that kind was
 * measured on, and the pages and handlers beside them. An unreadable container answers a snapshot
 * that says why, and the owner's words answer {@code null} where no model is loaded - this probe
 * runs without one, the way a plain unit test does.
 */
public class ASnapshotNamesTheBranchesAndThePropertiesTest
{
    private static final String PROJECT = "AiEdtOformSessionSnapshotProbe"; //$NON-NLS-1$

    private static SessionProbe probe;

    @BeforeClass
    public static void aProjectWithOneItemForm() throws Exception
    {
        probe = SessionProbe.with(PROJECT, SessionProbe.fixture("fixtures/item-form-v27.form"), "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @AfterClass
    public static void theProjectGoes()
    {
        if (probe != null)
        {
            probe.dispose();
        }
    }

    /** The snapshot names the version, the elements with their branch, and the properties of their kind. */
    @Test
    public void theSnapshotNamesBranchesAndProperties() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormSnapshot snapshot = session.snapshot();
                assertEquals("27", snapshot.version); //$NON-NLS-1$
                assertTrue(snapshot.versionSupported);
                assertNull(snapshot.problem);
                assertEquals("the snapshot counts what the reader does not recognise", //$NON-NLS-1$
                    unreadOfTheFixture(), snapshot.unreadRecords);
                assertTrue("the form holds elements", snapshot.allElements.size() >= 6); //$NON-NLS-1$
                assertTrue("the form holds pages", snapshot.pages.size() >= 2); //$NON-NLS-1$
                for (FormSnapshot.Element element : snapshot.allElements)
                {
                    assertNotNull(element.kind + " has a render branch", element.renderBranch); //$NON-NLS-1$
                    assertTrue(element.kind + " names its applicable properties", //$NON-NLS-1$
                        element.applicableProperties.contains("visible") //$NON-NLS-1$
                            && element.applicableProperties.contains("tooltip")); //$NON-NLS-1$
                }

                FormSnapshot.Element aLabel = named(snapshot, "НадписьНаименование"); //$NON-NLS-1$
                assertNotNull(aLabel);
                assertEquals(RenderKinds.LABEL, aLabel.renderBranch);
                assertTrue(aLabel.applicableProperties.contains("caption")); //$NON-NLS-1$
                assertTrue(aLabel.applicableProperties.contains("font")); //$NON-NLS-1$
                assertFalse("a label has no read-only", aLabel.applicableProperties.contains("readOnly")); //$NON-NLS-1$ //$NON-NLS-2$

                FormSnapshot.Element aField = named(snapshot, "Наименование"); //$NON-NLS-1$
                assertNotNull(aField);
                assertEquals(RenderKinds.INPUT_FIELD, aField.renderBranch);
                assertTrue(aField.applicableProperties.contains("readOnly")); //$NON-NLS-1$
                assertTrue(aField.applicableProperties.contains("handlers")); //$NON-NLS-1$

                assertEquals("Catalog", snapshot.ownerKind); //$NON-NLS-1$
                assertFalse("the kinds for creation are named", session.kindsForCreation().isEmpty()); //$NON-NLS-1$
                assertTrue(session.formEvents().contains("ПриОткрытии")); //$NON-NLS-1$
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** An element's events come from its kind, and the data paths answer what the form resolves. */
    @Test
    public void theReadingsOfTheSession() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(session.elementEventsOf("Table").contains("ПриАктивизацииСтроки")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(List.of("Нажатие"), session.elementEventsOf("Label")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(session.elementEventsOf("Label"), session.elementEventsOf("label")); //$NON-NLS-1$ //$NON-NLS-2$

                assertTrue("a kind that shows no data names no path", session.dataPathsFor("Chart").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
                assertFalse("a field's data paths name the form's attributes", //$NON-NLS-1$
                    session.dataPathsFor("Field").isEmpty()); //$NON-NLS-1$

                OwnerWords owner = session.ownerAttributes();
                assertNull("no model is loaded in this probe, so no owner words", owner); //$NON-NLS-1$
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** An unreadable container answers a snapshot that says why and holds no elements. */
    @Test
    public void anUnreadableContainerAnswersItsProblem() throws Exception
    {
        try
        {
            probe.anotherWriterWrites("not a container".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(session.readOnly());
                FormSnapshot snapshot = session.snapshot();
                assertNotNull(snapshot.problem);
                assertTrue("the snapshot holds no elements", snapshot.allElements.isEmpty()); //$NON-NLS-1$
                assertFalse(snapshot.versionSupported);
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** @return the unread-record count the reader itself gives the fixture */
    private static int unreadOfTheFixture()
    {
        try
        {
            int unread = 0;
            var layout = ru.aiedt.mcp.oform.layout.LayoutReader.read(
                ru.aiedt.mcp.oform.BraceTree.parse(
                    SessionProbe.fixture("fixtures/item-form-v27.form")), "Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
            for (var entry : layout.unread.entrySet())
            {
                unread += entry.getValue().intValue();
            }
            return unread;
        }
        catch (java.io.IOException e)
        {
            throw new AssertionError(e);
        }
    }

    private static FormSnapshot.Element named(FormSnapshot snapshot, String name)
    {
        for (FormSnapshot.Element element : snapshot.allElements)
        {
            if (element.name != null && element.name.equalsIgnoreCase(name))
            {
                return element;
            }
        }
        return null;
    }
}
