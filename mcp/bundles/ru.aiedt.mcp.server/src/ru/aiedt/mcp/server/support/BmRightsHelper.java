/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import ru.aiedt.mcp.server.Activator;

/**
 * Helper for {@code Role} rights operations (set_role_right).
 *
 * <p>Role rights live in the role's SEPARATE {@code src/Roles/<name>/Rights.rights}
 * resource, bound to the role by path. We edit that file directly
 * ({@link #applyRightToFile}) rather than mutating the EDT Business Model: a fresh
 * role has no loadable {@code RoleDescription}, and a factory-created one cannot be
 * persisted by a BM commit (the cross-resource "Failed to persist reference value"
 * wall). The file is parsed namespace-unaware and rewritten with a generic
 * tab + CRLF printer, so all content - including RLS {@code <restrictionByCondition>}
 * blocks and root templates - round-trips untouched; only the requested
 * {@code <object>/<right>/<value>} is changed. The caller revalidates the role to
 * sync the in-memory model.
 *
 * <p>Right names are mapped from English/Russian aliases to the canonical platform
 * names via {@link #canonicalRightName(String)} (Read/Чтение, Insert/Добавление,
 * Update/Изменение, Delete/Удаление, View/Просмотр, Edit/Редактирование, ...).
 */
public final class BmRightsHelper
{
    private static volatile Boolean apiAvailable;
    private static final Object LOCK = new Object();

    /** Right alias map: case-insensitive lookup, value is canonical name. */
    private static final Map<String, String> RIGHT_ALIASES = buildRightAliases();

    /**
     * Per-role-file locks so concurrent rights writers (set_role_right and its
     * dependency cascade, set_role_restriction, restriction templates) serialize their
     * read-modify-write on one {@code Rights.rights} instead of racing (M3). Keyed by
     * the resolved file path. A sequential agent rarely races, but the dependency
     * cascade issues N+1 writes to the same file, so guarding the write closes the
     * check-then-write window.
     */
    private static final Map<String, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

    private static ReentrantLock fileLock(String key)
    {
        return FILE_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
    }

    private BmRightsHelper()
    {
        // utility
    }

    /**
     * Returns true when the EDT rights model is reachable via Class.forName.
     * Cached after the first probe.
     */
    public static boolean isAvailable()
    {
        if (apiAvailable != null)
        {
            return apiAvailable;
        }
        synchronized (LOCK)
        {
            if (apiAvailable != null)
            {
                return apiAvailable;
            }
            apiAvailable = probe();
            return apiAvailable;
        }
    }

