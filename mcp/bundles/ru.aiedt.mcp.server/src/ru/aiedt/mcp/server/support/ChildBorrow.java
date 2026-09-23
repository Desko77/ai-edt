/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Borrows the children of an object that was just borrowed, and says what that walk did.
 * <p>
 * The walk is the containment collections {@link BmObjectHelper} already knows, depth first,
 * including an attribute of a tabular section and a subsystem inside a subsystem. A reference
 * held by an attribute is not a step: the object it names is another top object, and EDT may
 * pull that object's shell in on its own when the attribute is adopted. Those shells are listed
 * apart from the children, so a side effect is not reported as a child the caller asked for.
 * </p>
 * <p>
 * Modules are not overridden here, and predefined items are not in the collection list, so
 * neither is visited. A standard attribute has no adopter; it is skipped and the call is not
 * failed for it.
 * </p>
 */
public final class ChildBorrow
{
    /** Kind segment of a standard attribute, the one collection that cannot be adopted. */
    public static final String STANDARD_ATTRIBUTE = "StandardAttribute"; //$NON-NLS-1$

    /** Why a standard attribute is listed under skipped rather than as a failed borrow. */
    public static final String NOT_ADOPTABLE = "notAdoptable"; //$NON-NLS-1$

    private static final Set<String> NOT_PROMOTED = Set.of("targetFqn", "alreadyBorrowed", //$NON-NLS-1$ //$NON-NLS-2$
        "objectFqn", "operation", "message", "success", "includeChildren", "children", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "skipped", "pulledByType", "pulledByTypeUnverified"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private ChildBorrow()
    {
    }

    /**
     * The children of one owner, as the walk asks for them.
     * <p>
     * The production walk reads the model. A test hands a tree of its own, so the plan can be
     * asserted without a configuration project.
     * </p>
     */
    @FunctionalInterface
    public interface ChildCollections
    {
        /**
         * The children of {@code kind} on {@code owner}.
         *
         * @param owner the object being walked
         * @param kind the canonical English kind
         * @return the children, or <code>null</code> when the owner has no such collection
         */
        List<?> children(Object owner, String kind);
    }

    /**
     * One child borrow. The same call the root was borrowed with, and nothing else.
     */
    @FunctionalInterface
    public interface Attempt
    {
        /**
         * Borrows one child.
         *
         * @param fqn the child's address
         * @return what the borrow did
         */
        Attempted borrow(String fqn);
    }

    /**
     * The three ways one child borrow ends.
     */
    public static final class Attempted
    {
        /** The child is in the extension because this call placed it. */
        public final boolean placed;

        /** The child was already in the extension. */
        public final boolean alreadyBorrowed;

        /** Why it was not placed, when {@link #placed} is false. */
        public final String error;

        private Attempted(boolean placed, boolean alreadyBorrowed, String error)
        {
            this.placed = placed;
            this.alreadyBorrowed = alreadyBorrowed;
            this.error = error;
        }

        /**
         * A child this call placed.
         *
         * @return that outcome
         */
        public static Attempted placed()
        {
            return new Attempted(true, false, null);
        }

        /**
         * A child that was already there.
         *
         * @return that outcome
         */
        public static Attempted alreadyThere()
        {
            return new Attempted(true, true, null);
        }

        /**
         * A child the borrow refused.
         *
         * @param error the refusal, as the borrow reported it
         * @return that outcome
         */
        public static Attempted failed(String error)
        {
            return new Attempted(false, false, error);
        }
    }

    /**
     * One step of the plan: where the child sits and which collection it came from.
     */
    public static final class Address
    {
        /** The address passed to the borrow, {@code Catalog.X.Attribute.Y} and deeper. */
        public final String fqn;

        /** The canonical English kind, the key the answer groups by. */
        public final String kind;

        /**
         * @param fqn the address
         * @param kind the canonical kind
         */
        public Address(String fqn, String kind)
        {
            this.fqn = fqn;
            this.kind = kind;
        }
    }

    /**
     * What the walk has to say, grouped the way the answer writes it.
     * <p>
     * An empty kind is not recorded. {@code pulledByType} stays <code>null</code> until a snapshot
     * decides it, and {@code pulledByTypeUnverified} is set instead when the snapshot cannot be
     * taken.
     * </p>
     */
    public static final class Outcome
    {
        /** Kind to the children this call placed. */
        public final Map<String, List<String>> children = new LinkedHashMap<>();

        /** Kind to the children that were already in the extension. */
        public final Map<String, List<String>> alreadyBorrowed = new LinkedHashMap<>();

        /** Children that were not placed, each {@code fqn}, {@code kind}, {@code reason}. */
        public final List<Map<String, String>> skipped = new ArrayList<>();

        /** Top objects that appeared during the child borrows and were not requested. */
        public List<String> pulledByType;

        /** Why that list could not be built, when it could not. */
        public String pulledByTypeUnverified;
    }

    /**
     * The children that would be borrowed, in walk order.
     * <p>
     * {@code includeChildren} false, or no root, is an empty plan and the collections are not
     * asked. The root itself is not a step.
     * </p>
     *
     * @param rootFqn the borrowed object's address
     * @param root the base object to read the children from; the adopted shell has none
     * @param includeChildren whether the caller asked for the children
     * @param collections where a kind's children are read from
     * @return the steps, possibly empty
     */
    public static List<Address> plan(String rootFqn, Object root, boolean includeChildren,
        ChildCollections collections)
    {
        if (!includeChildren || root == null || rootFqn == null || rootFqn.isEmpty()
            || collections == null)
        {
            return List.of();
        }
        List<Address> planned = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        walk(root, rootFqn, collections, planned, seen);
        return planned;
    }

    /**
     * The model collections, read by the same getters an address uses.
     *
     * @return collections over a metadata object
     */
    public static ChildCollections fromTheModel()
    {
        return (owner, kind) -> {
            if (!(owner instanceof EObject))
            {
                return List.of();
            }
            List<?> list = BmObjectHelper.getChildListByKind((EObject)owner, kind);
            return list == null ? List.of() : list;
        };
    }

    /**
     * Places every planned child and records which group it landed in.
     * <p>
     * A standard attribute is skipped with {@link #NOT_ADOPTABLE} and {@code attempt} is not
     * called for it. Anything else goes through {@code attempt} with no child kind of its own,
     * so a module flag is not set.
     * </p>
     *
     * @param planned the steps {@link #plan} returned
     * @param attempt the borrow used for the root
     * @return the groups, with no snapshot yet
     */
    public static Outcome run(List<Address> planned, Attempt attempt)
    {
        Outcome outcome = new Outcome();
        if (planned == null)
        {
            return outcome;
        }
        for (Address address : planned)
        {
            if (address == null || address.fqn == null)
            {
                continue;
            }
            if (STANDARD_ATTRIBUTE.equals(address.kind))
            {
                skip(outcome, address, NOT_ADOPTABLE);
                continue;
            }
            Attempted attempted = attempt == null ? null : attempt.borrow(address.fqn);
            if (attempted != null && attempted.placed && attempted.alreadyBorrowed)
            {
                add(outcome.alreadyBorrowed, address.kind, address.fqn);
            }
            else if (attempted != null && attempted.placed)
            {
                add(outcome.children, address.kind, address.fqn);
            }
            else
            {
                String reason = attempted == null || attempted.error == null
                    ? "borrow failed" : attempted.error; //$NON-NLS-1$
                skip(outcome, address, reason);
            }
        }
        return outcome;
    }

    /**
     * Fills {@link Outcome#pulledByType} from the extension's top objects before and after the
     * child borrows.
     * <p>
     * Either snapshot missing is {@link Outcome#pulledByTypeUnverified} with a reason, not a
     * missing field. A name that is the root, a requested child, or any address under the root is
     * not pulled: a form is a top object and would otherwise be listed twice.
     * </p>
     *
     * @param outcome the groups already filled
     * @param rootFqn the borrowed object's address
     * @param planned the steps that were requested
     * @param before top objects after the root borrow and before the children, or <code>null</code>
     * @param after top objects after the children, or <code>null</code>
     */
    public static void notePulledByType(Outcome outcome, String rootFqn, Collection<Address> planned,
        Set<String> before, Set<String> after)
    {
        if (before == null || after == null)
        {
            outcome.pulledByType = null;
            outcome.pulledByTypeUnverified = "the extension's top objects could not be listed, " //$NON-NLS-1$
                + "so objects pulled in by attribute type were not distinguished from children"; //$NON-NLS-1$
            return;
        }
        Set<String> requested = new LinkedHashSet<>();
        if (planned != null)
        {
            for (Address address : planned)
            {
                if (address != null && address.fqn != null)
                {
                    requested.add(address.fqn);
                }
            }
        }
        String underRoot = rootFqn == null || rootFqn.isEmpty() ? null : rootFqn + "."; //$NON-NLS-1$
        List<String> pulled = new ArrayList<>();
        for (String fqn : after)
        {
            if (fqn == null || before.contains(fqn) || requested.contains(fqn)
                || fqn.equals(rootFqn) || (underRoot != null && fqn.startsWith(underRoot)))
            {
                continue;
            }
            pulled.add(fqn);
        }
        outcome.pulledByType = pulled;
        outcome.pulledByTypeUnverified = null;
    }

    /**
     * The success answer for a root that was borrowed with its children requested.
     * <p>
     * The root's own {@code alreadyBorrowed} tag is a map of linkage fields. The children's
     * {@code alreadyBorrowed} is a grouping by kind. When that grouping has anything in it, it
     * takes the name and the linkage fields are written beside it; {@code targetFqn} is already
     * {@code objectFqn}. When the grouping is empty the root's tag stays as it was.
     * </p>
     *
     * @param operation the operation name
     * @param objectFqn the root address
     * @param root the root borrow, already successful
     * @param outcome what the child walk found
     * @return the JSON answer
     */
    public static String answer(String operation, String objectFqn, BmExtensionHelper.BorrowResult root,
        Outcome outcome)
    {
        ToolResult result = ToolResult.success()
            .put("operation", operation) //$NON-NLS-1$
            .put("objectFqn", objectFqn) //$NON-NLS-1$
            .put("message", root != null && root.alreadyBorrowed ? "already borrowed" : "borrowed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map<String, Object> rootAlready = null;
        if (root != null && root.tags != null)
        {
            rootAlready = asMap(root.tags.get("alreadyBorrowed")); //$NON-NLS-1$
            boolean grouping = outcome != null && !outcome.alreadyBorrowed.isEmpty();
            for (Map.Entry<String, Object> entry : root.tags.entrySet())
            {
                if (grouping && "alreadyBorrowed".equals(entry.getKey())) //$NON-NLS-1$
                {
                    continue;
                }
                result.put(entry.getKey(), entry.getValue());
            }
        }
        if (outcome != null && !outcome.alreadyBorrowed.isEmpty())
        {
            result.put("alreadyBorrowed", outcome.alreadyBorrowed); //$NON-NLS-1$
            if (rootAlready != null)
            {
                for (Map.Entry<String, Object> entry : rootAlready.entrySet())
                {
                    if (!NOT_PROMOTED.contains(entry.getKey()))
                    {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        result.put("includeChildren", true); //$NON-NLS-1$
        if (outcome != null && !outcome.children.isEmpty())
        {
            result.put("children", outcome.children); //$NON-NLS-1$
        }
        result.put("skipped", outcome == null ? List.of() : outcome.skipped); //$NON-NLS-1$
        if (outcome != null && outcome.pulledByTypeUnverified != null)
        {
            result.put("pulledByTypeUnverified", outcome.pulledByTypeUnverified); //$NON-NLS-1$
        }
        else
        {
            result.put("pulledByType", //$NON-NLS-1$
                outcome == null || outcome.pulledByType == null ? List.of() : outcome.pulledByType);
        }
        return result.toJson();
    }

    /**
     * Reads what one borrow result means for a child.
     *
     * @param borrowed the result {@code attemptBorrow} returned
     * @return the outcome the walk records
     */
    public static Attempted fromBorrow(BmExtensionHelper.BorrowResult borrowed)
    {
        if (borrowed != null && borrowed.ok && borrowed.alreadyBorrowed)
        {
            return Attempted.alreadyThere();
        }
        if (borrowed != null && borrowed.ok)
        {
            return Attempted.placed();
        }
        String error = borrowed == null || borrowed.error == null ? "borrow failed" : borrowed.error; //$NON-NLS-1$
        return Attempted.failed(error);
    }

    /**
     * Walks the children after a successful root borrow and returns the answer.
     * <p>
     * The base object is what is walked. When it cannot be read, nothing is borrowed on the
     * caller's behalf and the answer says the walk did not happen, rather than reporting an empty
     * child list as if the object had none.
     * </p>
     *
     * @param operation the operation name
     * @param objectFqn the root address
     * @param root the successful root borrow
     * @param extension the extension the root was borrowed into
     * @param baseProjectName the base configuration, or empty to derive it
     * @param attempt the borrow used for each child
     * @return the JSON answer
     */
    public static String finish(String operation, String objectFqn, BmExtensionHelper.BorrowResult root,
        IProject extension, String baseProjectName, Attempt attempt)
    {
        EObject source = BmExtensionHelper.sourceObject(extension, baseProjectName, objectFqn);
        if (source == null)
        {
            Outcome unread = new Outcome();
            unread.pulledByTypeUnverified = "the base object could not be read, so its children " //$NON-NLS-1$
                + "were not walked and objects pulled in by type were not listed"; //$NON-NLS-1$
            return answer(operation, objectFqn, root, unread);
        }
        List<Address> planned = plan(objectFqn, source, true, fromTheModel());
        Set<String> before = topObjectNames(extension);
        Outcome outcome = run(planned, attempt);
        Set<String> after = before == null ? null : topObjectNames(extension);
        notePulledByType(outcome, objectFqn, planned, before, after);
        return answer(operation, objectFqn, root, outcome);
    }

    /**
     * The top-object addresses of a project, for the before/after snapshot.
     *
     * @param project the extension
     * @return the addresses, or <code>null</code> when they could not be listed
     */
    public static Set<String> topObjectNames(IProject project)
    {
        if (project == null)
        {
            return null;
        }
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
            Configuration configuration = provider.getConfiguration(project);
            if (configuration == null)
            {
                return null;
            }
            Set<String> names = new LinkedHashSet<>();
            for (String kind : MetadataTypeCatalog.getAllEnglishSingularNames())
            {
                List<? extends MdObject> objects = MetadataTypeCatalog.getObjects(configuration, kind);
                if (objects == null)
                {
                    continue;
                }
                for (MdObject object : objects)
                {
                    String fqn = addressOf(kind, object);
                    if (fqn != null)
                    {
                        names.add(fqn);
                    }
                }
            }
            return names;
        }
        catch (RuntimeException | LinkageError failed)
        {
            return null;
        }
    }

    private static String addressOf(String kind, MdObject object)
    {
        if (object == null)
        {
            return null;
        }
        if (object instanceof IBmObject)
        {
            try
            {
                String fqn = ((IBmObject)object).bmGetFqn();
                if (fqn != null && !fqn.isEmpty())
                {
                    return fqn;
                }
            }
            catch (RuntimeException notNamed)
            {
                // A top object that does not answer its address is still named by its kind below.
            }
        }
        String name = object.getName();
        if (name == null || name.isEmpty() || kind == null || kind.isEmpty())
        {
            return null;
        }
        return kind + "." + name; //$NON-NLS-1$
    }

    private static void walk(Object owner, String parentFqn, ChildCollections collections,
        List<Address> planned, Set<Object> seen)
    {
        if (owner == null || !seen.add(owner))
        {
            return;
        }
        for (String kind : BmObjectHelper.childCollectionKinds())
        {
            List<?> children = collections.children(owner, kind);
            if (children == null || children.isEmpty())
            {
                continue;
            }
            for (Object child : children)
            {
                if (child == null || seen.contains(child))
                {
                    continue;
                }
                String name = nameOf(child);
                if (name == null || name.isEmpty())
                {
                    continue;
                }
                String fqn = parentFqn + "." + kind + "." + name; //$NON-NLS-1$ //$NON-NLS-2$
                planned.add(new Address(fqn, kind));
                walk(child, fqn, collections, planned, seen);
            }
        }
    }

    private static String nameOf(Object child)
    {
        if (child instanceof MdObject)
        {
            return ((MdObject)child).getName();
        }
        try
        {
            Method method = child.getClass().getMethod("getName"); //$NON-NLS-1$
            Object value = method.invoke(child);
            return value == null ? null : value.toString();
        }
        catch (Exception ignored)
        {
            // A child with no name cannot be given an address, so it is not a step of the walk.
            return null;
        }
    }

    private static void add(Map<String, List<String>> groups, String kind, String fqn)
    {
        groups.computeIfAbsent(kind, key -> new ArrayList<>()).add(fqn);
    }

    private static void skip(Outcome outcome, Address address, String reason)
    {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("fqn", address.fqn); //$NON-NLS-1$
        row.put("kind", address.kind); //$NON-NLS-1$
        row.put("reason", reason); //$NON-NLS-1$
        outcome.skipped.add(row);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value)
    {
        if (value instanceof Map)
        {
            return (Map<String, Object>)value;
        }
        return null;
    }
}
