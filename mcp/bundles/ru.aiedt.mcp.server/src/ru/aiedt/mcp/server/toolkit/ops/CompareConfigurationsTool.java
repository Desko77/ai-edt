/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MetadataDiffEngine;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Compares two metadata configurations on different levels:
 * {@code object | attribute | form | module | template}.
 * <p>
 * <b>1.38 modes:</b> {@code projects} (two open EDT projects) or
 * {@code files} (two on-disk exports). VCS-aware modes (commits / branches /
 * bm_vs_disk) are deferred to 1.39 (cross-review HIGH F3+F17 — public
 * {@code IBmModel.reload()} not available).
 */
public class CompareConfigurationsTool implements IMcpTool
{
    public static final String NAME = "compare_configurations"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `insights` `operation=compare_configurations`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Diff two metadata configurations at object, attribute, module, or template level. " //$NON-NLS-1$
            + "Modes: projects (compare two open EDT projects) or files (compare two on-disk exports)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "First project name (required for both modes)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("mode", "projects | files (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("target", //$NON-NLS-1$
                "For projects: name of second project. For files: path to second export.", //$NON-NLS-1$
                true)
            .stringProperty("level", "object | attribute | module | template (default object)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", "project | objectType | objectFqn (default project)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectFqn", "Object FQN when scope=objectFqn") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("format", "json | markdown (default json)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("showRenames", "Detect renames via structural similarity (default true)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String mode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        if (mode == null || mode.isEmpty())
        {
            return ToolResult.error("mode is required (projects | files)").toJson(); //$NON-NLS-1$
        }
        // VCS-aware modes deferred to 1.39
        if ("commits".equalsIgnoreCase(mode) || "branches".equalsIgnoreCase(mode) //$NON-NLS-1$ //$NON-NLS-2$
            || "bm_vs_disk".equalsIgnoreCase(mode)) //$NON-NLS-1$
        {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("requestedMode", mode); //$NON-NLS-1$
            tag.put("hint", //$NON-NLS-1$
                "VCS-aware compare modes (commits / branches / bm_vs_disk) require git " //$NON-NLS-1$
                    + "shadow-clone + BmVsDiskDiffer; planned for 1.39 Phase F."); //$NON-NLS-1$
            return ToolResult.error("VCS-aware compare mode '" + mode + "' deferred to 1.39") //$NON-NLS-1$ //$NON-NLS-2$
                .put("vcsCompareDeferredTo139", tag) //$NON-NLS-1$
                .toJson();
        }
        if (!"projects".equalsIgnoreCase(mode) && !"files".equalsIgnoreCase(mode)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("mode must be projects | files (1.38)").toJson(); //$NON-NLS-1$
        }

        String level = orDefault(JsonUtils.extractStringArgument(params, "level"), "object"); //$NON-NLS-1$ //$NON-NLS-2$
        String format = orDefault(JsonUtils.extractStringArgument(params, "format"), "json"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean showRenames = JsonUtils.extractBooleanArgument(params, "showRenames", true); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String target = JsonUtils.extractStringArgument(params, "target"); //$NON-NLS-1$
        if (projectName == null || target == null)
        {
            return ToolResult.error("projectName and target are required").toJson(); //$NON-NLS-1$
        }

        try
        {
            return UiSync.call(() -> {
                if ("projects".equalsIgnoreCase(mode)) //$NON-NLS-1$
                {
                    return compareProjects(projectName, target, level, format, showRenames, params);
                }
                return compareFiles(projectName, target, level, format, params);
            });
        }
        catch (Exception e)
        {
            Activator.logError("compare_configurations error", e); //$NON-NLS-1$
            return ToolResult.error(TextSuggest.safeMessage(e)).toJson();
        }
    }

    private String compareProjects(String projectName, String targetProjectName, String level,
        String format, boolean showRenames, Map<String, String> params)
    {
        IProject p1 = ProjectResolver.resolve(projectName);
        IProject p2 = ProjectResolver.resolve(targetProjectName);
        if (p1 == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        if (p2 == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(targetProjectName)).toJson();
        }
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        if (provider == null)
        {
            return ToolResult.error("configuration provider is not published as a service").toJson(); //$NON-NLS-1$
        }
        Configuration c1 = provider.getConfiguration(p1);
        Configuration c2 = provider.getConfiguration(p2);
        if (c1 == null || c2 == null)
        {
            return ToolResult.error("Could not load both configurations").toJson(); //$NON-NLS-1$
        }
        if ("object".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            MetadataDiffEngine.DiffResult diff = MetadataDiffEngine.diffObjects(c1, c2,
                showRenames);
            return formatResult(level, format, diff.toMap());
        }
        if ("attribute".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
            if (objectFqn == null || objectFqn.isEmpty())
            {
                return ToolResult.error("level=attribute requires objectFqn").toJson(); //$NON-NLS-1$
            }
            String[] parts = MetadataTypeCatalog.normalizeFqn(objectFqn).split("\\.", 2); //$NON-NLS-1$
            if (parts.length < 2)
            {
                return ToolResult.error("objectFqn must be 'Type.Name'").toJson(); //$NON-NLS-1$
            }
            MdObject a = MetadataTypeCatalog.findObject(c1, parts[0], parts[1]);
            MdObject b = MetadataTypeCatalog.findObject(c2, parts[0], parts[1]);
            if (a == null || b == null)
            {
                return ToolResult.error("Object not found in one of the projects: " + objectFqn) //$NON-NLS-1$
                    .toJson();
            }
            MetadataDiffEngine.DiffResult diff = MetadataDiffEngine.diffAttributes(a, b);
            Map<String, Object> diffMap = diff.toMap();
            diffMap.put("objectFqn", objectFqn); //$NON-NLS-1$
            return formatResult(level, format, diffMap);
        }
        if ("module".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            return compareModulesByFiles(p1, p2, format, params);
        }
        if ("template".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            return compareTemplatesByFiles(p1, p2, format);
        }
        return ToolResult.error("Unsupported level: " + level + " (object|attribute|module|template)") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    private String compareModulesByFiles(IProject p1, IProject p2, String format,
        Map<String, String> params) throws IllegalStateException
    {
        Map<String, IFile> a = collectModuleFiles(p1);
        Map<String, IFile> b = collectModuleFiles(p2);
        return formatModuleDiff(a, b, format);
    }

    private String compareTemplatesByFiles(IProject p1, IProject p2, String format)
    {
        Map<String, IFile> a = collectTemplateFiles(p1);
        Map<String, IFile> b = collectTemplateFiles(p2);
        Map<String, Object> diff = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        java.util.Set<String> all = new java.util.TreeSet<>();
        all.addAll(a.keySet());
        all.addAll(b.keySet());
        for (String key : all)
        {
            IFile fa = a.get(key);
            IFile fb = b.get(key);
            if (fa == null && fb != null)
            {
                added.add(key);
            }
            else if (fa != null && fb == null)
            {
                removed.add(key);
            }
            else if (fa != null && fb != null)
            {
                if (!filesByteEqual(fa, fb))
                {
                    modified.add(key);
                }
            }
        }
        diff.put("added", added); //$NON-NLS-1$
        diff.put("removed", removed); //$NON-NLS-1$
        diff.put("modified", modified); //$NON-NLS-1$
        diff.put("addedCount", added.size()); //$NON-NLS-1$
        diff.put("removedCount", removed.size()); //$NON-NLS-1$
        diff.put("modifiedCount", modified.size()); //$NON-NLS-1$
        return formatResult("template", format, diff); //$NON-NLS-1$
    }

    private boolean filesByteEqual(IFile a, IFile b)
    {
        try (java.io.InputStream as = a.getContents();
             java.io.InputStream bs = b.getContents())
        {
            int ax;
            while ((ax = as.read()) != -1)
            {
                int bx = bs.read();
                if (ax != bx)
                {
                    return false;
                }
            }
            return bs.read() == -1;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private Map<String, IFile> collectModuleFiles(IProject project)
    {
        Map<String, IFile> map = new LinkedHashMap<>();
        try
        {
            project.accept(resource -> {
                if (resource instanceof IFile && resource.getName().endsWith(".bsl")) //$NON-NLS-1$
                {
                    String key = resource.getProjectRelativePath().toString();
                    map.put(key, (IFile) resource);
                }
                return true;
            });
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        return map;
    }

    private Map<String, IFile> collectTemplateFiles(IProject project)
    {
        Map<String, IFile> map = new LinkedHashMap<>();
        try
        {
            project.accept(resource -> {
                if (resource instanceof IFile)
                {
                    String name = resource.getName();
                    if (name.endsWith(".mxl") || name.endsWith(".dcs") || name.endsWith(".epf")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    {
                        map.put(resource.getProjectRelativePath().toString(), (IFile) resource);
                    }
                }
                return true;
            });
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        return map;
    }

    private String formatModuleDiff(Map<String, IFile> a, Map<String, IFile> b, String format)
    {
        java.util.Set<String> all = new java.util.TreeSet<>();
        all.addAll(a.keySet());
        all.addAll(b.keySet());
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<Map<String, Object>> modified = new ArrayList<>();
        for (String key : all)
        {
            IFile fa = a.get(key);
            IFile fb = b.get(key);
            if (fa == null && fb != null)
            {
                added.add(key);
            }
            else if (fa != null && fb == null)
            {
                removed.add(key);
            }
            else if (fa != null && fb != null)
            {
                String contentA = readText(fa);
                String contentB = readText(fb);
                if (contentA == null || contentB == null)
                {
                    continue;
                }
                if (!contentA.equals(contentB))
                {
                    Map<String, Object> mod = new LinkedHashMap<>();
                    mod.put("file", key); //$NON-NLS-1$
                    mod.put("aLines", contentA.split("\\r?\\n").length); //$NON-NLS-1$ //$NON-NLS-2$
                    mod.put("bLines", contentB.split("\\r?\\n").length); //$NON-NLS-1$ //$NON-NLS-2$
                    mod.put("preview", buildLineDiffPreview(contentA, contentB)); //$NON-NLS-1$
                    modified.add(mod);
                }
            }
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("added", added); //$NON-NLS-1$
        diff.put("removed", removed); //$NON-NLS-1$
        diff.put("modified", modified); //$NON-NLS-1$
        diff.put("addedCount", added.size()); //$NON-NLS-1$
        diff.put("removedCount", removed.size()); //$NON-NLS-1$
        diff.put("modifiedCount", modified.size()); //$NON-NLS-1$
        return formatResult("module", format, diff); //$NON-NLS-1$
    }

    private static String readText(IFile file)
    {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
        {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Returns first ~10 differing lines between two BSL texts as a structural
     * preview (not full unified diff).
     */
    private static List<String> buildLineDiffPreview(String a, String b)
    {
        String[] al = a.split("\\r?\\n"); //$NON-NLS-1$
        String[] bl = b.split("\\r?\\n"); //$NON-NLS-1$
        List<String> preview = new ArrayList<>();
        int max = Math.min(al.length, bl.length);
        for (int i = 0; i < max && preview.size() < 10; i++)
        {
            if (!al[i].equals(bl[i]))
            {
                preview.add("- " + al[i]); //$NON-NLS-1$
                preview.add("+ " + bl[i]); //$NON-NLS-1$
            }
        }
        if (al.length != bl.length && preview.size() < 10)
        {
            preview.add("# line count differs: A=" + al.length + " B=" + bl.length); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return preview;
    }

    private String compareFiles(String firstPath, String secondPath, String level, String format,
        Map<String, String> params)
    {
        Path p1 = Paths.get(firstPath);
        Path p2 = Paths.get(secondPath);
        if (!Files.exists(p1))
        {
            return ToolResult.error("First file not found: " + p1).toJson(); //$NON-NLS-1$
        }
        if (!Files.exists(p2))
        {
            return ToolResult.error("Second file not found: " + p2).toJson(); //$NON-NLS-1$
        }
        try
        {
            byte[] a = Files.readAllBytes(p1);
            byte[] b = Files.readAllBytes(p2);
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("firstSize", a.length); //$NON-NLS-1$
            diff.put("secondSize", b.length); //$NON-NLS-1$
            diff.put("identical", java.util.Arrays.equals(a, b)); //$NON-NLS-1$
            // For text level, also produce a preview diff
            if (firstPath.endsWith(".bsl") || firstPath.endsWith(".xml") //$NON-NLS-1$ //$NON-NLS-2$
                || firstPath.endsWith(".mdo") || firstPath.endsWith(".form")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String aStr = new String(a, StandardCharsets.UTF_8);
                String bStr = new String(b, StandardCharsets.UTF_8);
                if (!aStr.equals(bStr))
                {
                    diff.put("preview", buildLineDiffPreview(aStr, bStr)); //$NON-NLS-1$
                }
            }
            return formatResult(level, format, diff);
        }
        catch (Exception e)
        {
            return ToolResult.error("Failed to compare files: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private String formatResult(String level, String format, Map<String, Object> diff)
    {
        if ("markdown".equalsIgnoreCase(format)) //$NON-NLS-1$
        {
            return ToolResult.success()
                .put("level", level) //$NON-NLS-1$
                .put("format", "markdown") //$NON-NLS-1$ //$NON-NLS-2$
                .put("text", renderMarkdown(level, diff)) //$NON-NLS-1$
                .toJson();
        }
        ToolResult tr = ToolResult.success().put("level", level); //$NON-NLS-1$
        for (Map.Entry<String, Object> entry : diff.entrySet())
        {
            tr.put(entry.getKey(), entry.getValue());
        }
        return tr.toJson();
    }

    private static String renderMarkdown(String level, Map<String, Object> diff)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# Compare configurations - level=").append(level).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, Object> entry : diff.entrySet())
        {
            Object value = entry.getValue();
            if (value instanceof java.util.List)
            {
                java.util.List<?> list = (java.util.List<?>) value;
                sb.append("## ").append(entry.getKey()).append(" (").append(list.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                for (Object item : list)
                {
                    sb.append("- ").append(item).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                sb.append("\n"); //$NON-NLS-1$
            }
            else
            {
                sb.append("**").append(entry.getKey()).append(":** ").append(value).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        return sb.toString();
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }
}
