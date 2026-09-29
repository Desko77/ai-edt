/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;

/**
 * The 1C windows that are holding a client process when a run stops waiting.
 * <p>
 * The bundle has no Win32 or UI Automation API, so the read is a PowerShell script shipped with
 * the bundle and run as a child process. The script writes UTF-8 JSON to a file. Its stdout is
 * not the result: a console code page would change Cyrillic titles before they arrived.
 * </p>
 * <p>
 * Off Windows, or when neither {@code pwsh.exe} nor Windows PowerShell can be found, the read is
 * an empty list and nothing is started. A script that fails or outlives its budget is an empty
 * list plus a reason, and that reason never fails the run that asked.
 * </p>
 */
public final class ClientDialogReader
{
    /** Bundle entry of the reader script, also named by {@code build.properties}. */
    static final String SCRIPT_ENTRY = "scripts/read-client-dialogs.ps1"; //$NON-NLS-1$

    /**
     * How long the script may run. A dialog read that takes longer than this is not a read the
     * caller can wait out: the client is stopped either way.
     */
    static final int SCRIPT_TIMEOUT_SEC = 15;

    /** Win32 class of the shadow frames drawn around a 1C window. */
    static final String SHADOW_CLASS = "V8Window"; //$NON-NLS-1$

    /** Win32 class of a 1C notification, which is not a dialog. */
    static final String NOTIFICATION_CLASS = "V8NotificationWindow"; //$NON-NLS-1$

    /** Answer member carrying the windows that were holding the run. */
    public static final String WINDOWS = "blockingWindows"; //$NON-NLS-1$

    /** Answer member carrying why the windows could not be read. */
    public static final String ERROR = "blockingWindowsError"; //$NON-NLS-1$

    /** Name prefix of the temporary snapshot directory a read falls back to. */
    private static final String TEMPORARY_DIR_PREFIX = "blocking-windows-"; //$NON-NLS-1$

    private static final String NO_JSON = "The dialog reader returned no JSON."; //$NON-NLS-1$

    private static final String BAD_JSON = "The dialog reader returned JSON that could not be read."; //$NON-NLS-1$

    private static final String NO_LIST = "The dialog reader returned JSON without a windows list."; //$NON-NLS-1$

    private static final String TIMED_OUT = "The dialog reader timed out."; //$NON-NLS-1$

    private ClientDialogReader()
    {
    }

    /**
     * One window that was holding a client.
     */
    public static final class Window
    {
        private final long pid;

        private final String title;

        private final List<String> texts;

        private final List<String> buttons;

        private final boolean modal;

        private final String imageFile;

        /**
         * Records one window as the reader reported it.
         *
         * @param pid the process that owns the window
         * @param title the window title; empty when the frame has none
         * @param texts the message lines, in the order they were read
         * @param buttons the button captions, already trimmed
         * @param modal whether this window is enabled while its owner is not
         * @param imageFile the PNG of the window, or {@code null} when none was taken
         */
        Window(long pid, String title, List<String> texts, List<String> buttons, boolean modal,
            String imageFile)
        {
            this.pid = pid;
            this.title = title == null ? "" : title; //$NON-NLS-1$
            this.texts = texts == null ? List.of() : List.copyOf(texts);
            this.buttons = buttons == null ? List.of() : List.copyOf(buttons);
            this.modal = modal;
            this.imageFile = imageFile == null || imageFile.isBlank() ? null : imageFile;
        }

        /**
         * The process that owns the window.
         *
         * @return the process id
         */
        public long pid()
        {
            return pid;
        }

        /**
         * The window title.
         *
         * @return the title, or the empty string when the frame has none
         */
        public String title()
        {
            return title;
        }

        /**
         * The message lines read from the window.
         *
         * @return the lines, never {@code null}
         */
        public List<String> texts()
        {
            return texts;
        }

        /**
         * The button captions, with surrounding space removed.
         *
         * @return the captions, never {@code null}
         */
        public List<String> buttons()
        {
            return buttons;
        }

        /**
         * Whether this window is the one the user has to answer: it is enabled, and the window
         * that owns it is not.
         *
         * @return {@code true} for the holding dialog
         */
        public boolean modal()
        {
            return modal;
        }

