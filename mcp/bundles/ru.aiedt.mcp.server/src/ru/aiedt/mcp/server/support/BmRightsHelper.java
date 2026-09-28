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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

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
     * @param project the EDT project
     * @param roleName the simple role name
     * @param xml the document text
     * @return null on success, or a non-null error note (the BM mutation has
     *     already committed)
     */
    public static String writeRightsFile(IProject project, String roleName, String xml)
    {
        return writeRightsFile(project, roleName, xml, true);
    }

    /**
     * Writes {@code Rights.rights} by a temporary file and a move, then refreshes the workspace
     * folder so a later read through {@code IFile} sees the bytes just written.
     *
     * @param project the EDT project
     * @param roleName the simple role name
     * @param xml the document text
     * @param refuseEmptyReplacement when {@code true}, XML with no object block does not replace a
     *        file that still has one. A sweep that removed the last object on purpose passes
     *        {@code false}.
     * @return {@code null} on success, or the error text
     */
    public static String writeRightsFile(IProject project, String roleName, String xml,
        boolean refuseEmptyReplacement)
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
        // A sweep that deleted the last object opts out: that empty file is the result it asked for.
        if (refuseEmptyReplacement && !xml.contains("<object>")) //$NON-NLS-1$
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
        Path tmp = dir.resolve("Rights.rights.tmp"); //$NON-NLS-1$
        try
        {
            Files.createDirectories(dir);
            // Write to a temp file then move into place, so the workspace refresh
            // below (and any EDT re-read it triggers) never observes a truncated
            // half-written Rights.rights.
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
            try
            {
                Files.deleteIfExists(tmp);
            }
            catch (IOException ignored)
            {
                // The failure being returned is the write. A temp file that could not be
                // removed is left for the next write, which replaces it.
            }
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
        /** Wire tag such as {@code notFound}, or {@code null} when the call was not refused. */
        public String failureKind;
        /** What was missing: {@code role}, {@code object} or {@code right}. */
        public String subject;
        /** The role, object or right the refusal names. */
        public String subjectName;
        /** English object kind when a right does not apply, otherwise {@code null}. */
        public String objectKind;
        /** Canonical rights that do apply, when a right was refused for that reason. */
        public List<String> applicableRights;
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
     * A role the configuration does not contain, an object it does not contain, or a right that
     * does not apply to that object's kind is refused before any file is created. A name that is
     * not a single path segment (a separator, a dot, or {@code ..}) is refused the same way.
     * Object and right names already in the file are matched without regard to case, so a re-cased
     * call updates the existing block instead of appending a second one.
     *
     * @param project the EDT project
     * @param roleName the simple role name
     * @param targetFqn the metadata object, for example {@code Catalog.Goods}
     * @param canonicalRightName the platform right name
     * @param granted true -&gt; {@code <value>true</value>} (SET), false -&gt; {@code false} (denied)
     * @param dryRun {@code true} to leave the file untouched after a successful check
     * @return what was written, or the refusal
     */
    public static FileRightResult applyRightToFile(IProject project, String roleName,
        String targetFqn, String canonicalRightName, boolean granted, boolean dryRun)
    {
        return applyRightToFile(project, roleName, targetFqn, canonicalRightName, granted, dryRun, null);
    }

    /**
     * {@link #applyRightToFile(IProject, String, String, String, boolean, boolean)} with the
     * caller's own answers for whether the role, the object and the right exist.
     *
     * @param project the EDT project
     * @param roleName the simple role name
     * @param targetFqn the metadata object
     * @param canonicalRightName the platform right name
     * @param granted true to grant the right, false to deny it
     * @param dryRun {@code true} to leave the file untouched after a successful check
     * @param gate the answers, or {@code null} to read them from the project model
     * @return what was written, or the refusal
     */
    public static FileRightResult applyRightToFile(IProject project, String roleName,
        String targetFqn, String canonicalRightName, boolean granted, boolean dryRun, RightsGate gate)
    {
        FileRightResult res = new FileRightResult();
        if (project == null || roleName == null || roleName.isEmpty()
            || targetFqn == null || targetFqn.isEmpty()
            || canonicalRightName == null || canonicalRightName.isEmpty())
        {
            res.error = "project, roleName, targetFqn and rightName are required"; //$NON-NLS-1$
            return res;
        }
        // The directory is the role's name as the model stores it, not the caller's spelling.
        // A dot is not a separator on disk, but it is not a simple metadata name either.
        RightsGate effective = gateOf(project, gate);
        String roleDirectory = effective.roleDirectoryName(roleName);
        String nameError = simpleRoleNameError(roleDirectory);
        if (nameError != null)
        {
            res.error = nameError;
            return res;
        }
        if (project.getLocation() == null)
        {
            res.error = "project location is not on the local filesystem"; //$NON-NLS-1$
            return res;
        }
        Path file = project.getLocation().toFile().toPath()
            .resolve("src").resolve("Roles").resolve(roleDirectory).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Serialize the read-modify-write on this one Rights.rights so a concurrent
        // writer (or this op's own dependency cascade, N+1 writes) cannot interleave and
        // lose an edit. Lock before the try, release in finally.
        ReentrantLock lock = fileLock(file.toString());
        lock.lock();
        try
        {
            LoadedRights loaded = loadRights(file);
            if (loaded.parseError != null)
            {
                res.error = "Rights.rights could not be read: " + loaded.parseError; //$NON-NLS-1$
                return res;
            }
            Refusal refusal = assess(project, roleName, targetFqn, canonicalRightName, loaded.doc,
                effective);
            if (refusal != null)
            {
                applyRefusal(res, refusal);
                return res;
            }
            String objectToWrite = effective.objectFqnToWrite(targetFqn);
            String rightToWrite = effective.rightNameToWrite(targetFqn, canonicalRightName);
            Document doc = loaded.doc;
            if (doc == null)
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
            Element objectEl = findObjectBlock(root, objectToWrite, targetFqn);
            if (objectEl == null)
            {
                objectEl = doc.createElement("object"); //$NON-NLS-1$
                appendTextChild(doc, objectEl, "name", objectToWrite); //$NON-NLS-1$
                root.appendChild(objectEl);
                res.objectCreated = true;
            }
            Element rightEl = findRightBlock(objectEl, rightToWrite, canonicalRightName);
            if (rightEl == null)
            {
                rightEl = doc.createElement("right"); //$NON-NLS-1$
                appendTextChild(doc, rightEl, "name", rightToWrite); //$NON-NLS-1$
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
            String writeErr = writeRightsFile(project, roleDirectory, xml);
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
        /** Wire tag such as {@code notFound}, or {@code null} when the call was not refused. */
        public String failureKind;
        /** What was missing: {@code role}. */
        public String subject;
        /** The role the refusal names. */
        public String subjectName;
        /** Unused for a template; kept so every writer reports a refusal the same way. */
        public String objectKind;
        /** Unused for a template. */
        public List<String> applicableRights;
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
     * A role the configuration does not contain is refused before a file is created.
     *
     * @param project the EDT project
     * @param roleName the simple role name
     * @param templateName the template name
     * @param condition the RLS condition text (required for set, ignored for remove)
     * @param remove true -&gt; remove; false -&gt; add/update
     * @param dryRun {@code true} to leave the file untouched after a successful check
     * @return what was written, or the refusal
     */
    public static FileTemplateResult applyRestrictionTemplateToFile(IProject project,
        String roleName, String templateName, String condition, boolean remove, boolean dryRun)
    {
        return applyRestrictionTemplateToFile(project, roleName, templateName, condition, remove, dryRun,
            null);
    }

    /**
     * {@link #applyRestrictionTemplateToFile(IProject, String, String, String, boolean, boolean)}
     * with the caller's own answer for whether the role exists.
     *
     * @param project the EDT project
     * @param roleName the simple role name
     * @param templateName the template name
     * @param condition the RLS condition text
     * @param remove true to remove the template, false to add or update it
     * @param dryRun {@code true} to leave the file untouched after a successful check
     * @param gate the answers, or {@code null} to read them from the project model
     * @return what was written, or the refusal
     */
    public static FileTemplateResult applyRestrictionTemplateToFile(IProject project,
        String roleName, String templateName, String condition, boolean remove, boolean dryRun,
        RightsGate gate)
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
        RightsGate effective = gateOf(project, gate);
        String roleDirectory = effective.roleDirectoryName(roleName);
        String nameError = simpleRoleNameError(roleDirectory);
        if (nameError != null)
        {
            res.error = nameError;
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
            .resolve("src").resolve("Roles").resolve(roleDirectory).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Same per-file lock as applyRightToFile, so this writer serializes against
        // set_role_right / cascade and the other restriction writer on one Rights.rights.
        ReentrantLock lock = fileLock(file.toString());
        lock.lock();
        try
        {
            LoadedRights loaded = loadRights(file);
            if (loaded.parseError != null)
            {
                res.error = "Rights.rights could not be read: " + loaded.parseError; //$NON-NLS-1$
                return res;
            }
            // A template has no object and no right; only the role is checked.
            Refusal refusal = assess(project, roleName, null, null, loaded.doc, effective);
            if (refusal != null)
            {
                applyRefusal(res, refusal);
                return res;
            }
            if (loaded.doc == null && remove)
            {
                res.idempotent = true; // nothing to remove
                res.ok = true;
                return res;
            }
            Document doc = loaded.doc;
            if (doc == null)
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
            String writeErr = writeRightsFile(project, roleDirectory, xml);
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
        /** Wire tag such as {@code notFound}, or {@code null} when the call was not refused. */
        public String failureKind;
        /** What was missing: {@code role}, {@code object} or {@code right}. */
        public String subject;
        /** The role, object or right the refusal names. */
        public String subjectName;
        /** English object kind when a right does not apply, otherwise {@code null}. */
        public String objectKind;
        /** Canonical rights that do apply, when a right was refused for that reason. */
        public List<String> applicableRights;
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
     * A role, object or right the configuration does not contain is refused before a file is created.
     *
     * @param project the EDT project
     * @param roleName the simple role name
     * @param targetFqn the metadata object
     * @param rightName canonical right name (Read / Insert / Update / Delete / ...)
     * @param condition RLS condition text (required for set, ignored for remove); LF-normalized
     * @param remove true -&gt; remove the restriction; false -&gt; add/update
     * @param dryRun {@code true} to leave the file untouched after a successful check
     * @return what was written, or the refusal
     */
    public static FileRestrictionResult applyRoleRestrictionToFile(IProject project,
        String roleName, String targetFqn, String rightName, String condition, boolean remove,
        boolean dryRun)
    {
        return applyRoleRestrictionToFile(project, roleName, targetFqn, rightName, condition, remove,
            dryRun, null);
    }

    /**
     * {@link #applyRoleRestrictionToFile(IProject, String, String, String, String, boolean, boolean)}
     * with the caller's own answers for the role, the object and the right.
     *
     * @param project the EDT project
     * @param roleName the simple role name
     * @param targetFqn the metadata object
     * @param rightName the canonical right name
     * @param condition the RLS condition text
     * @param remove true to remove the restriction, false to add or update it
     * @param dryRun {@code true} to leave the file untouched after a successful check
     * @param gate the answers, or {@code null} to read them from the project model
     * @return what was written, or the refusal
     */
    public static FileRestrictionResult applyRoleRestrictionToFile(IProject project,
        String roleName, String targetFqn, String rightName, String condition, boolean remove,
        boolean dryRun, RightsGate gate)
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
        RightsGate effective = gateOf(project, gate);
        String roleDirectory = effective.roleDirectoryName(roleName);
        String nameError = simpleRoleNameError(roleDirectory);
        if (nameError != null)
        {
            res.error = nameError;
            return res;
        }
        if (project.getLocation() == null)
        {
            res.error = "project location is not on the local filesystem"; //$NON-NLS-1$
            return res;
        }
        Path file = project.getLocation().toFile().toPath()
            .resolve("src").resolve("Roles").resolve(roleDirectory).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Same per-file lock as applyRightToFile, so this writer serializes against
        // set_role_right / cascade and the other restriction writer on one Rights.rights.
        ReentrantLock lock = fileLock(file.toString());
        lock.lock();
        try
        {
            LoadedRights loaded = loadRights(file);
            if (loaded.parseError != null)
            {
                res.error = "Rights.rights could not be read: " + loaded.parseError; //$NON-NLS-1$
                return res;
            }
            Refusal refusal = assess(project, roleName, targetFqn, rightName, loaded.doc, effective);
            if (refusal != null)
            {
                applyRefusal(res, refusal);
                return res;
            }
            if (loaded.doc == null && remove)
            {
                res.idempotent = true; // nothing to remove
                res.ok = true;
                return res;
            }
            String objectToWrite = effective.objectFqnToWrite(targetFqn);
            String rightToWrite = effective.rightNameToWrite(targetFqn, rightName);
            Document doc = loaded.doc;
            if (doc == null)
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
            Element objectEl = findObjectBlock(root, objectToWrite, targetFqn);
            if (remove && objectEl == null)
            {
                res.idempotent = true;
                res.ok = true;
                return res;
            }
            if (objectEl == null)
            {
                objectEl = doc.createElement("object"); //$NON-NLS-1$
                appendTextChild(doc, objectEl, "name", objectToWrite); //$NON-NLS-1$
                root.appendChild(objectEl);
                res.objectCreated = true;
            }
            Element rightEl = findRightBlock(objectEl, rightToWrite, rightName);
            if (remove && rightEl == null)
            {
                res.idempotent = true;
                res.ok = true;
                return res;
            }
            if (rightEl == null)
            {
                rightEl = doc.createElement("right"); //$NON-NLS-1$
                appendTextChild(doc, rightEl, "name", rightToWrite); //$NON-NLS-1$
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
            String writeErr = writeRightsFile(project, roleDirectory, xml);
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

    /**
     * Builds an empty Rights document with the stock root and the three default flags.
     *
     * @return the document
     * @throws Exception when the JDK parser rejects the secure settings
     */
    private static Document newRightsDocument() throws Exception
    {
        Document doc = newRightsBuilder().newDocument();
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
        String nameError = simpleRoleNameError(roleName);
        if (nameError != null)
        {
            sweep.error = nameError;
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
            Document doc;
            try
            {
                doc = parseRights(Files.readAllBytes(file));
            }
            catch (Exception refused)
            {
                // The parser's own message names the DOCTYPE or the malformed markup. The path is
                // not part of that answer: it is a local filesystem address.
                sweep.ok = false;
                String detail = refused.getMessage() != null
                    ? refused.getMessage() : refused.getClass().getSimpleName();
                sweep.error = "Rights.rights could not be read: " + detail; //$NON-NLS-1$
                return sweep;
            }
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
            // The same temporary-file move and workspace refresh the other writer uses, so the
            // workspace does not keep the bytes from before the sweep and no .tmp file is left.
            // The empty-replacement guard is off: removing the last object is the point of a sweep.
            String writeErr = writeRightsFile(project, roleName, written, false);
            if (writeErr != null)
            {
                sweep.ok = false;
                sweep.error = writeErr;
                return sweep;
            }
            sweep.changed = true;
            return sweep;
        }
        catch (Exception e)
        {
            sweep.ok = false;
            // The path of the rights file is a local filesystem address and is not part of the
            // answer, the same rule as the parse failure above.
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sweep.error = "could not sweep the role: " + detail; //$NON-NLS-1$
            return sweep;
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * The gate to ask, reading the project when the caller did not supply one.
     *
     * @param project the project, used when {@code gate} is {@code null}
     * @param gate the caller's answers, or {@code null}
     * @return the gate that answers this write
     */
    private static RightsGate gateOf(IProject project, RightsGate gate)
    {
        return gate != null ? gate : RightsGate.forProject(project);
    }

    /**
     * The object block to update. The canonical address is tried first; a block stored under the
     * caller's spelling is the same block when the two differ only by case.
     *
     * @param root the {@code Rights} element
     * @param canonical the address to store
     * @param caller the address the caller passed
     * @return the block, or {@code null} when neither name is there
     */
    private static Element findObjectBlock(Element root, String canonical, String caller)
    {
        Element found = findChildByName(root, "object", canonical); //$NON-NLS-1$
        if (found == null && caller != null && (canonical == null || !caller.equalsIgnoreCase(canonical)))
        {
            found = findChildByName(root, "object", caller); //$NON-NLS-1$
        }
        return found;
    }

    /**
     * The right block to update, matched on the spelling that will be stored and on the name the
     * caller passed.
     *
     * @param objectEl the object element
     * @param canonical the right name to store
     * @param caller the right name the caller passed
     * @return the block, or {@code null} when neither name is there
     */
    private static Element findRightBlock(Element objectEl, String canonical, String caller)
    {
        Element found = findChildByName(objectEl, "right", canonical); //$NON-NLS-1$
        if (found == null && caller != null && (canonical == null || !caller.equalsIgnoreCase(canonical)))
        {
            found = findChildByName(objectEl, "right", caller); //$NON-NLS-1$
        }
        return found;
    }

    /**
     * Finds a direct child {@code tag} whose {@code name} text matches {@code name} without regard
     * to case. 1C object and right names do not distinguish case, so a re-cased call must update
     * the block that is already there.
     *
     * @param parent the element to search
     * @param tag the child tag ({@code object} or {@code right})
     * @param name the name to match
     * @return the child, or {@code null}
     */
    private static Element findChildByName(Element parent, String tag, String name)
    {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++)
        {
            Node n = kids.item(i);
            if (n instanceof Element && tag.equals(((Element) n).getTagName()))
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

    /**
     * Why a role name may not become a directory under {@code src/Roles}.
     *
     * @param roleName the candidate name
     * @return the refusal text, or {@code null} when the name is a single segment
     */
    private static String simpleRoleNameError(String roleName)
    {
        // A dot is a legal file-system character and would create "Role.Full" as a folder.
        // Metadata names do not contain one, so it is refused with the path forms.
        if (roleName.indexOf('/') >= 0 || roleName.indexOf('\\') >= 0
            || roleName.indexOf('.') >= 0 || roleName.contains("..")) //$NON-NLS-1$
        {
            return "roleName must be a simple role name (no path separators, dots or '..')"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Answers used to decide whether a rights file may be created or changed.
     * <p>
     * {@link #roleExists} returns {@code null} when the configuration cannot be read, and that
     * refuses the write as {@code configuration not readable} rather than as a missing role.
     * {@link #objectExists} returns {@code null} when a child cannot be resolved, and that does
     * not refuse the write; a top-level object that cannot be resolved is still refused.
     * {@link #applicableRights} returns {@code null} when the registry is not ready, and that
     * does not refuse the right.
     * </p>
     */
    public interface RightsGate
    {
        /**
         * Whether the role is in the configuration.
         *
         * @param roleName the simple role name
         * @return {@code TRUE} when it is there, {@code FALSE} when it is not, {@code null} when
         *         the model cannot tell
         */
        Boolean roleExists(String roleName);

        /**
         * Whether the metadata object is in the configuration.
         *
         * @param targetFqn the object address
         * @return {@code TRUE}, {@code FALSE}, or {@code null} when the model cannot tell
         */
        Boolean objectExists(String targetFqn);

        /**
         * The canonical right names that apply to the object.
         *
         * @param targetFqn the object address
         * @return the names, or {@code null} when applicability cannot be decided
         */
        Set<String> applicableRights(String targetFqn);

        /**
         * A gate that allows the role and the object and does not judge the right.
         *
         * @return the gate
         */
        static RightsGate allowAll()
        {
            return ALLOW_ALL;
        }

        /**
         * A gate that reads the project's configuration.
         *
         * @param project the project
         * @return the gate
         */
        static RightsGate forProject(IProject project)
        {
            return new ProjectGate(project);
        }

        /**
         * A gate over a configuration already in memory. A {@code null} configuration is not
         * readable.
         *
         * @param configuration the model, or {@code null} when it could not be read
         * @return the gate
         */
        static RightsGate forConfiguration(Configuration configuration)
        {
            return new ProjectGate(null, true, configuration);
        }

        /**
         * The directory name under {@code src/Roles} for {@code roleName}. A gate that has read
         * the role returns the name stored on it. The default is the name the caller passed.
         *
         * @param roleName the simple role name
         * @return the directory name to create or update
         */
        default String roleDirectoryName(String roleName)
        {
            return roleName;
        }

        /**
         * The object address to store in the rights file. A gate that has located the object
         * returns the English type and the names stored on the model. The default is the address
         * the caller passed.
         *
         * @param targetFqn the object address
         * @return the address to write
         */
        default String objectFqnToWrite(String targetFqn)
        {
            return targetFqn;
        }

        /**
         * The right name to store. The spelling in {@link #applicableRights} wins when that set
         * contains the right; otherwise {@code rightName} is stored as given.
         *
         * @param targetFqn the object address the right is stored on
         * @param rightName the right name, already mapped from an alias when the caller did that
         * @return the name to write
         */
        default String rightNameToWrite(String targetFqn, String rightName)
        {
            return rightSpelling(applicableRights(targetFqn), rightName);
        }
    }

    /** Allows every role and object and leaves the right unjudged. */
    private static final class AllowAllGate implements RightsGate
    {
        /**
         * @param roleName the simple role name
         * @return {@code TRUE}
         */
        @Override
        public Boolean roleExists(String roleName)
        {
            return Boolean.TRUE;
        }

        /**
         * @param targetFqn the object address
         * @return {@code TRUE}
         */
        @Override
        public Boolean objectExists(String targetFqn)
        {
            return Boolean.TRUE;
        }

        /**
         * @param targetFqn the object address
         * @return {@code null}, so the right is not judged
         */
        @Override
        public Set<String> applicableRights(String targetFqn)
        {
            return null;
        }
    }

    private static final RightsGate ALLOW_ALL = new AllowAllGate();

    /**
     * Reads the configuration of one project. A project that is not a configuration answers
     * {@code null}, which the callers treat as "the role is not there".
     */
    private static final class ProjectGate implements RightsGate
    {
        private final IProject project;

        private Configuration configuration;

        private boolean loaded;

        /**
         * @param project the project whose configuration is read
         */
        ProjectGate(IProject project)
        {
            this.project = project;
        }

        /**
         * @param project the project, unused when {@code loaded} is already true
         * @param loaded whether {@code configuration} is the answer and must not be read again
         * @param configuration the model, or {@code null} when the caller already knows it is not
         *        readable
         */
        private ProjectGate(IProject project, boolean loaded, Configuration configuration)
        {
            this.project = project;
            this.loaded = loaded;
            this.configuration = configuration;
        }

        /**
         * @param roleName the simple role name
         * @return whether a role of that name is in the configuration, or {@code null} when the
         *         configuration cannot be read
         */
        @Override
        public Boolean roleExists(String roleName)
        {
            Configuration model = configuration();
            if (model == null)
            {
                return null;
            }
            return MetadataTypeCatalog.findObject(model, "Role", roleName) != null //$NON-NLS-1$
                ? Boolean.TRUE : Boolean.FALSE;
        }

        /**
         * The name stored on the role, so the rights file is created under that directory.
         *
         * @param roleName the simple role name the caller used
         * @return the model's name, or {@code roleName} when the role was not read
         */
        @Override
        public String roleDirectoryName(String roleName)
        {
            Configuration model = configuration();
            if (model == null || roleName == null)
            {
                return roleName;
            }
            MdObject role = MetadataTypeCatalog.findObject(model, "Role", roleName); //$NON-NLS-1$
            if (role == null || role.getName() == null || role.getName().isEmpty())
            {
                return roleName;
            }
            return role.getName();
        }

        /**
         * @param targetFqn the object address
         * @return whether that object is in the configuration, or {@code null} when it cannot be
         *         decided
         */
        @Override
        public Boolean objectExists(String targetFqn)
        {
            Configuration model = configuration();
            if (model == null)
            {
                return null;
            }
            return locateObject(model, targetFqn).present;
        }

        /**
         * The address {@link #locateObject} built from the model, when it found the object.
         *
         * @param targetFqn the object address the caller used
         * @return the canonical address, or {@code targetFqn} when the object was not located
         */
        @Override
        public String objectFqnToWrite(String targetFqn)
        {
            Configuration model = configuration();
            if (model == null || targetFqn == null)
            {
                return targetFqn;
            }
            LocatedObject located = locateObject(model, targetFqn);
            return located.canonicalFqn != null ? located.canonicalFqn : targetFqn;
        }

        /**
         * @param targetFqn the object address
         * @return the rights that apply, or {@code null} when they cannot be decided
         */
        @Override
        public Set<String> applicableRights(String targetFqn)
        {
            Configuration model = configuration();
            EObject target = model == null ? null : locateObject(model, targetFqn).object;
            if (target != null)
            {
                Set<String> fromService = ApplicableRightsResolver.rightsFor(target);
                if (fromService != null)
                {
                    return fromService;
                }
            }
            // A child (Catalog.Goods.Attribute.Price) does not inherit the parent's right list.
            // Without the platform registry the answer is "not ready", not a guess.
            if (targetFqn != null && dotCount(targetFqn) > 1)
            {
                return null;
            }
            return ApplicableRightsResolver.knownRights(ApplicableRightsResolver.kindOf(targetFqn));
        }

        /**
         * The configuration, read once.
         *
         * @return the model, or {@code null} when this project has none
         */
        private Configuration configuration()
        {
            if (!loaded)
            {
                loaded = true;
                configuration = readConfiguration(project);
            }
            return configuration;
        }
    }

    /**
     * Where one FQN landed in a configuration, and the address to store when it was found.
     * <p>
     * The same walk answers a rights write and an orphan sweep. A child it cannot resolve is
     * {@link #unresolved()}, which neither refuses the write nor deletes the rights entry.
     * </p>
     */
    public static final class LocatedObject
    {
        /** {@code TRUE}, {@code FALSE}, or {@code null} when the walk could not decide. */
        public final Boolean present;

        /** The object, when {@link #present} is {@code TRUE}. */
        public final EObject object;

        /**
         * English type plus the names stored on the model ({@code Catalog.Товары}), or
         * {@code null} when the object was not found.
         */
        public final String canonicalFqn;

        private LocatedObject(Boolean present, EObject object, String canonicalFqn)
        {
            this.present = present;
            this.object = object;
            this.canonicalFqn = canonicalFqn;
        }

        /**
         * @param object the object that was found
         * @param canonicalFqn the address to store
         * @return a positive location
         */
        static LocatedObject found(EObject object, String canonicalFqn)
        {
            return new LocatedObject(Boolean.TRUE, object, canonicalFqn);
        }

        /**
         * @return a location that is sure the object is absent
         */
        static LocatedObject absent()
        {
            return new LocatedObject(Boolean.FALSE, null, null);
        }

        /**
         * @return a location that could not decide
         */
        static LocatedObject unresolved()
        {
            return new LocatedObject(null, null, null);
        }
    }

    /**
     * Locates one FQN in a configuration.
     * <p>
     * The top segment is the English metadata type. Each later pair is a child collection and a
     * member. The collection is the structural feature of that name, matched without regard to
     * case, and the member is the object whose {@code getName()} matches. A feature that cannot
     * be read, or a member whose name cannot be read, is not treated as absent.
     * </p>
     *
     * @param configuration the configuration; {@code null} cannot be decided
     * @param fqn the object address
     * @return where the walk landed
     */
    public static LocatedObject locateObject(Configuration configuration, String fqn)
    {
        if (configuration == null)
        {
            return LocatedObject.unresolved();
        }
        if (fqn == null || fqn.isEmpty())
        {
            return LocatedObject.absent();
        }
        if (fqn.equals(configuration.getName()) || "Configuration".equals(fqn) //$NON-NLS-1$
            || fqn.startsWith("Configuration.")) //$NON-NLS-1$
        {
            return LocatedObject.found(configuration, "Configuration"); //$NON-NLS-1$
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return LocatedObject.unresolved();
        }
        String type = MetadataTypeCatalog.toEnglishSingular(parts[0]);
        if (type == null)
        {
            type = parts[0];
        }
        if (MetadataTypeCatalog.resolve(type) == null)
        {
            return LocatedObject.unresolved();
        }
        MdObject owner = MetadataTypeCatalog.findObject(configuration, type, parts[1]);
        if (owner == null)
        {
            return LocatedObject.absent();
        }
        String canonical = type + "." + owner.getName(); //$NON-NLS-1$
        if (parts.length == 2)
        {
            return LocatedObject.found(owner, canonical);
        }
        return walkChildren(owner, parts, 2, canonical);
    }

    /**
     * Walks a child FQN one collection/member pair at a time.
     *
     * @param owner the object reached so far
     * @param parts the whole address, split on dots
     * @param at the index of the next collection name
     * @param canonicalSoFar the address of {@code owner}, already in canonical form
     * @return the location
     */
    private static LocatedObject walkChildren(EObject owner, String[] parts, int at,
        String canonicalSoFar)
    {
        if (at >= parts.length)
        {
            return LocatedObject.found(owner, canonicalSoFar);
        }
        if (at + 1 >= parts.length)
        {
            return LocatedObject.unresolved();
        }
        String kind = canonicalCollectionKind(parts[at]);
        Object children = readCollection(owner, kind);
        if (!(children instanceof Iterable))
        {
            return LocatedObject.unresolved();
        }
        String member = parts[at + 1];
        boolean sawName = false;
        boolean anyChild = false;
        for (Object child : (Iterable<?>)children)
        {
            if (!(child instanceof EObject))
            {
                continue;
            }
            anyChild = true;
            String name = objectName((EObject)child);
            if (name == null)
            {
                continue;
            }
            sawName = true;
            if (member.equalsIgnoreCase(name))
            {
                String next = canonicalSoFar + "." + kind + "." + name; //$NON-NLS-1$ //$NON-NLS-2$
                return walkChildren((EObject)child, parts, at + 2, next);
            }
        }
        // Children are there but none of them answered getName(). That is not "the member is
        // missing": the walk could not read the collection.
        if (anyChild && !sawName)
        {
            return LocatedObject.unresolved();
        }
        return LocatedObject.absent();
    }

    /**
     * The English collection kind an address segment names.
     *
     * @param segment the collection segment of an FQN
     * @return the English kind, or {@code segment} when it is not a known child kind
     */
    private static String canonicalCollectionKind(String segment)
    {
        String canonical = BmObjectHelper.canonicalChildKind(segment);
        if (canonical != null)
        {
            return canonical;
        }
        String english = MetadataTypeCatalog.toEnglishSingular(segment);
        return english != null ? english : segment;
    }

    /**
     * Reads one named collection off a metadata object.
     * <p>
     * The collection is a many-valued structural feature whose name matches {@code kind} without
     * regard to case, including the plural the model uses ({@code urlTemplates} for
     * {@code URLTemplate}). A getter built by concatenating the kind would look for
     * {@code getURLTemplates} and miss it.
     * </p>
     *
     * @param owner the object
     * @param kind the collection's English singular name as it appears in an FQN
     * @return the collection, or {@code null} when this object has no such feature or the feature
     *         could not be read
     */
    private static Object readCollection(EObject owner, String kind)
    {
        if (owner == null || kind == null)
        {
            return null;
        }
        org.eclipse.emf.ecore.EStructuralFeature matched = null;
        for (org.eclipse.emf.ecore.EStructuralFeature feature : owner.eClass().getEAllStructuralFeatures())
        {
            if (!feature.isMany() || !namesTheCollection(feature.getName(), kind))
            {
                continue;
            }
            if (feature instanceof org.eclipse.emf.ecore.EReference
                && ((org.eclipse.emf.ecore.EReference)feature).isContainment())
            {
                matched = feature;
                break;
            }
            if (matched == null)
            {
                matched = feature;
            }
        }
        if (matched == null)
        {
            return null;
        }
        try
        {
            return owner.eGet(matched);
        }
        catch (RuntimeException failed)
        {
            // The feature is there but did not answer. The walk stays undecided rather than
            // treating a reflective failure as "the child does not exist".
            return null;
        }
    }

    /**
     * Whether a structural feature is the collection {@code kind} names.
     *
     * @param featureName the feature name, as the model spells it ({@code urlTemplates})
     * @param kind the English singular kind ({@code URLTemplate})
     * @return {@code true} when the names are the same collection
     */
    private static boolean namesTheCollection(String featureName, String kind)
    {
        if (featureName == null || kind == null)
        {
            return false;
        }
        return featureName.equalsIgnoreCase(kind)
            || featureName.equalsIgnoreCase(kind + "s") //$NON-NLS-1$
            || featureName.equalsIgnoreCase(kind + "es"); //$NON-NLS-1$
    }

    /**
     * The name of a child, including one that is not an {@link MdObject}.
     *
     * @param object the child
     * @return the name, or {@code null} when the child has no readable {@code getName()}
     */
    private static String objectName(EObject object)
    {
        try
        {
            Object name = object.getClass().getMethod("getName").invoke(object); //$NON-NLS-1$
            if (name == null)
            {
                return null;
            }
            String text = name.toString();
            return text.isEmpty() ? null : text;
        }
        catch (ReflectiveOperationException | RuntimeException failed)
        {
            return null;
        }
    }

    /**
     * The spelling of {@code rightName} in {@code applicable}, when the set contains it.
     *
     * @param applicable the rights that apply, or {@code null} when they were not decided
     * @param rightName the name the caller used
     * @return the set's spelling, or {@code rightName} when the set does not contain it
     */
    private static String rightSpelling(Set<String> applicable, String rightName)
    {
        if (applicable == null || rightName == null)
        {
            return rightName;
        }
        for (String name : applicable)
        {
            if (name != null && rightName.equalsIgnoreCase(name))
            {
                return name;
            }
        }
        return rightName;
    }

    /**
     * Whether {@code fqn} names a child rather than a top-level object.
     *
     * @param fqn the address
     * @return {@code true} when the address has more than one dot
     */
    private static boolean isChildAddress(String fqn)
    {
        return fqn != null && dotCount(fqn) > 1;
    }

    /**
     * How many dots an address contains. One dot is a top-level object; more than one is a child.
     *
     * @param fqn the address
     * @return the count
     */
    private static int dotCount(String fqn)
    {
        int dots = 0;
        for (int i = 0; i < fqn.length(); i++)
        {
            if (fqn.charAt(i) == '.')
            {
                dots++;
            }
        }
        return dots;
    }

    /**
     * The project's configuration, or {@code null} when it has none.
     *
     * @param project the project
     * @return the configuration
     */
    private static Configuration readConfiguration(IProject project)
    {
        try
        {
            Activator activator = Activator.getDefault();
            if (activator == null)
            {
                return null;
            }
            IConfigurationProvider provider = activator.getConfigurationProvider();
            if (provider == null)
            {
                return null;
            }
            return provider.getConfiguration(project);
        }
        catch (Throwable unavailable)
        {
            // A plain workspace project is not a configuration. Absence is the answer, not an error
            // the caller should retry.
            return null;
        }
    }

    /** Why a write was refused before the file changed. */
    private static final class Refusal
    {
        final String error;

        final String failureKind;

        final String subject;

        final String subjectName;

        final String objectKind;

        final List<String> applicableRights;

        private Refusal(String error, String failureKind, String subject, String subjectName,
            String objectKind, List<String> applicableRights)
        {
            this.error = error;
            this.failureKind = failureKind;
            this.subject = subject;
            this.subjectName = subjectName;
            this.objectKind = objectKind;
            this.applicableRights = applicableRights;
        }

        /**
         * @param subject {@code role} or {@code object}
         * @param name the missing name
         * @param error the sentence returned to the caller
         * @return the refusal
         */
        static Refusal notFound(String subject, String name, String error)
        {
            return new Refusal(error, ErrorTags.NOT_FOUND.wire(), subject, name, null, null);
        }

        /**
         * A refusal that carries no wire tag.
         *
         * @param error the sentence returned to the caller
         * @return the refusal
         */
        static Refusal plain(String error)
        {
            return new Refusal(error, null, null, null, null, null);
        }

        /**
         * @param right the canonical right
         * @param kind the object kind
         * @param applicable the rights that do apply
         * @param error the sentence returned to the caller
         * @return the refusal
         */
        static Refusal right(String right, String kind, List<String> applicable, String error)
        {
            return new Refusal(error, ErrorTags.NOT_APPLICABLE_HERE.wire(), "right", right, kind, //$NON-NLS-1$
                applicable);
        }
    }

    /** A rights file that was read, or the reason it was not. */
    private static final class LoadedRights
    {
        /** The document, or {@code null} when the file does not exist yet. */
        Document doc;

        /** Why the bytes were refused, or {@code null} when they were read or the file is absent. */
        String parseError;
    }

    /**
     * Reads {@code Rights.rights} when it exists. A missing file is an empty load, not an error.
     *
     * @param file the rights file
     * @return the load
     */
    private static LoadedRights loadRights(Path file)
    {
        LoadedRights loaded = new LoadedRights();
        if (!Files.exists(file))
        {
            return loaded;
        }
        try
        {
            loaded.doc = parseRights(Files.readAllBytes(file));
        }
        catch (Exception refused)
        {
            loaded.parseError = refused.getMessage() != null
                ? refused.getMessage() : refused.getClass().getSimpleName();
        }
        return loaded;
    }

    /**
     * Parses rights XML with external entities and DOCTYPE turned off.
     *
     * @param xml the file bytes
     * @return the document
     * @throws Exception when the bytes are not well-formed or declare a DOCTYPE
     */
    private static Document parseRights(byte[] xml) throws Exception
    {
        return newRightsBuilder().parse(new ByteArrayInputStream(xml));
    }

    /**
     * A document builder that will not follow a DOCTYPE or an external entity. A rights file does
     * not carry a DOCTYPE, so a document that declares one is a parse error rather than a read of
     * whatever the entity points at.
     *
     * @return a fresh builder
     * @throws ParserConfigurationException when the JDK parser rejects one of the settings
     */
    private static DocumentBuilder newRightsBuilder() throws ParserConfigurationException
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false); // keep xmlns* as literal attributes for round-trip
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

    /**
     * Refuses the write when the configuration cannot be read, or the role, the object or the right
     * is not one it has. A child the locator could not resolve is not refused. A right already
     * stored on this kind in {@code doc} is a precedent and is not refused.
     *
     * @param project the project, used when {@code gate} is {@code null}
     * @param roleName the simple role name
     * @param targetFqn the object address, or {@code null} for a template (role only)
     * @param canonicalRight the platform right name, or {@code null} when there is no right
     * @param doc the file already read, or {@code null} when there is no file yet
     * @param gate the caller's answers, or {@code null} to read the project model
     * @return the refusal, or {@code null} when the write may proceed
     */
    private static Refusal assess(IProject project, String roleName, String targetFqn,
        String canonicalRight, Document doc, RightsGate gate)
    {
        RightsGate effective = gate != null ? gate : RightsGate.forProject(project);
        Boolean role = effective.roleExists(roleName);
        if (role == null)
        {
            return Refusal.plain("configuration not readable; Rights.rights was not written"); //$NON-NLS-1$
        }
        if (!role.booleanValue())
        {
            return Refusal.notFound("role", roleName, //$NON-NLS-1$
                "role not found: " + roleName + "; Rights.rights was not written"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (targetFqn == null)
        {
            return null;
        }
        Boolean object = effective.objectExists(targetFqn);
        if (object == null)
        {
            // A child the locator could not resolve is not judged. A top-level object that could
            // not be resolved is still a refusal: there is no collection left unread behind it.
            if (!isChildAddress(targetFqn))
            {
                return Refusal.plain("could not resolve " + targetFqn //$NON-NLS-1$
                    + " in the configuration; Rights.rights was not written"); //$NON-NLS-1$
            }
        }
        else if (!object.booleanValue())
        {
            return Refusal.notFound("object", targetFqn, //$NON-NLS-1$
                "object not found: " + targetFqn + "; Rights.rights was not written"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (canonicalRight == null)
        {
            return null;
        }
        String kind = ApplicableRightsResolver.kindOf(targetFqn);
        boolean precedent = doc != null
            && ApplicableRightsResolver.seen(doc.getDocumentElement(), kind, canonicalRight);
        ApplicableRightsResolver.Decision decision = ApplicableRightsResolver.decide(kind,
            canonicalRight, effective.applicableRights(targetFqn), precedent);
        if (!decision.allowed)
        {
            return Refusal.right(canonicalRight, kind, decision.applicableRights, decision.error);
        }
        return null;
    }

    /**
     * Copies a refusal onto a right-write result.
     *
     * @param res the result
     * @param refusal why the write stopped
     */
    private static void applyRefusal(FileRightResult res, Refusal refusal)
    {
        res.error = refusal.error;
        res.failureKind = refusal.failureKind;
        res.subject = refusal.subject;
        res.subjectName = refusal.subjectName;
        res.objectKind = refusal.objectKind;
        res.applicableRights = refusal.applicableRights;
    }

    /**
     * Copies a refusal onto a template-write result.
     *
     * @param res the result
     * @param refusal why the write stopped
     */
    private static void applyRefusal(FileTemplateResult res, Refusal refusal)
    {
        res.error = refusal.error;
        res.failureKind = refusal.failureKind;
        res.subject = refusal.subject;
        res.subjectName = refusal.subjectName;
        res.objectKind = refusal.objectKind;
        res.applicableRights = refusal.applicableRights;
    }

    /**
     * Copies a refusal onto a restriction-write result.
     *
     * @param res the result
     * @param refusal why the write stopped
     */
    private static void applyRefusal(FileRestrictionResult res, Refusal refusal)
    {
        res.error = refusal.error;
        res.failureKind = refusal.failureKind;
        res.subject = refusal.subject;
        res.subjectName = refusal.subjectName;
        res.objectKind = refusal.objectKind;
        res.applicableRights = refusal.applicableRights;
    }
}
