/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

import java.util.Collection;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Reduces an arbitrary object of the metadata model to the one line that belongs in a table cell.
 * <p>
 * The formatter knows how to lay out the kinds of object it recognizes. This handles everything else:
 * whatever an unremarkable feature happens to hold - a command group, a fill value, a field, a wrapper
 * around a single enum - has to come out as a short string, and nothing here may assume it knows what it
 * was handed.
 * </p>
 * <p>
 * There are two questions, and they are not the same one. <em>How</em> to show an object is answered by
 * {@link #render(EObject)}: a thin wrapper - one that owns no children and carries barely any state, the
 * shape of a {@code StandardCommandGroup} whose whole content is a {@code category} enum - is worth
 * inlining as that value, and anything else is worth a reference. <em>What</em> the reference says is
 * answered by {@link #formatReference(EObject)}, which walks a fixed precedence looking for the most
 * identifying thing the object has: a metadata object is {@code Type.Name}, and the rest fall back
 * through name, id and finally the bare type.
 * </p>
 * <p>
 * The three predicates behind all this - the containment probe, the count of attributes that carry
 * something, and the test for a simple wrapper - use three deliberately different filters: one skips
 * volatile features and the others do not, one insists a feature actually be set and the others do not,
 * and the two thresholds are different numbers. They look like they want harmonizing. They do not: every
 * one of them is load bearing on the output, and lining them up would move objects between the inline
 * and the reference rendering.
 * </p>
 */
final class EObjectProbe
{
    /** Above this many declared attributes an object is no longer a wrapper around one value. */
    private static final int MAX_SIMPLE_WRAPPER_ATTRIBUTES = 5;

    /** Above this many attributes actually carrying something, inlining loses more than it shows. */
    private static final int MAX_ATTRIBUTES_FOR_SIMPLE_VALUE = 3;

    /** Where a wrapper keeps the value it wraps, in the order worth looking. */
    private static final String[] PRIMARY_VALUE_FEATURE_NAMES = { "category", "group", "type", "value" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** What an object may call its identity when it has no name, in the order worth looking. */
    private static final String[] IDENTIFIER_FEATURE_NAMES = { "id", "identifier", "code" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String NAME_FEATURE = "name"; //$NON-NLS-1$

    private static final String NAME_RU_FEATURE = "nameRu"; //$NON-NLS-1$

    private static final String IMPL_SUFFIX = "Impl"; //$NON-NLS-1$

    private static final String DOT = "."; //$NON-NLS-1$

    private static final String EMPTY = ""; //$NON-NLS-1$

    private EObjectProbe()
    {
        // utility
    }

    /**
     * Renders an object as the cell it deserves: the value it wraps, when it is a wrapper, and a
     * reference to it otherwise.
     *
     * @param object the object; may be <code>null</code>
     * @return the cell text, never <code>null</code>; empty when there was no object
     */
    static String render(EObject object)
    {
        if (object == null)
        {
            return EMPTY;
        }
        if (wrapsASingleValue(object))
        {
            return primaryValueAsString(object);
        }
        return formatReference(object);
    }

    /**
     * Renders the value an object wraps.
     *
     * @param object the object; may be <code>null</code>
     * @return the value as text, never <code>null</code>; empty when the object has no value to show
     */
    static String primaryValueAsString(EObject object)
    {
        Object value = primaryValue(object);
        return value == null ? EMPTY : value.toString();
    }

    /**
     * Names an object as briefly as it can still be recognized by.
     * <p>
     * A metadata object is always {@code Type.Name} - never its properties, however few of them there
     * are. Everything else is tried in turn: the thing a reference value points at, the value a wrapper
     * wraps, a name, an id, and if none of that is there, the type on its own.
     * </p>
     *
     * @param object the object; may be <code>null</code>
     * @return the reference, never <code>null</code>; the EMPTY STRING when there was no object, which is
     *         not the dash the formatter shows for the same thing - the dash belongs to the cell, not to
     *         the reference
     */
    static String formatReference(EObject object)
    {
        if (object == null)
        {
            return EMPTY;
        }
        if (object instanceof ReferenceValue)
        {
            EObject referenced = ((ReferenceValue)object).getValue();
            return referenced == null ? EMPTY : formatReference(referenced);
        }
        if (object instanceof MdObject)
        {
            // A missing name shows as "Catalog.null", and so does an unresolved proxy - both mean the
            // model is not saying, and inventing something friendlier here would hide that.
            return object.eClass().getName() + DOT + ((MdObject)object).getName();
        }
        if (isSimpleValueHolder(object.eClass()))
        {
            return primaryValueAsString(object);
        }

        EClass eClass = object.eClass();
        EStructuralFeature nameFeature = eClass.getEStructuralFeature(NAME_FEATURE);
        if (nameFeature != null)
        {
            // Read, not tested for being set: an object whose name still sits at its default is better
            // shown by that default than by its bare type.
            Object name = object.eGet(nameFeature);
            if (name != null && !name.toString().isEmpty())
            {
                return cleanClassName(object) + DOT + name;
            }
        }
        for (String featureName : IDENTIFIER_FEATURE_NAMES)
        {
            EStructuralFeature feature = eClass.getEStructuralFeature(featureName);
            if (feature == null)
            {
                continue;
            }
            Object identifier = object.eGet(feature);
            if (identifier != null && !identifier.toString().isEmpty())
            {
                return cleanClassName(object) + DOT + identifier;
            }
        }
        return cleanClassName(object);
    }

    /**
     * Tells whether an object is a wrapper worth replacing with the value inside it.
     * <p>
     * A metadata object never is - it has an identity of its own and is always shown as a reference to
     * it. An object that owns children never is either: they would be lost. What is left is judged by how
     * much it is actually carrying.
     * </p>
     *
     * @param object the object, not <code>null</code>
     * @return <code>true</code> when the value it holds says more than a reference to it would
     */
    private static boolean wrapsASingleValue(EObject object)
    {
        if (object instanceof MdObject)
        {
            return false;
        }
        if (hasNonEmptyContainment(object))
        {
            return false;
        }
        return countMeaningfulAttributes(object) <= MAX_ATTRIBUTES_FOR_SIMPLE_VALUE;
    }

    /**
     * Finds the value an object is really about.
     * <p>
     * An enum wins over everything, set or not, and that is the point of the whole method: a command
     * group is a wrapper whose {@code category} is left at its default, and it still has to render as
     * that category rather than as its type.
     * </p>
     *
     * @param object the object; may be <code>null</code>
     * @return the value, or <code>null</code> when there was no object. Never <code>null</code> for a
     *         real object: the last resort is its type name
     */
    private static Object primaryValue(EObject object)
    {
        if (object == null)
        {
            return null;
        }
        EClass eClass = object.eClass();

        for (EAttribute attribute : eClass.getEAllAttributes())
        {
            if (attribute.isDerived() || attribute.isTransient())
            {
                continue;
            }
            Object value = object.eGet(attribute);
            if (value != null && value.getClass().isEnum())
            {
                return value;
            }
        }
        for (String featureName : PRIMARY_VALUE_FEATURE_NAMES)
        {
            EStructuralFeature feature = eClass.getEStructuralFeature(featureName);
            if (feature == null || !object.eIsSet(feature))
            {
                continue;
            }
            Object value = object.eGet(feature);
            if (value != null)
            {
                return value;
            }
        }
        EStructuralFeature nameFeature = eClass.getEStructuralFeature(NAME_FEATURE);
        if (nameFeature != null && object.eIsSet(nameFeature))
        {
            Object name = object.eGet(nameFeature);
            if (name != null && !name.toString().isEmpty())
            {
                return name;
            }
        }
        for (EAttribute attribute : eClass.getEAllAttributes())
        {
            if (attribute.isDerived() || attribute.isTransient())
            {
                continue;
            }
            String attributeName = attribute.getName();
            if (NAME_FEATURE.equalsIgnoreCase(attributeName) || NAME_RU_FEATURE.equalsIgnoreCase(attributeName))
            {
                continue;
            }
            if (!object.eIsSet(attribute))
            {
                continue;
            }
            Object value = object.eGet(attribute);
            if (value != null)
            {
                return value;
            }
        }
        return eClass.getName();
    }

    /**
     * Tells whether an object owns any children.
     * <p>
     * Volatile references are read here, and are skipped when the attributes are counted. The two are not
     * meant to agree.
     * </p>
     *
     * @param object the object, not <code>null</code>
     * @return <code>true</code> when some containment reference holds something
     */
    private static boolean hasNonEmptyContainment(EObject object)
    {
        for (EReference reference : object.eClass().getEAllReferences())
        {
            if (!reference.isContainment() || reference.isDerived() || reference.isTransient())
            {
                continue;
            }
            Object value = object.eGet(reference);
            if (value == null)
            {
                continue;
            }
            if (!reference.isMany())
            {
                return true;
            }
            if (value instanceof Collection && !((Collection<?>)value).isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts the attributes of an object that are actually carrying something.
     *
     * @param object the object, not <code>null</code>
     * @return how many stored attributes are set
     */
    private static int countMeaningfulAttributes(EObject object)
    {
        int count = 0;
        for (EAttribute attribute : object.eClass().getEAllAttributes())
        {
            if (attribute.isDerived() || attribute.isTransient() || attribute.isVolatile())
            {
                continue;
            }
            if (object.eIsSet(attribute))
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Tells whether a type is shaped like a wrapper: no children of its own, and few enough attributes
     * that one of them can stand for the whole thing.
     * <p>
     * This asks the type, not the object - what a class <em>could</em> hold, not what one instance
     * happens to. An instance test would add nothing: a class that declares no containment cannot have a
     * non-empty one.
     * </p>
     *
     * @param eClass the type, not <code>null</code>
     * @return <code>true</code> when an instance of it is worth inlining
     */
    private static boolean isSimpleValueHolder(EClass eClass)
    {
        for (EReference reference : eClass.getEAllReferences())
        {
            if (reference.isContainment() && !reference.isDerived() && !reference.isTransient())
            {
                return false;
            }
        }
        int declared = 0;
        for (EAttribute attribute : eClass.getEAllAttributes())
        {
            if (!attribute.isDerived() && !attribute.isTransient())
            {
                declared++;
            }
        }
        return declared <= MAX_SIMPLE_WRAPPER_ATTRIBUTES;
    }

    /**
     * Returns the name of an object's type.
     * <p>
     * The MODEL name - {@code Catalog} - which is not the name of the Java class implementing it -
     * {@code CatalogImpl}. The suffix is trimmed if it ever shows up, which for a model name it does not;
     * the line is here to say which of the two names is meant, because reaching for the Java one instead
     * would rewrite every reference this class produces.
     * </p>
     *
     * @param object the object; may be <code>null</code>
     * @return the type name, or the empty string when there was no object
     */
    private static String cleanClassName(EObject object)
    {
        if (object == null)
        {
            return EMPTY;
        }
        String name = object.eClass().getName();
        if (name.endsWith(IMPL_SUFFIX))
        {
            return name.substring(0, name.length() - IMPL_SUFFIX.length());
        }
        return name;
    }
}
