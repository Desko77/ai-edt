/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Two families of helpers that happen to be needed by the same callers.
 * <p>
 * <b>Envelope builders</b> ({@code build*}) produce the small JSON documents the HTTP layer sends
 * outside the tool-call path: JSON-RPC error envelopes, the server-info payload and the health
 * payload.
 * </p>
 * <p>
 * <b>Argument extractors</b> ({@code extract*}) read a tool's parameters. Tools are handed a
 * {@code Map<String, String>}, never typed JSON, so every value arrives as text: a JSON
 * {@code true} as {@code "true"}, a JSON {@code 10} as {@code "10"}, a nested object as its compact
 * JSON form, and an explicit JSON {@code null} not at all - the key is simply missing. The extractors
 * below absorb that; parsing a numeric argument by hand is a bug waiting to happen. They parse
 * through {@code double}, which is also why they still accept the {@code "10.0"} that builds before
 * the {@link GsonHolder} number policy produced.
 * </p>
 */
public final class JsonUtils
{
    private static final String KEY_JSONRPC = "jsonrpc"; //$NON-NLS-1$

    private static final String KEY_ERROR = "error"; //$NON-NLS-1$

    private static final String KEY_ID = "id"; //$NON-NLS-1$

    private static final String KEY_CODE = "code"; //$NON-NLS-1$

    private static final String KEY_MESSAGE = "message"; //$NON-NLS-1$

    private static final String KEY_NAME = "name"; //$NON-NLS-1$

    private static final String KEY_VERSION = "version"; //$NON-NLS-1$

    private static final String KEY_EDT_VERSION = "edt_version"; //$NON-NLS-1$

    private static final String KEY_PROTOCOL_VERSION = "protocol_version"; //$NON-NLS-1$

    private static final String KEY_PROTOCOL_VERSIONS = "protocol_versions"; //$NON-NLS-1$

    private static final String KEY_STATUS = "status"; //$NON-NLS-1$

    private static final String STATUS_RUNNING = "running"; //$NON-NLS-1$

    private static final String STATUS_OK = "ok"; //$NON-NLS-1$

    private static final String UNKNOWN_ERROR = "Unknown error"; //$NON-NLS-1$

    private static final String JSON_ARRAY_PREFIX = "["; //$NON-NLS-1$

    private static final String LIST_SEPARATOR = ","; //$NON-NLS-1$

    private static final char UNDERSCORE = '_';

    private JsonUtils()
    {
        // static access only
    }

    /**
     * Builds a stand-alone JSON-RPC error envelope for the HTTP layer.
     * <p>
     * A <code>null</code> request id leaves the {@code id} member out of the document entirely,
     * which is what today's clients receive for 401/403/500/503 answers.
     * </p>
     *
     * @param code one of the {@code McpServerMeta.ERROR_*} codes
     * @param message the human-readable reason; <code>null</code> becomes {@code Unknown error}
     * @param requestId the id to echo: a {@link String} or a {@link Number}; anything else,
     *            including <code>null</code>, omits the member
     * @return the JSON document
     */
    public static String buildJsonRpcError(int code, String message, Object requestId)
    {
        JsonObject error = new JsonObject();
        error.addProperty(KEY_CODE, Integer.valueOf(code));
        error.addProperty(KEY_MESSAGE, message != null ? message : UNKNOWN_ERROR);

        JsonObject envelope = new JsonObject();
        envelope.addProperty(KEY_JSONRPC, McpServerMeta.JSONRPC_VERSION);
        envelope.add(KEY_ERROR, error);
        if (requestId instanceof String)
        {
            envelope.addProperty(KEY_ID, (String)requestId);
        }
        else if (requestId instanceof Number)
        {
            envelope.addProperty(KEY_ID, (Number)requestId);
        }
        return GsonHolder.toJson(envelope);
    }

