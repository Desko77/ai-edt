/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.oform.BraceTree;
import ru.aiedt.mcp.server.support.oform.OrdinaryFormFile;
import ru.aiedt.mcp.server.support.oform.OrdinaryFormLocator;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * {@code ordinary_forms} - the ordinary (non-managed) forms of a project, which EDT itself does
 * not open: their {@code Form.oform} containers read from disk.
 *
 * <p>{@code audit} walks every ordinary form of the project and reports what the plugin can say
 * about it without the platform: the layout version each form was last saved with, the entries
 * its container holds, and whether the layout text survives a read and a write byte for byte -
 * the property every later edit of these files rests on. A form that fails that check is named,
 * so the number of forms this server may touch is a measured one.</p>
 */
public class OrdinaryFormsTool
    implements IMcpTool
{
    private static final int DEFAULT_LIMIT = 20;

    @Override
    public String getName()
    {
        return "ordinary_forms"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Ordinary (non-managed) forms of a project, read from their Form.oform containers - " //$NON-NLS-1$
            + "the forms EDT does not open. Operations: audit (every ordinary form: layout version, " //$NON-NLS-1$
            + "container entries, module size, and whether the layout text round-trips byte for byte " //$NON-NLS-1$
            + "through this server's reader and writer; mismatches are named), help."; //$NON-NLS-1$
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
            .stringProperty("operation", "audit | help (required)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "Name of the EDT project whose ordinary forms to read.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", //$NON-NLS-1$
                "audit: how many mismatching or unreadable forms to name (default 20).") //$NON-NLS-1$
            .booleanProperty("details", //$NON-NLS-1$
                "audit: list every form with its version and sizes, capped by limit (default false).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.trim().isEmpty())
        {
            return ToolResult.error("operation is required: audit | help").toJson(); //$NON-NLS-1$
        }
        String op = operation.trim().toLowerCase(Locale.ROOT);
        if (op.equals("help")) //$NON-NLS-1$
        {
            return help();
        }
        if (!op.equals("audit")) //$NON-NLS-1$
        {
            return ToolResult.error("Unknown operation: " + operation + ". Known: audit, help.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName must be provided").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        int limit = Math.max(1, JsonUtils.extractIntArgument(params, "limit", DEFAULT_LIMIT)); //$NON-NLS-1$
        boolean details = JsonUtils.extractBooleanArgument(params, "details", false); //$NON-NLS-1$
        try
        {
            return audit(project, limit, details);
        }
        catch (IOException e)
        {
            Activator.logError("ordinary_forms audit failed", e); //$NON-NLS-1$
            return ToolResult.error("The audit could not read the project: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private String help()
    {
        return ToolResult.success()
            .put("tool", getName()) //$NON-NLS-1$
            .put("operations", List.of( //$NON-NLS-1$
                "audit - every ordinary form of the project: layout version, container entries, module size, " //$NON-NLS-1$
                    + "byte-for-byte round trip of the layout text; details=true lists forms, limit caps the lists", //$NON-NLS-1$
                "help - this catalogue")) //$NON-NLS-1$
            .put("whatAnOrdinaryFormIs", "A form of the thick client's ordinary application mode. EDT keeps it " //$NON-NLS-1$ //$NON-NLS-2$
                + "as Form.oform - a platform container with two text entries, 'form' (the layout in the " //$NON-NLS-1$
                + "platform's brace format) and 'module' (BSL) - and does not open it in any editor.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Reads every ordinary form and reports what survived the round trip.
     */
    private String audit(IProject project, int limit, boolean details) throws IOException
    {
        List<OrdinaryFormLocator.Located> forms = OrdinaryFormLocator.locate(project);
        Map<String, Integer> byVersion = new TreeMap<>();
        Map<String, Integer> byEntries = new TreeMap<>();
        List<Map<String, Object>> mismatches = new ArrayList<>();
        List<Map<String, Object>> unreadable = new ArrayList<>();
        List<Map<String, Object>> listed = new ArrayList<>();
        int roundTripped = 0;
        long layoutBytes = 0;
        long moduleBytes = 0;
        int withoutModule = 0;
        for (OrdinaryFormLocator.Located located : forms)
        {
            OrdinaryFormFile file;
            try
            {
                file = OrdinaryFormFile.read(located.file());
            }
            catch (IOException e)
            {
                if (unreadable.size() < limit)
                {
                    unreadable.add(row(located.fqn(), "error", e.getMessage())); //$NON-NLS-1$
                }
                continue;
            }
            String entries = String.join("+", file.entryNames()); //$NON-NLS-1$
            byEntries.merge(entries, Integer.valueOf(1), Integer::sum);
            String version = file.formVersion();
            byVersion.merge(version == null ? "none" : version, Integer.valueOf(1), Integer::sum); //$NON-NLS-1$
            String formText = file.formText();
            String moduleText = file.moduleText();
            if (moduleText == null)
            {
                withoutModule++;
            }
            else
            {
                moduleBytes += moduleText.length();
            }
            boolean ok = false;
            String problem = null;
            if (formText != null)
            {
                layoutBytes += formText.length();
                try
                {
                    String again = BraceTree.serialize(BraceTree.parse(formText));
                    ok = again.equals(formText);
                    if (!ok)
                    {
                        problem = "serialized text differs at offset " + firstDifference(formText, again); //$NON-NLS-1$
                    }
                }
                catch (IllegalArgumentException e)
                {
                    problem = e.getMessage();
                }
            }
            else
            {
                problem = "no 'form' entry"; //$NON-NLS-1$
            }
            if (ok)
            {
                roundTripped++;
            }
            else if (mismatches.size() < limit)
            {
                mismatches.add(row(located.fqn(), "problem", problem)); //$NON-NLS-1$
            }
            if (details && listed.size() < limit)
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("form", located.fqn()); //$NON-NLS-1$
                row.put("version", version); //$NON-NLS-1$
                row.put("layoutChars", Integer.valueOf(formText == null ? 0 : formText.length())); //$NON-NLS-1$
                row.put("moduleChars", Integer.valueOf(moduleText == null ? 0 : moduleText.length())); //$NON-NLS-1$
                row.put("roundTrip", Boolean.valueOf(ok)); //$NON-NLS-1$
                listed.add(row);
            }
        }
        ToolResult answer = ToolResult.success()
            .put("operation", "audit") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("forms", forms.size()) //$NON-NLS-1$
            .put("roundTripped", roundTripped) //$NON-NLS-1$
            .put("mismatched", forms.size() - unreadable.size() - roundTripped) //$NON-NLS-1$
            .put("unreadable", unreadable.size()) //$NON-NLS-1$
            .put("withoutModule", withoutModule) //$NON-NLS-1$
            .put("byVersion", byVersion) //$NON-NLS-1$
            .put("byEntries", byEntries) //$NON-NLS-1$
            .put("layoutChars", layoutBytes) //$NON-NLS-1$
            .put("moduleChars", moduleBytes); //$NON-NLS-1$
        if (!mismatches.isEmpty())
        {
            answer.put("mismatches", mismatches); //$NON-NLS-1$
        }
        if (!unreadable.isEmpty())
        {
            answer.put("unreadableForms", unreadable); //$NON-NLS-1$
        }
        if (details)
        {
            answer.put("details", listed); //$NON-NLS-1$
        }
        answer.put("note", "roundTripped counts forms whose layout text this server reads and writes back " //$NON-NLS-1$ //$NON-NLS-2$
            + "byte for byte; only such a form can be edited here without touching what is not edited."); //$NON-NLS-1$
        return answer.toJson();
    }

    private static Map<String, Object> row(String form, String key, String value)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("form", form); //$NON-NLS-1$
        row.put(key, value);
        return row;
    }

    private static int firstDifference(String a, String b)
    {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++)
        {
            if (a.charAt(i) != b.charAt(i))
            {
                return i;
            }
        }
        return n;
    }
}
