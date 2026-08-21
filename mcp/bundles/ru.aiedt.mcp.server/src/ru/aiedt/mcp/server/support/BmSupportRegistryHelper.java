/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.v8.dt.distribution.IDistributionSupportManager;
import com.e1c.g5.v8.dt.distribution.model.DistributionSupport;
import com.e1c.g5.v8.dt.distribution.model.ParentConfigurationInfo;
import com.e1c.g5.v8.dt.distribution.model.ParentConfigurationInfoItem;
import com.e1c.g5.v8.dt.distribution.model.ParentSupportMode;
import com.e1c.g5.v8.dt.distribution.model.UserSupportMode;
import com.e1c.g5.v8.dt.distribution.model.UserSupportModeRules;

import ru.aiedt.mcp.server.Activator;

/**
 * Reads the vendor support state of a configuration: which vendor configurations it descends from,
 * what each object is allowed to have done to it, and what the environment will do to each object
 * on the next update.
 * <p>
 * <b>Two registries answer different questions and both live here.</b> The per-object mode says what
 * was DECLARED - locked, editable, or taken off support. Whether an object was actually modified is
 * a different fact, answered by comparison against the vendor delivery. This class reports the first
 * one only, and says so, because reporting a declaration as if it were a measurement is how an
 * update quietly overwrites work somebody did.
 * </p>
 * <p>
 * <b>Only the version-stable part of the EDT API is used.</b>
 * {@code com.e1c.g5.v8.dt.distribution} carries a different major version in every recent EDT
 * release - 2.0.0 on 2025.2, 3.0.1 on 2026.1, 4.0.0 on 2026.2 - and several manager methods differ
 * in arity between them. Everything read here comes either from the EMF model, whose interfaces are
 * stable, or from the three manager methods whose signature is identical across the supported
 * releases: {@link IDistributionSupportManager#getDistributionSupport(IProject)},
 * {@link IDistributionSupportManager#getUserSupportMode(MdObject)} and
 * {@link IDistributionSupportManager#getDependentMdObjects(MdObject)}. Parent-side modes are taken
 * from {@link ParentConfigurationInfoItem#getParentMode()} rather than from the manager, precisely
 * because the manager method that returns both modes changed arity.
 * </p>
 */
public final class BmSupportRegistryHelper
{
    /** Bundle that owns the support service. Named by string: the import is optional. */
    private static final String DISTRIBUTION_BUNDLE = "com.e1c.g5.v8.dt.distribution"; //$NON-NLS-1$

    /** Activator of that bundle, which owns the injector. Its package is not exported. */
    private static final String DISTRIBUTION_PLUGIN =
        "com.e1c.g5.v8.dt.internal.distribution.DistributionPlugin"; //$NON-NLS-1$

    /** How many objects one page of the object listing may carry. */
    public static final int PAGE_LIMIT = 500;

    private BmSupportRegistryHelper()
    {
        // Static helper.
    }

    /** One vendor configuration this one descends from. */
    public static final class Parent
    {
        /** Identity of the vendor configuration inside the support file. */
        public String id;

        /** Who published it. */
        public String providerName;

        /** Its configuration name. */
        public String configName;

        /** Its release, as the vendor numbers releases. */
        public String configRelease;

        /** Identity of the exact delivered version. */
        public String configVersion;

        /** FILE when the vendor configuration is kept as a file, SELF when it is this one. */
        public String storeMode;

        /** How many objects this vendor configuration accounts for. */
        public int itemCount;

        /** How many objects sit in each user mode. Keys are the mode literals. */
        public final Map<String, Integer> byUserMode = new TreeMap<>();

        /** How many objects sit in each vendor mode. Keys are the mode literals. */
        public final Map<String, Integer> byParentMode = new TreeMap<>();

        /** What the environment will do to objects on the next update, by category. */
        public final Map<String, String> updateRules = new LinkedHashMap<>();
    }

    /** The support state of one project. */
    public static final class Registry
    {
        /** Why nothing could be said. Present only when the answer is a refusal. */
        public String cannotTell;

