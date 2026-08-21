/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.server.support.BmComparisonHelper;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Reports what separates a reworked configuration from a new delivery, against the delivery they
 * both came from.
 * <p>
 * That is an update on support stated exactly: three sides, not two. Two-sided comparison answers
 * "what differs", which is the wrong question when a configuration has been reworked - almost
 * everything differs, and the part that matters is which side changed it. With the common ancestor
 * present the environment can tell a change made by the vendor from a change made here, and that is
 * the difference between a report and a decision.
 * </p>
 * <p>
 * <b>Reading is the default; merging is possible and deliberate.</b> A merge happens only when the
 * caller names the intent, has supplied decisions to apply, and - past a problem the environment
 * itself called blocking - asks again in different words. A merge writes into a configuration and a
 * wrong one is not undone by a button, so nothing about it is a default, a flag, or a shorthand.
 * </p>
 */
public class ThreeWayComparisonTool
    implements IMcpTool
{
    /**
     * Says what the merge did to the vendor support modes, and how to undo it.
     * <p>
     * Three answers rather than two. Modes unchanged is one. Modes changed with a snapshot on disk
     * names the file and the operation that puts them back. Modes changed with no snapshot is the
     * one that must not be softened: the work is unprotected and there is nothing to restore from.
     * </p>
     *
     * @param outcome what the comparison found.
     * @return the sentence to put in the answer
     */
    private static String supportNote(BmComparisonHelper.Outcome outcome)
    {
        if (!outcome.supportModesChanged)
        {
            return "the vendor support modes are unchanged by this call"; //$NON-NLS-1$
        }
        String what = "this merge CHANGED the vendor support modes - compare supportModesBefore " //$NON-NLS-1$
            + "with supportModesAfter, and read supportModesLost for the objects that lost one. " //$NON-NLS-1$
            + "The environment writes the support model as part of the merge and its merge rules " //$NON-NLS-1$
            + "do not prevent it. "; //$NON-NLS-1$
        if (outcome.supportSnapshotFile == null)
        {
            return what + "No snapshot was written, so the previous modes cannot be restored from " //$NON-NLS-1$
                + "this run."; //$NON-NLS-1$
        }
        return what + "The modes as they were before the merge are in " //$NON-NLS-1$
            + outcome.supportSnapshotFile
            + " - put them back with support_registry operation=restore_modes."; //$NON-NLS-1$
    }

    @Override
    public String getName()
    {
        return "compare_three_way"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Compares an open project against a new delivery and, optionally, against the " //$NON-NLS-1$
            + "delivery both came from - the three sides of an update on support. Reports node " //$NON-NLS-1$
            + "counts, how many differ, how many exist on one side only, the metadata objects " //$NON-NLS-1$
            + "that moved by name, and the problems the environment raises. With an ancestor " //$NON-NLS-1$
            + "every changed object carries changedBy - OURS, VENDOR or BOTH - so a customisation " //$NON-NLS-1$
            + "is told apart from a vendor change and BOTH marks the conflicts; without an " //$NON-NLS-1$
            + "ancestor changedBy is UNKNOWN rather than guessed. Each object also reports the " //$NON-NLS-1$
            + "merge rule the environment itself proposes. A MERGE also takes the vendor " //$NON-NLS-1$
            + "support settings from the delivery - that cannot be prevented through the " //$NON-NLS-1$
            + "environment's merge rules, so instead the support modes are counted before " //$NON-NLS-1$
            + "and after and reported as supportModesBefore, supportModesAfter and " //$NON-NLS-1$
            + "supportModesChanged. Decisions about " //$NON-NLS-1$
            + "individual objects can be recorded and written to a settings file that EDT reads " //$NON-NLS-1$
            + "back when a person runs the merge, and a file written earlier can be read back in " //$NON-NLS-1$
            + "through decisionsFrom. Reading is the default and changes nothing. Passing " //$NON-NLS-1$
            + "A configuration with no customisations updates in one call with " //$NON-NLS-1$
            + "intent=UPDATE_UNCHANGED, which needs no decisions and refuses itself the moment " //$NON-NLS-1$
            + "anything was changed here or on both sides. " //$NON-NLS-1$
            + "intent=MERGE applies the decisions to the project, which is IRREVERSIBLE and is " //$NON-NLS-1$
            + "refused when the environment raises a blocking problem or when no decisions were " //$NON-NLS-1$
            + "given; after a merge the touched objects are revalidated and the errors standing " //$NON-NLS-1$
            + "against them are reported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Open project that plays our side (MAIN)", true) //$NON-NLS-1$
            .stringProperty("otherPath", //$NON-NLS-1$
                "Directory holding the configuration to compare against (OTHER)", true) //$NON-NLS-1$
            .stringProperty("ancestorPath", //$NON-NLS-1$
                "Directory holding the common ancestor (COMMON_ANCESTOR). Omit for a two-sided " //$NON-NLS-1$
                    + "comparison.") //$NON-NLS-1$
            .stringProperty("decisions", //$NON-NLS-1$
                "What to do with objects, as a JSON array. An entry names one object - " //$NON-NLS-1$
                    + "{\"object\":\"...\",\"rule\":\"...\"} - or a whole class of them - " //$NON-NLS-1$
                    + "{\"select\":\"matching\",\"rule\":\"...\"}, which covers every object " //$NON-NLS-1$
                    + "the filter arguments matched, so changedBy=OURS with select=matching is " //$NON-NLS-1$
                    + "one call instead of thousands. The rule each object ends up carrying is " //$NON-NLS-1$
                    + "read back from the comparison, and the ones that did not take are named " //$NON-NLS-1$
                    + "in massRefused and massMismatched. The object is named as this tool " //$NON-NLS-1$
                    + "names it in changed; " //$NON-NLS-1$
                    + "the rule is one of GET_FROM_OTHER, DO_NOT_MERGE, MERGE_PRIORITIZING_MAIN, " //$NON-NLS-1$
                    + "MERGE_PRIORITIZING_OTHER, CUSTOM_MERGE, MERGE_USING_EXTERNAL_TOOL. " //$NON-NLS-1$
                    + "Recorded on the comparison, and applied only when intent says to.") //$NON-NLS-1$
            .stringProperty("decisionsPath", //$NON-NLS-1$
                "Absolute path to write the recorded decisions to, in the format EDT reads back " //$NON-NLS-1$
                    + "when a person runs the merge. Must end in .zip. Without it the decisions " //$NON-NLS-1$
                    + "die with the comparison.") //$NON-NLS-1$
            .stringProperty("decisionsFrom", //$NON-NLS-1$
                "Absolute path to a settings file (.zip) written earlier - by this tool or by a " //$NON-NLS-1$
                    + "person in EDT - whose decisions and hand-made object correspondences are " //$NON-NLS-1$
                    + "applied to this comparison before anything else. This is how work decided " //$NON-NLS-1$
                    + "by eye comes back to be carried out.") //$NON-NLS-1$
            .stringProperty("changedBy", //$NON-NLS-1$
                "List only objects with this attribution: OURS, VENDOR, BOTH or UNKNOWN. The " //$NON-NLS-1$
                    + "counts are unaffected - they always cover everything. OURS is what a " //$NON-NLS-1$
                    + "customisation-preserving update needs to enumerate.") //$NON-NLS-1$
            .stringProperty("type", //$NON-NLS-1$
                "List only objects of this metadata type, as the comparison qualifies names " //$NON-NLS-1$
                    + "(Catalog, Document, CommonModule).") //$NON-NLS-1$
            .booleanProperty("oneSided", //$NON-NLS-1$
                "true lists only objects present on one side, false only those present on both. " //$NON-NLS-1$
                    + "Omit for either.") //$NON-NLS-1$
            .booleanProperty("mustBeMergedOnly", //$NON-NLS-1$
                "List only objects the environment says must take part in a merge.") //$NON-NLS-1$
            .integerProperty("offset", //$NON-NLS-1$
                "How many matching objects to skip. Default 0. With limit this walks the whole " //$NON-NLS-1$
                    + "set: a real update runs to tens of thousands of changed objects and one " //$NON-NLS-1$
                    + "page names at most 500 of them.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "How many objects to name. Default and maximum 500.") //$NON-NLS-1$
            .stringProperty("scope", //$NON-NLS-1$
                "Compare only these objects, comma separated and named as this tool names them " //$NON-NLS-1$
                    + "(Catalog.X,Document.Y). Omit to compare the whole configuration, which on " //$NON-NLS-1$
                    + "a real one means minutes and a tree of some two hundred thousand nodes. " //$NON-NLS-1$
                    + "The environment adds what the named objects cannot be compared without, " //$NON-NLS-1$
                    + "and the additions come back in scopeExtendedBy. A name the environment " //$NON-NLS-1$
                    + "does not recognise is reported in scopeUnrecognised rather than dropped: " //$NON-NLS-1$
                    + "a scope of misspelled names compares nothing and would otherwise answer " //$NON-NLS-1$
                    + "that there are no differences. An object renamed between the two sides " //$NON-NLS-1$
                    + "cannot be scoped - it is called one thing here and another in the " //$NON-NLS-1$
                    + "delivery - so compare the whole configuration for those.") //$NON-NLS-1$
            .booleanProperty("closeSession", //$NON-NLS-1$
                "Close the comparison after answering instead of keeping it open for further " //$NON-NLS-1$
                    + "pages. Off by default: a comparison of a real configuration takes minutes, " //$NON-NLS-1$
                    + "and walking what changed a page at a time would otherwise pay that cost " //$NON-NLS-1$
                    + "per page. An open comparison expires by itself after 20 idle minutes.") //$NON-NLS-1$
            .booleanProperty("ignoreOriginMismatch", //$NON-NLS-1$
                "Compare the sides even when they do not identify as the same configuration in " //$NON-NLS-1$
                    + "different versions. Off by default: an ancestor from another configuration " //$NON-NLS-1$
                    + "inverts every changedBy in the answer without failing. Legitimate cases " //$NON-NLS-1$
                    + "exist - a renamed configuration, a vendor handover - and the mismatches are " //$NON-NLS-1$
                    + "then reported in originMismatches rather than swallowed.") //$NON-NLS-1$
            .stringProperty("intent", //$NON-NLS-1$
                "REPORT (default) reads and changes nothing. MERGE applies the decisions to the " //$NON-NLS-1$
                    + "project - IRREVERSIBLE. The environment validates first and stops before " //$NON-NLS-1$
                    + "writing when it raises a blocking problem; merged says what actually " //$NON-NLS-1$
                    + "happened, not what was asked for. MERGE_IGNORING_PROBLEMS proceeds past " //$NON-NLS-1$
                    + "those problems; it is a " //$NON-NLS-1$
                    + "separate value and not a flag, because overriding the environment's own " //$NON-NLS-1$
                    + "objection should not share a word with ordinary merging. A merge needs " //$NON-NLS-1$
                    + "decisions: without them there is nothing to apply. UPDATE_UNCHANGED is the " //$NON-NLS-1$
                    + "exception - it takes the delivery whole and needs no decisions, and is " //$NON-NLS-1$
                    + "refused unless the comparison is three-sided, finished, and found nothing " //$NON-NLS-1$
                    + "changed here, on both sides, or unattributed. The tool checks that itself " //$NON-NLS-1$
                    + "rather than trusting the caller, and names the counts when it refuses.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    /**
     * Reads the decisions a caller passed.
     * <p>
     * Malformed input is refused with a reason rather than silently treated as no decisions: a
     * caller who wrote decisions and got a comparison back without them would believe they had been
     * recorded.
     * </p>
     *
     * @param json the argument as written; may be <code>null</code>.
     * @return the decisions, empty when none were given
     * @throws IllegalArgumentException when the argument is there but unreadable
     */
    /**
     * Reads the objects a caller wants compared, when they want less than everything.
     *
     * @param params the call.
     * @return the names, empty when the whole configuration is meant
     */
    private static List<String> readScope(Map<String, String> params)
    {
        List<String> names = new ArrayList<>();
        String scope = JsonUtils.extractStringArgument(params, "scope"); //$NON-NLS-1$
        if (scope == null || scope.trim().isEmpty())
        {
            return names;
        }
        for (String name : scope.split(",")) //$NON-NLS-1$
        {
            if (!name.trim().isEmpty())
            {
                names.add(name.trim());
            }
        }
        return names;
    }

    private static List<BmComparisonHelper.Decision> readDecisions(String json)
    {
        List<BmComparisonHelper.Decision> decisions = new ArrayList<>();
        if (json == null || json.trim().isEmpty())
        {
            return decisions;
        }
        try
        {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(json);
            if (!parsed.isJsonArray())
            {
                throw new IllegalArgumentException(
                    "decisions must be a JSON array of {object, rule}"); //$NON-NLS-1$
            }
            for (com.google.gson.JsonElement element : parsed.getAsJsonArray())
            {
                if (!element.isJsonObject())
                {
                    throw new IllegalArgumentException(
                        "every decision must be an object with object and rule"); //$NON-NLS-1$
                }
                com.google.gson.JsonObject entry = element.getAsJsonObject();
                com.google.gson.JsonElement object = entry.get("object"); //$NON-NLS-1$
                com.google.gson.JsonElement select = entry.get("select"); //$NON-NLS-1$
                com.google.gson.JsonElement rule = entry.get("rule"); //$NON-NLS-1$
                if (rule == null || object == null && select == null)
                {
                    throw new IllegalArgumentException("every decision needs a rule and either " //$NON-NLS-1$
                        + "an object or a select: " + entry); //$NON-NLS-1$
                }
                if (object != null && select != null)
                {
                    // Refused rather than resolved by precedence. One entry naming both an object
                    // and a class is a caller who meant one of them, and guessing which would
                    // apply a rule to a set they did not ask for.
                    throw new IllegalArgumentException("a decision names an object or a select, " //$NON-NLS-1$
                        + "not both: " + entry); //$NON-NLS-1$
                }
                decisions.add(new BmComparisonHelper.Decision(
                    object == null ? null : object.getAsString(),
                    select == null ? null : select.getAsString(), rule.getAsString()));
            }
            return decisions;
        }
        catch (com.google.gson.JsonParseException | IllegalStateException malformed)
        {
            throw new IllegalArgumentException("decisions is not readable JSON: " //$NON-NLS-1$
                + malformed.getMessage());
        }
    }

    /**
     * Reads what the caller asked to happen.
     * <p>
     * An unrecognised value is refused rather than read as REPORT. Defaulting would be the safe
     * direction and still the wrong one: somebody who wrote MERGE and mistyped it would be told
     * their configuration is unchanged only by reading the answer closely, and would try again
     * with the same word.
     * </p>
     *
     * @param argument the value as written; may be <code>null</code>.
     * @return the intent, REPORT when nothing was asked for
     * @throws IllegalArgumentException when the value is not one of the three
     */
    private static BmComparisonHelper.Intent readIntent(String argument)
    {
        if (argument == null || argument.trim().isEmpty())
        {
            return BmComparisonHelper.Intent.REPORT;
        }
        for (BmComparisonHelper.Intent intent : BmComparisonHelper.Intent.values())
        {
            if (intent.name().equalsIgnoreCase(argument.trim()))
            {
                return intent;
            }
        }
        throw new IllegalArgumentException(argument + " is not an intent. Use REPORT, MERGE, " //$NON-NLS-1$
            + "MERGE_IGNORING_PROBLEMS or UPDATE_UNCHANGED."); //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String otherPath = JsonUtils.extractStringArgument(params, "otherPath"); //$NON-NLS-1$
        String ancestorPath = JsonUtils.extractStringArgument(params, "ancestorPath"); //$NON-NLS-1$

        String decisionsJson = JsonUtils.extractStringArgument(params, "decisions"); //$NON-NLS-1$
        String decisionsPath = JsonUtils.extractStringArgument(params, "decisionsPath"); //$NON-NLS-1$
        String decisionsFrom = JsonUtils.extractStringArgument(params, "decisionsFrom"); //$NON-NLS-1$
        List<BmComparisonHelper.Decision> decisions;
        try
        {
            decisions = readDecisions(decisionsJson);
        }
        catch (IllegalArgumentException malformed)
        {
            return ToolResult.error(malformed.getMessage()).toJson();
        }

        String intentArgument = JsonUtils.extractStringArgument(params, "intent"); //$NON-NLS-1$
        BmComparisonHelper.Intent intent;
        try
        {
            intent = readIntent(intentArgument);
        }
        catch (IllegalArgumentException unknown)
        {
            return ToolResult.error(unknown.getMessage()).toJson();
        }
        if (intent != BmComparisonHelper.Intent.REPORT)
        {
            // A merge writes into the configuration, so a preset that forbids writing forbids this
            // - checked before anything is compared, not after the work is done.
            String forbidden = ru.aiedt.mcp.server.support.ToolGate
                .gateIfPresetDisabled("write_module_source"); //$NON-NLS-1$
            if (forbidden != null)
            {
                return forbidden;
            }
        }

        boolean ignoreOriginMismatch = JsonUtils.extractBooleanArgument(params,
            "ignoreOriginMismatch", false); //$NON-NLS-1$
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();
        page.changedBy = JsonUtils.extractStringArgument(params, "changedBy"); //$NON-NLS-1$
        page.type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        page.oneSided = JsonUtils.extractBooleanArgumentNullable(params, "oneSided"); //$NON-NLS-1$
        page.mustBeMergedOnly =
            JsonUtils.extractBooleanArgument(params, "mustBeMergedOnly", false); //$NON-NLS-1$
        page.offset = JsonUtils.extractIntArgument(params, "offset", 0); //$NON-NLS-1$
        page.limit = JsonUtils.extractIntArgument(params, "limit", 0); //$NON-NLS-1$
        boolean closeSession = JsonUtils.extractBooleanArgument(params, "closeSession", false); //$NON-NLS-1$
        BmComparisonHelper.Outcome outcome = BmComparisonHelper.compare(projectName, otherPath,
            ancestorPath, decisions, decisionsPath, decisionsFrom, intent, ignoreOriginMismatch,
            page, closeSession, readScope(params));
        if (outcome.cannotTell != null)
        {
            return ToolResult.error(outcome.cannotTell)
                .put("threeWay", outcome.threeWay) //$NON-NLS-1$
            // A reporting comparison stays open under this key, so the next page costs nothing;
            // reused says whether this answer came from an open one or from a fresh comparison.
            // Empty after a merge: the merge writes into the project, so the comparison no longer
            // describes it, and holding it open leaves a transaction on the comparison store that
            // the environment waits for when somebody closes EDT.
            .put("sessionKey", outcome.sessionKey) //$NON-NLS-1$
            .put("sessionReused", outcome.sessionReused) //$NON-NLS-1$
                .put("status", outcome.status) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("threeWay", outcome.threeWay) //$NON-NLS-1$
            .put("status", outcome.status) //$NON-NLS-1$
            // What the sides turned out to be, read from the configurations themselves. A caller
            // who mistyped a path sees it here rather than in an inverted attribution.
            .put("otherIs", outcome.otherIs) //$NON-NLS-1$
            .put("ancestorIs", outcome.ancestorIs) //$NON-NLS-1$
            .put("projectDescendsFrom", outcome.projectDescendsFrom) //$NON-NLS-1$
            .put("originMismatches", outcome.originMismatches) //$NON-NLS-1$
            .put("nodes", outcome.nodes) //$NON-NLS-1$
            .put("differing", outcome.differing) //$NON-NLS-1$
            .put("oneSided", outcome.oneSided) //$NON-NLS-1$
            // Objects, not nodes. One changed object answers to many differing child nodes - its
            // properties, its module sections, its support settings - so these will never add up
            // to differing, and they are named apart from it so nobody expects them to.
            .put("objectsChanged", outcome.objectsChanged) //$NON-NLS-1$
            .put("objectsChangedByUs", outcome.objectsChangedByUs) //$NON-NLS-1$
            .put("objectsChangedByVendor", outcome.objectsChangedByVendor) //$NON-NLS-1$
            // The queue somebody has to work through by hand: both sides touched these.
            .put("objectsChangedByBoth", outcome.objectsChangedByBoth) //$NON-NLS-1$
            // Always equal to objectsChanged on a two-sided comparison: without the ancestor there
            // is nothing to attribute against, and a guess would be worse than the admission.
            .put("objectsChangedUnattributed", outcome.objectsChangedUnattributed) //$NON-NLS-1$
            // Named, not just counted: an update on support is decided object by object, and a
            // number tells nobody which ones to look at.
            .put("changed", outcome.changed) //$NON-NLS-1$
            // How many matched the filter across every page, where this page starts, and whether
            // more matched than fit. A short list is also what the last page looks like, so the
            // difference is stated rather than left to be inferred.
            .put("changedMatching", outcome.changedMatching) //$NON-NLS-1$
            .put("changedOffset", outcome.changedOffset) //$NON-NLS-1$
            .put("moreChanged", outcome.moreChanged) //$NON-NLS-1$
            .put("blockingProblems", outcome.blockingProblems) //$NON-NLS-1$
            .put("problems", outcome.problems) //$NON-NLS-1$
            // Present only when the list is empty for a reason other than there being no problems.
            .put("problemsNote", outcome.problemsNote) //$NON-NLS-1$
            .put("decided", outcome.decided) //$NON-NLS-1$
            // Decisions the environment would not take, counted apart from the ones it did. The
            // call that records a decision returns a boolean, and counting calls instead of
            // answers would report a merge as decided when nothing was decided.
            .put("decisionsRefused", outcome.decisionsRefused) //$NON-NLS-1$
            // A decision about a class reports what took, not how many calls were made. The two
            // differ whenever the environment refuses a rule for a node, and the difference is
            // the whole reason a mass assignment can be trusted at all.
            .put("massDecided", outcome.massDecided) //$NON-NLS-1$
            .put("massRefused", outcome.massRefused) //$NON-NLS-1$
            .put("massMismatched", outcome.massMismatched) //$NON-NLS-1$
            // What was asked for, what the environment did not recognise, and what it added on
            // its own. A narrowed comparison that quietly compared nothing is the failure these
            // three answer between them.
            .put("scopeRequested", outcome.scopeRequested) //$NON-NLS-1$
            .put("scopeUnrecognised", outcome.scopeUnrecognised) //$NON-NLS-1$
            .put("scopeExtendedBy", outcome.scopeExtendedBy) //$NON-NLS-1$
            // What the merge did to the vendor support settings, counted either side of it.
            // Not a promise that they were protected - that was tried and does not work.
            .put("supportModesBefore", outcome.supportModesBefore) //$NON-NLS-1$
            .put("supportModesAfter", outcome.supportModesAfter) //$NON-NLS-1$
            .put("supportModesChanged", outcome.supportModesChanged) //$NON-NLS-1$
            // Which objects lost the mode they had, not just how many. These are the ones somebody
            // deliberately unlocked and the update locked again, and naming them is the difference
            // between knowing damage happened and knowing which work is now unprotected.
            .put("supportModesLost", outcome.supportModesLost) //$NON-NLS-1$
            .put("supportModesLostCount", outcome.supportModesLostCount) //$NON-NLS-1$
            // Objects the delivery brought take the update rules' default rather than a lost mode,
            // and objects the update removed can take no mode at all. Both are ordinary, and
            // counting them apart keeps them out of the damage figure.
            .put("supportModesArrived", outcome.supportModesArrived) //$NON-NLS-1$
            .put("supportModesGone", outcome.supportModesGone) //$NON-NLS-1$
            // The route back. Written before the merge because a snapshot that lives only in the
            // call dies with it, and the modes it recorded are then gone for good.
            .put("supportSnapshotFile", outcome.supportSnapshotFile) //$NON-NLS-1$
            .put("supportSnapshotNote", outcome.supportSnapshotNote) //$NON-NLS-1$
            // Said plainly, because a merge that quietly re-locks objects somebody deliberately
            // unlocked is the worst outcome this tool can produce, and it cannot be prevented.
            .put("supportSettingsNote", supportNote(outcome)) //$NON-NLS-1$
            .put("decisionsWrittenTo", outcome.decisionsWrittenTo) //$NON-NLS-1$
            .put("decisionsNote", outcome.decisionsNote) //$NON-NLS-1$
            // Present only when decisions were restored from a file, so a caller who named
            // one can tell it was read from a run where the argument was quietly ignored.
            .put("decisionsReadFrom", outcome.decisionsReadFrom) //$NON-NLS-1$
            .put("decisionsRestored", outcome.decisionsRestored) //$NON-NLS-1$
            .put("merged", outcome.merged) //$NON-NLS-1$
            .put("mergeStatus", outcome.mergeStatus) //$NON-NLS-1$
            // Present whenever a merge was asked for and did not happen. Silence here would leave
            // the caller to infer from merged:false, which is also what a failed merge says.
            .put("mergeRefused", outcome.mergeRefused) //$NON-NLS-1$
            // What the project looks like after being written to. A merge that succeeds and
            // leaves the configuration broken is ordinary, not exceptional.
            .put("errorsAfterMerge", outcome.errorsAfterMerge) //$NON-NLS-1$
            .put("revalidatedAfterMerge", outcome.revalidatedAfterMerge) //$NON-NLS-1$
            .toJson();
    }
}
