/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.SyncBaseline;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter;

/**
 * A container write marks the form's owner as changed in the synchronization baseline, so the
 * next update loads the object with the form: the owner's signature is blanked, every other
 * signature and the layout of the file stay, a second write finds it already marked, and the
 * writer's answer says so.
 *
 * <p>The baseline is written by this test in the layout EDT 2026 writes, into the working
 * location of a workspace project over a temporary directory.</p>
 */
public class AContainerWriteMarksItsOwnerForTheUpdateTest
{
    private static final String PROJECT = "AiEdtOformDeliveryProbe"; //$NON-NLS-1$

    private static final String INFOBASE = UUID.randomUUID().toString();

    private static final String OWNER_KEY = "src/Catalogs/Products/Products.mdo"; //$NON-NLS-1$

    private static final String FORM_KEY = "src/Catalogs/Products/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    private static Path index;

    @BeforeClass
    public static void aProjectWithAFormAndABaseline() throws Exception
    {
        root = Files.createTempDirectory("aiedt-oform-delivery"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path form = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom("{27," + CRLF + "{1}" + CRLF + "}"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        container.add(V8Container.Entry.of("module", withBom("Процедура А()" + CRLF + "КонецПроцедуры" + CRLF))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Files.write(form.resolve("Form.oform"), container.write()); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        Path store = SyncBaseline.workspaceStore(project).resolve(INFOBASE);
        Files.createDirectories(store);
        index = store.resolve(SyncBaseline.INDEX_FILE);
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(index.toFile())))
        {
            out.writeUTF("1.0"); //$NON-NLS-1$
            out.writeLong(1_700_000_000_000L);
            out.writeInt(3);
            out.writeUTF(OWNER_KEY);
            out.writeInt(4);
            out.write(new byte[] { 1, 2, 3, 4 });
            out.writeBoolean(true);
            out.writeUTF("2a8d5f3e-0f0c-4a4c-9a5d-2f6a4c8e9b10"); //$NON-NLS-1$
            out.writeUTF(FORM_KEY);
            out.writeInt(4);
            out.write(new byte[] { 5, 6, 7, 8 });
            out.writeBoolean(false);
            out.writeUTF("src/CommonModules/Other/Module.bsl"); //$NON-NLS-1$
            out.writeInt(2);
            out.write(new byte[] { 9, 9 });
            out.writeBoolean(false);
            out.writeUTF("generation-7"); //$NON-NLS-1$
            out.writeUTF(UUID.randomUUID().toString());
        }
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
     * The owner of a form is the {@code .mdo} beside its folder; a common form's owner is the
     * common form; a path that is not a form's has no owner.
     */
    @Test
    public void theOwnerIsTheMdoBesideTheForm()
    {
        assertEquals(OWNER_KEY, OrdinaryFormDelivery.ownerKeyOf("Catalogs/Products/Forms/ItemForm/Form.oform")); //$NON-NLS-1$
        assertEquals("src/CommonForms/Picker/Picker.mdo", OrdinaryFormDelivery.ownerKeyOf("CommonForms/Picker/Form.oform")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(OrdinaryFormDelivery.ownerKeyOf("Catalogs/Products/Products.mdo")); //$NON-NLS-1$
    }

    /**
     * Marking blanks the owner's signature and nothing else; marking again finds it marked; the
     * file keeps its layout, its resource id and its ids.
     */
    @Test
    public void markingBlanksTheOwnerAndNothingElse() throws Exception
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, "Catalogs/Products/Forms/ItemForm/Module.bsl"); //$NON-NLS-1$
        SyncBaseline.Index before = SyncBaseline.read(index);

        OrdinaryFormDelivery.Marked marked = OrdinaryFormDelivery.mark(project, module);
        assertEquals(OWNER_KEY, marked.ownerKey());
        assertEquals(1, marked.baselines());
        assertEquals(List.of(INFOBASE), marked.markedInfobases());
        assertTrue(marked.statement(), marked.statement().contains("reads as changed in 1 of 1")); //$NON-NLS-1$

        SyncBaseline.Index after = SyncBaseline.read(index);
        assertTrue(after.versioned);
        assertEquals("1.0", after.version); //$NON-NLS-1$
        assertEquals(before.timestamp, after.timestamp);
        assertEquals(before.keys, after.keys);
        assertArrayEquals(new byte[4], after.signatures.get(0));
        assertArrayEquals(new byte[] { 5, 6, 7, 8 }, after.signatures.get(1));
        assertArrayEquals(new byte[] { 9, 9 }, after.signatures.get(2));
        assertEquals("2a8d5f3e-0f0c-4a4c-9a5d-2f6a4c8e9b10", after.resourceUuids.get(0)); //$NON-NLS-1$
        assertEquals(before.generationId, after.generationId);
        assertEquals(before.configurationUuid, after.configurationUuid);

        OrdinaryFormDelivery.Marked again = OrdinaryFormDelivery.mark(project, module);
        assertTrue(again.markedInfobases().isEmpty());
        assertEquals(List.of(INFOBASE), again.alreadyMarked());
        assertTrue(again.statement(), again.statement().contains("reads as changed in 1 of 1")); //$NON-NLS-1$
        assertFalse(SyncBaseline.blankSignature(index, OWNER_KEY));
        assertFalse(SyncBaseline.blankSignature(index, "src/NoSuch.mdo")); //$NON-NLS-1$
    }

    /**
     * The writer's answer for a container write says what the update will carry.
     */
    @Test
    public void theWriterSaysWhatTheUpdateWillCarry()
    {
        String answer = new ModuleSourceWriter().execute(Map.of("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", "Catalogs/Products/Forms/ItemForm/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "append", "source", "Процедура Б()" + CRLF + "КонецПроцедуры")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("delivery: ")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("reads as changed in 1 of 1 infobase baseline")); //$NON-NLS-1$
    }

    /**
     * A baseline in a store shared between projects belongs to the project whose configuration
     * id it recorded: the id is read from the project's {@code Configuration.mdo}, and only the
     * baseline that recorded it is matched.
     */
    @Test
    public void aSharedStoreBaselineBelongsToTheProjectWhoseIdItRecorded() throws Exception
    {
        String ours = UUID.randomUUID().toString();
        Path mdo = root.resolve(PROJECT).resolve("src/Configuration/Configuration.mdo"); //$NON-NLS-1$
        Files.createDirectories(mdo.getParent());
        Files.writeString(mdo, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + CRLF //$NON-NLS-1$
            + "<mdclass:Configuration xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"" + ours //$NON-NLS-1$
            + "\">" + CRLF + "  <name>Probe</name>" + CRLF + "</mdclass:Configuration>" + CRLF); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Path shared = root.resolve("shared-store"); //$NON-NLS-1$
        Path oursIndex = writeIndex(shared.resolve(UUID.randomUUID().toString()), ours);
        writeIndex(shared.resolve(UUID.randomUUID().toString()), UUID.randomUUID().toString());
        Files.createDirectories(shared.resolve("no-index-here")); //$NON-NLS-1$

        assertEquals(ours, SyncBaseline.configurationUuid(project));
        assertEquals(List.of(oursIndex), SyncBaseline.matchingIndexes(shared, ours));
        assertTrue(SyncBaseline.matchingIndexes(shared, null).isEmpty());
        assertTrue(SyncBaseline.matchingIndexes(root.resolve("no-such-store"), ours).isEmpty()); //$NON-NLS-1$
    }

    /**
     * Two callers blanking two resources of one index at the same time both land: neither
     * write is lost to the other's, and the file reads afterwards.
     */
    @Test
    public void twoBlankingsOfOneIndexBothLand() throws Exception
    {
        Path store = root.resolve("racing-store").resolve(UUID.randomUUID().toString()); //$NON-NLS-1$
        Path racing = writeIndex(store, UUID.randomUUID().toString());
        for (int round = 0; round < 40; round++)
        {
            SyncBaseline.Index fresh = SyncBaseline.read(racing);
            fresh.signatures.set(0, new byte[] { 1, 2, 3, 4 });
            fresh.signatures.set(1, new byte[] { 5, 6, 7, 8 });
            SyncBaseline.write(fresh, racing);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread a = blanker(racing, OWNER_KEY, failure);
            Thread b = blanker(racing, FORM_KEY, failure);
            a.start();
            b.start();
            a.join();
            b.join();
            if (failure.get() != null)
            {
                throw new AssertionError("round " + round + ": " + failure.get(), failure.get()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            SyncBaseline.Index after = SyncBaseline.read(racing);
            assertArrayEquals("round " + round + ", owner", new byte[4], after.signatures.get(0)); //$NON-NLS-1$ //$NON-NLS-2$
            assertArrayEquals("round " + round + ", form", new byte[4], after.signatures.get(1)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try (var files = Files.list(store))
        {
            assertEquals(List.of(racing), files.toList());
        }
    }

    private static Thread blanker(Path index, String key, java.util.concurrent.atomic.AtomicReference<Throwable> failure)
    {
        return new Thread(() -> {
            try
            {
                SyncBaseline.blankSignature(index, key);
            }
            catch (Throwable t)
            {
                failure.compareAndSet(null, t);
            }
        });
    }

    /** Writes a versioned index with the two form keys and one more, recording a configuration id. */
    private static Path writeIndex(Path infobaseDir, String configurationUuid) throws Exception
    {
        Files.createDirectories(infobaseDir);
        Path file = infobaseDir.resolve(SyncBaseline.INDEX_FILE);
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file.toFile())))
        {
            out.writeUTF("1.0"); //$NON-NLS-1$
            out.writeLong(1_700_000_000_000L);
            out.writeInt(3);
            out.writeUTF(OWNER_KEY);
            out.writeInt(4);
            out.write(new byte[] { 1, 2, 3, 4 });
            out.writeBoolean(false);
            out.writeUTF(FORM_KEY);
            out.writeInt(4);
            out.write(new byte[] { 5, 6, 7, 8 });
            out.writeBoolean(false);
            out.writeUTF("src/CommonModules/Other/Module.bsl"); //$NON-NLS-1$
            out.writeInt(2);
            out.write(new byte[] { 9, 9 });
            out.writeBoolean(false);
            out.writeUTF("generation-7"); //$NON-NLS-1$
            out.writeUTF(configurationUuid);
        }
        return file;
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
