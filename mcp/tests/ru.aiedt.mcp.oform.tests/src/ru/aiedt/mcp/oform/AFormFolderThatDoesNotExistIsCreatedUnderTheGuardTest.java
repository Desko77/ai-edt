/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A write whose form folder is not there yet: the creation and the import make the folder chain
 * and the file through the workspace, inside the write guard's rule - a rule another writer
 * holds over the destination keeps the write waiting, and the write lands only when that rule
 * goes, folders and all.
 */
public class AFormFolderThatDoesNotExistIsCreatedUnderTheGuardTest
{
    private static final String PROJECT = "AiEdtOformMissingFolderProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String MODULE = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    private static String layoutText;

    private static Path root;

    private static IProject project;

    private static OrdinaryFormCreation.ContainerWriter creationFiles;

    @BeforeClass
    public static void aProjectWithNoFormFolders() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-missing"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src")); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        creationFiles = creationFiles();
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
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
     * The creation write makes the whole missing folder chain and the container file, through the
     * workspace.
     */
    @Test
    public void theCreationWriteMakesItsFoldersAndItsFile() throws Exception
    {
        byte[] bytes = containerOf(MODULE);
        creationFiles.write("src/Catalogs/Products/Forms/NewForm/Form.oform", bytes); //$NON-NLS-1$

        IFile file = project.getFile(new org.eclipse.core.runtime.Path("src/Catalogs/Products/Forms/NewForm/Form.oform")); //$NON-NLS-1$
        assertTrue("the container was made through the workspace", file.exists()); //$NON-NLS-1$
        assertArrayEquals(bytes, Files.readAllBytes(file.getLocation().toFile().toPath()));
        assertTrue(folderOf("src/Catalogs/Products/Forms/NewForm").exists()); //$NON-NLS-1$
        assertTrue(folderOf("src/Catalogs/Products/Forms").exists()); //$NON-NLS-1$
    }

    /** The import write makes the folder chain and the file of a form that was not there. */
    @Test
    public void theImportWriteMakesItsFoldersAndItsFile() throws Exception
    {
        byte[] bytes = containerOf(MODULE);
        new OrdinaryFormImport.ProjectSpace(project)
            .write("Catalogs/Products/Forms/ImportedForm/Form.oform", null, bytes); //$NON-NLS-1$

        IFile file = project.getFile(new org.eclipse.core.runtime.Path("src/Catalogs/Products/Forms/ImportedForm/Form.oform")); //$NON-NLS-1$
        assertTrue("the container was made through the workspace", file.exists()); //$NON-NLS-1$
        assertArrayEquals(bytes, Files.readAllBytes(file.getLocation().toFile().toPath()));
        assertTrue(folderOf("src/Catalogs/Products/Forms/ImportedForm").exists()); //$NON-NLS-1$
    }

    /**
     * The creation write runs under the guard's rule: while another writer holds the rule the
     * destination's folder lies in, the write waits and makes nothing; once the rule goes, the
     * folders and the file arrive.
     */
    @Test
    public void theCreationWriteWaitsForTheRuleItsDestinationLiesIn() throws Exception
    {
        String path = "src/Catalogs/Products/Forms/HeldForm/Form.oform"; //$NON-NLS-1$
        IFile destination = project.getFile(new org.eclipse.core.runtime.Path(path));
        assertFalse(destination.exists());
        ISchedulingRule held = ResourcesPlugin.getWorkspace().getRuleFactory().createRule(destination);
        CountDownLatch releaseTheRule = new CountDownLatch(1);
        CountDownLatch ruleHeld = new CountDownLatch(1);
        AtomicReference<Exception> holderFailure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try
            {
                ResourcesPlugin.getWorkspace().run(monitor -> {
                    ruleHeld.countDown();
                    try
                    {
                        releaseTheRule.await();
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }, held, IResource.NONE, new NullProgressMonitor());
            }
            catch (CoreException e)
            {
                holderFailure.set(e);
            }
        }, "the-rule-holder"); //$NON-NLS-1$
        holder.start();
        assertTrue("the holder never took its rule", ruleHeld.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$

        CountDownLatch theWriteAnswered = new CountDownLatch(1);
        AtomicReference<Exception> writeFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try
            {
                creationFiles.write(path, containerOf(MODULE));
            }
            catch (IOException e)
            {
                writeFailure.set(e);
            }
            finally
            {
                theWriteAnswered.countDown();
            }
        }, "the-agent-writer"); //$NON-NLS-1$
        writer.start();
        assertFalse("the write waits behind the held rule", //$NON-NLS-1$
            theWriteAnswered.await(700, TimeUnit.MILLISECONDS));

        releaseTheRule.countDown();
        holder.join(10_000);
        writer.join(10_000);
        assertTrue("the holder failed", holderFailure.get() == null); //$NON-NLS-1$
        assertTrue("the write answered once the rule went", theWriteAnswered.getCount() == 0); //$NON-NLS-1$
        if (writeFailure.get() != null)
        {
            throw writeFailure.get();
        }
        assertTrue("the folders and the file arrived", destination.exists()); //$NON-NLS-1$
    }

    private IFolder folderOf(String path)
    {
        return project.getFolder(new org.eclipse.core.runtime.Path(path));
    }

    /**
     * Creates the production creation writer without invoking the metadata creation stage.
     *
     * @return the workspace container writer used by form creation
     * @throws Exception when its constructor cannot be resolved
     */
    private static OrdinaryFormCreation.ContainerWriter creationFiles() throws Exception
    {
        Class<?> type = Class.forName(OrdinaryFormCreation.class.getName() + "$ProjectFiles"); //$NON-NLS-1$
        var constructor = type.getDeclaredConstructor(IProject.class);
        constructor.setAccessible(true);
        return (OrdinaryFormCreation.ContainerWriter)constructor.newInstance(project);
    }

    private static byte[] containerOf(String module)
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(layoutText))); //$NON-NLS-1$
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
