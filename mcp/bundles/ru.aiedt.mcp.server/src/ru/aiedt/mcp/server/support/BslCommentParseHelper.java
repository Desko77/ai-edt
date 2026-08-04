/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.server.Activator;

/**
 * M2: parses a BSL method's documentation comment into a structured form
 * (description, typed parameters, return types, deprecated flag), the way the
 * EDT hover does. Delegates to EDT's own doc-comment parser in
 * {@code com._1c.g5.v8.dt.bsl.documentation.comment} via reflection so the
 * caller degrades gracefully (returns {@code null}) when the API is absent on
 * the running EDT.
 * <p>
 * Tier 1 only: types are the names as WRITTEN in the comment
 * ({@code TypeSection.TypeDefinition.getTypeName()}). The heavier
 * {@code computeParameterTypes} path (real Xtext scoping) is intentionally not
 * used here.
 */
public final class BslCommentParseHelper
{
    private static final String PKG = "com._1c.g5.v8.dt.bsl.documentation.comment."; //$NON-NLS-1$

    private BslCommentParseHelper()
    {
    }

    /**
     * Parses a method's doc-comment into a JSON-ready structured map
     * {@code { description, deprecated, parameters:[{name, types, description}],
     * returns:{types} }} by the SAME path EDT's hover uses: the node-based
     * {@code parseTemplateComment(Method, oldFormat, provider)}. That path has
     * the source node offsets and therefore splits the Parameters section
     * correctly - the string-only parser collapses space-aligned parameters
     * into the first one. Returns {@code null} when the doc-comment API is
     * absent or nothing meaningful is parsed.
     *
     * @param method the BSL {@code com._1c.g5.v8.dt.bsl.model.Method} object.
     */
    public static Map<String, Object> parseMethodDoc(Object method)
    {
        if (method == null)
        {
            return null;
        }
        try
        {
            Class<?> providerCls = Class.forName(PKG + "BslMultiLineCommentDocumentationProvider"); //$NON-NLS-1$
            Object provider = providerCls.getConstructor().newInstance();
            Class<?> methodCls = Class.forName("com._1c.g5.v8.dt.bsl.model.Method"); //$NON-NLS-1$
            Class<?> utils = Class.forName(PKG + "BslCommentUtils"); //$NON-NLS-1$
            Method parse = utils.getMethod("parseTemplateComment", //$NON-NLS-1$
                methodCls, boolean.class, providerCls);
            Object doc = parse.invoke(null, method, false, provider);
            return doc != null ? walk(doc) : null;
        }
        catch (ClassNotFoundException | NoSuchMethodException e)
        {
            Activator.logWarning("BslCommentParseHelper: doc-comment API unavailable: " //$NON-NLS-1$
                + e.getMessage());
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("BslCommentParseHelper.parseMethodDoc(method) failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return null;
        }
    }

    /**
     * String-based fallback for callers without a {@code Method} object (the
     * text-search read path). Parses joined {@code //} comment lines via the
     * grammar-only parser. Note: this path collapses space-aligned parameters
     * into the first one, so prefer {@link #parseMethodDoc(Object)} whenever the
     * Method is available.
     *
     * @param commentLines the {@code //} comment lines preceding the method.
     */
    public static Map<String, Object> parseMethodDoc(List<String> commentLines)
    {
        if (commentLines == null || commentLines.isEmpty())
        {
            return null;
        }
        try
        {
            List<String> lines = new ArrayList<>();
            for (String l : commentLines)
            {
                if (l != null)
                {
                    lines.add(l.trim());
                }
            }
            Class<?> utils = Class.forName(PKG + "BslCommentUtils"); //$NON-NLS-1$
            Method parse = utils.getMethod("parseTemplateComment", List.class, boolean.class); //$NON-NLS-1$
            Object doc = parse.invoke(null, lines, false);
            return doc != null ? walk(doc) : null;
        }
        catch (ClassNotFoundException | NoSuchMethodException e)
        {
            Activator.logWarning("BslCommentParseHelper: doc-comment API unavailable: " //$NON-NLS-1$
                + e.getMessage());
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("BslCommentParseHelper.parseMethodDoc(lines) failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return null;
        }
    }

    /**
     * 1.43.x M2: signature-anchored doc-comment parser. EDT's grammar/node
     * parsers collapse space-aligned BSP "Параметры:" blocks into the first
     * parameter; this carves the block by the ACTUAL parameter names (taken from
     * the method signature), so each name reliably starts a new parameter.
     * Returns the same shape as {@link #walk} (description / deprecated /
     * parameters[{name,types,description}] / returns{types}), or {@code null}
     * when the comment has no recognizable "Параметры:" / "Parameters:" section -
     * the caller then falls back to the EDT parser (unchanged behavior).
     *
     * @param commentLines raw {@code //} comment lines preceding the method.
     * @param paramNames   the method's formal parameter names (the anchors).
     */
    public static Map<String, Object> parseDocAnchored(List<String> commentLines, List<String> paramNames)
    {
        if (commentLines == null || commentLines.isEmpty()
            || paramNames == null || paramNames.isEmpty())
        {
            return null; // no anchors - hand off to the EDT parser (no silent param drop)
        }
        // Strip "//" but KEEP the text after it (internal indentation matters).
        List<String> content = new ArrayList<>();
        for (String raw : commentLines)
        {
            if (raw == null)
            {
                continue;
            }
            int slash = raw.indexOf("//"); //$NON-NLS-1$
            content.add(slash >= 0 ? raw.substring(slash + 2) : raw);
        }
        int paramsAt = -1;
        int returnsAt = -1;
        for (int i = 0; i < content.size(); i++)
        {
            String t = content.get(i).trim();
            if (paramsAt < 0
                && (t.equalsIgnoreCase("Параметры:") || t.equalsIgnoreCase("Parameters:"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                paramsAt = i;
            }
            else if (returnsAt < 0 && (t.startsWith("Возвращаемое значение") //$NON-NLS-1$
                || t.startsWith("Returns") || t.startsWith("Return value"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                returnsAt = i;
            }
        }
        if (paramsAt < 0)
        {
            return null; // not a standard parameterized doc-comment - let EDT parse it
        }

        Map<String, Object> out = new LinkedHashMap<>();

        // Description = lines before "Параметры:" (a leading "Устарела." marks deprecation).
        StringBuilder desc = new StringBuilder();
        boolean deprecated = false;
        for (int i = 0; i < paramsAt; i++)
        {
            String t = content.get(i).trim();
            if (t.isEmpty())
            {
                continue;
            }
            if (t.startsWith("Устарел") || t.toLowerCase().startsWith("deprecated")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                deprecated = true;
            }
            appendText(desc, t);
        }
        if (desc.length() > 0)
        {
            out.put("description", desc.toString()); //$NON-NLS-1$
        }
        if (deprecated)
        {
            out.put("deprecated", Boolean.TRUE); //$NON-NLS-1$
        }

        // Parameters, anchored on the real names.
        int paramEnd = returnsAt > paramsAt ? returnsAt : content.size();
        List<Map<String, Object>> params = new ArrayList<>();
        Map<String, Object> cur = null;
        List<String> curTypes = null;
        StringBuilder curDesc = null;
        for (int i = paramsAt + 1; i < paramEnd; i++)
        {
            String t = content.get(i).trim();
            if (t.isEmpty())
            {
                continue;
            }
            String anchor = matchParamName(t, paramNames);
            if (anchor != null)
            {
                flushParam(params, cur, curTypes, curDesc);
                cur = new LinkedHashMap<>();
                cur.put("name", anchor); //$NON-NLS-1$
                curTypes = new ArrayList<>();
                curDesc = new StringBuilder();
                consumeTypeAndDesc(t.substring(anchor.length()).trim(), curTypes, curDesc, true);
            }
            else if (cur != null)
            {
                consumeTypeAndDesc(t, curTypes, curDesc, false); // continuation / alt-type
            }
        }
        flushParam(params, cur, curTypes, curDesc);
        if (!params.isEmpty())
        {
            out.put("parameters", params); //$NON-NLS-1$
        }

        // Return type(s).
        if (returnsAt >= 0)
        {
            List<String> retTypes = new ArrayList<>();
            StringBuilder retDesc = new StringBuilder();
            boolean firstRet = true;
            for (int i = returnsAt + 1; i < content.size(); i++)
            {
                String t = content.get(i).trim();
                if (!t.isEmpty())
                {
                    consumeTypeAndDesc(t, retTypes, retDesc, firstRet);
                    firstRet = false;
                }
            }
            if (!retTypes.isEmpty())
            {
                Map<String, Object> ret = new LinkedHashMap<>();
                ret.put("types", String.join(", ", retTypes)); //$NON-NLS-1$ //$NON-NLS-2$
                out.put("returns", ret); //$NON-NLS-1$
            }
        }

        return out.isEmpty() ? null : out;
    }

    /**
     * Extracts the formal parameter NAMES from a BSL method declaration starting
     * at {@code declLine1Based} (the signature may span lines). Strips the
     * {@code Знач}/{@code Val} by-value keyword and default values.
     */
    public static List<String> extractParamNames(List<String> allLines, int declLine1Based)
    {
        List<String> names = new ArrayList<>();
        if (allLines == null || declLine1Based < 1 || declLine1Based > allLines.size())
        {
            return names;
        }
        // Accumulate the declaration tracking paren depth, so a multi-line
        // signature and default values containing ')' (e.g. Тип("...")) work.
        StringBuilder decl = new StringBuilder();
        int depth = 0;
        boolean started = false;
        for (int i = declLine1Based - 1; i < allLines.size() && !(started && depth == 0); i++)
        {
            String line = allLines.get(i);
            if (line == null)
            {
                continue;
            }
            for (int j = 0; j < line.length(); j++)
            {
                char c = line.charAt(j);
                decl.append(c);
                if (c == '(')
                {
                    depth++;
                    started = true;
                }
                else if (c == ')')
                {
                    depth--;
                }
                if (started && depth == 0)
                {
                    break;
                }
            }
            decl.append(' ');
        }
        String s = decl.toString();
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        if (open < 0 || close <= open + 1)
        {
            return names;
        }
        // Split by top-level commas (skip commas inside a default's nested parens).
        String inside = s.substring(open + 1, close);
        List<String> parts = new ArrayList<>();
        int d = 0;
        int partStart = 0;
        for (int i = 0; i < inside.length(); i++)
        {
            char c = inside.charAt(i);
            if (c == '(')
            {
                d++;
            }
            else if (c == ')')
            {
                d--;
            }
            else if (c == ',' && d == 0)
            {
                parts.add(inside.substring(partStart, i));
                partStart = i + 1;
            }
        }
        parts.add(inside.substring(partStart));
        for (String part : parts)
        {
            String p = part.trim();
            if (p.isEmpty())
            {
                continue;
            }
            if (p.regionMatches(true, 0, "Знач ", 0, 5)) //$NON-NLS-1$
            {
                p = p.substring(5).trim();
            }
            else if (p.regionMatches(true, 0, "Val ", 0, 4)) //$NON-NLS-1$
            {
                p = p.substring(4).trim();
            }
            int eq = p.indexOf('=');
            if (eq >= 0)
            {
                p = p.substring(0, eq).trim();
            }
            int sp = p.indexOf(' ');
            if (sp >= 0)
            {
                p = p.substring(0, sp).trim();
            }
            if (!p.isEmpty())
            {
                names.add(p);
            }
        }
        return names;
    }

    /** The longest param name that {@code line} begins with as a whole token, or null. */
    private static String matchParamName(String line, List<String> paramNames)
    {
        String best = null;
        for (String name : paramNames)
        {
            if (name == null || name.isEmpty() || !line.startsWith(name))
            {
                continue;
            }
            if (line.length() > name.length())
            {
                char next = line.charAt(name.length());
                if (next != ' ' && next != '\t' && next != '-')
                {
                    continue; // not a token boundary (a longer identifier)
                }
            }
            if (best == null || name.length() > best.length())
            {
                best = name;
            }
        }
        return best;
    }

    /**
     * Parses a "{@code - <Type> - <desc>}" / "{@code <Type> - <desc>}" fragment:
     * adds a type-like first token to {@code types} and the rest to {@code desc}.
     * A leading '-' (first/alternative type marker) is tolerated; a plain
     * continuation line (no type token) is appended whole to the description.
     */
    private static void consumeTypeAndDesc(String fragment, List<String> types, StringBuilder desc,
        boolean allowLeadingType)
    {
        String s = fragment.trim();
        boolean dash = s.startsWith("-"); //$NON-NLS-1$
        if (dash)
        {
            s = s.substring(1).trim();
        }
        // A type is taken ONLY from an explicit type line: a '-'-prefixed alt-type
        // line, or the FIRST line of a parameter/return (allowLeadingType, where the
        // type is the leading token - params after the name strip have a '-', a
        // return line "Произвольный - ..." does not). A plain continuation sentence
        // (no dash, not first) is description-only, so a capitalised word before
        // " - " in prose is never mistaken for a type.
        if (dash || allowLeadingType)
        {
            int sep = s.indexOf(" - "); //$NON-NLS-1$
            if (sep > 0)
            {
                String type = s.substring(0, sep).trim();
                if (isTypeLike(type))
                {
                    if (types != null)
                    {
                        types.add(type);
                    }
                    appendText(desc, s.substring(sep + 3).trim());
                    return;
                }
            }
            else if (isTypeLike(s))
            {
                if (types != null)
                {
                    types.add(s); // bare type, no description ("Произвольный" / "- Строка")
                }
                return;
            }
        }
        appendText(desc, s);
    }

    /** A BSL type token is a single word, capitalised or qualified (e.g. Строка, СправочникСсылка.X). */
    private static boolean isTypeLike(String token)
    {
        if (token.isEmpty() || token.indexOf(' ') >= 0)
        {
            return false;
        }
        // BSL types are capitalised (Строка, Число, ЛюбаяСсылка, СправочникСсылка.X).
        return Character.isUpperCase(token.charAt(0));
    }

    /** Appends a space-separated fragment to a description buffer. */
    private static void appendText(StringBuilder buf, String s)
    {
        if (s == null || s.isEmpty())
        {
            return;
        }
        if (buf.length() > 0)
        {
            buf.append(' ');
        }
        buf.append(s);
    }

    /** Finalises the current parameter into {@code params}; returns null (the new "cur"). */
    private static Map<String, Object> flushParam(List<Map<String, Object>> params,
        Map<String, Object> cur, List<String> types, StringBuilder desc)
    {
        if (cur != null)
        {
            if (types != null && !types.isEmpty())
            {
                cur.put("types", String.join(", ", types)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (desc != null && desc.length() > 0)
            {
                cur.put("description", desc.toString()); //$NON-NLS-1$
            }
            params.add(cur);
        }
        return null;
    }

    /** Walks a {@code BslDocumentationComment} into the structured map. */
    private static Map<String, Object> walk(Object doc) throws Exception
    {
        Map<String, Object> out = new LinkedHashMap<>();

        String descText = descriptionText(invoke(doc, "getDescription")); //$NON-NLS-1$
        if (descText != null && !descText.isEmpty())
        {
            out.put("description", descText); //$NON-NLS-1$
        }

        if (Boolean.TRUE.equals(invoke(doc, "isDeprecated"))) //$NON-NLS-1$
        {
            out.put("deprecated", Boolean.TRUE); //$NON-NLS-1$
        }

        Object paramsSection = invoke(doc, "getParametersSection"); //$NON-NLS-1$
        if (paramsSection != null)
        {
            Object defs = invoke(paramsSection, "getParameterDefinitions"); //$NON-NLS-1$
            List<Map<String, Object>> params = new ArrayList<>();
            if (defs instanceof List)
            {
                for (Object fd : (List<?>)defs)
                {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    Object nm = invoke(fd, "getName"); //$NON-NLS-1$
                    pm.put("name", nm != null ? nm.toString() : ""); //$NON-NLS-1$ //$NON-NLS-2$
                    String types = typeNames(invoke(fd, "getTypeSections")); //$NON-NLS-1$
                    if (!types.isEmpty())
                    {
                        pm.put("types", types); //$NON-NLS-1$
                    }
                    String pdesc = descriptionText(invoke(fd, "getDescription")); //$NON-NLS-1$
                    if (pdesc != null && !pdesc.isEmpty())
                    {
                        pm.put("description", pdesc); //$NON-NLS-1$
                    }
                    params.add(pm);
                }
            }
            if (!params.isEmpty())
            {
                out.put("parameters", params); //$NON-NLS-1$
            }
        }

        Object returnSection = invoke(doc, "getReturnSection"); //$NON-NLS-1$
        if (returnSection != null)
        {
            String types = typeNames(invoke(returnSection, "getReturnTypes")); //$NON-NLS-1$
            if (!types.isEmpty())
            {
                Map<String, Object> ret = new LinkedHashMap<>();
                ret.put("types", types); //$NON-NLS-1$
                out.put("returns", ret); //$NON-NLS-1$
            }
        }

        return out.isEmpty() ? null : out;
    }

    /**
     * Joins {@code TypeDefinition.getTypeName()} across a {@code List<TypeSection>}
     * (a FieldDefinition's type sections or a ReturnSection's return types).
     */
    private static String typeNames(Object typeSections) throws Exception
    {
        if (!(typeSections instanceof List))
        {
            return ""; //$NON-NLS-1$
        }
        List<String> names = new ArrayList<>();
        for (Object ts : (List<?>)typeSections)
        {
            Object defs = invoke(ts, "getTypeDefinitions"); //$NON-NLS-1$
            if (defs instanceof List)
            {
                for (Object td : (List<?>)defs)
                {
                    Object tn = invoke(td, "getTypeName"); //$NON-NLS-1$
                    if (tn != null && !tn.toString().isEmpty())
                    {
                        names.add(tn.toString());
                    }
                }
            }
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    /** Concatenates the text of a {@code Description}'s parts (TextPart.getText()). */
    private static String descriptionText(Object description) throws Exception
    {
        if (description == null)
        {
            return null;
        }
        Object parts = invoke(description, "getParts"); //$NON-NLS-1$
        if (!(parts instanceof List))
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : (List<?>)parts)
        {
            String t = partText(part);
            if (t != null && !t.isEmpty())
            {
                if (sb.length() > 0)
                {
                    sb.append(' ');
                }
                sb.append(t.trim());
            }
        }
        return sb.toString().trim();
    }

    /** Best-effort text of an IDescriptionPart - TextPart.getText(); LinkPart/TagPart skipped. */
    private static String partText(Object part)
    {
        if (part == null)
        {
            return null;
        }
        try
        {
            Object t = part.getClass().getMethod("getText").invoke(part); //$NON-NLS-1$
            return t != null ? t.toString() : null;
        }
        catch (NoSuchMethodException e)
        {
            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Object invoke(Object target, String getter) throws Exception
    {
        if (target == null)
        {
            return null;
        }
        return target.getClass().getMethod(getter).invoke(target);
    }
}
