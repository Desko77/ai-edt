/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.McpHttpEndpoint;
import ru.aiedt.mcp.server.upkeep.UpkeepPolicy;

/**
 * The General tab: the port, what starts the server, where the auxiliary tools live, who may reach
 * the socket, how markers are shown, and buttons to run the server by hand.
 * <p>
 * Not a JFace page of its own - the {@link McpSettingsPage} owns it and drives its OK,
 * Restore Defaults and disposal. The fields are grouped into titled {@link Group} sections rather
 * than a flat composite divided by separator lines. Two deliberate quirks worth not "tidying":
 * Start and Restart save the whole tab before they act, because you cannot bind a port you have not
 * committed; and a plain port change does not restart a running server - only a change on the Tools
 * tab does that. The tooltips tell the user as much.
 * </p>
 */
public class GeneralPrefTab
{
    private static final int MIN_UPKEEP_INTERVAL_HOURS = 1;

    private static final int MAX_UPKEEP_INTERVAL_HOURS = 24 * 30;

    private static final int MIN_PORT = 1024;

    private static final int MAX_PORT = 65535;

    private static final String[] MARKER_STYLE_LABELS =
        {"All (suffix)", "First only", "Count"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String[] MARKER_STYLE_VALUES = {PrefKeys.MARKERS_STYLE_SUFFIX,
        PrefKeys.MARKERS_STYLE_FIRST_MARKER, PrefKeys.MARKERS_STYLE_COUNT};

    private final IPreferenceStore store;

    private final Composite control;

    private final List<Image> images = new ArrayList<>();

    private Color headerGrey;

    private Font sectionFont;

    private Spinner portSpinner;

    private Button autoStartCheck;

    private Text checksFolderText;

    private Text bslLsJarText;

    private Text bslLsJavaText;

    private Text vanessaEpfText;

    private Text vanessa1cExeText;

    private Button plainTextCheck;

    private Button bindAllCheck;

    private Button authEnabledCheck;

    private Text authTokenText;

    private Button showMarkersCheck;

    private Combo markerStyleCombo;

    private Button upkeepEnabledCheck;

    private Text upkeepSiteText;

    private Spinner upkeepIntervalSpinner;

    private Button upkeepNotifyCheck;

    private Button upkeepAllowLocalCheck;

    private Runnable validationListener;

    private Label statusLabel;

    private Button startButton;

    private Button stopButton;

    private Button restartButton;

    /**
     * Builds the tab.
     *
     * @param parent the tab folder to build into
     */
    public GeneralPrefTab(Composite parent)
    {
        this.store = Activator.getDefault().getPreferenceStore();

        control = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        layout.verticalSpacing = 8;
        control.setLayout(layout);

        headerGrey = new Color(control.getDisplay(), 230, 230, 230);
        FontData baseFont = control.getFont().getFontData()[0];
        sectionFont = new Font(control.getDisplay(),
            new FontData(baseFont.getName(), baseFont.getHeight(), SWT.BOLD));

        createConnectionSection();
        createExternalToolsSection();
        createNetworkAndSecuritySection();
        createNavigatorMarkersSection();
        createUpdatesSection();
        createServerControlSection();

        refreshServerStatus();
    }

    /**
     * Returns the tab's control.
     *
     * @return the composite
     */
    public Composite getControl()
    {
        return control;
    }

    /**
     * Returns the port shown in the spinner, whether or not it is saved.
     *
     * @return the port
     */
    public int getPort()
    {
        return portSpinner.getSelection();
    }

    /**
     * Registers a callback fired whenever a field that can be invalid changes, so the enclosing
     * page can recheck itself as the user types instead of only when they press OK.
     *
     * @param listener the callback, or <code>null</code> to stop being told
     */
    public void setValidationListener(Runnable listener)
    {
        this.validationListener = listener;
    }

    /**
     * Checks the update site as it is currently typed, so a mistake is caught while the page is
     * open rather than at the next background check, where nobody is watching.
     * <p>
     * An empty address is not a mistake: it is the shipped value and it puts the feature to sleep.
     * </p>
     *
     * @return the message to show, or <code>null</code> when the address is usable
     */
    public String validateUpkeepSite()
    {
        UpkeepPolicy.SiteVerdict verdict = UpkeepPolicy.examineSite(upkeepSiteText.getText(),
            upkeepAllowLocalCheck.getSelection());
        if (!verdict.configured() || verdict.accepted())
        {
            return null;
        }
        return "Update site: " + verdict.reason(); //$NON-NLS-1$
    }

    /**
     * Writes every field to the store.
     * <p>
     * The checks folder is written as typed, spaces and all - unlike the five other paths, which are
     * trimmed. The marker style is written only when the combo has a selection.
     * </p>
     */
    public void performOk()
    {
        store.setValue(PrefKeys.PREF_PORT, portSpinner.getSelection());
        store.setValue(PrefKeys.PREF_AUTO_START, autoStartCheck.getSelection());
        store.setValue(PrefKeys.PREF_CHECKS_FOLDER, checksFolderText.getText());
        store.setValue(PrefKeys.PREF_BSL_LS_JAR, bslLsJarText.getText().trim());
        store.setValue(PrefKeys.PREF_BSL_LS_JAVA, bslLsJavaText.getText().trim());
        store.setValue(PrefKeys.PREF_VANESSA_EPF, vanessaEpfText.getText().trim());
        store.setValue(PrefKeys.PREF_VANESSA_1C_EXE, vanessa1cExeText.getText().trim());
        store.setValue(PrefKeys.PREF_PLAIN_TEXT_MODE, plainTextCheck.getSelection());
        store.setValue(PrefKeys.PREF_BIND_ALL_INTERFACES, bindAllCheck.getSelection());
        store.setValue(PrefKeys.PREF_AUTH_ENABLED, authEnabledCheck.getSelection());
        store.setValue(PrefKeys.PREF_AUTH_TOKEN, authTokenText.getText().trim());
        writeUpkeep();
        store.setValue(PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR, showMarkersCheck.getSelection());
        MarkerSettingsMigration.mirrorToLegacyKey(PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR,
            Boolean.toString(showMarkersCheck.getSelection()));

        int styleIndex = markerStyleCombo.getSelectionIndex();
        if (styleIndex >= 0 && styleIndex < MARKER_STYLE_VALUES.length)
        {
            store.setValue(PrefKeys.PREF_MARKERS_DECORATION_STYLE, MARKER_STYLE_VALUES[styleIndex]);
            MarkerSettingsMigration.mirrorToLegacyKey(PrefKeys.PREF_MARKERS_DECORATION_STYLE,
                MARKER_STYLE_VALUES[styleIndex]);
        }
    }

    /**
     * Writes the update settings, but only as a whole and only when the address is usable.
     * <p>
     * The page refuses OK while the address is rejected, but the Start and Restart buttons on this
     * tab call {@link #performOk()} directly - they have to commit the port before opening the
     * socket - and so reach this code without passing that refusal. Writing anyway would store an
     * address the page has just told the user it will not accept.
     * </p>
     * <p>
     * All five keys move together because they are only meaningful together: writing the
     * local-source flag on its own would combine a new flag with a previously stored address and
     * produce a pairing the user never chose. The rejected text stays in the field, and the reason
     * is already on screen from the validation that runs as it is typed.
     * </p>
     */
    private void writeUpkeep()
    {
        if (validateUpkeepSite() != null)
        {
            return;
        }
        store.setValue(PrefKeys.PREF_UPKEEP_ENABLED, upkeepEnabledCheck.getSelection());
        store.setValue(PrefKeys.PREF_UPKEEP_SITE_URL, upkeepSiteText.getText().trim());
        store.setValue(PrefKeys.PREF_UPKEEP_INTERVAL_HOURS, upkeepIntervalSpinner.getSelection());
        store.setValue(PrefKeys.PREF_UPKEEP_NOTIFY_POPUP, upkeepNotifyCheck.getSelection());
        store.setValue(PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE, upkeepAllowLocalCheck.getSelection());
    }

    /**
     * Puts the widgets back to the shipped values. Nothing is saved until OK.
     * <p>
     * The two Vanessa fields are left as they are - a known gap from when they were added after this
     * method, kept here so the fix can be its own reviewed change.
     * </p>
     */
    public void performDefaults()
    {
        portSpinner.setSelection(store.getDefaultInt(PrefKeys.PREF_PORT));
        autoStartCheck.setSelection(store.getDefaultBoolean(PrefKeys.PREF_AUTO_START));
        checksFolderText.setText(store.getDefaultString(PrefKeys.PREF_CHECKS_FOLDER));
        bslLsJarText.setText(store.getDefaultString(PrefKeys.PREF_BSL_LS_JAR));
        bslLsJavaText.setText(store.getDefaultString(PrefKeys.PREF_BSL_LS_JAVA));
        plainTextCheck.setSelection(store.getDefaultBoolean(PrefKeys.PREF_PLAIN_TEXT_MODE));
        bindAllCheck.setSelection(store.getDefaultBoolean(PrefKeys.PREF_BIND_ALL_INTERFACES));
        authEnabledCheck.setSelection(store.getDefaultBoolean(PrefKeys.PREF_AUTH_ENABLED));
        authTokenText.setText(store.getDefaultString(PrefKeys.PREF_AUTH_TOKEN));
        upkeepEnabledCheck.setSelection(store.getDefaultBoolean(PrefKeys.PREF_UPKEEP_ENABLED));
        upkeepSiteText.setText(store.getDefaultString(PrefKeys.PREF_UPKEEP_SITE_URL));
        upkeepIntervalSpinner.setSelection(
            store.getDefaultInt(PrefKeys.PREF_UPKEEP_INTERVAL_HOURS));
        upkeepNotifyCheck.setSelection(store.getDefaultBoolean(PrefKeys.PREF_UPKEEP_NOTIFY_POPUP));
        upkeepAllowLocalCheck.setSelection(
            store.getDefaultBoolean(PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE));
        showMarkersCheck.setSelection(
            store.getDefaultBoolean(PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR));
        selectMarkerStyle(store.getDefaultString(PrefKeys.PREF_MARKERS_DECORATION_STYLE));
    }

    /**
     * Frees the three button images. {@code Button.setImage} does not take ownership, so nothing else
     * will.
     */
    public void dispose()
    {
        for (Image image : images)
        {
            if (image != null && !image.isDisposed())
            {
                image.dispose();
            }
        }
        images.clear();
        if (headerGrey != null && !headerGrey.isDisposed())
        {
            headerGrey.dispose();
        }
        if (sectionFont != null && !sectionFont.isDisposed())
        {
            sectionFont.dispose();
        }
    }

    private void createConnectionSection()
    {
        Composite section = section("Connection"); //$NON-NLS-1$

        Label portLabel = new Label(section, SWT.NONE);
        portLabel.setText("MCP port:"); //$NON-NLS-1$

        portSpinner = new Spinner(section, SWT.BORDER);
        portSpinner.setMinimum(MIN_PORT);
        portSpinner.setMaximum(MAX_PORT);
        portSpinner.setSelection(store.getInt(PrefKeys.PREF_PORT));
        spacer(section);

        autoStartCheck = new Button(section, SWT.CHECK);
        autoStartCheck.setText("Start the server when EDT opens"); //$NON-NLS-1$
        autoStartCheck.setLayoutData(span(section, 3));
        autoStartCheck.setSelection(store.getBoolean(PrefKeys.PREF_AUTO_START));
    }

    private void createExternalToolsSection()
    {
        Composite section = section("External tools"); //$NON-NLS-1$

        checksFolderText = pathRow(section, "Check docs folder:", null, PrefKeys.PREF_CHECKS_FOLDER); //$NON-NLS-1$
        addFolderBrowse(section, checksFolderText, "Select check descriptions folder"); //$NON-NLS-1$

        bslLsJarText = pathRow(section, "BSL Language Server jar (code_review):", //$NON-NLS-1$
            "Path to bsl-language-server-<ver>-exec.jar " //$NON-NLS-1$
                + "(github.com/1c-syntax/bsl-language-server). Empty = code_review disabled.", //$NON-NLS-1$
            PrefKeys.PREF_BSL_LS_JAR);
        addFileBrowse(section, bslLsJarText, "Select bsl-language-server -exec.jar", //$NON-NLS-1$
            new String[]{"*.jar"}); //$NON-NLS-1$

        bslLsJavaText = pathRow(section, "BSL Language Server Java (optional):", //$NON-NLS-1$
            "Path to a java(.exe) for BSL-LS. Empty = EDT's JRE (Java 17). Set a Java 21+ here if you " //$NON-NLS-1$
                + "use BSL-LS 1.0.0+ (which requires Java 21).", //$NON-NLS-1$
            PrefKeys.PREF_BSL_LS_JAVA);
        addFileBrowse(section, bslLsJavaText, "Select java executable (Java 21+ for BSL-LS 1.0.0+)", null); //$NON-NLS-1$

        vanessaEpfText = pathRow(section, "Vanessa Automation .epf (vanessa):", //$NON-NLS-1$
            "Path to vanessa-automation.epf (github.com/Pr-Mex/vanessa-automation). " //$NON-NLS-1$
                + "Empty = vanessa disabled.", //$NON-NLS-1$
            PrefKeys.PREF_VANESSA_EPF);
        addFileBrowse(section, vanessaEpfText, "Select vanessa-automation.epf", new String[]{"*.epf"}); //$NON-NLS-1$ //$NON-NLS-2$

        vanessa1cExeText = pathRow(section, "1C thick client for vanessa (1cv8.exe):", //$NON-NLS-1$
            "Path to the 1C thick client 1cv8.exe used to play Vanessa scenarios (the THICK client, " //$NON-NLS-1$
                + "not the thin 1cv8c.exe). Empty = vanessa disabled.", //$NON-NLS-1$
            PrefKeys.PREF_VANESSA_1C_EXE);
        addFileBrowse(section, vanessa1cExeText, "Select 1cv8.exe (thick client)", new String[]{"*.exe"}); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void createUpdatesSection()
    {
        Composite section = section("Updates"); //$NON-NLS-1$

        upkeepEnabledCheck = new Button(section, SWT.CHECK);
        upkeepEnabledCheck.setText("Look for a newer AI-EDT on the update site"); //$NON-NLS-1$
        upkeepEnabledCheck.setLayoutData(span(section, 3));
        upkeepEnabledCheck.setToolTipText("Checks shortly after EDT starts and then on the interval " //$NON-NLS-1$
            + "below. Nothing is ever installed without you asking for it."); //$NON-NLS-1$
        upkeepEnabledCheck.setSelection(store.getBoolean(PrefKeys.PREF_UPKEEP_ENABLED));

        // The two inputs that can be wrong together tell the page to recheck as they change: an
        // address is refused or accepted depending on the checkbox below, so either one moving has
        // to re-run the same verdict.
        upkeepSiteText = pathRow(section, "Update site:", //$NON-NLS-1$
            "A p2 update site, the same kind of address you would paste into Help > Install New " //$NON-NLS-1$
                + "Software. Empty = the whole feature is off. Whoever controls this address runs " //$NON-NLS-1$
                + "code in your IDE, so only https is accepted.", //$NON-NLS-1$
            PrefKeys.PREF_UPKEEP_SITE_URL);
        upkeepSiteText.addModifyListener(e -> fireValidation());
        spacer(section);

        Label intervalLabel = new Label(section, SWT.NONE);
        intervalLabel.setText("Check every (hours):"); //$NON-NLS-1$

        upkeepIntervalSpinner = new Spinner(section, SWT.BORDER);
        upkeepIntervalSpinner.setMinimum(MIN_UPKEEP_INTERVAL_HOURS);
        upkeepIntervalSpinner.setMaximum(MAX_UPKEEP_INTERVAL_HOURS);
        upkeepIntervalSpinner.setSelection(store.getInt(PrefKeys.PREF_UPKEEP_INTERVAL_HOURS));
        spacer(section);

        upkeepNotifyCheck = new Button(section, SWT.CHECK);
        upkeepNotifyCheck.setText("Show a notice when a newer version is found"); //$NON-NLS-1$
        upkeepNotifyCheck.setLayoutData(span(section, 3));
        upkeepNotifyCheck.setToolTipText("A small notice that does not take focus and disappears by " //$NON-NLS-1$
            + "itself, shown once per version. Turn it off to keep checking quietly."); //$NON-NLS-1$
        upkeepNotifyCheck.setSelection(store.getBoolean(PrefKeys.PREF_UPKEEP_NOTIFY_POPUP));

        upkeepAllowLocalCheck = new Button(section, SWT.CHECK);
        upkeepAllowLocalCheck.setText("Accept a local directory (file:) as the update site"); //$NON-NLS-1$
        upkeepAllowLocalCheck.setLayoutData(span(section, 3));
        upkeepAllowLocalCheck.setToolTipText("For testing against a locally built repository. A file " //$NON-NLS-1$
            + "URL naming a host is a network share, not a local directory, and stays refused."); //$NON-NLS-1$
        upkeepAllowLocalCheck.setSelection(store.getBoolean(PrefKeys.PREF_UPKEEP_ALLOW_LOCAL_SITE));
        upkeepAllowLocalCheck.addSelectionListener(
            SelectionListener.widgetSelectedAdapter(e -> fireValidation()));
    }

    private void fireValidation()
    {
        if (validationListener != null)
        {
            validationListener.run();
        }
    }

    private void createNetworkAndSecuritySection()
    {
        Composite section = section("Network & security"); //$NON-NLS-1$

        plainTextCheck = new Button(section, SWT.CHECK);
        plainTextCheck.setText("Plain-text responses (for Cursor)"); //$NON-NLS-1$
        plainTextCheck.setLayoutData(span(section, 3));
        plainTextCheck.setToolTipText("When enabled, returns results as plain text instead of embedded " //$NON-NLS-1$
            + "resources. Enable this if your AI client (e.g., Cursor) doesn't support MCP resources."); //$NON-NLS-1$
        plainTextCheck.setSelection(store.getBoolean(PrefKeys.PREF_PLAIN_TEXT_MODE));

        bindAllCheck = new Button(section, SWT.CHECK);
        bindAllCheck.setText("Listen on all network interfaces (not only localhost)"); //$NON-NLS-1$
        bindAllCheck.setLayoutData(span(section, 3));
        bindAllCheck.setToolTipText("OFF by default: the server listens on 127.0.0.1 and is reachable " //$NON-NLS-1$
            + "only from this machine. Turn this on and any host that can route here can call the tools " //$NON-NLS-1$
            + "- which read and write the infobase and the sources. Only do it together with a bearer " //$NON-NLS-1$
            + "token below. Restart the server after changing."); //$NON-NLS-1$
        bindAllCheck.setSelection(store.getBoolean(PrefKeys.PREF_BIND_ALL_INTERFACES));

        authEnabledCheck = new Button(section, SWT.CHECK);
        authEnabledCheck.setText("Require a bearer token"); //$NON-NLS-1$
        authEnabledCheck.setLayoutData(span(section, 3));
        authEnabledCheck.setToolTipText("When enabled, MCP clients must send 'Authorization: Bearer " //$NON-NLS-1$
            + "<token>'. Default OFF, which is safe only while the server listens on loopback. Restart " //$NON-NLS-1$
            + "the server after changing, and add the token to your MCP client config."); //$NON-NLS-1$
        authEnabledCheck.setSelection(store.getBoolean(PrefKeys.PREF_AUTH_ENABLED));

        Label tokenLabel = new Label(section, SWT.NONE);
        tokenLabel.setText("Bearer token:"); //$NON-NLS-1$

        authTokenText = new Text(section, SWT.BORDER);
        authTokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        authTokenText.setText(store.getString(PrefKeys.PREF_AUTH_TOKEN));

        Button generateButton = new Button(section, SWT.PUSH);
        generateButton.setText("Generate"); //$NON-NLS-1$
        generateButton.setToolTipText("Generate a fresh random 256-bit token."); //$NON-NLS-1$
        generateButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(
            e -> authTokenText.setText(McpAuth.generateToken())));
    }

    private void createNavigatorMarkersSection()
    {
        Composite section = section("Navigator markers"); //$NON-NLS-1$

        showMarkersCheck = new Button(section, SWT.CHECK);
        showMarkersCheck.setText("Decorate Navigator items with markers"); //$NON-NLS-1$
        showMarkersCheck.setLayoutData(span(section, 3));
        showMarkersCheck.setSelection(store.getBoolean(PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR));

        Label styleLabel = new Label(section, SWT.NONE);
        styleLabel.setText("Decoration style:"); //$NON-NLS-1$

        markerStyleCombo = new Combo(section, SWT.READ_ONLY);
        markerStyleCombo.setItems(MARKER_STYLE_LABELS);
        selectMarkerStyle(store.getString(PrefKeys.PREF_MARKERS_DECORATION_STYLE));
        spacer(section);
    }

    private void createServerControlSection()
    {
        Composite section = section("Server control"); //$NON-NLS-1$

        Composite panel = new Composite(section, SWT.NONE);
        GridLayout panelLayout = new GridLayout(4, false);
        panelLayout.marginWidth = 0;
        panelLayout.marginHeight = 0;
        panel.setLayout(panelLayout);
        panel.setLayoutData(span(section, 3));

        Label statusCaption = new Label(panel, SWT.NONE);
        statusCaption.setText("State:"); //$NON-NLS-1$
        statusLabel = new Label(panel, SWT.NONE);
        statusLabel.setLayoutData(span(panel, 3));

        startButton = controlButton(panel, "Start", "icons/server_start.png"); //$NON-NLS-1$ //$NON-NLS-2$
        startButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> startServer()));
        stopButton = controlButton(panel, "Stop", "icons/server_stop.png"); //$NON-NLS-1$ //$NON-NLS-2$
        stopButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> stopServer()));
        restartButton = controlButton(panel, "Restart", "icons/server_restart.png"); //$NON-NLS-1$ //$NON-NLS-2$
        restartButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> restartServer()));

        new Label(panel, SWT.NONE);

        Label endpointLabel = new Label(panel, SWT.NONE);
        endpointLabel.setText("Address: http://localhost:<port>/mcp"); //$NON-NLS-1$
        GridData endpointData = new GridData();
        endpointData.horizontalSpan = 4;
        endpointLabel.setLayoutData(endpointData);
    }

    private void startServer()
    {
        McpHttpEndpoint server = Activator.getDefault().getMcpServer();
        if (server == null)
        {
            return;
        }
        // Save first: the socket cannot open on a port that has not been committed.
        performOk();
        try
        {
            server.start(portSpinner.getSelection());
        }
        catch (IOException e)
        {
            Activator.logError("The endpoint could not be started", e); //$NON-NLS-1$
            MessageDialog.openError(control.getShell(), "Start Failed", //$NON-NLS-1$
                "Failed to start MCP Server: " + e.getMessage()); //$NON-NLS-1$
        }
        refreshServerStatus();
    }

    private void stopServer()
    {
        McpHttpEndpoint server = Activator.getDefault().getMcpServer();
        if (server == null)
        {
            return;
        }
        server.stop();
        refreshServerStatus();
    }

    private void restartServer()
    {
        McpHttpEndpoint server = Activator.getDefault().getMcpServer();
        if (server == null)
        {
            return;
        }
        performOk();
        try
        {
            server.restart(portSpinner.getSelection());
        }
        catch (IOException e)
        {
            Activator.logError("The endpoint could not be restarted", e); //$NON-NLS-1$
            MessageDialog.openError(control.getShell(), "Restart Failed", //$NON-NLS-1$
                "Failed to restart MCP Server: " + e.getMessage()); //$NON-NLS-1$
        }
        refreshServerStatus();
    }

    /**
     * Repaints the status line and re-enables the buttons for the current server state. Called after
     * every click; not on a timer, so a server started elsewhere leaves this stale until the next
     * click - which is the intended behaviour.
     */
    private void refreshServerStatus()
    {
        McpHttpEndpoint server = Activator.getDefault().getMcpServer();
        boolean running = server != null && server.isRunning();

        if (running)
        {
            statusLabel.setText("Listening on :" + server.getPort()); //$NON-NLS-1$
            statusLabel.setForeground(control.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
        }
        else
        {
            statusLabel.setText("Not running"); //$NON-NLS-1$
            statusLabel.setForeground(control.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        }

        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        restartButton.setEnabled(running);
    }

    /**
     * Builds a titled section the fields are laid out inside. Uses an SWT forms {@link Section} with an
     * emerald title bar - the AI-EDT accent - instead of a beveled group box, so the page reads as a
     * stack of flat cards rather than etched panels.
     *
     * @param title the section label
     * @return the section body, a 3-column grid
     */
    private Composite section(String title)
    {
        Composite card = new Composite(control, SWT.BORDER);
        GridLayout cardLayout = new GridLayout(1, false);
        cardLayout.marginWidth = 0;
        cardLayout.marginHeight = 0;
        cardLayout.verticalSpacing = 0;
        card.setLayout(cardLayout);
        card.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label header = new Label(card, SWT.NONE);
        header.setText(" " + title); //$NON-NLS-1$
        header.setBackground(headerGrey);
        header.setFont(sectionFont);
        GridData headerData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        headerData.heightHint = 22;
        header.setLayoutData(headerData);

        Composite body = new Composite(card, SWT.NONE);
        GridLayout bodyLayout = new GridLayout(3, false);
        bodyLayout.marginWidth = 8;
        bodyLayout.marginHeight = 8;
        bodyLayout.verticalSpacing = 6;
        body.setLayout(bodyLayout);
        body.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return body;
    }

    private Text pathRow(Composite parent, String labelText, String tooltip, String prefKey)
    {
        Label label = new Label(parent, SWT.NONE);
        label.setText(labelText);

        Text text = new Text(parent, SWT.BORDER);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.setText(store.getString(prefKey));
        if (tooltip != null)
        {
            text.setToolTipText(tooltip);
        }
        return text;
    }

    private void addFolderBrowse(Composite parent, Text target, String message)
    {
        Button browse = new Button(parent, SWT.PUSH);
        browse.setText("Browse..."); //$NON-NLS-1$
        browse.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            DirectoryDialog dialog = new DirectoryDialog(control.getShell());
            dialog.setMessage(message);
            String result = dialog.open();
            if (result != null)
            {
                target.setText(result);
            }
        }));
    }

    private void addFileBrowse(Composite parent, Text target, String title, String[] filter)
    {
        Button browse = new Button(parent, SWT.PUSH);
        browse.setText("Browse..."); //$NON-NLS-1$
        browse.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            FileDialog dialog = new FileDialog(control.getShell(), SWT.OPEN);
            dialog.setText(title);
            if (filter != null)
            {
                dialog.setFilterExtensions(filter);
            }
            String result = dialog.open();
            if (result != null)
            {
                target.setText(result);
            }
        }));
    }

    private Button controlButton(Composite parent, String text, String iconPath)
    {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        ImageDescriptor descriptor = AbstractUIPlugin.imageDescriptorFromPlugin(Activator.PLUGIN_ID, iconPath);
        if (descriptor != null)
        {
            Image image = descriptor.createImage();
            images.add(image);
            button.setImage(image);
        }
        return button;
    }

    /**
     * Selects the combo item for a stored style value; falls back to the first item when the value is
     * one the combo does not offer.
     *
     * @param value the stored style value
     */
    private void selectMarkerStyle(String value)
    {
        for (int i = 0; i < MARKER_STYLE_VALUES.length; i++)
        {
            if (MARKER_STYLE_VALUES[i].equals(value))
            {
                markerStyleCombo.select(i);
                return;
            }
        }
        markerStyleCombo.select(0);
    }

    private void spacer(Composite parent)
    {
        new Label(parent, SWT.NONE);
    }

    private static GridData span(Composite parent, int columns)
    {
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.horizontalSpan = columns;
        return data;
    }
}
