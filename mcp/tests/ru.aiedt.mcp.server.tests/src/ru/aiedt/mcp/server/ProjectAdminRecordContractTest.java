/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import ru.aiedt.mcp.server.toolkit.ops.ProjectAdminFacadeTool;

/**
 * The executable contract of the {@code project_admin} record: its published shape, its seventeen
 * arguments and the caller-relevant condition each one carries, and the weight of the record
 * against the original 3698-byte one.
 * <p>
 * The record is read from a real {@code tools/list} answer through {@link LiveServer}, so the
 * oracle is compared with what a client actually receives rather than with a method called
 * directly. The non-description part - name, property order, types, required, annotations - is
 * frozen as a literal copied from the declarations, because prose may be shortened but a schema
 * that loses a parameter or an obligation is a different contract. The prose part is held by a
 * condition matrix: every property must still state the operation it applies to, its default, its
 * cap or its direction, where the original stated one.
 * </p>
 * <p>
 * The negative half mutates copies of the actual record. Each mutation removes one condition and
 * the detector must report the family that condition belongs to, which is what stops the matrix
 * from passing over a record whose descriptions have quietly stopped saying anything.
 * </p>
 */
public class ProjectAdminRecordContractTest
{
    /** The one tool this class is about. */
    private static final String TOOL_NAME = "project_admin"; //$NON-NLS-1$

    /** The JSON member carrying prose, the only part a shortening pass may touch. */
    private static final String DESCRIPTION = "description"; //$NON-NLS-1$

    /** The schema member holding the declared arguments, in declaration order. */
    private static final String PROPERTIES = "properties"; //$NON-NLS-1$

    /** The schema member listing the arguments a call has to supply. */
    private static final String REQUIRED = "required"; //$NON-NLS-1$

    /** The schema member that says what kind of document the arguments form. */
    private static final String TYPE = "type"; //$NON-NLS-1$

    /** The record member carrying the tool's class hints. */
    private static final String ANNOTATIONS = "annotations"; //$NON-NLS-1$

    /** The record member holding the argument schema. */
    private static final String INPUT_SCHEMA = "inputSchema"; //$NON-NLS-1$

    /**
     * What {@code tools/list} weighed for this record before the shortening pass, in UTF-8 bytes of
     * the compact JSON a client receives. Measured on a running server, not estimated.
     */
    private static final int ORIGINAL_RECORD_BYTES = 3698;

