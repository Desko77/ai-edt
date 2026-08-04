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
            return "**Error:** No documentation is available for check: " + checkId; //$NON-NLS-1$
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

        String folder = checksFolder();
        if (folder != null && !folder.isEmpty())
        {
            File directory = new File(folder);
            if (directory.isDirectory() && (new File(directory, checkId + MD_SUFFIX).isFile()
                || new File(directory, checkId.toLowerCase() + MD_SUFFIX).isFile()))
            {
                return true;
            }
        }

        Bundle bundle = bundle();
        return bundle != null && (bundle.getEntry(BUNDLE_CHECKS_PREFIX + checkId + MD_SUFFIX) != null
            || bundle.getEntry(BUNDLE_CHECKS_PREFIX + checkId.toLowerCase() + MD_SUFFIX) != null);
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
        File exact = new File(directory, checkId + MD_SUFFIX);
        if (exact.isFile())
        {
            return readUtf8(exact);
        }
        File lower = new File(directory, checkId.toLowerCase() + MD_SUFFIX);
        if (lower.isFile())
        {
            return readUtf8(lower);
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
        String exact = readBundleResource(BUNDLE_CHECKS_PREFIX + checkId + MD_SUFFIX);
        if (exact != null)
        {
            return exact;
        }
        return readBundleResource(BUNDLE_CHECKS_PREFIX + checkId.toLowerCase() + MD_SUFFIX);
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
