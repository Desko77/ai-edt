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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
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
import com._1c.g5.v8.dt.ql.model.AbstractQuerySchemaSource;
import com._1c.g5.v8.dt.ql.model.CommonExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchemaOperator;

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
     * A code, not a message: the message is localised and the code is not. The only diagnostic the
     * merge of two answers may drop is an error with this code - a resolved source says a field of
     * it exists, and says nothing about any other finding at that place.
     * </p>
     */
    static final String FIELD_NOT_FOUND = "Field not found"; //$NON-NLS-1$

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

    /** The alias the wrapper gives an expression it checks as a query. */
    static final String EXPRESSION_PROBE_ALIAS = "__DcsExpressionProbe"; //$NON-NLS-1$

    /** {@link #EXPRESSION_PROBE_ALIAS} in lower case, for the search in a diagnostic's text. */
    private static final String EXPRESSION_PROBE_ALIAS_LOWER =
        EXPRESSION_PROBE_ALIAS.toLowerCase(java.util.Locale.ROOT);

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
         * The character offset of the issue in the text, or a negative number when the issue
         * carries none. A resource's own copy of a finding names no offset; the checker's copy
         * does, and it is the one a place in the text can be settled by.
         */
        public final int offset;

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
            this(severity, message, line, column, -1, null);
        }

        public QlIssue(String severity, String message, int line, int column, String code)
        {
            this(severity, message, line, column, -1, code);
        }

        public QlIssue(String severity, String message, int line, int column, int offset,
            String code)
        {
            this.severity = severity;
            this.message = message;
            this.line = line;
            this.column = column;
            this.offset = offset;
            this.code = code;
        }

        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", severity); //$NON-NLS-1$
            m.put("message", message); //$NON-NLS-1$
            m.put("line", line); //$NON-NLS-1$
            m.put("column", column); //$NON-NLS-1$
            if (offset >= 0)
            {
                m.put("offset", Integer.valueOf(offset)); //$NON-NLS-1$
            }
            if (code != null)
            {
                m.put("code", code); //$NON-NLS-1$
            }
            return m;
        }
    }

    /**
     * One place in the query text, as an issue names it.
     * <p>
     * An offset is the precise place; line and column are what an issue carries when it carries no
     * offset, and two places are the same place when either spelling agrees on it.
     * </p>
     */
    public static final class At
    {
        /** The character offset, or a negative number when the issue carries none. */
        public final int offset;

        /** The 1-based line, or a negative number when unknown. */
        public final int line;

        /** The 1-based column, or a negative number when unknown. */
        public final int column;

        private At(int offset, int line, int column)
        {
            this.offset = offset;
            this.line = line;
            this.column = column;
        }

        /**
         * Makes a place out of what an issue named.
         *
         * @param offset the offset, negative when absent
         * @param line the line, negative when absent
         * @param column the column, negative when absent
         * @return the place
         */
        public static At of(int offset, int line, int column)
        {
            return new At(offset, line, column);
        }

        /**
         * Whether two issues point at the same place.
         * <p>
         * Offsets decide when both sides have one; otherwise the line and the column decide, and a
         * place that names neither is never the same place as anything - two unknown positions are
         * not one known position.
         * </p>
         *
         * @param other the other place
         * @return whether both issues point here
         */
        boolean sameAs(At other)
        {
            if (this.offset >= 0 && other.offset >= 0)
            {
                return this.offset == other.offset;
            }
            if (this.line <= 0 || this.column <= 0 || other.line <= 0 || other.column <= 0)
            {
                return false;
            }
            return this.line == other.line && this.column == other.column;
        }
    }

    /**
     * One written field reference that begins with a declared source alias, and the span of the text
     * it occupies.
     */
    public static final class FieldSite
    {
        /** The character offset of the reference. */
        public final int offset;

        /** The length of the reference in characters. */
        public final int length;

        /** The 1-based line the reference starts on. */
        public final int line;

        /** The alias the reference begins with, as written. */
        public final String alias;

        /**
         * The query scope that declares the alias.
         * <p>
         * A scope is one query of the package, one union part, or one nested query. Its id is the
         * scope's position in the text, so two parses of the same text agree on it and a site of
         * one model's layout names the other model's scope.
         * </p>
         */
        public final int scope;

        /**
         * The 1-based column the reference starts at, or a negative number when unknown.
         */
        public final int column;

        /**
         * The 1-based last column the reference occupies on its starting line, or a negative
         * number when unknown.
         */
        public final int endColumn;

        /**
         * Makes a site.
         *
         * @param offset the character offset
         * @param length the length in characters
         * @param line the 1-based starting line
         * @param alias the alias the reference begins with
         * @param scope the id of the query scope declaring the alias
         */
        public FieldSite(int offset, int length, int line, String alias, int scope)
        {
            this(offset, length, line, alias, scope, -1, -1);
        }

        /**
         * Makes a site with the columns it occupies on its starting line.
         *
         * @param offset the character offset
         * @param length the length in characters
         * @param line the 1-based starting line
         * @param alias the alias the reference begins with
         * @param scope the id of the query scope declaring the alias
         * @param column the 1-based column the reference starts at, negative when unknown
         * @param endColumn the 1-based last column on the starting line, negative when unknown
         */
        public FieldSite(int offset, int length, int line, String alias, int scope, int column,
            int endColumn)
        {
            this.offset = offset;
            this.length = length;
            this.line = line;
            this.alias = alias;
            this.scope = scope;
            this.column = column;
            this.endColumn = endColumn;
        }
    }

    /**
     * What a parsed query says about its own sources: which aliases each query scope declared,
     * whether each alias's table resolved in the model that parsed the query, and where its
     * alias-headed field references sit in the text.
     * <p>
     * Built per model: the parse is the same text, so the sites and the scope ids are the same in
     * both, while resolution is a property of the model that did the parsing - the very difference
     * the merge of two answers turns on.
     * </p>
     */
    public static final class QueryLayout
    {
        /**
         * One query scope (a batch query, a union part, a nested query) to its declared lower-case
         * aliases and whether each resolved in the model that parsed the query. A scope that
         * declares one alias twice with different resolvability holds no entry for it: which
         * declaration a field of that name resolves through is not something the map can say.
         */
        public final Map<Integer, Map<String, Boolean>> aliasResolvesByScope = new LinkedHashMap<>();

        /** The alias-headed field references, in text order. */
        public final List<FieldSite> sites = new ArrayList<>();
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

        /**
         * The projects whose models a settled answer draws on, when it draws on more than the one
         * the caller named.
         * <p>
         * Distinct from {@link #resolvedInProject}, which names the one model that answered alone.
         * A merged answer is drawn from both: the named project's diagnostics, kept or dropped
         * against the other model place by place.
         * </p>
         */
        public List<String> resolvedInProjects;

        private ValidationResult(boolean available, List<QlIssue> issues, String reason)
        {
            this(available, issues, reason, false);
        }

        private ValidationResult(boolean available, List<QlIssue> issues, String reason,
            boolean unconfirmed)
        {
            this.unconfirmed = unconfirmed;
            this.available = available;
            this.issues = oneIssuePerPlace(issues != null ? issues : new ArrayList<>());
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

        /**
         * Whether the text has an error: a diagnostic of severity ERROR, or a parse the parser
         * objected to.
         *
         * @return {@code true} when a write guarded by this result must not go through
         */
        public boolean hasErrors()
        {
            return errorCount > 0 || parserObjected;
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
            if (resolvedInProjects != null)
            {
                stats.put("resolvedInProjects", resolvedInProjects); //$NON-NLS-1$
            }
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
        String wrapped = "ВЫБРАТЬ (" + expression + ") КАК " + EXPRESSION_PROBE_ALIAS; //$NON-NLS-1$
        ValidationResult raw = settledAnswer(project, wrapped, true, true);
        if (!raw.available || raw.unconfirmed)
        {
            return raw;
        }
        ValidationResult kept = ValidationResult.of(whatTheExpressionOwns(raw.issues));
        kept.parserObjected = raw.parserObjected;
        return kept;
    }

    /**
     * Keeps the diagnostics that are about the expression rather than about the query wrapped
     * around it.
     * <p>
     * A diagnostic naming the wrapper's alias {@link #EXPRESSION_PROBE_ALIAS} is about the wrapper
     * and is dropped. The alias is this class's own identifier, so the test does not depend on the
     * language the platform writes its messages in. Every other diagnostic is kept, with the ones
     * this check cannot judge demoted by {@link #whatThisCannotJudge}.
     * </p>
     *
     * @param issues the diagnostics of the wrapped query
     * @return the diagnostics that belong to the expression
     */
    static List<QlIssue> whatTheExpressionOwns(List<QlIssue> issues)
    {
        List<QlIssue> kept = new ArrayList<>();
        for (QlIssue issue : issues)
        {
            if (issue.message != null
                && issue.message.toLowerCase(java.util.Locale.ROOT).contains(EXPRESSION_PROBE_ALIAS_LOWER))
            {
                continue;
            }
            kept.add(whatThisCannotJudge(issue));
        }
        return kept;
    }

    /**
     * One diagnostic per place: the resource's copy and the checker's copy of one finding share a
     * severity, a line, a column and a message, and answering with both reports every place twice
     * and doubles every count. When they do, the record that carries an offset is the one kept -
     * it is the one a caller can turn back into a position in the text.
     *
     * @param raw the diagnostics as collected
     * @return the diagnostics, one per place, in collection order
     */
    static List<QlIssue> oneIssuePerPlace(List<QlIssue> raw)
    {
        Map<String, QlIssue> byPlace = new LinkedHashMap<>();
        for (QlIssue issue : raw)
        {
            String key = placeKey(issue.severity, issue.line, issue.column, issue.message);
            QlIssue seen = byPlace.get(key);
            if (seen == null || (seen.offset < 0 && issue.offset >= 0))
            {
                byPlace.put(key, issue);
            }
        }
        return new ArrayList<>(byPlace.values());
    }

    /**
     * The identity of a place: severity, line, column and message together. Two diagnostics
     * identical on all four are the same finding said twice; two that differ on the message, or in
     * severity, are two findings and both stay.
     *
     * @param severity the severity
     * @param line the line
     * @param column the column
     * @param message the message
     * @return the key of the place
     */
    private static String placeKey(String severity, int line, int column, String message)
    {
        return severity + "\u0000" + line + "\u0000" + column + "\u0000" + message; //$NON-NLS-1$
    }

    /**
     * Reads the sources and the alias-headed field references of a parsed query, with the span each
     * reference occupies in the text and the query scope each alias belongs to.
     * <p>
     * An alias belongs to the query that declared it - one query of the package, one union part,
     * one nested query - and a field reference settles against the declarations of its own scope
     * only. One map for the whole batch would let a later query's same-named alias overwrite an
     * earlier one, and a source the other model cannot see would then settle an error it was never
     * asked about.
     * </p>
     * <p>
     * Best-effort by design: the layout exists to settle one model's answer against another's, and
     * a query the walk cannot read yields an empty layout, which settles nothing - the conservative
     * direction for a check that reports diagnostics.
     * </p>
     *
     * @param resource the parsed query
     * @return the layout, empty when the model could not be walked
     */
    public static QueryLayout layoutOf(Resource resource)
    {
        QueryLayout layout = new QueryLayout();
        try
        {
            // Scope ids are the scopes' positions in the text: two parses of the same text agree
            // on them, which is what lets one model's sites name the other model's scope.
            Map<QuerySchemaOperator, Integer> scopeIds = new IdentityHashMap<>();
            Map<Integer, Set<String>> declared = new LinkedHashMap<>();
            Set<String> disputed = new LinkedHashSet<>();
            for (TreeIterator<EObject> walk = EcoreUtil.getAllContents(resource, false); walk
                .hasNext();)
            {
                EObject next = walk.next();
                if (next instanceof QuerySchemaOperator)
                {
                    Integer id = Integer.valueOf(scopeIds.size());
                    scopeIds.put((QuerySchemaOperator)next, id);
                    declared.put(id, new LinkedHashSet<>());
                }
                else if (next instanceof AbstractQuerySchemaSource)
                {
                    AbstractQuerySchemaSource source = (AbstractQuerySchemaSource)next;
                    String alias = source.getAlias();
                    Integer scope = scopeIdOf(scopeIds, next);
                    if (alias != null && !alias.isBlank() && scope != null)
                    {
                        String key = alias.toLowerCase(Locale.ROOT);
                        declared.get(scope).add(key);
                        mergeAliasDeclaration(layout.aliasResolvesByScope, disputed,
                            scope.intValue(), key, resolvesHere(source));
                    }
                }
            }
            String text = rootText(resource);
            for (TreeIterator<EObject> walk = EcoreUtil.getAllContents(resource, false); walk
                .hasNext();)
            {
                EObject next = walk.next();
                if (!(next instanceof CommonExpression))
                {
                    continue;
                }
                Integer scope = scopeIdOf(scopeIds, next);
                if (scope == null)
                {
                    continue;
                }
                String alias = aliasHeaded(((CommonExpression)next).getContent(), declared.get(scope));
                if (alias == null)
                {
                    continue;
                }
                ICompositeNode node = NodeModelUtils.findActualNodeFor(next);
                if (node == null)
                {
                    continue;
                }
                layout.sites.add(new FieldSite(node.getTotalOffset(), node.getTotalLength(),
                    node.getStartLine(), alias, scope.intValue(),
                    columnWhere(text, node.getTotalOffset()),
                    lastColumnWhere(text, node.getTotalOffset(), node.getTotalLength())));
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not read the query's sources: " + e.getMessage()); //$NON-NLS-1$
            return new QueryLayout();
        }
        return layout;
    }

    /**
     * The id of the nearest query scope enclosing a node, when there is one.
     * <p>
     * A source or a field reference of a nested query reaches the nested query's own operator
     * first, which is what keeps an inner alias from being read as a declaration of the outer
     * query.
     * </p>
     *
     * @param scopeIds the scopes met so far, with their ids
     * @param node the node
     * @return the scope id, or <code>null</code> when the node sits in no query scope
     */
    private static Integer scopeIdOf(Map<QuerySchemaOperator, Integer> scopeIds, EObject node)
    {
        for (EObject container = node.eContainer(); container != null; container =
            container.eContainer())
        {
            Integer id = scopeIds.get(container);
            if (id != null)
            {
                return id;
            }
        }
        return null;
    }

    /**
     * Adds one alias declaration to its scope's resolution map.
     * <p>
     * A scope that declares one alias twice with the same resolvability keeps it; twice with
     * different resolvability keeps neither entry - which declaration a field of that name resolves
     * through is then not something the map can say, and a diagnostic it might have settled stays.
     * </p>
     *
     * @param scopes the layout's scope map, written in place
     * @param disputed the scope-alias pairs already found contradicting themselves
     * @param scope the declaring scope's id
     * @param alias the lower-case alias
     * @param resolves whether this source resolved in the model that parsed the query
     */
    private static void mergeAliasDeclaration(Map<Integer, Map<String, Boolean>> scopes,
        Set<String> disputed, int scope, String alias, boolean resolves)
    {
        String disputedKey = scope + "\u0000" + alias; //$NON-NLS-1$
        if (disputed.contains(disputedKey))
        {
            return;
        }
        Map<String, Boolean> ofScope =
            scopes.computeIfAbsent(Integer.valueOf(scope), k -> new LinkedHashMap<>());
        Boolean previous = ofScope.put(alias, Boolean.valueOf(resolves));
        if (previous != null && previous.booleanValue() != resolves)
        {
            ofScope.remove(alias);
            disputed.add(disputedKey);
        }
    }

    /**
     * The whole text the node model holds, when it can be read.
     *
     * @param resource the parsed query
     * @return the text, or <code>null</code> when the parse result carries none
     */
    private static String rootText(Resource resource)
    {
        if (resource instanceof XtextResource)
        {
            IParseResult parsed = ((XtextResource)resource).getParseResult();
            if (parsed != null && parsed.getRootNode() != null)
            {
                return parsed.getRootNode().getText();
            }
        }
        return null;
    }

    /**
     * The 1-based column a position sits at, or a negative number when the text is unknown.
     *
     * @param text the whole text, may be <code>null</code>
     * @param position the 0-based position
     * @return the column, or a negative number when unknown
     */
    private static int columnWhere(String text, int position)
    {
        if (text == null)
        {
            return -1;
        }
        int lineStart = text.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        return position - lineStart + 1;
    }

    /**
     * The 1-based last column a span occupies on its starting line, or a negative number when the
     * text is unknown. A span crossing the line end counts up to that line's end.
     *
     * @param text the whole text, may be <code>null</code>
     * @param start the 0-based position the span starts at
     * @param length the length of the span in characters
     * @return the last column, or a negative number when unknown
     */
    private static int lastColumnWhere(String text, int start, int length)
    {
        if (text == null)
        {
            return -1;
        }
        int lineStart = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
        int lineEnd = text.indexOf('\n', start);
        int spanEnd =
            Math.min(start + Math.max(0, length), lineEnd >= 0 ? lineEnd : text.length());
        return spanEnd <= start ? start - lineStart + 1 : spanEnd - lineStart;
    }

    /**
     * The declared alias a written field reference begins with, when it begins with one.
     *
     * @param content the written text of the reference
     * @param aliases the aliases declared in the reference's own query scope, lower case
     * @return the alias as written, or <code>null</code> when the reference names no alias of its
     *         scope
     */
    private static String aliasHeaded(String content, Set<String> aliases)
    {
        if (content == null)
        {
            return null;
        }
        String head = content;
        int dot = content.indexOf('.');
        if (dot >= 0)
        {
            head = content.substring(0, dot);
        }
        head = head.trim();
        return isIdentifier(head) && aliases.contains(head.toLowerCase(Locale.ROOT)) ? head : null;
    }

    /**
     * Whether a word is shaped like a query identifier: a letter or an underscore, then letters,
     * underscores or digits. Read per character rather than through a regular expression, so the
     * Cyrillic alphabet needs no separate spelling-out.
     *
     * @param word the word
     * @return whether the word can name an alias
     */
    private static boolean isIdentifier(String word)
    {
        if (word.isEmpty())
        {
            return false;
        }
        for (int i = 0; i < word.length(); i++)
        {
            char c = word.charAt(i);
            if (Character.isLetter(c) || c == '_' || (i > 0 && Character.isDigit(c)))
            {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Whether a source's table resolved in the model that parsed the query.
     * <p>
     * Read from the model rather than from a diagnostic's words: the words are localised, and a
     * rule written against them stops working on an install in another language. A source that is
     * not a metadata table - a temporary table, a nested query - resolves nowhere by definition.
     * </p>
     *
     * @param source the source
     * @return whether the model this query was parsed in holds the table the source names
     */
    private static boolean resolvesHere(AbstractQuerySchemaSource source)
    {
        return QueryResultSchema.dbViewOf(source) != null;
    }

    /**
     * Whether a diagnostic one model raised still stands now that the other model has answered too.
     * <p>
     * The diagnostic is dropped only when ALL THREE hold: it is an error about a field that is not
     * there, read by its code rather than its words; the other model said nothing at the very
     * place of the diagnostic; and the source its field's alias names resolved in the other
     * model's parse of the query scope that declares the alias.
     * </p>
     * <p>
     * The first condition keeps every other finding. The second keeps what both models see - an
     * invented field is reported everywhere. The third keeps what the other model was never in a
     * position to judge: an error about an object the named project owns itself, whose table the
     * other model does not hold, and an alias redeclared by another query of the batch, which
     * settles nothing about the query that first declared it. A diagnostic that is not a missing
     * field - a warning, a type mismatch, a syntax complaint, a table that is not there, a
     * diagnostic arriving with no code at all - stands, because the other model's holding the
     * table says nothing about what it found there.
     * </p>
     *
     * @param issue the diagnostic, with the place it names
     * @param otherIssues the places the other model reported at
     * @param sites the alias-headed field references of the parsed query
     * @param elsewhere the other model's layout of the same query
     * @return whether the diagnostic belongs in the settled answer
     */
    public static boolean standsAfterMerging(QlIssue issue, List<At> otherIssues,
        List<FieldSite> sites, QueryLayout elsewhere)
    {
        if (!"ERROR".equals(issue.severity) || !FIELD_NOT_FOUND.equals(issue.code)) //$NON-NLS-1$
        {
            return true;
        }
        At place = At.of(issue.offset, issue.line, issue.column);
        for (At other : otherIssues)
        {
            if (other.sameAs(place))
            {
                return true;
            }
        }
        FieldSite site = aliasUnderwriting(place, sites);
        if (site == null)
        {
            return true;
        }
        Map<String, Boolean> ofScope =
            elsewhere.aliasResolvesByScope.get(Integer.valueOf(site.scope));
        if (ofScope == null)
        {
            return true;
        }
        Boolean resolves = ofScope.get(site.alias.toLowerCase(Locale.ROOT));
        return resolves == null || !resolves.booleanValue();
    }

    /**
     * The field reference whose alias covers the place a diagnostic points at.
     * <p>
     * Offsets decide when both have them. A diagnostic without one is matched by line AND column,
     * the column lying inside the reference: a line alone is not a place, because one line holds
     * several fields and the first of them is not the others. When the place fits more than one
     * reference - or none - the diagnostic names no alias, and a diagnostic that names no alias
     * stands.
     * </p>
     *
     * @param issue the place of the diagnostic
     * @param sites the alias-headed field references, in text order
     * @return the site, or <code>null</code> when no one reference covers the place
     */
    private static FieldSite aliasUnderwriting(At issue, List<FieldSite> sites)
    {
        FieldSite found = null;
        for (FieldSite site : sites)
        {
            if (!covers(issue, site))
            {
                continue;
            }
            if (found != null)
            {
                return null;
            }
            found = site;
        }
        return found;
    }

    /**
     * Whether a diagnostic's place lies inside one field reference.
     * <p>
     * Offsets decide when both sides have them; otherwise the line and the column decide, the
     * column within the columns the reference occupies on its starting line.
     * </p>
     *
     * @param issue the place of the diagnostic
     * @param site the field reference
     * @return whether the place lies inside the reference
     */
    private static boolean covers(At issue, FieldSite site)
    {
        if (issue.offset >= 0 && site.offset >= 0)
        {
            return issue.offset >= site.offset && issue.offset < site.offset + site.length;
        }
        return issue.line > 0 && issue.column > 0 && site.line == issue.line && site.column > 0
            && site.endColumn >= site.column && issue.column >= site.column
            && issue.column <= site.endColumn;
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
     * What one model answered, with the layout of the query as that model resolved it.
     */
    private static final class Asked
    {
        /** The verdict, never <code>null</code>. */
        final ValidationResult verdict;

        /** The query's sources as this model resolved them. */
        final QueryLayout layout;

        private Asked(ValidationResult verdict, QueryLayout layout)
        {
            this.verdict = verdict;
            this.layout = layout;
        }

        static Asked of(ValidationResult verdict)
        {
            return new Asked(verdict, new QueryLayout());
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
            return new QlIssue("WARNING", issue.message, issue.line, issue.column, issue.offset, //$NON-NLS-1$
                issue.code);
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
     * one validation and not two, and its answer is taken whole only when it is clean.
     * </p>
     * <p>
     * Not ranked by diagnostic count: a model that cannot find the table never reaches the fields,
     * so it can answer with one complaint while the model that did find it reports a real error per
     * bad field. Counting would prefer the one that resolved nothing. Clean or not clean cannot go
     * wrong that way, and when neither is clean the two answers are settled place by place: the
     * named project's diagnostics kept against the other model's silence, so a non-adopted
     * attribute of a borrowed object stops being an error while an invented field, and an error
     * about an object the named project owns itself, stay.
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
     * <p>
     * The other model's clean answer settles the query on its own. Its unclean one does not end the
     * matter: a query that mixes a borrowed object's non-adopted attribute with an object the
     * extension owns upsets both models, and their answers are then settled place by place - each
     * diagnostic of the named model kept unless the other model is silent at that very place and
     * the source the diagnostic's field names resolved there.
     * </p>
     *
     * @param asked the project the caller named.
     * @param queryText the query.
     * @param dcsMode whether to parse it as a data composition query.
     * @return what the check found, or why it could not settle.
     */
    private static Pass onePass(IProject asked, String queryText, boolean dcsMode,
        boolean bareExpression)
    {
        Asked here = runValidation(asked, queryText, dcsMode);
        if (bareExpression)
        {
            // Demoted HERE, before anything counts the errors. Left until after the answer was
            // settled, a handful of unjudgeable field diagnostics counted as errors, which sent
            // the check to the other model to have them confirmed - and when that model could not
            // be reached, an ordinary expression came back refused as unconfirmed. Nothing was
            // ever going to confirm them: there is no FROM clause for a field to resolve against.
            here = new Asked(withTheUnjudgeableDemoted(here.verdict), here.layout);
        }
        if (!here.verdict.available)
        {
            // This EDT cannot check queries. Readiness has nothing to add to that, and asking about
            // it first would turn a build in progress into a block on an install where the policy
            // is to write anyway.
            return Pass.of(here.verdict);
        }
        String notReady = ProjectStateGuard.checkReadyOrError(asked);
        if (notReady != null)
        {
            // Checked after the answer, not before it, and it still governs: right after EDT
            // restarts this model reports missing tables that exist, and it can as easily report
            // nothing wrong with a query that is wrong.
            return Pass.of(ValidationResult.unconfirmed(notReady));
        }
        if (here.verdict.unconfirmed || here.verdict.errorCount == 0 || here.verdict.parserObjected)
        {
            // Parsing settled it: the other model would be parsing the same text.
            return Pass.of(here.verdict);
        }
        Companion companion = companionOf(asked);
        if (companion.kind == Companion.Kind.NONE)
        {
            return Pass.of(here.verdict);
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
            Asked above = runValidation(alternative, queryText, dcsMode);
            if (bareExpression)
            {
                above = new Asked(withTheUnjudgeableDemoted(above.verdict), above.layout);
            }
            if (other.moved())
            {
                // Not an answer, and not a refusal either: this pass is void and the outer loop
                // gets to try again. Refusing outright would spend the retry on a model that may
                // well have settled by the time a second pass ran.
                return Pass.voided();
            }
            if (!above.verdict.available || above.verdict.unconfirmed)
            {
                // It was there, it was ready, and asking it did not work. The errors found in the
                // named project stay unconfirmed all the same.
                return Pass.of(ValidationResult.unconfirmed(
                    "This query could not be confirmed against the model that holds the objects" //$NON-NLS-1$
                        + " it names. That model could not be asked.")); //$NON-NLS-1$
            }
            if (above.verdict.errorCount != 0)
            {
                return Pass.of(mergedFromBoth(here, above, asked.getName(), alternative.getName()));
            }
            above.verdict.resolvedInProject = alternative.getName();
            return Pass.of(above.verdict);
        }
    }

    /**
     * Settles the two models' answers against each other, for a query neither of them accounts for
     * whole.
     * <p>
     * Every diagnostic of the named model is kept or dropped by
     * {@link #standsAfterMerging(QlIssue, List, List, QueryLayout)}; the other model's own
     * diagnostics - about the objects the named project declares itself - enter the answer
     * nowhere, because the named model is the only one of the two that holds those objects. The
     * answer names both models in {@code resolvedInProjects}.
     * </p>
     *
     * @param here what the named project answered, with its layout.
     * @param above what the other model answered, with its layout.
     * @param askedName the named project's name.
     * @param otherName the other model's project name.
     * @return the settled answer.
     */
    private static ValidationResult mergedFromBoth(Asked here, Asked above, String askedName,
        String otherName)
    {
        List<At> otherPlaces = new ArrayList<>();
        for (QlIssue other : above.verdict.issues)
        {
            otherPlaces.add(At.of(other.offset, other.line, other.column));
        }
        List<QlIssue> kept = new ArrayList<>();
        for (QlIssue issue : here.verdict.issues)
        {
            if (standsAfterMerging(issue, otherPlaces, here.layout.sites, above.layout))
            {
                kept.add(issue);
            }
        }
        ValidationResult merged = ValidationResult.of(kept);
        merged.parserObjected = here.verdict.parserObjected;
        merged.resolvedInProjects = List.of(askedName, otherName);
        return merged;
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

    private static Asked runValidation(IProject project, String queryText,
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
                return Asked.of(ValidationResult.unavailable("QlDcs language support not registered")); //$NON-NLS-1$
            }
            IResourceSetProvider rsProvider = rsp.get(IResourceSetProvider.class);
            if (rsProvider == null)
            {
                return Asked.of(ValidationResult.unavailable("IResourceSetProvider not available")); //$NON-NLS-1$
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
            QueryLayout layout = layoutOf(resource);
            List<QlIssue> issues = found;
            for (Resource.Diagnostic d : resource.getErrors())
            {
                issues.add(new QlIssue("ERROR", d.getMessage(), d.getLine(), d.getColumn(), -1, //$NON-NLS-1$
                    kindOf(d)));
            }
            for (Resource.Diagnostic d : resource.getWarnings())
            {
                issues.add(new QlIssue("WARNING", d.getMessage(), d.getLine(), //$NON-NLS-1$
                    d.getColumn(), -1, kindOf(d)));
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
                    return new Asked(settledByTheParser, layout);
                }
                // Parsing found what parsing finds. Whether the query names objects that exist was
                // never asked, and answering "no problems" would say it was.
                return new Asked(ValidationResult.unconfirmedWith(issues,
                    "The checker that resolves names against metadata was not available, so only" //$NON-NLS-1$
                        + " the syntax of this query was checked."), layout); //$NON-NLS-1$
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
                int offset = i.getOffset() != null ? i.getOffset().intValue() : -1;
                issues.add(new QlIssue(sev, i.getMessage(), line, col, offset, i.getCode()));
            }
            ValidationResult settled = ValidationResult.of(issues);
            settled.parserObjected = parserObjected;
            return new Asked(settled, layout);
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
                return Asked.of(settledByTheParser);
            }
            // Not unavailable: this EDT can check queries, and this one check came apart. Saying
            // otherwise would have callers write through it, and would drop whatever parsing had
            // already found.
            return Asked.of(ValidationResult.unconfirmedWith(found, "Checking this query failed part-way: " //$NON-NLS-1$
                + e.getMessage()));
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
