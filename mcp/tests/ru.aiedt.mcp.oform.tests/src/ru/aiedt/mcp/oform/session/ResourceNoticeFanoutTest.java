/** AI-EDT Ordinary Forms - Tests. Copyright (C) 2026 Desko77. AGPL-3.0-or-later. */
package ru.aiedt.mcp.oform.session;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IFile;
import org.junit.Test;

public class ResourceNoticeFanoutTest
{
    private static OpenFormSessions.Notice receiver(AtomicInteger count)
    {
        return new OpenFormSessions.Notice() {
            public void moved(FormEditingSession s, IFile a, IFile b) { }
            public void removed(FormEditingSession s, IFile f) { }
            public void changed(FormEditingSession s, IFile f) { count.incrementAndGet(); }
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
        // Refresh detects disk changes through metadata. Rewriting identical bytes can have the
        // same size and timestamp, so it need not produce a workspace content delta.
        byte[] before = probe.bytes();
        ru.aiedt.mcp.oform.V8Container container = ru.aiedt.mcp.oform.V8Container.read(before);
        container.find("module").setData(module.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] changed = container.write();
        assertNotEquals("the external edit must change the container size, not only its timestamp",
            before.length, changed.length);
        probe.anotherWriterWrites(changed);
    }

    @Test public void twoEditorsReceiveContentNoticesAndDisposedEditorDoesNot() throws Exception
    {
        SessionProbe probe = SessionProbe.with("AiEdtNoticeFanout", SessionProbe.fixture("fixtures/item-form-v27.form"), "\r\n");
        FormEditingSession session = OpenFormSessions.open(probe.file());
        AtomicInteger first = new AtomicInteger(); AtomicInteger second = new AtomicInteger();
        OpenFormSessions.Notice a = receiver(first); OpenFormSessions.Notice b = receiver(second);
        try {
            OpenFormSessions.addNotice(a); OpenFormSessions.addNotice(b);
            writeExternalModule(probe, "// first external edit\r\n".repeat(128));
            assertTrue(first.get() > 0); assertEquals(first.get(), second.get());
            OpenFormSessions.removeNotice(a);
            int stopped = first.get();
            writeExternalModule(probe, "// second external edit with a different length\r\n".repeat(256));
            assertEquals(stopped, first.get()); assertTrue(second.get() > stopped);
        } finally {
            OpenFormSessions.removeNotice(a); OpenFormSessions.removeNotice(b);
            OpenFormSessions.release(session); probe.dispose();
        }
    }
}
