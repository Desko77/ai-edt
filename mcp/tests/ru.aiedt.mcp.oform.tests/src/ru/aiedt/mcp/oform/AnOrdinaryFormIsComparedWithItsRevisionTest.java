/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.GitDiffUtils;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.toolkit.ops.DiffModuleTool;

/**
 * An ordinary form is compared with a revision of its container, and {@code diff_module} reads the
 * module out of that revision through this bundle's provider.
 *
 * <p>The repository is a real one made through JGit. The container is committed, and the working
 * tree is edited afterwards, so HEAD is the previous revision and the file on disk is the current
 * one. A second repository has no commit, and a third project sits outside git: both answers name
 * the outcome instead of failing.</p>
 */
public class AnOrdinaryFormIsComparedWithItsRevisionTest
{
    private static final String PROJECT = "AiEdtOformRevisionProbe"; //$NON-NLS-1$

    private static final String NO_HEAD_PROJECT = "AiEdtOformRevisionNoHead"; //$NON-NLS-1$

    private static final String LOOSE_PROJECT = "AiEdtOformRevisionLoose"; //$NON-NLS-1$

    private static final String ITEM_MODULE = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static final String ITEM_CONTAINER = "Catalogs/Products/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String FORM_FQN = "Catalog.Products.Form.ItemForm"; //$NON-NLS-1$

    private static final String LAYOUT = "{27,\n{1,\"ru\",\"Товары\"}\n}"; //$NON-NLS-1$

    private static final String LAYOUT_CHANGED = "{27,\n{1,\"ru\",\"Склады\"}\n}"; //$NON-NLS-1$

    private static final String MODULE =
        "Процедура Старая()\n\tВозврат 1;\nКонецПроцедуры\n"; //$NON-NLS-1$

    private static final String MODULE_CHANGED =
        "Процедура Старая()\n\tВозврат 1;\n\t// строка добавлена\nКонецПроцедуры\n"; //$NON-NLS-1$

    private static final String MODULE_OTHER =
        "Процедура Прежняя()\n\tВозврат 0;\nКонецПроцедуры\n"; //$NON-NLS-1$

    private static final String EXTRA = "picture"; //$NON-NLS-1$

    private static Path repoRoot;

    private static Path noHeadRoot;

    private static Path looseRoot;

    private static Path itemContainer;

    private static IProject project;

    private static IProject noHeadProject;

    private static IProject looseProject;

    private static byte[] committed;

    private static OrdinaryFormModuleProvider registeredHere;

    @BeforeClass
    public static void aCommittedContainerAndTwoProjectsWithNothingToCompare() throws Exception
    {
        committed = container(LAYOUT, MODULE, null);
        repoRoot = Files.createTempDirectory("aiedt-oform-diff"); //$NON-NLS-1$
        Path projectDir = repoRoot.resolve(PROJECT);
        itemContainer = writeContainer(projectDir, committed);
        try (Git git = Git.init().setDirectory(repoRoot.toFile()).call())
        {
            StoredConfig config = git.getRepository().getConfig();
            config.setBoolean("core", null, "autocrlf", false); //$NON-NLS-1$ //$NON-NLS-2$
            config.save();
            Files.writeString(repoRoot.resolve(".gitattributes"), "* -text\n", StandardCharsets.US_ASCII); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern(".").call(); //$NON-NLS-1$
            git.commit().setAuthor("Probe", "probe@example.invalid") //$NON-NLS-1$ //$NON-NLS-2$
                .setCommitter("Probe", "probe@example.invalid") //$NON-NLS-1$ //$NON-NLS-2$
                .setMessage("Committed form").call(); //$NON-NLS-1$
            String repoPath = PROJECT + "/src/" + ITEM_CONTAINER; //$NON-NLS-1$
            byte[] head = GitDiffUtils.headRevision(git.getRepository(), repoPath).bytes();
            assertArrayEquals(repoPath, committed, head);
        }
        project = openProject(PROJECT, projectDir);

        noHeadRoot = Files.createTempDirectory("aiedt-oform-diff-no-head"); //$NON-NLS-1$
        Path noHeadDir = noHeadRoot.resolve(NO_HEAD_PROJECT);
        writeContainer(noHeadDir, committed);
        try (Git git = Git.init().setDirectory(noHeadRoot.toFile()).call())
        {
            assertNotNull(git.getRepository());
        }
        noHeadProject = openProject(NO_HEAD_PROJECT, noHeadDir);

        looseRoot = Files.createTempDirectory("aiedt-oform-diff-loose"); //$NON-NLS-1$
        Path looseDir = looseRoot.resolve(LOOSE_PROJECT);
        writeContainer(looseDir, committed);
        looseProject = openProject(LOOSE_PROJECT, looseDir);

        if (ModuleSources.providers().stream().noneMatch(OrdinaryFormModuleProvider.class::isInstance))
        {
            registeredHere = new OrdinaryFormModuleProvider();
            ModuleSources.register(registeredHere);
        }
    }

