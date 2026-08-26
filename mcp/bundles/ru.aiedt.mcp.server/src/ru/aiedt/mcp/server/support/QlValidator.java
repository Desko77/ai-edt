/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

import ru.aiedt.mcp.server.Activator;

/**
 * Lightweight wrapper around the Xtext {@code QlDcs} language used by
 * {@code dcs_workshop} to auto-validate query text and DCS expressions before
 * a write transaction commits. Mirrors the validation core of
 * {@code QueryValidator} but exposes a structured result instead of JSON.
 * <p>
 * Two entry points:
 * <ul>
 *   <li>{@link #validateQueryText(IProject, String, boolean)} - QL or DCS
 *       query (e.g. inside an {@code add_dataset queryText=...}).</li>
 *   <li>{@link #validateExpression(IProject, String)} - DCS expression
 *       (e.g. inside an {@code add_calculated_field expression=...}). The DCS
 *       expression grammar is a subset of the same Xtext resource - it
 *       parses fine in DCS mode.</li>
 * </ul>
 * Best-effort: when the QL plugin is missing or the resource fails to load,
 * the helper returns {@link ValidationResult#unavailable(String)} so callers
 * can decide whether to skip validation or block.
 */
public final class QlValidator
{
    /**
     * The code the platform gives a name that resolves to no field.
     * <p>
     * A code, not a message: the message is localised and the code is not.
     * </p>
     */
    private static final String FIELD_NOT_FOUND = "Field not found"; //$NON-NLS-1$

    private static final String V8_CONFIGURATION_NATURE = "com._1c.g5.v8.dt.core.V8ConfigurationNature"; //$NON-NLS-1$

    private static final String V8_EXTENSION_NATURE = "com._1c.g5.v8.dt.core.V8ExtensionNature"; //$NON-NLS-1$

    private static final String QLDCS_LOOKUP_URI =
        "/nopr/dcs_workshop_validate.qldcs"; //$NON-NLS-1$

    private static final String CLS_QL_DCS_RESOURCE =
        "com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource"; //$NON-NLS-1$

    private QlValidator()
    {
        // utility class
    }

    /**
     * Severity-tagged validation issue.
     */
    public static final class QlIssue
    {
        public final String severity; // ERROR / WARNING / INFO
        public final String message;
        public final int line;
        public final int column;

        /**
         * What kind of diagnostic this is, in a form that does not change with the language.
         * <p>
         * The message does change: the platform localises it, so "field not found" arrives in
         * Russian on a Russian EDT and the same diagnostic reads differently elsewhere. Anything
         * deciding what to do about a diagnostic has to read this instead - reading the message is
         * how a check silently stops working on somebody else's install.
         * </p>
         */
        public final String code;

        public QlIssue(String severity, String message, int line, int column)
        {
            this(severity, message, line, column, null);
        }

