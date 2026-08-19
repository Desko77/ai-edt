/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.osgi.framework.Bundle;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Serves the markdown that documents an EDT validation check.
 * <p>
 * A user-configured folder is consulted first and the descriptions shipped inside the plugin jar
 * second, each tried at the exact id and then at its lowercase form. The id is confined to a small
 * safe alphabet before either lookup, so a value carrying a path separator resolves to nothing rather
 * than escaping the folder it belongs in.
 * </p>
 */
public class CheckDocReader
    implements IMcpTool
{
    private static final String MD_SUFFIX = ".md"; //$NON-NLS-1$

    /** What EDT adds to a check's id when naming the file that describes it. */
    private static final String CHECK_SUFFIX = "-check"; //$NON-NLS-1$

    private static final String BUNDLE_CHECKS_PREFIX = "checks/"; //$NON-NLS-1$

    private static final String SAFE_ID = "[^a-zA-Z0-9_-]"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return "get_check_description"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `diagnostics` `operation=get_check_description`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Fetches the write-up for one EDT check, given its id: what it flags, sample " //$NON-NLS-1$
            + "violations, and the recommended fix, returned as markdown."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("checkId", //$NON-NLS-1$
                "Identifier of the check, e.g. 'begin-transaction' or 'ql-temp-table-index'", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        if (checkId == null || checkId.isEmpty())
        {
            return "get_check_description.md"; //$NON-NLS-1$
        }
        return checkId + MD_SUFFIX;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        return getCheckDescription(checkId);
    }

    /**
     * Returns the markdown for a check.
     * <p>
     * Public and static so other tools - get_project_errors among them - can offer the same
     * documentation. Runs on the calling thread.
     * </p>
     *
     * @param checkId the check id
     * @return the markdown, or a {@code **Error:**} line when the id is missing, unsafe or unknown
     */
    public static String getCheckDescription(String checkId)
    {
        if (checkId == null || checkId.isEmpty())
        {
            return "**Error:** checkId must be supplied"; //$NON-NLS-1$
        }
        try
        {
            String external = readExternalDescription(checkId);
            if (external != null)
            {
                return external;
            }
            String bundled = readBundledDescription(checkId);
            if (bundled != null)
            {
                return bundled;
            }
            // Naming what was tried, because the alternative wording - "no documentation" - reads
            // as "this check has none" when it often means "it is filed under a name nobody
            // guessed". EDT's own legacy and Xtext diagnostics genuinely ship none, and the
            // caller can tell the two apart from this line.
            return "**Error:** No documentation is available for check: " + checkId //$NON-NLS-1$
                + "\n\nLooked for: " + String.join(", ", candidateNames(checkId)) //$NON-NLS-1$ //$NON-NLS-2$
                + ". EDT's own legacy checks (`*-legacy-*`) and Xtext syntax diagnostics ship no " //$NON-NLS-1$
                + "description of any kind."; //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return "**Error:** Could not read the check description: " + e.getMessage(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Tells whether a check has documentation, in the user folder or in the jar.
     *
     * @param checkId the check id
     * @return <code>true</code> when a description file exists for it
     */
    public static boolean hasCheckDocumentation(String checkId)
    {
        if (checkId == null || checkId.isEmpty() || !isSafe(checkId))
        {
            return false;
        }

        // The same resolution the reader uses, and that matters more than it looks: this answers
        // the Docs column of a findings table, and an agent reads that column to decide whether
        // asking is worth a call. Saying false over a description that exists is the same lie as
        // failing to find it, told one step earlier.
        java.util.List<String> names = candidateNames(checkId);
        String folder = checksFolder();
        if (folder != null && !folder.isEmpty())
        {
            File directory = new File(folder);
            if (directory.isDirectory())
            {
                for (String name : names)
                {
                    if (new File(directory, name + MD_SUFFIX).isFile())
                    {
                        return true;
                    }
                }
            }
        }

        Bundle bundle = bundle();
        if (bundle == null)
        {
            return false;
        }
        for (String name : names)
        {
            if (bundle.getEntry(BUNDLE_CHECKS_PREFIX + name + MD_SUFFIX) != null)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The file names one check id might be stored under, in the order they are tried.
     * <p>
     * A marker reports {@code module-unused-method}; the description sits in
     * {@code module-unused-method-check.md}. Both names come from EDT - the first is the id the
     * check registers under, the second is what the shipped description file is called - and until
     * now only the first was tried, so an agent reading a finding could not reach its explanation.
     * </p>
     * <p>
     * Counted against a real project on 2026-08-19: of 51 check ids actually firing, 7 resolved by
     * exact name and 24 more resolve once the suffix is tried. The remaining 20 are EDT's own
     * legacy and Xtext diagnostics, which ship no description of any kind - a gap this cannot
     * close, and does not pretend to.
     * </p>
     * <p>
     * Exact first, always. One name exists on disk both with and without the suffix
     * ({@code data-composition-conditional-appearance-use-check}), so appending before looking
     * would answer one check with the other's text.
     * </p>
     *
     * @param checkId the id as the caller gave it
     * @return the candidate base names, without extension, most likely first
     */
    static java.util.List<String> candidateNames(String checkId)
    {
        java.util.List<String> names = new java.util.ArrayList<>(4);
        names.add(checkId);
        // ROOT, not the default locale. In a Turkish locale toLowerCase maps ASCII 'I' to a
        // dotless 'i', and a check id containing one stops resolving on that machine alone.
        String lower = checkId.toLowerCase(java.util.Locale.ROOT);
        if (!lower.equals(checkId))
        {
            names.add(lower);
        }
        if (!checkId.endsWith(CHECK_SUFFIX))
        {
            names.add(checkId + CHECK_SUFFIX);
            if (!lower.equals(checkId))
            {
                names.add(lower + CHECK_SUFFIX);
            }
        }
        return names;
    }

    /**
     * Reads a description from the user-configured folder.
     *
     * @param checkId the check id
     * @return the markdown, or <code>null</code> when the folder is unset, missing, unsafe or has no
     *         matching file
     * @throws IOException if a matching file cannot be read
     */
    private static String readExternalDescription(String checkId) throws IOException
    {
        String folder = checksFolder();
        if (folder == null || folder.isEmpty())
        {
            return null;
        }
        File directory = new File(folder);
        if (!directory.isDirectory() || !isSafe(checkId))
        {
            return null;
        }
        for (String name : candidateNames(checkId))
        {
            File candidate = new File(directory, name + MD_SUFFIX);
            if (candidate.isFile())
            {
                return readUtf8(candidate);
            }
        }
        return null;
    }

    /**
     * Reads a description from the descriptions bundled in the jar.
     *
     * @param checkId the check id
     * @return the markdown, or <code>null</code> when the id is unsafe or the jar has no matching entry
     * @throws IOException if a matching entry cannot be read
     */
    private static String readBundledDescription(String checkId) throws IOException
    {
        if (!isSafe(checkId))
        {
            return null;
        }
        for (String name : candidateNames(checkId))
        {
            String found = readBundleResource(BUNDLE_CHECKS_PREFIX + name + MD_SUFFIX);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    /**
     * Reads a bundle entry as UTF-8 text.
     *
     * @param path the bundle-relative path
     * @return the text, or <code>null</code> when there is no bundle or no such entry
     * @throws IOException if the entry cannot be read
     */
    private static String readBundleResource(String path) throws IOException
    {
        Bundle bundle = bundle();
        if (bundle == null)
        {
            return null;
        }
        URL url = bundle.getEntry(path);
        if (url == null)
        {
            return null;
        }
        try (InputStream stream = url.openStream())
        {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads a file as UTF-8 text.
     *
     * @param file the file
     * @return the text
     * @throws IOException if the file cannot be read
     */
    private static String readUtf8(File file) throws IOException
    {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * @param checkId the check id
     * @return whether the id survives sanitizing unchanged - the guard against path traversal
     */
    private static boolean isSafe(String checkId)
    {
        return checkId.equals(checkId.replaceAll(SAFE_ID, "")); //$NON-NLS-1$
    }

    /**
     * @return the user-configured checks folder, or <code>null</code> when the plugin is not running
     */
    private static String checksFolder()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return null;
        }
        IPreferenceStore store = activator.getPreferenceStore();
        return store == null ? null : store.getString(PrefKeys.PREF_CHECKS_FOLDER);
    }

    /**
     * @return this plugin's bundle, or <code>null</code> when the plugin is not running
     */
    private static Bundle bundle()
    {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getBundle();
    }
}
