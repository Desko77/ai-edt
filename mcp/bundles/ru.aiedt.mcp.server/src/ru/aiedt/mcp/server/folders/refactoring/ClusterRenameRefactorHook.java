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
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Keeps the names in {@code aiedt-clusters.yaml} correct when EDT renames a metadata object.
 * <p>
 * Clusters hold objects by name, so a rename that this contributor does not follow would leave a cluster
 * pointing at a name that no longer exists. It contributes a single post-rename change that rewrites
 * the object's name in every cluster that held it, and only when the object was in a cluster at all.
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
        if (service == null || service.findClusterForObject(project, oldFqn) == null)
        {
            return null;
        }
        String newFqn = MarkerHelpers.buildNewFqn(oldFqn, newName);
        return Collections.singletonList(new ClusterFqnRenameChange(project, oldFqn, newFqn));
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
     * The change that rewrites one object's name across the clusters that held it.
     */
    private static class ClusterFqnRenameChange
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

        @Override
        public Change perform(IProgressMonitor monitor)
        {
            IClusterManager service = Activator.getClusterServiceStatic();
            if (service != null)
            {
                service.renameObject(project, oldFqn, newFqn);
            }
            return null;
        }

        @Override
        public Object getModifiedElement()
        {
            return project;
        }
    }
}
