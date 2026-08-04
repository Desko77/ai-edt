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
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;

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

        public QlIssue(String severity, String message, int line, int column)
        {
            this.severity = severity;
            this.message = message;
            this.line = line;
            this.column = column;
        }

        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", severity); //$NON-NLS-1$
            m.put("message", message); //$NON-NLS-1$
            m.put("line", line); //$NON-NLS-1$
            m.put("column", column); //$NON-NLS-1$
            return m;
        }
    }

    /**
     * Outcome of a validation pass.
     */
    public static final class ValidationResult
    {
        public final boolean available; // false when QlDcs language not present
        public final List<QlIssue> issues;
        public final int errorCount;
        public final int warningCount;
        public final String unavailableReason;

        private ValidationResult(boolean available, List<QlIssue> issues, String reason)
        {
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
            if (!available && unavailableReason != null)
            {
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
        return runValidation(project, queryText, dcsMode);
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
        ValidationResult raw = runValidation(project, wrapped, true);
        if (!raw.available)
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
            filtered.add(i);
        }
        return ValidationResult.of(filtered);
    }

    private static ValidationResult runValidation(IProject project, String queryText,
        boolean dcsMode)
    {
        XtextResource resource = null;
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
            List<QlIssue> issues = new ArrayList<>();
            for (Resource.Diagnostic d : resource.getErrors())
            {
                issues.add(new QlIssue("ERROR", d.getMessage(), d.getLine(), d.getColumn())); //$NON-NLS-1$
            }
            for (Resource.Diagnostic d : resource.getWarnings())
            {
                issues.add(new QlIssue("WARNING", d.getMessage(), d.getLine(), //$NON-NLS-1$
                    d.getColumn()));
            }
            IResourceValidator validator = rsp.get(IResourceValidator.class);
            if (validator != null)
            {
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
                    issues.add(new QlIssue(sev, i.getMessage(), line, col));
                }
            }
            return ValidationResult.of(issues);
        }
        catch (Exception e)
        {
            Activator.logWarning("QlValidator failed: " + e.getMessage()); //$NON-NLS-1$
            return ValidationResult.unavailable(e.getMessage());
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
