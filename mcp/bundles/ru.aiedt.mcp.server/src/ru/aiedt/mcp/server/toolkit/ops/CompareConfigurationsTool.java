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
import ru.aiedt.mcp.server.support.BmComparisonHelper;
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
 * {@code files} (two on-disk exports - single files or whole export
 * directories). VCS-aware modes (commits / branches / bm_vs_disk) are
 * deferred to 1.39: public {@code IBmModel.reload()} is not available.
 * <p>
 * Only the model levels (object, attribute of mode=projects) run under
 * {@code UiSync}; the file levels and the files mode read files and walk
 * directories on the calling thread.
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
            .stringProperty("projectName", //$NON-NLS-1$
                "mode=projects: first project name. mode=files: path to the first export " //$NON-NLS-1$
                    + "(a file, or the directory of an export)", true) //$NON-NLS-1$
            .stringProperty("mode", "projects | files (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("target", //$NON-NLS-1$
                "For projects: name of second project. For files: path to the second export " //$NON-NLS-1$
                    + "(a file, or the directory of an export)", true) //$NON-NLS-1$
            .stringProperty("level", "object | attribute | module | template (default object; " //$NON-NLS-1$ //$NON-NLS-2$
                + "attribute requires mode=projects)") //$NON-NLS-1$
            .stringProperty("scope", "project | objectFqn (default project). objectFqn narrows " //$NON-NLS-1$ //$NON-NLS-2$
                + "the comparison to one object.") //$NON-NLS-1$
            .stringProperty("objectFqn", "Object FQN when scope=objectFqn, or with " //$NON-NLS-1$ //$NON-NLS-2$
                + "level=attribute in mode=projects") //$NON-NLS-1$
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
        boolean projects = "projects".equalsIgnoreCase(mode); //$NON-NLS-1$

        String level = orDefault(JsonUtils.extractStringArgument(params, "level"), "object"); //$NON-NLS-1$ //$NON-NLS-2$
        String format = orDefault(JsonUtils.extractStringArgument(params, "format"), "json"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean showRenames = JsonUtils.extractBooleanArgument(params, "showRenames", true); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String target = JsonUtils.extractStringArgument(params, "target"); //$NON-NLS-1$
        if (projectName == null || target == null)
        {
            return ToolResult.error("projectName and target are required").toJson(); //$NON-NLS-1$
        }
        if (!isKnownLevel(level))
        {
            return ToolResult.error(
                "Unsupported level: " + level + " (object|attribute|module|template)").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String scope = present(JsonUtils.extractStringArgument(params, "scope")); //$NON-NLS-1$
        String objectFqn = present(JsonUtils.extractStringArgument(params, "objectFqn")); //$NON-NLS-1$
        String narrowingRefusal = scopeRefusal(projects, level, scope, objectFqn);
        if (narrowingRefusal != null)
        {
            return ToolResult.error(narrowingRefusal).toJson();
        }
        boolean narrowToFqn = "objectFqn".equalsIgnoreCase(scope); //$NON-NLS-1$

        try
        {
            // Only the metadata-model levels read the live model, and only those run under
            // UiSync. The file levels walk workspace files and the files mode reads plain
            // directories: holding the UI thread for them is what a long diff answered with
            // UiBusyException while the session was busy with something else.
            if (projects)
            {
                if ("module".equalsIgnoreCase(level) || "template".equalsIgnoreCase(level)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    return compareProjectSides(projectName, target, level, format,
                        narrowToFqn ? objectFqn : null);
                }
                return UiSync.call(() -> compareProjects(projectName, target, level, format,
                    showRenames, params, narrowToFqn ? objectFqn : null));
            }
            return compareFiles(projectName, target, level, format, narrowToFqn ? objectFqn : null);
        }
        catch (Exception e)
        {
            Activator.logError("compare_configurations error", e); //$NON-NLS-1$
            return ToolResult.error(TextSuggest.safeMessage(e)).toJson();
        }
    }

    /**
     * Whether a level word is one this comparison takes.
     *
     * @param level the level argument.
     * @return whether it names a level the tool compares at
     */
    private static boolean isKnownLevel(String level)
    {
        return "object".equalsIgnoreCase(level) || "attribute".equalsIgnoreCase(level) //$NON-NLS-1$ //$NON-NLS-2$
            || "module".equalsIgnoreCase(level) || "template".equalsIgnoreCase(level); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The refusal for narrowing arguments that cannot act on this call, or {@code null} when they
     * can.
     * <p>
     * {@code scope} names the sides the comparison covers - the whole of both, or one object when
     * {@code objectFqn} names it. An argument that cannot act is refused by name rather than
     * accepted and dropped: the call would answer as though it had been applied, and the caller
     * has no way to learn otherwise.
     * </p>
     *
     * @param projects whether the mode is projects.
     * @param level the level argument.
     * @param scope the scope argument, trimmed; {@code null} when absent.
     * @param objectFqn the objectFqn argument, trimmed; {@code null} when absent.
     * @return the refusal, or {@code null} when the arguments act or are absent
     */
    private static String scopeRefusal(boolean projects, String level, String scope, String objectFqn)
    {
        if (scope != null && !"project".equalsIgnoreCase(scope) //$NON-NLS-1$
            && !"objectFqn".equalsIgnoreCase(scope)) //$NON-NLS-1$
        {
            return TextSuggest.invalidValue("scope", scope, List.of("project", "objectFqn")); //$NON-NLS-1$
        }
        if (!projects && "attribute".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            return "level=attribute needs the metadata model; call it with mode=projects."; //$NON-NLS-1$
        }
        if (scope == null || !"objectFqn".equalsIgnoreCase(scope)) //$NON-NLS-1$
        {
            // Without scope=objectFqn the objectFqn acts only as the subject of an
            // attribute-level comparison in projects mode; anywhere else it changes nothing.
            if (objectFqn != null && !(projects && "attribute".equalsIgnoreCase(level))) //$NON-NLS-1$
            {
                return "objectFqn is read with scope=objectFqn, or with level=attribute in " //$NON-NLS-1$
                    + "mode=projects; with neither it changes nothing in this comparison."; //$NON-NLS-1$
            }
            return null;
        }
        if (objectFqn == null)
        {
            return "scope=objectFqn requires objectFqn."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * A trimmed argument value.
     *
     * @param value the raw argument.
     * @return the trimmed value, or {@code null} when empty or absent
     */
    private static String present(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Compares the two open projects at the metadata-model levels, under UiSync in the caller.
     *
     * @param projectName the first project.
     * @param targetProjectName the second project.
     * @param level object or attribute.
     * @param format json or markdown.
     * @param showRenames whether rename detection runs at object level.
     * @param params the whole call, for the attribute level's objectFqn.
     * @param narrowFqn the object the comparison is narrowed to by {@code scope=objectFqn}, or
     *                  {@code null} for the whole of both sides.
     * @return the comparison answer.
     */
    private String compareProjects(String projectName, String targetProjectName, String level,
        String format, boolean showRenames, Map<String, String> params, String narrowFqn)
    {
        IProject p1 = ProjectResolver.resolve(projectName);
        IProject p2 = ProjectResolver.resolve(targetProjectName);
        if (p1 == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        if (p2 == null)
        {
            return ProjectResolver.notFound(targetProjectName).toJson();
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
            if (narrowFqn != null)
            {
                diff.retainOnly(narrowFqn);
            }
            Map<String, Object> diffMap = diff.toMap();
            if (narrowFqn != null)
            {
                diffMap.put("narrowedTo", narrowFqn); //$NON-NLS-1$
            }
            return formatResult(level, format, diffMap);
        }
        // attribute: the objectFqn argument names the one object this level reads, whether it
        // arrived with scope=objectFqn or on its own.
        String objectFqn = present(JsonUtils.extractStringArgument(params, "objectFqn")); //$NON-NLS-1$
        if (objectFqn == null)
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

    /**
     * Compares the two open projects at the file levels - modules and templates - outside the UI
     * thread: the walk reads workspace files and asks nothing of the model.
     *
     * @param projectName the first project.
     * @param targetProjectName the second project.
     * @param level module or template.
     * @param format json or markdown.
     * @param narrowFqn the object whose files the walk is narrowed to by
     *                  {@code scope=objectFqn}, or {@code null} for all files.
     * @return the comparison answer.
     */
    private String compareProjectSides(String projectName, String targetProjectName, String level,
        String format, String narrowFqn)
    {
        IProject p1 = ProjectResolver.resolve(projectName);
        IProject p2 = ProjectResolver.resolve(targetProjectName);
        if (p1 == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        if (p2 == null)
        {
            return ProjectResolver.notFound(targetProjectName).toJson();
        }
        String prefix = objectDirectoryPrefix(narrowFqn);
        if (narrowFqn != null && prefix == null)
        {
            return ToolResult.error("objectFqn '" + narrowFqn //$NON-NLS-1$
                + "' names no metadata object directory; name it like Catalog.Products").toJson(); //$NON-NLS-1$
        }
        if ("module".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            return formatModuleDiff(collectModuleFiles(p1, prefix), collectModuleFiles(p2, prefix),
                format, narrowFqn);
        }
        return compareTemplatesByFiles(p1, p2, format, prefix, narrowFqn);
    }

    /**
     * Compares the template files of two projects.
     *
     * @param p1 the first project.
     * @param p2 the second project.
     * @param format json or markdown.
     * @param prefix the directory prefix the walk is narrowed to, or {@code null} for all files.
     * @param narrowFqn the object the prefix came from, for the answer; {@code null} when not
     *                  narrowed.
     * @return the comparison answer.
     */
    private String compareTemplatesByFiles(IProject p1, IProject p2, String format, String prefix,
        String narrowFqn)
    {
        Map<String, IFile> a = collectTemplateFiles(p1, prefix);
        Map<String, IFile> b = collectTemplateFiles(p2, prefix);
        Map<String, Object> diff = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> failed = new ArrayList<>();
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
                byte[] contentA = contentsOf(fa);
                byte[] contentB = contentsOf(fb);
                if (contentA == null || contentB == null)
                {
                    // A template that cannot be read is neither modified nor equal: the
                    // comparison says nothing about it, and names it as unread.
                    failed.add(key);
                }
                else if (!java.util.Arrays.equals(contentA, contentB))
                {
                    modified.add(key);
                }
            }
        }
        diff.put("added", added); //$NON-NLS-1$
        diff.put("removed", removed); //$NON-NLS-1$
        diff.put("modified", modified); //$NON-NLS-1$
        diff.put("failed", failed); //$NON-NLS-1$
        diff.put("addedCount", added.size()); //$NON-NLS-1$
        diff.put("removedCount", removed.size()); //$NON-NLS-1$
        diff.put("modifiedCount", modified.size()); //$NON-NLS-1$
        diff.put("failedCount", failed.size()); //$NON-NLS-1$
        if (narrowFqn != null)
        {
            diff.put("narrowedTo", narrowFqn); //$NON-NLS-1$
        }
        return formatResult("template", format, diff); //$NON-NLS-1$
    }

    /**
     * The bytes of a workspace file.
     *
     * @param file the file.
     * @return the content, or {@code null} when it could not be read
     */
    private static byte[] contentsOf(IFile file)
    {
        try (java.io.InputStream stream = file.getContents())
        {
            return stream.readAllBytes();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Collects the module files of a project.
     *
     * @param project the project.
     * @param prefix the directory prefix the walk is narrowed to, or {@code null} for all files.
     * @return project-relative path to file
     */
    private Map<String, IFile> collectModuleFiles(IProject project, String prefix)
    {
        Map<String, IFile> map = new LinkedHashMap<>();
        try
        {
            project.accept(resource -> {
                if (resource instanceof IFile && resource.getName().endsWith(".bsl")) //$NON-NLS-1$
                {
                    String key = resource.getProjectRelativePath().toString();
                    if (underPrefix(key.replace('\\', '/'), prefix))
                    {
                        map.put(key, (IFile) resource);
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

    /**
     * Collects the template files of a project.
     *
     * @param project the project.
     * @param prefix the directory prefix the walk is narrowed to, or {@code null} for all files.
     * @return project-relative path to file
     */
    private Map<String, IFile> collectTemplateFiles(IProject project, String prefix)
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
                        String key = resource.getProjectRelativePath().toString();
                        if (underPrefix(key.replace('\\', '/'), prefix))
                        {
                            map.put(key, (IFile) resource);
                        }
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

    /**
     * The export directory of an object, as a prefix both a plain export and an EDT-style
     * {@code src/} tree are keyed by.
     *
     * @param objectFqn the object; {@code null} narrows nothing.
     * @return the directory relative to the export root, or {@code null} when the name opens with
     *         no metadata type this EDT knows.
     */
    private static String objectDirectoryPrefix(String objectFqn)
    {
        return objectFqn == null ? null : BmComparisonHelper.objectDirectoryOf(objectFqn);
    }

    /**
     * Whether a file key sits under the directory prefix an object names. A key under
     * {@code src/} is tried without it, so a plain export and an EDT-style tree narrow alike.
     *
     * @param key the file path, with forward separators.
     * @param prefix the directory prefix, or {@code null} to keep everything.
     * @return whether the file belongs to the narrowed walk
     */
    private static boolean underPrefix(String key, String prefix)
    {
        if (prefix == null)
        {
            return true;
        }
        String candidate = key.startsWith("src/") ? key.substring(4) : key; //$NON-NLS-1$
        return candidate.startsWith(prefix + "/"); //$NON-NLS-1$
    }

    /**
     * Formats the module-file diff of two collected sides.
     *
     * @param a the first side's files, keyed by project-relative path.
     * @param b the second side's files.
     * @param format json or markdown.
     * @param narrowFqn the object the walk was narrowed to, for the answer; {@code null} when not
     *                  narrowed.
     * @return the comparison answer.
     */
    private String formatModuleDiff(Map<String, IFile> a, Map<String, IFile> b, String format,
        String narrowFqn)
    {
        java.util.Set<String> all = new java.util.TreeSet<>();
        all.addAll(a.keySet());
        all.addAll(b.keySet());
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<Map<String, Object>> modified = new ArrayList<>();
        List<String> failed = new ArrayList<>();
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
                    // A module that cannot be read is neither equal nor different, and leaving
                    // it out says neither: it is named, so the caller knows a comparison is
                    // missing rather than clean.
                    failed.add(key);
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
        diff.put("failed", failed); //$NON-NLS-1$
        diff.put("addedCount", added.size()); //$NON-NLS-1$
        diff.put("removedCount", removed.size()); //$NON-NLS-1$
        diff.put("modifiedCount", modified.size()); //$NON-NLS-1$
        diff.put("failedCount", failed.size()); //$NON-NLS-1$
        if (narrowFqn != null)
        {
            diff.put("narrowedTo", narrowFqn); //$NON-NLS-1$
        }
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

    /**
     * Compares two on-disk exports in the files mode: two files byte by byte, or two export
     * directories file by file. An export path is a directory as often as a file - a
     * Designer-XML export arrives as a tree - and reading a directory as a file answered with a
     * read error for a call the schema accepts.
     *
     * @param firstPath the first export, file or directory.
     * @param secondPath the second export, file or directory.
     * @param level the file kinds the comparison narrows to.
     * @param format json or markdown.
     * @param objectFqn the object the comparison is narrowed to by {@code scope=objectFqn}, or
     *                  {@code null} for the whole of both sides.
     * @return the comparison answer.
     */
    private String compareFiles(String firstPath, String secondPath, String level, String format,
        String objectFqn)
    {
        Path p1 = Paths.get(firstPath);
        Path p2 = Paths.get(secondPath);
        if (!Files.exists(p1))
        {
            return ToolResult.error("First export not found: " + p1).toJson(); //$NON-NLS-1$
        }
        if (!Files.exists(p2))
        {
            return ToolResult.error("Second export not found: " + p2).toJson(); //$NON-NLS-1$
        }
        boolean firstIsDirectory = Files.isDirectory(p1);
        boolean secondIsDirectory = Files.isDirectory(p2);
        if (firstIsDirectory || secondIsDirectory)
        {
            if (firstIsDirectory != secondIsDirectory)
            {
                return ToolResult.error("mode=files compares two files or two export " //$NON-NLS-1$
                    + "directories; one of these is a file and the other is a directory") //$NON-NLS-1$
                        .toJson();
            }
            if (objectFqn != null)
            {
                String prefix = objectDirectoryPrefix(objectFqn);
                if (prefix == null)
                {
                    return ToolResult.error("objectFqn '" + objectFqn //$NON-NLS-1$
                        + "' names no metadata object directory; name it like Catalog.Products") //$NON-NLS-1$
                            .toJson();
                }
                return compareExportDirectories(p1, p2, level, format, prefix, objectFqn);
            }
            return compareExportDirectories(p1, p2, level, format, null, null);
        }
        if (objectFqn != null)
        {
            return ToolResult.error("scope=objectFqn narrows a comparison of two export " //$NON-NLS-1$
                + "directories; two files are already as narrow as this comparison gets") //$NON-NLS-1$
                    .toJson();
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

    /**
     * Compares two export directories file by file, keyed by each file's path relative to its
     * root.
     *
     * @param first the first export directory.
     * @param second the second export directory.
     * @param level the file kinds the walk keeps: module keeps {@code .bsl}, template keeps the
     *              template extensions, object keeps everything.
     * @param format json or markdown.
     * @param prefix the directory prefix the walk is narrowed to, or {@code null} for all files.
     * @param narrowFqn the object the prefix came from, for the answer; {@code null} when not
     *                  narrowed.
     * @return the comparison answer.
     */
    private String compareExportDirectories(Path first, Path second, String level, String format,
        String prefix, String narrowFqn)
    {
        Map<String, Path> a = walkExport(first, level, prefix);
        Map<String, Path> b = walkExport(second, level, prefix);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        java.util.Set<String> all = new java.util.TreeSet<>();
        all.addAll(a.keySet());
        all.addAll(b.keySet());
        for (String key : all)
        {
            Path fa = a.get(key);
            Path fb = b.get(key);
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
                byte[] contentA = readBytes(fa);
                byte[] contentB = readBytes(fb);
                if (contentA == null || contentB == null)
                {
                    failed.add(key);
                }
                else if (!java.util.Arrays.equals(contentA, contentB))
                {
                    modified.add(key);
                }
            }
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("added", added); //$NON-NLS-1$
        diff.put("removed", removed); //$NON-NLS-1$
        diff.put("modified", modified); //$NON-NLS-1$
        diff.put("failed", failed); //$NON-NLS-1$
        diff.put("addedCount", added.size()); //$NON-NLS-1$
        diff.put("removedCount", removed.size()); //$NON-NLS-1$
        diff.put("modifiedCount", modified.size()); //$NON-NLS-1$
        diff.put("failedCount", failed.size()); //$NON-NLS-1$
        if (narrowFqn != null)
        {
            diff.put("narrowedTo", narrowFqn); //$NON-NLS-1$
        }
        return formatResult(level, format, diff);
    }

    /**
     * Walks one export tree and keeps the files the level and the narrowing ask about.
     *
     * @param root the export directory.
     * @param level the file kinds the walk keeps.
     * @param prefix the directory prefix the walk is narrowed to, or {@code null} for all files.
     * @return path relative to the root, with forward separators, mapped to the file
     */
    private static Map<String, Path> walkExport(Path root, String level, String prefix)
    {
        Map<String, Path> files = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root))
        {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String key = root.relativize(file).toString().replace('\\', '/');
                if (fileKindKept(level, key) && underPrefix(key, prefix))
                {
                    files.put(key, file);
                }
            });
        }
        catch (Exception e)
        {
            Activator.logWarning("compare_configurations: could not walk " + root //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return files;
    }

    /**
     * Whether a file of the walked export belongs to the requested level.
     *
     * @param level the level argument.
     * @param key the file path with forward separators.
     * @return whether the walk keeps the file
     */
    private static boolean fileKindKept(String level, String key)
    {
        if ("module".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            return key.endsWith(".bsl"); //$NON-NLS-1$
        }
        if ("template".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            return key.endsWith(".mxl") || key.endsWith(".dcs") || key.endsWith(".epf"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return true;
    }

    /**
     * The bytes of a plain file.
     *
     * @param file the file.
     * @return the content, or {@code null} when it could not be read
     */
    private static byte[] readBytes(Path file)
    {
        try
        {
            return Files.readAllBytes(file);
        }
        catch (Exception e)
        {
            return null;
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
