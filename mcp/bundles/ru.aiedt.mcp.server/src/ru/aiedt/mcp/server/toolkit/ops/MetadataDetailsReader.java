/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.mdreport.MetadataFormatterHub;
import ru.aiedt.mcp.server.support.ExternalProjectResolver;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Describes named metadata objects in detail, one section each.
 * <p>
 * The object descriptions are the formatter's job; this tool is the plumbing around it - resolving
 * the project, reading the model on the UI thread, splitting each FQN into a type and a name, and
 * choosing the synonym language. An external-object project has no configuration, so its objects are
 * resolved straight from the project model instead.
 * </p>
 */
public class MetadataDetailsReader
    implements IMcpTool
{
    private static final String DEFAULT_LANGUAGE = "ru"; //$NON-NLS-1$

    private static final String SECTION_SEPARATOR = "\n---\n\n"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return "get_metadata_details"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Get the properties of one or more metadata objects from a 1C configuration. Returns a " //$NON-NLS-1$
            + "basic summary by default, or every property when 'full: true' is passed. For a one-call " //$NON-NLS-1$
            + "bundle of details + modules + structure use ai_context; for details + references + " //$NON-NLS-1$
            + "errors use object_summary."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("objectFqns", //$NON-NLS-1$
                "Array of FQNs (e.g. ['Catalog.Products', 'Document.SalesOrder']). Russian type names " //$NON-NLS-1$
                    + "work too (e.g. '\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u041D\u043E\u043C\u0435\u043D\u043A\u043B\u0430\u0442\u0443\u0440\u0430'). Required.", true) //$NON-NLS-1$
            .booleanProperty("full", //$NON-NLS-1$
                "true returns every property, false returns just the key facts. Default: false") //$NON-NLS-1$
            .booleanProperty("outline", //$NON-NLS-1$
                "true answers with the object's section map alone - every section against how many rows " //$NON-NLS-1$
                    + "it holds - and no content. Ask this first about a large object, then pull the one " //$NON-NLS-1$
                    + "section you need with 'sections'. Default: false") //$NON-NLS-1$
            .stringArrayProperty("sections", //$NON-NLS-1$
                "Return only these sections, e.g. ['Attributes'] or ['Tabular Sections','Forms']. Case, " //$NON-NLS-1$
                    + "spaces, hyphens and underscores are ignored, so 'tabularSections' also works. " //$NON-NLS-1$
                    + "A name the object has no section for is reported, together with the names it does " //$NON-NLS-1$
                    + "have. Omit for the whole object.", false) //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Language code for synonyms (e.g. 'en', 'ru'). Falls back to the configuration's " //$NON-NLS-1$
                    + "default when omitted.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return "metadata-details.md"; //$NON-NLS-1$
        }
        return "metadata-details-" + projectName.toLowerCase(Locale.ROOT) + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName parameter is required"; //$NON-NLS-1$
        }

        List<String> objectFqns = JsonUtils.extractArrayArgument(params, "objectFqns"); //$NON-NLS-1$
        if (objectFqns == null || objectFqns.isEmpty())
        {
            return "Error: objectFqns is required - an array of FQNs like 'Catalog.Products'"; //$NON-NLS-1$
        }

        boolean full = "true".equalsIgnoreCase(JsonUtils.extractStringArgument(params, "full")); //$NON-NLS-1$ //$NON-NLS-2$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        List<String> requested = JsonUtils.extractArrayArgument(params, "sections"); //$NON-NLS-1$
        Set<String> sections = requested == null || requested.isEmpty() ? null
            : new LinkedHashSet<>(requested);
        boolean outline = JsonUtils.extractBooleanArgument(params, "outline", false); //$NON-NLS-1$
        View view = new View(sections, outline);

        try
        {
            return UiSync.call(() -> describe(projectName, objectFqns, full, language, view));
        }
        catch (Exception e)
        {
            return "Error: " + TextSuggest.safeMessage(e); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the project and describes each object. Runs on the UI thread.
     *
     * @param projectName the project
     * @param objectFqns the FQNs to describe
     * @param full whether to dump every property
     * @param language the requested synonym language, or <code>null</code>
     * @param view which sections to write, and whether to write only their names and sizes
     * @return the markdown, or an {@code Error:} line
     */
    private static String describe(String projectName, List<String> objectFqns, boolean full,
        String language, View view)
    {
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        Activator activator = Activator.getDefault();
        IConfigurationProvider configurationProvider =
            activator == null ? null : activator.getConfigurationProvider();
        if (configurationProvider == null)
        {
            return "Error: no configuration provider available"; //$NON-NLS-1$
        }

        if (ExternalProjectResolver.isExternalProject(project))
        {
            return describeExternal(project, projectName, objectFqns, full, language, view);
        }

        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null)
        {
            return "Error: unable to load configuration for project: " + projectName; //$NON-NLS-1$
        }
        String effectiveLanguage = effectiveLanguage(language, configuration);

        StringBuilder builder = new StringBuilder();
        builder.append("# Metadata Object Details: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String fqn : objectFqns)
        {
            builder.append(formatObjectDetails(configuration, fqn, full, effectiveLanguage, view));
            builder.append(SECTION_SEPARATOR);
        }
        return builder.toString();
    }

    /**
     * How much of an object the caller wants.
     * <p>
     * Carried as one value rather than two parameters because it travels through every describe method
     * unchanged, and a pair of unrelated-looking arguments threaded through four signatures is how they
     * end up passed in the wrong order.
     * </p>
     */
    private static final class View
    {
        /** The sections asked for, or <code>null</code> for all of them. */
        final Set<String> sections;

        /** Whether only the section map was asked for. */
        final boolean outline;

        View(Set<String> sections, boolean outline)
        {
            this.sections = sections;
            this.outline = outline;
        }
    }

    /**
     * Describes each object of an external-object project, straight from the project model.
     *
     * @param project the project
     * @param projectName the project name, for the heading
     * @param objectFqns the FQNs to describe
     * @param full whether to dump every property
     * @param language the requested synonym language, or <code>null</code>
     * @param view which sections to write, and whether to write only their names and sizes
     * @return the markdown
     */
    private static String describeExternal(IProject project, String projectName, List<String> objectFqns,
        boolean full, String language, View view)
    {
        String effectiveLanguage = language != null && !language.isEmpty() ? language : DEFAULT_LANGUAGE;
        String rootFqn = ExternalProjectResolver.getRootFqn(project);

        StringBuilder builder = new StringBuilder();
        builder.append("# Metadata Object Details: ").append(projectName).append(" (external)\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String fqn : objectFqns)
        {
            String normalized = MetadataTypeCatalog.normalizeFqn(fqn);
            MdObject object = ExternalProjectResolver.resolveByFqn(project, normalized);
            if (object == null && rootFqn != null && !rootFqn.equals(normalized))
            {
                object = ExternalProjectResolver.resolveByFqn(project, rootFqn);
            }
            if (object == null)
            {
                builder.append("**Error:** no such object: ").append(fqn).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                builder.append(MetadataFormatterHub.format(object, full, effectiveLanguage,
                    view.sections, view.outline));
            }
            builder.append(SECTION_SEPARATOR);
        }
        return builder.toString();
    }

    /**
     * Describes one object of a configuration.
     *
     * @param configuration the configuration
     * @param fqn the fully qualified name
     * @param full whether to dump every property
     * @param language the synonym language
     * @param view which sections to write, and whether to write only their names and sizes
     * @return the object's markdown, or a {@code **Error:**} line
     */
    private static String formatObjectDetails(Configuration configuration, String fqn, boolean full,
        String language, View view)
    {
        // The configuration root is an MdObject like any other, but it lives in no
        // collection, so the Type.Name lookup below could never find it and the answer
        // was "no such object" for the one object every project certainly has. Asking
        // the model about the root - its name, synonym, default language, mobile and
        // interface properties - meant reading the .mdo by hand instead.
        String rootAnswer = configurationRootDetails(configuration, fqn, full, language, view);
        if (rootAnswer != null)
        {
            return rootAnswer;
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return "**Error:** invalid FQN: " + fqn //$NON-NLS-1$
                + ". Expected the form Type.Name (e.g. Catalog.Products)\n"; //$NON-NLS-1$
        }
        String type = parts[0];
        String name = parts[1];
        String normalizedType = MetadataTypeCatalog.toEnglishSingular(type);
        if (normalizedType != null)
        {
            type = normalizedType;
        }
        MdObject object = MetadataTypeCatalog.findObject(configuration, type, name);
        if (object == null)
        {
            return "**Error:** no such object: " + fqn + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return MetadataFormatterHub.format(object, full, language, view.sections, view.outline);
    }

    /**
     * Answers for the configuration root, which no collection holds.
     * <p>
     * Accepts {@code Configuration} on its own and {@code Configuration.<name>}, in
     * English or Russian. A name that does not match is refused by NAME rather than
     * silently formatting the only configuration there is - the caller who spelled it
     * wrong is otherwise told about an object they did not ask for.
     * </p>
     *
     * @param configuration the project's configuration (non-null)
     * @param fqn the requested FQN
     * @param full whether the caller asked for the full view
     * @param language the requested language, or <code>null</code>
     * @param view the requested sections
     * @return the formatted root, an error naming the mismatch, or <code>null</code> when
     *         {@code fqn} does not address the root at all
     */
    /**
     * Reads an FQN as a request for the configuration root.
     *
     * @param fqn the requested FQN (nullable)
     * @return the name asked for, {@code ""} when the root was addressed without one, or
     *         <code>null</code> when this FQN is about some other object
     */
    static String rootRequestName(String fqn)
    {
        if (fqn == null)
        {
            return null;
        }
        String trimmed = fqn.trim();
        int dot = trimmed.indexOf('.');
        String head = dot < 0 ? trimmed : trimmed.substring(0, dot);
        if (!"Configuration".equalsIgnoreCase(head) && !"Конфигурация".equalsIgnoreCase(head)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        return dot < 0 ? "" : trimmed.substring(dot + 1).trim(); //$NON-NLS-1$
    }

    private static String configurationRootDetails(Configuration configuration, String fqn,
        boolean full, String language, View view)
    {
        String asked = rootRequestName(fqn);
        if (asked == null)
        {
            return null;
        }
        String actual = configuration.getName();
        // Case-insensitive like every other FQN lookup here - the collection search matches
        // object names with equalsIgnoreCase, and a root that alone insisted on exact case
        // would be a rule nobody could guess from the other objects.
        if (!asked.isEmpty() && !asked.equalsIgnoreCase(actual))
        {
            return "**Error:** no such configuration: " + asked //$NON-NLS-1$
                + ". This project's configuration is named " + actual //$NON-NLS-1$
                + " - ask for Configuration." + actual + " or just Configuration.\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return MetadataFormatterHub.format(configuration, full, language, view.sections, view.outline);
    }

    /**
     * @param language the requested language, or <code>null</code>
     * @param configuration the configuration, for its default language
     * @return the language to prefer for synonyms
     */
    private static String effectiveLanguage(String language, Configuration configuration)
    {
        if (language != null && !language.isEmpty())
        {
            return language;
        }
        Language defaultLanguage = configuration.getDefaultLanguage();
        return defaultLanguage != null ? defaultLanguage.getName() : DEFAULT_LANGUAGE;
    }
}
