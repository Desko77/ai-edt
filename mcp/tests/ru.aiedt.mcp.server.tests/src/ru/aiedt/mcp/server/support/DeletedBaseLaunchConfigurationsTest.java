/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper.DeletedBaseConfigurations;
import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper.LaunchEnvironment;
import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper.LaunchIds;
import ru.aiedt.mcp.server.support.FakeLaunchConfigurations.Configuration;

/**
 * The delete path of the infobase-list guard, from the snapshot to the answer.
 *
 * <p>Deleting an infobase is the one write where a stripped application id must stay stripped for
 * the configurations of the deleted base and be put back for every other one. Which
 * configurations those are is decided before the delete, while the bindings are still readable:
 * each configuration's application is resolved through the project it names and the reference it
 * points at, and it is addressed by its memento - a display name occurs in several launch types
 * at once.</p>
 *
 * <p>The cases here drive the whole sequence through fakes: snapshot, search, the strip the
 * reload performs, and the answer. A configuration that cannot be read is named and leaves the
 * search running; a search that cannot run at all restores nothing and says why.</p>
 */
public class DeletedBaseLaunchConfigurationsTest
{
    /** The launch manager, the projects and the applications the guard reads. */
    private static final class FakeEnvironment
        implements LaunchEnvironment
    {
        final FakeLaunchConfigurations configs = new FakeLaunchConfigurations();

        /** Project name to the project. */
        final Map<String, IProject> projects = new LinkedHashMap<>();

        /** Project name, a separator, then the application id. */
        final Map<String, IInfobaseApplication> applications = new LinkedHashMap<>();

        /** When false, EDT answers no applications at all. */
        boolean applicationManagerAvailable = true;

        /**
         * When set, looking up an application of {@link #applicationLookupFailureProject} fails with
         * this message.
         */
        String applicationLookupFailure;

        /** The project whose application lookup fails; the other projects answer as bound. */
        String applicationLookupFailureProject;

        IProject project(String name)
        {
            return projects.computeIfAbsent(name, FakeEnvironment::projectProxy);
        }

        /** Binds an application of {@code projectName} to {@code infobase}. */
        void bind(String projectName, String applicationId, InfobaseReference infobase)
        {
            project(projectName);
            applications.put(key(projectName, applicationId), applicationProxy(applicationId, infobase));
        }

        @Override
        public ILaunchManager launchManager()
        {
            return configs.manager();
        }

        @Override
        public IApplicationManager applicationManager()
        {
            if (!applicationManagerAvailable)
            {
                return null;
            }
            return (IApplicationManager)Proxy.newProxyInstance(
                FakeEnvironment.class.getClassLoader(),
                new Class<?>[] { IApplicationManager.class }, (proxy, method, args) -> {
                    if ("getApplication".equals(method.getName())) //$NON-NLS-1$
                    {
                        String projectName = ((IProject)args[0]).getName();
                        if (applicationLookupFailure != null
                            && projectName.equals(applicationLookupFailureProject))
                        {
                            throw new IllegalStateException(applicationLookupFailure);
                        }
                        return Optional.ofNullable(applications.get(key(projectName, (String)args[1])));
                    }
                    return FakeLaunchConfigurations.defaultValue(method.getReturnType());
                });
        }

        @Override
        public IProject resolveProject(String name)
        {
            return projects.get(name);
        }

        private static String key(String projectName, String applicationId)
        {
            return projectName + "|" + applicationId; //$NON-NLS-1$
        }

        private static IProject projectProxy(String name)
        {
            return (IProject)Proxy.newProxyInstance(FakeEnvironment.class.getClassLoader(),
                new Class<?>[] { IProject.class }, (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) //$NON-NLS-1$
                    {
                        return name;
                    }
                    if ("exists".equals(method.getName())) //$NON-NLS-1$
                    {
                        return Boolean.TRUE;
                    }
                    return FakeLaunchConfigurations.defaultValue(method.getReturnType());
                });
        }

