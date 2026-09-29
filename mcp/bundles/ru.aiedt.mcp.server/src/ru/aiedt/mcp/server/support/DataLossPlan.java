/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;

/**
 * What an update would delete, worked out before the update is started.
 *
 * <p>The infobase holds the state its last synchronization left behind, and EDT records that state
 * in the synchronization baseline: the per-infobase {@code ConfigDumpInfo.xml} in
 * {@code ib-sync/ss}, one {@code <Metadata name="Catalog.Валюты" id="1d6b8425-...">} per entity the
 * base was loaded with, one per attribute, tabular section, dimension or resource under it. The
 * {@code id} is the entity's {@code uuid} in the model. So a record of a data-carrying kind whose
 * {@code id} appears nowhere in the model is an entity the base has and the configuration does not:
 * the next restructure drops the table behind it. A rename keeps the {@code uuid} and is therefore
 * not a deletion, which is why the comparison is by id and never by name.</p>
 *
 * <p><b>Read from the file, not from the question the platform asks.</b> The platform's own
 * confirmation window names a changed object and nothing under it: measured 29.09, deleting an
 * attribute showed as {@code Объект изменен: Справочник.Проба} with no nested node
 * and no marker of what was lost. The baseline, by contrast, records every entity the base holds
 * before anything runs - which is why the answer can name {@code Catalog.X.Attribute.Y} and not
 * only {@code Catalog.X}.</p>
 *
 * <p><b>The file is read streaming, with the safe factory.</b> It is the workspace's copy of a file
 * the platform wrote, several megabytes on a real configuration (2.6 MB and 13 389 records measured
 * on the demo BSP), so it is parsed with StAX rather than loaded as a document, and the factory is
 * told to open no DTD and no external entity - a document type in a file under a project must not
 * reach out of the workspace.</p>
 *
 * <p><b>Nothing here refuses on an incomplete answer.</b> A model that could not be walked whole, a
 * model that reported no identities at all, a baseline that does not parse - each of those means the
 * comparison was not made, and each is reported as such rather than turned into a deletion. A
 * baseline record with no {@code id} is skipped and is not a finding. A refusal is built only from a
 * comparison that was whole on both sides.</p>
 */
