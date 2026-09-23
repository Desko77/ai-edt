/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;

/**
 * A module without a file of its own is compared against a revision of its container, and a
 * comparison that cannot be made says so instead of reporting a new file.
 *
 * <p>The module lives inside a container file that git holds, which is what a module the EDT model
 * keeps somewhere it does not open looks like: the address has no {@code Module.bsl}, the text is
 * inside the container, and only the provider can find it there. The repository is a real one made
 * through JGit, the container is committed, and the working tree is edited afterwards, so the
 * previous revision is HEAD and the current text is what the provider reads now.</p>
 *
 * <p>What each outcome answers is decided here as well: a provider that does not read previous
 * revisions, one that cannot parse the revision, one whose module is not in it, one whose module
 * has no container, and an ordinary file in a project outside any repository. Every one of them
 * used to reach the caller as a new file.</p>
 */
public class ADiffAgainstTheContainerRevisionTest
{
    private static final String PROJECT = "AiEdtDiffContainerProbe"; //$NON-NLS-1$

    private static final String NO_REPO_PROJECT = "AiEdtDiffNoRepoProbe"; //$NON-NLS-1$

    private static final String ITEM_FORM = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static final String ITEM_CONTAINER = "Catalogs/Products/Forms/ItemForm/Form.probe"; //$NON-NLS-1$

    private static final String NEW_FORM = "Catalogs/Products/Forms/NewForm/Module.bsl"; //$NON-NLS-1$

    private static final String NEW_CONTAINER = "Catalogs/Products/Forms/NewForm/Form.probe"; //$NON-NLS-1$

    private static final String NO_HISTORY = "Catalogs/Products/Forms/NoHistory/Module.bsl"; //$NON-NLS-1$

    private static final String NO_HISTORY_CONTAINER = "Catalogs/Products/Forms/NoHistory/Form.probe"; //$NON-NLS-1$

    private static final String PLAIN = "CommonModules/Plain/Module.bsl"; //$NON-NLS-1$

    private static final String LONE = "CommonModules/Lone/Module.bsl"; //$NON-NLS-1$

    private static final String OPEN = "<module>"; //$NON-NLS-1$

    private static final String CLOSE = "</module>"; //$NON-NLS-1$

    private static final String BOM = Character.toString(0xFEFF);

    private static final String OLD_MODULE =
        "Процедура Старая()\n\tВозврат 1;\nКонецПроцедуры"; //$NON-NLS-1$

    private static final String CHANGED_MODULE =
        "Процедура Старая()\n\tВозврат 1;\n\t// строка добавлена\nКонецПроцедуры\n" //$NON-NLS-1$
            + "Процедура Новая()\n\tВозврат 2;\nКонецПроцедуры"; //$NON-NLS-1$

    private static final String COMMITTED_ITEM = container("форма: ItemForm", OLD_MODULE); //$NON-NLS-1$

    /** A container committed before the module existed in it. */
    private static final String COMMITTED_NEW_FORM = "форма: NewForm\n"; //$NON-NLS-1$

    private static final String COMMITTED_NO_HISTORY =
        container("форма: NoHistory", OLD_MODULE); //$NON-NLS-1$

    /** A plain module with a byte order mark and no trailing newline. */
    private static final String COMMITTED_PLAIN = BOM + OLD_MODULE;

    private static final String PLAIN_CHANGED =
        OLD_MODULE + "\nПроцедура Новая()\n\tВозврат 2;\nКонецПроцедуры\n"; //$NON-NLS-1$

    /**
     * A provider whose modules live inside a container file, one module per container.
     *
     * <p>The module address is {@code <dir>/Module.bsl} and the container is {@code <dir>/Form.probe},
     * so one provider serves several addresses and each returns its own module. The container is the
     * module's text between the markers; a container without them holds no module.</p>
     *
     * <p>It answers for the addresses it owns and for no others, the way a provider sharing the
     * registry with another one has to: the first provider that answers is the one a lookup stops
     * at.</p>
     */
    private static final class ContainerProbe implements IModuleSourceProvider
    {
        /** The addresses this provider holds, and the only ones it claims. */
        private static final List<String> OWNS = List.of(ITEM_FORM, NEW_FORM);


