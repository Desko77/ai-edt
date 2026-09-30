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

    /** How many unreadable schemas the answer names; the rest are counted. */
    private static final int UNREADABLE_LISTED = 20;

    /** The longest fragment a hit shows. */
    private static final int FRAGMENT_LIMIT = 200;

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
            pattern = compile(query, isRegex, caseSensitive);
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

    /**
     * Compiles the query as the search reads it.
     * <p>
     * The whole schema text is searched at once, so a query may span lines. MULTILINE keeps
     * {@code ^} and {@code $} at line boundaries, where a line-by-line search put them.
     * </p>
     *
     * @param query the text or regular expression
     * @param isRegex whether the query is a regular expression
     * @param caseSensitive whether case must match
     * @return the pattern
     * @throws PatternSyntaxException when a regular expression does not compile
     */
    static Pattern compile(String query, boolean isRegex, boolean caseSensitive)
    {
        int flags = Pattern.UNICODE_CHARACTER_CLASS | Pattern.MULTILINE;
        if (!caseSensitive)
        {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        return Pattern.compile(isRegex ? query : Pattern.quote(query), flags);
    }

    /**
     * Renders the answer: the counts, the schemas that could not be read, and the hits by schema.
     *
     * @param query the query as given
     * @param c the finished walk
     * @return the markdown answer
     */
    String format(String query, Collector c)
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
        if (c.unreadableFiles > 0)
        {
            sb.append("**Unreadable:** ").append(c.unreadableFiles) //$NON-NLS-1$
                .append(" .dcs/.dcss file(s) could not be read and were not searched:\n"); //$NON-NLS-1$
            for (String entry : c.unreadable)
            {
                sb.append("- ").append(entry).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (c.unreadableFiles > c.unreadable.size())
            {
                sb.append("- and ").append(c.unreadableFiles - c.unreadable.size()) //$NON-NLS-1$
                    .append(" more\n"); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }

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
                sb.append("- **").append(hit.line); //$NON-NLS-1$
                if (hit.endLine > hit.line)
                {
                    sb.append('-').append(hit.endLine);
                }
                sb.append(":** `") //$NON-NLS-1$
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

    /** One hit: the lines it spans and the fragment shown. */
    static final class Hit
    {
        final int line;
        final int endLine;
        final String text;

        /**
         * @param line the line the match starts on, from 1
         * @param endLine the line the match ends on
         * @param text the fragment shown
         */
        Hit(int line, int endLine, String text)
        {
            this.line = line;
            this.endLine = endLine;
            this.text = text;
        }
    }

    /** The walk over a project's schemas: what it read, what it could not, and what matched. */
    static class Collector implements IResourceVisitor
    {
        private final Pattern pattern;
        private final String pathFilter;
        private final int maxResults;

        final Map<String, List<Hit>> matchesByFile = new LinkedHashMap<>();
        int totalMatches = 0;
        int shownMatches = 0;
        int scannedFiles = 0;
        int unreadableFiles = 0;
        final List<String> unreadable = new ArrayList<>();

        /**
         * @param pattern the compiled query
         * @param pathFilter the path substring a schema must carry, or <code>null</code>
         * @param maxResults how many hits to keep
         */
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
            String content;
            try
            {
                content = readText((IFile)resource);
            }
            catch (Exception e)
            {
                unreadable(displayPath, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                return false;
            }
            try
            {
                search(content, displayPath);
                scannedFiles++;
            }
            catch (RuntimeException | StackOverflowError e)
            {
                // A pattern that recurses per character can run out of stack over a whole schema.
                unreadable(displayPath, "search failed: " + e.getClass().getSimpleName()); //$NON-NLS-1$
            }
            return false;
        }

        /**
         * Counts a schema that was not searched through, and names it while the list has room.
         *
         * @param displayPath the schema path
         * @param reason why it was not searched
         */
        private void unreadable(String displayPath, String reason)
        {
            unreadableFiles++;
            if (unreadable.size() < UNREADABLE_LISTED)
            {
                unreadable.add(displayPath + ": " + reason); //$NON-NLS-1$
            }
            Activator.logWarning("dcs_search failed on " + displayPath + ": " + reason); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * Reads a schema's text.
         *
         * @param file the schema file
         * @return its text
         * @throws Exception when the file cannot be read
         */
        String readText(IFile file) throws Exception
        {
            return BslModuleAccess.readFileText(file);
        }

        /**
         * Searches one schema's text as a whole, so a match may span lines.
         * <p>
         * A hit is counted once per line it starts on, as the search counted lines before a
         * match could span them; whitespace that opens a match across a line break is not where
         * it starts (see {@link #startOfContent}). Trailing line breaks are dropped first: an
         * empty last line is not a line of the schema, and {@code .*} would match it; a schema of
         * line breaks alone has no lines. A match made only of whitespace runs across line breaks
         * and counts once, and {@code \A} and {@code \z} mark the ends of the schema.
         * </p>
         *
         * @param content the schema text
         * @param displayPath the schema path the hits are filed under
         */
        void search(String content, String displayPath)
        {
            String text = content.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            int length = text.length();
            while (length > 0 && text.charAt(length - 1) == '\n')
            {
                length--;
            }
            if (length == 0)
            {
                return;
            }
            text = text.substring(0, length);
            Matcher m = pattern.matcher(text);
            int line = 1;
            int counted = 0;
            int lastLine = 0;
            while (m.find())
            {
                int start = startOfContent(text, m.start(), m.end());
                line += countBreaks(text, counted, start);
                counted = start;
                if (line == lastLine)
                {
                    continue;
                }
                lastLine = line;
                totalMatches++;
                if (shownMatches < maxResults)
                {
                    int endLine = line + countBreaks(text, start, Math.max(start, m.end() - 1));
                    matchesByFile.computeIfAbsent(displayPath, k -> new ArrayList<>())
                        .add(new Hit(line, endLine, fragment(text, start, m.end())));
                    shownMatches++;
                }
            }
        }

        /**
         * Where a match starts for the line it is filed under.
         * <p>
         * A match that opens with whitespace running across a line break - {@code \s*ВЫБРАТЬ}
         * matched from the end of the line above - starts after the last of those breaks. A
         * match made only of whitespace and ending on a line break keeps its start, the line
         * that break ends.
         * </p>
         *
         * @param text the schema text
         * @param start where the match starts
         * @param end where the match ends
         * @return the offset the match's line is read from
         */
        static int startOfContent(String text, int start, int end)
        {
            int from = start;
            for (int i = start; i < end && Character.isWhitespace(text.charAt(i)); i++)
            {
                if (text.charAt(i) == '\n' && i + 1 < end)
                {
                    from = i + 1;
                }
            }
            return from;
        }

        /**
         * Counts the line breaks in a range of text.
         *
         * @param text the text
         * @param from the first offset, inclusive
         * @param to the last offset, exclusive
         * @return how many line breaks lie in the range
         */
        private static int countBreaks(String text, int from, int to)
        {
            int breaks = 0;
            for (int i = from; i < to; i++)
            {
                if (text.charAt(i) == '\n')
                {
                    breaks++;
                }
            }
            return breaks;
        }

        /**
         * The fragment a hit shows: the lines from the start of the match to its end, each
         * trimmed, joined with a literal {@code \n}, cut at {@link #FRAGMENT_LIMIT} characters.
         *
         * @param text the schema text
         * @param start where the match starts
         * @param end where the match ends
         * @return the fragment
         */
        private static String fragment(String text, int start, int end)
        {
            int from = text.lastIndexOf('\n', start - 1) + 1;
            int to = text.indexOf('\n', Math.max(start, end - 1));
            if (to < 0)
            {
                to = text.length();
            }
            List<String> lines = new ArrayList<>();
            for (String piece : text.substring(from, to).split("\n", -1)) //$NON-NLS-1$
            {
                lines.add(piece.trim());
            }
            String shown = String.join(" \\n ", lines); //$NON-NLS-1$
            if (shown.length() <= FRAGMENT_LIMIT)
            {
                return shown;
            }
            int cut = Character.isHighSurrogate(shown.charAt(FRAGMENT_LIMIT - 1)) ? FRAGMENT_LIMIT - 1
                : FRAGMENT_LIMIT;
            return shown.substring(0, cut) + "..."; //$NON-NLS-1$
        }
    }
}
