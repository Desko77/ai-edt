/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import java.io.IOException;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.McpHttpEndpoint;
import ru.aiedt.mcp.server.wire.McpServerMeta;

/**
 * The AI-EDT preference page: two tabs, General and Tools, on the plugin's own preference store.
 * <p>
 * The page itself is a shell. It builds the two tabs, hands each its slice of OK and Restore
 * Defaults, and disposes them - which is the only thing that frees the images the tabs create, so
 * losing this override leaks them on every open.
 * </p>
 */
public class McpSettingsPage
    extends PreferencePage
    implements IWorkbenchPreferencePage
{
    private GeneralPrefTab generalTab;

    private ToolsPrefTab toolsTab;

    /**
     * Binds the page to the plugin store and gives it its heading.
     */
    public McpSettingsPage()
    {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("AI-EDT " + McpServerMeta.PLUGIN_VERSION //$NON-NLS-1$
            + " - AI-assisted 1C:EDT tooling over MCP"); //$NON-NLS-1$
    }

    @Override
    public void init(IWorkbench workbench)
    {
        // Nothing to take from the workbench; the store is set in the constructor.
    }

    @Override
    protected Control createContents(Composite parent)
    {
        Composite composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        CTabFolder tabFolder = new CTabFolder(composite, SWT.BORDER);
        tabFolder.setSimple(true);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        generalTab = new GeneralPrefTab(tabFolder);
        generalTab.setValidationListener(this::revalidate);
        CTabItem generalItem = new CTabItem(tabFolder, SWT.NONE);
        generalItem.setText("General"); //$NON-NLS-1$
        generalItem.setControl(generalTab.getControl());

        toolsTab = new ToolsPrefTab(tabFolder);
        CTabItem toolsItem = new CTabItem(tabFolder, SWT.NONE);
        toolsItem.setText("Tools"); //$NON-NLS-1$
        toolsItem.setControl(toolsTab.getControl());

        tabFolder.setSelection(0);
        revalidate();
        return composite;
    }

    /**
     * Rechecks the fields that can hold something unusable and reflects the verdict on the page.
     * <p>
     * Disabling OK is what makes the check meaningful: telling someone their update site is wrong
     * and then saving it anyway would leave a setting that silently fails hours later, in a
     * background task with nobody watching.
     * </p>
     */
    private void revalidate()
    {
        String problem = generalTab.validateUpkeepSite();
        setErrorMessage(problem);
        setValid(problem == null);
    }

    @Override
    public boolean performOk()
    {
        // Belt and braces: OK is already disabled while the page is invalid, but a saved bad
        // address is expensive enough to be worth refusing on the way out too.
        String problem = generalTab.validateUpkeepSite();
        if (problem != null)
        {
            setErrorMessage(problem);
            return false;
        }
        // Read before saving: the flag compares against what is in the store, so once the store is
        // written it always reads false and the restart below never fires. Order matters here.
        boolean toolsChanged = toolsTab.hasChanges();

        generalTab.performOk();
        toolsTab.performOk();

        if (toolsChanged)
        {
            McpHttpEndpoint server = Activator.getDefault().getMcpServer();
            if (server != null && server.isRunning())
            {
                try
                {
                    // Strictly the registry re-reads the disabled set per request, so this is not
                    // needed to apply the change - but it drops the open connections, which is
                    // observable, so it stays.
                    server.restart(generalTab.getPort());
                    Activator.logInfo("MCP Server restarted following a tool configuration change"); //$NON-NLS-1$
                }
                catch (IOException e)
                {
                    Activator.logError("MCP Server restart failed after a tool change", e); //$NON-NLS-1$
                }
            }
        }

        return super.performOk();
    }

    @Override
    protected void performDefaults()
    {
        generalTab.performDefaults();
        toolsTab.performDefaults();
        super.performDefaults();
    }

    @Override
    public void dispose()
    {
        if (generalTab != null)
        {
            generalTab.dispose();
        }
        if (toolsTab != null)
        {
            toolsTab.dispose();
        }
        super.dispose();
    }
}
