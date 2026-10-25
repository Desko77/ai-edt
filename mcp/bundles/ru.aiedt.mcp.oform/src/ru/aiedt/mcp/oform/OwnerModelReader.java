/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdtype.AbstractMdType;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Token;
import ru.aiedt.mcp.oform.layout.StandardAttributes;
import ru.aiedt.mcp.oform.layout.TypePattern;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Reads an {@link OwnerModel} off the EDT model. The composer never calls this: a test builds
 * the description by hand, and only {@code create_form} and {@code describe_owner} go through
 * the configuration.
 */
public final class OwnerModelReader
{
    /** What the read produced. */
    public static final class Read
    {
        public final OwnerModel model;

        /** Why the owner was not read, or {@code null}. */
        public final String error;

        private Read(OwnerModel model, String error)
        {
            this.model = model;
            this.error = error;
        }
    }

    private OwnerModelReader()
    {
        // static utility
    }

    /**
     * The owner's description.
     *
     * @param project the project
     * @param kind the owner's kind, English singular
     * @param name the owner's name
     * @param language the language of synonyms
     * @return the description, or the reason it was not read
     */
    public static Read read(IProject project, String kind, String name, String language)
    {
        try
        {
            return UiSync.call(() -> readOnUi(project, kind, name, language == null ? "ru" : language)); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            return new Read(null, e.getMessage() == null ? "The owner was not read." : e.getMessage()); //$NON-NLS-1$
        }
    }

