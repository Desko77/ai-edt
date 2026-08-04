/**

 * AI-EDT - 1C AI tools for EDT

 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)

 * Licensed under AGPL-3.0-or-later

 */



package ru.aiedt.mcp.server.labels.ui;



import java.util.Set;

import java.util.concurrent.atomic.AtomicLong;



import org.eclipse.core.resources.IProject;

import org.eclipse.emf.ecore.EObject;

import org.eclipse.jface.preference.IPreferenceStore;

import org.eclipse.jface.util.IPropertyChangeListener;

import org.eclipse.jface.util.PropertyChangeEvent;

import org.eclipse.jface.viewers.IDecoration;

import org.eclipse.jface.viewers.ILabelProviderListener;

import org.eclipse.jface.viewers.ILightweightLabelDecorator;

import org.eclipse.swt.widgets.Display;

import org.eclipse.ui.IWorkbench;

import org.eclipse.ui.PlatformUI;



import com._1c.g5.v8.bm.core.IBmObject;



import ru.aiedt.mcp.server.Activator;

import ru.aiedt.mcp.server.settings.PrefKeys;

import ru.aiedt.mcp.server.labels.MarkerDecorationHelpers;

import ru.aiedt.mcp.server.labels.MarkerManager;

import ru.aiedt.mcp.server.labels.MarkerHelpers;

import ru.aiedt.mcp.server.labels.model.Marker;



/**

 * Appends a marker suffix, such as {@code  [bug, critical]}, to metadata object labels in the Navigator.

 * <p>

 * The suffix and whether it appears at all come from two preferences; an object with no markers gets no

 * suffix and so looks exactly like an unmarked one. The decorator listens for marker changes and for

 * changes to those preferences, and refreshes itself - debounced to {@value #REFRESH_DEBOUNCE_MS}

 * milliseconds so a burst of assignments repaints the tree once rather than once each.

 * </p>

 */

public class MarkerBadgeDecorator

    implements ILightweightLabelDecorator, MarkerManager.IMarkerChangeListener, IPropertyChangeListener

{

    /** The decorator id, which must match the one the refresh call names. */

    private static final String DECORATOR_ID = "ru.aiedt.mcp.server.labels.decorator"; //$NON-NLS-1$



    /** How long refreshes are coalesced, in milliseconds. */

    private static final long REFRESH_DEBOUNCE_MS = 100;



    private final AtomicLong lastRefreshRequest = new AtomicLong(0);



    private volatile boolean refreshPending;



    /**

     * Builds the decorator and starts listening for marker and preference changes. Called by the

     * decorators extension point.

     */

    public MarkerBadgeDecorator()

    {

        MarkerManager.getInstance().addMarkerChangeListener(this);

        IPreferenceStore store = getPreferenceStore();

        if (store != null)

        {

            store.addPropertyChangeListener(this);

        }

    }



    @Override

    public void decorate(Object element, IDecoration decoration)

    {

        IPreferenceStore store = getPreferenceStore();

        if (store == null || !store.getBoolean(PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR))

        {

            return;

        }

        if (!(element instanceof EObject))

        {

            return;

        }

        EObject eObject = (EObject)element;

        IProject project = MarkerHelpers.extractProject(eObject);

        if (project == null)

        {

            return;

        }

        String fqn = eObject instanceof IBmObject ? MarkerHelpers.extractFqn((IBmObject)eObject)

            : MarkerHelpers.extractFqn(eObject);

        if (fqn == null)

        {

            return;

        }

        Set<Marker> markers = MarkerManager.getInstance().getObjectMarkers(project, fqn);

        if (markers.isEmpty())

        {

            return;

        }

        String style = store.getString(PrefKeys.PREF_MARKERS_DECORATION_STYLE);

        String suffix = MarkerDecorationHelpers.formatMarkers(markers, style);

        if (!suffix.isEmpty())

        {

            decoration.addSuffix(suffix);

        }

    }



    @Override

    public void onMarkersChanged(IProject project)

    {

        scheduleRefresh();

    }



    @Override

    public void onAssignmentsChanged(IProject project, String objectFqn)

    {

        scheduleRefresh();

    }



    @Override

    public void propertyChange(PropertyChangeEvent event)

    {

        String property = event.getProperty();

        if (PrefKeys.PREF_MARKERS_SHOW_IN_NAVIGATOR.equals(property)

            || PrefKeys.PREF_MARKERS_DECORATION_STYLE.equals(property))

        {

            scheduleRefresh();

        }

    }



    @Override

    public void addListener(ILabelProviderListener listener)

    {

        // Lightweight decorators do not push change events of their own.

    }



    @Override

    public void removeListener(ILabelProviderListener listener)

    {

        // Nothing registered in addListener.

    }



    @Override

    public boolean isLabelProperty(Object element, String property)

    {

        return false;

    }



    @Override

    public void dispose()

    {

        MarkerManager.getInstance().removeMarkerChangeListener(this);

        IPreferenceStore store = getPreferenceStore();

        if (store != null)

        {

            store.removePropertyChangeListener(this);

        }

    }



    /**

     * Requests a debounced refresh of the decoration, safe to call from any thread.

     */

    private void scheduleRefresh()

    {

        lastRefreshRequest.set(System.currentTimeMillis());

        if (refreshPending)

        {

            return;

        }

        refreshPending = true;

        Display display = Display.getDefault();

        if (display != null && !display.isDisposed())

        {

            display.timerExec((int)REFRESH_DEBOUNCE_MS, this::executeRefresh);

        }

        else

        {

            refreshPending = false;

        }

    }



    /**

     * Repaints the decoration, or reschedules itself when more requests arrived inside the debounce

     * window so the tree is refreshed only once the burst has settled.

     */

    private void executeRefresh()

    {

        long elapsed = System.currentTimeMillis() - lastRefreshRequest.get();

        Display display = Display.getDefault();

        if (elapsed < REFRESH_DEBOUNCE_MS)

        {

            if (display != null && !display.isDisposed())

            {

                display.timerExec((int)(REFRESH_DEBOUNCE_MS - elapsed), this::executeRefresh);

                return;

            }

        }

        refreshPending = false;

        if (!PlatformUI.isWorkbenchRunning())

        {

            return;

        }

        IWorkbench workbench = PlatformUI.getWorkbench();

        if (workbench != null)

        {

            workbench.getDecoratorManager().update(DECORATOR_ID);

        }

    }



    /**

     * Returns the plugin preference store, or <code>null</code> when the plugin is not running.

     *

     * @return the preference store, or <code>null</code>

     */

    private static IPreferenceStore getPreferenceStore()

    {

        Activator activator = Activator.getDefault();

        return activator != null ? activator.getPreferenceStore() : null;

    }

}