        /** The module has no container file at all. */
        boolean containerless;

        /** When set, reading the previous revision throws this. */
        IOException parseFailure;

        @Override
        public String kind()
        {
            return "ProbeModule"; //$NON-NLS-1$
        }

        @Override
        public IModuleSource locate(IProject candidate, String modulePath)
        {
            if (candidate != project || modulePath == null)
            {
                return null;
            }
            String path = modulePath.startsWith("src/") ? modulePath.substring(4) : modulePath; //$NON-NLS-1$
            if (!OWNS.contains(path))
            {
                return null;
            }
            String dir = path.substring(0, path.length() - "Module.bsl".length()); //$NON-NLS-1$
            String container = containerless ? null : dir + "Form.probe"; //$NON-NLS-1$
            if (container != null && !file(container).exists())
            {
                return null;
            }
            return new ContainerModule(path, container);
        }

        @Override
        public List<IModuleSource> list(IProject candidate)
        {
            return List.of();
        }
    }

    /** One module of {@link ContainerProbe}, in its own container. */
    private static final class ContainerModule implements IModuleSource
    {
        private final String address;

        private final String container;

        ContainerModule(String address, String container)
        {
            this.address = address;
            this.container = container;
        }

        @Override
        public String kind()
        {
            return "ProbeModule"; //$NON-NLS-1$
        }