        private static IInfobaseApplication applicationProxy(String applicationId,
            InfobaseReference infobase)
        {
            return (IInfobaseApplication)Proxy.newProxyInstance(FakeEnvironment.class.getClassLoader(),
                new Class<?>[] { IInfobaseApplication.class }, (proxy, method, args) -> {
                    if ("getId".equals(method.getName())) //$NON-NLS-1$
                    {
                        return applicationId;
                    }
                    if ("getInfobase".equals(method.getName())) //$NON-NLS-1$
                    {
                        return infobase;
                    }
                    return FakeLaunchConfigurations.defaultValue(method.getReturnType());
                });
        }
    }

    /** A distinct infobase: two of these are never the same one. */
    private static InfobaseReference infobase()
    {
        InfobaseReference reference = ModelFactory.eINSTANCE.createInfobaseReference();
        reference.setUuid(UUID.randomUUID());
        return reference;
    }

    /** How many times {@code needle} occurs in {@code haystack}. */
    private static int occurrences(String haystack, String needle)
    {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
            at = haystack.indexOf(needle, at + needle.length()))
        {
            count++;
        }
        return count;
    }

    @Test
    public void theDeletedBasesConfigurationsAreFoundByMementoAndTheForeignOneIsNot()
    {
        FakeEnvironment environment = new FakeEnvironment();
        environment.configs.add("m-deleted", "Deleted base run", "project-one", "app-deleted"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.configs.add("m-foreign", "Foreign run", "project-two", "app-foreign"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        InfobaseReference deleted = infobase();
        environment.bind("project-one", "app-deleted", deleted); //$NON-NLS-1$ //$NON-NLS-2$
        environment.bind("project-two", "app-foreign", infobase()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        DeletedBaseConfigurations found =
            BmInfobaseLifecycleHelper.deletedBaseConfigurations(launchIds, environment, deleted);

        assertEquals(Set.of("m-deleted"), found.mementos); //$NON-NLS-1$
        assertTrue(found.notIdentified.isEmpty());
        assertNull(found.searchFailed);
        assertNull(found.describeSearch());
        assertEquals("both configurations were in the snapshot", 2, launchIds.snapshot.held.size()); //$NON-NLS-1$
    }

    @Test
    public void aConfigurationThatCannotBeReadIsNamedAndDoesNotStopTheSearch()
    {
        // The broken configuration comes first: the ones after it are still examined.
        FakeEnvironment environment = new FakeEnvironment();
        Configuration broken = environment.configs.add("m-broken", "Run broken", "project-one", "app-broken"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        broken.unreadable = true;
        environment.configs.add("m-deleted", "Deleted base run", "project-one", "app-deleted"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        InfobaseReference deleted = infobase();
        environment.bind("project-one", "app-deleted", deleted); //$NON-NLS-1$ //$NON-NLS-2$

        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        DeletedBaseConfigurations found =
            BmInfobaseLifecycleHelper.deletedBaseConfigurations(launchIds, environment, deleted);

        assertEquals("the configuration after the unreadable one was still examined", //$NON-NLS-1$
            Set.of("m-deleted"), found.mementos); //$NON-NLS-1$
        assertEquals(1, found.notIdentified.size());
        assertNull(found.searchFailed);
        assertNotNull(found.describeSearch());
        assertTrue(found.describeSearch().contains("Run broken")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an unaddressable configuration is named as unaddressable", //$NON-NLS-1$
            found.describeSearch().contains("could not address 'Run broken'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and the consequence is named with it", //$NON-NLS-1$
            found.describeSearch().contains("the application id was left off")); //$NON-NLS-1$
    }

    @Test
    public void anUnreadableConfigurationIsNotClaimedRestored()
    {
        // The broken configuration is not in the snapshot - its memento could not be had - so
        // the restore writes nothing to it, and the answer must not call it restored as a
        // foreign one.
        FakeEnvironment environment = new FakeEnvironment();
        Configuration broken = environment.configs.add("m-broken", "Run broken", "project-one", "app-broken"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        broken.unreadable = true;
        environment.configs.add("m-foreign", "Foreign run", "project-two", "app-foreign"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.bind("project-two", "app-foreign", infobase()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        DeletedBaseConfigurations found =
            BmInfobaseLifecycleHelper.deletedBaseConfigurations(launchIds, environment, infobase());
        environment.configs.stripApplicationIds();
        String answer = BmInfobaseLifecycleHelper.restoreLaunchApplicationIds(launchIds, found,
            "delete_infobase"); //$NON-NLS-1$

        assertEquals("app-foreign", environment.configs.byName("Foreign run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("nothing is put back into a configuration the guard could not read", //$NON-NLS-1$
            broken.applicationId());
        assertNotNull(answer);
        assertTrue("the snapshot names it as unprotected", //$NON-NLS-1$
            answer.contains("not protected: no memento: Run broken")); //$NON-NLS-1$
        assertTrue("the search names it as unaddressable", //$NON-NLS-1$
            answer.contains("could not address 'Run broken'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the unreadable configuration is not claimed restored", //$NON-NLS-1$
            answer.contains("restored the application id of: Run broken")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aSameNamedConfigurationIsNotExcludedByOneThatCannotBeAddressed()
    {
        // A display name is not unique: two configurations of different launch types may carry
        // one name. The configuration whose memento cannot be read names itself and excludes
        // nothing else - excluding a same-named configuration whose memento does read would
        // leave a binding off that no write took away.
        FakeEnvironment environment = new FakeEnvironment();
        Configuration unaddressable =
            environment.configs.add("m-unaddressable", "Shared run", "project-one", "app-unaddressable"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Configuration foreign =
            environment.configs.add("m-foreign", "Shared run", "project-two", "app-foreign"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.bind("project-two", "app-foreign", infobase()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        unaddressable.mementoFailure = "the .launch file could not be read"; //$NON-NLS-1$

        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        DeletedBaseConfigurations found =
            BmInfobaseLifecycleHelper.deletedBaseConfigurations(launchIds, environment, infobase());
        environment.configs.stripApplicationIds();
        String answer = BmInfobaseLifecycleHelper.restoreLaunchApplicationIds(launchIds, found,
            "delete_infobase"); //$NON-NLS-1$

        assertEquals("only the configuration whose memento read is held by the snapshot", //$NON-NLS-1$
            Set.of("m-foreign"), launchIds.snapshot.held.keySet()); //$NON-NLS-1$
        assertTrue("a same-named configuration is not excluded by one that cannot be addressed", //$NON-NLS-1$
            found.unverified.isEmpty());
        assertEquals("app-foreign", foreign.applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(answer);
        assertTrue("the same-named configuration is restored and named as such", //$NON-NLS-1$
            answer.contains("restored the application id of: Shared run")); //$NON-NLS-1$
        assertEquals("the unaddressable configuration is named once, as unaddressable", 1, //$NON-NLS-1$
            occurrences(answer, "could not address 'Shared run'")); //$NON-NLS-1$
        assertFalse("the same-named configuration is not claimed gone or unread", //$NON-NLS-1$
            answer.contains("not read on restore")); //$NON-NLS-1$
        assertFalse("nothing is left without an application id after deletion", //$NON-NLS-1$
            answer.contains("left without an application id after deletion")); //$NON-NLS-1$
    }

    @Test
    public void aConfigurationWhoseProjectDoesNotResolveIsNotRestoredAndIsNamed()
    {
        // project-closed is never registered: a closed project resolves to null, and a
        // configuration naming no project at all resolves to none. Their bindings cannot be
        // placed - they may be the deleted base's - so their ids stay removed and the answer
        // names them.
        FakeEnvironment environment = new FakeEnvironment();
        environment.configs.add("m-closed", "Closed run", "project-closed", "app-closed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.configs.add("m-noproject", "No project run", "", "app-noproject"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.configs.add("m-foreign", "Foreign run", "project-two", "app-foreign"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        InfobaseReference deleted = infobase();
        environment.bind("project-two", "app-foreign", infobase()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        DeletedBaseConfigurations found =
            BmInfobaseLifecycleHelper.deletedBaseConfigurations(launchIds, environment, deleted);
        environment.configs.stripApplicationIds();
        String answer = BmInfobaseLifecycleHelper.restoreLaunchApplicationIds(launchIds, found,
            "delete_infobase"); //$NON-NLS-1$

        assertEquals(2, found.notIdentified.size());
        assertTrue("an unplaceable configuration is excluded so its id stays removed", //$NON-NLS-1$
            found.unverified.containsAll(Set.of("m-closed", "m-noproject"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(found.mementos.isEmpty());
        assertNull("the id of a configuration whose project does not resolve is not handed back", //$NON-NLS-1$
            environment.configs.byName("Closed run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(environment.configs.byName("No project run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("app-foreign", environment.configs.byName("Foreign run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(answer);
        assertTrue("the answer names the unresolved project, once", //$NON-NLS-1$
            answer.contains("Closed run: project 'project-closed' not resolved - the application id was left off")); //$NON-NLS-1$
        assertTrue(answer.contains("No project run: project '' not resolved - the application id was left off")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the configuration is named once, not once per list", 1, //$NON-NLS-1$
            occurrences(answer, "Closed run")); //$NON-NLS-1$
        assertEquals(1, occurrences(answer, "No project run")); //$NON-NLS-1$
        assertFalse("an unverified configuration is not named as the deleted base's", //$NON-NLS-1$
            answer.contains("left without an application id after deletion")); //$NON-NLS-1$
        assertFalse(answer.contains("could not read")); //$NON-NLS-1$
    }

    @Test
    public void aConfigurationThatFailsAfterItsMementoWasReadIsExcludedAndNamedOnce()
    {
        // The memento was read, then the attribute read failed: the configuration can be
        // addressed, so it is excluded from the restore - its binding may be the deleted base's -
        // and the answer names it once, as unread. It is not claimed restored, and it is not
        // named as the deleted base's own.
        FakeEnvironment environment = new FakeEnvironment();
        Configuration failing = environment.configs.add("m-failing", "Failing run", "project-one", "app-failing"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.configs.add("m-foreign", "Foreign run", "project-two", "app-foreign"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.bind("project-two", "app-foreign", infobase()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        failing.attributeFailure = "the attribute store is unreadable"; //$NON-NLS-1$
        DeletedBaseConfigurations found =
            BmInfobaseLifecycleHelper.deletedBaseConfigurations(launchIds, environment, infobase());
        environment.configs.stripApplicationIds();
        String answer = BmInfobaseLifecycleHelper.restoreLaunchApplicationIds(launchIds, found,
            "delete_infobase"); //$NON-NLS-1$

        assertTrue("the configuration is excluded so its id stays removed", //$NON-NLS-1$
            found.unverified.contains("m-failing")); //$NON-NLS-1$
        assertTrue(found.mementos.isEmpty());
        assertNull(environment.configs.byName("Failing run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("app-foreign", environment.configs.byName("Foreign run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(answer);
        assertTrue(answer.contains("Failing run: could not be read (the attribute store is unreadable) - the application id was left off")); //$NON-NLS-1$
        assertEquals("the configuration is named once", 1, occurrences(answer, "Failing run")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.contains("restored the application id of: Failing run")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.contains("left without an application id after deletion: Failing run")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aConfigurationWhoseApplicationCannotBeLookedUpIsExcludedAndNamedOnce()
    {
        // The configuration was read and its project resolves, but the application lookup failed:
        // ownership is unverified, so the binding stays off and the answer says why, once.
        FakeEnvironment environment = new FakeEnvironment();
        environment.configs.add("m-bound", "Bound run", "project-one", "app-bound"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.configs.add("m-foreign", "Foreign run", "project-two", "app-foreign"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.project("project-one"); //$NON-NLS-1$
        environment.bind("project-two", "app-foreign", infobase()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        environment.applicationLookupFailure = "the application registry is unavailable"; //$NON-NLS-1$
        environment.applicationLookupFailureProject = "project-one"; //$NON-NLS-1$

        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        DeletedBaseConfigurations found =
            BmInfobaseLifecycleHelper.deletedBaseConfigurations(launchIds, environment, infobase());
        environment.configs.stripApplicationIds();
        String answer = BmInfobaseLifecycleHelper.restoreLaunchApplicationIds(launchIds, found,
            "delete_infobase"); //$NON-NLS-1$

        assertTrue(found.unverified.contains("m-bound")); //$NON-NLS-1$
        assertNull(environment.configs.byName("Bound run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("app-foreign", environment.configs.byName("Foreign run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(answer);
        assertTrue(answer.contains("Bound run: could not be read (the application registry is unavailable) - the application id was left off")); //$NON-NLS-1$
        assertEquals("the configuration is named once", 1, occurrences(answer, "Bound run")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.contains("restored the application id of: Bound run")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void withoutAnApplicationManagerNothingIsRestoredAndTheAnswerSaysWhy()
    {
        FakeEnvironment environment = new FakeEnvironment();
        environment.configs.add("m-one", "Run one", "project-one", "app-one"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.applicationManagerAvailable = false;

        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        environment.configs.stripApplicationIds();
        DeletedBaseConfigurations found =
            BmInfobaseLifecycleHelper.deletedBaseConfigurations(launchIds, environment, infobase()); //$NON-NLS-1$
        String answer = BmInfobaseLifecycleHelper.restoreLaunchApplicationIds(launchIds, found,
            "delete_infobase"); //$NON-NLS-1$

        assertTrue(found.mementos.isEmpty());
        assertNotNull(found.searchFailed);
        assertNotNull("the answer carries the reason", answer); //$NON-NLS-1$
        assertTrue(answer.contains("application manager is unavailable")); //$NON-NLS-1$
        assertNull("nothing was put back when the search could not run", //$NON-NLS-1$
            environment.configs.byName("Run one").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aConfigurationListThatCannotBeReadRestoresNothingAndSaysWhy()
    {
        FakeEnvironment environment = new FakeEnvironment();
        environment.configs.add("m-one", "Run one", "project-one", "app-one"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        environment.configs.stripApplicationIds();
        // The list stops answering after the snapshot: nothing can be identified, so nothing is
        // put back - and the caller is told rather than handed silence.
        environment.configs.listFailure = "the .launch store is unreadable"; //$NON-NLS-1$
        DeletedBaseConfigurations found =
            BmInfobaseLifecycleHelper.deletedBaseConfigurations(launchIds, environment, infobase()); //$NON-NLS-1$
        String answer = BmInfobaseLifecycleHelper.restoreLaunchApplicationIds(launchIds, found,
            "delete_infobase"); //$NON-NLS-1$

        assertNotNull(found.searchFailed);
        assertTrue(answer.contains("could not be listed")); //$NON-NLS-1$
        assertNull(environment.configs.byName("Run one").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theDeletePathExcludesTheDeletedBaseAndRestoresTheForeignOne()
    {
        FakeEnvironment environment = new FakeEnvironment();
        environment.configs.add("m-deleted", "Deleted base run", "project-one", "app-deleted"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.configs.add("m-foreign", "Foreign run", "project-two", "app-foreign"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        InfobaseReference deleted = infobase();
        environment.bind("project-one", "app-deleted", deleted); //$NON-NLS-1$ //$NON-NLS-2$
        environment.bind("project-two", "app-foreign", infobase()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // The product's order: the snapshot, then the search while the bindings are still there,
        // then the delete, whose reload strips every configuration before it returns.
        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        DeletedBaseConfigurations found =
            BmInfobaseLifecycleHelper.deletedBaseConfigurations(launchIds, environment, deleted);
        environment.configs.stripApplicationIds();
        String answer = BmInfobaseLifecycleHelper.restoreLaunchApplicationIds(launchIds, found,
            "delete_infobase"); //$NON-NLS-1$

        assertNull("the deleted base's configuration stays unbound", //$NON-NLS-1$
            environment.configs.byName("Deleted base run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("app-foreign", environment.configs.byName("Foreign run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(answer.contains("left without an application id after deletion: Deleted base run")); //$NON-NLS-1$
        assertTrue(answer.contains("restored the application id of: Foreign run")); //$NON-NLS-1$
    }

    @Test
    public void theCreatePathRestoresBothForeignApplicationIds()
    {
        FakeEnvironment environment = new FakeEnvironment();
        environment.configs.add("m-one", "Foreign one", "project-one", "app-one"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        environment.configs.add("m-two", "Foreign two", "project-two", "app-two"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        LaunchIds launchIds = BmInfobaseLifecycleHelper.snapshotLaunchApplicationIds(environment);
        environment.configs.stripApplicationIds();
        String answer = BmInfobaseLifecycleHelper.restoreLaunchApplicationIds(launchIds,
            DeletedBaseConfigurations.none(), "create_infobase"); //$NON-NLS-1$

        assertEquals("app-one", environment.configs.byName("Foreign one").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("app-two", environment.configs.byName("Foreign two").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(answer.contains("Foreign one")); //$NON-NLS-1$
        assertTrue(answer.contains("Foreign two")); //$NON-NLS-1$
        assertFalse("the create path deletes no application, so nothing is left unbound", //$NON-NLS-1$
            answer.contains("left without an application id")); //$NON-NLS-1$
    }
}
