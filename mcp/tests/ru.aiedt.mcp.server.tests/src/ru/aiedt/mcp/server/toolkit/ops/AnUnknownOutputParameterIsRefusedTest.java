/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * An output parameter name the platform does not offer is refused before anything is created.
 * <p>
 * An entry used to be made for whatever name arrived, so a typo answered {@code set} and left a
 * parameter in the model that nothing reads. The refusal carries the closest name and the ones that
 * are allowed, and the call writes nothing.
 * </p>
 * <p>
 * The names are read from the platform, whose set is built from the installed 1C:Enterprise, so the
 * tests that go through an operation pin the set instead and say which one they use.
 * </p>
 */
public class AnUnknownOutputParameterIsRefusedTest
{
    /** A misspelling of the platform's Заголовок / Title parameter. */
    private static final String TYPO = "Заголвок"; //$NON-NLS-1$

    /** The name the misspelling stands next to. */
    private static final String TITLE = "Заголовок"; //$NON-NLS-1$

    /** Enough of the platform's own set to tell a known name from an unknown one. */
    private static final List<String> PINNED_NAMES =
        Collections.unmodifiableList(Arrays.asList(
            TITLE, "Title", "ВыводитьЗаголовок", "ВыводитьОтбор", "OutputTitle")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private DcsWorkshopTool tool;

    private EObject schema;

    /**
     * Builds an empty composition schema.
     */
    @Before
    public void buildASchema()
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
        tool = new DcsWorkshopTool();
    }

    /**
     * Puts the name set back the way the production path reads it.
     */
    @After
    public void clearThePinnedNames()
    {
        DcsWorkshopTool.outputParameterNamesForTests = null;
    }