public final class DataLossPlan
{
    /**
     * Top-level kinds whose entities the infobase stores data for.
     * <p>
     * The kinds an object belongs to are read off the baseline's own name rather than guessed from
     * the nesting: 1C object names cannot contain a dot, so for a name of {@code n} segments the
     * entity's own kind is the segment before its name and the object that owns it is the first
     * segment. Registers and catalogs alike are covered by one rule.
     * </p>
     */
    private static final Set<String> DATA_OBJECT_KINDS = Set.of("Catalog", "Document", //$NON-NLS-1$ //$NON-NLS-2$
        "InformationRegister", "AccumulationRegister", "AccountingRegister", "CalculationRegister", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "ChartOfCharacteristicTypes", "ChartOfAccounts", "ChartOfCalculationTypes", "ExchangePlan", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "BusinessProcess", "Task", "Constant", "CommonAttribute", "Sequence", "Enum"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    /**
     * Kinds of an entity under a data-carrying object. A form, a template, a command and a module
     * are metadata the base does not hold rows for, so losing one is not a data loss and is not
     * named here. A recalculation of a calculation register is a table of its own, and an enum
     * value is a row the data refers to.
     */
    private static final Set<String> DATA_PART_KINDS = Set.of("Attribute", "TabularSection", //$NON-NLS-1$ //$NON-NLS-2$
        "Dimension", "Resource", "AccountingFlag", "ExtDimensionAccountingFlag", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "AddressingAttribute", "Recalculation", "EnumValue"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * How many segments a name needs before a name is an entity of a kind at all:
     * {@code Catalog.Валюты} is the shortest one.
     */
    private static final int SHORTEST_ENTITY_NAME = 2;

    /** The separator between the segments of a baseline name. */
    private static final String SEGMENT_SEPARATOR = "."; //$NON-NLS-1$

    /** The element one record of the baseline is written as. */
    private static final String RECORD_ELEMENT = "Metadata"; //$NON-NLS-1$

    /** What the plan could not be built from, when it could not be built at all. */
    private static final String NO_BASELINE =
        "this infobase has no synchronization baseline in this workspace, so there is no recorded " //$NON-NLS-1$
            + "state to compare the model with"; //$NON-NLS-1$

    private DataLossPlan()
    {
        // static utility
    }

    /**
     * Reads the plan for one infobase - the seam the update and the inspection both read through,
     * so a test can hand the caller a plan without a project, a baseline or a model behind it.
     */
    @FunctionalInterface
    public interface Reader
    {
        /**
         * @param infobaseProject the project that owns the infobase - the parent, for an extension
         * @param application the application the call resolved to
         * @param modelProjects the projects whose model is the state the update would write
         * @return the plan, never {@code null}
         */
        Plan read(IProject infobaseProject, IApplication application, List<IProject> modelProjects);
    }

    /** The reader against the running environment. */
    public static final Reader fromStore = DataLossPlan::plan;

    /**
     * What one comparison established.
     * <p>
     * {@code compared} is the field that decides whether anything may be refused: an uncompared
     * plan carries no addresses, and an empty address list means two sides that were really read
     * and really agreed - not a comparison that never happened.
     * </p>
     */
    public static final class Plan
    {
        /** Whether the baseline and the model were both read and compared. */
        public final boolean compared;

        /** The baseline file the comparison used, or the one it would have used. */
        public final String file;

        /** Why the comparison was not made. {@code null} when it was. */
        public final String notComparedBecause;

        /** The addresses the base holds and the model does not. Empty when not compared. */
        public final List<String> dataLoss;

        /** How many records the baseline carries, or -1 when it was not read. */
        public final int records;

        /** How many identities the model reported, or -1 when it was not walked. */
        public final int modelIdentities;

        private Plan(boolean compared, String file, String notComparedBecause, List<String> dataLoss,
            int records, int modelIdentities)
        {
            this.compared = compared;
            this.file = file;
            this.notComparedBecause = notComparedBecause;
            this.dataLoss = dataLoss;
            this.records = records;
            this.modelIdentities = modelIdentities;
        }

        /**
         * @return whether the comparison found nothing the model lost
         */
        public boolean isEmpty()
        {
            return dataLoss.isEmpty();
        }

        /**
         * The comparison in one sentence, for an answer that has to say what was compared.
         *
         * @return the sentence
         */
        public String check()
        {
            if (!compared)
            {
                return "not compared: " + notComparedBecause; //$NON-NLS-1$
            }
            StringBuilder sb = new StringBuilder();
            sb.append("compared ").append(records).append(" records of ").append(file) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" with the ").append(modelIdentities).append(" identities of the model: "); //$NON-NLS-1$ //$NON-NLS-2$
            if (dataLoss.isEmpty())
            {
                sb.append("no entity that holds data is missing from the model"); //$NON-NLS-1$
            }
            else
            {
                sb.append(dataLoss.size()) //$NON-NLS-1$
                    .append(dataLoss.size() == 1 //$NON-NLS-1$
                        ? " entity that holds data is in the base and not in the model" //$NON-NLS-1$
                        : " entities that hold data are in the base and not in the model"); //$NON-NLS-1$
            }
            return sb.toString();
        }
    }

    /**
     * A plan built from parts - what a test hands a caller when it is the comparison being
     * exercised rather than the reading of the file.
     *
     * @param file the baseline file the comparison used
     * @param dataLoss the addresses the base holds and the model does not, in the order to report
     * @param records how many records the baseline carried
     * @param modelIdentities how many identities the model reported
     * @return the plan
     */
    public static Plan compared(String file, List<String> dataLoss, int records, int modelIdentities)
    {
        return new Plan(true, file, null, List.copyOf(dataLoss), records, modelIdentities);
    }

    /**
     * A plan for a comparison that was not made.
     *
     * @param file the baseline file the comparison would have used
     * @param because why it was not made
     * @return the plan
     */
    public static Plan notCompared(String file, String because)
    {
        return new Plan(false, file, because, List.of(), -1, -1);
    }

    /**
     * The comparison for one application, read off the disk and the model.
     * <p>
     * Only the reading is here; what a non-empty result means for the call is the caller's
     * business, because the update refuses on it and the inspection only reports it.
     * </p>
     *
     * @param infobaseProject the project that owns the infobase - the parent, for an extension
     * @param application the application the call resolved to
     * @param modelProjects the projects the update would write; the model identities are the union
     *            of theirs, so an extension routed to its parent's infobase compares against both
     * @return the plan, never {@code null}
     */
    public static Plan plan(IProject infobaseProject, IApplication application,
        List<IProject> modelProjects)
    {
        if (infobaseProject == null || !(application instanceof IInfobaseApplication))
        {
            return notCompared(null, "this application has no infobase of its own, so it has no " //$NON-NLS-1$
                + "synchronization baseline to compare the model with"); //$NON-NLS-1$
        }
        com._1c.g5.v8.dt.platform.services.model.InfobaseReference infobase =
            ((IInfobaseApplication)application).getInfobase();
        if (infobase == null || infobase.getUuid() == null)
        {
            return notCompared(null, "this application names no infobase, so it has no " //$NON-NLS-1$
                + "synchronization baseline to compare the model with"); //$NON-NLS-1$
        }
        Path file = baselineFile(infobaseProject, infobase.getUuid().toString());
        if (file == null || !Files.isRegularFile(file))
        {
            return notCompared(file == null ? null : file.toString(), NO_BASELINE);
        }
        ModelIds model = idsOf(modelProjects);
        if (model.whyNotWhole != null)
        {
            return notCompared(file.toString(), model.whyNotWhole);
        }
        try (InputStream in = Files.newInputStream(file))
        {
            return plan(in, model.ids, file.toString());
        }
        catch (IOException | RuntimeException cannotRead)
        {
            return notCompared(file.toString(), "the baseline could not be read (" //$NON-NLS-1$
                + TextSuggest.safeMessage(cannotRead) + ")"); //$NON-NLS-1$
        }
    }

    /**
     * The comparison itself, over a baseline stream and a set of model identities.
     * <p>
     * This is the seam the rest of the class exists to feed: the file, the model and the reader of
     * the running environment are all outside it, so a test can put a fixture in and read the
     * addresses out.
     * </p>
     *
     * @param baseline the {@code ConfigDumpInfo.xml} to read; the caller closes it
     * @param modelIds the identities of the model, in any case
     * @param file the baseline's path, as the plan names it
     * @return the plan
     * @throws IOException when the stream does not read as a baseline
     */
    public static Plan plan(InputStream baseline, Set<String> modelIds, String file)
        throws IOException
    {
        Set<String> known = new HashSet<>();
        if (modelIds != null)
        {
            for (String id : modelIds)
            {
                if (id != null)
                {
                    known.add(id.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        List<Record> records = readRecords(baseline);

        // Two passes, both order-independent: what the base holds and the model does not, then the
        // suppression of everything the named entity already accounts for.
        List<Record> candidates = new ArrayList<>();
        for (Record record : records)
        {
            if (carriesData(record.name) && isMissing(record.id, known))
            {
                candidates.add(record);
            }
        }
        Set<String> deletedObjects = new HashSet<>();
        for (Record candidate : candidates)
        {
            if (segmentsOf(candidate.name).length == SHORTEST_ENTITY_NAME)
            {
                deletedObjects.add(candidate.name);
            }
        }
        Set<String> named = new LinkedHashSet<>();
        for (Record candidate : candidates)
        {
            String owner = ownerOf(candidate.name);
            if (owner == null || !deletedObjects.contains(owner))
            {
                named.add(candidate.name);
            }
        }
        // A named entity speaks for what is under it: the object is named once rather than once per
        // attribute, and a deleted tabular section is named once rather than together with its
        // columns. Every container of a name is a prefix of it, and the prefixes of a baseline name
        // are every even one shorter than the name itself - so a name whose container was already
        // named is not named again. Forms and templates are containers too and are never named, so
        // nothing under them is suppressed by this.
        List<String> findings = new ArrayList<>();
        for (String name : named)
        {
            if (!containerNamed(name, named))
            {
                findings.add(name);
            }
        }
        return compared(file, findings, records.size(), known.size());
    }

    /**
     * The baseline file of one infobase: what exists in either store, else the path EDT 2026 would
     * write, so a refusal and a "not compared" both name a place a reader can go to.
     *
     * @param infobaseProject the project that owns the infobase
     * @param infobaseUuid the infobase's uuid, as the store names its directory
     * @return the file path, or {@code null} when there is no project to resolve it against
     */
    public static Path baselineFile(IProject infobaseProject, String infobaseUuid)
    {
        if (infobaseProject == null || infobaseUuid == null)
        {
            return null;
        }
        for (Path store : SyncBaseline.stores(infobaseProject))
        {
            Path candidate = store.resolve(infobaseUuid).resolve(DumpInfoProbe.FILE_NAME);
            if (Files.isRegularFile(candidate))
            {
                return candidate;
            }
        }
        return SyncBaseline.indexOf(infobaseProject, infobaseUuid).getParent()
            .resolve(DumpInfoProbe.FILE_NAME);
    }

    /**
     * The identities of the model, top-level objects and everything under them, read under a
     * read-only transaction so a write committed at the same time is either wholly seen or not
     * seen at all.
     *
     * <p>A walk that could not be made whole says so instead of answering with what it managed to
     * read: a partial set of identities makes every entity it does not carry look deleted, and a
     * refusal built on that is a refusal built on a failure of the reading. The kinds are asked
     * for by name rather than listed here for the same reason {@link MetadataTypeCatalog} exists -
     * a kind the platform adds is picked up rather than silently missed.</p>
     *
     * @param projects the projects whose model is being read; {@code null} or empty is nothing read
     * @return the identities, lower-cased, and why they are not whole when they are not
     */
    public static ModelIds idsOf(List<IProject> projects)
    {
        Set<String> ids = new HashSet<>();
        if (projects == null || projects.isEmpty())
        {
            return new ModelIds(ids, "no project was named to read the model from"); //$NON-NLS-1$
        }
        Activator activator = Activator.getDefault();
        IConfigurationProvider provider = activator == null ? null : activator.getConfigurationProvider();
        IBmModelManager modelManager = activator == null ? null : activator.getBmModelManager();
        if (provider == null || modelManager == null)
        {
            return new ModelIds(ids, "the configuration or model service of this EDT is " //$NON-NLS-1$
                + "unavailable"); //$NON-NLS-1$
        }
        for (IProject project : projects)
        {
            if (project == null || !project.exists() || !project.isOpen())
            {
                continue;
            }
            String why = readProject(provider, modelManager, project, ids);
            if (why != null)
            {
                return new ModelIds(ids, why);
            }
        }
        if (ids.isEmpty())
        {
            return new ModelIds(ids, "the model reported no identities at all"); //$NON-NLS-1$
        }
        return new ModelIds(ids, null);
    }

    /**
     * Adds one project's identities to the set, inside a read-only transaction of that project's
     * model.
     *
     * @param provider the configuration provider
     * @param modelManager the model manager
     * @param project the project
     * @param ids the set to add to
     * @return why the walk was not whole, or {@code null} when it was
     */
    private static String readProject(IConfigurationProvider provider, IBmModelManager modelManager,
        IProject project, Set<String> ids)
    {
        try
        {
            Configuration configuration = provider.getConfiguration(project);
            if (configuration == null)
            {
                return "the project " + project.getName() + " has no configuration"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            IBmModel bmModel = modelManager.getModel(project);
            if (bmModel == null)
            {
                return "the project " + project.getName() + " has no object model"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            final String[] refused = new String[1];
            bmModel.executeReadonlyTask(new AbstractBmTask<Void>("data_loss_plan") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor monitor)
                {
                    refused[0] = collect(configuration, ids);
                    return null;
                }
            });
            return refused[0];
        }
        catch (RuntimeException | LinkageError refused)
        {
            return "the model of " + project.getName() + " was not read (" //$NON-NLS-1$ //$NON-NLS-2$
                + TextSuggest.safeMessage(refused) + ")"; //$NON-NLS-1$
        }
    }

    /**
     * Walks one configuration, adding the identity of every object and of everything under it.
     *
     * @param configuration the configuration to walk
     * @param ids the set to add to
     * @return why the walk was not whole, or {@code null} when it was
     */
    private static String collect(Configuration configuration, Set<String> ids)
    {
        add(ids, configuration.getUuid());
        for (String type : MetadataTypeCatalog.getAllEnglishSingularNames())
        {
            List<? extends MdObject> objects;
            try
            {
                objects = MetadataTypeCatalog.getObjects(configuration, type);
            }
            catch (RuntimeException noSuchCollection)
            {
                objects = null;
            }
            if (objects == null)
            {
                // A collection this build does not hold cannot be walked, and a kind that was not
                // walked makes every entity of it look deleted. For a kind that carries data that
                // would be a refusal built on a failure of the reading, so nothing is claimed at
                // all; for the rest - forms, subsystems, external objects - no baseline record of
                // theirs is judged in the first place and the walk goes on.
                if (DATA_OBJECT_KINDS.contains(type))
                {
                    return "the objects of kind " + type + " were not enumerated"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                continue;
            }
            for (MdObject object : objects)
            {
                if (object == null)
                {
                    continue;
                }
                add(ids, object.getUuid());
                try
                {
                    java.util.Iterator<org.eclipse.emf.ecore.EObject> inside = object.eAllContents();
                    while (inside.hasNext())
                    {
                        org.eclipse.emf.ecore.EObject child = inside.next();
                        if (child instanceof MdObject)
                        {
                            add(ids, ((MdObject)child).getUuid());
                        }
                    }
                }
                catch (RuntimeException | LinkageError refused)
                {
                    return "the contents of an object were not read (" //$NON-NLS-1$
                        + TextSuggest.safeMessage(refused) + ")"; //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    /** Adds an identity in the case the comparison uses, ignoring an entity without one. */
    private static void add(Set<String> ids, java.util.UUID uuid)
    {
        if (uuid != null)
        {
            ids.add(uuid.toString().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Reads the records of a baseline, one {@code Metadata} element at a time.
     *
     * @param baseline the stream; not closed here
     * @return the records, in file order
     * @throws IOException when the stream does not read as XML
     */
    private static List<Record> readRecords(InputStream baseline) throws IOException
    {
        XMLInputFactory factory = safeFactory();
        List<Record> records = new ArrayList<>();
        try
        {
            XMLStreamReader reader =
                factory.createXMLStreamReader(baseline, StandardCharsets.UTF_8.name());
            try
            {
                while (reader.hasNext())
                {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT)
                    {
                        continue;
                    }
                    if (!RECORD_ELEMENT.equals(reader.getLocalName()))
                    {
                        continue;
                    }
                    String name = reader.getAttributeValue(null, "name"); //$NON-NLS-1$
                    if (name != null && !name.isEmpty())
                    {
                        records.add(new Record(name, reader.getAttributeValue(null, "id"))); //$NON-NLS-1$
                    }
                }
            }
            finally
            {
                reader.close();
            }
        }
        catch (XMLStreamException malformed)
        {
            throw new IOException("the baseline is not well-formed XML: " //$NON-NLS-1$
                + TextSuggest.safeMessage(malformed), malformed);
        }
        return records;
    }

    /**
     * The parser the baseline is read with: no document type, no external entity, no reference to
     * anything outside the file. A record file lives under a project, and a project is data.
     *
     * @return the factory
     */
    private static XMLInputFactory safeFactory()
    {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        refuse(factory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        refuse(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        refuse(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
        return factory;
    }

    /** Sets one factory property, leaving a factory that does not know it as it was. */
    private static void refuse(XMLInputFactory factory, String property, Object value)
    {
        try
        {
            factory.setProperty(property, value);
        }
        catch (IllegalArgumentException unsupported)
        {
            // An implementation without the property is one that cannot be asked to open a DTD by
            // this route; the default of the properties that matter here is already to open none.
            Activator.logDebug("baseline parser does not support " + property); //$NON-NLS-1$
        }
    }

    /**
     * Whether a baseline name is an entity the infobase holds data for.
     *
     * @param name the name, {@code Catalog.Валюты.Attribute.НаименованиеПолное}
     * @return whether deleting it would delete data
     */
    static boolean carriesData(String name)
    {
        String[] segments = segmentsOf(name);
        if (segments.length < SHORTEST_ENTITY_NAME || !DATA_OBJECT_KINDS.contains(segments[0]))
        {
            return false;
        }
        if (segments.length == SHORTEST_ENTITY_NAME)
        {
            return true;
        }
        return DATA_PART_KINDS.contains(segments[segments.length - 2]);
    }

    /**
     * The name of the object a baseline name belongs to, which is its first two segments.
     *
     * @param name the name
     * @return the object's name, or {@code null} when the name IS an object and has no container
     */
    static String ownerOf(String name)
    {
        String[] segments = segmentsOf(name);
        if (segments.length <= SHORTEST_ENTITY_NAME)
        {
            return null;
        }
        return segments[0] + SEGMENT_SEPARATOR + segments[1];
    }

    /**
     * Whether a container of a name is among the names.
     *
     * @param name the name
     * @param named the names already reported
     * @return whether an even-length proper prefix of the name is in {@code named}
     */
    private static boolean containerNamed(String name, Set<String> named)
    {
        String[] segments = segmentsOf(name);
        for (int length = SHORTEST_ENTITY_NAME; length < segments.length; length += 2)
        {
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < length; i++)
            {
                if (i > 0)
                {
                    prefix.append(SEGMENT_SEPARATOR);
                }
                prefix.append(segments[i]);
            }
            if (named.contains(prefix.toString()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an entity's id is nowhere in the model. An entity the baseline carries without an id
     * is not counted as deleted: nothing was compared for it, and a guess is not a finding.
     *
     * @param id the record's id, as the file writes it
     * @param known the model identities, lower-cased
     * @return whether the id is absent
     */
    private static boolean isMissing(String id, Set<String> known)
    {
        return id != null && !id.trim().isEmpty()
            && !known.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    /** The segments of a baseline name. */
    private static String[] segmentsOf(String name)
    {
        if (name == null || name.isEmpty())
        {
            return new String[0];
        }
        return name.split("\\."); //$NON-NLS-1$
    }

    /** One {@code Metadata} element: the name the platform wrote and the identity it wrote for it. */
    private static final class Record
    {
        final String name;

        final String id;

        Record(String name, String id)
        {
            this.name = name;
            this.id = id;
        }
    }

    /** The identities of a model, and why they are not the whole model when they are not. */
    public static final class ModelIds
    {
        /** The identities, lower-cased. */
        public final Set<String> ids;

        /** Why the walk was not whole, or {@code null} when it was. */
        public final String whyNotWhole;

        ModelIds(Set<String> ids, String whyNotWhole)
        {
            this.ids = ids;
            this.whyNotWhole = whyNotWhole;
        }
    }
}
