/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.aiedt.mcp.server.wire.JsonUtils;

/**
 * Decides which part of a project a scan is allowed to walk.
 * <p>
 * The selectors name the area when {@code scope} is absent: {@code moduleFqn} a module, that plus
 * {@code methodName} a method, {@code subsystemName} a subsystem, and nothing the whole project.
 * When {@code scope} is present the selectors have to agree with it. A disagreement, an unsupported
 * scope word, or {@code methodName} without {@code moduleFqn} is a refusal that names what was
 * wrong. The scan that follows this decision must not walk the project anyway: an empty filter is
 * not a reason to look at everything.
 * </p>
 */
public final class WalkNarrowing
{
    /** The whole project. */
    public static final String PROJECT = "project"; //$NON-NLS-1$

    /** One module. */
    public static final String MODULE = "module"; //$NON-NLS-1$

    /** One method of one module. */
    public static final String METHOD = "method"; //$NON-NLS-1$

    /** One subsystem, including the composition of the subsystems nested in it. */
    public static final String SUBSYSTEM = "subsystem"; //$NON-NLS-1$

    /**
     * Which selectors a tool accepts. A selector it does not accept is ignored, so a parameter that
     * belongs to a neighbouring operation on the same facade does not change this call.
     */
    public static final class Selectors
    {
        /** {@code detect_query_anti_patterns} and {@code find_rls_violations}. */
        public static final Selectors MODULE_AND_METHOD = new Selectors(true, true, false);

        /** {@code project_metrics}. */
        public static final Selectors SUBSYSTEM_ONLY = new Selectors(false, false, true);

        /** {@code sensitive_data_scan}. */
        public static final Selectors MODULE_AND_SUBSYSTEM = new Selectors(true, false, true);

        private final boolean module;

        private final boolean method;

        private final boolean subsystem;

        private Selectors(boolean module, boolean method, boolean subsystem)
        {
            this.module = module;
            this.method = method;
            this.subsystem = subsystem;
        }
    }

    /**
     * Where the scan walks, or why it must not start.
     */
    public static final class Decision
    {
        private final String refusal;

        private final String area;

        private final String moduleFqn;

        private final String methodName;

        private final String subsystemName;

        private Decision(String refusal, String area, String moduleFqn, String methodName,
            String subsystemName)
        {
            this.refusal = refusal;
            this.area = area;
            this.moduleFqn = moduleFqn;
            this.methodName = methodName;
            this.subsystemName = subsystemName;
        }

        /**
         * @return the refusal, or <code>null</code> when the call may walk
         */
        public String refusal()
        {
            return refusal;
        }

        /**
         * @return whether the call must be refused
         */
        public boolean refused()
        {
            return refusal != null;
        }

        /**
         * @return {@code project}, {@code module}, {@code method} or {@code subsystem}; <code>null</code>
         *         when the call was refused
         */
        public String area()
        {
            return area;
        }

        /**
         * @return the module the walk is limited to, or <code>null</code>
         */
        public String moduleFqn()
        {
            return moduleFqn;
        }

        /**
         * @return the method the walk is limited to, or <code>null</code>
         */
        public String methodName()
        {
            return methodName;
        }

        /**
         * @return the subsystem the walk is limited to, or <code>null</code>
         */
        public String subsystemName()
        {
            return subsystemName;
        }
    }

    /**
     * The lines of one method, both ends inclusive and one-based, the same numbering the scanners
     * put on a finding.
     */
    public static final class MethodSpan
    {
        private final int startLine;

        private final int endLine;

        private MethodSpan(int startLine, int endLine)
        {
            this.startLine = startLine;
            this.endLine = endLine;
        }

        /**
         * @param line a one-based line
         * @return whether the line sits inside this method
         */
        public boolean contains(int line)
        {
            return line >= startLine && line <= endLine;
        }

        /**
         * @return the first line of the method
         */
        public int startLine()
        {
            return startLine;
        }

        /**
         * @return the last line of the method
         */
        public int endLine()
        {
            return endLine;
        }
    }

