/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.PreviousRevision.Origin;
import ru.aiedt.mcp.server.support.PreviousRevision.Outcome;
import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.toolkit.ops.DiffModuleTool;

/**
 * A revision read out of Eclipse's local history reaches its reader as the bytes the state holds,
 * and a history that cannot be read is a read error rather than the outcome it replaced.
 *
 * <p>The state used to be read as lines through a UTF-8 reader and written back out as UTF-8 text,
 * which a module tool cannot compare against: a byte order mark, the line terminators, a missing
 * last newline and a container that is not UTF-8 all came out of that read changed. The bytes are
 * what {@code IModuleSource.linesIn} is handed, so they are what this pins down.</p>
 *
 * <p>A state that throws is the second half: with the file absent from HEAD the answer was the
 * {@code NOT_IN_HEAD} outcome, which a diff reports as a new module - on the strength of a read
 * that never happened. A history kept in a real workspace is exercised end to end as well, so the
 * bytes are seen arriving in a provider's hands and not only in this test's.</p>
 */
public class ALocalHistoryRevisionKeepsItsBytesTest
{
    private static final String PROJECT = "AiEdtLocalHistoryProbe"; //$NON-NLS-1$

    private static final String ADDRESS = "Catalogs/Products/Forms/Hist/Module.bsl"; //$NON-NLS-1$

    private static final String CONTAINER = "Catalogs/Products/Forms/Hist/Form.probe"; //$NON-NLS-1$

    private static final String OPEN = "<module>"; //$NON-NLS-1$

    private static final String CLOSE = "</module>"; //$NON-NLS-1$

    /** The byte order mark, built from its code point so this file stays ASCII. */
    private static final String BOM = Character.toString(0xFEFF);

    private static final String OLD_MODULE =
        "Процедура Старая()\r\n\tВозврат 1;\r\nКонецПроцедуры"; //$NON-NLS-1$

    private static final String CHANGED_MODULE =
        "Процедура Старая()\r\n\tВозврат 1;\r\nКонецПроцедуры\r\n" //$NON-NLS-1$
            + "Процедура Новая()\r\n\tВозврат 2;\r\nКонецПроцедуры"; //$NON-NLS-1$

    /**
     * The container as local history holds it: a byte order mark, CRLF terminators and no newline
     * at the end, which is what the old line-based read lost.
     */
    private static final byte[] OLD_BYTES =
        (BOM + container("форма: Hist", OLD_MODULE)).getBytes(StandardCharsets.UTF_8);

    /** The container as the workspace holds it now, written over the one above. */
    private static final byte[] CURRENT_BYTES =
        (BOM + container("форма: Hist", CHANGED_MODULE)).getBytes(StandardCharsets.UTF_8);

    /**
     * A provider whose module lives inside a container file, and which keeps the bytes it was asked
     * to read its module out of.
     */
    private static final class HistoryProbe implements IModuleSourceProvider
    {
        /** The container revision the provider was last handed, or {@code null}. */
        byte[] revisionSeen;

        @Override
        public String kind()
        {
            return "LocalHistoryProbe"; //$NON-NLS-1$
        }

        @Override
        public IModuleSource locate(IProject candidate, String modulePath)
        {
            if (candidate != project || !addressMatches(modulePath, ADDRESS))
            {
                return null;
            }
            IFile container = project.getFile(
                new org.eclipse.core.runtime.Path("src").append(CONTAINER)); //$NON-NLS-1$
            if (!container.exists())
            {
                return null;
            }
            return new HistoryModule(container.getLocation().toFile());
        }

        @Override
        public List<IModuleSource> list(IProject candidate)
        {
            return List.of();
        }
    }

    /** The one module of {@link HistoryProbe}, held in the container file it is given. */
    private static final class HistoryModule implements IModuleSource
    {
        private final File container;

        HistoryModule(File container)
        {
            this.container = container;
        }

        @Override
        public String kind()
        {
            return "LocalHistoryProbe"; //$NON-NLS-1$
        }

        @Override
        public String source()
        {
            return "local-history-probe"; //$NON-NLS-1$
        }

        @Override
        public String modulePath()
        {
            return ADDRESS;
        }

        @Override
        public String containerPath()
        {
            return CONTAINER;
        }

        @Override
        public String fqn()
        {
            return null;
        }

        @Override
        public List<String> lines() throws IOException
        {
            return parse(new String(Files.readAllBytes(container.toPath()), StandardCharsets.UTF_8));
        }

        @Override
        public List<String> linesIn(byte[] containerRevision) throws IOException
        {
            PROBE.revisionSeen = containerRevision;
            return parse(new String(containerRevision, StandardCharsets.UTF_8));
        }

        @Override
        public boolean write(List<String> lines)
        {
            throw new UnsupportedOperationException("the history probe writes nothing"); //$NON-NLS-1$
        }
    }

    private static final HistoryProbe PROBE = new HistoryProbe();

    private static Path projectDir;

    private static IProject project;

