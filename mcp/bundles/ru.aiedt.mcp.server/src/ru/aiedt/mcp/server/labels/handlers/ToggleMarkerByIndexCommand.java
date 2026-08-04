/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.MarkerHelpers;
import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * Toggles the marker at a keyboard position on every selected object.
 * <p>
 * Bound to {@code Ctrl+Alt+1} through {@code Ctrl+Alt+0}, where 0 stands for the tenth marker. For each
 * selected object the marker at that 1-based position among its project's markers is added if absent and
 * removed if present; objects whose project has fewer markers than the position are left alone.
 * </p>
 */
public class ToggleMarkerByIndexCommand
    extends MarkerCommandHandler
{
    /** The id of the toggle command this handler serves. */
    public static final String COMMAND_ID = "ru.aiedt.mcp.server.labels.toggleMarker"; //$NON-NLS-1$

    /** The id of the command parameter carrying the 1-based marker position. */
    public static final String PARAM_TAG_INDEX = "ru.aiedt.mcp.server.labels.toggleMarker.index"; //$NON-NLS-1$

    /** The position that stands for the tenth marker, entered as {@code Ctrl+Alt+0}. */
    private static final int TENTH_POSITION = 10;

    /** A selected object reduced to what the toggle needs. */
    private record ObjectInfo(IProject project, String fqn)
    {
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        String parameter = event.getParameter(PARAM_TAG_INDEX);
        if (parameter == null)
        {
            return null;
        }
        int position;
        try
        {
            position = Integer.parseInt(parameter);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
        if (position == 0)
        {
            position = TENTH_POSITION;
        }

        MarkerManager service = MarkerManager.getInstance();
        for (ObjectInfo info : collectObjects(event))
        {
            List<Marker> markers = service.getMarkers(info.project());
            if (markers.size() < position)
            {
                continue;
            }
            Marker marker = markers.get(position - 1);
            boolean assigned = service.getObjectMarkers(info.project(), info.fqn()).contains(marker);
            if (assigned)
            {
                service.unassignMarker(info.project(), info.fqn(), marker.getName());
            }
            else
            {
                service.assignMarker(info.project(), info.fqn(), marker.getName());
            }
        }
        return null;
    }

    /**
     * Reduces the current selection to the objects the toggle can act on.
     *
     * @param event the command event
     * @return the resolved objects, in selection order; possibly empty
     */
    private List<ObjectInfo> collectObjects(ExecutionEvent event)
    {
        List<ObjectInfo> result = new ArrayList<>();
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection))
        {
            return result;
        }
        for (Object element : (IStructuredSelection)selection)
        {
            EObject mdObject = MarkerHelpers.extractMdObject(element);
            IProject project = MarkerHelpers.extractProjectFromElement(element);
            if (mdObject == null || project == null)
            {
                continue;
            }
            String fqn = extractFqn(mdObject);
            if (fqn != null)
            {
                result.add(new ObjectInfo(project, fqn));
            }
        }
        return result;
    }
}
