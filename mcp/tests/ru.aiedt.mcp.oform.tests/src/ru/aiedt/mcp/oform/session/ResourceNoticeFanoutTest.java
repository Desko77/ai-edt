/** AI-EDT Ordinary Forms - Tests. Copyright (C) 2026 Desko77. AGPL-3.0-or-later. */
package ru.aiedt.mcp.oform.session;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.junit.Test;

public class ResourceNoticeFanoutTest
{
    private static OpenFormSessions.Notice receiver(FormEditingSession expected, IFile file,
        AtomicInteger count, java.util.concurrent.atomic.AtomicReference<CountDownLatch> changes)
    {
        return new OpenFormSessions.Notice() {
            public void moved(FormEditingSession s, IFile a, IFile b) { }
            public void removed(FormEditingSession s, IFile f) { }
            public void changed(FormEditingSession s, IFile f) {
                if (s == expected && file.equals(f)) {
                    count.incrementAndGet();
                    changes.get().countDown();
                }
            }
        };
    }
    @Test public void externalModuleRefreshRetainsDirtyLayoutAndOpeningWritesNothing() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtNoticeDirty", SessionProbe.fixture("fixtures/item-form-v27.form"), "\r\n");
        byte[] before = probe.bytes();
        FormEditingSession session = OpenFormSessions.open(probe.file());
        try {
            session.snapshot();
            assertArrayEquals(before, probe.bytes());
            assertTrue(session.edit("set_property", java.util.Map.of("property", "title", "value", "Dirty layout")).done);
            String dirty = session.currentFormText();
            ru.aiedt.mcp.oform.V8Container container = ru.aiedt.mcp.oform.V8Container.read(probe.bytes());
            container.find("module").setData("Процедура External()\r\nКонецПроцедуры\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            probe.anotherWriterWrites(container.write());
            session.reconcile();
            assertEquals(dirty, session.currentFormText());
            assertTrue(session.formChanged()); assertFalse(session.moduleChanged());
            assertTrue(session.currentModuleText().contains("External"));
        } finally { OpenFormSessions.release(session); probe.dispose(); }
    }

    private static void writeExternalModule(SessionProbe probe, String module) throws Exception
    {
        IFile file = probe.file();
        long beforeTimestamp = file.getLocalTimeStamp();
        IFileInfo beforeInfo = EFS.getStore(file.getLocationURI()).fetchInfo();
        byte[] before = probe.bytes();
        ru.aiedt.mcp.oform.V8Container container = ru.aiedt.mcp.oform.V8Container.read(before);
        container.find("module").setData(module.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] changed = container.write();
        assertFalse("the external edit must change the container bytes", java.util.Arrays.equals(before, changed));
        // RefreshLocalVisitor can return early on an unchanged lastModified, regardless of size.
        // Set the timestamp before refreshing and check what Eclipse's filesystem actually sees.
        java.nio.file.Path location = file.getLocation().toFile().toPath();
        Files.write(location, changed);
        long requestedTimestamp = beforeTimestamp + TimeUnit.MINUTES.toMillis(2);
        Files.setLastModifiedTime(location, FileTime.fromMillis(requestedTimestamp));
        IFileInfo afterInfo = EFS.getStore(file.getLocationURI()).fetchInfo();
        String metadata = "workspaceBefore=" + beforeTimestamp + ", diskBefore=" + beforeInfo.getLastModified()
            + ", requested=" + requestedTimestamp + ", diskAfter=" + afterInfo.getLastModified()
            + ", sizeBefore=" + beforeInfo.getLength() + ", sizeAfter=" + afterInfo.getLength();
        assertNotEquals("refresh must observe a different disk timestamp: " + metadata,
            beforeTimestamp, afterInfo.getLastModified());
        assertEquals("the external bytes must be present before refresh: " + metadata,
            changed.length, afterInfo.getLength());
        file.refreshLocal(IResource.DEPTH_ZERO, null);
        assertEquals("refresh must record the observed disk timestamp: " + metadata,
            afterInfo.getLastModified(), file.getLocalTimeStamp());
    }

    @Test public void twoEditorsReceiveContentNoticesAndDisposedEditorDoesNot() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtNoticeFanout", SessionProbe.fixture("fixtures/item-form-v27.form"), "\r\n");
        FormEditingSession session = OpenFormSessions.open(probe.file());
        AtomicInteger first = new AtomicInteger(); AtomicInteger second = new AtomicInteger();
        var firstChange = new java.util.concurrent.atomic.AtomicReference<>(new CountDownLatch(1));
        var secondChange = new java.util.concurrent.atomic.AtomicReference<>(new CountDownLatch(1));
        OpenFormSessions.Notice a = receiver(session, probe.file(), first, firstChange);
        OpenFormSessions.Notice b = receiver(session, probe.file(), second, secondChange);
        try {
            OpenFormSessions.addNotice(a); OpenFormSessions.addNotice(b);
            writeExternalModule(probe, "// first external edit\r\n".repeat(128));
            assertTrue("first editor must receive the first content delta", firstChange.get().await(5, TimeUnit.SECONDS));
            assertTrue("second editor must receive the first content delta", secondChange.get().await(5, TimeUnit.SECONDS));
            assertTrue(first.get() > 0); assertEquals(first.get(), second.get());
            OpenFormSessions.removeNotice(a);
            int stopped = first.get();
            int remainingBefore = second.get();
            secondChange.set(new CountDownLatch(1));
            writeExternalModule(probe, "// second external edit with a different length\r\n".repeat(256));
            assertTrue("remaining editor must receive the second content delta", secondChange.get().await(5, TimeUnit.SECONDS));
            assertEquals(stopped, first.get()); assertTrue(second.get() > remainingBefore);
        } finally {
            OpenFormSessions.removeNotice(a); OpenFormSessions.removeNotice(b);
            OpenFormSessions.release(session); probe.dispose();
        }
    }
}
