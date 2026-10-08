/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterResource;
import com._1c.g5.v8.dt.metadata.mdclass.Enum;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * The wide attribute table shows the fill value an attribute carries, in the spelling a caller can hand back.
 * <p>
 * A date is shown the way EDT serializes it; a reference is shown as the FQN of what it points at, which
 * is the literal {@code set_object_property} accepts for the same fill; Undefined is one word, so a
 * cleared attribute reads differently from an unset one.
 * </p>
 */
public class AnAttributeRowShowsItsFillValueTest
{
    // The wide table's columns, counted from the leading bar: Name, Synonym, Type, Indexing,
    // Fill Checking, Full Text Search, Password Mode, Multi Line, Quick Choice, Create On Input,
    // Fill Value.
    private static final int FILL_VALUE = 11;

    private static InformationRegister registerWithResource(InformationRegisterResource resource)
    {
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        register.setName("Events"); //$NON-NLS-1$
        resource.setName("Amount"); //$NON-NLS-1$
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName("Number"); //$NON-NLS-1$
        description.getTypes().add(type);
        resource.setType(description);
        register.getResources().add(resource);
        return register;
    }

    /** The cells of the row naming one resource, out of a rendered wide table. */
    private static String[] rowFor(InformationRegister register)
    {
        for (String line : MetadataFormatter.format(register, true, "ru").split("\n")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String[] cells = line.split("\\|"); //$NON-NLS-1$
            if (cells.length > FILL_VALUE && cells[1].trim().equals("Amount")) //$NON-NLS-1$
            {
                for (int i = 0; i < cells.length; i++)
                {
                    cells[i] = cells[i].trim();
                }
                return cells;
            }
        }
        return null;
    }

    /** A date fill value is shown the way EDT serializes it. */
    @Test
    public void aDateFillValueIsShownAsEdtWritesIt()
    {
        InformationRegisterResource resource =
            MdClassFactory.eINSTANCE.createInformationRegisterResource();
        com._1c.g5.v8.dt.mcore.DateValue value = McoreFactory.eINSTANCE.createDateValue();
        value.setValue(new com._1c.g5.v8.dt.mcore.util.Date(1, 1, 1, 0, 0, 0));
        resource.setFillValue(value);

        String[] row = rowFor(registerWithResource(resource));

        assertNotNull("the resource belongs in the wide table", row); //$NON-NLS-1$
        assertEquals("0001-01-01T00:00:00", row[FILL_VALUE]); //$NON-NLS-1$
    }

    /** A reference fill value is shown as the FQN of what it points at. */
    @Test
    public void aReferenceFillValueIsShownAsTheFqnOfItsTarget()
    {
        Enum importance = MdClassFactory.eINSTANCE.createEnum();
        importance.setName("Важность"); //$NON-NLS-1$
        EnumValue usual = MdClassFactory.eINSTANCE.createEnumValue();
        usual.setName("Обычная"); //$NON-NLS-1$
        importance.getEnumValues().add(usual);
        InformationRegisterResource resource =
            MdClassFactory.eINSTANCE.createInformationRegisterResource();
        ReferenceValue reference = McoreFactory.eINSTANCE.createReferenceValue();
        reference.setValue(usual);
        resource.setFillValue(reference);

        String[] row = rowFor(registerWithResource(resource));

        assertEquals("Enum.Важность.EnumValue.Обычная", row[FILL_VALUE]); //$NON-NLS-1$
    }

    /** A cleared attribute reads Undefined, an unset one reads as a dash. */
    @Test
    public void undefinedReadsAsOneWordAndUnsetAsADash()
    {
        InformationRegisterResource cleared =
            MdClassFactory.eINSTANCE.createInformationRegisterResource();
        cleared.setFillValue(McoreFactory.eINSTANCE.createUndefinedValue());
        InformationRegisterResource unset =
            MdClassFactory.eINSTANCE.createInformationRegisterResource();

        assertEquals("Undefined", rowFor(registerWithResource(cleared))[FILL_VALUE]); //$NON-NLS-1$
        assertEquals("-", rowFor(registerWithResource(unset))[FILL_VALUE]); //$NON-NLS-1$
    }

    /** A string fill value is shown as the string itself. */
    @Test
    public void aStringFillValueIsShownAsItself()
    {
        InformationRegisterResource resource =
            MdClassFactory.eINSTANCE.createInformationRegisterResource();
        com._1c.g5.v8.dt.mcore.StringValue value = McoreFactory.eINSTANCE.createStringValue();
        value.setValue("N/A"); //$NON-NLS-1$
        resource.setFillValue(value);

        assertEquals("N/A", rowFor(registerWithResource(resource))[FILL_VALUE]); //$NON-NLS-1$
    }

    /** A bare UndefinedValue with nothing set on it still reads Undefined, not its class name. */
    @Test
    public void aBareUndefinedValueReadsUndefined()
    {
        assertEquals("Undefined", MetadataFormatter.formatFillValue( //$NON-NLS-1$
            McoreFactory.eINSTANCE.createUndefinedValue()));
    }
}
