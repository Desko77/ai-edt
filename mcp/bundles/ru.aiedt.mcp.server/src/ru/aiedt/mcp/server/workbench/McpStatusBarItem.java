/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.workbench;

import java.io.IOException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.MenuListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.McpHistory;
import ru.aiedt.mcp.server.McpHttpEndpoint;
import ru.aiedt.mcp.server.OperatorSignal;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.upkeep.ReleaseOffer;
import ru.aiedt.mcp.server.upkeep.ReleaseSweep;
import ru.aiedt.mcp.server.upkeep.UpkeepPolicy;
import ru.aiedt.mcp.server.wire.McpServerMeta;

/**
 * The trim-bar strip that shows what an agent is doing to this workspace right now.
 * <p>
 * The indicator is a short track, not a lamp: motion along it carries the state. Stopped leaves the
 * track bare. Listening parks a teal marker at its left end, still. A call in flight sends an amber
 * marker sweeping along the track, so activity reads as movement in a direction rather than as a
 * colour that has to be recognized - noticeable out of the corner of an eye, and unambiguous in
 * grayscale or to a colour-blind reader, neither of which is true of a red/green/yellow dot.
 * </p>
 * <p>
 * The slot on the right carries what is worth knowing at a glance, which is not the same thing at
 * rest and under load. While a call runs it counts the seconds it has been running. At rest it shows
 * the port instead - with several EDT sessions open, each on its own port, that is what tells you
 * which server this trim bar belongs to. The lifetime call count lives in the tooltip, where a
 * number nobody acts on belongs.
 * </p>
 * <p>
 * Clicking anywhere on the strip opens the menu: signals for a running call, start/stop/restart
 * otherwise. A background thread ticks and marshals every widget touch to the UI thread; nothing
 * here paints off it.
 * </p>
 */
