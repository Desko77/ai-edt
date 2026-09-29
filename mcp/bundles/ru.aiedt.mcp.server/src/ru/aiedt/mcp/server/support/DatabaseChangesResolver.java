/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseChangesResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChangeType;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Answers EDT's conflict question for a pull from the infobase: take the infobase side, or leave
 * the project alone.
 *
 * <p>{@code IInfobaseSynchronizationManager.retrieveInfobaseChanges} reaches this class through the
 * {@code IInfobaseChangesResolver} it is handed and asks once, when the changes carried by the
 * infobase meet the changes of the project. EDT's own answer to that question is a dialog
 * ({@code InfobaseUpdateDialogBasedCallback}); a tool call has nobody to ask, so the answer is
 * decided up front by {@code replaceLocal}.</p>
 *
 * <p>The two answers map onto the platform's own result constants, measured on the EDT 2026
 * connection layer: {@code OVERRIDDEN} makes the caller record that no external changes remain and
 * report {@code CHANGES_RESOLVED}, which is the infobase's content replacing the project's;
 * {@code IGNORED} makes it report {@code CHANGES_IGNORE} and drop the loaded changes, which is the
 * refusal. {@code DEFERRED} is never returned: it makes the platform wait on a future this class
 * would have to complete asynchronously, and nothing here works in the background.</p>
 *
 * <p>What this class records ({@link #localChangeCount()}, {@link #infobaseChanges()},
 * {@link #refusal()}) is what the caller reports after the pull. It writes nothing itself.</p>
 */
public class DatabaseChangesResolver implements IInfobaseChangesResolver
{
    private final boolean replaceLocal;

    private final Object lock = new Object();

    private int localChangeCount;

    private boolean sawChangeSet;

    private boolean fullReloadRequired;

    private Set<ObjectChange> infobaseChanges = Collections.emptySet();

    private String refusal;

    /**
     * Creates the resolver.
     *
     * @param replaceLocal whether the infobase side may replace changes made in the project
     */
    public DatabaseChangesResolver(boolean replaceLocal)
    {
        this.replaceLocal = replaceLocal;
    }

    /**
     * Answers one conflict question, recording what the question carried.
     * <p>
     * A project that carries changes of its own is refused unless {@code replaceLocal} was asked
     * for; a project that carries none is always answered with the infobase side, because there is
     * nothing to lose. The three sets are the project's own changes, as the synchronization
     * strategy collected them, and every other argument is recorded rather than consulted - which
     * is why this method answers the same way whichever thread the platform asks it on.
     * </p>
     *
     * @param project the project being pulled into
     * @param infobase the infobase the changes come from
     * @param changedObjects the objects changed in the project
     * @param deletedObjects the objects deleted in the project
     * @param changedProperties the properties changed in the project
     * @param infobaseChange the change the infobase carries; may be <code>null</code>
     * @param conflictResolver the platform's own conflict resolver, not used here
     * @param assist the platform's resolve assistance, not used here
     * @param flow the synchronization flow, not used here
     * @param monitor the progress monitor, not used here
     * @return the infobase side, or the refusal
     * @throws InfobaseSynchronizationException never; declared by the interface
     */
    @Override
    public InfobaseConflictResolution resolveInfobaseChanges(IProject project, InfobaseReference infobase,
        Set<EObject> changedObjects, Set<EObject> deletedObjects, Set<String> changedProperties,
        IInfobaseConfigurationChange infobaseChange, IInfobaseUpdateConflictResolver conflictResolver,
        IInfobaseUpdateConflictResolver.IConflictResolveAssist assist, IInfobaseSynchronizationFlow flow,
        IProgressMonitor monitor) throws InfobaseSynchronizationException
    {
        int local = size(changedObjects) + size(deletedObjects) + size(changedProperties);
        Set<ObjectChange> changes = infobaseChange == null
            ? Collections.<ObjectChange>emptySet()
            : copyOf(infobaseChange.getObjectChanges());

        synchronized (this.lock)
        {
            this.localChangeCount = local;
            this.sawChangeSet = infobaseChange != null;
            this.fullReloadRequired = infobaseChange != null && infobaseChange.isFullReloadRequired();
            this.infobaseChanges = changes;
            this.refusal = local > 0 && !this.replaceLocal ? describeRefusal(local) : null;
        }

        if (local > 0 && !this.replaceLocal)
        {
            return new InfobaseConflictResolution(InfobaseConflictResolutionResult.IGNORED);
        }
        return new InfobaseConflictResolution(InfobaseConflictResolutionResult.OVERRIDDEN);
    }

    /**
     * How many changes the project carried when the platform asked.
     *
     * @return the total across the changed objects, the deleted objects and the changed properties
     */
    public int localChangeCount()
    {
        synchronized (this.lock)
        {
            return this.localChangeCount;
        }
    }

    /**
     * Whether the platform asked at all.
     * <p>
     * A pull that found nothing to reconcile never reaches the resolver, and a caller reading zero
     * local changes off it would report a clean project where no question was asked.
     * </p>
     *
     * @return <code>true</code> when a change set was handed over
     */
    public boolean sawChangeSet()
    {
        synchronized (this.lock)
        {
            return this.sawChangeSet;
        }
    }

    /**
     * Whether the infobase's change set says the whole configuration has to be reloaded.
     *
     * @return <code>true</code> when a full reload is required
     */
    public boolean fullReloadRequired()
    {
        synchronized (this.lock)
        {
            return this.fullReloadRequired;
        }
    }

    /**
     * The change the infobase carried, as the platform handed it over.
     *
     * @return the changes, empty when none were handed over
     */
    public Set<ObjectChange> infobaseChanges()
    {
        synchronized (this.lock)
        {
            return this.infobaseChanges;
        }
    }

    /**
     * The names of the objects the infobase no longer has.
     *
     * @return one platform qualified name per deleted object, in the order the platform listed them
     */
    public List<String> deletedObjectNames()
    {
        List<String> names = new ArrayList<>();
        for (ObjectChange change : infobaseChanges())
        {
            if (change != null && change.getType() == ObjectChangeType.DELETED
                && change.getPlatformQualifiedName() != null && !change.getPlatformQualifiedName().isEmpty())
            {
                names.add(change.getPlatformQualifiedName());
            }
        }
        return names;
    }

    /**
     * How many changes the infobase carried, by kind.
     *
     * @param type the kind to count
     * @return the number of changes of that kind
     */
    public int countOf(ObjectChangeType type)
    {
        int count = 0;
        for (ObjectChange change : infobaseChanges())
        {
            if (change != null && change.getType() == type)
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Why the pull was refused, in the words the caller reports it with.
     *
     * @return the refusal, or <code>null</code> when the infobase side was taken
     */
    public String refusal()
    {
        synchronized (this.lock)
        {
            return this.refusal;
        }
    }

    /**
     * Names the refusal, with what the caller has to change to get past it.
     *
     * @param local how many changes the project carried
     * @return the text
     */
    private static String describeRefusal(int local)
    {
        return "The project has " + local + " change(s) of its own and the infobase carries changes that " //$NON-NLS-1$
            + "would replace them, so nothing was pulled. Re-run with replaceLocal=true to take the " //$NON-NLS-1$
            + "infobase's version of those objects, or update_database to push the project's changes into " //$NON-NLS-1$
            + "the infobase first."; //$NON-NLS-1$
    }

    /**
     * The size of one of the project-change sets.
     *
     * @param set the set; may be <code>null</code>
     * @return its size, or zero when it is absent
     */
    private static int size(Set<?> set)
    {
        return set == null ? 0 : set.size();
    }

    /**
     * A read-only copy of the platform's change set, so what the caller reports cannot move under
     * it while the pull is still running.
     *
     * @param changes the change set; may be <code>null</code>
     * @return the copy, empty when the set is absent
     */
    private static Set<ObjectChange> copyOf(Set<ObjectChange> changes)
    {
        return changes == null || changes.isEmpty()
            ? Collections.<ObjectChange>emptySet()
            : Collections.unmodifiableSet(new LinkedHashSet<>(changes));
    }
}