        /**
         * The PNG written for this window.
         *
         * @return the file, or {@code null} when no snapshot was taken
         */
        public String imageFile()
        {
            return imageFile;
        }

        /**
         * The window as the timeout answer publishes it.
         *
         * @return the members {@code pid}, {@code title}, {@code texts}, {@code buttons},
         *         {@code modal} and, when a snapshot was taken, {@code imageFile}
         */
        public Map<String, Object> fields()
        {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("pid", Long.valueOf(pid)); //$NON-NLS-1$
            fields.put("title", title); //$NON-NLS-1$
            fields.put("texts", texts); //$NON-NLS-1$
            fields.put("buttons", buttons); //$NON-NLS-1$
            fields.put("modal", Boolean.valueOf(modal)); //$NON-NLS-1$
            if (imageFile != null)
            {
                fields.put("imageFile", imageFile); //$NON-NLS-1$
            }
            return fields;
        }
    }

    /**
     * What a read came to: the holding windows, or the reason there are none.
     */
    public static final class Outcome
    {
        private final List<Window> windows;

        private final String error;

        /**
         * Records one read.
         *
         * @param windows the holding windows; empty when nothing was holding the run or the read
         *            failed
         * @param error why the read failed, or {@code null} when it did not
         */
        private Outcome(List<Window> windows, String error)
        {
            this.windows = windows == null ? List.of() : List.copyOf(windows);
            this.error = error == null || error.isBlank() ? null : error;
        }

        /**
         * A read that found nothing and did not fail.
         *
         * @return an empty outcome
         */
        public static Outcome none()
        {
            return new Outcome(List.of(), null);
        }

        /**
         * A read that failed.
         *
         * @param reason why, told to the caller; a blank reason becomes a generic one
         * @return an empty window list with that reason
         */
        public static Outcome failed(String reason)
        {
            return new Outcome(List.of(),
                reason == null || reason.isBlank() ? "The dialog reader failed." : reason); //$NON-NLS-1$
        }

        /**
         * The holding windows.
         *
         * @return the windows, never {@code null}
         */
        public List<Window> windows()
        {
            return windows;
        }

        /**
         * Why the windows could not be read.
         *
         * @return the reason, or {@code null} when the read did not fail
         */
        public String error()
        {
            return error;
        }

        /**
         * The windows as the timeout answer publishes them.
         *
         * @return one map per window, in the order the reader returned them
         */
        public List<Map<String, Object>> fields()
        {
            List<Map<String, Object>> rows = new ArrayList<>(windows.size());
            for (Window window : windows)
            {
                rows.add(window.fields());
            }
            return rows;
        }
    }

    /**
     * Where this process is running and which PowerShell it can start.
     */
    interface Host
    {
        /**
         * Whether the process is running on Windows.
         *
         * @return {@code true} on Windows
         */
        boolean windows();

        /**
         * The PowerShell executable to start.
         *
         * @return {@code pwsh.exe} when it is on {@code PATH}, otherwise Windows PowerShell, or
         *         {@code null} when neither exists
         */
        Path powershell();
    }

    /**
     * Starts the reader script and waits for it.
     */
    @FunctionalInterface
    interface Runner
    {
        /**
         * Runs one command.
         *
         * @param command the executable and its arguments
         * @param timeoutSec how long to wait, in seconds
         * @return {@code null} when the process finished, or why it did not
         */
        String run(List<String> command, int timeoutSec);
    }

    /**
     * Reads the windows of one live process and of the processes it has started.
     * <p>
     * Called while the process is still alive. Stopping it first would close the dialog the read
     * exists to report.
     * </p>
     *
     * @param process the client process
     * @param imageDirectory where the PNGs are written; created when it is missing
     * @return the holding windows, or an empty list and a reason
     */
    public static Outcome ofProcess(Process process, Path imageDirectory)
    {
        if (process == null)
        {
            return Outcome.none();
        }
        List<Long> ids = new ArrayList<>();
        ids.add(Long.valueOf(process.pid()));
        try
        {
            process.descendants().forEach(handle -> ids.add(Long.valueOf(handle.pid())));
        }
        catch (RuntimeException unavailable)
        {
            // The parent is still named. A descendant query that fails must not skip the read.
        }
        return capture(ids, imageDirectory);
    }