    @AfterClass
    public static void theProjectsAndTheRepositoriesGo() throws Exception
    {
        if (registeredHere != null)
        {
            ModuleSources.unregister(registeredHere);
        }
        deleteProject(looseProject);
        deleteProject(noHeadProject);
        deleteProject(project);
        deleteTree(looseRoot);
        deleteTree(noHeadRoot);
        deleteTree(repoRoot);
    }

    /** Every comparison starts from the committed container. */
    @Before
    public void theWorkingTreeIsTheCommit() throws Exception
    {
        Files.write(itemContainer, committed);
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    // -- linesIn --

    /** A revision that holds another module yields that module's lines, not the live ones. */
    @Test
    public void thePreviousLinesComeOutOfARevisionThatHoldsAnotherModule() throws Exception
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, ITEM_MODULE);
        assertNotNull(module);
        List<String> previous = module.linesIn(container(LAYOUT, MODULE_OTHER, null));
        assertEquals(OrdinaryFormModule.splitLines(MODULE_OTHER), previous);
        assertFalse(previous.equals(module.lines()));
    }

    /** A revision whose container has no module entry has no module to return. */
    @Test
    public void aRevisionWithoutAModuleEntryHasNoLines() throws Exception
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, ITEM_MODULE);
        V8Container bare = V8Container.empty();
        bare.add(V8Container.Entry.of("form", withBom(LAYOUT))); //$NON-NLS-1$
        assertNull(module.linesIn(bare.write()));
    }

    /** Bytes that are not a container are an {@link IOException}, not a parsed module. */
    @Test
    public void bytesThatAreNotAContainerDoNotParse() throws Exception
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, ITEM_MODULE);
        try
        {
            module.linesIn(new byte[] { 1, 2, 3, 4 });
            fail("garbage parsed as a container"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            assertNotNull(e.getMessage());
        }
    }

    // -- diff_form on a pair of containers --

    /** Only the module entry differs: the layout does not, and the module is left to diff_module. */
    @Test
    public void aModuleChangeIsNamedForDiffModule() throws Exception
    {
        writeWorking(container(LAYOUT, MODULE_CHANGED, null));
        JsonObject answer = compared(FORM_FQN);
        assertFalse(answer.get("layoutChanged").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("moduleChanged").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, answer.getAsJsonArray("otherEntries").size()); //$NON-NLS-1$
        JsonObject pointer = answer.getAsJsonObject("diffModule"); //$NON-NLS-1$
        assertEquals("diff_module", pointer.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ITEM_MODULE, pointer.get("modulePath").getAsString()); //$NON-NLS-1$
        assertEquals(PROJECT, pointer.get("projectName").getAsString()); //$NON-NLS-1$
        assertFalse(answer.toString(), answer.toString().contains("строка добавлена")); //$NON-NLS-1$
        assertFalse(answer.toString(), answer.toString().contains("Возврат")); //$NON-NLS-1$
    }

    /** Only the layout entry differs, so there is no pointer at the module. */
    @Test
    public void aLayoutChangeLeavesTheModule() throws Exception
    {
        writeWorking(container(LAYOUT_CHANGED, MODULE, null));
        JsonObject answer = compared(FORM_FQN);
        assertTrue(answer.get("layoutChanged").getAsBoolean()); //$NON-NLS-1$
        assertFalse(answer.get("moduleChanged").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, answer.getAsJsonArray("otherEntries").size()); //$NON-NLS-1$
        assertNull(answer.get("diffModule")); //$NON-NLS-1$
    }

    /** Both entries differ, and the module is still named for diff_module. */
    @Test
    public void aChangeOfBothNamesBoth() throws Exception
    {
        writeWorking(container(LAYOUT_CHANGED, MODULE_CHANGED, null));
        JsonObject answer = compared(FORM_FQN);
        assertTrue(answer.get("layoutChanged").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("moduleChanged").getAsBoolean()); //$NON-NLS-1$
        assertEquals(ITEM_MODULE, answer.getAsJsonObject("diffModule").get("modulePath").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The working container is the commit: nothing changed. The address may be the module's. */
    @Test
    public void anUnchangedContainerComparesEqual()
    {
        String json = new OrdinaryFormsTool().execute(Map.of("operation", "diff_form", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "modulePath", ITEM_MODULE)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(json, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("FOUND", answer.getAsJsonObject("revision").get("outcome").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("git HEAD", answer.getAsJsonObject("revision").get("origin").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(answer.get("layoutChanged").getAsBoolean()); //$NON-NLS-1$
        assertFalse(answer.get("moduleChanged").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, answer.getAsJsonArray("otherEntries").size()); //$NON-NLS-1$
        assertNull(answer.get("diffModule")); //$NON-NLS-1$
    }

    /** An entry other than the layout and the module, added in the working tree, is named. */
    @Test
    public void anAddedEntryIsNamed() throws Exception
    {
        writeWorking(container(LAYOUT, MODULE, EXTRA));
        JsonObject answer = compared(FORM_FQN);
        assertFalse(answer.get("layoutChanged").getAsBoolean()); //$NON-NLS-1$
        assertFalse(answer.get("moduleChanged").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, answer.getAsJsonArray("otherEntries").size()); //$NON-NLS-1$
        assertEquals(EXTRA, answer.getAsJsonArray("otherEntries").get(0).getAsString()); //$NON-NLS-1$
    }

    // -- diff_module through this bundle's provider --

    /**
     * A method edited after the commit is what {@code diff_module} names: the previous text comes
     * out of HEAD through {@code linesIn}, the current text from the provider.
     */
    @Test
    public void aChangedMethodIsNamedByDiffModule() throws Exception
    {
        writeWorking(container(LAYOUT, MODULE_CHANGED, null));
        String methods = new DiffModuleTool().execute(Map.of("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", ITEM_MODULE, "mode", "methods")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(methods, methods.contains("previousRevision: git HEAD")); //$NON-NLS-1$
        assertTrue(methods, methods.contains("source: oform")); //$NON-NLS-1$
        assertTrue(methods, methods.contains("container: " + ITEM_CONTAINER)); //$NON-NLS-1$
        assertTrue(methods, methods.contains("modulePath: " + ITEM_MODULE)); //$NON-NLS-1$
        assertTrue(methods, methods.contains("hasChanges: true")); //$NON-NLS-1$
        assertTrue(methods, methods.contains("### Procedure Старая (modified)")); //$NON-NLS-1$
        assertTrue(methods, methods.contains("// строка добавлена")); //$NON-NLS-1$
    }

    // -- nothing to compare with --

    /** A repository with no commit names {@code NO_HEAD} and does not compare. */
    @Test
    public void aRepositoryWithoutACommitNamesTheOutcome()
    {
        JsonObject answer = comparedIn(NO_HEAD_PROJECT);
        JsonObject revision = answer.getAsJsonObject("revision"); //$NON-NLS-1$
        assertEquals("NO_HEAD", revision.get("outcome").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(revision.get("explanation").getAsString(), //$NON-NLS-1$
            revision.get("explanation").getAsString().contains("no commits")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.has("layoutChanged")); //$NON-NLS-1$
        assertFalse(answer.has("moduleChanged")); //$NON-NLS-1$
    }

    /** A project outside any repository names {@code NOT_UNDER_GIT} and does not compare. */
    @Test
    public void aProjectOutsideGitNamesTheOutcome()
    {
        JsonObject answer = comparedIn(LOOSE_PROJECT);
        JsonObject revision = answer.getAsJsonObject("revision"); //$NON-NLS-1$
        assertEquals("NOT_UNDER_GIT", revision.get("outcome").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(revision.get("explanation").getAsString(), //$NON-NLS-1$
            revision.get("explanation").getAsString().contains("no git repository")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.has("layoutChanged")); //$NON-NLS-1$
    }

    // -- Helpers --

    /** Runs {@code diff_form} by FQN against the committed project and checks the revision was found. */
    private static JsonObject compared(String formFqn)
    {
        String json = new OrdinaryFormsTool().execute(Map.of("operation", "diff_form", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", formFqn)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(json, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("FOUND", answer.getAsJsonObject("revision").get("outcome").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("git HEAD", answer.getAsJsonObject("revision").get("origin").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(FORM_FQN, answer.get("form").getAsString()); //$NON-NLS-1$
        return answer;
    }

    /** Runs {@code diff_form} in a project where the comparison cannot be made. */
    private static JsonObject comparedIn(String projectName)
    {
        String json = new OrdinaryFormsTool().execute(Map.of("operation", "diff_form", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", projectName, "formFqn", FORM_FQN)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(json, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        return answer;
    }

    private static void writeWorking(byte[] bytes) throws Exception
    {
        Files.write(itemContainer, bytes);
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    private static Path writeContainer(Path projectDir, byte[] bytes) throws IOException
    {
        Path file = projectDir.resolve("src").resolve(ITEM_CONTAINER); //$NON-NLS-1$
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return file;
    }

    private static byte[] container(String form, String module, String extra)
    {
        V8Container built = V8Container.empty();
        built.add(V8Container.Entry.of("form", withBom(form))); //$NON-NLS-1$
        built.add(V8Container.Entry.of("module", withBom(module))); //$NON-NLS-1$
        if (extra != null)
        {
            built.add(V8Container.Entry.of(extra, new byte[] { 1, 2, 3 }));
        }
        return built.write();
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

    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        opened.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        assertNotNull(opened);
        return opened;
    }

    private static void deleteProject(IProject opened) throws Exception
    {
        if (opened != null && opened.exists())
        {
            opened.delete(false, true, new NullProgressMonitor());
        }
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
