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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Full-text search across all Data Composition Schemas of a project - the
 * {@code .dcs} schema templates (report main schemas, object templates) and the
 * {@code .dcss} form settings. Closes the gap that {@link CodeTextSearcher} only
 * scans {@code .bsl}: a field, parameter, expression or query fragment that lives
 * in a DCS is invisible to a code search.
 * <p>
 * Each hit is reported with coordinates the agent can act on: the owning
 * metadata FQN (derived from the file path), the template, the line number and
 * the matched line - enough to then open the schema or edit it via
 * {@code dcs_workshop}.
 */
public class DcsSearchTool implements IMcpTool
{
    public static final String NAME = "dcs_search"; //$NON-NLS-1$

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int ABSOLUTE_MAX_RESULTS = 500;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Full-text search across all Data Composition Schemas of a project " //$NON-NLS-1$
            + "(.dcs schema templates + .dcss form settings) - the DCS counterpart " //$NON-NLS-1$
            + "of search_in_code (which only scans .bsl). Finds a field / parameter / " //$NON-NLS-1$
            + "expression / dataset / query fragment that lives inside a schema and " //$NON-NLS-1$
            + "returns it with coordinates: owning metadata FQN, template, line number " //$NON-NLS-1$
            + "and the matched line. Supports plain text or regex, case sensitivity, " //$NON-NLS-1$
            + "and a path substring filter."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("query", "Text or regex pattern to search for (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("caseSensitive", "Match upper and lower case exactly; off unless set") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("isRegex", "Interpret query as a regular expression. Default: false") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("pathFilter", //$NON-NLS-1$
                "Filter by schema path substring (e.g. 'Reports/Sales' or a schema name)") //$NON-NLS-1$
            .integerProperty("maxResults", //$NON-NLS-1$
                "Maximum matches to return. Default: 100, max: 500") //$NON-NLS-1$
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
        String query = JsonUtils.extractStringArgument(params, "query"); //$NON-NLS-1$
        boolean caseSensitive = JsonUtils.extractBooleanArgument(params, "caseSensitive", false); //$NON-NLS-1$
        boolean isRegex = JsonUtils.extractBooleanArgument(params, "isRegex", false); //$NON-NLS-1$
        String pathFilter = JsonUtils.extractStringArgument(params, "pathFilter"); //$NON-NLS-1$
        int maxResults = JsonUtils.extractIntArgument(params, "maxResults", DEFAULT_MAX_RESULTS); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (query == null || query.isEmpty())
        {
            return "Error: query is required"; //$NON-NLS-1$
        }
        maxResults = Math.min(Math.max(1, maxResults), ABSOLUTE_MAX_RESULTS);

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        Pattern pattern;
        try
        {
            int flags = Pattern.UNICODE_CHARACTER_CLASS;
            if (!caseSensitive)
            {
                flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            }
            pattern = Pattern.compile(isRegex ? query : Pattern.quote(query), flags);
        }
        catch (PatternSyntaxException e)
        {
            return "Error: Invalid regex pattern '" + query + "': " + TextSuggest.safeMessage(e); //$NON-NLS-1$ //$NON-NLS-2$
        }

        Collector collector = new Collector(pattern, pathFilter, maxResults);
        try
        {
            IResource srcFolder = project.findMember("src"); //$NON-NLS-1$
            if (srcFolder == null)
            {
                return "Error: no src/ folder found in project " + projectName; //$NON-NLS-1$
            }
            srcFolder.accept(collector);
        }
        catch (CoreException e)
        {
            return "Error searching project: " + TextSuggest.safeMessage(e); //$NON-NLS-1$
        }