    /**
     * Builds the bare error payload used for HTTP-level failures that are not JSON-RPC at all.
     *
     * @param message the reason; <code>null</code> becomes {@code Unknown error}
     * @return a document of the shape <code>{"error":"..."}</code>
     */
    public static String buildSimpleError(String message)
    {
        JsonObject payload = new JsonObject();
        payload.addProperty(KEY_ERROR, message != null ? message : UNKNOWN_ERROR);
        return GsonHolder.toJson(payload);
    }

    /**
     * Builds the diagnostic payload served by a plain {@code GET /mcp}.
     * <p>
     * The keys are snake_case, unlike the MCP messages themselves. External health scripts read
     * them; leave them alone.
     * </p>
     *
     * @param name the server name
     * @param version the plugin version
     * @param edtVersion the version of the hosting EDT
     * @param protocolVersion the revision an {@code initialize} handshake settles on by default
     * @param protocolVersions every revision this server serves; <code>null</code> or empty leaves
     *            the list out rather than publishing an empty one
     * @return the JSON document
     */
    public static String buildServerInfo(String name, String version, String edtVersion,
        String protocolVersion, java.util.Collection<String> protocolVersions)
    {
        JsonObject payload = new JsonObject();
        payload.addProperty(KEY_NAME, name);
        payload.addProperty(KEY_VERSION, version);
        payload.addProperty(KEY_EDT_VERSION, edtVersion);
        payload.addProperty(KEY_PROTOCOL_VERSION, protocolVersion);
        // One revision was named here while five were served, which reads as "this is all it
        // speaks" - the single most misleading line in the whole endpoint, because a client
        // choosing a revision from it would never pick the current one. The scalar stays: it is
        // what a handshake settles on by default, and something out there reads it.
        if (protocolVersions != null && !protocolVersions.isEmpty())
        {
            JsonArray revisions = new JsonArray();
            for (String revision : protocolVersions)
            {
                revisions.add(revision);
            }
            payload.add(KEY_PROTOCOL_VERSIONS, revisions);
        }
        payload.addProperty(KEY_STATUS, STATUS_RUNNING);
        return GsonHolder.toJson(payload);
    }

    /**
     * Builds the minimal health payload - status plus EDT version. The live {@code GET /health} endpoint
     * builds its own, richer body (with server metrics) in {@code McpHttpEndpoint}; this helper remains
     * for tests and any caller that wants just the base fields.
     *
     * @param edtVersion the version of the hosting EDT
     * @return the JSON document
     */
    public static String buildHealthResponse(String edtVersion)
    {
        JsonObject payload = new JsonObject();
        payload.addProperty(KEY_STATUS, STATUS_OK);
        payload.addProperty(KEY_EDT_VERSION, edtVersion);
        return GsonHolder.toJson(payload);
    }

    /**
     * Reads a string argument verbatim: no trimming, no coercion of the empty string.
     *
     * @param params the tool parameters; may be <code>null</code>
     * @param argumentName the argument name; may be <code>null</code>
     * @return the value, or <code>null</code> when it was not supplied
     */
    public static String extractStringArgument(Map<String, String> params, String argumentName)
    {
        if (params == null || argumentName == null)
        {
            return null;
        }
        return params.get(argumentName);
    }

    /**
     * Reads an argument that carries a JSON object of name to scalar value.
     * <p>
     * A tool is handed text, so a nested object arrives as its compact JSON form and has to be
     * parsed back. Only primitive members are taken, each in its string form: a nested object or
     * array under a property name has no meaning for the callers of this - they set one value per
     * property - and taking it would hand the setter a string of JSON.
     * </p>
     * <p>
     * Insertion order is kept, so a refusal names the properties in the order the caller wrote
     * them rather than in whatever order a hash landed on.
     * </p>
     *
     * @param params the tool parameters; may be <code>null</code>
     * @param argumentName the argument name
     * @return the members in the order given, or an empty map when the argument is missing, empty,
     *         not an object, or carries no primitive member
     */
    public static Map<String, String> extractObjectArgument(Map<String, String> params,
        String argumentName)
    {
        String raw = extractStringArgument(params, argumentName);
        if (raw == null || raw.trim().isEmpty())
        {
            return new java.util.LinkedHashMap<>();
        }
        Map<String, String> members = new java.util.LinkedHashMap<>();
        try
        {
            JsonElement parsed = JsonParser.parseString(raw.trim());
            if (!parsed.isJsonObject())
            {
                return members;
            }
            for (Map.Entry<String, JsonElement> member : parsed.getAsJsonObject().entrySet())
            {
                if (member.getValue().isJsonPrimitive())
                {
                    members.put(member.getKey(), member.getValue().getAsString());
                }
            }
        }
        catch (RuntimeException notJson)
        {
            // A value that does not parse carries nothing this can use. The caller reports the
            // argument as unusable; guessing at a shape here would invent members nobody wrote.
            return new java.util.LinkedHashMap<>();
        }
        return members;
    }

