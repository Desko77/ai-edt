/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdtype.AbstractMdType;

import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * The names behind the ids a layout records, read off the environment's configuration.
 *
 * <p>Built on the UI thread in one pass: every produced type of every top object and tabular
 * section ({@code objectType}, {@code refType}, {@code listType}, {@code rowType}, ...) is indexed
 * by its {@code typeId}, which is what a layout writes after {@code "#"} in a pattern, and the
 * members of the objects the layout names - attributes, tabular sections, their attributes,
 * dimensions, resources - are indexed by uuid, with the kind a member of one reference type
 * points at. Every call after the build reads memory only.</p>
 */
public final class ConfigurationNames
    implements LayoutNames
{
    /** One produced type: the object that produces it, the object's kind, what the type is of it. */
    private static final class Produced
    {
        final MdObject object;

        final String kind;

        final String suffix;

        final String name;

        Produced(MdObject object, String kind, String suffix, String name)
        {
            this.object = object;
            this.kind = kind;
            this.suffix = suffix;
            this.name = name;
        }
    }

    /** One member of an object: its name, its types, the kind it refers to and the produced type it holds, when it has one type. */
    private static final class Member
    {
        final String name;

        final String refKind;

        final String typeUuid;

        final Set<String> types;

        Member(String name, String refKind, String typeUuid, Set<String> types)
        {
            this.name = name;
            this.refKind = refKind;
            this.typeUuid = typeUuid;
            this.types = types;
        }
    }

    /** How many members deep the objects a layout names are followed through their members' types. */
    private static final int MEMBER_DEPTH = 3;

    private final Map<String, Produced> byTypeId = new HashMap<>();

    private final Map<String, String> byTypeName = new HashMap<>();

    private final Map<MdObject, Map<String, Member>> members = new HashMap<>();

    /** The top objects of the configuration by uuid: what a member outside any owner resolves to, a constant for one. */
    private final Map<String, String> topByUuid = new HashMap<>();

    private ConfigurationNames()
    {
        // built through of
    }

    /**
     * The names of a project's configuration, with the members of the objects behind the given
     * produced types read in advance; nothing when the environment has no configuration loaded.
     *
     * @param project the project
     * @param producedTypeUuids the produced types a layout names, whose owners' members will be
     *        asked about
     * @return the names; {@link LayoutNames#NONE} when the configuration is not reachable
     */
    public static LayoutNames of(IProject project, Collection<String> producedTypeUuids)
    {
        try
        {
            Activator activator = Activator.getDefault();
            IConfigurationProvider provider = activator == null ? null : activator.getConfigurationProvider();
            if (provider == null)
            {
                return NONE;
            }
            LayoutNames built = UiSync.call(() -> {
                Configuration configuration = provider.getConfiguration(project);
                if (configuration == null)
                {
                    return null;
                }
                ConfigurationNames names = new ConfigurationNames();
                names.index(configuration);
                java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
                for (String uuid : producedTypeUuids)
                {
                    wanted.add(uuid.toLowerCase(Locale.ROOT));
                }
                for (int depth = 0; depth < MEMBER_DEPTH && !wanted.isEmpty(); depth++)
                {
                    java.util.Set<String> next = new java.util.LinkedHashSet<>();
                    for (String uuid : wanted)
                    {
                        Produced produced = names.byTypeId.get(uuid);
                        if (produced != null && !names.members.containsKey(produced.object))
                        {
                            for (Member member : names.indexMembers(produced.object).values())
                            {
                                if (member.typeUuid != null)
                                {
                                    next.add(member.typeUuid);
                                }
                            }
                        }
                    }
                    wanted = next;
                }
                return names;
            });
            return built == null ? NONE : built;
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("ordinary_forms: the configuration's names were not read: " + e.getMessage()); //$NON-NLS-1$
            return NONE;
        }
    }

    private void index(Configuration configuration)
    {
        for (String kind : MetadataTypeCatalog.getAllEnglishSingularNames())
        {
            List<? extends MdObject> objects;
            try
            {
                objects = MetadataTypeCatalog.getObjects(configuration, kind);
            }
            catch (RuntimeException e)
            {
                continue;
            }
            if (objects == null)
            {
                continue;
            }
            for (MdObject object : objects)
            {
                if (object.getUuid() != null && object.getName() != null)
                {
                    topByUuid.put(object.getUuid().toString().toLowerCase(Locale.ROOT), object.getName());
                }
                indexProducedTypes(object, kind, object.getName());
                Object sections = feature(object, "tabularSections"); //$NON-NLS-1$
                if (sections instanceof Iterable)
                {
                    for (Object section : (Iterable<?>)sections)
                    {
                        if (section instanceof MdObject)
                        {
                            indexProducedTypes((MdObject)section, kind + "TabularSection", //$NON-NLS-1$
                                object.getName() + "." + ((MdObject)section).getName()); //$NON-NLS-1$
                        }
                    }
                }
            }
        }
    }

    private void indexProducedTypes(MdObject object, String kind, String name)
    {
        Object produced = feature(object, "producedTypes"); //$NON-NLS-1$
        if (!(produced instanceof EObject))
        {
            return;
        }
        EObject types = (EObject)produced;
        for (EReference reference : types.eClass().getEAllReferences())
        {
            Object item = types.eGet(reference);
            if (item instanceof AbstractMdType && ((AbstractMdType)item).getTypeId() != null)
            {
                String featureName = reference.getName();
                String suffix = featureName.endsWith("Type") ? featureName.substring(0, featureName.length() - 4) : featureName; //$NON-NLS-1$
                suffix = suffix.isEmpty() ? suffix : Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
                String typeId = ((AbstractMdType)item).getTypeId().toString().toLowerCase(Locale.ROOT);
                byTypeId.put(typeId, new Produced(object, kind, suffix, name));
                byTypeName.put((kind + suffix + "." + name).toLowerCase(Locale.ROOT), typeId); //$NON-NLS-1$
            }
        }
    }

    private Map<String, Member> indexMembers(MdObject object)
    {
        Map<String, Member> index = members.get(object);
        if (index != null)
        {
            return index;
        }
        index = new HashMap<>();
        for (Iterator<EObject> it = object.eAllContents(); it.hasNext();)
        {
            EObject child = it.next();
            if (child instanceof MdObject && ((MdObject)child).getUuid() != null && ((MdObject)child).getName() != null)
            {
                Set<String> types = typesOf(child);
                if (types.isEmpty())
                {
                    // a tabular section has no value type of its own: what a table over it shows
                    // is typed with the section's object type, the first of its produced types
                    String sectionType = producedObjectType(child);
                    if (sectionType != null)
                    {
                        types = new java.util.HashSet<>(List.of(sectionType));
                    }
                }
                String type = types.size() == 1 ? types.iterator().next() : null;
                String refKind = null;
                if (type != null)
                {
                    int ref = type.indexOf("Ref."); //$NON-NLS-1$
                    refKind = ref > 0 ? type.substring(0, ref) : null;
                }
                index.put(((MdObject)child).getUuid().toString().toLowerCase(Locale.ROOT),
                    new Member(((MdObject)child).getName(), refKind,
                        type == null ? null : byTypeName.get(type.toLowerCase(Locale.ROOT)), types));
            }
        }
        members.put(object, index);
        return index;
    }

    /** The name of the object type among a child's produced types, once indexed, or {@code null}. */
    private String producedObjectType(EObject child)
    {
        Object produced = feature(child, "producedTypes"); //$NON-NLS-1$
        if (!(produced instanceof EObject))
        {
            return null;
        }
        EObject types = (EObject)produced;
        for (EReference reference : types.eClass().getEAllReferences())
        {
            if (!"objectType".equals(reference.getName())) //$NON-NLS-1$
            {
                continue;
            }
            Object item = types.eGet(reference);
            if (item instanceof AbstractMdType && ((AbstractMdType)item).getTypeId() != null)
            {
                Produced entry = byTypeId.get(((AbstractMdType)item).getTypeId().toString().toLowerCase(Locale.ROOT));
                return entry == null ? null : entry.kind + entry.suffix + "." + entry.name; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static Set<String> typesOf(EObject member)
    {
        try
        {
            return BmDefinedTypeHelper.readValueTypeNames(member);
        }
        catch (RuntimeException e)
        {
            return new java.util.HashSet<>();
        }
    }

    private static Object feature(EObject object, String name)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(name);
        return feature == null ? null : object.eGet(feature);
    }

    @Override
    public String typeName(String producedTypeUuid)
    {
        Produced produced = producedTypeUuid == null ? null : byTypeId.get(producedTypeUuid.toLowerCase(Locale.ROOT));
        return produced == null ? null : produced.kind + produced.suffix + "." + produced.name; //$NON-NLS-1$
    }

    @Override
    public String ownerKind(String producedTypeUuid)
    {
        Produced produced = producedTypeUuid == null ? null : byTypeId.get(producedTypeUuid.toLowerCase(Locale.ROOT));
        return produced == null ? null : produced.kind;
    }

    @Override
    public String memberName(String producedTypeUuid, String memberUuid)
    {
        Member member = member(producedTypeUuid, memberUuid);
        if (member != null)
        {
            return member.name;
        }
        return memberUuid == null ? null : topByUuid.get(memberUuid.toLowerCase(Locale.ROOT));
    }

    @Override
    public String referencedKind(String producedTypeUuid, String memberUuid)
    {
        Member member = member(producedTypeUuid, memberUuid);
        return member == null ? null : member.refKind;
    }

    @Override
    public String memberTypeUuid(String producedTypeUuid, String memberUuid)
    {
        Member member = member(producedTypeUuid, memberUuid);
        return member == null ? null : member.typeUuid;
    }

    @Override
    public String memberUuid(String producedTypeUuid, String memberName)
    {
        Produced produced = producedTypeUuid == null ? null : byTypeId.get(producedTypeUuid.toLowerCase(Locale.ROOT));
        Map<String, Member> index = produced == null ? null : members.get(produced.object);
        if (index == null || memberName == null)
        {
            return null;
        }
        for (Map.Entry<String, Member> entry : index.entrySet())
        {
            if (entry.getValue().name.equalsIgnoreCase(memberName))
            {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public ru.aiedt.mcp.oform.BraceTree.ListNode memberPattern(String producedTypeUuid, String memberUuid)
    {
        Member member = member(producedTypeUuid, memberUuid);
        if (member == null || member.types.isEmpty())
        {
            return null;
        }
        ru.aiedt.mcp.oform.BraceTree.ListNode pattern = new ru.aiedt.mcp.oform.BraceTree.ListNode();
        pattern.items().add(new ru.aiedt.mcp.oform.BraceTree.Token("\"Pattern\"")); //$NON-NLS-1$
        for (String type : new java.util.TreeSet<>(member.types))
        {
            ru.aiedt.mcp.oform.BraceTree.ListNode item = new ru.aiedt.mcp.oform.BraceTree.ListNode();
            String produced = byTypeName.get(type.toLowerCase(Locale.ROOT));
            String primitive = primitiveCode(type);
            if (produced != null)
            {
                item.items().add(new ru.aiedt.mcp.oform.BraceTree.Token("\"#\"")); //$NON-NLS-1$
                item.items().add(new ru.aiedt.mcp.oform.BraceTree.Token(produced));
            }
            else if (primitive != null)
            {
                item.items().add(new ru.aiedt.mcp.oform.BraceTree.Token(primitive));
            }
            else
            {
                continue;
            }
            pattern.items().add(item);
        }
        return pattern;
    }

    private static String primitiveCode(String type)
    {
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.startsWith("string")) //$NON-NLS-1$
        {
            return "\"S\""; //$NON-NLS-1$
        }
        if (lower.startsWith("number")) //$NON-NLS-1$
        {
            return "\"N\""; //$NON-NLS-1$
        }
        if (lower.startsWith("boolean")) //$NON-NLS-1$
        {
            return "\"B\""; //$NON-NLS-1$
        }
        if (lower.startsWith("date")) //$NON-NLS-1$
        {
            return "\"D\""; //$NON-NLS-1$
        }
        return null;
    }

    private Member member(String producedTypeUuid, String memberUuid)
    {
        Produced produced = producedTypeUuid == null ? null : byTypeId.get(producedTypeUuid.toLowerCase(Locale.ROOT));
        if (produced == null || memberUuid == null)
        {
            return null;
        }
        Map<String, Member> index = members.get(produced.object);
        return index == null ? null : index.get(memberUuid.toLowerCase(Locale.ROOT));
    }
}
