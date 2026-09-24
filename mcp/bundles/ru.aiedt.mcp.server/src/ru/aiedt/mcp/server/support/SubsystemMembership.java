/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

import ru.aiedt.mcp.server.Activator;

/**
 * The objects a subsystem scan is allowed to touch: the subsystem's own composition and the
 * composition of every subsystem nested in it.
 * <p>
 * The model is asked first. A project whose configuration is not built - a scratch project, a
 * model that cannot be read - is answered from the subsystem files on disk, which is where that
 * composition is written. Either way a name that is not there is a refusal, not an empty
 * composition, and a name that matches more than one subsystem is a refusal too.
 * </p>
 */
public final class SubsystemMembership
{
    /** What an answer says when nested subsystems were folded into the composition. */
    public static final String NESTED_INCLUDED =
        "Nested subsystems are included"; //$NON-NLS-1$

    private static final Pattern NAME_TAG = Pattern.compile("<name>\\s*([^<]+?)\\s*</name>"); //$NON-NLS-1$

    private static final Pattern CONTENT_TAG = Pattern.compile("<content>\\s*([^<]+?)\\s*</content>"); //$NON-NLS-1$

    private final String refusal;

    private final String subsystemName;

    private final List<String> objectFqns;

    private final List<String> folderPrefixes;

    private SubsystemMembership(String refusal, String subsystemName, List<String> objectFqns,
        List<String> folderPrefixes)
    {
        this.refusal = refusal;
        this.subsystemName = subsystemName;
        this.objectFqns = objectFqns;
        this.folderPrefixes = folderPrefixes;
    }

    /**
     * @return the refusal, or <code>null</code> when the subsystem was found
     */
    public String refusal()
    {
        return refusal;
    }

    /**
     * @return whether the name could not be resolved
     */
    public boolean refused()
    {
        return refusal != null;
    }

    /**
     * @return the name that was asked for
     */
    public String subsystemName()
    {
        return subsystemName;
    }

    /**
     * @return the objects in the composition, nested subsystems included; empty when refused
     */
    public List<String> objectFqns()
    {
        return objectFqns;
    }

    /**
     * @return how many objects the composition holds
     */
    public int compositionSize()
    {
        return objectFqns.size();
    }

