/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.osgi.framework.Bundle;

import ru.aiedt.mcp.server.Activator;

/**
 * Puts breakpoints on BSL modules, and takes them off again.
 * <p>
 * All of it is reflection, and it has to be. EDT's BSL breakpoints are implemented in
 * {@code com._1c.g5.v8.dt.internal.debug.core.model.breakpoints}, an internal package that OSGi will
 * not let this bundle import at all, so the classes are reached through the owning bundle's own
 * classloader ({@link Bundle#loadClass(String)}), which ignores the import rules. Importing the debug
 * bundle instead is not an option worth wanting: it would make this plugin fail to resolve on an EDT
 * that ships without it, trading five debug tools for all ninety.
 * </p>
 * <p>
 * Creating a line breakpoint is therefore layered. First EDT's own class, which is what actually runs.
 * Failing that, a raw marker of EDT's marker type, which EDT may adopt into a real breakpoint of its
 * own. Failing even that, a plain Eclipse line-breakpoint marker wrapped in
 * {@link MarkerOnlyBreakpoint} - visible and removable, but it will not stop the 1C debugger, and the
 * tools say so rather than letting an agent believe a breakpoint is armed when it is not.
 * </p>
 */
public final class BreakpointAccess
{
    /** The EDT bundle that owns the BSL breakpoint classes and marker types. */
    private static final String DEBUG_CORE_BUNDLE = "com._1c.g5.v8.dt.debug.core"; //$NON-NLS-1$

    /** EDT's BSL debug model identifier, as its breakpoints report it. */
    private static final String BSL_DEBUG_MODEL_ID = "com._1c.g5.v8.dt.debug"; //$NON-NLS-1$

    /**
     * The hit-condition enum.
     * <p>
     * Note where it lives: {@code debug.core.model.breakpoints}, with no {@code internal} in it, while
     * the breakpoint classes below sit in {@code internal.debug.core.model.breakpoints}. That reads
     * like a typo and is not one - both were checked against the shipped jars. EDT publishes the enum
     * and hides the classes that take it.
     * </p>
     */
    private static final String HIT_CONDITION_ENUM =
        "com._1c.g5.v8.dt.debug.core.model.breakpoints.BslLineBreakpointDebugModel$Condition"; //$NON-NLS-1$

