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
import com._1c.g5.v8.dt.metadata.mdclass.Language;

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
        return codeOfDefault(config);
    }

    /**
     * The default language code of a configuration already in hand.
     *
     * @param configuration the configuration; may be <code>null</code>
     * @return the code, or {@link #FALLBACK} when the configuration names no default language
     */
    public static String codeOfDefault(Configuration configuration)
    {
        if (configuration == null || configuration.getDefaultLanguage() == null)
        {
            return FALLBACK;
        }
        String code = configuration.getDefaultLanguage().getLanguageCode();
        if (code != null && !code.isEmpty())
        {
            return code;
        }
        String name = configuration.getDefaultLanguage().getName();
        return name != null && !name.isEmpty() ? name : FALLBACK;
    }

    /**
     * Resolves a caller-supplied language to the code a synonym map is keyed by.
     * <p>
     * The synonym {@code EMap} holds its entries under language CODES ({@code ru}, {@code en}),
     * while a caller - or the configuration's own default language object - may hand out the
     * language NAME ({@code Русский}). A lookup by name misses the entry and falls through to
     * "whatever synonym is first", so the name is translated through the configuration's language
     * list before any lookup happens. A value that is neither a known code nor a known name is
     * returned unchanged: it may be a code of a language this configuration does not declare, and
     * the empty-synonym fallback of the reader is the honest answer for it.
     * </p>
     *
     * @param requested the requested language, code or name; may be <code>null</code> or empty
     * @param configuration the configuration whose languages translate a name; may be
     *            <code>null</code>
     * @return the language code to look synonyms up by, never <code>null</code>
     */
    public static String resolve(String requested, Configuration configuration)
    {
        if (requested == null || requested.isEmpty())
        {
            return codeOfDefault(configuration);
        }
        if (configuration != null)
        {
            for (Language language : configuration.getLanguages())
            {
                if (language == null)
                {
                    continue;
                }
                String code = language.getLanguageCode();
                String canonical = code != null && !code.isEmpty() ? code : null;
                if (requested.equals(code) || (canonical != null && requested.equalsIgnoreCase(canonical)))
                {
                    return canonical != null ? canonical : requested;
                }
                String name = language.getName();
                if (name != null && name.equalsIgnoreCase(requested))
                {
                    return canonical != null ? canonical : name;
                }
            }
        }
        return requested;
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
