/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * A call with an unknown operation is answered with the operations the schema declares - the same
 * list, from the same source, not a second one written beside it.
 * <p>
 * A client builds its call from the schema, so the schema's operation list is the vocabulary the
 * caller knows. A refusal that lists something else - one operation more, one fewer, a name
 * spelled differently - teaches the caller a word the tool then refuses, and the two lists drift
 * apart in silence until one of them names an operation the other does not answer at all.
 * </p>
 * <p>
 * The sweep is every facade of the catalog: a tool that routes by {@code operation} and declares
 * a list of them in its schema. Two groups stand outside the comparison, each recorded here with
 * its reason so neither grows quietly.
 * </p>
 */
@RunWith(Parameterized.class)
public class AnUnknownOperationIsAnsweredWithTheSchemaListTest
{
    /**
     * Facades whose schema carries no operation list to compare against.
     * <p>
     * Each points at {@code operation=help} instead: the list lives in the help, and the schema
     * refuses to duplicate it. A facade newly added without a schema list fails this sweep until
     * it is recorded here with its reason or given the list.
     * </p>
     */
    private static final Set<String> NO_LIST_IN_SCHEMA = Set.of(
        // "Operation name. Use 'help' for catalog." - the catalog is the list.
        "dcs_workshop",
        // "Operation name. Use 'help' to list available operations and topics." - same.
        "edit_metadata",
        // Three sentences naming status / ask / help in prose, no list to parse.
        "naparnik");

    /**
     * Facades whose unknown-operation refusal cannot be reached from this runtime.
     * <p>
     * marker_corrections resolves the project before it dispatches, and the headless test
     * workspace holds none, so the call stops at the project refusal rather than the
     * operation one. The schema list still parses, which the sweep checks, so a schema that
     * stops listing its operations is caught all the same. sync_control is in the same
     * position and is left out with the other hidden names instead.
     * </p>
     */
    private static final Set<String> REFUSAL_BEHIND_A_LIVE_PROJECT =
        Set.of("marker_corrections"); //$NON-NLS-1$

    /** How a refusal introduces the names it lists. */
    private static final Pattern LISTING = Pattern.compile("(Allowed|Available|Known|Valid):");

    /** One operation name at the start of a piece of text. */
    private static final Pattern LEADING_NAME = Pattern.compile("^[A-Za-z0-9_]+");

    /** The name of the tool this run of the sweep is about. */
    private final String toolName;

    private McpToolCatalog registry;

    /**
     * Every facade of the catalog: a tool that routes by {@code operation}, declares a list of
     * them in its schema and is advertised under its own name, plus the recorded exceptions the
     * list does not reach.
     * <p>
     * The legacy standalone aliases share their facade's schema and would read as facades here;
     * they are told apart by the same unlisted set the server hides them with, not by a list of
     * names written beside it.
     * </p>
     *
     * @return the facade names, in the registry's order
     */
    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> facadesOfTheCatalog()
    {
        Set<String> hidden = ToolProfile.CANONICAL.getUnlistedTools();
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.clear();
        new McpHttpEndpoint().registerTools();
        List<Object[]> swept = new ArrayList<>();
        Set<String> named = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (IMcpTool tool : catalog.getAllTools())
        {
            String name = tool.getName();
            if (hidden.contains(name))
            {
                continue;
            }
            boolean exception = NO_LIST_IN_SCHEMA.contains(name)
                || REFUSAL_BEHIND_A_LIVE_PROJECT.contains(name);
            if (declaresAnOperationList(tool) || exception)
            {
                swept.add(new Object[]{name});
            }
            if (exception)
            {
                named.add(name);
            }
        }
        catalog.clear();
        // A recorded exception naming no tool is a typo, and a sweep that quietly lost its
        // exception pins nothing about it.
        Set<String> recorded = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        recorded.addAll(NO_LIST_IN_SCHEMA);
        recorded.addAll(REFUSAL_BEHIND_A_LIVE_PROJECT);
        assertTrue("exceptions recorded against tools the registry does not hold: " //$NON-NLS-1$
            + difference(recorded, named), recorded.equals(named));
        return swept;
    }

    /**
     * What one set holds that the other does not, for a failure that names the difference.
     *
     * @param left one set
     * @param right the other set
     * @return the names of {@code left} missing from {@code right}, comma-joined
     */
    private static String difference(Set<String> left, Set<String> right)
    {
        Set<String> only = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        only.addAll(left);
        only.removeAll(right);
        return String.join(", ", only); //$NON-NLS-1$
    }

    /**
     * Whether the tool's schema declares an operation list worth reconciling.
     *
     * @param tool the tool
     * @return {@code true} when the schema declares an operation parameter whose description
     *         parses as a list of two names or more
     */
    private static boolean declaresAnOperationList(IMcpTool tool)
    {
        JsonElement schema = JsonParser.parseString(tool.getInputSchema());
        if (!schema.isJsonObject())
        {
            return false;
        }
        JsonObject properties = schema.getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
        return properties != null && properties.has("operation") //$NON-NLS-1$
            && schemaOperations(operationDescription(tool)).size() >= 2;
    }

    /**
     * The sweep needs the tools registered; each run starts from a registry it filled itself, the
     * same way the schema-shape sweep does.
     */
    @Before
    public void registerEveryTool()
    {
        registry = McpToolCatalog.getInstance();
        registry.clear();
        new McpHttpEndpoint().registerTools();
    }

