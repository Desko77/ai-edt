/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

/**
 * Decides which side changed an object in a three-way comparison.
 * <p>
 * <b>This is the number a person reads to decide an update is safe.</b> Everything downstream rests
 * on it: which objects go into the queue a human has to review, whether the fast path for an
 * unchanged configuration may run at all, and which objects a class-wide decision covers. An
 * attribution that is wrong in the reassuring direction is worse than no attribution.
 * </p>
 * <p>
 * Kept free of every environment type on purpose. The rule is the part that carries the meaning and
 * it is provable without an EDT to run in; the caller does the asking, this does the deciding.
 * </p>
 */
public final class AttributionRule
{
    /** Two sides cannot say who moved anything. */
    public static final String UNKNOWN = "UNKNOWN"; //$NON-NLS-1$

    /** Changed here and not in the delivery. */
    public static final String OURS = "OURS"; //$NON-NLS-1$

    /** Changed in the delivery and not here. */
    public static final String VENDOR = "VENDOR"; //$NON-NLS-1$

    /** Changed on both sides - the objects a person has to look at. */
    public static final String BOTH = "BOTH"; //$NON-NLS-1$

    private AttributionRule()
    {
        // Static rule.
    }

    /**
     * Attributes an object that exists on one side only.
     * <p>
     * <b>Measured, and the plain reading is wrong.</b> On a stand, a catalogue we had changed and
     * the delivery had deleted came back attributed to the vendor, with GET_FROM_OTHER recommended
     * - following which deletes the customisation. The reverse, an object we deleted and the
     * delivery changed, came back as ours. Both are conflicts and both were landing in a column
     * that reads as safe.
     * </p>
     * <p>
     * So a deletion is not attributed by the side it left behind. Where the ancestor had the
     * object, the surviving copy decides: changed since the ancestor means one side reworked what
     * the other removed, which is a conflict; unchanged means a plain deletion by the other side.
     * </p>
     *
     * @param presentOnMain <code>true</code> when the object survives on our side, <code>false</code>
     *            when it survives in the delivery.
     * @param existedInAncestor whether the delivery both sides came from had the object.
     * @param survivorChanged whether the surviving copy differs from the ancestor, or
     *     <code>null</code> when that could not be read - which is answered as BOTH, not as
     *     unchanged.
     * @return OURS, VENDOR or BOTH
     */
    public static String forOneSided(boolean presentOnMain, boolean existedInAncestor,
        Boolean survivorChanged)
    {
        if (!existedInAncestor)
        {
            // Nothing to conflict with: the object is new on the side it stands on.
            return presentOnMain ? OURS : VENDOR;
        }
        if (survivorChanged == null)
        {
            // Unreadable is not unchanged, and the difference decides whether an object survives
            // an update. Answering "unchanged" here names the surviving copy VENDOR, which keeps
            // it out of the protection list AND out of what blocks an unchanged-configuration
            // update - so a customisation nobody could read gets deleted without appearing
            // anywhere. BOTH costs a decision by hand; the alternative costs the object.
            return BOTH;
        }
        if (survivorChanged.booleanValue())
        {
            return BOTH;
        }
        return presentOnMain ? VENDOR : OURS;
    }

    /**
     * Attributes an object both sides still have.
     *
     * @param doubleChanged what the environment reports as a change on both sides.
     * @param oursChanged whether our copy differs from the ancestor.
     * @param vendorChanged whether the delivery differs from the ancestor.
     * @return OURS, VENDOR, BOTH or UNKNOWN when nothing differs
     */
    public static String forTwoSided(boolean doubleChanged, boolean oursChanged,
        boolean vendorChanged)
    {
        if (doubleChanged || oursChanged && vendorChanged)
        {
            return BOTH;
        }
        if (oursChanged)
        {
            return OURS;
        }
        if (vendorChanged)
        {
            return VENDOR;
        }
        return UNKNOWN;
    }
}
