/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.Group;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.InfobaseIdentity;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Lists the infobases registered in EDT - the list the Infobases view shows - with their groups.
 * <p>
 * Each entry carries its name, id, group path, type, connection string and platform version, and
 * the workspace projects whose applications point at it. Passwords never leave: {@code Pwd} and
 * {@code DBPwd} in a connection string and {@code /P} in the additional parameters are masked, and
 * the access settings EDT keeps in its secure store are not read. Nothing is written.
 * </p>
 */
public class RegisteredInfobasesReader
    implements IMcpTool
{
    /** The tool name. */
    public static final String NAME = "list_registered_infobases"; //$NON-NLS-1$

    /** What a masked secret reads as. */
    static final String MASK = "***"; //$NON-NLS-1$

    private static final Pattern PASSWORD_KEY = Pattern.compile(
        "(?i)((?:^|;)\\s*(?:Pwd|DBPwd)\\s*=\\s*)(\"(?:[^\"]|\"\")*\"|[^;]*)"); //$NON-NLS-1$

    private static final Pattern PASSWORD_SWITCH = Pattern.compile("(?i)((?:^|\\s)/P\\s+)(\"[^\"]*\"|\\S+)"); //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `infobase_admin` `operation=list_registered_infobases`; prefer the " //$NON-NLS-1$
            + "facade for new prompts. Lists the infobases registered in EDT with their groups: name, " //$NON-NLS-1$
            + "id, group, type, connection string (passwords masked), version and the projects bound " //$NON-NLS-1$
            + "to each."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object().build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        Activator activator = Activator.getDefault();
        IInfobaseManager infobases = activator == null ? null : activator.getInfobaseManager();
        if (infobases == null)
        {
            return ToolResult.error("IInfobaseManager service cannot be reached").toJson(); //$NON-NLS-1$
        }
        List<Section> sections;
        try
        {
            sections = infobases.getAll();
        }
        catch (Exception e)
        {
            return ToolResult.error("EDT's infobase list could not be read: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        List<String> unreadProjects = new ArrayList<>();
        Map<InfobaseReference, List<String>> bound = boundProjects(sections,
            activator.getApplicationManager(), unreadProjects);
        ToolResult answer = render(sections, bound);
        if (!unreadProjects.isEmpty())
        {
            answer.put("projectsNotRead", unreadProjects); //$NON-NLS-1$
        }
        return answer.toJson();
    }

    /**
     * Builds the answer from EDT's list. Does not read the projects; the bindings are handed in.
     *
     * @param sections the top level of EDT's list: groups and infobases
     * @param bound the projects bound to each infobase; an infobase absent from the map has none
     * @return the answer: {@code count}, {@code groups} (paths) and {@code infobases}
     */
    static ToolResult render(Collection<Section> sections, Map<InfobaseReference, List<String>> bound)
    {
        List<String> groups = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        walk(sections, "", groups, rows, bound); //$NON-NLS-1$
        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("count", rows.size()) //$NON-NLS-1$
            .put("groups", groups) //$NON-NLS-1$
            .put("infobases", rows); //$NON-NLS-1$
    }

    /**
     * Collects the groups and infobases of one level of the list and of the levels under it.
     *
     * @param sections the sections of this level
     * @param groupPath the path of the group holding them, {@code ""} at the top
     * @param groups receives each group's path, parents before children
     * @param rows receives one row per infobase
     * @param bound the projects bound to each infobase
     */
    private static void walk(Collection<? extends Section> sections, String groupPath, List<String> groups,
        List<Map<String, Object>> rows, Map<InfobaseReference, List<String>> bound)
    {
        if (sections == null)
        {
            return;
        }
        for (Section section : sections)
        {
            if (section instanceof Group)
            {
                String path = groupPath.isEmpty() ? section.getName() : groupPath + "/" + section.getName(); //$NON-NLS-1$
                groups.add(path);
                walk(((Group)section).getSubsections(), path, groups, rows, bound);
            }
            else if (section instanceof InfobaseReference)
            {
                rows.add(row((InfobaseReference)section, groupPath, bound.get(section)));
            }
        }
    }

    /**
     * One infobase as the answer shows it.
     *
     * @param infobase the entry
     * @param groupPath the group it stands in, {@code ""} at the top
     * @param projects the projects bound to it, or <code>null</code>
     * @return the row
     */
    private static Map<String, Object> row(InfobaseReference infobase, String groupPath, List<String> projects)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", infobase.getName()); //$NON-NLS-1$
        row.put("uuid", infobase.getUuid() == null ? null : infobase.getUuid().toString()); //$NON-NLS-1$
        row.put("group", groupPath); //$NON-NLS-1$
        row.put("type", infobase.getInfobaseType() == null ? null : infobase.getInfobaseType().name()); //$NON-NLS-1$
        IConnectionString connection = infobase.getConnectionString();
        row.put("connectionString", //$NON-NLS-1$
            connection == null ? null : maskPasswords(connection.asConnectionString()));
        String version = infobase.getVersion();
        if (version != null && !version.isEmpty())
        {
            row.put("version", version); //$NON-NLS-1$
        }
        String additional = infobase.getAdditionalParameters();
        if (additional != null && !additional.isEmpty())
        {
            row.put("additionalParameters", maskPasswords(additional)); //$NON-NLS-1$
        }
        row.put("projects", projects == null ? List.of() : projects); //$NON-NLS-1$
        return row;
    }

    /**
     * Replaces the value of every password in a connection string or a command line with
     * {@value #MASK}: {@code Pwd=}, {@code DBPwd=} and the {@code /P} switch.
     *
     * @param text the text, may be <code>null</code>
     * @return the text with the passwords masked, <code>null</code> for <code>null</code>
     */
    static String maskPasswords(String text)
    {
        if (text == null)
        {
            return null;
        }
        String masked = PASSWORD_KEY.matcher(text).replaceAll("$1\"" + MASK + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        return PASSWORD_SWITCH.matcher(masked).replaceAll("$1" + MASK); //$NON-NLS-1$
    }

    /**
     * The workspace projects whose applications point at each infobase of the list.
     *
     * @param sections EDT's list
     * @param applications the application service, or <code>null</code>
     * @param unreadProjects receives the projects whose applications could not be read
     * @return the bound projects per infobase entry of the list
     */
    private static Map<InfobaseReference, List<String>> boundProjects(Collection<Section> sections,
        IApplicationManager applications, List<String> unreadProjects)
    {
        Map<InfobaseReference, List<String>> bound = new LinkedHashMap<>();
        if (applications == null)
        {
            return bound;
        }
        List<InfobaseReference> listed = new ArrayList<>();
        collectReferences(sections, listed);
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (!project.isOpen())
            {
                continue;
            }
            List<IApplication> projectApplications;
            try
            {
                projectApplications = applications.getApplications(project);
            }
            catch (Exception e)
            {
                unreadProjects.add(project.getName());
                continue;
            }
            if (projectApplications == null)
            {
                continue;
            }
            for (IApplication application : projectApplications)
            {
                if (!(application instanceof IInfobaseApplication))
                {
                    continue;
                }
                InfobaseReference target = ((IInfobaseApplication)application).getInfobase();
                for (InfobaseReference entry : listed)
                {
                    if (sameInfobase(entry, target))
                    {
                        List<String> names = bound.computeIfAbsent(entry, k -> new ArrayList<>());
                        if (!names.contains(project.getName()))
                        {
                            names.add(project.getName());
                        }
                    }
                }
            }
        }
        return bound;
    }

    /**
     * Every infobase entry of the list, groups flattened.
     *
     * @param sections one level of the list
     * @param into receives the entries
     */
    private static void collectReferences(Collection<? extends Section> sections, List<InfobaseReference> into)
    {
        if (sections == null)
        {
            return;
        }
        for (Section section : sections)
        {
            if (section instanceof Group)
            {
                collectReferences(((Group)section).getSubsections(), into);
            }
            else if (section instanceof InfobaseReference)
            {
                into.add((InfobaseReference)section);
            }
        }
    }

    /**
     * Whether two references name the same infobase: by id when both carry one, else by address.
     *
     * @param left one reference
     * @param right the other, may be <code>null</code>
     * @return <code>true</code> when they name the same infobase
     */
    static boolean sameInfobase(InfobaseReference left, InfobaseReference right)
    {
        if (left == right)
        {
            return true;
        }
        if (left == null || right == null)
        {
            return false;
        }
        if (left.getUuid() != null && right.getUuid() != null)
        {
            return left.getUuid().equals(right.getUuid());
        }
        String identity = InfobaseIdentity.of(left);
        return identity != null && identity.equals(InfobaseIdentity.of(right));
    }
}
