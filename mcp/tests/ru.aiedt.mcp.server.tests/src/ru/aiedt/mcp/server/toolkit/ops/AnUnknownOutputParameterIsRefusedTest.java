/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * An output parameter name the platform does not offer is refused before anything is created.
 * <p>
 * An entry used to be made for whatever name arrived, so a typo answered {@code set} and left a
 * parameter in the model that nothing reads. The refusal carries the closest name and the ones that
 * are allowed, and the call writes nothing. A set of names that could not be read at all is the
 * opposite corner: the write goes through unchecked and the answer says so, because refusing every
 * name for want of the list would leave the operation unable to set a parameter at all.
 * </p>
 * <p>
 * The names are read from the platform, whose set is built by the platform-version bundles of the
 * EDT installation, so the tests that go through an operation pin the set instead and say which one
 * they use. The tests that read the real set ask for the version whose bundle the test launch
 * carries, 8.3.22 - asking for a version without a bundle answers with nothing.
 * </p>
 */
public class AnUnknownOutputParameterIsRefusedTest
{
    /** A misspelling of the platform's Заголовок / Title parameter. */
    private static final String TYPO = "Заголвок"; //$NON-NLS-1$

    /** The name the misspelling stands next to. */
    private static final String TITLE = "Заголовок"; //$NON-NLS-1$

    /** The platform version whose bundle the test launch carries; see this fragment's pom. */
    private static final Version PRESENT = Version.V8_3_22;

    /**
     * Enough of the platform's own set to tell a known name from an unknown one, each parameter
     * as the pair of spellings the platform carries for it.
     */
    private static final List<String[]> PINNED_NAMES =
        Collections.unmodifiableList(Arrays.asList(
            new String[] { TITLE, "Title" }, //$NON-NLS-1$
            new String[] { "ВыводитьЗаголовок", "OutputTitle" }, //$NON-NLS-1$ //$NON-NLS-2$
            new String[] { "ВыводитьОтбор", "OutputFilter" })); //$NON-NLS-1$ //$NON-NLS-2$

