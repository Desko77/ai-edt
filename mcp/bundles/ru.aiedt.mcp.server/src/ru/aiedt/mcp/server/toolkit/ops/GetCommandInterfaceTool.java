/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.cmi.model.CommandsOrderFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacementFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibilityFragment;
import com._1c.g5.v8.dt.cmi.model.SubsystemsVisibilityFragment;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.mcore.CommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ForRoleType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * J3: reads the command interface of the Configuration root, its main section
 * (desktop), or a subsystem - the explicit overrides EDT stores in the
 * {@code cmi.model.CommandInterface} sub-model:
 * <ul>
 *   <li>Configuration: subsystems order + per-subsystem section visibility, plus
 *       the main section's command placement / order / visibility.</li>
 *   <li>Subsystem: that subsystem's own command placement / order / visibility.</li>
 * </ul>
 * Read-only: it reports only the explicit fragments stored in the model (no
 * effective/defaulted value computation - that is a later enhancement). This is
 * the "see the area before mutating" companion to the J3 write ops.
 */
public class GetCommandInterfaceTool implements IMcpTool
{
    @Override
    public String getName()
    {
        return "get_command_interface"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Read the command interface (subsystems order/visibility, command placement/order/" //$NON-NLS-1$
            + "visibility) of the Configuration root and its main section (desktop), or of a subsystem. " //$NON-NLS-1$
            + "ownerFqn=Configuration (default) reads the config-level subsystems order + section " //$NON-NLS-1$
            + "visibility and the main section's command placement/order/visibility; " //$NON-NLS-1$
            + "ownerFqn=Subsystem.<name> (nested: Subsystem.A.Subsystem.B) reads that subsystem's own " //$NON-NLS-1$
            + "command bar. Reports the explicit overrides stored in the model."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return "{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
            + "\"projectName\":{\"type\":\"string\",\"description\":\"EDT project name.\"}," //$NON-NLS-1$
            + "\"ownerFqn\":{\"type\":\"string\",\"description\":\"Configuration (default) or " //$NON-NLS-1$
            + "Subsystem.<name> (nested via Subsystem.A.Subsystem.B).\"}}," //$NON-NLS-1$
            + "\"required\":[\"projectName\"]}"; //$NON-NLS-1$
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
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        if (ownerFqn == null || ownerFqn.trim().isEmpty())
        {
            ownerFqn = "Configuration"; //$NON-NLS-1$
        }
        ownerFqn = ownerFqn.trim();

        if (projectName == null || projectName.trim().isEmpty())
        {
            return ToolResult.error("projectName is required.").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return ToolResult.error("configuration provider is not published as a service.").toJson(); //$NON-NLS-1$
        }
        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return ToolResult.error("No Configuration in project '" + projectName //$NON-NLS-1$
                + "' (external-object projects have no command interface).").toJson(); //$NON-NLS-1$
        }

        if ("Configuration".equalsIgnoreCase(ownerFqn)) //$NON-NLS-1$
        {
            return readConfiguration(config).toJson();
        }
        if (ownerFqn.regionMatches(true, 0, "Subsystem.", 0, "Subsystem.".length())) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Subsystem ss = navigateSubsystem(config, ownerFqn);
            if (ss == null)
            {
                return ToolResult.error("No subsystem named: " + ownerFqn //$NON-NLS-1$
                    + " (use Subsystem.<name>, nested via Subsystem.A.Subsystem.B).").toJson(); //$NON-NLS-1$
            }
            ToolResult r = ToolResult.success()
                .put("operation", "get_command_interface") //$NON-NLS-1$ //$NON-NLS-2$
                .put("target", ownerFqn); //$NON-NLS-1$
            CommandInterface ci = asCommandInterface(ss.getCommandInterface());
            r.put("commandInterface", commandSections(ci)); //$NON-NLS-1$
            return r.toJson();
        }
        return ToolResult.error("ownerFqn must be 'Configuration' or 'Subsystem.<name>' (got '" //$NON-NLS-1$
            + ownerFqn + "').").toJson(); //$NON-NLS-1$
    }

    private ToolResult readConfiguration(Configuration config)
    {
        ToolResult r = ToolResult.success()
            .put("operation", "get_command_interface") //$NON-NLS-1$ //$NON-NLS-2$
            .put("target", "Configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        CommandInterface ci = asCommandInterface(config.getCommandInterface());
        List<String> order = new ArrayList<>();
        List<Map<String, Object>> subVis = new ArrayList<>();
        if (ci != null)
        {
            if (ci.getSubsystemsOrder() != null)
            {
                for (Subsystem s : ci.getSubsystemsOrder().getSubsystems())
                {
                    order.add(fqn(s));
                }
            }
            if (ci.getSubsystemsVisibility() != null)
            {
                for (SubsystemsVisibilityFragment f : ci.getSubsystemsVisibility().getVisibilityFragments())
                {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("subsystem", fqn(f.getSubsystem())); //$NON-NLS-1$
                    m.put("visible", adjBool(f.getVisible())); //$NON-NLS-1$
                    subVis.add(m);
                }
            }
        }
        r.put("subsystemsOrder", order); //$NON-NLS-1$
        r.put("subsystemsVisibility", subVis); //$NON-NLS-1$
        r.put("mainSection", commandSections(asCommandInterface(config.getMainSectionCommandInterface()))); //$NON-NLS-1$
        return r;
    }

    /** Command placement / order / visibility fragments of one CommandInterface. */
    private Map<String, Object> commandSections(CommandInterface ci)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> placement = new ArrayList<>();
        List<Map<String, Object>> order = new ArrayList<>();
        List<Map<String, Object>> visibility = new ArrayList<>();
        if (ci != null)
        {
            if (ci.getCommandsPlacement() != null)
            {
                for (CommandsPlacementFragment f : ci.getCommandsPlacement().getPlacementFragments())
                {
                    placement.add(groupFragment(f.getGroup(), f.getCommands()));
                }
            }
            if (ci.getCommandsOrder() != null)
            {
                for (CommandsOrderFragment f : ci.getCommandsOrder().getOrderFragments())
                {
                    order.add(groupFragment(f.getGroup(), f.getCommands()));
                }
            }
            if (ci.getCommandsVisibility() != null)
            {
                for (CommandsVisibilityFragment f : ci.getCommandsVisibility().getVisibilityFragments())
                {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("command", fqn(f.getCommand())); //$NON-NLS-1$
                    m.put("visible", adjBool(f.getVisible())); //$NON-NLS-1$
                    visibility.add(m);
                }
            }
        }
        out.put("commandsPlacement", placement); //$NON-NLS-1$
        out.put("commandsOrder", order); //$NON-NLS-1$
        out.put("commandsVisibility", visibility); //$NON-NLS-1$
        return out;
    }

    private Map<String, Object> groupFragment(CommandGroup group, EList<Command> commands)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group", group != null ? fqn(group) : "(default)"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> cmds = new ArrayList<>();
        if (commands != null)
        {
            for (Command c : commands)
            {
                cmds.add(fqn(c));
            }
        }
        m.put("commands", cmds); //$NON-NLS-1$
        return m;
    }

    /** Renders an AdjustableBoolean as {common, forRoles:[{role,value}]}. */
    private Map<String, Object> adjBool(AdjustableBoolean ab)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ab == null)
        {
            m.put("common", null); //$NON-NLS-1$
            return m;
        }
        m.put("common", ab.isCommon()); //$NON-NLS-1$
        List<Map<String, Object>> forRoles = new ArrayList<>();
        for (ForRoleType fr : ab.getFor())
        {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("role", fr.getRole() != null ? fqn(fr.getRole()) : null); //$NON-NLS-1$
            rm.put("value", fr.isValue()); //$NON-NLS-1$
            forRoles.add(rm);
        }
        if (!forRoles.isEmpty())
        {
            m.put("forRoles", forRoles); //$NON-NLS-1$
        }
        return m;
    }

    /** cast AbstractCommandInterface to the cmi.model.CommandInterface, or null. */
    private CommandInterface asCommandInterface(Object aci)
    {
        return (aci instanceof CommandInterface) ? (CommandInterface) aci : null;
    }

    /**
     * A readable FQN for an mdclass/mcore element, reconstructing the owner path
     * from {@code eContainer} so a child command is distinguishable - a
     * StandardCommand inside a Document renders as
     * {@code Document.X.StandardCommand.Y} (matching the .cmi serialization)
     * rather than a bare {@code StandardCommand.Y} that repeats for every object.
     * Top-level objects (CommonCommand, CommandGroup) render as {@code Type.Name}.
     */
    private String fqn(Object o)
    {
        if (!(o instanceof EObject))
        {
            return o == null ? null : String.valueOf(o);
        }
        EObject e = (EObject) o;
        java.util.Deque<String> chain = new java.util.ArrayDeque<>();
        chain.addFirst(typeDotName(e));
        EObject c = e.eContainer();
        int guard = 0;
        while (c != null && guard++ < 16)
        {
            String ty = c.eClass() != null ? c.eClass().getName() : null;
            if ("Configuration".equals(ty)) //$NON-NLS-1$
            {
                break; // the config root is not part of an object FQN
            }
            if (c instanceof MdObject)
            {
                chain.addFirst(typeDotName(c));
            }
            c = c.eContainer();
        }
        return String.join(".", chain); //$NON-NLS-1$
    }

    /**
     * {@code Type.Name} for a single element - getName() read reflectively (it is
     * on the concrete impls / DuallyNamedElement, not the mcore interfaces).
     */
    private String typeDotName(EObject e)
    {
        String type = e.eClass() != null ? e.eClass().getName() : "?"; //$NON-NLS-1$
        String name = null;
        try
        {
            Object n = e.getClass().getMethod("getName").invoke(e); //$NON-NLS-1$
            if (n instanceof String)
            {
                name = (String) n;
            }
        }
        catch (ReflectiveOperationException ignore)
        {
            // no getName() - type only
        }
        if ((name == null || name.isEmpty()) && e.eIsProxy())
        {
            // A not-yet-resolved reference has no populated name slot - e.g. a StandardCommandGroup
            // placed this same session is stored as an "unresolved:/<name>" proxy and only resolves to
            // a named instance after a reload. Derive the name from the proxy URI's last segment so the
            // read-back matches the persisted <group> value instead of rendering a bare type. Scope
            // strictly to the "unresolved" scheme EDT's XML reader uses for these refs: other proxy
            // schemes (platform:/resource/..., bm:///...) have trailing segments that are file names or
            // numeric ids, and rendering those as the object's name would fabricate a misleading label
            // in this read-back tool for a genuine dangling/stale reference.
            URI uri = ((InternalEObject)e).eProxyURI();
            if (uri != null && "unresolved".equals(uri.scheme())) //$NON-NLS-1$
            {
                String seg = uri.lastSegment();
                if (seg != null && !seg.isEmpty())
                {
                    name = seg;
                }
            }
        }
        return (name != null && !name.isEmpty()) ? type + "." + name : type; //$NON-NLS-1$
    }

    /** Navigates a (possibly nested) Subsystem.A.Subsystem.B FQN. */
    private Subsystem navigateSubsystem(Configuration config, String ownerFqn)
    {
        String[] segs = ownerFqn.split("\\."); //$NON-NLS-1$
        // odd indices carry the names: Subsystem, A, Subsystem, B -> [A, B]
        List<String> names = new ArrayList<>();
        for (int i = 1; i < segs.length; i += 2)
        {
            names.add(segs[i]);
        }
        if (names.isEmpty())
        {
            return null;
        }
        EList<Subsystem> level = config.getSubsystems();
        Subsystem found = null;
        for (String n : names)
        {
            found = null;
            for (Subsystem s : level)
            {
                if (n.equalsIgnoreCase(s.getName()))
                {
                    found = s;
                    break;
                }
            }
            if (found == null)
            {
                return null;
            }
            level = found.getSubsystems();
        }
        return found;
    }
}
