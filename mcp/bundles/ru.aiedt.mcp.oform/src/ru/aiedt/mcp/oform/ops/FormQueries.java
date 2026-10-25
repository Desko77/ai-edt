/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.ConfigurationNames;
import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.OrdinaryFormLocator;
import ru.aiedt.mcp.oform.OrdinaryFormModule;
import ru.aiedt.mcp.oform.V8Container;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutInspection;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.GitDiffUtils;
import ru.aiedt.mcp.server.support.PreviousRevision;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The reading operations: {@code audit} over every ordinary form of a project, {@code layout} of
 * one form, {@code inspect_layout} of one form, {@code diff_form} of one form against the previous
 * revision of its container.
 */
public final class FormQueries
{
    private static final int DEFAULT_LIMIT = 20;

    private FormQueries()
    {
        // static operations
    }

    /**
     * Reads every ordinary form and reports what survived the round trip.
     *
     * @param project the project
     * @param params the tool arguments: {@code limit}, {@code details}
     * @return the answer as JSON
     */
    public static String audit(IProject project, Map<String, String> params)
    {
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

    private static String audit(IProject project, int limit, boolean details) throws IOException
    {
        List<OrdinaryFormLocator.Located> forms = OrdinaryFormLocator.locate(project);
        Map<String, Integer> byVersion = new TreeMap<>();
        Map<String, Integer> byEntries = new TreeMap<>();
        List<Map<String, Object>> mismatches = new ArrayList<>();
        List<Map<String, Object>> unreadable = new ArrayList<>();
        List<Map<String, Object>> listed = new ArrayList<>();
        int roundTripped = 0;
        int unreadableCount = 0;
        long layoutBytes = 0;
        long moduleBytes = 0;
        int withoutModule = 0;
        int layoutsRead = 0;
        int elementsRead = 0;
        Map<String, Integer> unreadRecords = new TreeMap<>();
        List<Map<String, Object>> layoutFailures = new ArrayList<>();
        for (OrdinaryFormLocator.Located located : forms)
        {
            OrdinaryFormFile file;
            try
            {
                file = OrdinaryFormFile.read(located.file());
            }
            catch (IOException e)
            {
                unreadableCount++;
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
                try
                {
                    String[] parts = located.fqn().split("\\."); //$NON-NLS-1$
                    FormLayout layout = LayoutReader.read(file.formTree(), parts.length == 4 ? parts[0] : null);
                    layoutsRead++;
                    elementsRead += layout.allElements().size();
                    for (Map.Entry<String, Integer> unread : layout.unread.entrySet())
                    {
                        unreadRecords.merge(unread.getKey(), unread.getValue(), Integer::sum);
                    }
                }
                catch (RuntimeException e)
                {
                    if (layoutFailures.size() < limit)
                    {
                        layoutFailures.add(row(located.fqn(), "error", String.valueOf(e.getMessage()))); //$NON-NLS-1$
                    }
                }
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
            .put("mismatched", forms.size() - unreadableCount - roundTripped) //$NON-NLS-1$
            .put("unreadable", unreadableCount) //$NON-NLS-1$
            .put("withoutModule", withoutModule) //$NON-NLS-1$
            .put("byVersion", byVersion) //$NON-NLS-1$
            .put("byEntries", byEntries) //$NON-NLS-1$
            .put("layoutChars", layoutBytes) //$NON-NLS-1$
            .put("moduleChars", moduleBytes) //$NON-NLS-1$
            .put("layoutsRead", layoutsRead) //$NON-NLS-1$
            .put("elementsRead", elementsRead); //$NON-NLS-1$
        if (!unreadRecords.isEmpty())
        {
            answer.put("unreadRecordTypes", unreadRecords); //$NON-NLS-1$
        }
        if (!layoutFailures.isEmpty())
        {
            answer.put("layoutFailures", layoutFailures); //$NON-NLS-1$
        }
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
            + "byte for byte; only such a form can be edited here without touching what is not edited. " //$NON-NLS-1$
            + "layoutsRead counts the forms whose element tree the layout reader yields; unreadRecordTypes names " //$NON-NLS-1$
            + "the element types it does not know, by id, with counts. unreadable counts every container " //$NON-NLS-1$
            + "this server refused to open, while unreadableForms names at most limit of them."); //$NON-NLS-1$
        return answer.toJson();
    }

    /**
     * Reads the layout of one form.
     *
     * @param project the project
     * @param params the tool arguments: {@code formFqn} or {@code modulePath}
     * @return the answer as JSON
     */
    public static String layout(IProject project, Map<String, String> params)
    {
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String fqn = formFqn == null ? null : formFqn.trim();
        Path file = null;
        if ((fqn == null || fqn.isEmpty()) && modulePath != null && !modulePath.trim().isEmpty())
        {
            OrdinaryFormModule module = OrdinaryFormModule.locate(project, modulePath.trim());
            if (module == null)
            {
                return ToolResult.error("No ordinary form's container lies behind " + modulePath.trim() //$NON-NLS-1$
                    + ": the address is not a form module's, a Module.bsl exists there, or there is no Form.oform beside it.") //$NON-NLS-1$
                    .toJson();
            }
            fqn = module.fqn();
            file = module.container().getLocation() == null ? null : module.container().getLocation().toFile().toPath();
        }
        if (fqn == null || fqn.isEmpty())
        {
            return ToolResult.error("layout needs formFqn (Type.Object.Form.Name or CommonForm.Name) or modulePath.").toJson(); //$NON-NLS-1$
        }
        if (file == null)
        {
            file = OrdinaryFormLocator.fileOf(project, fqn);
        }
        if (file == null)
        {
            return ToolResult.error("No Form.oform for " + fqn + " in " + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ": the form is managed, does not exist, or the FQN is not a form's.").toJson(); //$NON-NLS-1$
        }
        OrdinaryFormFile container;
        BraceTree.ListNode tree;
        try
        {
            container = OrdinaryFormFile.read(file);
            tree = container.formTree();
        }
        catch (IOException | IllegalArgumentException e)
        {
            return ToolResult.error("The container of " + fqn + " could not be read: " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (tree == null)
        {
            return ToolResult.error("The container of " + fqn + " holds no layout entry.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        String ownerKind = parts.length == 4 ? parts[0] : null;
        FormLayout layout = LayoutReader.read(tree, ownerKind);
        LayoutNames names = ConfigurationNames.of(project, layout.producedTypeUuids());
        layout.resolve(names);
        ToolResult answer = ToolResult.success()
            .put("operation", "layout") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("form", fqn) //$NON-NLS-1$
            .put("container", containerPath(project, file)); //$NON-NLS-1$
        for (Map.Entry<String, Object> entry : layout.toMap().entrySet())
        {
            answer.put(entry.getKey(), entry.getValue());
        }
        if (names == LayoutNames.NONE)
        {
            answer.put("note", "the configuration is not loaded in this environment: a type stays the uuid of the " //$NON-NLS-1$ //$NON-NLS-2$
                + "produced type, a data path that names a metadata object by id shows the id as uuid:<id>"); //$NON-NLS-1$
        }
        return answer.toJson();
    }

    /**
     * Inspects one form's layout: the findings of the read-only checks over the model the reader
     * yields, and the coverage that names what was and was not checked. The module entry, when the
     * container holds one, is read for the handlers the layout binds against its declarations;
     * nothing is written and nothing is corrected.
     *
     * @param project the project
     * @param params the tool arguments: {@code formFqn} or {@code modulePath}
     * @return the answer as JSON
     */
    public static String inspectLayout(IProject project, Map<String, String> params)
    {
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String fqn = formFqn == null ? null : formFqn.trim();
        Path file = null;
        if ((fqn == null || fqn.isEmpty()) && modulePath != null && !modulePath.trim().isEmpty())
        {
            OrdinaryFormModule module = OrdinaryFormModule.locate(project, modulePath.trim());
            if (module == null)
            {
                return ToolResult.error("No ordinary form's container lies behind " + modulePath.trim() //$NON-NLS-1$
                    + ": the address is not a form module's, a Module.bsl exists there, or there is no Form.oform beside it.") //$NON-NLS-1$
                    .toJson();
            }
            fqn = module.fqn();
            file = module.container().getLocation() == null ? null : module.container().getLocation().toFile().toPath();
        }
        if (fqn == null || fqn.isEmpty())
        {
            return ToolResult.error("inspect_layout needs formFqn (Type.Object.Form.Name or CommonForm.Name) or modulePath.").toJson(); //$NON-NLS-1$
        }
        if (file == null)
        {
            file = OrdinaryFormLocator.fileOf(project, fqn);
        }
        if (file == null)
        {
            return ToolResult.error("No Form.oform for " + fqn + " in " + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ": the form is managed, does not exist, or the FQN is not a form's.").toJson(); //$NON-NLS-1$
        }
        OrdinaryFormFile container;
        BraceTree.ListNode tree;
        try
        {
            container = OrdinaryFormFile.read(file);
            tree = container.formTree();
        }
        catch (IOException | IllegalArgumentException e)
        {
            return ToolResult.error("The container of " + fqn + " could not be read: " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (tree == null)
        {
            return ToolResult.error("The container of " + fqn + " holds no layout entry.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        String ownerKind = parts.length == 4 ? parts[0] : null;
        FormLayout layout = LayoutReader.read(tree, ownerKind);
        LayoutNames names = ConfigurationNames.of(project, layout.producedTypeUuids());
        layout.resolve(names);
        Map<String, Object> inspection = LayoutInspection.inspect(tree, layout, container.formText(),
            container.moduleText(), names);
        ToolResult answer = ToolResult.success()
            .put("operation", "inspect_layout") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("form", fqn) //$NON-NLS-1$
            .put("container", containerPath(project, file)); //$NON-NLS-1$
        for (Map.Entry<String, Object> entry : inspection.entrySet())
        {
            answer.put(entry.getKey(), entry.getValue());
        }
        return answer.toJson();
    }

    /**
     * Compares one form's container with the previous revision of that file.
     * <p>
     * The layout entry, the module entry and every other entry are compared by their bytes. A
     * changed module is named for {@code diff_module}; the methods themselves are that tool's
     * answer. When the revision cannot be read, the outcome is named and nothing is compared.
     * </p>
     *
     * @param project the project
     * @param params the tool arguments: {@code formFqn} or {@code modulePath}
     * @return the answer as JSON
     */
    public static String diffForm(IProject project, Map<String, String> params)
    {
        Addressed addressed = addressed(project, params);
        if (addressed.refusal != null)
        {
            return addressed.refusal;
        }
        PreviousRevision revision = GitDiffUtils.previousRevision(addressed.workspaceFile, project);
        String container = containerPath(project, addressed.file);
        ToolResult answer = ToolResult.success()
            .put("operation", "diff_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("form", addressed.fqn) //$NON-NLS-1$
            .put("container", container) //$NON-NLS-1$
            .put("revision", revisionFields(revision)); //$NON-NLS-1$
        if (!revision.isFound())
        {
            return answer.toJson();
        }
        if (revision.bytes() == null)
        {
            return ToolResult.error("The previous revision of " + addressed.fqn + " has no bytes.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        OrdinaryFormFile current;
        OrdinaryFormFile previous;
        try
        {
            current = OrdinaryFormFile.read(addressed.file);
            previous = OrdinaryFormFile.of(addressed.file, V8Container.read(revision.bytes()));
        }
        catch (IOException | IllegalArgumentException e)
        {
            return ToolResult.error("The container of " + addressed.fqn + " could not be compared: " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        boolean moduleChanged = entryChanged(current.getContainer(), previous.getContainer(), OrdinaryFormFile.MODULE_ENTRY);
        answer.put("layoutChanged", entryChanged(current.getContainer(), previous.getContainer(), OrdinaryFormFile.FORM_ENTRY)); //$NON-NLS-1$
        answer.put("moduleChanged", moduleChanged); //$NON-NLS-1$
        answer.put("otherEntries", otherEntries(current.getContainer(), previous.getContainer())); //$NON-NLS-1$
        if (moduleChanged)
        {
            Map<String, String> call = new LinkedHashMap<>();
            call.put("tool", "diff_module"); //$NON-NLS-1$ //$NON-NLS-2$
            call.put("projectName", project.getName()); //$NON-NLS-1$
            call.put("modulePath", moduleAddressOf(container)); //$NON-NLS-1$
            answer.put("diffModule", call); //$NON-NLS-1$
        }
        return answer.toJson();
    }

    /** One form the operation has opened, or the refusal to hand back. */
    private static final class Addressed
    {
        private final String fqn;

        private final Path file;

        private final IFile workspaceFile;

        private final String refusal;

        private Addressed(String fqn, Path file, IFile workspaceFile, String refusal)
        {
            this.fqn = fqn;
            this.file = file;
            this.workspaceFile = workspaceFile;
            this.refusal = refusal;
        }

        private static Addressed refused(String refusal)
        {
            return new Addressed(null, null, null, refusal);
        }
    }

    /**
     * The form a {@code diff_form} call names, the way {@code layout} names one.
     *
     * @param project the project
     * @param params the tool arguments
     * @return the form, or a refusal
     */
    private static Addressed addressed(IProject project, Map<String, String> params)
    {
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String fqn = formFqn == null ? null : formFqn.trim();
        Path file = null;
        IFile workspaceFile = null;
        if ((fqn == null || fqn.isEmpty()) && modulePath != null && !modulePath.trim().isEmpty())
        {
            OrdinaryFormModule module = OrdinaryFormModule.locate(project, modulePath.trim());
            if (module == null)
            {
                return Addressed.refused(ToolResult.error("No ordinary form's container lies behind " + modulePath.trim() //$NON-NLS-1$
                    + ": the address is not a form module's, a Module.bsl exists there, or there is no Form.oform beside it.") //$NON-NLS-1$
                    .toJson());
            }
            fqn = module.fqn();
            workspaceFile = module.container();
            file = workspaceFile.getLocation() == null ? null : workspaceFile.getLocation().toFile().toPath();
        }
        if (fqn == null || fqn.isEmpty())
        {
            return Addressed.refused(ToolResult.error(
                "diff_form needs formFqn (Type.Object.Form.Name or CommonForm.Name) or modulePath.").toJson()); //$NON-NLS-1$
        }
        if (file == null)
        {
            file = OrdinaryFormLocator.fileOf(project, fqn);
        }
        if (file == null)
        {
            return Addressed.refused(ToolResult.error("No Form.oform for " + fqn + " in " + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ": the form is managed, does not exist, or the FQN is not a form's.").toJson()); //$NON-NLS-1$
        }
        if (workspaceFile == null)
        {
            workspaceFile = workspaceFile(project, file);
        }
        if (workspaceFile == null || workspaceFile.getLocation() == null)
        {
            return Addressed.refused(ToolResult.error("The container of " + fqn + " has no location in the workspace.").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new Addressed(fqn, file, workspaceFile, null);
    }

    /** The outcome of the lookup, and the explanation when there is nothing to compare with. */
    private static Map<String, Object> revisionFields(PreviousRevision revision)
    {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outcome", revision.outcome().name()); //$NON-NLS-1$
        if (revision.label() != null)
        {
            fields.put("origin", revision.label()); //$NON-NLS-1$
        }
        String explanation = revision.explanation();
        if (explanation != null)
        {
            fields.put("explanation", explanation); //$NON-NLS-1$
        }
        return fields;
    }

    /**
     * Whether the named entry differs between the working container and the revision: missing on
     * one side, or present on both with different bytes.
     */
    private static boolean entryChanged(V8Container current, V8Container previous, String name)
    {
        V8Container.Entry left = current.find(name);
        V8Container.Entry right = previous.find(name);
        if (left == null || right == null)
        {
            return left != right;
        }
        return !Arrays.equals(left.getData(), right.getData());
    }

    /**
     * The names of the entries other than the layout and the module that were added, removed or
     * changed. The working container's order comes first, then names that exist only in the revision.
     */
    private static List<String> otherEntries(V8Container current, V8Container previous)
    {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (V8Container.Entry entry : current.getEntries())
        {
            names.add(entry.getName());
        }
        for (V8Container.Entry entry : previous.getEntries())
        {
            names.add(entry.getName());
        }
        List<String> changed = new ArrayList<>();
        for (String name : names)
        {
            if (OrdinaryFormFile.FORM_ENTRY.equals(name) || OrdinaryFormFile.MODULE_ENTRY.equals(name))
            {
                continue;
            }
            if (entryChanged(current, previous, name))
            {
                changed.add(name);
            }
        }
        return changed;
    }

    /** The module address {@code diff_module} takes, from a project-relative container path. */
    private static String moduleAddressOf(String projectRelativeContainer)
    {
        String path = projectRelativeContainer.replace('\\', '/');
        if (path.regionMatches(true, 0, "src/", 0, 4)) //$NON-NLS-1$
        {
            path = path.substring(4);
        }
        return OrdinaryFormModule.moduleAddressOf(path);
    }

    /** The workspace file at a path under the project, or {@code null} when the path is not. */
    private static IFile workspaceFile(IProject project, Path file)
    {
        if (project.getLocation() == null)
        {
            return null;
        }
        Path root = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root))
        {
            return null;
        }
        String relative = root.relativize(target).toString().replace('\\', '/');
        return project.getFile(new org.eclipse.core.runtime.Path(relative));
    }

    private static String containerPath(IProject project, Path file)
    {
        if (project.getLocation() == null)
        {
            return file.toString();
        }
        Path root = project.getLocation().toFile().toPath();
        return root.relativize(file).toString().replace('\\', '/');
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