    /**
     * Reads the holding windows of the named processes.
     *
     * @param processIds the client and the processes it started
     * @param imageDirectory where the PNGs are written
     * @return the holding windows, or an empty list and a reason
     */
    public static Outcome capture(Collection<Long> processIds, Path imageDirectory)
    {
        return capture(processIds, imageDirectory, liveHost(), liveRunner(), null);
    }

    /**
     * Where the snapshots of one run are written.
     * <p>
     * The receipt directory when the caller has one, and a temporary directory under the plugin
     * state location otherwise. Nothing is created here: the read takes the directory, and a read
     * that never runs - a machine that is not Windows, or one with no PowerShell - leaves no
     * directory behind. A temporary directory the read created and left empty is removed again.
     * </p>
     *
     * @param receiptDirectory the run's receipt directory, or {@code null} when there is none
     * @param stateLocation the plugin state location, used only when the receipt directory is
     *            absent
     * @return the directory the PNGs go to
     */
    public static Path imageDirectory(Path receiptDirectory, Path stateLocation)
    {
        if (receiptDirectory != null)
        {
            return receiptDirectory;
        }
        Path parent = stateLocation != null
            ? stateLocation : Path.of(System.getProperty("java.io.tmpdir", ".")); //$NON-NLS-1$ //$NON-NLS-2$
        return parent.resolve(TEMPORARY_DIR_PREFIX + UUID.randomUUID());
    }

    /**
     * The directory a timeout of {@code tool} writes its snapshots into.
     * <p>
     * The tool's receipt directory when the plugin can name one, and a temporary directory under
     * the plugin state location otherwise. Nothing is created here: the directory is made by the
     * read, so a machine where the windows cannot be read keeps no directory at all.
     * </p>
     *
     * @param tool the tool name the receipt directory is named after
     * @return the directory, or a system temporary directory when the plugin has no state
     */
    public static Path imagesFor(String tool)
    {
        Path receipts = null;
        try
        {
            receipts = RunReceipts.directoryFor(tool);
        }
        catch (RuntimeException unavailable)
        {
            // The state location cannot be asked while the plugin is stopping.
            receipts = null;
        }
        return imageDirectory(receipts, receipts == null ? stateLocation() : null);
    }

    /**
     * The plugin state location, when the plugin can name one.
     *
     * @return the directory, or {@code null} when there is no plugin to ask
     */
    private static Path stateLocation()
    {
        try
        {
            Activator activator = Activator.getDefault();
            if (activator == null || activator.getStateLocation() == null)
            {
                return null;
            }
            return activator.getStateLocation().toFile().toPath();
        }
        catch (RuntimeException unavailable)
        {
            // Stopping, or a runtime with no bundle. The caller falls back to a system temp dir.
            return null;
        }
    }

    /**
     * The host this process is running on.
     *
     * @return the live host
     */
    private static Host liveHost()
    {
        return new Host()
        {
            /** {@inheritDoc} */
            @Override
            public boolean windows()
            {
                return windowsHost();
            }

            /** {@inheritDoc} */
            @Override
            public Path powershell()
            {
                return powershellExecutable();
            }
        };
    }

    /**
     * The runner that starts a real child process.
     *
     * @return the live runner
     */
    private static Runner liveRunner()
    {
        return ClientDialogReader::runScript;
    }