    // UNICODE_CHARACTER_CLASS because the name after the keyword is a \w, and a Cyrillic method
    // name is a word. UNICODE_CASE because the keyword is written in either case.
    private static final Pattern METHOD_START = Pattern.compile(
        "^\\s*(Процедура|Функция|Procedure|Function)\\s+([\\w]+)", //$NON-NLS-1$
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            | Pattern.UNICODE_CHARACTER_CLASS);

    private WalkNarrowing()
    {
        // utility
    }

    /**
     * Decides the walk from the call.
     *
     * @param params the call arguments
     * @param supported the scope words this tool accepts, in the order a refusal should list them
     * @param selectors which selectors this tool accepts
     * @return the decision; never <code>null</code>
     */
    public static Decision decide(Map<String, String> params, List<String> supported, Selectors selectors)
    {
        String scope = present(JsonUtils.extractStringArgument(params, "scope")); //$NON-NLS-1$
        String moduleFqn = selectors.module
            ? present(JsonUtils.extractStringArgument(params, "moduleFqn")) : null; //$NON-NLS-1$
        String methodName = selectors.method
            ? present(JsonUtils.extractStringArgument(params, "methodName")) : null; //$NON-NLS-1$
        String subsystemName = selectors.subsystem
            ? present(JsonUtils.extractStringArgument(params, "subsystemName")) : null; //$NON-NLS-1$

        if (moduleFqn != null && subsystemName != null)
        {
            return refuse("moduleFqn '" + moduleFqn + "' conflicts with subsystemName '" //$NON-NLS-1$ //$NON-NLS-2$
                + subsystemName + "': pass one of them."); //$NON-NLS-1$
        }
        if (scope == null)
        {
            if (methodName != null && moduleFqn == null)
            {
                return refuse("methodName '" + methodName //$NON-NLS-1$
                    + "' requires moduleFqn: a method is named inside its module."); //$NON-NLS-1$
            }
            if (methodName != null)
            {
                return accept(METHOD, moduleFqn, methodName, null);
            }
            if (moduleFqn != null)
            {
                return accept(MODULE, moduleFqn, null, null);
            }
            if (subsystemName != null)
            {
                return accept(SUBSYSTEM, null, null, subsystemName);
            }
            return accept(PROJECT, null, null, null);
        }
        String canonical = scope.toLowerCase(Locale.ROOT);
        if (!contains(supported, canonical))
        {
            return refuse(TextSuggest.invalidValue("scope", scope, supported)); //$NON-NLS-1$
        }
        switch (canonical)
        {
            case PROJECT:
                return projectScope(moduleFqn, methodName, subsystemName);
            case MODULE:
                return moduleScope(moduleFqn, methodName, subsystemName);
            case METHOD:
                return methodScope(moduleFqn, methodName);
            case SUBSYSTEM:
                return subsystemScope(subsystemName, moduleFqn, methodName);
            default:
                return refuse(TextSuggest.invalidValue("scope", scope, supported)); //$NON-NLS-1$
        }
    }

    /**
     * Finds a method in module text.
     * <p>
     * The span runs from the method's header up to the line before the next method, so a finding in
     * a neighbouring method is outside it. Matching ignores case, the way the language does.
     * </p>
     *
     * @param content the module text; may be <code>null</code>
     * @param methodName the method; may be <code>null</code>
     * @return the span, or <code>null</code> when the module has no such method
     */
    public static MethodSpan locateMethod(String content, String methodName)
    {
        if (content == null || methodName == null || methodName.isEmpty())
        {
            return null;
        }
        List<String> names = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        Matcher matcher = METHOD_START.matcher(content);
        while (matcher.find())
        {
            names.add(matcher.group(2));
            lines.add(Integer.valueOf(lineAt(content, matcher.start())));
        }
        int found = -1;
        for (int i = 0; i < names.size(); i++)
        {
            if (methodName.equalsIgnoreCase(names.get(i)))
            {
                found = i;
                break;
            }
        }
        if (found < 0)
        {
            return null;
        }
        int start = lines.get(found).intValue();
        int end = found + 1 < lines.size()
            ? lines.get(found + 1).intValue() - 1
            : lineAt(content, Math.max(0, content.length() - 1));
        if (end < start)
        {
            end = start;
        }
        return new MethodSpan(start, end);
    }

