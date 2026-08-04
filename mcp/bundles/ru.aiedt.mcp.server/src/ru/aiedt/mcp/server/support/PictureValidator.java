/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;

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
 *   <li>{@code StdPicture.<Name>} - looked up against the EDT
 *       {@code StandardPictures} reflection registry</li>
 *   <li>{@code StdExtPicture.<Name>} - same registry, extension namespace</li>
 *   <li>{@code CommonPicture.<Name>} - looked up against the configuration's
 *       {@code getCommonPictures()} collection</li>
 * </ul>
 */
public final class PictureValidator
{
    private static final String[] STOCK_REGISTRY_CLASSES = {
        "com._1c.g5.v8.dt.platform.pictures.StandardPictures", //$NON-NLS-1$
        "com._1c.g5.v8.dt.platform.pictures.PlatformPictures", //$NON-NLS-1$
        "com._1c.g5.v8.dt.ui.platform.PlatformPictures" //$NON-NLS-1$
    };

    private PictureValidator()
    {
    }

    /**
     * Validates a picture reference. Empty or null input is accepted as
     * "no picture requested" and returns {@code null} (no error).
     *
     * @param projectName project that owns the configuration. Required only
     *        for {@code CommonPicture.<Name>} references; may be {@code null}
     *        otherwise.
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
                if (isValidStockPicture(name))
                {
                    return null;
                }
                return "Stock picture '" + pictureRef + "' was not found in the platform " //$NON-NLS-1$ //$NON-NLS-2$
                    + "registry. Either a typo or the picture appeared in a later 1C " //$NON-NLS-1$
                    + "platform version. List the available names via " //$NON-NLS-1$
                    + "edit_metadata operation=listPictures."; //$NON-NLS-1$
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

    private static boolean isValidStockPicture(String name)
    {
        for (String cls : STOCK_REGISTRY_CLASSES)
        {
            try
            {
                Class<?> clazz = Class.forName(cls);
                for (Field f : clazz.getDeclaredFields())
                {
                    if (Modifier.isStatic(f.getModifiers())
                        && Modifier.isPublic(f.getModifiers())
                        && name.equals(f.getName()))
                    {
                        return true;
                    }
                }
                // Class found but field absent - registry exists, name is wrong.
                return false;
            }
            catch (ClassNotFoundException ignored)
            {
                // Try next candidate class.
            }
        }
        // No stock registry class on this EDT runtime - cannot validate.
        // Conservative default: accept the name to avoid false rejections.
        return true;
    }

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
            if (config == null)
            {
                return false;
            }
            EList<?> pictures = (EList<?>) config.getClass()
                .getMethod("getCommonPictures").invoke(config); //$NON-NLS-1$
            for (Object pic : pictures)
            {
                if (pic instanceof MdObject
                    && name.equals(((MdObject) pic).getName()))
                {
                    return true;
                }
            }
            return false;
        }
        catch (Exception e)
        {
            Activator.logWarning("PictureValidator.isValidCommonPicture failed: " //$NON-NLS-1$
                + e.getMessage());
            // Conservative default - avoid blocking the operation when probe fails.
            return true;
        }
    }
}