    /**
     * Reads the holding windows, using {@code host} and {@code runner} in place of the machine.
     * <p>
     * Off Windows, or when {@code host} has no PowerShell, {@code runner} is not called.
     * </p>
     *
     * @param processIds the processes to read
     * @param imageDirectory where the PNGs are written
     * @param host where the process is running
     * @param runner starts the script
     * @param script the script file, or {@code null} to take the one shipped in the bundle
     * @return the holding windows, or an empty list and a reason
     */
    static Outcome capture(Collection<Long> processIds, Path imageDirectory, Host host,
        Runner runner, Path script)
    {
        String joined = joinPids(processIds);
        if (joined.isEmpty() || host == null || !host.windows())
        {
            return Outcome.none();
        }
        Path shell = host.powershell();
        if (shell == null)
        {
            return Outcome.none();
        }
        Path scriptFile = null;
        boolean deleteScript = false;
        Path resultFile = null;
        boolean createdDirectory = false;
        try
        {
            if (script != null)
            {
                scriptFile = script;
            }
            else
            {
                scriptFile = materializeScript();
                deleteScript = true;
            }
            if (imageDirectory != null)
            {
                createdDirectory = !Files.exists(imageDirectory);
                Files.createDirectories(imageDirectory);
            }
            resultFile = Files.createTempFile("aiedt-client-dialogs-", ".json"); //$NON-NLS-1$ //$NON-NLS-2$
            List<String> command = readerCommand(shell, scriptFile, joined, imageDirectory, resultFile);
            String failure = runner.run(command, SCRIPT_TIMEOUT_SEC);
            if (failure != null)
            {
                return Outcome.failed(failure);
            }
            if (!Files.isRegularFile(resultFile))
            {
                return Outcome.failed(NO_JSON);
            }
            return parse(Files.readString(resultFile, StandardCharsets.UTF_8));
        }
        catch (IOException cannotRun)
        {
            return Outcome.failed("The dialog reader could not be started: " + cannotRun.getMessage()); //$NON-NLS-1$
        }
        finally
        {
            deleteQuietly(resultFile);
            if (deleteScript)
            {
                deleteQuietly(scriptFile);
            }
            discardTemporaryDirectory(imageDirectory, createdDirectory);
        }
    }

    /**
     * The command that runs the reader.
     *
     * @param shell the PowerShell executable
     * @param script the script file
     * @param pids the process ids, comma separated
     * @param imageDirectory where the PNGs are written, or {@code null} to report the windows
     *            without a picture
     * @param resultFile where the JSON is written
     * @return the command, one element per argument
     */
    private static List<String> readerCommand(Path shell, Path script, String pids,
        Path imageDirectory, Path resultFile)
    {
        List<String> command = new ArrayList<>();
        command.add(shell.toString());
        command.add("-NoProfile"); //$NON-NLS-1$
        command.add("-NonInteractive"); //$NON-NLS-1$
        command.add("-STA"); //$NON-NLS-1$
        command.add("-ExecutionPolicy"); //$NON-NLS-1$
        command.add("Bypass"); //$NON-NLS-1$
        command.add("-File"); //$NON-NLS-1$
        command.add(script.toString());
        command.add("-Pids"); //$NON-NLS-1$
        command.add(pids);
        if (imageDirectory != null)
        {
            // Left out rather than sent empty: the script declares this argument as a path.
            command.add("-ImageDir"); //$NON-NLS-1$
            command.add(imageDirectory.toString());
        }
        command.add("-ResultPath"); //$NON-NLS-1$
        command.add(resultFile.toString());
        return command;
    }