    private static boolean probe()
    {
        for (String cls : new String[] {
            "com._1c.g5.v8.dt.rights.model.RightsFactory", //$NON-NLS-1$
            "com._1c.g5.v8.dt.rights.model.ObjectRights", //$NON-NLS-1$
            "com._1c.g5.v8.dt.rights.model.RoleDescription" //$NON-NLS-1$
        })
        {
            try
            {
                Class.forName(cls);
            }
            catch (ClassNotFoundException ignored)
            {
                Activator.logInfo("BmRightsHelper.probe: " + cls + " not on classpath"); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a deferred-style explanation when the rights API is not
     * available on the current EDT build.
     */
    public static String deferredMessage(String op)
    {
        return op + " requires the EDT rights model (com._1c.g5.v8.dt.rights.model). "
            + "It is not available on this EDT build. Open the role in the EDT GUI "
            + "and edit rights manually for now.";
    }

    /**
     * Returns the canonical names of the rights that {@code canonicalRightName} REQUIRES
     * (its prerequisites - e.g. Update requires Read; Posting requires Read + Update),
     * from the platform's own transitively-closed dependency map
     * {@code com._1c.g5.v8.dt.rights.model.util.IRightsConstants.RIGHT_DEPEND_HIERARHY}.
     * <p>
     * Read strictly in the GRANT direction: the map answers "what does X require", so a
     * caller granting X can also grant this set without ever over-granting - the map never
     * expresses the reverse (granting Read never implies Update). The target right itself
     * is excluded. Names come from {@code RightName.getName()}, which yields exactly the
     * canonical strings the {@code Rights.rights} writer expects (Read, Update,
     * ReadDataHistory, ...).
     * <p>
     * Reflection-only over a static immutable {@code Map} + enum (no EMF / BM lifecycle,
     * unlike RoleDescription), so it degrades to an EMPTY set when the
     * {@code rights.model.util} package is absent on the running EDT - the caller then
     * simply performs no cascade.
     */
    public static Set<String> requiredRightNames(String canonicalRightName)
    {
        Set<String> result = new LinkedHashSet<>();
        if (canonicalRightName == null || canonicalRightName.isEmpty())
        {
            return result;
        }
        try
        {
            Class<?> rightNameCls = Class.forName("com._1c.g5.v8.dt.rights.model.util.RightName"); //$NON-NLS-1$
            Method getByName = rightNameCls.getMethod("getByName", String.class); //$NON-NLS-1$
            Object rn = getByName.invoke(null, canonicalRightName);
            if (rn == null)
            {
                return result; // unknown right name - nothing to cascade
            }
            Class<?> constCls =
                Class.forName("com._1c.g5.v8.dt.rights.model.util.IRightsConstants"); //$NON-NLS-1$
            Field depField = constCls.getField("RIGHT_DEPEND_HIERARHY"); //$NON-NLS-1$
            Object mapObj = depField.get(null);
            if (!(mapObj instanceof Map))
            {
                return result;
            }
            Object depsObj = ((Map<?, ?>) mapObj).get(rn);
            if (!(depsObj instanceof Iterable))
            {
                return result;
            }
            Method getName = rightNameCls.getMethod("getName"); //$NON-NLS-1$
            for (Object dep : (Iterable<?>) depsObj)
            {
                if (dep == null)
                {
                    continue;
                }
                Object nm = getName.invoke(dep);
                String s = nm != null ? nm.toString() : null;
                if (s != null && !s.isEmpty() && !s.equalsIgnoreCase(canonicalRightName))
                {
                    result.add(s);
                }
            }
        }
        catch (Throwable t)
        {
            // rights.model.util not wired on this EDT build, or the map shape changed:
            // degrade to no cascade rather than a hard failure.
            Activator.logInfo("requiredRightNames(" + canonicalRightName //$NON-NLS-1$
                + "): rights dependency map unavailable (" + t.getClass().getSimpleName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    /**
     * Resolves an alias to the canonical right name.
     *
     * @return canonical name, or the input if no alias matches (caller may
     *         use the input verbatim, the platform will reject if invalid)
     */
    public static String canonicalRightName(String alias)
    {
        if (alias == null || alias.isEmpty())
        {
            return alias;
        }
        String key = alias.toLowerCase(Locale.ROOT);
        String mapped = RIGHT_ALIASES.get(key);
        return mapped == null ? alias : mapped;
    }

    /**
     * Builds the standard right-name aliases (case-insensitive).
     */
    private static Map<String, String> buildRightAliases()
    {
        Map<String, String> m = new HashMap<>();
        // English canonical -> identity
        for (String name : new String[] { "Read", "Insert", "Update", "Delete", "View",
            "Edit", "InteractiveInsert", "InteractiveDelete", "InteractiveUpdate",
            "InteractiveDeletionMark", "InteractivePosting", "InteractiveUndoPosting",
            "ThinClient", "WebClient", "MobileClient", "SaveUserData", "Output",
            "Use", "Posting", "UndoPosting", "InputByString", "TotalsControl",
            "RecoverData", "InteractiveOpenExtDataProcessors", "DataAdministration",
            "Administration", "ConfigurationExtensionsAdministration" })
        {
            m.put(name.toLowerCase(Locale.ROOT), name);
        }
        // Russian -> English canonical
        m.put("чтение", "Read");
        m.put("просмотр", "View");
        m.put("добавление", "Insert");
        m.put("изменение", "Update");
        m.put("редактирование", "Edit");
        m.put("удаление", "Delete");
        m.put("интерактивноедобавление", "InteractiveInsert");
        m.put("интерактивноеудаление", "InteractiveDelete");
        m.put("интерактивноеизменение", "InteractiveUpdate");
        m.put("проведение", "Posting");
        m.put("отменапроведения", "UndoPosting");
        m.put("использование", "Use");
        m.put("вывод", "Output");
        m.put("сохранениеданныхпользователя", "SaveUserData");
        m.put("тонкийклиент", "ThinClient");
        m.put("веб-клиент", "WebClient");
        m.put("веб клиент", "WebClient");
        m.put("мобильныйклиент", "MobileClient");
        m.put("администрирование", "Administration");
        m.put("администрированиерасширенийконфигурации", "ConfigurationExtensionsAdministration");
        return m;
    }

    /**
     * Writes the prebuilt {@code Rights.rights} XML to
     * {@code src/Roles/<roleName>/Rights.rights} and refreshes the workspace
     * folder. Call AFTER the BM commit (file write + workspace refresh must not
     * run inside the transaction).
     *
     * @return null on success, or a non-null error note (the BM mutation has
     *     already committed)
     */
    public static String writeRightsFile(IProject project, String roleName, String xml)
    {
        if (project == null || roleName == null || roleName.isEmpty() || xml == null)
        {
            return "project, roleName and xml are required"; //$NON-NLS-1$
        }
        if (project.getLocation() == null)
        {
            return "project location is not on the local filesystem"; //$NON-NLS-1$
        }
        Path dir = project.getLocation().toFile().toPath()
            .resolve("src").resolve("Roles").resolve(roleName); //$NON-NLS-1$ //$NON-NLS-2$
        Path target = dir.resolve("Rights.rights"); //$NON-NLS-1$
        // Data-loss guard: never replace a populated Rights.rights with an
        // object-less one. If the serialized XML carries no <object> but the
        // existing file does, the mutated RoleDescription was read empty (e.g. a
        // cross-resource proxy that did not load) - refuse the wipe and keep the
        // file. The BM mutation has still committed; the caller surfaces this note.
        if (!xml.contains("<object>")) //$NON-NLS-1$
        {
            try
            {
                if (Files.exists(target)
                    && new String(Files.readAllBytes(target), StandardCharsets.UTF_8).contains("<object>")) //$NON-NLS-1$
                {
                    return "Rights.rights NOT rewritten: the resolved role rights were empty but " //$NON-NLS-1$
                        + "the existing file has object rights - refusing to overwrite (no data lost). " //$NON-NLS-1$
                        + "Re-run after the role's rights resource is loaded."; //$NON-NLS-1$
                }
            }
            catch (IOException ignored)
            {
                // fall through to the write attempt - a read failure must not block a fresh write
            }
        }
        try
        {
            Files.createDirectories(dir);
            // Write to a temp file then move into place, so the workspace refresh
            // below (and any EDT re-read it triggers) never observes a truncated
            // half-written Rights.rights.
            Path tmp = dir.resolve("Rights.rights.tmp"); //$NON-NLS-1$
            Files.write(tmp, xml.getBytes(StandardCharsets.UTF_8));
            try
            {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException amns)
            {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException ioe)
        {
            return "Failed to write Rights.rights: " + ioe.getMessage(); //$NON-NLS-1$
        }
        try
        {
            IFolder folder = project.getFolder("src").getFolder("Roles").getFolder(roleName); //$NON-NLS-1$ //$NON-NLS-2$
            if (folder.exists())
            {
                folder.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
            else
            {
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
        }
        catch (CoreException ce)
        {
            Activator.logWarning("Rights.rights written but workspace refresh failed: " //$NON-NLS-1$
                + ce.getMessage());
        }
        return null;
    }

    /** Outcome of {@link #applyRightToFile}. */
    public static final class FileRightResult
    {
        public boolean ok;
        public boolean idempotent;
        public boolean objectCreated;
        public boolean rightCreated;
        public boolean fileCreated;
        public String previousValue; // "true" / "false" / null (not set before)
        public String error;
    }

    /**
     * Sets a role right directly in the role's {@code src/Roles/<name>/Rights.rights}
     * resource - the authoritative, EDT-path-bound persistence for role rights.
     *
     * <p>This sidesteps the BM cross-resource problem: a fresh role has no loadable
     * {@code RoleDescription}, and a factory-created one cannot be persisted by a BM
     * commit ("Failed to persist reference value RoleDescriptionImpl"). The file is
     * what EDT reads, so we parse-merge-write it and let the caller revalidate to
     * sync the in-memory model.
     *
     * <p>The existing file is parsed (namespace-unaware) and rewritten with a generic
     * tab + CRLF printer, so ALL content is preserved - including RLS
     * {@code <restrictionByCondition>} blocks and root templates that a name/value
     * model would drop. Only the one {@code <object>/<right>/<value>} is touched.
     *
     * @param granted true -&gt; {@code <value>true</value>} (SET), false -&gt; {@code false} (denied)
     */
    public static FileRightResult applyRightToFile(IProject project, String roleName,
        String targetFqn, String canonicalRightName, boolean granted, boolean dryRun)
    {
        FileRightResult res = new FileRightResult();
        if (project == null || roleName == null || roleName.isEmpty()
            || targetFqn == null || targetFqn.isEmpty()
            || canonicalRightName == null || canonicalRightName.isEmpty())
        {
            res.error = "project, roleName, targetFqn and rightName are required"; //$NON-NLS-1$
            return res;
        }
        // L2: roleName becomes a path segment under src/Roles/ - reject traversal (matches
        // the guard on set_restriction_template / set_role_restriction).
        if (roleName.indexOf('/') >= 0 || roleName.indexOf('\\') >= 0 || roleName.contains("..")) //$NON-NLS-1$
        {
            res.error = "roleName must be a simple role name (no path separators or '..')"; //$NON-NLS-1$
            return res;
        }
        if (project.getLocation() == null)
        {
            res.error = "project location is not on the local filesystem"; //$NON-NLS-1$
            return res;
        }
        Path file = project.getLocation().toFile().toPath()
            .resolve("src").resolve("Roles").resolve(roleName).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // M3: serialize the read-modify-write on this one Rights.rights so a concurrent
        // writer (or this op's own dependency cascade, N+1 writes) cannot interleave and
        // lose an edit. Lock before the try, release in finally.
        ReentrantLock lock = fileLock(file.toString());
        lock.lock();
        try
        {
            Document doc;
            if (Files.exists(file))
            {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(false); // keep xmlns* as literal attributes for round-trip
                DocumentBuilder db = dbf.newDocumentBuilder();
                doc = db.parse(new ByteArrayInputStream(Files.readAllBytes(file)));
            }
            else
            {
                doc = newRightsDocument();
                res.fileCreated = true;
            }
            Element root = doc.getDocumentElement();
            if (root == null || !"Rights".equals(root.getTagName())) //$NON-NLS-1$
            {
                res.error = "Rights.rights has no <Rights> root"; //$NON-NLS-1$
                return res;
            }
            Element objectEl = findChildByName(root, "object", targetFqn); //$NON-NLS-1$
            if (objectEl == null)
            {
                objectEl = doc.createElement("object"); //$NON-NLS-1$
                appendTextChild(doc, objectEl, "name", targetFqn); //$NON-NLS-1$
                root.appendChild(objectEl);
                res.objectCreated = true;
            }
            Element rightEl = findChildByName(objectEl, "right", canonicalRightName); //$NON-NLS-1$
            if (rightEl == null)
            {
                rightEl = doc.createElement("right"); //$NON-NLS-1$
                appendTextChild(doc, rightEl, "name", canonicalRightName); //$NON-NLS-1$
                appendTextChild(doc, rightEl, "value", String.valueOf(granted)); //$NON-NLS-1$
                objectEl.appendChild(rightEl);
                res.rightCreated = true;
            }
            else
            {
                Element valueEl = firstChild(rightEl, "value"); //$NON-NLS-1$
                res.previousValue = valueEl != null ? valueEl.getTextContent() : null;
                if (res.previousValue != null
                    && String.valueOf(granted).equalsIgnoreCase(res.previousValue.trim()))
                {
                    res.idempotent = true;
                    res.ok = true;
                    return res; // already at the requested value - no rewrite
                }
                if (valueEl == null)
                {
                    appendTextChild(doc, rightEl, "value", String.valueOf(granted)); //$NON-NLS-1$
                }
                else
                {
                    valueEl.setTextContent(String.valueOf(granted));
                }
            }
            if (dryRun)
            {
                res.ok = true;
                return res;
            }
            String xml = printRightsDom(doc);
            String writeErr = writeRightsFile(project, roleName, xml);
            if (writeErr != null)
            {
                res.error = writeErr;
                return res;
            }
            res.ok = true;
            return res;
        }
        catch (Exception e)
        {
            res.error = "Rights.rights edit failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return res;
        }
        finally
        {
            lock.unlock();
        }
    }

    /** Outcome of {@link #applyRestrictionTemplateToFile}. */
    public static final class FileTemplateResult
    {
        public boolean ok;
        public boolean idempotent; // remove: not present; set: condition already equal
        public boolean created;    // set: a new template was added
        public boolean updated;    // set: an existing template's condition changed
        public boolean removed;    // remove: template was removed
        public boolean fileCreated;
        public String error;
    }

    /**
     * Adds/updates or removes a root-level named RLS restriction template -
     * {@code <restrictionTemplate><name>..</name><condition>..</condition></restrictionTemplate>}
     * directly under the {@code <Rights>} root of the role's
     * {@code src/Roles/<name>/Rights.rights}. Uses the same file-level
     * parse-merge-write as {@link #applyRightToFile}, so all other content (object
     * rights, RLS conditions, the other templates) is preserved untouched.
     *
     * <p>{@code set} (remove=false): if a template with {@code templateName} exists,
     * its {@code <condition>} is replaced; otherwise a new template is appended.
     * {@code remove} (remove=true): the template with that name is removed;
     * idempotent when absent (or the file does not exist).
     *
     * @param templateName the template name/signature (e.g. {@code ДляОбъекта(ПолеОбъекта)})
     * @param condition the RLS condition text (required for set, ignored for remove);
     *        line endings are normalized to LF to match round-tripped conditions
     * @param remove true -&gt; remove; false -&gt; add/update
     */
    public static FileTemplateResult applyRestrictionTemplateToFile(IProject project,
        String roleName, String templateName, String condition, boolean remove, boolean dryRun)
    {
        FileTemplateResult res = new FileTemplateResult();
        if (project == null || roleName == null || roleName.isEmpty()
            || templateName == null || templateName.isEmpty())
        {
            res.error = "project, roleName and templateName are required"; //$NON-NLS-1$
            return res;
        }
        if (!remove && condition == null)
        {
            res.error = "condition is required to set a restriction template"; //$NON-NLS-1$
            return res;
        }
        // Defend the path: roleName becomes a path segment under src/Roles/.
        if (roleName.indexOf('/') >= 0 || roleName.indexOf('\\') >= 0 || roleName.contains("..")) //$NON-NLS-1$
        {
            res.error = "roleName must be a simple role name (no path separators or '..')"; //$NON-NLS-1$
            return res;
        }
        // 1C identifiers are case-insensitive; match and store the trimmed name.
        templateName = templateName.trim();
        if (templateName.isEmpty())
        {
            res.error = "templateName must not be blank"; //$NON-NLS-1$
            return res;
        }
        if (project.getLocation() == null)
        {
            res.error = "project location is not on the local filesystem"; //$NON-NLS-1$
            return res;
        }
        Path file = project.getLocation().toFile().toPath()
            .resolve("src").resolve("Roles").resolve(roleName).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // M3: same per-file lock as applyRightToFile, so this writer serializes against
        // set_role_right / cascade and the other restriction writer on one Rights.rights.
        ReentrantLock lock = fileLock(file.toString());
        lock.lock();
        try
        {
            if (!Files.exists(file) && remove)
            {
                res.idempotent = true; // nothing to remove
                res.ok = true;
                return res;
            }
            Document doc;
            if (Files.exists(file))
            {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(false); // keep xmlns* as literal attributes for round-trip
                doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(Files.readAllBytes(file)));
            }
            else
            {
                doc = newRightsDocument();
                res.fileCreated = true;
            }
            Element root = doc.getDocumentElement();
            if (root == null || !"Rights".equals(root.getTagName())) //$NON-NLS-1$
            {
                res.error = "Rights.rights has no <Rights> root"; //$NON-NLS-1$
                return res;
            }
            Element tplEl = findRestrictionTemplateCI(root, templateName);
            if (remove)
            {
                if (tplEl == null)
                {
                    res.idempotent = true;
                    res.ok = true;
                    return res; // not present
                }
                if (dryRun)
                {
                    res.removed = true;
                    res.ok = true;
                    return res;
                }
                root.removeChild(tplEl);
                res.removed = true;
            }
            else
            {
                String normCondition = condition.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                if (tplEl == null)
                {
                    tplEl = doc.createElement("restrictionTemplate"); //$NON-NLS-1$
                    appendTextChild(doc, tplEl, "name", templateName); //$NON-NLS-1$
                    appendTextChild(doc, tplEl, "condition", normCondition); //$NON-NLS-1$
                    root.appendChild(tplEl);
                    res.created = true;
                }
                else
                {
                    Element condEl = firstChild(tplEl, "condition"); //$NON-NLS-1$
                    String prev = condEl != null ? condEl.getTextContent() : null;
                    if (prev != null && normCondition.equals(prev))
                    {
                        res.idempotent = true; // condition unchanged
                        res.ok = true;
                        return res;
                    }
                    res.updated = true; // reached only when the condition actually differs
                    if (condEl == null)
                    {
                        appendTextChild(doc, tplEl, "condition", normCondition); //$NON-NLS-1$
                    }
                    else
                    {
                        condEl.setTextContent(normCondition);
                    }
                }
            }
            if (dryRun)
            {
                res.ok = true;
                return res;
            }
            String xml = printRightsDom(doc);
            String writeErr = writeRightsFile(project, roleName, xml);
            if (writeErr != null)
            {
                res.error = writeErr;
                return res;
            }
            res.ok = true;
            return res;
        }
        catch (Exception e)
        {
            res.error = "Rights.rights template edit failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return res;
        }
        finally
        {
            lock.unlock();
        }
    }

    /** Outcome of {@link #applyRoleRestrictionToFile}. */
    public static final class FileRestrictionResult
    {
        public boolean ok;
        public boolean idempotent; // set: condition already equal; remove: not present
        public boolean created;    // set: a new restrictionByCondition was added
        public boolean updated;    // set: an existing condition changed
        public boolean removed;    // remove: restrictionByCondition removed
        public boolean objectCreated;
        public boolean rightCreated;
        public boolean fileCreated;
        public String error;
    }

    /**
     * Adds/updates or removes a per-object, per-right ROW-LEVEL RLS condition -
     * {@code <right>..<restrictionByCondition><condition>TEXT</condition></restrictionByCondition></right>}
     * inside the {@code <object>} for {@code targetFqn} in the role's Rights.rights.
     * Same file-level parse-merge-write as {@link #applyRightToFile}; all other content
     * (other objects/rights/root templates) is preserved untouched. Field-level restriction
     * (the {@code <field>} list) is intentionally out of scope - condition-only.
     *
     * <p>set (remove=false): ensures the {@code <object>} and {@code <right>} exist (a newly
     * created right is granted, {@code <value>true</value>}, since an RLS condition on a denied
     * right is inert), then adds or replaces its {@code <restrictionByCondition><condition>}.
     * remove (remove=true): strips the restriction from the right; the right itself is left
     * intact (use set_role_right to revoke a right). Idempotent (set: condition unchanged;
     * remove: no restriction / no right / no object / no file).
     *
     * @param rightName canonical right name (Read / Insert / Update / Delete / ...)
     * @param condition RLS condition text (required for set, ignored for remove); LF-normalized
     * @param remove true -&gt; remove the restriction; false -&gt; add/update
     */
    public static FileRestrictionResult applyRoleRestrictionToFile(IProject project,
        String roleName, String targetFqn, String rightName, String condition, boolean remove,
        boolean dryRun)
    {
        FileRestrictionResult res = new FileRestrictionResult();
        if (project == null || roleName == null || roleName.isEmpty()
            || targetFqn == null || targetFqn.isEmpty()
            || rightName == null || rightName.isEmpty())
        {
            res.error = "project, roleName, targetFqn and rightName are required"; //$NON-NLS-1$
            return res;
        }
        if (!remove && condition == null)
        {
            res.error = "condition is required to set a role restriction"; //$NON-NLS-1$
            return res;
        }
        if (roleName.indexOf('/') >= 0 || roleName.indexOf('\\') >= 0 || roleName.contains("..")) //$NON-NLS-1$
        {
            res.error = "roleName must be a simple role name (no path separators or '..')"; //$NON-NLS-1$
            return res;
        }
        if (project.getLocation() == null)
        {
            res.error = "project location is not on the local filesystem"; //$NON-NLS-1$
            return res;
        }
        Path file = project.getLocation().toFile().toPath()
            .resolve("src").resolve("Roles").resolve(roleName).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // M3: same per-file lock as applyRightToFile, so this writer serializes against
        // set_role_right / cascade and the other restriction writer on one Rights.rights.
        ReentrantLock lock = fileLock(file.toString());
        lock.lock();
        try
        {
            if (!Files.exists(file) && remove)
            {
                res.idempotent = true; // nothing to remove
                res.ok = true;
                return res;
            }
            Document doc;
            if (Files.exists(file))
            {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(false); // keep xmlns* as literal attributes for round-trip
                doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(Files.readAllBytes(file)));
            }
            else
            {
                doc = newRightsDocument();
                res.fileCreated = true;
            }
            Element root = doc.getDocumentElement();
            if (root == null || !"Rights".equals(root.getTagName())) //$NON-NLS-1$
            {
                res.error = "Rights.rights has no <Rights> root"; //$NON-NLS-1$
                return res;
            }
            Element objectEl = findChildByName(root, "object", targetFqn); //$NON-NLS-1$
            if (remove && objectEl == null)
            {
                res.idempotent = true;
                res.ok = true;
                return res;
            }
            if (objectEl == null)
            {
                objectEl = doc.createElement("object"); //$NON-NLS-1$
                appendTextChild(doc, objectEl, "name", targetFqn); //$NON-NLS-1$
                root.appendChild(objectEl);
                res.objectCreated = true;
            }
            Element rightEl = findChildByName(objectEl, "right", rightName); //$NON-NLS-1$
            if (remove && rightEl == null)
            {
                res.idempotent = true;
                res.ok = true;
                return res;
            }
            if (rightEl == null)
            {
                rightEl = doc.createElement("right"); //$NON-NLS-1$
                appendTextChild(doc, rightEl, "name", rightName); //$NON-NLS-1$
                appendTextChild(doc, rightEl, "value", "true"); //$NON-NLS-1$ //$NON-NLS-2$
                objectEl.appendChild(rightEl);
                res.rightCreated = true;
            }
            Element rbcEl = firstChild(rightEl, "restrictionByCondition"); //$NON-NLS-1$
            if (remove)
            {
                if (rbcEl == null)
                {
                    res.idempotent = true;
                    res.ok = true;
                    return res;
                }
                if (dryRun)
                {
                    res.removed = true;
                    res.ok = true;
                    return res;
                }
                rightEl.removeChild(rbcEl);
                res.removed = true;
            }
            else
            {
                String normCondition = condition.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                if (rbcEl == null)
                {
                    rbcEl = doc.createElement("restrictionByCondition"); //$NON-NLS-1$
                    appendTextChild(doc, rbcEl, "condition", normCondition); //$NON-NLS-1$
                    rightEl.appendChild(rbcEl);
                    res.created = true;
                }
                else
                {
                    Element condEl = firstChild(rbcEl, "condition"); //$NON-NLS-1$
                    String prev = condEl != null ? condEl.getTextContent() : null;
                    if (prev != null && normCondition.equals(prev))
                    {
                        res.idempotent = true; // condition unchanged
                        res.ok = true;
                        return res;
                    }
                    res.updated = true;
                    if (condEl == null)
                    {
                        appendTextChild(doc, rbcEl, "condition", normCondition); //$NON-NLS-1$
                    }
                    else
                    {
                        condEl.setTextContent(normCondition);
                    }
                }
            }
            if (dryRun)
            {
                res.ok = true;
                return res;
            }
            String xml = printRightsDom(doc);
            String writeErr = writeRightsFile(project, roleName, xml);
            if (writeErr != null)
            {
                res.error = writeErr;
                return res;
            }
            res.ok = true;
            return res;
        }
        catch (Exception e)
        {
            res.error = "Rights.rights restriction edit failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return res;
        }
        finally
        {
            lock.unlock();
        }
    }

    /** Builds an empty Rights document with the stock root + three default flags. */
    private static Document newRightsDocument() throws Exception
    {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        Document doc = dbf.newDocumentBuilder().newDocument();
        Element root = doc.createElement("Rights"); //$NON-NLS-1$
        root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance"); //$NON-NLS-1$ //$NON-NLS-2$
        root.setAttribute("xmlns", "http://v8.1c.ru/8.2/roles"); //$NON-NLS-1$ //$NON-NLS-2$
        root.setAttribute("xsi:type", "Rights"); //$NON-NLS-1$ //$NON-NLS-2$
        doc.appendChild(root);
        appendTextChild(doc, root, "setForNewObjects", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        appendTextChild(doc, root, "setForAttributesByDefault", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        appendTextChild(doc, root, "independentRightsOfChildObjects", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        return doc;
    }

    /**
     * What a sweep of a role's rights file found.
     */
    public static final class OrphanSweep
    {
        /** Whether the file could be read at all. */
        public boolean ok;

        /** FQNs whose object is certainly not in the configuration. */
        public final java.util.List<String> orphaned = new java.util.ArrayList<>();

        /** FQNs this could not decide about, with the reason - never removed. */
        public final java.util.Map<String, String> undecided = new java.util.LinkedHashMap<>();

        /** How many object blocks the file holds in all. */
        public int total;

        /** Whether anything was actually written. */
        public boolean changed;

        /** Why the sweep failed, when it did. */
        public String error;
    }

    /**
     * Finds - and optionally removes - rights on objects the configuration no longer has.
     * <p>
     * A role keeps an {@code <object>} block per metadata object it says anything about. Delete the
     * object and the block stays: EDT does not always sweep it, and an XML import from the
     * Configurator or a storage update leaves them behind wholesale. They are invisible in the
     * editor and they accumulate.
     * </p>
     * <p>
     * <b>Removal is refused unless the caller asks for it explicitly, and even then only for
     * entries this is CERTAIN about.</b> Deleting a rights entry is a security change that nobody
     * reviews afterwards, so anything undecidable - a type prefix this does not recognise, a model
     * that would not load - is reported and left exactly where it is. A repair that guesses is
     * worse than the rubbish it removes.
     * </p>
     *
     * @param project the project the role belongs to.
     * @param roleName the role.
     * @param exists decides whether one FQN is still in the configuration; returns {@code null} when
     *            it cannot tell, and the reason goes in the report.
     * @param apply {@code false} to report only.
     * @return what was found, and what was done about it
     */
    public static OrphanSweep sweepOrphanedRights(IProject project, String roleName,
        java.util.function.Function<String, Boolean> exists, boolean apply)
    {
        OrphanSweep sweep = new OrphanSweep();
        if (project == null || roleName == null || roleName.isEmpty() || exists == null)
        {
            sweep.error = "project, roleName and a resolver are required"; //$NON-NLS-1$
            return sweep;
        }
        if (roleName.indexOf('/') >= 0 || roleName.indexOf('\\') >= 0 || roleName.contains("..")) //$NON-NLS-1$
        {
            sweep.error = "roleName must be a simple role name (no path separators or '..')"; //$NON-NLS-1$
            return sweep;
        }
        if (project.getLocation() == null)
        {
            sweep.error = "project location is not on the local filesystem"; //$NON-NLS-1$
            return sweep;
        }
        Path file = project.getLocation().toFile().toPath()
            .resolve("src").resolve("Roles").resolve(roleName).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!Files.exists(file))
        {
            sweep.error = "the role has no Rights.rights: " + roleName; //$NON-NLS-1$
            return sweep;
        }
        // One writer at a time on one file, the same lock the right setters take: a sweep is a
        // read-modify-write and would otherwise lose whatever a concurrent setter had just added.
        ReentrantLock lock = fileLock(file.toString());
        lock.lock();
        try
        {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            Document doc = dbf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(Files.readAllBytes(file)));
            Element root = doc.getDocumentElement();
            java.util.List<Element> doomed = new java.util.ArrayList<>();
            NodeList kids = root.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++)
            {
                Node n = kids.item(i);
                if (!(n instanceof Element) || !"object".equals(((Element)n).getTagName())) //$NON-NLS-1$
                {
                    continue;
                }
                sweep.total++;
                Element nameEl = firstChild((Element)n, "name"); //$NON-NLS-1$
                String fqn = nameEl == null ? null : nameEl.getTextContent().trim();
                if (fqn == null || fqn.isEmpty())
                {
                    sweep.undecided.put("<object> with no name", //$NON-NLS-1$
                        "the block names no object, so there is nothing to resolve"); //$NON-NLS-1$
                    continue;
                }
                Boolean present = exists.apply(fqn);
                if (present == null)
                {
                    sweep.undecided.put(fqn, "could not be resolved either way"); //$NON-NLS-1$
                }
                else if (!present.booleanValue())
                {
                    sweep.orphaned.add(fqn);
                    doomed.add((Element)n);
                }
            }
            sweep.ok = true;
            if (!apply || doomed.isEmpty())
            {
                return sweep;
            }
            for (Element dead : doomed)
            {
                root.removeChild(dead);
            }
            String written = printRightsDom(doc);
            Files.write(file, written.getBytes(StandardCharsets.UTF_8));
            sweep.changed = true;
            return sweep;
        }
        catch (Exception e)
        {
            sweep.ok = false;
            sweep.error = "could not sweep " + file + ": " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            return sweep;
        }
        finally
        {
            lock.unlock();
        }
    }

    /** Finds a direct child element {@code <tag>} whose {@code <name>} text equals value. */
    private static Element findChildByName(Element parent, String tag, String name)
    {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++)
        {
            Node n = kids.item(i);
            if (n instanceof Element && tag.equals(((Element) n).getTagName()))
            {
                Element nameEl = firstChild((Element) n, "name"); //$NON-NLS-1$
                if (nameEl != null && name.equals(nameEl.getTextContent().trim()))
                {
                    return (Element) n;
                }
            }
        }
        return null;
    }

    /**
     * Finds a root {@code <restrictionTemplate>} whose {@code <name>} matches {@code name}
     * case-insensitively (trimmed) - 1C identifiers, including RLS template names
     * referenced as {@code #Name(...)}, are case-insensitive, so a re-cased name must
     * update the same template rather than append a duplicate.
     */
    private static Element findRestrictionTemplateCI(Element root, String name)
    {
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++)
        {
            Node n = kids.item(i);
            if (n instanceof Element && "restrictionTemplate".equals(((Element) n).getTagName())) //$NON-NLS-1$
            {
                Element nameEl = firstChild((Element) n, "name"); //$NON-NLS-1$
                if (nameEl != null && name.equalsIgnoreCase(nameEl.getTextContent().trim()))
                {
                    return (Element) n;
                }
            }
        }
        return null;
    }

    /** First direct child element with the given tag, or null. */
    private static Element firstChild(Element parent, String tag)
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

    private static void appendTextChild(Document doc, Element parent, String tag, String text)
    {
        Element el = doc.createElement(tag);
        el.setTextContent(text);
        parent.appendChild(el);
    }

    /**
     * Generic DOM -&gt; string printer reproducing the stock {@code .rights} format:
     * XML declaration, tab indent, CRLF, text-leaf elements on one line. Works for
     * arbitrary element content, so RLS / template blocks round-trip untouched.
     */
    private static String printRightsDom(Document doc)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"); //$NON-NLS-1$
        printElement(doc.getDocumentElement(), 0, sb);
        return sb.toString();
    }

    private static void printElement(Element el, int depth, StringBuilder sb)
    {
        for (int i = 0; i < depth; i++)
        {
            sb.append('\t');
        }
        sb.append('<').append(el.getTagName());
        org.w3c.dom.NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++)
        {
            Node a = attrs.item(i);
            sb.append(' ').append(a.getNodeName()).append("=\"") //$NON-NLS-1$
                .append(xmlEscape(a.getNodeValue())).append('"');
        }
        // Determine element children vs text content.
        boolean hasElementChild = false;
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++)
        {
            if (kids.item(i) instanceof Element)
            {
                hasElementChild = true;
                break;
            }
        }
        if (hasElementChild)
        {
            sb.append(">\r\n"); //$NON-NLS-1$
            for (int i = 0; i < kids.getLength(); i++)
            {
                if (kids.item(i) instanceof Element)
                {
                    printElement((Element) kids.item(i), depth + 1, sb);
                }
            }
            for (int i = 0; i < depth; i++)
            {
                sb.append('\t');
            }
            sb.append("</").append(el.getTagName()).append(">\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            String text = el.getTextContent();
            // M2: the rest of the file uses CRLF; give multiline text leaves (RLS
            // <condition> bodies) CRLF internally too, so a rewrite doesn't leave LF
            // inside / CRLF around - which shows up as spurious "changed" lines in the
            // git diff of the whole access-control file. DOM text is always LF here
            // (an XML parse normalizes CRLF->LF), so adding the CR never doubles it.
            String escaped = xmlEscape(text == null ? "" : text).replace("\n", "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append('>').append(escaped)
                .append("</").append(el.getTagName()).append(">\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String xmlEscape(String s)
    {
        if (s == null)
        {
            return ""; //$NON-NLS-1$
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            .replace("\"", "&quot;").replace("'", "&apos;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
