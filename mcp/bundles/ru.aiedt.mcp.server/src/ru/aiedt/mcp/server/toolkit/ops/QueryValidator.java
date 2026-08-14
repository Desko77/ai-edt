/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.QueryResultSchema;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Validates 1C:Enterprise query language text against the QlDcs parser bundled with EDT. Reports both
 * the syntax errors the parser raises and the semantic issues the validator finds, plus a curated set
 * of common-mistake hints. Optionally applies conservative typo fixes before validating.
 */
public class QueryValidator
    implements IMcpTool
{
    public static final String NAME = "validate_query"; //$NON-NLS-1$

    private static final URI QLDCS_LOOKUP_URI = URI.createURI("/nopr/querywizard_validate.qldcs"); //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Validate 1C:Enterprise query language (QL) text in the context of a project, returning " //$NON-NLS-1$
            + "syntax and semantic errors with line numbers. Use after writing or editing any 1C query " //$NON-NLS-1$
            + "to catch mistakes early. Supports regular queries and DCS (Data Composition System) " //$NON-NLS-1$
            + "queries; also returns a 'hints' array flagging common mistakes (SQL keywords like " //$NON-NLS-1$
            + "SELECT/FROM/WHERE/JOIN/LIMIT/ORDER BY used instead of 1C QL, and wrong keywords like " //$NON-NLS-1$
            + "УБЫВАНИЕ instead of УБЫВ). The hints array is empty when the query is correct. " //$NON-NLS-1$
            + "Pass describeResult=true to also get what the query returns - the columns of each " //$NON-NLS-1$
            + "result table and their types - instead of reading them off the query text; an " //$NON-NLS-1$
            + "asterisk is expanded into the fields it stands for."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("queryText", //$NON-NLS-1$
                "Query text to validate (required). The full text of the 1C query, e.g. 'SELECT Ref " //$NON-NLS-1$
                    + "FROM Catalog.Products WHERE Description LIKE &SearchString'", //$NON-NLS-1$
                true)
            .booleanProperty("dcsMode", //$NON-NLS-1$
                "DCS (Data Composition System) mode. Set to true for queries used in data composition " //$NON-NLS-1$
                    + "schemas. Allows additional DCS-specific syntax. Default: false") //$NON-NLS-1$
            .booleanProperty("describeResult", //$NON-NLS-1$
                "Also report what the query returns: one entry per result table with its column " //$NON-NLS-1$
                    + "names and types, so you do not have to read them off the query text. " //$NON-NLS-1$
                    + "SELECT * and T.* are expanded into the fields they stand for, named in " //$NON-NLS-1$
                    + "the language the query names its tables in. To list the tables and " //$NON-NLS-1$
                    + "fields of an object without writing a query, use describe_db_tables. " //$NON-NLS-1$
                    + "Temporary-table statements are counted but produce no table, and " //$NON-NLS-1$
                    + "packageIndex is the real ВыполнитьПакет() position. A column whose type " //$NON-NLS-1$
                    + "cannot be determined is reported without one rather than guessed. Skipped " //$NON-NLS-1$
                    + "when the query has errors - describe an invalid query and the answer would " //$NON-NLS-1$
                    + "describe something you are not going to run. Default: false.") //$NON-NLS-1$
            .booleanProperty("fix", //$NON-NLS-1$
                "Auto-fix obvious syntax errors: keyword typos (ВЫБРАТ -> ВЫБРАТЬ), empty trailing " //$NON-NLS-1$
                    + "WHERE removal. Conservative - never changes semantics. Returns fixedQuery in " //$NON-NLS-1$
                    + "response. Default: false.") //$NON-NLS-1$
            .booleanProperty("fixedOnly", //$NON-NLS-1$
                "When fix=true, return only the fixed query text without diagnostics. Default: false.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String queryText = JsonUtils.extractStringArgument(params, "queryText"); //$NON-NLS-1$
        boolean dcsMode = JsonUtils.extractBooleanArgument(params, "dcsMode", false); //$NON-NLS-1$
        boolean fix = JsonUtils.extractBooleanArgument(params, "fix", false); //$NON-NLS-1$
        boolean fixedOnly = JsonUtils.extractBooleanArgument(params, "fixedOnly", false); //$NON-NLS-1$
        boolean describeResult =
            JsonUtils.extractBooleanArgument(params, "describeResult", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (queryText == null || queryText.isEmpty())
        {
            return ToolResult.error("queryText is required").toJson(); //$NON-NLS-1$
        }

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        if (fix)
        {
            QueryFixResult fixResult = applyConservativeFixes(queryText);
            if (fixedOnly)
            {
                return ToolResult.success()
                    .put("fix", true) //$NON-NLS-1$
                    .put("fixedQuery", fixResult.fixedQuery) //$NON-NLS-1$
                    .put("fixesApplied", fixResult.fixesApplied) //$NON-NLS-1$
                    .toJson();
            }
            String validation =
                validateQuery(project, fixResult.fixedQuery, dcsMode, describeResult);
            try
            {
                JsonObject obj = JsonParser.parseString(validation).getAsJsonObject();
                obj.addProperty("fix", true); //$NON-NLS-1$
                obj.addProperty("fixedQuery", fixResult.fixedQuery); //$NON-NLS-1$
                obj.addProperty("originalQuery", queryText); //$NON-NLS-1$
                JsonArray fixesArray = new JsonArray();
                for (Map<String, String> fixEntry : fixResult.fixesApplied)
                {
                    JsonObject fixObj = new JsonObject();
                    for (Map.Entry<String, String> entry : fixEntry.entrySet())
                    {
                        fixObj.addProperty(entry.getKey(), entry.getValue());
                    }
                    fixesArray.add(fixObj);
                }
                obj.add("fixesApplied", fixesArray); //$NON-NLS-1$
                obj.addProperty("fixable", !fixResult.fixesApplied.isEmpty()); //$NON-NLS-1$
                return obj.toString();
            }
            catch (Exception e)
            {
                return validation;
            }
        }

        return validateQuery(project, queryText, dcsMode, describeResult);
    }

    private static QueryFixResult applyConservativeFixes(String queryText)
    {
        QueryFixResult result = new QueryFixResult();
        String current = queryText;

        Map<String, String> typos = new LinkedHashMap<>();
        typos.put("ВЫБРАТ ", "ВЫБРАТЬ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ВЫБРОТЬ ", "ВЫБРАТЬ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ГЬДЕ ", "ГДЕ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ГДЕЕ ", "ГДЕ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("СОЕДЕНИ", "СОЕДИНЕНИ"); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ЛЕВО СОЕД", "ЛЕВОЕ СОЕД"); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ОБЪЕДИНИ ", "ОБЪЕДИНИТЬ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ИСТЬ ", "ЕСТЬ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("УПОРЯДОЧИТ ", "УПОРЯДОЧИТЬ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("СГРУПИРОВАТЬ", "СГРУППИРОВАТЬ"); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ИМЕЮЩИИ ", "ИМЕЮЩИЕ "); //$NON-NLS-1$ //$NON-NLS-2$

        for (Map.Entry<String, String> entry : typos.entrySet())
        {
            String key = entry.getKey();
            String value = entry.getValue();
            if (current.contains(key))
            {
                String fixed = replaceOutsideStringLiterals(current, key, value);
                // Record a fix only if the typo was actually in a code span (not only inside a
                // literal/comment), so a literal-only match does not advertise a phantom fix.
                if (!fixed.equals(current))
                {
                    current = fixed;
                    Map<String, String> fixMap = new LinkedHashMap<>();
                    fixMap.put("rule", "TYPO_KEYWORD"); //$NON-NLS-1$ //$NON-NLS-2$
                    fixMap.put("from", key.trim()); //$NON-NLS-1$
                    fixMap.put("to", value.trim()); //$NON-NLS-1$
                    result.fixesApplied.add(fixMap);
                }
            }
        }

        Pattern emptyWherePattern = Pattern.compile("(\\bГДЕ\\s*$|\\bWHERE\\s*$)", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
        Matcher m = emptyWherePattern.matcher(current);
        if (m.find())
        {
            current = current.substring(0, m.start()).trim();
            Map<String, String> fixMap = new LinkedHashMap<>();
            fixMap.put("rule", "EMPTY_WHERE"); //$NON-NLS-1$ //$NON-NLS-2$
            fixMap.put("description", "Removed empty trailing WHERE/ГДЕ"); //$NON-NLS-1$ //$NON-NLS-2$
            result.fixesApplied.add(fixMap);
        }

        result.fixedQuery = current;
        return result;
    }

    /**
     * Replaces every occurrence of {@code literal} with {@code replacement} in the parts of the query
     * that are NOT inside a string literal or a {@code //} line comment, so a typo that happens to
     * match a keyword (for example СГРУППИРОВАТЬ inside {@code "..."}) - or a quote that happens to
     * sit inside a comment - does not corrupt data. 1C query string literals are {@code "..."} with
     * {@code ""} as an escaped quote.
     *
     * @param text the query text
     * @param literal the substring to replace
     * @param replacement the replacement
     * @return the text with replacements applied only in code spans
     */
    private static String replaceOutsideStringLiterals(String text, String literal, String replacement)
    {
        // Match either a // line comment or a "..." string literal; replacement runs only in the gaps.
        Pattern skip = Pattern.compile("//[^\\n]*|\"(?:[^\"]|\"\")*\""); //$NON-NLS-1$
        Matcher m = skip.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (m.find())
        {
            out.append(text.substring(last, m.start()).replace(literal, replacement));
            out.append(m.group());
            last = m.end();
        }
        out.append(text.substring(last).replace(literal, replacement));
        return out.toString();
    }

    private String validateQuery(IProject project, String queryText, boolean dcsMode,
        boolean describeResult)
    {
        try
        {
            return UiSync.call(() -> doValidateQuery(project, queryText, dcsMode, describeResult));
        }
        catch (UiSync.UiBusyException e)
        {
            return ToolResult.error(e.getMessage()).put("tag", e.tag()).toJson(); //$NON-NLS-1$
        }
    }

    private String doValidateQuery(IProject project, String queryText, boolean dcsMode,
        boolean describeResult)
    {
        XtextResource resource = null;
        try
        {
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(QLDCS_LOOKUP_URI);
            if (rsp == null)
            {
                return ToolResult
                    .error("QlDcs language support not available. Please ensure the QL plugin is installed.") //$NON-NLS-1$
                    .toJson();
            }

            IResourceSetProvider resourceSetProvider = rsp.get(IResourceSetProvider.class);
            if (resourceSetProvider == null)
            {
                return ToolResult.error("Error: no resource-set provider is registered for this project").toJson(); //$NON-NLS-1$
            }

            ResourceSet resourceSet = resourceSetProvider.get(project);
            URI resourceUri = URI.createPlatformResourceURI(
                "/" + project.getName() + "/mcp_validate_query_" + System.currentTimeMillis() + ".qldcs", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // Held as a plain Resource until the kind is known: casting the call itself would let a
            // ClassCastException escape with the resource already in the set and the field still
            // null, so the finally below would have nothing to clean up and the set would keep it
            // for the life of the project.
            Resource created = resourceSet.createResource(resourceUri);
            if (!(created instanceof XtextResource))
            {
                if (created != null)
                {
                    resourceSet.getResources().remove(created);
                }
                return ToolResult.error("Error: the query resource is not an Xtext resource (" //$NON-NLS-1$
                    + (created == null ? "nothing was created" : created.getClass().getName()) + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            resource = (XtextResource)created;

            if (resource instanceof QlDcsResource)
            {
                QlDcsResource qlResource = (QlDcsResource)resource;
                qlResource.addOptions("DcsValidationModeOption", dcsMode); //$NON-NLS-1$
                qlResource.setPreComputeAnnounceAlias(dcsMode);
            }

            try (InputStream is = new ByteArrayInputStream(queryText.getBytes(StandardCharsets.UTF_8)))
            {
                resource.load(is, null);
            }

            IResourceValidator validator = rsp.get(IResourceValidator.class);
            if (validator == null)
            {
                return ToolResult.error("Error: no query validator is registered for this project").toJson(); //$NON-NLS-1$
            }

            List<QueryIssue> issues = new ArrayList<>();
            for (Resource.Diagnostic error : resource.getErrors())
            {
                issues.add(new QueryIssue("ERROR", error.getMessage(), error.getLine(), error.getColumn(), -1)); //$NON-NLS-1$
            }
            for (Resource.Diagnostic warning : resource.getWarnings())
            {
                issues.add(
                    new QueryIssue("WARNING", warning.getMessage(), warning.getLine(), warning.getColumn(), -1)); //$NON-NLS-1$
            }

            List<Issue> validationIssues = validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl);
            for (Issue issue : validationIssues)
            {
                String severity;
                switch (issue.getSeverity())
                {
                case ERROR:
                    severity = "ERROR"; //$NON-NLS-1$
                    break;
                case WARNING:
                    severity = "WARNING"; //$NON-NLS-1$
                    break;
                case INFO:
                    severity = "INFO"; //$NON-NLS-1$
                    break;
                default:
                    severity = "WARNING"; //$NON-NLS-1$
                    break;
                }
                Integer lineNumber = issue.getLineNumber();
                Integer column = issue.getColumn();
                Integer offset = issue.getOffset();
                issues.add(new QueryIssue(severity, issue.getMessage(),
                    lineNumber != null ? lineNumber.intValue() : -1,
                    column != null ? column.intValue() : -1,
                    offset != null ? offset.intValue() : -1));
            }

            return buildResult(project, queryText, issues, dcsMode, describeResult);
        }
        catch (IOException e)
        {
            Activator.logError("Error: the query text could not be loaded into a resource", e); //$NON-NLS-1$
            return ToolResult.error("Error: could not load the query text: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error: the query could not be validated", e); //$NON-NLS-1$
            return ToolResult.error("Query rejected: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            if (resource != null)
            {
                ResourceSet rs = resource.getResourceSet();
                try
                {
                    resource.unload();
                }
                catch (Exception e)
                {
                    Activator.logError("Error unloading validation resource", e); //$NON-NLS-1$
                }
                // Remove from the set even if unload() threw, so a failing unload does not leak the
                // resource. Guarded so a throwing remove cannot mask the validation result.
                if (rs != null)
                {
                    try
                    {
                        rs.getResources().remove(resource);
                    }
                    catch (Exception e)
                    {
                        Activator.logError("Error removing validation resource from set", e); //$NON-NLS-1$
                    }
                }
            }
        }
    }

    /**
     * Adds what the query returns to the reply.
     * <p>
     * Only for a query that passed: describing a broken one would report the shape of something the
     * caller is not going to run, and a caller reading columns off a failed validation is worse off
     * than one reading none.
     * </p>
     * <p>
     * A failure to describe is reported in the reply rather than raised. The validation result is
     * the answer to the question that was asked; the schema is an extra, and losing the extra must
     * not cost the answer.
     * </p>
     *
     * @param result the reply being built
     * @param project the project the query runs against
     * @param queryText the query
     * @param issues what validation found
     */
    private static void appendResultSchema(JsonObject result, IProject project, String queryText,
        List<QueryIssue> issues, boolean dcsMode)
    {
        boolean hasErrors = issues.stream().anyMatch(i -> "ERROR".equals(i.severity)); //$NON-NLS-1$
        if (hasErrors)
        {
            result.addProperty("resultSchemaSkipped", //$NON-NLS-1$
                "the query has errors - fix them and ask again"); //$NON-NLS-1$
            return;
        }

        QueryResultSchema.Result described =
            QueryResultSchema.describe(project, queryText, dcsMode);
        if (described.error != null)
        {
            result.addProperty("resultSchemaError", described.error); //$NON-NLS-1$
            return;
        }

        JsonArray tables = new JsonArray();
        for (QueryResultSchema.ResultTable table : described.tables)
        {
            JsonObject tableObj = new JsonObject();
            tableObj.addProperty("packageIndex", table.packageIndex); //$NON-NLS-1$
            JsonArray columns = new JsonArray();
            for (QueryResultSchema.Column column : table.columns)
            {
                JsonObject columnObj = new JsonObject();
                columnObj.addProperty("name", column.name); //$NON-NLS-1$
                if (!column.types.isEmpty())
                {
                    JsonArray types = new JsonArray();
                    for (String type : column.types)
                    {
                        types.add(type);
                    }
                    columnObj.add("types", types); //$NON-NLS-1$
                }
                columns.add(columnObj);
            }
            tableObj.add("columns", columns); //$NON-NLS-1$
            tables.add(tableObj);
        }
        result.add("resultTables", tables); //$NON-NLS-1$
        if (described.temporaryTables > 0)
        {
            // Worth saying out loud: these consume ВыполнитьПакет() slots without returning
            // anything, which is why the indexes above can have gaps.
            result.addProperty("temporaryTableStatements", described.temporaryTables); //$NON-NLS-1$
        }
    }

    private String buildResult(IProject project, String queryText, List<QueryIssue> issues,
        boolean dcsMode, boolean describeResult)
    {
        JsonObject result = new JsonObject();
        result.addProperty("success", true); //$NON-NLS-1$
        result.addProperty("valid", issues.isEmpty()); //$NON-NLS-1$
        result.addProperty("dcsMode", dcsMode); //$NON-NLS-1$
        result.addProperty("errorCount", //$NON-NLS-1$
            issues.stream().filter(i -> "ERROR".equals(i.severity)).count()); //$NON-NLS-1$
        result.addProperty("warningCount", //$NON-NLS-1$
            issues.stream().filter(i -> "WARNING".equals(i.severity)).count()); //$NON-NLS-1$
        result.addProperty("infoCount", issues.stream().filter(i -> "INFO".equals(i.severity)).count()); //$NON-NLS-1$ //$NON-NLS-2$

        if (describeResult)
        {
            appendResultSchema(result, project, queryText, issues, dcsMode);
        }

        if (!issues.isEmpty())
        {
            JsonArray issuesArray = new JsonArray();
            for (QueryIssue issue : issues)
            {
                JsonObject issueObj = new JsonObject();
                issueObj.addProperty("severity", issue.severity); //$NON-NLS-1$
                issueObj.addProperty("message", issue.message); //$NON-NLS-1$
                if (issue.line > 0)
                {
                    issueObj.addProperty("line", issue.line); //$NON-NLS-1$
                }
                if (issue.column > 0)
                {
                    issueObj.addProperty("column", issue.column); //$NON-NLS-1$
                }
                if (issue.offset >= 0)
                {
                    issueObj.addProperty("offset", issue.offset); //$NON-NLS-1$
                }
                issuesArray.add(issueObj);
            }
            result.add("issues", issuesArray); //$NON-NLS-1$
        }
        else
        {
            result.add("issues", new JsonArray()); //$NON-NLS-1$
        }

        result.add("hints", computeQueryHints(queryText)); //$NON-NLS-1$
        return result.toString();
    }

    private JsonArray computeQueryHints(String queryText)
    {
        JsonArray hints = new JsonArray();
        if (queryText == null || queryText.isEmpty())
        {
            return hints;
        }

        String scrubbed =
            queryText.replaceAll("\"(?:[^\"]|\"\")*\"", " ").replaceAll("(?iu)\\bКАК\\s+[\\p{L}_]\\w*", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        addWordHint(hints, scrubbed, "LIMIT", "1C QL has no LIMIT - use ВЫБРАТЬ ПЕРВЫЕ N right after ВЫБРАТЬ"); //$NON-NLS-1$ //$NON-NLS-2$
        addWordHint(hints, scrubbed, "SELECT", "SQL keyword - 1C QL uses ВЫБРАТЬ"); //$NON-NLS-1$ //$NON-NLS-2$
        addWordHint(hints, scrubbed, "WHERE", "SQL keyword - 1C QL uses ГДЕ"); //$NON-NLS-1$ //$NON-NLS-2$
        addWordHint(hints, scrubbed, "HAVING", "SQL keyword - 1C QL uses ИМЕЮЩИЕ"); //$NON-NLS-1$ //$NON-NLS-2$
        addWordHint(hints, scrubbed, "DISTINCT", "SQL keyword - 1C QL uses РАЗЛИЧНЫЕ"); //$NON-NLS-1$ //$NON-NLS-2$
        addWordHint(hints, scrubbed, "JOIN", "SQL keyword - 1C QL uses СОЕДИНЕНИЕ (ЛЕВОЕ / ВНУТРЕННЕЕ / ПОЛНОЕ)"); //$NON-NLS-1$ //$NON-NLS-2$
        addWordHint(hints, scrubbed, "UNION", "SQL keyword - 1C QL uses ОБЪЕДИНИТЬ / ОБЪЕДИНИТЬ ВСЕ"); //$NON-NLS-1$ //$NON-NLS-2$
        addPhraseHint(hints, scrubbed, "ORDER\\s+BY", "ORDER BY", "1C QL uses УПОРЯДОЧИТЬ ПО"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addPhraseHint(hints, scrubbed, "GROUP\\s+BY", "GROUP BY", "1C QL uses СГРУППИРОВАТЬ ПО"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addPhraseHint(hints, scrubbed, "IS\\s+NULL", "IS NULL", "1C QL uses ЕСТЬ NULL"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addWordHint(hints, scrubbed, "УБЫВАНИЕ", "the descending keyword is УБЫВ, not УБЫВАНИЕ"); //$NON-NLS-1$ //$NON-NLS-2$
        addWordHint(hints, scrubbed, "ВОЗРАСТАНИЕ", "the ascending keyword is ВОЗР, not ВОЗРАСТАНИЕ"); //$NON-NLS-1$ //$NON-NLS-2$

        return hints;
    }

    private static void addWordHint(JsonArray hints, String text, String word, String hint)
    {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", //$NON-NLS-1$ //$NON-NLS-2$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE);
        if (p.matcher(text).find())
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("found", word); //$NON-NLS-1$
            obj.addProperty("hint", hint); //$NON-NLS-1$
            hints.add(obj);
        }
    }

    private static void addPhraseHint(JsonArray hints, String text, String regex, String label, String hint)
    {
        Pattern p = Pattern.compile("\\b" + regex + "\\b", //$NON-NLS-1$ //$NON-NLS-2$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE);
        if (p.matcher(text).find())
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("found", label); //$NON-NLS-1$
            obj.addProperty("hint", hint); //$NON-NLS-1$
            hints.add(obj);
        }
    }

    private static class QueryFixResult
    {
        String fixedQuery;
        List<Map<String, String>> fixesApplied = new ArrayList<>();
    }

    private static class QueryIssue
    {
        final String severity;
        final String message;
        final int line;
        final int column;
        final int offset;

        QueryIssue(String severity, String message, int line, int column, int offset)
        {
            this.severity = severity;
            this.message = message;
            this.line = line;
            this.column = column;
            this.offset = offset;
        }
    }
}