    /**
     * Reads a list argument, accepting either spelling clients use: a JSON array
     * (<code>["a","b"]</code>) or a comma-separated string (<code>a, b</code>).
     * <p>
     * Inside a JSON array only primitive elements are taken, each in its string form; objects and
     * nested arrays are skipped. A value that opens like an array but does not parse falls back to
     * comma splitting. Comma splitting trims the parts and drops the empty ones.
     * </p>
     *
     * @param params the tool parameters; may be <code>null</code>
     * @param argumentName the argument name
     * @return the elements, or <code>null</code> when the argument is missing, empty or yields
     *         nothing; never an empty list
     */
    public static List<String> extractArrayArgument(Map<String, String> params, String argumentName)
    {
        String raw = extractStringArgument(params, argumentName);
        if (raw == null)
        {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty())
        {
            return null;
        }

        List<String> elements = new ArrayList<>();
        if (value.startsWith(JSON_ARRAY_PREFIX))
        {
            try
            {
                JsonElement parsed = JsonParser.parseString(value);
                if (parsed.isJsonArray())
                {
                    for (JsonElement element : parsed.getAsJsonArray())
                    {
                        if (element.isJsonPrimitive())
                        {
                            elements.add(element.getAsString());
                        }
                    }
                    return elements.isEmpty() ? null : elements;
                }
            }
            catch (JsonParseException e)
            {
                // Not the array it pretended to be - fall through and treat it as a plain list.
            }
        }

        for (String part : value.split(LIST_SEPARATOR))
        {
            String element = part.trim();
            if (!element.isEmpty())
            {
                elements.add(element);
            }
        }
        return elements.isEmpty() ? null : elements;
    }

    /**
     * Reads a boolean argument, substituting a default for anything it cannot make sense of.
     *
     * @param params the tool parameters; may be <code>null</code>
     * @param argumentName the argument name
     * @param defaultValue the value to use when the argument is missing or unrecognized
     * @return the boolean value
     */
    public static boolean extractBooleanArgument(Map<String, String> params, String argumentName,
        boolean defaultValue)
    {
        Boolean value = extractBooleanArgumentNullable(params, argumentName);
        return value != null ? value.booleanValue() : defaultValue;
    }

