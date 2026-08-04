/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.jsoup.Jsoup;

import com._1c.g5.v8.dt.mcore.Help;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.md.help.MdHelpUtil;

import ru.aiedt.mcp.server.Activator;

/**
 * Authors object help pages - the inverse of the {@code mdo-help-page-missing-html}
 * export linter.
 * <p>
 * An object's help is two coordinated artefacts:
 * <ul>
 *   <li>the {@code <help><pages><lang>CODE</lang></pages></help>} block inside the
 *       object's own {@code .mdo} (a containment {@link Help}/{@code HelpPage} on the
 *       {@code BasicDbObject.help} feature - it round-trips through a normal BM
 *       forceExport because it lives in the same resource as the object), and</li>
 *   <li>the {@code Help/<lang>.html} file sibling to the {@code .mdo} that carries
 *       the actual page content (bound by path convention, like {@code Rights.rights}
 *       - written directly to disk, never through the BM).</li>
 * </ul>
 * A {@code <lang>} declared without its {@code Help/<lang>.html} file makes the EDT
 * editor stay green yet crashes configuration {@code .cf} export on the empty page;
 * this helper keeps the two in lockstep.
 */
public final class BmHelpHelper
{
    private BmHelpHelper()
    {
    }

    /** Default help-page language when the caller does not specify one. */
    public static final String DEFAULT_LANG = "ru"; //$NON-NLS-1$

    /**
     * EDT writes help pages as HTML 4.0 Transitional with the platform help
     * stylesheet link ({@link MdHelpUtil#MD_HELP_CSS_LINK}). We replicate the
     * envelope (plus a UTF-8 charset meta for Cyrillic content) so the page
     * renders the same way in the EDT help viewer; only the GENERATOR differs.
     */
    private static final String HTML_HEADER =
        "<!DOCTYPE html PUBLIC \"-//W3C//DTD HTML 4.0 Transitional//EN\">" //$NON-NLS-1$
        + "<html><head>" //$NON-NLS-1$
        + "<meta content=\"text/html; charset=utf-8\" http-equiv=\"Content-Type\"></meta>" //$NON-NLS-1$
        + "<link rel=\"stylesheet\" type=\"text/css\" " //$NON-NLS-1$
        + "href=\"" + MdHelpUtil.MD_HELP_CSS_LINK + "\"></link>" //$NON-NLS-1$ //$NON-NLS-2$
        + "<meta name=\"GENERATOR\" content=\"1C:EDT MCP\"></meta>" //$NON-NLS-1$
        + "</head><body>\n"; //$NON-NLS-1$

    private static final String HTML_FOOTER = "\n</body></html>"; //$NON-NLS-1$

