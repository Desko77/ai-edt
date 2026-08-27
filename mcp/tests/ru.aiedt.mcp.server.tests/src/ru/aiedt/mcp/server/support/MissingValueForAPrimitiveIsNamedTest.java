/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.ecore.impl.EObjectImpl;
import org.junit.Test;

/**
 * A property whose setter takes a primitive, asked for without a value.
 * <p>
 * Both setter paths coerce the value to the setter's parameter type and pass the result on. The
 * coercion returns the value unchanged when it is null, so a request that named the property but
 * carried no value reached {@code Method.invoke} with null for an {@code int} or a {@code boolean}.
 * On a JDK whose core reflection runs on method handles that surfaces as
 * {@code Cannot invoke "java.lang.Number.intValue()" because the return value of
 * "sun.invoke.util.ValueConversions.primitiveConversion(...)" is null} - a sentence about JDK
 * internals, which reads as a broken model rather than as a value nobody supplied.
 * </p>
 * <p>
 * Measured on EDT 2026.2: {@code setExternalConnection}, {@code setClientOrdinaryApplication} and
 * {@code setReadOnly} all take a bare {@code boolean}, so every one of them lands here.
 * </p>
 */
public class MissingValueForAPrimitiveIsNamedTest
{
    /** An object with one primitive setter and one that takes a reference. */
    public static final class Target extends EObjectImpl
    {
        private boolean flag;
        private String label;
        private int count;

        public void setFlag(boolean value)
        {
            this.flag = value;
        }

        public boolean isFlag()
        {
            return flag;
        }

        public void setLabel(String value)
        {
            this.label = value;
        }

        public String getLabel()
        {
            return label;
        }

        public void setCount(int value)
        {
            this.count = value;
        }

        public int getCount()
        {
            return count;
        }
    }

    @Test
    public void aBooleanSetterAskedWithoutAValueSaysWhatIsMissing()
    {
        Target target = new Target();

        String refusal = BmObjectHelper.setProperty(target, "flag", null); //$NON-NLS-1$

        assertNotNull("a primitive setter must not be invoked with null", refusal); //$NON-NLS-1$
        assertTrue("the refusal names the property: " + refusal, refusal.contains("flag")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal names the type it wanted: " + refusal, //$NON-NLS-1$
            refusal.contains("boolean")); //$NON-NLS-1$
        assertTrue("the refusal says which argument to pass: " + refusal, //$NON-NLS-1$
            refusal.contains("propertyValue")); //$NON-NLS-1$
        assertTrue("nothing was written", !target.isFlag()); //$NON-NLS-1$
    }

    @Test
    public void anIntSetterAskedWithoutAValueSaysWhatIsMissing()
    {
        Target target = new Target();

        String refusal = BmObjectHelper.setProperty(target, "count", null); //$NON-NLS-1$

        assertNotNull("a primitive setter must not be invoked with null", refusal); //$NON-NLS-1$
        assertTrue("the refusal names the type it wanted: " + refusal, refusal.contains("int")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("nothing was written", 0, target.getCount()); //$NON-NLS-1$
    }

    @Test
    public void aValueThatIsThereStillGoesThrough()
    {
        Target target = new Target();

        assertNull("a supplied value is applied, not refused", //$NON-NLS-1$
            BmObjectHelper.setProperty(target, "flag", "true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the setter ran", target.isFlag()); //$NON-NLS-1$
    }

    @Test
    public void aReferenceSetterStillAcceptsNull()
    {
        Target target = new Target();
        target.setLabel("something"); //$NON-NLS-1$

        assertNull("null is a legitimate value for a reference type", //$NON-NLS-1$
            BmObjectHelper.setProperty(target, "label", null)); //$NON-NLS-1$
        assertNull("clearing the property is what was asked for", target.getLabel()); //$NON-NLS-1$
    }

    @Test
    public void theFormPathRefusesTheSameWay()
    {
        Target target = new Target();

        String refusal = new BmFormHelper().setScalarProperty(target, "flag", null); //$NON-NLS-1$

        assertNotNull("the form path has the same hole and the same guard", refusal); //$NON-NLS-1$
        assertTrue("the refusal names the property: " + refusal, refusal.contains("flag")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal names the type it wanted: " + refusal, //$NON-NLS-1$
            refusal.contains("boolean")); //$NON-NLS-1$
        assertTrue("nothing was written", !target.isFlag()); //$NON-NLS-1$
    }

    @Test
    public void theFormPathStillAppliesAValueThatIsThere()
    {
        Target target = new Target();

        assertNull("a supplied value is applied, not refused", //$NON-NLS-1$
            new BmFormHelper().setScalarProperty(target, "flag", "true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the setter ran", target.isFlag()); //$NON-NLS-1$
    }
}
