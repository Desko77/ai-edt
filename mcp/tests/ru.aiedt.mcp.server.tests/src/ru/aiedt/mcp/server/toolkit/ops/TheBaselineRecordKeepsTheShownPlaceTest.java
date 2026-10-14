/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Baseline;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diagnostic;

/**
 * A baseline record carries the place its finding is shown at beside the reference it is
 * identified by.
 * <p>
 * A plain Eclipse marker whose line stands outside its location presents itself as
 * {@code path:line} while its reference is the path alone. The record held the reference only, so
 * the recorded finding lost the line it sat on, a query naming the line filtered the marker
 * standing now but not the record of the same finding, and that finding came back as new and again
 * as new on every comparison.
 * </p>
 */
public class TheBaselineRecordKeepsTheShownPlaceTest
{
    private static final String PATH = "/Project/src/CommonModules/Common/Module.bsl"; //$NON-NLS-1$

    private static final String MESSAGE = "The import X is never used"; //$NON-NLS-1$

    private static final String SOURCE = "org.eclipse.core.resources.problem"; //$NON-NLS-1$

    private Path root;

    @Before
    public void makeRoot() throws IOException
    {
        root = Files.createTempDirectory("baseline-shown-place-test"); //$NON-NLS-1$
    }

    @After
    public void dropRoot() throws IOException
    {
        deleteTree(root);
    }

    private static void deleteTree(Path dir) throws IOException
    {
        if (!Files.exists(dir))
        {
            return;
        }
        try (var stream = Files.walk(dir))
        {
            var paths = stream.sorted(java.util.Comparator.reverseOrder());
            for (Path path : paths.toArray(Path[]::new))
            {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * The collected row the reader builds for a plain Eclipse marker: the same two helpers the
     * marker pass reads its place and its reference from.
     *
     * @param location the marker's location attribute, or an empty string
     * @param line the line the marker sits on
     * @return the row
     */
    private static ProjectProblemsReader.ErrorInfo markerRow(String location, int line)
    {
        return new ProjectProblemsReader.ErrorInfo(SOURCE, null, false, MESSAGE,
            ProjectProblemsReader.eclipsePresentation(location, PATH, line), "ERRORS", line, -1, -1, //$NON-NLS-1$
            null, ProjectProblemsReader.eclipseIdentityRef(location, PATH));
    }

    /**
     * The records the current side of a comparison is made of, for a marker on the given line.
     *
     * @param line the line the marker sits on
     * @return the collapsed records
     */
    private static List<Diagnostic> currentSideAt(int line)
    {
        return ProjectProblemsReader.collapseErrors(Collections.singletonList(markerRow("", line))); //$NON-NLS-1$
    }

    /**
     * A marker without a location records both its reference and the place it is shown at: the
     * reference alone would drop the line, and the place alone would make a finding that moved a
     * different finding.
     */
    @Test
    public void aMarkerWithoutALocationRecordsBothItsReferenceAndItsPlace()
    {
        List<Diagnostic> records = currentSideAt(42);

        assertEquals(1, records.size());
        assertEquals(PATH, records.get(0).objectRef);
        assertEquals(PATH + ":42", records.get(0).objectPresentation); //$NON-NLS-1$
    }

    /**
     * A location the marker carries is both its place and its reference: the two fields say the
     * same thing, and the identity is unchanged.
     */
    @Test
    public void aCarriedLocationIsBothThePlaceAndTheReference()
    {
        assertEquals("line 5", ProjectProblemsReader.eclipsePresentation("line 5", PATH, 42)); //$NON-NLS-1$ //$NON-NLS-2$

        List<Diagnostic> records = ProjectProblemsReader.collapseErrors(
            Collections.singletonList(markerRow("line 5", 42))); //$NON-NLS-1$

        assertEquals("line 5", records.get(0).objectRef); //$NON-NLS-1$
        assertEquals("line 5", records.get(0).objectPresentation); //$NON-NLS-1$
    }

    /**
     * A line filter meets the recorded finding where it meets the marker standing now: the record
     * is kept by the frame, the subtraction leaves nothing, and the finding is neither new nor
     * gone.
     */
    @Test
    public void aLineFilterMeetsTheRecordItMeetsTheMarkerWith() throws IOException
    {
        List<Diagnostic> current = currentSideAt(42);
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), current); //$NON-NLS-1$

        String answer = ProjectProblemsReader.renderSinceBaseline(root, "Project", current, 1, "", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, ":42", null, false, null, 100, 5000); //$NON-NLS-1$

        assertTrue(answer, answer.contains("**resolvedSinceBaseline:** 0")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("# Nothing New Since Baseline")); //$NON-NLS-1$
    }

    /**
     * A finding the comparison names new is shown at the place it was collected from, line
     * included.
     */
    @Test
    public void aNewFindingIsShownAtItsPlace() throws IOException
    {
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Collections.emptyList());

        String answer = ProjectProblemsReader.renderSinceBaseline(root, "Project", currentSideAt(42), //$NON-NLS-1$
            1, "", null, null, null, null, false, null, 100, 5000); //$NON-NLS-1$

        assertTrue(answer, answer.contains(PATH + ":42")); //$NON-NLS-1$
    }

