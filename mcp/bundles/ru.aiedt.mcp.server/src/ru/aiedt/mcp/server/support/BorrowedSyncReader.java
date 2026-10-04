/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.md.resource.MdTypeUtil;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdObjectExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BorrowedSyncClassification.Qualifiers;
import ru.aiedt.mcp.server.support.BorrowedSyncClassification.Status;
import ru.aiedt.mcp.server.support.BorrowedSyncClassification.TypeItem;
import ru.aiedt.mcp.server.support.BorrowedSyncClassification.TypeSide;

/**
 * Reads what an extension borrowed and what the base now holds, into the plain values
 * {@link BorrowedSyncClassification} decides on.
 * <p>
 * The walk answers three questions per borrowed thing: does the uuid it links to still resolve in
 * the base, under which name, and - for a type-carrying attribute - how far the record it took has
 * drifted. A form is asked whether the base form grew items the borrowed copy has no counterpart
 * of, by name, through the same form model {@code get_form_structure} reads. Interceptors are
 * scanned by {@code list_interceptors}, which this reader does not duplicate: the tool merges its
 * answer in.
 * </p>
 * <p>
 * Everything the answer cannot establish is collected in {@code notChecked} with a reason rather
 * than classified: a side that could not be read is not a side that agrees, and the difference has
 * to stay visible in the response.
 * </p>
 */
public final class BorrowedSyncReader
{
    /** One borrowed thing and its verdict. */
    public static final class Row
    {
        /** The address in the extension: an FQN, or a module path for an interceptor. */
        public String fqn;

        /** object / attribute / tabularSectionAttribute / form. */
        public String kind;

        /** The verdict. */
        public Status status;

        /** The controlled type as text, for attribute rows that carry one. */
        public String controlledType;

        /** The base's type as text, for attribute rows that carry one. */
        public String baseType;

        /** The name the extension holds, for uuid-matched rows. */
        public String extensionName;

        /** The name the base holds under the uuid, for uuid-matched rows. */
        public String baseName;

        /** Base form items the borrowed form has no copy of, for form rows. */
        public List<String> missingItems;

        /** The id of the borrowed attribute in the extension's object model, for type writes. */
        public long targetId;

        /** The base's type description to align to, for type writes. */
        public Object baseTypeDescription;

        /** The base side as plain values, for the write plan. */
        public TypeSide baseTypeSide;

        /** The base form object, for form updates. */
        public EObject baseForm;

        /** The top object whose file a write has to flush. */
        public String flushFqn;
    }

    /** The whole walk over one extension against one base. */
    public static final class Report
    {
        /** Every borrowed row the walk classified. */
        public final List<Row> rows = new ArrayList<>();

        /** What could not be established, each with a reason. */
        public final List<Map<String, String>> notChecked = new ArrayList<>();

        /** Why nothing could be read at all. Null when the walk ran. */
        public String error;

        /** The base the walk read, by name. */
        public String baseProjectName;

        /** The base the walk read. */
        public IProject baseProject;

        /** The extension the walk read, for the reads that need both sides. */
        public IProject extensionProject;

        /** The extension's configuration, for the caller that names what was not found. */
        public Configuration extensionConfiguration;
    }

    /** How deep the form item walk goes before it stops counting. */
    private static final int FORM_ITEM_DEPTH = 30;

    private BorrowedSyncReader()
    {
        // Static walk.
    }

