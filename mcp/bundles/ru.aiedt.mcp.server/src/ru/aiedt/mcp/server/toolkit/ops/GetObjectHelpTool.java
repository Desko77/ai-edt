/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.YamlFrontMatter;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;

import io.github.furstenheim.CopyDown;

/**
 * Tool to read the embedded help associated with a metadata object.
 * <p>
 * 1C metadata objects can carry HTML help pages per language (the "?" button
 * in the 1C user interface). Pages live both in the BM model (when EDT exposes
 * an Help/HelpPage API) and on disk under {@code src/<Plural>/<Name>/Help/*.html}.
 * This tool tries the BM model first via reflection and falls back to reading
 * the on-disk HTML files. Output is converted to Markdown via {@code CopyDown}.
 */
public class GetObjectHelpTool implements IMcpTool
{
    public static final String NAME = "get_object_help"; //$NON-NLS-1$

    /** Lazy-init CopyDown (thread-confined - method is invoked from UI thread). */
    private CopyDown copyDown;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `docs_lookup` `operation=get_object_help`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Read the embedded help of a 1C metadata object - the HTML pages " //$NON-NLS-1$
            + "shown on the '?' button in the 1C UI. Falls back to on-disk Help/*.html " //$NON-NLS-1$
            + "scan when the EDT BM API does not expose Help. Returns markdown by default. " //$NON-NLS-1$
            + "Filter by language=ru/en/auto. Use this to understand business intent " //$NON-NLS-1$
            + "before modifying an object."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectName", //$NON-NLS-1$
                "Object FQN, e.g. 'Document.SalesOrder', 'Catalog.Products', " //$NON-NLS-1$
                    + "'CommonModule.Common'. Russian type names supported.", true) //$NON-NLS-1$
            .stringProperty("format", //$NON-NLS-1$
                "Output format: 'markdown' (default), 'html', 'text'.") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Filter pages by language code, e.g. 'ru' / 'en'. Default 'auto' " //$NON-NLS-1$
                    + "concatenates every available page.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String format = JsonUtils.extractStringArgument(params, "format"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (objectName == null || objectName.isEmpty())
        {
            return "Error: " + TextSuggest.missingParam("objectName", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (format == null || format.isEmpty())
        {
            format = "markdown"; //$NON-NLS-1$
        }
        if (language == null || language.isEmpty())
        {
            language = "auto"; //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        // Resolve MdObject via the existing Configuration API (English/Russian/etc.)
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return "Error: configuration provider is unavailable"; //$NON-NLS-1$
        }
        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return "Error: unable to retrieve configuration for project: " + projectName; //$NON-NLS-1$
        }

        String fqn = MetadataTypeCatalog.normalizeFqn(objectName);
        String[] parts = fqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return "Error: objectName must be 'Type.Name', e.g. 'Document.SalesOrder'"; //$NON-NLS-1$
        }
        String typePart = parts[0];
        String namePart = parts[1];

        MdObject mdObject = MetadataTypeCatalog.findObject(config, typePart, namePart);
        if (mdObject == null)
        {
            return "Error: Object not found: " + fqn; //$NON-NLS-1$
        }

        // Collect help pages: prefer BM API when available.
        List<HelpPage> pages = collectFromBmModel(mdObject, language);
        boolean fromBm = !pages.isEmpty();
        if (pages.isEmpty())
        {
            pages = collectFromDisk(project, typePart, namePart, language);
        }

        // Object metadata for YamlFrontMatter (synonym, comment, subsystems hint).
        // 1.42.4 BUG-T4: Synonym is an EMap<String, String> (language -> text)
        // and v.toString() prints "[LocalStringMapEntryImpl@hash]" by default.
        // Resolve it to a localized string using the language preference.
        String synonym = resolveLocalizedString(mdObject, "getSynonym", language); //$NON-NLS-1$
        String comment = invokeStringNoArg(mdObject, "getComment"); //$NON-NLS-1$

        YamlFrontMatter fm = YamlFrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("objectName", fqn) //$NON-NLS-1$
            .put("type", typePart) //$NON-NLS-1$
            .put("format", format) //$NON-NLS-1$
            .put("source", fromBm ? "bm" : (pages.isEmpty() ? "none" : "disk")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .put("pages", pages.size()); //$NON-NLS-1$

        if (synonym != null && !synonym.isEmpty())
        {
            fm.put("synonym", synonym); //$NON-NLS-1$
        }
        if (comment != null && !comment.isEmpty())
        {
            fm.put("comment", comment); //$NON-NLS-1$
        }

        StringBuilder body = new StringBuilder();
        if (pages.isEmpty())
        {
            body.append("# Help not available\n\n"); //$NON-NLS-1$
            body.append("No embedded help is associated with **").append(fqn).append("**.\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (synonym != null && !synonym.isEmpty())
            {
                body.append("**Synonym:** ").append(synonym).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (comment != null && !comment.isEmpty())
            {
                body.append("**Comment:** ").append(comment).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return fm.wrapContent(body.toString());
        }

        body.append("# Help: ").append(fqn).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (HelpPage page : pages)
        {
            body.append("## ").append(page.label()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            body.append(formatContent(page.html, format));
            if (!body.toString().endsWith("\n")) //$NON-NLS-1$
            {
                body.append("\n"); //$NON-NLS-1$
            }
            body.append("\n"); //$NON-NLS-1$
        }
        return fm.wrapContent(body.toString());
    }

    /**
     * Holder for one help page, language-tagged.
     */
    private static final class HelpPage
    {
        final String language;
        final String html;

        HelpPage(String language, String html)
        {
            this.language = language;
            this.html = html;
        }

        String label()
        {
            return language != null && !language.isEmpty() ? language : "Help"; //$NON-NLS-1$
        }
    }

    /**
     * Tries to read help from the BM model via reflection. EDT historically
     * exposes {@code MdObject.getHelp()} returning a {@code Help} object that
     * holds {@code getPages()} ({@link List} of pages with
     * {@code getLanguage().getKey()} / {@code getContent()}).
     */
    @SuppressWarnings("unchecked")
    private List<HelpPage> collectFromBmModel(MdObject mdObject, String language)
    {
        List<HelpPage> result = new ArrayList<>();
        try
        {
            Method getHelp = mdObject.getClass().getMethod("getHelp"); //$NON-NLS-1$
            Object help = getHelp.invoke(mdObject);
            if (help == null)
            {
                return result;
            }
            Method getPages;
            try
            {
                getPages = help.getClass().getMethod("getPages"); //$NON-NLS-1$
            }
            catch (NoSuchMethodException nsme)
            {
                // Some EDT versions expose pages as direct fields; treat help itself as a single page.
                String html = invokeStringNoArg(help, "getContent"); //$NON-NLS-1$
                if (html != null && !html.isEmpty())
                {
                    result.add(new HelpPage("default", html)); //$NON-NLS-1$
                }
                return result;
            }
            Object pages = getPages.invoke(help);
            if (!(pages instanceof List))
            {
                return result;
            }
            for (Object page : (List<Object>) pages)
            {
                String lang = extractLanguageCode(page);
                if (!languageMatches(lang, language))
                {
                    continue;
                }
                String html = invokeStringNoArg(page, "getContent"); //$NON-NLS-1$
                if (html == null || html.isEmpty())
                {
                    continue;
                }
                result.add(new HelpPage(lang, html));
            }
        }
        catch (NoSuchMethodException nsme)
        {
            // BM API does not expose help on this EDT version - fall back to disk
        }
        catch (Exception e)
        {
            Activator.logWarning("get_object_help: BM help read failed - " + e.getMessage()); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Falls back to scanning {@code src/<dir>/<name>/Help/} for *.html files.
     * Each file's stem is treated as the language code (e.g. {@code ru.html}).
     */
    private List<HelpPage> collectFromDisk(IProject project, String typePart, String namePart,
        String language)
    {
        List<HelpPage> result = new ArrayList<>();
        String dirName = MetadataTypeCatalog.getDirectoryName(typePart);
        if (dirName == null)
        {
            return result;
        }
        IPath helpPath = new Path("src").append(dirName).append(namePart).append("Help"); //$NON-NLS-1$ //$NON-NLS-2$
        IFolder folder = project.getFolder(helpPath);
        if (folder == null || !folder.exists())
        {
            return result;
        }
        try
        {
            for (IResource member : folder.members())
            {
                if (!(member instanceof IFile))
                {
                    continue;
                }
                IFile file = (IFile) member;
                String name = file.getName();
                if (!name.toLowerCase().endsWith(".html") && !name.toLowerCase().endsWith(".htm")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    continue;
                }
                String stem = name.contains(".") //$NON-NLS-1$
                    ? name.substring(0, name.lastIndexOf('.'))
                    : name;
                if (!languageMatches(stem, language))
                {
                    continue;
                }
                String html = readFileAsString(file);
                if (html != null && !html.isEmpty())
                {
                    result.add(new HelpPage(stem, html));
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("get_object_help: disk scan failed - " + e.getMessage()); //$NON-NLS-1$
        }
        return result;
    }

    private String formatContent(String html, String format)
    {
        if (html == null || html.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        switch (format.toLowerCase())
        {
            case "html": //$NON-NLS-1$
                return html;
            case "text": //$NON-NLS-1$
                return html.replaceAll("(?s)<style[^>]*>.*?</style>", "") //$NON-NLS-1$ //$NON-NLS-2$
                    .replaceAll("<[^>]+>", " ") //$NON-NLS-1$ //$NON-NLS-2$
                    .replaceAll("\\s+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                    .trim();
            case "markdown": //$NON-NLS-1$
            default:
                return convertHtmlToMarkdown(html);
        }
    }

    private String convertHtmlToMarkdown(String html)
    {
        try
        {
            String cleaned = html.replaceAll("(?s)<style[^>]*>.*?</style>", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (copyDown == null)
            {
                copyDown = new CopyDown();
            }
            String markdown = copyDown.convert(cleaned);
            return markdown.replaceAll("\n{3,}", "\n\n").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logError("Error converting help HTML to Markdown", e); //$NON-NLS-1$
            return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }

    private String extractLanguageCode(Object page)
    {
        try
        {
            Method getLang = page.getClass().getMethod("getLanguage"); //$NON-NLS-1$
            Object lang = getLang.invoke(page);
            if (lang == null)
            {
                return null;
            }
            String code = invokeStringNoArg(lang, "getKey"); //$NON-NLS-1$
            if (code != null && !code.isEmpty())
            {
                return code;
            }
            return invokeStringNoArg(lang, "getName"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
            return null;
        }
    }

    private boolean languageMatches(String pageLang, String requested)
    {
        if (requested == null || requested.isEmpty() || "auto".equalsIgnoreCase(requested)) //$NON-NLS-1$
        {
            return true;
        }
        if (pageLang == null)
        {
            return false;
        }
        return pageLang.equalsIgnoreCase(requested);
    }

    private String readFileAsString(IFile file)
    {
        try (InputStream in = file.getContents())
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0)
            {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            Activator.logWarning("get_object_help: read failed for " + file.getFullPath() //$NON-NLS-1$
                + " - " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private String invokeStringNoArg(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v != null ? v.toString() : null;
        }
        catch (Exception ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
            return null;
        }
    }

    /**
     * 1.42.4 BUG-T4: resolve a LocalString-typed property (EMap<String, String>)
     * to a single localized string. Tries the requested {@code language} first,
     * then "ru", "en", and finally any first available value. Returns null when
     * the getter is missing or the map is empty / non-EMap.
     */
    private String resolveLocalizedString(Object target, String methodName, String language)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v == null)
            {
                return null;
            }
            if (v instanceof String)
            {
                return ((String) v).isEmpty() ? null : (String) v;
            }
            if (!(v instanceof java.util.Map))
            {
                // Some EMF impls expose Map<String,String> directly; if it's
                // an EMap-style structure with .get(key), try that first.
                try
                {
                    Method get = v.getClass().getMethod("get", Object.class); //$NON-NLS-1$
                    String[] order = (language != null && !language.isEmpty()
                        && !"auto".equalsIgnoreCase(language))
                        ? new String[] { language, "ru", "en" } //$NON-NLS-1$ //$NON-NLS-2$
                        : new String[] { "ru", "en" }; //$NON-NLS-1$ //$NON-NLS-2$
                    for (String code : order)
                    {
                        Object val = get.invoke(v, code);
                        if (val instanceof String && !((String) val).isEmpty())
                        {
                            return (String) val;
                        }
                    }
                }
                catch (NoSuchMethodException ignored)
                {
                    // not a Map-like container
                }
                return null;
            }
            @SuppressWarnings("unchecked")
            java.util.Map<Object, Object> map = (java.util.Map<Object, Object>) v;
            if (map.isEmpty())
            {
                return null;
            }
            String[] order = (language != null && !language.isEmpty()
                && !"auto".equalsIgnoreCase(language))
                ? new String[] { language, "ru", "en" } //$NON-NLS-1$ //$NON-NLS-2$
                : new String[] { "ru", "en" }; //$NON-NLS-1$ //$NON-NLS-2$
            for (String code : order)
            {
                Object val = map.get(code);
                if (val instanceof String && !((String) val).isEmpty())
                {
                    return (String) val;
                }
            }
            // Fall back to the first available entry.
            Object first = map.values().iterator().next();
            return first != null ? first.toString() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