public class McpStatusBarItem
    extends WorkbenchWindowControlContribution
{
    private static final int MAX_TOOL_NAME_LENGTH = 28;

    private static final int TOOL_NAME_TRUNCATED_LENGTH = 25;

    private static final int TRACK_WIDTH = 26;

    private static final int TRACK_HEIGHT = 6;

    private static final int MARKER_WIDTH = 10;

    /**
     * Sweep positions a marker passes through before it wraps back to the start. The marker can only
     * move when the tick fires, so this sets how far it jumps each time: too many steps and the
     * crawl reads as a still image, which is the one thing the moving marker exists to avoid.
     */
    private static final int SWEEP_STEPS = 6;

    private static final int INDICATOR_WIDTH_HINT = TRACK_WIDTH + 4;

    private static final int INDICATOR_HEIGHT_HINT = 14;

    private static final int UPDATE_INTERVAL_MS = 600;

    private static final double FONT_SCALE = 0.88;

    private static final String UPDATE_THREAD_NAME = "AI-EDT-Status-Update"; //$NON-NLS-1$


    /** Below this, an age is not worth a number. */
    private static final int JUST_NOW_SECONDS = 10;

    private static final int SECONDS_PER_MINUTE = 60;

    private static final int MINUTES_PER_HOUR = 60;

    private static final int HOURS_PER_DAY = 24;

    /** What the indicator is showing; drives both the marker's colour and whether it moves. */
    private enum Activity
    {
        STOPPED, LISTENING, WORKING
    }

    private volatile boolean disposed;

    private Activity activity = Activity.STOPPED;

    private int sweepPhase;

    private Composite container;

    private Canvas indicatorCanvas;

    private Label statusLabel;

    private Label counterLabel;

    private Font scaledFont;

    private Color trackColor;

    private Color restingColor;

    private Color workingColor;

    private Menu popupMenu;

    private Thread updateThread;

    /** Last width the trim row was told about; -1 until the strip has been laid out once. */
    private int lastPreferredWidth = -1;

    @Override
    public boolean isDynamic()
    {
        return true;
    }

    @Override
    protected Control createControl(Composite parent)
    {
        container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 2;
        layout.marginHeight = 0;
        layout.marginTop = 4;
        container.setLayout(layout);

        scaledFont = createScaledFont();
        buildIndicatorColors();
        createPopupMenu();

        indicatorCanvas = new Canvas(container, SWT.NONE);
        GridData indicatorData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        indicatorData.widthHint = INDICATOR_WIDTH_HINT;
        indicatorData.heightHint = INDICATOR_HEIGHT_HINT;
        indicatorCanvas.setLayoutData(indicatorData);
        indicatorCanvas.setMenu(popupMenu);
        indicatorCanvas.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseUp(MouseEvent e)
            {
                if (e.button == 1)
                {
                    popupMenu.setVisible(true);
                }
            }
        });
        indicatorCanvas.addPaintListener(this::paintIndicator);

        statusLabel = new Label(container, SWT.NONE);
        statusLabel.setText("AI-EDT"); //$NON-NLS-1$
        statusLabel.setFont(scaledFont);
        // Sized to its text, deliberately. A fixed slot wide enough for the longest tool name leaves
        // a gap the width of that name whenever nothing is running, and the port on the far side of
        // it ends up pushed past the edge of the trim bar - which is what a reserved 180px did.
        statusLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        counterLabel = new Label(container, SWT.RIGHT);
        counterLabel.setText("off"); //$NON-NLS-1$
        counterLabel.setFont(scaledFont);
        // Reserve the widest text this slot can ever hold. The trim bar measures the contribution
        // once, when the server is usually still down and the slot reads "off" - three characters.
        // Everything shown later is longer, and a slot sized for "off" truncated a port such as
        // ":12250" to the three characters that fitted.
        GridData counterData = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        counterData.widthHint = widestTextWidth(":65535", "199:59", "off"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        counterLabel.setLayoutData(counterData);

        // The trim bar does not repaint on the first contribution without this nudge.
        parent.getParent().setRedraw(true);

        updateStatus();
        startUpdateThread();

        return container;
    }

    @Override
    public void dispose()
    {
        // Set first: the background thread reads it to know it should stop touching widgets.
        disposed = true;
        if (updateThread != null)
        {
            updateThread.interrupt();
        }
        disposeResource(scaledFont);
        disposeResource(trackColor);
        disposeResource(restingColor);
        disposeResource(workingColor);
        if (popupMenu != null && !popupMenu.isDisposed())
        {
            popupMenu.dispose();
        }
        super.dispose();
    }

    /**
     * Redraws the indicator from the server's current state. Must run on the UI thread; the tick
     * thread reaches it only through {@code asyncExec}.
     */
    private void updateStatus()
    {
        if (disposed || container == null || container.isDisposed())
        {
            return;
        }

        McpHttpEndpoint server = Activator.getDefault() != null ? Activator.getDefault().getMcpServer() : null;
        boolean running = server != null && server.isRunning();
        long requests = server != null ? server.getRequestCount() : 0;
        // getPort() reports the port the server was last STARTED on, so it reads 0 on a fresh
        // install where nothing has started yet. The strip and the tooltip would then name port 0
        // as the address to connect to, which is not merely uninformative but wrong: Start will
        // use the configured port. Same fallback the menu applies.
        int startedPort = server != null ? server.getPort() : 0;
        int port = startedPort > 0 ? startedPort : storedPort();
        String toolName = server != null ? server.getCurrentToolName() : null;
        long seconds = server != null ? server.getToolExecutionSeconds() : 0;
        // A tool call still in flight is what "executing" means, not the listening socket. A Stop or
        // Restart drops the socket at once but cannot interrupt a call already inside a blocking EDT
        // operation, so there is a window where the server reads as not running while a call is still
        // finishing. Gating this on `running` would show a slate "stopped" square through that window
        // while the very same class's menu still (correctly) offers to interrupt the call. Checked
        // before the not-running branch for the same reason.
        boolean executing = toolName != null;

        if (executing)
        {
            activity = Activity.WORKING;
            // Only a working marker travels; parking the phase at zero otherwise means the marker is
            // always back at the start when a call begins, so the sweep reads from the same origin.
            sweepPhase = (sweepPhase + 1) % SWEEP_STEPS;
            statusLabel.setText("AI-EDT - " + shorten(toolName)); //$NON-NLS-1$
            counterLabel.setText(formatDuration(seconds));
        }
        else if (!running)
        {
            activity = Activity.STOPPED;
            sweepPhase = 0;
            statusLabel.setText(labelWithUpkeep());
            counterLabel.setText("off"); //$NON-NLS-1$
        }
        else
        {
            activity = Activity.LISTENING;
            sweepPhase = 0;
            statusLabel.setText(labelWithUpkeep());
            // The port, not a lifetime counter: with several EDT sessions open this is what says
            // which one you are looking at.
            counterLabel.setText(":" + port); //$NON-NLS-1$
        }
        if (!indicatorCanvas.isDisposed())
        {
            indicatorCanvas.redraw();
        }

        announceUpdateOnce();

        String tooltip = buildTooltip(activity, toolName, seconds, port, requests);
        indicatorCanvas.setToolTipText(tooltip);
        statusLabel.setToolTipText(tooltip);
        counterLabel.setToolTipText(tooltip);

        relayout();
    }

    /**
     * Re-lays out the strip, and the trim row too when the strip's own width changed.
     * <p>
     * Laying out only the container redistributes whatever width the trim handed out at creation.
     * That is enough for the counter, which now reserves its widest text, but not for the label: it
     * grows by the length of a tool name when a call starts. Without telling the row, the extra text
     * has nowhere to go and is simply clipped.
     * </p>
     */
    private void relayout()
    {
        container.layout(true);
        int width = container.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x;
        if (width == lastPreferredWidth)
        {
            return;
        }
        lastPreferredWidth = width;
        Composite trimRow = container.getParent();
        if (trimRow != null && !trimRow.isDisposed())
        {
            trimRow.layout(true, true);
        }
    }

    /**
     * Measures the widest of the given strings in the strip's own font.
     *
     * @param samples the candidate texts
     * @return the width in pixels
     */
    private int widestTextWidth(String... samples)
    {
        GC gc = new GC(container);
        try
        {
            gc.setFont(scaledFont);
            int widest = 0;
            for (String sample : samples)
            {
                widest = Math.max(widest, gc.textExtent(sample).x);
            }
            return widest;
        }
        finally
        {
            gc.dispose();
        }
    }

    /**
     * Paints the track and, unless the server is down, the marker riding it.
     *
     * @param event the paint event
     */
    private void paintIndicator(PaintEvent event)
    {
        if (trackColor == null || trackColor.isDisposed())
        {
            return;
        }
        GC gc = event.gc;
        gc.setAntialias(SWT.ON);

        org.eclipse.swt.graphics.Rectangle bounds = indicatorCanvas.getBounds();
        int left = Math.max(0, (bounds.width - TRACK_WIDTH) / 2);
        int top = Math.max(0, (bounds.height - TRACK_HEIGHT) / 2);

        gc.setBackground(trackColor);
        gc.fillRoundRectangle(left, top, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT, TRACK_HEIGHT);

        if (activity == Activity.STOPPED)
        {
            // A bare track: nothing is riding it because nothing can.
            return;
        }

        int travel = TRACK_WIDTH - MARKER_WIDTH;
        int offset = activity == Activity.WORKING ? travel * sweepPhase / (SWEEP_STEPS - 1) : 0;
        gc.setBackground(activity == Activity.WORKING ? workingColor : restingColor);
        gc.fillRoundRectangle(left + offset, top, MARKER_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT,
            TRACK_HEIGHT);
    }

    private static String shorten(String toolName)
    {
        if (toolName.length() > MAX_TOOL_NAME_LENGTH)
        {
            return toolName.substring(0, TOOL_NAME_TRUNCATED_LENGTH) + "..."; //$NON-NLS-1$
        }
        return toolName;
    }

    private static String formatDuration(long seconds)
    {
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        return String.format("%02d:%02d", minutes, remainder); //$NON-NLS-1$
    }

    /**
     * Builds the hover text.
     * <p>
     * It opens with the subject rather than with the product name - which is already written beside
     * the pointer - so the first line answers what is happening to this workspace. The affordance
     * comes second, naming what a click actually offers in this state instead of pointing at a menu.
     * The connection facts close it, as one line, carrying the endpoint an MCP client is configured
     * with rather than a bare port number.
     * </p>
     *
     * @param state    what the indicator is showing
     * @param toolName the running tool, when one is running
     * @param seconds  how long it has been running
     * @param port     the configured port
     * @param calls    calls served since this server came up
     * @return the tooltip text
     */
    private static String buildTooltip(Activity state, String toolName, long seconds, int port,
        long calls)
    {
        if (state == Activity.STOPPED)
        {
            return describeActivity(state, toolName, seconds)
                + "\nClick to open the endpoint on port " + port //$NON-NLS-1$
                + "\n\nbuild " + McpServerMeta.PLUGIN_VERSION; //$NON-NLS-1$
        }
        return describeActivity(state, toolName, seconds)
            + (state == Activity.WORKING
                ? "\nClick to step in: interrupt, send to background, hand off" //$NON-NLS-1$
                : "\nClick to stop or restart") //$NON-NLS-1$
            + "\n\n" + describeConnection(port, calls) + describeUpkeep(); //$NON-NLS-1$
    }

    /**
     * Raises the update notice, at most once for any one version.
     * <p>
     * Driven from the strip's own tick rather than from the code that finds the update, and that
     * buys the awkward part for nothing: the notice can only be shown while the IDE has an active
     * window, and a tick that finds no window simply does not mark the version as announced, so the
     * notice appears the next time the IDE is in front instead of being spent on an empty screen.
     * </p>
     * <p>
     * The version is recorded at the moment it goes on screen, not before. Recording it first would
     * spend the one announcement on a notice that never appeared.
     * </p>
     */
    private static void announceUpdateOnce()
    {
        IPreferenceStore store = Activator.getDefault() != null
            ? Activator.getDefault().getPreferenceStore() : null;
        if (store == null || !store.getBoolean(PrefKeys.PREF_UPKEEP_NOTIFY_POPUP))
        {
            return;
        }
        ReleaseOffer offer = ReleaseSweep.get().ledger().current();
        if (!UpkeepPolicy.shouldAnnounce(offer, true,
            store.getString(PrefKeys.PREF_UPKEEP_NOTIFIED_VERSION)))
        {
            return;
        }
        boolean shown = UpdateNoticePopup.show(offer,
            () -> runUpkeep("Installing the AI-EDT update", monitor -> { //$NON-NLS-1$
                ReleaseOffer after = ReleaseSweep.get().installNow(monitor);
                if (after.state() == ReleaseOffer.State.RESTART_PENDING)
                {
                    restartWorkbench();
                }
            }));
        if (shown)
        {
            store.setValue(PrefKeys.PREF_UPKEEP_NOTIFIED_VERSION, offer.offered().toString());
        }
    }

    /**
     * The strip's own name, with a word appended when this plugin has news about itself.
     * <p>
     * A word rather than a colour: this class already commits to being readable in greyscale and to
     * a reader who cannot tell two hues apart, and a coloured dot would say nothing to either. It is
     * only appended while no call is running - what a call is doing is the more urgent thing for the
     * strip to be showing, and the news keeps until it finishes.
     * </p>
     *
     * @return the label text
     */
    private static String labelWithUpkeep()
    {
        switch (ReleaseSweep.get().ledger().current().state())
        {
            case UPDATE_AVAILABLE:
                return "AI-EDT update"; //$NON-NLS-1$
            case RESTART_PENDING:
                return "AI-EDT restart"; //$NON-NLS-1$
            default:
                return "AI-EDT"; //$NON-NLS-1$
        }
    }

    /**
     * The tooltip's line about updates, or nothing at all when there is nothing to say.
     * <p>
     * Silent while the feature is asleep, up to date or merely checking: a tooltip that reported
     * "no update" every day would be noise, and the point of the line is that it appears only when
     * it means something.
     * </p>
     *
     * @return the line, starting with a newline, or an empty string
     */
    private static String describeUpkeep()
    {
        ReleaseOffer offer = ReleaseSweep.get().ledger().current();
        if (offer.state() == ReleaseOffer.State.UPDATE_AVAILABLE && offer.offered() != null)
        {
            return "\nUpdate " + offer.offered() + " is published - click to install it"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (offer.state() == ReleaseOffer.State.RESTART_PENDING)
        {
            return "\nRestart to finish an update already in the profile"; //$NON-NLS-1$
        }
        if (offer.state() == ReleaseOffer.State.CHECK_FAILED && offer.note() != null)
        {
            // A check or install the user started reports its outcome nowhere else: the job is
            // silent by design, so without this line a failure they asked for looks like nothing
            // happening at all.
            return "\nLast update attempt did not finish: " + offer.note(); //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * States what is happening to this workspace, in one line.
     * <p>
     * Shared with the popup menu on purpose: the menu opens over the very spot the tooltip was
     * describing, and two independently worded answers to "what is going on" would eventually
     * disagree about it.
     * </p>
     *
     * @param state    what the indicator is showing
     * @param toolName the running tool, when one is running
     * @param seconds  how long it has been running
     * @return the line
     */
    private static String describeActivity(Activity state, String toolName, long seconds)
    {
        if (state == Activity.STOPPED)
        {
            return "Closed - no agent can reach this workspace"; //$NON-NLS-1$
        }
        if (state == Activity.WORKING)
        {
            return toolName + " - " + formatDuration(seconds) + " and counting"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return describeLastCall();
    }

    /**
     * States where an agent connects and what this build has served.
     *
     * @param port  the configured port
     * @param calls calls served since this server came up
     * @return the line
     */
    private static String describeConnection(int port, long calls)
    {
        return endpointUrl(port) + " - " + calls + (calls == 1 ? " call" : " calls") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ", build " + McpServerMeta.PLUGIN_VERSION; //$NON-NLS-1$
    }

    private static String endpointUrl(int port)
    {
        return "http://localhost:" + port + "/mcp"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Describes the quiet state by what happened last rather than by a word for nothing.
     * <p>
     * "Idle" and its synonyms say only what the indicator already showed. Whether the agent finished
     * a second ago or went quiet an hour back is the thing a reader cannot see anywhere else, and it
     * is what tells them whether a session is still going.
     * </p>
     *
     * @return the first line of the tooltip while no call is running
     */
    private static String describeLastCall()
    {
        McpHistory.LastCall last = McpHistory.lastCall();
        if (last == null)
        {
            return "No calls yet"; //$NON-NLS-1$
        }
        return "Last call " + describeAge(System.currentTimeMillis() - last.timestamp) //$NON-NLS-1$
            + " - " + last.toolName; //$NON-NLS-1$
    }

    /**
     * Renders an elapsed span the way someone would say it, coarsening as it grows: nobody needs the
     * seconds on something that happened two hours ago.
     *
     * @param millis how long ago it was; a negative value is treated as just now
     * @return the phrase, without a leading article
     */
    private static String describeAge(long millis)
    {
        long seconds = millis / 1000L;
        if (seconds < JUST_NOW_SECONDS)
        {
            return "just now"; //$NON-NLS-1$
        }
        if (seconds < SECONDS_PER_MINUTE)
        {
            return seconds + " s ago"; //$NON-NLS-1$
        }
        long minutes = seconds / SECONDS_PER_MINUTE;
        if (minutes < MINUTES_PER_HOUR)
        {
            return minutes + " min ago"; //$NON-NLS-1$
        }
        long hours = minutes / MINUTES_PER_HOUR;
        if (hours < HOURS_PER_DAY)
        {
            return hours + " h ago"; //$NON-NLS-1$
        }
        return hours / HOURS_PER_DAY + " d ago"; //$NON-NLS-1$
    }

    private void startUpdateThread()
    {
        updateThread = new Thread(UPDATE_THREAD_NAME)
        {
            @Override
            public void run()
            {
                while (!disposed && !isInterrupted())
                {
                    try
                    {
                        Thread.sleep(UPDATE_INTERVAL_MS);
                    }
                    catch (InterruptedException e)
                    {
                        interrupt();
                        break;
                    }
                    Display display = Display.getDefault();
                    if (display != null && !display.isDisposed())
                    {
                        display.asyncExec(McpStatusBarItem.this::updateStatus);
                    }
                }
            }
        };
        updateThread.setDaemon(true);
        updateThread.start();
    }

    private void createPopupMenu()
    {
        popupMenu = new Menu(container);

        // Built fresh on every open, whichever way it was opened - left click through the mouse
        // handler, right click or the context-menu key straight into the native menu.
        popupMenu.addMenuListener(MenuListener.menuShownAdapter(e -> rebuildMenu()));
    }

    /**
     * Fills the menu with what is going on and what can be done about it right now.
     * <p>
     * Opening the menu hides the tooltip that was explaining the strip, so the menu repeats the two
     * facts it carried - the state and the endpoint - as a heading. Without them a click traded an
     * answer for a pair of verbs, and the only way back to the answer was to close the menu and hover
     * again.
     * </p>
     * <p>
     * Below the heading everything is a command that can be run at this moment. Building every
     * command once and grey-out whatever does not apply produces a menu that is mostly dead: with no
     * call running, five of the nine entries would be unreachable and the reader has to skim past
     * them. Only the heading is inert, and it is worded as a fact rather than as a verb so it does
     * not read as a command that stopped working.
     * </p>
     */
    private void rebuildMenu()
    {
        for (MenuItem stale : popupMenu.getItems())
        {
            stale.dispose();
        }

        McpHttpEndpoint server = Activator.getDefault() != null ? Activator.getDefault().getMcpServer() : null;
        boolean running = server != null && server.isRunning();
        String toolName = server != null ? server.getCurrentToolName() : null;
        long seconds = server != null ? server.getToolExecutionSeconds() : 0;
        long requests = server != null ? server.getRequestCount() : 0;
        int started = server != null ? server.getPort() : 0;
        int port = started > 0 ? started : storedPort();
        Activity state = toolName != null ? Activity.WORKING : running ? Activity.LISTENING : Activity.STOPPED;

        addHeadingItem(popupMenu, describeActivity(state, toolName, seconds));
        addHeadingItem(popupMenu, describeConnection(port, requests));
        new MenuItem(popupMenu, SWT.SEPARATOR);

        if (toolName != null)
        {
            String elapsed = formatDuration(server.getToolExecutionSeconds());
            addPushItem(popupMenu, "Interrupt " + toolName + " (" + elapsed + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                e -> sendSignal(OperatorSignal.SignalType.CANCEL, "Interrupt")); //$NON-NLS-1$
            addPushItem(popupMenu, "Run in background", //$NON-NLS-1$
                e -> sendSignal(OperatorSignal.SignalType.BACKGROUND, "Run in background")); //$NON-NLS-1$
            addPushItem(popupMenu, "Start over", //$NON-NLS-1$
                e -> sendSignal(OperatorSignal.SignalType.RETRY, "Start over")); //$NON-NLS-1$
            addPushItem(popupMenu, "Hand off to expert", //$NON-NLS-1$
                e -> sendSignal(OperatorSignal.SignalType.EXPERT, "Hand off to expert")); //$NON-NLS-1$
            addPushItem(popupMenu, "Attach a note...", //$NON-NLS-1$
                e -> sendSignal(OperatorSignal.SignalType.CUSTOM, "Attach a note")); //$NON-NLS-1$
            new MenuItem(popupMenu, SWT.SEPARATOR);
        }

        addUpkeepItems(popupMenu);

        // The endpoint is the one thing here that gets typed into another program, so offer it as
        // something to take rather than something to read off the screen and retype.
        final int endpointPort = port;
        addPushItem(popupMenu, "Copy endpoint address", e -> copyToClipboard(endpointUrl(endpointPort))); //$NON-NLS-1$

        if (running)
        {
            addPushItem(popupMenu, "Restart", e -> restartServer()); //$NON-NLS-1$
            addPushItem(popupMenu, "Stop", e -> stopServer()); //$NON-NLS-1$
        }
        else
        {
            addPushItem(popupMenu, "Start", e -> startServer()); //$NON-NLS-1$
        }
    }

    /**
     * Adds whatever the update state actually offers, and nothing when it offers nothing.
     * <p>
     * No placeholder that cannot be run: an entry greyed out because there happens to be no update
     * teaches the reader to skip that part of the menu, and by the time there is an update they no
     * longer look. Each entry does its work on a job - a check talks to the network and an install
     * rewrites the profile, neither of which belongs on the thread painting this menu.
     * </p>
     *
     * @param menu the menu being rebuilt
     */
    private void addUpkeepItems(Menu menu)
    {
        ReleaseSweep sweep = ReleaseSweep.get();
        ReleaseOffer offer = sweep.ledger().current();
        boolean anything = false;

        if (offer.state() == ReleaseOffer.State.UPDATE_AVAILABLE && offer.offered() != null)
        {
            addPushItem(menu, "Install update " + offer.offered() + " and restart", //$NON-NLS-1$ //$NON-NLS-2$
                e -> runUpkeep("Installing the AI-EDT update", monitor -> { //$NON-NLS-1$
                    ReleaseOffer after = sweep.installNow(monitor);
                    if (after.state() == ReleaseOffer.State.RESTART_PENDING)
                    {
                        restartWorkbench();
                    }
                }));
            anything = true;
        }
        else if (offer.state() == ReleaseOffer.State.RESTART_PENDING)
        {
            addPushItem(menu, "Restart to finish the update", e -> restartWorkbench()); //$NON-NLS-1$
            anything = true;
        }
        if (offer.state() != ReleaseOffer.State.DORMANT)
        {
            addPushItem(menu, "Check for updates now", //$NON-NLS-1$
                e -> runUpkeep("Checking for an AI-EDT update", sweep::checkNow)); //$NON-NLS-1$
            anything = true;
        }
        if (anything)
        {
            new MenuItem(menu, SWT.SEPARATOR);
        }
    }

    /**
     * Runs an update chore off the UI thread, as something the user started and can watch.
     *
     * @param title what to call it in the progress view
     * @param work what to do
     */
    private static void runUpkeep(String title, java.util.function.Consumer<IProgressMonitor> work)
    {
        Job job = new Job(title)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    work.accept(monitor);
                }
                catch (OperationCanceledException e)
                {
                    return Status.CANCEL_STATUS;
                }
                catch (RuntimeException e)
                {
                    // The outcome is reported through the strip and the tooltip, which is where the
                    // user is already looking; the log carries the detail.
                    Activator.logError(title + " failed", e); //$NON-NLS-1$
                }
                return Status.OK_STATUS;
            }
        };
        // Not a system job: the user asked for this one, so it belongs in the progress view.
        job.setUser(true);
        job.schedule();
    }

    /**
     * Restarts the workbench, on the UI thread and without waiting for it.
     */
    private static void restartWorkbench()
    {
        Display.getDefault().asyncExec(() -> {
            try
            {
                if (PlatformUI.isWorkbenchRunning() && !PlatformUI.getWorkbench().restart())
                {
                    // Vetoed - an unsaved editor, most likely. The profile is already updated, so
                    // the strip keeps saying a restart is outstanding until one happens.
                    Activator.logWarning(
                        "The restart that would finish the AI-EDT update was refused"); //$NON-NLS-1$
                }
            }
            catch (RuntimeException e)
            {
                Activator.logError("The AI-EDT update could not restart the workbench", e); //$NON-NLS-1$
            }
        });
    }

    private static void addPushItem(Menu menu, String text, java.util.function.Consumer<Object> action)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        item.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> action.accept(null)));
    }

    /**
     * Adds an inert line stating a fact about the server. Disabled because there is nothing to run,
     * not because something became unavailable.
     *
     * @param menu the menu to add to
     * @param text the fact
     */
    private static void addHeadingItem(Menu menu, String text)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        item.setEnabled(false);
    }

    /**
     * Puts text on the system clipboard.
     *
     * @param text what to copy
     */
    private void copyToClipboard(String text)
    {
        Clipboard clipboard = new Clipboard(container.getDisplay());
        try
        {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        }
        finally
        {
            clipboard.dispose();
        }
    }

    /**
     * Opens the signal dialog and delivers whatever the user sends. Does nothing unless a tool is
     * actually executing.
     *
     * @param type  the kind of signal
     * @param title the dialog title
     */
    private void sendSignal(OperatorSignal.SignalType type, String title)
    {
        McpHttpEndpoint server = Activator.getDefault() != null ? Activator.getDefault().getMcpServer() : null;
        if (server == null || !server.isToolExecuting())
        {
            return;
        }

        OperatorSignalDialog dialog = new OperatorSignalDialog(container.getShell(), type, title);
        if (dialog.open() != Window.OK)
        {
            return;
        }

        OperatorSignal signal = new OperatorSignal(type, dialog.getMessage());
        if (server.interruptToolCall(signal))
        {
            Activator.logInfo("Call stopped by operator signal: " + type); //$NON-NLS-1$
        }
        else
        {
            // Nothing was interruptible after all: park it so it rides along with the next result.
            server.setUserSignal(signal);
            Activator.logInfo("Operator signal waiting: " + type); //$NON-NLS-1$
        }
    }

    private void startServer()
    {
        McpHttpEndpoint server = Activator.getDefault() != null ? Activator.getDefault().getMcpServer() : null;
        if (server != null && !server.isRunning())
        {
            try
            {
                server.start(storedPort());
            }
            catch (IOException e)
            {
                Activator.logError("MCP server failed to start from the status bar", e); //$NON-NLS-1$
            }
        }
        updateStatus();
    }

    private void stopServer()
    {
        McpHttpEndpoint server = Activator.getDefault() != null ? Activator.getDefault().getMcpServer() : null;
        if (server != null && server.isRunning())
        {
            server.stop();
        }
        updateStatus();
    }

    private void restartServer()
    {
        McpHttpEndpoint server = Activator.getDefault() != null ? Activator.getDefault().getMcpServer() : null;
        if (server != null)
        {
            try
            {
                server.restart(storedPort());
            }
            catch (IOException e)
            {
                Activator.logError("MCP server failed to restart from the status bar", e); //$NON-NLS-1$
            }
        }
        updateStatus();
    }

    private static int storedPort()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        return store.getInt(PrefKeys.PREF_PORT);
    }

    private Font createScaledFont()
    {
        FontData[] fontData = container.getFont().getFontData();
        FontData base = fontData[0];
        int height = (int)(base.getHeight() * FONT_SCALE);
        return new Font(container.getDisplay(), new FontData(base.getName(), height, base.getStyle()));
    }

    /**
     * Allocates the three colours the indicator paints with. They match the plugin's icon palette,
     * so the trim strip and the toolbar read as one set.
     */
    private void buildIndicatorColors()
    {
        Display display = container.getDisplay();
        // The track sits well back so the marker on it carries the colour: at this size the two
        // cannot both be saturated without the marker disappearing into its own rail.
        trackColor = new Color(display, new RGB(198, 204, 211));
        restingColor = new Color(display, new RGB(13, 115, 143));
        workingColor = new Color(display, new RGB(222, 126, 16));
    }

    private static void disposeResource(org.eclipse.swt.graphics.Resource resource)
    {
        if (resource != null && !resource.isDisposed())
        {
            resource.dispose();
        }
    }
}