    /**
     * The refusal a scan gives when the method the caller named is not in the module.
     *
     * @param methodName the method
     * @param moduleFqn the module
     * @return the refusal
     */
    public static String methodNotFound(String methodName, String moduleFqn)
    {
        return "Method '" + methodName + "' was not found in module '" + moduleFqn + "'."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static Decision projectScope(String moduleFqn, String methodName, String subsystemName)
    {
        String rejected = rejectedSelectors(moduleFqn, methodName, subsystemName);
        if (rejected != null)
        {
            return refuse("scope=project does not accept " + rejected + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return accept(PROJECT, null, null, null);
    }

    private static Decision moduleScope(String moduleFqn, String methodName, String subsystemName)
    {
        List<String> problems = new ArrayList<>();
        if (moduleFqn == null)
        {
            problems.add("scope=module requires moduleFqn"); //$NON-NLS-1$
        }
        if (methodName != null)
        {
            problems.add("scope=module does not accept methodName '" + methodName + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (subsystemName != null)
        {
            problems.add("scope=module does not accept subsystemName '" + subsystemName + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!problems.isEmpty())
        {
            return refuse(String.join("; ", problems) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return accept(MODULE, moduleFqn, null, null);
    }

    private static Decision methodScope(String moduleFqn, String methodName)
    {
        List<String> problems = new ArrayList<>();
        if (moduleFqn == null)
        {
            problems.add("scope=method requires moduleFqn"); //$NON-NLS-1$
        }
        if (methodName == null)
        {
            problems.add("scope=method requires methodName"); //$NON-NLS-1$
        }
        if (!problems.isEmpty())
        {
            return refuse(String.join("; ", problems) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return accept(METHOD, moduleFqn, methodName, null);
    }

    private static Decision subsystemScope(String subsystemName, String moduleFqn, String methodName)
    {
        List<String> problems = new ArrayList<>();
        if (subsystemName == null)
        {
            problems.add("scope=subsystem requires subsystemName"); //$NON-NLS-1$
        }
        if (moduleFqn != null)
        {
            problems.add("scope=subsystem does not accept moduleFqn '" + moduleFqn + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (methodName != null)
        {
            problems.add("scope=subsystem does not accept methodName '" + methodName + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!problems.isEmpty())
        {
            return refuse(String.join("; ", problems) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return accept(SUBSYSTEM, null, null, subsystemName);
    }

    private static String rejectedSelectors(String moduleFqn, String methodName, String subsystemName)
    {
        List<String> parts = new ArrayList<>();
        if (moduleFqn != null)
        {
            parts.add("moduleFqn '" + moduleFqn + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (methodName != null)
        {
            parts.add("methodName '" + methodName + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (subsystemName != null)
        {
            parts.add("subsystemName '" + subsystemName + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (parts.isEmpty())
        {
            return null;
        }
        return String.join(", ", parts); //$NON-NLS-1$
    }

    private static boolean contains(Collection<String> supported, String canonical)
    {
        if (supported == null)
        {
            return false;
        }
        for (String word : supported)
        {
            if (canonical.equalsIgnoreCase(word))
            {
                return true;
            }
        }
        return false;
    }

    private static String present(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Decision accept(String area, String moduleFqn, String methodName, String subsystemName)
    {
        return new Decision(null, area, moduleFqn, methodName, subsystemName);
    }

    private static Decision refuse(String refusal)
    {
        return new Decision(refusal, null, null, null, null);
    }

    private static int lineAt(String text, int offset)
    {
        int line = 1;
        int max = Math.min(Math.max(offset, 0), text.length());
        for (int i = 0; i < max; i++)
        {
            if (text.charAt(i) == '\n')
            {
                line++;
            }
        }
        return line;
    }
}