    @BeforeClass
    public static void aProjectOutsideAnyRepository() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-local-history"); //$NON-NLS-1$
        Path container = projectDir.resolve("src").resolve(CONTAINER); //$NON-NLS-1$
        Files.createDirectories(container.getParent());
        Files.write(container, OLD_BYTES);
        project = openProject(PROJECT, projectDir);
        ModuleSources.register(PROBE);
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        ModuleSources.unregister(PROBE);
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        deleteTree(projectDir);
    }

    // -- The state's bytes --

    /**
     * Whatever a history state holds arrives unchanged: a byte order mark, CRLF terminators, no
     * newline at the end, and a container that is not UTF-8 at all. Each is a thing the line-based
     * read changed, so each is checked on its own.
     */
    @Test
    public void theBytesOfAStateArriveUnchanged()
    {
        Map<String, byte[]> states = new LinkedHashMap<>();
        states.put("byte order mark", OLD_BYTES); //$NON-NLS-1$
        states.put("no final newline", "Процедура Старая()".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        // Single-byte Cyrillic: read as UTF-8 it would come out as replacement characters, so a
        // read that decodes and re-encodes cannot return these bytes.
        states.put("not UTF-8", "Процедура Старая()\r\n".getBytes(StandardCharsets.ISO_8859_1)); //$NON-NLS-1$
        states.put("empty", new byte[0]); //$NON-NLS-1$

        for (Map.Entry<String, byte[]> state : states.entrySet())
        {
            byte[] bytes = GitDiffUtils.fromLocalHistory(
                new IFileState[] {new StateProbe(state.getValue(), null)},
                PreviousRevision.missing(Outcome.NOT_IN_HEAD, "not in HEAD")) //$NON-NLS-1$
                .bytes();
            assertArrayEquals(state.getKey(), state.getValue(), bytes);
        }
    }

    /**
     * A state that holds something is a found revision from the local history, and the note says
     * which git outcome sent the lookup there.
     */
    @Test
    public void aStateThatWasReadIsAFoundRevisionFromTheLocalHistory()
    {
        PreviousRevision revision = GitDiffUtils.fromLocalHistory(
            new IFileState[] {new StateProbe(OLD_BYTES, null)},
            PreviousRevision.missing(Outcome.NOT_IN_HEAD, "src/Module.bsl is not in HEAD")); //$NON-NLS-1$

        assertTrue(revision.isFound());
        assertEquals(Origin.LOCAL_HISTORY, revision.origin());
        assertEquals("local history", revision.label()); //$NON-NLS-1$
        assertTrue(revision.note(), revision.note().contains("NOT_IN_HEAD")); //$NON-NLS-1$
        // The text keeps the behaviour it had: UTF-8 with the byte order mark taken off.
        assertTrue(revision.text(), revision.text().startsWith("форма: Hist")); //$NON-NLS-1$
    }

    /**
     * A history that is empty leaves the git outcome standing: nothing was read, so nothing is
     * claimed about the revision.
     */
    @Test
    public void anEmptyHistoryLeavesTheGitOutcomeStanding()
    {
        PreviousRevision git = PreviousRevision.missing(Outcome.NOT_UNDER_GIT, "no repository"); //$NON-NLS-1$
        assertEquals(Outcome.NOT_UNDER_GIT, GitDiffUtils.fromLocalHistory(new IFileState[0], git).outcome()); //$NON-NLS-1$
        assertEquals(Outcome.NOT_UNDER_GIT,
            GitDiffUtils.fromLocalHistory((IFileState[]) null, git).outcome());
    }

    // -- A history that cannot be read --

    /**
     * A state whose stream fails is a read error naming what was thrown. The file is absent from
     * HEAD here, and answering {@code NOT_IN_HEAD} would report the module as new on the strength
     * of a read that never happened.
     */
    @Test
    public void aStateWhoseStreamFailsIsAReadError()
    {
        PreviousRevision revision = GitDiffUtils.fromLocalHistory(
            new IFileState[] {new StateProbe(null, new IOException("the history stream is gone"))}, //$NON-NLS-1$
            PreviousRevision.missing(Outcome.NOT_IN_HEAD, "src/Module.bsl is not in HEAD")); //$NON-NLS-1$

        assertFalse(revision.isFound());
        assertEquals(Outcome.READ_ERROR, revision.outcome());
        assertNotNull(revision.note());
        assertTrue(revision.note(), revision.note().contains("the history stream is gone")); //$NON-NLS-1$
        // The sentence the answer prints is not the one that means a new module.
        String sentence = revision.explanation();
        assertTrue(sentence, sentence.contains("the previous revision could not be read")); //$NON-NLS-1$
        assertFalse(sentence, sentence.contains("it is new")); //$NON-NLS-1$
    }

    /**
     * A state whose contents throw is the same answer: the read failed, and the outcome says a read
     * failed rather than that the revision holds nothing.
     */
    @Test
    public void aStateWhoseContentsThrowIsAReadError()
    {
        PreviousRevision revision = GitDiffUtils.fromLocalHistory(
            new IFileState[] {new StateProbe(null, null)}, // the probe throws from getContents
            PreviousRevision.missing(Outcome.NOT_IN_HEAD, "src/Module.bsl is not in HEAD")); //$NON-NLS-1$

        assertFalse(revision.isFound());
        assertEquals(Outcome.READ_ERROR, revision.outcome());
        assertNotNull(revision.note());
        assertTrue(revision.note(), revision.note().contains("the probe holds no state")); //$NON-NLS-1$
    }

    // -- The bytes in a provider's hands --

    /**
     * The whole path over a history kept in a real workspace: the project is in no repository, so
     * the revision of the container comes from local history, and the provider is handed exactly
     * the bytes that were written before the current text - byte order mark, CRLF and the missing
     * final newline included.
     */
    @Test
    public void theLocalHistoryOfAWorkspaceFileReachesTheProvider() throws Exception
    {
        IFile container = project.getFile(
            new org.eclipse.core.runtime.Path("src").append(CONTAINER)); //$NON-NLS-1$
        container.setContents(new ByteArrayInputStream(CURRENT_BYTES),
            IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());
        container.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

        IFileState[] history = container.getHistory(null);
        assertNotNull("the workspace keeps a local history of the file", history); //$NON-NLS-1$
        assertTrue("the previous content is in the local history", history.length > 0); //$NON-NLS-1$

        PROBE.revisionSeen = null;
        String answer = diff(ADDRESS, "methods"); //$NON-NLS-1$

        assertNotNull("the provider was asked for the module of the revision", PROBE.revisionSeen); //$NON-NLS-1$
        assertArrayEquals("the revision arrives as the bytes of the state", OLD_BYTES, PROBE.revisionSeen); //$NON-NLS-1$
        assertTrue(answer, answer.contains("previousRevision: local history")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("source: local-history-probe")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("container: " + CONTAINER)); //$NON-NLS-1$
        assertTrue(answer, answer.contains("hasChanges: true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("### Procedure Новая (added)")); //$NON-NLS-1$
    }

    // -- Helpers --

    /** Runs the tool over the module of the fixture project. */
    private static String diff(String modulePath, String mode)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("modulePath", modulePath); //$NON-NLS-1$
        params.put("mode", mode); //$NON-NLS-1$
        return new DiffModuleTool().execute(params);
    }

    /** The container text of a module: a header, then the module between the markers. */
    private static String container(String header, String module)
    {
        return header + "\n" + OPEN + "\n" + module + "\n" + CLOSE + "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** The lines between the module markers, or {@code null} when the container holds no module. */
    private static List<String> parse(String containerText)
    {
        String[] lines = containerText.split("\n", -1); //$NON-NLS-1$
        int open = -1;
        for (int i = 0; i < lines.length; i++)
        {
            if (OPEN.equals(lines[i].trim()))
            {
                open = i;
                break;
            }
        }
        if (open < 0)
        {
            return null;
        }
        List<String> module = new ArrayList<>();
        for (int i = open + 1; i < lines.length; i++)
        {
            if (CLOSE.equals(lines[i].trim()))
            {
                return module;
            }
            module.add(lines[i]);
        }
        return null;
    }

    private static boolean addressMatches(String modulePath, String address)
    {
        if (modulePath == null)
        {
            return false;
        }
        String path = modulePath.startsWith("src/") ? modulePath.substring(4) : modulePath; //$NON-NLS-1$
        return address.equals(path);
    }

    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        opened.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        return opened;
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null)
        {
            return;
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    /**
     * A local-history state as a test can hold one: fixed bytes, or a failure where a state of a
     * real workspace would have its content.
     */
    private static final class StateProbe implements IFileState
    {
        private final byte[] content;

        private final IOException failure;

        StateProbe(byte[] content, IOException failure)
        {
            this.content = content;
            this.failure = failure;
        }

        @Override
        public InputStream getContents() throws CoreException
        {
            if (failure != null)
            {
                return new InputStream()
                {
                    @Override
                    public int read() throws IOException
                    {
                        throw failure;
                    }
                };
            }
            if (content == null)
            {
                throw new CoreException(new Status(IStatus.ERROR, "probe", "the probe holds no state")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return new ByteArrayInputStream(content);
        }

        @Override
        public String getCharset() throws CoreException
        {
            return StandardCharsets.UTF_8.name();
        }

        @Override
        public IPath getFullPath()
        {
            return new org.eclipse.core.runtime.Path("/.history/probe"); //$NON-NLS-1$
        }

        @Override
        public String getName()
        {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public long getModificationTime()
        {
            return 0L;
        }

        @Override
        public boolean exists()
        {
            return true;
        }

        @Override
        public boolean isReadOnly()
        {
            return true;
        }

        @Override
        public <T> T getAdapter(Class<T> adapter)
        {
            return null;
        }
    }
}
