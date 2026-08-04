/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;

/**
 * Utility for collecting and grouping EDT validation markers.
 * <p>
 * Filters markers via {@link Marker#getObjectPresentation()} (FQN of the
 * containing metadata object), since EDT marker API does not expose
 * direct file-level filtering. Used by {@code ModuleSourceWriter}
 * for post-write validation feedback and shared with
 * {@code ProjectProblemsReader} for object-scope queries.
 */
public final class FileMarkers
{
    private FileMarkers()
    {
        // utility class
    }

    /**
     * Lightweight POJO carrying a subset of marker fields safe to
     * serialize into a tool response.
     */
    public static final class MarkerInfo
    {
        public String message = ""; //$NON-NLS-1$
        public String severity = ""; //$NON-NLS-1$
        public String checkId = ""; //$NON-NLS-1$
        public String objectPresentation = ""; //$NON-NLS-1$
        public int line = -1;
    }

    /**
     * Snapshot of markers grouped by severity classification.
     * <ul>
     * <li>{@code errors}: BLOCKER, CRITICAL (must-fix to compile/deploy)</li>
     * <li>{@code warnings}: MAJOR (semantic issues, often real bugs)</li>
     * <li>{@code codeStyle}: MINOR, TRIVIAL (style hints)</li>
     * </ul>
     */
    public static final class Grouped
    {
        public final List<MarkerInfo> errors = new ArrayList<>();
        public final List<MarkerInfo> warnings = new ArrayList<>();
        public final List<MarkerInfo> codeStyle = new ArrayList<>();

        public int errorCount()
        {
            return errors.size();
        }

        public int warningCount()
        {
            return warnings.size();
        }

        public int codeStyleCount()
        {
            return codeStyle.size();
        }

        public boolean isEmpty()
        {
            return errors.isEmpty() && warnings.isEmpty() && codeStyle.isEmpty();
        }
    }

    /**
     * Returns markers whose object presentation contains the given FQN
     * (case-insensitive substring match). Limit applied last.
     *
     * @param markerManager EDT marker manager (must not be null)
     * @param project filter project (must not be null)
     * @param objectFqn FQN substring, e.g. {@code "Document.SalesOrder.ObjectModule"}
     *                  (lowercased before comparison; null/empty returns empty list)
     * @param minSeverity minimum severity to include (null = include all)
     * @param limit maximum results (must be &gt; 0)
     * @return list of matching markers, never null
     */
    public static List<MarkerInfo> getMarkersByObjectPresentation(IMarkerManager markerManager,
        IProject project, String objectFqn, MarkerSeverity minSeverity, int limit)
    {
        List<MarkerInfo> result = new ArrayList<>();
        if (markerManager == null || project == null
            || objectFqn == null || objectFqn.isEmpty() || limit <= 0)
        {
            return result;
        }

        String fqnLower = objectFqn.toLowerCase();

        markerManager.markers()
            .filter(marker -> {
                IProject markerProject = marker.getProject();
                if (markerProject == null || !markerProject.equals(project))
                {
                    return false;
                }

                if (minSeverity != null && !meetsMinimum(marker.getSeverity(), minSeverity))
                {
                    return false;
                }

                String presentation = marker.getObjectPresentation();
                if (presentation == null || presentation.isEmpty())
                {
                    return false;
                }
                return presentation.toLowerCase().contains(fqnLower);
            })
            .limit(limit)
            .forEach(marker -> result.add(toInfo(marker)));

        return result;
    }

    /**
     * Splits markers into errors / warnings / codeStyle by EDT severity.
     */
    public static Grouped groupBySeverity(List<MarkerInfo> markers)
    {
        Grouped g = new Grouped();
        if (markers == null)
        {
            return g;
        }
        for (MarkerInfo m : markers)
        {
            String sev = m.severity != null ? m.severity.toUpperCase() : ""; //$NON-NLS-1$
            switch (sev)
            {
                case "BLOCKER": //$NON-NLS-1$
                case "CRITICAL": //$NON-NLS-1$
                    g.errors.add(m);
                    break;
                case "MAJOR": //$NON-NLS-1$
                    g.warnings.add(m);
                    break;
                case "MINOR": //$NON-NLS-1$
                case "TRIVIAL": //$NON-NLS-1$
                    g.codeStyle.add(m);
                    break;
                default:
                    g.warnings.add(m);
                    break;
            }
        }
        return g;
    }

    private static MarkerInfo toInfo(Marker marker)
    {
        MarkerInfo info = new MarkerInfo();
        info.message = marker.getMessage() != null ? marker.getMessage() : ""; //$NON-NLS-1$
        info.severity = marker.getSeverity() != null ? marker.getSeverity().name() : ""; //$NON-NLS-1$
        info.checkId = marker.getCheckId() != null ? marker.getCheckId() : ""; //$NON-NLS-1$
        info.objectPresentation = marker.getObjectPresentation() != null
            ? marker.getObjectPresentation() : ""; //$NON-NLS-1$
        info.line = extractLine(marker);
        return info;
    }

    /**
     * Extracts a line number from a marker if EDT exposes one.
     * Tries reflection over common method names; returns -1 if none available.
     * EDT marker API does not declare {@code getLineNumber()} on the public
     * interface, but the underlying implementation often carries it.
     */
    private static int extractLine(Marker marker)
    {
        if (marker == null)
        {
            return -1;
        }
        String[] candidates = { "getLineNumber", "getLine", "getStartLine" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String name : candidates)
        {
            try
            {
                Object value = marker.getClass().getMethod(name).invoke(marker);
                if (value instanceof Number)
                {
                    int n = ((Number)value).intValue();
                    if (n >= 0)
                    {
                        return n;
                    }
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // try next candidate
            }
            catch (Exception ignored)
            {
                // reflection failure - silently skip; line is optional metadata
            }
        }
        return -1;
    }

    private static boolean meetsMinimum(MarkerSeverity actual, MarkerSeverity minimum)
    {
        if (actual == null || minimum == null)
        {
            return true;
        }
        // MarkerSeverity declared in order: BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL.
        // ordinal() = 0 for highest; lower ordinal = stricter.
        return actual.ordinal() <= minimum.ordinal();
    }
}
