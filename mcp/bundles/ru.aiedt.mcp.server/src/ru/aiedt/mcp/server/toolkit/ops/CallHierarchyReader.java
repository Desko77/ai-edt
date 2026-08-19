/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MarkdownTableHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Reports the call hierarchy of a BSL method: either the callers of a method (who invokes it) or its
 * callees (what it invokes). Callers are resolved through the Xtext reference index, which is built
 * from the BM-aware BSL model, so a result is a semantic reference rather than a text match.
 */
@SuppressWarnings("restriction")
public class CallHierarchyReader
    implements IMcpTool
{
    /** The tool name, also the registry key. */
    public static final String NAME = "get_method_call_hierarchy"; //$NON-NLS-1$

    private static final String DESCRIPTION =
        "Back-compat alias of `code_search` `operation=call_hierarchy`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Maps a BSL method's call graph: either who calls it, or what it calls. " //$NON-NLS-1$
            + "Driven by the semantic BM index (callers/callees), so it is not a plain-text scan."; //$NON-NLS-1$

    private static final String DIRECTION_CALLERS = "callers"; //$NON-NLS-1$
    private static final String DIRECTION_CALLEES = "callees"; //$NON-NLS-1$

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int MIN_LIMIT = 1;
    private static final int CALL_TEXT_TRUNCATE_AT = 100;

    /**
     * The furthest a transitive walk will go.
     * <p>
     * Five, because each level multiplies the index lookups by the fan-in of the level before it,
     * and on a configuration where a common-module method is called from three hundred places the
     * second level is already the whole project. The limit stops it before the depth does; this
     * stops somebody asking for a walk that could never finish.
     * </p>
     */
    private static final int MAX_DEPTH = 5;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return DESCRIPTION;
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "The EDT project to look in (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path under src/, e.g. 'CommonModules/MyModule/Module.bsl', " //$NON-NLS-1$
                    + "or a module FQN such as 'CommonModule.MyModule' / 'Catalog.Products.ManagerModule' (required)", //$NON-NLS-1$
                true)
            .stringProperty("methodName", "Procedure or function name; case is ignored when matching (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("direction", //$NON-NLS-1$
                "Which direction to walk: 'callers' (who invokes this method, the default) or 'callees' (what it invokes)") //$NON-NLS-1$
            .integerProperty("depth", //$NON-NLS-1$
                "How many levels of callers to follow (default 1, max " + MAX_DEPTH + "). Only for " //$NON-NLS-1$ //$NON-NLS-2$
                    + "direction=callers: a callee is reported by name, and a name alone does not " //$NON-NLS-1$
                    + "say which module to follow it into.") //$NON-NLS-1$
            .integerProperty("limit", "Ceiling on how many results come back. Defaults to 100, up to 500") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        String direction = JsonUtils.extractStringArgument(params, "direction"); //$NON-NLS-1$
        if (methodName != null && !methodName.isEmpty())
        {
            return "call-hierarchy-" + methodName.toLowerCase() + "-" + //$NON-NLS-1$ //$NON-NLS-2$
                (direction != null ? direction : DIRECTION_CALLERS) + ".md"; //$NON-NLS-1$
        }
        return "call-hierarchy.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", DEFAULT_LIMIT); //$NON-NLS-1$
        int depth = JsonUtils.extractIntArgument(params, "depth", 1); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: the projectName parameter is required"; //$NON-NLS-1$
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: " + TextSuggest.missingParam("modulePath", "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (methodName == null || methodName.isEmpty())
        {
            return "Error: " + TextSuggest.missingParam("methodName", "MyProcedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        String direction = JsonUtils.extractStringArgument(params, "direction"); //$NON-NLS-1$
        if (direction == null || direction.isEmpty())
        {
            direction = DIRECTION_CALLERS;
        }
        else
        {
            direction = direction.toLowerCase();
            if (!DIRECTION_CALLERS.equals(direction) && !DIRECTION_CALLEES.equals(direction))
            {
                return "Error: direction must be either 'callers' or 'callees'"; //$NON-NLS-1$
            }
        }

        limit = Math.min(Math.max(MIN_LIMIT, limit), MAX_LIMIT);
        depth = Math.min(Math.max(1, depth), MAX_DEPTH);
        if (depth > 1 && DIRECTION_CALLEES.equals(direction))
        {
            // Said rather than quietly ignored. A callee is found as a NAME in the caller's text;
            // which module that name belongs to is a resolution step this does not do, so there is
            // nothing to recurse into. Silently walking one level would answer a question about
            // three with an answer about one.
            return "Error: depth applies to direction=callers only. A callee is reported by name, " //$NON-NLS-1$
                + "and a name does not say which module to follow it into - use resolve_symbol on " //$NON-NLS-1$
                + "a callee, then ask for its own callees."; //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        BslModuleAccess.ModulePathResolution resolution = BslModuleAccess.resolveModulePath(project, modulePath);
        if (!resolution.isResolved())
        {
            return resolution.getHint();
        }
        final String resolvedModulePath = resolution.getPath();

        final String dir = direction;
        final int maxResults = limit;
        final int levels = depth;
        try
        {
            return UiSync.call(() -> {
                if (!DIRECTION_CALLERS.equals(dir))
                {
                    return findCallees(projectName, resolvedModulePath, methodName, maxResults);
                }
                return levels > 1
                    ? findCallersTransitively(projectName, resolvedModulePath, methodName, maxResults, levels)
                    : findCallers(projectName, resolvedModulePath, methodName, maxResults);
            });
        }
        catch (Exception e)
        {
            Activator.logError("Call hierarchy computation raised an exception", e); //$NON-NLS-1$
            return "Error: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Finds every caller of the method through the Xtext reference index.
     *
     * @param projectName the project name
     * @param modulePath the resolved {@code src/}-relative module path
     * @param methodName the method name, case-insensitive
     * @param limit the maximum number of callers to collect
     * @return the formatted Markdown report
     */
    private String findCallers(String projectName, String modulePath, String methodName, int limit)
    {
        Harvest harvest = harvestCallers(projectName, modulePath, methodName, limit);
        return harvest.error != null ? harvest.error
            : formatCallersOutput(modulePath, methodName, harvest.callers, limit, harvest.total);
    }

    /**
     * Collects the callers without formatting them.
     * <p>
     * Split out because a transitive walk needs the rows rather than the report, and because the
     * walk feeds each row's own module and method straight back in - which only works while they
     * are still values rather than table cells.
     * </p>
     *
     * @param projectName the project name
     * @param modulePath the resolved {@code src/}-relative module path
     * @param methodName the method name, case-insensitive
     * @param limit the maximum number of callers to collect
     * @return the rows, or a harvest carrying the reason there are none
     */
    private Harvest harvestCallers(String projectName, String modulePath, String methodName, int limit)
    {
        Harvest harvest = new Harvest();
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            harvest.error = "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
            return harvest;
        }

        Module module = BslModuleAccess.loadModule(project, modulePath);
        if (module == null)
        {
            harvest.error = "Error: could not load the EMF model for " + modulePath //$NON-NLS-1$
                + ". The call hierarchy needs the BSL AST (EMF) to build it. Check the EDT Error Log for details."; //$NON-NLS-1$
            return harvest;
        }

        Method method = BslModuleAccess.findMethod(module, methodName);
        if (method == null)
        {
            harvest.error =
                BslModuleAccess.buildMethodNotFoundResponse(module, modulePath, methodName);
            return harvest;
        }

        URI methodUri = EcoreUtil.getURI(method);

        IResourceServiceProvider rsp =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BslModuleAccess.BSL_LOOKUP_URI);
        if (rsp == null)
        {
            harvest.error = "Error: the BSL resource service provider cannot be reached"; //$NON-NLS-1$
            return harvest;
        }

        IReferenceFinder finder = rsp.get(IReferenceFinder.class);
        if (finder == null)
        {
            harvest.error = "Error: the reference finder cannot be reached"; //$NON-NLS-1$
            return harvest;
        }

        List<CallerInfo> callers = new ArrayList<>();
        List<URI> targetURIs = new ArrayList<>();
        targetURIs.add(methodUri);
        final int[] totalReferences = {0};
        final org.eclipse.emf.ecore.resource.ResourceSet sharedResourceSet =
            new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl();

        try
        {
            org.eclipse.xtext.resource.XtextResourceFactory factory =
                rsp.get(org.eclipse.xtext.resource.XtextResourceFactory.class);
            if (factory != null)
            {
                sharedResourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("bsl", factory); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Registering the XtextResourceFactory failed: " + e.getMessage()); //$NON-NLS-1$
        }

        try
        {
            finder.findAllReferences(targetURIs, null, (IReferenceDescription refDesc) -> {
                totalReferences[0]++;
                if (callers.size() < limit)
                {
                    CallerInfo caller = extractCallerInfo(refDesc, sharedResourceSet);
                    if (caller != null)
                    {
                        callers.add(caller);
                    }
                }
            }, new NullProgressMonitor());
        }
        catch (Exception e)
        {
            Activator.logError("Collecting callers raised an exception", e); //$NON-NLS-1$
        }
        finally
        {
            for (org.eclipse.emf.ecore.resource.Resource res : sharedResourceSet.getResources())
            {
                try
                {
                    res.unload();
                }
                catch (Exception ignore)
                {
                    // best-effort cleanup
                }
            }
            sharedResourceSet.getResources().clear();
        }

        harvest.callers = callers;
        harvest.total = totalReferences[0];
        return harvest;
    }

    /**
     * Extracts the caller's module path, enclosing method, line and call snippet from a reference
     * description, resolving the source EObject through the shared resource set.
     *
     * @param refDesc the reference description
     * @param sharedResourceSet the shared resource set used to load source resources
     * @return the caller info, or {@code null} when the reference has no source EObject
     */
    private CallerInfo extractCallerInfo(IReferenceDescription refDesc,
        org.eclipse.emf.ecore.resource.ResourceSet sharedResourceSet)
    {
        URI sourceUri = refDesc.getSourceEObjectUri();
        if (sourceUri == null)
        {
            return null;
        }

        CallerInfo caller = new CallerInfo();
        caller.modulePath = BslModuleAccess.extractModulePath(sourceUri.path());

        try
        {
            URI resourceUri = sourceUri.trimFragment();
            org.eclipse.emf.ecore.resource.Resource resource = sharedResourceSet.getResource(resourceUri, true);
            if (resource != null && sourceUri.fragment() != null)
            {
                EObject eObject = resource.getEObject(sourceUri.fragment());
                if (eObject != null)
                {
                    String refName = null;
                    if (eObject instanceof StaticFeatureAccess)
                    {
                        refName = ((StaticFeatureAccess)eObject).getName();
                    }
                    else if (eObject instanceof DynamicFeatureAccess)
                    {
                        refName = ((DynamicFeatureAccess)eObject).getName();
                    }

                    EObject current = eObject;
                    while (current != null && !(current instanceof Invocation))
                    {
                        current = current.eContainer();
                    }
                    EObject invocationObj = current;

                    INode callNode = invocationObj instanceof Invocation
                        ? NodeModelUtils.findActualNodeFor(invocationObj)
                        : NodeModelUtils.findActualNodeFor(eObject);
                    if (callNode != null)
                    {
                        caller.line = callNode.getStartLine();
                        String text = callNode.getText();
                        text = stripCommentLines(text);
                        if (text.length() > CALL_TEXT_TRUNCATE_AT)
                        {
                            text = smartTruncateCall(text, refName);
                        }
                        caller.callCode = text;
                    }

                    EObject parent = eObject;
                    while (parent != null && !(parent instanceof Method))
                    {
                        parent = parent.eContainer();
                    }
                    if (parent instanceof Method)
                    {
                        caller.callerMethodName = ((Method)parent).getName();
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Reading caller details raised an exception", e); //$NON-NLS-1$
        }
        return caller;
    }

    /**
     * Collapses a multi-line call snippet to one line, dropping comment-only lines.
     *
     * @param text the raw node text
     * @return the collapsed text, or the trimmed input when every line was a comment
     */
    private String stripCommentLines(String text)
    {
        if (text == null || text.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\r?\\n")) //$NON-NLS-1$
        {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) //$NON-NLS-1$
            {
                continue;
            }
            if (sb.length() > 0)
            {
                sb.append(' ');
            }
            sb.append(trimmed);
        }
        return sb.length() > 0 ? sb.toString() : text.trim();
    }

    /**
     * Truncates a call snippet so it stays readable, preferring to cut right after the method name.
     *
     * @param text the call text
     * @param methodName the referenced method name, may be {@code null}
     * @return the truncated text
     */
    private String smartTruncateCall(String text, String methodName)
    {
        if (methodName != null && !methodName.isEmpty())
        {
            int idx = text.indexOf(methodName);
            if (idx >= 0)
            {
                return text.substring(0, idx + methodName.length()) + "(...)"; //$NON-NLS-1$
            }
        }
        return text.substring(0, Math.min(text.length(), CALL_TEXT_TRUNCATE_AT)) + "..."; //$NON-NLS-1$
    }

    /**
     * Walks the method's AST and collects every invocation it makes.
     *
     * @param projectName the project name
     * @param modulePath the resolved {@code src/}-relative module path
     * @param methodName the method name, case-insensitive
     * @param limit the maximum number of callees to collect
     * @return the formatted Markdown report
     */
    private String findCallees(String projectName, String modulePath, String methodName, int limit)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        Module module = BslModuleAccess.loadModule(project, modulePath);
        if (module == null)
        {
            return "Error: could not load the EMF model for " + modulePath //$NON-NLS-1$
                + ". The call hierarchy needs the BSL AST (EMF) to build it. Check the EDT Error Log for details."; //$NON-NLS-1$
        }

        Method method = BslModuleAccess.findMethod(module, methodName);
        if (method == null)
        {
            return BslModuleAccess.buildMethodNotFoundResponse(module, modulePath, methodName);
        }

        List<CalleeInfo> callees = new ArrayList<>();
        int totalInvocations = 0;
        Iterator<EObject> iter = method.eAllContents();
        while (iter.hasNext())
        {
            EObject obj = iter.next();
            String calledName = null;
            int line = 0;
            if (obj instanceof Invocation)
            {
                Invocation inv = (Invocation)obj;
                EObject methodAccess = inv.getMethodAccess();
                if (methodAccess instanceof StaticFeatureAccess)
                {
                    calledName = ((StaticFeatureAccess)methodAccess).getName();
                }
                else if (methodAccess instanceof DynamicFeatureAccess)
                {
                    calledName = ((DynamicFeatureAccess)methodAccess).getName();
                }
                line = BslModuleAccess.getStartLine(inv);
            }
            if (calledName != null && !calledName.isEmpty())
            {
                totalInvocations++;
                if (callees.size() < limit)
                {
                    CalleeInfo callee = new CalleeInfo();
                    callee.calledMethodName = calledName;
                    callee.line = line;
                    INode node = NodeModelUtils.findActualNodeFor(obj);
                    if (node != null)
                    {
                        String text = node.getText();
                        if (text != null)
                        {
                            text = stripCommentLines(text);
                            if (text.length() > CALL_TEXT_TRUNCATE_AT)
                            {
                                text = smartTruncateCall(text, calledName);
                            }
                            callee.callCode = text;
                        }
                    }
                    callees.add(callee);
                }
            }
        }
        return formatCalleesOutput(modulePath, methodName, callees, limit, totalInvocations);
    }

    /**
     * Walks callers of callers, to a depth.
     * <p>
     * Breadth-first, so the nearest callers are reported before the distant ones and a limit cuts
     * the far end rather than a branch. Every method already seen is skipped: recursion in BSL is
     * ordinary, mutual recursion between two modules is not rare, and without that check the first
     * cycle turns the walk into an infinite one.
     * </p>
     *
     * @param projectName the project name
     * @param modulePath the starting module
     * @param methodName the starting method
     * @param limit the most rows to collect in total, across every level
     * @param depth how many levels to walk
     * @return the report
     */
    private String findCallersTransitively(String projectName, String modulePath, String methodName,
        int limit, int depth)
    {
        List<CallerInfo> collected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(key(modulePath, methodName));
        List<String[]> frontier = new ArrayList<>();
        frontier.add(new String[] {modulePath, methodName});
        String firstError = null;
        int reachedDepth = 0;

        for (int level = 1; level <= depth && !frontier.isEmpty() && collected.size() < limit; level++)
        {
            List<String[]> next = new ArrayList<>();
            for (String[] target : frontier)
            {
                if (collected.size() >= limit)
                {
                    break;
                }
                Harvest harvest = harvestCallers(projectName, target[0], target[1], limit - collected.size());
                if (harvest.error != null)
                {
                    // One unreadable module must not lose the whole walk - the rest of the frontier
                    // is still worth following, and the reason is reported at the end.
                    if (firstError == null)
                    {
                        firstError = harvest.error;
                    }
                    continue;
                }
                for (CallerInfo caller : harvest.callers)
                {
                    if (collected.size() >= limit)
                    {
                        break;
                    }
                    caller.level = level;
                    caller.throughMethod = level > 1 ? target[1] : null;
                    if (seen.add(key(caller.modulePath, caller.callerMethodName)))
                    {
                        collected.add(caller);
                        if (caller.modulePath != null && caller.callerMethodName != null)
                        {
                            next.add(new String[] {caller.modulePath, caller.callerMethodName});
                        }
                    }
                }
                reachedDepth = Math.max(reachedDepth, level);
            }
            frontier = next;
        }
        if (collected.isEmpty() && firstError != null)
        {
            return firstError;
        }
        return formatTransitiveOutput(modulePath, methodName, collected, limit, depth, reachedDepth,
            !frontier.isEmpty());
    }

    /**
     * The identity a method is remembered by while walking.
     *
     * @param modulePath the module, possibly null
     * @param methodName the method, possibly null
     * @return a key that is case-insensitive on the method, as BSL is
     */
    private static String key(String modulePath, String methodName)
    {
        return (modulePath == null ? "" : modulePath) + "::" //$NON-NLS-1$ //$NON-NLS-2$
            + (methodName == null ? "" : methodName.toLowerCase(Locale.ROOT)); //$NON-NLS-1$
    }

    /**
     * Formats a transitive walk, one row per caller, with the level it was found at.
     *
     * @param modulePath the starting module
     * @param methodName the starting method
     * @param callers what was found
     * @param limit the limit applied
     * @param requestedDepth how deep the caller asked to go
     * @param reachedDepth how deep the walk actually got
     * @param moreToFollow whether the frontier still had unexplored methods when it stopped
     * @return the Markdown report
     */
    private String formatTransitiveOutput(String modulePath, String methodName,
        List<CallerInfo> callers, int limit, int requestedDepth, int reachedDepth, boolean moreToFollow)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Method Call Graph: ").append(modulePath).append(" :: ").append(methodName) //$NON-NLS-1$ //$NON-NLS-2$
            .append("\n\n"); //$NON-NLS-1$
        sb.append("**Direction:** callers, followed ").append(reachedDepth) //$NON-NLS-1$
            .append(" level").append(reachedDepth == 1 ? "" : "s") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .append(" of ").append(requestedDepth).append(" asked for\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Distinct callers found:** ").append(callers.size()); //$NON-NLS-1$
        if (callers.size() >= limit && moreToFollow)
        {
            sb.append(" (the limit stopped the walk; raise `limit` or lower `depth`)"); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (callers.isEmpty())
        {
            sb.append("Nothing calls this method.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| # | Level | Module | Enclosing Method | Reaches It Through | Line | Call Snippet |\n"); //$NON-NLS-1$
        sb.append("|---|-------|--------|------------------|--------------------|------|-------------|\n"); //$NON-NLS-1$
        for (int i = 0; i < callers.size(); i++)
        {
            CallerInfo caller = callers.get(i);
            sb.append("| ").append(i + 1).append(" | ").append(caller.level) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" | ").append(MarkdownTableHelper.escapeForTable( //$NON-NLS-1$
                    caller.modulePath != null ? caller.modulePath : "-")) //$NON-NLS-1$
                .append(" | ").append(MarkdownTableHelper.escapeForTable( //$NON-NLS-1$
                    caller.callerMethodName != null ? caller.callerMethodName : "-")) //$NON-NLS-1$
                .append(" | ").append(MarkdownTableHelper.escapeForTable( //$NON-NLS-1$
                    caller.throughMethod != null ? caller.throughMethod : "-")) //$NON-NLS-1$
                .append(" | ").append(caller.line > 0 ? String.valueOf(caller.line) : "-") //$NON-NLS-1$ //$NON-NLS-2$
                .append(" | `").append(MarkdownTableHelper.escapeForTable( //$NON-NLS-1$
                    caller.callCode != null ? caller.callCode : "-")).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    /** What a caller harvest produced, or why it produced nothing. */
    private static final class Harvest
    {
        String error;

        List<CallerInfo> callers = new ArrayList<>();

        int total;
    }

    /**
     * Formats the callers report as Markdown.
     *
     * @param modulePath the module path
     * @param methodName the method name
     * @param callers the collected callers
     * @param limit the limit applied
     * @param totalReferences the total number of references found
     * @return the Markdown report
     */
    private String formatCallersOutput(String modulePath, String methodName, List<CallerInfo> callers, int limit,
        int totalReferences)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Method Call Graph: ").append(modulePath).append(" :: ").append(methodName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Direction:** callers (methods that invoke this one)\n"); //$NON-NLS-1$
        sb.append("**References found:** ").append(totalReferences); //$NON-NLS-1$
        if (callers.size() < totalReferences)
        {
            sb.append(" (first ").append(callers.size()).append(" shown)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (callers.isEmpty())
        {
            sb.append("Nothing calls this method.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| # | Module | Enclosing Method | Line | Call Snippet |\n"); //$NON-NLS-1$
        sb.append("|---|--------|--------|------|-----------|\n"); //$NON-NLS-1$
        for (int i = 0; i < callers.size(); i++)
        {
            CallerInfo caller = callers.get(i);
            int idx = i + 1;
            String moduleCol = caller.modulePath != null ? caller.modulePath : "-"; //$NON-NLS-1$
            String methodCol = caller.callerMethodName != null ? caller.callerMethodName : "-"; //$NON-NLS-1$
            String lineCol = caller.line > 0 ? String.valueOf(caller.line) : "-"; //$NON-NLS-1$
            String codeCol = caller.callCode != null ? caller.callCode : "-"; //$NON-NLS-1$
            sb.append("| ").append(idx).append(" | ").append(MarkdownTableHelper.escapeForTable(moduleCol)) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" | ").append(MarkdownTableHelper.escapeForTable(methodCol)) //$NON-NLS-1$
                .append(" | ").append(lineCol) //$NON-NLS-1$
                .append(" | `").append(MarkdownTableHelper.escapeForTable(codeCol)).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    /**
     * Formats the callees report as Markdown.
     *
     * @param modulePath the module path
     * @param methodName the method name
     * @param callees the collected callees
     * @param limit the limit applied
     * @param totalInvocations the total number of invocations found
     * @return the Markdown report
     */
    private String formatCalleesOutput(String modulePath, String methodName, List<CalleeInfo> callees, int limit,
        int totalInvocations)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Method Call Graph: ").append(modulePath).append(" :: ").append(methodName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Direction:** callees (methods this one invokes)\n"); //$NON-NLS-1$
        sb.append("**Calls found:** ").append(totalInvocations); //$NON-NLS-1$
        if (callees.size() < totalInvocations)
        {
            sb.append(" (first ").append(callees.size()).append(" shown)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (callees.isEmpty())
        {
            sb.append("This method does not call anything.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| # | Invoked Method | Line | Call Snippet |\n"); //$NON-NLS-1$
        sb.append("|---|--------------|------|-----------|\n"); //$NON-NLS-1$
        for (int i = 0; i < callees.size(); i++)
        {
            CalleeInfo callee = callees.get(i);
            int idx = i + 1;
            String lineCol = callee.line > 0 ? String.valueOf(callee.line) : "-"; //$NON-NLS-1$
            String codeCol = callee.callCode != null ? callee.callCode : "-"; //$NON-NLS-1$
            sb.append("| ").append(idx).append(" | ").append(MarkdownTableHelper.escapeForTable(callee.calledMethodName)) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" | ").append(lineCol) //$NON-NLS-1$
                .append(" | `").append(MarkdownTableHelper.escapeForTable(codeCol)).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    /** Collected information about one caller of the target method. */
    private static class CallerInfo
    {
        /** {@code src/}-relative module path of the caller. */
        String modulePath;
        /** Name of the method that contains the call. */
        String callerMethodName;
        /** 1-based line of the call, or 0 when unknown. */
        int line;
        /** The call snippet, comment-stripped and truncated. */
        String callCode;
        /** How many hops from the method asked about; 1 for a direct caller. */
        int level = 1;
        /** On a transitive walk, the method of the previous level this one reaches. */
        String throughMethod;
    }

    /** Collected information about one callee invoked by the target method. */
    private static class CalleeInfo
    {
        /** Name of the invoked method. */
        String calledMethodName;
        /** 1-based line of the invocation, or 0 when unknown. */
        int line;
        /** The call snippet, comment-stripped and truncated. */
        String callCode;
    }
}
