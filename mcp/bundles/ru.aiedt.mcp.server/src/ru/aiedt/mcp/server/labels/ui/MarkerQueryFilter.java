/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.ui.ClusterNavigatorBridge;
import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.MarkerHelpers;
import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * Hides everything in the EDT Navigator except the objects carrying the selected markers and the tree
 * path down to them.
 * <p>
 * The filter does nothing until it is switched on with a set of markers per project. Once on, an element
 * passes when its FQN is among the project's matching objects, when it is a container on the path to
 * one (the project, the configuration, a folder, a parent subsystem, a cluster), or - in
 * "show unmarked only" mode - when it carries no markers at all. Elements that resolve to nothing known
 * are hidden.
 * </p>
 */
public class MarkerQueryFilter
    extends ViewerFilter
{
    /** The navigator content id this filter is registered under. */
    public static final String FILTER_ID = "ru.aiedt.mcp.server.labels.MarkerQueryFilter"; //$NON-NLS-1$

    /**
     * The metadata types clustered under the Navigator's "Common" node. Cross-checked against the model
     * package at use, so a name the running EDT does not know is simply ignored.
     */
    private static final String[] COMMON_FOLDER_TYPES =
        {"Subsystem", "CommonModule", "SessionParameter", "Role", "CommonAttribute", "ExchangePlan", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "FilterCriterion", "EventSubscription", "ScheduledJob", "FunctionalOption", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "FunctionalOptionsParameter", "DefinedType", "SettingsStorage", "CommonForm", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "CommonCommand", "CommandGroup", "CommonTemplate", "CommonPicture", "Interface", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "XDTOPackage", "WebService", "HTTPService", "WSReference", "Style", "StyleItem", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "Language", "Bot", "IntegrationService", "ExternalDataSource"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private boolean dialogMode;

    private boolean showUnmarkedOnly;

    private final Map<IProject, Set<Marker>> selectedMarkers = new HashMap<>();

    private final Map<IProject, Set<String>> matchCache = new HashMap<>();

    /**
     * Creates an inactive filter. The Navigator extension factory calls this.
     */
    public MarkerQueryFilter()
    {
        // Starts switched off.
    }

    /**
     * Switches the filter on with the markers to keep, per project.
     *
     * @param markersByProject the selected markers for each project
     */
    public void setSelectedMarkersMode(Map<IProject, Set<Marker>> markersByProject)
    {
        selectedMarkers.clear();
        if (markersByProject != null)
        {
            selectedMarkers.putAll(markersByProject);
        }
        showUnmarkedOnly = false;
        dialogMode = true;
        matchCache.clear();
    }

    /**
     * Switches the filter to show only unmarked objects.
     *
     * @param unmarkedOnly whether to invert the filter to unmarked objects
     */
    public void setShowUnmarkedOnly(boolean unmarkedOnly)
    {
        showUnmarkedOnly = unmarkedOnly;
        dialogMode = true;
        matchCache.clear();
    }

    /**
     * Switches the filter off, so everything is shown again.
     */
    public void clearSelectedMarkersMode()
    {
        dialogMode = false;
        showUnmarkedOnly = false;
        selectedMarkers.clear();
        matchCache.clear();
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element)
    {
        if (!dialogMode)
        {
            return true;
        }
        if (element instanceof IProject)
        {
            return projectHasVisible((IProject)element);
        }
        if (element instanceof ClusterNavigatorBridge)
        {
            return clusterHasMatch((ClusterNavigatorBridge)element);
        }

        EObject eObject = element instanceof EObject ? (EObject)element : MarkerHelpers.unwrapToEObject(element);
        IProject project = MarkerHelpers.extractProjectFromElement(element);
        if (eObject != null)
        {
            if (eObject instanceof Configuration)
            {
                return project != null && projectHasVisible(project);
            }
            if (project == null)
            {
                return false;
            }
            return isEObjectVisible(project, eObject);
        }

        if (project != null && isFolderAdapter(element))
        {
            return folderVisible(project, element);
        }
        return false;
    }

    /**
     * Tells whether a metadata object should be shown under the active filter.
     *
     * @param project the object's project
     * @param eObject the object
     * @return <code>true</code> when it or its path matches
     */
    private boolean isEObjectVisible(IProject project, EObject eObject)
    {
        String fqn = fqnOf(eObject);
        if (fqn == null)
        {
            // A subsystem or wrapper we could not name; keep it if it plausibly holds matches.
            return eObject instanceof Subsystem && projectHasVisible(project);
        }
        Set<String> matching = matchingFqns(project);
        if (showUnmarkedOnly)
        {
            return !matching.contains(fqn);
        }
        return matching.contains(fqn) || isAncestorOfMatch(fqn, matching)
            || isDescendantOfMatch(fqn, matching);
    }

    /**
     * Tells whether a cluster node should be shown, which it should when it holds a matching child.
     *
     * @param adapter the cluster adapter
     * @return <code>true</code> when a child of the cluster matches
     */
    private boolean clusterHasMatch(ClusterNavigatorBridge adapter)
    {
        IProject project = adapter.getProject();
        Cluster cluster = adapter.getCluster();
        if (project == null || cluster == null)
        {
            return showUnmarkedOnly;
        }
        Set<String> matching = matchingFqns(project);
        for (String childFqn : cluster.getChildren())
        {
            if (showUnmarkedOnly)
            {
                if (!matching.contains(childFqn))
                {
                    return true;
                }
            }
            else if (matching.contains(childFqn) || isDescendantOfMatch(childFqn, matching))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a Navigator folder node should be shown, by matching the metadata types it holds
     * against the projects's matching objects.
     *
     * @param project the folder's project
     * @param element the folder adapter
     * @return <code>true</code> when the folder holds a matching object
     */
    private boolean folderVisible(IProject project, Object element)
    {
        if (showUnmarkedOnly)
        {
            return true;
        }
        Set<String> matching = matchingFqns(project);
        if (matching.isEmpty())
        {
            return false;
        }
        Set<String> types = folderTypeNames(element);
        if (types.isEmpty())
        {
            // Cannot classify the folder; keep it so a matching child is never hidden behind it.
            return true;
        }
        for (String fqn : matching)
        {
            if (types.contains(topType(fqn)))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the metadata type names a folder node represents.
     *
     * @param element the folder adapter
     * @return the type names, filtered to those the model package knows; possibly empty
     */
    private Set<String> folderTypeNames(Object element)
    {
        Set<String> types = new HashSet<>();
        String label = folderLabel(element);
        if (label != null && label.toLowerCase().contains("common")) //$NON-NLS-1$
        {
            for (String type : COMMON_FOLDER_TYPES)
            {
                if (isKnownType(type))
                {
                    types.add(type);
                }
            }
            return types;
        }
        String simpleName = element.getClass().getSimpleName();
        for (String candidate : new String[] {label, simpleName})
        {
            String type = matchTypeName(candidate);
            if (type != null)
            {
                types.add(type);
            }
        }
        return types;
    }

    /**
     * Reduces a candidate string to a known metadata type name, or <code>null</code>.
     *
     * @param candidate the candidate text
     * @return a known type name, or <code>null</code>
     */
    private String matchTypeName(String candidate)
    {
        if (candidate == null)
        {
            return null;
        }
        String trimmed = candidate.replace("NavigatorAdapter", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("Folder", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        // Folder labels are usually the plural collection name; try the singular too.
        for (String form : new String[] {trimmed, singularize(trimmed)})
        {
            if (isKnownType(form))
            {
                return form;
            }
        }
        return null;
    }

    /**
     * Returns a naive singular of a collection name.
     *
     * @param plural the plural form
     * @return the singular guess
     */
    private String singularize(String plural)
    {
        if (plural.endsWith("ies")) //$NON-NLS-1$
        {
            return plural.substring(0, plural.length() - 3) + "y"; //$NON-NLS-1$
        }
        if (plural.endsWith("s")) //$NON-NLS-1$
        {
            return plural.substring(0, plural.length() - 1);
        }
        return plural;
    }

    /**
     * Tells whether a name is an EClassifier of the metadata model package.
     *
     * @param name the candidate type name
     * @return <code>true</code> when the model package defines it
     */
    private boolean isKnownType(String name)
    {
        return name != null && !name.isEmpty() && MdClassPackage.eINSTANCE.getEClassifier(name) != null;
    }

    /**
     * Reads a display label off a folder adapter reflectively.
     *
     * @param element the folder adapter
     * @return its label, or <code>null</code>
     */
    private String folderLabel(Object element)
    {
        Object viaGetText = MarkerHelpers.unwrapToEObject(element) == null ? invoke(element, "getLabel") : null; //$NON-NLS-1$
        if (viaGetText instanceof String)
        {
            return (String)viaGetText;
        }
        String text = element.toString();
        return text != null && !text.isEmpty() ? text : null;
    }

    /**
     * Invokes a zero-argument accessor reflectively.
     *
     * @param target the object
     * @param method the method name
     * @return the result, or <code>null</code> on any failure
     */
    private Object invoke(Object target, String method)
    {
        try
        {
            return target.getClass().getMethod(method).invoke(target);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Tells whether a folder-style Navigator adapter is what an element is.
     *
     * @param element the element
     * @return <code>true</code> when its class looks like a folder adapter
     */
    private boolean isFolderAdapter(Object element)
    {
        String className = element.getClass().getName();
        return className.endsWith("NavigatorAdapter") || className.endsWith("$Folder") //$NON-NLS-1$ //$NON-NLS-2$
            || className.contains("Folder"); //$NON-NLS-1$
    }

    /**
     * Tells whether a project has anything to show under the active filter.
     *
     * @param project the project
     * @return <code>true</code> when the project should be shown
     */
    private boolean projectHasVisible(IProject project)
    {
        if (showUnmarkedOnly)
        {
            return true;
        }
        return !matchingFqns(project).isEmpty();
    }

    /**
     * Returns the FQNs that match the filter for a project, computing and caching them on first use.
     *
     * @param project the project
     * @return the matching FQNs (the marked objects in unmarked mode, the selected-marker objects
     *         otherwise)
     */
    private Set<String> matchingFqns(IProject project)
    {
        Set<String> cached = matchCache.get(project);
        if (cached != null)
        {
            return cached;
        }
        Set<String> result;
        if (showUnmarkedOnly)
        {
            result = new HashSet<>(MarkerManager.getInstance().getMarkerStorage(project).getAssignments().keySet());
        }
        else
        {
            Set<Marker> markers = selectedMarkers.getOrDefault(project, Set.of());
            Set<String> names = new HashSet<>();
            for (Marker marker : markers)
            {
                names.add(marker.getName());
            }
            result = new HashSet<>(MarkerManager.getInstance().findObjectsByMarkers(project, names).keySet());
        }
        matchCache.put(project, result);
        return result;
    }

    /**
     * Tells whether the given FQN is an ancestor of any matching FQN.
     *
     * @param fqn the candidate ancestor FQN
     * @param matching the matching FQNs
     * @return <code>true</code> when a match sits below it
     */
    private boolean isAncestorOfMatch(String fqn, Set<String> matching)
    {
        String prefix = fqn + "."; //$NON-NLS-1$
        for (String match : matching)
        {
            if (match.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether the given FQN sits below any matching FQN.
     *
     * @param fqn the candidate descendant FQN
     * @param matching the matching FQNs
     * @return <code>true</code> when a match sits above it
     */
    private boolean isDescendantOfMatch(String fqn, Set<String> matching)
    {
        for (String match : matching)
        {
            if (fqn.startsWith(match + ".")) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the top type segment of an FQN.
     *
     * @param fqn the FQN
     * @return the leading type name
     */
    private String topType(String fqn)
    {
        int dot = fqn.indexOf('.');
        return dot < 0 ? fqn : fqn.substring(0, dot);
    }

    /**
     * Returns the FQN of a metadata object, preferring the BM engine's answer.
     *
     * @param eObject the object
     * @return its FQN, or <code>null</code>
     */
    private String fqnOf(EObject eObject)
    {
        return eObject instanceof IBmObject ? MarkerHelpers.extractFqn((IBmObject)eObject)
            : MarkerHelpers.extractFqn(eObject);
    }
}
