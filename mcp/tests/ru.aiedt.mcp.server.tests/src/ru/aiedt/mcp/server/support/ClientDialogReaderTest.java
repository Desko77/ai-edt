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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
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

    /** What the reader returns when a login dialog is holding the client. */
    private static final String HELD_WINDOW = "{\"windows\":[{\"pid\":10," //$NON-NLS-1$
        + "\"className\":\"V8TopLevelFrameSDIsec\",\"title\":\"held\"," //$NON-NLS-1$
        + "\"texts\":[\"Type a password\"],\"buttons\":[\"OK\"],\"modal\":true}]}"; //$NON-NLS-1$

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
     * A window's texts are another program's content: a long one is cut and marked, and past the
     * limit the rest are counted in one last entry rather than listed.
     */
    @Test
    public void aWindowsTextsAreBoundedInLengthAndNumber()
    {
        StringBuilder texts = new StringBuilder('"' + "x".repeat(2000) + '"'); //$NON-NLS-1$
        for (int i = 1; i < ClientDialogReader.MAX_STRINGS + 3; i++)
        {
            texts.append(",\"line ").append(i).append('"'); //$NON-NLS-1$
        }
        ClientDialogReader.Outcome read = ClientDialogReader.parse("{\"windows\":[{\"pid\":1,\"title\":\"held\"," //$NON-NLS-1$
            + "\"texts\":[" + texts + "],\"buttons\":[],\"modal\":true}]}"); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> read0 = read.windows().get(0).texts();
        assertEquals(ClientDialogReader.MAX_STRINGS + 1, read0.size());
        assertEquals("x".repeat(ClientDialogReader.MAX_STRING_CHARS) + "...", read0.get(0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("line 1", read0.get(1)); //$NON-NLS-1$
        assertEquals("(3 more not shown)", read0.get(ClientDialogReader.MAX_STRINGS)); //$NON-NLS-1$
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
     * under the plugin state location otherwise. Neither is created by the lookup: a read that
     * never runs, on a machine with no PowerShell or no Windows, leaves no directory behind.
     *
     * @throws IOException when the temporary directories cannot be created
     */
    @Test
    public void snapshotsGoToTheReceiptDirectoryWhenThereIsOne() throws IOException
    {
        Path root = Files.createTempDirectory("aiedt-receipts"); //$NON-NLS-1$
        Path state = Files.createDirectory(root.resolve("state")); //$NON-NLS-1$
        Path receipt = root.resolve("run-receipts"); //$NON-NLS-1$
        Path created = null;
        try
        {
            assertEquals(receipt, ClientDialogReader.imageDirectory(receipt, state));
            assertFalse("naming the receipt directory must not create it", Files.exists(receipt)); //$NON-NLS-1$
            created = ClientDialogReader.imageDirectory(null, state);
            assertTrue(created.startsWith(state));
            assertTrue(created.getFileName().toString().startsWith("blocking-windows-")); //$NON-NLS-1$
            assertFalse("a temporary directory is created by the read, not by the lookup", //$NON-NLS-1$
                Files.exists(created));
        }
        finally
        {
            remove(created);
            remove(root);
        }
    }

    /**
     * A temporary snapshot directory exists for one read: an empty one is removed afterwards, and
     * one that holds a snapshot stays, because the answer names that file.
     *
     * @throws IOException when the temporary directories cannot be created
     */
    @Test
    public void aTemporarySnapshotDirectoryDoesNotOutliveAnEmptyRead() throws IOException
    {
        Path state = Files.createTempDirectory("aiedt-state"); //$NON-NLS-1$
        Path empty = null;
        Path kept = null;
        try
        {
            empty = ClientDialogReader.imageDirectory(null, state);
            ClientDialogReader.Outcome nothing = ClientDialogReader.capture(
                List.of(Long.valueOf(10L)), empty, windowsWith(Path.of("pwsh.exe")), //$NON-NLS-1$
                writingRunner(new ArrayList<>(), "{\"windows\":[]}"), Path.of("reader.ps1")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(nothing.error());
            assertFalse("a read that took no snapshot leaves no directory", Files.exists(empty)); //$NON-NLS-1$

            kept = ClientDialogReader.imageDirectory(null, state);
            ClientDialogReader.Outcome held = ClientDialogReader.capture(
                List.of(Long.valueOf(10L)), kept, windowsWith(Path.of("pwsh.exe")), //$NON-NLS-1$
                writingRunnerWithASnapshot(new ArrayList<>(), HELD_WINDOW), Path.of("reader.ps1")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(held.error());
            assertTrue("a snapshot keeps the directory it is in", Files.exists(kept.resolve("blocking-10-0.png"))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            remove(empty);
            remove(kept);
            remove(state);
        }
    }

    /**
     * Two reads give the script different labels for their snapshot names, so two reads writing
     * into one receipt directory at the same time do not overwrite each other's pictures.
     *
     * @throws IOException when the temporary directories cannot be created
     */
    @Test
    public void twoReadsNameTheirSnapshotsApart() throws IOException
    {
        Path images = Files.createTempDirectory("aiedt-receipts"); //$NON-NLS-1$
        try
        {
            List<String> first = new ArrayList<>();
            List<String> second = new ArrayList<>();
            ClientDialogReader.capture(List.of(Long.valueOf(10L)), images, windowsWith(Path.of("pwsh.exe")), //$NON-NLS-1$
                writingRunner(first, HELD_WINDOW), Path.of("reader.ps1")); //$NON-NLS-1$
            ClientDialogReader.capture(List.of(Long.valueOf(10L)), images, windowsWith(Path.of("pwsh.exe")), //$NON-NLS-1$
                writingRunner(second, HELD_WINDOW), Path.of("reader.ps1")); //$NON-NLS-1$

            String firstLabel = argumentOf(first, "-Label"); //$NON-NLS-1$
            String secondLabel = argumentOf(second, "-Label"); //$NON-NLS-1$
            assertFalse(firstLabel.isBlank());
            assertFalse("each read names its snapshots apart", firstLabel.equals(secondLabel)); //$NON-NLS-1$
        }
        finally
        {
            remove(images);
        }
    }

    /**
     * The temporary snapshot directories of earlier reads are removed once they are a day old; a
     * fresh one, the current one and a directory of any other name stay.
     *
     * @throws IOException when the temporary directories cannot be created
     */
    @Test
    public void oldTemporarySnapshotDirectoriesAreSwept() throws IOException
    {
        Path state = Files.createTempDirectory("aiedt-state"); //$NON-NLS-1$
        try
        {
            Path old = Files.createDirectories(state.resolve("blocking-windows-old")); //$NON-NLS-1$
            Files.write(old.resolve("blocking-a-10-0.png"), new byte[] { 1 }); //$NON-NLS-1$
            Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
            Path fresh = Files.createDirectories(state.resolve("blocking-windows-fresh")); //$NON-NLS-1$
            Path other = Files.createDirectories(state.resolve("receipts")); //$NON-NLS-1$
            Files.setLastModifiedTime(other, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
            Path current = Files.createDirectories(state.resolve("blocking-windows-current")); //$NON-NLS-1$
            Files.setLastModifiedTime(current, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

            int removed = ClientDialogReader.sweepStaleTemporaryDirectories(state, current,
                Instant.now().minus(Duration.ofHours(24)));

            assertEquals(1, removed);
            assertFalse(Files.exists(old));
            assertTrue(Files.exists(fresh));
            assertTrue(Files.exists(other));
            assertTrue(Files.exists(current));
        }
        finally
        {
            remove(state);
        }
    }

    /**
     * A read with no snapshot directory is given no {@code -ImageDir} argument at all. The script
     * declares that argument as a path, so an empty one is refused before the read starts and the
     * caller gets no windows for a reason that says nothing about the machine.
     *
     * @throws IOException when the temporary directories cannot be created
     */
    @Test
    public void aReadWithNoSnapshotDirectoryIsGivenNoImageArgument() throws IOException
    {
        List<String> command = new ArrayList<>();
        ClientDialogReader.Outcome read = ClientDialogReader.capture(List.of(Long.valueOf(10L)), null,
            windowsWith(Path.of("pwsh.exe")), writingRunner(command, HELD_WINDOW), //$NON-NLS-1$
            Path.of("reader.ps1")); //$NON-NLS-1$
        assertNull(read.error());
        assertEquals(1, read.windows().size());
        assertNull(read.windows().get(0).imageFile());
        assertFalse("an empty -ImageDir is not sent", command.contains("-ImageDir")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(command.contains("-ResultPath")); //$NON-NLS-1$
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

        String script = scriptOfTheBundle();
        assertTrue(script, script.contains("UIAutomationClient")); //$NON-NLS-1$
        assertTrue(script, script.contains("WriteAllText")); //$NON-NLS-1$
        assertTrue(script, script.contains("[System.Text.Encoding]::UTF8")); //$NON-NLS-1$
    }

    /**
     * The script draws the window itself instead of copying the screen: an application covering
     * the client must not end up in the picture that is handed to the caller.
     *
     * @throws IOException when the script cannot be read
     */
    @Test
    public void theScriptDrawsTheWindowInsteadOfTheScreen() throws IOException
    {
        String script = scriptOfTheBundle();
        assertTrue(script, script.contains("public static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags)")); //$NON-NLS-1$
        assertTrue(script, script.contains("PrintWindow($hwnd, $deviceContext, 2)")); //$NON-NLS-1$
        assertFalse("the screen is no longer copied", script.contains("CopyFromScreen")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The message of a 1C question box is the name of a pane rather than a text element, so the
     * walk collects pane names as message lines along with the text elements.
     *
     * @throws IOException when the script cannot be read
     */
    @Test
    public void theScriptReadsPaneNamesAsMessageLines() throws IOException
    {
        String script = scriptOfTheBundle();
        assertTrue(script, script.contains("[System.Windows.Automation.ControlType]::Pane.Id")); //$NON-NLS-1$
        assertTrue(script, script.contains("$typeId -eq $paneTypeId")); //$NON-NLS-1$
    }

    /**
     * The reader script as the bundle carries it.
     *
     * @return the script text
     * @throws IOException when the bundle entry or the file cannot be read
     */
    private static String scriptOfTheBundle() throws IOException
    {
        Bundle bundle = FrameworkUtil.getBundle(ClientDialogReader.class);
        assertNotNull(bundle);
        URL entry = bundle.getEntry(SCRIPT_ENTRY);
        assertNotNull(entry);
        try (InputStream stream = entry.openStream())
        {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
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
     * A runner that writes the document where the command says the result goes, the way the
     * script does, and reports that it finished.
     *
     * @param command the command is recorded into this list
     * @param json the document to write
     * @return the runner
     */
    private static ClientDialogReader.Runner writingRunner(List<String> command, String json)
    {
        return (arguments, timeoutSec) -> {
            command.addAll(arguments);
            try
            {
                Files.writeString(resultOf(arguments), json, StandardCharsets.UTF_8);
                return null;
            }
            catch (IOException cannotWrite)
            {
                return "The dialog reader could not be started: " + cannotWrite.getMessage(); //$NON-NLS-1$
            }
        };
    }

    /**
     * A runner that writes the document and saves a picture beside it, the way the script leaves
     * the snapshot it drew.
     *
     * @param command the command is recorded into this list
     * @param json the document to write
     * @return the runner
     */
    private static ClientDialogReader.Runner writingRunnerWithASnapshot(List<String> command,
        String json)
    {
        return (arguments, timeoutSec) -> {
            command.addAll(arguments);
            try
            {
                Files.writeString(resultOf(arguments), json, StandardCharsets.UTF_8);
                Path directory = Path.of(argumentOf(arguments, "-ImageDir")); //$NON-NLS-1$
                Files.write(directory.resolve("blocking-10-0.png"), new byte[] { 1 }); //$NON-NLS-1$
                return null;
            }
            catch (IOException cannotWrite)
            {
                return "The dialog reader could not be started: " + cannotWrite.getMessage(); //$NON-NLS-1$
            }
        };
    }

    /**
     * The file a command tells the script to write its document to.
     *
     * @param arguments one command
     * @return the path
     */
    private static Path resultOf(List<String> arguments)
    {
        return Path.of(argumentOf(arguments, "-ResultPath")); //$NON-NLS-1$
    }

    /**
     * The value of one argument of a command.
     *
     * @param arguments one command
     * @param name the argument name
     * @return the value that follows it
     */
    private static String argumentOf(List<String> arguments, String name)
    {
        return arguments.get(arguments.indexOf(name) + 1);
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