    /**
     * The place survives the file: a record written with one reads back with it, and the reference
     * it is identified by is untouched.
     */
    @Test
    public void thePlaceSurvivesTheFile() throws IOException
    {
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Collections.singletonList(new Diagnostic(PATH, "bsl-comment-length", MESSAGE, "ERRORS") //$NON-NLS-1$ //$NON-NLS-2$
                .shownAt(PATH + ":42"))); //$NON-NLS-1$

        Baseline read = DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$

        assertEquals(PATH, read.diagnostics.get(0).objectRef);
        assertEquals(PATH + ":42", read.diagnostics.get(0).objectPresentation); //$NON-NLS-1$
    }

    /**
     * A place equal to the reference is not written: the file carries the second field only where
     * the two differ, and a file without it reads with the place equal to the reference.
     */
    @Test
    public void aPlaceEqualToTheReferenceIsNotWritten() throws IOException
    {
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Collections.singletonList(new Diagnostic(PATH, "bsl-comment-length", MESSAGE, "ERRORS"))); //$NON-NLS-1$ //$NON-NLS-2$

        Path file = DiagnosticsBaselineStore.fileFor(root, "Project"); //$NON-NLS-1$
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("objectPresentation")); //$NON-NLS-1$

        Baseline read = DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$
        assertEquals(PATH, read.diagnostics.get(0).objectPresentation);
    }

    /**
     * A file written before the place was carried reads with the place equal to the reference -
     * what the report printed for that record then.
     */
    @Test
    public void aFileWithoutThePlaceReadsWithTheReference() throws IOException
    {
        Files.writeString(DiagnosticsBaselineStore.fileFor(root, "Project"), //$NON-NLS-1$
            "{\"formatVersion\":" + DiagnosticsBaselineStore.FORMAT_VERSION //$NON-NLS-1$
                + ",\"savedAt\":\"2026-01-01T00:00:00Z\",\"objects\":[],\"diagnostics\":[" //$NON-NLS-1$
                + "{\"object\":\"" + PATH + "\",\"check\":\"bsl-comment-length\"," //$NON-NLS-1$ //$NON-NLS-2$
                + "\"message\":\"" + MESSAGE + "\",\"count\":1}]}", //$NON-NLS-1$ //$NON-NLS-2$
            StandardCharsets.UTF_8);

        Baseline read = DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$

        assertEquals(1, read.diagnostics.size());
        assertEquals(PATH, read.diagnostics.get(0).objectRef);
        assertEquals(PATH, read.diagnostics.get(0).objectPresentation);
    }
}