    /**
     * Runs one schema operation.
     *
     * @param op the operation name
     * @param keysAndValues argument names and values, alternating
     * @return what the operation reports
     * @throws Exception if the operation refuses
     */
    private Object run(String op, String... keysAndValues) throws Exception
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tool.applyToSchemaForTest(op, params, schema);
    }

    /**
     * How many output parameter entries the settings hold.
     *
     * @return the count, 0 when no settings variant was ever created
     */
    private int outputParameterItems()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        if (variants == null)
        {
            return 0;
        }
        int total = 0;
        for (EObject variant : variants)
        {
            Object settings = variant.eGet(variant.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
            if (!(settings instanceof EObject))
            {
                continue;
            }
            EObject settingsObject = (EObject)settings;
            Object container = settingsObject
                .eGet(settingsObject.eClass().getEStructuralFeature("outputParameters")); //$NON-NLS-1$
            if (!(container instanceof EObject))
            {
                continue;
            }
            EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
            total += items == null ? 0 : items.size();
        }
        return total;
    }

    /**
     * The refusal names the unknown parameter, offers the closest name and lists the allowed ones.
     *
     * @param error the refusal
     */
    private static void assertRefusalNamesTheClosest(Exception error)
    {
        String message = String.valueOf(error.getMessage());
        assertTrue("the refusal names the parameter that was not found: " + message, //$NON-NLS-1$
            message.contains("Unknown output parameter '" + TYPO + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and the name the caller meant: " + message, //$NON-NLS-1$
            message.contains("Did you mean '" + TITLE + "'?")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and it lists what is allowed: " + message, //$NON-NLS-1$
            message.contains("Available: ") && message.contains(TITLE)); //$NON-NLS-1$
    }

    /**
     * A typo in an output parameter name is refused and leaves no entry behind.
     */
    @Test
    public void anUnknownOutputParameterIsRefusedAndNothingIsWritten()
    {
        DcsWorkshopTool.outputParameterNamesForTests = PINNED_NAMES;
        try
        {
            run("set_output_parameter", "name", TYPO, "value", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("an unknown output parameter must be refused before an entry is created"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertRefusalNamesTheClosest(e);
        }
        assertEquals("no output parameter entry was created", 0, outputParameterItems()); //$NON-NLS-1$
    }

    /**
     * The same refusal is what a caller sees when the platform cannot be asked for the set at all.
     */
    @Test
    public void anUnreadablePlatformSetRefusesWithoutWriting()
    {
        DcsWorkshopTool.outputParameterNamesForTests = Collections.<String>emptyList();
        try
        {
            run("set_output_parameter", "name", TITLE, "value", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("an unread set of names must not read as 'no name is allowed'"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal says the list could not be read: " + message, //$NON-NLS-1$
                message.contains("output parameter list is unavailable")); //$NON-NLS-1$
            assertTrue("and that nothing was written: " + message, //$NON-NLS-1$
                message.contains("nothing was written")); //$NON-NLS-1$
        }
        assertEquals("no output parameter entry was created", 0, outputParameterItems()); //$NON-NLS-1$
    }

    /**
     * A parameter the set holds is still set.
     */
    @Test
    public void aKnownOutputParameterIsStillWritten()
    {
        DcsWorkshopTool.outputParameterNamesForTests = PINNED_NAMES;
        try
        {
            run("set_output_parameter", "name", TITLE, "value", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        catch (Exception e)
        {
            fail("a name the platform offers must be accepted: " + e.getMessage()); //$NON-NLS-1$
        }
        assertEquals("the parameter was set", 1, outputParameterItems()); //$NON-NLS-1$
    }

    /**
     * A parameter named in English is accepted as well, since the platform carries both spellings.
     */
    @Test
    public void aParameterNamedInTheOtherSpellingIsAccepted()
    {
        DcsWorkshopTool.outputParameterNamesForTests = PINNED_NAMES;
        try
        {
            run("set_output_parameter", "name", "Title", "value", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        catch (Exception e)
        {
            fail("the other spelling of a known parameter must be accepted: " + e.getMessage()); //$NON-NLS-1$
        }
        assertEquals("the parameter was set", 1, outputParameterItems()); //$NON-NLS-1$
    }

    /**
     * The check matches a name whatever its case.
     */
    @Test
    public void aNameInEitherCaseIsAccepted()
    {
        DcsWorkshopTool.mustBeKnownOutputParameter(TITLE, PINNED_NAMES);
        DcsWorkshopTool.mustBeKnownOutputParameter(TITLE.toUpperCase(), PINNED_NAMES);
        DcsWorkshopTool.mustBeKnownOutputParameter("TITLE", PINNED_NAMES); //$NON-NLS-1$
    }

    /**
     * A name outside the set is refused, and the refusal carries the closest name and the set.
     */
    @Test
    public void aNameOutsideTheSetIsRefused()
    {
        try
        {
            DcsWorkshopTool.mustBeKnownOutputParameter(TYPO, PINNED_NAMES);
            fail("a name outside the set must be refused"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertRefusalNamesTheClosest(e);
        }
    }

    /**
     * A set that could not be read is a refusal in its own right, not an empty set of allowed names.
     */
    @Test
    public void anUnavailableSetIsRefusedWithoutWriting()
    {
        for (List<String> absent : Arrays.asList(null, Collections.<String>emptyList()))
        {
            try
            {
                DcsWorkshopTool.mustBeKnownOutputParameter("Title", absent); //$NON-NLS-1$
                fail("an unread parameter set must not read as 'no name is allowed'"); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                String message = String.valueOf(e.getMessage());
                assertTrue("the refusal says the list could not be read: " + message, //$NON-NLS-1$
                    message.contains("output parameter list is unavailable")); //$NON-NLS-1$
                assertTrue("and that nothing was written: " + message, //$NON-NLS-1$
                    message.contains("nothing was written")); //$NON-NLS-1$
            }
        }
    }

    /**
     * A refusal over a long set stays a readable length and says how many names it left out.
     */
    @Test
    public void aLongSetIsListedUpToTheCap()
    {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 60; i++)
        {
            many.add("Parameter" + i); //$NON-NLS-1$
        }
        try
        {
            DcsWorkshopTool.mustBeKnownOutputParameter(TYPO, many);
            fail("a name outside the set must be refused"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal counts what it did not list: " + message, //$NON-NLS-1$
                message.contains("(+20 more)")); //$NON-NLS-1$
            assertTrue("and it names none of the ones it left out: " + message, //$NON-NLS-1$
                !message.contains("Parameter59")); //$NON-NLS-1$
        }
    }

    /**
     * The platform's own set answers with both spellings of a parameter, which is what lets a caller
     * name it either way. Skipped where no 1C:Enterprise installation stands behind the registry.
     */
    @Test
    public void thePlatformSetCarriesBothSpellings()
    {
        List<String> known = DcsWorkshopTool.outputParameterNames(null);
        Assume.assumeNotNull(known);
        assertNotNull("the platform answered with a set of names", known); //$NON-NLS-1$
        assertTrue("the set carries the title parameter: " + known, known.contains(TITLE)); //$NON-NLS-1$
        assertTrue("and its English spelling: " + known, known.contains("Title")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The names the platform answers with include a name this check accepts, so the check and the
     * writer address the same set.
     */
    @Test
    public void thePlatformSetAcceptsTheNameItCarries()
    {
        List<String> known = DcsWorkshopTool.outputParameterNames(null);
        Assume.assumeNotNull(known);
        String first = known.get(0);
        DcsWorkshopTool.mustBeKnownOutputParameter(first, known);
    }
}
