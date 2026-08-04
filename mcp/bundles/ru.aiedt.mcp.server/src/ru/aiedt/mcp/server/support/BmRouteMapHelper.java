/**
 * Copyright (c) 2025 Desko77
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import ru.aiedt.mcp.server.Activator;

/**
 * Reads and removes a BusinessProcess route map, stored on disk as a
 * {@code Flowchart.scheme} GraphicalSchema XML next to the BP {@code .mdo}
 * (the .mdo itself only declares the routePoint producedType and the linked
 * Task). Route points are element children of {@code <Items>}; a
 * {@code <ConnectionLine>} is a transition carrying {@code Connect/From/Item ->
 * Connect/To/Item}. See the memory note wave-f-route-map-format for the format.
 *
 * <p>Read / remove are low-risk (parse / delete a file). Generating a valid
 * scheme from scratch (createRouteMap) is the picky "valid in EDT, crashes IB"
 * class and lives elsewhere.
 */
public final class BmRouteMapHelper
{
    private BmRouteMapHelper()
    {
    }

    /** Structured route map: ordered points and transitions between them. */
    public static final class RouteMap
    {
        public final List<Map<String, Object>> points = new ArrayList<>();
        public final List<Map<String, Object>> transitions = new ArrayList<>();
        public String error;
        public boolean exists;
    }

    /**
     * Resolves the {@code Flowchart.scheme} file for a BusinessProcess FQN
     * ({@code BusinessProcess.<Name>}); returns {@code null} when the FQN is not
     * a BusinessProcess or the project has no local location.
     */
    public static IFile flowchartFile(IProject project, String bpFqn)
    {
        if (project == null || bpFqn == null)
        {
            return null;
        }
        String[] parts = bpFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2
            || !("BusinessProcess".equals(parts[0]) || "БизнесПроцесс".equals(parts[0]))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        return project.getFolder("src").getFolder("BusinessProcesses") //$NON-NLS-1$ //$NON-NLS-2$
            .getFolder(parts[1]).getFile("Flowchart.scheme"); //$NON-NLS-1$
    }

