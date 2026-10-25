/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.toolkit.IToolRoad;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;

/**
 * A form pulled out of the infobase into the project: the export runs through the plugin's tool
 * road - a Pending answer is resumed until the run finishes, no longer than the call's timeout,
 * and a cancellation of the caller stops the run by its run key - the container the project gets
 * is the exported one with the module entry of the chosen source, a handler the infobase layout
 * binds that the chosen module lacks stops the write, a form the owner's model does not list or
 * a missing container with module=project refuses before the export, and the parent of the
 * export is removed at the end - before the answer on the ordinary paths, in the background
 * after a cancellation or the deadline - named as leftBehind while that runs or when it could
 * not be removed.
 *
 * <p>The road is a stand-in that answers Pending once and then the export's answer, placing the
 * exported {@code Ext/Form.bin} under the output path the call carried, the way the real export
 * does; its cancel drops the run the way the real road does, so a resume against the key is a
 * finished refusal. The project is a workspace project over a temporary directory, the way the
 * delivery probe works.</p>
 */
public class AnOrdinaryFormIsPulledFromTheInfobaseTest
{
    private static final String PROJECT = "AiEdtOformImportProbe"; //$NON-NLS-1$

    private static final String FQN = "Catalog.Banks.Form.ItemForm"; //$NON-NLS-1$

    private static final String CONTAINER = "Catalogs/Banks/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    /** The listing of a form the owner's model does list. */
    private static final OrdinaryFormImport.FormListing LISTED = () -> null;

    /** Where the real export places the form's container, relative to its output path. */
    private static final String EXPORT_FILE = "Catalogs/Banks/Forms/ItemForm/Ext/Form.bin"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    /** The infobase's layout binds two handlers. */
    private static final String INFOBASE_LAYOUT = "{27,0,0,{3,\"Проц1\",0},{3,\"Проц2\",0}}" + CRLF; //$NON-NLS-1$

    private static final String INFOBASE_MODULE = "Процедура Проц1()" + CRLF + "КонецПроцедуры" + CRLF //$NON-NLS-1$ //$NON-NLS-2$
        + "Процедура Проц2()" + CRLF + "КонецПроцедуры" + CRLF //$NON-NLS-1$ //$NON-NLS-2$
        + "Процедура ИнфоЛокальная()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /** The project's own layout and module: another layout, a module with both handlers. */
    private static final String PROJECT_LAYOUT = "{26,0,0,{3,\"Проц1\",0}}" + CRLF; //$NON-NLS-1$

    private static final String PROJECT_MODULE = "Процедура Проц1()" + CRLF + "КонецПроцедуры" + CRLF //$NON-NLS-1$ //$NON-NLS-2$
        + "Процедура Проц2()" + CRLF + "КонецПроцедуры" + CRLF //$NON-NLS-1$ //$NON-NLS-2$
        + "Процедура Локальная()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /** A project module that lacks one handler the infobase layout binds. */
    private static final String LACKING_MODULE = "Процедура Проц1()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * How long the parent has to stay gone in the check below: the quiet interval the
     * background removal waits out, plus a margin.
     */
    private static final long QUIET_WITH_MARGIN_MILLIS = 3000L;

    private static Path root;

    private static Path tempRoot;

    private static IProject project;