    /**
     * @return the sentence an answer carries: nested subsystems are included, and how many objects
     *         the composition holds
     */
    public String compositionNote()
    {
        return NESTED_INCLUDED + "; " + compositionSize() + " objects in the composition."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether a project-relative path sits inside one of the composition's objects.
     * <p>
     * The match is the object's folder as a prefix of the path from the source root {@code src/}.
     * A copy of the same folder elsewhere in the project - {@code backup/CommonModules/Sales/},
     * for example - is a different file and is not part of the composition.
     * </p>
     *
     * @param projectRelativePath the path
     * @return whether the scan may read it
     */
    public boolean coversPath(String projectRelativePath)
    {
        if (projectRelativePath == null)
        {
            return false;
        }
        String normalized = projectRelativePath.replace('\\', '/');
        for (String prefix : folderPrefixes)
        {
            if (normalized.startsWith("src/" + prefix)) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an object FQN is one of the composition, or a child of one (an attribute of a member).
     *
     * @param ownerFqn the object
     * @return whether a finding on it belongs to this subsystem
     */
    public boolean coversObject(String ownerFqn)
    {
        if (ownerFqn == null)
        {
            return false;
        }
        for (String fqn : objectFqns)
        {
            if (ownerFqn.equalsIgnoreCase(fqn)
                || ownerFqn.toLowerCase(Locale.ROOT).startsWith(fqn.toLowerCase(Locale.ROOT) + ".")) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a subsystem by name.
     *
     * @param project the project; required for the file fallback
     * @param configuration the configuration, or <code>null</code> when the model is not built
     * @param subsystemName the name
     * @return the membership, refused when the name is unknown or ambiguous
     */
    public static SubsystemMembership resolve(IProject project, Configuration configuration,
        String subsystemName)
    {
        if (subsystemName == null || subsystemName.isEmpty())
        {
            return refused(subsystemName, "subsystemName is required."); //$NON-NLS-1$
        }
        if (configuration != null)
        {
            SubsystemMembership modeled = fromModel(configuration, subsystemName);
            // Not found on the model is not the last word: a project whose configuration has not
            // been built still has the composition in its subsystem files. Ambiguous is final.
            if (!modeled.refused() || modeled.refusal().indexOf("was not found") < 0) //$NON-NLS-1$
            {
                return modeled;
            }
        }
        return fromFiles(project, subsystemName);
    }

    /**
     * The configuration of a project, or <code>null</code> when the model is not there to ask.
     *
     * @param project the project
     * @return the configuration, or <code>null</code>
     */
    public static Configuration configurationOf(IProject project)
    {
        try
        {
            Activator activator = Activator.getDefault();
            if (activator == null)
            {
                return null;
            }
            com._1c.g5.v8.dt.core.platform.IConfigurationProvider provider =
                activator.getConfigurationProvider();
            if (provider == null || project == null)
            {
                return null;
            }
            return provider.getConfiguration(project);
        }
        catch (RuntimeException unavailable)
        {
            // A project the model has never opened has nothing to hand back. The files are the
            // composition then, and a failure here must not become a walk of the whole project.
            return null;
        }
    }

    private static SubsystemMembership fromModel(Configuration configuration, String subsystemName)
    {
        List<Subsystem> matches = new ArrayList<>();
        if (subsystemName.indexOf('.') >= 0)
        {
            String fqn = subsystemName.toLowerCase(Locale.ROOT).startsWith("subsystem.") //$NON-NLS-1$
                ? subsystemName : "Subsystem." + subsystemName; //$NON-NLS-1$
            Subsystem nested = MetadataTypeCatalog.findNestedSubsystem(configuration, fqn);
            if (nested != null)
            {
                matches.add(nested);
            }
        }
        if (matches.isEmpty())
        {
            collectNamed(configuration.getSubsystems(), subsystemName, matches);
        }
        if (matches.isEmpty())
        {
            return notFound(subsystemName);
        }
        if (matches.size() > 1)
        {
            return ambiguous(subsystemName);
        }
        LinkedHashSet<String> fqns = new LinkedHashSet<>();
        collectContent(matches.get(0), fqns);
        return found(subsystemName, fqns);
    }

    private static void collectNamed(EList<Subsystem> level, String name, List<Subsystem> matches)
    {
        if (level == null)
        {
            return;
        }
        for (Subsystem subsystem : level)
        {
            if (subsystem == null)
            {
                continue;
            }
            if (name.equalsIgnoreCase(subsystem.getName()))
            {
                matches.add(subsystem);
            }
            collectNamed(subsystem.getSubsystems(), name, matches);
        }
    }

    private static void collectContent(Subsystem subsystem, LinkedHashSet<String> fqns)
    {
        for (Object item : subsystem.getContent())
        {
            if (item instanceof Subsystem)
            {
                continue;
            }
            if (item instanceof MdObject)
            {
                MdObject object = (MdObject) item;
                if (object.getName() != null)
                {
                    fqns.add(object.eClass().getName() + "." + object.getName()); //$NON-NLS-1$
                }
            }
        }
        for (Subsystem child : subsystem.getSubsystems())
        {
            if (child != null)
            {
                collectContent(child, fqns);
            }
        }
    }

    private static SubsystemMembership fromFiles(IProject project, String subsystemName)
    {
        if (project == null)
        {
            return notFound(subsystemName);
        }
        List<Node> nodes = readNodes(project);
        if (nodes.isEmpty())
        {
            return notFound(subsystemName);
        }
        linkChildren(nodes);
        List<Node> matches = new ArrayList<>();
        for (Node node : nodes)
        {
            if (subsystemName.equalsIgnoreCase(node.name))
            {
                matches.add(node);
            }
        }
        if (matches.isEmpty() && subsystemName.indexOf('.') >= 0)
        {
            Node dotted = findDotted(nodes, subsystemName);
            if (dotted != null)
            {
                matches.add(dotted);
            }
        }
        if (matches.isEmpty())
        {
            return notFound(subsystemName);
        }
        if (matches.size() > 1)
        {
            return ambiguous(subsystemName);
        }
        LinkedHashSet<String> fqns = new LinkedHashSet<>();
        collectFileContent(matches.get(0), fqns);
        return found(subsystemName, fqns);
    }

    private static Node findDotted(List<Node> roots, String subsystemName)
    {
        String[] segments = subsystemName.split("\\."); //$NON-NLS-1$
        List<String> names = new ArrayList<>();
        for (int i = 0; i < segments.length; i++)
        {
            if (!"subsystem".equalsIgnoreCase(segments[i]) && !segments[i].isEmpty()) //$NON-NLS-1$
            {
                names.add(segments[i]);
            }
        }
        if (names.isEmpty())
        {
            return null;
        }
        List<Node> level = new ArrayList<>();
        for (Node node : roots)
        {
            if (node.parent == null)
            {
                level.add(node);
            }
        }
        Node found = null;
        for (String name : names)
        {
            found = null;
            for (Node node : level)
            {
                if (name.equalsIgnoreCase(node.name))
                {
                    found = node;
                    break;
                }
            }
            if (found == null)
            {
                return null;
            }
            level = found.children;
        }
        return found;
    }

    private static void collectFileContent(Node node, LinkedHashSet<String> fqns)
    {
        fqns.addAll(node.content);
        for (Node child : node.children)
        {
            collectFileContent(child, fqns);
        }
    }

    private static List<Node> readNodes(IProject project)
    {
        List<Node> nodes = new ArrayList<>();
        try
        {
            project.accept(new IResourceVisitor()
            {
                @Override
                public boolean visit(IResource resource)
                {
                    if (resource instanceof IFile && resource.getName().endsWith(".mdo")) //$NON-NLS-1$
                    {
                        String path = resource.getProjectRelativePath().toString().replace('\\', '/');
                        if (path.startsWith("src/Subsystems/") && path.endsWith(".mdo")) //$NON-NLS-1$ //$NON-NLS-2$
                        {
                            Node node = readNode((IFile) resource, path);
                            if (node != null)
                            {
                                nodes.add(node);
                            }
                        }
                    }
                    return true;
                }
            }, IResource.DEPTH_INFINITE, IResource.NONE);
        }
        catch (Exception failed)
        {
            Activator.logWarning("subsystem composition not read: " + failed.getMessage()); //$NON-NLS-1$
        }
        return nodes;
    }

    private static Node readNode(IFile file, String path)
    {
        String text = readText(file);
        if (text == null)
        {
            return null;
        }
        Matcher name = NAME_TAG.matcher(text);
        if (!name.find())
        {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String folder = slash < 0 ? path : path.substring(0, slash);
        Node node = new Node(name.group(1).trim(), folder);
        Matcher content = CONTENT_TAG.matcher(text);
        while (content.find())
        {
            String fqn = content.group(1).trim();
            if (!fqn.isEmpty())
            {
                node.content.add(fqn);
            }
        }
        return node;
    }

    private static void linkChildren(List<Node> nodes)
    {
        for (Node node : nodes)
        {
            Node parent = null;
            int parentLength = -1;
            for (Node candidate : nodes)
            {
                String childRoot = candidate.folder + "/Subsystems/"; //$NON-NLS-1$
                if (node.folder.startsWith(childRoot) && candidate.folder.length() > parentLength)
                {
                    parent = candidate;
                    parentLength = candidate.folder.length();
                }
            }
            if (parent != null && parent != node)
            {
                node.parent = parent;
                parent.children.add(node);
            }
        }
    }

    private static String readText(IFile file)
    {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                text.append(line).append('\n');
            }
            return text.toString();
        }
        catch (Exception failed)
        {
            return null;
        }
    }

    private static SubsystemMembership found(String subsystemName, LinkedHashSet<String> fqns)
    {
        List<String> objects = new ArrayList<>(fqns);
        List<String> prefixes = new ArrayList<>();
        for (String fqn : objects)
        {
            String prefix = folderPrefix(fqn);
            if (prefix != null)
            {
                prefixes.add(prefix);
            }
        }
        return new SubsystemMembership(null, subsystemName, objects, prefixes);
    }

    /**
     * The folder an object occupies under {@code src/}, with a trailing slash so a shorter name
     * does not match a longer neighbour.
     *
     * @param fqn an object FQN, {@code Type.Name} or deeper
     * @return the prefix, or <code>null</code> when the type has no folder
     */
    static String folderPrefix(String fqn)
    {
        if (fqn == null)
        {
            return null;
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        String directory = MetadataTypeCatalog.getDirectoryName(parts[0]);
        if (directory == null)
        {
            return null;
        }
        return directory + "/" + parts[1] + "/"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static SubsystemMembership notFound(String subsystemName)
    {
        return refused(subsystemName, "Subsystem '" + subsystemName + "' was not found."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static SubsystemMembership ambiguous(String subsystemName)
    {
        return refused(subsystemName, "Subsystem '" + subsystemName //$NON-NLS-1$
            + "' is ambiguous: more than one subsystem has that name."); //$NON-NLS-1$
    }

    private static SubsystemMembership refused(String subsystemName, String refusal)
    {
        return new SubsystemMembership(refusal, subsystemName, new ArrayList<>(), new ArrayList<>());
    }

    private static final class Node
    {
        private final String name;

        private final String folder;

        private final List<String> content = new ArrayList<>();

        private final List<Node> children = new ArrayList<>();

        private Node parent;

        private Node(String name, String folder)
        {
            this.name = name;
            this.folder = folder;
        }
    }
}
