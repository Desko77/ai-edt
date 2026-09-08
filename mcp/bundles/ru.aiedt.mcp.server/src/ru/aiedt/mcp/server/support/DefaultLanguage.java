/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

import ru.aiedt.mcp.server.Activator;

/**
 * The language a localized string belongs to when the caller names none.
 * <p>
 * Asked of the configuration rather than assumed: a synonym written under {@code ru} into a
 * configuration whose language is something else is stored where nothing reads it, and the object
 * then shows an empty synonym while the write reported success.
 * </p>
 * <p>
 * {@code ru} remains the answer when the configuration cannot be reached at all, which is what this
 * did everywhere before it could ask.
 * </p>
 */
public final class DefaultLanguage
{
    /** What a configuration that cannot be asked is treated as. */
    public static final String FALLBACK = "ru"; //$NON-NLS-1$

    private DefaultLanguage()
    {
        // Read through the static entry points.
    }

    /**
     * The default language code of a project's configuration.
     *
     * @param project the project; may be <code>null</code>.
     * @return the code, or {@link #FALLBACK} when there is none
     */
    public static String codeFor(IProject project)
    {
        if (project == null)
        {
            return FALLBACK;
        }
        Activator activator = Activator.getDefault();
        IConfigurationProvider provider = activator == null ? null : activator.getConfigurationProvider();
        Configuration config = provider == null ? null : provider.getConfiguration(project);
        if (config == null || config.getDefaultLanguage() == null)
        {
            return FALLBACK;
        }
        String code = config.getDefaultLanguage().getLanguageCode();
        if (code != null && !code.isEmpty())
        {
            return code;
        }
        String name = config.getDefaultLanguage().getName();
        return name != null && !name.isEmpty() ? name : FALLBACK;
    }

    /**
     * The default language code of whatever configuration holds this object.
     *
     * @param object the model object; may be <code>null</code>.
     * @return the code, or {@link #FALLBACK} when the object leads to no project
     */
    public static String codeFor(EObject object)
    {
        return codeFor(projectOf(object));
    }

    /**
     * The project an object belongs to.
     *
     * @param object the model object; may be <code>null</code>.
     * @return the project, or <code>null</code>
     */
    private static IProject projectOf(EObject object)
    {
        if (object == null)
        {
            return null;
        }
        try
        {
            Activator activator = Activator.getDefault();
            IV8ProjectManager manager = activator == null ? null : activator.getV8ProjectManager();
            IV8Project v8Project = manager == null ? null : manager.getProject(object);
            return v8Project == null ? null : v8Project.getProject();
        }
        catch (RuntimeException unreachable)
        {
            // An object outside any project - a detached one, or one from a model this manager does
            // not own. The fallback then applies, which is what happened before this asked at all.
            Activator.logDebug("no project for object: " + unreachable); //$NON-NLS-1$
            return null;
        }
    }
}
