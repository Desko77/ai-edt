/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * The dialog reader turns the script's JSON into windows, and it does not start PowerShell when
 * the machine cannot run it.
 */
public class ClientDialogReaderTest
{
    private static final String SCRIPT_ENTRY = "scripts/read-client-dialogs.ps1"; //$NON-NLS-1$

    /**
     * A shadow frame and a notification are not dialogs. A button caption keeps the word and loses
     * the padding the platform draws around it. The modal flag is the one the script reported.
     */
    @Test
    public void shadowsDropOutAndButtonNamesAreTrimmed()
    {
        String json = "{\"windows\":[" //$NON-NLS-1$
            + "{\"pid\":1,\"className\":\"V8Window\",\"title\":\"\",\"texts\":[\"shadow\"]," //$NON-NLS-1$
            + "\"buttons\":[\"no\"],\"modal\":false}," //$NON-NLS-1$
            + "{\"pid\":1,\"className\":\"V8NotificationWindow\",\"title\":\"toast\"," //$NON-NLS-1$
            + "\"texts\":[\"n\"],\"buttons\":[],\"modal\":false}," //$NON-NLS-1$
            + "{\"pid\":42,\"className\":\"V8TopLevelFrameSDIsec\"," //$NON-NLS-1$
            + "\"title\":\"\u0414\u043e\u0441\u0442\u0443\u043f \u043a \u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u043e\u043d\u043d\u043e\u0439 \u0431\u0430\u0437\u0435\"," //$NON-NLS-1$
            + "\"texts\":[\"\u041d\u0435\u0432\u0435\u0440\u043d\u043e \u0443\u043a\u0430\u0437\u0430\u043d \u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u0435\u043b\u044c \u0438\u043b\u0438 \u043f\u0430\u0440\u043e\u043b\u044c\"]," //$NON-NLS-1$
            + "\"buttons\":[\"   OK   \",\"  \",\" \u0412\u043e\u0439\u0442\u0438 \"],\"modal\":true," //$NON-NLS-1$
            + "\"imageFile\":\"C:\\\\shots\\\\login.png\"}," //$NON-NLS-1$
            + "{\"pid\":7,\"className\":\"V8Window\",\"title\":\"kept\",\"texts\":[]," //$NON-NLS-1$
            + "\"buttons\":[\"\u0417\u0430\u043a\u0440\u044b\u0442\u044c\"],\"modal\":false}" //$NON-NLS-1$
            + "]}"; //$NON-NLS-1$

        ClientDialogReader.Outcome read = ClientDialogReader.parse(json);
        assertNull(read.error());
        assertEquals(2, read.windows().size());

        ClientDialogReader.Window dialog = read.windows().get(0);
        assertEquals(42L, dialog.pid());
        assertEquals("\u0414\u043e\u0441\u0442\u0443\u043f \u043a \u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u043e\u043d\u043d\u043e\u0439 \u0431\u0430\u0437\u0435", dialog.title()); //$NON-NLS-1$
        assertEquals("\u041d\u0435\u0432\u0435\u0440\u043d\u043e \u0443\u043a\u0430\u0437\u0430\u043d \u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u0435\u043b\u044c \u0438\u043b\u0438 \u043f\u0430\u0440\u043e\u043b\u044c", dialog.texts().get(0)); //$NON-NLS-1$
        assertEquals(List.of("OK", "\u0412\u043e\u0439\u0442\u0438"), dialog.buttons()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(dialog.modal());
        assertEquals("C:\\shots\\login.png", dialog.imageFile()); //$NON-NLS-1$

        ClientDialogReader.Window titled = read.windows().get(1);
        assertEquals("kept", titled.title()); //$NON-NLS-1$
        assertFalse(titled.modal());
        assertEquals(List.of("\u0417\u0430\u043a\u0440\u044b\u0442\u044c"), titled.buttons()); //$NON-NLS-1$
    }

    /**
     * The first modal window is the one a message names, which is the top of the stack when the
     * reader listed windows top-down.
     */
    @Test
    public void theTopModalWindowIsTheFirstEnabledDialog()
    {
        ClientDialogReader.Outcome read = ClientDialogReader.parse("{\"windows\":[" //$NON-NLS-1$
            + "{\"pid\":1,\"title\":\"main\",\"texts\":[\"body\"],\"modal\":false}," //$NON-NLS-1$
            + "{\"pid\":1,\"title\":\"held\",\"texts\":[\"line\"],\"modal\":true}" //$NON-NLS-1$
            + "]}"); //$NON-NLS-1$
        assertEquals("held", ClientDialogReader.topModal(read.windows()).title()); //$NON-NLS-1$
        assertNull(ClientDialogReader.topModal(List.of()));
    }

    /**
     * An empty document and a document that is not JSON are an empty list plus a reason. A document
     * that lists no windows is a successful read of nothing.
     */
    @Test
    public void emptyAndBrokenJsonAreAnEmptyListWithAReason()
    {
        assertFailed(ClientDialogReader.parse(null), ClientDialogReader.noJson());
        assertFailed(ClientDialogReader.parse(""), ClientDialogReader.noJson()); //$NON-NLS-1$
        assertFailed(ClientDialogReader.parse("   "), ClientDialogReader.noJson()); //$NON-NLS-1$
        assertFailed(ClientDialogReader.parse("{"), ClientDialogReader.badJson()); //$NON-NLS-1$
        assertFailed(ClientDialogReader.parse("null"), ClientDialogReader.badJson()); //$NON-NLS-1$
        assertFailed(ClientDialogReader.parse("{}"), ClientDialogReader.noList()); //$NON-NLS-1$
        assertFailed(ClientDialogReader.parse("{\"error\":\"the script broke\"}"), "the script broke"); //$NON-NLS-1$ //$NON-NLS-2$

        ClientDialogReader.Outcome empty = ClientDialogReader.parse("{\"windows\":[]}"); //$NON-NLS-1$
        assertNull(empty.error());
        assertTrue(empty.windows().isEmpty());

        ClientDialogReader.Outcome reported = ClientDialogReader.parse(
            "{\"windows\":[],\"error\":\"the script broke\"}"); //$NON-NLS-1$
        assertFailed(reported, "the script broke"); //$NON-NLS-1$
    }

    /**
     * Off Windows the reader returns nothing and does not start a process.
     */
    @Test
    public void nothingIsStartedOffWindows()
    {
        AtomicBoolean started = new AtomicBoolean();
        ClientDialogReader.Outcome read = ClientDialogReader.capture(List.of(Long.valueOf(10L)),
            null, notWindows(), recordingRunner(started), Path.of("unused.ps1")); //$NON-NLS-1$
        assertFalse(started.get());
        assertTrue(read.windows().isEmpty());
        assertNull(read.error());
    }

    /**
     * With no PowerShell executable the reader returns nothing and does not start a process.
     */
    @Test
    public void nothingIsStartedWhenPowerShellIsMissing()
    {
        AtomicBoolean started = new AtomicBoolean();
        ClientDialogReader.Outcome read = ClientDialogReader.capture(List.of(Long.valueOf(10L)),
            null, windowsWithoutShell(), recordingRunner(started), Path.of("unused.ps1")); //$NON-NLS-1$
        assertFalse(started.get());
        assertTrue(read.windows().isEmpty());
        assertNull(read.error());
    }

    /**
     * The script is given at most fifteen seconds, the process ids, and a failure comes back as
     * the reason with no windows.
     *
     * @throws IOException when the temporary image directory cannot be created
     */
    @Test
    public void theScriptIsBoundedAndAFailureIsTheReason() throws IOException
    {
        assertTrue(ClientDialogReader.SCRIPT_TIMEOUT_SEC <= 15);
        Path images = Files.createTempDirectory("aiedt-dialog-images"); //$NON-NLS-1$
        try
        {
            AtomicInteger timeout = new AtomicInteger();
            List<String> command = new ArrayList<>();
            ClientDialogReader.Runner runner = (arguments, seconds) -> {
                command.addAll(arguments);
                timeout.set(seconds);
                return "The dialog reader timed out."; //$NON-NLS-1$
            };
            Path script = Path.of("reader.ps1"); //$NON-NLS-1$
            ClientDialogReader.Outcome read = ClientDialogReader.capture(
                List.of(Long.valueOf(10L), Long.valueOf(11L), Long.valueOf(0L)), images,
                windowsWith(Path.of("pwsh.exe")), runner, script); //$NON-NLS-1$
            assertEquals(ClientDialogReader.SCRIPT_TIMEOUT_SEC, timeout.get());
            assertTrue(command.contains("-File")); //$NON-NLS-1$
            assertTrue(command.contains("-STA")); //$NON-NLS-1$
            assertTrue(command.contains(script.toString()));
            assertTrue(command.contains("10,11")); //$NON-NLS-1$
            assertTrue(command.contains(images.toString()));
            assertEquals("The dialog reader timed out.", read.error()); //$NON-NLS-1$
            assertTrue(read.windows().isEmpty());
        }
        finally
        {
            remove(images);
        }
    }

    /**
     * {@code pwsh.exe} on {@code PATH} is preferred to Windows PowerShell, and neither file means
     * there is nothing to start. The lookup does not start the file it finds.
     *
     * @throws IOException when the temporary directories cannot be created
     */
    @Test
    public void pwshOnThePathBeatsWindowsPowerShell() throws IOException
    {
        Path pathDir = Files.createTempDirectory("aiedt-pwsh"); //$NON-NLS-1$
        Path root = Files.createTempDirectory("aiedt-sysroot"); //$NON-NLS-1$
        try
        {
            Files.createFile(pathDir.resolve("pwsh.exe")); //$NON-NLS-1$
            Path windowsShell = root.resolve("System32/WindowsPowerShell/v1.0/powershell.exe"); //$NON-NLS-1$
            Files.createDirectories(windowsShell.getParent());
            Files.createFile(windowsShell);

            assertEquals(pathDir.resolve("pwsh.exe"), //$NON-NLS-1$
                ClientDialogReader.findPowershell(pathDir.toString(), root.toString()));
            assertEquals(windowsShell, ClientDialogReader.findPowershell("", root.toString())); //$NON-NLS-1$
            assertNull(ClientDialogReader.findPowershell("", root.resolve("missing").toString())); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(ClientDialogReader.findPowershell(null, null));
        }
        finally
        {
            remove(pathDir);
            remove(root);
        }
    }

    /**
     * Snapshots go to the receipt directory when the caller has one, and to a temporary directory
     * under the plugin state location otherwise.
     *
     * @throws IOException when the temporary directories cannot be created
     */
    @Test
    public void snapshotsGoToTheReceiptDirectoryWhenThereIsOne() throws IOException
    {
        Path receipt = Files.createTempDirectory("aiedt-receipts"); //$NON-NLS-1$
        Path state = Files.createTempDirectory("aiedt-state"); //$NON-NLS-1$
        Path created = null;
        try
        {
            assertEquals(receipt, ClientDialogReader.imageDirectory(receipt, state));
            assertTrue(Files.isDirectory(receipt));
            created = ClientDialogReader.imageDirectory(null, state);
            assertTrue(created.startsWith(state));
            assertTrue(created.getFileName().toString().startsWith("blocking-windows-")); //$NON-NLS-1$
        }
        finally
        {
            remove(created);
            remove(receipt);
            remove(state);
        }
    }

    /**
     * The reader script is a bundle entry, and {@code build.properties} names it so the package
     * step puts it in the bundle.
     *
     * @throws IOException when the script or {@code build.properties} cannot be read
     */
    @Test
    public void theScriptIsPackagedWithTheBundle() throws IOException
    {
        Path project = bundleProject();
        assertNotNull("build.properties not found from " + System.getProperty("user.dir"), project); //$NON-NLS-1$ //$NON-NLS-2$
        String properties = Files.readString(project.resolve("build.properties"), StandardCharsets.UTF_8); //$NON-NLS-1$
        assertTrue(properties, properties.contains(SCRIPT_ENTRY));
        assertTrue(Files.isRegularFile(project.resolve(SCRIPT_ENTRY)));

        Bundle bundle = FrameworkUtil.getBundle(ClientDialogReader.class);
        assertNotNull(bundle);
        URL entry = bundle.getEntry(SCRIPT_ENTRY);
        assertNotNull(entry);
        String script;
        try (InputStream stream = entry.openStream())
        {
            script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(script.contains("UIAutomationClient")); //$NON-NLS-1$
        assertTrue(script.contains("WriteAllText")); //$NON-NLS-1$
        assertTrue(script.contains("CopyFromScreen")); //$NON-NLS-1$
        assertTrue(script.contains("[System.Text.Encoding]::UTF8")); //$NON-NLS-1$
    }

    /**
     * An outcome that failed has no windows and carries the reason.
     *
     * @param read the outcome
     * @param reason the reason it must carry
     */
    private static void assertFailed(ClientDialogReader.Outcome read, String reason)
    {
        assertTrue(read.windows().isEmpty());
        assertEquals(reason, read.error());
    }

    /**
     * A host that is not Windows. Asking it for PowerShell is a test failure: that lookup is the
     * start of a launch.
     *
     * @return the host
     */
    private static ClientDialogReader.Host notWindows()
    {
        return new ClientDialogReader.Host()
        {
            @Override
            public boolean windows()
            {
                return false;
            }

            @Override
            public Path powershell()
            {
                fail("PowerShell was looked up off Windows"); //$NON-NLS-1$
                return null;
            }
        };
    }

    /**
     * A Windows host with no PowerShell executable.
     *
     * @return the host
     */
    private static ClientDialogReader.Host windowsWithoutShell()
    {
        return windowsWith(null);
    }

    /**
     * A Windows host whose PowerShell is {@code shell}.
     *
     * @param shell the executable, or {@code null} when there is none
     * @return the host
     */
    private static ClientDialogReader.Host windowsWith(Path shell)
    {
        return new ClientDialogReader.Host()
        {
            @Override
            public boolean windows()
            {
                return true;
            }

            @Override
            public Path powershell()
            {
                return shell;
            }
        };
    }

    /**
     * A runner that records that it was called and does not start a process.
     *
     * @param started set when the runner is called
     * @return the runner
     */
    private static ClientDialogReader.Runner recordingRunner(AtomicBoolean started)
    {
        return (command, timeoutSec) -> {
            started.set(true);
            return "started"; //$NON-NLS-1$
        };
    }

    /**
     * The bundle project directory, walking up from the working directory.
     *
     * @return the directory, or {@code null} when this process is not standing in the tree
     */
    private static Path bundleProject()
    {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath(); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 16 && dir != null; i++)
        {
            Path nested = dir.resolve("mcp/bundles/ru.aiedt.mcp.server/build.properties"); //$NON-NLS-1$
            if (Files.isRegularFile(nested))
            {
                return nested.getParent();
            }
            Path beside = dir.resolve("bundles/ru.aiedt.mcp.server/build.properties"); //$NON-NLS-1$
            if (Files.isRegularFile(beside))
            {
                return beside.getParent();
            }
            if (dir.getFileName() != null && "ru.aiedt.mcp.server".equals(dir.getFileName().toString()) //$NON-NLS-1$
                && Files.isRegularFile(dir.resolve("build.properties"))) //$NON-NLS-1$
            {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Deletes a file or a directory tree.
     *
     * @param path the file, or {@code null}
     * @throws IOException when a file cannot be deleted
     */
    private static void remove(Path path) throws IOException
    {
        if (path == null || !Files.exists(path))
        {
            return;
        }
        if (Files.isDirectory(path))
        {
            try (var children = Files.list(path))
            {
                for (Path child : children.toList())
                {
                    remove(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