    /**
     * Walks an extension's borrowed objects against the base they were borrowed from.
     *
     * @param extension the extension project
     * @param baseProjectName the base by name, or null to derive it from the extension's parent
     * @param objectFqn the one object to restrict the walk to, normalized, or null for all of them
     * @return the report; {@link Report#error} says why nothing was read when the walk could not
     *         run
     */
    public static Report read(IProject extension, String baseProjectName, String objectFqn)
    {
        Report report = new Report();
        report.extensionProject = extension;
        IProject base = baseProject(extension, baseProjectName);
        if (base == null)
        {
            report.error = baseProjectName == null || baseProjectName.isBlank()
                ? "could not auto-resolve the base configuration from extension " //$NON-NLS-1$
                    + extension.getName() + " - pass baseProjectName" //$NON-NLS-1$
                : "baseProjectName '" + baseProjectName + "' does not resolve to an open project"; //$NON-NLS-1$ //$NON-NLS-2$
            return report;
        }
        report.baseProject = base;
        report.baseProjectName = base.getName();
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        Configuration extConfig = provider == null ? null : provider.getConfiguration(extension);
        Configuration baseConfig = provider == null ? null : provider.getConfiguration(base);
        if (extConfig == null || baseConfig == null)
        {
            report.error = "one of the configurations is not loaded - both projects have to be " //$NON-NLS-1$
                + "open and indexed"; //$NON-NLS-1$
            return report;
        }
        report.extensionConfiguration = extConfig;
        BmFormHelper forms = new BmFormHelper();
        if (!forms.init())
        {
            forms = null;
        }
        for (String type : MetadataTypeCatalog.getAllEnglishSingularNames())
        {
            List<? extends MdObject> extObjects;
            try
            {
                extObjects = MetadataTypeCatalog.getObjects(extConfig, type);
            }
            catch (RuntimeException noSuchCollection)
            {
                continue;
            }
            if (extObjects == null)
            {
                continue;
            }
            for (MdObject extObject : extObjects)
            {
                if (extObject == null || extObject.getName() == null || !isAdopted(extObject))
                {
                    continue;
                }
                String ownerFqn = type + "." + extObject.getName(); //$NON-NLS-1$
                if (objectFqn != null && !under(ownerFqn, objectFqn))
                {
                    continue;
                }
                readObject(report, extObject, ownerFqn, baseConfig, type, forms);
            }
        }
        return report;
    }

    /**
     * Reads one adopted top object and everything borrowed under it.
     *
     * @param report the report being built
     * @param extObject the object as the extension holds it
     * @param ownerFqn its address in the extension
     * @param baseConfig the base's configuration
     * @param type the metadata type name
     * @param forms the form reader, or null when the form model is unreachable
     */
    private static void readObject(Report report, MdObject extObject, String ownerFqn,
        Configuration baseConfig, String type, BmFormHelper forms)
    {
        Row row = new Row();
        row.fqn = ownerFqn;
        row.kind = "object"; //$NON-NLS-1$
        row.extensionName = extObject.getName();
        report.rows.add(row);
        java.util.UUID link = extObject.getExtendedConfigurationObject();
        if (link == null)
        {
            // An adopted object without its uuid link extends nothing and is a borrow-repair
            // question, not a drift question: said and left out of the statuses.
            report.rows.remove(row);
            report.notChecked.add(notChecked(ownerFqn,
                "the object carries no extendedConfigurationObject, so there is no base to " //$NON-NLS-1$
                    + "read - call borrow_object again to repair the link")); //$NON-NLS-1$
            return;
        }
        MdObject baseOwner = findByUuid(baseConfig, type, link);
        if (baseOwner == null)
        {
            row.status = Status.SOURCE_GONE;
            markChildrenGone(report, extObject, ownerFqn);
            return;
        }
        row.baseName = baseOwner.getName();
        Status byName = BorrowedSyncClassification.byUuid(extObject.getName(), baseOwner.getName());
        row.status = byName == null ? Status.IN_SYNC : byName;
        readAttributes(report, extObject, baseOwner, ownerFqn, "getAttributes"); //$NON-NLS-1$
        readTabularSections(report, extObject, baseOwner, ownerFqn);
        readForms(report, extObject, baseOwner, ownerFqn, forms);
    }

