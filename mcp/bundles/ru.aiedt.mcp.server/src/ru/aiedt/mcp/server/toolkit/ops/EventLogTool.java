/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.EventLogReader;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Reads what actually happened in a project's infobase.
 * <p>
 * Everything else this server offers describes the configuration - what the code says will happen.
 * The event log is the other half: who logged in, what was posted, which update the platform
 * refused and why. Until now there was no route to it at all, and the absence was written down as
 * impossible because the EDT model has no such API. It does not need one: for a file infobase the
 * log sits on disk beside the data in a readable shape.
 * </p>
 * <p>
 * The two cases this cannot serve are named rather than answered with an empty list. A server
 * infobase keeps its log on the server, out of reach from here. A newer file infobase may keep the
 * same log in a single SQLite file, which is a different format. Both come back as a refusal that
 * says which one it is.
 * </p>
 */
public class EventLogTool implements IMcpTool
{
    /** The operation name, also the standalone tool name. */
    public static final String NAME = "read_event_log"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Reads the event log of a project's FILE infobase - the record of what happened in " //$NON-NLS-1$
            + "it: logins, postings, configuration updates, errors the platform raised. Answers " //$NON-NLS-1$
            + "with one row per event (moment, event, severity, user, computer, application, " //$NON-NLS-1$
            + "session, comment), newest last, filtered by from / to / event / user / severity and " //$NON-NLS-1$
            + "capped by limit. Free text passes through the sensitive-data masker on the way out. " //$NON-NLS-1$
            + "A server infobase keeps its log on the server and is refused by name; so is the " //$NON-NLS-1$
            + "single-file SQLite form of the log, which needs a different reader. Read-only."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "EDT project whose infobase to read the log of.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Which infobase, from get_applications. Optional: the project's default is used, " //$NON-NLS-1$
                    + "and for an extension the default of the configuration it extends.") //$NON-NLS-1$
            .stringProperty("from", //$NON-NLS-1$
                "Earliest moment to report. Digits are taken as yyyyMMddHHmmss and a short value " //$NON-NLS-1$
                    + "is padded, so 20260225 means that day from midnight.") //$NON-NLS-1$
            .stringProperty("to", "Latest moment to report, written the same way as from.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("event", //$NON-NLS-1$
                "Keep only events whose name contains this, ignoring case - e.g. 'Session', " //$NON-NLS-1$
                    + "'DBConfigUpdate', '_$Data$_.Post'.") //$NON-NLS-1$
            .stringProperty("user", "Keep only records of users whose name contains this.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("severity", //$NON-NLS-1$
                "Keep only this severity: Information, Warning, Error or Note.") //$NON-NLS-1$
            .integerProperty("limit", "Most rows to return (default 100).") //$NON-NLS-1$ //$NON-NLS-2$
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
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return ToolResult.error("The IApplicationManager service is currently unavailable").toJson(); //$NON-NLS-1$
        }

        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        Optional<IApplication> app = resolveApplication(appManager, project, applicationId);
        if (!app.isPresent())
        {
            return ToolResult.error("No infobase found for " + projectName //$NON-NLS-1$
                + ". Call get_applications to see what is available.").toJson(); //$NON-NLS-1$
        }
        if (!(app.get() instanceof IInfobaseApplication))
        {
            return ToolResult.error("The application " + app.get().getName() //$NON-NLS-1$
                + " is not an infobase, so it keeps no event log.").toJson(); //$NON-NLS-1$
        }

        InfobaseReference infobase = ((IInfobaseApplication)app.get()).getInfobase();
        IConnectionString connection = infobase == null ? null : infobase.getConnectionString();
        if (connection instanceof ServerConnectionString)
        {
            ServerConnectionString server = (ServerConnectionString)connection;
            ToolResult onTheServer = ToolResult.error("This is a server infobase (" //$NON-NLS-1$
                + server.getServer() + "/" + server.getReference() + "). Its event log is written " //$NON-NLS-1$ //$NON-NLS-2$
                + "on the 1C server, in the cluster's working directory under the infobase's own " //$NON-NLS-1$
                + "folder, and this machine has no path to it. Three ways to reach it: open the " //$NON-NLS-1$
                + "log from the Designer or the client connected to this infobase, which reads it " //$NON-NLS-1$
                + "through the server; look in the cluster working directory on " //$NON-NLS-1$
                + server.getServer() + " itself; or copy that infobase's log directory to this " //$NON-NLS-1$ //$NON-NLS-2$
                + "machine and point a file infobase at it."); //$NON-NLS-1$
            onTheServer.put("logLocation", "server"); //$NON-NLS-1$ //$NON-NLS-2$
            onTheServer.put("server", server.getServer()); //$NON-NLS-1$
            return onTheServer.toJson();
        }
        if (!(connection instanceof FileConnectionString))
        {
            return ToolResult.error("The infobase does not say where its files are, so its log " //$NON-NLS-1$
                + "cannot be found.").toJson(); //$NON-NLS-1$
        }
        String directory = ((FileConnectionString)connection).getFile();
        if (directory == null || directory.trim().isEmpty())
        {
            return ToolResult.error("The infobase names no directory, so its log cannot be found.") //$NON-NLS-1$
                .toJson();
        }

        EventLogReader.Query query = new EventLogReader.Query();
        query.from = orEmpty(JsonUtils.extractStringArgument(params, "from")); //$NON-NLS-1$
        query.to = orEmpty(JsonUtils.extractStringArgument(params, "to")); //$NON-NLS-1$
        query.event = orEmpty(JsonUtils.extractStringArgument(params, "event")); //$NON-NLS-1$
        query.user = orEmpty(JsonUtils.extractStringArgument(params, "user")); //$NON-NLS-1$
        query.severity = orEmpty(JsonUtils.extractStringArgument(params, "severity")); //$NON-NLS-1$
        Integer limit = JsonUtils.extractIntegerArgument(params, "limit"); //$NON-NLS-1$
        if (limit != null && limit > 0)
        {
            query.limit = limit;
        }

        Path infobaseDirectory = Paths.get(directory.trim());
        EventLogReader.Result read = EventLogReader.read(infobaseDirectory, query);
        if (!read.ok)
        {
            ToolResult failed = ToolResult.error(read.error);
            failed.put("projectName", projectName); //$NON-NLS-1$
            failed.put("applicationId", app.get().getId()); //$NON-NLS-1$
            if (read.unsupported != null)
            {
                failed.put("unsupportedLogFormat", read.unsupported); //$NON-NLS-1$
            }
            return failed.toJson();
        }
        ToolResult result = ToolResult.success()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("applicationId", app.get().getId()) //$NON-NLS-1$
            .put("events", read.rows) //$NON-NLS-1$
            .put("count", read.rows.size()) //$NON-NLS-1$
            .put("scanned", read.scanned) //$NON-NLS-1$
            .put("files", read.files); //$NON-NLS-1$
        if (read.truncated)
        {
            result.put("truncated", true); //$NON-NLS-1$
            result.put("truncatedNote", "The limit stopped the answer before the records ran out. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Narrow it with from / to / event, or raise limit."); //$NON-NLS-1$
        }
        if (read.rows.isEmpty())
        {
            result.put("note", "The log was read and nothing matched. Filters in force: " //$NON-NLS-1$ //$NON-NLS-2$
                + describeFilters(query)); //$NON-NLS-1$
        }
        result.put("masked", "Free text passes through the sensitive-data masker, so a value that " //$NON-NLS-1$ //$NON-NLS-2$
            + "matches one of its patterns is replaced. Anything the library has no pattern for - " //$NON-NLS-1$
            + "a user name, a computer name - comes through as written."); //$NON-NLS-1$
        return result.toJson();
    }

    /**
     * Which filters were in force, so an empty answer names what emptied it.
     *
     * @param query the filters.
     * @return them spelled out.
     */
    private static String describeFilters(EventLogReader.Query query)
    {
        StringBuilder sb = new StringBuilder();
        appendFilter(sb, "from", query.from); //$NON-NLS-1$
        appendFilter(sb, "to", query.to); //$NON-NLS-1$
        appendFilter(sb, "event", query.event); //$NON-NLS-1$
        appendFilter(sb, "user", query.user); //$NON-NLS-1$
        appendFilter(sb, "severity", query.severity); //$NON-NLS-1$
        if (sb.length() == 0)
        {
            return "none - the log itself holds no records in this range"; //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Adds one filter to the description when it is set.
     *
     * @param sb what is being built.
     * @param name the filter.
     * @param value its value.
     */
    private static void appendFilter(StringBuilder sb, String name, String value)
    {
        if (value == null || value.isEmpty())
        {
            return;
        }
        if (sb.length() > 0)
        {
            sb.append(", "); //$NON-NLS-1$
        }
        sb.append(name).append('=').append(value);
    }

    /**
     * The application to read the log of - the one named, else the project's default, else the
     * default of the configuration it extends.
     *
     * @param appManager the application manager.
     * @param project the project asked about.
     * @param applicationId the application named, possibly none.
     * @return the application, or empty when there is none.
     */
    private static Optional<IApplication> resolveApplication(IApplicationManager appManager,
        IProject project, String applicationId)
    {
        try
        {
            boolean named = applicationId != null && !applicationId.isEmpty();
            Optional<IApplication> found = named ? appManager.getApplication(project, applicationId)
                : appManager.getDefaultApplication(project);
            if (found.isPresent())
            {
                return found;
            }
            IProject parent = ru.aiedt.mcp.server.support.BmCommonModuleGuards.parentProjectOf(project);
            if (parent == null || !parent.exists() || !parent.isOpen())
            {
                return Optional.empty();
            }
            return named ? appManager.getApplication(parent, applicationId)
                : appManager.getDefaultApplication(parent);
        }
        catch (Exception e)
        {
            // Reported rather than swallowed: without it the caller is told the project has no
            // infobase, which is a different thing from "the question could not be asked".
            Activator.logWarning("Could not resolve the application for " + project.getName() //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
            return Optional.empty();
        }
    }

    /**
     * An argument as text, never null.
     *
     * @param value as extracted.
     * @return the value, or an empty string.
     */
    private static String orEmpty(String value)
    {
        return value == null ? "" : value.trim(); //$NON-NLS-1$
    }
}
