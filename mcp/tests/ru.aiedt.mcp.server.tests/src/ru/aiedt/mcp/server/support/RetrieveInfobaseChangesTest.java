/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChangeType;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;

/**
 * The two answers a pull from the infobase can get from this project, and what the pull leaves on
 * disk afterwards.
 *
 * <p>A project that carries changes of its own is refused - the infobase's version is not taken and
 * the caller is told what to change to get past the refusal; the same project with
 * {@code replaceLocal=true} takes the infobase side. The objects the infobase no longer has lose
 * their sources, and nothing outside those objects does: the configuration file, a neighbouring
 * object and every name that does not place one whole object stay where they are.</p>
 *
 * <p>What is asserted is the production decision itself, taken by calling
 * {@link DatabaseChangesResolver#resolveInfobaseChanges} the way the platform calls it, and the
 * production removal over a real project in the workspace. Neither an infobase nor a running EDT is
 * needed for that: the change set is the platform's own value type, and the one interface the
 * resolver reads it through is answered here.</p>
 */
public class RetrieveInfobaseChangesTest
{
    private static final String PROJECT = "AiEdtRetrieveChangesProbe"; //$NON-NLS-1$

    private static final String GOODS = "src/Catalogs/Goods"; //$NON-NLS-1$

    private static final String OTHER = "src/Catalogs/Other"; //$NON-NLS-1$

    private static final String CONFIGURATION = "src/Configuration/Configuration.mdo"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    @BeforeClass
    public static void aProjectHoldingTwoCatalogsOneOfThemDeletedInTheInfobase() throws Exception
    {
        root = Files.createTempDirectory("aiedt-retrieve-changes"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        write(projectDir, CONFIGURATION, "<mdclass:Configuration uuid=\"0aa00000-0000-0000-0000-000000000001\"/>\n"); //$NON-NLS-1$
        write(projectDir, GOODS + "/Catalog.mdo", "<mdclass:Catalog uuid=\"0aa00000-0000-0000-0000-000000000002\"/>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        write(projectDir, GOODS + "/ObjectModule.bsl", "// module of the catalog the infobase no longer has\n"); //$NON-NLS-1$ //$NON-NLS-2$
        write(projectDir, GOODS + "/Forms/ItemForm/Form.form", "<form/>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        write(projectDir, OTHER + "/Catalog.mdo", "<mdclass:Catalog uuid=\"0aa00000-0000-0000-0000-000000000003\"/>\n"); //$NON-NLS-1$ //$NON-NLS-2$

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        // The files were laid down before the project existed, so the tree is told about them
        // explicitly - the removal below reads the tree, not the disk.
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.<Path>reverseOrder()).map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
        }
    }