    /**
     * Marks every borrowed child under an object whose base is gone.
     *
     * @param report the report being built
     * @param extObject the adopted shell
     * @param ownerFqn its address
     */
    static void markChildrenGone(Report report, MdObject extObject, String ownerFqn)
    {
        markCollectionGone(report, children(extObject, "getAttributes"), ownerFqn, "Attribute"); //$NON-NLS-1$ //$NON-NLS-2$
        for (EObject section : children(extObject, "getTabularSections")) //$NON-NLS-1$
        {
            // A section the extension added itself borrowed nothing, so a gone base breaks nothing
            // in it - the same rule markCollectionGone applies to attributes and forms.
            if (!(section instanceof MdObject)
                || ((MdObject)section).getExtendedConfigurationObject() == null)
            {
                continue;
            }
            String sectionFqn = ownerFqn + ".TabularSection." + nameOf(section); //$NON-NLS-1$
            markRowGone(report, sectionFqn, "tabularSection"); //$NON-NLS-1$
            markCollectionGone(report, children(section, "getAttributes"), sectionFqn, "Attribute"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        markCollectionGone(report, children(extObject, "getForms"), ownerFqn, "Form"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Marks one collection's borrowed children as gone.
     *
     * @param report the report being built
     * @param kids the children the extension holds
     * @param ownerFqn the address the children hang under
     * @param kindSegment the kind segment of the child addresses
     */
    private static void markCollectionGone(Report report, List<EObject> kids, String ownerFqn,
        String kindSegment)
    {
        for (EObject kid : kids)
        {
            if (!(kid instanceof MdObject) || ((MdObject)kid).getExtendedConfigurationObject() == null)
            {
                continue;
            }
            markRowGone(report, ownerFqn + "." + kindSegment + "." + nameOf(kid), //$NON-NLS-1$
                kindSegment.equals("Form") ? "form" : "attribute"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /**
     * Adds one sourceGone row.
     *
     * @param report the report being built
     * @param fqn the child's address in the extension
     * @param kind the row kind
     */
    private static void markRowGone(Report report, String fqn, String kind)
    {
        Row row = new Row();
        row.fqn = fqn;
        row.kind = kind;
        row.status = Status.SOURCE_GONE;
        report.rows.add(row);
    }

    /**
     * Reads the borrowed attributes of an owner pair.
     *
     * @param report the report being built
     * @param extObject the extension's owner
     * @param baseOwner the base's owner
     * @param ownerFqn the owner's address in the extension
     * @param getter the attribute collection getter
     */
    private static void readAttributes(Report report, MdObject extObject, MdObject baseOwner,
        String ownerFqn, String getter)
    {
        Map<String, EObject> mine = byName(extObject, getter);
        Map<String, EObject> theirs = byName(baseOwner, getter);
        for (Map.Entry<String, EObject> entry : mine.entrySet())
        {
            EObject attr = entry.getValue();
            if (!(attr instanceof MdObject) || ((MdObject)attr).getExtendedConfigurationObject() == null)
            {
                continue;
            }
            readAttribute(report, attr, theirs, ownerFqn + ".Attribute." + entry.getKey()); //$NON-NLS-1$
        }
    }

    /**
     * Reads the borrowed tabular sections of an owner pair and their attributes.
     *
     * @param report the report being built
     * @param extObject the extension's owner
     * @param baseOwner the base's owner
     * @param ownerFqn the owner's address in the extension
     */
    private static void readTabularSections(Report report, MdObject extObject, MdObject baseOwner,
        String ownerFqn)
    {
        Map<String, EObject> mySections = byName(extObject, "getTabularSections"); //$NON-NLS-1$
        Map<String, EObject> theirSections = byName(baseOwner, "getTabularSections"); //$NON-NLS-1$
        for (Map.Entry<String, EObject> entry : mySections.entrySet())
        {
            EObject section = entry.getValue();
            if (!(section instanceof MdObject)
                || ((MdObject)section).getExtendedConfigurationObject() == null)
            {
                continue;
            }
            MdObject mdSection = (MdObject)section;
            String sectionFqn = ownerFqn + ".TabularSection." + entry.getKey(); //$NON-NLS-1$
            Row row = new Row();
            row.fqn = sectionFqn;
            row.kind = "tabularSection"; //$NON-NLS-1$
            row.extensionName = mdSection.getName();
            report.rows.add(row);
            EObject baseSection = findByUuidAmong(theirSections.values(),
                mdSection.getExtendedConfigurationObject());
            if (baseSection == null)
            {
                row.status = Status.SOURCE_GONE;
                markCollectionGone(report, children(section, "getAttributes"), sectionFqn, //$NON-NLS-1$
                    "Attribute"); //$NON-NLS-1$
                continue;
            }
            row.baseName = nameOf(baseSection);
            Status byName =
                BorrowedSyncClassification.byUuid(mdSection.getName(), row.baseName);
            row.status = byName == null ? Status.IN_SYNC : byName;
            if (!(baseSection instanceof MdObject))
            {
                report.notChecked.add(notChecked(sectionFqn,
                    "the base's tabular section is not a metadata object, so its attributes " //$NON-NLS-1$
                        + "were not read")); //$NON-NLS-1$
                continue;
            }
            readAttributes(report, mdSection, (MdObject)baseSection, sectionFqn,
                "getAttributes"); //$NON-NLS-1$
        }
    }

    /**
     * Reads one borrowed type-carrying child against the base's children.
     *
     * @param report the report being built
     * @param child the child as the extension holds it
     * @param baseChildren the base's children of the same kind, by name
     * @param fqn the child's address in the extension
     */
    private static void readAttribute(Report report, EObject child,
        Map<String, EObject> baseChildren, String fqn)
    {
        MdObject mdChild = (MdObject)child;
        Row row = new Row();
        row.fqn = fqn;
        row.kind = "attribute"; //$NON-NLS-1$
        row.extensionName = mdChild.getName();
        report.rows.add(row);
        java.util.UUID link = mdChild.getExtendedConfigurationObject();
        EObject baseChild = link == null ? null
            : baseChildren == null ? null
                : findByUuidAmong(baseChildren.values(), link);
        if (link == null)
        {
            report.rows.remove(row);
            report.notChecked.add(notChecked(fqn,
                "the child carries no extendedConfigurationObject, so there is no base to read")); //$NON-NLS-1$
            return;
        }
        if (baseChild == null)
        {
            row.status = Status.SOURCE_GONE;
            return;
        }
        row.baseName = nameOf(baseChild);
        Status byName = BorrowedSyncClassification.byUuid(mdChild.getName(), row.baseName);
        if (byName != null)
        {
            row.status = byName;
            return;
        }
        TypeSide controlled = controlledTypeOf(child, report, fqn);
        TypeSide base = baseTypeOf(baseChild, report, fqn);
        if (controlled == null || base == null)
        {
            // One side could not be read: the row stays out of the statuses, and the reason is
            // in notChecked - a type nobody read is not a type that agrees.
            report.rows.remove(row);
            return;
        }
        row.controlledType = BorrowedSyncClassification.render(controlled);
        row.baseType = BorrowedSyncClassification.render(base);
        row.status = BorrowedSyncClassification.typeVsBase(controlled, base);
        if (row.status == Status.TYPE_CONFLICT)
        {
            row.baseTypeSide = base;
            row.baseTypeDescription = typeDescriptionOf(baseChild);
            if (child instanceof IBmObject)
            {
                row.targetId = ((IBmObject)child).bmGetId();
                row.flushFqn = topOf(fqn);
            }
            else
            {
                report.notChecked.add(notChecked(fqn,
                    "the attribute is not tracked by the object model, so its type cannot be " //$NON-NLS-1$
                        + "aligned here")); //$NON-NLS-1$
            }
        }
    }

    /**
     * Reads the borrowed forms of an owner pair and compares their items with the base's.
     *
     * @param report the report being built
     * @param extObject the extension's owner
     * @param baseOwner the base's owner
     * @param ownerFqn the owner's address in the extension
     * @param forms the form reader, or null when the form model is unreachable
     */
    private static void readForms(Report report, MdObject extObject, MdObject baseOwner,
        String ownerFqn, BmFormHelper forms)
    {
        Map<String, EObject> myForms = byName(extObject, "getForms"); //$NON-NLS-1$
        Map<String, EObject> theirForms = byName(baseOwner, "getForms"); //$NON-NLS-1$
        for (Map.Entry<String, EObject> entry : myForms.entrySet())
        {
            EObject form = entry.getValue();
            if (!(form instanceof MdObject)
                || ((MdObject)form).getExtendedConfigurationObject() == null)
            {
                continue;
            }
            String formFqn = ownerFqn + ".Form." + entry.getKey(); //$NON-NLS-1$
            MdObject mdForm = (MdObject)form;
            Row row = new Row();
            row.fqn = formFqn;
            row.kind = "form"; //$NON-NLS-1$
            row.extensionName = mdForm.getName();
            report.rows.add(row);
            EObject baseForm = findByUuidAmong(theirForms.values(),
                mdForm.getExtendedConfigurationObject());
            if (baseForm == null)
            {
                row.status = Status.SOURCE_GONE;
                continue;
            }
            row.baseName = nameOf(baseForm);
            Status byName = BorrowedSyncClassification.byUuid(mdForm.getName(), row.baseName);
            if (byName != null)
            {
                row.status = byName;
                continue;
            }
            if (forms == null)
            {
                report.notChecked.add(notChecked(formFqn,
                    "the form model is unreachable, so the items were not compared")); //$NON-NLS-1$
                report.rows.remove(row);
                continue;
            }
            Set<String> baseItems = formItemNames(forms, report.baseProject, formFqn);
            Set<String> myItems = formItemNames(forms, report.extensionProject, formFqn);
            if (baseItems == null || myItems == null)
            {
                report.notChecked.add(notChecked(formFqn,
                    "the form could not be read on " //$NON-NLS-1$
                        + (baseItems == null ? "the base" : "the extension") //$NON-NLS-1$ //$NON-NLS-2$
                        + " side, so the items were not compared")); //$NON-NLS-1$
                report.rows.remove(row);
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String item : baseItems)
            {
                if (!myItems.contains(item))
                {
                    missing.add(item);
                }
            }
            if (missing.isEmpty())
            {
                row.status = Status.IN_SYNC;
            }
            else
            {
                row.status = Status.FORM_OUT_OF_DATE;
                row.missingItems = missing;
                row.baseForm = baseForm;
            }
        }
    }

    /** One side of a type comparison that was read, or the reason it was not. */
    public static final class SideRead
    {
        /** The side, or null when it could not be read. */
        public final TypeSide side;

        /** Why it could not be read. Null when it was. */
        public final String reason;

        /**
         * Records one read.
         *
         * @param side the side, or null
         * @param reason the reason, or null
         */
        public SideRead(TypeSide side, String reason)
        {
            this.side = side;
            this.reason = reason;
        }
    }

    /**
     * Reads the controlled type of a borrowed attribute, from its extension block.
     * <p>
     * Public because {@code extension_diff} answers the same question for its typeChanges rows:
     * a borrowed attribute keeps its type in the extension block, and reading the ordinary type
     * there answers nothing.
     * </p>
     *
     * @param attribute the borrowed attribute
     * @return the read, its side null only when a name would not resolve
     */
    public static SideRead readControlledSide(EObject attribute)
    {
        Object block = invoke(attribute, "getExtension"); //$NON-NLS-1$
        if (!(block instanceof MdObjectExtension))
        {
            // No extension block at all means the extension never recorded a type for it: that is
            // the no-opinion record, not a failure to read.
            return new SideRead(new TypeSide(BorrowedSyncClassification.noItems(), null), null);
        }
        Object composition = ((MdObjectExtension)block).eGet(
            ((MdObjectExtension)block).eClass().getEStructuralFeature("typeExtension")); //$NON-NLS-1$
        if (!(composition instanceof TypeDescriptionExtension))
        {
            return new SideRead(new TypeSide(BorrowedSyncClassification.noItems(), null), null);
        }
        List<TypeItem> items = new ArrayList<>();
        for (Object entry : ((TypeDescriptionExtension)composition).getTypes())
        {
            if (!(entry instanceof com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension))
            {
                continue;
            }
            com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension typeExtension =
                (com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension)entry;
            if (typeExtension.getState() != MdPropertyState.CHECKED)
            {
                continue;
            }
            String name = BmDefinedTypeHelper.readTypeNameOf(typeExtension.getType());
            if (name == null || name.isEmpty())
            {
                return new SideRead(null,
                    "a controlled type entry carries no readable name, so the composition was " //$NON-NLS-1$
                        + "not compared"); //$NON-NLS-1$
            }
            items.add(new TypeItem(name, isMetadataType(typeExtension.getType())));
        }
        return new SideRead(new TypeSide(items, qualifiersOf(composition)), null);
    }

    /**
     * Reads the base's type for an attribute.
     *
     * @param baseChild the base's attribute
     * @return the read, its side null only when a name would not resolve
     */
    public static SideRead readBaseSide(EObject baseChild)
    {
        Object description = typeDescriptionOf(baseChild);
        if (description == null)
        {
            return new SideRead(null,
                "the base attribute answers with no type description, so nothing was compared"); //$NON-NLS-1$
        }
        Object types = invoke(description, "getTypes"); //$NON-NLS-1$
        if (!(types instanceof List) || ((List<?>)types).isEmpty())
        {
            return new SideRead(null,
                "the base type description carries no types, so nothing was compared"); //$NON-NLS-1$
        }
        List<TypeItem> items = new ArrayList<>();
        for (Object item : (List<?>)types)
        {
            String name = BmDefinedTypeHelper.readTypeNameOf(item);
            if (name == null || name.isEmpty())
            {
                return new SideRead(null,
                    "a base type entry carries no readable name, so the composition was not " //$NON-NLS-1$
                        + "compared"); //$NON-NLS-1$
            }
            items.add(new TypeItem(name, isMetadataType(item)));
        }
        return new SideRead(new TypeSide(items, qualifiersOf(description)), null);
    }

    /**
     * Whether a type item is one the platform calls a metadata object type.
     *
     * @param item the item, possibly not a type item at all
     * @return true when it names a metadata object
     */
    private static boolean isMetadataType(Object item)
    {
        return item instanceof com._1c.g5.v8.dt.mcore.TypeItem
            && MdTypeUtil.isMetadataObjectType((com._1c.g5.v8.dt.mcore.TypeItem)item);
    }

    /**
     * The controlled side of a borrowed attribute, with the reason kept for the report.
     *
     * @param attribute the borrowed attribute
     * @param report the report, for what could not be read
     * @param fqn the attribute's address, for the reason
     * @return the controlled side, or null when it could not be read
     */
    private static TypeSide controlledTypeOf(EObject attribute, Report report, String fqn)
    {
        SideRead read = readControlledSide(attribute);
        if (read.reason != null)
        {
            report.notChecked.add(notChecked(fqn, read.reason));
        }
        return read.side;
    }

    /**
     * The base's type for an attribute, with the reason kept for the report.
     *
     * @param baseChild the base's attribute
     * @param report the report, for what could not be read
     * @param fqn the extension-side address, for the reason
     * @return the base side, or null when it could not be read
     */
    private static TypeSide baseTypeOf(EObject baseChild, Report report, String fqn)
    {
        SideRead read = readBaseSide(baseChild);
        if (read.reason != null)
        {
            report.notChecked.add(notChecked(fqn, read.reason));
        }
        return read.side;
    }

    /**
     * The type description EMF object of an attribute, on either side.
     *
     * @param attribute the attribute
     * @return its type description, or null when it answers with none
     */
    private static Object typeDescriptionOf(EObject attribute)
    {
        return invoke(attribute, "getType"); //$NON-NLS-1$
    }

    /**
     * Reads the qualifiers off a type description, extension block or ordinary.
     *
     * @param description the description or the extension composition
     * @return the qualifiers, or null when it holds none
     */
    private static Qualifiers qualifiersOf(Object description)
    {
        Qualifiers qualifiers = new Qualifiers();
        boolean any = false;
        Object string = invoke(description, "getStringQualifiers"); //$NON-NLS-1$
        if (string != null)
        {
            any = true;
            qualifiers.stringLength = intValue(invoke(string, "getLength")); //$NON-NLS-1$
            qualifiers.stringFixed = booleanValue(invoke(string, "isFixed")); //$NON-NLS-1$
        }
        Object number = invoke(description, "getNumberQualifiers"); //$NON-NLS-1$
        if (number != null)
        {
            any = true;
            qualifiers.numberPrecision = intValue(invoke(number, "getPrecision")); //$NON-NLS-1$
            qualifiers.numberScale = intValue(invoke(number, "getScale")); //$NON-NLS-1$
            qualifiers.numberNonNegative = booleanValue(invoke(number, "isNonNegative")); //$NON-NLS-1$
        }
        Object date = invoke(description, "getDateQualifiers"); //$NON-NLS-1$
        if (date != null)
        {
            any = true;
            Object fractions = invoke(date, "getDateFractions"); //$NON-NLS-1$
            qualifiers.dateFractions = fractions == null ? null : String.valueOf(fractions);
        }
        Object binary = invoke(description, "getBinaryQualifiers"); //$NON-NLS-1$
        if (binary != null)
        {
            any = true;
            qualifiers.binaryLength = intValue(invoke(binary, "getLength")); //$NON-NLS-1$
            qualifiers.binaryTolerance = intValue(invoke(binary, "getTolerance")); //$NON-NLS-1$
        }
        return any ? qualifiers : null;
    }

    /**
     * The names of every item of a form, walked the way {@code get_form_structure} walks it.
     *
     * @param forms the form reader
     * @param project the project the form lives in
     * @param formFqn the form's address, without the trailing {@code .Form}
     * @return the names, case-insensitively ordered, or null when the form could not be read
     */
    private static Set<String> formItemNames(BmFormHelper forms, IProject project, String formFqn)
    {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        String[] failure = { null };
        String error = forms.executeFormReadOperation(project, formFqn, (transaction, form) -> {
            collectItemNames(form, names, 0, failure);
            return null;
        });
        if (error != null && error.startsWith("Error:")) //$NON-NLS-1$
        {
            return null;
        }
        if (failure[0] != null)
        {
            return null;
        }
        return names;
    }

    /**
     * Walks a form item tree, collecting every item's name.
     *
     * @param item the item, starting at the form itself
     * @param names the names being collected
     * @param depth the depth, a guard against a cyclic model
     * @param failure the first walk failure, if one happens
     */
    private static void collectItemNames(Object item, Set<String> names, int depth,
        String[] failure)
    {
        if (item == null || depth > FORM_ITEM_DEPTH)
        {
            return;
        }
        String name = nameOfReflective(item);
        if (name != null && !name.isEmpty())
        {
            names.add(name);
        }
        Object children = invoke(item, "getItems"); //$NON-NLS-1$
        if (children == null)
        {
            return;
        }
        if (!(children instanceof List))
        {
            failure[0] = "the form answered getItems with " //$NON-NLS-1$
                + children.getClass().getSimpleName();
            return;
        }
        for (Object child : (List<?>)children)
        {
            collectItemNames(child, names, depth + 1, failure);
        }
    }

    /**
     * Whether an address names an object or something under it.
     *
     * @param fqn the object's address
     * @param filter the address the caller asked for
     * @return true when the object is the one asked for or lives under it
     */
    private static boolean under(String fqn, String filter)
    {
        return fqn.equals(filter) || fqn.startsWith(filter + "."); //$NON-NLS-1$
    }

    /**
     * The top object's address, the one whose file a child write flushes into.
     *
     * @param fqn the child's address
     * @return the top object's address
     */
    private static String topOf(String fqn)
    {
        String[] segments = fqn.split("\\."); //$NON-NLS-1$
        return segments.length > 2 ? segments[0] + "." + segments[1] : fqn; //$NON-NLS-1$
    }

    /**
     * Finds the base project: the named one when a name was given, the parent otherwise.
     *
     * @param extension the extension project
     * @param baseProjectName the base by name, or null
     * @return the base project, or null when it cannot be reached
     */
    private static IProject baseProject(IProject extension, String baseProjectName)
    {
        if (baseProjectName != null && !baseProjectName.isBlank())
        {
            IProject named =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
                    .getProject(baseProjectName);
            return named != null && named.exists() ? named : null;
        }
        Object extProject = BmExtensionHelper.resolveExtensionProject(extension);
        return extProject == null ? null : BmExtensionHelper.deriveParentProject(extProject);
    }

    /**
     * Says whether an object is one the extension borrowed.
     *
     * @param object the object
     * @return true when it was adopted
     */
    private static boolean isAdopted(MdObject object)
    {
        try
        {
            Object belonging = object.getObjectBelonging();
            return belonging != null && "ADOPTED".equalsIgnoreCase(String.valueOf(belonging)); //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError cannotTell)
        {
            // An object whose belonging cannot be read is one whose rows would be guesses.
            return false;
        }
    }

    /**
     * Finds an object of a type by uuid.
     *
     * @param config the configuration to search
     * @param type the metadata type name
     * @param uuid the uuid the extension links to
     * @return the base object, or null
     */
    private static MdObject findByUuid(Configuration config, String type, java.util.UUID uuid)
    {
        List<? extends MdObject> objects;
        try
        {
            objects = MetadataTypeCatalog.getObjects(config, type);
        }
        catch (RuntimeException noSuchCollection)
        {
            return null;
        }
        if (objects == null)
        {
            return null;
        }
        EObject found = findByUuidAmong(objects, uuid);
        return found instanceof MdObject ? (MdObject)found : null;
    }

    /**
     * Finds the child of a collection by uuid.
     *
     * @param candidates the children
     * @param uuid the uuid the extension links to
     * @return the child, or null
     */
    private static EObject findByUuidAmong(Iterable<? extends EObject> candidates,
        java.util.UUID uuid)
    {
        if (uuid == null || candidates == null)
        {
            return null;
        }
        for (EObject candidate : candidates)
        {
            if (candidate instanceof MdObject && uuid.equals(((MdObject)candidate).getUuid()))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Indexes a child collection by name.
     *
     * @param owner the owner
     * @param getter the collection getter
     * @return the children by name, empty when the owner has no such collection
     */
    private static Map<String, EObject> byName(MdObject owner, String getter)
    {
        Map<String, EObject> byName = new LinkedHashMap<>();
        for (EObject child : children(owner, getter))
        {
            String name = nameOf(child);
            if (name != null && !name.isEmpty())
            {
                byName.put(name, child);
            }
        }
        return byName;
    }

    /**
     * Reads a child collection of an owner.
     *
     * @param owner the owner
     * @param getter the collection getter
     * @return the children, empty when the owner has no such collection
     */
    private static List<EObject> children(EObject owner, String getter)
    {
        Object value = invoke(owner, getter);
        if (!(value instanceof List))
        {
            return new ArrayList<>();
        }
        List<EObject> kids = new ArrayList<>();
        for (Object child : (List<?>)value)
        {
            if (child instanceof EObject)
            {
                kids.add((EObject)child);
            }
        }
        return kids;
    }

    /**
     * Reads an object's name.
     *
     * @param object the object
     * @return the name, or null when it answers with none
     */
    private static String nameOf(EObject object)
    {
        return nameOfReflective(object);
    }

    /**
     * Reads an object's name through its accessor, for model objects of either world.
     *
     * @param object the object
     * @return the name, or null when it answers with none
     */
    private static String nameOfReflective(Object object)
    {
        Object name = invoke(object, "getName"); //$NON-NLS-1$
        return name == null ? null : String.valueOf(name);
    }

    /**
     * Calls a no-argument getter and turns any refusal into null.
     *
     * @param target the object
     * @param getter the getter name
     * @return the value, or null
     */
    private static Object invoke(Object target, String getter)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            Method method = target.getClass().getMethod(getter);
            return method.invoke(target);
        }
        catch (ReflectiveOperationException | RuntimeException absent)
        {
            // Collections one object type does not carry are ordinary, and the callers treat null
            // as "not here" by design.
            return null;
        }
    }

    /**
     * Reads an int-valued getter.
     *
     * @param value the value read
     * @return the int, or null
     */
    private static Integer intValue(Object value)
    {
        return value instanceof Number ? Integer.valueOf(((Number)value).intValue()) : null;
    }

    /**
     * Reads a boolean-valued getter.
     *
     * @param value the value read
     * @return the boolean, or null
     */
    private static Boolean booleanValue(Object value)
    {
        return value instanceof Boolean ? (Boolean)value : null;
    }

    /**
     * One notChecked entry.
     *
     * @param fqn what could not be established
     * @param reason why
     * @return the entry
     */
    private static Map<String, String> notChecked(String fqn, String reason)
    {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("fqn", fqn); //$NON-NLS-1$
        entry.put("reason", reason); //$NON-NLS-1$
        return entry;
    }
}