    /**
     * The process ids the script is given, skipping anything that is not a live process id.
     *
     * @param processIds the ids the caller named
     * @return the ids comma separated, or the empty string when none remain
     */
    private static String joinPids(Collection<Long> processIds)
    {
        if (processIds == null || processIds.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder joined = new StringBuilder();
        for (Long id : processIds)
        {
            if (id == null || id.longValue() <= 0)
            {
                continue;
            }
            if (joined.length() > 0)
            {
                joined.append(',');
            }
            joined.append(id.longValue());
        }
        return joined.toString();
    }

    /**
     * Parses the script's JSON into holding windows.
     * <p>
     * A shadow frame ({@code V8Window} with no title) and a notification
     * ({@code V8NotificationWindow}) are dropped. Button captions lose their surrounding space.
     * An empty document and a document that is not the expected object are an empty list plus a
     * reason. A document whose window list is empty is a successful read of nothing.
     * </p>
     *
     * @param json the document the script wrote, or {@code null}
     * @return the windows, or an empty list and a reason
     */
    public static Outcome parse(String json)
    {
        if (json == null || stripBom(json).isBlank())
        {
            return Outcome.failed(NO_JSON);
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(stripBom(json).trim());
            if (parsed == null || !parsed.isJsonObject())
            {
                return Outcome.failed(BAD_JSON);
            }
            JsonObject root = parsed.getAsJsonObject();
            String reported = textOf(root.get("error")); //$NON-NLS-1$
            JsonElement listed = root.get("windows"); //$NON-NLS-1$
            if (listed == null || !listed.isJsonArray())
            {
                return Outcome.failed(reported != null ? reported : NO_LIST);
            }
            List<Window> windows = new ArrayList<>();
            for (JsonElement element : listed.getAsJsonArray())
            {
                Window window = windowOf(element);
                if (window != null)
                {
                    windows.add(window);
                }
            }
            if (windows.isEmpty() && reported != null)
            {
                return Outcome.failed(reported);
            }
            return new Outcome(windows, null);
        }
        catch (RuntimeException unreadable)
        {
            return Outcome.failed(BAD_JSON);
        }
    }

    /**
     * The window the timeout message names: the first enabled dialog over a disabled owner, which
     * is the top one when the reader returned windows in top-down order.
     *
     * @param windows the windows the reader returned
     * @return that window, the first window when none is modal, or {@code null} when the list is
     *         empty
     */
    public static Window topModal(List<Window> windows)
    {
        if (windows == null || windows.isEmpty())
        {
            return null;
        }
        for (Window window : windows)
        {
            if (window != null && window.modal())
            {
                return window;
            }
        }
        return windows.get(0);
    }

    /**
     * One window from the document, or {@code null} when the element is a shadow, a notification,
     * or not an object.
     *
     * @param element one entry of {@code windows}
     * @return the window, or {@code null} when it is left out
     */
    private static Window windowOf(JsonElement element)
    {
        if (element == null || !element.isJsonObject())
        {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String title = textOf(object.get("title")); //$NON-NLS-1$
        if (title == null)
        {
            title = ""; //$NON-NLS-1$
        }
        String className = textOf(object.get("className")); //$NON-NLS-1$
        if (isShadow(className, title) || isNotification(className))
        {
            return null;
        }
        long pid = 0L;
        JsonElement pidElement = object.get("pid"); //$NON-NLS-1$
        if (pidElement != null && pidElement.isJsonPrimitive() && pidElement.getAsJsonPrimitive().isNumber())
        {
            pid = pidElement.getAsLong();
        }
        boolean modal = false;
        JsonElement modalElement = object.get("modal"); //$NON-NLS-1$
        if (modalElement != null && modalElement.isJsonPrimitive()
            && modalElement.getAsJsonPrimitive().isBoolean())
        {
            modal = modalElement.getAsBoolean();
        }
        return new Window(pid, title, stringsOf(object.get("texts"), false), //$NON-NLS-1$
            stringsOf(object.get("buttons"), true), modal, textOf(object.get("imageFile"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether the frame is one of the four shadow windows drawn around a 1C dialog.
     *
     * @param className the Win32 class
     * @param title the window title
     * @return {@code true} for {@code V8Window} with no title
     */
    private static boolean isShadow(String className, String title)
    {
        return SHADOW_CLASS.equals(className) && (title == null || title.isBlank());
    }

    /**
     * Whether the frame is a 1C notification rather than a dialog.
     *
     * @param className the Win32 class
     * @return {@code true} for {@code V8NotificationWindow}
     */
    private static boolean isNotification(String className)
    {
        return NOTIFICATION_CLASS.equals(className);
    }

    /**
     * The strings in a JSON array.
     *
     * @param element the array, or anything else
     * @param trim whether each string loses its surrounding space, and an empty result is dropped
     * @return the strings, never {@code null}
     */
    private static List<String> stringsOf(JsonElement element, boolean trim)
    {
        if (element == null || !element.isJsonArray())
        {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray())
        {
            String text = textOf(item);
            if (text == null)
            {
                continue;
            }
            if (trim)
            {
                text = text.trim();
                if (text.isEmpty())
                {
                    continue;
                }
            }
            values.add(text);
        }
        return values;
    }

    /**
     * A JSON string, or {@code null} when the element is not a string.
     *
     * @param element the element
     * @return the text, or {@code null}
     */
    private static String textOf(JsonElement element)
    {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isString())
        {
            return null;
        }
        return element.getAsString();
    }

    /**
     * The document with a leading byte-order mark removed.
     *
     * @param json the text the script wrote
     * @return the text without a leading BOM
     */
    private static String stripBom(String json)
    {
        if (json != null && !json.isEmpty() && json.charAt(0) == '\uFEFF')
        {
            return json.substring(1);
        }
        return json;
    }

    /**
     * Whether this process is running on Windows.
     *
     * @return {@code true} when {@code os.name} names Windows
     */
    private static boolean windowsHost()
    {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The PowerShell executable on this machine.
     *
     * @return {@code pwsh.exe} from {@code PATH} when it is there, otherwise Windows PowerShell
     *         under {@code SystemRoot}, or {@code null} when neither exists
     */
    private static Path powershellExecutable()
    {
        String systemRoot = System.getenv("SystemRoot"); //$NON-NLS-1$
        if (systemRoot == null || systemRoot.isEmpty())
        {
            systemRoot = System.getenv("WINDIR"); //$NON-NLS-1$
        }
        return findPowershell(System.getenv("PATH"), systemRoot); //$NON-NLS-1$
    }

    /**
     * The PowerShell executable named by a path list and a system root.
     * <p>
     * {@code pwsh.exe} wins when both are present. The lookup only checks that the file exists; it
     * does not start it.
     * </p>
     *
     * @param pathEnv the {@code PATH} value, or {@code null}
     * @param systemRoot the Windows directory, or {@code null}
     * @return the executable, or {@code null} when neither is present
     */
    static Path findPowershell(String pathEnv, String systemRoot)
    {
        if (pathEnv != null)
        {
            for (String entry : pathEnv.split(java.io.File.pathSeparator))
            {
                Path found = pwshIn(entry);
                if (found != null)
                {
                    return found;
                }
            }
        }
        if (systemRoot == null || systemRoot.isBlank())
        {
            return null;
        }
        try
        {
            Path windowsPowerShell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "powershell.exe"); //$NON-NLS-1$
            if (Files.isRegularFile(windowsPowerShell))
            {
                return windowsPowerShell;
            }
        }
        catch (InvalidPathException notAPath)
        {
            // A system root that is not a path names no executable.
            return null;
        }
        return null;
    }

    /**
     * {@code pwsh.exe} in one {@code PATH} entry.
     *
     * @param entry one entry, possibly quoted
     * @return the file, or {@code null} when this entry does not contain it
     */
    private static Path pwshIn(String entry)
    {
        if (entry == null)
        {
            return null;
        }
        String trimmed = entry.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"')
        {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty())
        {
            return null;
        }
        try
        {
            Path candidate = Path.of(trimmed).resolve("pwsh.exe"); //$NON-NLS-1$
            if (Files.isRegularFile(candidate))
            {
                return candidate;
            }
        }
        catch (InvalidPathException notAPath)
        {
            // A PATH entry that is not a path is not an executable.
            return null;
        }
        return null;
    }

    /**
     * Copies the bundled script out to a file {@code -File} can name.
     *
     * @return the temporary script file
     * @throws IOException when the bundle has no script or the copy fails
     */
    private static Path materializeScript() throws IOException
    {
        Path temp = Files.createTempFile("aiedt-client-dialogs-", ".ps1"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(temp, scriptBytes());
        return temp;
    }

    /**
     * The reader script, from the bundle or, when the bundle is not installed, from the project
     * tree beside this class's sources.
     *
     * @return the script bytes
     * @throws IOException when the script cannot be read
     */
    private static byte[] scriptBytes() throws IOException
    {
        Bundle bundle = FrameworkUtil.getBundle(ClientDialogReader.class);
        if (bundle != null)
        {
            URL url = bundle.getEntry(SCRIPT_ENTRY);
            if (url != null)
            {
                try (InputStream stream = url.openStream())
                {
                    return stream.readAllBytes();
                }
            }
        }
        Path onDisk = scriptOnDisk();
        if (onDisk != null)
        {
            return Files.readAllBytes(onDisk);
        }
        throw new IOException("The dialog reader script is not in the bundle."); //$NON-NLS-1$
    }

    /**
     * The script file in a working copy, for a runtime whose bundle has not been packaged.
     *
     * @return the file, or {@code null} when this process is not standing in the project tree
     */
    private static Path scriptOnDisk()
    {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath(); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 8 && dir != null; i++)
        {
            Path nested = dir.resolve("mcp/bundles/ru.aiedt.mcp.server").resolve(SCRIPT_ENTRY); //$NON-NLS-1$
            if (Files.isRegularFile(nested))
            {
                return nested;
            }
            Path beside = dir.resolve("bundles/ru.aiedt.mcp.server").resolve(SCRIPT_ENTRY); //$NON-NLS-1$
            if (Files.isRegularFile(beside))
            {
                return beside;
            }
            if (dir.getFileName() != null
                && "ru.aiedt.mcp.server".equals(dir.getFileName().toString())) //$NON-NLS-1$
            {
                Path local = dir.resolve(SCRIPT_ENTRY);
                if (Files.isRegularFile(local))
                {
                    return local;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Starts the script and waits for it to finish.
     *
     * @param command the executable and its arguments
     * @param timeoutSec how long to wait, in seconds
     * @return {@code null} when the process finished, or why it did not
     */
    private static String runScript(List<String> command, int timeoutSec)
    {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try
        {
            Process process = builder.start();
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS))
            {
                process.destroyForcibly();
                return TIMED_OUT;
            }
            return null;
        }
        catch (IOException cannotStart)
        {
            return "The dialog reader could not be started: " + cannotStart.getMessage(); //$NON-NLS-1$
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            return "The dialog reader was interrupted."; //$NON-NLS-1$
        }
    }

    /**
     * Removes a temporary snapshot directory this read created and left empty.
     * <p>
     * A temporary directory exists for one read, so one that holds no snapshot is debris: the
     * answer names the picture it took, and a read that took none has nothing to point at. A
     * directory that holds a file stays where it is. A receipt directory is never removed: it is
     * shared with the JSON receipts, which a run finishing at that moment may be writing.
     * </p>
     *
     * @param directory the directory the read was given, or {@code null}
     * @param createdByThisRead whether the directory did not exist before this read
     */
    private static void discardTemporaryDirectory(Path directory, boolean createdByThisRead)
    {
        if (!createdByThisRead || directory == null || directory.getFileName() == null
            || !directory.getFileName().toString().startsWith(TEMPORARY_DIR_PREFIX))
        {
            return;
        }
        try (Stream<Path> entries = Files.list(directory))
        {
            if (entries.findAny().isPresent())
            {
                return;
            }
        }
        catch (IOException notListed)
        {
            // A directory that cannot be listed is left alone.
            return;
        }
        try
        {
            Files.deleteIfExists(directory);
        }
        catch (IOException stillNeeded)
        {
            // Something landed in it between the listing and the delete. It stays.
        }
    }

    /**
     * Deletes a temporary file and ignores a failure to do so.
     * <p>
     * The read has already been taken. A file the operating system has not released yet must not
     * replace that result with an error.
     * </p>
     *
     * @param file the file, or {@code null}
     */
    private static void deleteQuietly(Path file)
    {
        if (file == null)
        {
            return;
        }
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException stillThere)
        {
            // The result is already in hand. A temp file left behind is not the read failing.
        }
    }

    /**
     * The reason a blank document is given.
     *
     * @return the sentence an empty document produces
     */
    static String noJson()
    {
        return NO_JSON;
    }

    /**
     * The reason a document that does not parse is given.
     *
     * @return the sentence a broken document produces
     */
    static String badJson()
    {
        return BAD_JSON;
    }

    /**
     * The reason an object with no windows list is given.
     *
     * @return the sentence that document produces
     */
    static String noList()
    {
        return NO_LIST;
    }
}
