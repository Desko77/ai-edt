/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * 1.43+: lists method interceptors declared in an extension project.
 * <p>
 * Scans every {@code .bsl} file under the extension and matches the
 * platform annotation pattern that an extension uses to attach a handler
 * to a base configuration method:
 * <ul>
 *   <li>{@code &Перед("OriginalMethod")} / {@code &Before("OriginalMethod")}</li>
 *   <li>{@code &После("OriginalMethod")} / {@code &After("OriginalMethod")}</li>
 *   <li>{@code &Вместо("OriginalMethod")} / {@code &Around("OriginalMethod")}</li>
 *   <li>{@code &ИзменениеИКонтроль("OriginalMethod")} / {@code &ChangeAndValidate("OriginalMethod")}</li>
 * </ul>
 * The handler procedure declared right after the annotation is captured as
 * the interceptor entry. Result is a flat list of intercept records grouped
 * by module path.
 * <p>
 * Use this before refactoring or merging an extension to understand what
 * the extension actually overrides.
 */
public class ListInterceptorsTool implements IMcpTool
{
    public static final String NAME = "list_interceptors"; //$NON-NLS-1$

    private static final Pattern ANNOTATION_PATTERN = Pattern.compile(
        // &Перед / &После / &Вместо / &ИзменениеИКонтроль / English equivalents
        "&\\s*(Перед|После|Вместо" //$NON-NLS-1$
            + "|ИзменениеИКонтроль" //$NON-NLS-1$
            + "|Before|After|Around|ChangeAndValidate)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)" //$NON-NLS-1$
            + "\\s*(?://[^\\n]*)?\\s*\\r?\\n\\s*(?:&[^\\n]+\\r?\\n\\s*)*" //$NON-NLS-1$
            + "(?:Процедура|Функция" //$NON-NLS-1$
            + "|Procedure|Function)\\s+(\\w+)", //$NON-NLS-1$
        // UNICODE_CHARACTER_CLASS is not optional here: without it Java's \w is ASCII-only, so the
        // handler name - Cyrillic in every real 1C codebase - matches nothing and the whole pattern
        // fails. The tool then walks every file and reports zero interceptors.
        Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS);

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `extension_workshop` `operation=list_interceptors`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "List method interceptors declared in an extension project. Scans BSL " //$NON-NLS-1$
            + "files for annotations like &Перед / &После / &Вместо / &ИзменениеИКонтроль " //$NON-NLS-1$
            + "(and English equivalents) and reports the handler procedure attached to " //$NON-NLS-1$
            + "each base configuration method. Use to understand an extension's footprint " //$NON-NLS-1$
            + "before refactoring or merging. Pass baseProjectName to validate each " //$NON-NLS-1$
            + "interceptor: targetExists=false means the annotation target method is " //$NON-NLS-1$
            + "missing in the base module (a hallucinated / renamed target that would " //$NON-NLS-1$
            + "fail at runtime) - a pre-merge barrier for multi-agent workflows."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Extension project name (V8ExtensionNature). Required.", true) //$NON-NLS-1$
            .stringProperty("kind", //$NON-NLS-1$
                "Filter by interceptor kind: before / after / around / changeAndValidate. " //$NON-NLS-1$
                    + "Comma-separated for multiple values. Default: all kinds.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum interceptors to return. Default 200.") //$NON-NLS-1$
            .stringProperty("baseProjectName", //$NON-NLS-1$
                "Optional base configuration project (V8ConfigurationNature). When set, " //$NON-NLS-1$
                    + "each interceptor is validated: targetExists=false means the annotation " //$NON-NLS-1$
                    + "target method does not exist in the base module (a hallucinated / renamed " //$NON-NLS-1$
                    + "target that fails at runtime). Use as a pre-merge barrier.") //$NON-NLS-1$
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
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required.").toJson(); //$NON-NLS-1$
        }
        String kindFilterCsv = JsonUtils.extractStringArgument(params, "kind"); //$NON-NLS-1$
        Integer limit = JsonUtils.extractIntegerArgument(params, "limit"); //$NON-NLS-1$
        int maxResults = (limit != null && limit > 0) ? limit : 200;

        IProject project = ru.aiedt.mcp.server.support.ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error("Error: no open project under this name: " + projectName //$NON-NLS-1$
                + " (tried exact name and unique '<base>." + projectName //$NON-NLS-1$
                + "' suffix match)").toJson(); //$NON-NLS-1$
        }

        // Optional base-project validation: when set, each interceptor's target
        // method is checked against the same-path module in the base config.
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        final IProject baseProject;
        if (baseProjectName != null && !baseProjectName.isEmpty())
        {
            baseProject = ru.aiedt.mcp.server.support.ProjectResolver.resolve(baseProjectName);
            if (baseProject == null)
            {
                return ToolResult.error("Base project not found or closed: " + baseProjectName) //$NON-NLS-1$
                    .toJson();
            }
        }
        else
        {
            baseProject = null;
        }

        List<String> allowedKinds = parseKindFilter(kindFilterCsv);
        List<Map<String, Object>> hits = new ArrayList<>();
        int[] scanned = { 0 };
        try
        {
            project.accept(new IResourceVisitor()
            {
                @Override
                public boolean visit(IResource resource)
                {
                    if (hits.size() >= maxResults)
                    {
                        return false;
                    }
                    if (resource.getType() == IResource.FILE
                        && "bsl".equalsIgnoreCase(resource.getFileExtension())) //$NON-NLS-1$
                    {
                        scanned[0]++;
                        scanFile((IFile) resource, allowedKinds, hits, maxResults, baseProject);
                    }
                    return true;
                }
            });
        }
        catch (Exception e)
        {
            return ToolResult.error("Workspace traversal failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())).toJson();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectName", projectName); //$NON-NLS-1$
        body.put("filesScanned", scanned[0]); //$NON-NLS-1$
        body.put("interceptorsFound", hits.size()); //$NON-NLS-1$
        body.put("truncated", hits.size() >= maxResults); //$NON-NLS-1$
        if (baseProject != null)
        {
            int unresolved = 0;
            for (Map<String, Object> e : hits)
            {
                if (Boolean.FALSE.equals(e.get("targetExists"))) //$NON-NLS-1$
                {
                    unresolved++;
                }
            }
            body.put("baseProject", baseProjectName); //$NON-NLS-1$
            body.put("unresolvedTargets", unresolved); //$NON-NLS-1$
            body.put("validated", true); //$NON-NLS-1$
        }
        body.put("interceptors", hits); //$NON-NLS-1$
        body.put("hint", "kind=before/after/around/changeAndValidate maps to Russian " //$NON-NLS-1$ //$NON-NLS-2$
            + "&Перед/&После/&Вместо/&ИзменениеИКонтроль and their English aliases.");
        return ToolResult.success().put("listInterceptors", body).toJson(); //$NON-NLS-1$
    }

    private static void scanFile(IFile file, List<String> allowedKinds,
        List<Map<String, Object>> hits, int maxResults, IProject baseProject)
    {
        String content;
        try (InputStream is = file.getContents())
        {
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException | org.eclipse.core.runtime.CoreException e)
        {
            Activator.logWarning("Cannot read " + file.getFullPath() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Matcher m = ANNOTATION_PATTERN.matcher(content);
        while (m.find())
        {
            if (hits.size() >= maxResults)
            {
                return;
            }
            String annotation = m.group(1);
            String kindKey = annotationToKind(annotation);
            if (allowedKinds != null && !allowedKinds.contains(kindKey))
            {
                continue;
            }
            String target = m.group(2);
            String handler = m.group(3);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", kindKey); //$NON-NLS-1$
            entry.put("annotation", annotation); //$NON-NLS-1$
            entry.put("targetMethod", target); //$NON-NLS-1$
            entry.put("handler", handler); //$NON-NLS-1$
            entry.put("module", file.getFullPath().toString()); //$NON-NLS-1$
            entry.put("line", lineOfOffset(content, m.start())); //$NON-NLS-1$
            if (baseProject != null)
            {
                annotateTargetExists(entry, file, target, baseProject);
            }
            hits.add(entry);
        }
    }

    /**
     * 1.43.x E2: validates an interceptor's target method against the base config.
     * The base module sits at the same project-relative path in {@code baseProject}
     * (the extension's adopted module mirrors the base module's location). Reads the
     * base module and looks for a {@code Процедура/Функция} (or English) declaration
     * of {@code target}. Adds {@code baseModuleFound} and {@code targetExists} to the
     * entry. A {@code targetExists=false} flags a hallucinated / renamed target that
     * fails at runtime. Best-effort: never throws.
     */
    private static void annotateTargetExists(Map<String, Object> entry, IFile extFile,
        String target, IProject baseProject)
    {
        try
        {
            IFile baseFile = baseProject.getFile(extFile.getProjectRelativePath());
            if (baseFile == null || !baseFile.exists())
            {
                entry.put("baseModuleFound", false); //$NON-NLS-1$
                entry.put("targetExists", false); //$NON-NLS-1$
                entry.put("validationNote", //$NON-NLS-1$
                    "base module not found at " + extFile.getProjectRelativePath() //$NON-NLS-1$
                        + " in " + baseProject.getName()); //$NON-NLS-1$
                return;
            }
            String baseSrc;
            try (InputStream is = baseFile.getContents())
            {
                baseSrc = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            entry.put("baseModuleFound", true); //$NON-NLS-1$
            entry.put("targetExists", methodDeclared(baseSrc, target)); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            entry.put("baseModuleFound", false); //$NON-NLS-1$
            entry.put("targetExists", false); //$NON-NLS-1$
            entry.put("validationNote", "base check failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * True when {@code src} declares a {@code Процедура/Функция/Procedure/Function}
     * named exactly {@code method} (case-insensitive on the keyword, exact on the name).
     */
    private static boolean methodDeclared(String src, String method)
    {
        if (src == null || method == null || method.isEmpty())
        {
            return false;
        }
        Pattern p = Pattern.compile(
            "(?im)^\\s*(?:&[^\\n]*\\r?\\n\\s*)*" //$NON-NLS-1$
                + "(?:Процедура|Функция|Procedure|Function)\\s+" //$NON-NLS-1$
                + Pattern.quote(method) + "\\s*\\("); //$NON-NLS-1$
        return p.matcher(src).find();
    }

    private static String annotationToKind(String annotation)
    {
        if (annotation == null)
        {
            return "unknown"; //$NON-NLS-1$
        }
        String a = annotation.toLowerCase();
        if (a.equals("перед") || a.equals("before")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "before"; //$NON-NLS-1$
        }
        if (a.equals("после") || a.equals("after")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "after"; //$NON-NLS-1$
        }
        if (a.equals("вместо") || a.equals("around")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "around"; //$NON-NLS-1$
        }
        if (a.equals("изменениеиконтроль") //$NON-NLS-1$
            || a.equals("changeandvalidate")) //$NON-NLS-1$
        {
            return "changeAndValidate"; //$NON-NLS-1$
        }
        return annotation;
    }

    private static List<String> parseKindFilter(String csv)
    {
        if (csv == null || csv.isEmpty())
        {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) //$NON-NLS-1$
        {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty())
            {
                if (t.equals("changeandvalidate")) //$NON-NLS-1$
                {
                    out.add("changeAndValidate"); //$NON-NLS-1$
                }
                else
                {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static int lineOfOffset(String content, int offset)
    {
        int line = 1;
        int end = Math.min(offset, content.length());
        for (int i = 0; i < end; i++)
        {
            if (content.charAt(i) == '\n')
            {
                line++;
            }
        }
        return line;
    }
}