        @Override
        public String source()
        {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String modulePath()
        {
            return address;
        }

        @Override
        public String containerPath()
        {
            return container;
        }

        @Override
        public String fqn()
        {
            return null;
        }

        @Override
        public List<String> lines() throws IOException
        {
            if (container == null)
            {
                return List.of("Процедура Один()", "КонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return parse(Files.readString(file(container).toPath(), StandardCharsets.UTF_8));
        }

        @Override
        public List<String> linesIn(byte[] containerRevision) throws IOException
        {
            if (PROBE.parseFailure != null)
            {
                throw PROBE.parseFailure;
            }
            return parse(new String(containerRevision, StandardCharsets.UTF_8));
        }

        @Override
        public Map<String, String> answerFields()
        {
            Map<String, String> fields = new java.util.LinkedHashMap<>();
            fields.put("formName", "ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
            fields.put("probeField", "kept"); //$NON-NLS-1$ //$NON-NLS-2$
            return fields;
        }

        @Override
        public boolean write(List<String> lines)
        {
            throw new UnsupportedOperationException("the diff probe writes nothing"); //$NON-NLS-1$
        }

        @Override
        public boolean readOnly()
        {
            return true;
        }
    }

    /**
     * A provider whose module has a container but which does not read previous revisions: it leaves
     * {@link IModuleSource#linesIn} at its default.
     */
    private static final class NoHistoryProbe implements IModuleSourceProvider, IModuleSource
    {
        @Override
        public String kind()
        {
            return "NoHistoryProbe"; //$NON-NLS-1$
        }

        @Override
        public IModuleSource locate(IProject candidate, String modulePath)
        {
            return candidate == project && addressMatches(modulePath, NO_HISTORY) ? this : null;
        }

        @Override
        public List<IModuleSource> list(IProject candidate)
        {
            return List.of();
        }

        @Override
        public String source()
        {
            return "no-history-probe"; //$NON-NLS-1$
        }

        @Override
        public String modulePath()
        {
            return NO_HISTORY;
        }

        @Override
        public String containerPath()
        {
            return NO_HISTORY_CONTAINER;
        }

        @Override
        public String fqn()
        {
            return null;
        }

        @Override
        public List<String> lines() throws IOException
        {
            return parse(Files.readString(file(NO_HISTORY_CONTAINER).toPath(),
                StandardCharsets.UTF_8));
        }

        @Override
        public boolean write(List<String> lines)
        {
            throw new UnsupportedOperationException("the diff probe writes nothing"); //$NON-NLS-1$
        }
    }

    private static final ContainerProbe PROBE = new ContainerProbe();

    private static final NoHistoryProbe NO_HISTORY_PROBE = new NoHistoryProbe();

    private static Path repoRoot;

    private static Path projectDir;

    private static IProject project;

    private static Path noRepoRoot;

    private static IProject noRepoProject;

    private static long stamp;

    @BeforeClass
    public static void aCommittedContainerAndAProjectOverIt() throws Exception
    {
        repoRoot = Files.createTempDirectory("aiedt-diff-container"); //$NON-NLS-1$
        projectDir = repoRoot.resolve(PROJECT);
        writeCommitted(ITEM_CONTAINER, COMMITTED_ITEM);
        writeCommitted(NEW_CONTAINER, COMMITTED_NEW_FORM);
        writeCommitted(NO_HISTORY_CONTAINER, COMMITTED_NO_HISTORY);
        writeCommitted(PLAIN, COMMITTED_PLAIN);
        try (Git git = Git.init().setDirectory(repoRoot.toFile()).call())
        {
            git.add().addFilepattern(PROJECT + "/src").call(); //$NON-NLS-1$
            git.commit().setAuthor("Probe", "probe@example.invalid") //$NON-NLS-1$ //$NON-NLS-2$
                .setMessage("Committed sources").call(); //$NON-NLS-1$
        }
        project = openProject(PROJECT, projectDir);

        noRepoRoot = Files.createTempDirectory("aiedt-diff-no-git"); //$NON-NLS-1$
        writeCommitted(noRepoRoot.resolve(NO_REPO_PROJECT), LONE, OLD_MODULE + "\n"); //$NON-NLS-1$
        noRepoProject = openProject(NO_REPO_PROJECT, noRepoRoot.resolve(NO_REPO_PROJECT));

        ModuleSources.register(PROBE);
        ModuleSources.register(NO_HISTORY_PROBE);
    }

    @AfterClass
    public static void theProjectsAndTheRepositoryGo() throws Exception
    {
        ModuleSources.unregister(PROBE);
        ModuleSources.unregister(NO_HISTORY_PROBE);
        deleteProject(noRepoProject);
        deleteProject(project);
        deleteTree(repoRoot);
        deleteTree(noRepoRoot);
    }

    /** Every test starts from the committed state of the working tree. */
    @Before
    public void theWorkingTreeIsRestored() throws Exception
    {
        PROBE.containerless = false;
        PROBE.parseFailure = null;
        writeWorking(ITEM_CONTAINER, COMMITTED_ITEM);
        writeWorking(NEW_CONTAINER, COMMITTED_NEW_FORM);
        writeWorking(NO_HISTORY_CONTAINER, COMMITTED_NO_HISTORY);
        writeWorking(PLAIN, COMMITTED_PLAIN);
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    // -- The full path: a provided module against the head of its container --

    /**
     * A module with no file of its own is compared against HEAD of the container that holds it:
     * the previous text comes out of the revision through the provider, the current text comes from
     * the provider, and the method diff shows what changed between them.
     */
    @Test
    public void aProvidedModuleIsComparedAgainstTheHeadOfItsContainer() throws Exception
    {
        writeWorking(ITEM_CONTAINER, container("форма: ItemForm", CHANGED_MODULE)); //$NON-NLS-1$

        String methods = diff(ITEM_FORM, "methods"); //$NON-NLS-1$
        assertTrue(methods, methods.contains("previousRevision: git HEAD")); //$NON-NLS-1$
        assertTrue(methods, methods.contains("modulePath: " + ITEM_FORM)); //$NON-NLS-1$
        assertTrue(methods, methods.contains("source: probe")); //$NON-NLS-1$
        assertTrue(methods, methods.contains("container: " + ITEM_CONTAINER)); //$NON-NLS-1$
        assertTrue(methods, methods.contains("hasChanges: true")); //$NON-NLS-1$
        assertTrue(methods, methods.contains("totalChangedMethods: 2")); //$NON-NLS-1$
        assertTrue(methods, methods.contains("### Procedure Старая (modified)")); //$NON-NLS-1$
        assertTrue(methods, methods.contains("### Procedure Новая (added)")); //$NON-NLS-1$
        assertTrue(methods, methods.contains("+	// строка добавлена")); //$NON-NLS-1$

        String unified = diff(ITEM_FORM, "unified"); //$NON-NLS-1$
        assertTrue(unified, unified.contains("previousRevision: git HEAD")); //$NON-NLS-1$
        assertTrue(unified, unified.contains("+Процедура Новая()")); //$NON-NLS-1$
        assertTrue(unified, unified.contains("hasChanges: true")); //$NON-NLS-1$
    }

    /**
     * The revision of the container holds the module as it was, not as the provider reads it now,
     * so a module left untouched answers as identical rather than as changed.
     */
    @Test
    public void anUntouchedProvidedModuleIsIdenticalToTheRevision()
    {
        String answer = diff(ITEM_FORM, "summary"); //$NON-NLS-1$
        assertTrue(answer, answer.contains("previousRevision: git HEAD")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("hasChanges: false")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("identical to the previous revision (git HEAD)")); //$NON-NLS-1$
    }

    // -- The three answers of a provider --

    /**
     * A provider that does not read previous revisions is named, and the answer says there is
     * nothing to compare with rather than calling the module new.
     */
    @Test
    public void aProviderThatDoesNotReadPreviousRevisionsSaysSo()
    {
        String answer = diff(NO_HISTORY, "summary"); //$NON-NLS-1$
        assertTrue(answer, answer.contains("outcome: PROVIDER_UNSUPPORTED")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("source: no-history-probe")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("container: " + NO_HISTORY_CONTAINER)); //$NON-NLS-1$
        assertTrue(answer,
            answer.contains("the provider NoHistoryProbe does not read previous revisions")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("isNewFile")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("hasChanges")); //$NON-NLS-1$
    }

    /**
     * A provider whose module is not in the revision of its container answers as new: the revision
     * was read and the module is not in it.
     */
    @Test
    public void aModuleTheContainerRevisionDoesNotHoldIsNew() throws Exception
    {
        writeWorking(NEW_CONTAINER, container("форма: NewForm", OLD_MODULE)); //$NON-NLS-1$

        String answer = diff(NEW_FORM, "summary"); //$NON-NLS-1$
        assertTrue(answer, answer.contains("isNewFile: true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("source: probe")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("addedMethodCount: 1")); //$NON-NLS-1$
        assertTrue(answer,
            answer.contains("Module is new (the container's previous revision holds no such module)")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("outcome:")); //$NON-NLS-1$
    }

    /**
     * A provider that cannot parse the revision answers with a read error naming what was thrown,
     * not with an empty comparison.
     */
    @Test
    public void aProviderThatCannotParseTheRevisionAnswersWithTheError()
    {
        PROBE.parseFailure = new IOException("probe cannot parse the container"); //$NON-NLS-1$
        String answer = diff(ITEM_FORM, "summary"); //$NON-NLS-1$
        assertTrue(answer, answer.contains("outcome: READ_ERROR")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("the previous revision could not be read")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("probe cannot parse the container")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("the module is not reported as new")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("isNewFile")); //$NON-NLS-1$
    }

    /**
     * The fields a provider requires of every answer about its module are in the diff answer too,
     * in every mode and in the new-module answer: an answer that names the module and leaves them
     * out is a different answer about the same module.
     */
    @Test
    public void theFieldsAProviderRequiresAreInEveryAnswer() throws Exception
    {
        writeWorking(NEW_CONTAINER, container("форма: NewForm", OLD_MODULE)); //$NON-NLS-1$

        for (String mode : List.of("summary", "unified", "methods")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String answer = diff(ITEM_FORM, mode);
            assertTrue(mode, answer.contains("formName: ItemForm")); //$NON-NLS-1$
            assertTrue(mode, answer.contains("probeField: kept")); //$NON-NLS-1$
        }

        String newModule = diff(NEW_FORM, "summary"); //$NON-NLS-1$
        assertTrue(newModule, newModule.contains("isNewFile: true")); //$NON-NLS-1$
        assertTrue(newModule, newModule.contains("formName: ItemForm")); //$NON-NLS-1$
        assertTrue(newModule, newModule.contains("probeField: kept")); //$NON-NLS-1$
    }

    /**
     * A module whose provider has no container file has no revision to ask for, and the answer says
     * which case that is.
     */
    @Test
    public void aModuleWithoutAContainerSaysThereIsNothingToAsk()
    {
        PROBE.containerless = true;
        String answer = diff(ITEM_FORM, "summary"); //$NON-NLS-1$
        assertTrue(answer, answer.contains("outcome: NO_CONTAINER")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("no container file")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("isNewFile")); //$NON-NLS-1$
    }

    // -- An ordinary file keeps the answers it had --

    /**
     * A module that has a file of its own is still compared with HEAD of that file, and the byte
     * order mark of the committed text is not mistaken for content: unchanged answers as unchanged.
     */
    @Test
    public void anOrdinaryFileUnchangedIsIdenticalToHead()
    {
        String answer = diff(PLAIN, "summary"); //$NON-NLS-1$
        assertTrue(answer, answer.contains("previousRevision: git HEAD")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("hasChanges: false")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("identical to the previous revision (git HEAD)")); //$NON-NLS-1$
        // A file that has a file of its own is nobody's provided module.
        assertFalse(answer, answer.contains("source:")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("container:")); //$NON-NLS-1$
    }

    /**
     * The current text of an ordinary file comes from the file and the previous text from HEAD, so
     * an edit made after the commit is what the diff shows.
     */
    @Test
    public void anOrdinaryFileIsComparedWithHead() throws Exception
    {
        writeWorking(PLAIN, PLAIN_CHANGED);

        String summary = diff(PLAIN, "summary"); //$NON-NLS-1$
        assertTrue(summary, summary.contains("previousRevision: git HEAD")); //$NON-NLS-1$
        assertTrue(summary, summary.contains("hasChanges: true")); //$NON-NLS-1$
        assertTrue(summary, summary.contains("addedMethodCount: 1")); //$NON-NLS-1$

        String unified = diff(PLAIN, "unified"); //$NON-NLS-1$
        assertTrue(unified, unified.contains("previousRevision: git HEAD")); //$NON-NLS-1$
        assertTrue(unified, unified.contains("+Процедура Новая()")); //$NON-NLS-1$
        assertTrue(unified, unified.contains("+	Возврат 2;")); //$NON-NLS-1$
    }

    // -- No repository is not a new file --

    /**
     * A module whose project is in no repository at all is not reported as new: no comparison was
     * made, and the answer names the outcome and the reason.
     */
    @Test
    public void aModuleOutsideAnyRepositoryIsNotANewFile()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NO_REPO_PROJECT); //$NON-NLS-1$
        params.put("modulePath", LONE); //$NON-NLS-1$
        String answer = new DiffModuleTool().execute(params);

        assertTrue(answer, answer.contains("outcome: NOT_UNDER_GIT")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("no git repository holds it")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("the module is not reported as new")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("isNewFile")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("hasChanges")); //$NON-NLS-1$
    }

    // -- Helpers --

    /** Runs the tool over one module of the fixture project. */
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

    private static File file(String srcRelative)
    {
        return project.getFile(new org.eclipse.core.runtime.Path("src").append(srcRelative)) //$NON-NLS-1$
            .getLocation().toFile();
    }

    private static void writeCommitted(String srcRelative, String text) throws IOException
    {
        writeCommitted(projectDir, srcRelative, text);
    }

    private static void writeCommitted(Path root, String srcRelative, String text) throws IOException
    {
        Path target = root.resolve("src").resolve(srcRelative); //$NON-NLS-1$
        Files.createDirectories(target.getParent());
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    /** Writes a file of the working tree with a stamp that is newer than the one before it. */
    private static void writeWorking(String srcRelative, String text) throws IOException
    {
        Path target = project.getFile(new org.eclipse.core.runtime.Path("src").append(srcRelative)) //$NON-NLS-1$
            .getLocation().toPath();
        Files.writeString(target, text, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis() + (stamp++)));
    }

    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        // The files were written before the project existed, so the workspace is told about them
        // here: the tool asks the workspace for the file before it asks any provider.
        opened.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        assertNotNull(opened);
        return opened;
    }

    private static void deleteProject(IProject project) throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
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
}