        /**
         * How the support service was reached.
         * <p>
         * Reported rather than kept private: the service is bound through Guice and published to
         * the OSGi registry by the environment, and if that ever stops happening the fallback route
         * takes over silently. A caller comparing two runs should be able to see that the route
         * changed, instead of guessing why the answers did.
         * </p>
         */
        public String serviceRoute;

        /** False when the project carries no support information at all. */
        public boolean onSupport;

        /** Whether the environment considers an update to be waiting. */
        public boolean updateAvailable;

        /** Whether the configuration file is a normal one or a distributive. */
        public String fileState;

        /** Version counter the environment keeps on the support model. */
        public int version;

        /** The vendor configurations this one descends from. */
        public final List<Parent> parents = new ArrayList<>();
    }

    /** The support state of one metadata object. */
    public static final class ObjectState
    {
        /** Why nothing could be said. Present only when the answer is a refusal. */
        public String cannotTell;

        /** How the support service was reached. */
        public String serviceRoute;

        /** The object as it was named in the request. */
        public String object;

        /**
         * What the environment reports for the object across every vendor configuration at once.
         * <p>
         * Aggregated by the environment, not by this code, and therefore ambiguous the moment a
         * configuration descends from more than one vendor. Read {@link #perParent} to see which
         * vendor a mode belongs to.
         * </p>
         */
        public String userMode;

        /**
         * The mode recorded against each vendor configuration separately.
         * <p>
         * Present because a configuration can sit on several supports at once - a typical
         * application on the vendor's support, which itself sits on a library's. One aggregate
         * answer would name no vendor, and a caller about to change a mode has to know whose.
         * </p>
         */
        public final List<ParentModes> perParent = new ArrayList<>();

        /** Whether the environment lets this object be edited right now. */
        public boolean canEdit;

        /** Whether the environment lets this object be deleted right now. */
        public boolean canDelete;

        /** Objects the environment would require to change mode alongside this one. */
        public final List<String> dependents = new ArrayList<>();
    }

    /** What one vendor configuration records about one object. */
    public static final class ParentModes
    {
        /** Identity of the vendor configuration the two modes below belong to. */
        public String parentId;

        /** Its configuration name, so the identity does not have to be looked up. */
        public String parentName;

        /** What the vendor allowed for the object. */
        public String parentMode;

        /** What was declared here against that vendor. */
        public String userMode;

        /** Whether the support file marks the entry as used. */
        public boolean used;
    }

    /** One entry of the object listing. */
    public static final class Entry
    {
        /**
         * Fully qualified name, when the identity could be resolved to an object.
         * <p>
         * Absent for a support record whose object is gone from this configuration, and for
         * subordinate entities the name index does not cover. Both cases are counted in
         * {@link Listing#unnamed}; neither is dropped, because a listing that quietly skipped
         * records would stop adding up to the support file's own count.
         * </p>
         */
        public String object;

        /** Identity as the support file records it. Always present, even when the name is not. */
        public String id;

        /** What was declared for the object. */
        public String userMode;

        /** What the vendor allowed. */
        public String parentMode;

        /** Whether the support file marks the entry as used. */
        public boolean used;
    }

    /** A page of the object listing. */
    public static final class Listing
    {
        /** Why nothing could be said. Present only when the answer is a refusal. */
        public String cannotTell;

        /** How the support service was reached. */
        public String serviceRoute;

        /** Identity of the vendor configuration the vendor modes below belong to. */
        public String parentId;

        /** Its configuration name. */
        public String parentName;

        /** Entries matching the filter, cut to one page. */
        public final List<Entry> entries = new ArrayList<>();

        /** How many entries matched the filter in total, before the page was cut. */
        public int matched;

        /** Where this page starts inside the matching set. */
        public int offset;

        /** True when entries beyond this page matched and were not returned. */
        public boolean more;

        /**
         * How many entries could not be given a name.
         * <p>
         * Counted rather than hidden. The support file records identities, not names, and an
         * identity that no longer resolves means the object is gone from this configuration - which
         * is itself worth seeing, and would be invisible if such entries were quietly dropped.
         * </p>
         */
        public int unnamed;
    }