    /** The seventeen arguments in the order the facade declares them. */
    private static final List<String> PROPERTY_ORDER = List.of(
        "operation", "button", "topic", "find", "projectName", "version", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "deleteContent", "objects", "action", "delayMs", "type", "nameFilter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "includeContent", "limit", "refresh", "waitTimeoutMs", "language"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /**
     * The whole ordered record without its prose, as the original declarations define it: the
     * property names and their order, each declared type, the string items of the one array, the
     * single required argument, and the annotations of a writer whose reach is this machine.
     * <p>
     * A literal rather than a snapshot written from the current answer: an oracle built from the
     * output it is meant to judge records whatever the output happens to be and cannot fail.
     * </p>
     */
    private static final String ORIGINAL_NON_DESCRIPTION_RECORD =
        "{\"name\":\"project_admin\",\"inputSchema\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
            + "\"operation\":{\"type\":\"string\"},\"button\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"topic\":{\"type\":\"string\"},\"find\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"projectName\":{\"type\":\"string\"},\"version\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"deleteContent\":{\"type\":\"boolean\"},\"objects\":{\"type\":\"array\"," //$NON-NLS-1$
            + "\"items\":{\"type\":\"string\"}},\"action\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"delayMs\":{\"type\":\"integer\"},\"type\":{\"type\":\"string\"}," //$NON-NLS-1$
            + "\"nameFilter\":{\"type\":\"string\"},\"includeContent\":{\"type\":\"boolean\"}," //$NON-NLS-1$
            + "\"limit\":{\"type\":\"integer\"},\"refresh\":{\"type\":\"boolean\"}," //$NON-NLS-1$
            + "\"waitTimeoutMs\":{\"type\":\"integer\"},\"language\":{\"type\":\"string\"}}," //$NON-NLS-1$
            + "\"required\":[\"operation\"]},\"annotations\":{\"openWorldHint\":false}}"; //$NON-NLS-1$

    /** The annotations a writing facade reaching only this machine is published with. */
    private static final String ORIGINAL_ANNOTATIONS = "{\"openWorldHint\":false}"; //$NON-NLS-1$

    /** What the root description must still say about mutation, restart, dry runs and aliases. */
    private static final List<String> ROOT_REQUIRED = List.of(
        "create_project", "delete_project", "resync_to_disk", "mutate", "restart_edt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "host EDT", "no dryRun", "standalone", "back-compat"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** A claim the root description must not silently acquire while the facade still mutates. */
    private static final List<String> ROOT_FORBIDDEN = List.of("read-only"); //$NON-NLS-1$

    /** The operations the facade accepts besides {@code help}, in the catalog's order. */
    private static final List<String> OPERATIONS = List.of(
        "list_projects", "list_configurations", "get_configuration_properties", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "create_project", "delete_project", "resync_to_disk", "restart_edt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "answer_dialog", "self_upkeep", "list_subsystems"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The declared JSON type of every property, keyed by the property name. */
    private static final Map<String, String> PROPERTY_TYPES = propertyTypes();

    /** The condition matrix, one row per published argument. */
    private static final List<PropertyCheck> PROPERTY_CHECKS = propertyChecks();

    private LiveServer server;

    /**
     * Starts the server whose {@code tools/list} is the record under test.
     *
     * @throws IOException when the endpoint cannot be started
     */
    @Before
    public void startTheServer() throws IOException
    {
        server = LiveServer.start();
    }

    /**
     * Stops the server started for the test, so no port and no temporary registry are left behind.
     */
    @After
    public void stopTheServer()
    {
        if (server != null)
        {
            server.close();
        }
    }

    /**
     * The non-description record is the one the declarations describe, in the same order.
     * <p>
     * Runs on the unchanged base as well: this test holds what may not change, so a shortening
     * pass cannot pay for prose with a parameter, a type, an obligation or an annotation.
     * </p>
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void thePublishedRecordKeepsTheShapeItWasDeclaredWith() throws IOException
    {
        JsonObject published = publishedRecord();
        assertEquals("the non-description record changed - a property, its order, a type, the " //$NON-NLS-1$
            + "required list or the annotations is part of the contract, not prose", //$NON-NLS-1$
            ORIGINAL_NON_DESCRIPTION_RECORD, withoutDescriptions(published).toString());
    }

    /**
     * Every one of the seventeen arguments is still in the record, in the original order, with its
     * declared type, and the whole record still insists on exactly one of them.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void theSeventeenArgumentsAreStillDeclaredInTheirOriginalOrder() throws IOException
    {
        JsonObject published = publishedRecord();
        JsonObject properties = propertiesOf(published);
        assertNotNull("the record declares no properties", properties); //$NON-NLS-1$
        assertEquals(PROPERTY_ORDER, new ArrayList<>(properties.keySet()));
        for (String property : PROPERTY_ORDER)
        {
            JsonObject declaration = propertyOf(published, property);
            assertNotNull(property + " is not declared", declaration); //$NON-NLS-1$
            assertEquals(property + " changed its type", PROPERTY_TYPES.get(property), //$NON-NLS-1$
                declaration.get(TYPE).getAsString());
        }
        assertEquals(List.of("operation"), stringsOf(schemaOf(published).get(REQUIRED))); //$NON-NLS-1$
        assertEquals(ORIGINAL_ANNOTATIONS, published.get(ANNOTATIONS).toString());
    }

    /**
     * The condition matrix covers all seventeen arguments and the published record satisfies every
     * row: the operation each argument applies to, the values it takes, its default, its cap or
     * its direction. This is the positive half of the executable contract.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void everyArgumentKeepsTheConditionTheMatrixRecords() throws IOException
    {
        List<String> covered = new ArrayList<>();
        for (PropertyCheck check : PROPERTY_CHECKS)
        {
            covered.add(check.property);
        }
        assertEquals("the matrix has to cover every argument exactly once", PROPERTY_ORDER, //$NON-NLS-1$
            covered);

        List<String> failures = contractFailures(publishedRecord());
        assertTrue("project_admin no longer states what a caller has to know: " //$NON-NLS-1$
            + String.join("; ", failures), failures.isEmpty()); //$NON-NLS-1$
    }

    /**
     * The root description still names what the facade changes, that it restarts the host, that it
     * adds no dry run and that the standalone tools remain.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void theRootStillStatesTheMutationRestartAndNoDryRunRules() throws IOException
    {
        String root = descriptionOf(publishedRecord());
        for (String phrase : ROOT_REQUIRED)
        {
            assertTrue("the tool description must still say \"" + phrase + "\": " + root, //$NON-NLS-1$ //$NON-NLS-2$
                containsIgnoreCase(root, phrase));
        }
    }

    /**
     * The record shrank below the original 3698 bytes.
     * <p>
     * The threshold is the record measured on a running server before the pass, not the record
     * this run happens to produce: a test that measured itself would pass over the very bytes it
     * was meant to cut. This body compiles against the unchanged base and fails there on this
     * assertion alone - 3698 is not smaller than 3698.
     * </p>
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void thePublishedRecordIsNowSmallerThanTheOriginal() throws IOException
    {
        int weight = publishedWeight(publishedRecord());
        assertTrue("project_admin publishes " + weight + " bytes; the original record was " //$NON-NLS-1$ //$NON-NLS-2$
            + ORIGINAL_RECORD_BYTES + " bytes and the shortened record has to be smaller than it", //$NON-NLS-1$
            weight < ORIGINAL_RECORD_BYTES);
    }

    /**
     * Calling the facade with no arguments still refuses and names every operation it accepts,
     * which is the published behaviour of the required {@code operation} argument.
     */
    @Test
    public void anEmptyCallIsStillRefusedByNamingEveryOperation()
    {
        String answer = new ProjectAdminFacadeTool().execute(Map.of());
        assertTrue(answer, answer.contains("operation is required")); //$NON-NLS-1$
        for (String operation : OPERATIONS)
        {
            assertTrue(answer, answer.contains(operation));
        }
        assertTrue(answer, answer.contains("help")); //$NON-NLS-1$
    }

    /**
     * An empty operation is still the same refusal, not a delegation.
     */
    @Test
    public void anEmptyOperationIsStillRefused()
    {
        String answer = new ProjectAdminFacadeTool().execute(Map.of("operation", "")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer, answer.contains("operation is required")); //$NON-NLS-1$
    }

    /**
     * A detector that does not report this cannot hold the operation list, so the negative proves
     * the family is observed rather than assumed.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void anOperationThatLeftTheCatalogIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "operation", "answer_dialog", "dialog_answer"); //$NON-NLS-1$
        assertReported(published, "operation", "answer_dialog"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A button whose matching no longer ignores the mnemonic and case is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aButtonThatNoLongerIgnoresCaseIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "button", "case", "spelling"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "button", "case"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A help topic whose summary list is gone is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aTopicWithoutItsSummariesIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "topic", "summaries", "headings"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "topic", "summaries"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A help search that no longer ignores case is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aHelpSearchThatNoLongerIgnoresCaseIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "find", "case-insensitive", "exact"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "find", "case-insensitive"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A project name that no longer says which operations require it is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aProjectNameThatIsNoLongerRequiredIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "projectName", "Required", "Optional"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "projectName", "required"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A version without its default-to-latest rule is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aVersionWithoutItsDefaultIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "version", "defaults", "guesses"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "version", "default"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Flipping the default that keeps the files is reported: this is the destructive distinction.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aChangedDeletionDefaultIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "deleteContent", "default false", "default true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "deleteContent", "default false"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A delete that no longer says it reaches the disk is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void deletionThatNoLongerReachesTheDiskIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "deleteContent", "from disk", "from the workspace"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "deleteContent", "from disk"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A reversed resync direction is reported: the model goes to the disk, never the other way.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aReversedResyncDirectionIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "objects", "export to disk", "import from disk"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "objects", "export"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Objects that are no longer required for the resync are reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aResyncWithoutItsRequiredObjectsIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "objects", "required", "optional"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "objects", "required"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A restart that no longer names the shutdown alternative is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aRestartWithoutItsShutdownIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "action", "shutdown", "close"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "action", "shutdown"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A delay without its upper bound is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aDelayWithoutItsBoundIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "delayMs", "max 60000", "no maximum"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "delayMs", "max 60000"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A launch-configuration filter that lost its default is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aFilterWithoutItsDefaultIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "type", "all (default)", "all"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "type", "default"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A subsystem name filter that is no longer case-insensitive is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aFilterThatNoLongerIgnoresCaseIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "nameFilter", "case-insensitive", "case-sensitive"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "nameFilter", "case-insensitive"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A content listing whose default is flipped is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aContentListingWithoutItsDefaultIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "includeContent", "default false", "default true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "includeContent", "default false"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A cap that became per parent instead of across the whole tree is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aCapThatBecamePerParentIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "limit", "whole tree", "each parent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "limit", "whole tree"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A refresh that now runs before the export instead of after it is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aRefreshThatRunsBeforeTheExportIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "refresh", "after the BM export", "before the BM export"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "refresh", "after the BM export"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A settle timeout that lost its default is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aSettleTimeoutWithoutItsDefaultIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "waitTimeoutMs", "default 10000", "no bound"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "waitTimeoutMs", "default 10000"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A synonym language that no longer falls back to the first available one is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aSynonymWithoutItsDefaultLanguageIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInProperty(published, "language", "first available", "a fixed language"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "language", "first available"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A facade whose root no longer says it mutates is reported: a caller has to read that before
     * it calls one.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aFacadeThatNoLongerSaysItMutatesIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInRoot(published, "mutate", "read"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "root", "mutate"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A facade that claims a dry run is reported, because the facade adds none.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aFacadeThatClaimsADryRunIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInRoot(published, "no dryRun", "dryRun supported"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "root", "no dryRun"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A root that hides the host restart is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aRootThatHidesTheHostRestartIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInRoot(published, "host EDT", "host process"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "root", "host EDT"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A root that no longer keeps the standalone aliases is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aRootThatDropsTheStandaloneAliasesIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        replaceInRoot(published, "standalone", "hidden"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReported(published, "root", "standalone"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A schema that is not an object is reported instead of throwing.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aSchemaThatIsNotAnObjectIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        published.add(INPUT_SCHEMA, new JsonPrimitive("not a schema")); //$NON-NLS-1$
        assertReported(published, "shape", "inputSchema"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A schema without its required list is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aSchemaWithoutItsRequiredOperationIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        schemaOf(published).remove(REQUIRED);
        assertReported(published, "shape", "required"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A property whose declared type changed is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aChangedArgumentTypeIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        propertyOf(published, "limit").addProperty(TYPE, "string"); //$NON-NLS-1$ //$NON-NLS-2$
        assertReported(published, "shape", "limit"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A property that left the schema is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aRemovedArgumentIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        propertiesOf(published).remove("limit"); //$NON-NLS-1$
        assertReported(published, "shape", "limit"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Properties written in another order are reported, because the declared order is the
     * contract the facade publishes.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aReorderedArgumentIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        JsonObject properties = propertiesOf(published);
        JsonObject reordered = new JsonObject();
        reordered.add("button", properties.get("button")); //$NON-NLS-1$ //$NON-NLS-2$
        for (String property : PROPERTY_ORDER)
        {
            if (!"button".equals(property)) //$NON-NLS-1$
            {
                reordered.add(property, properties.get(property));
            }
        }
        schemaOf(published).add(PROPERTIES, reordered);
        assertReported(published, "shape", "properties must be"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A properties member that is not an object is reported.
     *
     * @throws IOException when {@code tools/list} cannot be read
     */
    @Test
    public void aSchemaWithoutAPropertyMapIsReported() throws IOException
    {
        JsonObject published = publishedRecord().deepCopy();
        schemaOf(published).add(PROPERTIES, new JsonPrimitive("no properties")); //$NON-NLS-1$
        assertReported(published, "shape", "properties"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reads {@code project_admin} out of a real {@code tools/list} answer.
     *
     * @return the published record of the facade
     * @throws IOException when the request fails
     */
    private JsonObject publishedRecord() throws IOException
    {
        Map<String, String> headers = LiveServer.headers(
            "Content-Type", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Accept", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Authorization", server.bearer()); //$NON-NLS-1$
        LiveServer.Response answer = server.request("POST", "/mcp", headers, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"); //$NON-NLS-1$
        JsonObject document = JsonParser.parseString(answer.body).getAsJsonObject();
        JsonArray tools = document.getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
        for (JsonElement element : tools)
        {
            JsonObject tool = element.getAsJsonObject();
            if (TOOL_NAME.equals(tool.get("name").getAsString())) //$NON-NLS-1$
            {
                return tool;
            }
        }
        fail("tools/list does not advertise " + TOOL_NAME); //$NON-NLS-1$
        return null;
    }

    /**
     * The same element with every prose {@code description} removed, member order kept.
     *
     * @param element any part of the record
     * @return a copy without descriptions
     */
    private static JsonElement withoutDescriptions(JsonElement element)
    {
        if (element.isJsonArray())
        {
            JsonArray copied = new JsonArray();
            for (JsonElement item : element.getAsJsonArray())
            {
                copied.add(withoutDescriptions(item));
            }
            return copied;
        }
        if (!element.isJsonObject())
        {
            return element;
        }
        JsonObject copied = new JsonObject();
        for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet())
        {
            if (!DESCRIPTION.equals(member.getKey()))
            {
                copied.add(member.getKey(), withoutDescriptions(member.getValue()));
            }
        }
        return copied;
    }

    /**
     * The schema member of a record.
     *
     * @param published the record
     * @return the schema object, or <code>null</code> when the member is missing or not an object
     */
    private static JsonObject schemaOf(JsonObject published)
    {
        JsonElement schema = published.get(INPUT_SCHEMA);
        return schema != null && schema.isJsonObject() ? schema.getAsJsonObject() : null;
    }

    /**
     * The declared arguments of a record.
     *
     * @param published the record
     * @return the properties object, or <code>null</code> when it is missing or not an object
     */
    private static JsonObject propertiesOf(JsonObject published)
    {
        JsonObject schema = schemaOf(published);
        JsonElement properties = schema == null ? null : schema.get(PROPERTIES);
        return properties != null && properties.isJsonObject() ? properties.getAsJsonObject() : null;
    }

    /**
     * One declared argument of a record.
     *
     * @param published the record
     * @param property the argument name
     * @return its declaration, or <code>null</code> when it is missing or not an object
     */
    private static JsonObject propertyOf(JsonObject published, String property)
    {
        JsonObject properties = propertiesOf(published);
        JsonElement declaration = properties == null ? null : properties.get(property);
        return declaration != null && declaration.isJsonObject()
            ? declaration.getAsJsonObject() : null;
    }

    /**
     * The prose of a record or of one of its property declarations.
     *
     * @param holder the record or property declaration
     * @return the description, or an empty string when it is missing
     */
    private static String descriptionOf(JsonObject holder)
    {
        JsonElement description = holder == null ? null : holder.get(DESCRIPTION);
        return description != null && description.isJsonPrimitive() ? description.getAsString() : ""; //$NON-NLS-1$
    }

    /**
     * The string members of a JSON array.
     *
     * @param element the array, or <code>null</code>
     * @return its string values in order; empty for anything that is not an array
     */
    private static List<String> stringsOf(JsonElement element)
    {
        List<String> values = new ArrayList<>();
        if (element != null && element.isJsonArray())
        {
            for (JsonElement each : element.getAsJsonArray())
            {
                if (each.isJsonPrimitive())
                {
                    values.add(each.getAsString());
                }
            }
        }
        return values;
    }

    /**
     * Whether a text contains a phrase, ignoring ASCII case.
     *
     * @param text the text to search
     * @param phrase the phrase to look for
     * @return <code>true</code> when the phrase is present
     */
    private static boolean containsIgnoreCase(String text, String phrase)
    {
        return text.toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT));
    }

    /**
     * Every way the record's non-prose contract differs from the declaration, as readable lines.
     *
     * @param published the record
     * @return the failures; empty when the shape is the one declared
     */
    private static List<String> shapeFailures(JsonObject published)
    {
        List<String> failures = new ArrayList<>();
        JsonElement name = published.get("name"); //$NON-NLS-1$
        if (name == null || !name.isJsonPrimitive() || !TOOL_NAME.equals(name.getAsString()))
        {
            failures.add("shape: the tool name must be " + TOOL_NAME); //$NON-NLS-1$
        }
        if (descriptionOf(published).isBlank())
        {
            failures.add("shape: the tool description is missing"); //$NON-NLS-1$
        }
        JsonObject schema = schemaOf(published);
        if (schema == null)
        {
            failures.add("shape: inputSchema must be an object"); //$NON-NLS-1$
            return failures;
        }
        for (String member : schema.keySet())
        {
            if (!Set.of(TYPE, PROPERTIES, REQUIRED).contains(member))
            {
                failures.add("shape: unexpected schema member " + member); //$NON-NLS-1$
            }
        }
        JsonElement declaredType = schema.get(TYPE);
        if (declaredType == null || !declaredType.isJsonPrimitive()
            || !"object".equals(declaredType.getAsString())) //$NON-NLS-1$
        {
            failures.add("shape: the schema type must be object"); //$NON-NLS-1$
        }
        List<String> insisted = stringsOf(schema.get(REQUIRED));
        if (!List.of("operation").equals(insisted)) //$NON-NLS-1$
        {
            failures.add("shape: required must be [operation], not " + insisted); //$NON-NLS-1$
        }
        JsonObject properties = propertiesOf(published);
        if (properties == null)
        {
            failures.add("shape: properties must be an object"); //$NON-NLS-1$
            return failures;
        }
        List<String> declared = new ArrayList<>(properties.keySet());
        if (!PROPERTY_ORDER.equals(declared))
        {
            failures.add("shape: properties must be " + PROPERTY_ORDER + " in that order, not " //$NON-NLS-1$ //$NON-NLS-2$
                + declared);
        }
        for (String property : PROPERTY_ORDER)
        {
            JsonObject declaration = propertyOf(published, property);
            if (declaration == null)
            {
                failures.add("shape: property " + property + " is missing"); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            String expected = PROPERTY_TYPES.get(property);
            JsonElement actualType = declaration.get(TYPE);
            if (actualType == null || !actualType.isJsonPrimitive()
                || !expected.equals(actualType.getAsString()))
            {
                failures.add("shape: " + property + " must be a " + expected + ", not " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + (actualType == null ? "undeclared" : actualType.toString())); //$NON-NLS-1$
            }
            if ("array".equals(expected)) //$NON-NLS-1$
            {
                JsonElement items = declaration.get("items"); //$NON-NLS-1$
                JsonElement itemType = items != null && items.isJsonObject()
                    ? items.getAsJsonObject().get(TYPE) : null;
                if (itemType == null || !itemType.isJsonPrimitive()
                    || !"string".equals(itemType.getAsString())) //$NON-NLS-1$
                {
                    failures.add("shape: " + property + " must declare string items"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            if (descriptionOf(declaration).isBlank())
            {
                failures.add("shape: " + property + " carries no description"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return failures;
    }

    /**
     * Whether the record publishes the annotations of a writer that reaches only this machine.
     *
     * @param published the record
     * @return the failures; empty when the annotations are the declared ones
     */
    private static List<String> annotationFailures(JsonObject published)
    {
        List<String> failures = new ArrayList<>();
        JsonElement annotations = published.get(ANNOTATIONS);
        if (annotations == null || !annotations.isJsonObject())
        {
            failures.add("shape: annotations must carry openWorldHint false"); //$NON-NLS-1$
        }
        else if (!ORIGINAL_ANNOTATIONS.equals(annotations.toString()))
        {
            failures.add("shape: annotations must be " + ORIGINAL_ANNOTATIONS + ", not " //$NON-NLS-1$ //$NON-NLS-2$
                + annotations);
        }
        return failures;
    }

    /**
     * Everything the published record no longer tells a caller: its non-prose shape, its
     * annotations, the root rules and one row of the condition matrix per argument.
     *
     * @param published the record to judge
     * @return the failures, each prefixed with the family it belongs to; empty when all hold
     */
    private static List<String> contractFailures(JsonObject published)
    {
        List<String> failures = new ArrayList<>(shapeFailures(published));
        failures.addAll(annotationFailures(published));

        String root = descriptionOf(published);
        for (String phrase : ROOT_REQUIRED)
        {
            if (!containsIgnoreCase(root, phrase))
            {
                failures.add("root: the tool description must still say \"" + phrase + "\""); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (String phrase : ROOT_FORBIDDEN)
        {
            if (containsIgnoreCase(root, phrase))
            {
                failures.add("root: the tool description must not say \"" + phrase + "\""); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (PropertyCheck check : PROPERTY_CHECKS)
        {
            String text = descriptionOf(propertyOf(published, check.property));
            if (text.isEmpty())
            {
                failures.add(check.family + ": " + check.property //$NON-NLS-1$
                    + " carries no description"); //$NON-NLS-1$
                continue;
            }
            for (String phrase : check.required)
            {
                if (!containsIgnoreCase(text, phrase))
                {
                    failures.add(check.family + ": " + check.property //$NON-NLS-1$
                        + " must still say \"" + phrase + "\""); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            for (String phrase : check.forbidden)
            {
                if (containsIgnoreCase(text, phrase))
                {
                    failures.add(check.family + ": " + check.property //$NON-NLS-1$
                        + " must not say \"" + phrase + "\""); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        return failures;
    }

    /**
     * The declared type of every property, in the declaration order.
     *
     * @return the property-to-type map
     */
    private static Map<String, String> propertyTypes()
    {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("operation", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("button", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("topic", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("find", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("projectName", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("version", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("deleteContent", "boolean"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("objects", "array"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("action", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("delayMs", "integer"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("type", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("nameFilter", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("includeContent", "boolean"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("limit", "integer"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("refresh", "boolean"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("waitTimeoutMs", "integer"); //$NON-NLS-1$ //$NON-NLS-2$
        types.put("language", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(types);
    }

    /**
     * The seventeen matrix rows: for each argument, the phrases its description must still carry
     * and the claims it must not make.
     *
     * @return the matrix, one row per property
     */
    private static List<PropertyCheck> propertyChecks()
    {
        List<PropertyCheck> checks = new ArrayList<>();
        checks.add(new PropertyCheck("operation", "operation", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("list_projects", "list_configurations", "get_configuration_properties", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "create_project", "delete_project", "resync_to_disk", "restart_edt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "answer_dialog", "self_upkeep", "list_subsystems", "help", "snake_case", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "camelCase"), //$NON-NLS-1$
            List.of()));
        checks.add(new PropertyCheck("button", "button", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("answer_dialog", "button", "press", "case", "mnemonic"), List.of())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        checks.add(new PropertyCheck("topic", "topic", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("Help topic", "without", "summaries"), List.of())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        checks.add(new PropertyCheck("find", "find", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("help", "case-insensitive", "words", "chunk", "topic"), List.of())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        checks.add(new PropertyCheck("projectName", "projectName", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("required", "create_project", "delete_project", "resync_to_disk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "list_subsystems", "optional", "get_configuration_properties", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "list_configurations"), //$NON-NLS-1$
            List.of()));
        checks.add(new PropertyCheck("version", "version", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("create_project", "major.minor.micro", "optional", "default", "latest", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "EDT"), //$NON-NLS-1$
            List.of()));
        checks.add(new PropertyCheck("deleteContent", "deleteContent", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("delete_project", "from disk", "default false", "keep", "workspace"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            List.of("default true"))); //$NON-NLS-1$
        checks.add(new PropertyCheck("objects", "objects", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("resync_to_disk", "top-object", "FQN", "export", "to disk", "required"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            List.of("import", "from disk"))); //$NON-NLS-1$ //$NON-NLS-2$
        checks.add(new PropertyCheck("action", "action", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("restart", "default", "relaunch", "shutdown", "down"), List.of())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        checks.add(new PropertyCheck("delayMs", "delayMs", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("delay", "response", "default 1000", "max 60000"), List.of())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        checks.add(new PropertyCheck("type", "type", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("list_configurations", "attach", "client", "all", "default"), List.of())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        checks.add(new PropertyCheck("nameFilter", "nameFilter", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("list_subsystems", "partial", "case-insensitive"), List.of())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        checks.add(new PropertyCheck("includeContent", "includeContent", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("list_subsystems", "content", "FQN", "default false"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            List.of("default true"))); //$NON-NLS-1$
        checks.add(new PropertyCheck("limit", "limit", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("list_subsystems", "whole tree", "default 500"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List.of("per parent", "each parent"))); //$NON-NLS-1$ //$NON-NLS-2$
        checks.add(new PropertyCheck("refresh", "refresh", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("resync_to_disk", "after the BM export", "workspace", "default true"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            List.of()));
        checks.add(new PropertyCheck("waitTimeoutMs", "waitTimeoutMs", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("resync_to_disk", "BM export", "settle", "max wait", "default 10000"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            List.of()));
        checks.add(new PropertyCheck("language", "language", //$NON-NLS-1$ //$NON-NLS-2$
            List.of("list_subsystems", "synonym", "language", "first available"), List.of())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return Collections.unmodifiableList(checks);
    }

    /**
     * The compact UTF-8 weight of a published record, as a client receives it.
     *
     * @param published the record
     * @return its size in bytes
     */
    private static int publishedWeight(JsonObject published)
    {
        return published.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Replaces a phrase in one property's description, on a copy of the actual record.
     *
     * @param published the record copy to mutate
     * @param property the argument whose prose is changed
     * @param from the phrase the actual record is expected to carry
     * @param to its replacement
     */
    private static void replaceInProperty(JsonObject published, String property, String from,
        String to)
    {
        JsonObject declaration = propertyOf(published, property);
        assertNotNull("the mutation fixture needs property " + property, declaration); //$NON-NLS-1$
        String text = descriptionOf(declaration);
        assertTrue("the mutation fixture needs \"" + from + "\" in " + property, //$NON-NLS-1$ //$NON-NLS-2$
            text.contains(from));
        declaration.addProperty(DESCRIPTION, text.replace(from, to));
    }

    /**
     * Replaces a phrase in the root description, on a copy of the actual record.
     *
     * @param published the record copy to mutate
     * @param from the phrase the actual record is expected to carry
     * @param to its replacement
     */
    private static void replaceInRoot(JsonObject published, String from, String to)
    {
        String text = descriptionOf(published);
        assertTrue("the mutation fixture needs \"" + from + "\" in the root description", //$NON-NLS-1$ //$NON-NLS-2$
            text.contains(from));
        published.addProperty(DESCRIPTION, text.replace(from, to));
    }

    /**
     * Asserts that the detector names a family and the condition that was just removed from the
     * mutation fixture.
     *
     * @param published the mutated record
     * @param family the family the failure has to belong to
     * @param phrase the condition the failure has to name
     */
    private static void assertReported(JsonObject published, String family, String phrase)
    {
        List<String> failures = contractFailures(published);
        for (String failure : failures)
        {
            if (failure.startsWith(family + ": ") && containsIgnoreCase(failure, phrase)) //$NON-NLS-1$
            {
                return;
            }
        }
        fail("the detector did not report " + family + " / " + phrase + "; it said: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + failures);
    }

    /**
     * One row of the condition matrix: the argument it applies to, the family a failure is
     * reported under, and the phrases its description must keep and must not acquire.
     */
    private static final class PropertyCheck
    {
        private final String property;

        private final String family;

        private final List<String> required;

        private final List<String> forbidden;

        /**
         * Creates a matrix row.
         *
         * @param property the argument the row applies to
         * @param family the family its failures are reported under
         * @param required the phrases the description must still carry
         * @param forbidden the claims the description must not make
         */
        PropertyCheck(String property, String family, List<String> required, List<String> forbidden)
        {
            this.property = property;
            this.family = family;
            this.required = required;
            this.forbidden = forbidden;
        }
    }
}
