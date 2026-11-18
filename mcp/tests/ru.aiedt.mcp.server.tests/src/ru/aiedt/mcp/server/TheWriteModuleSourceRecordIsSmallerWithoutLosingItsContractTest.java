/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The published {@code write_module_source} record keeps its contract while its prose shrinks.
 * <p>
 * The fixture is the tools/list entry measured before this prose change. The live entry is read
 * from {@code tools/list} on {@link LiveServer}; its name and annotations are the ones the server
 * publishes, not a copy of the fixture with a new description pasted in. Both sides are compared
 * through {@link JsonElement#toString()}, the compact serialization the catalogue byte counts use.
 * The check requires the record and its parameter prose to get smaller. It does not pin the
 * resulting size.
 * </p>
 */
public class TheWriteModuleSourceRecordIsSmallerWithoutLosingItsContractTest
{
    private static final String TOOL = "write_module_source"; //$NON-NLS-1$

    private static final String FIXTURE = "catalog-runtime-baseline-write-module-source.json"; //$NON-NLS-1$

    private static final String CONDITIONS = "prose-baseline/critical-conditions.json"; //$NON-NLS-1$

    /** SHA-256 of the measured tools/list entry, hex. */
    private static final String FIXTURE_SHA256 =
        "5919b422cc0a022001cb834595f3f93ccc7f85890731dd40058196487965f4ab"; //$NON-NLS-1$

    /** UTF-8 bytes of that entry. */
    private static final int RECORDED_BYTES = 5851;

    /** UTF-8 bytes of the parameter descriptions inside it, excluding the tool description. */
    private static final int RECORDED_PROSE = 4288;

    private static final int RECORDED_PROPERTIES = 20;

    /**
     * Quoted mode names in publication order. The closing quote belongs to the name, so
     * {@code 'replace'} is not a prefix of {@code 'replaceLines'}.
     */
    private static final String[] MODE_NAMES = {
        "'searchReplace'", //$NON-NLS-1$
        "'replace'", //$NON-NLS-1$
        "'append'", //$NON-NLS-1$
        "'replaceLines'", //$NON-NLS-1$
        "'replaceMethod'", //$NON-NLS-1$
        "'replaceMethods'", //$NON-NLS-1$
        "'insertBefore'", //$NON-NLS-1$
        "'insertAfter'", //$NON-NLS-1$
    };

    /** What each mode does, and only that mode's span may carry it. Aligned with {@link #MODE_NAMES}. */
    private static final String[][] MODE_EFFECTS = {
        { "the default", "oldSource", "swap in source" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        { "overwrite the entire file" }, //$NON-NLS-1$
        { "add text at the end" }, //$NON-NLS-1$
        { "overwrite a range of lines" }, //$NON-NLS-1$
        { "one named method" }, //$NON-NLS-1$
        { "atomic multi-method", "every target must be found", "nothing gets written" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        { "ahead of line N" }, //$NON-NLS-1$
        { "right after line N" }, //$NON-NLS-1$
    };

    private LiveServer server;

    /**
     * Stops the live server when this test started one.
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
     * The fixture is the measured runtime record, byte for byte.
     *
     * @throws IOException when the fixture cannot be read
     */
    @Test
    public void theSuppliedFixtureIsTheMeasuredRuntimeRecord() throws IOException
    {
        byte[] bytes = resourceBytes(FIXTURE);
        assertEquals(RECORDED_BYTES, bytes.length);
        assertEquals(FIXTURE_SHA256, sha256(bytes));
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        JsonObject entry = JsonParser.parseString(text).getAsJsonObject();
        assertEquals(text, entry.toString());
        assertEquals(RECORDED_BYTES, entry.toString().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(TOOL, entry.get("name").getAsString()); //$NON-NLS-1$
        JsonObject schema = entry.getAsJsonObject("inputSchema"); //$NON-NLS-1$
        assertEquals(RECORDED_PROPERTIES, schema.getAsJsonObject("properties").size()); //$NON-NLS-1$
        JsonArray required = schema.getAsJsonArray("required"); //$NON-NLS-1$
        assertEquals(1, required.size());
        assertEquals("projectName", required.get(0).getAsString()); //$NON-NLS-1$
        assertFalse(entry.getAsJsonObject("annotations").get("openWorldHint").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(RECORDED_PROSE, proseOf(schema));
    }

    /**
     * Dropping descriptions leaves the live tools/list entry equal to the fixture.
     *
     * @throws IOException when the server or the fixture cannot be read
     */
    @Test
    public void theLiveRecordKeepsTheNonProseShape() throws IOException
    {
        JsonObject before = fixture();
        JsonObject live = liveRecord();
        assertEquals(TOOL, live.get("name").getAsString()); //$NON-NLS-1$
        assertFalse("a fixture without prose would make this comparison vacuous", //$NON-NLS-1$
            proseOf(before.get("inputSchema")) == 0); //$NON-NLS-1$
        assertEquals(withoutDescriptions(before), withoutDescriptions(live));
    }

    /**
     * The live record and its parameter prose are strictly smaller than the measured entry.
     * <p>
     * The resulting sizes are not pinned. On the prose this fixture was recorded from, the live
     * entry compares equal, so the strict comparison fails there.
     * </p>
     *
     * @throws IOException when the server or the fixture cannot be read
     */
    @Test
    public void theRecordAndItsParameterProseShrink() throws IOException
    {
        JsonObject before = fixture();
        JsonObject live = liveRecord();
        int beforeBytes = before.toString().getBytes(StandardCharsets.UTF_8).length;
        int liveBytes = live.toString().getBytes(StandardCharsets.UTF_8).length;
        assertEquals(RECORDED_BYTES, beforeBytes);
        assertTrue(TOOL + " weighs " + liveBytes + " bytes, not less than " + beforeBytes, //$NON-NLS-1$ //$NON-NLS-2$
            liveBytes < beforeBytes);
        int beforeProse = proseOf(before.get("inputSchema")); //$NON-NLS-1$
        int liveProse = proseOf(live.get("inputSchema")); //$NON-NLS-1$
        assertEquals(RECORDED_PROSE, beforeProse);
        assertTrue(TOOL + " carries " + liveProse + " bytes of parameter prose, not less than " //$NON-NLS-1$ //$NON-NLS-2$
            + beforeProse, liveProse < beforeProse);
    }

    /**
     * Every phrase recorded for a property is still on that same property, in the fixture and live.
     *
     * @throws IOException when the server or a resource cannot be read
     */
    @Test
    public void everyRecordedPhraseRemainsOnItsProperty() throws IOException
    {
        List<String> missing = new ArrayList<>();
        missing.addAll(phrasesMissing("fixture", fixture())); //$NON-NLS-1$
        missing.addAll(phrasesMissing("live", liveRecord())); //$NON-NLS-1$
        assertTrue(String.join("\n", missing), missing.isEmpty()); //$NON-NLS-1$
    }

    /**
     * A mode's effect stays in that mode's span, and the other applicability facts stay in the
     * sentence that states them. Both the fixture and the live record have to pass.
     *
     * @throws IOException when the server or the fixture cannot be read
     */
    @Test
    public void applicabilityStaysInsideTheModeThatUsesIt() throws IOException
    {
        List<String> problems = new ArrayList<>();
        for (String problem : windowProblems(fixture()))
        {
            problems.add("fixture " + problem); //$NON-NLS-1$
        }
        for (String problem : windowProblems(liveRecord()))
        {
            problems.add("live " + problem); //$NON-NLS-1$
        }
        assertTrue(String.join("\n", problems), problems.isEmpty()); //$NON-NLS-1$
    }

    /**
     * The same mode words in the wrong spans do not satisfy the applicability check.
     * <p>
     * Every effect is still present in the string, so a search that only asks whether the words
     * occur would accept it.
     * </p>
     */
    @Test
    public void aShuffledModeListDoesNotPass()
    {
        String shuffled = "'searchReplace', 'replace', 'append', 'replaceLines', " //$NON-NLS-1$
            + "'replaceMethod', 'replaceMethods', 'insertBefore', 'insertAfter'. " //$NON-NLS-1$
            + "the default oldSource swap in source overwrite the entire file " //$NON-NLS-1$
            + "add text at the end overwrite a range of lines one named method " //$NON-NLS-1$
            + "atomic multi-method every target must be found nothing gets written " //$NON-NLS-1$
            + "ahead of line N right after line N."; //$NON-NLS-1$
        for (String[] effects : MODE_EFFECTS)
        {
            for (String effect : effects)
            {
                assertTrue(effect, shuffled.contains(effect));
            }
        }
        List<String> problems = modeWindowProblems(shuffled);
        assertTrue(problems.toString(),
            problems.contains("mode 'searchReplace' lost the default")); //$NON-NLS-1$
    }

    /**
     * Removing schema descriptions keeps an argument whose name is {@code description}.
     */
    @Test
    public void anArgumentNamedDescriptionIsKeptWhenProseIsRemoved()
    {
        JsonObject named = new JsonObject();
        named.addProperty("type", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        named.addProperty("description", "its own prose"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject properties = new JsonObject();
        properties.add("description", named); //$NON-NLS-1$
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
        schema.addProperty("description", "the prose"); //$NON-NLS-1$ //$NON-NLS-2$
        schema.add("properties", properties); //$NON-NLS-1$

        JsonObject stripped = withoutDescriptions(schema).getAsJsonObject();

        assertFalse(stripped.has("description")); //$NON-NLS-1$
        JsonObject kept = stripped.getAsJsonObject("properties").getAsJsonObject("description"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(kept);
        assertEquals("string", kept.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(kept.has("description")); //$NON-NLS-1$
    }

    /**
     * Phrases of {@code write_module_source} that are not on the property the matrix names.
     *
     * @param label fixture or live, used in the failure text.
     * @param entry one tools/list entry.
     * @return one line per missing phrase. Empty when every phrase is on its property.
     * @throws IOException when the condition matrix cannot be read
     */
    private static List<String> phrasesMissing(String label, JsonObject entry) throws IOException
    {
        JsonArray rows = JsonParser.parseString(resource(CONDITIONS)).getAsJsonObject()
            .getAsJsonArray(TOOL);
        assertNotNull(rows);
        assertEquals(29, rows.size());
        Set<String> ids = new HashSet<>();
        List<String> missing = new ArrayList<>();
        for (JsonElement element : rows)
        {
            JsonObject condition = element.getAsJsonObject();
            String id = condition.get("id").getAsString(); //$NON-NLS-1$
            assertTrue(id, ids.add(id));
            String property = location(condition);
            String text = textOf(entry, property);
            for (JsonElement required : condition.getAsJsonArray("requires")) //$NON-NLS-1$
            {
                String token = required.getAsString();
                if (!holds(text, token))
                {
                    String where = property == null ? "description" : property; //$NON-NLS-1$
                    missing.add(label + " " + id + " lost \"" + token + "\" at " + where); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
        }
        for (int number = 1; number <= 29; number++)
        {
            assertTrue(ids.contains("W" + number)); //$NON-NLS-1$
        }
        return missing;
    }

    /**
     * Applicability failures in one entry.
     * <p>
     * Mode effects have to sit between that mode's quoted name and the next one. The source,
     * confirmation, path, line and method facts have to share the sentence that relates them.
     * </p>
     *
     * @param entry one tools/list entry.
     * @return the failures. Empty when every relationship holds.
     */
    private static List<String> windowProblems(JsonObject entry)
    {
        List<String> problems = new ArrayList<>();
        problems.addAll(modeWindowProblems(description(entry, "mode"))); //$NON-NLS-1$
        String sourceText = description(entry, "source"); //$NON-NLS-1$
        String[] anchors = {
            "For replace:", //$NON-NLS-1$
            "For searchReplace:", //$NON-NLS-1$
            "For append:", //$NON-NLS-1$
            "For replaceMethod/replaceMethods:", //$NON-NLS-1$
        };
        String[] effects = {
            "the module's entire new content", //$NON-NLS-1$
            "what to put in place of oldSource", //$NON-NLS-1$
            "the text being added", //$NON-NLS-1$
            "the full method", //$NON-NLS-1$
        };
        int[] anchorAt = new int[anchors.length];
        int cursor = 0;
        for (int index = 0; index < anchors.length; index++)
        {
            int at = sourceText.indexOf(anchors[index], cursor);
            if (at < 0)
            {
                problems.add("source missing " + anchors[index]); //$NON-NLS-1$
                at = cursor;
            }
            anchorAt[index] = at;
            cursor = at + anchors[index].length();
        }
        for (int index = 0; index < anchors.length; index++)
        {
            int end = index + 1 < anchorAt.length ? anchorAt[index + 1] : sourceText.length();
            String span = anchorAt[index] <= end ? sourceText.substring(anchorAt[index], end) : ""; //$NON-NLS-1$
            if (!span.contains(effects[index]))
            {
                problems.add("source effect left " + anchors[index]); //$NON-NLS-1$
            }
        }
        List<String> sourceSentences = sentences(sourceText);
        String header = sentenceContaining(sourceSentences, "never leaving a duplicate"); //$NON-NLS-1$
        if (header == null || !header.contains("header comment") || !header.contains("left alone")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            problems.add("header facts split"); //$NON-NLS-1$
        }
        String drop = sentenceContaining(sourceSentences, "use searchReplace instead"); //$NON-NLS-1$
        if (drop == null || !drop.contains("header")) //$NON-NLS-1$
        {
            problems.add("drop-header fact split"); //$NON-NLS-1$
        }
        problems.addAll(confirmWindowProblems(description(entry, "confirmFullReplace"))); //$NON-NLS-1$
        problems.addAll(expectedWindowProblems(description(entry, "expectedText"))); //$NON-NLS-1$
        problems.addAll(pathWindowProblems(description(entry, "modulePath"))); //$NON-NLS-1$
        problems.addAll(lineWindowProblems(description(entry, "line"))); //$NON-NLS-1$
        problems.addAll(methodWindowProblems(description(entry, "methodName"))); //$NON-NLS-1$
        return problems;
    }

    /**
     * Mode names whose following text does not state that mode's effect.
     *
     * @param mode the mode parameter's description.
     * @return one failure per missing effect. Empty when each effect is inside its own span.
     */
    private static List<String> modeWindowProblems(String mode)
    {
        List<String> problems = new ArrayList<>();
        int[] positions = new int[MODE_NAMES.length];
        int cursor = 0;
        for (int index = 0; index < MODE_NAMES.length; index++)
        {
            int at = mode.indexOf(MODE_NAMES[index], cursor);
            if (at < 0)
            {
                problems.add("mode missing " + MODE_NAMES[index]); //$NON-NLS-1$
                at = cursor;
            }
            positions[index] = at;
            cursor = at + MODE_NAMES[index].length();
        }
        for (int index = 0; index < MODE_NAMES.length; index++)
        {
            int end = index + 1 < positions.length ? positions[index + 1] : mode.length();
            String span = positions[index] <= end ? mode.substring(positions[index], end) : ""; //$NON-NLS-1$
            for (String effect : MODE_EFFECTS[index])
            {
                if (!span.contains(effect))
                {
                    problems.add("mode " + MODE_NAMES[index] + " lost " + effect); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        return problems;
    }

    /**
     * The 50 percent refusal has to precede the 30 percent warning, and additive modes have to be
     * the ones described as never gated.
     *
     * @param confirm the confirmFullReplace description.
     * @return the failures. Empty when the order holds.
     */
    private static List<String> confirmWindowProblems(String confirm)
    {
        List<String> problems = new ArrayList<>();
        List<String> confirmSentences = sentences(confirm);
        int fifty = sentenceIndex(confirmSentences, "more than 50%"); //$NON-NLS-1$
        int thirty = sentenceIndex(confirmSentences, "Removals above 30%"); //$NON-NLS-1$
        if (fifty < 0 || thirty < 0 || fifty >= thirty)
        {
            problems.add("50/30 order"); //$NON-NLS-1$
        }
        else if (!confirmSentences.get(thirty).contains("protection' warning")) //$NON-NLS-1$
        {
            problems.add("30% sentence lost protection warning"); //$NON-NLS-1$
        }
        if (!confirm.contains("replace (a replace that strips more than half")) //$NON-NLS-1$
        {
            problems.add("half-replace token"); //$NON-NLS-1$
        }
        int addAt = confirm.indexOf("append"); //$NON-NLS-1$
        int gateAt = confirm.indexOf("never gated"); //$NON-NLS-1$
        if (addAt < 0 || gateAt < addAt)
        {
            problems.add("additive never gated"); //$NON-NLS-1$
        }
        for (String name : new String[] { "insertBefore", "insertAfter" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            int at = confirm.indexOf(name);
            if (at < 0 || at > gateAt)
            {
                problems.add("additive order " + name); //$NON-NLS-1$
            }
        }
        return problems;
    }

    /**
     * The range being checked has to be in the sentence that calls expectedText a safety net.
     * The rejection facts have to follow that sentence, not replace it.
     *
     * @param expected the expectedText description.
     * @return the failures. Empty when the two parts stay apart.
     */
    private static List<String> expectedWindowProblems(String expected)
    {
        List<String> problems = new ArrayList<>();
        List<String> expectedSentences = sentences(expected);
        String first = expectedSentences.isEmpty()
            ? "" //$NON-NLS-1$
            : expectedSentences.get(0).toLowerCase(Locale.ROOT);
        if (!first.contains("safety net for replacelines")) //$NON-NLS-1$
        {
            problems.add("expected first sentence"); //$NON-NLS-1$
        }
        if (!first.contains("linefrom..lineto")) //$NON-NLS-1$
        {
            problems.add("expected range not in first sentence"); //$NON-NLS-1$
        }
        StringBuilder later = new StringBuilder();
        for (int index = 1; index < expectedSentences.size(); index++)
        {
            if (later.length() > 0)
            {
                later.append(' ');
            }
            later.append(expectedSentences.get(index));
        }
        for (String token : new String[] {
            "does not match", //$NON-NLS-1$
            "REJECTED", //$NON-NLS-1$
            "expected-vs-actual diff", //$NON-NLS-1$
            "silent overwrite", //$NON-NLS-1$
            "wrong lines", //$NON-NLS-1$
            "module changed", //$NON-NLS-1$
        })
        {
            if (!later.toString().contains(token))
            {
                problems.add("expected later lost " + token); //$NON-NLS-1$
            }
        }
        return problems;
    }

    /**
     * The path rule, the refusal, and the objectName alternative each stay in one place.
     *
     * @param path the modulePath description.
     * @return the failures. Empty when each fact shares its sentence.
     */
    private static List<String> pathWindowProblems(String path)
    {
        List<String> problems = new ArrayList<>();
        boolean strip = false;
        boolean refusal = false;
        for (String sentence : sentences(path))
        {
            if (sentence.contains("from the src/ folder") && sentence.contains("leading 'src/' is stripped")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                strip = true;
            }
            if (sentence.contains("misspelled kind folder") //$NON-NLS-1$
                && sentence.contains("object the configuration does not hold") //$NON-NLS-1$
                && sentence.contains("canonical path")) //$NON-NLS-1$
            {
                refusal = true;
            }
        }
        if (!strip)
        {
            problems.add("path strip split"); //$NON-NLS-1$
        }
        if (!refusal)
        {
            problems.add("path refusal split"); //$NON-NLS-1$
        }
        if (!path.contains("objectName + moduleType")) //$NON-NLS-1$
        {
            problems.add("path alternative"); //$NON-NLS-1$
        }
        return problems;
    }

    /**
     * insertBefore's shift and insertAfter's shift stay in that order, after the shared line rule.
     *
     * @param line the line description.
     * @return the failures. Empty when the four effects occur in order.
     */
    private static List<String> lineWindowProblems(String line)
    {
        List<String> problems = new ArrayList<>();
        if (!line.contains("1-based") || !line.contains("insertBefore/insertAfter")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            problems.add("line shared"); //$NON-NLS-1$
        }
        int beforeEffect = line.indexOf("right before line N"); //$NON-NLS-1$
        int shift = line.indexOf("N+sourceLines"); //$NON-NLS-1$
        int afterEffect = line.indexOf("right after line N"); //$NON-NLS-1$
        int further = line.indexOf("N+1 moves further down"); //$NON-NLS-1$
        if (!(beforeEffect >= 0 && beforeEffect < shift && shift < afterEffect && afterEffect < further))
        {
            problems.add("line effects out of order"); //$NON-NLS-1$
        }
        return problems;
    }

    /**
     * The method lookup and the span it replaces each stay inside one sentence.
     *
     * @param method the methodName description.
     * @return the failures. Empty when both sentences hold together.
     */
    private static List<String> methodWindowProblems(String method)
    {
        List<String> problems = new ArrayList<>();
        boolean lookup = false;
        boolean span = false;
        for (String sentence : sentences(method))
        {
            if (sentence.contains("The method's name for replaceMethod mode") //$NON-NLS-1$
                && sentence.contains("Procedure/Function by that name")) //$NON-NLS-1$
            {
                lookup = true;
            }
            if (sentence.contains("&-directives") //$NON-NLS-1$
                && sentence.contains("header comment") //$NON-NLS-1$
                && sentence.contains("opens with one")) //$NON-NLS-1$
            {
                span = true;
            }
        }
        if (!lookup)
        {
            problems.add("method lookup split"); //$NON-NLS-1$
        }
        if (!span)
        {
            problems.add("method span split"); //$NON-NLS-1$
        }
        return problems;
    }

    /**
     * Sentences split on a period that ends a sentence. The abbreviation {@code e.g.} is not a split.
     *
     * @param text one description.
     * @return the sentences, in order, each including its period when it had one.
     */
    private static List<String> sentences(String text)
    {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < text.length())
        {
            if (text.startsWith("e.g.", index)) //$NON-NLS-1$
            {
                index += 4;
                continue;
            }
            if (text.charAt(index) == '.'
                && (index + 1 == text.length() || text.charAt(index + 1) == ' '))
            {
                parts.add(text.substring(start, index + 1));
                start = index + 1;
                if (start < text.length() && text.charAt(start) == ' ')
                {
                    start++;
                }
                index = start;
                continue;
            }
            index++;
        }
        if (start < text.length())
        {
            parts.add(text.substring(start));
        }
        return parts;
    }

    /**
     * The first sentence containing a phrase.
     *
     * @param sentences the sentences of one description.
     * @param phrase the phrase to find.
     * @return that sentence, or <code>null</code> when none contains it.
     */
    private static String sentenceContaining(List<String> sentences, String phrase)
    {
        for (String sentence : sentences)
        {
            if (sentence.contains(phrase))
            {
                return sentence;
            }
        }
        return null;
    }

    /**
     * Index of the first sentence containing a phrase.
     *
     * @param sentences the sentences of one description.
     * @param phrase the phrase to find.
     * @return the index, or -1 when none contains it.
     */
    private static int sentenceIndex(List<String> sentences, String phrase)
    {
        for (int index = 0; index < sentences.size(); index++)
        {
            if (sentences.get(index).contains(phrase))
            {
                return index;
            }
        }
        return -1;
    }

    /**
     * One parameter description, or the tool description when {@code property} is null.
     *
     * @param entry one tools/list entry.
     * @param property the parameter name, or <code>null</code> for the tool description.
     * @return the description text.
     */
    private static String description(JsonObject entry, String property)
    {
        if (property == null)
        {
            return entry.get("description").getAsString(); //$NON-NLS-1$
        }
        return entry.getAsJsonObject("inputSchema") //$NON-NLS-1$
            .getAsJsonObject("properties") //$NON-NLS-1$
            .getAsJsonObject(property)
            .get("description") //$NON-NLS-1$
            .getAsString();
    }

    /**
     * The property a condition watches. Null in the matrix means the tool description.
     *
     * @param condition one matrix row.
     * @return the parameter name, or <code>null</code> for the tool description.
     */
    private static String location(JsonObject condition)
    {
        JsonElement value = condition.get("property"); //$NON-NLS-1$
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /**
     * The description a condition watches.
     *
     * @param tool the published tool entry.
     * @param property the parameter name, or <code>null</code> for the tool description.
     * @return the description, or <code>null</code> when that property is absent.
     */
    private static String textOf(JsonObject tool, String property)
    {
        if (property == null)
        {
            return tool.has("description") ? tool.get("description").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        }
        JsonObject schema = tool.getAsJsonObject("inputSchema"); //$NON-NLS-1$
        if (schema == null || !schema.has("properties")) //$NON-NLS-1$
        {
            return null;
        }
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        if (!properties.has(property) || !properties.get(property).isJsonObject())
        {
            return null;
        }
        JsonElement description = properties.getAsJsonObject(property).get("description"); //$NON-NLS-1$
        return description == null || description.isJsonNull() ? null : description.getAsString();
    }

    /**
     * Whether a description carries a required phrase.
     *
     * @param text the description, or <code>null</code> when it is not published.
     * @param required the phrase.
     * @return <code>true</code> when the phrase is there, case-insensitively.
     */
    private static boolean holds(String text, String required)
    {
        return text != null
            && text.toLowerCase(Locale.ROOT).contains(required.toLowerCase(Locale.ROOT));
    }

    /**
     * The same value with every schema description removed.
     * <p>
     * Inside {@code properties} the keys are parameter names, so a parameter named description
     * stays while the keyword of that spelling goes.
     * </p>
     *
     * @param element any part of a tool entry.
     * @return a copy without prose.
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
        JsonObject source = element.getAsJsonObject();
        JsonObject copied = new JsonObject();
        for (String key : new TreeSet<>(source.keySet()))
        {
            if ("description".equals(key)) //$NON-NLS-1$
            {
                continue;
            }
            JsonElement value = source.get(key);
            if ("properties".equals(key) && value.isJsonObject()) //$NON-NLS-1$
            {
                JsonObject named = new JsonObject();
                JsonObject declared = value.getAsJsonObject();
                for (String name : new TreeSet<>(declared.keySet()))
                {
                    named.add(name, withoutDescriptions(declared.get(name)));
                }
                copied.add(key, named);
            }
            else
            {
                copied.add(key, withoutDescriptions(value));
            }
        }
        return copied;
    }

    /**
     * UTF-8 bytes of every primitive {@code description} in a schema.
     *
     * @param element a schema, or part of one.
     * @return the byte count. The tool's own description is not part of the schema, so it is not
     *         included.
     */
    private static int proseOf(JsonElement element)
    {
        int bytes = 0;
        if (element == null || element.isJsonNull())
        {
            return bytes;
        }
        if (element.isJsonObject())
        {
            for (java.util.Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet())
            {
                if ("description".equals(member.getKey()) && member.getValue().isJsonPrimitive()) //$NON-NLS-1$
                {
                    bytes += member.getValue().getAsString().getBytes(StandardCharsets.UTF_8).length;
                }
                else
                {
                    bytes += proseOf(member.getValue());
                }
            }
        }
        else if (element.isJsonArray())
        {
            for (JsonElement each : element.getAsJsonArray())
            {
                bytes += proseOf(each);
            }
        }
        return bytes;
    }

    /**
     * The measured entry.
     *
     * @return the fixture parsed as JSON.
     * @throws IOException when the fixture cannot be read
     */
    private static JsonObject fixture() throws IOException
    {
        return JsonParser.parseString(new String(resourceBytes(FIXTURE), StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    /**
     * The write_module_source entry from a live tools/list response.
     *
     * @return the entry the server published.
     * @throws IOException when the server cannot be asked
     */
    private JsonObject liveRecord() throws IOException
    {
        if (server == null)
        {
            server = LiveServer.start();
        }
        java.util.Map<String, String> headers = LiveServer.headers(
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
            if (TOOL.equals(tool.get("name").getAsString())) //$NON-NLS-1$
            {
                return tool;
            }
        }
        fail(TOOL + " is no longer advertised"); //$NON-NLS-1$
        return null;
    }

    /**
     * SHA-256 of a byte sequence, as lowercase hex.
     *
     * @param data the bytes.
     * @return the hex digest.
     */
    private static String sha256(byte[] data)
    {
        try
        {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(data); //$NON-NLS-1$
            char[] digits = "0123456789abcdef".toCharArray(); //$NON-NLS-1$
            char[] out = new char[hashed.length * 2];
            for (int index = 0; index < hashed.length; index++)
            {
                int value = hashed[index] & 0xff;
                out[index * 2] = digits[value >>> 4];
                out[index * 2 + 1] = digits[value & 0x0f];
            }
            return new String(out);
        }
        catch (NoSuchAlgorithmException failure)
        {
            throw new AssertionError(failure);
        }
    }

    /**
     * Bytes of a classpath resource next to this class.
     *
     * @param name the resource name.
     * @return the bytes.
     * @throws IOException when the resource is missing or cannot be read
     */
    private static byte[] resourceBytes(String name) throws IOException
    {
        try (InputStream stream =
            TheWriteModuleSourceRecordIsSmallerWithoutLosingItsContractTest.class.getResourceAsStream(name))
        {
            if (stream == null)
            {
                fail("missing test resource " + name); //$NON-NLS-1$
            }
            return stream.readAllBytes();
        }
    }

    /**
     * Text of a classpath resource next to this class.
     *
     * @param name the resource name.
     * @return the UTF-8 text.
     * @throws IOException when the resource is missing or cannot be read
     */
    private static String resource(String name) throws IOException
    {
        return new String(resourceBytes(name), StandardCharsets.UTF_8);
    }
}
