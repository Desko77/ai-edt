/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * How far one borrowed thing has drifted from the base it was borrowed from, decided on plain
 * values.
 * <p>
 * Nothing in this class touches the EDT model: a type arrives as a list of named items with their
 * qualifiers, a link as a uuid with the two names it pairs. That is what makes the drift decidable
 * without a loaded project, and the reader that turns the model into these values stays outside.
 * </p>
 * <p>
 * <b>What the type comparison answers.</b> A borrowed attribute keeps no type of its own; it keeps
 * a record of the types it took from the base - the controlled composition - and the platform
 * accepts the extension while that record still fits what the base now has. Three answers:
 * the record matches the base ({@link #IN_SYNC}); the base grew types the record never took, and
 * every type the record did take is unchanged ({@link #BASE_WIDER} - accepted, nothing to align);
 * a type the record took changed or vanished, or its qualifiers moved ({@link #TYPE_CONFLICT} -
 * the platform refuses the extension).
 * </p>
 * <p>
 * <b>Why uuid and not name.</b> A rename in the base leaves the uuid in place and changes the
 * name; a deletion followed by an unrelated addition can leave the name in place with a different
 * uuid. Matching by name reads the first as two unrelated breaks and the second as no break at
 * all. The uuid is the one identity a borrowed link actually carries, so it is the one asked.
 * </p>
 */
public final class BorrowedSyncClassification
{
    /** The one status of a borrowed thing against its base. */
    public enum Status
    {
        /** The controlled composition matches the base; there is nothing to align. */
        IN_SYNC,

        /** The base holds more types than the controlled composition; the common ones are unchanged. */
        BASE_WIDER,

        /** A controlled type, or its qualifiers, no longer match the base. */
        TYPE_CONFLICT,

        /** The base holds no object under the uuid the borrowed thing links to. */
        SOURCE_GONE,

        /** The base holds the uuid under a different name. */
        RENAMED_IN_BASE,

        /** The base form holds items the borrowed form has no copy of. */
        FORM_OUT_OF_DATE,

        /** An interceptor whose handler no longer fits the method it intercepts. */
        HANDLER_SIGNATURE_CHANGED
    }

    /** How a type conflict is written into the extension: the base's own types, or one AnyRef. */
    public enum TypePlan
    {
        /** Rewrite the controlled composition with the base's exact types and qualifiers. */
        EXACT,

        /** Rewrite it with a single AnyRef and no qualifiers - the form the environment writes. */
        ANY_REF
    }

    /** One type of a composition, by name, with whether it names a metadata object. */
    public static final class TypeItem
    {
        /** The type name the platform answers for it, for example {@code String}. */
        public final String name;

        /**
         * True when the name is a metadata object type - a reference to a catalog, a document and
         * so on. AnyRef stands for exactly these, so the comparison needs to know them apart.
         */
        public final boolean referenceType;

        /**
         * Records one item.
         *
         * @param name the type name
         * @param referenceType whether it names a metadata object
         */
        public TypeItem(String name, boolean referenceType)
        {
            this.name = name;
            this.referenceType = referenceType;
        }
    }

    /** The qualifiers of a composition, each kind present or absent as the composition holds it. */
    public static final class Qualifiers
    {
        /** Length of a String, or null when the composition holds no string qualifiers. */
        public Integer stringLength;

        /** Whether a String is fixed length, or null when the composition holds no string qualifiers. */
        public Boolean stringFixed;

        /** Precision of a Number, or null when the composition holds no number qualifiers. */
        public Integer numberPrecision;

        /** Scale of a Number, or null when the composition holds no number qualifiers. */
        public Integer numberScale;

        /** Whether a Number is non-negative, or null when the composition holds no number qualifiers. */
        public Boolean numberNonNegative;

        /** The date fractions of a Date, or null when the composition holds no date qualifiers. */
        public String dateFractions;

        /** Length of a BinaryData, or null when the composition holds no binary qualifiers. */
        public Integer binaryLength;

        /** Tolerance of a BinaryData, or null when the composition holds no binary qualifiers. */
        public Integer binaryTolerance;
    }

    /** One side of a type comparison: the items of the composition and its qualifiers. */
    public static final class TypeSide
    {
        /** The items, in no particular order; empty when the side has no composition. */
        public final List<TypeItem> items;

        /** The qualifiers, or null when the side carries none at all. */
        public final Qualifiers qualifiers;

        /**
         * Records one side.
         *
         * @param items the items of the composition
         * @param qualifiers the qualifiers, or null
         */
        public TypeSide(List<TypeItem> items, Qualifiers qualifiers)
        {
            this.items = items == null ? List.of() : items;
            this.qualifiers = qualifiers;
        }
    }

    private static final String ANY_REF = "AnyRef"; //$NON-NLS-1$

    private BorrowedSyncClassification()
    {
        // Static classifier.
    }

    /**
     * The wire spelling of a status, the one the answer carries.
     *
     * @param status the status
     * @return its name in the response
     */
    public static String wire(Status status)
    {
        switch (status)
        {
        case IN_SYNC:
            return "inSync"; //$NON-NLS-1$
        case BASE_WIDER:
            return "baseWider"; //$NON-NLS-1$
        case TYPE_CONFLICT:
            return "typeConflict"; //$NON-NLS-1$
        case SOURCE_GONE:
            return "sourceGone"; //$NON-NLS-1$
        case RENAMED_IN_BASE:
            return "renamedInBase"; //$NON-NLS-1$
        case FORM_OUT_OF_DATE:
            return "formOutOfDate"; //$NON-NLS-1$
        case HANDLER_SIGNATURE_CHANGED:
            return "handlerSignatureChanged"; //$NON-NLS-1$
        default:
            return String.valueOf(status);
        }
    }

    /**
     * What a uuid link says about the object on the other end.
     *
     * @param nameInExtension the name the extension holds
     * @param nameInBase the name the base holds under that uuid, or null when it holds nothing
     * @return the status the link alone settles, or null when the names agree and the caller has
     *         to compare the contents
     */
    public static Status byUuid(String nameInExtension, String nameInBase)
    {
        if (nameInBase == null)
        {
            return Status.SOURCE_GONE;
        }
        return nameInBase.equals(nameInExtension) ? null : Status.RENAMED_IN_BASE;
    }

    /**
     * How far a controlled composition has drifted from the base's type.
     *
     * @param controlled what the extension took from the base, Checked entries only
     * @param base what the base now holds
     * @return the status; a controlled side with no entries takes whatever the base has and
     *         cannot disagree with it
     */
    public static Status typeVsBase(TypeSide controlled, TypeSide base)
    {
        if (controlled == null || controlled.items.isEmpty())
        {
            // No entries is a record of its own: the extension wrote down that it takes whatever
            // the base has. That is agreement by construction, not a failure to read - the reader
            // keeps an unreadable side out of this comparison altogether.
            return Status.IN_SYNC;
        }
        if (base == null || base.items.isEmpty())
        {
            // The caller reads the base before asking; an empty base here means the comparison
            // ran against nothing, and the safe answer is the one that shows up in the response.
            return Status.TYPE_CONFLICT;
        }
        Set<String> baseNames = names(base);
        Set<String> controlledNames = new HashSet<>();
        boolean anyRef = false;
        for (TypeItem item : controlled.items)
        {
            if (ANY_REF.equals(item.name))
            {
                anyRef = true;
                continue;
            }
            if (!baseNames.contains(item.name))
            {
                return Status.TYPE_CONFLICT;
            }
            controlledNames.add(item.name);
        }
        if (qualifiersDiverge(controlled, base, controlledNames))
        {
            return Status.TYPE_CONFLICT;
        }
        Set<String> covered = new HashSet<>(controlledNames);
        if (anyRef)
        {
            // AnyRef stands for every reference type the base holds, and for an AnyRef the base
            // holds itself; without this the coverage would call its own record a divergence.
            if (baseNames.contains(ANY_REF))
            {
                covered.add(ANY_REF);
            }
            for (TypeItem item : base.items)
            {
                if (item.referenceType)
                {
                    covered.add(item.name);
                }
            }
        }
        return covered.equals(baseNames) ? Status.IN_SYNC : Status.BASE_WIDER;
    }

    /**
     * How a type conflict is written back, given the base it is written against.
     *
     * @param base what the base holds
     * @return {@link TypePlan#ANY_REF} for a composite base holding reference types,
     *         {@link TypePlan#EXACT} for everything else
     */
    public static TypePlan planFor(TypeSide base)
    {
        if (base == null || base.items.size() <= 1)
        {
            return TypePlan.EXACT;
        }
        for (TypeItem item : base.items)
        {
            if (item.referenceType)
            {
                return TypePlan.ANY_REF;
            }
        }
        return TypePlan.EXACT;
    }

    /**
     * Renders one side the way the answer shows it, sorted, with each primitive carrying the
     * qualifiers the composition holds for it.
     *
     * @param side the side to render
     * @return the rendered composition, empty for a side with no items
     */
    public static String render(TypeSide side)
    {
        if (side == null || side.items.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        Set<String> parts = new TreeSet<>();
        for (TypeItem item : side.items)
        {
            parts.add(renderItem(item.name, side.qualifiers));
        }
        return String.join(", ", parts); //$NON-NLS-1$
    }

    /**
     * The names of a side's items.
     *
     * @param side the side
     * @return the names, empty for no items
     */
    private static Set<String> names(TypeSide side)
    {
        Set<String> names = new HashSet<>();
        for (TypeItem item : side.items)
        {
            names.add(item.name);
        }
        return names;
    }

    /**
     * Whether the qualifiers of the types both sides hold have moved.
     * <p>
     * A composition keeps one qualifier object of each kind for all its items, so a kind is
     * compared only where a type of that kind sits on both sides: the base growing a Number
     * beside an untouched String does not move the String.
     * </p>
     *
     * @param controlled the controlled side
     * @param base the base side
     * @param common the type names both sides hold
     * @return true when a shared type's qualifiers differ, including when one side holds the
     *         qualifier and the other holds nothing
     */
    private static boolean qualifiersDiverge(TypeSide controlled, TypeSide base,
        Set<String> common)
    {
        if (common.contains("String") //$NON-NLS-1$
            && differ(controlled.qualifiers == null ? null : controlled.qualifiers.stringLength,
                base.qualifiers == null ? null : base.qualifiers.stringLength))
        {
            return true;
        }
        if (common.contains("String") //$NON-NLS-1$
            && differ(controlled.qualifiers == null ? null : controlled.qualifiers.stringFixed,
                base.qualifiers == null ? null : base.qualifiers.stringFixed))
        {
            return true;
        }
        if (common.contains("Number") //$NON-NLS-1$
            && (differ(numberPrecision(controlled), numberPrecision(base))
                || differ(numberScale(controlled), numberScale(base))
                || differ(numberNonNegative(controlled), numberNonNegative(base))))
        {
            return true;
        }
        if (common.contains("Date") //$NON-NLS-1$
            && differ(controlled.qualifiers == null ? null : controlled.qualifiers.dateFractions,
                base.qualifiers == null ? null : base.qualifiers.dateFractions))
        {
            return true;
        }
        if (common.contains("BinaryData") //$NON-NLS-1$
            && (differ(binaryLength(controlled), binaryLength(base))
                || differ(binaryTolerance(controlled), binaryTolerance(base))))
        {
            return true;
        }
        return false;
    }

    /**
     * Reads a side's number precision without knowing whether it has qualifiers at all.
     *
     * @param side the side
     * @return the precision, or null
     */
    private static Integer numberPrecision(TypeSide side)
    {
        return side.qualifiers == null ? null : side.qualifiers.numberPrecision;
    }

    /**
     * Reads a side's number scale without knowing whether it has qualifiers at all.
     *
     * @param side the side
     * @return the scale, or null
     */
    private static Integer numberScale(TypeSide side)
    {
        return side.qualifiers == null ? null : side.qualifiers.numberScale;
    }

    /**
     * Reads a side's number non-negativity without knowing whether it has qualifiers at all.
     *
     * @param side the side
     * @return the flag, or null
     */
    private static Boolean numberNonNegative(TypeSide side)
    {
        return side.qualifiers == null ? null : side.qualifiers.numberNonNegative;
    }

    /**
     * Reads a side's binary length without knowing whether it has qualifiers at all.
     *
     * @param side the side
     * @return the length, or null
     */
    private static Integer binaryLength(TypeSide side)
    {
        return side.qualifiers == null ? null : side.qualifiers.binaryLength;
    }

    /**
     * Reads a side's binary tolerance without knowing whether it has qualifiers at all.
     *
     * @param side the side
     * @return the tolerance, or null
     */
    private static Integer binaryTolerance(TypeSide side)
    {
        return side.qualifiers == null ? null : side.qualifiers.binaryTolerance;
    }

    /**
     * Whether two optional qualifier values differ. Equal nulls agree; one null against a value
     * differs, because a qualifier the composition does not hold is not the same as a qualifier
     * the other side wrote down.
     *
     * @param left one side's value
     * @param right the other side's value
     * @return true when they differ
     */
    private static boolean differ(Object left, Object right)
    {
        if (left == null && right == null)
        {
            return false;
        }
        return left == null || right == null || !left.equals(right);
    }

    /**
     * Renders one item with its qualifier, where the composition holds one.
     *
     * @param name the type name
     * @param qualifiers the composition's qualifiers, or null
     * @return the rendered item
     */
    private static String renderItem(String name, Qualifiers qualifiers)
    {
        if (qualifiers == null)
        {
            return name;
        }
        switch (name)
        {
        case "String": //$NON-NLS-1$
            if (qualifiers.stringLength != null)
            {
                return qualifiers.stringFixed != null && qualifiers.stringFixed.booleanValue()
                    ? "String(" + qualifiers.stringLength + ",fixed)" //$NON-NLS-1$ //$NON-NLS-2$
                    : "String(" + qualifiers.stringLength + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return name;
        case "Number": //$NON-NLS-1$
            if (qualifiers.numberPrecision != null)
            {
                String suffix = qualifiers.numberNonNegative != null
                    && qualifiers.numberNonNegative.booleanValue() ? ",nonNegative" : ""; //$NON-NLS-1$ //$NON-NLS-2$
                return "Number(" + qualifiers.numberPrecision + "," //$NON-NLS-1$ //$NON-NLS-2$
                    + qualifiers.numberScale + suffix + ")"; //$NON-NLS-1$
            }
            return name;
        case "Date": //$NON-NLS-1$
            return qualifiers.dateFractions != null
                ? "Date(" + qualifiers.dateFractions + ")" : name; //$NON-NLS-1$ //$NON-NLS-2$
        case "BinaryData": //$NON-NLS-1$
            return qualifiers.binaryLength != null
                ? "BinaryData(" + qualifiers.binaryLength + ")" : name; //$NON-NLS-1$ //$NON-NLS-2$
        default:
            return name;
        }
    }

    /**
     * A fresh mutable side, for the reader and the tests.
     *
     * @param items the items
     * @param qualifiers the qualifiers, or null
     * @return the side
     */
    public static TypeSide side(List<TypeItem> items, Qualifiers qualifiers)
    {
        return new TypeSide(items, qualifiers);
    }

    /**
     * Convenience builder of an item that is not a reference type.
     *
     * @param name the type name
     * @return the item
     */
    public static TypeItem primitive(String name)
    {
        return new TypeItem(name, false);
    }

    /**
     * Convenience builder of an item that names a metadata object.
     *
     * @param name the type name
     * @return the item
     */
    public static TypeItem reference(String name)
    {
        return new TypeItem(name, true);
    }

    /**
     * Convenience builder of an empty item list.
     *
     * @return a fresh empty list
     */
    public static List<TypeItem> noItems()
    {
        return new ArrayList<>();
    }
}
