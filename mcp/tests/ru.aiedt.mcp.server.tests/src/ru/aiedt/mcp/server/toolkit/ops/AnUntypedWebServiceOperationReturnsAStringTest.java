/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Operation;

/**
 * An operation of a web service returns the XSD string type when the caller names no type: the
 * platform refuses to load a configuration whose WebService operation carries an empty return
 * value type, reporting the XDTO error
 * {@code Тип: {http://www.w3.org/2001/XMLSchema}QName}.
 *
 * <p>Pinned without an EDT runtime, on a metadata model built from the factories: the type an
 * unnamed argument resolves to, the type a named argument resolves to, and what the write puts on
 * the operation.
 */
public class AnUntypedWebServiceOperationReturnsAStringTest
{
    /** The XSD namespace the platform reads a bare local name in. */
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema"; //$NON-NLS-1$

    /** An operation of a web service, as the create and add operations build it. */
    private static Operation operation()
    {
        return MdClassFactory.eINSTANCE.createOperation();
    }

    @Test
    public void anOperationWithoutATypeReturnsTheXsdStringType()
    {
        String[] type = ServiceOps.resolveReturningValueType(null, null);

        assertEquals(XSD_NS, type[0]);
        assertEquals("string", type[1]); //$NON-NLS-1$
    }

    @Test
    public void aBlankTypeIsTheSameAsNone()
    {
        assertEquals(XSD_NS, ServiceOps.resolveReturningValueType("", null)[0]); //$NON-NLS-1$
        assertEquals("string", ServiceOps.resolveReturningValueType("  ", null)[1]); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("string", ServiceOps.resolveReturningValueType("", "")[1]); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("string", ServiceOps.resolveReturningValueType(null, null)[1]); //$NON-NLS-1$
    }

    @Test
    public void aNamedTypeIsReturnedUnchanged()
    {
        String[] local = ServiceOps.resolveReturningValueType("int", null); //$NON-NLS-1$
        assertEquals(XSD_NS, local[0]);
        assertEquals("int", local[1]); //$NON-NLS-1$

        String[] withNamespace = ServiceOps.resolveReturningValueType("MyType", //$NON-NLS-1$
            "http://example.com/ns"); //$NON-NLS-1$
        assertEquals("http://example.com/ns", withNamespace[0]); //$NON-NLS-1$
        assertEquals("MyType", withNamespace[1]); //$NON-NLS-1$

        String[] braced = ServiceOps.resolveReturningValueType(
            "{http://example.com/ns}MyType", "http://ignored"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("http://example.com/ns", braced[0]); //$NON-NLS-1$
        assertEquals("MyType", braced[1]); //$NON-NLS-1$
    }

    @Test
    public void theXsdStringTypeIsWrittenOnTheOperation() throws Exception
    {
        Operation operation = operation();
        boolean[] applied = { false };
        List<String> warnings = new ArrayList<>();

        ServiceOps.applyReturningValueType(operation,
            ServiceOps.resolveReturningValueType(null, null), applied, warnings);

        assertTrue(warnings.toString(), applied[0]);
        assertTrue(warnings.toString(), warnings.isEmpty());
        assertEquals("string", operation.getXdtoReturningValueType().getName()); //$NON-NLS-1$
        assertEquals(XSD_NS, operation.getXdtoReturningValueType().getNsUri());
    }

    @Test
    public void aNamedTypeIsWrittenAsGiven() throws Exception
    {
        Operation operation = operation();
        boolean[] applied = { false };
        List<String> warnings = new ArrayList<>();

        ServiceOps.applyReturningValueType(operation,
            ServiceOps.resolveReturningValueType("int", null), applied, warnings); //$NON-NLS-1$

        assertTrue(warnings.toString(), applied[0]);
        assertTrue(warnings.toString(), warnings.isEmpty());
        assertEquals("int", operation.getXdtoReturningValueType().getName()); //$NON-NLS-1$
        assertEquals(XSD_NS, operation.getXdtoReturningValueType().getNsUri());
    }
}