        return format(query, collector);
    }

    private String format(String query, Collector c)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## DCS Search for \"").append(query).append("\"\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Total:** ").append(c.totalMatches).append(" hits in ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(c.matchesByFile.size()).append(" schema(s) (scanned ") //$NON-NLS-1$
            .append(c.scannedFiles).append(" .dcs/.dcss files)"); //$NON-NLS-1$
        if (c.shownMatches < c.totalMatches)
        {
            sb.append(" - showing first ").append(c.shownMatches); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (c.matchesByFile.isEmpty())
        {
            sb.append("Nothing matched.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        for (Map.Entry<String, List<Hit>> entry : c.matchesByFile.entrySet())
        {
            String path = entry.getKey();
            sb.append("### ").append(deriveOwner(path)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("`").append(path).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (Hit hit : entry.getValue())
            {
                sb.append("- **").append(hit.line).append(":** `") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(hit.text.replace("`", "'")).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Best-effort owning metadata FQN from a schema path, e.g.
     * {@code Reports/Sales/Templates/Main/Template.dcs} -&gt;
     * {@code Report.Sales (template Main)}. Falls back to the raw path.
     */
    private String deriveOwner(String displayPath)
    {
        String[] seg = displayPath.split("/"); //$NON-NLS-1$
        if (seg.length >= 2)
        {
            String type = MetadataTypeCatalog.getTypeByDirectoryName(seg[0]);
            if (type != null)
            {
                String owner = type + "." + seg[1]; //$NON-NLS-1$
                for (int i = 2; i + 1 < seg.length; i++)
                {
                    if ("Templates".equals(seg[i])) //$NON-NLS-1$
                    {
                        return owner + " (template " + seg[i + 1] + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                return owner;
            }
        }
        return displayPath;
    }

    private static final class Hit
    {
        final int line;
        final String text;

        Hit(int line, String text)
        {
            this.line = line;
            this.text = text;
        }
    }

    private static final class Collector implements IResourceVisitor
    {
        private final Pattern pattern;
        private final String pathFilter;
        private final int maxResults;

        final Map<String, List<Hit>> matchesByFile = new LinkedHashMap<>();
        int totalMatches = 0;
        int shownMatches = 0;
        int scannedFiles = 0;

        Collector(Pattern pattern, String pathFilter, int maxResults)
        {
            this.pattern = pattern;
            this.pathFilter = pathFilter;
            this.maxResults = maxResults;
        }

        @Override
        public boolean visit(IResource resource)
        {
            if (resource.getType() != IResource.FILE)
            {
                return true;
            }
            String name = resource.getName();
            if (!name.endsWith(".dcs") && !name.endsWith(".dcss")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return false;
            }
            String displayPath = resource.getProjectRelativePath().toString();
            if (displayPath.startsWith("src/")) //$NON-NLS-1$
            {
                displayPath = displayPath.substring(4);
            }
            if (pathFilter != null && !pathFilter.isEmpty()
                && !displayPath.toLowerCase().contains(pathFilter.toLowerCase()))
            {
                return false;
            }
            scannedFiles++;
            try
            {
                searchInFile((IFile)resource, displayPath);
            }
            catch (Exception e)
            {
                Activator.logWarning("dcs_search failed on " + displayPath + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return false;
        }

        private void searchInFile(IFile file, String displayPath) throws Exception
        {
            String content = BslModuleAccess.readFileText(file);
            if (!pattern.matcher(content).find())
            {
                return;
            }
            // split() (no -1) drops trailing empty lines so a final newline does
            // not create a spurious empty last line that a '.*' regex would match.
            String[] lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            for (int i = 0; i < lines.length; i++)
            {
                if (pattern.matcher(lines[i]).find())
                {
                    totalMatches++;
                    if (shownMatches < maxResults)
                    {
                        String text = lines[i].trim();
                        if (text.length() > 200)
                        {
                            text = text.substring(0, 200) + "..."; //$NON-NLS-1$
                        }
                        matchesByFile.computeIfAbsent(displayPath, k -> new ArrayList<>())
                            .add(new Hit(i + 1, text));
                        shownMatches++;
                    }
                }
            }
        }
    }
}