    /** Releases the shared catalog for whichever class runs next. */
    @AfterClass
    public static void clearRegistry()
    {
        McpToolCatalog.getInstance().clear();
    }

    /**
     * @param toolName the tool this run is about
     */
    public AnUnknownOperationIsAnsweredWithTheSchemaListTest(String toolName)
    {
        this.toolName = toolName;
    }

    /**
     * The refusal lists exactly the operations the schema declares.
     * <p>
     * A facade with no schema list is checked to still have none, and a facade whose refusal
     * needs a live project is checked to still parse its list - so both groups stay pinned rather
     * than silently growing.
     * </p>
     */
    @Test
    public void theRefusalListsWhatTheSchemaDeclares()
    {
        IMcpTool tool = registry.getTool(toolName);
        Set<String> declared = schemaOperations(operationDescription(tool));
        if (NO_LIST_IN_SCHEMA.contains(toolName))
        {
            assertTrue(toolName + " is recorded as having no schema list, but its description " //$NON-NLS-1$
                + "parses as one: " + declared + " - move it into the comparison", //$NON-NLS-1$
                declared.size() < 2);
            return;
        }
        assertTrue(toolName + " parses no operation list out of its schema description - record " //$NON-NLS-1$
            + "it with a reason or give it the list", declared.size() >= 2); //$NON-NLS-1$
        if (REFUSAL_BEHIND_A_LIVE_PROJECT.contains(toolName))
        {
            return;
        }
        Set<String> listed = listedOperations(refuseAnUnknownOperation(tool));
        assertEquals(toolName + " lists operations the schema does not declare, or misses some:", //$NON-NLS-1$
            declared, listed);
    }

    /**
     * The answer to a call naming an operation the tool does not know.
     *
     * @param tool the tool to call
     * @return the answer's text
     */
    private static String refuseAnUnknownOperation(IMcpTool tool)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "definitely_not_an_operation"); //$NON-NLS-1$ //$NON-NLS-2$
        // edit_form asks for the project and the form before it asks whether the operation is
        // one of its own; the values are never read that far, but the questions are asked.
        params.put("projectName", "definitely_no_such_project"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formFqn", "Catalog.None.Form.None.Form"); //$NON-NLS-1$ //$NON-NLS-2$
        return tool.execute(params);
    }

    /**
     * The description of the operation parameter, where the declared list lives.
     *
     * @param tool the tool
     * @return the description, or an empty string when it carries none
     */
    private static String operationDescription(IMcpTool tool)
    {
        JsonElement schema = JsonParser.parseString(tool.getInputSchema());
        if (!schema.isJsonObject())
        {
            return ""; //$NON-NLS-1$
        }
        JsonObject properties = schema.getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
        JsonObject operation = properties == null ? null
            : properties.getAsJsonObject("operation"); //$NON-NLS-1$
        JsonElement description = operation == null ? null : operation.get("description"); //$NON-NLS-1$
        return description != null && description.isJsonPrimitive()
            ? description.getAsString() : ""; //$NON-NLS-1$
    }

    /**
     * The operation names a refusal lists, out of the segment that lists them.
     *
     * @param answer the refusal's text
     * @return the names, case-insensitively distinct
     */
    private static Set<String> listedOperations(String answer)
    {
        // The end of the LAST listing is kept rather than the matcher: a matcher whose final
        // find() said no has no match to read the end of, however many it found before.
        int listingAt = -1;
        Matcher found = LISTING.matcher(answer);
        while (found.find())
        {
            listingAt = found.end();
        }
        assertTrue("the refusal lists no operations: " + answer, listingAt >= 0); //$NON-NLS-1$
        String segment = answer.substring(listingAt);
        int lineEnd = segment.indexOf('\n');
        return leadingNames((lineEnd < 0 ? segment : segment.substring(0, lineEnd))
            .replaceFirst("\\.$", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The operation names the schema's description declares.
     * <p>
     * The list is the pieces separated by slashes, bars or commas at the start of the description;
     * parenthetical notes are taken out first (they sit between names as often as after them) and
     * the rest of the sentence is cut at its first full stop. A description that names one thing
     * in prose and not in a list answers with fewer than two names, which is how a listless
     * schema is recognised.
     * </p>
     *
     * @param description the operation parameter's description
     * @return the names, case-insensitively distinct
     */
    private static Set<String> schemaOperations(String description)
    {
        String listPart = description.replaceAll("\\([^)]*\\)", " "); //$NON-NLS-1$ //$NON-NLS-2$
        int stop = listPart.length();
        for (int at : new int[]{listPart.indexOf('.'), listPart.indexOf(';')})
        {
            if (at >= 0)
            {
                stop = Math.min(stop, at);
            }
        }
        listPart = listPart.substring(0, stop);
        int colon = listPart.indexOf(':');
        if (colon >= 0 && colon < 32)
        {
            listPart = listPart.substring(colon + 1);
        }
        return leadingNames(listPart);
    }

    /**
     * The identifier each separator-delimited piece starts with.
     *
     * @param listPart the text holding the list
     * @return the leading names of its pieces
     */
    private static Set<String> leadingNames(String listPart)
    {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String piece : listPart.split(" / | \\| |, ")) //$NON-NLS-1$
        {
            Matcher name = LEADING_NAME.matcher(piece.trim());
            if (name.find())
            {
                names.add(name.group());
            }
        }
        return names;
    }
}