    private static Read readOnUi(IProject project, String kind, String name, String language)
    {
        Activator activator = Activator.getDefault();
        IConfigurationProvider provider = activator == null ? null : activator.getConfigurationProvider();
        if (provider == null)
        {
            return new Read(null, "No configuration is loaded."); //$NON-NLS-1$
        }
        Configuration configuration = provider.getConfiguration(project);
        if (configuration == null)
        {
            return new Read(null, "No configuration is loaded for " + project.getName() + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        java.util.Map<String, String> typeByUuid = new java.util.HashMap<>();
        java.util.Map<String, String> uuidByType = new java.util.HashMap<>();
        index(configuration, typeByUuid, uuidByType);
        MdObject owner = MetadataTypeCatalog.findObject(configuration, kind, name);
        if (owner == null)
        {
            return new Read(null, "Owner not found: " + kind + "." + name); //$NON-NLS-1$ //$NON-NLS-2$
        }
        OwnerModel.Builder builder = OwnerModel.builder(kind, name);
        fillProduced(builder, owner, kind, name, typeByUuid);
        String refUuid = builderRef(owner);
        String refName = refUuid == null ? null : typeByUuid.get(refUuid);
        addStandards(builder, owner, kind, refUuid, refName);
        for (MdObject attribute : list(BmObjectHelper.getAttributes(owner)))
        {
            builder.attribute(member(attribute, language, uuidByType));
        }
        for (MdObject section : list(BmObjectHelper.getTabularSections(owner)))
        {
            builder.section(section(section, kind, name, language, uuidByType, typeByUuid));
        }
        for (MdObject dimension : reflectList(owner, "getDimensions")) //$NON-NLS-1$
        {
            builder.dimension(member(dimension, language, uuidByType));
        }
        for (MdObject resource : reflectList(owner, "getResources")) //$NON-NLS-1$
        {
            builder.resource(member(resource, language, uuidByType));
        }
        return new Read(builder.build(), null);
    }

    private static void index(Configuration configuration, java.util.Map<String, String> typeByUuid,
        java.util.Map<String, String> uuidByType)
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
                if (object.getName() == null)
                {
                    continue;
                }
                indexProduced(object, kind, object.getName(), typeByUuid, uuidByType);
                for (MdObject section : list(BmObjectHelper.getTabularSections(object)))
                {
                    if (section.getName() != null)
                    {
                        indexProduced(section, kind + "TabularSection", object.getName() + "." + section.getName(), //$NON-NLS-1$ //$NON-NLS-2$
                            typeByUuid, uuidByType);
                    }
                }
            }
        }
    }

    private static void indexProduced(MdObject object, String kind, String name, java.util.Map<String, String> typeByUuid,
        java.util.Map<String, String> uuidByType)
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
                String typeName = kind + suffix + "." + name; //$NON-NLS-1$
                typeByUuid.put(typeId, typeName);
                uuidByType.put(typeName.toLowerCase(Locale.ROOT), typeId);
            }
        }
    }

    private static void fillProduced(OwnerModel.Builder builder, MdObject owner, String kind, String name,
        java.util.Map<String, String> typeByUuid)
    {
        String objectUuid = producedId(owner, "objectType"); //$NON-NLS-1$
        String listUuid = producedId(owner, "listType"); //$NON-NLS-1$
        String recordUuid = producedId(owner, "recordManagerType"); //$NON-NLS-1$
        String setUuid = producedId(owner, "recordSetType"); //$NON-NLS-1$
        String refUuid = producedId(owner, "refType"); //$NON-NLS-1$
        if (objectUuid != null)
        {
            builder.objectType(objectUuid, nameOf(typeByUuid, objectUuid, kind + "Object." + name)); //$NON-NLS-1$
        }
        if (listUuid != null)
        {
            builder.listType(listUuid, nameOf(typeByUuid, listUuid, kind + "List." + name)); //$NON-NLS-1$
        }
        if (recordUuid != null)
        {
            builder.recordType(recordUuid, nameOf(typeByUuid, recordUuid, kind + "RecordManager." + name)); //$NON-NLS-1$
        }
        if (setUuid != null)
        {
            builder.recordSetType(setUuid, nameOf(typeByUuid, setUuid, kind + "RecordSet." + name)); //$NON-NLS-1$
        }
        if (refUuid != null)
        {
            builder.refType(refUuid, nameOf(typeByUuid, refUuid, kind + "Ref." + name)); //$NON-NLS-1$
        }
    }

    private static String builderRef(MdObject owner)
    {
        return producedId(owner, "refType"); //$NON-NLS-1$
    }

    private static String nameOf(java.util.Map<String, String> typeByUuid, String uuid, String fallback)
    {
        String named = typeByUuid.get(uuid);
        return named == null ? fallback : named;
    }

    private static void addStandards(OwnerModel.Builder builder, MdObject owner, String kind, String refUuid, String refName)
    {
        String key = kind.toLowerCase(Locale.ROOT);
        if ("catalog".equals(key) || "chartofcharacteristictypes".equals(key) || "exchangeplan".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            if (intFeature(owner, "codeLength") > 0) //$NON-NLS-1$
            {
                coded(builder, owner, kind, "Код", false); //$NON-NLS-1$
            }
            if (intFeature(owner, "descriptionLength") > 0) //$NON-NLS-1$
            {
                coded(builder, owner, kind, "Наименование", false); //$NON-NLS-1$
            }
            if ("exchangeplan".equals(key)) //$NON-NLS-1$
            {
                coded(builder, owner, kind, "НомерОтправленного", false); //$NON-NLS-1$
                coded(builder, owner, kind, "НомерПринятого", false); //$NON-NLS-1$
            }
            if (!"exchangeplan".equals(key) && hierarchical(owner) && refUuid != null) //$NON-NLS-1$
            {
                Integer code = StandardAttributes.codeOf(kind, "Родитель"); //$NON-NLS-1$
                if (code != null)
                {
                    builder.standard(new OwnerModel.Attribute("Родитель", "Родитель", null, code, true, //$NON-NLS-1$ //$NON-NLS-2$
                        TypePattern.ofProducedType(refUuid), refName == null ? kind + "Ref" : refName)); //$NON-NLS-1$
                }
            }
            if ("chartofcharacteristictypes".equals(key)) //$NON-NLS-1$
            {
                coded(builder, owner, kind, "ТипЗначения", false); //$NON-NLS-1$
            }
        }
        else if ("document".equals(key) || "businessprocess".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            coded(builder, owner, kind, "Номер", false); //$NON-NLS-1$
            coded(builder, owner, kind, "Дата", false); //$NON-NLS-1$
            if ("document".equals(key)) //$NON-NLS-1$
            {
                // the corpus measured no code for Проведен, so it is described and not placed
                builder.standard(new OwnerModel.Attribute("Проведен", "Проведен", null, null, false, null, "Boolean")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        else if ("task".equals(key)) //$NON-NLS-1$
        {
            coded(builder, owner, kind, "Дата", false); //$NON-NLS-1$
            coded(builder, owner, kind, "Наименование", false); //$NON-NLS-1$
            coded(builder, owner, kind, "Выполнена", false); //$NON-NLS-1$
        }
        else if ("informationregister".equals(key)) //$NON-NLS-1$
        {
            coded(builder, owner, kind, "Период", false); //$NON-NLS-1$
        }
    }

    private static void coded(OwnerModel.Builder builder, MdObject owner, String kind, String name, boolean folderOnly)
    {
        Integer code = StandardAttributes.codeOf(kind, name);
        if (code == null)
        {
            return;
        }
        Typed typed = standardType(owner, name);
        builder.standard(new OwnerModel.Attribute(name, name, null, code, folderOnly, typed.pattern, typed.text));
    }

    /**
     * The type of Код or Номер from the owner's own properties. {@code typeName} is {@code Number}
     * or {@code String} (a missing type is a string). {@code variable == null} is a fixed string,
     * which is what a code and a number record when the allowed length is not set.
     *
     * @param typeName the owner's code or number type, or {@code null}
     * @param length the owner's code or number length
     * @param variable {@code true} for a variable string, {@code false} for a fixed one, {@code null}
     *        when the owner does not say
     * @return the pattern
     */
    static ListNode codePattern(String typeName, int length, Boolean variable)
    {
        if (typeName != null && "number".equals(typeName.toLowerCase(Locale.ROOT))) //$NON-NLS-1$
        {
            return numberPattern(Integer.valueOf(length), Integer.valueOf(0), Boolean.FALSE);
        }
        return stringPattern(length, variable != null && variable.booleanValue());
    }

    /**
     * A number pattern. A null precision is the letter alone, and then the field takes no format.
     *
     * @param precision the digits, or {@code null}
     * @param scale the fraction digits, or {@code null} for none
     * @param nonNegative {@code true} when the number cannot be negative
     * @return the pattern
     */
    static ListNode numberPattern(Integer precision, Integer scale, Boolean nonNegative)
    {
        ListNode item = letter("\"N\""); //$NON-NLS-1$
        if (precision != null)
        {
            item.items().add(new Token(Integer.toString(precision.intValue())));
            item.items().add(new Token(Integer.toString(scale == null ? 0 : scale.intValue())));
            item.items().add(new Token(Boolean.TRUE.equals(nonNegative) ? "1" : "0")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return wrap(item);
    }

    /**
     * A string pattern. A length of zero is the letter alone: the corpus records no {@code {"S",0}}.
     *
     * @param length the length
     * @param variable {@code true} for a variable length, {@code false} for a fixed one
     * @return the pattern
     */
    static ListNode stringPattern(int length, boolean variable)
    {
        ListNode item = letter("\"S\""); //$NON-NLS-1$
        if (length > 0)
        {
            item.items().add(new Token(Integer.toString(length)));
            item.items().add(new Token(variable ? "1" : "0")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return wrap(item);
    }

    /**
     * A date pattern. {@code Date}, {@code DateTime} and {@code Time} are the fractions tokens
     * {@code D}, {@code DT} and {@code T}. A null name is the letter alone, and then the field
     * takes no format.
     *
     * @param fractionsName the date fractions, or {@code null}
     * @return the pattern
     */
    static ListNode datePattern(String fractionsName)
    {
        ListNode item = letter("\"D\""); //$NON-NLS-1$
        String token = dateToken(fractionsName);
        if (token != null)
        {
            item.items().add(new Token("\"" + token + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return wrap(item);
    }

    private static Typed standardType(MdObject owner, String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        if ("код".equals(lower)) //$NON-NLS-1$
        {
            return codeOrNumber(owner, "codeType", "codeLength", "codeAllowedLength"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("номер".equals(lower)) //$NON-NLS-1$
        {
            return codeOrNumber(owner, "numberType", "numberLength", "numberAllowedLength"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("наименование".equals(lower)) //$NON-NLS-1$
        {
            int length = intFeature(owner, "descriptionLength"); //$NON-NLS-1$
            return new Typed(stringPattern(length, true), length > 0 ? "String(" + length + ")" : "String"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("дата".equals(lower)) //$NON-NLS-1$
        {
            // a document, a task and a business process date includes the time
            return new Typed(datePattern("DateTime"), "DateTime"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("период".equals(lower)) //$NON-NLS-1$
        {
            String fractions = periodFractions(owner);
            return new Typed(datePattern(fractions), fractions == null ? "Date" : fractions); //$NON-NLS-1$
        }
        if ("выполнена".equals(lower)) //$NON-NLS-1$
        {
            return new Typed(wrap(letter("\"B\"")), "Boolean"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new Typed(wrap(letter("\"S\"")), "String"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Typed codeOrNumber(EObject owner, String typeFeature, String lengthFeature, String allowedFeature)
    {
        int length = intFeature(owner, lengthFeature);
        String typeName = enumIfSet(owner, typeFeature);
        Boolean variable = null;
        String allowed = enumIfSet(owner, allowedFeature);
        if (allowed != null)
        {
            variable = Boolean.valueOf("variable".equals(allowed.toLowerCase(Locale.ROOT))); //$NON-NLS-1$
        }
        ListNode pattern = codePattern(typeName, length, variable);
        boolean number = typeName != null && "number".equals(typeName.toLowerCase(Locale.ROOT)); //$NON-NLS-1$
        String text = number ? "Number(" + length + ")" : length > 0 ? "String(" + length + ")" : "String"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        return new Typed(pattern, text);
    }

    /** Second and a recorder position are a date with time; a day and coarser are a date. */
    private static String periodFractions(EObject owner)
    {
        String name = enumIfSet(owner, "informationRegisterPeriodicity"); //$NON-NLS-1$
        if (name == null || "nonperiodical".equals(name.toLowerCase(Locale.ROOT))) //$NON-NLS-1$
        {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if ("second".equals(lower) || "recorderposition".equals(lower)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "DateTime"; //$NON-NLS-1$
        }
        return "Date"; //$NON-NLS-1$
    }

    private static String dateToken(String fractionsName)
    {
        if (fractionsName == null)
        {
            return null;
        }
        switch (fractionsName.toLowerCase(Locale.ROOT))
        {
        case "date": //$NON-NLS-1$
            return "D"; //$NON-NLS-1$
        case "datetime": //$NON-NLS-1$
            return "DT"; //$NON-NLS-1$
        case "time": //$NON-NLS-1$
            return "T"; //$NON-NLS-1$
        default:
            return null;
        }
    }

    private static ListNode letter(String quoted)
    {
        ListNode item = new ListNode();
        item.items().add(new Token(quoted));
        return item;
    }

    private static ListNode wrap(ListNode item)
    {
        ListNode pattern = new ListNode();
        pattern.items().add(new Token("\"Pattern\"")); //$NON-NLS-1$
        pattern.items().add(item);
        return pattern;
    }

    /** A pattern and the type in words that goes with it. */
    private static final class Typed
    {
        final ListNode pattern;

        final String text;

        Typed(ListNode pattern, String text)
        {
            this.pattern = pattern;
            this.text = text;
        }
    }

    private static OwnerModel.TabularSection section(MdObject section, String kind, String ownerName, String language,
        java.util.Map<String, String> uuidByType, java.util.Map<String, String> typeByUuid)
    {
        String uuid = section.getUuid() == null ? null : section.getUuid().toString().toLowerCase(Locale.ROOT);
        String rowUuid = producedId(section, "objectType"); //$NON-NLS-1$
        String rowName = rowUuid == null ? null : typeByUuid.get(rowUuid);
        if (rowName == null)
        {
            rowName = kind + "TabularSectionObject." + ownerName + "." + section.getName(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<OwnerModel.Attribute> attributes = new ArrayList<>();
        for (MdObject attribute : list(BmObjectHelper.getAttributes(section)))
        {
            attributes.add(member(attribute, language, uuidByType));
        }
        String synonym = synonym(section, language);
        return new OwnerModel.TabularSection(section.getName(), synonym, uuid, rowUuid, rowName, attributes);
    }

    private static OwnerModel.Attribute member(MdObject attribute, String language, java.util.Map<String, String> uuidByType)
    {
        String uuid = attribute.getUuid() == null ? null : attribute.getUuid().toString().toLowerCase(Locale.ROOT);
        Set<String> types;
        try
        {
            types = BmDefinedTypeHelper.readValueTypeNames(attribute);
        }
        catch (RuntimeException e)
        {
            types = new TreeSet<>();
        }
        Qualifiers qualifiers = qualifiersOf(attribute);
        return new OwnerModel.Attribute(attribute.getName(), synonym(attribute, language), uuid, null, false,
            pattern(types, uuidByType, qualifiers), words(types, qualifiers));
    }

    private static String words(Set<String> types, Qualifiers qualifiers)
    {
        if (types == null || types.isEmpty())
        {
            return "Any"; //$NON-NLS-1$
        }
        List<String> parts = new ArrayList<>();
        for (String type : new TreeSet<>(types))
        {
            String word = word(type, qualifiers);
            if (word != null)
            {
                parts.add(word);
            }
        }
        return parts.isEmpty() ? "Any" : String.join(", ", parts); //$NON-NLS-1$
    }

    private static String word(String type, Qualifiers qualifiers)
    {
        String primitive = primitive(type);
        if (primitive == null)
        {
            return type;
        }
        if ("\"S\"".equals(primitive)) //$NON-NLS-1$
        {
            return qualifiers.length != null && qualifiers.length.intValue() > 0 ? "String(" + qualifiers.length + ")" : "String"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if ("\"N\"".equals(primitive)) //$NON-NLS-1$
        {
            if (qualifiers.precision == null)
            {
                return "Number"; //$NON-NLS-1$
            }
            int scale = qualifiers.scale == null ? 0 : qualifiers.scale.intValue();
            return scale > 0 ? "Number(" + qualifiers.precision + "," + scale + ")" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : "Number(" + qualifiers.precision + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("\"D\"".equals(primitive)) //$NON-NLS-1$
        {
            return qualifiers.dateFractions == null ? "Date" : qualifiers.dateFractions; //$NON-NLS-1$
        }
        if ("\"B\"".equals(primitive)) //$NON-NLS-1$
        {
            return "Boolean"; //$NON-NLS-1$
        }
        return type;
    }

    /** The pattern of an attribute, with the qualifiers the type description actually carries. */
    private static ListNode pattern(Set<String> types, java.util.Map<String, String> uuidByType, Qualifiers qualifiers)
    {
        ListNode pattern = new ListNode();
        pattern.items().add(new Token("\"Pattern\"")); //$NON-NLS-1$
        if (types == null)
        {
            return pattern;
        }
        for (String type : new TreeSet<>(types))
        {
            ListNode item = item(type, uuidByType, qualifiers);
            if (item != null)
            {
                pattern.items().add(item);
            }
        }
        return pattern;
    }

    private static ListNode item(String type, java.util.Map<String, String> uuidByType, Qualifiers qualifiers)
    {
        String produced = uuidByType.get(type.toLowerCase(Locale.ROOT));
        if (produced != null)
        {
            ListNode item = letter("\"#\""); //$NON-NLS-1$
            item.items().add(new Token(produced));
            return item;
        }
        String primitive = primitive(type);
        if ("\"S\"".equals(primitive)) //$NON-NLS-1$
        {
            int length = qualifiers.length == null ? 0 : qualifiers.length.intValue();
            return body(stringPattern(length, qualifiers.variable));
        }
        if ("\"N\"".equals(primitive)) //$NON-NLS-1$
        {
            return body(numberPattern(qualifiers.precision, qualifiers.scale, qualifiers.nonNegative));
        }
        if ("\"D\"".equals(primitive)) //$NON-NLS-1$
        {
            return body(datePattern(qualifiers.dateFractions));
        }
        if ("\"B\"".equals(primitive)) //$NON-NLS-1$
        {
            return letter("\"B\""); //$NON-NLS-1$
        }
        return null;
    }

    private static ListNode body(ListNode pattern)
    {
        return (ListNode)pattern.items().get(1);
    }

    /**
     * Qualifiers taken off a type description only when the model has set them. An unset
     * qualifier stays null, and the pattern then keeps the letter alone.
     */
    private static Qualifiers qualifiersOf(MdObject attribute)
    {
        Qualifiers qualifiers = new Qualifiers();
        Object type = attribute == null ? null : feature(attribute, "type"); //$NON-NLS-1$
        if (!(type instanceof EObject))
        {
            return qualifiers;
        }
        EObject description = (EObject)type;
        EObject number = qualifier(description, "numberQualifiers"); //$NON-NLS-1$
        if (number != null)
        {
            qualifiers.precision = asInt(setFeature(number, "precision")); //$NON-NLS-1$
            qualifiers.scale = asInt(setFeature(number, "scale")); //$NON-NLS-1$
            qualifiers.nonNegative = Boolean.valueOf(Boolean.TRUE.equals(feature(number, "nonNegative"))); //$NON-NLS-1$
        }
        EObject string = qualifier(description, "stringQualifiers"); //$NON-NLS-1$
        if (string != null)
        {
            qualifiers.length = asInt(setFeature(string, "length")); //$NON-NLS-1$
            Object fixed = setFeature(string, "fixed"); //$NON-NLS-1$
            qualifiers.variable = !(fixed instanceof Boolean) || !((Boolean)fixed).booleanValue();
        }
        EObject date = qualifier(description, "dateQualifiers"); //$NON-NLS-1$
        if (date != null)
        {
            qualifiers.dateFractions = enumName(setFeature(date, "dateFractions")); //$NON-NLS-1$
        }
        return qualifiers;
    }

    private static EObject qualifier(EObject description, String name)
    {
        Object value = setFeature(description, name);
        return value instanceof EObject ? (EObject)value : null;
    }

    /** The value of a feature the model has set, or {@code null} when it has not. */
    private static Object setFeature(EObject object, String name)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(name);
        if (feature == null || !object.eIsSet(feature))
        {
            return null;
        }
        return object.eGet(feature);
    }

    private static String enumIfSet(EObject object, String name)
    {
        return enumName(setFeature(object, name));
    }

    private static String enumName(Object value)
    {
        return value instanceof Enumerator ? ((Enumerator)value).getName() : null;
    }

    private static Integer asInt(Object value)
    {
        return value instanceof Number ? Integer.valueOf(((Number)value).intValue()) : null;
    }

    /** Qualifiers of one type description. A null field is a qualifier the model did not set. */
    private static final class Qualifiers
    {
        Integer precision;

        Integer scale;

        Boolean nonNegative;

        Integer length;

        boolean variable = true;

        String dateFractions;
    }

    private static String primitive(String type)
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

    private static String synonym(MdObject object, String language)
    {
        String text = object.getSynonym() == null ? null : object.getSynonym().get(language);
        if (text == null || text.isEmpty())
        {
            return OrdinaryFormCreation.synonymOf(object.getName());
        }
        return text;
    }

    private static String producedId(MdObject owner, String producedType)
    {
        Object produced = feature(owner, "producedTypes"); //$NON-NLS-1$
        if (!(produced instanceof EObject))
        {
            return null;
        }
        EObject types = (EObject)produced;
        for (EReference reference : types.eClass().getEAllReferences())
        {
            if (producedType.equals(reference.getName()))
            {
                Object item = types.eGet(reference);
                if (item instanceof AbstractMdType && ((AbstractMdType)item).getTypeId() != null)
                {
                    return ((AbstractMdType)item).getTypeId().toString().toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static boolean hierarchical(EObject object)
    {
        Object value = feature(object, "hierarchical"); //$NON-NLS-1$
        return !(value instanceof Boolean) || ((Boolean)value).booleanValue();
    }

    private static int intFeature(EObject object, String name)
    {
        Object value = feature(object, name);
        return value instanceof Number ? ((Number)value).intValue() : 0;
    }

    private static Object feature(EObject object, String name)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(name);
        return feature == null ? null : object.eGet(feature);
    }

    private static List<MdObject> list(EList<MdObject> value)
    {
        return value == null ? List.of() : value;
    }

    @SuppressWarnings("unchecked")
    private static List<MdObject> reflectList(MdObject owner, String getter)
    {
        try
        {
            Object value = owner.getClass().getMethod(getter).invoke(owner);
            if (value instanceof EList)
            {
                return (EList<MdObject>)value;
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return List.of();
        }
        return List.of();
    }
}
