/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.swt.widgets.Display;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com._1c.g5.v8.dt.refactoring.core.ltk.BmObjectTextContentChange;
import com._1c.g5.v8.dt.refactoring.core.ltk.BmObjectTextContentCompositeChange;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.support.ExternalProjectResolver;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Rename a metadata object (top-level or nested attribute / tabular section / dimension / resource) with
 * full cascade refactoring across BSL modules, forms and metadata.
 * <p>
 * Runs in two phases: a non-mutating preview that lists every change point with file / line / column /
 * method context, and a confirm pass that performs the rename. The change list merges three pipelines:
 * EDT's exact-match text search, EDT's native BSL rename preview, and EDT's refactoring-service items.
 * The merge is load-bearing - each pipeline covers a slightly different surface, and only the union
 * produces a complete preview with line numbers. The EDT-internal pipelines are reached by reflective
 * cross-bundle class loading because the plugin deliberately does not declare compile-time dependencies
 * on the BSL UI or search bundles (doing so would break headless installs).
 * </p>
 * <p>
 * After a confirmed rename, an optional off-thread scan walks BSL modules for surviving whole-word
 * textual mentions of the OLD name (string literals, query text, comments) that the semantic refactoring
 * does not touch.
 * </p>
 */
public class MetadataObjectRenamer implements IMcpTool
{
    public static final String NAME = "rename_metadata_object"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `edit_metadata` `operation=rename_metadata_object`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Change the name of a metadata object or one of its attributes, cascading the update. " //$NON-NLS-1$
            + "Every usage across BSL code, forms, and nearby metadata is rewritten to match. " //$NON-NLS-1$
            + "WORKFLOW: 1) Call without confirm to receive a dry run listing each change point and its index. " //$NON-NLS-1$
            + "2) Look over the dry run - each entry carries an index, a file, a description, and an on/off flag. " //$NON-NLS-1$
            + "3) Call again with confirm=true. Optionally add disableIndices to leave chosen change points alone. " //$NON-NLS-1$
            + "Accepts FQNs like 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount'. " //$NON-NLS-1$
            + "Type names in Russian work too. When confirmed it also sweeps BSL modules for " //$NON-NLS-1$
            + "leftover plain-text traces of the former name that the refactoring leaves alone " //$NON-NLS-1$
            + "(scanLeftovers, default true)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object you want to rename " //$NON-NLS-1$
                    + "(for example 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount'). " //$NON-NLS-1$
                    + "Names in Russian are accepted.", true) //$NON-NLS-1$
            .stringProperty("newName", "The name to give the object (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("confirm", //$NON-NLS-1$
                "Pass true to carry out the rename. Defaults to false, which only builds the dry run.") //$NON-NLS-1$
            .stringProperty("disableIndices", //$NON-NLS-1$
                "Indices of change points to leave out, comma-separated (taken from the dry-run list). " //$NON-NLS-1$
                    + "Only skippable changes may be turned off. Example: '2,3,5'") //$NON-NLS-1$
            .integerProperty("maxResults", //$NON-NLS-1$
                "Upper bound on how many change points the dry run lists (default 20). 0 removes the cap.") //$NON-NLS-1$
            .booleanProperty("scanLeftovers", //$NON-NLS-1$
                "Once the rename is confirmed, comb BSL modules for whole-word plain-text traces " //$NON-NLS-1$
                    + "of the FORMER name (string literals / query text / comments) that the semantic " //$NON-NLS-1$
                    + "refactoring leaves untouched. Default true; listed as items to review by hand.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName != null && !projectName.isEmpty())
        {
            return "rename-refactoring-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "rename-refactoring.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        String newName = JsonUtils.extractStringArgument(params, "newName"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$ //$NON-NLS-2$
        String disableIndicesStr = JsonUtils.extractStringArgument(params, "disableIndices"); //$NON-NLS-1$
        int maxResults = Math.max(0, JsonUtils.extractIntArgument(params, "maxResults", 20)); //$NON-NLS-1$ //$NON-NLS-2$
        boolean scanLeftovers = JsonUtils.extractBooleanArgument(params, "scanLeftovers", true); //$NON-NLS-1$ //$NON-NLS-2$

        // Parse disableIndices (silent skip on NumberFormatException).
        Set<Integer> disableIndices = new HashSet<>();
        if (disableIndicesStr != null && !disableIndicesStr.isEmpty())
        {
            for (String part : disableIndicesStr.split(",")) //$NON-NLS-1$
            {
                try
                {
                    disableIndices.add(Integer.parseInt(part.trim()));
                }
                catch (NumberFormatException e)
                {
                    // silent skip
                }
            }
        }

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName must be supplied. " //$NON-NLS-1$
                + "Example: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"; //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return "Error: objectFqn must be supplied. " //$NON-NLS-1$
                + "Such as: 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount', " //$NON-NLS-1$
                + "'Catalog.Products.TabularSection.Prices'"; //$NON-NLS-1$
        }
        if (newName == null || newName.isEmpty())
        {
            return "Error: newName must be supplied. " //$NON-NLS-1$
                + "Example: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"; //$NON-NLS-1$
        }

        final Set<Integer> finalDisableIndices = disableIndices;
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() ->
        {
            try
            {
                resultRef.set(executeInternal(projectName, objectFqn, newName, confirm, finalDisableIndices,
                    maxResults));
            }
            catch (Exception e)
            {
                Activator.logError("Unhandled exception in rename_metadata_object", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });
        String result = resultRef.get();

        // Post-execute leftover scan - OFF the UI thread (file I/O + regex, no BM/UI access; running it
        // here does not extend the syncExec UI freeze). Only when something was actually renamed.
        if (scanLeftovers && confirm && result != null && !result.startsWith("Error") //$NON-NLS-1$
            && !result.contains("performedCount: 0\n")) //$NON-NLS-1$
        {
            String oldName = objectFqn.substring(objectFqn.lastIndexOf('.') + 1);
            IProject scanProject = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!oldName.isEmpty() && scanProject != null && scanProject.exists())
            {
                result += buildLeftoverScan(scanProject, oldName);
            }
        }
        return result;
    }

    /**
     * UI-thread entry point. Resolves the target object, builds EDT's rename refactoring collection, then
     * branches to preview or execute.
     */
    private String executeInternal(String projectName, String objectFqn, String newName, boolean confirm,
        Set<Integer> disableIndices, int maxResults)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return "Error: Configuration provider is unavailable"; //$NON-NLS-1$
        }
        // An external-object project answers getConfiguration() with a foreign configuration - finding a
        // namesake there would rename the wrong object.
        if (ExternalProjectResolver.isExternalProject(project))
        {
            return "Error: rename_metadata_object cannot handle external-object projects yet ('" //$NON-NLS-1$
                + projectName
                + "' contains external data processors / reports). Rename the object from the EDT UI, " //$NON-NLS-1$
                + "or build it again under the new name."; //$NON-NLS-1$
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return "Error: Unable to obtain the configuration for project: " + projectName; //$NON-NLS-1$
        }

        IMdRefactoringService refactoringService = Activator.getDefault().getMdRefactoringService();
        if (refactoringService == null)
        {
            return "Error: IMdRefactoringService is unavailable"; //$NON-NLS-1$
        }

        objectFqn = MetadataTypeCatalog.normalizeFqn(objectFqn);
        MdObject targetObject = resolveObject(config, objectFqn);
        if (targetObject == null)
        {
            return "Error: No such object: " + objectFqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Verify the FQN shape: 'Type.Name' for top-level objects (e.g. 'Catalog.Products'), " //$NON-NLS-1$
                + "'Type.Name.ChildType.ChildName' for a child (e.g. 'Document.Order.Attribute.Amount'). " //$NON-NLS-1$
                + "Recognized child types: Attribute, TabularSection, Dimension, Resource."; //$NON-NLS-1$
        }

        // Returns a COLLECTION because renaming a base object also renames in extension projects.
        Collection<IRefactoring> refactorings =
            refactoringService.createMdObjectRenameRefactoring(targetObject, newName);
        if (refactorings == null || refactorings.isEmpty())
        {
            return "Error: Could not build the rename refactoring for: " + objectFqn; //$NON-NLS-1$
        }

        if (!confirm)
        {
            return buildPreview(project, objectFqn, newName, targetObject, refactorings, maxResults);
        }
        return performRename(objectFqn, newName, refactorings, disableIndices);
    }

    /**
     * Builds the markdown preview. Three pipelines (exact-match text search, EDT BSL preview, EDT
     * refactoring service items) feed into a merged change list.
     */
    private String buildPreview(IProject project, String objectFqn, String newName, MdObject targetObject,
        Collection<IRefactoring> refactorings, int maxResults)
    {
        String oldName = targetObject.getName();

        // Pipeline A: EDT exact-match text search (reflective). Degrades to empty on any failure.
        Map<String, ExactMatchInfo> exactMatches;
        try
        {
            exactMatches = buildExactMatchInfo(project, targetObject, newName);
        }
        catch (Exception e)
        {
            Activator.logError("Could not collect exact rename matches", e); //$NON-NLS-1$
            exactMatches = Map.of();
        }

        // Pipeline B: EDT native rename preview (reflective, no text-search side).
        List<ChangePoint> edtBslPreviewChanges;
        try
        {
            edtBslPreviewChanges = buildEdtBslPreviewChanges(project, targetObject, newName, exactMatches);
        }
        catch (Exception e)
        {
            Activator.logError("Could not assemble EDT BSL preview changes", e); //$NON-NLS-1$
            edtBslPreviewChanges = List.of();
        }

        // Pipeline C: EDT refactoring service items + problems.
        List<ChangePoint> allChanges = new ArrayList<>();
        List<String> allProblems = new ArrayList<>();
        int[] indexCounter = {0};
        for (IRefactoring refactoring : refactorings)
        {
            String title = refactoring.getTitle();
            Collection<IRefactoringItem> items = refactoring.getItems();
            if (items != null)
            {
                for (IRefactoringItem item : items)
                {
                    if (item instanceof INativeChangeRefactoringItem nativeItem)
                    {
                        Change nativeChange = nativeItem.getNativeChange();
                        if (nativeChange != null)
                        {
                            collectFlatChanges(nativeChange, null, null, exactMatches, allChanges,
                                indexCounter, title, item.isOptional(), oldName);
                        }
                    }
                    else
                    {
                        // Regular rename item: not skippable, but the index must advance.
                        allChanges.add(new ChangePoint(indexCounter[0]++, "rename", null, null, //$NON-NLS-1$
                            item.getName(), item.isOptional(), item.isChecked(), title));
                    }
                }
            }
            RefactoringStatus status = refactoring.getStatus();
            if (status != null)
            {
                Collection<IRefactoringProblem> problems = status.getProblems();
                if (problems != null)
                {
                    for (IRefactoringProblem problem : problems)
                    {
                        StringBuilder pb = new StringBuilder();
                        if (problem instanceof CleanReferenceProblem crp)
                        {
                            if (crp.getReferencingObject() instanceof IBmObject bmObj)
                            {
                                String topFqn = safeTopFqn(bmObj);
                                if (topFqn != null)
                                {
                                    pb.append(topFqn);
                                }
                            }
                            EStructuralFeature feat = crp.getReference();
                            if (feat != null)
                            {
                                pb.append(" → ").append(feat.getName()); //$NON-NLS-1$
                            }
                        }
                        if (problem.getObject() instanceof IBmObject bmObj)
                        {
                            if (pb.length() > 0)
                            {
                                pb.append(" | "); //$NON-NLS-1$
                            }
                            String topFqn = safeTopFqn(bmObj);
                            if (topFqn != null)
                            {
                                pb.append(topFqn);
                            }
                        }
                        allProblems.add(pb.toString());
                    }
                }
            }
        }

        // Merge: overlay pipeline-B line/column/context/method onto pipeline-C bslRef entries when they
        // line up 1:1 positionally.
        applyEdtBslPreviewData(allChanges, edtBslPreviewChanges);

        // Assemble markdown.
        long enabledCount = allChanges.stream().filter(c -> c.enabled).count();
        int shown = (maxResults > 0) ? Math.min(allChanges.size(), maxResults) : allChanges.size();

        StringBuilder sb = new StringBuilder();
        sb.append("---\n"); //$NON-NLS-1$
        sb.append("action: preview\n"); //$NON-NLS-1$
        sb.append("objectFqn: ").append(objectFqn).append('\n');
        sb.append("newName: ").append(newName).append('\n');
        sb.append("totalChanges: ").append(allChanges.size()).append('\n');
        sb.append("enabledChanges: ").append(enabledCount).append('\n');
        sb.append("problems: ").append(allProblems.size()).append('\n');
        sb.append("debugExactMatches: ").append(exactMatches.size()).append('\n');
        sb.append("---\n"); //$NON-NLS-1$

        sb.append("# Rename Dry Run: `").append(objectFqn).append("` → `").append(newName) //$NON-NLS-1$ //$NON-NLS-2$
            .append("`\n\n"); //$NON-NLS-1$

        sb.append("**Change points in total:** ").append(allChanges.size()).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**On by default:** ").append(enabledCount).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (allChanges.size() > shown)
        {
            sb.append("_Listing ").append(shown).append(" out of ").append(allChanges.size()) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" entries. Add `maxResults=").append(allChanges.size()).append("` for the whole list._\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        sb.append("## Detected Changes\n\n"); //$NON-NLS-1$
        sb.append("| # | Kind | Details | Line | Col | On? | Optional | Project | FQN |\n"); //$NON-NLS-1$
        sb.append("|---|------|-------------|------|-----|---------|-----------|---------|-----|\n"); //$NON-NLS-1$
        for (int i = 0; i < shown; i++)
        {
            ChangePoint cp = allChanges.get(i);
            String enabledMark = cp.enabled ? "✅" : "❌"; //$NON-NLS-1$ //$NON-NLS-2$
            String optionalMark = cp.optional ? "Y" : "N"; //$NON-NLS-1$ //$NON-NLS-2$
            String description = cp.description != null ? escapeMarkdownCell(cp.description) : "—"; //$NON-NLS-1$
            String projectCell = cp.project != null ? escapeMarkdownCell(cp.project) : "—"; //$NON-NLS-1$
            String fqnCell = cp.fqn != null ? escapeMarkdownCell(cp.fqn) : "—"; //$NON-NLS-1$
            String line = cp.lineNumber > 0 ? String.valueOf(cp.lineNumber) : "—"; //$NON-NLS-1$
            String column = cp.columnNumber > 0 ? String.valueOf(cp.columnNumber) : "—"; //$NON-NLS-1$
            sb.append("| ").append(cp.index).append(" | ").append(cp.type).append(" | ").append(description) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" | ").append(line).append(" | ").append(column).append(" | ").append(enabledMark) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" | ").append(optionalMark).append(" | ").append(projectCell).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(fqnCell).append(" |\n"); //$NON-NLS-1$
        }
        if (allChanges.size() > shown)
        {
            sb.append("| ... | | | | | | | | _").append(allChanges.size() - shown).append(" additional_ |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append('\n');

        // Code Context section.
        boolean anyContext = false;
        for (int i = 0; i < shown; i++)
        {
            if (allChanges.get(i).codeContext != null)
            {
                anyContext = true;
                break;
            }
        }
        if (anyContext)
        {
            sb.append("## Source Snippets\n"); //$NON-NLS-1$
            for (int i = 0; i < shown; i++)
            {
                ChangePoint cp = allChanges.get(i);
                if (cp.codeContext == null)
                {
                    continue;
                }
                sb.append("### #").append(cp.index); //$NON-NLS-1$
                if (cp.methodName != null)
                {
                    sb.append(" — `").append(escapeMarkdownCell(cp.methodName)).append('`'); //$NON-NLS-1$
                }
                if (cp.fqn != null && cp.lineNumber > 0)
                {
                    sb.append(" · ").append(escapeMarkdownCell(cp.fqn)).append(':').append(cp.lineNumber); //$NON-NLS-1$
                }
                sb.append("\n```bsl\n").append(cp.codeContext).append("\n```\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        if (!allProblems.isEmpty())
        {
            sb.append("## Issues\n\n"); //$NON-NLS-1$
            for (String problem : allProblems)
            {
                sb.append("- ").append(problem).append('\n'); //$NON-NLS-1$
            }
            sb.append('\n');
        }

        sb.append("> Re-run with `confirm=true` to apply it.\n"); //$NON-NLS-1$
        sb.append("> Add `disableIndices='1,2,3'` to leave chosen change points out (skippable ones only).\n"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Performs the rename: applies disableIndices first (matching the preview's traversal order), then
     * runs each refactoring.
     */
    private String performRename(String objectFqn, String newName, Collection<IRefactoring> refactorings,
        Set<Integer> disableIndices)
    {
        // Apply disableIndices BEFORE perform() - shared index counter, same traversal as the preview.
        if (!disableIndices.isEmpty())
        {
            int[] indexCounter = {0};
            for (IRefactoring refactoring : refactorings)
            {
                Collection<IRefactoringItem> items = refactoring.getItems();
                if (items == null)
                {
                    continue;
                }
                for (IRefactoringItem item : items)
                {
                    if (item instanceof INativeChangeRefactoringItem nativeItem)
                    {
                        Change nativeChange = nativeItem.getNativeChange();
                        if (nativeChange != null)
                        {
                            applyDisableToChange(nativeChange, disableIndices, indexCounter);
                            // If all leaf changes under this native item are now disabled AND the item is
                            // optional: uncheck it so EDT skips the whole subtree.
                            if (nativeItem.isOptional() && isCompletelyDisabled(nativeChange))
                            {
                                nativeItem.setChecked(false);
                            }
                        }
                    }
                    else
                    {
                        indexCounter[0]++; // regular rename item - not skippable, but index advances
                    }
                }
            }
        }

        List<String> performed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (IRefactoring refactoring : refactorings)
        {
            try
            {
                refactoring.perform();
                performed.add(refactoring.getTitle());
            }
            catch (Exception e)
            {
                Activator.logError("Could not apply rename refactoring: " + refactoring.getTitle(), e); //$NON-NLS-1$
                errors.add(refactoring.getTitle() + ": " + e.getMessage()); //$NON-NLS-1$
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("---\n"); //$NON-NLS-1$
        sb.append("action: executed\n"); //$NON-NLS-1$
        sb.append("objectFqn: ").append(objectFqn).append('\n');
        sb.append("newName: ").append(newName).append('\n');
        sb.append("disabledCount: ").append(disableIndices.size()).append('\n');
        sb.append("performedCount: ").append(performed.size()).append('\n');
        sb.append("errors: ").append(errors.size()).append('\n');
        sb.append("---\n"); //$NON-NLS-1$

        sb.append("# Rename Done: `").append(objectFqn).append("` → `").append(newName) //$NON-NLS-1$ //$NON-NLS-2$
            .append("`\n\n"); //$NON-NLS-1$

        if (!performed.isEmpty())
        {
            sb.append("## Applied\n\n"); //$NON-NLS-1$
            for (String title : performed)
            {
                sb.append("- ").append(title).append('\n'); //$NON-NLS-1$
            }
            sb.append('\n');
        }
        if (!errors.isEmpty())
        {
            sb.append("## Failures\n\n"); //$NON-NLS-1$
            for (String error : errors)
            {
                sb.append("- ").append(error).append('\n'); //$NON-NLS-1$
            }
            sb.append('\n');
        }
        if (!disableIndices.isEmpty())
        {
            sb.append('_').append(disableIndices.size()).append(" change point(s) were left out on request._\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Recursively walks an LTK Change tree, disabling leaf changes whose global index is in
     * disableIndices. The leaf-visit order MUST match {@link #collectFlatChanges}.
     */
    private void applyDisableToChange(Change change, Set<Integer> disableIndices, int[] indexCounter)
    {
        if (change instanceof CompositeChange composite)
        {
            Change[] children = composite.getChildren();
            if (children != null)
            {
                for (Change child : children)
                {
                    applyDisableToChange(child, disableIndices, indexCounter);
                }
                return;
            }
        }
        int idx = indexCounter[0]++;
        if (disableIndices.contains(idx))
        {
            change.setEnabled(false);
        }
    }

    /**
     * Reports whether every leaf under a Change is disabled (or the composite is empty).
     */
    private boolean isCompletelyDisabled(Change change)
    {
        if (change instanceof CompositeChange composite)
        {
            Change[] children = composite.getChildren();
            if (children == null || children.length == 0)
            {
                return true;
            }
            for (Change child : children)
            {
                if (!isCompletelyDisabled(child))
                {
                    return false;
                }
            }
            return true;
        }
        return !change.isEnabled();
    }

    /**
     * Bounded whole-word regex scan of BSL modules in the renamed project AND any open extension
     * projects. Runs OFF the UI thread.
     */
    private String buildLeftoverScan(IProject project, String oldName)
    {
        final int cap = 100;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        Pattern wholeWord = Pattern.compile(
            "(?<![\\p{L}\\p{Nd}_])" + Pattern.quote(oldName) + "(?![\\p{L}\\p{Nd}_])", //$NON-NLS-1$ //$NON-NLS-2$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        List<String> hits = new ArrayList<>();
        boolean[] flags = new boolean[2]; // [0]=cap reached, [1]=timeout
        List<String> scannedExtras = new ArrayList<>();

        try
        {
            List<IProject> projects = new ArrayList<>();
            projects.add(project);
            try
            {
                for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
                {
                    if (!p.equals(project) && p.isOpen() && BmDcsHelper.isExtensionProject(p))
                    {
                        projects.add(p);
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logWarning("rename leftover scan: workspace listing failed: " + e.getMessage()); //$NON-NLS-1$
            }

            for (IProject p : projects)
            {
                if (hits.size() >= cap || System.nanoTime() > deadline)
                {
                    break;
                }
                IResource src = p.findMember("src"); //$NON-NLS-1$
                if (src != null)
                {
                    boolean isInput = p.equals(project);
                    scanBslForWord(src, wholeWord, hits, cap, deadline, flags, isInput ? "" : p.getName()); //$NON-NLS-1$
                    if (!isInput)
                    {
                        scannedExtras.add(p.getName());
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("rename leftover scan aborted: " + e.getMessage()); //$NON-NLS-1$
            return "\n\n> Leftover-mention scan did not complete: " + e.getMessage() + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Surviving plain-text uses of `").append(oldName).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!scannedExtras.isEmpty())
        {
            sb.append("_Additionally swept ").append(scannedExtras.size()).append(" extension project(s): ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(String.join(", ", scannedExtras)).append("._\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (hits.isEmpty())
        {
            sb.append("_Nothing left in BSL modules; the semantic refactoring caught every reference._\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("Typed references were rewritten, yet these **plain-text** whole-word occurrences of the " //$NON-NLS-1$
            + "former name are still present (in string literals like query text or " //$NON-NLS-1$
            + "`НайтиПоНаименованию(\"...\")`, " //$NON-NLS-1$
            + "and comments). Check whichever ones pointed at the renamed object; a few may be chance " //$NON-NLS-1$
            + "matches.\n\n"); //$NON-NLS-1$
        sb.append("| File | Line | Snippet |\n"); //$NON-NLS-1$
        sb.append("|------|------|------|\n"); //$NON-NLS-1$
        for (String hit : hits)
        {
            sb.append(hit).append('\n');
        }
        if (flags[0])
        {
            sb.append("\n_Limited to ").append(cap) //$NON-NLS-1$
                .append(" matches; run search_in_code (wholeWord=true) to get them all._\n"); //$NON-NLS-1$
        }
        if (flags[1])
        {
            sb.append("\n_The scan ran out of time; this list may be incomplete._\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Recursive scan of a workspace resource for whole-word matches of the old name in .bsl files.
     * bounded by cap and deadline at every recursion and every line.
     */
    private void scanBslForWord(IResource res, Pattern wholeWord, List<String> hits, int cap, long deadline,
        boolean[] flags, String projectLabel)
    {
        if (hits.size() >= cap)
        {
            flags[0] = true;
            return;
        }
        if (System.nanoTime() > deadline)
        {
            flags[1] = true;
            return;
        }
        if (res instanceof IContainer container)
        {
            IResource[] members;
            try
            {
                members = container.members();
            }
            catch (Exception e)
            {
                return;
            }
            if (members == null)
            {
                return;
            }
            for (IResource member : members)
            {
                if (hits.size() >= cap || System.nanoTime() > deadline)
                {
                    break;
                }
                scanBslForWord(member, wholeWord, hits, cap, deadline, flags, projectLabel);
            }
        }
        else if (res instanceof IFile file)
        {
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".bsl")) //$NON-NLS-1$
            {
                return;
            }
            List<String> lines;
            try
            {
                lines = BslModuleAccess.readFileLines(file);
            }
            catch (Exception e)
            {
                return;
            }
            String rel = file.getProjectRelativePath().toString();
            String displayPath = projectLabel.isEmpty() ? rel : projectLabel + "/" + rel; //$NON-NLS-1$
            for (int i = 0; i < lines.size(); i++)
            {
                if (hits.size() >= cap)
                {
                    flags[0] = true;
                    return;
                }
                if (System.nanoTime() > deadline)
                {
                    flags[1] = true;
                    return;
                }
                String line = lines.get(i);
                if (wholeWord.matcher(line).find())
                {
                    String snippet = line.trim();
                    if (snippet.length() > 120)
                    {
                        snippet = snippet.substring(0, 120) + "..."; //$NON-NLS-1$
                    }
                    hits.add("| " + escapeMarkdownCell(displayPath) + " | " + (i + 1) + " | " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + escapeMarkdownCell(snippet) + " |"); //$NON-NLS-1$
                }
            }
        }
    }

    // -- Cascade refactoring mechanism (reflective cross-bundle access) --

    /**
     * Pipeline A: drives EDT's exact-match text search via the BslBmRenameRefactoringProvider. Returns a
     * map keyed by model/file match identity, carrying precise location data for true textual
     * occurrences. On any exception returns an empty map (caller degrades to pipeline-C-only).
     */
    private Map<String, ExactMatchInfo> buildExactMatchInfo(IProject project, MdObject targetObject,
        String newName) throws Exception
    {
        Object bslInjector = getBslInjector();
        Class<?> providerClass =
            getClassOrThrow("com._1c.g5.v8.dt.bsl.bm.ui.refactoring.BslBmRenameRefactoringProvider"); //$NON-NLS-1$
        Object renameProvider = bslInjector.getClass().getMethod("getInstance", Class.class) //$NON-NLS-1$
            .invoke(bslInjector, providerClass);

        Object renameContext = createRenameElementContext(targetObject);
        Class<?> renameContextIface =
            getClassOrThrow("org.eclipse.xtext.ui.refactoring.ui.IRenameElementContext"); //$NON-NLS-1$

        Object refactoring = renameProvider.getClass()
            .getMethod("getRenameRefactoring", renameContextIface, renameContextIface) //$NON-NLS-1$
            .invoke(renameProvider, renameContextIface, renameContext);
        Object processor = null;
        if (refactoring != null)
        {
            processor = invokeNoArg(refactoring, "getProcessor"); //$NON-NLS-1$
        }
        if (processor == null)
        {
            processor = renameProvider.getClass()
                .getMethod("getRenameProcessor", renameContextIface, renameContextIface) //$NON-NLS-1$
                .invoke(renameProvider, renameContextIface, renameContext);
        }
        if (processor == null)
        {
            Activator.logWarning("rename_metadata_object: exact-match pipeline returned no rename processor"); //$NON-NLS-1$
            return Map.of();
        }

        Change normalChange = createRenameChange(refactoring, processor, newName);
        String oldName = (String)invokeMethod(processor, "getOriginalName", new Class<?>[0]); //$NON-NLS-1$
        EObject contextElement = (EObject)invokeMethod(processor, "getContextElement", new Class<?>[0]); //$NON-NLS-1$
        if (normalChange == null || oldName == null || contextElement == null)
        {
            Activator.logWarning("rename_metadata_object: exact-match pipeline is missing base state: " //$NON-NLS-1$
                + "changePresent=" + (normalChange != null) //$NON-NLS-1$
                + ", originalName=" + oldName //$NON-NLS-1$
                + ", contextPresent=" + (contextElement != null)); //$NON-NLS-1$
            return Map.of();
        }

        Class<?> supplierClass =
            getClassOrThrow("com._1c.g5.v8.dt.bsl.bm.ui.refactoring.BslTextSearchRefactoringSupplier"); //$NON-NLS-1$
        Object supplier = bslInjector.getClass().getMethod("getInstance", Class.class) //$NON-NLS-1$
            .invoke(bslInjector, supplierClass);

        Object searchInjector = getSearchCoreInjector();
        Class<?> factoryClass =
            getClassOrThrow("com._1c.g5.v8.dt.search.core.refactoring.TextSearchRefactoringParticipantFactory"); //$NON-NLS-1$
        Object factory = searchInjector.getClass().getMethod("getInstance", Class.class) //$NON-NLS-1$
            .invoke(searchInjector, factoryClass);

        Class<?> supplierInterface =
            getClassOrThrow("com._1c.g5.v8.dt.search.core.refactoring.ITextSearchRefactoringSupplier"); //$NON-NLS-1$
        Method createMethod = findMethod(factory.getClass(), "create", Class.class, Class.class, //$NON-NLS-1$
            Class.class, String.class, EObject.class, supplierInterface);
        Object participant = createMethod.invoke(factory, String.class, EObject.class, supplierInterface,
            oldName, contextElement, supplier);

        Class<?> resultCollectorClass =
            getClassOrThrow("com._1c.g5.v8.dt.search.core.refactoring.TextSearchRefactoringResultCollector"); //$NON-NLS-1$
        Constructor<?> collectorCtor = resultCollectorClass.getDeclaredConstructor(String.class);
        collectorCtor.setAccessible(true);
        Object collector = collectorCtor.newInstance(oldName);

        Class<?> scopeSettingsClass =
            getClassOrThrow("com._1c.g5.v8.dt.search.core.TextSearchScopeSettings"); //$NON-NLS-1$
        Object searchScopeSettings = scopeSettingsClass.getDeclaredConstructor().newInstance();

        Class<?> searchInClass = getClassOrThrow("com._1c.g5.v8.dt.search.core.SearchIn"); //$NON-NLS-1$
        Object modules = getEnumConstant("com._1c.g5.v8.dt.search.core.SearchIn", "MODULES"); //$NON-NLS-1$ //$NON-NLS-2$
        Object dcs = getEnumConstant("com._1c.g5.v8.dt.search.core.SearchIn", "DCS"); //$NON-NLS-1$ //$NON-NLS-2$
        Object dynamicListQuery =
            getEnumConstant("com._1c.g5.v8.dt.search.core.SearchIn", "DYNAMIC_LIST_QUERY"); //$NON-NLS-1$ //$NON-NLS-2$
        Object searchInArray = arrayOf(searchInClass, modules, dcs, dynamicListQuery);
        Method addSearchInMethod =
            findMethod(searchScopeSettings.getClass(), "addSearchIn", searchInClass.arrayType()); //$NON-NLS-1$
        addSearchInMethod.invoke(searchScopeSettings, searchInArray);

        Method getProjectsMethod =
            findMethod(participant.getClass(), "getProjects", Class.class, IProject.class); //$NON-NLS-1$
        Object projects = getProjectsMethod.invoke(participant, IProject.class, project);
        Method addProjectsMethod =
            findMethod(searchScopeSettings.getClass(), "addProjects", Class.class, Collection.class); //$NON-NLS-1$
        addProjectsMethod.invoke(searchScopeSettings, Collection.class, projects);

        // Pull three private fields by name off the participant.
        Object textSearchIndexProvider = getFieldValue(participant, "textSearchIndexProvider"); //$NON-NLS-1$
        Object managerRegistry = getFieldValue(participant, "managerRegistry"); //$NON-NLS-1$
        Object hostResourceManager = getFieldValue(participant, "hostResourceManager"); //$NON-NLS-1$

        Method getSearchStringsMethod = findMethod(supplier.getClass(), "getSearchStrings", Class.class, //$NON-NLS-1$
            Class.class, EObject.class, String.class);
        Object searchStrings = getSearchStringsMethod.invoke(supplier, EObject.class, String.class,
            contextElement, oldName);

        Class<?> searcherClass = getClassOrThrow("com._1c.g5.v8.dt.search.core.TextSearcher"); //$NON-NLS-1$
        Class<?> collectorInterface = getClassOrThrow("com._1c.g5.v8.dt.search.core.ISearchResultCollector"); //$NON-NLS-1$
        Class<?> bmModelManagerClass = getClassOrThrow("com._1c.g5.v8.dt.core.platform.IBmModelManager"); //$NON-NLS-1$
        Class<?> indexProviderClass =
            getClassOrThrow("com._1c.g5.v8.dt.search.core.text.ITextSearchIndexProvider"); //$NON-NLS-1$
        Class<?> externalPropMgrRegistryClass =
            getClassOrThrow("com._1c.g5.v8.dt.md.IExternalPropertyManagerRegistry"); //$NON-NLS-1$
        Class<?> hostResMgrClass =
            getClassOrThrow("com._1c.g5.v8.dt.core.platform.management.IDtHostResourceManager"); //$NON-NLS-1$
        Constructor<?> searcherCtor = searcherClass.getDeclaredConstructor(String.class, boolean.class,
            scopeSettingsClass, collectorInterface, bmModelManagerClass, indexProviderClass,
            externalPropMgrRegistryClass, hostResMgrClass);
        searcherCtor.setAccessible(true);

        // Iterable of search strings; if it isn't iterable for some reason we abort to empty map.
        Iterable<?> searchStringIterable = (Iterable<?>)searchStrings;
        for (Object searchStringObj : searchStringIterable)
        {
            String searchString = (String)searchStringObj;
            Object searcher = searcherCtor.newInstance(searchString, Boolean.FALSE, searchScopeSettings,
                collector, Activator.getDefault().getBmModelManager(), textSearchIndexProvider,
                managerRegistry, hostResourceManager);
            Method searchMethod = findMethod(searcher.getClass(), "search", Class.class, IProgressMonitor.class); //$NON-NLS-1$
            searchMethod.invoke(searcher, IProgressMonitor.class, new NullProgressMonitor());
        }

        Class<?> simpleCollectorClass =
            getClassOrThrow("com._1c.g5.v8.dt.search.core.SimpleSearchResultCollector"); //$NON-NLS-1$
        Method getMatchesMethod = findMethod(supplier.getClass(), "getMatches", Class.class, Class.class, //$NON-NLS-1$
            Change.class, simpleCollectorClass);
        Object matches = getMatchesMethod.invoke(supplier, Change.class, simpleCollectorClass, normalChange,
            collector);

        return toExactMatchMap(matches);
    }

    /**
     * Pipeline B: same BslBmRenameRefactoringProvider rename Change, WITHOUT the text-search side.
     * Flattens the Change tree into ChangePoints via {@link #collectFlatChanges}.
     */
    private List<ChangePoint> buildEdtBslPreviewChanges(IProject project, MdObject targetObject,
        String newName, Map<String, ExactMatchInfo> exactMatches)
    {
        try
        {
            Object bslInjector = getBslInjector();
            Class<?> providerClass =
                getClassOrThrow("com._1c.g5.v8.dt.bsl.bm.ui.refactoring.BslBmRenameRefactoringProvider"); //$NON-NLS-1$
            Object renameProvider = bslInjector.getClass().getMethod("getInstance", Class.class) //$NON-NLS-1$
                .invoke(bslInjector, providerClass);

            Object renameContext = createRenameElementContext(targetObject);
            Class<?> renameContextIface =
                getClassOrThrow("org.eclipse.xtext.ui.refactoring.ui.IRenameElementContext"); //$NON-NLS-1$

            Object refactoring = renameProvider.getClass()
                .getMethod("getRenameRefactoring", renameContextIface, renameContextIface) //$NON-NLS-1$
                .invoke(renameProvider, renameContextIface, renameContext);
            Object processor = null;
            if (refactoring != null)
            {
                processor = invokeNoArg(refactoring, "getProcessor"); //$NON-NLS-1$
            }
            if (processor == null)
            {
                processor = renameProvider.getClass()
                    .getMethod("getRenameProcessor", renameContextIface, renameContextIface) //$NON-NLS-1$
                    .invoke(renameProvider, renameContextIface, renameContext);
            }
            if (refactoring == null || processor == null)
            {
                return List.of();
            }
            Change edtChange = createRenameChange(refactoring, processor, newName);
            if (edtChange == null)
            {
                return List.of();
            }
            List<ChangePoint> edtChanges = new ArrayList<>();
            int[] indexCounter = {0};
            collectFlatChanges(edtChange, null, null, exactMatches, edtChanges, indexCounter, "edt-preview", //$NON-NLS-1$
                false, targetObject.getName());
            return edtChanges;
        }
        catch (Exception e)
        {
            Activator.logError("Could not assemble EDT BSL preview changes", e); //$NON-NLS-1$
            return List.of();
        }
    }

    /**
     * Drives the LTK rename processor / refactoring to produce its Change object.
     */
    private Change createRenameChange(Object refactoring, Object processor, String newName) throws Exception
    {
        Class<?> processorClass = processor.getClass();
        // setNewName is on the processor and takes a type token + value.
        Method setNewNameMethod = findMethod(processorClass, "setNewName", Class.class, String.class); //$NON-NLS-1$
        setNewNameMethod.invoke(processor, String.class, newName);

        NullProgressMonitor progressMonitor = new NullProgressMonitor();
        if (refactoring != null)
        {
            Method checkInitial =
                findMethod(refactoring.getClass(), "checkInitialConditions", Class.class, IProgressMonitor.class); //$NON-NLS-1$
            checkInitial.invoke(refactoring, IProgressMonitor.class, progressMonitor);
            Method checkFinal =
                findMethod(refactoring.getClass(), "checkFinalConditions", Class.class, IProgressMonitor.class); //$NON-NLS-1$
            checkFinal.invoke(refactoring, IProgressMonitor.class, progressMonitor);
            Method createChange =
                findMethod(refactoring.getClass(), "createChange", Class.class, IProgressMonitor.class); //$NON-NLS-1$
            return (Change)createChange.invoke(refactoring, IProgressMonitor.class, progressMonitor);
        }
        // Processor-only path - checkFinalConditions takes two type tokens + monitor + context.
        Method checkInitial =
            findMethod(processorClass, "checkInitialConditions", Class.class, IProgressMonitor.class); //$NON-NLS-1$
        checkInitial.invoke(processor, IProgressMonitor.class, progressMonitor);
        Method checkFinal = findMethod(processorClass, "checkFinalConditions", Class.class, Class.class, //$NON-NLS-1$
            IProgressMonitor.class, CheckConditionsContext.class);
        checkFinal.invoke(processor, IProgressMonitor.class, CheckConditionsContext.class, progressMonitor,
            new CheckConditionsContext());
        Method createChange =
            findMethod(processorClass, "createChange", Class.class, IProgressMonitor.class); //$NON-NLS-1$
        return (Change)createChange.invoke(processor, IProgressMonitor.class, progressMonitor);
    }

    /**
     * Walks the matches collection (mixed TextSearchFileMatch / TextSearchModelMatch) into a keyed map.
     */
    private Map<String, ExactMatchInfo> toExactMatchMap(Object matches)
    {
        Map<String, ExactMatchInfo> result = new LinkedHashMap<>();
        if (!(matches instanceof Collection<?>))
        {
            return result;
        }
        for (Object match : (Collection<?>)matches)
        {
            if (isInstanceOf(match, "com._1c.g5.v8.dt.search.core.text.TextSearchFileMatch")) //$NON-NLS-1$
            {
                ExactMatchInfo info = createFileExactMatchInfo(match);
                if (info != null)
                {
                    IFile file = (IFile)invokeNoArg(match, "getFile"); //$NON-NLS-1$
                    int fileOffset = intValue(invokeNoArg(match, "getFileOffset")); //$NON-NLS-1$
                    int textLength = intValue(invokeNoArg(match, "getTextLength")); //$NON-NLS-1$
                    if (file != null)
                    {
                        result.put(getFileMatchKey(file, fileOffset, textLength), info);
                    }
                }
            }
            else if (isInstanceOf(match, "com._1c.g5.v8.dt.search.core.text.TextSearchModelMatch")) //$NON-NLS-1$
            {
                ExactMatchInfo info = createModelExactMatchInfo(match);
                if (info != null)
                {
                    long objectId = longValue(invokeNoArg(match, "getObjectId")); //$NON-NLS-1$
                    EStructuralFeature feature = (EStructuralFeature)invokeNoArg(match, "getFeature"); //$NON-NLS-1$
                    int textOffset = intValue(invokeNoArg(match, "getTextOffset")); //$NON-NLS-1$
                    int textLength = intValue(invokeNoArg(match, "getTextLength")); //$NON-NLS-1$
                    result.put(getModelMatchKey(objectId, feature, textOffset, textLength), info);
                }
            }
        }
        return result;
    }

    /**
     * Builds an ExactMatchInfo from a TextSearchFileMatch. Returns null on any failure.
     */
    private ExactMatchInfo createFileExactMatchInfo(Object match)
    {
        try
        {
            IFile file = (IFile)invokeNoArg(match, "getFile"); //$NON-NLS-1$
            if (file == null)
            {
                return null;
            }
            int lineNumber = intValue(invokeNoArg(match, "getLineNumber")); //$NON-NLS-1$
            String fqn = getBslFqn(file);
            String project = file.getProject().getName();
            String content = BslModuleAccess.readFileText(file);
            int fileOffset = intValue(invokeNoArg(match, "getFileOffset")); //$NON-NLS-1$
            int columnNumber = computeColumnNumber(content, fileOffset);
            String codeContext = extractContext(content, lineNumber);
            String methodName = null;
            Module module = BslModuleAccess.loadModule(file.getProject(),
                BslModuleAccess.extractModulePath(file.getFullPath().toString()));
            if (module != null)
            {
                methodName = findContainingMethodAst(module, lineNumber);
            }
            if (methodName == null)
            {
                methodName = findContainingMethodText(content, lineNumber);
            }
            return new ExactMatchInfo(file.getFullPath().toString(), fileOffset, lineNumber, columnNumber,
                codeContext, methodName, fqn, project);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Builds an ExactMatchInfo from a TextSearchModelMatch. Returns null on any failure.
     */
    private ExactMatchInfo createModelExactMatchInfo(Object match)
    {
        try
        {
            Object optional = invokeNoArg(match, "resolveMatchObject"); //$NON-NLS-1$
            Object presentObj = invokeNoArg(optional, "isPresent"); //$NON-NLS-1$
            boolean present = Boolean.TRUE.equals(presentObj);
            if (!present)
            {
                return null;
            }
            Object resolved = invokeNoArg(optional, "get"); //$NON-NLS-1$
            if (!(resolved instanceof EObject))
            {
                return null;
            }
            EStructuralFeature feature = (EStructuralFeature)invokeNoArg(match, "getFeature"); //$NON-NLS-1$
            String content = getFeatureText((EObject)resolved, feature);
            if (content == null)
            {
                return null;
            }
            int textOffset = intValue(invokeNoArg(match, "getTextOffset")); //$NON-NLS-1$
            int lineNumber = computeLineNumber(content, textOffset);
            int columnNumber = computeColumnNumber(content, textOffset);
            String project = null;
            String fqn = null;
            if (resolved instanceof IBmObject bmObject)
            {
                project = bmObject.bmGetEngine().getId();
                IBmObject top = bmObject.bmGetTopObject();
                if (top != null)
                {
                    fqn = top.bmGetFqn();
                }
            }
            return new ExactMatchInfo(null, textOffset, lineNumber, columnNumber,
                extractContext(content, lineNumber), null, fqn, project);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Match-key builders. The exact-match map uses these as keys; {@link #findExactMatchInfos} reproduces
     * them to look up infos that overlap a Change.
     */
    private static String getFileMatchKey(IFile file, int offset, int length)
    {
        return file.getFullPath().toString() + "[" + offset + "," + length + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String getModelMatchKey(long objectId, EStructuralFeature feature, int offset, int length)
    {
        return "(" + objectId + "," + feature.getFeatureID() + ")[" + offset + "," + length + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    private static String getExactMatchIdentity(ExactMatchInfo info)
    {
        return info.project + "|" + info.fqn + "|" + info.matchOffset; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The trickiest method in the tool. Flattens an LTK Change tree into a flat list of ChangePoints with
     * globally-monotonic indices. For each leaf it tries three sources for line/column/context/method, in
     * order: exact-match lookup, BmObjectTextContentChange content/edit parsing, source-file content/edit
     * parsing.
     * <p>
     * Branch order is load-bearing - callers depend on the index sequence matching what the preview
     * showed.
     * </p>
     */
    @SuppressWarnings("unchecked")
    private void collectFlatChanges(Change change, String currentFqn, String currentProject,
        Map<String, ExactMatchInfo> exactMatches, List<ChangePoint> result, int[] indexCounter,
        String refactoringTitle, boolean optional, String oldName)
    {
        // Branch 1: BmObjectTextContentCompositeChange updates currentProject/currentFqn and falls through.
        if (change instanceof BmObjectTextContentCompositeChange<?> bmComposite)
        {
            currentProject = bmComposite.getProjectName();
            Object modifiedElement = bmComposite.getModifiedElement();
            if (modifiedElement instanceof IBmObject bmObj)
            {
                currentFqn = safeTopFqn(bmObj);
            }
        }

        // Branch 2: CompositeChange recurses on children.
        if (change instanceof CompositeChange composite)
        {
            Change[] children = composite.getChildren();
            if (children != null && children.length > 0)
            {
                for (Change child : children)
                {
                    collectFlatChanges(child, currentFqn, currentProject, exactMatches, result, indexCounter,
                        refactoringTitle, optional, oldName);
                }
                return;
            }
        }

        // Branch 3: leaf / non-composite handling.
        boolean hasExactMatches = exactMatches != null && !exactMatches.isEmpty();
        boolean isBslReferenceChange = isBslReferenceChange(change, currentFqn);
        boolean isFullTextSearchChange = isFullTextSearchSourceFileChange(change);
        boolean addedFallbackChange = false;
        int lineNumber = -1;
        int columnNumber = -1;
        String codeContext = null;
        String methodName = null;
        String fqn = currentFqn;
        String project = currentProject;
        List<ExactMatchInfo> exactMatchInfos = findExactMatchInfos(change, exactMatches);
        logPreviewMapping(change, fqn, project, exactMatchInfos.size());

        // Branch A: exact matches present.
        if (!exactMatchInfos.isEmpty())
        {
            for (ExactMatchInfo exactMatch : exactMatchInfos)
            {
                String exactFqn = exactMatch.fqn != null ? exactMatch.fqn : fqn;
                String exactProject = exactMatch.project != null ? exactMatch.project : project;
                result.add(new ChangePoint(indexCounter[0]++, "bslRef", exactFqn, exactProject, //$NON-NLS-1$
                    change.getName(), optional, change.isEnabled(), refactoringTitle, exactMatch.lineNumber,
                    exactMatch.columnNumber, exactMatch.codeContext, exactMatch.methodName));
            }
            return;
        }

        // Branch B: BmObjectTextContentChange fallback.
        else if (change instanceof BmObjectTextContentChange<?> bmChange)
        {
            try
            {
                project = bmChange.getProjectName();
                Object modifiedElement = bmChange.getModifiedElement();
                EObject bmObj = modifiedElement instanceof EObject ? (EObject)modifiedElement : null;
                if (modifiedElement instanceof IBmObject ibm)
                {
                    fqn = safeTopFqn(ibm);
                }
                String content = bmChange.getCurrentContent(new NullProgressMonitor());
                TextEdit edit = bmChange.getEdit();
                if (content != null && !content.isEmpty() && edit != null)
                {
                    List<TextEdit> leafEdits = getLeafEdits(edit);
                    if (!leafEdits.isEmpty())
                    {
                        for (TextEdit leafEdit : leafEdits)
                        {
                            int matchedLineNumber = computeLineNumber(content, leafEdit.getOffset());
                            int matchedColumnNumber = computeColumnNumber(content, leafEdit.getOffset());
                            String matchedCodeContext = extractContext(content, matchedLineNumber);
                            String matchedMethodName = null;
                            if (bmObj instanceof Module module)
                            {
                                matchedMethodName = findContainingMethodAst(module, matchedLineNumber);
                            }
                            if (matchedMethodName == null)
                            {
                                matchedMethodName = findContainingMethodText(content, matchedLineNumber);
                            }
                            result.add(new ChangePoint(indexCounter[0]++, "bslRef", fqn, project, //$NON-NLS-1$
                                change.getName(), optional, change.isEnabled(), refactoringTitle,
                                matchedLineNumber, matchedColumnNumber, matchedCodeContext,
                                matchedMethodName));
                        }
                        addedFallbackChange = true;
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logError("Could not extract BSL change location", e); //$NON-NLS-1$
            }
        }

        // Branch C: source-file fallback (non-Bm, non-exact).
        else
        {
            try
            {
                IFile file = getIFile(change);
                if (file != null)
                {
                    project = file.getProject().getName();
                    String resolvedFqn = getBslFqn(file);
                    if (resolvedFqn != null && !resolvedFqn.isEmpty())
                    {
                        fqn = resolvedFqn;
                    }
                    String content = BslModuleAccess.readFileText(file);
                    TextEdit edit = getChangeEdit(change);
                    if (content != null && !content.isEmpty() && edit != null)
                    {
                        List<TextEdit> leafEdits = getLeafEdits(edit);
                        if (!leafEdits.isEmpty())
                        {
                            Module module = BslModuleAccess.loadModule(file.getProject(),
                                BslModuleAccess.extractModulePath(file.getFullPath().toString()));
                            for (TextEdit leafEdit : leafEdits)
                            {
                                int matchedLineNumber = computeLineNumber(content, leafEdit.getOffset());
                                int matchedColumnNumber = computeColumnNumber(content, leafEdit.getOffset());
                                String matchedCodeContext = extractContext(content, matchedLineNumber);
                                String matchedMethodName = null;
                                if (module != null)
                                {
                                    matchedMethodName =
                                        findContainingMethodAst(module, matchedLineNumber);
                                }
                                if (matchedMethodName == null)
                                {
                                    matchedMethodName =
                                        findContainingMethodText(content, matchedLineNumber);
                                }
                                result.add(new ChangePoint(indexCounter[0]++, "bslRef", fqn, project, //$NON-NLS-1$
                                    change.getName(), optional, change.isEnabled(), refactoringTitle,
                                    matchedLineNumber, matchedColumnNumber, matchedCodeContext,
                                    matchedMethodName));
                            }
                            addedFallbackChange = true;
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logError("Could not extract source-file change location", e); //$NON-NLS-1$
            }
        }

        // Early-return guards after B/C.
        if (addedFallbackChange)
        {
            return;
        }
        if (hasExactMatches && isBslReferenceChange && isFullTextSearchChange)
        {
            // Full-text-search change that didn't match the exact-match map - already counted via the
            // exact-match pipeline; skip the generic-row fallback to avoid duplicates.
            return;
        }

        // Generic fallback row.
        result.add(new ChangePoint(indexCounter[0]++, "bslRef", fqn, project, change.getName(), optional, //$NON-NLS-1$
            change.isEnabled(), refactoringTitle, lineNumber, columnNumber, codeContext, methodName));
    }

    /**
     * Overlays pipeline-B line/column/context/method onto pipeline-C bslRef entries. Both lists must be
     * non-empty. The overlay is positional on the bslRef subsequence.
     */
    private void applyEdtBslPreviewData(List<ChangePoint> allChanges, List<ChangePoint> edtBslPreviewChanges)
    {
        if (allChanges.isEmpty() || edtBslPreviewChanges.isEmpty())
        {
            return;
        }
        List<Integer> bslIndices = new ArrayList<>();
        for (int i = 0; i < allChanges.size(); i++)
        {
            if ("bslRef".equals(allChanges.get(i).type)) //$NON-NLS-1$
            {
                bslIndices.add(i);
            }
        }
        if (bslIndices.size() != edtBslPreviewChanges.size())
        {
            return; // counts must match positionally - no overlay
        }
        for (int i = 0; i < bslIndices.size(); i++)
        {
            ChangePoint original = allChanges.get(bslIndices.get(i));
            ChangePoint edt = edtBslPreviewChanges.get(i);
            String mergedFqn = edt.fqn != null ? edt.fqn : original.fqn;
            String mergedProject = edt.project != null ? edt.project : original.project;
            allChanges.set(bslIndices.get(i),
                new ChangePoint(original.index, original.type, mergedFqn, mergedProject, original.description,
                    original.optional, original.enabled, null, edt.lineNumber, edt.columnNumber,
                    edt.codeContext, edt.methodName));
        }
    }

    /**
     * Dedup-merge of all exact-match infos that overlap with this change. Returns the values in insertion
     * order keyed by exact-match identity.
     */
    private List<ExactMatchInfo> findExactMatchInfos(Change change, Map<String, ExactMatchInfo> exactMatches)
    {
        LinkedHashMap<String, ExactMatchInfo> map = new LinkedHashMap<>();
        if (exactMatches == null || exactMatches.isEmpty())
        {
            return new ArrayList<>();
        }

        if (change instanceof BmObjectTextContentChange<?> bmChange)
        {
            Object modifiedElement = bmChange.getModifiedElement();
            if (modifiedElement instanceof IBmObject bmObject)
            {
                EStructuralFeature feature = getBmChangeFeature(bmChange);
                if (feature != null)
                {
                    TextEdit bmEdit = bmChange.getEdit();
                    if (bmEdit != null)
                    {
                        for (TextEdit leafEdit : getLeafEdits(bmEdit))
                        {
                            String key = getModelMatchKey(bmObject.bmGetId(), feature, leafEdit.getOffset(),
                                leafEdit.getLength());
                            ExactMatchInfo info = exactMatches.get(key);
                            if (info != null)
                            {
                                map.put(getExactMatchIdentity(info), info);
                            }
                        }
                    }
                }
                String projectName = bmChange.getProjectName();
                String objectFqn = safeTopFqn(bmObject);
                TextEdit bmEdit = bmChange.getEdit();
                if (bmEdit != null)
                {
                    for (ExactMatchInfo info : exactMatches.values())
                    {
                        if (info == null || info.filePath == null)
                        {
                            continue;
                        }
                        if (projectName != null && !projectName.equals(info.project))
                        {
                            continue;
                        }
                        if (objectFqn != null && !objectFqn.equals(info.fqn))
                        {
                            continue;
                        }
                        for (TextEdit leafEdit : getLeafEdits(bmEdit))
                        {
                            if (containsOffset(leafEdit, info.matchOffset))
                            {
                                map.put(getExactMatchIdentity(info), info);
                                break;
                            }
                        }
                    }
                }
            }
        }

        // Always: file-based lookup.
        IFile file = getIFile(change);
        TextEdit edit = getChangeEdit(change);
        if (file != null && edit != null)
        {
            String filePath = file.getFullPath().toString();
            for (ExactMatchInfo info : exactMatches.values())
            {
                if (info == null || info.filePath == null)
                {
                    continue;
                }
                if (!filePath.equals(info.filePath))
                {
                    continue;
                }
                for (TextEdit leafEdit : getLeafEdits(edit))
                {
                    if (containsOffset(leafEdit, info.matchOffset))
                    {
                        map.put(getExactMatchIdentity(info), info);
                        break;
                    }
                }
            }
        }

        return new ArrayList<>(map.values());
    }

    private static boolean containsOffset(TextEdit edit, int offset)
    {
        if (edit == null || edit.getOffset() < 0)
        {
            return false;
        }
        int start = edit.getOffset();
        int end = edit.getLength() > 0 ? start + edit.getLength() : start + 1;
        return offset >= start && offset < end;
    }

    /**
     * Diagnostic one-liner logged only for custom source-file changes with zero exact matches. Swallows
     * any exception.
     */
    private void logPreviewMapping(Change change, String fqn, String project, int exactMatchesCount)
    {
        try
        {
            if (!isCustomSourceFileChange(change) || exactMatchesCount != 0)
            {
                return;
            }
            TextEdit edit = getChangeEdit(change);
            List<TextEdit> leafEdits = edit != null ? getLeafEdits(edit) : List.of();
            int leafEditsCount = leafEdits.size();
            StringBuilder offsets = new StringBuilder();
            for (int i = 0; i < leafEdits.size(); i++)
            {
                TextEdit le = leafEdits.get(i);
                if (i > 0)
                {
                    offsets.append(';');
                }
                offsets.append(le.getOffset()).append(',').append(le.getLength());
            }
            IFile file = getIFile(change);
            String filePath = file != null ? file.getFullPath().toString() : "null"; //$NON-NLS-1$
            Activator.logInfo("rename_metadata_object: preview mapping for a custom changeType=" //$NON-NLS-1$
                + change.getClass().getSimpleName()
                + ", exactHits=" + exactMatchesCount //$NON-NLS-1$
                + ", leafCount=" + leafEditsCount //$NON-NLS-1$
                + ", editOffsets=" + offsets.toString() //$NON-NLS-1$
                + ", proj=" + project //$NON-NLS-1$
                + ", targetFqn=" + fqn //$NON-NLS-1$
                + ", filePath=" + filePath //$NON-NLS-1$
                + ", changeName=" + change.getName()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Could not log preview mapping diagnostics", e); //$NON-NLS-1$
        }
    }

    // -- Data holders --

    /** One row in the preview / one entry in the rename refactoring. */
    private static final class ChangePoint
    {
        final int index;
        final String type;
        final String fqn;
        final String project;
        final String description;
        final boolean optional;
        final boolean enabled;
        final int lineNumber;
        final int columnNumber;
        final String codeContext;
        final String methodName;

        ChangePoint(int index, String type, String fqn, String project, String description, boolean optional,
            boolean enabled, String ignoredTitle)
        {
            this(index, type, fqn, project, description, optional, enabled, ignoredTitle, -1, -1, null,
                null);
        }

        ChangePoint(int index, String type, String fqn, String project, String description, boolean optional,
            boolean enabled, String ignoredTitle, int lineNumber, int columnNumber, String codeContext,
            String methodName)
        {
            this.index = index;
            this.type = type;
            this.fqn = fqn;
            this.project = project;
            this.description = description;
            this.optional = optional;
            this.enabled = enabled;
            // ignoredTitle (refactoringTitle) intentionally NOT stored - param is load-bearing for
            // call-site ergonomics only.
            this.lineNumber = lineNumber;
            this.columnNumber = columnNumber;
            this.codeContext = codeContext;
            this.methodName = methodName;
        }
    }

    /** Precise location data for one true textual occurrence of the old name. */
    private static final class ExactMatchInfo
    {
        final String filePath;
        final int matchOffset;
        final int lineNumber;
        final int columnNumber;
        final String codeContext;
        final String methodName;
        final String fqn;
        final String project;

        ExactMatchInfo(String filePath, int matchOffset, int lineNumber, int columnNumber, String codeContext,
            String methodName, String fqn, String project)
        {
            this.filePath = filePath;
            this.matchOffset = matchOffset;
            this.lineNumber = lineNumber;
            this.columnNumber = columnNumber;
            this.codeContext = codeContext;
            this.methodName = methodName;
            this.fqn = fqn;
            this.project = project;
        }
    }

    // -- Helpers --

    private static String escapeMarkdownCell(String s)
    {
        return s == null ? "" : s.replace("|", "\\|"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * EDT 2026.1 restricts {@code bmGetFqn()} to top objects - nested ones throw "may be called on top
     * objects only". Tries in order: own FQN, top-object FQN, null.
     */
    private static String safeTopFqn(IBmObject bmObj)
    {
        if (bmObj == null)
        {
            return null;
        }
        try
        {
            return bmObj.bmGetFqn();
        }
        catch (Exception topObjectsOnly)
        {
            try
            {
                IBmObject top = bmObj.bmGetTopObject();
                return top != null ? top.bmGetFqn() : null;
            }
            catch (Exception e)
            {
                return null;
            }
        }
    }

    private static String getBslFqn(IFile file)
    {
        if (file == null)
        {
            return null;
        }
        return fallbackBslFqn(file.getFullPath());
    }

    private static String fallbackBslFqn(IPath path)
    {
        if (path == null || path.segmentCount() < 4 || !"src".equals(path.segment(0))) //$NON-NLS-1$
        {
            return null;
        }
        String topLevelType;
        switch (path.segment(1))
        {
            case "CommonModules": //$NON-NLS-1$
                topLevelType = "CommonModule"; //$NON-NLS-1$
                break;
            case "Catalogs": //$NON-NLS-1$
                topLevelType = "Catalog"; //$NON-NLS-1$
                break;
            case "Documents": //$NON-NLS-1$
                topLevelType = "Document"; //$NON-NLS-1$
                break;
            case "Enums": //$NON-NLS-1$
                topLevelType = "Enum"; //$NON-NLS-1$
                break;
            case "Reports": //$NON-NLS-1$
                topLevelType = "Report"; //$NON-NLS-1$
                break;
            case "DataProcessors": //$NON-NLS-1$
                topLevelType = "DataProcessor"; //$NON-NLS-1$
                break;
            case "CommonForms": //$NON-NLS-1$
                topLevelType = "CommonForm"; //$NON-NLS-1$
                break;
            case "CommonCommands": //$NON-NLS-1$
                topLevelType = "CommonCommand"; //$NON-NLS-1$
                break;
            case "HTTPServices": //$NON-NLS-1$
                topLevelType = "HTTPService"; //$NON-NLS-1$
                break;
            case "WebServices": //$NON-NLS-1$
                topLevelType = "WebService"; //$NON-NLS-1$
                break;
            case "WSReferences": //$NON-NLS-1$
                topLevelType = "WSReference"; //$NON-NLS-1$
                break;
            case "InformationRegisters": //$NON-NLS-1$
                topLevelType = "InformationRegister"; //$NON-NLS-1$
                break;
            case "AccumulationRegisters": //$NON-NLS-1$
                topLevelType = "AccumulationRegister"; //$NON-NLS-1$
                break;
            case "AccountingRegisters": //$NON-NLS-1$
                topLevelType = "AccountingRegister"; //$NON-NLS-1$
                break;
            case "CalculationRegisters": //$NON-NLS-1$
                topLevelType = "CalculationRegister"; //$NON-NLS-1$
                break;
            case "BusinessProcesses": //$NON-NLS-1$
                topLevelType = "BusinessProcess"; //$NON-NLS-1$
                break;
            case "Tasks": //$NON-NLS-1$
                topLevelType = "Task"; //$NON-NLS-1$
                break;
            default:
                return null;
        }
        return topLevelType + "." + path.segment(2); //$NON-NLS-1$
    }

    private static IFile getIFile(Change change)
    {
        Object modifiedElement = invokeNoArg(change, "getModifiedElement"); //$NON-NLS-1$
        IFile file = getIFileFromModifiedElement(modifiedElement);
        if (file != null)
        {
            return file;
        }
        Object affected = invokeNoArg(change, "getAffectedObjects"); //$NON-NLS-1$
        if (affected instanceof Object[] arr && arr.length == 1 && arr[0] instanceof IFile f)
        {
            return f;
        }
        return null;
    }

    private static IFile getIFileFromModifiedElement(Object modifiedElement)
    {
        if (modifiedElement == null)
        {
            return null;
        }
        Object file = invokeNoArg(modifiedElement, "getFile"); //$NON-NLS-1$
        return file instanceof IFile f ? f : null;
    }

    private static TextEdit getChangeEdit(Change change)
    {
        Object edit = invokeNoArg(change, "getEdit"); //$NON-NLS-1$
        return edit instanceof TextEdit textEdit ? textEdit : null;
    }

    private static List<TextEdit> getLeafEdits(TextEdit edit)
    {
        List<TextEdit> result = new ArrayList<>();
        collectLeafEdits(edit, result);
        return result;
    }

    private static void collectLeafEdits(TextEdit edit, List<TextEdit> result)
    {
        if (edit == null)
        {
            return;
        }
        TextEdit[] children = edit.getChildren();
        if (children == null || children.length == 0)
        {
            if (edit.getOffset() >= 0 && edit.getLength() >= 0)
            {
                result.add(edit);
            }
        }
        else
        {
            for (TextEdit child : children)
            {
                collectLeafEdits(child, result);
            }
        }
    }

    private static int computeLineNumber(String content, int offset)
    {
        if (content == null)
        {
            return 1;
        }
        int limit = Math.min(offset, content.length());
        int count = 1;
        for (int i = 0; i < limit; i++)
        {
            if (content.charAt(i) == '\n')
            {
                count++;
            }
        }
        return count;
    }

    private static int computeColumnNumber(String content, int offset)
    {
        if (content == null)
        {
            return 1;
        }
        int norm = Math.max(0, Math.min(offset, content.length()));
        int lastLB = Math.max(content.lastIndexOf('\n', norm - 1), content.lastIndexOf('\r', norm - 1));
        return norm - lastLB;
    }

    private static String extractContext(String content, int lineNumber)
    {
        if (content == null || lineNumber < 1)
        {
            return null;
        }
        String[] lines = content.split("\n", -1); //$NON-NLS-1$
        if (lineNumber > lines.length)
        {
            return null;
        }
        int lineIdx = lineNumber - 1;
        int startIdx = Math.max(0, lineIdx - 3);
        int endIdx = Math.min(lines.length - 1, lineIdx + 3);
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i <= endIdx; i++)
        {
            String prefix = (i == lineIdx) ? ">>>" : "   "; //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(String.format("%4d: %s %s\n", Integer.valueOf(i + 1), prefix, //$NON-NLS-1$
                lines[i].replace("\r", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    private static String findContainingMethodAst(Module module, int lineNumber)
    {
        for (com._1c.g5.v8.dt.bsl.model.Method method : module.allMethods())
        {
            int startLine = BslModuleAccess.getStartLine(method);
            int endLine = BslModuleAccess.getEndLine(method);
            if (startLine > 0 && lineNumber >= startLine && lineNumber <= endLine)
            {
                return BslModuleAccess.buildSignature(method);
            }
        }
        return null;
    }

    private static String findContainingMethodText(String content, int lineNumber)
    {
        if (content == null || lineNumber < 1)
        {
            return null;
        }
        String[] lines = content.split("\n", -1); //$NON-NLS-1$
        if (lineNumber > lines.length)
        {
            return null;
        }
        int lineIdx = lineNumber - 1;
        for (int i = lineIdx - 1; i >= 0; i--)
        {
            String trimmed = lines[i].trim().replace("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (BslModuleAccess.METHOD_START_PATTERN.matcher(trimmed).find())
            {
                StringBuilder method = new StringBuilder();
                // Prepend any leading BSL method annotations (&-directives).
                for (int k = i - 1; k >= 0; k--)
                {
                    String kt = lines[k].trim();
                    if (kt.startsWith("&")) //$NON-NLS-1$
                    {
                        method.insert(0, kt + " "); //$NON-NLS-1$
                    }
                    else
                    {
                        break;
                    }
                }
                method.append(trimmed);
                return method.toString();
            }
            if (BslModuleAccess.METHOD_END_PATTERN.matcher(trimmed).find())
            {
                break;
            }
        }
        return null;
    }

    private static boolean isBslReferenceChange(Change change, String currentFqn)
    {
        if (currentFqn != null && !currentFqn.isBlank())
        {
            return true;
        }
        if (change instanceof BmObjectTextContentChange<?>)
        {
            return true;
        }
        IFile file = getIFile(change);
        return file != null && getBslFqn(file) != null;
    }

    private static boolean isFullTextSearchSourceFileChange(Change change)
    {
        return isInstanceOf(change, "com._1c.g5.v8.dt.lcore.refactoring.FullTextSearchSourceFileChange"); //$NON-NLS-1$
    }

    private static boolean isCustomSourceFileChange(Change change)
    {
        return isInstanceOf(change, "com._1c.g5.v8.dt.lcore.refactoring.CustomSourceFileChange") //$NON-NLS-1$
            && !isFullTextSearchSourceFileChange(change);
    }

    // -- Reflection helpers (hide the cross-bundle class loading) --

    private static Object invokeMethod(Object target, String methodName, Class<?>[] parameterTypes,
        Object... args) throws Exception
    {
        Method m = findMethod(target.getClass(), methodName, parameterTypes);
        if (!m.canAccess(target))
        {
            m.setAccessible(true);
        }
        return m.invoke(target, args);
    }

    private static Object invokeNoArg(Object target, String methodName)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            return invokeMethod(target, methodName, new Class<?>[0]);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String methodName, Class<?>... parameterTypes)
        throws NoSuchMethodException
    {
        Class<?> current = type;
        while (current != null)
        {
            try
            {
                return current.getDeclaredMethod(methodName, parameterTypes);
            }
            catch (NoSuchMethodException e)
            {
                // try interfaces
                for (Class<?> iface : current.getInterfaces())
                {
                    try
                    {
                        return iface.getMethod(methodName, parameterTypes);
                    }
                    catch (NoSuchMethodException ignored)
                    {
                        // continue
                    }
                }
                current = current.getSuperclass();
            }
        }
        return type.getMethod(methodName, parameterTypes);
    }

    private static Class<?> getClassOrThrow(String className) throws ClassNotFoundException
    {
        try
        {
            return Class.forName(className);
        }
        catch (ClassNotFoundException e)
        {
            Bundle bundle = getOwningBundle(className);
            if (bundle == null)
            {
                Activator.logWarning("rename_metadata_object: could not find an owning bundle for class " //$NON-NLS-1$
                    + className);
                throw e;
            }
            return bundle.loadClass(className);
        }
    }

    private static Bundle getOwningBundle(String className)
    {
        String bundleId;
        if (className.startsWith("com._1c.g5.v8.dt.bsl.ui.")) //$NON-NLS-1$
        {
            bundleId = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
        }
        else if (className.startsWith("com._1c.g5.v8.dt.bsl.bm.ui.")) //$NON-NLS-1$
        {
            bundleId = "com._1c.g5.v8.dt.bsl.bm.ui"; //$NON-NLS-1$
        }
        else if (className.startsWith("com._1c.g5.v8.dt.search.core.")) //$NON-NLS-1$
        {
            bundleId = "com._1c.g5.v8.dt.search.core"; //$NON-NLS-1$
        }
        else if (className.startsWith("com._1c.g5.v8.dt.internal.search.core.")) //$NON-NLS-1$
        {
            bundleId = "com._1c.g5.v8.dt.search.core"; //$NON-NLS-1$
        }
        else if (className.startsWith("com._1c.g5.v8.dt.core.platform.management.")) //$NON-NLS-1$
        {
            bundleId = "com._1c.g5.v8.dt.core"; //$NON-NLS-1$
        }
        else
        {
            return null;
        }
        Bundle bundle = Platform.getBundle(bundleId);
        if (bundle == null)
        {
            Activator.logWarning("rename_metadata_object: could not resolve bundle: " + bundleId); //$NON-NLS-1$
        }
        return bundle;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object getEnumConstant(String className, String constantName) throws Exception
    {
        Class<Enum> enumClass = (Class<Enum>)getClassOrThrow(className);
        return Enum.valueOf(enumClass, constantName);
    }

    private static Object arrayOf(Class<?> componentType, Object... values)
    {
        Object array = Array.newInstance(componentType, values.length);
        for (int i = 0; i < values.length; i++)
        {
            Array.set(array, i, values[i]);
        }
        return array;
    }

    private static boolean isInstanceOf(Object value, String className)
    {
        try
        {
            return getClassOrThrow(className).isInstance(value);
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }

    private static Object getBslInjector() throws Exception
    {
        Class<?> activatorClass = getClassOrThrow("com._1c.g5.v8.dt.bsl.ui.internal.BslActivator"); //$NON-NLS-1$
        Object activator = activatorClass.getMethod("getInstance").invoke(null); //$NON-NLS-1$
        return activatorClass.getMethod("getInjector", String.class).invoke(activator, //$NON-NLS-1$
            "com._1c.g5.v8.dt.bsl.Bsl"); //$NON-NLS-1$
    }

    private static Object getSearchCoreInjector() throws Exception
    {
        Class<?> pluginClass =
            getClassOrThrow("com._1c.g5.v8.dt.internal.search.core.SearchCorePlugin"); //$NON-NLS-1$
        Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
        return pluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
    }

    private static Object createRenameElementContext(EObject targetObject) throws Exception
    {
        Class<?> contextClass =
            getClassOrThrow("com._1c.g5.v8.dt.bsl.bm.ui.refactoring.ConfigurationObjectRenameElementContext"); //$NON-NLS-1$
        Constructor<?> c =
            contextClass.getDeclaredConstructor(URI.class, EClass.class, IBmObject.class);
        c.setAccessible(true);
        return c.newInstance(EcoreUtil.getURI(targetObject), targetObject.eClass(), targetObject);
    }

    private static Object getFieldValue(Object target, String fieldName)
    {
        if (target == null)
        {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field f = type.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            }
            catch (NoSuchFieldException e)
            {
                type = type.getSuperclass();
            }
            catch (Exception e)
            {
                return null;
            }
        }
        return null;
    }

    private static EStructuralFeature getBmChangeFeature(BmObjectTextContentChange<?> change)
    {
        Object feature = getFieldValue(change, "feature"); //$NON-NLS-1$
        return feature instanceof EStructuralFeature sf ? sf : null;
    }

    private static String getFeatureText(EObject object, EStructuralFeature feature)
    {
        if (object == null || feature == null)
        {
            return null;
        }
        Object value = object.eGet(feature);
        return value instanceof String text ? text : null;
    }

    private static int intValue(Object obj)
    {
        return obj instanceof Number ? ((Number)obj).intValue() : 0;
    }

    private static long longValue(Object obj)
    {
        return obj instanceof Number ? ((Number)obj).longValue() : 0L;
    }

    // -- FQN resolution --

    private MdObject resolveObject(Configuration config, String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String[] segments = fqn.split("\\."); //$NON-NLS-1$
        if (segments.length < 2)
        {
            return null;
        }
        MdObject topObject = MetadataTypeCatalog.findObject(config, segments[0], segments[1]);
        if (topObject == null || segments.length == 2)
        {
            return topObject;
        }
        MdObject current = topObject;
        for (int i = 2; i + 1 < segments.length; i += 2)
        {
            current = findChild(current, segments[i], segments[i + 1]);
            if (current == null)
            {
                return null;
            }
        }
        return current;
    }

    /**
     * Finds a named child of a parent metadata object by its collection type. Supports only the 4 child
     * types this tool renames: Attribute, TabularSection, Dimension, Resource.
     */
    @SuppressWarnings("unchecked")
    private MdObject findChild(MdObject parent, String childType, String childName)
    {
        String type = childType.toLowerCase(Locale.ROOT);
        String getterName;
        switch (type)
        {
            case "attribute": //$NON-NLS-1$
            case "attributes": //$NON-NLS-1$
            case "реквизит": // реквизит
            case "реквизиты": // реквизиты
                getterName = "getAttributes"; //$NON-NLS-1$
                break;
            case "tabularsection": //$NON-NLS-1$
            case "tabularsections": //$NON-NLS-1$
            case "табличнаячасть": // табличнаячасть
            case "табличныечасти": // табличныечасти
                getterName = "getTabularSections"; //$NON-NLS-1$
                break;
            case "dimension": //$NON-NLS-1$
            case "dimensions": //$NON-NLS-1$
            case "измерение": // измерение
            case "измерения": // измерения
                getterName = "getDimensions"; //$NON-NLS-1$
                break;
            case "resource": //$NON-NLS-1$
            case "resources": //$NON-NLS-1$
            case "ресурс": // ресурс
            case "ресурсы": // ресурсы
                getterName = "getResources"; //$NON-NLS-1$
                break;
            default:
                return null;
        }
        try
        {
            Method method = parent.getClass().getMethod(getterName);
            Object result = method.invoke(parent);
            if (result instanceof EList)
            {
                EList<? extends MdObject> children = (EList<? extends MdObject>)result;
                for (MdObject child : children)
                {
                    if (childName.equalsIgnoreCase(child.getName()))
                    {
                        return child;
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Could not find child " + childType + "." + childName, e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }
}
