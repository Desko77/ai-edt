/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import ru.aiedt.mcp.server.support.WatchForCancel;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.SensitivePatternLibrary;
import ru.aiedt.mcp.server.support.SubsystemMembership;
import ru.aiedt.mcp.server.support.UiSync;
import ru.aiedt.mcp.server.support.WalkNarrowing;

/**
 * Scans the project for potential personal-data / secret leaks: attribute
 * names, hardcoded tokens, comment leaks, sensitive log records.
 */
public class SensitiveDataScanTool implements IMcpTool
{
    public static final String NAME = "sensitive_data_scan"; //$NON-NLS-1$

    private static final java.util.List<String> SCOPES = java.util.List.of(
        WalkNarrowing.PROJECT, WalkNarrowing.SUBSYSTEM, WalkNarrowing.MODULE);

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\""); //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `security_audit` `operation=sensitive_data_scan`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Scan project for sensitive-data findings: attribute names that match the " //$NON-NLS-1$
            + "personal-data dictionary (Password, Passport, СНИЛС, etc.), hardcoded secrets " //$NON-NLS-1$
            + "in BSL string literals (Bearer tokens, AWS keys, JWT, base64), email/phone " //$NON-NLS-1$
            + "leaks in comments, log records that may include sensitive fields. 4 check kinds."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", //$NON-NLS-1$
                "project | subsystem | module. Absent, the selectors decide: moduleFqn is a " //$NON-NLS-1$
                    + "module, subsystemName is a subsystem, and nothing is the whole project. " //$NON-NLS-1$
                    + "moduleFqn together with subsystemName is refused: they name different " //$NON-NLS-1$
                    + "areas. scope=project rejects every selector. scope=module requires " //$NON-NLS-1$
                    + "moduleFqn. scope=subsystem requires subsystemName and rejects moduleFqn. " //$NON-NLS-1$
                    + "An unknown module, subsystem or scope word is refused by name and the " //$NON-NLS-1$
                    + "project is not scanned. A subsystem includes nested subsystems; the answer " //$NON-NLS-1$
                    + "names that and how many objects the composition holds.") //$NON-NLS-1$
            .stringProperty("moduleFqn", //$NON-NLS-1$
                "Module FQN, for example CommonModule.Sales. Required for scope=module. Without " //$NON-NLS-1$
                    + "scope it selects the module on its own. Refused together with subsystemName.") //$NON-NLS-1$
            .stringProperty("subsystemName", //$NON-NLS-1$
                "Subsystem name. Required for scope=subsystem. Without scope it selects the " //$NON-NLS-1$
                    + "subsystem on its own, nested subsystems included. Refused together with " //$NON-NLS-1$
                    + "moduleFqn.") //$NON-NLS-1$
            .stringProperty("checks", //$NON-NLS-1$
                "Comma-separated: ATTRIBUTE_NAME, HARDCODED_SECRET, COMMENT_LEAK, LOG_SENSITIVE") //$NON-NLS-1$
            .stringProperty("severity_filter", "info | warning | error | all (default warning)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("customPatterns", //$NON-NLS-1$
                "Comma-separated additional regex patterns for ATTRIBUTE_NAME") //$NON-NLS-1$
            .stringProperty("format", "json | markdown (default json)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        WalkNarrowing.Decision decision = WalkNarrowing.decide(params, SCOPES,
            WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM);
        if (decision.refused())
        {
            return ToolResult.error(decision.refusal()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        try
        {
            return UiSync.call(() -> {
                try
                {
                    return runScan(project, params, decision);
                }
                catch (Exception e)
                {
                    Activator.logError("sensitive_data_scan error", e); //$NON-NLS-1$
                    return ToolResult.error(e.getMessage()).toJson();
                }
            });
        }
        catch (UiSync.UiBusyException e)
        {
            return ToolResult.error(e.getMessage()).put("tag", e.tag()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * The scan itself, without the UI-thread hop {@link #execute} wraps it in.
     *
     * @param project the project
     * @param params the call arguments
     * @param decision the accepted walk
     * @return the JSON answer
     */
    String runScan(IProject project, Map<String, String> params, WalkNarrowing.Decision decision)
        throws Exception
    {
        Set<String> checks = parseChecks(JsonUtils.extractStringArgument(params, "checks")); //$NON-NLS-1$
        String severity = orDefault(JsonUtils.extractStringArgument(params, "severity_filter"), //$NON-NLS-1$
            "warning"); //$NON-NLS-1$
        if (!"all".equalsIgnoreCase(severity) && !"error".equalsIgnoreCase(severity) //$NON-NLS-1$ //$NON-NLS-2$
            && !"warning".equalsIgnoreCase(severity) && !"info".equalsIgnoreCase(severity)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error(TextSuggest.invalidValue("severity_filter", severity, //$NON-NLS-1$
                java.util.Arrays.asList("error", "warning", "info", "all"))).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        String format = orDefault(JsonUtils.extractStringArgument(params, "format"), "json"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<Pattern> custom = parseCustomPatterns(
            JsonUtils.extractStringArgument(params, "customPatterns")); //$NON-NLS-1$
        SubsystemMembership membership = null;
        IFile onlyModule = null;
        if (WalkNarrowing.SUBSYSTEM.equals(decision.area()))
        {
            membership = SubsystemMembership.resolve(project,
                SubsystemMembership.configurationOf(project), decision.subsystemName());
            if (membership.refused())
            {
                return ToolResult.error(membership.refusal()).toJson();
            }
        }
        else if (WalkNarrowing.MODULE.equals(decision.area()))
        {
            BslModuleAccess.ModulePathResolution resolution =
                BslModuleAccess.resolveModulePath(project, decision.moduleFqn());
            if (!resolution.isResolved())
            {
                return ToolResult.error(resolution.getHint()).toJson();
            }
            onlyModule = project.getFile(new Path("src").append(resolution.getPath())); //$NON-NLS-1$
            if (!onlyModule.exists())
            {
                return ToolResult.error("Module '" + decision.moduleFqn() + "' was not found.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        // One watch for the whole call: both scans walk the same project, and an operator
        // who cancels means the call, not one of its halves.
        WatchForCancel watch = WatchForCancel.begin();

        // Attribute names live on metadata, not in a module. A module walk does not report them;
        // a subsystem walk reports them only for objects in the composition.
        if (isEnabled("ATTRIBUTE_NAME", checks) && onlyModule == null) //$NON-NLS-1$
        {
            scanAttributes(project, custom, findings, watch, membership);
        }
        if (isEnabled("HARDCODED_SECRET", checks) //$NON-NLS-1$
            || isEnabled("COMMENT_LEAK", checks) //$NON-NLS-1$
            || isEnabled("LOG_SENSITIVE", checks)) //$NON-NLS-1$
        {
            scanBslFiles(project, checks, findings, watch, membership, onlyModule);
        }

        // Severity filter
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> f : findings)
        {
            String sev = (String) f.get("severity"); //$NON-NLS-1$
            if (sev == null || matchesSeverity(sev, severity))
            {
                filtered.add(f);
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("findings", filtered.size()); //$NON-NLS-1$
        // One watch spans two walks that count different things - metadata objects, then module
        // files - so the unit names both. "files" would report a count of objects as a count of
        // files whenever ATTRIBUTE_NAME is on, which it is by default.
        String cancelled = watch.note("objects and modules"); //$NON-NLS-1$
        ToolResult result = composition(ToolResult.success(), membership).put("scope", decision.area()); //$NON-NLS-1$
        if ("markdown".equalsIgnoreCase(format)) //$NON-NLS-1$
        {
            return result
                .put("statistics", stats) //$NON-NLS-1$
                .put("text", renderMarkdown(filtered, stats, cancelled)) //$NON-NLS-1$
                .put("cancelled", cancelled) //$NON-NLS-1$
                .toJson();
        }
        return result
            .put("statistics", stats) //$NON-NLS-1$
            .put("findings", filtered) //$NON-NLS-1$
            .put("cancelled", cancelled) //$NON-NLS-1$
            .toJson();
    }

    private static ToolResult composition(ToolResult result, SubsystemMembership membership)
    {
        if (membership == null)
        {
            return result;
        }
        return result.put("subsystemName", membership.subsystemName()) //$NON-NLS-1$
            .put("nestedSubsystemsIncluded", true) //$NON-NLS-1$
            .put("compositionSize", membership.compositionSize()) //$NON-NLS-1$
            .put("composition", membership.compositionNote()); //$NON-NLS-1$
    }

    private void scanAttributes(IProject project, Set<Pattern> custom,
        List<Map<String, Object>> findings, WatchForCancel watch, SubsystemMembership membership)
    {
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        if (provider == null)
        {
            return;
        }
        Configuration config = provider.getConfiguration(project);
        if (config == null)
        {
            return;
        }
        for (java.lang.reflect.Method m : config.getClass().getMethods())
        {
            if (watch.raised())
            {
                return;
            }
            if (m.getParameterCount() != 0)
            {
                continue;
            }
            String name = m.getName();
            if (!name.startsWith("get") || "getClass".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            if (!java.util.List.class.isAssignableFrom(m.getReturnType()))
            {
                continue;
            }
            try
            {
                Object value = m.invoke(config);
                if (value instanceof java.util.List)
                {
                    for (Object item : (java.util.List<?>) value)
                    {
                        // One metadata object is the boundary: its attributes, dimensions
                        // and resources are read together or not at all.
                        if (watch.stopHere())
                        {
                            return;
                        }
                        if (item instanceof MdObject)
                        {
                            MdObject object = (MdObject) item;
                            String owner = object.eClass().getName() + "." + object.getName(); //$NON-NLS-1$
                            if (membership != null && !membership.coversObject(owner))
                            {
                                continue;
                            }
                            scanMdObjectAttributes(object, custom, findings);
                        }
                    }
                }
            }
            catch (Throwable ignored)
            {
                // best-effort
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void scanMdObjectAttributes(MdObject obj, Set<Pattern> custom,
        List<Map<String, Object>> findings)
    {
        try
        {
            for (String getter : new String[] { "getAttributes", "getDimensions", //$NON-NLS-1$ //$NON-NLS-2$
                "getResources" }) //$NON-NLS-1$
            {
                java.lang.reflect.Method m;
                try
                {
                    m = obj.getClass().getMethod(getter);
                }
                catch (NoSuchMethodException nsme)
                {
                    continue;
                }
                Object value = m.invoke(obj);
                if (!(value instanceof java.util.List))
                {
                    continue;
                }
                for (Object attr : (java.util.List<Object>) value)
                {
                    if (!(attr instanceof MdObject))
                    {
                        continue;
                    }
                    String attrName = ((MdObject) attr).getName();
                    if (attrName == null)
                    {
                        continue;
                    }
                    if (SensitivePatternLibrary.isSensitiveName(attrName)
                        || matchesAnyCustom(attrName, custom))
                    {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("kind", "ATTRIBUTE_NAME"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("severity", "WARNING"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("ownerFqn", obj.eClass().getName() + "." + obj.getName()); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("attribute", attrName); //$NON-NLS-1$
                        finding.put("message", //$NON-NLS-1$
                            "Attribute name '" + attrName + "' looks like sensitive data"); //$NON-NLS-1$ //$NON-NLS-2$
                        findings.add(finding);
                    }
                }
            }
        }
        catch (Throwable ignored)
        {
            // best-effort
        }
    }

    private boolean matchesAnyCustom(String name, Set<Pattern> custom)
    {
        if (custom == null || custom.isEmpty())
        {
            return false;
        }
        for (Pattern pattern : custom)
        {
            if (pattern.matcher(name).find())
            {
                return true;
            }
        }
        return false;
    }

    private void scanBslFiles(IProject project, Set<String> checks,
        List<Map<String, Object>> findings, WatchForCancel watch, SubsystemMembership membership,
        IFile onlyModule) throws Exception
    {
        if (onlyModule != null)
        {
            scanBslFile(onlyModule, checks, findings);
            return;
        }
        org.eclipse.core.resources.IResourceVisitor visitor = resource -> {
            if (resource instanceof IFile && resource.getName().endsWith(".bsl") //$NON-NLS-1$
                && (membership == null || membership.coversPath(
                    resource.getProjectRelativePath().toString())))
            {
                if (watch.stopHere())
                {
                    // Returning false would only skip this one resource's children, and a
                    // walk over a whole project has plenty more to visit. Thrown, and
                    // caught below, so the findings collected so far are kept.
                    throw new org.eclipse.core.runtime.OperationCanceledException();
                }
                scanBslFile((IFile) resource, checks, findings);
            }
            return true;
        };
        try
        {
            project.accept(visitor, IResource.DEPTH_INFINITE, IResource.NONE);
        }
        catch (org.eclipse.core.runtime.OperationCanceledException stopped)
        {
            // The watch already remembers it, and the answer reads that rather than this.
        }
    }

    /** How many lines one log-record call may be gathered across before it is judged as it is. */
    private static final int LOG_RECORD_LINE_LIMIT = 20;

    /** A log-record call being gathered across the lines it is written on. */
    private static final class OpenLogRecord
    {
        /** The line the call starts on - the one a finding names. */
        private final int line;

        /** The text gathered so far, from the call name onward. */
        private final StringBuilder text = new StringBuilder();

        /** How many lines have been gathered into it. */
        private int lines;

        OpenLogRecord(int line)
        {
            this.line = line;
        }
    }

    /**
     * Gathers a log-record call across the lines it spans, and judges it once it is whole.
     *
     * @param open the call being gathered, or <code>null</code> when none is
     * @param line the line just read
     * @param lineNumber its number, one-based
     * @param file the file being scanned
     * @param findings where a finding goes
     * @return the call still being gathered, or <code>null</code> when nothing is open
     */
    private OpenLogRecord trackLogRecord(OpenLogRecord open, String line, int lineNumber, IFile file,
        List<Map<String, Object>> findings)
    {
        OpenLogRecord gathering = open;
        if (gathering == null)
        {
            Matcher start = SensitivePatternLibrary.LOG_RECORD_START.matcher(line);
            if (!start.find())
            {
                return null;
            }
            gathering = new OpenLogRecord(lineNumber);
            gathering.text.append(line.substring(start.start()));
        }
        else
        {
            gathering.text.append(' ').append(line);
        }
        gathering.lines++;
        if (bracketsStillOpen(gathering.text.toString())
            && gathering.lines < LOG_RECORD_LINE_LIMIT)
        {
            return gathering;
        }
        reportSensitiveNames(gathering, file, findings);
        return null;
    }

    /**
     * Whether the gathered text has an unclosed bracket, counting outside string literals.
     * <p>
     * Outside them because a comment or a message inside a call is written in the language of the
     * user - "Ошибка (код 5)" - and its brackets say nothing about where the call ends.
     * </p>
     *
     * @param text the gathered text
     * @return whether the call is still open
     */
    private static boolean bracketsStillOpen(String text)
    {
        String outsideLiterals = STRING_LITERAL.matcher(text).replaceAll("\"\""); //$NON-NLS-1$
        int depth = 0;
        for (int at = 0; at < outsideLiterals.length(); at++)
        {
            char character = outsideLiterals.charAt(at);
            if (character == '(')
            {
                depth++;
            }
            else if (character == ')')
            {
                depth--;
            }
        }
        return depth > 0;
    }

    /**
     * Whether the text carries this name as a name of its own, rather than inside a longer one.
     * <p>
     * The first argument of a log record is conventionally ИмяСобытия, which carries "имя" as a
     * substring; a check that counted that reported nearly every call in a configuration and so
     * said nothing about any of them. A name counts when what sits on either side of it is not
     * part of an identifier - a dot, a bracket, a space, a quote, the end of the text.
     * </p>
     *
     * @param lowered the text, lower case
     * @param name the sensitive name, lower case
     * @return whether the text names it
     */
    private static boolean carriesAsAName(String lowered, String name)
    {
        int at = lowered.indexOf(name);
        while (at >= 0)
        {
            boolean clearBefore = at == 0 || !partOfAName(lowered.charAt(at - 1));
            int after = at + name.length();
            boolean clearAfter = after >= lowered.length() || !partOfAName(lowered.charAt(after));
            if (clearBefore && clearAfter)
            {
                return true;
            }
            at = lowered.indexOf(name, at + 1);
        }
        return false;
    }

    /**
     * Whether a character can be part of an identifier.
     *
     * @param character the character
     * @return whether it continues a name
     */
    private static boolean partOfAName(char character)
    {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    /**
     * Reports the first sensitive field name the gathered call carries, if any.
     *
     * @param record the gathered call
     * @param file the file it is in
     * @param findings where a finding goes
     */
    private static void reportSensitiveNames(OpenLogRecord record, IFile file,
        List<Map<String, Object>> findings)
    {
        String lowered = record.text.toString().toLowerCase();
        for (String sensitive : SensitivePatternLibrary.SENSITIVE_NAMES)
        {
            if (!carriesAsAName(lowered, sensitive))
            {
                continue;
            }
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("kind", "LOG_SENSITIVE"); //$NON-NLS-1$ //$NON-NLS-2$
            finding.put("severity", "INFO"); //$NON-NLS-1$ //$NON-NLS-2$
            finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
            finding.put("line", Integer.valueOf(record.line)); //$NON-NLS-1$
            finding.put("matchedTerm", sensitive); //$NON-NLS-1$
            finding.put("message", //$NON-NLS-1$
                "Log record may include sensitive field '" + sensitive + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            findings.add(finding);
            return;
        }
    }

    private void scanBslFile(IFile file, Set<String> checks, List<Map<String, Object>> findings)
    {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
        {
            String line;
            int lineNumber = 0;
            OpenLogRecord openRecord = null;
            while ((line = reader.readLine()) != null)
            {
                lineNumber++;
                if (isEnabled("HARDCODED_SECRET", checks)) //$NON-NLS-1$
                {
                    Matcher literalMatcher = STRING_LITERAL.matcher(line);
                    while (literalMatcher.find())
                    {
                        String literal = literalMatcher.group(1);
                        Pattern matched = SensitivePatternLibrary.matchSecret(literal);
                        if (matched != null)
                        {
                            Map<String, Object> finding = new LinkedHashMap<>();
                            finding.put("kind", "HARDCODED_SECRET"); //$NON-NLS-1$ //$NON-NLS-2$
                            finding.put("severity", "ERROR"); //$NON-NLS-1$ //$NON-NLS-2$
                            finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
                            finding.put("line", lineNumber); //$NON-NLS-1$
                            finding.put("pattern", matched.pattern()); //$NON-NLS-1$
                            finding.put("message", //$NON-NLS-1$
                                "Hardcoded secret-like literal detected"); //$NON-NLS-1$
                            findings.add(finding);
                        }
                    }
                }
                if (isEnabled("COMMENT_LEAK", checks)) //$NON-NLS-1$
                {
                    if (SensitivePatternLibrary.EMAIL_IN_COMMENT.matcher(line).find())
                    {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("kind", "COMMENT_LEAK"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("severity", "WARNING"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("subkind", "email"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
                        finding.put("line", lineNumber); //$NON-NLS-1$
                        finding.put("message", "Email address in BSL comment"); //$NON-NLS-1$ //$NON-NLS-2$
                        findings.add(finding);
                    }
                    if (SensitivePatternLibrary.PHONE_IN_COMMENT.matcher(line).find())
                    {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("kind", "COMMENT_LEAK"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("severity", "WARNING"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("subkind", "phone"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
                        finding.put("line", lineNumber); //$NON-NLS-1$
                        finding.put("message", "Phone number in BSL comment"); //$NON-NLS-1$ //$NON-NLS-2$
                        findings.add(finding);
                    }
                }
                if (isEnabled("LOG_SENSITIVE", checks)) //$NON-NLS-1$
                {
                    openRecord = trackLogRecord(openRecord, line, lineNumber, file, findings);
                }
            }
            if (openRecord != null)
            {
                // The file ended with the call still open. What was gathered is judged rather than
                // dropped: a call whose bracket never closes is a file this scan could not parse,
                // not a call without sensitive fields.
                reportSensitiveNames(openRecord, file, findings);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to scan BSL " + file.getFullPath() + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
        }
    }

    private static boolean isEnabled(String check, Set<String> enabled)
    {
        return enabled == null || enabled.isEmpty() || enabled.contains(check);
    }

    private static Set<String> parseChecks(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return null;
        }
        Set<String> set = new HashSet<>(Arrays.asList(raw.split("\\s*,\\s*"))); //$NON-NLS-1$
        set.removeIf(String::isEmpty);
        return set;
    }

    private static Set<Pattern> parseCustomPatterns(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return null;
        }
        Set<Pattern> patterns = new java.util.LinkedHashSet<>();
        for (String s : raw.split("\\s*,\\s*")) //$NON-NLS-1$
        {
            if (s.isEmpty())
            {
                continue;
            }
            try
            {
                patterns.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
            }
            catch (Exception ignored)
            {
                // skip invalid pattern
            }
        }
        return patterns;
    }

    private static boolean matchesSeverity(String sev, String filter)
    {
        if (filter == null || "all".equalsIgnoreCase(filter)) //$NON-NLS-1$
        {
            return true;
        }
        switch (filter.toLowerCase())
        {
            case "error": //$NON-NLS-1$
                return "ERROR".equals(sev); //$NON-NLS-1$
            case "warning": //$NON-NLS-1$
                return "ERROR".equals(sev) || "WARNING".equals(sev); //$NON-NLS-1$ //$NON-NLS-2$
            case "info": //$NON-NLS-1$
                return true;
            default:
                return true;
        }
    }

    private static String renderMarkdown(List<Map<String, Object>> findings,
        Map<String, Object> stats, String cancelled)
    {
        StringBuilder sb = new StringBuilder("# Sensitive data scan\n\n"); //$NON-NLS-1$
        sb.append("**Findings:** ").append(stats.get("findings")).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (cancelled != null)
        {
            sb.append("> **").append(cancelled).append("**\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (findings.isEmpty())
        {
            // Only sayable when the scan finished. Stopped short, nothing found
            // means nothing was looked at, which is a different statement.
            sb.append(cancelled != null
                ? "Nothing had been found when the scan stopped.\n" //$NON-NLS-1$
                : "No sensitive data findings.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        sb.append("| Kind | Severity | Location | Message |\n"); //$NON-NLS-1$
        sb.append("|---|---|---|---|\n"); //$NON-NLS-1$
        for (Map<String, Object> f : findings)
        {
            String location = f.containsKey("file") //$NON-NLS-1$
                ? f.get("file") + ":" + f.get("line") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : (String) f.get("ownerFqn"); //$NON-NLS-1$
            sb.append("| ").append(f.get("kind")).append(" | ").append(f.get("severity")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .append(" | ").append(location).append(" | ").append(f.get("message")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" |\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }
}
