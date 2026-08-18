/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;

import ru.aiedt.mcp.server.Activator;

/**
 * Analyzer for 1C:Enterprise Role rights. Walks the {@link Role} EMF model and
 * collects per-object rights for the four audit modes.
 * <p>
 * <b>1.39 status:</b> EMF-based with reflection fallback. Real EDT 2026.1 may
 * expose {@code IRole.getRightsSettings()} or similar; this helper probes the
 * available getter via reflection and surfaces results as a uniform
 * {@link RightsTable}.
 */
public final class RoleRightsAnalyzer
{
    /**
     * Standard 1C right kinds. Order matters for column layout in markdown.
     */
    public static final List<String> STANDARD_RIGHTS = List.of(
        "Read", "Update", "Insert", "Delete", "View", "Use"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    private RoleRightsAnalyzer()
    {
        // utility class
    }

    /**
     * Single right-setting on a metadata object: {@code Allow} / {@code Deny}
     * / {@code unspecified}.
     */
    public enum Verdict
    {
        ALLOW, DENY, UNSPECIFIED
    }

    /**
     * Per-object grid of rights for a role. {@code rights[objectFqn][rightKind]}
     * maps to {@link Verdict}. Includes {@code hasRls} boolean indicating
     * presence of an RLS restriction on the object.
     */
    public static final class RightsTable
    {
        public final String roleName;
        public final Map<String, Map<String, Verdict>> rights = new LinkedHashMap<>();
        public final Map<String, Boolean> hasRls = new LinkedHashMap<>();

        public RightsTable(String roleName)
        {
            this.roleName = roleName;
        }

        public Map<String, Object> toMap()
        {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("roleName", roleName); //$NON-NLS-1$
            Map<String, Object> rightsOut = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Verdict>> entry : rights.entrySet())
            {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<String, Verdict> e2 : entry.getValue().entrySet())
                {
                    row.put(e2.getKey(), e2.getValue().name());
                }
                if (Boolean.TRUE.equals(hasRls.get(entry.getKey())))
                {
                    row.put("hasRls", true); //$NON-NLS-1$
                }
                rightsOut.put(entry.getKey(), row);
            }
            map.put("rights", rightsOut); //$NON-NLS-1$
            return map;
        }
    }

    /**
     * Resolves a Role by case-insensitive name from the configuration.
     */
    public static Role findRole(Configuration config, String name)
    {
        if (config == null || name == null)
        {
            return null;
        }
        Collection<Role> roles = listRoles(config);
        for (Role role : roles)
        {
            if (name.equalsIgnoreCase(role.getName()))
            {
                return role;
            }
        }
        return null;
    }

    /**
     * Returns the role list of a Configuration via reflection on
     * {@code getRoles()}.
     */
    @SuppressWarnings("unchecked")
    public static Collection<Role> listRoles(Configuration config)
    {
        if (config == null)
        {
            return Collections.emptyList();
        }
        // Configuration declares getRoles(), so it is called, not looked up by name.
        try
        {
            EList<Role> roles = config.getRoles();
            if (roles != null)
            {
                return roles;
            }
        }
        catch (Throwable e)
        {
            Activator.logWarning("RoleRightsAnalyzer.listRoles failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return Collections.emptyList();
    }

    /**
     * Builds the per-role rights table (reflection-only fallback; no project
     * context). Prefer {@link #analyze(IProject, Role, Collection)} which reads
     * the role's {@code Rights.rights} file directly.
     */
    public static RightsTable analyze(Role role, Collection<MdObject> objects)
    {
        return analyze(null, role, objects);
    }

    /**
     * Builds the per-role rights table. The PRIMARY path reads the role's
     * authoritative {@code src/Roles/<name>/Rights.rights} XML resource (the
     * same file EDT itself loads and {@code BmRightsHelper} writes) - parsing
     * it yields both the per-object right verdicts and the RLS flags in one
     * pass. The REFLECTION path over the EMF {@code Role} model is a fallback
     * for when the file is absent: on EDT 2026.1 {@code role.getRights()}
     * returns an {@code AbstractRoleDescription} whose object-rights list is not
     * a plain {@code EList} on the Role, so the reflection probe indexed
     * nothing and every object came back {@code UNSPECIFIED} (the
     * "audit_role_rights returns '.' for all" bug).
     */
    public static RightsTable analyze(IProject project, Role role, Collection<MdObject> objects)
    {
        RightsTable table = new RightsTable(role != null ? role.getName() : null);
        if (role == null)
        {
            return table;
        }
        Map<String, Map<String, Verdict>> rightsIndex = new LinkedHashMap<>();
        Map<String, Boolean> rlsIndex = new LinkedHashMap<>();
        boolean fileRead;
        try
        {
            fileRead = indexFromRightsFile(project, role.getName(), rightsIndex, rlsIndex);
        }
        catch (Exception e)
        {
            // PARSE_ERROR on the authoritative rights file: surface it explicitly
            // instead of silently falling back to the (broken on EDT 2026.1)
            // reflection path, which would return UNSPECIFIED for all - the
            // original defect. Propagates to the tool as a clear error.
            throw new RuntimeException("Failed to parse Rights.rights for role " //$NON-NLS-1$
                + role.getName() + ": " + e.getMessage(), e); //$NON-NLS-1$
        }
        if (!fileRead)
        {
            rightsIndex = indexRoleRights(role);
        }
        for (MdObject obj : objects)
        {
            String fqn = fqnOf(obj);
            if (fqn == null)
            {
                continue;
            }
            table.rights.put(fqn, rightsIndex.getOrDefault(fqn, defaultVerdicts()));
            if (fileRead)
            {
                table.hasRls.put(fqn, Boolean.TRUE.equals(rlsIndex.get(fqn)));
            }
            else
            {
                table.hasRls.put(fqn, hasRlsFor(role, fqn));
            }
        }
        return table;
    }

    /**
     * Parses the role's {@code src/Roles/<roleName>/Rights.rights} XML into the
     * supplied rights + RLS indexes. Returns {@code true} when the file was
     * found and parsed, {@code false} when it is absent (caller falls back to
     * reflection). A parse error on an EXISTING file is THROWN ({@code Exception})
     * so the caller surfaces a clear error instead of silently falling back to
     * the broken-on-EDT-2026.1 reflection path (which would mask the failure as
     * UNSPECIFIED-for-all - the original bug). Each {@code <object>} contributes
     * one FQN row; each {@code <right>} sets the verdict via
     * {@link #parseVerdict(Object)}; an object has RLS when any of its rights
     * carries a {@code <restrictionByCondition>} or {@code <restrictionByObject>}.
     */
    private static boolean indexFromRightsFile(IProject project, String roleName,
        Map<String, Map<String, Verdict>> rightsIndex, Map<String, Boolean> rlsIndex)
        throws Exception
    {
        if (project == null || project.getLocation() == null || roleName == null || roleName.isEmpty())
        {
            return false;
        }
        Path file = project.getLocation().toFile().toPath()
            .resolve("src").resolve("Roles").resolve(roleName).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Files.exists() returns false on an indeterminate state (e.g. access
        // error) too, which would silently fall back to the broken reflection
        // path. notExists() is true ONLY when absence is definite; an
        // indeterminate or unreadable file falls through to the parse, whose
        // IOException then surfaces as a real error instead of UNSPECIFIED-for-all.
        if (Files.notExists(file))
        {
            return false;
        }
        DocumentBuilder db = newSecureDocumentBuilder();
        Document doc = db.parse(new ByteArrayInputStream(Files.readAllBytes(file)));
        Element rootEl = doc.getDocumentElement();
        if (rootEl == null || !"Rights".equals(rootEl.getTagName())) //$NON-NLS-1$
        {
            throw new IllegalStateException("Rights.rights for role " + roleName //$NON-NLS-1$
                + " has no <Rights> root (got: " //$NON-NLS-1$
                + (rootEl != null ? rootEl.getTagName() : "null") + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        NodeList objectEls = doc.getElementsByTagName("object"); //$NON-NLS-1$
        for (int i = 0; i < objectEls.getLength(); i++)
        {
            Element objEl = (Element) objectEls.item(i);
            Element nameEl = firstChildEl(objEl, "name"); //$NON-NLS-1$
            if (nameEl == null)
            {
                continue;
            }
            String fqn = nameEl.getTextContent().trim();
            Map<String, Verdict> row = rightsIndex.computeIfAbsent(fqn, k -> defaultVerdicts());
            boolean objRls = false;
            NodeList kids = objEl.getChildNodes();
            for (int j = 0; j < kids.getLength(); j++)
            {
                Node n = kids.item(j);
                if (!(n instanceof Element) || !"right".equals(((Element) n).getTagName())) //$NON-NLS-1$
                {
                    continue;
                }
                Element rightEl = (Element) n;
                Element rName = firstChildEl(rightEl, "name"); //$NON-NLS-1$
                if (rName == null)
                {
                    continue;
                }
                Element rValue = firstChildEl(rightEl, "value"); //$NON-NLS-1$
                String valueText = rValue != null ? rValue.getTextContent().trim() : null;
                row.put(rName.getTextContent().trim(), parseVerdict(valueText));
                if (firstChildEl(rightEl, "restrictionByCondition") != null //$NON-NLS-1$
                    || firstChildEl(rightEl, "restrictionByObject") != null) //$NON-NLS-1$
                {
                    objRls = true;
                }
            }
            rlsIndex.put(fqn, objRls);
        }
        return true;
    }

    /**
     * XXE-hardened DOM builder (mirrors {@code JUnitXmlReader.newSecureBuilder}):
     * disallow DOCTYPE, no external general/parameter entities, no external DTD,
     * XInclude and entity expansion off, and an empty external access allow-list.
     * Rights.rights carries no DOCTYPE, so {@code disallow-doctype-decl} does not
     * reject legitimate files but blocks entity-expansion / external-entity
     * attacks on a crafted rights file.
     */
    private static DocumentBuilder newSecureDocumentBuilder() throws ParserConfigurationException
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); //$NON-NLS-1$
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); //$NON-NLS-1$
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); //$NON-NLS-1$
        return factory.newDocumentBuilder();
    }

    /** First direct child element of {@code parent} with the given tag, or null. */
    private static Element firstChildEl(Element parent, String tag)
    {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++)
        {
            Node n = kids.item(i);
            if (n instanceof Element && tag.equals(((Element) n).getTagName()))
            {
                return (Element) n;
            }
        }
        return null;
    }

    private static Map<String, Verdict> defaultVerdicts()
    {
        Map<String, Verdict> map = new LinkedHashMap<>();
        for (String right : STANDARD_RIGHTS)
        {
            map.put(right, Verdict.UNSPECIFIED);
        }
        return map;
    }

    /**
     * Computes objects where the role lacks any Allow right (under-privileged).
     */
    public static List<String> missingObjects(RightsTable table, String objectTypeFilter)
    {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Map<String, Verdict>> entry : table.rights.entrySet())
        {
            String fqn = entry.getKey();
            if (objectTypeFilter != null && !objectTypeFilter.isEmpty()
                && !fqn.startsWith(objectTypeFilter + ".")) //$NON-NLS-1$
            {
                continue;
            }
            boolean anyAllow = false;
            for (Verdict v : entry.getValue().values())
            {
                if (v == Verdict.ALLOW)
                {
                    anyAllow = true;
                    break;
                }
            }
            if (!anyAllow)
            {
                missing.add(fqn);
            }
        }
        return missing;
    }

    /**
     * Computes conflicts between two role tables.
     */
    public static List<Map<String, Object>> conflicts(RightsTable a, RightsTable b)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        if (a == null || b == null)
        {
            return result;
        }
        for (Map.Entry<String, Map<String, Verdict>> entry : a.rights.entrySet())
        {
            String fqn = entry.getKey();
            Map<String, Verdict> rowA = entry.getValue();
            Map<String, Verdict> rowB = b.rights.get(fqn);
            if (rowB == null)
            {
                continue;
            }
            for (String right : STANDARD_RIGHTS)
            {
                Verdict va = rowA.get(right);
                Verdict vb = rowB.get(right);
                if ((va == Verdict.ALLOW && vb == Verdict.DENY)
                    || (va == Verdict.DENY && vb == Verdict.ALLOW))
                {
                    Map<String, Object> conflict = new LinkedHashMap<>();
                    conflict.put("objectFqn", fqn); //$NON-NLS-1$
                    conflict.put("right", right); //$NON-NLS-1$
                    conflict.put(a.roleName, va.name()); //$NON-NLS-1$
                    conflict.put(b.roleName, vb.name()); //$NON-NLS-1$
                    result.add(conflict);
                }
            }
        }
        return result;
    }

    private static String fqnOf(MdObject obj)
    {
        if (obj == null)
        {
            return null;
        }
        try
        {
            if (obj instanceof com._1c.g5.v8.bm.core.IBmObject)
            {
                String fqn = ((com._1c.g5.v8.bm.core.IBmObject) obj).bmGetFqn();
                if (fqn != null)
                {
                    return fqn;
                }
            }
        }
        catch (Throwable ignored)
        {
            // best-effort
        }
        return obj.eClass().getName() + "." + obj.getName(); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Verdict>> indexRoleRights(Role role)
    {
        Map<String, Map<String, Verdict>> result = new LinkedHashMap<>();
        Object source = invokeGetter(role, "getRights"); //$NON-NLS-1$
        if (source == null)
        {
            source = invokeGetter(role, "getRightsSettings"); //$NON-NLS-1$
        }
        if (source == null)
        {
            source = invokeGetter(role, "getObjectRights"); //$NON-NLS-1$
        }
        if (!(source instanceof EList))
        {
            return result;
        }
        for (Object entry : (EList<Object>) source)
        {
            indexOneEntry(result, entry);
        }
        return result;
    }

    private static void indexOneEntry(Map<String, Map<String, Verdict>> result, Object entry)
    {
        if (!(entry instanceof EObject))
        {
            return;
        }
        EObject e = (EObject) entry;
        Object obj = invokeGetter(e, "getObject"); //$NON-NLS-1$
        if (!(obj instanceof MdObject))
        {
            return;
        }
        String fqn = fqnOf((MdObject) obj);
        if (fqn == null)
        {
            return;
        }
        Map<String, Verdict> row = result.computeIfAbsent(fqn, k -> defaultVerdicts());
        Object rightsList = invokeGetter(e, "getRights"); //$NON-NLS-1$
        if (rightsList instanceof EList)
        {
            for (Object rightEntry : (EList<Object>) rightsList)
            {
                applyRightEntry(row, rightEntry);
            }
        }
    }

    private static void applyRightEntry(Map<String, Verdict> row, Object rightEntry)
    {
        if (!(rightEntry instanceof EObject))
        {
            return;
        }
        EObject re = (EObject) rightEntry;
        Object name = invokeGetter(re, "getName"); //$NON-NLS-1$
        Object value = invokeGetter(re, "getValue"); //$NON-NLS-1$
        if (name == null)
        {
            return;
        }
        Verdict verdict = parseVerdict(value);
        row.put(name.toString(), verdict);
    }

    private static Verdict parseVerdict(Object value)
    {
        if (value == null)
        {
            return Verdict.UNSPECIFIED;
        }
        if (value instanceof Boolean)
        {
            return ((Boolean) value) ? Verdict.ALLOW : Verdict.DENY;
        }
        String s = value.toString().toLowerCase();
        if (s.contains("true") || s.contains("allow") || s.contains("разреш")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Verdict.ALLOW;
        }
        if (s.contains("false") || s.contains("deny") || s.contains("запрет")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Verdict.DENY;
        }
        return Verdict.UNSPECIFIED;
    }

    /**
     * Best-effort RLS detection on a role for an object FQN.
     */
    public static boolean hasRlsFor(Role role, String objectFqn)
    {
        if (role == null || objectFqn == null)
        {
            return false;
        }
        Object source = invokeGetter(role, "getRights"); //$NON-NLS-1$
        if (source == null)
        {
            source = invokeGetter(role, "getRightsSettings"); //$NON-NLS-1$
        }
        if (!(source instanceof EList))
        {
            return false;
        }
        for (Object entry : (EList<?>) source)
        {
            if (!(entry instanceof EObject))
            {
                continue;
            }
            EObject e = (EObject) entry;
            Object obj = invokeGetter(e, "getObject"); //$NON-NLS-1$
            if (obj instanceof MdObject)
            {
                String fqn = fqnOf((MdObject) obj);
                if (objectFqn.equals(fqn))
                {
                    Object restrictions = invokeGetter(e, "getRestrictionByCondition"); //$NON-NLS-1$
                    if (restrictions == null)
                    {
                        restrictions = invokeGetter(e, "getRestrictions"); //$NON-NLS-1$
                    }
                    if (restrictions instanceof EList && !((EList<?>) restrictions).isEmpty())
                    {
                        return true;
                    }
                    Object byObject = invokeGetter(e, "getRestrictionByObject"); //$NON-NLS-1$
                    if (byObject != null && !"".equals(byObject.toString())) //$NON-NLS-1$
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Object invokeGetter(Object target, String name)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        }
        catch (NoSuchMethodException nsme)
        {
            return null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
