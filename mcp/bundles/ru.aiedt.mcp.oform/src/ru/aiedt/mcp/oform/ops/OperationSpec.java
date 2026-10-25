/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

/**
 * One operation of {@code ordinary_forms}: its name, kind, arguments, help line and handler.
 * <p>
 * The catalog of specs is the only place the tool learns operations from: the known names, the
 * writing names, the schema and the help are all derived from it.
 * </p>
 */
public final class OperationSpec
{
    /** How an operation runs. */
    public enum Kind
    {
        /** Reads and answers; no container is written. The form may be optional. */
        QUERY,
        /** Edits an existing form: the layout tree (and maybe the module) goes back into the container. */
        LAYOUT,
        /** Creates a form that does not exist yet; the creator owns the life cycle. */
        CREATION
    }

    /** A query's handler. */
    @FunctionalInterface
    public interface QueryHandler
    {
        /**
         * @param project the project, or {@code null} when the operation needs none
         * @param params the tool arguments
         * @return the answer as JSON
         */
        String run(IProject project, Map<String, String> params);
    }

    /** A layout edit's handler. */
    @FunctionalInterface
    public interface LayoutHandler
    {
        /**
         * @param context the form, the editor over it and the arguments
         * @return what the edit did, and the module text when it changes
         */
        OperationResult apply(LayoutContext context);
    }

    /** A creation's handler. */
    @FunctionalInterface
    public interface CreationHandler
    {
        /**
         * @param project the project
         * @param params the tool arguments
         * @return the answer as JSON
         */
        String run(IProject project, Map<String, String> params);
    }

    public final String name;

    public final Kind kind;

    /** Whether the operation writes; the tool refuses such an operation, a dryRun included, when
     * the active preset switches the write capability of the whole tool off. */
    public final boolean writes;

    /** Whether {@code projectName} is required. */
    public final boolean needsProject;

    /** The line the help prints for the operation. */
    public final String help;

    public final List<ParamSpec> params;

    public final QueryHandler query;

    public final LayoutHandler layout;

    public final CreationHandler creation;

    private OperationSpec(String name, Kind kind, boolean writes, boolean needsProject, String help,
        List<ParamSpec> params, QueryHandler query, LayoutHandler layout, CreationHandler creation)
    {
        this.name = name;
        this.kind = kind;
        this.writes = writes;
        this.needsProject = needsProject;
        this.help = help;
        this.params = List.copyOf(params);
        this.query = query;
        this.layout = layout;
        this.creation = creation;
    }

    /**
     * A query that needs a project.
     */
    public static OperationSpec query(String name, String help, List<ParamSpec> params, QueryHandler handler)
    {
        return new OperationSpec(name, Kind.QUERY, false, true, help, params, handler, null, null);
    }

    /**
     * A query that works without a project ({@code help}).
     */
    public static OperationSpec projectlessQuery(String name, String help, List<ParamSpec> params, QueryHandler handler)
    {
        return new OperationSpec(name, Kind.QUERY, false, false, help, params, handler, null, null);
    }

    /**
     * An edit of an existing form's layout.
     */
    public static OperationSpec layout(String name, String help, List<ParamSpec> params, LayoutHandler handler)
    {
        return new OperationSpec(name, Kind.LAYOUT, true, true, help, params, null, handler, null);
    }

    /**
     * A creation of a form.
     */
    public static OperationSpec creation(String name, String help, List<ParamSpec> params, CreationHandler handler)
    {
        return new OperationSpec(name, Kind.CREATION, true, true, help, params, null, null, handler);
    }
}
