/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.refactoring;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.refactoring.core.IBmRefactoringOperation;
import com._1c.g5.v8.dt.refactoring.core.IDeleteRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.folders.ClusterWriteOutcome;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.model.ClusterStore;
import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Cleans a deleted metadata object out of {@code aiedt-clusters.yaml}.
 * <p>
 * When EDT deletes an object, this contributes an operation that removes the object's name, and the
 * names nested under it, from every cluster that held either - and only when a cluster held one of
 * them at all, so a cluster is never left pointing at something that has gone. The gate asks about
 * the object and everything nested under it: a delete that asks only about the exact name leaves
 * the memberships of the children in the file, where they attach themselves to the next object
 * that reuses the name.
 * </p>
 */
public class ClusterDeleteRefactorHook
    implements IDeleteRefactoringContributor
{
    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject eObject,
        RefactoringSettings settings, RefactoringStatus status)
    {
        IProject project = MarkerHelpers.extractProject(eObject);
        String fqn = MarkerHelpers.extractFqn(eObject);
        if (project == null || fqn == null)
        {
            return null;
        }
        IClusterManager service = Activator.getClusterServiceStatic();
        if (service == null || !service.holdsObjectOrDescendant(project, fqn))
        {
            return null;
        }
        return new RefactoringOperationDescriptor(new ClusterObjectRemoveOperation(project, fqn));
    }

    /**
     * Tells whether a delete of {@code objectFqn} has cluster membership to clean.
     * <p>
     * True when a cluster holds that name or a name nested under it. The contributor calls this
     * before it builds an operation, so a cluster that holds only a child of the deleted object is
     * not skipped.
     * </p>
     *
     * @param storage the project's clusters; <code>null</code> counts as none
     * @param objectFqn the name being deleted
     * @return <code>true</code> when the hook must contribute an operation
     */
    static boolean holdsObjectOrDescendant(ClusterStore storage, String objectFqn)
    {
        return storage != null && storage.holdsObjectOrDescendant(objectFqn);
    }

    @Override
    public RefactoringOperationDescriptor createCleanReferenceOperation(IBmObject referencedObject,
        IBmObject referencingObject, EStructuralFeature feature, RefactoringSettings settings,
        RefactoringStatus status)
    {
        return null;
    }

    @Override
    public boolean allowProhibitedReferenceEditing(IBmCrossReference crossReference)
    {
        return false;
    }

    /**
     * The operation that removes one object's name from every cluster that held it.
     */
    private static class ClusterObjectRemoveOperation
        implements IBmRefactoringOperation
    {
        private final IProject project;

        private final String fqn;

        ClusterObjectRemoveOperation(IProject project, String fqn)
        {
            this.project = project;
            this.fqn = fqn;
        }

        @Override
        public void setActiveTransaction(IBmTransaction transaction)
        {
            // Removing cluster membership does not touch the model, so the transaction is not needed.
        }

        @Override
        public void perform()
        {
            IClusterManager service = Activator.getClusterServiceStatic();
            if (service != null)
            {
                ClusterWriteOutcome outcome = service.removeObject(project, fqn);
                if (outcome.isRefused())
                {
                    Activator.logWarning("Cluster membership for " + fqn //$NON-NLS-1$
                        + " was not removed: " + outcome.explanation()); //$NON-NLS-1$
                }
            }
        }
    }
}
