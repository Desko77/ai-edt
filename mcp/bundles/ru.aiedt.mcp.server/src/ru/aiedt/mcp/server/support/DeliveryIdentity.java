/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import ru.aiedt.mcp.server.Activator;

/**
 * Says which configuration a directory holds, and in what version.
 * <p>
 * <b>Why a comparison needs this.</b> The comparison tool takes directories, and a directory being
 * readable proves only that it is a configuration - not that it is the RIGHT one. Point the ancestor
 * at a different configuration and nothing fails: the comparison runs, and every object our side
 * changed is attributed to the vendor while every vendor change is attributed to us. The attribution
 * is silently inverted, and a bulk decision taken on it applies the wrong rule to everything.
 * </p>
 * <p>
 * So the sides are identified before they are compared, and the identity comes from the
 * configuration itself rather than from the caller.
 * </p>
 */
public final class DeliveryIdentity
{
    /** Where an EDT project keeps the configuration root. */
    private static final String EDT_SHAPE = "src/Configuration/Configuration.mdo"; //$NON-NLS-1$

    /** Where an export made by the Designer keeps it. */
    private static final String DESIGNER_SHAPE = "Configuration.xml"; //$NON-NLS-1$

    /** Identity of the configuration, stable across its versions. */
    public String uuid;

    /** Its name, as the configuration calls itself. */
    public String name;

    /** Who publishes it. */
    public String vendor;

    /** Its version, as the vendor numbers versions. */
    public String version;

    /** Which layout the directory turned out to be, for the answer. */
    public String shape;

    /** Why nothing could be read. Present only when the answer is a refusal. */
    public String cannotTell;

    private DeliveryIdentity()
    {
        // Built by read.
    }

    /**
     * Reads the identity of a configuration held in a directory.
     * <p>
     * Two layouts are accepted because both are legitimate sides of a comparison: an EDT project,
     * and an export made by the Designer. A directory that is neither is refused by name rather
     * than compared blindly.
     * </p>
     *
     * @param directory the directory to identify.
     * @return what was read, or a refusal saying why nothing could be
     */
    public static DeliveryIdentity read(Path directory)
    {
        DeliveryIdentity identity = new DeliveryIdentity();
        if (directory == null || !Files.isDirectory(directory))
        {
            identity.cannotTell = "no directory at " + directory; //$NON-NLS-1$
            return identity;
        }
        Path edt = directory.resolve(EDT_SHAPE);
        if (Files.isRegularFile(edt))
        {
            identity.shape = "EDT project"; //$NON-NLS-1$
            readEdt(edt, identity);
            return identity;
        }
        Path designer = directory.resolve(DESIGNER_SHAPE);
        if (Files.isRegularFile(designer))
        {
            identity.shape = "Designer export"; //$NON-NLS-1$
            readDesigner(designer, identity);
            return identity;
        }
        identity.cannotTell = directory
            + " holds neither " + EDT_SHAPE + " nor " + DESIGNER_SHAPE //$NON-NLS-1$ //$NON-NLS-2$
            + ", so it is not a configuration this can identify"; //$NON-NLS-1$
        return identity;
    }

    /**
     * Reads the root element of an XML file.
     *
     * @param file the file.
     * @param identity where to record a failure.
     * @return the root element, or <code>null</code> when it could not be read
     */
    private static Element rootOf(Path file, DeliveryIdentity identity)
    {
        try (InputStream stream = Files.newInputStream(file))
        {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The file is a configuration written by EDT or the Designer, not input from outside,
            // but external entities are switched off anyway: a parser that fetches whatever a
            // document names is a liability regardless of where the document came from.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(stream);
            return document.getDocumentElement();
        }
        catch (Exception unreadable)
        {
            identity.cannotTell = file + " could not be read: " + unreadable; //$NON-NLS-1$
            Activator.logDebug("delivery identity: " + identity.cannotTell); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reads the identity out of an EDT configuration root.
     *
     * @param file the {@code Configuration.mdo}.
     * @param identity where to write what was found.
     */
    private static void readEdt(Path file, DeliveryIdentity identity)
    {
        Element root = rootOf(file, identity);
        if (root == null)
        {
            return;
        }
        identity.uuid = emptyToNull(root.getAttribute("uuid")); //$NON-NLS-1$
        identity.name = childText(root, "name"); //$NON-NLS-1$
        identity.vendor = childText(root, "vendor"); //$NON-NLS-1$
        identity.version = childText(root, "version"); //$NON-NLS-1$
    }

    /**
     * Reads the identity out of a Designer export.
     *
     * @param file the {@code Configuration.xml}.
     * @param identity where to write what was found.
     */
    private static void readDesigner(Path file, DeliveryIdentity identity)
    {
        Element root = rootOf(file, identity);
        if (root == null)
        {
            return;
        }
        Element configuration = firstChild(root, "Configuration"); //$NON-NLS-1$
        if (configuration == null)
        {
            identity.cannotTell = file + " carries no Configuration element"; //$NON-NLS-1$
            return;
        }
        identity.uuid = emptyToNull(configuration.getAttribute("uuid")); //$NON-NLS-1$
        Element properties = firstChild(configuration, "Properties"); //$NON-NLS-1$
        Element from = properties == null ? configuration : properties;
        identity.name = childText(from, "Name"); //$NON-NLS-1$
        identity.vendor = childText(from, "Vendor"); //$NON-NLS-1$
        identity.version = childText(from, "Version"); //$NON-NLS-1$
    }

    /**
     * Finds the first child element with a local name, ignoring namespaces.
     *
     * @param parent the element to look in.
     * @param localName the name to look for.
     * @return the child, or <code>null</code>
     */
    private static Element firstChild(Element parent, String localName)
    {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if (child instanceof Element && localName.equals(child.getLocalName()))
            {
                return (Element)child;
            }
        }
        return null;
    }

    /**
     * Reads the text of the first child element with a local name.
     *
     * @param parent the element to look in.
     * @param localName the name to look for.
     * @return its text, or <code>null</code> when absent or empty
     */
    private static String childText(Element parent, String localName)
    {
        Element child = firstChild(parent, localName);
        return child == null ? null : emptyToNull(child.getTextContent());
    }

    /**
     * Treats blank as absent.
     *
     * @param value the value read.
     * @return the trimmed value, or <code>null</code> when there is nothing in it
     */
    private static String emptyToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Describes the identity for an answer.
     *
     * @return a readable one-liner
     */
    @Override
    public String toString()
    {
        if (cannotTell != null)
        {
            return cannotTell;
        }
        return (name == null ? "unnamed" : name) //$NON-NLS-1$
            + (version == null ? "" : " " + version) //$NON-NLS-1$ //$NON-NLS-2$
            + (vendor == null ? "" : " by " + vendor); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