    /** The pinned set, wrapped the way a read of the platform answers it. */
    private static final DcsWorkshopTool.OutputParameterSet PINNED_SET =
        DcsWorkshopTool.OutputParameterSet.known(PINNED_NAMES);

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
     * A set of names that could not be read is not a refusal: the write goes through and the
     * record says the name was not checked.
     *
     * @throws Exception if the operation refuses
     */
    @Test
    public void anUnreadablePlatformSetStillWritesWithAWarning() throws Exception
    {
        DcsWorkshopTool.outputParameterNamesForTests = Collections.<String[]>emptyList();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("name", TITLE); //$NON-NLS-1$
        params.put("value", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$
        String note = tool.outputParameterNameNoteForTest("set_output_parameter", params, schema); //$NON-NLS-1$

        assertNotNull("the answer carries why the name was not checked", note); //$NON-NLS-1$
        assertEquals("and the write went through", 1, outputParameterItems()); //$NON-NLS-1$
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
     * The check matches a name whatever its case, and answers with the parameter's own pair of
     * spellings, so the write is keyed by the platform's spelling rather than the caller's.
     */
    @Test
    public void aNameInEitherCaseIsMatchedToThePlatformSpelling()
    {
        assertSame(PINNED_NAMES.get(0), DcsWorkshopTool.mustBeKnownOutputParameter(TITLE, PINNED_SET));
        assertSame(PINNED_NAMES.get(0),
            DcsWorkshopTool.mustBeKnownOutputParameter(TITLE.toUpperCase(), PINNED_SET));
        assertSame(PINNED_NAMES.get(0),
            DcsWorkshopTool.mustBeKnownOutputParameter("TITLE", PINNED_SET)); //$NON-NLS-1$
        assertSame(PINNED_NAMES.get(0),
            DcsWorkshopTool.mustBeKnownOutputParameter("title", PINNED_SET)); //$NON-NLS-1$
    }

    /**
     * A name outside the set is refused, and the refusal carries the closest name and the set.
     */
    @Test
    public void aNameOutsideTheSetIsRefused()
    {
        try
        {
            DcsWorkshopTool.mustBeKnownOutputParameter(TYPO, PINNED_SET);
            fail("a name outside the set must be refused"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertRefusalNamesTheClosest(e);
        }
    }

    /**
     * A set that could not be read matches nothing and refuses nothing: the caller writes the name
     * unchecked and says so in the answer.
     */
    @Test
    public void anUnreadableSetMatchesNothingAndRefusesNothing()
    {
        assertNull("an unread set is not an empty set of allowed names", //$NON-NLS-1$
            DcsWorkshopTool.mustBeKnownOutputParameter("Title", //$NON-NLS-1$
                DcsWorkshopTool.OutputParameterSet.unavailable("no answer"))); //$NON-NLS-1$
        assertNull("and an empty one is treated the same", //$NON-NLS-1$
            DcsWorkshopTool.mustBeKnownOutputParameter("Title", //$NON-NLS-1$
                DcsWorkshopTool.OutputParameterSet.known(Collections.<String[]>emptyList())));
    }

    /**
     * A refusal over a long set stays a readable length and says how many names it left out.
     */
    @Test
    public void aLongSetIsListedUpToTheCap()
    {
        List<String[]> many = new ArrayList<>();
        for (int i = 0; i < 60; i++)
        {
            many.add(new String[] { "Parameter" + i, "Parameter" + i }); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            DcsWorkshopTool.mustBeKnownOutputParameter(TYPO,
                DcsWorkshopTool.OutputParameterSet.known(many));
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
     * The answer to a write whose name went in unchecked says so, and a checked name carries
     * nothing.
     */
    @Test
    public void anUncheckedWriteSaysSoInTheAnswer()
    {
        JsonObject unchecked = JsonParser.parseString(DcsWorkshopTool.dynamicListAnswer(
            "set_output_parameter", "Form.Форма", "Список", "output parameter 'Title' set", false, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            List.of(), "IllegalArgumentException: no answer", null)) //$NON-NLS-1$
            .getAsJsonObject();
        assertTrue(unchecked.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("the write went through, and the answer says the name was not checked", //$NON-NLS-1$
            unchecked.get("outputParameterNameChecked").getAsBoolean()); //$NON-NLS-1$
        assertEquals("and why", "IllegalArgumentException: no answer", //$NON-NLS-1$ //$NON-NLS-2$
            unchecked.get("outputParameterNameCheckNote").getAsString()); //$NON-NLS-1$

        JsonObject checked = JsonParser.parseString(DcsWorkshopTool.dynamicListAnswer(
            "set_output_parameter", "Form.Форма", "Список", "output parameter 'Title' set", false, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            List.of(), null, null))
            .getAsJsonObject();
        assertFalse("a checked name carries no flag: " + checked, //$NON-NLS-1$
            checked.has("outputParameterNameChecked")); //$NON-NLS-1$
    }

    /**
     * The platform's own set for the version whose bundle the test launch carries answers with
     * both spellings of a parameter, which is what lets a caller name it either way.
     */
    @Test
    public void thePlatformSetCarriesBothSpellings()
    {
        DcsWorkshopTool.OutputParameterSet set = DcsWorkshopTool.outputParameterNames(PRESENT);
        assertNotNull("the platform answered with a set of names: " + set.unavailableNote, //$NON-NLS-1$
            set.spellings);
        List<String> all = new ArrayList<>();
        for (String[] pair : set.spellings)
        {
            all.add(pair[0]);
            all.add(pair[1]);
        }
        assertTrue("the set carries the title parameter: " + all, all.contains(TITLE)); //$NON-NLS-1$
        assertTrue("and its English spelling: " + all, all.contains("Title")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The names the platform answers with include a name this check accepts, so the check and the
     * writer address the same set.
     */
    @Test
    public void thePlatformSetAcceptsTheNameItCarries()
    {
        DcsWorkshopTool.OutputParameterSet set = DcsWorkshopTool.outputParameterNames(PRESENT);
        assertNotNull("the platform answered with a set of names: " + set.unavailableNote, //$NON-NLS-1$
            set.spellings);
        String[] first = set.spellings.get(0);
        assertSame("the first spelling matches the pair it was read from", first, //$NON-NLS-1$
            DcsWorkshopTool.mustBeKnownOutputParameter(first[0], set));
        assertSame("and so does the second", first, //$NON-NLS-1$
            DcsWorkshopTool.mustBeKnownOutputParameter(first[1], set));
    }
}
