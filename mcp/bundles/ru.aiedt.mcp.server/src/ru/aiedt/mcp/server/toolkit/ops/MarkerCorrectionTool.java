/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com.e1c.g5.v8.dt.check.qfix.FixProcessHandle;
import com.e1c.g5.v8.dt.check.qfix.FixVariantDescriptor;
import com.e1c.g5.v8.dt.check.qfix.IFixManager;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Applies EDT's own correction to a validation finding, instead of leaving a
 * caller to invent a patch for it.
 * <p>
 * When the project validator reports a problem, EDT usually knows how to fix it -
 * the same corrections the IDE offers on a marker. Without access to them an agent
 * reads the message, guesses at an edit and writes it by hand, which is how a
 * correct diagnosis turns into a wrong repair. This exposes the corrections
 * themselves: {@code list} says what EDT would do, {@code apply} does it.
 * <p>
 * Backed by {@code IFixManager} from {@code com.e1c.g5.v8.dt.check.qfix}, taken as
 * an OSGi service. The sequence is EDT's own: prepare a handle for the marker, read
 * the applicable variants, select one, execute, finish. A marker with no correction
 * is not an error - most have none - so an empty list is reported as such.
 */
public class MarkerCorrectionTool implements IMcpTool
{
    public static final String NAME = "marker_corrections"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "EDT's own corrections for a validation finding - what the IDE offers on a marker. " //$NON-NLS-1$
            + "operation=list reports the corrections available for the markers that match, " //$NON-NLS-1$
            + "operation=apply performs one of them. Target a finding with checkId (as reported by " //$NON-NLS-1$
            + "diagnostics get_project_errors) plus, when several match, messageContains. Use this " //$NON-NLS-1$
            + "instead of writing a repair by hand: the correction comes from the same check that " //$NON-NLS-1$
            + "raised the problem, so it fixes what was actually diagnosed."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Project holding the finding (required).", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("operation", "list (default) | apply") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("checkId", //$NON-NLS-1$
                "Check that raised the finding, as reported by get_project_errors. Required.", true) //$NON-NLS-1$
            .stringProperty("messageContains", //$NON-NLS-1$
                "Narrows the match when one check raised several findings - a substring of the " //$NON-NLS-1$
                    + "message or of the object presentation.") //$NON-NLS-1$
            .stringProperty("variant", //$NON-NLS-1$
                "apply: which correction to perform, by its description as reported by list. " //$NON-NLS-1$
                    + "Optional when the finding has exactly one.") //$NON-NLS-1$
            .booleanProperty("dryRun", //$NON-NLS-1$
                "apply: report what would be corrected without changing anything.")
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isEmpty())
        {
            operation = "list"; //$NON-NLS-1$
        }
        if (projectName == null || projectName.isEmpty() || checkId == null || checkId.isEmpty())
        {
            return ToolResult.error("projectName and checkId are required.").toJson(); //$NON-NLS-1$
        }
        if (!"list".equals(operation) && !"apply".equals(operation)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("Unknown operation '" + operation + "'. Valid: list, apply.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        Activator activator = Activator.getDefault();
        IFixManager fixManager = activator != null ? activator.getFixManager() : null;
        IMarkerManager markerManager = activator != null ? activator.getMarkerManager() : null;
        IDtProjectManager dtProjectManager = activator != null ? activator.getDtProjectManager() : null;
        if (fixManager == null || markerManager == null || dtProjectManager == null)
        {
            return ToolResult.error("EDT's correction service is not available on this runtime " //$NON-NLS-1$
                + "(com.e1c.g5.v8.dt.check.qfix).") //$NON-NLS-1$
                .put(ErrorTags.SERVICE_UNAVAILABLE.wire(), Boolean.TRUE)
                .toJson();
        }
        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null)
        {
            return ToolResult.error("'" + projectName + "' is not an EDT project.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String messageContains = JsonUtils.extractStringArgument(params, "messageContains"); //$NON-NLS-1$
        List<Marker> matched = findMarkers(markerManager, project, checkId, messageContains);
        if (matched.isEmpty())
        {
            return ToolResult.error("No finding of check '" + checkId + "' in '" + projectName //$NON-NLS-1$ //$NON-NLS-2$
                + "'" + (messageContains != null ? " matching '" + messageContains + "'" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + ". Run diagnostics revalidate_objects first - markers left from an earlier state " //$NON-NLS-1$
                + "are not re-raised on their own.") //$NON-NLS-1$
                .put(ErrorTags.NOT_FOUND.wire(), Boolean.TRUE)
                .toJson();
        }
        return "list".equals(operation) //$NON-NLS-1$
            ? doList(fixManager, dtProject, matched, checkId)
            : doApply(fixManager, dtProject, matched, params, checkId);
    }

    /**
     * Reports the corrections EDT offers for each matching finding.
     *
     * @param fixManager the correction service
     * @param dtProject  the project the findings belong to
     * @param markers    the findings that matched
     * @param checkId    the check asked about, for the reply
     * @return the JSON reply
     */
    private String doList(IFixManager fixManager, IDtProject dtProject, List<Marker> markers,
        String checkId)
    {
        List<Map<String, Object>> findings = new ArrayList<>();
        int withCorrections = 0;
        for (Marker marker : markers)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("message", marker.getMessage()); //$NON-NLS-1$
            row.put("object", marker.getObjectPresentation()); //$NON-NLS-1$
            List<String> variants = new ArrayList<>();
            FixProcessHandle handle = null;
            try
            {
                handle = fixManager.prepareFix(marker, dtProject);
                for (FixVariantDescriptor v : fixManager.getApplicableFixVariants(handle))
                {
                    variants.add(v.getDescription());
                }
            }
            catch (Exception e)
            {
                row.put("error", "Corrections could not be read: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            finally
            {
                finish(fixManager, handle);
            }
            row.put("corrections", variants); //$NON-NLS-1$
            if (!variants.isEmpty())
            {
                withCorrections++;
            }
            findings.add(row);
        }
        return ToolResult.success()
            .put("operation", "list") //$NON-NLS-1$ //$NON-NLS-2$
            .put("checkId", checkId) //$NON-NLS-1$
            .put("findings", findings) //$NON-NLS-1$
            .put("message", withCorrections == 0 //$NON-NLS-1$
                ? "None of the " + markers.size() + " findings has a correction - most checks " //$NON-NLS-1$ //$NON-NLS-2$
                    + "report without offering one, and this is not a failure." //$NON-NLS-1$
                : withCorrections + " of " + markers.size() + " findings offer a correction; pass " //$NON-NLS-1$ //$NON-NLS-2$
                    + "the description as variant to operation=apply. Not every correction is an " //$NON-NLS-1$
                    + "edit - some are EDT interface actions that only a person can carry out in " //$NON-NLS-1$
                    + "the editor, and apply says so rather than performing them.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Performs one correction. Refuses rather than guessing when the target or the
     * variant is ambiguous: a correction applied to the wrong finding is harder to
     * notice than one that never ran.
     *
     * @param fixManager the correction service
     * @param dtProject  the project the findings belong to
     * @param markers    the findings that matched
     * @param params     the call parameters
     * @param checkId    the check asked about, for the reply
     * @return the JSON reply
     */
    private String doApply(IFixManager fixManager, IDtProject dtProject, List<Marker> markers,
        Map<String, String> params, String checkId)
    {
        if (markers.size() > 1)
        {
            List<String> ambiguous = new ArrayList<>();
            for (Marker m : markers)
            {
                ambiguous.add(m.getObjectPresentation() + ": " + m.getMessage()); //$NON-NLS-1$
            }
            return ToolResult.error("Check '" + checkId + "' raised " + markers.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " findings - narrow it with messageContains before correcting one.") //$NON-NLS-1$
                .put("findings", ambiguous) //$NON-NLS-1$
                .toJson();
        }
        Marker marker = markers.get(0);
        String wanted = JsonUtils.extractStringArgument(params, "variant"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        FixProcessHandle handle = null;
        // Declared out here so the failure path can name which correction refused - the catch below
        // reports it, and a correction that cannot run is only useful information if it is named.
        FixVariantDescriptor chosen = null;
        try
        {
            handle = fixManager.prepareFix(marker, dtProject);
            List<FixVariantDescriptor> variants =
                new ArrayList<>(fixManager.getApplicableFixVariants(handle));
            if (variants.isEmpty())
            {
                return ToolResult.error("EDT offers no correction for this finding: " //$NON-NLS-1$
                    + marker.getMessage() + ". Most checks report without offering one.").toJson(); //$NON-NLS-1$
            }
            if (wanted == null || wanted.isEmpty())
            {
                if (variants.size() > 1)
                {
                    List<String> names = new ArrayList<>();
                    for (FixVariantDescriptor v : variants)
                    {
                        names.add(v.getDescription());
                    }
                    return ToolResult.error("This finding has " + variants.size() //$NON-NLS-1$
                        + " corrections - name one as variant.") //$NON-NLS-1$
                        .put("corrections", names) //$NON-NLS-1$
                        .toJson();
                }
                chosen = variants.get(0);
            }
            else
            {
                for (FixVariantDescriptor v : variants)
                {
                    if (wanted.equals(v.getDescription()))
                    {
                        chosen = v;
                        break;
                    }
                }
                if (chosen == null)
                {
                    List<String> names = new ArrayList<>();
                    for (FixVariantDescriptor v : variants)
                    {
                        names.add(v.getDescription());
                    }
                    return ToolResult.error("No correction named '" + wanted + "' for this finding.") //$NON-NLS-1$ //$NON-NLS-2$
                        .put("corrections", names) //$NON-NLS-1$
                        .toJson();
                }
            }
            if (dryRun)
            {
                return ToolResult.success()
                    .put("operation", "apply") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("dryRun", Boolean.TRUE) //$NON-NLS-1$
                    .put("wouldCorrect", marker.getMessage()) //$NON-NLS-1$
                    .put("object", marker.getObjectPresentation()) //$NON-NLS-1$
                    .put("correction", chosen.getDescription()) //$NON-NLS-1$
                    .toJson();
            }
            fixManager.selectFixVariant(chosen, handle);
            fixManager.executeFix(handle, new NullProgressMonitor());
            return ToolResult.success()
                .put("operation", "apply") //$NON-NLS-1$ //$NON-NLS-2$
                .put("checkId", checkId) //$NON-NLS-1$
                .put("object", marker.getObjectPresentation()) //$NON-NLS-1$
                .put("corrected", marker.getMessage()) //$NON-NLS-1$
                .put("correction", chosen.getDescription()) //$NON-NLS-1$
                .put("message", "Applied. The marker is cleared by the next validation, not by this " //$NON-NLS-1$ //$NON-NLS-2$
                    + "call - run diagnostics revalidate_objects to see the current state.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            if (needsTheEditor(e))
            {
                // Measured, not guessed: EDT offers corrections that are IDE ACTIONS rather than
                // edits - "open the documentation-comment panel" is one - and executing such a
                // variant off the display thread throws SWT's invalid-thread-access. Running it on
                // the display thread instead would not help: it would open a panel in the user's
                // editor and change nothing in the file, which is a worse answer than a refusal.
                return ToolResult.error("This correction is an EDT interface action, not an edit: " //$NON-NLS-1$
                    + "it opens something in the editor and has to be performed by a person there. " //$NON-NLS-1$
                    + "Nothing was changed, and the finding still stands.") //$NON-NLS-1$
                    .put("correction", chosen == null ? null : chosen.getDescription()) //$NON-NLS-1$
                    .put("object", marker.getObjectPresentation()) //$NON-NLS-1$
                    .put("refusedBy", e.getClass().getSimpleName() + ": " + e.getMessage()) //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
            return ToolResult.error("The correction failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            finish(fixManager, handle);
        }
    }

    /**
     * Tells apart a correction that needs the editor from one that simply failed.
     * <p>
     * Matched on the exception type first and only then on the message, because the message is
     * produced by the platform and a русская EDT would word it differently - a check written
     * against English words alone has failed here before.
     * </p>
     *
     * @param failure what the correction threw.
     * @return true when the failure means "this has to happen in the editor"
     */
    private static boolean needsTheEditor(Throwable failure)
    {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause())
        {
            String type = t.getClass().getName();
            if (type.startsWith("org.eclipse.swt.")) //$NON-NLS-1$
            {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.contains("Invalid thread access")) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the markers of one check in one project.
     *
     * @param markerManager   the marker service
     * @param project         the project to look in
     * @param checkId         the check id or short code, as reported
     * @param messageContains optional narrowing substring, may be {@code null}
     * @return the matching markers, never {@code null}
     */
    private static List<Marker> findMarkers(IMarkerManager markerManager, IProject project,
        String checkId, String messageContains)
    {
        Activator activator = Activator.getDefault();
        ICheckRepository checkRepository = activator != null ? activator.getCheckRepository() : null;
        List<Marker> found = new ArrayList<>();
        markerManager.markers()
            .filter(m -> project.equals(m.getProject()))
            .filter(m -> matchesCheck(m, checkId, checkRepository, project))
            .filter(m -> messageContains == null || messageContains.isEmpty()
                || contains(m.getMessage(), messageContains)
                || contains(m.getObjectPresentation(), messageContains))
            .forEach(found::add);
        return found;
    }

    /**
     * Tells whether a marker belongs to the check the caller named.
     * <p>
     * A marker carries the project-local short uid ({@code SU123}), while what
     * get_project_errors prints - and therefore what a caller passes here - is the
     * symbolic id ({@code common-module-type}). Comparing the two directly matches
     * nothing, so the marker's code is resolved through the check repository first.
     * The raw code is still accepted: it is what the report falls back to when the
     * symbolic id cannot be resolved, so a caller can legitimately hold either.
     *
     * @param marker          the marker to test
     * @param wanted          the check id or code the caller named
     * @param checkRepository the repository resolving short uids, may be {@code null}
     * @param project         the project the marker belongs to
     * @return true when the marker is of that check
     */
    private static boolean matchesCheck(Marker marker, String wanted,
        ICheckRepository checkRepository, IProject project)
    {
        String code = marker.getCheckId();
        if (code == null)
        {
            // A marker without a check code cannot be the one asked for, and testing
            // it further would end the whole request on a NullPointerException.
            return false;
        }
        if (wanted.equals(code))
        {
            return true;
        }
        if (checkRepository == null)
        {
            return false;
        }
        try
        {
            CheckUid uid = checkRepository.getUidForShortUid(code, project);
            return uid != null && wanted.equals(uid.getCheckId());
        }
        catch (Exception e)
        {
            // An unresolvable code is not a match, and not a reason to fail the request.
            return false;
        }
    }

    private static boolean contains(String haystack, String needle)
    {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    /**
     * Releases a correction handle. EDT keeps state per handle, so one left open
     * outlives the call that made it.
     *
     * @param fixManager the correction service
     * @param handle     the handle to release, may be {@code null}
     */
    private static void finish(IFixManager fixManager, FixProcessHandle handle)
    {
        if (handle == null)
        {
            return;
        }
        try
        {
            fixManager.finishFix(handle);
        }
        catch (Exception e)
        {
            Activator.logWarning("Correction handle not released: " + e.getMessage()); //$NON-NLS-1$
        }
    }
}