    /**
     * A project with changes of its own: the infobase side is not taken, the answer is the refusal
     * the platform turns into {@code CHANGES_IGNORE}, and what the pull saw is legible afterwards.
     */
    @Test
    public void aProjectWithChangesOfItsOwnIsRefused() throws Exception
    {
        DatabaseChangesResolver resolver = new DatabaseChangesResolver(false);
        InfobaseConflictResolution resolution = ask(resolver,
            Set.<EObject>of(anObject()), Set.<EObject>of(anObject()), Set.of("Description"), //$NON-NLS-1$
            changeSet(false, new ObjectChange("Catalog.Goods", ObjectChangeType.DELETED), //$NON-NLS-1$
                new ObjectChange("Catalog.Other", ObjectChangeType.MODIFIED))); //$NON-NLS-1$

        assertEquals(InfobaseConflictResolutionResult.IGNORED, resolution.getResolutionResult());
        assertEquals(3, resolver.localChangeCount());
        assertTrue(resolver.sawChangeSet());
        assertEquals(1, resolver.countOf(ObjectChangeType.DELETED));
        assertEquals(List.of("Catalog.Goods"), resolver.deletedObjectNames()); //$NON-NLS-1$
        assertTrue("the refusal has to say what the project holds", //$NON-NLS-1$
            resolver.refusal() != null && resolver.refusal().contains("replaceLocal=true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The same project and the same change set with {@code replaceLocal=true}: the infobase side is
     * taken, which is the answer the platform records as {@code CHANGES_RESOLVED}.
     */
    @Test
    public void theInfobaseSideIsTakenWhenTheCallAllowsReplacing() throws Exception
    {
        DatabaseChangesResolver resolver = new DatabaseChangesResolver(true);
        InfobaseConflictResolution resolution = ask(resolver,
            Set.<EObject>of(anObject()), Set.<EObject>of(anObject()), Set.of(), //$NON-NLS-1$
            changeSet(false, new ObjectChange("Catalog.Goods", ObjectChangeType.DELETED))); //$NON-NLS-1$

        assertEquals(InfobaseConflictResolutionResult.OVERRIDDEN, resolution.getResolutionResult());
        assertNull(resolver.refusal());
        assertEquals(2, resolver.localChangeCount());
    }

    /**
     * A project that carries nothing of its own is never refused, whatever the flags say: there is
     * nothing to lose, and a refusal here would turn a clean pull into a no-op.
     */
    @Test
    public void aProjectWithoutChangesOfItsOwnIsNotRefused() throws Exception
    {
        DatabaseChangesResolver resolver = new DatabaseChangesResolver(false);
        InfobaseConflictResolution resolution = ask(resolver,
            Set.<EObject>of(), Set.<EObject>of(), Set.of(), //$NON-NLS-1$ //$NON-NLS-2$
            changeSet(true, new ObjectChange("Catalog.Goods", ObjectChangeType.NEW))); //$NON-NLS-1$

        assertEquals(InfobaseConflictResolutionResult.OVERRIDDEN, resolution.getResolutionResult());
        assertEquals(0, resolver.localChangeCount());
        assertTrue(resolver.fullReloadRequired());
        assertEquals(1, resolver.countOf(ObjectChangeType.NEW));
        assertEquals(0, resolver.countOf(ObjectChangeType.DELETED));
    }

    /**
     * The sources of the object the infobase no longer has go, whole directory and all, and the
     * neighbouring objects and the configuration keep theirs.
     */
    @Test
    public void theSourcesOfADeletedObjectGoAndNothingElseDoes()
    {
        RemovedObjectFiles.Outcome outcome = RemovedObjectFiles.deleteFor(project, List.of("Catalog.Goods")); //$NON-NLS-1$

        assertEquals(List.of(GOODS), outcome.removed);
        assertTrue(outcome.failures.isEmpty());
        assertFalse("the object's own directory is gone", project.getFolder(GOODS).exists()); //$NON-NLS-1$
        assertFalse("the module beside it is gone with it", //$NON-NLS-1$
            project.getFile(GOODS + "/ObjectModule.bsl").exists()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the configuration keeps its file", project.getFile(CONFIGURATION).exists()); //$NON-NLS-1$
        assertTrue("the other catalog keeps its file", //$NON-NLS-1$
            project.getFile(OTHER + "/Catalog.mdo").exists()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Names that do not place one whole object are left alone: a nested object belongs to its owner,
     * an unknown type is not addressed by path, and the configuration is not an object of the
     * configuration.
     */
    @Test
    public void aNameThatIsNotAWholeObjectIsLeftAlone()
    {
        RemovedObjectFiles.Outcome outcome = RemovedObjectFiles.deleteFor(project,
            List.of("Catalog.Other.Forms.ItemForm", "NotAType.Thing", "Configuration.Configuration", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "../Catalog.Other")); //$NON-NLS-1$

        assertTrue("nothing may be removed for these names", outcome.removed.isEmpty()); //$NON-NLS-1$
        assertEquals(4, outcome.skipped.size());
        assertTrue(outcome.failures.isEmpty());
        assertTrue("the other catalog is still there", //$NON-NLS-1$
            project.getFile(OTHER + "/Catalog.mdo").exists()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Calls the resolver the way the platform calls it.
     * <p>
     * The conflict resolver, the resolve assistance and the synchronization flow are handed over as
     * <code>null</code>. The platform always supplies them, and this call ignores them: the decision
     * is made from the project's change sets, the change set the infobase carried and
     * {@code replaceLocal}, and nothing else. Passing nothing where the platform passes three
     * objects is what states that, and a resolver that starts reading one of them fails here rather
     * than passing on a value this test made up. They cannot be stood in for either - their methods
     * throw a platform exception that is not exported to this bundle, so no proxy of them can be
     * built from here.
     * </p>
     *
     * @param resolver the resolver under test
     * @param changed the objects the project changed
     * @param deleted the objects the project deleted
     * @param properties the properties the project changed
     * @param change the change set the infobase carried
     * @return the answer the platform would act on
     * @throws Exception when the platform's own signature fails
     */
    private static InfobaseConflictResolution ask(DatabaseChangesResolver resolver, Set<EObject> changed,
        Set<EObject> deleted, Set<String> properties, IInfobaseConfigurationChange change) throws Exception
    {
        return resolver.resolveInfobaseChanges(project,
            ModelFactory.eINSTANCE.createInfobaseReference(), changed, deleted, properties, change, null,
            null, null, new NullProgressMonitor());
    }

    /**
     * A change set carrying the named changes.
     *
     * @param fullReload what {@code isFullReloadRequired} answers
     * @param changes the changes the infobase carried
     * @return the change set
     */
    private static IInfobaseConfigurationChange changeSet(boolean fullReload, ObjectChange... changes)
    {
        Set<ObjectChange> set = new LinkedHashSet<>(Arrays.asList(changes));
        return (IInfobaseConfigurationChange)Proxy.newProxyInstance(loader(),
            new Class<?>[] { IInfobaseConfigurationChange.class },
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "isEmpty": //$NON-NLS-1$
                        return Boolean.valueOf(set.isEmpty());
                    case "isFullReloadRequired": //$NON-NLS-1$
                        return Boolean.valueOf(fullReload);
                    case "getObjectChanges": //$NON-NLS-1$
                        return set;
                    default:
                        return nothing(method, proxy, args);
                }
            });
    }

    private static EObject anObject()
    {
        return EcoreFactory.eINSTANCE.createEAnnotation();
    }

    private static Object nothing(Method method, Object proxy, Object[] args)
    {
        switch (method.getName())
        {
            case "toString": //$NON-NLS-1$
                return "probe"; //$NON-NLS-1$
            case "hashCode": //$NON-NLS-1$
                return Integer.valueOf(System.identityHashCode(proxy));
            case "equals": //$NON-NLS-1$
                return Boolean.valueOf(proxy == args[0]);
            default:
                break;
        }
        Class<?> type = method.getReturnType();
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == int.class)
        {
            return Integer.valueOf(0);
        }
        if (type == long.class)
        {
            return Long.valueOf(0L);
        }
        return null;
    }

    private static ClassLoader loader()
    {
        return RetrieveInfobaseChangesTest.class.getClassLoader();
    }

    private static void write(Path projectDir, String relative, String text) throws Exception
    {
        Path file = projectDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
