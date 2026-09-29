/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import ru.aiedt.mcp.server.Activator;

/**
 * 1.42 (B4): validates picture references before they are written to a
 * metadata object or a form element.
 *
 * <p>RSV 4.2 release notes describe the bug this class closes: when a button
 * or command was created with a typo'd or non-existent picture name (e.g.
 * {@code StdPicture.Erase} where the right name is {@code Delete}, or
 * {@code CommonPicture.НесуществующийЛоготип}), the plugin silently dropped
 * the picture and returned {@code success=true}, leaving an icon-less button
 * that nobody noticed until the form was opened. After the fix the operation
 * fails up-front with a clear message.
 *
 * <p>Three reference forms are recognised:
 * <ul>
 *   <li>{@code StdPicture.<Name>} - looked up among the stock pictures of the
 *       project's platform version ({@link StockPictures}), by English or
 *       Russian name</li>
 *   <li>{@code StdExtPicture.<Name>} - the same, among the extended stock
 *       pictures</li>
 *   <li>{@code CommonPicture.<Name>} - looked up against the configuration's
 *       {@code getCommonPictures()} collection</li>
 * </ul>
 */
public final class PictureValidator
{
    private PictureValidator()
    {
    }

    /**
     * Validates a picture reference. Empty or null input is accepted as
     * "no picture requested" and returns {@code null} (no error).
     *
     * @param projectName project that owns the configuration. Required for
     *        {@code CommonPicture.<Name>} references; for a stock picture it
     *        names the platform version checked against, and without it the
     *        newest version is used.
     * @param pictureRef full reference, e.g. {@code StdPicture.Delete},
     *        {@code CommonPicture.MyLogo}, or a bare {@code Delete} (treated
     *        as {@code StdPicture.Delete}).
     * @return {@code null} when the reference is valid; otherwise a
     *         human-readable error message with a hint.
     */
    public static String validate(String projectName, String pictureRef)
    {
        if (pictureRef == null || pictureRef.isEmpty())
        {
            return null;
        }
        int dotIdx = pictureRef.indexOf('.');
        String prefix = (dotIdx > 0) ? pictureRef.substring(0, dotIdx) : "StdPicture"; //$NON-NLS-1$
        String name = (dotIdx > 0) ? pictureRef.substring(dotIdx + 1) : pictureRef;
        if (name.isEmpty())
        {
            return "Picture reference '" + pictureRef + "' is missing a name after the prefix."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        switch (prefix)
        {
            case "StdPicture": //$NON-NLS-1$
            case "StdExtPicture": //$NON-NLS-1$
                if (isValidStockPicture(projectName, prefix, name))
                {
                    return null;
                }
                return "Stock picture '" + pictureRef + "' was not found in the platform " //$NON-NLS-1$ //$NON-NLS-2$
                    + "registry. Either a typo or the picture appeared in a later 1C " //$NON-NLS-1$
                    + "platform version. List the available names via " //$NON-NLS-1$
                    + "edit_metadata operation=list_pictures."; //$NON-NLS-1$
            case "CommonPicture": //$NON-NLS-1$
                if (projectName == null || projectName.isEmpty())
                {
                    return "Cannot validate '" + pictureRef + "' without a projectName " //$NON-NLS-1$ //$NON-NLS-2$
                        + "(needed to look up the configuration's common pictures)."; //$NON-NLS-1$
                }
                if (isValidCommonPicture(projectName, name))
                {
                    return null;
                }
                return "Common picture '" + pictureRef + "' was not found in project '" //$NON-NLS-1$ //$NON-NLS-2$
                    + projectName + "'. Create it via edit_metadata operation=createObject " //$NON-NLS-1$
                    + "objectName=CommonPicture." + name + ", or check the spelling."; //$NON-NLS-1$ //$NON-NLS-2$
            default:
                return "Unsupported picture prefix '" + prefix + "'. Allowed prefixes: " //$NON-NLS-1$ //$NON-NLS-2$
                    + "StdPicture, StdExtPicture, CommonPicture."; //$NON-NLS-1$
        }
    }

    /**
     * Whether a stock picture of the project's platform version answers to a name.
     *
     * @param projectName the project whose version is checked, or <code>null</code> for the newest
     * @param prefix {@link StockPictures#STD} or {@link StockPictures#STD_EXT}
     * @param name the picture name without the prefix, English or Russian
     * @return <code>true</code> when a picture of that prefix answers to the name, and when this
     *         runtime registers no stock pictures at all - the name cannot be checked then, and the
     *         write resolves it itself
     */
    private static boolean isValidStockPicture(String projectName, String prefix, String name)
    {
        java.util.List<StockPictures.Entry> pictures = StockPictures.read(StockPictures.versionOf(projectName));
        if (pictures.isEmpty())
        {
            return true;
        }
        for (StockPictures.Entry picture : pictures)
        {
            if (picture.prefix.equals(prefix) && picture.answersTo(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the configuration of a project has a common picture of a name.
     *
     * @param projectName the project
     * @param name the common picture's name, in any case
     * @return <code>true</code> when the picture is there, and when reading the configuration
     *         throws - the write resolves the name itself then; <code>false</code> for a project or
     *         configuration that is not there
     */
    private static boolean isValidCommonPicture(String projectName, String name)
    {
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists() || !project.isOpen())
            {
                return false;
            }
            Configuration config = Activator.getDefault().getConfigurationProvider()
                .getConfiguration(project);
            return config != null && carriesCommonPicture(config, name);
        }
        catch (Exception e)
        {
            Activator.logWarning("PictureValidator.isValidCommonPicture failed: " //$NON-NLS-1$
                + e.getMessage());
            // Conservative default - avoid blocking the operation when probe fails.
            return true;
        }
    }

    /**
     * Whether a configuration carries a common picture of a name. Metadata names do not tell case
     * apart, so neither does this.
     *
     * @param config the configuration
     * @param name the common picture's name, in any case
     * @return <code>true</code> when a common picture answers to the name
     */
    static boolean carriesCommonPicture(Configuration config, String name)
    {
        for (Object pic : config.getCommonPictures())
        {
            if (pic instanceof MdObject && name.equalsIgnoreCase(((MdObject) pic).getName()))
            {
                return true;
            }
        }
        return false;
    }
}
