/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.refactoring;

import java.util.Collection;
import java.util.Collections;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ltk.core.refactoring.Change;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.refactoring.core.IRenameRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.folders.ClusterWriteOutcome;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.repository.ClusterSaveOutcome;
import ru.aiedt.mcp.server.folders.model.ClusterStore;
import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Keeps the names in {@code aiedt-clusters.yaml} correct when EDT renames a metadata object.
 * <p>
 * Clusters hold objects by name, so a rename that this contributor does not follow would leave a cluster
 * pointing at a name that no longer exists. It contributes a single post-rename change that rewrites the
 * object's name, and the names nested under it, in every cluster that held either. The change is
 * contributed when a cluster holds the object itself or one of its children, and performing it returns
 * the change that puts the names back.
 * </p>
 */
public class ClusterRenameRefactorHook
    implements IRenameRefactoringContributor
{
    @Override
    public Collection<Change> createNativePostChanges(EObject eObject, String newName,
        RefactoringSettings settings, RefactoringStatus status)
    {
        IProject project = MarkerHelpers.extractProject(eObject);
        String oldFqn = MarkerHelpers.extractFqn(eObject);
        if (project == null || oldFqn == null)
        {
            return null;
        }
        IClusterManager service = Activator.getClusterServiceStatic();
        if (service == null || !service.holdsObjectOrDescendant(project, oldFqn))
        {
            return null;
        }
        String newFqn = MarkerHelpers.buildNewFqn(oldFqn, newName);
        return Collections.singletonList(new ClusterFqnRenameChange(project, oldFqn, newFqn));
    }

    /**
     * Tells whether a rename of {@code oldFqn} has cluster membership to carry.
     * <p>
     * True when a cluster holds that name or a name nested under it. The contributor calls this
     * before it builds a change, so a cluster that holds only a child of the renamed object is not
     * skipped.
     * </p>
     *
     * @param storage the project's clusters; <code>null</code> counts as none
     * @param oldFqn the name being renamed
     * @return <code>true</code> when the hook must contribute a change
     */
    static boolean holdsObjectOrDescendant(ClusterStore storage, String oldFqn)
    {
        return storage != null && storage.holdsObjectOrDescendant(oldFqn);
    }

    @Override
    public Collection<Change> createNativePreChanges(EObject eObject, String newName,
        RefactoringSettings settings, RefactoringStatus status)
    {
        return null;
    }

    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject eObject,
        RefactoringSettings settings, RefactoringStatus status)
    {
        return null;
    }

    @Override
    public RefactoringOperationDescriptor createPreReferenceUpdateParticipatingOperation(IBmObject bmObject,
        RefactoringSettings settings, RefactoringStatus status)
    {
        return null;
    }

    @Override
    public boolean allowProhibitedReferenceEditing(IBmCrossReference crossReference)
    {
        return false;
    }

    /**
     * The change that rewrites one object's name, and names nested under it, across the clusters
     * that held them. Performing it returns the change that writes the previous names back.
     */
    static class ClusterFqnRenameChange
        extends Change
    {
        private final IProject project;

        private final String oldFqn;

        private final String newFqn;

        ClusterFqnRenameChange(IProject project, String oldFqn, String newFqn)
        {
            this.project = project;
            this.oldFqn = oldFqn;
            this.newFqn = newFqn;
        }

        @Override
        public String getName()
        {
            return "Update cluster membership: " + oldFqn + " -> " + newFqn; //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void initializeValidationData(IProgressMonitor monitor)
        {
            // Nothing to validate.
        }

        @Override
        public org.eclipse.ltk.core.refactoring.RefactoringStatus isValid(IProgressMonitor monitor)
        {
            return new org.eclipse.ltk.core.refactoring.RefactoringStatus();
        }

        /**
         * Rewrites cluster membership from the old name to the new one.
         *
         * @param monitor unused; the rewrite does not report progress
         * @return the reverse change, which renames the new name back to the old one
         */
        @Override
        public Change perform(IProgressMonitor monitor)
        {
            ClusterWriteOutcome outcome = apply(Activator.getClusterServiceStatic(), project, oldFqn, newFqn);
            if (outcome.isRefused())
            {
                String projectName = project == null ? "<unknown>" : project.getName(); //$NON-NLS-1$
                Activator.logWarning("Cluster membership for " + oldFqn + " was not saved after rename to " //$NON-NLS-1$ //$NON-NLS-2$
                    + newFqn + " in project " + projectName + ": " + outcome.explanation()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return new ClusterFqnRenameChange(project, newFqn, oldFqn);
        }

        /**
         * Applies one direction of a cluster rename through the given service.
         * <p>
         * {@link #perform(IProgressMonitor)} calls this with the running service. A test calls it
         * with a service of its own, which is the same step undo runs.
         * </p>
         *
         * @param service the cluster service; <code>null</code> applies nothing
         * @param project the project; <code>null</code> applies nothing
         * @param from the name to replace
         * @param to the name to give it
         * @return what {@link IClusterManager#renameObject(IProject, String, String)} returned,
         *         or a refusal when there is no service or no project
         */
        static ClusterWriteOutcome apply(IClusterManager service, IProject project, String from, String to)
        {
            if (service == null || project == null || from == null || to == null)
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.refused(ClusterSaveOutcome.WRITE_FAILED,
                    "nothing to rename")); //$NON-NLS-1$
            }
            return service.renameObject(project, from, to);
        }

        @Override
        public Object getModifiedElement()
        {
            return project;
        }
    }
}
