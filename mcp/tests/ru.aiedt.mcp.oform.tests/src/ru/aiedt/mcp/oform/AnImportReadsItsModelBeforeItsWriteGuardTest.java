/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.oform.session.FormEditingSession;
import ru.aiedt.mcp.oform.session.OpenFormSessions;
import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.toolkit.IToolRoad;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;

/**
 * The import reads the owner's model - the agent's own model read, the one that rides the UI
 * thread in a live EDT - before it enters the write guard: the read runs and answers while the
 * registry lock the guard takes is still free to another thread, and the write at the seam then
 * refuses on a session holding unsaved changes, with the container keeping its bytes. The
 * ordering is asked of the locks themselves: the model read of the stand-in waits on the
 * registry lock this test holds, so a read placed behind the guard would never answer while the
 * lock is held - and the read answers.
 */
public class AnImportReadsItsModelBeforeItsWriteGuardTest
{
    private static final String PROJECT = "AiEdtOformUiBeforeGuardProbe"; //$NON-NLS-1$

    private static final String FQN = "Catalog.Banks.Form.ItemForm"; //$NON-NLS-1$

    private static final String CONTAINER = "Catalogs/Banks/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String EXPORT_FILE = "Catalogs/Banks/Forms/ItemForm/Ext/Form.bin"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String PROJECT_MODULE = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    private static final String INFOBASE_LAYOUT = "{27,0,0,{3,\"Проц1\",0}}" + CRLF; //$NON-NLS-1$

    private static final String INFOBASE_MODULE = "Процедура Проц1()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    private static String layoutText;

    private static Path root;

    private static Path tempRoot;

    private static Path containerFile;

    private static IFile container;

    private static IProject project;

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-ui-guard"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/Catalogs/Banks/Forms/ItemForm")); //$NON-NLS-1$
        containerFile = projectDir.resolve("src").resolve(CONTAINER); //$NON-NLS-1$
        Files.write(containerFile, containerOf(layoutText, PROJECT_MODULE));
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        container = project.getFile("src/" + CONTAINER); //$NON-NLS-1$
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

    /**
     * The model read answers while the registry lock is held by another thread - so it runs
     * before the guard takes that lock - and the write at the seam then refuses on the session's
     * unsaved changes: nothing of the import lands, both sides finish.
     */
    @Test
    public void theModelReadAnswersBeforeTheGuardTakesItsLock() throws Exception
    {
        byte[] asWritten = Files.readAllBytes(containerFile);
        FormEditingSession session = OpenFormSessions.open(container);
        try
        {
            FormEditingSession.Edit edit = session.edit("set_property", //$NON-NLS-1$
                Map.of("property", "title", "value", "Заголовок из редактора")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue(edit.message, edit.done);
            assertTrue(session.formChanged());

            // The registry lock of this form's session, the second lock the guard takes. This
            // thread holds it: a model read placed behind the guard would block on it here and
            // never answer; a read placed before the guard answers and then asks for the lock
            // itself, which this thread sees as the import coming to wait.
            OpenFormSessions.SessionLock registry = OpenFormSessions.lockOf(container);
            registry.lock();
            CountDownLatch readAnswered = new CountDownLatch(1);
            CountDownLatch importAnswered = new CountDownLatch(1);
            AtomicReference<Map<String, Object>> answerOf = new AtomicReference<>();
            AtomicReference<Exception> failureOf = new AtomicReference<>();
            AtomicInteger marks = new AtomicInteger();
            Thread importer = new Thread(() -> {
                ToolCallScope scope = ToolCallScope.create(null);
                ToolCallScope.enter(scope);
                try
                {
                    answerOf.set(OrdinaryFormImport.importForm(
                        new OrdinaryFormImport.Plan(PROJECT, FQN, CONTAINER, "infobase", null, false), //$NON-NLS-1$
                        anExportRoad(), tempRoot, () -> {
                            // The agent's model read. It answers at once - it runs before the
                            // guard - and only then takes the registry lock, on its way into the
                            // guard, to prove that order to the thread that holds the lock.
                            readAnswered.countDown();
                            OpenFormSessions.SessionLock own = OpenFormSessions.lockOf(container);
                            own.lock();
                            own.unlock();
                            return null;
                        }, new OrdinaryFormImport.ProjectSpace(project), written -> {
                            marks.incrementAndGet();
                            return "marked"; //$NON-NLS-1$
                        }));
                }
                catch (Exception e)
                {
                    failureOf.set(e);
                }
                finally
                {
                    ToolCallScope.exit();
                    importAnswered.countDown();
                }
            }, "the-importing-agent"); //$NON-NLS-1$
            importer.start();

            assertTrue("the model read did not answer while the registry lock was held", //$NON-NLS-1$
                readAnswered.await(10, TimeUnit.SECONDS));
            assertFalse("the import already answered - it passed the guard it never entered", //$NON-NLS-1$
                importAnswered.await(700, TimeUnit.MILLISECONDS));
            assertTrue("the import waits behind the registry lock", registry.hasQueuedThread(importer)); //$NON-NLS-1$

            registry.unlock();
            assertTrue("the import did not finish once the lock went", //$NON-NLS-1$
                importAnswered.await(10, TimeUnit.SECONDS));
            importer.join(10_000);
            if (failureOf.get() != null)
            {
                throw failureOf.get();
            }
            Map<String, Object> answer = answerOf.get();
            assertEquals(String.valueOf(answer), Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
            assertTrue(String.valueOf(answer.get("error")), //$NON-NLS-1$
                String.valueOf(answer.get("error")).contains("unsaved changes")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("a refused import does not mark the owner", 0, marks.get()); //$NON-NLS-1$
            assertArrayEquals("the container keeps its bytes", asWritten, Files.readAllBytes(containerFile)); //$NON-NLS-1$
            assertTrue("the session keeps its edit", session.formChanged()); //$NON-NLS-1$
        }
        finally
        {
            OpenFormSessions.release(session);
        }
    }

    /**
     * The export road as the import meets it: the call answers Pending once, having placed the
     * exported {@code Form.bin} under the output path, and the resume answers the export's own
     * answer.
     *
     * @return the road
     */
    private static IToolRoad anExportRoad()
    {
        return new IToolRoad()
        {
            private final String runKey = "ui-guard-" + UUID.randomUUID(); //$NON-NLS-1$

            @Override
            public ToolRoadOutcome call(String tool, Map<String, Object> arguments, String origin)
            {
                try
                {
                    Path bin = Path.of(String.valueOf(arguments.get("outputPath"))).resolve(EXPORT_FILE); //$NON-NLS-1$
                    Files.createDirectories(bin.getParent());
                    Files.write(bin, containerOf(INFOBASE_LAYOUT, INFOBASE_MODULE));
                }
                catch (IOException e)
                {
                    throw new IllegalStateException(e);
                }
                return ToolRoadOutcome.pending("the export is running", runKey, "export-infobase"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            @Override
            public ToolRoadOutcome resume(String runKey, long waitMillis)
            {
                JsonObject done = new JsonObject();
                done.addProperty("ok", true); //$NON-NLS-1$
                done.addProperty("source", "infobase"); //$NON-NLS-1$ //$NON-NLS-2$
                done.add("files", com.google.gson.JsonParser.parseString("[\"" + EXPORT_FILE + "\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return ToolRoadOutcome.done(done.toString());
            }

            @Override
            public boolean cancel(String runKey)
            {
                return true;
            }
        };
    }

    private static byte[] containerOf(String layout, String module)
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(layout))); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", withBom(module))); //$NON-NLS-1$
        return container.write();
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
}