        public QlIssue(String severity, String message, int line, int column, String code)
        {
            this.severity = severity;
            this.message = message;
            this.line = line;
            this.column = column;
            this.code = code;
        }

        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", severity); //$NON-NLS-1$
            m.put("message", message); //$NON-NLS-1$
            m.put("line", line); //$NON-NLS-1$
            m.put("column", column); //$NON-NLS-1$
            if (code != null)
            {
                m.put("code", code); //$NON-NLS-1$
            }
            return m;
        }
    }

    /**
     * Outcome of a validation pass.
     */
    public static final class ValidationResult
    {
        public final boolean available; // false when QlDcs language not present

        /**
         * Whether what is reported here can be acted on.
         * <p>
         * Distinct from {@link #available}, and the distinction is the point. Unavailable means
         * this EDT cannot check queries at all, which callers have always tolerated by writing
         * anyway. Unconfirmed means a check was possible and its answer is not settled - the model
         * that would confirm it could not be asked, or the semantic checker that finds bad
         * references was absent. Sharing one flag between those made a refusal indistinguishable
         * from a shrug, and a write went through on both.
         * </p>
         */
        public final boolean unconfirmed;

        /**
         * Whether the parser objected, which no second model can overturn.
         */
        public boolean parserObjected;
        public final List<QlIssue> issues;
        public final int errorCount;
        public final int warningCount;
        public final String unavailableReason;

        /**
         * The project the query was actually resolved against, when it is not the one asked
         * about.
         * <p>
         * An extension is validated in the configuration it extends. Saying so is the difference
         * between an answer and a silent redirection: a caller who sees a query pass has to be
         * able to know which model said so.
         * </p>
         */
        public String resolvedInProject;

        private ValidationResult(boolean available, List<QlIssue> issues, String reason)
        {
            this(available, issues, reason, false);
        }

        private ValidationResult(boolean available, List<QlIssue> issues, String reason,
            boolean unconfirmed)
        {
            this.unconfirmed = unconfirmed;
            this.available = available;
            this.issues = issues != null ? issues : new ArrayList<>();
            this.unavailableReason = reason;
            int e = 0;
            int w = 0;
            for (QlIssue i : this.issues)
            {
                if ("ERROR".equals(i.severity)) //$NON-NLS-1$
                {
                    e++;
                }
                else if ("WARNING".equals(i.severity)) //$NON-NLS-1$
                {
                    w++;
                }
            }
            this.errorCount = e;
            this.warningCount = w;
        }

        public boolean hasErrors()
        {
            return errorCount > 0;
        }

        public static ValidationResult ok()
        {
            return new ValidationResult(true, new ArrayList<>(), null);
        }

        public static ValidationResult of(List<QlIssue> issues)
        {
            return new ValidationResult(true, issues, null);
        }

        /**
         * A check whose answer cannot be acted on, with nothing to report.
         *
         * @param reason what stopped it from being settled.
         * @return the result.
         */
        public static ValidationResult unconfirmed(String reason)
        {
            return new ValidationResult(false, new ArrayList<>(), reason, true);
        }

        /**
         * A check that found something and still cannot be acted on.
         *
         * @param issues what it did find, which is worth reporting even so.
         * @param reason what stopped the rest from being settled.
         * @return the result.
         */
        public static ValidationResult unconfirmedWith(List<QlIssue> issues, String reason)
        {
            return new ValidationResult(true, issues, reason, true);
        }

        public static ValidationResult unavailable(String reason)
        {
            return new ValidationResult(false, new ArrayList<>(), reason);
        }

        /**
         * Renders the validation outcome as a tag payload suitable for
         * {@code BmDcsHelper.Result.tags.put("queryValidation", ...)}.
         */
        public Map<String, Object> toTagData()
        {
            Map<String, Object> data = new LinkedHashMap<>();
            List<Map<String, Object>> issuesList = new ArrayList<>();
            for (QlIssue i : issues)
            {
                issuesList.add(i.toMap());
            }
            data.put("issues", issuesList); //$NON-NLS-1$
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("errors", errorCount); //$NON-NLS-1$
            stats.put("warnings", warningCount); //$NON-NLS-1$
            stats.put("available", available); //$NON-NLS-1$
            stats.put("unconfirmed", unconfirmed); //$NON-NLS-1$
            if (unavailableReason != null)
            {
                // Reported whenever there is one. It used to be withheld unless the whole check
                // was unavailable, which hid the reason exactly where it matters most: a partial
                // answer that looks complete.
                stats.put("reason", unavailableReason); //$NON-NLS-1$
            }
            data.put("statistics", stats); //$NON-NLS-1$
            return data;
        }
    }

    /**
     * Validates a query text in the project's QL/DCS context.
     *
     * @param project EDT project providing the metadata context
     * @param queryText the BSL query text to validate; {@code null} or empty
     *            short-circuits to {@link ValidationResult#ok()}
     * @param dcsMode use DCS-specific syntax extensions
     */
    public static ValidationResult validateQueryText(IProject project, String queryText,
        boolean dcsMode)
    {
        if (queryText == null || queryText.trim().isEmpty())
        {
            return ValidationResult.ok();
        }
        if (project == null || !project.exists() || !project.isOpen())
        {
            return ValidationResult.unavailable("project not available"); //$NON-NLS-1$
        }
        return settledAnswer(project, queryText, dcsMode, false);
    }

    /**
     * Validates a DCS expression (e.g. for add_calculated_field). DCS
     * expression grammar is parsed by the same QlDcs resource in DCS mode.
     * <p>
     * Implementation: wraps the expression as a SELECT list element without a
     * FROM clause. The QL parser accepts this in DCS mode because expressions
     * are evaluated against the schema's data sets at composition time, not
     * via an explicit table reference. Filtering only retains issues that
     * mention the user-provided expression (so the wrapper itself does not
     * produce false-positive errors).
     */
    public static ValidationResult validateExpression(IProject project, String expression)
    {
        if (expression == null || expression.trim().isEmpty())
        {
            return ValidationResult.ok();
        }
        if (project == null || !project.exists() || !project.isOpen())
        {
            return ValidationResult.unavailable("project not available"); //$NON-NLS-1$
        }
        // Bare-expression entry: QL DCS mode accepts a SELECT list without FROM.
        // We surround with parentheses + alias so even malformed expressions
        // are syntactically wrapped.
        String wrapped = "ВЫБРАТЬ (" + expression + ") КАК __DcsExpressionProbe"; //$NON-NLS-1$
        ValidationResult raw = settledAnswer(project, wrapped, true, true);
        if (!raw.available || raw.unconfirmed)
        {
            return raw;
        }
        // Filter out wrapper-induced errors: only retain issues whose message
        // text mentions "FROM" / "ИЗ" / "alias" only - and at least one issue
        // attributable to the user expression (heuristic).
        java.util.List<QlIssue> filtered = new java.util.ArrayList<>();
        for (QlIssue i : raw.issues)
        {
            String msg = i.message != null ? i.message.toLowerCase() : ""; //$NON-NLS-1$
            // Skip wrapper-only issues (FROM clause expected, etc.)
            if (msg.contains("from") || msg.contains(" из ") //$NON-NLS-1$ //$NON-NLS-2$
                || msg.contains("__dcsexpressionprobe")) //$NON-NLS-1$
            {
                continue;
            }
            filtered.add(whatThisCannotJudge(i));
        }
        ValidationResult kept = ValidationResult.of(filtered);
        kept.parserObjected = raw.parserObjected;
        return kept;
    }


    /**
     * The configuration an extension extends, when there is a usable one to ask as well.
     * <p>
     * <b>Neither model can answer every query an extension can ask.</b> The extension's own view
     * lacks the inherited fields of a borrowed object; the configuration's view lacks the objects
     * the extension declares itself. Choosing between them without looking at the query traded one
     * class of false error for another - measured on a stand, an extension's own information
     * register came back as "table not found" from the configuration that had never held it. So
     * the caller asks both and keeps the answer that accounts for the query.
     * </p>
     *
     * @param project the project the caller named.
     * @return the parent configuration, or <code>null</code> when there is none worth asking.
     */
    public static IProject alsoAsk(IProject project)
    {
        Companion companion = companionOf(project);
        return companion.kind == Companion.Kind.READY_TO_ASK ? companion.project : null;
    }

    /**
     * What one pass of the check produced: an answer, or word that a model moved under it.
     */
    private static final class Pass
    {
        /** The answer, or <code>null</code> when the pass is void. */
        final ValidationResult answer;

        private Pass(ValidationResult answer)
        {
            this.answer = answer;
        }

        static Pass of(ValidationResult answer)
        {
            return new Pass(answer);
        }

        static Pass voided()
        {
            return new Pass(null);
        }
    }

    /**
     * Rebuilds a result with the diagnostics this check could not have made demoted.
     * <p>
     * Rebuilt rather than edited, because a result counts its errors when it is made - which is
     * the number everything downstream decides on.
     * </p>
     *
     * @param result the result as reported.
     * @return the same result, or a new one with the unjudgeable demoted.
     */
    private static ValidationResult withTheUnjudgeableDemoted(ValidationResult result)
    {
        if (!result.available || result.issues.isEmpty())
        {
            return result;
        }
        List<QlIssue> kept = new ArrayList<>();
        boolean changed = false;
        for (QlIssue issue : result.issues)
        {
            QlIssue demoted = whatThisCannotJudge(issue);
            changed = changed || demoted != issue;
            kept.add(demoted);
        }
        if (!changed)
        {
            return result;
        }
        ValidationResult rebuilt = result.unconfirmed
            ? ValidationResult.unconfirmedWith(kept, result.unavailableReason)
            : ValidationResult.of(kept);
        rebuilt.parserObjected = result.parserObjected;
        rebuilt.resolvedInProject = result.resolvedInProject;
        return rebuilt;
    }

    /**
     * Demotes a diagnostic this check was never in a position to make.
     * <p>
     * An expression is checked by wrapping it in a query with no FROM clause, because there is no
     * dataset to point one at. So no field in it can resolve, and the checker duly reports every
     * one of them as missing - which meant a calculated field of {@code Amount * 2} was refused,
     * and so was every other expression naming a field, which is nearly all of them.
     * </p>
     * <p>
     * The diagnostic is demoted rather than dropped, so it is still there for anything that reads
     * the issues; it just stops being a reason to refuse the write. Measured, and worth saying
     * plainly: the schema workshop attaches its validation data only when it refuses, so on a call
     * that now goes through, nobody sees this warning. Surfacing it would mean reporting
     * validation data on success too, which changes the shape of every successful answer - a
     * larger change than this one, and not made here.
     * </p>
     * <p>
     * Syntax errors and names that are not fields - a function that does not exist, say - are
     * untouched, because those verdicts stand without a schema.
     * </p>
     * <p>
     * Recognised by code rather than by message. The message is localised - this one reads
     * "Поле ... не найдено" here - and a check written against the words silently stops working
     * on an install in another language.
     * </p>
     * <p>
     * <b>Known and left as it is.</b> An expression containing a nested SELECT with a FROM of its
     * own gives its fields a scope that IS resolvable, and this demotes those too, so a wrong name
     * inside such a subquery becomes a warning and gets written. Telling the two apart needs to
     * know whether a diagnostic sits inside that nested scope, and the only cheap way to guess is
     * to look for the FROM keyword in the text - which misfires on the word inside a string
     * literal, and misfires toward refusing valid work, the worse of the two directions. The
     * shape left open is a rare construct reported one level too gently; the shape avoided is
     * every ordinary expression being refused.
     * </p>
     *
     * @param issue the diagnostic as reported.
     * @return the same diagnostic, demoted if this check could not have judged it.
     */
    private static QlIssue whatThisCannotJudge(QlIssue issue)
    {
        if (FIELD_NOT_FOUND.equals(issue.code) && "ERROR".equals(issue.severity)) //$NON-NLS-1$
        {
            return new QlIssue("WARNING", issue.message, issue.line, issue.column, issue.code); //$NON-NLS-1$
        }
        return issue;
    }

    /**
     * Reads what a project is from its nature, when the project layer cannot say.
     * <p>
     * A configuration holds its own objects: nothing else confirms or overturns what its model
     * reports, so there is no companion to want. An extension has one by definition, and if this
     * is all we know, we know we cannot reach it.
     * </p>
     *
     * @param project the project; never <code>null</code> when called
     * @return the verdict its nature supports, never <code>null</code>
     */
    private static Companion fromNatureAlone(IProject project)
    {
        try
        {
            if (project.hasNature(V8_EXTENSION_NATURE))
            {
                return Companion.cannotTell("This project extends another, and which one could" //$NON-NLS-1$
                    + " not be established."); //$NON-NLS-1$
            }
            if (project.hasNature(V8_CONFIGURATION_NATURE))
            {
                return Companion.none();
            }
        }
        catch (CoreException e)
        {
            Activator.logWarning("Could not read a project's nature: " + e.getMessage()); //$NON-NLS-1$
        }
        return Companion.cannotTell("What this project is could not be established."); //$NON-NLS-1$
    }

    /**
     * What other model, if any, can be asked about a query alongside the named project.
     * <p>
     * <b>Absent and unusable are different answers.</b> A configuration project has no other model
     * and needs none - its errors are settled. An extension whose parent is closed, missing, or
     * unknowable has one and cannot reach it, and its errors are settled by nothing: a query naming
     * an inherited field fails in the extension and passes in the parent. Answering the same way to
     * both is how an unconfirmed error gets reported as a verdict.
     * </p>
     *
     * @param project the project the caller named; may be <code>null</code>
     * @return the verdict, never <code>null</code>
     */
    public static Companion companionOf(IProject project)
    {
        if (project == null)
        {
            return Companion.none();
        }
        if (!project.isOpen())
        {
            return Companion.unusable("The project is not open."); //$NON-NLS-1$
        }
        Activator activator = Activator.getDefault();
        IV8ProjectManager projectManager = activator == null ? null : activator.getV8ProjectManager();
        if (projectManager == null)
        {
            // Losing that service need not blind us. A project carries its own nature, and a
            // configuration's nature already settles the question: it holds its own objects and
            // wants no second model. Refusing every failing query in every configuration because
            // one service is away would turn a narrow gap into an outage.
            return fromNatureAlone(project);
        }
        try
        {
            IV8Project v8Project = projectManager.getProject(project);
            if (v8Project == null)
            {
                // Not the same as "not an extension". During start-up the project layer answers
                // null for projects it has not taken in yet, and reading that as a configuration
                // is how an extension's unconfirmed errors get reported as a verdict.
                return fromNatureAlone(project);
            }
            if (!(v8Project instanceof IExtensionProject))
            {
                return Companion.none();
            }
            IProject parent = ((IExtensionProject)v8Project).getParentProject();
            if (parent == null)
            {
                return Companion.unusable("This extension names no configuration to extend."); //$NON-NLS-1$
            }
            if (parent.equals(project))
            {
                return Companion.unusable("This extension names itself as the configuration it" //$NON-NLS-1$
                    + " extends."); //$NON-NLS-1$
            }
            if (!parent.exists())
            {
                return Companion.unusable("The configuration this extension extends is not in the" //$NON-NLS-1$
                    + " workspace: " + parent.getName()); //$NON-NLS-1$
            }
            if (!parent.isOpen())
            {
                return Companion.unusable("The configuration this extension extends is closed: " //$NON-NLS-1$
                    + parent.getName());
            }
            return Companion.readyToAsk(parent);
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not establish what this project extends: " + e.getMessage()); //$NON-NLS-1$
            return fromNatureAlone(project);
        }
    }

    /**
     * The other model that may hold the objects a query names, and whether it can be asked.
     */
    public static final class Companion
    {
        /** Which of the three situations this is. */
        public enum Kind
        {
            /** There is no other model, and none is needed. */
            NONE,

            /** There is one and it can be asked. */
            READY_TO_ASK,

            /** There is one and it cannot be asked. */
            UNUSABLE,

            /** Whether there is one could not be established. */
            CANNOT_TELL
        }

        /** Which situation this is. */
        public final Kind kind;

        /** The model to ask, set only for {@link Kind#READY_TO_ASK}. */
        public final IProject project;

        /** Why it cannot be asked, set for {@link Kind#UNUSABLE} and {@link Kind#CANNOT_TELL}. */
        public final String why;

        private Companion(Kind kind, IProject project, String why)
        {
            this.kind = kind;
            this.project = project;
            this.why = why;
        }

        static Companion none()
        {
            return new Companion(Kind.NONE, null, null);
        }

        static Companion readyToAsk(IProject project)
        {
            return new Companion(Kind.READY_TO_ASK, project, null);
        }

        static Companion unusable(String why)
        {
            return new Companion(Kind.UNUSABLE, null, why);
        }

        static Companion cannotTell(String why)
        {
            return new Companion(Kind.CANNOT_TELL, null, why);
        }
    }

    /**
     * Asks the project named and, when it is an extension, the configuration it extends, and
     * answers with whichever accounted for the query.
     * <p>
     * The extension is asked first because a query written in one usually concerns it. The
     * configuration is asked only when the extension raised errors, so the ordinary case pays for
     * one validation and not two, and its answer is taken only when it is clean.
     * </p>
     * <p>
     * Not ranked by diagnostic count: a model that cannot find the table never reaches the fields,
     * so it can answer with one complaint while the model that did find it reports a real error per
     * bad field. Counting would prefer the one that resolved nothing. Clean or not clean cannot go
     * wrong that way, and when neither is clean the answer kept is the one from the project the
     * caller named.
     * </p>
     *
     * @param asked the project the caller named.
     * @param queryText the query.
     * @param dcsMode whether to parse it as a data composition query.
     * @return the better of the two answers, naming the model that gave it when that was not the
     *         project the caller named.
     */
    private static ValidationResult settledAnswer(IProject asked, String queryText,
        boolean dcsMode, boolean bareExpression)
    {
        // Everything validate_query does at its own door, done here too: this answer gates writes,
        // so an answer taken off a model in motion is worse here than there.
        //
        // The watch goes on before readiness is read, and the pass is repeated once if the model
        // moved under it. Reading readiness twice would not do: a model can go ready, building,
        // ready again while one check runs, and both readings say ready.
        try (ProjectStateGuard.ModelWatch watch = ProjectStateGuard.watchModel(asked))
        {
            if (watch == null)
            {
                Pass probe = onePass(asked, queryText, dcsMode, bareExpression);
                if (probe.answer != null && !probe.answer.available && !probe.answer.unconfirmed)
                {
                    // No query support in this EDT at all. That verdict does not depend on any
                    // model holding still, and callers have always been allowed to write through it.
                    return probe.answer;
                }
                return ValidationResult.unconfirmed(
                    "Cannot tell whether this project's model is holding still, so this query was" //$NON-NLS-1$
                        + " not checked against it. Try again in a moment."); //$NON-NLS-1$
            }
            for (int pass = 0; pass < 2; pass++)
            {
                watch.reset();
                Pass attempt = onePass(asked, queryText, dcsMode, bareExpression);
                if (attempt.answer == null)
                {
                    // A model moved under this pass. Nothing it produced is worth reporting.
                    continue;
                }
                if (!attempt.answer.available && !attempt.answer.unconfirmed)
                {
                    return attempt.answer;
                }
                if (!watch.moved())
                {
                    return attempt.answer;
                }
            }
            return ValidationResult.unconfirmed("The model kept changing while this query was" //$NON-NLS-1$
                + " being checked. Wait for the build to settle and try again."); //$NON-NLS-1$
        }
    }

    /**
     * One pass of the check: the named project's model, and the other one when its answer would be
     * used.
     *
     * @param asked the project the caller named.
     * @param queryText the query.
     * @param dcsMode whether to parse it as a data composition query.
     * @return what the check found, or why it could not settle.
     */
    private static Pass onePass(IProject asked, String queryText, boolean dcsMode,
        boolean bareExpression)
    {
        ValidationResult here = runValidation(asked, queryText, dcsMode);
        if (bareExpression)
        {
            // Demoted HERE, before anything counts the errors. Left until after the answer was
            // settled, a handful of unjudgeable field diagnostics counted as errors, which sent
            // the check to the other model to have them confirmed - and when that model could not
            // be reached, an ordinary expression came back refused as unconfirmed. Nothing was
            // ever going to confirm them: there is no FROM clause for a field to resolve against.
            here = withTheUnjudgeableDemoted(here);
        }
        if (!here.available)
        {
            // This EDT cannot check queries. Readiness has nothing to add to that, and asking about
            // it first would turn a build in progress into a block on an install where the policy
            // is to write anyway.
            return Pass.of(here);
        }
        String notReady = ProjectStateGuard.checkReadyOrError(asked);
        if (notReady != null)
        {
            // Checked after the answer, not before it, and it still governs: right after EDT
            // restarts this model reports missing tables that exist, and it can as easily report
            // nothing wrong with a query that is wrong.
            return Pass.of(ValidationResult.unconfirmed(notReady));
        }
        if (here.unconfirmed || here.errorCount == 0 || here.parserObjected)
        {
            // Parsing settled it: the other model would be parsing the same text.
            return Pass.of(here);
        }
        Companion companion = companionOf(asked);
        if (companion.kind == Companion.Kind.NONE)
        {
            return Pass.of(here);
        }
        if (companion.kind != Companion.Kind.READY_TO_ASK)
        {
            // There is a model that would confirm or overturn these errors and it cannot be
            // reached. Reporting them as findings would have a caller reject a query that the
            // other model would have passed, and this result gates writes.
            return Pass.of(ValidationResult.unconfirmed(
                "This query could not be confirmed against the model that holds the objects it" //$NON-NLS-1$
                    + " names. " + companion.why)); //$NON-NLS-1$
        }
        IProject alternative = companion.project;
        try (ProjectStateGuard.ModelWatch other = ProjectStateGuard.watchModel(alternative))
        {
            if (other == null)
            {
                return Pass.of(ValidationResult.unconfirmed(
                    "This query could not be confirmed against the model that holds the objects" //$NON-NLS-1$
                        + " it names. Cannot tell whether that model is holding still.")); //$NON-NLS-1$
            }
            String otherNotReady = ProjectStateGuard.checkReadyOrError(alternative);
            if (otherNotReady != null)
            {
                return Pass.of(ValidationResult.unconfirmed(
                    "This query could not be confirmed against the model that holds the objects" //$NON-NLS-1$
                        + " it names. " + otherNotReady)); //$NON-NLS-1$
            }
            ValidationResult above = runValidation(alternative, queryText, dcsMode);
            if (bareExpression)
            {
                above = withTheUnjudgeableDemoted(above);
            }
            if (other.moved())
            {
                // Not an answer, and not a refusal either: this pass is void and the outer loop
                // gets to try again. Refusing outright would spend the retry on a model that may
                // well have settled by the time a second pass ran.
                return Pass.voided();
            }
            if (!above.available || above.unconfirmed)
            {
                // It was there, it was ready, and asking it did not work. The errors found in the
                // named project stay unconfirmed all the same.
                return Pass.of(ValidationResult.unconfirmed(
                    "This query could not be confirmed against the model that holds the objects" //$NON-NLS-1$
                        + " it names. That model could not be asked.")); //$NON-NLS-1$
            }
            if (above.errorCount != 0)
            {
                return Pass.of(here);
            }
            above.resolvedInProject = alternative.getName();
            return Pass.of(above);
        }
    }

    /**
     * Names the kind of a resource diagnostic without reading its message.
     *
     * @param diagnostic the diagnostic; never <code>null</code> when called.
     * @return the class it arrived as, which says what went wrong in every language.
     */
    private static String kindOf(Resource.Diagnostic diagnostic)
    {
        return diagnostic.getClass().getSimpleName();
    }

    private static ValidationResult runValidation(IProject project, String queryText,
        boolean dcsMode)
    {
        XtextResource resource = null;
        // Held outside the try so a throw part-way can still report what was found before it,
        // and so a settled parser verdict survives whatever came apart after it.
        List<QlIssue> found = new ArrayList<>();
        boolean parserObjectedBeforeTheThrow = false;
        try
        {
            URI lookup = URI.createURI(QLDCS_LOOKUP_URI);
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(lookup);
            if (rsp == null)
            {
                return ValidationResult.unavailable("QlDcs language support not registered"); //$NON-NLS-1$
            }
            IResourceSetProvider rsProvider = rsp.get(IResourceSetProvider.class);
            if (rsProvider == null)
            {
                return ValidationResult.unavailable("IResourceSetProvider not available"); //$NON-NLS-1$
            }
            ResourceSet rs = rsProvider.get(project);
            URI uri = URI.createPlatformResourceURI("/" + project.getName() //$NON-NLS-1$
                + "/mcp_validate_" + System.currentTimeMillis() + ".qldcs", true); //$NON-NLS-1$ //$NON-NLS-2$
            resource = (XtextResource) rs.createResource(uri);
            // Configure DCS mode reflectively to avoid hard dependency on
            // QlDcsResource (the class is in an optional Import-Package).
            try
            {
                Class<?> qlResClass = Class.forName(CLS_QL_DCS_RESOURCE);
                if (qlResClass.isInstance(resource))
                {
                    qlResClass.getMethod("addOptions", String.class, Object.class) //$NON-NLS-1$
                        .invoke(resource, "DcsValidationModeOption", Boolean.valueOf(dcsMode)); //$NON-NLS-1$
                    qlResClass.getMethod("setPreComputeAnnounceAlias", boolean.class) //$NON-NLS-1$
                        .invoke(resource, Boolean.valueOf(dcsMode));
                }
            }
            catch (Exception ignored)
            {
                // Without the DCS option the resource still validates as plain QL
            }
            try (InputStream in = new ByteArrayInputStream(
                queryText.getBytes(StandardCharsets.UTF_8)))
            {
                resource.load(in, null);
            }
            List<QlIssue> issues = found;
            for (Resource.Diagnostic d : resource.getErrors())
            {
                issues.add(new QlIssue("ERROR", d.getMessage(), d.getLine(), d.getColumn(), //$NON-NLS-1$
                    kindOf(d)));
            }
            for (Resource.Diagnostic d : resource.getWarnings())
            {
                issues.add(new QlIssue("WARNING", d.getMessage(), d.getLine(), //$NON-NLS-1$
                    d.getColumn(), kindOf(d)));
            }
            IParseResult parsed = resource.getParseResult();
            boolean parserObjected = parsed != null && parsed.hasSyntaxErrors();
            parserObjectedBeforeTheThrow = parserObjected;

            IResourceValidator validator = rsp.get(IResourceValidator.class);
            if (validator == null)
            {
                if (parserObjected)
                {
                    // The text does not parse. No checker of names was going to change that, so
                    // this verdict is settled and saying otherwise would send a caller back to
                    // retry a query that will fail the same way every time.
                    ValidationResult settledByTheParser = ValidationResult.of(issues);
                    settledByTheParser.parserObjected = true;
                    return settledByTheParser;
                }
                // Parsing found what parsing finds. Whether the query names objects that exist was
                // never asked, and answering "no problems" would say it was.
                return ValidationResult.unconfirmedWith(issues,
                    "The checker that resolves names against metadata was not available, so only" //$NON-NLS-1$
                        + " the syntax of this query was checked."); //$NON-NLS-1$
            }
            List<Issue> semantic = validator.validate(resource, CheckMode.ALL,
                CancelIndicator.NullImpl);
            for (Issue i : semantic)
            {
                String sev;
                switch (i.getSeverity())
                {
                    case ERROR:
                        sev = "ERROR"; break; //$NON-NLS-1$
                    case WARNING:
                        sev = "WARNING"; break; //$NON-NLS-1$
                    case INFO:
                        sev = "INFO"; break; //$NON-NLS-1$
                    default:
                        sev = "WARNING"; break; //$NON-NLS-1$
                }
                int line = i.getLineNumber() != null ? i.getLineNumber().intValue() : -1;
                int col = i.getColumn() != null ? i.getColumn().intValue() : -1;
                issues.add(new QlIssue(sev, i.getMessage(), line, col, i.getCode()));
            }
            ValidationResult settled = ValidationResult.of(issues);
            settled.parserObjected = parserObjected;
            return settled;
        }
        catch (Exception e)
        {
            Activator.logWarning("QlValidator failed: " + e.getMessage()); //$NON-NLS-1$
            if (parserObjectedBeforeTheThrow)
            {
                // Whatever came apart, it came apart after the parser had already refused the
                // text. That verdict stands on its own.
                ValidationResult settledByTheParser = ValidationResult.of(found);
                settledByTheParser.parserObjected = true;
                return settledByTheParser;
            }
            // Not unavailable: this EDT can check queries, and this one check came apart. Saying
            // otherwise would have callers write through it, and would drop whatever parsing had
            // already found.
            return ValidationResult.unconfirmedWith(found, "Checking this query failed part-way: " //$NON-NLS-1$
                + e.getMessage());
        }
        finally
        {
            if (resource != null)
            {
                try
                {
                    ResourceSet rs = resource.getResourceSet();
                    resource.unload();
                    if (rs != null)
                    {
                        rs.getResources().remove(resource);
                    }
                }
                catch (Exception ignored)
                {
                    // best-effort cleanup
                }
            }
        }
    }
}
