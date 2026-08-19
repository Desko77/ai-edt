/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.TimeoutArgs;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmExportHelper;
import ru.aiedt.mcp.server.support.BmExternalObjectDumpHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Tool to build an external data processor / report DT project into a binary
 * .epf / .erf file.
 * <p>
 * Uses EDT's {@code IExternalObjectDumper} (package
 * {@code com._1c.g5.v8.dt.platform.services.core.dump}) via
 * {@link BmExternalObjectDumpHelper}. The dumper exports the object to a
 * temporary XML directory and launches a 1C:Enterprise thick client to convert
 * it to the binary, so it needs a resolvable platform runtime (and an infobase
 * only when the external project is linked to a base configuration). Failures
 * (no runtime, no infobase, model errors) are surfaced as actionable errors
 * with typed tags; the EDT GUI export remains a manual fallback.
 * <p>
 * The heavy work runs behind the {@link PendingWorkRegistry} soft-timeout /
 * runKey mechanism so a slow build returns a resumable Pending response.
 */
public class ExportObjectTool implements IMcpTool
{
    public static final String NAME = "export_object"; //$NON-NLS-1$

    /** 1.41: clamp range for timeoutSeconds on the Pending mechanism. */
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `config_io` `operation=export_object`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Build an external data processor / report DT project into a binary " //$NON-NLS-1$
            + ".epf or .erf file, ready to open in 1C:Enterprise. Pass projectName " //$NON-NLS-1$
            + "(the external-object project created via external_object_workshop) and " //$NON-NLS-1$
            + "outputPath; objectName is optional when the project has one root object. " //$NON-NLS-1$
            + "Requires a resolvable 1C:Enterprise platform runtime (a thick client " //$NON-NLS-1$
            + "performs the build); an infobase is needed only for projects linked to a " //$NON-NLS-1$
            + "base configuration. A slow build returns a Pending response with a runKey " //$NON-NLS-1$
            + "to resume."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("outputPath", //$NON-NLS-1$
                "Absolute path to the output .epf / .erf file (required). " //$NON-NLS-1$
                    + "Extension determines the kind: .epf for ExternalDataProcessor, " //$NON-NLS-1$
                    + ".erf for ExternalReport. If omitted, the kind is auto-detected " //$NON-NLS-1$
                    + "from the project nature and the proper extension is appended.", true) //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "Object name within the project (optional). Required only when the " //$NON-NLS-1$
                    + "project contains more than one external object.") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", //$NON-NLS-1$
                "Soft timeout in seconds before returning a Pending JSON " //$NON-NLS-1$
                    + "with a runKey. Default: 30. Range: 5-120 (clamped). Calling " //$NON-NLS-1$
                    + "again with the same params resumes waiting.") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "Resume polling a previously-issued export. Pass the runKey " //$NON-NLS-1$
                    + "returned by an earlier Pending response; other params are " //$NON-NLS-1$
                    + "ignored. Returns the result if ready, or another Pending JSON.") //$NON-NLS-1$
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
        // 1.41: explicit retry mode
        String runKeyParam = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        if (runKeyParam != null && !runKeyParam.isEmpty())
        {
            return resumePending(runKeyParam, params);
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String outputPathRaw = JsonUtils.extractStringArgument(params, "outputPath"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (outputPathRaw == null || outputPathRaw.isEmpty())
        {
            return ToolResult.error(
                "outputPath is required. Specify an absolute path with .epf or .erf extension, " //$NON-NLS-1$
                    + "e.g. outputPath=\"C:/build/MyReport.erf\".").toJson(); //$NON-NLS-1$
        }

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        // 1.41: detect kind from extension or project nature, normalize outputPath
        KindResolution kind = resolveKind(project, outputPathRaw);
        if (kind.error != null)
        {
            return ToolResult.error(kind.error)
                .put("operation", NAME)
                .put(ErrorTags.KIND_MISMATCH.wire(), kind.diagnosticTag())
                .toJson();
        }
        final String outputPath = kind.normalizedPath;
        final String detectedKind = kind.kindLabel;

        // runKey seed only: an external object project usually holds a single
        // object named like the project, so the bare project name is a stable
        // key seed when objectName is omitted. The real object is resolved inside
        // doExport (auto-picking the single object when objectName is null), so
        // the resolved name may differ from this seed - that only affects the key.
        final String runKeySeed = (objectName == null || objectName.isEmpty())
            ? project.getName() : objectName;

        // Pending registry dispatch — runKey from normalized path so retries
        // with the bare path produce the same key as retries with the
        // extension that the first call appended.
        String runKey = PendingWorkRegistry.computeRunKey(projectName,
            runKeySeed, outputPath);
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;

        PendingWorkRegistry registry = PendingWorkRegistry.EXPORT;
        registry.pruneExpired();

        // Pass the RAW objectName (may be null) so doExport's resolveRoot can
        // auto-pick the single object regardless of the project name.
        PendingWorkRegistry.PendingEntry entry = registry.getOrStart(runKey,
            () -> doExport(project, projectName, objectName, outputPath));

        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return buildPendingJson(runKey, entry, projectName, outputPath, detectedKind, timeoutMs);
    }

    /**
     * Synchronous export work (runs inside the registry executor): resolves the
     * root external object, syncs BM state to disk, resolves the dumper, and runs
     * the build via {@link BmExternalObjectDumpHelper}.
     */
    private String doExport(IProject project, String projectName, String objectName,
        String outputPath)
    {
        long start = System.currentTimeMillis();

        // Resolve the exact root MdObject the dumper validates by containment.
        // When objectName is omitted this auto-picks the project's single
        // external object (and reports ambiguity when there are several); the
        // resolved name may therefore differ from the project name.
        BmExternalObjectDumpHelper.RootResolution root =
            BmExternalObjectDumpHelper.resolveRoot(project, objectName);
        if (root.error != null)
        {
            return ToolResult.error(root.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put(root.notExternalProject ? "notExternalProject" //$NON-NLS-1$
                    : "objectResolutionFailed", Boolean.TRUE) //$NON-NLS-1$
                .toJson();
        }

        // Authoritative extension from the real object EClass - if the
        // nature-derived path disagrees, surface kindMismatch (do not silently
        // write .epf when .erf was requested, or vice versa).
        String objExt = BmExternalObjectDumpHelper.extensionForEClass(root.eClassName);
        if (objExt != null && !outputPath.toLowerCase().endsWith(objExt))
        {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("objectEClass", root.eClassName); //$NON-NLS-1$
            tag.put("expectedExtension", objExt); //$NON-NLS-1$
            tag.put("outputPath", outputPath); //$NON-NLS-1$
            return ToolResult.error("kindMismatch: object '" + root.objectName //$NON-NLS-1$
                + "' is an " + root.eClassName + " (builds " + objExt //$NON-NLS-1$ //$NON-NLS-2$
                + "), but outputPath ends with a different extension. Use " //$NON-NLS-1$
                + objExt + ".") //$NON-NLS-1$
                .put("operation", NAME) //$NON-NLS-1$
                .put(ErrorTags.KIND_MISMATCH.wire(), tag)
                .toJson();
        }

        // Sync BM state to disk before the build so freshly edited BSL / template
        // content is reflected. Best-effort: a failure is logged and surfaced as a
        // soft warning rather than silently building stale content.
        String preSyncWarning = null;
        Activator activator = Activator.getDefault();
        IBmModelManager bmManager = activator != null ? activator.getBmModelManager() : null;
        if (bmManager != null)
        {
            String fqn = inferFqnForKind(root.eClassName, root.objectName);
            if (fqn != null)
            {
                BmExportHelper.Result sync = BmExportHelper.forceExportAndWait(
                    bmManager, project, Collections.singletonList(fqn), 5_000L);
                if (sync != null && sync.syncFlushPending)
                {
                    // Row 42: BM committed but the on-disk flush did not confirm
                    // within the budget - the build reads on-disk sources, so it
                    // may reflect stale content.
                    preSyncWarning = "pre-build BM sync did not confirm the on-disk flush in " //$NON-NLS-1$
                        + "time; the built file may reflect the last saved state. Re-run once " //$NON-NLS-1$
                        + "EDT settles if the output looks stale."; //$NON-NLS-1$
                    Activator.logWarning("ExportObjectTool: " + preSyncWarning); //$NON-NLS-1$
                }
                else if (sync != null && !sync.isOk())
                {
                    preSyncWarning = "pre-build BM sync did not complete (" //$NON-NLS-1$
                        + (sync.error != null ? sync.error : "timeout") //$NON-NLS-1$
                        + "); the built file may reflect the last saved state."; //$NON-NLS-1$
                    Activator.logWarning("ExportObjectTool: " + preSyncWarning); //$NON-NLS-1$
                }
            }
        }

        Object dumper = BmExternalObjectDumpHelper.resolveDumper();
        if (dumper == null)
        {
            return buildDumperUnavailableError(projectName, outputPath);
        }

        BmExternalObjectDumpHelper.DumpInvocation inv =
            BmExternalObjectDumpHelper.dump(dumper, project, root.object, outputPath);
        long elapsed = System.currentTimeMillis() - start;
        if (!inv.ok)
        {
            return ToolResult.error(inv.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("objectName", root.objectName) //$NON-NLS-1$
                .put("outputPath", outputPath) //$NON-NLS-1$
                .put("elapsedMs", elapsed) //$NON-NLS-1$
                .put(inv.failureKind != null ? inv.failureKind : ErrorTags.DUMP_FAILED.wire(),
                    Boolean.TRUE)
                .toJson();
        }

        File out = new File(outputPath);
        if (!out.isFile())
        {
            return ToolResult.error("The dumper reported success but no file was " //$NON-NLS-1$
                + "written at " + outputPath + ". The build may have been redirected " //$NON-NLS-1$ //$NON-NLS-2$
                + "or blocked.") //$NON-NLS-1$
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("outputPath", outputPath) //$NON-NLS-1$
                .put(ErrorTags.OUTPUT_MISSING.wire(), Boolean.TRUE)
                .toJson();
        }
        Activator.logInfo("ExportObjectTool: built " + root.eClassName + " '" //$NON-NLS-1$ //$NON-NLS-2$
            + root.objectName + "' -> " + outputPath + " (" + out.length() //$NON-NLS-1$ //$NON-NLS-2$
            + " bytes, " + elapsed + " ms)"); //$NON-NLS-1$ //$NON-NLS-2$
        ToolResult success = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("objectName", root.objectName) //$NON-NLS-1$
            .put("kind", root.eClassName) //$NON-NLS-1$
            .put("outputPath", outputPath) //$NON-NLS-1$
            .put("sizeBytes", out.length()) //$NON-NLS-1$
            .put("elapsedMs", elapsed); //$NON-NLS-1$
        if (preSyncWarning != null)
        {
            success.put("preSyncWarning", preSyncWarning); //$NON-NLS-1$
        }
        return success.toJson();
    }

    /** Error when the Guice-bound dumper cannot be resolved (rare on 2026.1). */
    private String buildDumperUnavailableError(String projectName, String outputPath)
    {
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("dumperFqn", //$NON-NLS-1$
            "com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumper"); //$NON-NLS-1$
        tag.put("hint", //$NON-NLS-1$
            "The platform-services external-object dumper could not be resolved from " //$NON-NLS-1$
                + "its Guice injector on this EDT runtime. Fallback: open the project " //$NON-NLS-1$
                + "in EDT and export the object via the Navigator context menu."); //$NON-NLS-1$
        return ToolResult.error(
            "The external-object dumper (IExternalObjectDumper) is not available on " //$NON-NLS-1$
                + "this EDT runtime. Use the EDT GUI to export the object as a fallback.") //$NON-NLS-1$
            .put("operation", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("outputPath", outputPath) //$NON-NLS-1$
            .put("dumpApiUnavailable", tag) //$NON-NLS-1$
            .toJson();
    }

    /**
     * 1.41: poll a previously-issued runKey. Returns the cached result (and
     * removes the entry) or a fresh Pending JSON.
     */
    private String resumePending(String runKey, Map<String, String> params)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.EXPORT;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return ToolResult.error("runKey not found - the export either completed " //$NON-NLS-1$
                + "and was already retrieved, or was abandoned and evicted by TTL. " //$NON-NLS-1$
                + "Issue a new request without runKey to start over.") //$NON-NLS-1$
                .put("operation", NAME) //$NON-NLS-1$
                .put("runKey", runKey) //$NON-NLS-1$
                .toJson();
        }
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;
        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return buildPendingJson(runKey, entry, null, null, null, timeoutMs);
    }


    private String buildPendingJson(String runKey, PendingWorkRegistry.PendingEntry entry,
        String projectName, String outputPath, String detectedKind, long timeoutMs)
    {
        ToolResult tr = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put(ru.aiedt.mcp.server.support.PendingEnvelope.MARK, true)
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", timeoutMs) //$NON-NLS-1$
            .put("hint", "Export still running. Call this tool again with runKey=\"" //$NON-NLS-1$ //$NON-NLS-2$
                + runKey + "\" to resume waiting (or with the same params - they " //$NON-NLS-1$
                + "produce the same runKey)."); //$NON-NLS-1$
        if (projectName != null)
        {
            tr.put("projectName", projectName); //$NON-NLS-1$
        }
        if (outputPath != null)
        {
            tr.put("outputPath", outputPath); //$NON-NLS-1$
        }
        if (detectedKind != null)
        {
            tr.put("detectedKind", detectedKind); //$NON-NLS-1$
        }
        return tr.toJson();
    }

    /**
     * 1.41: holder for kind/extension resolution result.
     */
    private static final class KindResolution
    {
        String kindLabel;        // "ExternalDataProcessor" / "ExternalReport"
        String normalizedPath;   // outputPath with proper extension
        String error;
        String requestedExtension;
        String detectedFromNature;

        Map<String, Object> diagnosticTag()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            if (requestedExtension != null)
            {
                m.put("requestedExtension", requestedExtension); //$NON-NLS-1$
            }
            if (detectedFromNature != null)
            {
                m.put("detectedFromNature", detectedFromNature); //$NON-NLS-1$
            }
            if (kindLabel != null)
            {
                m.put("expectedExtension", kindLabel.equals("ExternalReport") //$NON-NLS-1$ //$NON-NLS-2$
                    ? ".erf" : ".epf"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return m;
        }
    }

    /**
     * 1.41: resolve the export kind from the requested {@code outputPath}
     * extension (.epf or .erf) or, when missing, from the project nature.
     * Validates that the requested extension matches the detected kind.
     */
    private KindResolution resolveKind(IProject project, String outputPathRaw)
    {
        KindResolution kr = new KindResolution();
        String lower = outputPathRaw.toLowerCase();
        String detectedFromNature = detectKindFromNatures(project);
        kr.detectedFromNature = detectedFromNature;

        boolean hasEpf = lower.endsWith(".epf"); //$NON-NLS-1$
        boolean hasErf = lower.endsWith(".erf"); //$NON-NLS-1$

        if (hasEpf || hasErf)
        {
            kr.requestedExtension = hasEpf ? ".epf" : ".erf"; //$NON-NLS-1$ //$NON-NLS-2$
            kr.kindLabel = hasEpf ? "ExternalDataProcessor" : "ExternalReport"; //$NON-NLS-1$ //$NON-NLS-2$
            kr.normalizedPath = outputPathRaw;
            // If we detected a project nature, validate it matches.
            if (detectedFromNature != null && !detectedFromNature.equals(kr.kindLabel))
            {
                kr.error = "kindMismatch: outputPath has extension " //$NON-NLS-1$
                    + kr.requestedExtension + " but project '" //$NON-NLS-1$
                    + project.getName() + "' looks like " //$NON-NLS-1$
                    + detectedFromNature + " (use ." //$NON-NLS-1$
                    + (detectedFromNature.equals("ExternalReport") ? "erf" : "epf") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " instead)"; //$NON-NLS-1$
            }
            return kr;
        }

        // 1.41: refuse paths whose filename already carries a non-.epf/.erf
        // extension, otherwise the auto-append below would silently produce
        // garbage like "y.txt.epf".
        int slash = Math.max(outputPathRaw.lastIndexOf('/'), outputPathRaw.lastIndexOf('\\'));
        String fileNamePart = slash >= 0 ? outputPathRaw.substring(slash + 1) : outputPathRaw;
        int dot = fileNamePart.lastIndexOf('.');
        if (dot > 0 && dot < fileNamePart.length() - 1)
        {
            String unknownExt = fileNamePart.substring(dot);
            kr.requestedExtension = unknownExt;
            kr.error = "Unsupported outputPath extension '" + unknownExt //$NON-NLS-1$
                + "'. Use .epf for ExternalDataProcessor or .erf for ExternalReport, " //$NON-NLS-1$
                + "or a path without an extension (auto-appended from project nature)."; //$NON-NLS-1$
            return kr;
        }

        // No extension: use detected kind to append it.
        if (detectedFromNature != null)
        {
            kr.kindLabel = detectedFromNature;
            kr.normalizedPath = outputPathRaw
                + (detectedFromNature.equals("ExternalReport") ? ".erf" : ".epf"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return kr;
        }

        kr.error = "Cannot determine export kind: outputPath has no .epf/.erf extension " //$NON-NLS-1$
            + "and the project nature did not reveal an ExternalDataProcessor / " //$NON-NLS-1$
            + "ExternalReport. Pass an outputPath with the proper extension."; //$NON-NLS-1$
        return kr;
    }

    /**
     * Probe project natures for {@code ExternalDataProcessor} or
     * {@code ExternalReport}. Returns the kind label or {@code null} when
     * the project is a regular configuration / extension / unknown.
     */
    private String detectKindFromNatures(IProject project)
    {
        try
        {
            String[] natures = project.getDescription().getNatureIds();
            for (String n : natures)
            {
                String lc = n.toLowerCase();
                // Order matters: test the longer/more specific substring first
                // to avoid false positives from a nature ID that happens to
                // contain "externalreport" as a sub-substring.
                if (lc.contains("externaldataprocessor")) //$NON-NLS-1$
                {
                    return "ExternalDataProcessor"; //$NON-NLS-1$
                }
                if (lc.contains("externalreport")) //$NON-NLS-1$
                {
                    return "ExternalReport"; //$NON-NLS-1$
                }
            }
        }
        catch (Exception ignored)
        {
            // project closed or in transient state - fall through to null
        }
        return null;
    }

    /**
     * 1.41: best-effort FQN for {@link BmExportHelper#forceExportAndWait}.
     * External processors / reports use kind-prefixed FQNs in EDT BM
     * (e.g. {@code ExternalDataProcessor.MyTool}).
     */
    private String inferFqnForKind(String kindLabel, String objectName)
    {
        if (kindLabel == null || objectName == null || objectName.isEmpty())
        {
            return null;
        }
        return kindLabel + "." + objectName; //$NON-NLS-1$
    }

}
