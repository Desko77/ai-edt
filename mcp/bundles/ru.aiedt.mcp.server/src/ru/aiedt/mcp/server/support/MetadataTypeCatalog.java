/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

/**
 * What a 1C metadata type is called, in every spelling it answers to.
 * <p>
 * One type wears four names and the agent may use any of them: {@code Catalog}, {@code Catalogs}, and
 * the Russian singular and plural, in any case. Underneath, three more names matter and none of them
 * can be guessed from the others - the canonical English singular that the rest of the plugin speaks,
 * the EMF reference that holds the collection on the configuration ({@code httpServices}, not
 * {@code hTTPServices}), and the directory the objects live in under {@code src/}. This registry is
 * where all of that is written down, and it is the only place that should be.
 * </p>
 * <p>
 * A type with no directory is not an omission. The directory doubles as the answer to "can this type
 * own a module or a form", and roles, subsystems and common pictures cannot - even though they do have
 * folders on disk. Module and form path resolution both gate on the absent directory, so filling one
 * in would quietly hand a caller a path to a file that never existed.
 * </p>
 */
public final class MetadataTypeCatalog
{
    /**
     * The metadata types this plugin knows, and their names.
     * <p>
     * Declaration order is the order the types are reported in, so it is worth keeping readable: the
     * ones a configuration is mostly made of first, the rare ones last.
     * </p>
     * <p>
     * The Russian aliases are written as {@code \}{@code uXXXX} escapes to keep this file pure ASCII.
     * That is deliberate. A Cyrillic alias that got mangled in transit - by an editor that guessed the
     * encoding, by a tool that did not - would not fail to compile: it would compile into a key nobody
     * ever looks up, and every Russian spelling of that type would quietly stop resolving. Four of the
     * aliases mix scripts ({@code HTTP} + Cyrillic, Cyrillic + {@code XDTO}), where a Latin letter
     * standing in for its Cyrillic twin is invisible to the eye but not to the map.
     * </p>
     */
    public enum MetadataTypeInfo
    {
        CATALOG("Catalog", "Catalogs", "catalogs", "Catalogs",
            "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a",
            "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a\u0438"),
        DOCUMENT("Document", "Documents", "documents", "Documents",
            "\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442",
            "\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442\u044b"),
        COMMON_MODULE("CommonModule", "CommonModules", "commonModules", "CommonModules",
            "\u041e\u0431\u0449\u0438\u0439\u041c\u043e\u0434\u0443\u043b\u044c"),
        INFORMATION_REGISTER("InformationRegister", "InformationRegisters", "informationRegisters", "InformationRegisters",
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0421\u0432\u0435\u0434\u0435\u043d\u0438\u0439",
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u044b\u0421\u0432\u0435\u0434\u0435\u043d\u0438\u0439"),
        ACCUMULATION_REGISTER("AccumulationRegister", "AccumulationRegisters", "accumulationRegisters", "AccumulationRegisters",
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u041d\u0430\u043a\u043e\u043f\u043b\u0435\u043d\u0438\u044f",
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u044b\u041d\u0430\u043a\u043e\u043f\u043b\u0435\u043d\u0438\u044f"),
        ENUM("Enum", "Enums", "enums", "Enums",
            "\u041f\u0435\u0440\u0435\u0447\u0438\u0441\u043b\u0435\u043d\u0438\u0435",
            "\u041f\u0435\u0440\u0435\u0447\u0438\u0441\u043b\u0435\u043d\u0438\u044f"),
        REPORT("Report", "Reports", "reports", "Reports",
            "\u041e\u0442\u0447\u0435\u0442", "\u041e\u0442\u0447\u0435\u0442\u044b"),
        DATA_PROCESSOR("DataProcessor", "DataProcessors", "dataProcessors", "DataProcessors",
            "\u041e\u0431\u0440\u0430\u0431\u043e\u0442\u043a\u0430",
            "\u041e\u0431\u0440\u0430\u0431\u043e\u0442\u043a\u0438"),
        EXCHANGE_PLAN("ExchangePlan", "ExchangePlans", "exchangePlans", "ExchangePlans",
            "\u041f\u043b\u0430\u043d\u041e\u0431\u043c\u0435\u043d\u0430",
            "\u041f\u043b\u0430\u043d\u044b\u041e\u0431\u043c\u0435\u043d\u0430"),
        BUSINESS_PROCESS("BusinessProcess", "BusinessProcesses", "businessProcesses", "BusinessProcesses",
            "\u0411\u0438\u0437\u043d\u0435\u0441\u041f\u0440\u043e\u0446\u0435\u0441\u0441",
            "\u0411\u0438\u0437\u043d\u0435\u0441\u041f\u0440\u043e\u0446\u0435\u0441\u0441\u044b"),
        TASK("Task", "Tasks", "tasks", "Tasks",
            "\u0417\u0430\u0434\u0430\u0447\u0430", "\u0417\u0430\u0434\u0430\u0447\u0438"),
        ROLE("Role", "Roles", "roles", null, "\u0420\u043e\u043b\u044c", "\u0420\u043e\u043b\u0438"),
        SUBSYSTEM("Subsystem", "Subsystems", "subsystems", null,
            "\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u0430",
            "\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u044b"),
        COMMON_COMMAND("CommonCommand", "CommonCommands", "commonCommands", "CommonCommands",
            "\u041e\u0431\u0449\u0430\u044f\u041a\u043e\u043c\u0430\u043d\u0434\u0430"),
        COMMON_FORM("CommonForm", "CommonForms", "commonForms", "CommonForms",
            "\u041e\u0431\u0449\u0430\u044f\u0424\u043e\u0440\u043c\u0430"),
        WEB_SERVICE("WebService", "WebServices", "webServices", "WebServices",
            "\u0412\u0435\u0431\u0421\u0435\u0440\u0432\u0438\u0441"),
        HTTP_SERVICE("HTTPService", "HTTPServices", "httpServices", "HTTPServices",
            "HTTP\u0421\u0435\u0440\u0432\u0438\u0441"),
        CONSTANT("Constant", "Constants", "constants", "Constants",
            "\u041a\u043e\u043d\u0441\u0442\u0430\u043d\u0442\u0430",
            "\u041a\u043e\u043d\u0441\u0442\u0430\u043d\u0442\u044b"),
        CHART_OF_CHARACTERISTIC_TYPES("ChartOfCharacteristicTypes", "ChartsOfCharacteristicTypes", "chartsOfCharacteristicTypes", "ChartsOfCharacteristicTypes",
            "\u041f\u043b\u0430\u043d\u0412\u0438\u0434\u043e\u0432\u0425\u0430\u0440\u0430\u043a\u0442\u0435\u0440\u0438\u0441\u0442\u0438\u043a"),
        CHART_OF_ACCOUNTS("ChartOfAccounts", "ChartsOfAccounts", "chartsOfAccounts", "ChartsOfAccounts",
            "\u041f\u043b\u0430\u043d\u0421\u0447\u0435\u0442\u043e\u0432"),
        CHART_OF_CALCULATION_TYPES("ChartOfCalculationTypes", "ChartsOfCalculationTypes", "chartsOfCalculationTypes", "ChartsOfCalculationTypes",
            "\u041f\u043b\u0430\u043d\u0412\u0438\u0434\u043e\u0432\u0420\u0430\u0441\u0447\u0435\u0442\u0430"),
        ACCOUNTING_REGISTER("AccountingRegister", "AccountingRegisters", "accountingRegisters", "AccountingRegisters",
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0411\u0443\u0445\u0433\u0430\u043b\u0442\u0435\u0440\u0438\u0438"),
        CALCULATION_REGISTER("CalculationRegister", "CalculationRegisters", "calculationRegisters", "CalculationRegisters",
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0420\u0430\u0441\u0447\u0435\u0442\u0430"),
        DOCUMENT_JOURNAL("DocumentJournal", "DocumentJournals", "documentJournals", "DocumentJournals",
            "\u0416\u0443\u0440\u043d\u0430\u043b\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442\u043e\u0432"),
        SEQUENCE("Sequence", "Sequences", "sequences", "Sequences",
            "\u041f\u043e\u0441\u043b\u0435\u0434\u043e\u0432\u0430\u0442\u0435\u043b\u044c\u043d\u043e\u0441\u0442\u044c"),
        FILTER_CRITERION("FilterCriterion", "FilterCriteria", "filterCriteria", "FilterCriteria",
            "\u041a\u0440\u0438\u0442\u0435\u0440\u0438\u0439\u041e\u0442\u0431\u043e\u0440\u0430"),
        SETTINGS_STORAGE("SettingsStorage", "SettingsStorages", "settingsStorages", "SettingsStorages",
            "\u0425\u0440\u0430\u043d\u0438\u043b\u0438\u0449\u0435\u041d\u0430\u0441\u0442\u0440\u043e\u0435\u043a"),
        EXTERNAL_DATA_PROCESSOR("ExternalDataProcessor", "ExternalDataProcessors", "externalDataProcessors", "ExternalDataProcessors",
            "\u0412\u043d\u0435\u0448\u043d\u044f\u044f\u041e\u0431\u0440\u0430\u0431\u043e\u0442\u043a\u0430",
            "\u0412\u043d\u0435\u0448\u043d\u0438\u0435\u041e\u0431\u0440\u0430\u0431\u043e\u0442\u043a\u0438"),
        EXTERNAL_REPORT("ExternalReport", "ExternalReports", "externalReports", "ExternalReports",
            "\u0412\u043d\u0435\u0448\u043d\u0438\u0439\u041e\u0442\u0447\u0435\u0442",
            "\u0412\u043d\u0435\u0448\u043d\u0438\u0435\u041e\u0442\u0447\u0435\u0442\u044b"),
        EXTERNAL_DATA_SOURCE("ExternalDataSource", "ExternalDataSources", "externalDataSources", "ExternalDataSources",
            "\u0412\u043d\u0435\u0448\u043d\u0438\u0439\u0418\u0441\u0442\u043e\u0447\u043d\u0438\u043a\u0414\u0430\u043d\u043d\u044b\u0445"),
        COMMON_ATTRIBUTE("CommonAttribute", "CommonAttributes", "commonAttributes", null,
            "\u041e\u0431\u0449\u0438\u0439\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442"),
        EVENT_SUBSCRIPTION("EventSubscription", "EventSubscriptions", "eventSubscriptions", null,
            "\u041f\u043e\u0434\u043f\u0438\u0441\u043a\u0430\u041d\u0430\u0421\u043e\u0431\u044b\u0442\u0438\u0435"),
        SCHEDULED_JOB("ScheduledJob", "ScheduledJobs", "scheduledJobs", null,
            "\u0420\u0435\u0433\u043b\u0430\u043c\u0435\u043d\u0442\u043d\u043e\u0435\u0417\u0430\u0434\u0430\u043d\u0438\u0435"),
        SESSION_PARAMETER("SessionParameter", "SessionParameters", "sessionParameters", null,
            "\u041f\u0430\u0440\u0430\u043c\u0435\u0442\u0440\u0421\u0435\u0430\u043d\u0441\u0430"),
        FUNCTIONAL_OPTION("FunctionalOption", "FunctionalOptions", "functionalOptions", null,
            "\u0424\u0443\u043d\u043a\u0446\u0438\u043e\u043d\u0430\u043b\u044c\u043d\u0430\u044f\u041e\u043f\u0446\u0438\u044f"),
        FUNCTIONAL_OPTIONS_PARAMETER("FunctionalOptionsParameter", "FunctionalOptionsParameters", "functionalOptionsParameters", null,
            "\u041f\u0430\u0440\u0430\u043c\u0435\u0442\u0440\u0424\u0443\u043d\u043a\u0446\u0438\u043e\u043d\u0430\u043b\u044c\u043d\u044b\u0445\u041e\u043f\u0446\u0438\u0439"),
        COMMON_PICTURE("CommonPicture", "CommonPictures", "commonPictures", null,
            "\u041e\u0431\u0449\u0430\u044f\u041a\u0430\u0440\u0442\u0438\u043d\u043a\u0430"),
        STYLE_ITEM("StyleItem", "StyleItems", "styleItems", null,
            "\u042d\u043b\u0435\u043c\u0435\u043d\u0442\u0421\u0442\u0438\u043b\u044f"),
        DEFINED_TYPE("DefinedType", "DefinedTypes", "definedTypes", null,
            "\u041e\u043f\u0440\u0435\u0434\u0435\u043b\u044f\u0435\u043c\u044b\u0439\u0422\u0438\u043f"),
        COMMON_TEMPLATE("CommonTemplate", "CommonTemplates", "commonTemplates", null,
            "\u041e\u0431\u0449\u0438\u0439\u041c\u0430\u043a\u0435\u0442"),
        COMMAND_GROUP("CommandGroup", "CommandGroups", "commandGroups", null,
            "\u0413\u0440\u0443\u043f\u043f\u0430\u041a\u043e\u043c\u0430\u043d\u0434"),
        DOCUMENT_NUMERATOR("DocumentNumerator", "DocumentNumerators", "documentNumerators", null,
            "\u041d\u0443\u043c\u0435\u0440\u0430\u0442\u043e\u0440\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442\u043e\u0432"),
        WS_REFERENCE("WSReference", "WSReferences", "wsReferences", null,
            "WS\u0421\u0441\u044b\u043b\u043a\u0430"),
        XDTO_PACKAGE("XDTOPackage", "XDTOPackages", "xdtoPackages", null,
            "\u041f\u0430\u043a\u0435\u0442XDTO"),
        LANGUAGE("Language", "Languages", "languages", null,
            "\u042f\u0437\u044b\u043a", "\u042f\u0437\u044b\u043a\u0438"),
        STYLE("Style", "Styles", "styles", null,
            "\u0421\u0442\u0438\u043b\u044c", "\u0421\u0442\u0438\u043b\u0438"),
        INTERFACE("Interface", "Interfaces", "interfaces", null,
            "\u0418\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441",
            "\u0418\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441\u044b"),
        INTEGRATION_SERVICE("IntegrationService", "IntegrationServices", "integrationServices", null,
            "\u0421\u0435\u0440\u0432\u0438\u0441\u0418\u043d\u0442\u0435\u0433\u0440\u0430\u0446\u0438\u0438"),
        BOT("Bot", "Bots", "bots", null, "\u0411\u043e\u0442", "\u0411\u043e\u0442\u044b"),
        WEB_SOCKET_CLIENT("WebSocketClient", "WebSocketClients", "webSocketClients", null,
            "WebSocket\u041a\u043b\u0438\u0435\u043d\u0442");

        private final String englishSingular;

        private final String englishPlural;

        private final String configReferenceName;

        private final String directoryName;

        private final String[] russianNames;

        /**
         * @param englishSingular the canonical name, the one everything else normalizes to
         * @param englishPlural the plural, also accepted from callers
         * @param configReferenceName the EMF reference on {@code Configuration} holding the collection
         * @param directoryName the directory under {@code src/}, or <code>null</code> when objects of
         *            this type are not addressed by path
         * @param russianNames the Russian aliases: the singular first, the plural after it when the
         *            type has one. At least one, never <code>null</code>
         */
        MetadataTypeInfo(String englishSingular, String englishPlural, String configReferenceName,
            String directoryName, String... russianNames)
        {
            this.englishSingular = englishSingular;
            this.englishPlural = englishPlural;
            this.configReferenceName = configReferenceName;
            this.directoryName = directoryName;
            this.russianNames = russianNames;
        }

        /**
         * Returns the canonical name of this type.
         *
         * @return the English singular, for example {@code Catalog}, never <code>null</code>
         */
        public String getEnglishSingular()
        {
            return englishSingular;
        }

        /**
         * Returns the plural of the canonical name.
         *
         * @return the English plural, for example {@code Catalogs}, never <code>null</code>
         */
        public String getEnglishPlural()
        {
            return englishPlural;
        }

        /**
         * Returns the name of the EMF reference on {@code Configuration} that holds objects of this
         * type. Hand-written: it does not follow from the plural.
         *
         * @return the reference name, for example {@code catalogs}, never <code>null</code>
         */
        public String getConfigReferenceName()
        {
            return configReferenceName;
        }

        /**
         * Returns the directory objects of this type live in under {@code src/}.
         *
         * @return the directory, for example {@code Catalogs}, or <code>null</code> when objects of this
         *         type are not addressed by path - which is also what tells module and form resolution
         *         to refuse them
         */
        public String getDirectoryName()
        {
            return directoryName;
        }

        /**
         * Returns the Russian names of this type.
         *
         * @return a fresh array holding the singular and, where the type has one, the plural; never
         *         <code>null</code>, never empty
         */
        public String[] getRussianNames()
        {
            return russianNames.clone();
        }
    }

    /** Every name a type answers to, lowercased, to the type. */
    private static final Map<String, MetadataTypeInfo> BY_NAME;

    /** Directory under {@code src/} to the type. Exact case: the keys come from real paths. */
    private static final Map<String, MetadataTypeInfo> BY_DIRECTORY;

    /** The canonical names, in declaration order. */
    private static final Set<String> ALL_ENGLISH_SINGULAR;

    static
    {
        Map<String, MetadataTypeInfo> byName = new HashMap<>();
        Map<String, MetadataTypeInfo> byDirectory = new HashMap<>();
        Set<String> singulars = new LinkedHashSet<>();

        for (MetadataTypeInfo type : MetadataTypeInfo.values())
        {
            byName.put(type.getEnglishSingular().toLowerCase(), type);
            byName.put(type.getEnglishPlural().toLowerCase(), type);
            for (String russianName : type.getRussianNames())
            {
                byName.put(russianName.toLowerCase(), type);
            }
            if (type.getDirectoryName() != null)
            {
                byDirectory.put(type.getDirectoryName(), type);
            }
            singulars.add(type.getEnglishSingular());
        }

        BY_NAME = Collections.unmodifiableMap(byName);
        BY_DIRECTORY = Collections.unmodifiableMap(byDirectory);
        ALL_ENGLISH_SINGULAR = Collections.unmodifiableSet(singulars);
    }

    private MetadataTypeCatalog()
    {
        // utility
    }

    /**
     * Resolves any spelling of a type name to the type.
     *
     * @param typeName the name: English or Russian, singular or plural, in any case; may be
     *            <code>null</code>
     * @return the type, or <code>null</code> when the name is not a metadata type
     */
    public static MetadataTypeInfo resolve(String typeName)
    {
        if (typeName == null || typeName.isEmpty())
        {
            return null;
        }
        return BY_NAME.get(typeName.toLowerCase());
    }

    /**
     * Reduces any spelling of a type name - English or Russian, singular or plural, any case - to the
     * canonical one.
     *
     * @param typeName the name in any recognized spelling; may be <code>null</code>
     * @return the English singular, for example {@code Catalog} for {@code Catalogs}, or
     *         <code>null</code> when the name is not a metadata type
     */
    public static String toEnglishSingular(String typeName)
    {
        MetadataTypeInfo type = resolve(typeName);
        return type == null ? null : type.getEnglishSingular();
    }

    /**
     * Tells whether a name is one of the metadata types.
     *
     * @param name the name to test; may be <code>null</code>
     * @return <code>true</code> when some type answers to it
     */
    public static boolean isMetadataTypeName(String name)
    {
        return resolve(name) != null;
    }

    /**
     * Returns the directory objects of a type live in under {@code src/}.
     *
     * @param typeName the type name in any recognized spelling; may be <code>null</code>
     * @return the directory, or <code>null</code> both for an unknown type and for a known type that is
     *         not addressed by path. Callers rely on the two answering the same: either way there is no
     *         file to go to
     */
    public static String getDirectoryName(String typeName)
    {
        MetadataTypeInfo type = resolve(typeName);
        return type == null ? null : type.getDirectoryName();
    }

    /**
     * Returns the EMF reference that holds objects of a type on the configuration.
     *
     * @param typeName the type name in any recognized spelling; may be <code>null</code>
     * @return the reference name, or <code>null</code> when the name is not a metadata type
     */
    public static String getConfigReferenceName(String typeName)
    {
        MetadataTypeInfo type = resolve(typeName);
        return type == null ? null : type.getConfigReferenceName();
    }

    /**
     * Returns the type stored in a directory - the inverse of {@link #getDirectoryName(String)}.
     * <p>
     * The match is case-sensitive: the argument comes from a real path, where the case is EDT's.
     * </p>
     *
     * @param directoryName the directory under {@code src/}, for example {@code ChartsOfAccounts}; may
     *            be <code>null</code>
     * @return the English singular of the type, or <code>null</code> when no type is stored there
     */
    public static String getTypeByDirectoryName(String directoryName)
    {
        if (directoryName == null || directoryName.isEmpty())
        {
            return null;
        }
        MetadataTypeInfo type = BY_DIRECTORY.get(directoryName);
        return type == null ? null : type.getEnglishSingular();
    }

    /**
     * Returns the canonical name of every metadata type.
     *
     * @return an unmodifiable set in declaration order - it is shown to agents as the list of types a
     *         tool accepts, so the order is read by a person
     */
    public static Set<String> getAllEnglishSingularNames()
    {
        return ALL_ENGLISH_SINGULAR;
    }

    /**
     * Rewrites the type segment of a fully qualified name into its canonical English form, leaving the
     * rest of the name exactly as it came.
     * <p>
     * A Russian or plural type segment becomes the English singular; everything from the first dot on
     * is copied through untouched. The object's own name is data - Cyrillic object names are the norm -
     * and translating it would name something that does not exist.
     * </p>
     *
     * @param fqn the fully qualified name; may be <code>null</code>
     * @return the normalized name; the input unchanged when it has no type segment, or when the type
     *         segment is not a metadata type, or when it is already canonical
     */
    public static String normalizeFqn(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return fqn;
        }
        int dot = fqn.indexOf('.');
        if (dot <= 0)
        {
            return fqn;
        }
        String typePart = fqn.substring(0, dot);
        String rest = fqn.substring(dot);
        String singular = toEnglishSingular(typePart);
        if (singular == null || singular.equals(typePart))
        {
            return fqn;
        }
        return singular + rest;
    }

    /**
     * Returns every spelling of a fully qualified name, lowercased.
     * <p>
     * A configuration is authored in one language and the agent asks in another. This answers with the
     * name as given, the English form and the Russian form, so a caller can match against all three at
     * once - which is how error filtering keeps working on a Russian configuration when the filter was
     * written in English.
     * </p>
     *
     * @param fqn the fully qualified name; may be <code>null</code>
     * @return one to three lowercased spellings, in that order; empty for a <code>null</code> or empty
     *         name, and a single element when the name carries no recognizable type
     */
    public static Set<String> getAllFqnVariants(String fqn)
    {
        Set<String> variants = new LinkedHashSet<>();
        if (fqn == null || fqn.isEmpty())
        {
            return variants;
        }
        variants.add(fqn.toLowerCase());

        int dot = fqn.indexOf('.');
        if (dot <= 0)
        {
            return variants;
        }
        MetadataTypeInfo type = resolve(fqn.substring(0, dot));
        if (type == null)
        {
            return variants;
        }
        String rest = fqn.substring(dot);
        variants.add((type.getEnglishSingular() + rest).toLowerCase());

        String[] russianNames = type.getRussianNames();
        if (russianNames.length > 0)
        {
            // The singular only: a plural never appears in a fully qualified name.
            variants.add((russianNames[0] + rest).toLowerCase());
        }
        return variants;
    }

    /**
     * Returns the objects of one type held by a configuration.
     * <p>
     * The list is the configuration's own, not a copy: callers walk it inside a model read, and the
     * order is the order the configuration keeps.
     * </p>
     *
     * @param config the configuration; may be <code>null</code>
     * @param typeName the type name in any recognized spelling; may be <code>null</code>
     * @return the live collection, or <code>null</code> when there is no configuration, when the type is
     *         not recognized, or when this build of EDT does not hold that collection under the name
     *         this registry expects
     */
    public static List<? extends MdObject> getObjects(Configuration config, String typeName)
    {
        if (config == null)
        {
            return null;
        }
        String referenceName = getConfigReferenceName(typeName);
        if (referenceName == null)
        {
            return null;
        }
        EReference match = findReference(config, referenceName);
        if (match == null)
        {
            return null;
        }
        Object value = config.eGet(match);
        if (!(value instanceof EList))
        {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<? extends MdObject> objects = (List<? extends MdObject>)value;
        return objects;
    }

    /**
     * Finds the configuration's collection by name, exactly if it can and otherwise ignoring case.
     * <p>
     * The names in this registry are written the way the type reads - {@code xdtoPackages} - while the
     * model spells a few of them around an acronym: the XDTO packages are held under
     * {@code xDTOPackages}, because the accessor is {@code getXDTOPackages}. An exact-only match left
     * that collection unreachable, so an XDTO package could be created and then neither listed, nor
     * described, nor deleted - it existed on disk and in the configuration, and every reader answered
     * "no such object".
     * </p>
     * <p>
     * Exact match is tried first and wins, so nothing that resolved before resolves differently now.
     * Two collections differing only in case would be a contradiction in the model itself.
     * </p>
     *
     * @param config the configuration, not <code>null</code>
     * @param referenceName the collection name this registry expects
     * @return the reference, or <code>null</code> when the model has no such collection
     */
    private static EReference findReference(Configuration config, String referenceName)
    {
        EReference insensitive = null;
        for (EReference reference : config.eClass().getEAllReferences())
        {
            String name = reference.getName();
            if (referenceName.equals(name))
            {
                return reference;
            }
            if (insensitive == null && referenceName.equalsIgnoreCase(name))
            {
                insensitive = reference;
            }
        }
        return insensitive;
    }

    /**
     * Finds one object of a type by name.
     * <p>
     * The name is matched ignoring case, because the agent's spelling of an object name is nobody's
     * contract. It is otherwise matched as given: object names are data and are never normalized.
     * </p>
     *
     * @param config the configuration; may be <code>null</code>
     * @param typeName the type name in any recognized spelling; may be <code>null</code>
     * @param objectName the object name; may be <code>null</code>
     * @return the first object of that type with that name, or <code>null</code> when there is none
     */
    public static MdObject findObject(Configuration config, String typeName, String objectName)
    {
        if (objectName == null)
        {
            return null;
        }
        List<? extends MdObject> objects = getObjects(config, typeName);
        if (objects == null)
        {
            return null;
        }
        for (MdObject object : objects)
        {
            if (objectName.equalsIgnoreCase(object.getName()))
            {
                return object;
            }
        }
        return null;
    }

    /**
     * Resolves a (possibly nested) {@code Subsystem.A.Subsystem.B....} FQN against the
     * configuration's subsystem containment tree, case-insensitively. {@link #findObject}
     * only walks the top-level subsystem list and compares names literally, so a nested
     * FQN resolves to null there even though the subsystem exists - this walker mirrors
     * the read path (e.g. {@code get_command_interface}'s navigateSubsystem) so write ops
     * that take an ownerFqn resolve nested subsystems the same way reads do.
     *
     * @param config the configuration; may be <code>null</code>
     * @param fqn a subsystem FQN with at least one name segment (Subsystem.A or deeper)
     * @return the nested subsystem, or <code>null</code> when any segment is missing
     */
    public static Subsystem findNestedSubsystem(Configuration config, String fqn)
    {
        if (config == null || fqn == null)
        {
            return null;
        }
        String[] segs = fqn.split("\\."); //$NON-NLS-1$
        // odd indices carry the names: Subsystem, A, Subsystem, B -> [A, B]
        List<String> names = new ArrayList<>();
        for (int i = 1; i < segs.length; i += 2)
        {
            names.add(segs[i]);
        }
        if (names.isEmpty())
        {
            return null;
        }
        EList<Subsystem> level = config.getSubsystems();
        Subsystem found = null;
        for (String n : names)
        {
            found = null;
            for (Subsystem s : level)
            {
                if (n.equalsIgnoreCase(s.getName()))
                {
                    found = s;
                    break;
                }
            }
            if (found == null)
            {
                return null;
            }
            level = found.getSubsystems();
        }
        return found;
    }

    /**
     * Suggests objects of a type whose names are close to one that was not found.
     * <p>
     * Two passes, and the second only runs when the first came back empty. The first accepts a name
     * that contains the query or is contained by it, which is what catches an abbreviation or an
     * over-long guess. The second falls back to edit distance, with a tolerance that grows with the
     * length of the query - that is the one that recovers a dropped soft sign or a swapped pair in a
     * Cyrillic identifier, where a substring match finds nothing at all.
     * </p>
     *
     * @param config the configuration; may be <code>null</code>
     * @param typeName the type name in any recognized spelling; may be <code>null</code>
     * @param name the name that was looked for; may be <code>null</code>
     * @param maxResults how many suggestions to return at most
     * @return the names of the candidates, closest first when they came from the second pass; never
     *         <code>null</code>, possibly empty
     */
    public static List<String> findSimilarObjects(Configuration config, String typeName, String name, int maxResults)
    {
        List<String> matches = new ArrayList<>();
        if (name == null)
        {
            return matches;
        }
        List<? extends MdObject> objects = getObjects(config, typeName);
        if (objects == null)
        {
            return matches;
        }
        String query = name.toLowerCase();

        for (MdObject object : objects)
        {
            if (matches.size() >= maxResults)
            {
                break;
            }
            String objectName = object.getName();
            if (objectName == null)
            {
                continue;
            }
            String candidate = objectName.toLowerCase();
            if (candidate.contains(query) || query.contains(candidate))
            {
                matches.add(objectName);
            }
        }
        if (!matches.isEmpty())
        {
            return matches;
        }

        int threshold = Math.max(2, name.length() / 5);
        List<ScoredName> scored = new ArrayList<>();
        for (MdObject object : objects)
        {
            String objectName = object.getName();
            if (objectName == null)
            {
                continue;
            }
            int distance = levenshtein(objectName.toLowerCase(), query);
            if (distance <= threshold)
            {
                scored.add(new ScoredName(objectName, distance));
            }
        }
        // A stable sort, so equally close candidates stay in the configuration's own order.
        scored.sort(Comparator.comparingInt(candidate -> candidate.distance));
        for (ScoredName candidate : scored)
        {
            if (matches.size() >= maxResults)
            {
                break;
            }
            matches.add(candidate.name);
        }
        return matches;
    }

    /**
     * Returns the edit distance between two strings: how many single-character insertions, deletions or
     * substitutions turn one into the other.
     * <p>
     * Only the ranking matters to the caller, but the numbers are the real ones.
     * </p>
     *
     * @param a the first string; may be <code>null</code>
     * @param b the second string; may be <code>null</code>
     * @return the distance, or {@link Integer#MAX_VALUE} when either string is <code>null</code> - a
     *         value that sorts last and never passes a threshold
     */
    static int levenshtein(String a, String b)
    {
        if (a == null || b == null)
        {
            return Integer.MAX_VALUE;
        }
        if (a.equals(b))
        {
            return 0;
        }
        if (a.isEmpty())
        {
            return b.length();
        }
        if (b.isEmpty())
        {
            return a.length();
        }

        // Two rows of the matrix are enough: each cell only ever looks at the row above and the cell
        // to its left.
        int width = b.length() + 1;
        int[] previous = new int[width];
        int[] current = new int[width];
        for (int column = 0; column < width; column++)
        {
            previous[column] = column;
        }

        for (int row = 1; row <= a.length(); row++)
        {
            current[0] = row;
            for (int column = 1; column < width; column++)
            {
                int substitution = a.charAt(row - 1) == b.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(Math.min(current[column - 1] + 1, previous[column] + 1),
                    previous[column - 1] + substitution);
            }
            int[] finished = previous;
            previous = current;
            current = finished;
        }
        return previous[width - 1];
    }

    /** A suggestion and how far it is from what was asked for. */
    private static final class ScoredName
    {
        private final String name;

        private final int distance;

        ScoredName(String name, int distance)
        {
            this.name = name;
            this.distance = distance;
        }
    }
}