    /** What EDT calls its hit conditions, for the message when an agent invents a fifth. */
    private static final String[] HIT_CONDITIONS = {"EQUALS", "EQUAL_OR_LESS", "EQUAL_OR_HIGHER", "MULTIPLIER"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** The default when a hit count is given without saying how to compare it. */
    private static final String DEFAULT_HIT_CONDITION = "EQUALS"; //$NON-NLS-1$

    /**
     * EDT's line breakpoint, current name first, then the names it went by in earlier EDT versions.
     */
    private static final String[] LINE_BREAKPOINT_CLASSES = {
        "com._1c.g5.v8.dt.internal.debug.core.model.breakpoints.BslLineBreakpoint", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug.core.model.BslLineBreakpoint", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug.bsl.model.BslLineBreakpoint", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug.core.BslLineBreakpoint"}; //$NON-NLS-1$

    /** EDT's line breakpoint marker type, likewise newest first. */
    private static final String[] LINE_BREAKPOINT_MARKERS = {
        "com._1c.g5.v8.dt.debug.core.bslLineBreakpointMarker", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug.bslLineBreakpointMarker", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug.bsl.bslLineBreakpointMarker"}; //$NON-NLS-1$

    private static final String EXCEPTION_BREAKPOINT_CLASS =
        "com._1c.g5.v8.dt.internal.debug.core.model.breakpoints.BslExceptionBreakpoint"; //$NON-NLS-1$

    private static final String RUN_TO_LINE_BREAKPOINT_CLASS =
        "com._1c.g5.v8.dt.internal.debug.core.model.breakpoints.BslRunToLineBreakpoint"; //$NON-NLS-1$

    private static final String SET_CONDITION = "setCondition"; //$NON-NLS-1$
    private static final String SET_HIT_COUNT = "setHitCount"; //$NON-NLS-1$
    private static final String SET_HIT_CONDITION = "setHitCondition"; //$NON-NLS-1$
    private static final String SET_LOG_EXPRESSION = "setExpressionForEvaluation"; //$NON-NLS-1$
    private static final String SET_CONTINUE_EXECUTION = "setContinueExecution"; //$NON-NLS-1$
    private static final String SET_PUT_STACK_TRACE = "setPutStackTrace"; //$NON-NLS-1$
    private static final String SET_EXCEPTION_MESSAGE = "setExceptionMessage"; //$NON-NLS-1$
    private static final String SET_CATCH_ALL = "setCatchAllExceptions"; //$NON-NLS-1$

    private BreakpointAccess()
    {
        // utility
    }

    /**
     * Finds the module a breakpoint request names.
     * <p>
     * Two forms are accepted: a path inside a project, which is taken relative to its {@code src}
     * folder, so {@code CommonModules/Foo/Module.bsl} means what a developer would expect; or an
     * absolute path on disk, which is matched back to a workspace file, and answers nothing when it
     * points outside the workspace.
     * </p>
     * <p>
     * The file that comes back need not exist. Whether it does is the caller's question to ask, and
     * each of them asks it in its own words.
     * </p>
     *
     * @param projectName the project, required unless the module is an absolute path
     * @param module the module, project-relative or absolute
     * @return the file, existing or not, or <code>null</code> when the arguments name nothing
     */
    public static IFile resolveModuleFile(String projectName, String module)
    {
        if (module == null || module.isEmpty())
        {
            return null;
        }

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        if (looksLikeAbsolutePath(module))
        {
            IFile[] found = root.findFilesForLocationURI(new File(module).toURI());
            return found.length == 0 ? null : found[0];
        }

        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }

        IProject project = root.getProject(projectName);
        if (!project.exists())
        {
            return null;
        }
        return project.getFile(IPath.fromOSString("src").append(module)); //$NON-NLS-1$
    }

    /**
     * Whether a path is absolute rather than project-relative.
     * <p>
     * Deliberately crude: a leading separator, or a colon in second place for a Windows drive. It
     * decides one thing only - whether a project name is required alongside - and the callers validate
     * on the answer, so it is left exactly as strict as it is.
     * </p>
     *
     * @param s the path to judge; may be <code>null</code>
     * @return whether it looks absolute
     */
    public static boolean looksLikeAbsolutePath(String s)
    {
        if (s == null || s.isEmpty())
        {
            return false;
        }

        char first = s.charAt(0);
        if (first == '/' || first == '\\')
        {
            return true;
        }
        return s.length() >= 2 && s.charAt(1) == ':';
    }

    /**
     * Puts a line breakpoint on a BSL module.
     *
     * @param file the module file; must not be <code>null</code>
     * @param lineNumber the line, counting from 1
     * @return the breakpoint. A {@link MarkerOnlyBreakpoint} means EDT's own breakpoint could not be
     *         made and the result will not actually stop the debugger
     * @throws IllegalArgumentException if the file is <code>null</code> or the line is below 1
     * @throws Exception if even a plain Eclipse marker cannot be created
     */
    public static IBreakpoint createLineBreakpoint(IFile file, int lineNumber) throws Exception
    {
        requireFile(file);
        requireLine(lineNumber);

        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();

        IBreakpoint edtBreakpoint = createEdtLineBreakpoint(file, lineNumber, manager);
        if (edtBreakpoint != null)
        {
            return edtBreakpoint;
        }

        IBreakpoint fromMarker = createFromEdtMarker(file, lineNumber, manager);
        if (fromMarker != null)
        {
            return fromMarker;
        }

        // Last resort: a plain Eclipse line breakpoint marker. Reachable only on an EDT that has
        // neither the class nor any of its marker types, which is to say: not one we have ever met.
        IMarker marker = file.createMarker(IBreakpoint.LINE_BREAKPOINT_MARKER);
        marker.setAttributes(new String[] {IMarker.LINE_NUMBER, IBreakpoint.ENABLED},
            new Object[] {Integer.valueOf(lineNumber), Boolean.TRUE});
        return registerMarkerOnly(marker, manager);
    }

    /**
     * Puts a breakpoint that stops on a raised 1C exception.
     * <p>
     * Unlike a line breakpoint this has no marker fallback: a marker without EDT's class behind it
     * carries none of the meaning - which exception, caught or not - so there would be nothing to fall
     * back to.
     * </p>
     * <p>
     * Either the breakpoint is configured exactly as asked or none is left behind. An exception
     * breakpoint that quietly failed to become catch-all, or quietly kept a message it was told to
     * change, is a breakpoint that will stop in the wrong places, and it is better for the agent to be
     * told that than to be told nothing.
     * </p>
     *
     * @param resource the resource to hang the breakpoint on; must not be <code>null</code>
     * @param message the exception message to match; <code>null</code> or blank matches on nothing in
     *            particular
     * @param catchAll whether to stop on exceptions that the code catches itself
     * @return the registered breakpoint
     * @throws IllegalArgumentException if the resource is <code>null</code>
     * @throws IllegalStateException if the platform, the EDT debug plugin or one of the breakpoint's
     *             own setters is not there
     * @throws Exception if EDT's breakpoint cannot be created
     */
    public static IBreakpoint createExceptionBreakpoint(IResource resource, String message, boolean catchAll)
        throws Exception
    {
        if (resource == null)
        {
            throw new IllegalArgumentException("a resource must be provided"); //$NON-NLS-1$
        }

        IBreakpointManager manager = requireBreakpointManager();
        Bundle debugCore = requireDebugCore("Exception breakpoint support"); //$NON-NLS-1$

        Class<?> breakpointClass = debugCore.loadClass(EXCEPTION_BREAKPOINT_CLASS);
        Object instance = breakpointClass.getConstructor(IResource.class).newInstance(resource);
        if (!(instance instanceof IBreakpoint))
        {
            throw new IllegalStateException(EXCEPTION_BREAKPOINT_CLASS + " is not a usable Eclipse breakpoint"); //$NON-NLS-1$
        }

        IBreakpoint breakpoint = (IBreakpoint)instance;
        try
        {
            if (message != null && !message.isBlank())
            {
                applyOrFail(breakpoint, SET_EXCEPTION_MESSAGE, String.class, message.trim());
            }
            applyOrFail(breakpoint, SET_CATCH_ALL, boolean.class, Boolean.valueOf(catchAll));
        }
        catch (RuntimeException e)
        {
            // Configured wrongly is worse than absent: take the marker back out before reporting.
            deleteQuietly(breakpoint);
            throw e;
        }

        manager.addBreakpoint(breakpoint);
        return breakpoint;
    }

    /**
     * Puts a one-shot breakpoint used to run to a line. EDT takes it away again once the debugger
     * arrives.
     *
     * @param file the module file; must not be <code>null</code>
     * @param lineNumber the line, counting from 1
     * @return the registered breakpoint
     * @throws IllegalArgumentException if the file is <code>null</code> or the line is below 1
     * @throws IllegalStateException if the platform or the EDT debug plugin is not there
     * @throws Exception if EDT's breakpoint cannot be created
     */
    public static IBreakpoint createRunToLineBreakpoint(IFile file, int lineNumber) throws Exception
    {
        requireFile(file);
        requireLine(lineNumber);

        IBreakpointManager manager = requireBreakpointManager();
        Bundle debugCore = requireDebugCore("Run-to-line breakpoint support"); //$NON-NLS-1$

        Class<?> breakpointClass = debugCore.loadClass(RUN_TO_LINE_BREAKPOINT_CLASS);
        Object instance = breakpointClass.getConstructor(IResource.class, int.class)
            .newInstance(file, Integer.valueOf(lineNumber));
        if (!(instance instanceof IBreakpoint))
        {
            throw new IllegalStateException(RUN_TO_LINE_BREAKPOINT_CLASS + " is not a usable Eclipse breakpoint"); //$NON-NLS-1$
        }

        IBreakpoint breakpoint = (IBreakpoint)instance;
        manager.addBreakpoint(breakpoint);
        return breakpoint;
    }

    /**
     * Removes a breakpoint by the id of its marker, whatever kind of breakpoint it is.
     *
     * @param markerId the marker id, as reported when the breakpoint was created
     * @return whether one was found and removed
     * @throws Exception if the platform refuses the removal
     */
    public static boolean removeBreakpointById(long markerId) throws Exception
    {
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();

        for (IBreakpoint breakpoint : manager.getBreakpoints())
        {
            IMarker marker = breakpoint.getMarker();
            if (marker != null && marker.getId() == markerId)
            {
                manager.removeBreakpoint(breakpoint, true);
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the line breakpoint on a given line of a given file.
     * <p>
     * Line breakpoints only. Exception and run-to-line breakpoints are not bound to a line - they are
     * not {@link ILineBreakpoint}s at all - and are removed by marker id instead.
     * </p>
     *
     * @param file the module file; must not be <code>null</code>
     * @param line the line, counting from 1
     * @return whether one was found and removed
     * @throws Exception if the platform refuses the removal
     */
    public static boolean removeBreakpointAt(IFile file, int line) throws Exception
    {
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();

        for (IBreakpoint breakpoint : manager.getBreakpoints())
        {
            if (!(breakpoint instanceof ILineBreakpoint))
            {
                continue;
            }

            IMarker marker = breakpoint.getMarker();
            if (marker != null && file.equals(marker.getResource())
                && ((ILineBreakpoint)breakpoint).getLineNumber() == line)
            {
                manager.removeBreakpoint(breakpoint, true);
                return true;
            }
        }
        return false;
    }

    /**
     * Removes every breakpoint whose marker id is in the given set.
     * <p>
     * Batch counterpart to {@link #removeBreakpointById(long)}: ids that no current breakpoint
     * carries are silently skipped (they may already be gone). {@link IBreakpointManager
     * #getBreakpoints()} returns a snapshot, so removing while iterating is safe.
     * </p>
     *
     * @param markerIds the marker ids to drop; null / empty removes nothing
     * @return how many breakpoints were removed
     * @throws Exception if the platform refuses a removal
     */
    public static int removeBreakpointsByIds(java.util.Collection<Long> markerIds) throws Exception
    {
        if (markerIds == null || markerIds.isEmpty())
        {
            return 0;
        }
        java.util.Set<Long> wanted = new java.util.HashSet<>(markerIds);
        int removed = 0;
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        for (IBreakpoint breakpoint : manager.getBreakpoints())
        {
            IMarker marker = breakpoint.getMarker();
            if (marker != null && wanted.contains(Long.valueOf(marker.getId())))
            {
                manager.removeBreakpoint(breakpoint, true);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes every breakpoint attached to a resource (a BSL module file).
     * <p>
     * Covers line, exception and run-to-line breakpoints alike - the tie to the resource is the
     * marker, which every breakpoint kind carries. This is the "remove all breakpoints of one
     * module" operation that pairs with the set tool's batch arming.
     * </p>
     *
     * @param resource the module file; null removes nothing
     * @return how many breakpoints were removed
     * @throws Exception if the platform refuses a removal
     */
    public static int removeAllBreakpointsInResource(IResource resource) throws Exception
    {
        if (resource == null)
        {
            return 0;
        }
        int removed = 0;
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        for (IBreakpoint breakpoint : manager.getBreakpoints())
        {
            IMarker marker = breakpoint.getMarker();
            if (marker != null && resource.equals(marker.getResource()))
            {
                manager.removeBreakpoint(breakpoint, true);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes every registered breakpoint the manager is holding, regardless of model.
     * <p>
     * Best-effort per item: one uncooperative breakpoint does not spare the rest.
     * {@link IBreakpointManager#getBreakpoints()} is a snapshot, so iterating-and-removing is safe.
     * </p>
     *
     * @return how many breakpoints were removed
     */
    public static int removeAllBreakpoints()
    {
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        IBreakpoint[] breakpoints = manager.getBreakpoints();
        int removed = 0;
        for (IBreakpoint breakpoint : breakpoints)
        {
            try
            {
                manager.removeBreakpoint(breakpoint, true);
                removed++;
            }
            catch (Exception e)
            {
                Activator.logWarning("Failed to remove a breakpoint while clearing all: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return removed;
    }

    /**
     * Applies the optional settings of a line breakpoint, and reports which of them took.
     * <p>
     * The returned entries go straight into the tool's JSON answer, so they are the wire contract: a
     * setting that worked is echoed under its own name ({@code condition}, {@code hitCount},
     * {@code hitCondition}, {@code logpoint}), and one that did not is flagged
     * ({@code conditionFailed} and so on). Nothing is claimed that was not done - an agent that is told
     * its condition was applied will wait forever at a breakpoint that is stopping on every pass.
     * </p>
     * <p>
     * A logpoint is two steps, and they are reported apart on purpose. If the expression is set but the
     * breakpoint cannot then be told to carry on, it is no longer a logpoint - it is an ordinary
     * breakpoint that will suspend - and saying merely "logpoint failed" would hide a breakpoint that
     * is now armed.
     * </p>
     *
     * @param bp the breakpoint to configure
     * @param condition a BSL expression that must be true to stop; ignored when <code>null</code> or
     *            blank
     * @param hitCount stop on this many hits; ignored when <code>null</code> or not positive
     * @param hitCondition how to compare the hit count: one of EQUALS, EQUAL_OR_LESS, EQUAL_OR_HIGHER,
     *            MULTIPLIER; defaults to EQUALS
     * @param logExpression an expression to evaluate and log without stopping; ignored when
     *            <code>null</code> or blank
     * @return what was applied and what was not, in the order the client should read it; empty when
     *         nothing was asked for
     */
    public static Map<String, Object> applyBreakpointOptions(IBreakpoint bp, String condition, Integer hitCount,
        String hitCondition, String logExpression)
    {
        Map<String, Object> applied = new LinkedHashMap<>();

        boolean wantCondition = condition != null && !condition.isBlank();
        boolean wantHitCount = hitCount != null && hitCount.intValue() > 0;
        boolean wantLogpoint = logExpression != null && !logExpression.isBlank();

        if (!wantCondition && !wantHitCount && !wantLogpoint)
        {
            return applied;
        }

        if (bp instanceof MarkerOnlyBreakpoint)
        {
            applied.put(ErrorTags.OPTIONS_IGNORED.wire(),
                "The EDT BSL breakpoint class could not be loaded (marker-only breakpoint), so condition / hitCount / logpoint settings cannot be applied on this runtime."); //$NON-NLS-1$
            return applied;
        }

        if (wantCondition)
        {
            String expression = condition.trim();
            if (apply(bp, SET_CONDITION, String.class, expression))
            {
                applied.put("condition", expression); //$NON-NLS-1$
            }
            else
            {
                applied.put("conditionFailed", Boolean.TRUE); //$NON-NLS-1$
            }
        }

        if (wantHitCount)
        {
            if (apply(bp, SET_HIT_COUNT, int.class, hitCount))
            {
                applied.put("hitCount", hitCount); //$NON-NLS-1$

                String comparison = applyHitCondition(bp, hitCondition);
                if (comparison != null)
                {
                    applied.put("hitCondition", comparison); //$NON-NLS-1$
                }
                else
                {
                    // A hit count with an unsaid comparison is a breakpoint that stops somewhere the
                    // agent did not ask for. Say so, as every other option here does.
                    applied.put("hitConditionFailed", Boolean.TRUE); //$NON-NLS-1$
                }
            }
            else
            {
                applied.put("hitCountFailed", Boolean.TRUE); //$NON-NLS-1$
            }
        }

        if (wantLogpoint)
        {
            String expression = logExpression.trim();
            if (apply(bp, SET_LOG_EXPRESSION, String.class, expression))
            {
                applied.put("logpoint", expression); //$NON-NLS-1$

                if (apply(bp, SET_CONTINUE_EXECUTION, boolean.class, Boolean.TRUE))
                {
                    applied.put("continueExecution", Boolean.TRUE); //$NON-NLS-1$
                    apply(bp, SET_PUT_STACK_TRACE, boolean.class, Boolean.TRUE);
                }
                else
                {
                    applied.put("continueExecutionFailed", //$NON-NLS-1$
                        "The log expression was set, but the setContinueExecution call failed, so this breakpoint WILL SUSPEND rather than behaving as a pure logpoint."); //$NON-NLS-1$
                }
            }
            else
            {
                applied.put("logpointFailed", Boolean.TRUE); //$NON-NLS-1$
            }
        }

        return applied;
    }

    /**
     * Builds EDT's own line breakpoint and arms it.
     *
     * @param file the module file
     * @param lineNumber the line
     * @param manager the breakpoint manager
     * @return the breakpoint, or <code>null</code> when EDT's class is not to be had
     */
    private static IBreakpoint createEdtLineBreakpoint(IFile file, int lineNumber, IBreakpointManager manager)
    {
        Bundle debugCore = Platform.getBundle(DEBUG_CORE_BUNDLE);
        if (debugCore == null)
        {
            Activator.logError("EDT debug bundle " + DEBUG_CORE_BUNDLE + " is missing", null); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        for (String className : LINE_BREAKPOINT_CLASSES)
        {
            try
            {
                Class<?> breakpointClass = debugCore.loadClass(className);

                Constructor<?> constructor = findResourceLineConstructor(breakpointClass);
                if (constructor == null)
                {
                    continue;
                }

                Object instance = constructor.newInstance(file, Integer.valueOf(lineNumber));
                if (!(instance instanceof IBreakpoint))
                {
                    continue;
                }

                IBreakpoint breakpoint = (IBreakpoint)instance;

                // EDT's constructor writes the marker but stops there. Until the manager is told, the
                // breakpoint is in no view and arms nothing - it simply never fires.
                manager.addBreakpoint(breakpoint);
                return breakpoint;
            }
            catch (ClassNotFoundException e)
            {
                // A name from an older EDT. Try the next.
            }
            catch (Exception e)
            {
                Activator.logError("Failed to construct a BSL line breakpoint using " + className, e); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Writes a marker of EDT's own breakpoint type and lets EDT adopt it if it will.
     *
     * @param file the module file
     * @param lineNumber the line
     * @param manager the breakpoint manager
     * @return the breakpoint EDT made of the marker, or one of ours wrapping it, or <code>null</code>
     *         when none of EDT's marker types exist
     */
    private static IBreakpoint createFromEdtMarker(IFile file, int lineNumber, IBreakpointManager manager)
    {
        for (String markerType : LINE_BREAKPOINT_MARKERS)
        {
            try
            {
                IMarker marker = file.createMarker(markerType);
                marker.setAttributes(new String[] {IMarker.LINE_NUMBER, IBreakpoint.ENABLED, IBreakpoint.ID},
                    new Object[] {Integer.valueOf(lineNumber), Boolean.TRUE, BSL_DEBUG_MODEL_ID});

                // Creating the marker notifies the workspace synchronously, so by now EDT may already
                // have turned it into a breakpoint of its own. If so, that one is the real thing.
                IBreakpoint adopted = manager.getBreakpoint(marker);
                if (adopted != null)
                {
                    return adopted;
                }

                return registerMarkerOnly(marker, manager);
            }
            catch (Exception e)
            {
                Activator.logWarning("Marker kind " + markerType + " was rejected: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    /**
     * @param marker the marker to wrap
     * @param manager the breakpoint manager
     * @return the wrapper, registered
     * @throws CoreException if the manager refuses it
     */
    private static IBreakpoint registerMarkerOnly(IMarker marker, IBreakpointManager manager) throws CoreException
    {
        MarkerOnlyBreakpoint breakpoint = new MarkerOnlyBreakpoint(marker);
        manager.addBreakpoint(breakpoint);
        breakpoint.setRegistered(true);
        return breakpoint;
    }

    /**
     * Finds the constructor that takes a file and a line, whatever EDT calls its parameter types.
     *
     * @param breakpointClass the breakpoint class
     * @return the first public {@code (IResource-ish, int)} constructor, or <code>null</code>
     */
    private static Constructor<?> findResourceLineConstructor(Class<?> breakpointClass)
    {
        for (Constructor<?> candidate : breakpointClass.getConstructors())
        {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length != 2 || parameters[1] != int.class)
            {
                continue;
            }

            if (parameters[0].isAssignableFrom(IFile.class) || parameters[0].isAssignableFrom(IResource.class))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Tells the breakpoint how to compare its hit count.
     * <p>
     * The enum is loaded through the breakpoint's <em>own</em> classloader, which is the only one that
     * can see both it and the class that takes it, and the declaring class is what the setter is looked
     * up by. That last point matters more than it looks: were EDT ever to give one of these constants a
     * body, the constant's runtime class would be an anonymous subclass, and a setter looked up by
     * <em>that</em> would not be found.
     * </p>
     *
     * @param bp the breakpoint
     * @param hitCondition the comparison to use; <code>null</code> or blank means EQUALS
     * @return the comparison that was applied, or <code>null</code> when it could not be
     */
    private static String applyHitCondition(IBreakpoint bp, String hitCondition)
    {
        String name = hitCondition == null || hitCondition.isBlank() ? DEFAULT_HIT_CONDITION
            : hitCondition.trim().toUpperCase(Locale.ROOT);

        try
        {
            Class<?> conditionClass = bp.getClass().getClassLoader().loadClass(HIT_CONDITION_ENUM);
            Object condition = enumConstant(conditionClass, name);

            Method setter = bp.getClass().getMethod(SET_HIT_CONDITION, conditionClass);
            setter.invoke(bp, condition);
            return name;
        }
        catch (IllegalArgumentException e)
        {
            Activator.logWarning(
                "Unrecognized hitCondition '" + name + "'. Valid values are: " + String.join(", ", HIT_CONDITIONS)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        catch (Throwable t)
        {
            Activator.logWarning("Setter " + SET_HIT_CONDITION + " did not apply: " + describe(t)); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * @param enumClass the enum's declaring class
     * @param name the constant to find
     * @return the constant
     * @throws IllegalArgumentException if the enum has no constant of that name
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumClass, String name)
    {
        return Enum.valueOf((Class<Enum>)(Class<?>)enumClass, name);
    }

    /**
     * Calls a setter on a breakpoint, and swallows the failure.
     *
     * @param target the breakpoint
     * @param methodName the setter
     * @param parameterType its parameter type
     * @param value the value to set
     * @return whether it took; a <code>false</code> here always ends up in the response
     */
    private static boolean apply(Object target, String methodName, Class<?> parameterType, Object value)
    {
        try
        {
            target.getClass().getMethod(methodName, parameterType).invoke(target, value);
            return true;
        }
        catch (Throwable t)
        {
            Activator.logWarning("Setter " + methodName + " did not apply: " + describe(t)); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    /**
     * Calls a setter on a breakpoint, and does not swallow the failure.
     *
     * @param target the breakpoint
     * @param methodName the setter
     * @param parameterType its parameter type
     * @param value the value to set
     * @throws IllegalStateException if the setter is missing or throws
     */
    private static void applyOrFail(Object target, String methodName, Class<?> parameterType, Object value)
    {
        try
        {
            target.getClass().getMethod(methodName, parameterType).invoke(target, value);
        }
        catch (Throwable t)
        {
            throw new IllegalStateException(
                "Setter " + methodName + " failed: " + describe(t), unwrap(t)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * @param breakpoint a breakpoint that was built but never registered
     */
    private static void deleteQuietly(IBreakpoint breakpoint)
    {
        try
        {
            breakpoint.delete();
        }
        catch (CoreException e)
        {
            Activator.logWarning("Failed to delete a half-configured breakpoint: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * @param t what reflection threw
     * @return the real cause, since a reflective wrapper carries no message of its own
     */
    private static Throwable unwrap(Throwable t)
    {
        if (t instanceof InvocationTargetException && t.getCause() != null)
        {
            return t.getCause();
        }
        return t;
    }

    /**
     * @param t what reflection threw
     * @return the cause, named and quoted, for a log line or a message
     */
    private static String describe(Throwable t)
    {
        Throwable cause = unwrap(t);
        return cause.getClass().getSimpleName() + ": " + cause.getMessage(); //$NON-NLS-1$
    }

    /**
     * @return Eclipse's breakpoint manager
     * @throws IllegalStateException if the debug platform is not running
     */
    private static IBreakpointManager requireBreakpointManager()
    {
        DebugPlugin plugin = DebugPlugin.getDefault();
        if (plugin == null)
        {
            throw new IllegalStateException("The Eclipse debug platform is unavailable"); //$NON-NLS-1$
        }
        return plugin.getBreakpointManager();
    }

    /**
     * @param feature what the caller wanted, for the message
     * @return the EDT debug bundle
     * @throws IllegalStateException if EDT ships without it
     */
    private static Bundle requireDebugCore(String feature)
    {
        Bundle debugCore = Platform.getBundle(DEBUG_CORE_BUNDLE);
        if (debugCore == null)
        {
            throw new IllegalStateException(
                feature + " requires the EDT debug plugin (" + DEBUG_CORE_BUNDLE + "), which is missing"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return debugCore;
    }

    /**
     * @param file the file to check
     * @throws IllegalArgumentException if it is <code>null</code>
     */
    private static void requireFile(IFile file)
    {
        if (file == null)
        {
            throw new IllegalArgumentException("a file must be provided"); //$NON-NLS-1$
        }
    }

    /**
     * @param lineNumber the line to check
     * @throws IllegalArgumentException if it is below 1
     */
    private static void requireLine(int lineNumber)
    {
        if (lineNumber < 1)
        {
            throw new IllegalArgumentException("lineNumber must be at least 1, but was " + lineNumber); //$NON-NLS-1$
        }
    }

    /**
     * A breakpoint that is really just a marker.
     * <p>
     * What is left when EDT's breakpoint class cannot be reached: it shows in the Breakpoints view, it
     * can be listed and removed, and it will not stop the 1C debugger, because nothing in EDT is
     * watching it. The tools check for this type by name and warn the agent that the breakpoint is
     * degraded, which is the only honest thing to do with it.
     * </p>
     */
    public static final class MarkerOnlyBreakpoint
        implements ILineBreakpoint
    {
        private final IMarker marker;

        /** Not a marker attribute: this breakpoint's registration lives only as long as the session. */
        private boolean registered;

        /**
         * @param marker the marker to present as a breakpoint
         */
        MarkerOnlyBreakpoint(IMarker marker)
        {
            this.marker = marker;
        }

        @Override
        public IMarker getMarker()
        {
            return marker;
        }

        @Override
        public void setMarker(IMarker newMarker) throws CoreException
        {
            // The marker is what this breakpoint is. Swapping it would leave the manager holding a
            // breakpoint that has quietly become a different one.
        }

        @Override
        public String getModelIdentifier()
        {
            return BSL_DEBUG_MODEL_ID;
        }

        @Override
        public boolean isEnabled() throws CoreException
        {
            return marker.getAttribute(IBreakpoint.ENABLED, true);
        }

        @Override
        public void setEnabled(boolean enabled) throws CoreException
        {
            marker.setAttribute(IBreakpoint.ENABLED, enabled);
        }

        @Override
        public boolean isRegistered() throws CoreException
        {
            return registered;
        }

        @Override
        public void setRegistered(boolean registered) throws CoreException
        {
            this.registered = registered;
        }

        @Override
        public boolean isPersisted() throws CoreException
        {
            return marker.getAttribute(IBreakpoint.PERSISTED, true);
        }

        @Override
        public void setPersisted(boolean persisted) throws CoreException
        {
            marker.setAttribute(IBreakpoint.PERSISTED, persisted);
        }

        @Override
        public void delete() throws CoreException
        {
            marker.delete();
        }

        @Override
        public int getLineNumber() throws CoreException
        {
            return marker.getAttribute(IMarker.LINE_NUMBER, -1);
        }

        @Override
        public int getCharStart() throws CoreException
        {
            return marker.getAttribute(IMarker.CHAR_START, -1);
        }

        @Override
        public int getCharEnd() throws CoreException
        {
            return marker.getAttribute(IMarker.CHAR_END, -1);
        }

        @Override
        public <T> T getAdapter(Class<T> adapter)
        {
            // Adapts to nothing. There is no debug model behind this to adapt to.
            return null;
        }

        @Override
        public int hashCode()
        {
            return marker == null ? 0 : Long.hashCode(marker.getId());
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
            {
                return true;
            }
            if (obj == null || getClass() != obj.getClass())
            {
                return false;
            }

            MarkerOnlyBreakpoint other = (MarkerOnlyBreakpoint)obj;
            if (marker == null || other.marker == null)
            {
                return marker == other.marker;
            }
            return marker.getId() == other.marker.getId();
        }
    }
}
