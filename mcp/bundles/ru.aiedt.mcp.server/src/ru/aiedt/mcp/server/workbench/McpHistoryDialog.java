/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.workbench;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TrayDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import ru.aiedt.mcp.server.McpHistory;
import ru.aiedt.mcp.server.settings.HistorySettings;
import ru.aiedt.mcp.server.support.HistoryJournal;

/**
 * The call history, for a person to read.
 * <p>
 * Everything here was already being recorded; the only way to see it was to ask an agent to call
 * {@code get_mcp_history}, which means reading a session through the thing being inspected. This
 * opens the same buffer directly: what ran, when, how long it took, what it was asked and what it
 * answered.
 * </p>
 * <p>
 * Modeless and non-blocking, because the question it answers - what is this agent doing to my
 * project - is asked while the agent is still working. Refreshing is a button rather than a timer:
 * a list that reorders itself under the pointer loses the row the reader was looking at.
 * </p>
 */
public class McpHistoryDialog
    extends TrayDialog
{
    /** Every way a shell can be modal, so all of them can be taken off in one go. */
    static final int MODAL_BITS = SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL;

    private static final int REFRESH_ID = IDialogConstants.CLIENT_ID + 1;

    private static final int CLEAR_ID = IDialogConstants.CLIENT_ID + 2;

    private static final int TABLE_HEIGHT = 260;

    private static final int DETAIL_HEIGHT = 120;

    private static final int WIDTH_TIME = 90;

    private static final int WIDTH_TOOL = 220;

    private static final int WIDTH_OUTCOME = 70;

    private static final int WIDTH_DURATION = 80;

    private static final int WIDTH_PREVIEW = 320;

    private static final int FILTER_WIDTH = 200;

    private static final DateTimeFormatter TIME =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault()); //$NON-NLS-1$

    private final List<Map<String, Object>> shown = new ArrayList<>();

    private Table table;

    private Text filterText;

    private Button failuresOnlyCheck;

    private Text requestText;

    private Text responseText;

    private Label summaryLabel;

    private Label requestLabel;

    private Label responseLabel;

    /**
     * Opens the history over a shell.
     *
     * @param parentShell the shell to centre on
     */
    public McpHistoryDialog(Shell parentShell)
    {
        super(parentShell);
        // The modal bits have to be MASKED OFF, not overridden. TrayDialog arrives carrying
        // SWT.APPLICATION_MODAL and SWT.MODELESS is zero, so OR-ing it in changes nothing at all -
        // the window would still lock the whole IDE while someone reads it, which is the opposite
        // of what this is for. setBlockOnOpen only decides whether open() returns; it does not
        // touch modality.
        setShellStyle(modeless(getShellStyle()));
        setBlockOnOpen(false);
    }

    /**
     * Takes modality off a shell style.
     * <p>
     * Separate and static so it can be checked without a display, because the mistake it exists to
     * prevent is invisible from the code that makes it: {@code SWT.MODELESS} is zero, so OR-ing it
     * into a style that already carries {@code SWT.APPLICATION_MODAL} reads as asking for a
     * modeless window and produces a modal one.
     * </p>
     *
     * @param style the inherited style
     * @return the same style with every modal bit cleared and resizing allowed
     */
    static int modeless(int style)
    {
        return (style & ~MODAL_BITS) | SWT.MODELESS | SWT.RESIZE;
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText("MCP call history"); //$NON-NLS-1$
    }

    @Override
    protected boolean isResizable()
    {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite)super.createDialogArea(parent);
        Composite content = new Composite(area, SWT.NONE);
        content.setLayout(new GridLayout(1, false));
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        summaryLabel = new Label(content, SWT.NONE);
        summaryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createFilterRow(content);
        createTable(content);
        createDetail(content);

        reload();
        return area;
    }

    /**
     * The two controls that narrow the list: a name fragment and a failures switch.
     *
     * @param parent the content composite
     */
    private void createFilterRow(Composite parent)
    {
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayout(new GridLayout(3, false));
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(row, SWT.NONE).setText("Tool:"); //$NON-NLS-1$

        filterText = new Text(row, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
        GridData filterData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        filterData.widthHint = FILTER_WIDTH;
        filterText.setLayoutData(filterData);
        filterText.setMessage("part of a tool name"); //$NON-NLS-1$
        filterText.addModifyListener(e -> reload());

        failuresOnlyCheck = new Button(row, SWT.CHECK);
        failuresOnlyCheck.setText("Failures only"); //$NON-NLS-1$
        failuresOnlyCheck.addListener(SWT.Selection, e -> reload());
    }

    private void createTable(Composite parent)
    {
        table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = TABLE_HEIGHT;
        table.setLayoutData(tableData);

        addColumn("Time", WIDTH_TIME); //$NON-NLS-1$
        addColumn("Tool", WIDTH_TOOL); //$NON-NLS-1$
        addColumn("Outcome", WIDTH_OUTCOME); //$NON-NLS-1$
        addColumn("Duration", WIDTH_DURATION); //$NON-NLS-1$
        addColumn("Request", WIDTH_PREVIEW); //$NON-NLS-1$

        table.addListener(SWT.Selection, e -> showSelected());
    }

    private void addColumn(String title, int width)
    {
        TableColumn column = new TableColumn(table, SWT.NONE);
        column.setText(title);
        column.setWidth(width);
    }

    /**
     * The two read-only panes under the list. Their labels carry the sizes, so that a response cut
     * down to the setting is visibly a cut response and not a short one.
     *
     * @param parent the content composite
     */
    private void createDetail(Composite parent)
    {
        requestLabel = new Label(parent, SWT.NONE);
        requestLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        requestText = detailPane(parent);

        responseLabel = new Label(parent, SWT.NONE);
        responseLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        responseText = detailPane(parent);
    }

    private static Text detailPane(Composite parent)
    {
        Text text = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
        data.heightHint = DETAIL_HEIGHT;
        text.setLayoutData(data);
        return text;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, REFRESH_ID, "Refresh", false); //$NON-NLS-1$
        createButton(parent, CLEAR_ID, "Clear", false); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    @Override
    protected void buttonPressed(int buttonId)
    {
        if (buttonId == REFRESH_ID)
        {
            reload();
        }
        else if (buttonId == CLEAR_ID)
        {
            McpHistory.clear();
            reload();
        }
        else if (buttonId == IDialogConstants.CLOSE_ID)
        {
            close();
        }
        else
        {
            super.buttonPressed(buttonId);
        }
    }

    /**
     * Rebuilds the list from the buffer, applying whatever the filter row currently says.
     */
    private void reload()
    {
        if (table == null || table.isDisposed())
        {
            return;
        }
        String needle = filterText.getText().trim().toLowerCase(Locale.ROOT);
        boolean failuresOnly = failuresOnlyCheck.getSelection();

        shown.clear();
        for (Map<String, Object> call : McpHistory.recent(0))
        {
            if (failuresOnly && Boolean.TRUE.equals(call.get("success"))) //$NON-NLS-1$
            {
                continue;
            }
            if (!needle.isEmpty() && !text(call, "tool").toLowerCase(Locale.ROOT).contains(needle)) //$NON-NLS-1$
            {
                continue;
            }
            shown.add(call);
        }

        table.removeAll();
        for (Map<String, Object> call : shown)
        {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, TIME.format(Instant.ofEpochMilli(number(call, "timestamp")))); //$NON-NLS-1$
            item.setText(1, text(call, "tool")); //$NON-NLS-1$
            item.setText(2, Boolean.TRUE.equals(call.get("success")) ? "ok" : "failed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            item.setText(3, number(call, "durationMs") + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
            item.setText(4, oneLine(text(call, "args"))); //$NON-NLS-1$
        }

        updateSummary();
        showSelected();
    }

    /**
     * The line above the list: how much is being kept, and where the journal is when one is written.
     * <p>
     * Stated here because the settings are what decides whether the panes below are the whole
     * answer, and someone who finds them cut short needs to know which knob to turn.
     * </p>
     */
    private void updateSummary()
    {
        HistorySettings settings = HistorySettings.current();
        StringBuilder sb = new StringBuilder();
        sb.append(shown.size()).append(" of ").append(McpHistory.size()) //$NON-NLS-1$
            .append(" calls shown, keeping the last ").append(settings.depth()); //$NON-NLS-1$
        // The extents in force, not the ones asked for. A very deep buffer with very long extents
        // is brought down to fit a total memory budget, and this is where that becomes visible.
        sb.append(" at ").append(settings.argChars()).append('/').append(settings.resultChars()) //$NON-NLS-1$
            .append(" characters"); //$NON-NLS-1$
        if (!settings.isEnabled())
        {
            sb.append(" - recording is off"); //$NON-NLS-1$
        }
        Path journal = HistoryJournal.path();
        if (settings.isFileEnabled() && journal != null)
        {
            sb.append(" - journal: ").append(journal); //$NON-NLS-1$
        }
        summaryLabel.setText(sb.toString());
        summaryLabel.getParent().layout();
    }

    /**
     * Fills the two panes from the selected row, or empties them when nothing is selected.
     */
    private void showSelected()
    {
        int index = table.getSelectionIndex();
        if (index < 0 || index >= shown.size())
        {
            requestLabel.setText("Request"); //$NON-NLS-1$
            responseLabel.setText("Response"); //$NON-NLS-1$
            requestText.setText(""); //$NON-NLS-1$
            responseText.setText(""); //$NON-NLS-1$
            return;
        }
        Map<String, Object> call = shown.get(index);
        String args = text(call, "args"); //$NON-NLS-1$
        String result = text(call, "result"); //$NON-NLS-1$

        requestLabel.setText("Request" //$NON-NLS-1$
            + (Boolean.TRUE.equals(call.get("argsCut")) ? " (cut to " + args.length() + " characters)" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        long full = number(call, "resultChars"); //$NON-NLS-1$
        responseLabel.setText("Response" //$NON-NLS-1$
            + (full > result.length() ? " (" + result.length() + " of " + full + " characters)" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        requestText.setText(args);
        responseText.setText(result);
        requestLabel.getParent().layout();
    }

    private static String text(Map<String, Object> call, String key)
    {
        Object value = call.get(key);
        return value == null ? "" : String.valueOf(value); //$NON-NLS-1$
    }

    private static long number(Map<String, Object> call, String key)
    {
        Object value = call.get(key);
        return value instanceof Number ? ((Number)value).longValue() : 0L;
    }

    /**
     * Flattens a value for the one-line preview column, where a line break would otherwise show as a
     * box or swallow the rest of the row.
     *
     * @param value the text
     * @return the same text on one line
     */
    private static String oneLine(String value)
    {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