    @BeforeClass
    public static void aProjectAndAPlaceForTheTemporaryDirectory() throws Exception
    {
        root = Files.createTempDirectory("aiedt-oform-import"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/Catalogs/Banks/Forms/ItemForm")); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        tempRoot = Files.createDirectories(root.resolve("temp")); //$NON-NLS-1$
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /** The project keeps its module and takes the infobase layout; the run went through a resume. */
    @Test
    public void theProjectKeepsItsModuleAndTakesTheInfobaseLayout() throws Exception
    {
        byte[] before = container(PROJECT_LAYOUT, PROJECT_MODULE);
        theProjectHolds(before);
        FakeRoad road = FakeRoad.thatExports(infobaseContainer());

        Map<String, Object> answer = imported("project", false, road); //$NON-NLS-1$

        assertEquals(Boolean.TRUE, answer.get("success")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, answer.get("written")); //$NON-NLS-1$
        assertEquals(1, road.resumes);
        assertEquals(List.of("config_io"), road.calls); //$NON-NLS-1$
        OrdinaryFormFile written = theProjectsContainer();
        assertEquals(INFOBASE_LAYOUT, written.formText());
        assertEquals(PROJECT_MODULE, written.moduleText());
        assertEquals("project", answer.get("module")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("project", answer.get("moduleSource")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("form"), answer.get("fromInfobase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Проц1", "Проц2"), answer.get("handlersChecked")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(String.valueOf(answer.get("delivery")), String.valueOf(answer.get("delivery")).contains("infobase")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(answer.containsKey("missingHandlers")); //$NON-NLS-1$
        theTemporaryDirectoryIsGone();
    }

    /** With module=infobase the whole form - the layout and the module - comes from the base. */
    @Test
    public void theWholeFormComesFromTheInfobase() throws Exception
    {
        theProjectHolds(container(PROJECT_LAYOUT, PROJECT_MODULE));
        FakeRoad road = FakeRoad.thatExports(infobaseContainer());

        Map<String, Object> answer = imported("infobase", false, road); //$NON-NLS-1$

        assertEquals(Boolean.TRUE, answer.get("success")); //$NON-NLS-1$
        OrdinaryFormFile written = theProjectsContainer();
        assertEquals(INFOBASE_LAYOUT, written.formText());
        assertEquals(INFOBASE_MODULE, written.moduleText());
        assertEquals("infobase", answer.get("module")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("infobase", answer.get("moduleSource")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("form", "module"), answer.get("fromInfobase")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        theTemporaryDirectoryIsGone();
    }

    /** Refuses both module sources when an export overlaps a saved container edit. */
    @Test
    public void aSavedEditDuringExportIsNotReplaced() throws Exception
    {
        for (String source : List.of("project", "infobase")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            for (byte[] replacement : List.of(container(PROJECT_LAYOUT, PROJECT_MODULE + CRLF),
                container(PROJECT_LAYOUT + CRLF, PROJECT_MODULE), containerWithUnknownEntry()))
            {
                assertExportConflict(source, container(PROJECT_LAYOUT, PROJECT_MODULE), replacement);
            }
        }
    }

    /** Refuses an import rather than recreating a container deleted during the export. */
    @Test
    public void aContainerDeletedDuringExportStaysDeleted() throws Exception
    {
        for (String source : List.of("project", "infobase")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertExportConflict(source, container(PROJECT_LAYOUT, PROJECT_MODULE), null);
        }
    }

    /** Refuses an import rather than replacing a container created during the export. */
    @Test
    public void aContainerCreatedDuringExportIsNotReplaced() throws Exception
    {
        assertExportConflict("infobase", null, container(PROJECT_LAYOUT, PROJECT_MODULE)); //$NON-NLS-1$
    }

    /** A stale no-op cannot report success or mark the owner after a concurrent edit. */
    @Test
    public void aNoOpAlsoRefusesAContainerChangedDuringExport() throws Exception
    {
        for (String source : List.of("project", "infobase")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertExportConflict(source, infobaseContainer(), container(INFOBASE_LAYOUT, INFOBASE_MODULE + CRLF));
        }
    }

    /**
     * Exercises a change made after the initial read but before the export returns.
     *
     * @param source the module source
     * @param before the initial container, or null when absent
     * @param replacement the concurrently saved bytes, or null for deletion
     * @throws Exception when the fixture cannot be prepared
     */
    private static void assertExportConflict(String source, byte[] before, byte[] replacement) throws Exception
    {
        if (before == null)
        {
            theProjectHoldsNothing();
        }
        else
        {
            theProjectHolds(before);
        }
        FakeRoad road = FakeRoad.thatExports(infobaseContainer());
        road.replaceProjectDuringExport = true;
        road.projectReplacement = replacement;
        int[] marks = new int[1];
        Map<String, Object> answer = OrdinaryFormImport.importForm(
            new OrdinaryFormImport.Plan(PROJECT, FQN, CONTAINER, source, null, false), road, tempRoot, LISTED,
            new OrdinaryFormImport.ProjectSpace(project), written -> {
                marks[0]++;
                return "marked"; //$NON-NLS-1$
            });
        assertEquals(answer.toString(), Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(answer.toString(), String.valueOf(answer.get("error")).contains("container changed after it was read")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a refused import does not mark the owner", 0, marks[0]); //$NON-NLS-1$
        if (replacement == null)
        {
            assertFalse(Files.exists(theProjectContainerFile()));
        }
        else
        {
            assertArrayEquals(replacement, theProjectsContainerBytes());
        }
        theTemporaryDirectoryIsGone();
    }

    /** @return a concurrently saved container with a changed unknown entry */
    private static byte[] containerWithUnknownEntry()
    {
        V8Container changed = V8Container.read(container(PROJECT_LAYOUT, PROJECT_MODULE));
        changed.add(V8Container.Entry.of("other", new byte[] { 1, 2, 3 })); //$NON-NLS-1$
        return changed.write();
    }

    /** The final workspace writer checks observed bytes even after the import's preflight. */
    @Test
    public void theFinalWriterAlsoRefusesAChangedContainer() throws Exception
    {
        byte[] observed = container(PROJECT_LAYOUT, PROJECT_MODULE);
        byte[] concurrent = containerWithUnknownEntry();
        theProjectHolds(concurrent);
        var modified = Files.getLastModifiedTime(theProjectContainerFile());
        try
        {
            new OrdinaryFormImport.ProjectSpace(project).write(CONTAINER, observed, infobaseContainer());
            org.junit.Assert.fail("a stale final write must refuse"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("container changed after it was read")); //$NON-NLS-1$
        }
        assertArrayEquals(concurrent, theProjectsContainerBytes());
        assertEquals(modified, Files.getLastModifiedTime(theProjectContainerFile()));
    }

    /** Imports added, replaced and removed unknown entries even when the layout and module texts match. */
    @Test
    public void differentUnknownEntriesAreNotANoOp() throws Exception
    {
        byte[] plain = infobaseContainer();
        byte[] projectOther = withUnknownEntry(plain, new byte[] { 1 });
        byte[] infobaseOther = withUnknownEntry(plain, new byte[] { 2 });
        byte[][][] variants = { { plain, infobaseOther }, { projectOther, infobaseOther }, { projectOther, plain } };
        for (String source : List.of("project", "infobase")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            for (byte[][] variant : variants)
            {
                theProjectHolds(variant[0]);
                Map<String, Object> answer = imported(source, false, FakeRoad.thatExports(variant[1]));
                assertEquals(answer.toString(), Boolean.TRUE, answer.get("success")); //$NON-NLS-1$
                assertEquals(answer.toString(), Boolean.TRUE, answer.get("written")); //$NON-NLS-1$
                assertArrayEquals(variant[1], theProjectsContainerBytes());
                theTemporaryDirectoryIsGone();
            }
        }
    }

    /**
     * Adds an uninterpreted entry to a synthetic container.
     *
     * @param bytes the original container
     * @param value the uninterpreted entry bytes
     * @return the container with the added entry
     */
    private static byte[] withUnknownEntry(byte[] bytes, byte[] value)
    {
        V8Container changed = V8Container.read(bytes);
        changed.add(V8Container.Entry.of("other", value)); //$NON-NLS-1$
        return changed.write();
    }

    /** A handler the chosen module does not declare stops the write and is named in the answer. */
    @Test
    public void aHandlerTheChosenModuleLacksStopsTheWrite() throws Exception
    {
        byte[] before = container(PROJECT_LAYOUT, LACKING_MODULE);
        theProjectHolds(before);
        FakeRoad road = FakeRoad.thatExports(infobaseContainer());

        Map<String, Object> answer = imported("project", false, road); //$NON-NLS-1$

        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), String.valueOf(answer.get("error")).contains("Проц2")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Проц2"), answer.get("missingHandlers")); //$NON-NLS-1$ //$NON-NLS-2$
        assertArrayEquals(before, theProjectsContainerBytes());
        theTemporaryDirectoryIsGone();
    }

    /**
     * The assembled container is the export with the module of the chosen source: the layout
     * entry keeps the export's bytes, the module entry carries the chosen module, and a module
     * the export already holds - or none chosen - leaves the export's original bytes in place.
     */
    @Test
    public void theAssembledContainerIsTheExportWithTheChosenModule() throws Exception
    {
        byte[] export = infobaseContainer();

        byte[] changed = OrdinaryFormImport.assembled(export, PROJECT_MODULE);
        V8Container back = V8Container.read(changed);
        assertEquals(List.of("form", "module"), names(back)); //$NON-NLS-1$ //$NON-NLS-2$
        assertArrayEquals(V8Container.read(export).find("form").getData(), back.find("form").getData()); //$NON-NLS-1$ //$NON-NLS-2$
        assertArrayEquals(withBom(PROJECT_MODULE), back.find("module").getData()); //$NON-NLS-1$

        assertArrayEquals("a chosen module of the export's own changes nothing", export, //$NON-NLS-1$
            OrdinaryFormImport.assembled(export, INFOBASE_MODULE));
        assertArrayEquals("no chosen module changes nothing", export, OrdinaryFormImport.assembled(export, null)); //$NON-NLS-1$

        // A byte-identical export leaves the project's container unchanged, including its entry timestamps.
        byte[] same = export.clone();
        theProjectHolds(same);
        Map<String, Object> answer = imported("infobase", false, FakeRoad.thatExports(export)); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, answer.get("success")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, answer.get("written")); //$NON-NLS-1$
        assertArrayEquals(same, theProjectsContainerBytes());
        assertTrue(String.valueOf(answer.get("delivery")), //$NON-NLS-1$
            String.valueOf(answer.get("delivery")).contains("nothing changed")); //$NON-NLS-1$ //$NON-NLS-2$
        theTemporaryDirectoryIsGone();
    }

    /** An export that did not place the form refuses, and nothing of the import stays behind. */
    @Test
    public void anExportFailureRefusesAndLeavesNothingBehind() throws Exception
    {
        byte[] before = container(PROJECT_LAYOUT, PROJECT_MODULE);
        theProjectHolds(before);
        FakeRoad road = FakeRoad.thatAnswers(okFalse("no form named 'ItemForm' in Catalog.Banks")); //$NON-NLS-1$

        Map<String, Object> answer = imported("project", false, road); //$NON-NLS-1$

        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
            String.valueOf(answer.get("error")).contains("no form named 'ItemForm'")); //$NON-NLS-1$
        assertArrayEquals(before, theProjectsContainerBytes());
        theTemporaryDirectoryIsGone();
    }

    /**
     * A dry run is a preflight: the road is never called, no temporary directory appears, the
     * project's bytes stay as they were, the owner is never marked for an update, and the answer
     * says the form has not been exported and the container and handler checks have not run.
     */
    @Test
    public void aDryRunWritesNothing() throws Exception
    {
        byte[] before = container(PROJECT_LAYOUT, PROJECT_MODULE);
        theProjectHolds(before);
        FakeRoad road = FakeRoad.thatExports(infobaseContainer());
        int[] marks = new int[1];
        OrdinaryFormImport.DeliveryMarker counting = written -> {
            marks[0]++;
            return "marked";
        };

        Map<String, Object> answer = OrdinaryFormImport.importForm(
            new OrdinaryFormImport.Plan(PROJECT, FQN, CONTAINER, "infobase", null, true), //$NON-NLS-1$
            road, tempRoot, LISTED, new OrdinaryFormImport.ProjectSpace(project), counting);

        assertEquals(Boolean.TRUE, answer.get("success")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, answer.get("written")); //$NON-NLS-1$
        assertEquals("the road was not called", List.of(), road.calls); //$NON-NLS-1$
        assertEquals("the owner was not marked", 0, marks[0]); //$NON-NLS-1$
        assertArrayEquals(before, theProjectsContainerBytes());
        theTemporaryDirectoryIsGone();
        String note = String.valueOf(answer.get("note")); //$NON-NLS-1$
        assertTrue(note, note.contains("has not been exported")); //$NON-NLS-1$
        assertTrue(note, note.contains("have not run")); //$NON-NLS-1$
    }

    /**
     * The output path the road receives lies inside this bundle's own parent directory: the
     * export's service directory is created beside the output path, so inside the parent, and
     * one removal of the parent takes everything.
     */
    @Test
    public void theOutputTheRoadReceivesLiesInsideTheParent() throws Exception
    {
        theProjectHolds(container(PROJECT_LAYOUT, PROJECT_MODULE));
        FakeRoad road = FakeRoad.thatExports(infobaseContainer());

        imported("project", false, road); //$NON-NLS-1$

        Path output = road.outputPassed;
        assertEquals("export", output.getFileName().toString()); //$NON-NLS-1$
        Path parent = output.getParent();
        assertTrue(String.valueOf(parent), parent.getFileName().toString().startsWith("oform-import-")); //$NON-NLS-1$
        assertEquals(tempRoot, parent.getParent());
        theTemporaryDirectoryIsGone();
    }

    /**
     * A form the owner's model does not list refuses before the export: the road is never called,
     * no file appears, and the refusal names create_form as the operation that creates the form.
     */
    @Test
    public void aFormTheModelDoesNotListRefusesBeforeTheExport() throws Exception
    {
        byte[] before = container(PROJECT_LAYOUT, PROJECT_MODULE);
        theProjectHolds(before);
        FakeRoad road = FakeRoad.thatExports(infobaseContainer());

        Map<String, Object> answer = imported("project", false, road, //$NON-NLS-1$
            () -> "The model of Catalog.Banks lists no form ItemForm."); //$NON-NLS-1$

        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
            String.valueOf(answer.get("error")).contains("create_form")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
            String.valueOf(answer.get("error")).contains("lists no form ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the road was not called", List.of(), road.calls); //$NON-NLS-1$
        assertArrayEquals(before, theProjectsContainerBytes());
        theTemporaryDirectoryIsGone();
    }

    /**
     * A missing container with module=project refuses before the export and names module=infobase
     * as the source that has a module; an empty module is not invented.
     */
    @Test
    public void noContainerWithTheProjectModuleRefuses() throws Exception
    {
        theProjectHoldsNothing();
        FakeRoad road = FakeRoad.thatExports(infobaseContainer());

        Map<String, Object> answer = imported("project", false, road); //$NON-NLS-1$

        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
            String.valueOf(answer.get("error")).contains("module=infobase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the road was not called", List.of(), road.calls); //$NON-NLS-1$
        assertFalse("no container appears", theProjectContainerFile().toFile().isFile()); //$NON-NLS-1$
        theTemporaryDirectoryIsGone();
    }

    /**
     * A cancellation that arrives after the first resume stops the run by its run key and refuses
     * at once: the road drops the run as it cancels it, so no resume follows, and the parent is
     * named as leftBehind with the answer saying it is removed in the background.
     */
    @Test
    public void aCancellationAfterTheFirstResumeCancelsTheRun() throws Exception
    {
        byte[] before = container(PROJECT_LAYOUT, PROJECT_MODULE);
        theProjectHolds(before);
        ToolCallScope scope = ToolCallScope.create(null);
        FakeRoad road = FakeRoad.thatExports(infobaseContainer()).thatCancelsTheCallerOnTheFirstResume();

        Map<String, Object> answer;
        ToolCallScope.enter(scope);
        try
        {
            answer = imported("project", false, road); //$NON-NLS-1$
        }
        finally
        {
            ToolCallScope.exit();
        }

        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
            String.valueOf(answer.get("error")).contains("cancelled")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
            String.valueOf(answer.get("error")).contains("background")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the run was cancelled by its key", 1, road.cancels); //$NON-NLS-1$
        assertEquals("the refusal came without a wait for the run", 1, road.resumes); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("leftBehind")), //$NON-NLS-1$
            String.valueOf(answer.get("leftBehind")).contains("oform-import-")); //$NON-NLS-1$ //$NON-NLS-2$
        assertArrayEquals(before, theProjectsContainerBytes());
        theBackgroundRemovalTookTheParent();
    }

    /**
     * A resume that always answers Pending refuses at the deadline of the call's own timeout,
     * cancels the run and refuses at once; the parent is named as leftBehind and removed in the
     * background.
     */
    @Test
    public void aResumeThatNeverFinishesRefusesOnTheDeadline() throws Exception
    {
        byte[] before = container(PROJECT_LAYOUT, PROJECT_MODULE);
        theProjectHolds(before);
        ToolCallScope scope = ToolCallScope.create(null);
        scope.setTimeoutSeconds(1L);
        FakeRoad road = FakeRoad.thatAnswers((String)null).thatNeverFinishes();

        Map<String, Object> answer;
        ToolCallScope.enter(scope);
        try
        {
            answer = imported("project", false, road); //$NON-NLS-1$
        }
        finally
        {
            ToolCallScope.exit();
        }

        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
            String.valueOf(answer.get("error")).contains("did not finish within 1 seconds")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
            String.valueOf(answer.get("error")).contains("background")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the run was cancelled by its key", 1, road.cancels); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("leftBehind")), //$NON-NLS-1$
            String.valueOf(answer.get("leftBehind")).contains("oform-import-")); //$NON-NLS-1$ //$NON-NLS-2$
        assertArrayEquals(before, theProjectsContainerBytes());
        theBackgroundRemovalTookTheParent();
    }

    /**
     * A Designer that keeps working past the cancel - the road has already dropped the run, but
     * the process writes into the service directory beside the output path and moves it onto the
     * output path after it exits - is answered at once: the output path lies inside the parent,
     * the parent is named as leftBehind with the answer saying it is removed in the background,
     * and the background removal takes the parent for good once the Designer has left. The call
     * answers Pending only once the Designer holds its begun file open, so the removal cannot
     * race the Designer's start; whether the place step moved the service directory into place
     * before the removal took it stays a race the test records, not a condition.
     */
    @Test
    public void aDesignerStillWritingPastTheCancelIsRemovedInTheBackground() throws Exception
    {
        byte[] before = container(PROJECT_LAYOUT, PROJECT_MODULE);
        theProjectHolds(before);
        ToolCallScope scope = ToolCallScope.create(null);
        FakeRoad road = FakeRoad.thatExports(infobaseContainer()).thatCancelsTheCallerOnTheFirstResume()
            .thatRunsADesignerPastTheCancel();

        Map<String, Object> answer;
        ToolCallScope.enter(scope);
        try
        {
            answer = imported("project", false, road); //$NON-NLS-1$
        }
        finally
        {
            ToolCallScope.exit();
        }

        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
            String.valueOf(answer.get("error")).contains("cancelled")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
            String.valueOf(answer.get("error")).contains("background")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the run was cancelled by its key", 1, road.cancels); //$NON-NLS-1$
        assertTrue("the refusal came before the Designer left", road.designerDone.getCount() == 1); //$NON-NLS-1$
        Path parent = theOneImportDirectory();
        assertEquals("the output path lies inside the parent", parent, road.outputPassed.getParent()); //$NON-NLS-1$
        assertEquals(parent.toString(), String.valueOf(answer.get("leftBehind"))); //$NON-NLS-1$
        assertArrayEquals(before, theProjectsContainerBytes());

        road.designerGate.countDown();
        assertTrue("the Designer did not leave", road.designerDone.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
        assertNull("the Designer failed: " + road.designerFailure, road.designerFailure); //$NON-NLS-1$
        boolean thePlaceStepMoved = road.placeStepMoved;
        assertTrue("the background removal did not take the parent for good (the place step moved: " //$NON-NLS-1$
            + thePlaceStepMoved + ")", goneAndStaysGone(parent, QUIET_WITH_MARGIN_MILLIS, 30)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A temporary directory that cannot be removed - the road holds the exported file open, the
     * way a cancelled export holds its output - is named in the answer as leftBehind instead of
     * disappearing silently.
     */
    @Test
    public void aDirectoryThatCannotBeRemovedIsLeftBehind() throws Exception
    {
        byte[] before = container(PROJECT_LAYOUT, PROJECT_MODULE);
        theProjectHolds(before);
        FakeRoad road = FakeRoad.thatExports(infobaseContainer()).thatHoldsTheExportOpen();

        Map<String, Object> answer;
        try
        {
            answer = imported("project", false, road); //$NON-NLS-1$
        }
        finally
        {
            road.closeHeld();
            removeWhatWasLeftBehind();
        }

        assertEquals(Boolean.TRUE, answer.get("success")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, answer.get("written")); //$NON-NLS-1$
        String leftBehind = String.valueOf(answer.get("leftBehind")); //$NON-NLS-1$
        assertTrue(leftBehind, leftBehind.contains("oform-import-")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(INFOBASE_LAYOUT, theProjectsContainer().formText());
    }

    private static Map<String, Object> imported(String module, boolean dryRun, IToolRoad road)
    {
        return imported(module, dryRun, road, LISTED);
    }

    private static Map<String, Object> imported(String module, boolean dryRun, IToolRoad road,
        OrdinaryFormImport.FormListing listing)
    {
        return OrdinaryFormImport.importForm(new OrdinaryFormImport.Plan(PROJECT, FQN, CONTAINER, module, null, dryRun),
            road, tempRoot, listing, new OrdinaryFormImport.ProjectSpace(project),
            new OrdinaryFormImport.ProjectMarker(project, CONTAINER));
    }

    private static void theTemporaryDirectoryIsGone() throws IOException
    {
        try (var entries = Files.list(tempRoot))
        {
            assertEquals("the temporary export directory stays behind", List.of(), entries.toList()); //$NON-NLS-1$
        }
    }

    /** Waits, boundedly, for the background removal to empty the temporary root. */
    private static void theBackgroundRemovalTookTheParent() throws IOException
    {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() - end < 0)
        {
            try (var entries = Files.list(tempRoot))
            {
                if (entries.findAny().isEmpty())
                {
                    return;
                }
            }
            sleep(100);
        }
        theTemporaryDirectoryIsGone();
    }

    /** The one directory of this import under the temporary root. */
    private static Path theOneImportDirectory() throws IOException
    {
        try (var entries = Files.list(tempRoot))
        {
            List<Path> directories = entries
                .filter(p -> p.getFileName().toString().startsWith("oform-import-")).toList(); //$NON-NLS-1$
            assertEquals("one import directory under the root", 1, directories.size()); //$NON-NLS-1$
            return directories.get(0);
        }
    }

    /**
     * Waits, boundedly, for the directory to go and then to stay gone for the quiet interval
     * given: a reappearance inside it restarts the stability wait, still inside the bound.
     *
     * @param directory the directory
     * @param quietMillis how long it has to stay gone
     * @param timeoutSeconds the overall bound
     * @return whether it went and stayed gone for the quiet interval
     */
    private static boolean goneAndStaysGone(Path directory, long quietMillis, long timeoutSeconds)
    {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long goneSince = 0L;
        while (System.nanoTime() - end < 0)
        {
            if (Files.exists(directory))
            {
                goneSince = 0L;
            }
            else if (goneSince == 0L)
            {
                goneSince = System.nanoTime();
            }
            else if (System.nanoTime() - goneSince >= TimeUnit.MILLISECONDS.toNanos(quietMillis))
            {
                return true;
            }
            sleep(100);
        }
        return false;
    }

    private static void theProjectHolds(byte[] bytes) throws IOException
    {
        Files.write(theProjectContainerFile(), bytes);
        try
        {
            project.getFile(new org.eclipse.core.runtime.Path("src/" + CONTAINER)) //$NON-NLS-1$
                .refreshLocal(org.eclipse.core.resources.IResource.DEPTH_ZERO, null);
        }
        catch (org.eclipse.core.runtime.CoreException e)
        {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static void theProjectHoldsNothing() throws IOException
    {
        Files.deleteIfExists(theProjectContainerFile());
        try
        {
            project.getFile(new org.eclipse.core.runtime.Path("src/" + CONTAINER)) //$NON-NLS-1$
                .refreshLocal(org.eclipse.core.resources.IResource.DEPTH_ZERO, null);
        }
        catch (org.eclipse.core.runtime.CoreException e)
        {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static Path theProjectContainerFile()
    {
        return project.getLocation().toFile().toPath().resolve("src").resolve(CONTAINER); //$NON-NLS-1$
    }

    /** Removes what a left-behind temporary directory still holds, keeping the root. */
    private static void removeWhatWasLeftBehind() throws IOException
    {
        try (var entries = Files.walk(tempRoot))
        {
            entries.filter(p -> !p.equals(tempRoot))
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
        }
    }

    private static OrdinaryFormFile theProjectsContainer() throws IOException
    {
        Path file = project.getLocation().toFile().toPath().resolve("src").resolve(CONTAINER); //$NON-NLS-1$
        return OrdinaryFormFile.read(file);
    }

    private static byte[] theProjectsContainerBytes() throws IOException
    {
        return Files.readAllBytes(project.getLocation().toFile().toPath().resolve("src").resolve(CONTAINER)); //$NON-NLS-1$
    }

    private static byte[] infobaseContainer()
    {
        return container(INFOBASE_LAYOUT, INFOBASE_MODULE);
    }

    private static byte[] container(String layout, String module)
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(layout))); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", withBom(module))); //$NON-NLS-1$
        return container.write();
    }

    private static List<String> names(V8Container container)
    {
        List<String> out = new ArrayList<>();
        for (V8Container.Entry entry : container.getEntries())
        {
            out.add(entry.getName());
        }
        return out;
    }

    private static String okFalse(String error)
    {
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", false); //$NON-NLS-1$
        answer.addProperty("error", error); //$NON-NLS-1$
        return answer.toString();
    }

    private static byte[] withBom(String text)
    {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte)0xEF;
        out[1] = (byte)0xBB;
        out[2] = (byte)0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    private static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the test was interrupted", e); //$NON-NLS-1$
        }
    }

    /**
     * The road as the import meets it: the call answers Pending and the resume brings the
     * export's own answer. The stand-in places the exported {@code Form.bin} under the output
     * path the call carried, the way the real export does, and its cancel drops the run the way
     * the real road does. Four modes extend it: one that cancels the caller on the first resume,
     * one whose resume never finishes, one that holds the exported file open past the return,
     * and one whose Designer keeps writing beside the output path past the cancel and moves its
     * service directory into place after the test lets it leave. In that mode the call answers
     * Pending only once the Designer holds its begun file open, so the background removal the
     * cancel starts cannot take the parent before the Designer holds it.
     */
    static final class FakeRoad implements IToolRoad
    {
        final List<String> calls = new ArrayList<>();

        /** The output path the call carried, the way the real export receives it. */
        Path outputPassed;

        int resumes;

        int cancels;

        private final byte[] export;

        private final String answer;

        private boolean replaceProjectDuringExport;

        private byte[] projectReplacement;

        private boolean cancelTheCallerOnTheFirstResume;

        private boolean neverFinishes;

        private boolean holdTheExportOpen;

        private boolean callerCancelled;

        /** Set by cancel: the run's registry entry is gone, the way the real road drops it. */
        private boolean runDropped;

        /** Opens when the road has cancelled the run; the Designer of the stand-in waits on it. */
        final CountDownLatch cancelledByRoad = new CountDownLatch(1);

        /** Opens when the stand-in's Designer holds its begun file open; the call waits on it. */
        final CountDownLatch designerBegun = new CountDownLatch(1);

        /** Lets the stand-in's Designer leave, so it moves its service directory into place. */
        final CountDownLatch designerGate = new CountDownLatch(1);

        /** Opens when the stand-in's Designer has moved its service directory into place. */
        final CountDownLatch designerDone = new CountDownLatch(1);

        /** What the stand-in's Designer failed with, or {@code null} when it did not. */
        volatile Exception designerFailure;

        /** Whether the place step moved the service directory into place before the removal took it. */
        volatile boolean placeStepMoved;

        private boolean designerWorksPastTheCancel;

        private String runKey;

        private InputStream held;

        private FakeRoad(byte[] export, String answer)
        {
            this.export = export;
            this.answer = answer;
        }

        static FakeRoad thatExports(byte[] export)
        {
            JsonObject done = new JsonObject();
            done.addProperty("ok", true); //$NON-NLS-1$
            done.addProperty("source", "infobase"); //$NON-NLS-1$ //$NON-NLS-2$
            done.add("files", com.google.gson.JsonParser.parseString("[\"" + EXPORT_FILE + "\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return new FakeRoad(export, done.toString());
        }

        static FakeRoad thatAnswers(String answer)
        {
            return new FakeRoad(null, answer);
        }

        FakeRoad thatCancelsTheCallerOnTheFirstResume()
        {
            this.cancelTheCallerOnTheFirstResume = true;
            return this;
        }

        FakeRoad thatNeverFinishes()
        {
            this.neverFinishes = true;
            return this;
        }

        FakeRoad thatHoldsTheExportOpen()
        {
            this.holdTheExportOpen = true;
            return this;
        }

        FakeRoad thatRunsADesignerPastTheCancel()
        {
            this.designerWorksPastTheCancel = true;
            return this;
        }

        @Override
        public ToolRoadOutcome call(String tool, Map<String, Object> arguments, String origin)
        {
            calls.add(tool);
            outputPassed = Path.of(String.valueOf(arguments.get("outputPath"))); //$NON-NLS-1$
            if (export != null)
            {
                try
                {
                    Path bin = outputPassed.resolve(EXPORT_FILE);
                    Files.createDirectories(bin.getParent());
                    Files.write(bin, export);
                    if (replaceProjectDuringExport)
                    {
                        if (projectReplacement == null)
                        {
                            theProjectHoldsNothing();
                        }
                        else
                        {
                            theProjectHolds(projectReplacement);
                        }
                    }
                    if (holdTheExportOpen)
                    {
                        // FileInputStream opens without the delete share, the way a process that
                        // holds its output holds it: the file cannot be deleted while it is open
                        held = new java.io.FileInputStream(bin.toFile());
                    }
                }
                catch (IOException e)
                {
                    throw new IllegalStateException(e);
                }
            }
            runKey = "eio-" + UUID.randomUUID(); //$NON-NLS-1$
            if (designerWorksPastTheCancel)
            {
                startTheDesigner(outputPassed, runKey);
                awaitTheDesigner();
            }
            return ToolRoadOutcome.pending("the export is running", runKey, "export-infobase"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public ToolRoadOutcome resume(String runKey, long waitMillis)
        {
            resumes++;
            if (runDropped)
            {
                // the real road drops the run's registry entry as it cancels it, so a resume
                // against the key is a finished refusal, not a wait
                return ToolRoadOutcome.refused("no run is held for " + runKey); //$NON-NLS-1$
            }
            if (neverFinishes)
            {
                // a run that never notices anything waits out the slice it was asked for
                sleep(waitMillis);
                return ToolRoadOutcome.pending("the export is running", this.runKey, "export-infobase"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (cancelTheCallerOnTheFirstResume && !callerCancelled)
            {
                callerCancelled = true;
                ToolCallScope scope = ToolCallScope.current();
                if (scope != null)
                {
                    scope.cancellation().cancel("the caller asked"); //$NON-NLS-1$
                }
                return ToolRoadOutcome.pending("the export is running", this.runKey, "export-infobase"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return ToolRoadOutcome.done(answer);
        }

        @Override
        public boolean cancel(String runKey)
        {
            cancels++;
            runDropped = true;
            cancelledByRoad.countDown();
            return true;
        }

        /**
         * The Designer of the stand-in: it works from the call - it writes into the service
         * directory beside the output path and holds the file open, the way a process holds its
         * output - goes on writing past the cancel, and moves the directory onto the output
         * path only after the test lets it leave.
         */
        private void startTheDesigner(Path output, String runKey)
        {
            Thread designer = new Thread(() -> {
                try
                {
                    Path service = output.getParent().resolve(".aiedt-export-" + runKey); //$NON-NLS-1$
                    Files.createDirectories(service);
                    Path begun = service.resolve("begun.bin"); //$NON-NLS-1$
                    Files.write(begun, new byte[]{1});
                    java.io.FileInputStream running = new java.io.FileInputStream(begun.toFile());
                    designerBegun.countDown();
                    try
                    {
                        cancelledByRoad.await();
                        Files.write(service.resolve("working.bin"), new byte[]{1}); //$NON-NLS-1$
                        designerGate.await();
                    }
                    finally
                    {
                        running.close();
                    }
                    // The place step: the exporter moves the service directory onto the output
                    // path. The background removal races it - the run's answer is read by no
                    // one once the road has dropped it - so a move that lost the race is tried
                    // again while the service directory is still there.
                    for (int attempt = 0; attempt < 200 && Files.exists(service); attempt++)
                    {
                        try
                        {
                            Files.deleteIfExists(output);
                            Files.move(service, output);
                            placeStepMoved = true;
                            break;
                        }
                        catch (IOException raced)
                        {
                            if (attempt == 199)
                            {
                                designerFailure = raced;
                            }
                            sleep(50);
                        }
                    }
                }
                catch (Exception failure)
                {
                    designerFailure = failure;
                }
                finally
                {
                    designerDone.countDown();
                }
            }, "the-designer-of-the-stand-in"); //$NON-NLS-1$
            designer.setDaemon(true);
            designer.start();
        }

        /**
         * Waits, boundedly, until the stand-in's Designer holds its begun file open: the call
         * answers Pending only then, so the background removal the cancel starts cannot take
         * the parent before the Designer holds it.
         */
        private void awaitTheDesigner()
        {
            try
            {
                if (!designerBegun.await(10, TimeUnit.SECONDS))
                {
                    throw new IllegalStateException("the stand-in's Designer did not begin"); //$NON-NLS-1$
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the stand-in road was interrupted", e); //$NON-NLS-1$
            }
        }

        void closeHeld() throws IOException
        {
            if (held != null)
            {
                held.close();
                held = null;
            }
        }

        private static void sleep(long millis)
        {
            try
            {
                Thread.sleep(millis);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the stand-in road was interrupted", e); //$NON-NLS-1$
            }
        }
    }
}
