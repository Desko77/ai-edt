/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Properties whose value is a list rather than a scalar, written through
 * {@code set_object_property} with a {@code listMode}.
 * <p>
 * Four of them sit on the configuration root: the purposes an application is used for, the
 * permissions a mobile application requires in either of the two spellings the platform has had,
 * and the functionalities it uses. The first two are lists of enumeration literals, the third a
 * list of objects, and the fourth a single object holding two lists of its own.
 * </p>
 * <p>
 * Nothing here names an EDT type. The shape of a value is read off the model itself - whether the
 * feature holds many values, whether its type is an enumeration or a class, which literals that
 * enumeration declares - so the same code answers for whatever the model carries in a given
 * release, and a test can exercise it against a model built for the test.
 * </p>
 * <p>
 * A value arrives as JSON text inside {@code propertyValue}, because that argument is declared a
 * string and the declaration is not changed. Every element is parsed and checked before anything is
 * written: a call that names one bad literal writes none of the good ones.
 * </p>
 */
public final class ConfigurationListProperties
{
    /** What a call does to the list it names. */
    public enum ListMode
    {
        /** The list becomes exactly what was passed. */
        REPLACE,
        /** What was passed is appended, in the order given. */
        ADD,
        /** What was passed is taken out; the order of the rest is kept. */
        REMOVE,
        /** The list becomes empty and the value is not read. */
        CLEAR;

        /**
         * Reads the mode a caller asked for.
         *
         * @param raw what the caller passed, or <code>null</code> for the default.
         * @return the mode, or <code>null</code> when the word is not one of these
         */
        public static ListMode parse(String raw)
        {
            if (raw == null || raw.trim().isEmpty())
            {
                return REPLACE;
            }
            for (ListMode mode : values())
            {
                if (mode.name().equalsIgnoreCase(raw.trim()))
                {
                    return mode;
                }
            }
            return null;
        }

        /** @return the four words a caller may pass, for a refusal that names them */
        public static String spelled()
        {
            StringBuilder sb = new StringBuilder();
            for (ListMode mode : values())
            {
                if (sb.length() > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(mode.name().toLowerCase());
            }
            return sb.toString();
        }
    }

    /**
     * The single-valued property that holds lists inside it rather than being one.
     * <p>
     * Named rather than found by shape: "a single containment whose class has only many-valued
     * features" would also match objects that have nothing to do with this, and matching them would
     * silently take over properties this was never written for.
     * </p>
     */
    private static final String NESTED_LISTS_PROPERTY = "usedMobileApplicationFunctionalities"; //$NON-NLS-1$

    /** What a call did, or why it did nothing. */
    public static final class Outcome
    {
        /** Why the call was refused, or <code>null</code> when it was carried out. */
        public final String refusal;

        /** The property as it stands (or would stand) after the call, in its own shape. */
        public final JsonElement value;

        /** What was done, for the message a caller reads. */
        public final String message;

        private Outcome(String refusal, JsonElement value, String message)
        {
            this.refusal = refusal;
            this.value = value;
            this.message = message;
        }

        static Outcome refused(String why)
        {
            return new Outcome(why, null, null);
        }

        static Outcome done(JsonElement value, String message)
        {
            return new Outcome(null, value, message);
        }

        /** @return true when the call was carried out */
        public boolean ok()
        {
            return refusal == null;
        }
    }

    private ConfigurationListProperties()
    {
    }

    /**
     * Whether this property is written as a list rather than as a scalar.
     *
     * @param owner the object carrying it.
     * @param propertyName the property.
     * @return true when {@link #apply} handles it
     */
    public static boolean isListShaped(EObject owner, String propertyName)
    {
        EStructuralFeature feature = featureOf(owner, propertyName);
        if (feature == null)
        {
            return false;
        }
        return feature.isMany() || NESTED_LISTS_PROPERTY.equalsIgnoreCase(propertyName);
    }

