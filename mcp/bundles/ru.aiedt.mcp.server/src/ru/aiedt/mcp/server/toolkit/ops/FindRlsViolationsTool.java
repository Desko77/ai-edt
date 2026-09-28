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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Role;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.RoleRightsAnalyzer;
import ru.aiedt.mcp.server.support.UiSync;
import ru.aiedt.mcp.server.support.WalkNarrowing;

/**
 * Static analyzer of the privileged-mode bypass: {@code УстановитьПривилегированныйРежим(Истина)}
 * without a reset in the same method (kind {@code PRIVILEGED_MODE}). The answer also says whether
 * any role's {@code Rights.rights} restricts rows ({@code noRlsConfigured}), or why that could not
 * be determined.
 */
public class FindRlsViolationsTool implements IMcpTool
{
    public static final String NAME = "find_rls_violations"; //$NON-NLS-1$

    private static final java.util.List<String> SCOPES = java.util.List.of(
        WalkNarrowing.PROJECT, WalkNarrowing.MODULE, WalkNarrowing.METHOD);

    private static final Pattern PRIVILEGED_MODE_SET = Pattern.compile(
        "УстановитьПривилегированныйРежим\\s*\\(\\s*Истина\\s*\\)|" //$NON-NLS-1$
            + "SetPrivilegedMode\\s*\\(\\s*True\\s*\\)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern PRIVILEGED_MODE_RESET = Pattern.compile(
        "УстановитьПривилегированныйРежим\\s*\\(\\s*Ложь\\s*\\)|" //$NON-NLS-1$
            + "SetPrivilegedMode\\s*\\(\\s*False\\s*\\)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern PROC_BOUNDARY = Pattern.compile(
        "^\\s*(Процедура|Функция|Procedure|Function)\\s+\\w+", //$NON-NLS-1$
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern PROC_END = Pattern
        .compile("^\\s*(КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)", //$NON-NLS-1$
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `security_audit` `operation=find_rls_violations`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Finds SetPrivilegedMode(True) without a reset in the same method (kind PRIVILEGED_MODE) " //$NON-NLS-1$
            + "and reports whether any role's Rights.rights restricts rows (noRlsConfigured, " //$NON-NLS-1$
            + "rlsNotDetermined). Intra-method analysis, no call graph."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", //$NON-NLS-1$
                "project | module | method, and absent, the selectors decide the area.") //$NON-NLS-1$
            .stringProperty("moduleFqn", //$NON-NLS-1$
                "Module FQN, for example CommonModule.Sales.") //$NON-NLS-1$
            .stringProperty("methodName", "Method inside moduleFqn.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("roleName", "Decide noRlsConfigured from this role only (default: every role)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("severity_filter", "info | warning | error | all (default warning)") //$NON-NLS-1$ //$NON-NLS-2$
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
            WalkNarrowing.Selectors.MODULE_AND_METHOD);
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
                    return scan(project, params, decision);
                }
                catch (Exception e)
                {
                    Activator.logError("find_rls_violations error", e); //$NON-NLS-1$
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
    String scan(IProject project, Map<String, String> params, WalkNarrowing.Decision decision) throws Exception
    {
        String severity = orDefault(JsonUtils.extractStringArgument(params, "severity_filter"), //$NON-NLS-1$
            "warning"); //$NON-NLS-1$
        String format = orDefault(JsonUtils.extractStringArgument(params, "format"), "json"); //$NON-NLS-1$ //$NON-NLS-2$
        String roleName = JsonUtils.extractStringArgument(params, "roleName"); //$NON-NLS-1$

        WatchForCancel watch = WatchForCancel.begin();
        // Asked before the predicate, and never inside it: checkNoRls answers whether ANY
        // object has a rule, and a walk stopped half way would answer "none" when it
        // merely stopped looking. Skipped entirely when the operator has already cancelled,
        // which leaves noRlsConfigured absent from the answer rather than false.
        // A narrowed walk still asks it of the project: the flag says whether RLS is configured
        // at all, which a single module cannot answer, and it is not a finding.
        RlsVerdict rls = watch.raised()
            ? RlsVerdict.undetermined("the call was cancelled before the rights were read", List.of()) //$NON-NLS-1$
            : checkNoRls(project, roleName);
        if (rls.refusal != null)
        {
            return rls.refusal;
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        String methodMissing = walkFiles(project, decision, watch, findings);
        if (methodMissing != null)
        {
            return ToolResult.error(methodMissing).toJson();
        }

        // Severity filter
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> f : findings)
        {
            String sev = (String) f.get("severity"); //$NON-NLS-1$
            if (matchesSeverity(sev, severity))
            {
                filtered.add(f);
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("findings", filtered.size()); //$NON-NLS-1$
        ToolResult tr = ToolResult.success();
        tr.put("scope", decision.area()); //$NON-NLS-1$
        tr.put("cancelled", watch.note("files")); //$NON-NLS-1$
        rls.putInto(tr);
        if ("markdown".equalsIgnoreCase(format)) //$NON-NLS-1$
        {
            return tr.put("statistics", stats) //$NON-NLS-1$
                .put("text", renderMarkdown(filtered, stats, watch.note("files"))) //$NON-NLS-1$
                .toJson();
        }
        return tr.put("statistics", stats) //$NON-NLS-1$
            .put("issues", filtered) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Whether any role restricts rows, read from the project's configuration.
     *
     * @param project the project
     * @param roleName the only role to read, or {@code null} for every role
     * @return the verdict
     */
    private RlsVerdict checkNoRls(IProject project, String roleName)
    {
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        if (provider == null)
        {
            return RlsVerdict.undetermined("the configuration provider is not available", List.of()); //$NON-NLS-1$
        }
        Configuration config = provider.getConfiguration(project);
        if (config == null)
        {
            return RlsVerdict.undetermined("the project's configuration could not be read", List.of()); //$NON-NLS-1$
        }
        return rlsOf(project, config, roleName);
    }

    /**
     * Whether any role restricts rows. A role whose {@code Rights.rights} cannot be read is named,
     * and while such a role remains, "no role restricts rows" is not answered: the restriction may
     * be in that file.
     *
     * @param project the project the rights files are read from
     * @param config the configuration
     * @param roleName the only role to read, or {@code null} or empty for every role
     * @return the verdict; a refusal when {@code roleName} is not a role of the configuration
     */
    static RlsVerdict rlsOf(IProject project, Configuration config, String roleName)
    {
        List<com._1c.g5.v8.dt.metadata.mdclass.MdObject> objects = collectAllObjects(config);
        Collection<Role> roles;
        if (roleName != null && !roleName.isEmpty())
        {
            Role role = RoleRightsAnalyzer.findRole(config, roleName);
            if (role == null)
            {
                return RlsVerdict.refused(AuditRoleRightsTool.errorRoleNotFound(config, roleName));
            }
            roles = java.util.Collections.singletonList(role);
        }
        else
        {
            roles = RoleRightsAnalyzer.listRoles(config);
            if (roles.isEmpty())
            {
                return RlsVerdict.undetermined("the configuration lists no roles", List.of()); //$NON-NLS-1$
            }
        }
        List<String> unread = new ArrayList<>();
        for (Role role : roles)
        {
            RoleRightsAnalyzer.RightsTable table;
            try
            {
                // The rights FILE, not the role model: the model answers empty on EDT 2026.
                table = RoleRightsAnalyzer.analyze(project, role, objects);
            }
            catch (RuntimeException e)
            {
                Activator.logDebug("rights of " + role.getName() + " not read: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                unread.add(role.getName());
                continue;
            }
            for (Boolean restricted : table.hasRls.values())
            {
                if (Boolean.TRUE.equals(restricted))
                {
                    return new RlsVerdict(Boolean.FALSE, null, List.of(), null);
                }
            }
        }
        if (unread.isEmpty())
        {
            return new RlsVerdict(Boolean.TRUE, null, List.of(), null);
        }
        if (unread.size() == roles.size())
        {
            return RlsVerdict.undetermined("no role's Rights.rights could be read", unread); //$NON-NLS-1$
        }
        return RlsVerdict.undetermined("the Rights.rights of " + unread.size() //$NON-NLS-1$
            + " roles could not be read, and a restriction may be there", unread); //$NON-NLS-1$
    }

    /** What the rights files say about row-level security. */
    static final class RlsVerdict
    {
        /** At most this many unread role names go into the answer. */
        private static final int NAMED_LIMIT = 20;

        /** {@code TRUE} no role restricts rows, {@code FALSE} some role does, {@code null} not determined. */
        final Boolean noRlsConfigured;

        /** Why {@link #noRlsConfigured} is {@code null}; {@code null} otherwise. */
        final String reason;

        /** The roles whose {@code Rights.rights} could not be read. */
        final List<String> unreadRoles;

        /** The JSON refusal of the call, or {@code null}. */
        final String refusal;

        /**
         * @param noRlsConfigured the answer, or {@code null} when not determined
         * @param reason why the answer is not determined
         * @param unreadRoles the roles whose rights could not be read
         * @param refusal the JSON refusal, or {@code null}
         */
        RlsVerdict(Boolean noRlsConfigured, String reason, List<String> unreadRoles, String refusal)
        {
            this.noRlsConfigured = noRlsConfigured;
            this.reason = reason;
            this.unreadRoles = unreadRoles;
            this.refusal = refusal;
        }

        /**
         * A verdict that could not be reached.
         *
         * @param reason why
         * @param unreadRoles the roles whose rights could not be read
         * @return the verdict
         */
        static RlsVerdict undetermined(String reason, List<String> unreadRoles)
        {
            return new RlsVerdict(null, reason, unreadRoles, null);
        }

        /**
         * A verdict that refuses the call.
         *
         * @param refusal the JSON refusal
         * @return the verdict
         */
        static RlsVerdict refused(String refusal)
        {
            return new RlsVerdict(null, null, List.of(), refusal);
        }

        /**
         * Writes the verdict into the answer: {@code noRlsConfigured} when no role restricts rows,
         * nothing when some role does, {@code rlsNotDetermined} with the reason and the unread roles
         * otherwise.
         *
         * @param answer the answer
         */
        void putInto(ToolResult answer)
        {
            if (Boolean.TRUE.equals(noRlsConfigured))
            {
                answer.put("noRlsConfigured", true); //$NON-NLS-1$
                return;
            }
            if (noRlsConfigured != null)
            {
                return;
            }
            answer.put("rlsNotDetermined", true) //$NON-NLS-1$
                .put("rlsNote", "Whether row-level security is configured could not be determined: " //$NON-NLS-1$ //$NON-NLS-2$
                    + reason + "."); //$NON-NLS-1$
            if (!unreadRoles.isEmpty())
            {
                answer.put("rightsNotRead", unreadRoles.subList(0, Math.min(NAMED_LIMIT, unreadRoles.size()))) //$NON-NLS-1$
                    .put("rightsNotReadCount", unreadRoles.size()); //$NON-NLS-1$
            }
        }
    }

    /**
     * Every metadata object of the configuration.
     *
     * @param cfg the configuration
     * @return the objects
     */
    private static List<com._1c.g5.v8.dt.metadata.mdclass.MdObject> collectAllObjects(Configuration cfg)
    {
        List<com._1c.g5.v8.dt.metadata.mdclass.MdObject> all = new ArrayList<>();
        for (java.lang.reflect.Method m : cfg.getClass().getMethods())
        {
            if (m.getParameterCount() != 0 || !m.getName().startsWith("get") //$NON-NLS-1$
                || "getClass".equals(m.getName())) //$NON-NLS-1$
            {
                continue;
            }
            if (!java.util.List.class.isAssignableFrom(m.getReturnType()))
            {
                continue;
            }
            try
            {
                Object value = m.invoke(cfg);
                if (value instanceof java.util.List)
                {
                    for (Object item : (java.util.List<?>) value)
                    {
                        if (item instanceof com._1c.g5.v8.dt.metadata.mdclass.MdObject)
                        {
                            all.add((com._1c.g5.v8.dt.metadata.mdclass.MdObject) item);
                        }
                    }
                }
            }
            catch (Throwable ignored)
            {
                // skip inaccessible
            }
        }
        return all;
    }

    /**
     * Walks the files the decision names.
     *
     * @param project the project
     * @param decision the accepted walk
     * @param watch the cancel flag
     * @param findings where findings go
     * @return a refusal when the named method is not in the module, or <code>null</code> when the
     *         walk ran
     */
    private String walkFiles(IProject project, WalkNarrowing.Decision decision, WatchForCancel watch,
        List<Map<String, Object>> findings) throws Exception
    {
        if (WalkNarrowing.PROJECT.equals(decision.area()))
        {
            org.eclipse.core.resources.IResourceVisitor visitor = resource -> {
                if (resource instanceof IFile && resource.getName().endsWith(".bsl")) //$NON-NLS-1$
                {
                    if (watch.stopHere())
                    {
                        // Thrown rather than answered false: false prunes this one resource
                        // and leaves the rest of the project to walk.
                        throw new org.eclipse.core.runtime.OperationCanceledException();
                    }
                    scanFile((IFile) resource, null, findings);
                }
                return true;
            };
            try
            {
                project.accept(visitor, IResource.DEPTH_INFINITE, IResource.NONE);
            }
            catch (org.eclipse.core.runtime.OperationCanceledException stopped)
            {
                // Caught here so the findings collected so far are kept; the answer says
                // they are part of the work rather than all of it.
            }
            return null;
        }
        BslModuleAccess.ModulePathResolution resolution =
            BslModuleAccess.resolveModulePath(project, decision.moduleFqn());
        if (!resolution.isResolved())
        {
            return resolution.getHint();
        }
        IFile module = project.getFile(new Path("src").append(resolution.getPath())); //$NON-NLS-1$
        if (!module.exists())
        {
            return "Module '" + decision.moduleFqn() + "' was not found."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        // The same boundary the project walk uses, asked before this one module is read.
        // scope=module and scope=method otherwise scan the file after the operator has cancelled.
        if (watch.stopHere())
        {
            return null;
        }
        String onlyMethod = WalkNarrowing.METHOD.equals(decision.area()) ? decision.methodName() : null;
        boolean found = scanFile(module, onlyMethod, findings);
        if (onlyMethod != null && !found)
        {
            return WalkNarrowing.methodNotFound(onlyMethod, decision.moduleFqn());
        }
        return null;
    }

    /**
     * Scans one module.
     *
     * @param file the module
     * @param onlyMethod a method to limit the scan to, or <code>null</code> for every method
     * @param findings where findings go
     * @return whether {@code onlyMethod} was seen; <code>true</code> when no method was asked for
     */
    private boolean scanFile(IFile file, String onlyMethod, List<Map<String, Object>> findings)
    {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
        {
            StringBuilder buffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                buffer.append(line).append('\n');
            }
            String content = buffer.toString();
            // Walk method by method, count privileged set / reset
            Matcher proc = PROC_BOUNDARY.matcher(content);
            int lastIndex = 0;
            String currentMethod = null;
            int currentLine = 0;
            boolean seen = onlyMethod == null;
            while (proc.find())
            {
                if (currentMethod != null && wanted(onlyMethod, currentMethod))
                {
                    String body = content.substring(lastIndex, proc.start());
                    checkPrivilegedMode(file, currentMethod, body, currentLine, findings);
                }
                currentMethod = methodNameFromHeader(proc.group(0));
                if (wanted(onlyMethod, currentMethod))
                {
                    seen = true;
                }
                currentLine = lineAt(content, proc.start());
                lastIndex = proc.end();
            }
            if (currentMethod != null && wanted(onlyMethod, currentMethod))
            {
                String body = content.substring(lastIndex);
                checkPrivilegedMode(file, currentMethod, body, currentLine, findings);
            }
            return seen;
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to scan " + file.getFullPath() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            // A file that could not be read did not contain the method. When no method was asked
            // for, the walk of the other files still stands.
            return onlyMethod == null;
        }
    }

    private static boolean wanted(String onlyMethod, String currentMethod)
    {
        return onlyMethod == null || onlyMethod.equalsIgnoreCase(currentMethod);
    }

    private static String methodNameFromHeader(String header)
    {
        String[] parts = header.trim().split("\\s+"); //$NON-NLS-1$
        if (parts.length >= 2)
        {
            String name = parts[1];
            int paren = name.indexOf('(');
            return paren > 0 ? name.substring(0, paren) : name;
        }
        return "?"; //$NON-NLS-1$
    }

    private void checkPrivilegedMode(IFile file, String methodName, String body, int startLine,
        List<Map<String, Object>> findings)
    {
        Matcher endMatcher = PROC_END.matcher(body);
        String activeBody = endMatcher.find() ? body.substring(0, endMatcher.start()) : body;
        Matcher setMatcher = PRIVILEGED_MODE_SET.matcher(activeBody);
        int setCount = 0;
        int firstSetLine = 0;
        while (setMatcher.find())
        {
            setCount++;
            if (setCount == 1)
            {
                firstSetLine = startLine + countNewlines(activeBody, setMatcher.start());
            }
        }
        Matcher resetMatcher = PRIVILEGED_MODE_RESET.matcher(activeBody);
        int resetCount = 0;
        while (resetMatcher.find())
        {
            resetCount++;
        }
        if (setCount > 0 && resetCount < setCount)
        {
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("kind", "PRIVILEGED_MODE"); //$NON-NLS-1$ //$NON-NLS-2$
            finding.put("severity", "ERROR"); //$NON-NLS-1$ //$NON-NLS-2$
            finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
            finding.put("line", firstSetLine); //$NON-NLS-1$
            finding.put("method", methodName); //$NON-NLS-1$
            finding.put("setCount", setCount); //$NON-NLS-1$
            finding.put("resetCount", resetCount); //$NON-NLS-1$
            finding.put("message", //$NON-NLS-1$
                "PRIVILEGED_MODE set without reset in method " + methodName); //$NON-NLS-1$
            findings.add(finding);
        }
    }

    private static int countNewlines(String s, int upTo)
    {
        int count = 0;
        int max = Math.min(upTo, s.length());
        for (int i = 0; i < max; i++)
        {
            if (s.charAt(i) == '\n')
            {
                count++;
            }
        }
        return count;
    }

    private static int lineAt(String content, int offset)
    {
        return 1 + countNewlines(content, offset);
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
            default:
                return true;
        }
    }

    private static String renderMarkdown(List<Map<String, Object>> findings,
        Map<String, Object> stats, String cancelled)
    {
        StringBuilder sb = new StringBuilder("# RLS Violations\n\n"); //$NON-NLS-1$
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
                : "No violations.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        sb.append("| Kind | Severity | File:Line | Method | Message |\n"); //$NON-NLS-1$
        sb.append("|---|---|---|---|---|\n"); //$NON-NLS-1$
        for (Map<String, Object> f : findings)
        {
            sb.append("| ").append(f.get("kind")).append(" | ").append(f.get("severity")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .append(" | ").append(f.get("file")).append(":").append(f.get("line")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .append(" | ").append(f.getOrDefault("method", "")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" | ").append(f.get("message")).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return sb.toString();
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }
}