    /** What the service lookup produced, and by which route. */
    private static final class Service
    {
        IDistributionSupportManager manager;

        String route;

        String failure;
    }

    /**
     * Finds the support service.
     * <p>
     * Two routes, tried in order. The environment binds the service through Guice and publishes it
     * to the OSGi registry, so the ordinary lookup should answer. When it does not - a binding that
     * was never published, or an EDT that arranges this differently - the injector on the owning
     * bundle's activator is asked directly. That activator sits in a package the bundle does not
     * export, which is why the second route goes through the bundle class loader and reflection
     * rather than through an import.
     * </p>
     * <p>
     * Neither failure is swallowed. When both routes come back empty the reason is carried out in
     * {@link Service#failure}, because a tool that answers "no support information" when the truth
     * is "the service could not be reached" sends its caller to look in the wrong place.
     * </p>
     *
     * @return what was found, never <code>null</code>
     */
    private static Service findService()
    {
        Service found = new Service();
        try
        {
            found.manager = ServiceAccess.get(IDistributionSupportManager.class);
            if (found.manager != null)
            {
                found.route = "OSGi service registry"; //$NON-NLS-1$
                return found;
            }
        }
        catch (RuntimeException | LinkageError registryRefused)
        {
            Activator.logDebug("support registry: service lookup failed: " + registryRefused); //$NON-NLS-1$
        }
        try
        {
            Bundle bundle = Platform.getBundle(DISTRIBUTION_BUNDLE);
            if (bundle == null)
            {
                found.failure = "this EDT install carries no support subsystem (" //$NON-NLS-1$
                    + DISTRIBUTION_BUNDLE + " is not installed)"; //$NON-NLS-1$
                return found;
            }
            Class<?> plugin = bundle.loadClass(DISTRIBUTION_PLUGIN);
            Object instance = plugin.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            if (instance == null)
            {
                found.failure = "the support subsystem is installed but not started"; //$NON-NLS-1$
                return found;
            }
            Object injector = plugin.getMethod("getInjector").invoke(instance); //$NON-NLS-1$
            if (injector == null)
            {
                found.failure = "the support subsystem started without an injector"; //$NON-NLS-1$
                return found;
            }
            Method getInstance = injector.getClass().getMethod("getInstance", Class.class); //$NON-NLS-1$
            getInstance.setAccessible(true);
            Object manager = getInstance.invoke(injector, IDistributionSupportManager.class);
            if (manager instanceof IDistributionSupportManager)
            {
                found.manager = (IDistributionSupportManager)manager;
                found.route = "bundle injector"; //$NON-NLS-1$
                return found;
            }
            found.failure = "the support subsystem returned no manager"; //$NON-NLS-1$
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unreachable)
        {
            // Named, not swallowed: the shape of this bundle changes between EDT releases, and a
            // silent empty answer here would look exactly like a project that is not on support.
            found.failure = "the support subsystem could not be reached: " + unreachable; //$NON-NLS-1$
        }
        return found;
    }

