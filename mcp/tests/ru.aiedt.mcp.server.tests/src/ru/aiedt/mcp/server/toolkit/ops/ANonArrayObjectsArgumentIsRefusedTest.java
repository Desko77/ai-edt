/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Covers the refusal for an {@code objects} argument that is not a JSON array.
 * <p>
 * Both tools that take {@code objects} parsed a scalar or a broken value into the same empty
 * list an omitted argument produces, and neither said a word. For {@code get_project_errors}
 * the answer then covered everything while the caller read it as filtered. For
 * {@code revalidate_objects} the empty list means revalidate the whole project, so a caller
 * who typed {@code "Catalog.Products"} instead of {@code ["Catalog.Products"]} triggered a
 * full build of the project rather than a check of one object. These tests pin the refusal
 * and the sample array it carries.
 * </p>
 */
public class ANonArrayObjectsArgumentIsRefusedTest
{
    @Test
    public void aScalarIsRefusedWithTheShapeItShouldHaveHad()
    {
        String refusal = ProjectProblemsReader.objectsNotArrayRefusal("Catalog.Products"); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue("the refusal shows a sample array: " + refusal, //$NON-NLS-1$
            refusal.contains("[\"Catalog.Products\", \"Document.SalesOrder\"]")); //$NON-NLS-1$
        assertTrue(refusal.contains("Catalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void anAbsentBlankOrArrayArgumentIsNotRefused()
    {
        assertNull(ProjectProblemsReader.objectsNotArrayRefusal(null));
        assertNull(ProjectProblemsReader.objectsNotArrayRefusal("")); //$NON-NLS-1$
        assertNull(ProjectProblemsReader.objectsNotArrayRefusal("  ")); //$NON-NLS-1$
        assertNull(ProjectProblemsReader.objectsNotArrayRefusal("[]")); //$NON-NLS-1$
        assertNull(ProjectProblemsReader.objectsNotArrayRefusal("[\"Catalog.Products\"]")); //$NON-NLS-1$
    }

    @Test
    public void brokenJsonIsRefusedRatherThanDropped()
    {
        assertNotNull(ProjectProblemsReader.objectsNotArrayRefusal("[Catalog.Products")); //$NON-NLS-1$
        assertNotNull(ProjectProblemsReader.objectsNotArrayRefusal("{\"fqn\": \"Catalog.Products\"}")); //$NON-NLS-1$
    }

    @Test
    public void getProjectErrorsRefusesBeforeTouchingTheWorkspace()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objects", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = new ProjectProblemsReader().execute(params);

        assertTrue("the refusal is the whole answer: " + answer, //$NON-NLS-1$
            answer.startsWith("# Request Failed")); //$NON-NLS-1$
        assertTrue(answer.contains("[\"Catalog.Products\", \"Document.SalesOrder\"]")); //$NON-NLS-1$
    }

    @Test
    public void revalidateObjectsRefusesTheSameWay()
    {
        String refusal = ObjectsRevalidator.objectsNotArrayError("Catalog.Products"); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal.contains("[\"Catalog.Products\", \"Document.SalesOrder\"]")); //$NON-NLS-1$
        assertTrue("a refused call must say nothing was built: " + refusal, //$NON-NLS-1$
            refusal.contains("Nothing was revalidated")); //$NON-NLS-1$
        assertNull(ObjectsRevalidator.objectsNotArrayError(null));
        assertNull(ObjectsRevalidator.objectsNotArrayError("[\"Catalog.Products\"]")); //$NON-NLS-1$
    }

    @Test
    public void revalidateObjectsRefusesTheAliasToo()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectFqns", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = new ObjectsRevalidator().execute(params);

        assertTrue("a scalar alias value must not become a full-project build: " + answer, //$NON-NLS-1$
            answer.contains("objects must be a JSON array")); //$NON-NLS-1$
    }
}