    /**
     * Parses the route map of {@code bpFqn}. On success {@code exists=true} and
     * points / transitions are populated; when there is no Flowchart.scheme
     * {@code exists=false} with no error; on a real failure {@code error} is set.
     */
    public static RouteMap readRouteMap(IProject project, String bpFqn)
    {
        RouteMap result = new RouteMap();
        IFile file = flowchartFile(project, bpFqn);
        if (file == null)
        {
            result.error = "bpFqn must be a BusinessProcess FQN (BusinessProcess.<Name>)"; //$NON-NLS-1$
            return result;
        }
        File osFile = file.getLocation() != null ? file.getLocation().toFile() : null;
        if (osFile == null || !osFile.isFile())
        {
            result.exists = false;
            return result;
        }
        result.exists = true;
        try
        {
            Element root = newSecureFactory().newDocumentBuilder().parse(osFile).getDocumentElement();
            Element items = firstChild(root, "Items"); //$NON-NLS-1$
            if (items == null)
            {
                return result;
            }
            NodeList children = items.getChildNodes();
            for (int i = 0; i < children.getLength(); i++)
            {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE)
                {
                    continue;
                }
                Element item = (Element)n;
                String tag = item.getTagName();
                Element props = firstChild(item, "Properties"); //$NON-NLS-1$
                if ("ConnectionLine".equals(tag)) //$NON-NLS-1$
                {
                    Element connect = props != null ? firstChild(props, "Connect") : null; //$NON-NLS-1$
                    if (connect != null)
                    {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("from", connectItem(connect, "From")); //$NON-NLS-1$ //$NON-NLS-2$
                        t.put("to", connectItem(connect, "To")); //$NON-NLS-1$ //$NON-NLS-2$
                        String title = titleText(props);
                        if (title != null)
                        {
                            t.put("title", title); //$NON-NLS-1$
                        }
                        result.transitions.add(t);
                    }
                    continue;
                }
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", tag); //$NON-NLS-1$
                p.put("name", props != null ? childText(props, "Name") : null); //$NON-NLS-1$ //$NON-NLS-2$
                List<Map<String, String>> handlers = readEvents(item);
                if (!handlers.isEmpty())
                {
                    p.put("events", handlers); //$NON-NLS-1$
                }
                result.points.add(p);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to parse Flowchart.scheme for " + bpFqn, e); //$NON-NLS-1$
            result.error = "Failed to parse Flowchart.scheme: " + e.getMessage(); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Deletes the {@code Flowchart.scheme} of {@code bpFqn} (clearing the route
     * map). Returns {@code null} on success (or when there was nothing to
     * delete), an error description otherwise.
     */
    public static String removeRouteMap(IProject project, String bpFqn)
    {
        IFile file = flowchartFile(project, bpFqn);
        if (file == null)
        {
            return "bpFqn must be a BusinessProcess FQN (BusinessProcess.<Name>)"; //$NON-NLS-1$
        }
        try
        {
            if (file.exists())
            {
                file.delete(true, null);
            }
            else
            {
                File osFile = file.getLocation() != null ? file.getLocation().toFile() : null;
                if (osFile != null && osFile.isFile() && !osFile.delete())
                {
                    return "Flowchart.scheme could not be deleted from disk"; //$NON-NLS-1$
                }
                if (file.getParent() != null)
                {
                    file.getParent().refreshLocal(IResource.DEPTH_ONE, null);
                }
            }
        }
        catch (Exception e)
        {
            return "Failed to remove Flowchart.scheme: " + e.getMessage(); //$NON-NLS-1$
        }
        return null;
    }

    private static List<Map<String, String>> readEvents(Element item)
    {
        List<Map<String, String>> handlers = new ArrayList<>();
        Element events = firstChild(item, "Events"); //$NON-NLS-1$
        if (events == null)
        {
            return handlers;
        }
        NodeList evs = events.getChildNodes();
        for (int i = 0; i < evs.getLength(); i++)
        {
            Node n = evs.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"Event".equals(n.getNodeName())) //$NON-NLS-1$
            {
                continue;
            }
            Element ev = (Element)n;
            String handler = ev.getTextContent() != null ? ev.getTextContent().trim() : ""; //$NON-NLS-1$
            if (!handler.isEmpty())
            {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("event", ev.getAttribute("name")); //$NON-NLS-1$ //$NON-NLS-2$
                h.put("handler", handler); //$NON-NLS-1$
                handlers.add(h);
            }
        }
        return handlers;
    }

    private static String connectItem(Element connect, String side)
    {
        Element sideEl = firstChild(connect, side);
        return sideEl != null ? childText(sideEl, "Item") : null; //$NON-NLS-1$
    }

    private static String titleText(Element props)
    {
        Element title = firstChild(props, "Title"); //$NON-NLS-1$
        if (title == null)
        {
            return null;
        }
        Element item = firstChild(title, "item"); //$NON-NLS-1$
        String content = item != null ? childText(item, "content") : null; //$NON-NLS-1$
        return content != null && !content.isEmpty() ? content : null;
    }

    private static Element firstChild(Element parent, String localName)
    {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                && localNameMatches(n.getNodeName(), localName))
            {
                return (Element)n;
            }
        }
        return null;
    }

    private static String childText(Element parent, String localName)
    {
        Element child = firstChild(parent, localName);
        return child != null && child.getTextContent() != null ? child.getTextContent().trim() : null;
    }

    /** Matches a tag name ignoring any namespace prefix (e.g. {@code v8:content}). */
    private static boolean localNameMatches(String nodeName, String localName)
    {
        if (nodeName.equals(localName))
        {
            return true;
        }
        int colon = nodeName.indexOf(':');
        return colon >= 0 && nodeName.substring(colon + 1).equals(localName);
    }

    /**
     * A namespace-unaware {@link DocumentBuilderFactory} with external entity and
     * DOCTYPE processing disabled (XXE hardening), shared by read and generate.
     */
    private static DocumentBuilderFactory newSecureFactory() throws Exception
    {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        // Each setFeature is independently best-effort: an older parser missing one
        // feature must not skip the others (defense in depth).
        setFeatureQuietly(dbf, "http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
        setFeatureQuietly(dbf, "http://xml.org/sax/features/external-general-entities", false); //$NON-NLS-1$
        setFeatureQuietly(dbf, "http://xml.org/sax/features/external-parameter-entities", false); //$NON-NLS-1$
        dbf.setExpandEntityReferences(false);
        return dbf;
    }

    private static void setFeatureQuietly(DocumentBuilderFactory dbf, String feature, boolean value)
    {
        try
        {
            dbf.setFeature(feature, value);
        }
        catch (Exception ignore)
        {
            // Parser does not support this feature - best effort, keep the others.
        }
    }

    // ----- generate side (Wave F create_route_map) -----

    /** Result of generating a route map: the produced XML plus counts / error. */
    public static final class WriteResult
    {
        public String error;
        public String xml;
        public boolean written;
        public int pointCount;
        public int transitionCount;
    }

    /**
     * Generates a {@code Flowchart.scheme} for {@code bpFqn} from a declarative
     * list of {@code points} ({type,name,title?,taskDescription?,subprocess?}) and
     * {@code transitions} ({from,to,branch?,title?}), laid out top-to-bottom.
     * Activity points emit the linked Task's addressing attributes (nil), matching
     * what the EDT editor writes, so the scheme stays infobase-valid. When
     * {@code dryRun} is true the XML is returned without touching disk. Refuses to
     * clobber an existing scheme unless {@code overwrite} is true.
     */
    public static WriteResult writeRouteMap(IProject project, String bpFqn,
        List<Map<String, String>> points, List<Map<String, String>> transitions,
        boolean overwrite, boolean dryRun)
    {
        WriteResult r = new WriteResult();
        IFile file = flowchartFile(project, bpFqn);
        if (file == null)
        {
            r.error = "bpFqn must be a BusinessProcess FQN (BusinessProcess.<Name>)"; //$NON-NLS-1$
            return r;
        }
        if (file.getParent() == null || !file.getParent().exists())
        {
            r.error = "BusinessProcess " + bpFqn + " does not exist; create it first"; //$NON-NLS-1$ //$NON-NLS-2$
            return r;
        }
        if (points == null || points.isEmpty())
        {
            r.error = "points must contain at least one route point"; //$NON-NLS-1$
            return r;
        }
        File osFile = file.getLocation() != null ? file.getLocation().toFile() : null;
        if (!overwrite && osFile != null && osFile.isFile())
        {
            r.error = "Flowchart.scheme already exists for " + bpFqn //$NON-NLS-1$
                + "; pass overwrite=true to replace it"; //$NON-NLS-1$
            return r;
        }

        List<Placed> placed = new ArrayList<>();
        Map<String, Placed> byName = new LinkedHashMap<>();
        int y = 60;
        for (Map<String, String> p : points)
        {
            String type = asString(p.get("type")); //$NON-NLS-1$
            String name = asString(p.get("name")); //$NON-NLS-1$
            PointDef def = pointDef(type);
            if (def == null)
            {
                r.error = "Unknown route point type '" + type //$NON-NLS-1$
                    + "' (use Start / Action / Condition / Completion / NestedBusinessProcess)"; //$NON-NLS-1$
                return r;
            }
            if (name == null || name.trim().isEmpty())
            {
                r.error = "Every route point needs a name"; //$NON-NLS-1$
                return r;
            }
            name = name.trim();
            if (byName.containsKey(name))
            {
                r.error = "Duplicate route point name '" + name + "'"; //$NON-NLS-1$ //$NON-NLS-2$
                return r;
            }
            Placed pl = new Placed();
            pl.def = def;
            pl.name = name;
            pl.title = asString(p.get("title")); //$NON-NLS-1$
            pl.taskDescription = asString(p.get("taskDescription")); //$NON-NLS-1$
            pl.subprocess = asString(p.get("subprocess")); //$NON-NLS-1$
            if (def.hasSubprocess && (pl.subprocess == null || pl.subprocess.trim().isEmpty()))
            {
                r.error = "Route point '" + name //$NON-NLS-1$
                    + "' (NestedBusinessProcess) needs a 'subprocess' (a BusinessProcess FQN)"; //$NON-NLS-1$
                return r;
            }
            int w = def.small ? 40 : 120;
            int h = def.small ? 40 : 60;
            pl.left = 200 - w / 2;
            pl.right = 200 + w / 2;
            pl.top = y;
            pl.bottom = y + h;
            y = pl.bottom + 40;
            placed.add(pl);
            byName.put(name, pl);
        }

        int startCount = 0;
        int completionCount = 0;
        for (Placed pl : placed)
        {
            if ("Start".equals(pl.def.tag)) //$NON-NLS-1$
            {
                startCount++;
            }
            else if ("Completion".equals(pl.def.tag)) //$NON-NLS-1$
            {
                completionCount++;
            }
        }
        if (startCount != 1)
        {
            r.error = "A route map needs exactly one Start point (found " + startCount + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            return r;
        }
        if (completionCount < 1)
        {
            r.error = "A route map needs at least one Completion point"; //$NON-NLS-1$
            return r;
        }

        TaskAddressing addr = readLinkedTask(project, bpFqn);

        List<Trans> lines = new ArrayList<>();
        if (transitions != null)
        {
            for (Map<String, String> t : transitions)
            {
                String fromName = asString(t.get("from")); //$NON-NLS-1$
                String toName = asString(t.get("to")); //$NON-NLS-1$
                Placed from = fromName != null ? byName.get(fromName.trim()) : null;
                Placed to = toName != null ? byName.get(toName.trim()) : null;
                if (from == null)
                {
                    r.error = "Transition references unknown 'from' point: " + fromName; //$NON-NLS-1$
                    return r;
                }
                if (to == null)
                {
                    r.error = "Transition references unknown 'to' point: " + toName; //$NON-NLS-1$
                    return r;
                }
                if (from == to)
                {
                    r.error = "Transition from '" + from.name + "' to itself is not allowed"; //$NON-NLS-1$ //$NON-NLS-2$
                    return r;
                }
                if (!from.def.hasOut && !from.def.condition)
                {
                    r.error = "Point '" + from.name + "' (" + from.def.tag //$NON-NLS-1$ //$NON-NLS-2$
                        + ") has no outgoing port"; //$NON-NLS-1$
                    return r;
                }
                if (!to.def.hasIn)
                {
                    r.error = "Point '" + to.name + "' (" + to.def.tag //$NON-NLS-1$ //$NON-NLS-2$
                        + ") has no incoming port"; //$NON-NLS-1$
                    return r;
                }
                Trans tr = new Trans();
                tr.from = from;
                tr.to = to;
                tr.toPort = 2;
                tr.title = asString(t.get("title")); //$NON-NLS-1$
                if (from.def.condition)
                {
                    Boolean b = parseBranch(asString(t.get("branch"))); //$NON-NLS-1$
                    if (b == null)
                    {
                        r.error = "Transition from Condition '" + from.name //$NON-NLS-1$
                            + "' needs branch=true|false (yes/no, да/нет)"; //$NON-NLS-1$
                        return r;
                    }
                    tr.fromPort = b.booleanValue() ? 3 : 1;
                    if (tr.title == null || tr.title.isEmpty())
                    {
                        tr.title = b.booleanValue() ? "Да" : "Нет"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                else
                {
                    tr.fromPort = 4;
                }
                lines.add(tr);
            }
        }

        Set<String> claimedPorts = new HashSet<>();
        for (Trans tr : lines)
        {
            if (!claimedPorts.add(tr.from.name + "#" + tr.fromPort)) //$NON-NLS-1$
            {
                r.error = "Point '" + tr.from.name //$NON-NLS-1$
                    + "' has two transitions leaving the same port" //$NON-NLS-1$
                    + (tr.from.def.condition
                        ? " (a Condition's true and false branch may each be used once)" : ""); //$NON-NLS-1$ //$NON-NLS-2$
                return r;
            }
        }
        for (Placed pl : placed)
        {
            if (!pl.def.condition)
            {
                continue;
            }
            boolean hasTrue = false;
            boolean hasFalse = false;
            for (Trans tr : lines)
            {
                if (tr.from != pl)
                {
                    continue;
                }
                if (tr.fromPort == 3)
                {
                    hasTrue = true;
                }
                else if (tr.fromPort == 1)
                {
                    hasFalse = true;
                }
            }
            if (!hasTrue || !hasFalse)
            {
                r.error = "Condition '" + pl.name //$NON-NLS-1$
                    + "' needs both a true and a false outgoing transition (branch=true and branch=false)"; //$NON-NLS-1$
                return r;
            }
        }

        StringBuilder sb = new StringBuilder();
        appendHeader(sb);
        int id = 1;
        for (Placed pl : placed)
        {
            appendPointXml(sb, pl, id, id, id - 1, addr);
            id++;
        }
        int lineNo = 1;
        for (Trans tr : lines)
        {
            appendLineXml(sb, tr, id, id, id - 1, lineNo);
            id++;
            lineNo++;
        }
        appendFooter(sb);
        r.xml = sb.toString();
        r.pointCount = placed.size();
        r.transitionCount = lines.size();
        if (dryRun)
        {
            return r;
        }
        try
        {
            byte[] bytes = r.xml.getBytes(StandardCharsets.UTF_8);
            // Sync Eclipse's cached resource state with disk truth before deciding
            // create() vs setContents() - the overwrite guard above tested the disk
            // file directly, so a stale cache could otherwise pick the wrong branch.
            if (file.getParent() != null)
            {
                file.getParent().refreshLocal(IResource.DEPTH_ONE, null);
            }
            if (file.exists())
            {
                file.setContents(new ByteArrayInputStream(bytes), true, false, null);
            }
            else
            {
                file.create(new ByteArrayInputStream(bytes), true, null);
            }
            if (file.getParent() != null)
            {
                file.getParent().refreshLocal(IResource.DEPTH_ONE, null);
            }
            r.written = true;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to write Flowchart.scheme for " + bpFqn, e); //$NON-NLS-1$
            r.error = "Failed to write Flowchart.scheme: " + e.getMessage(); //$NON-NLS-1$
        }
        return r;
    }

    /** Fixed properties of one route-point kind (ports, box size, events, tails). */
    private static final class PointDef
    {
        String tag;
        boolean small;
        boolean hasIn;
        boolean hasOut;
        boolean condition;
        boolean hasTaskDescription;
        boolean hasAddressing;
        boolean hasSubprocess;
        String[] events;
    }

    /** A point placed into the vertical layout. */
    private static final class Placed
    {
        PointDef def;
        String name;
        String title;
        String taskDescription;
        String subprocess;
        int top;
        int bottom;
        int left;
        int right;

        int midY()
        {
            return (top + bottom) / 2;
        }
    }

    /** A resolved transition (source / target point, ports, optional title). */
    private static final class Trans
    {
        Placed from;
        Placed to;
        int fromPort;
        int toPort;
        String title;
    }

    /** Linked Task FQN plus its addressing-attribute names (for Activity points). */
    private static final class TaskAddressing
    {
        String taskFqn;
        final List<String> attributeNames = new ArrayList<>();
    }

    private static PointDef pointDef(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        PointDef d = new PointDef();
        switch (raw.trim().toLowerCase(Locale.ROOT))
        {
        case "start": //$NON-NLS-1$
        case "старт": //$NON-NLS-1$
            d.tag = "Start"; //$NON-NLS-1$
            d.small = true;
            d.hasOut = true;
            d.events = new String[] {"BeforeStart"}; //$NON-NLS-1$
            return d;
        case "completion": //$NON-NLS-1$
        case "завершение": //$NON-NLS-1$
            d.tag = "Completion"; //$NON-NLS-1$
            d.small = true;
            d.hasIn = true;
            d.events = new String[] {"OnComplete"}; //$NON-NLS-1$
            return d;
        case "action": //$NON-NLS-1$
        case "activity": //$NON-NLS-1$
        case "действие": //$NON-NLS-1$
            d.tag = "Activity"; //$NON-NLS-1$
            d.hasIn = true;
            d.hasOut = true;
            d.hasTaskDescription = true;
            d.hasAddressing = true;
            d.events = new String[] {"InteractiveActivationProcessing", "BeforeCreateTasks", //$NON-NLS-1$ //$NON-NLS-2$
                "OnCreateTask", "OnExecute", "CheckExecutionProcessing", "BeforeExecute", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "BeforeExecuteInteractively"}; //$NON-NLS-1$
            return d;
        case "condition": //$NON-NLS-1$
        case "условие": //$NON-NLS-1$
            d.tag = "Condition"; //$NON-NLS-1$
            d.hasIn = true;
            d.condition = true;
            d.events = new String[] {"ConditionCheck"}; //$NON-NLS-1$
            return d;
        case "nestedbusinessprocess": //$NON-NLS-1$
        case "subbusinessprocess": //$NON-NLS-1$
        case "вложенный": //$NON-NLS-1$
        case "вложенныйбизнеспроцесс": //$NON-NLS-1$
            d.tag = "SubBusinessProcess"; //$NON-NLS-1$
            d.hasIn = true;
            d.hasOut = true;
            d.hasTaskDescription = true;
            d.hasSubprocess = true;
            d.events = new String[] {"BeforeCreateTasks", "OnCreateTask", //$NON-NLS-1$ //$NON-NLS-2$
                "OnCreateSubBusinessProcesses", "OnExecute", "BeforeExecute", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "BeforeCreateSubBusinessProcesses"}; //$NON-NLS-1$
            return d;
        default:
            return null;
        }
    }

    private static void appendHeader(StringBuilder sb)
    {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"); //$NON-NLS-1$
        sb.append("<GraphicalSchema xmlns=\"http://v8.1c.ru/8.3/xcf/scheme\"") //$NON-NLS-1$
            .append(" xmlns:sch=\"http://v8.1c.ru/8.2/data/graphscheme\"") //$NON-NLS-1$
            .append(" xmlns:style=\"http://v8.1c.ru/8.1/data/ui/style\"") //$NON-NLS-1$
            .append(" xmlns:v8=\"http://v8.1c.ru/8.1/data/core\"") //$NON-NLS-1$
            .append(" xmlns:v8ui=\"http://v8.1c.ru/8.1/data/ui\"") //$NON-NLS-1$
            .append(" xmlns:web=\"http://v8.1c.ru/8.1/data/ui/colors/web\"") //$NON-NLS-1$
            .append(" xmlns:win=\"http://v8.1c.ru/8.1/data/ui/colors/windows\"") //$NON-NLS-1$
            .append(" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"") //$NON-NLS-1$
            .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"); //$NON-NLS-1$
        sb.append("\t<BackColor>style:FieldBackColor</BackColor>\n"); //$NON-NLS-1$
        sb.append("\t<GridEnabled>true</GridEnabled>\n"); //$NON-NLS-1$
        sb.append("\t<DrawGridMode>Lines</DrawGridMode>\n"); //$NON-NLS-1$
        sb.append("\t<GridHorizontalStep>20</GridHorizontalStep>\n"); //$NON-NLS-1$
        sb.append("\t<GridVerticalStep>20</GridVerticalStep>\n"); //$NON-NLS-1$
        sb.append("\t<PrintParameters>\n"); //$NON-NLS-1$
        sb.append("\t\t<TopMargin>10</TopMargin>\n"); //$NON-NLS-1$
        sb.append("\t\t<LeftMargin>10</LeftMargin>\n"); //$NON-NLS-1$
        sb.append("\t\t<BottomMargin>10</BottomMargin>\n"); //$NON-NLS-1$
        sb.append("\t\t<RightMargin>10</RightMargin>\n"); //$NON-NLS-1$
        sb.append("\t\t<BlackAndWhite>false</BlackAndWhite>\n"); //$NON-NLS-1$
        sb.append("\t\t<FitPageMode>Auto</FitPageMode>\n"); //$NON-NLS-1$
        sb.append("\t</PrintParameters>\n"); //$NON-NLS-1$
        sb.append("\t<Items>\n"); //$NON-NLS-1$
    }

    private static void appendFooter(StringBuilder sb)
    {
        sb.append("\t</Items>\n"); //$NON-NLS-1$
        sb.append("</GraphicalSchema>"); //$NON-NLS-1$
    }

    private static void appendCommonProps(StringBuilder sb, String ind, String name, String title,
        int tabOrder, int zOrder)
    {
        sb.append(ind).append("<Name>").append(xmlEscape(name)).append("</Name>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (title != null && !title.isEmpty())
        {
            sb.append(ind).append("<Title>\n"); //$NON-NLS-1$
            sb.append(ind).append("\t<v8:item>\n"); //$NON-NLS-1$
            sb.append(ind).append("\t\t<v8:lang>ru</v8:lang>\n"); //$NON-NLS-1$
            sb.append(ind).append("\t\t<v8:content>").append(xmlEscape(title)) //$NON-NLS-1$
                .append("</v8:content>\n"); //$NON-NLS-1$
            sb.append(ind).append("\t</v8:item>\n"); //$NON-NLS-1$
            sb.append(ind).append("</Title>\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append(ind).append("<Title/>\n"); //$NON-NLS-1$
        }
        sb.append(ind).append("<ToolTip/>\n"); //$NON-NLS-1$
        sb.append(ind).append("<TabOrder>").append(tabOrder).append("</TabOrder>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(ind).append("<BackColor>auto</BackColor>\n"); //$NON-NLS-1$
        sb.append(ind).append("<TextColor>style:FormTextColor</TextColor>\n"); //$NON-NLS-1$
        sb.append(ind).append("<LineColor>style:BorderColor</LineColor>\n"); //$NON-NLS-1$
        sb.append(ind).append("<GroupNumber>0</GroupNumber>\n"); //$NON-NLS-1$
        sb.append(ind).append("<ZOrder>").append(zOrder).append("</ZOrder>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(ind).append("<Hyperlink>false</Hyperlink>\n"); //$NON-NLS-1$
        sb.append(ind).append("<Transparent>false</Transparent>\n"); //$NON-NLS-1$
        sb.append(ind).append("<Font kind=\"AutoFont\"/>\n"); //$NON-NLS-1$
        sb.append(ind).append("<HorizontalAlign>Center</HorizontalAlign>\n"); //$NON-NLS-1$
        sb.append(ind).append("<VerticalAlign>Center</VerticalAlign>\n"); //$NON-NLS-1$
        sb.append(ind).append("<PictureLocation>Left</PictureLocation>\n"); //$NON-NLS-1$
    }

    private static void appendPointXml(StringBuilder sb, Placed pl, int id, int tab, int z,
        TaskAddressing addr)
    {
        PointDef d = pl.def;
        sb.append("\t\t<").append(d.tag).append(" id=\"").append(id).append("\" uuid=\"") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .append(UUID.randomUUID()).append("\">\n"); //$NON-NLS-1$
        String ind = "\t\t\t\t"; //$NON-NLS-1$
        sb.append("\t\t\t<Properties>\n"); //$NON-NLS-1$
        appendCommonProps(sb, ind, pl.name, pl.title, tab, z);
        sb.append(ind).append("<Location top=\"").append(pl.top).append("\" left=\"") //$NON-NLS-1$ //$NON-NLS-2$
            .append(pl.left).append("\" bottom=\"").append(pl.bottom).append("\" right=\"") //$NON-NLS-1$ //$NON-NLS-2$
            .append(pl.right).append("\"/>\n"); //$NON-NLS-1$
        sb.append(ind).append("<Border width=\"1\" gap=\"false\">\n"); //$NON-NLS-1$
        sb.append(ind).append("\t<v8ui:style xsi:type=\"sch:ConnectorLineType\">Solid</v8ui:style>\n"); //$NON-NLS-1$
        sb.append(ind).append("</Border>\n"); //$NON-NLS-1$
        sb.append(ind).append("<Picture/>\n"); //$NON-NLS-1$
        sb.append(ind).append("<PictureSize>AutoSize</PictureSize>\n"); //$NON-NLS-1$
        if (d.hasSubprocess && pl.subprocess != null && !pl.subprocess.isEmpty())
        {
            sb.append(ind).append("<Subprocess>").append(xmlEscape(pl.subprocess)) //$NON-NLS-1$
                .append("</Subprocess>\n"); //$NON-NLS-1$
        }
        if (d.hasTaskDescription)
        {
            String td = pl.taskDescription != null && !pl.taskDescription.isEmpty()
                ? pl.taskDescription : pl.name;
            sb.append(ind).append("<TaskDescription>").append(xmlEscape(td)) //$NON-NLS-1$
                .append("</TaskDescription>\n"); //$NON-NLS-1$
        }
        if (d.hasAddressing)
        {
            sb.append(ind).append("<Group>false</Group>\n"); //$NON-NLS-1$
            if (addr != null && addr.taskFqn != null && !addr.attributeNames.isEmpty())
            {
                sb.append(ind).append("<AddressingAttributes>\n"); //$NON-NLS-1$
                for (String an : addr.attributeNames)
                {
                    sb.append(ind).append("\t<AddressingAttribute ref=\"").append(addr.taskFqn) //$NON-NLS-1$
                        .append(".AddressingAttribute.").append(an).append("\">\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    sb.append(ind).append("\t\t<Value xsi:nil=\"true\"/>\n"); //$NON-NLS-1$
                    sb.append(ind).append("\t</AddressingAttribute>\n"); //$NON-NLS-1$
                }
                sb.append(ind).append("</AddressingAttributes>\n"); //$NON-NLS-1$
            }
        }
        if (d.condition)
        {
            sb.append(ind).append("<TruePortIndex>3</TruePortIndex>\n"); //$NON-NLS-1$
            sb.append(ind).append("<FalsePortIndex>1</FalsePortIndex>\n"); //$NON-NLS-1$
        }
        sb.append("\t\t\t</Properties>\n"); //$NON-NLS-1$
        sb.append("\t\t\t<Events>\n"); //$NON-NLS-1$
        for (String ev : d.events)
        {
            sb.append("\t\t\t\t<Event name=\"").append(ev).append("\"/>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\t\t\t</Events>\n"); //$NON-NLS-1$
        sb.append("\t\t</").append(d.tag).append(">\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void appendLineXml(StringBuilder sb, Trans tr, int id, int tab, int z, int lineNo)
    {
        sb.append("\t\t<ConnectionLine id=\"").append(id).append("\">\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String ind = "\t\t\t\t"; //$NON-NLS-1$
        sb.append("\t\t\t<Properties>\n"); //$NON-NLS-1$
        appendCommonProps(sb, ind, "Линия" + lineNo, tr.title, tab, z); //$NON-NLS-1$
        int[] fromA = anchor(tr.from, tr.fromPort);
        int[] toA = anchor(tr.to, tr.toPort);
        int mx = (fromA[0] + toA[0]) / 2;
        int my = (fromA[1] + toA[1]) / 2;
        sb.append(ind).append("<PivotPoints>\n"); //$NON-NLS-1$
        appendPoint(sb, ind, fromA[0], fromA[1]);
        appendPoint(sb, ind, mx, my);
        appendPoint(sb, ind, mx, my);
        appendPoint(sb, ind, toA[0], toA[1]);
        sb.append(ind).append("</PivotPoints>\n"); //$NON-NLS-1$
        sb.append(ind).append("<Connect>\n"); //$NON-NLS-1$
        sb.append(ind).append("\t<From>\n"); //$NON-NLS-1$
        sb.append(ind).append("\t\t<Item>").append(xmlEscape(tr.from.name)).append("</Item>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(ind).append("\t\t<PortIndex>").append(tr.fromPort).append("</PortIndex>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(ind).append("\t</From>\n"); //$NON-NLS-1$
        sb.append(ind).append("\t<To>\n"); //$NON-NLS-1$
        sb.append(ind).append("\t\t<Item>").append(xmlEscape(tr.to.name)).append("</Item>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(ind).append("\t\t<PortIndex>").append(tr.toPort).append("</PortIndex>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(ind).append("\t</To>\n"); //$NON-NLS-1$
        sb.append(ind).append("</Connect>\n"); //$NON-NLS-1$
        sb.append(ind).append("<Line width=\"1\" gap=\"false\">\n"); //$NON-NLS-1$
        sb.append(ind).append("\t<v8ui:style xsi:type=\"sch:ConnectorLineType\">Solid</v8ui:style>\n"); //$NON-NLS-1$
        sb.append(ind).append("</Line>\n"); //$NON-NLS-1$
        sb.append(ind).append("<DecorativeLine>false</DecorativeLine>\n"); //$NON-NLS-1$
        sb.append(ind).append("<TextLocation>FirstSegment</TextLocation>\n"); //$NON-NLS-1$
        sb.append(ind).append("<BeginArrow>None</BeginArrow>\n"); //$NON-NLS-1$
        sb.append(ind).append("<EndArrow>Filled</EndArrow>\n"); //$NON-NLS-1$
        sb.append("\t\t\t</Properties>\n"); //$NON-NLS-1$
        sb.append("\t\t</ConnectionLine>\n"); //$NON-NLS-1$
    }

    private static void appendPoint(StringBuilder sb, String ind, int x, int y)
    {
        sb.append(ind).append("\t<Point x=\"").append(x).append("\" y=\"").append(y).append("\"/>\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static int[] anchor(Placed pl, int port)
    {
        switch (port)
        {
        case 1:
            return new int[] {pl.left, pl.midY()};
        case 2:
            return new int[] {200, pl.top};
        case 3:
            return new int[] {pl.right, pl.midY()};
        case 4:
        default:
            return new int[] {200, pl.bottom};
        }
    }

    private static TaskAddressing readLinkedTask(IProject project, String bpFqn)
    {
        try
        {
            IFile flow = flowchartFile(project, bpFqn);
            if (flow == null || flow.getParent() == null)
            {
                return null;
            }
            String bpName = bpFqn.substring(bpFqn.indexOf('.') + 1);
            IFile bpMdoRes = ((org.eclipse.core.resources.IContainer)flow.getParent())
                .getFile(new org.eclipse.core.runtime.Path(bpName + ".mdo")); //$NON-NLS-1$
            File bpMdo = bpMdoRes.getLocation() != null ? bpMdoRes.getLocation().toFile() : null;
            if (bpMdo == null || !bpMdo.isFile())
            {
                return null;
            }
            Element bpRoot = newSecureFactory().newDocumentBuilder().parse(bpMdo).getDocumentElement();
            String taskFqn = childText(bpRoot, "task"); //$NON-NLS-1$
            if (taskFqn == null || !taskFqn.startsWith("Task.")) //$NON-NLS-1$
            {
                return null;
            }
            String taskName = taskFqn.substring("Task.".length()); //$NON-NLS-1$
            IFile taskMdoRes = project.getFolder("src").getFolder("Tasks").getFolder(taskName) //$NON-NLS-1$ //$NON-NLS-2$
                .getFile(taskName + ".mdo"); //$NON-NLS-1$
            File taskMdo = taskMdoRes.getLocation() != null ? taskMdoRes.getLocation().toFile() : null;
            if (taskMdo == null || !taskMdo.isFile())
            {
                return null;
            }
            Element taskRoot =
                newSecureFactory().newDocumentBuilder().parse(taskMdo).getDocumentElement();
            TaskAddressing addr = new TaskAddressing();
            addr.taskFqn = taskFqn;
            NodeList kids = taskRoot.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++)
            {
                Node n = kids.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE
                    && localNameMatches(n.getNodeName(), "addressingAttributes")) //$NON-NLS-1$
                {
                    String an = childText((Element)n, "name"); //$NON-NLS-1$
                    if (an != null && !an.isEmpty())
                    {
                        addr.attributeNames.add(an);
                    }
                }
            }
            return addr.attributeNames.isEmpty() ? null : addr;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to read linked Task addressing for " + bpFqn, e); //$NON-NLS-1$
            return null;
        }
    }

    private static String asString(Object o)
    {
        return o == null ? null : o.toString();
    }

    private static Boolean parseBranch(String b)
    {
        if (b == null)
        {
            return null;
        }
        switch (b.trim().toLowerCase(Locale.ROOT))
        {
        case "true": //$NON-NLS-1$
        case "yes": //$NON-NLS-1$
        case "да": //$NON-NLS-1$
        case "1": //$NON-NLS-1$
            return Boolean.TRUE;
        case "false": //$NON-NLS-1$
        case "no": //$NON-NLS-1$
        case "нет": //$NON-NLS-1$
        case "0": //$NON-NLS-1$
            return Boolean.FALSE;
        default:
            return null;
        }
    }

    private static String xmlEscape(String s)
    {
        if (s == null)
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
            case '&':
                b.append("&amp;"); //$NON-NLS-1$
                break;
            case '<':
                b.append("&lt;"); //$NON-NLS-1$
                break;
            case '>':
                b.append("&gt;"); //$NON-NLS-1$
                break;
            case '"':
                b.append("&quot;"); //$NON-NLS-1$
                break;
            case '\'':
                b.append("&apos;"); //$NON-NLS-1$
                break;
            default:
                b.append(c);
            }
        }
        return b.toString();
    }
}