    // ---- markdown subset patterns ----
    private static final Pattern HEADING = Pattern.compile("^\\s*(#{1,6})\\s+(.*)$"); //$NON-NLS-1$
    private static final Pattern UL_ITEM = Pattern.compile("^\\s*[-*+]\\s+(.*)$"); //$NON-NLS-1$
    private static final Pattern OL_ITEM = Pattern.compile("^\\s*\\d+\\.\\s+(.*)$"); //$NON-NLS-1$
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)"); //$NON-NLS-1$
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*"); //$NON-NLS-1$
    private static final Pattern ITALIC_STAR = Pattern.compile("\\*(.+?)\\*"); //$NON-NLS-1$
    private static final Pattern ITALIC_US = Pattern.compile("_(.+?)_"); //$NON-NLS-1$
    private static final Pattern CODE = Pattern.compile("`([^`]+?)`"); //$NON-NLS-1$

    // -- = --
    // BM model mutation (run inside BmObjectHelper.executeWriteOnObject)
    // -- = --

    /**
     * True when the object's metadata type carries the {@code help} feature
     * (resolved through EDT's own {@link MdHelpUtil#findHelpFeature}).
     */
    public static boolean hasHelpFeature(EObject owner)
    {
        return owner != null && MdHelpUtil.findHelpFeature(owner.eClass()) != null;
    }

    /**
     * Ensures a help page for {@code lang} exists on the object's {@link Help},
     * creating and attaching the {@code Help} container if needed.
     * <p>
     * Mirrors {@link MdHelpUtil} but does the container {@code eSet} directly
     * rather than via {@code MdHelpUtil.getHelp}: the latter may spawn a nested
     * BM task, and this runs inside an already-open BM transaction. The page
     * lookup/creation reuse the canonical {@code MdHelpUtil} helpers (pure EMF).
     *
     * @return {@code true} if a new page was added, {@code false} if it was
     *     already declared (idempotent)
     */
    public static boolean ensureHelpPage(EObject owner, String lang)
    {
        EReference feature = MdHelpUtil.findHelpFeature(owner.eClass());
        if (feature == null)
        {
            throw new IllegalStateException(
                "object type " + owner.eClass().getName() + " does not support help pages"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Help help = (Help)owner.eGet(feature);
        if (help == null || help.eIsProxy())
        {
            help = McoreFactory.eINSTANCE.createHelp();
            owner.eSet(feature, help);
        }
        if (MdHelpUtil.selectHelpPage(help, lang) != null)
        {
            return false;
        }
        MdHelpUtil.createHelpPage(help, lang);
        return true;
    }

    /**
     * Removes the help page for {@code lang}. When it was the last page the whole
     * {@link Help} container is cleared so the {@code .mdo} drops the empty
     * {@code <help>} block. Reads the container directly (never auto-creates).
     *
     * @return {@code true} if a page was removed
     */
    public static boolean removeHelpPage(EObject owner, String lang)
    {
        EReference feature = MdHelpUtil.findHelpFeature(owner.eClass());
        if (feature == null)
        {
            return false;
        }
        Help help = (Help)owner.eGet(feature);
        if (help == null || help.eIsProxy())
        {
            return false;
        }
        boolean removed = help.getPages().removeIf(p -> lang.equals(p.getLang()));
        if (removed && help.getPages().isEmpty())
        {
            owner.eSet(feature, null);
        }
        return removed;
    }

    // -- = --
    // HTML building
    // -- = --

    /** Wraps the content (per {@code format}) in the EDT help HTML envelope. */
    public static String buildHelpHtml(String content, String format)
    {
        return HTML_HEADER + toBodyHtml(content, format) + HTML_FOOTER;
    }

    private static String toBodyHtml(String content, String format)
    {
        if (content == null)
        {
            content = ""; //$NON-NLS-1$
        }
        String fmt = format == null ? "" : format.trim().toLowerCase(); //$NON-NLS-1$
        if (fmt.isEmpty())
        {
            fmt = looksLikeHtml(content) ? "html" : "markdown"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        switch (fmt)
        {
            case "md": //$NON-NLS-1$
            case "markdown": //$NON-NLS-1$
                return markdownToHtml(content);
            case "text": //$NON-NLS-1$
            case "plain": //$NON-NLS-1$
                return textToHtml(content);
            case "html": //$NON-NLS-1$
            default:
                return normalizeHtmlFragment(content);
        }
    }

    private static boolean looksLikeHtml(String s)
    {
        return Pattern.compile("<[a-zA-Z!/]").matcher(s).find(); //$NON-NLS-1$
    }

    /** Balances a user-supplied HTML fragment (or extracts the body of a full doc). */
    private static String normalizeHtmlFragment(String content)
    {
        try
        {
            String lower = content.toLowerCase();
            if (lower.contains("<html") || lower.contains("<body")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return Jsoup.parse(content).body().html();
            }
            return Jsoup.parseBodyFragment(content).body().html();
        }
        catch (Exception e)
        {
            Activator.logWarning("Help HTML normalize failed, using raw content: " + e.getMessage()); //$NON-NLS-1$
            return content;
        }
    }

    private static String textToHtml(String content)
    {
        StringBuilder sb = new StringBuilder();
        for (String para : content.replace("\r\n", "\n").replace("\r", "\n").split("\n\\s*\n")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            String trimmed = para.trim();
            if (!trimmed.isEmpty())
            {
                sb.append("<p>").append(escapeHtml(trimmed).replace("\n", "<br>")).append("</p>\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
        }
        return sb.toString();
    }

    /**
     * Minimal markdown -> HTML for the common help-page subset: ATX headings,
     * unordered/ordered lists, bold/italic/code spans, links, blank-line
     * paragraphs. Complex documents should be supplied as {@code format=html}.
     */
    private static String markdownToHtml(String md)
    {
        String[] lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        StringBuilder out = new StringBuilder();
        List<String> para = new ArrayList<>();
        List<String> ul = new ArrayList<>();
        List<String> ol = new ArrayList<>();
        for (String line : lines)
        {
            if (line.trim().isEmpty())
            {
                flushParagraph(out, para);
                flushList(out, ul, "ul"); //$NON-NLS-1$
                flushList(out, ol, "ol"); //$NON-NLS-1$
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches())
            {
                flushParagraph(out, para);
                flushList(out, ul, "ul"); //$NON-NLS-1$
                flushList(out, ol, "ol"); //$NON-NLS-1$
                int level = heading.group(1).length();
                out.append("<h").append(level).append('>').append(inline(heading.group(2).trim())) //$NON-NLS-1$
                    .append("</h").append(level).append(">\n"); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            Matcher uli = UL_ITEM.matcher(line);
            if (uli.matches())
            {
                flushParagraph(out, para);
                flushList(out, ol, "ol"); //$NON-NLS-1$
                ul.add(inline(uli.group(1).trim()));
                continue;
            }
            Matcher oli = OL_ITEM.matcher(line);
            if (oli.matches())
            {
                flushParagraph(out, para);
                flushList(out, ul, "ul"); //$NON-NLS-1$
                ol.add(inline(oli.group(1).trim()));
                continue;
            }
            flushList(out, ul, "ul"); //$NON-NLS-1$
            flushList(out, ol, "ol"); //$NON-NLS-1$
            para.add(inline(line.trim()));
        }
        flushParagraph(out, para);
        flushList(out, ul, "ul"); //$NON-NLS-1$
        flushList(out, ol, "ol"); //$NON-NLS-1$
        return out.toString();
    }

    private static void flushParagraph(StringBuilder out, List<String> para)
    {
        if (!para.isEmpty())
        {
            out.append("<p>").append(String.join(" ", para)).append("</p>\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            para.clear();
        }
    }

    private static void flushList(StringBuilder out, List<String> items, String tag)
    {
        if (!items.isEmpty())
        {
            out.append('<').append(tag).append(">\n"); //$NON-NLS-1$
            for (String item : items)
            {
                out.append("<li>").append(item).append("</li>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            out.append("</").append(tag).append(">\n"); //$NON-NLS-1$ //$NON-NLS-2$
            items.clear();
        }
    }

    // Out-of-band placeholders for extracted spans (private-use chars + index).
    private static final char SENTINEL_OPEN = '\uE000';
    private static final char SENTINEL_CLOSE = '\uE001';

    private static String sentinel(int index)
    {
        return SENTINEL_OPEN + Integer.toString(index) + SENTINEL_CLOSE;
    }

    /**
     * Escapes HTML then applies inline markdown spans. Code spans (literal,
     * never emphasised) and links (URL literal, link text emphasised) are
     * extracted to out-of-band sentinels first so the bold/italic regexes never
     * corrupt a backtick body or a URL containing {@code _}/{@code *}. The
     * sentinel control chars are stripped from the input up front so user
     * content cannot collide with the placeholders.
     */
    private static String inline(String s)
    {
        String escaped = escapeHtml(s)
            .replace(String.valueOf(SENTINEL_OPEN), "") //$NON-NLS-1$
            .replace(String.valueOf(SENTINEL_CLOSE), ""); //$NON-NLS-1$
        List<String> spans = new ArrayList<>();
        // Code spans first - their content stays literal.
        String t = replaceAll(escaped, CODE, m -> {
            spans.add("<code>" + m.group(1) + "</code>"); //$NON-NLS-1$ //$NON-NLS-2$
            return sentinel(spans.size() - 1);
        });
        // Links next - URL literal, link text still emphasised.
        t = replaceAll(t, LINK, m -> {
            spans.add("<a href=\"" + escapeAttr(m.group(2)) + "\">" + emphasis(m.group(1)) + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return sentinel(spans.size() - 1);
        });
        t = emphasis(t);
        // Restore high index first so a span nested in a later-restored span
        // (e.g. a code sentinel inside link text) is itself resolved.
        for (int i = spans.size() - 1; i >= 0; i--)
        {
            t = t.replace(sentinel(i), spans.get(i));
        }
        return t;
    }

    /** Applies bold/italic spans only (code + links are pre-extracted in {@link #inline}). */
    private static String emphasis(String t)
    {
        t = replaceAll(t, BOLD, m -> "<strong>" + m.group(1) + "</strong>"); //$NON-NLS-1$ //$NON-NLS-2$
        t = replaceAll(t, ITALIC_STAR, m -> "<em>" + m.group(1) + "</em>"); //$NON-NLS-1$ //$NON-NLS-2$
        t = replaceAll(t, ITALIC_US, m -> "<em>" + m.group(1) + "</em>"); //$NON-NLS-1$ //$NON-NLS-2$
        return t;
    }

    /**
     * Applies a function-based replacement (the replacement is inserted literally,
     * so user {@code $}/{@code \} in captured groups never trigger group expansion).
     */
    private static String replaceAll(String input, Pattern pattern, Function<Matcher, String> fn)
    {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find())
        {
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String escapeHtml(String s)
    {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    private static String escapeAttr(String s)
    {
        return escapeHtml(s).replace("\"", "&quot;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -- = --
    // Help/<lang>.html file IO (run AFTER the BM commit, never inside it)
    // -- = --

    /** Outcome of a {@code Help/<lang>.html} file write or removal. */
    public static final class FileResult
    {
        public boolean ok;
        public String error;
        public boolean fileWritten;
        public boolean fileRemoved;
        public boolean idempotent;
        public String relPath;
        public int bytes;
    }

    /**
     * Writes the help HTML to {@code src/<dirFolder>/<objectName>/Help/<lang>.html}
     * via a temp-file move and refreshes the workspace folder. Skips the write when
     * the on-disk content is byte-identical (idempotent).
     */
    public static FileResult writeHelpFile(IProject project, String dirFolder, String objectName,
        String lang, String html, boolean dryRun)
    {
        FileResult res = new FileResult();
        res.relPath = "src/" + dirFolder + "/" + objectName + "/Help/" + lang + ".html"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (project.getLocation() == null)
        {
            res.error = "project location is not on the local filesystem"; //$NON-NLS-1$
            return res;
        }
        Path dir = helpDir(project, dirFolder, objectName);
        Path target = dir.resolve(lang + ".html"); //$NON-NLS-1$
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        res.bytes = data.length;
        try
        {
            if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), data))
            {
                res.ok = true;
                res.idempotent = true;
                return res;
            }
        }
        catch (IOException ignored)
        {
            // unreadable existing file - fall through to the write attempt
        }
        if (dryRun)
        {
            res.ok = true;
            return res;
        }
        Path tmp = dir.resolve(lang + ".html.tmp"); //$NON-NLS-1$
        try
        {
            Files.createDirectories(dir);
            Files.write(tmp, data);
            try
            {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException amns)
            {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            res.fileWritten = true;
            res.ok = true;
        }
        catch (IOException ioe)
        {
            res.error = "Failed to write Help/" + lang + ".html: " + ioe.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            return res;
        }
        finally
        {
            // A failed (non-atomic) move can leave the temp file behind.
            try
            {
                Files.deleteIfExists(tmp);
            }
            catch (IOException ignored)
            {
                // best-effort cleanup
            }
        }
        refresh(project, dirFolder, objectName);
        return res;
    }

    /** Deletes {@code Help/<lang>.html} (idempotent when already absent). */
    public static FileResult removeHelpFile(IProject project, String dirFolder, String objectName,
        String lang, boolean dryRun)
    {
        FileResult res = new FileResult();
        res.relPath = "src/" + dirFolder + "/" + objectName + "/Help/" + lang + ".html"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (project.getLocation() == null)
        {
            res.error = "project location is not on the local filesystem"; //$NON-NLS-1$
            return res;
        }
        Path target = helpDir(project, dirFolder, objectName).resolve(lang + ".html"); //$NON-NLS-1$
        if (!Files.exists(target))
        {
            res.ok = true;
            res.idempotent = true;
            return res;
        }
        if (dryRun)
        {
            res.ok = true;
            return res;
        }
        try
        {
            Files.delete(target);
            res.fileRemoved = true;
            res.ok = true;
        }
        catch (IOException ioe)
        {
            res.error = "Failed to delete Help/" + lang + ".html: " + ioe.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            return res;
        }
        refresh(project, dirFolder, objectName);
        return res;
    }

    private static Path helpDir(IProject project, String dirFolder, String objectName)
    {
        return project.getLocation().toFile().toPath()
            .resolve("src").resolve(dirFolder).resolve(objectName).resolve("Help"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void refresh(IProject project, String dirFolder, String objectName)
    {
        try
        {
            IFolder folder = project.getFolder("src").getFolder(dirFolder).getFolder(objectName); //$NON-NLS-1$
            if (folder.exists())
            {
                folder.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
            else
            {
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
        }
        catch (CoreException ce)
        {
            Activator.logWarning("Help file written but workspace refresh failed: " + ce.getMessage()); //$NON-NLS-1$
        }
    }
}
