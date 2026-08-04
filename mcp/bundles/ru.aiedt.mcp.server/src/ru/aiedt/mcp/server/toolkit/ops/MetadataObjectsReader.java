/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.ToolParamSettings;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ExternalProjectResolver;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Lists a configuration's metadata objects as a markdown table.
 * <p>
 * With no type named, every collection is walked in a fixed order; with one named, just that
 * collection. Each object carries whether it has an object and a manager module, worked out from the
 * feature the object's own kind keeps a module under. An external-object project has no configuration
 * to walk, so its roots are listed directly instead.
 * </p>
 */
public class MetadataObjectsReader
    implements IMcpTool
{
    private static final String ALL = "all"; //$NON-NLS-1$

    private static final String DEFAULT_LANGUAGE = "ru"; //$NON-NLS-1$

    private static final int LIMIT_MIN = 1;

    private static final int LIMIT_MAX = 1000;

    private static final int DEFAULT_LIMIT = 100;

    /** How an object's module presence is worked out, by the kind of object it is. */
    private enum Category
    {
        DB_OBJECT,
        REGISTER,
        COMMON_MODULE,
        ENUM,
        CONSTANT,
        SERVICE,
        GENERIC
    }

    /** One collection to walk: its argument token, its type label, and how to read its modules. */
    private static final Collector[] COLLECTORS = {
        new Collector("documents", "Document", Category.DB_OBJECT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("catalogs", "Catalog", Category.DB_OBJECT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("informationregisters", "InformationRegister", Category.REGISTER), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("accumulationregisters", "AccumulationRegister", Category.REGISTER), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("commonmodules", "CommonModule", Category.COMMON_MODULE), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("enums", "Enum", Category.ENUM), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("constants", "Constant", Category.CONSTANT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("reports", "Report", Category.DB_OBJECT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("dataprocessors", "DataProcessor", Category.DB_OBJECT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("exchangeplans", "ExchangePlan", Category.DB_OBJECT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("businessprocesses", "BusinessProcess", Category.DB_OBJECT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("tasks", "Task", Category.DB_OBJECT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("commonattributes", "CommonAttribute", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("eventsubscriptions", "EventSubscription", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("scheduledjobs", "ScheduledJob", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("httpservices", "HTTPService", Category.SERVICE), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("webservices", "WebService", Category.SERVICE), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("chartsofaccounts", "ChartOfAccounts", Category.DB_OBJECT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("chartsofcharacteristictypes", "ChartOfCharacteristicTypes", Category.DB_OBJECT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("chartsofcalculationtypes", "ChartOfCalculationTypes", Category.DB_OBJECT), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("calculationregisters", "CalculationRegister", Category.REGISTER), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("accountingregisters", "AccountingRegister", Category.REGISTER), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("xdtopackages", "XDTOPackage", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("commonforms", "CommonForm", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("commoncommands", "CommonCommand", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("commontemplates", "CommonTemplate", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("commonpictures", "CommonPicture", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("roles", "Role", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("subsystems", "Subsystem", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("definedtypes", "DefinedType", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("settingsstorages", "SettingsStorage", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("functionaloptions", "FunctionalOption", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("filtercriteria", "FilterCriterion", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("styleitems", "StyleItem", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("languages", "Language", Category.GENERIC), //$NON-NLS-1$ //$NON-NLS-2$
        new Collector("sessionparameters", "SessionParameter", Category.GENERIC) //$NON-NLS-1$ //$NON-NLS-2$
    };

    @Override
    public String getName()
    {
        return "get_metadata_objects"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Lists the metadata objects defined in a 1C configuration. Each row reports Name, Synonym, " //$NON-NLS-1$
            + "Comment, Type, ObjectModule, and ManagerModule for the object. Filtering by metadata type is supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to search (mandatory)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Restricts results to one metadata type: 'all', 'documents', 'catalogs', 'informationRegisters', " //$NON-NLS-1$
                    + "'accumulationRegisters', 'commonModules', 'enums', 'constants', 'reports', " //$NON-NLS-1$
                    + "'dataProcessors', 'exchangePlans', 'businessProcesses', 'tasks', " //$NON-NLS-1$
                    + "'commonAttributes', 'eventSubscriptions', 'scheduledJobs', 'httpServices', " //$NON-NLS-1$
                    + "'webServices', 'chartsOfAccounts', 'chartsOfCharacteristicTypes', " //$NON-NLS-1$
                    + "'chartsOfCalculationTypes', 'calculationRegisters', 'accountingRegisters', " //$NON-NLS-1$
                    + "'xdtoPackages', 'commonForms', 'commonCommands', 'commonTemplates', " //$NON-NLS-1$
                    + "'commonPictures', 'roles', 'subsystems', 'definedTypes', 'settingsStorages', " //$NON-NLS-1$
                    + "'functionalOptions', 'filterCriteria', 'styleItems', 'languages', " //$NON-NLS-1$
                    + "'sessionParameters'. Falls back to 'all' when omitted.") //$NON-NLS-1$
            .stringProperty("nameFilter", "Keeps only objects whose name contains this text (case-insensitive)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Caps the number of rows returned. Defaults to 100 when omitted.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("language", //$NON-NLS-1$
                "ISO language code to select which synonym to show (e.g. 'en', 'ru'). When omitted, the " //$NON-NLS-1$
                    + "configuration's default language is used.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return "metadata-objects.md"; //$NON-NLS-1$
        }
        return "metadata-" + projectName.toLowerCase(Locale.ROOT) + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: the projectName parameter must be provided"; //$NON-NLS-1$
        }

        String metadataTypeArg = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String metadataType = metadataTypeArg == null || metadataTypeArg.isEmpty() ? ALL : metadataTypeArg;
        String nameFilter = JsonUtils.extractStringArgument(params, "nameFilter"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        int configured = ToolParamSettings.getInstance()
            .getParameterValue("get_metadata_objects", "limit", DEFAULT_LIMIT); //$NON-NLS-1$ //$NON-NLS-2$
        int limit = JsonUtils.extractIntArgument(params, "limit", configured); //$NON-NLS-1$
        limit = Math.max(LIMIT_MIN, Math.min(LIMIT_MAX, limit));

        int effectiveLimit = limit;
        try
        {
            return UiSync.call(() ->
                collectAndFormat(projectName, metadataType, nameFilter, effectiveLimit, language));
        }
        catch (Exception e)
        {
            return "Error: " + TextSuggest.safeMessage(e); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the project and collects its objects. Runs on the UI thread.
     *
     * @param projectName the project
     * @param metadataType the type argument, original case
     * @param nameFilter a name fragment to keep, or <code>null</code>/empty for any
     * @param limit the most rows to show
     * @param language the requested synonym language, or <code>null</code>
     * @return the markdown, or an {@code Error:} line
     */
    private static String collectAndFormat(String projectName, String metadataType, String nameFilter,
        int limit, String language)
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
            return "Error: no configuration provider is available"; //$NON-NLS-1$
        }

        if (ExternalProjectResolver.isExternalProject(project))
        {
            String effectiveLanguage =
                language != null && !language.isEmpty() ? language : DEFAULT_LANGUAGE;
            List<MetadataInfo> objects = new ArrayList<>();
            for (MdObject root : ExternalProjectResolver.getExternalObjects(project))
            {
                objects.add(new MetadataInfo(root.getName(), synonymForLanguage(root, effectiveLanguage),
                    comment(root), ExternalProjectResolver.kindOf(root), false, false));
            }
            return formatOutput(objects, projectName, metadataType, limit);
        }

        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null)
        {
            return "Error: unable to resolve the configuration for project: " + projectName; //$NON-NLS-1$
        }
        String effectiveLanguage = effectiveLanguage(language, configuration);

        String typeLower = metadataType.toLowerCase(Locale.ROOT);
        List<MetadataInfo> objects = new ArrayList<>();
        if (ALL.equals(typeLower))
        {
            for (Collector collector : COLLECTORS)
            {
                collectOne(configuration, collector, nameFilter, effectiveLanguage, objects);
            }
        }
        else
        {
            Collector collector = findCollector(typeLower);
            if (collector == null)
            {
                return unknownTypeError(metadataType);
            }
            collectOne(configuration, collector, nameFilter, effectiveLanguage, objects);
        }
        return formatOutput(objects, projectName, metadataType, limit);
    }

    /**
     * Collects one type's objects into the accumulator.
     *
     * @param configuration the configuration
     * @param collector the collection descriptor
     * @param nameFilter a name fragment to keep
     * @param language the synonym language
     * @param result the accumulator
     */
    private static void collectOne(Configuration configuration, Collector collector, String nameFilter,
        String language, List<MetadataInfo> result)
    {
        List<? extends MdObject> objects = MetadataTypeCatalog.getObjects(configuration, collector.typeName);
        if (objects == null)
        {
            return;
        }
        for (MdObject object : objects)
        {
            String name = object.getName();
            if (nameFilter != null && !nameFilter.isEmpty()
                && (name == null
                    || !name.toLowerCase(Locale.ROOT).contains(nameFilter.toLowerCase(Locale.ROOT))))
            {
                continue;
            }
            result.add(new MetadataInfo(name, synonymForLanguage(object, language), comment(object),
                collector.typeName, hasObjectModule(object, collector.category),
                hasManagerModule(object, collector.category)));
        }
    }

    /**
     * @param object the object
     * @param category its kind
     * @return whether the object has an object-level module
     */
    private static boolean hasObjectModule(MdObject object, Category category)
    {
        switch (category)
        {
        case DB_OBJECT:
            return hasFeature(object, "objectModule"); //$NON-NLS-1$
        case REGISTER:
            return hasFeature(object, "recordSetModule"); //$NON-NLS-1$
        case COMMON_MODULE:
        case SERVICE:
            return hasFeature(object, "module"); //$NON-NLS-1$
        case CONSTANT:
            return hasFeature(object, "valueManagerModule"); //$NON-NLS-1$
        default:
            return false;
        }
    }

    /**
     * @param object the object
     * @param category its kind
     * @return whether the object has a manager module
     */
    private static boolean hasManagerModule(MdObject object, Category category)
    {
        switch (category)
        {
        case DB_OBJECT:
        case REGISTER:
        case ENUM:
        case CONSTANT:
            return hasFeature(object, "managerModule"); //$NON-NLS-1$
        default:
            return false;
        }
    }

    /**
     * @param object the object
     * @param featureName an EMF feature name
     * @return whether the object holds a non-null value under that feature
     */
    private static boolean hasFeature(MdObject object, String featureName)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        return feature != null && object.eGet(feature) != null;
    }

    /**
     * Reads an object's synonym, preferring the requested language and falling back to any non-empty
     * entry.
     *
     * @param object the object
     * @param language the requested language
     * @return the synonym, or the empty string
     */
    private static String synonymForLanguage(MdObject object, String language)
    {
        EMap<String, String> synonym = object.getSynonym();
        if (synonym == null || synonym.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, String> entry : synonym.entrySet())
        {
            map.put(entry.getKey(), entry.getValue());
        }
        String requested = map.get(language);
        if (requested != null && !requested.isEmpty())
        {
            return requested;
        }
        for (String value : map.values())
        {
            if (value != null && !value.isEmpty())
            {
                return value;
            }
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * @param object the object
     * @return its comment, or the empty string
     */
    private static String comment(MdObject object)
    {
        String comment = object.getComment();
        return comment != null ? comment : ""; //$NON-NLS-1$
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

    /**
     * @param typeLower the lowercased type token
     * @return the matching collector, or <code>null</code>
     */
    private static Collector findCollector(String typeLower)
    {
        for (Collector collector : COLLECTORS)
        {
            if (collector.token.equals(typeLower))
            {
                return collector;
            }
        }
        return null;
    }

    /**
     * Renders the collected objects.
     *
     * @param objects the objects
     * @param projectName the project, for the heading
     * @param metadataType the type argument, for the filter line
     * @param limit the most rows to show
     * @return the markdown
     */
    private static String formatOutput(List<MetadataInfo> objects, String projectName, String metadataType,
        int limit)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("## Configuration Metadata Overview: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        int total = objects.size();
        int shown = Math.min(total, limit);

        if (!metadataType.equals(ALL))
        {
            builder.append("**Applied filter:** ").append(metadataType).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("**Object count:** ").append(total).append(" objects"); //$NON-NLS-1$ //$NON-NLS-2$
        if (shown < total)
        {
            builder.append(" (displaying ").append(shown).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("\n\n"); //$NON-NLS-1$

        if (objects.isEmpty())
        {
            builder.append("No matching metadata objects were found.\n"); //$NON-NLS-1$
            return builder.toString();
        }

        builder.append("| Object Name | Synonym | Comment | Kind | ObjectModule | ManagerModule |\n"); //$NON-NLS-1$
        builder.append("|------|---------|---------|------|--------------|---------------|\n"); //$NON-NLS-1$
        for (int i = 0; i < shown; i++)
        {
            MetadataInfo object = objects.get(i);
            builder.append("| ").append(object.name) //$NON-NLS-1$
                .append(" | ").append(object.synonym) //$NON-NLS-1$
                .append(" | ").append(object.comment) //$NON-NLS-1$
                .append(" | ").append(object.type) //$NON-NLS-1$
                .append(" | ").append(object.hasObjectModule ? "Yes" : "-") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" | ").append(object.hasManagerModule ? "Yes" : "-") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" |\n"); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /**
     * Builds the message for an unrecognized type token.
     *
     * @param metadataType the type argument, original case
     * @return the error line
     */
    private static String unknownTypeError(String metadataType)
    {
        return "Error: unrecognized metadata type: " + metadataType + ". Accepted values (case-insensitive): all, " //$NON-NLS-1$ //$NON-NLS-2$
            + "documents, catalogs, informationRegisters, accumulationRegisters, commonModules, enums, " //$NON-NLS-1$
            + "constants, reports, dataProcessors, exchangePlans, businessProcesses, tasks, " //$NON-NLS-1$
            + "commonAttributes, eventSubscriptions, scheduledJobs, httpServices, webServices, " //$NON-NLS-1$
            + "chartsOfAccounts, chartsOfCharacteristicTypes, chartsOfCalculationTypes, " //$NON-NLS-1$
            + "calculationRegisters, accountingRegisters, xdtoPackages, commonForms, commonCommands, " //$NON-NLS-1$
            + "commonTemplates, commonPictures, roles, subsystems, definedTypes, settingsStorages, " //$NON-NLS-1$
            + "functionalOptions, filterCriteria, styleItems, languages, sessionParameters"; //$NON-NLS-1$
    }

    /** One object, flattened to the six columns the table shows. */
    private static final class MetadataInfo
    {
        private final String name;

        private final String synonym;

        private final String comment;

        private final String type;

        private final boolean hasObjectModule;

        private final boolean hasManagerModule;

        MetadataInfo(String name, String synonym, String comment, String type, boolean hasObjectModule,
            boolean hasManagerModule)
        {
            this.name = name;
            this.synonym = synonym;
            this.comment = comment;
            this.type = type;
            this.hasObjectModule = hasObjectModule;
            this.hasManagerModule = hasManagerModule;
        }
    }

    /** A collection to walk. */
    private static final class Collector
    {
        private final String token;

        private final String typeName;

        private final Category category;

        Collector(String token, String typeName, Category category)
        {
            this.token = token;
            this.typeName = typeName;
            this.category = category;
        }
    }
}