    /**
     * Reads a boolean argument while keeping "not supplied" distinguishable from "supplied false".
     * <p>
     * Recognized, case-insensitively and after trimming: {@code true} / {@code 1} / {@code yes} and
     * {@code false} / {@code 0} / {@code no}. Anything else reads as <code>null</code>.
     * </p>
     *
     * @param params the tool parameters; may be <code>null</code>
     * @param argumentName the argument name
     * @return {@link Boolean#TRUE}, {@link Boolean#FALSE}, or <code>null</code>
     */
    public static Boolean extractBooleanArgumentNullable(Map<String, String> params,
        String argumentName)
    {
        String raw = extractStringArgument(params, argumentName);
        if (raw == null)
        {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        switch (value)
        {
        case "true": //$NON-NLS-1$
        case "1": //$NON-NLS-1$
        case "yes": //$NON-NLS-1$
            return Boolean.TRUE;
        case "false": //$NON-NLS-1$
        case "0": //$NON-NLS-1$
        case "no": //$NON-NLS-1$
            return Boolean.FALSE;
        default:
            return null;
        }
    }

    /**
     * Reads a long argument. Accepts the {@code "10.0"} spelling that numeric arguments arrive in.
     *
     * @param params the tool parameters; may be <code>null</code>
     * @param argumentName the argument name
     * @param defaultValue the value to use when the argument is missing, non-numeric, fractional or
     *            out of range
     * @return the long value
     */
    public static long extractLongArgument(Map<String, String> params, String argumentName,
        long defaultValue)
    {
        Double number = parseNumericArgument(params, argumentName);
        if (number == null)
        {
            return defaultValue;
        }
        double value = number.doubleValue();
        if (!isIntegral(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE)
        {
            return defaultValue;
        }
        return (long)value;
    }

    /**
     * Reads an int argument. Accepts the {@code "10.0"} spelling that numeric arguments arrive in.
     *
     * @param params the tool parameters; may be <code>null</code>
     * @param argumentName the argument name
     * @param defaultValue the value to use when the argument is missing, non-numeric, fractional or
     *            out of range
     * @return the int value
     */
    public static int extractIntArgument(Map<String, String> params, String argumentName,
        int defaultValue)
    {
        Integer value = extractIntegerArgument(params, argumentName);
        return value != null ? value.intValue() : defaultValue;
    }

    /**
     * Reads an int argument while keeping "not supplied" distinguishable from a supplied zero.
     *
     * @param params the tool parameters; may be <code>null</code>
     * @param argumentName the argument name
     * @return the value, or <code>null</code> when it is missing, non-numeric, fractional or out of
     *         int range
     */
    public static Integer extractIntegerArgument(Map<String, String> params, String argumentName)
    {
        Double number = parseNumericArgument(params, argumentName);
        if (number == null)
        {
            return null;
        }
        double value = number.doubleValue();
        if (!isIntegral(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
        {
            return null;
        }
        return Integer.valueOf((int)value);
    }

    /**
     * Rewrites a camelCase operation token in snake_case, so that a multi-operation tool accepts
     * both spellings of the same operation ({@code textSearch} and {@code text_search}).
     * <p>
     * Known and accepted limitation: a run of capitals is split letter by letter
     * ({@code addURLTemplate} becomes {@code add_u_r_l_template}). No real operation name looks like
     * that.
     * </p>
     *
     * @param token the token to normalize; may be <code>null</code>
     * @return the snake_case form, the trimmed token when it is blank, or <code>null</code> for a
     *         <code>null</code> input
     */
    public static String normalizeOperationToken(String token)
    {
        if (token == null)
        {
            return null;
        }
        String value = token.trim();
        if (value.isEmpty())
        {
            return value;
        }

        StringBuilder normalized = new StringBuilder(value.length() * 2);
        for (int i = 0; i < value.length(); i++)
        {
            char symbol = value.charAt(i);
            if (Character.isUpperCase(symbol))
            {
                int length = normalized.length();
                if (length > 0 && normalized.charAt(length - 1) != UNDERSCORE)
                {
                    normalized.append(UNDERSCORE);
                }
                normalized.append(Character.toLowerCase(symbol));
            }
            else
            {
                normalized.append(symbol);
            }
        }
        return normalized.toString();
    }

    /**
     * Reads an argument as a double, which is the common ground of every numeric spelling a tool can
     * receive.
     *
     * @param params the tool parameters; may be <code>null</code>
     * @param argumentName the argument name
     * @return the number, or <code>null</code> when the argument is missing, empty or not numeric
     */
    private static Double parseNumericArgument(Map<String, String> params, String argumentName)
    {
        String raw = extractStringArgument(params, argumentName);
        if (raw == null)
        {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty())
        {
            return null;
        }
        try
        {
            return Double.valueOf(value);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /**
     * Tells whether a double carries a usable whole number. NaN fails the equality test and the
     * infinities are ruled out explicitly, so neither can reach a narrowing cast.
     *
     * @param value the value to test
     * @return <code>true</code> when the value is finite and has no fractional part
     */
    private static boolean isIntegral(double value)
    {
        return value == Math.floor(value) && !Double.isInfinite(value);
    }
}