    /**
     * Reads the support state of a project.
     *
     * @param projectName the project to read; must be open in the workspace.
     * @return what was found, or a refusal saying why nothing could be
     */
    public static Registry read(String projectName)
    {
        Registry registry = new Registry();
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            registry.cannotTell = ProjectResolver.describeNotFound(projectName);
            return registry;
        }
        Service service = findService();
        if (service.manager == null)
        {
            registry.cannotTell = service.failure;
            return registry;
        }
        registry.serviceRoute = service.route;
        DistributionSupport support = service.manager.getDistributionSupport(project);
        if (support == null)
        {
            // Not a failure: a configuration written from scratch is on nobody's support. Said
            // plainly so the caller can tell it apart from a service that would not answer.
            registry.onSupport = false;
            return registry;
        }
        registry.onSupport = true;
        registry.updateAvailable = support.isUpdateAvailable();
        registry.version = support.getVersion();
        registry.fileState = support.getFileState() == null ? null : support.getFileState().getName();
        for (ParentConfigurationInfo info : support.getParentConfigurationInfos())
        {
            registry.parents.add(describeParent(info));
        }
        return registry;
    }

    /**
     * Describes one vendor configuration and counts its objects by mode.
     *
     * @param info the vendor configuration as the support model holds it.
     * @return the description
     */
    private static Parent describeParent(ParentConfigurationInfo info)
    {
        Parent parent = new Parent();
        parent.id = info.getId() == null ? null : info.getId().toString();
        parent.providerName = info.getProviderName();
        parent.configName = info.getConfigName();
        parent.configRelease = info.getConfigRelease();
        parent.configVersion = info.getConfigVersion() == null ? null : info.getConfigVersion().toString();
        parent.storeMode = info.getStoreMode() == null ? null : info.getStoreMode().getName();
        List<ParentConfigurationInfoItem> items = info.getItems();
        parent.itemCount = items.size();
        for (ParentConfigurationInfoItem item : items)
        {
            countMode(parent.byUserMode, item.getUserMode() == null ? null : item.getUserMode().getName());
            countMode(parent.byParentMode,
                item.getParentMode() == null ? null : item.getParentMode().getName());
        }
        describeRules(info.getRules(), parent.updateRules);
        return parent;
    }

    /**
     * Adds one to the count of a mode.
     * <p>
     * A mode the model leaves unset is counted under its own key rather than skipped: the support
     * file omits an attribute equal to the model default, so unset is the majority case and
     * dropping it would make the counts fail to add up to the number of objects.
     * </p>
     *
     * @param census where to count.
     * @param mode the mode literal, or <code>null</code> when the model left it unset.
     */
    private static void countMode(Map<String, Integer> census, String mode)
    {
        String key = mode == null ? "(default)" : mode; //$NON-NLS-1$
        census.merge(key, Integer.valueOf(1), (was, one) -> Integer.valueOf(was.intValue() + 1));
    }

    /**
     * Reads the rules the environment applies to objects on the next update.
     * <p>
     * These are what actually decides the outcome of an update for an object nobody looked at, so
     * they belong in the answer next to the counts rather than behind another call.
     * </p>
     *
     * @param rules the rules as the support model holds them; may be <code>null</code>.
     * @param into where to write the description.
     */
    private static void describeRules(UserSupportModeRules rules, Map<String, String> into)
    {
        if (rules == null)
        {
            return;
        }
        into.put("newObjectsWithFreeMode", literal(rules.getNewObjectsWithFreeMode())); //$NON-NLS-1$
        into.put("newObjectsWithWarningMode", literal(rules.getNewObjectsWithWarningMode())); //$NON-NLS-1$
        into.put("existingIdenticalObjectsWithFreeMode", //$NON-NLS-1$
            rules.isExistingIdenticalObjectsWithFreeModeKeepMode() ? "keep current mode" //$NON-NLS-1$
                : literal(rules.getExistingIdenticalObjectsWithFreeMode()));
        into.put("existingIdenticalObjectsWithWarningMode", //$NON-NLS-1$
            rules.isExistingIdenticalObjectsWithWarningModeKeepMode() ? "keep current mode" //$NON-NLS-1$
                : literal(rules.getExistingIdenticalObjectsWithWarningMode()));
        into.put("existingDifferentObjectsWithFreeMode", //$NON-NLS-1$
            rules.isExistingDifferentObjectsWithFreeModeKeepMode() ? "keep current mode" //$NON-NLS-1$
                : literal(rules.getExistingDifferentObjectsWithFreeMode()));
        into.put("existingDifferentObjectsWithWarningMode", //$NON-NLS-1$
            rules.isExistingDifferentObjectsWithWarningModeKeepMode() ? "keep current mode" //$NON-NLS-1$
                : literal(rules.getExistingDifferentObjectsWithWarningMode()));
    }

    /**
     * Names a user mode.
     *
     * @param mode the mode; may be <code>null</code>.
     * @return its literal, or <code>null</code>
     */
    private static String literal(UserSupportMode mode)
    {
        return mode == null ? null : mode.getName();
    }

    /**
     * Reads the support state of one metadata object.
     *
     * @param projectName the project holding it.
     * @param fqn the object, named as the rest of this server names metadata objects.
     * @return what was found, or a refusal saying why nothing could be
     */
    public static ObjectState objectMode(String projectName, String fqn)
    {
        ObjectState state = new ObjectState();
        state.object = fqn;
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            state.cannotTell = ProjectResolver.describeNotFound(projectName);
            return state;
        }
        Service service = findService();
        if (service.manager == null)
        {
            state.cannotTell = service.failure;
            return state;
        }
        state.serviceRoute = service.route;
        Configuration configuration = configurationOf(project);
        if (configuration == null)
        {
            state.cannotTell = "no configuration is loaded for project " + projectName; //$NON-NLS-1$
            return state;
        }
        MdObject object = resolve(configuration, fqn);
        if (object == null)
        {
            state.cannotTell = fqn + " was not found in " + projectName; //$NON-NLS-1$
            return state;
        }
        DistributionSupport support = service.manager.getDistributionSupport(project);
        if (support == null)
        {
            state.cannotTell = projectName + " is not on support, so no object has a support mode"; //$NON-NLS-1$
            return state;
        }
        UserSupportMode mode = service.manager.getUserSupportMode(object);
        state.userMode = literal(mode);
        collectPerParent(support, object.getUuid(), state.perParent);
        state.canEdit = service.manager.canEdit(object);
        state.canDelete = service.manager.canDelete(object);
        for (MdObject dependent : service.manager.getDependentMdObjects(object))
        {
            if (dependent != null)
            {
                state.dependents.add(nameOf(dependent));
            }
        }
        return state;
    }

    /**
     * Collects what every vendor configuration records about one object.
     * <p>
     * Taken from the support model rather than from the manager on purpose, twice over. The manager
     * method that returns the vendor mode changed arity between EDT 2026.1 and 2026.2, and the
     * model did not; and the manager's single-object method aggregates across vendors, which
     * silently loses the answer to "whose support" as soon as there is more than one.
     * </p>
     *
     * @param support the support model of the project.
     * @param uuid identity of the object; may be <code>null</code>.
     * @param into where to add one entry per vendor configuration that mentions the object.
     */
    private static void collectPerParent(DistributionSupport support, UUID uuid, List<ParentModes> into)
    {
        if (uuid == null)
        {
            return;
        }
        for (ParentConfigurationInfo info : support.getParentConfigurationInfos())
        {
            for (ParentConfigurationInfoItem item : info.getItems())
            {
                if (!uuid.equals(item.getUserId()))
                {
                    continue;
                }
                ParentModes modes = new ParentModes();
                modes.parentId = info.getId() == null ? null : info.getId().toString();
                modes.parentName = info.getConfigName();
                ParentSupportMode vendor = item.getParentMode();
                modes.parentMode = vendor == null ? null : vendor.getName();
                modes.userMode = item.getUserMode() == null ? null : item.getUserMode().getName();
                modes.used = item.isUsed();
                into.add(modes);
                break;
            }
        }
    }

    /**
     * Lists objects by declared support mode.
     * <p>
     * The listing walks the metadata objects of the configuration rather than the entries of the
     * support file, and asks the environment for the mode of each. The support file records
     * identities without names, and an answer full of identities is of no use to whoever has to
     * decide what to do with the objects.
     * </p>
     *
     * @param projectName the project to list.
     * @param userModeFilter list only objects in this user mode; all modes when <code>null</code>.
     * @param parentId which vendor configuration the vendor modes should come from; may be
     *            <code>null</code> only when the project descends from exactly one.
     * @param offset how many matching objects to skip.
     * @param limit how many to return, capped at {@link #PAGE_LIMIT}.
     * @return one page, or a refusal saying why nothing could be listed
     */
    public static Listing listObjects(String projectName, String userModeFilter, String parentId,
        int offset, int limit)
    {
        Listing listing = new Listing();
        listing.offset = Math.max(0, offset);
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            listing.cannotTell = ProjectResolver.describeNotFound(projectName);
            return listing;
        }
        Service service = findService();
        if (service.manager == null)
        {
            listing.cannotTell = service.failure;
            return listing;
        }
        listing.serviceRoute = service.route;
        Configuration configuration = configurationOf(project);
        if (configuration == null)
        {
            listing.cannotTell = "no configuration is loaded for project " + projectName; //$NON-NLS-1$
            return listing;
        }
        DistributionSupport support = service.manager.getDistributionSupport(project);
        if (support == null)
        {
            listing.cannotTell = projectName + " is not on support, so no object has a support mode"; //$NON-NLS-1$
            return listing;
        }
        ParentConfigurationInfo scope = scopeTo(support, parentId);
        if (scope == null)
        {
            listing.cannotTell = describeScopeRefusal(support, parentId);
            return listing;
        }
        listing.parentId = scope.getId() == null ? null : scope.getId().toString();
        listing.parentName = scope.getConfigName();
        Map<UUID, String> names = indexNames(configuration);
        int page = limit <= 0 ? PAGE_LIMIT : Math.min(limit, PAGE_LIMIT);
        int seen = 0;
        for (ParentConfigurationInfoItem item : scope.getItems())
        {
            String userMode = item.getUserMode() == null ? null : item.getUserMode().getName();
            if (userModeFilter != null && !userModeFilter.equalsIgnoreCase(userMode))
            {
                continue;
            }
            listing.matched++;
            if (seen++ < listing.offset)
            {
                continue;
            }
            if (listing.entries.size() >= page)
            {
                listing.more = true;
                continue;
            }
            Entry entry = new Entry();
            entry.id = item.getUserId() == null ? null : item.getUserId().toString();
            entry.object = item.getUserId() == null ? null : names.get(item.getUserId());
            entry.userMode = userMode;
            entry.parentMode = item.getParentMode() == null ? null : item.getParentMode().getName();
            entry.used = item.isUsed();
            if (entry.object == null)
            {
                listing.unnamed++;
            }
            listing.entries.add(entry);
        }
        return listing;
    }

    /**
     * Builds a lookup from object identity to fully qualified name.
     * <p>
     * The support file records identities and no names, so this is what turns a listing into
     * something a person can act on. Subordinate entities - attributes, forms, templates - carry
     * their own support records and outnumber the objects several times over; they are not indexed
     * here, and the entries that answer to them come back named as identities and counted under
     * {@code unnamed}. That is deliberate: walking every object's contents to name them would load
     * the whole model for a listing, and an identity reported as an identity is honest, whereas an
     * entry silently dropped would make the listing disagree with the support file's own count.
     * </p>
     *
     * @param configuration the configuration to index.
     * @return identity to name, including the configuration root
     */
    private static Map<UUID, String> indexNames(Configuration configuration)
    {
        Map<UUID, String> names = new LinkedHashMap<>();
        if (configuration.getUuid() != null)
        {
            // The root is an ordinary entry of the registry and the one whose mode has to be open
            // before any other can be, so a listing that omitted it would hide the answer people
            // look for first.
            names.put(configuration.getUuid(), "Configuration"); //$NON-NLS-1$
        }
        for (String type : MetadataTypeCatalog.getAllEnglishSingularNames())
        {
            List<? extends MdObject> objects;
            try
            {
                objects = MetadataTypeCatalog.getObjects(configuration, type);
            }
            catch (RuntimeException noSuchCollection)
            {
                continue;
            }
            if (objects == null)
            {
                continue;
            }
            for (MdObject object : objects)
            {
                if (object != null && object.getUuid() != null)
                {
                    names.put(object.getUuid(), type + "." + object.getName()); //$NON-NLS-1$
                }
            }
        }
        return names;
    }

    /**
     * Chooses which vendor configuration the listing reports against.
     * <p>
     * One vendor and no argument is the ordinary case and needs no ceremony. Several vendors and no
     * argument is refused rather than answered from whichever came first: a mode belongs to a
     * vendor, and an answer that does not say which vendor is worse than no answer, because it
     * looks like one.
     * </p>
     *
     * @param support the support model of the project.
     * @param parentId the vendor asked for; may be <code>null</code>.
     * @return the vendor configuration to report against, or <code>null</code> when the request is
     *         ambiguous or names a vendor this project does not descend from
     */
    private static ParentConfigurationInfo scopeTo(DistributionSupport support, String parentId)
    {
        List<ParentConfigurationInfo> parents = support.getParentConfigurationInfos();
        if (parentId == null || parentId.trim().isEmpty())
        {
            return parents.size() == 1 ? parents.get(0) : null;
        }
        String wanted = parentId.trim();
        for (ParentConfigurationInfo info : parents)
        {
            if (info.getId() != null && wanted.equalsIgnoreCase(info.getId().toString())
                || wanted.equalsIgnoreCase(info.getConfigName()))
            {
                return info;
            }
        }
        return null;
    }

    /**
     * Says why a vendor configuration could not be chosen.
     *
     * @param support the support model of the project.
     * @param parentId the vendor asked for; may be <code>null</code>.
     * @return the reason, naming the vendors that are actually there
     */
    private static String describeScopeRefusal(DistributionSupport support, String parentId)
    {
        StringBuilder names = new StringBuilder();
        for (ParentConfigurationInfo info : support.getParentConfigurationInfos())
        {
            if (names.length() > 0)
            {
                names.append(", "); //$NON-NLS-1$
            }
            names.append(info.getConfigName()).append(" (") //$NON-NLS-1$
                .append(info.getId() == null ? "no id" : info.getId().toString()).append(')'); //$NON-NLS-1$
        }
        if (parentId == null || parentId.trim().isEmpty())
        {
            return "this configuration descends from more than one vendor configuration, so a " //$NON-NLS-1$
                + "support mode belongs to one of them rather than to the object outright. Name " //$NON-NLS-1$
                + "one in parentId: " + names; //$NON-NLS-1$
        }
        return parentId + " is not a vendor configuration this one descends from. Available: " //$NON-NLS-1$
            + names;
    }

    /**
     * Reads the configuration of a project.
     *
     * @param project the project.
     * @return its configuration, or <code>null</code> when none is loaded
     */
    private static Configuration configurationOf(IProject project)
    {
        try
        {
            com._1c.g5.v8.dt.core.platform.IConfigurationProvider provider =
                Activator.getDefault().getConfigurationProvider();
            return provider == null ? null : provider.getConfiguration(project);
        }
        catch (RuntimeException notLoaded)
        {
            Activator.logDebug("support registry: no configuration for " //$NON-NLS-1$
                + project.getName() + ": " + notLoaded); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Finds a metadata object by its fully qualified name.
     *
     * @param configuration the configuration to look in.
     * @param fqn the name.
     * @return the object, or <code>null</code> when the configuration has no such object
     */
    private static MdObject resolve(Configuration configuration, String fqn)
    {
        if (fqn == null || fqn.trim().isEmpty())
        {
            return null;
        }
        String wanted = fqn.trim();
        if ("Configuration".equalsIgnoreCase(wanted)) //$NON-NLS-1$
        {
            // The configuration root is an ordinary entry of the support registry and the one whose
            // mode has to be set before any other can be, so it must be addressable by name.
            return configuration;
        }
        return BmSubsystemHelper.resolveByFqn(configuration, wanted);
    }

    /**
     * Names a metadata object for the answer.
     *
     * @param object the object.
     * @return its name, or <code>null</code> when it will not name itself
     */
    private static String nameOf(MdObject object)
    {
        try
        {
            if (object instanceof Configuration)
            {
                return "Configuration"; //$NON-NLS-1$
            }
            // Qualified, not bare. A listing that answers "Products" leaves the caller to guess
            // whether that is a catalog or a document, and the name cannot be handed back to
            // object_mode, which is the one thing a caller will want to do with it.
            String type = object.eClass() == null ? null : object.eClass().getName();
            String name = object.getName();
            if (name == null)
            {
                return null;
            }
            return type == null || type.isEmpty() ? name : type + "." + name; //$NON-NLS-1$
        }
        catch (RuntimeException unnamed)
        {
            return null;
        }
    }
}
