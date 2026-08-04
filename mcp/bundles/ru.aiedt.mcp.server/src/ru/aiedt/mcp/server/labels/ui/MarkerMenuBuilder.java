/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.IParameter;
import org.eclipse.core.commands.Parameterization;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.keys.IBindingService;

import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.MarkerHelpers;
import ru.aiedt.mcp.server.labels.handlers.ToggleMarkerByIndexCommand;
import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * The dynamic "Markers" context submenu: one checkable entry per marker defined for the selected objects.
 * <p>
 * Entries are sorted by name and each is checked only when every selected object already carries that
 * marker. An entry that maps to one of the first ten markers of its project shows the live
 * {@code Ctrl+Alt+N} accelerator next to it. Toggling an entry assigns or unassigns the marker on every
 * selected object whose project defines it. With no resolvable selection the submenu is empty.
 * </p>
 */
public class MarkerMenuBuilder
    extends CompoundContributionItem
{
    private static final int ICON_SIZE = 16;

    /** One selected object reduced to its project and FQN. */
    private record ObjectInfo(IProject project, String fqn)
    {
    }

    /**
     * Creates the contribution.
     */
    public MarkerMenuBuilder()
    {
        super();
    }

    /**
     * Creates the contribution with an explicit id.
     *
     * @param id the contribution id
     */
    public MarkerMenuBuilder(String id)
    {
        super(id);
    }

    @Override
    public boolean isDynamic()
    {
        return true;
    }

    @Override
    protected IContributionItem[] getContributionItems()
    {
        List<ObjectInfo> objects = collectObjects();
        if (objects.isEmpty())
        {
            return new IContributionItem[0];
        }

        Map<String, MarkerEntry> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        MarkerManager service = MarkerManager.getInstance();
        for (ObjectInfo info : objects)
        {
            for (Marker marker : service.getMarkers(info.project()))
            {
                entries.computeIfAbsent(marker.getName(),
                    name -> new MarkerEntry(name, marker.getColor(), service.getMarkerHotkeyIndex(info.project(), name)));
            }
        }
        if (entries.isEmpty())
        {
            return new IContributionItem[0];
        }

        List<IContributionItem> items = new ArrayList<>();
        for (MarkerEntry entry : entries.values())
        {
            items.add(new MultiMarkerMenuItem(entry, objects));
        }
        items.add(new Separator());
        return items.toArray(new IContributionItem[0]);
    }

    /**
     * Reduces the workbench selection to the objects the menu can act on.
     *
     * @return the resolved objects, possibly empty
     */
    private static List<ObjectInfo> collectObjects()
    {
        List<ObjectInfo> result = new ArrayList<>();
        if (!PlatformUI.isWorkbenchRunning())
        {
            return result;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
        {
            return result;
        }
        Object selection = window.getSelectionService().getSelection();
        if (!(selection instanceof IStructuredSelection))
        {
            return result;
        }
        for (Object element : (IStructuredSelection)selection)
        {
            IProject project = MarkerHelpers.extractProjectFromElement(element);
            Object mdObject = MarkerHelpers.extractMdObject(element);
            if (project == null || !(mdObject instanceof org.eclipse.emf.ecore.EObject))
            {
                continue;
            }
            org.eclipse.emf.ecore.EObject eObject = (org.eclipse.emf.ecore.EObject)mdObject;
            String fqn = eObject instanceof com._1c.g5.v8.bm.core.IBmObject
                ? MarkerHelpers.extractFqn((com._1c.g5.v8.bm.core.IBmObject)eObject) : MarkerHelpers.extractFqn(eObject);
            if (fqn != null)
            {
                result.add(new ObjectInfo(project, fqn));
            }
        }
        return result;
    }

    /**
     * Returns the live {@code Ctrl+Alt+N} accelerator text for a toggle digit, or an empty string when
     * none is bound.
     *
     * @param digit the keyboard digit (1..9 or 0 for the tenth)
     * @return the formatted accelerator, or an empty string
     */
    private static String getHotkeyString(int digit)
    {
        if (!PlatformUI.isWorkbenchRunning())
        {
            return ""; //$NON-NLS-1$
        }
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        IBindingService bindingService = PlatformUI.getWorkbench().getService(IBindingService.class);
        if (commandService == null || bindingService == null)
        {
            return ""; //$NON-NLS-1$
        }
        try
        {
            Command command = commandService.getCommand(ToggleMarkerByIndexCommand.COMMAND_ID);
            IParameter parameter = command.getParameter(ToggleMarkerByIndexCommand.PARAM_TAG_INDEX);
            Parameterization parameterization = new Parameterization(parameter, String.valueOf(digit));
            ParameterizedCommand target =
                new ParameterizedCommand(command, new Parameterization[] {parameterization});
            for (Binding binding : bindingService.getBindings())
            {
                if (target.equals(binding.getParameterizedCommand()))
                {
                    TriggerSequence trigger = binding.getTriggerSequence();
                    if (trigger instanceof KeySequence)
                    {
                        return ((KeySequence)trigger).format();
                    }
                }
            }
        }
        catch (org.eclipse.core.commands.common.NotDefinedException e)
        {
            // The command or parameter is not defined; show no accelerator.
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * A marker as it appears in the menu: its name, color and keyboard digit.
     */
    private record MarkerEntry(String name, String color, int hotkeyDigit)
    {
    }

    /**
     * One checkable marker menu item that toggles the marker across the whole selection.
     */
    private static final class MultiMarkerMenuItem
        extends ContributionItem
    {
        private final MarkerEntry entry;

        private final List<ObjectInfo> objects;

        MultiMarkerMenuItem(MarkerEntry entry, List<ObjectInfo> objects)
        {
            this.entry = entry;
            this.objects = objects;
        }

        @Override
        public boolean isDynamic()
        {
            return true;
        }

        @Override
        public void fill(Menu menu, int index)
        {
            boolean checked = allCarry();
            MenuItem item = index >= 0 ? new MenuItem(menu, SWT.CHECK, index) : new MenuItem(menu, SWT.CHECK);
            String hotkey = entry.hotkeyDigit() >= 0 ? getHotkeyString(entry.hotkeyDigit()) : ""; //$NON-NLS-1$
            item.setText(hotkey.isEmpty() ? entry.name() : entry.name() + "\t" + hotkey); //$NON-NLS-1$
            item.setSelection(checked);

            Image image =
                MarkerIconFactory.getCircularColorIconWithCheck(entry.color(), ICON_SIZE, checked).createImage();
            item.setImage(image);
            item.addDisposeListener(event -> {
                if (!image.isDisposed())
                {
                    image.dispose();
                }
            });

            item.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> {
                // Read the new state now: the item may be disposed by the time the async runnable runs.
                boolean nowChecked = item.getSelection();
                Display display = Display.getCurrent();
                if (display == null)
                {
                    display = Display.getDefault();
                }
                display.asyncExec(() -> toggle(nowChecked));
            }));
        }

        /**
         * Tells whether every selected object already carries this marker.
         *
         * @return <code>true</code> when all carry it
         */
        private boolean allCarry()
        {
            MarkerManager service = MarkerManager.getInstance();
            for (ObjectInfo info : objects)
            {
                if (!service.getMarkerStorage(info.project()).getMarkerNames(info.fqn()).contains(entry.name()))
                {
                    return false;
                }
            }
            return true;
        }

        /**
         * Assigns or unassigns the marker on every selected object whose project defines it.
         *
         * @param assign whether to assign (as opposed to unassign)
         */
        private void toggle(boolean assign)
        {
            MarkerManager service = MarkerManager.getInstance();
            for (ObjectInfo info : objects)
            {
                if (service.getMarkerStorage(info.project()).getMarkerByName(entry.name()) == null)
                {
                    continue;
                }
                if (assign)
                {
                    service.assignMarker(info.project(), info.fqn(), entry.name());
                }
                else
                {
                    service.unassignMarker(info.project(), info.fqn(), entry.name());
                }
            }
        }
    }
}