    /**
     * Writes a list-shaped property.
     * <p>
     * Everything is parsed and checked first, so a refusal leaves the model untouched. The value
     * handed back is the property in its own shape: an array for a list, an object for the one
     * property that holds lists inside it.
     * </p>
     *
     * @param owner the object carrying the property.
     * @param propertyName the property.
     * @param rawValue the JSON text the caller passed; ignored for {@link ListMode#CLEAR}.
     * @param mode what to do with the list.
     * @param dryRun when true nothing is written and the value shows what would have been.
     * @param defaultLanguageCode the language a text without one belongs to.
     * @return what was done, or why it was not
     */
    public static Outcome apply(EObject owner, String propertyName, String rawValue, ListMode mode,
        boolean dryRun, String defaultLanguageCode)
    {
        EStructuralFeature feature = featureOf(owner, propertyName);
        if (feature == null)
        {
            return Outcome.refused("'" + propertyName + "' is not a property of " //$NON-NLS-1$ //$NON-NLS-2$
                + owner.eClass().getName());
        }
        if (!feature.isMany() && NESTED_LISTS_PROPERTY.equalsIgnoreCase(propertyName))
        {
            return applyNested(owner, feature, rawValue, mode, dryRun, defaultLanguageCode);
        }
        if (!feature.isMany())
        {
            return Outcome.refused("'" + propertyName + "' holds one value, not a list"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return applyToList(feature, listOf(owner, feature), rawValue, mode, dryRun,
            propertyName, defaultLanguageCode);
    }

    /**
     * Reads a list-shaped property without changing it.
     *
     * @param owner the object carrying the property.
     * @param propertyName the property.
     * @return the property in its own shape, or <code>null</code> when it is not list-shaped
     */
    public static JsonElement read(EObject owner, String propertyName)
    {
        EStructuralFeature feature = featureOf(owner, propertyName);
        if (feature == null)
        {
            return null;
        }
        if (feature.isMany())
        {
            return describeValues(feature, listOf(owner, feature));
        }
        if (NESTED_LISTS_PROPERTY.equalsIgnoreCase(propertyName))
        {
            Object held = owner.eGet(feature);
            return held instanceof EObject ? describeNested((EObject)held) : new JsonObject();
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // The flat lists
    // ---------------------------------------------------------------------

    private static Outcome applyToList(EStructuralFeature feature, List<Object> live,
        String rawValue, ListMode mode, boolean dryRun, String label, String defaultLanguageCode)
    {
        if (mode == ListMode.CLEAR)
        {
            int had = live.size();
            if (!dryRun)
            {
                live.clear();
            }
            return Outcome.done(new JsonArray(), label + " cleared, " + had + " removed"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        JsonArray given;
        try
        {
            given = asArray(rawValue);
        }
        catch (IllegalArgumentException bad)
        {
            return Outcome.refused(bad.getMessage());
        }
        if (feature.getEType() instanceof EEnum)
        {
            return applyLiterals(feature, live, given, mode, dryRun, label);
        }
        if (feature.getEType() instanceof EClass)
        {
            return applyComposites(feature, live, given, mode, dryRun, label,
                defaultLanguageCode);
        }
        return Outcome.refused("'" + label + "' holds values of a kind this cannot write: " //$NON-NLS-1$ //$NON-NLS-2$
            + feature.getEType().getName());
    }

    private static Outcome applyLiterals(EStructuralFeature feature, List<Object> live,
        JsonArray given, ListMode mode, boolean dryRun, String label)
    {
        EEnum type = (EEnum)feature.getEType();
        List<EEnumLiteral> wanted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement element : given)
        {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            {
                return Outcome.refused("'" + label + "' takes literal names; " + element //$NON-NLS-1$ //$NON-NLS-2$
                    + " is not one"); //$NON-NLS-1$
            }
            String spelling = element.getAsString().trim();
            EEnumLiteral literal = literalOf(type, spelling);
            if (literal == null)
            {
                return Outcome.refused("'" + spelling + "' is not a value of " + type.getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + ". It takes: " + literalsOf(type)); //$NON-NLS-1$
            }
            if (!seen.add(literal.getName()))
            {
                return Outcome.refused("'" + literal.getName() //$NON-NLS-1$
                    + "' is given twice in one call"); //$NON-NLS-1$
            }
            wanted.add(literal);
        }
        List<Object> after = new ArrayList<>(live);
        String note;
        if (mode == ListMode.REPLACE)
        {
            after.clear();
            for (EEnumLiteral literal : wanted)
            {
                after.add(literal.getInstance());
            }
            note = label + " set to " + wanted.size() + " value(s)"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (mode == ListMode.ADD)
        {
            for (EEnumLiteral literal : wanted)
            {
                if (holds(after, literal))
                {
                    return Outcome.refused("'" + literal.getName() + "' is already in " + label); //$NON-NLS-1$ //$NON-NLS-2$
                }
                after.add(literal.getInstance());
            }
            note = wanted.size() + " value(s) added to " + label; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            for (EEnumLiteral literal : wanted)
            {
                if (!holds(after, literal))
                {
                    return Outcome.refused("'" + literal.getName() + "' is not in " + label); //$NON-NLS-1$ //$NON-NLS-2$
                }
                after.removeIf(held -> sameLiteral(held, literal));
            }
            note = wanted.size() + " value(s) removed from " + label; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!dryRun)
        {
            live.clear();
            live.addAll(after);
        }
        return Outcome.done(describeValues(feature, after), note);
    }

    private static Outcome applyComposites(EStructuralFeature feature, List<Object> live,
        JsonArray given, ListMode mode, boolean dryRun, String label, String defaultLanguageCode)
    {
        EClass elementType = (EClass)feature.getEType();
        EStructuralFeature key = identityFeature(elementType);
        if (key == null)
        {
            return Outcome.refused("'" + label + "' holds " + elementType.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ", which has no single value telling one entry from another"); //$NON-NLS-1$
        }
        List<EObject> built = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement element : given)
        {
            if (mode == ListMode.REMOVE)
            {
                String spelling = element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    ? element.getAsString().trim()
                    : (element.isJsonObject() && element.getAsJsonObject().has(key.getName())
                        ? element.getAsJsonObject().get(key.getName()).getAsString().trim() : null);
                if (spelling == null)
                {
                    return Outcome.refused("removing from '" + label + "' takes the " //$NON-NLS-1$ //$NON-NLS-2$
                        + key.getName() + " of the entry to remove"); //$NON-NLS-1$
                }
                EEnumLiteral literal = literalOf((EEnum)key.getEType(), spelling);
                if (literal == null)
                {
                    return Outcome.refused("'" + spelling + "' is not a value of " //$NON-NLS-1$ //$NON-NLS-2$
                        + key.getEType().getName() + ". It takes: " //$NON-NLS-1$
                        + literalsOf((EEnum)key.getEType()));
                }
                if (!seen.add(literal.getName()))
                {
                    return Outcome.refused("'" + literal.getName() //$NON-NLS-1$
                        + "' is given twice in one call"); //$NON-NLS-1$
                }
                EObject present = findByKey(live, key, literal);
                if (present == null)
                {
                    return Outcome.refused("'" + literal.getName() + "' is not in " + label); //$NON-NLS-1$ //$NON-NLS-2$
                }
                built.add(present);
                continue;
            }
            if (!element.isJsonObject())
            {
                return Outcome.refused("'" + label + "' takes objects with a " + key.getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + "; " + element + " is not one"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            EObject made;
            try
            {
                made = buildComposite(elementType, element.getAsJsonObject(), key, label,
                    defaultLanguageCode);
            }
            catch (IllegalArgumentException bad)
            {
                return Outcome.refused(bad.getMessage());
            }
            String keyName = nameOf(key.getEType(), made.eGet(key));
            if (!seen.add(keyName))
            {
                return Outcome.refused("'" + keyName + "' is given twice in one call"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            built.add(made);
        }
        List<Object> after = new ArrayList<>(live);
        String note;
        if (mode == ListMode.REPLACE)
        {
            after.clear();
            after.addAll(built);
            note = label + " set to " + built.size() + " entry(ies)"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (mode == ListMode.ADD)
        {
            for (EObject made : built)
            {
                if (findByKeyValue(after, key, made.eGet(key)) != null)
                {
                    return Outcome.refused("'" + nameOf(key.getEType(), made.eGet(key)) //$NON-NLS-1$
                        + "' is already in " + label); //$NON-NLS-1$
                }
                after.add(made);
            }
            note = built.size() + " entry(ies) added to " + label; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            after.removeAll(built);
            note = built.size() + " entry(ies) removed from " + label; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!dryRun)
        {
            live.clear();
            live.addAll(after);
        }
        return Outcome.done(describeValues(feature, after), note);
    }

    // ---------------------------------------------------------------------
    // The one property that holds lists inside it
    // ---------------------------------------------------------------------

    private static Outcome applyNested(EObject owner, EStructuralFeature feature, String rawValue,
        ListMode mode, boolean dryRun, String defaultLanguageCode)
    {
        if (!(feature.getEType() instanceof EClass))
        {
            return Outcome.refused("'" + NESTED_LISTS_PROPERTY //$NON-NLS-1$
                + "' does not hold an object in this model"); //$NON-NLS-1$
        }
        EClass holderType = (EClass)feature.getEType();
        Object held = owner.eGet(feature);
        EObject holder = held instanceof EObject ? (EObject)held : null;
        boolean holderIsNew = holder == null;
        if (holderIsNew)
        {
            if (mode == ListMode.CLEAR)
            {
                return Outcome.done(new JsonObject(), NESTED_LISTS_PROPERTY + " holds nothing"); //$NON-NLS-1$
            }
            // A removal against nothing is still a removal: it goes through the same path and is
            // refused by name, the way removing from an empty list of any other kind is. Answering
            // success here would accept both a value that is not there and a value that is not JSON.
            holder = create(holderType);
        }
        JsonObject given = new JsonObject();
        if (mode != ListMode.CLEAR)
        {
            JsonElement parsed;
            try
            {
                parsed = parse(rawValue);
            }
            catch (IllegalArgumentException bad)
            {
                return Outcome.refused(bad.getMessage());
            }
            if (!parsed.isJsonObject())
            {
                return Outcome.refused("'" + NESTED_LISTS_PROPERTY //$NON-NLS-1$
                    + "' takes an object naming its lists: " + innerListNames(holderType)); //$NON-NLS-1$
            }
            given = parsed.getAsJsonObject();
            for (String name : given.keySet())
            {
                EStructuralFeature inner = holderType.getEStructuralFeature(name);
                if (inner == null || !inner.isMany())
                {
                    return Outcome.refused("'" + name + "' is not a list of " //$NON-NLS-1$ //$NON-NLS-2$
                        + holderType.getName() + ". It has: " + innerListNames(holderType)); //$NON-NLS-1$
                }
            }
        }
        // Every inner list is checked without being written, and only when all of them pass is any
        // of them written. Writing them one at a time would leave the first written and the second
        // refused, which is not what this promises.
        List<String> notes = new ArrayList<>();
        List<EStructuralFeature> toWrite = new ArrayList<>();
        List<String> textFor = new ArrayList<>();
        JsonObject prospective = new JsonObject();
        for (EStructuralFeature inner : holderType.getEAllStructuralFeatures())
        {
            if (!inner.isMany())
            {
                continue;
            }
            JsonElement forThisOne = given.get(inner.getName());
            if (mode != ListMode.CLEAR && forThisOne == null)
            {
                if (mode != ListMode.REPLACE)
                {
                    // add and remove leave a list the caller did not name alone.
                    prospective.add(inner.getName(), describeValues(inner, listOf(holder, inner)));
                    continue;
                }
                forThisOne = new JsonArray();
            }
            String text = mode == ListMode.CLEAR ? null : forThisOne.toString();
            Outcome one = applyToList(inner, listOf(holder, inner), text, mode, true,
                inner.getName(), defaultLanguageCode);
            if (!one.ok())
            {
                return one;
            }
            prospective.add(inner.getName(), one.value);
            notes.add(one.message);
            toWrite.add(inner);
            textFor.add(text);
        }
        if (!dryRun)
        {
            for (int i = 0; i < toWrite.size(); i++)
            {
                EStructuralFeature inner = toWrite.get(i);
                applyToList(inner, listOf(holder, inner), textFor.get(i), mode, false,
                    inner.getName(), defaultLanguageCode);
            }
            if (holderIsNew)
            {
                owner.eSet(feature, holder);
            }
        }
        // What the property would hold, assembled from what each inner list was found to become -
        // reading the holder back here would describe what is there, not what was asked for.
        return Outcome.done(prospective, String.join("; ", notes)); //$NON-NLS-1$
    }

    private static String innerListNames(EClass holderType)
    {
        StringBuilder sb = new StringBuilder();
        for (EStructuralFeature inner : holderType.getEAllStructuralFeatures())
        {
            if (!inner.isMany())
            {
                continue;
            }
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(inner.getName());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Building and describing
    // ---------------------------------------------------------------------

    private static EObject buildComposite(EClass type, JsonObject given, EStructuralFeature key,
        String label, String defaultLanguageCode)
    {
        for (String name : given.keySet())
        {
            if (type.getEStructuralFeature(name) == null)
            {
                throw new IllegalArgumentException("'" + name + "' is not a field of " //$NON-NLS-1$ //$NON-NLS-2$
                    + type.getName() + ". It has: " + fieldNames(type)); //$NON-NLS-1$
            }
        }
        if (!given.has(key.getName()))
        {
            throw new IllegalArgumentException("every entry of '" + label + "' needs a " //$NON-NLS-1$ //$NON-NLS-2$
                + key.getName());
        }
        EObject made = create(type);
        for (EStructuralFeature field : type.getEAllStructuralFeatures())
        {
            JsonElement value = given.get(field.getName());
            if (value == null || value.isJsonNull())
            {
                continue;
            }
            if (field.getEType() instanceof EEnum)
            {
                EEnumLiteral literal = literalOf((EEnum)field.getEType(), value.getAsString().trim());
                if (literal == null)
                {
                    throw new IllegalArgumentException("'" + value.getAsString() //$NON-NLS-1$
                        + "' is not a value of " + field.getEType().getName() + ". It takes: " //$NON-NLS-1$ //$NON-NLS-2$
                        + literalsOf((EEnum)field.getEType()));
                }
                made.eSet(field, literal.getInstance());
            }
            else if (made.eGet(field) instanceof EMap)
            {
                putLocalised(made, field, value, defaultLanguageCode);
            }
            else if (field.isMany())
            {
                throw new IllegalArgumentException("'" + field.getName() + "' of " //$NON-NLS-1$ //$NON-NLS-2$
                    + type.getName() + " is a list of its own and cannot be written from here"); //$NON-NLS-1$
            }
            else if (Boolean.TYPE.equals(field.getEType().getInstanceClass())
                || Boolean.class.equals(field.getEType().getInstanceClass()))
            {
                made.eSet(field, value.getAsBoolean());
            }
            else if (String.class.equals(field.getEType().getInstanceClass()))
            {
                made.eSet(field, value.getAsString());
            }
            else if (!field.isMany())
            {
                throw new IllegalArgumentException("'" + field.getName() + "' of " //$NON-NLS-1$ //$NON-NLS-2$
                    + type.getName() + " takes a " + field.getEType().getName() //$NON-NLS-1$
                    + ", which cannot be written from JSON here"); //$NON-NLS-1$
            }
        }
        return made;
    }

    /**
     * Writes a value that carries one text per language.
     * <p>
     * A plain string goes under the language the configuration is written in, an object of
     * language to text is taken as written. This is the same shape a synonym takes, and it is
     * deliberately not a second convention.
     * </p>
     */
    @SuppressWarnings("unchecked")
    private static void putLocalised(EObject made, EStructuralFeature field, JsonElement value,
        String defaultLanguageCode)
    {
        EMap<String, String> map = (EMap<String, String>)made.eGet(field);
        map.clear();
        if (value.isJsonObject())
        {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet())
            {
                String code = entry.getKey() != null ? entry.getKey().trim() : null;
                if (code == null || code.isEmpty())
                {
                    continue;
                }
                map.put(code, entry.getValue().isJsonNull() ? "" : entry.getValue().getAsString()); //$NON-NLS-1$
            }
            return;
        }
        map.put(defaultLanguageCode == null || defaultLanguageCode.isEmpty() ? "ru" //$NON-NLS-1$
            : defaultLanguageCode, value.getAsString());
    }

    private static JsonElement describeNested(EObject holder)
    {
        JsonObject out = new JsonObject();
        for (EStructuralFeature inner : holder.eClass().getEAllStructuralFeatures())
        {
            if (inner.isMany())
            {
                out.add(inner.getName(), describeValues(inner, listOf(holder, inner)));
            }
        }
        return out;
    }

    private static JsonArray describeValues(EStructuralFeature feature, List<Object> values)
    {
        JsonArray out = new JsonArray();
        for (Object value : values)
        {
            if (value instanceof EObject && !(value instanceof EEnumLiteral))
            {
                out.add(describeComposite((EObject)value));
            }
            else
            {
                out.add(new JsonPrimitive(nameOf(feature.getEType(), value)));
            }
        }
        return out;
    }

    /**
     * What a stored value is called, asked of the type that declares it.
     * <p>
     * A value held for an enumeration cannot be named by {@code toString}: a generated model
     * answers with the literal, a model built at run time answers with a dump of the literal and
     * its enumeration. Both are matched here against the literals the type declares, so the name is
     * the same either way.
     * </p>
     *
     * @param type the type the value belongs to.
     * @param value the stored value.
     * @return the literal's name, or the value as text when the type is not an enumeration
     */
    private static String nameOf(EClassifier type, Object value)
    {
        if (type instanceof EEnum)
        {
            for (EEnumLiteral literal : ((EEnum)type).getELiterals())
            {
                if (sameLiteral(value, literal))
                {
                    return literal.getName();
                }
            }
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static JsonObject describeComposite(EObject value)
    {
        JsonObject out = new JsonObject();
        for (EStructuralFeature field : value.eClass().getEAllStructuralFeatures())
        {
            Object held = value.eGet(field);
            if (held instanceof EMap)
            {
                JsonObject texts = new JsonObject();
                for (Map.Entry<String, String> entry : ((EMap<String, String>)held).entrySet())
                {
                    texts.addProperty(entry.getKey(), entry.getValue());
                }
                out.add(field.getName(), texts);
            }
            else if (held instanceof Boolean)
            {
                out.addProperty(field.getName(), (Boolean)held);
            }
            else if (held != null && !(held instanceof List))
            {
                out.addProperty(field.getName(), nameOf(field.getEType(), held));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Model reading
    // ---------------------------------------------------------------------

    private static EStructuralFeature featureOf(EObject owner, String propertyName)
    {
        if (owner == null || propertyName == null || propertyName.isEmpty())
        {
            return null;
        }
        EStructuralFeature exact = owner.eClass().getEStructuralFeature(propertyName);
        if (exact != null)
        {
            return exact;
        }
        for (EStructuralFeature feature : owner.eClass().getEAllStructuralFeatures())
        {
            if (feature.getName().equalsIgnoreCase(propertyName))
            {
                return feature;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(EObject owner, EStructuralFeature feature)
    {
        return (List<Object>)owner.eGet(feature);
    }

    private static EObject create(EClass type)
    {
        return type.getEPackage().getEFactoryInstance().create(type);
    }

    /**
     * The field that tells one entry of a list apart from another.
     * <p>
     * The first enumeration-valued field the class declares. Both classes written here name that
     * field the permission or the functionality the entry is about, and an entry is that thing;
     * the rest of the entry - whether it is used, what it says to a person - is its content.
     * </p>
     */
    private static EStructuralFeature identityFeature(EClass type)
    {
        for (EStructuralFeature feature : type.getEAllStructuralFeatures())
        {
            if (!feature.isMany() && feature.getEType() instanceof EEnum)
            {
                return feature;
            }
        }
        return null;
    }

    private static EEnumLiteral literalOf(EEnum type, String spelling)
    {
        for (EEnumLiteral literal : type.getELiterals())
        {
            if (literal.getName().equalsIgnoreCase(spelling)
                || literal.getLiteral().equalsIgnoreCase(spelling))
            {
                return literal;
            }
        }
        return null;
    }

    private static String literalsOf(EEnum type)
    {
        StringBuilder sb = new StringBuilder();
        for (EEnumLiteral literal : type.getELiterals())
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(literal.getName());
        }
        return sb.toString();
    }

    private static String fieldNames(EClass type)
    {
        StringBuilder sb = new StringBuilder();
        for (EStructuralFeature feature : type.getEAllStructuralFeatures())
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(feature.getName());
        }
        return sb.toString();
    }

    private static boolean holds(List<Object> values, EEnumLiteral literal)
    {
        for (Object value : values)
        {
            if (sameLiteral(value, literal))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean sameLiteral(Object value, EEnumLiteral literal)
    {
        if (value == null)
        {
            return false;
        }
        if (value == literal || value == literal.getInstance())
        {
            return true;
        }
        if (value instanceof EEnumLiteral)
        {
            // A model built at run time hands back the literal itself; two literals of the same
            // enumeration are the same value when they carry the same name.
            return ((EEnumLiteral)value).getName().equals(literal.getName());
        }
        String asText = String.valueOf(value);
        return asText.equals(literal.getName()) || asText.equals(literal.getLiteral());
    }

    private static EObject findByKey(List<Object> values, EStructuralFeature key,
        EEnumLiteral literal)
    {
        for (Object value : values)
        {
            if (value instanceof EObject && sameLiteral(((EObject)value).eGet(key), literal))
            {
                return (EObject)value;
            }
        }
        return null;
    }

    private static EObject findByKeyValue(List<Object> values, EStructuralFeature key,
        Object keyValue)
    {
        String wanted = nameOf(key.getEType(), keyValue);
        for (Object value : values)
        {
            if (value instanceof EObject
                && nameOf(key.getEType(), ((EObject)value).eGet(key)).equals(wanted))
            {
                return (EObject)value;
            }
        }
        return null;
    }

    private static JsonArray asArray(String rawValue)
    {
        JsonElement parsed = parse(rawValue);
        if (!parsed.isJsonArray())
        {
            throw new IllegalArgumentException(
                "this property takes a JSON array, for example [\"First\",\"Second\"]"); //$NON-NLS-1$
        }
        return parsed.getAsJsonArray();
    }

    private static JsonElement parse(String rawValue)
    {
        if (rawValue == null || rawValue.trim().isEmpty())
        {
            throw new IllegalArgumentException("propertyValue is required for this property"); //$NON-NLS-1$
        }
        try
        {
            return JsonParser.parseString(rawValue.trim());
        }
        catch (RuntimeException notJson)
        {
            throw new IllegalArgumentException("propertyValue is not JSON: " //$NON-NLS-1$
                + (notJson.getMessage() != null ? notJson.getMessage()
                    : notJson.getClass().getSimpleName()));
        }
    }
}
