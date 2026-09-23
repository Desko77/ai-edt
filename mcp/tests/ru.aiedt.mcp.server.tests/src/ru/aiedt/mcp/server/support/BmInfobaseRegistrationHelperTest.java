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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.Group;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.support.BmInfobaseRegistrationHelper.RegisterResult;
import ru.aiedt.mcp.server.support.BmInfobaseRegistrationHelper.RegistrationEnvironment;

/**
 * Registering an existing infobase, from the arguments to the guarded write.
 *
 * <p>The fake managers behave the way the real ones do, measured on EDT 2026.2: a write of the
 * infobase list ({@code add}, and {@code delete} for the rollback) reloads the list, and the
 * reload strips {@code ATTR_APPLICATION_ID} from the launch configurations of EVERY project in
 * the workspace - so the fake {@code IInfobaseManager} strips them on both writes, and the fake
 * {@code ILaunchManager} addresses configurations by memento only, never by display name.</p>
 */
public class BmInfobaseRegistrationHelperTest
{
    /** The managers, the projects and the applications the registration reads. */
    private static final class FakeEnvironment implements RegistrationEnvironment
    {
        final FakeLaunchConfigurations configs = new FakeLaunchConfigurations();

        /** The infobase list as EDT holds it. */
        final List<Section> infobases = new ArrayList<>();

        /** Project name to the project. */
        final Map<String, IProject> projects = new LinkedHashMap<>();

        /** Project name to the applications bound to it. */
        final Map<String, List<IInfobaseApplication>> applications = new LinkedHashMap<>();

        /** When set, the association throws with this message. */
        String associateFailure;

        /** When set, deleting the list entry throws with this message. */
        String deleteFailure;

        /** When set, reading the default application throws with this message. */
        String defaultReadFailure;

        /** Projects whose applications cannot be read: {@code getApplications} throws for them. */
        final Set<String> applicationsReadFailures = new LinkedHashSet<>();

        /** When true, {@code getApplications} answers an empty list, the way a lookup that found
         * nothing does. */
        boolean hideApplications;

        /** Whether the infobase-list scan ran under the write lock. */
        boolean getAllUnderWriteLock;

        /** What {@link #projectRunMode} answers. */
        String runMode = ClientLaunchMode.MANAGED;

        /** The default application before the call, or {@code null} for none. */
        IInfobaseApplication defaultApplication;

        /** What {@code setDefaultApplication} was last called with, or {@code null}. */
        IApplication defaultSet;

        /** Whether the ordinary-application flag was applied, and with what outcome. */
        IApplication flagApplication;
        boolean flagWanted;
        String flagOutcome = "added"; //$NON-NLS-1$

        private int applicationCounter;

        IProject project(String name)
        {
            return projects.computeIfAbsent(name, FakeEnvironment::projectProxy);
        }

        /**
         * Binds an application of {@code projectName} to {@code infobase}, the way the real
         * provision delegate materializes one after {@code associate}.
         */
        IInfobaseApplication bind(String projectName, InfobaseReference infobase)
        {
            project(projectName);
            IInfobaseApplication application =
                applicationProxy("app-" + (++applicationCounter), infobase); //$NON-NLS-1$
            applications.computeIfAbsent(projectName, k -> new ArrayList<>()).add(application);
            return application;
        }

        @Override
        public IInfobaseManager infobaseManager()
        {
            return (IInfobaseManager)Proxy.newProxyInstance(FakeEnvironment.class.getClassLoader(),
                new Class<?>[] { IInfobaseManager.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "getAll": //$NON-NLS-1$
                        getAllUnderWriteLock = LaunchApplicationIds.WRITE_LOCK.isHeldByCurrentThread();
                        return new ArrayList<Section>(infobases);
                    case "add": //$NON-NLS-1$
                        infobases.add((Section)args[0]);
                        // What the real save does: the reload strips the application id from
                        // every launch configuration of every project.
                        configs.stripApplicationIds();
                        return null;
                    case "delete": //$NON-NLS-1$
                        if (deleteFailure != null)
                        {
                            throw new IllegalStateException(deleteFailure);
                        }
                        infobases.remove(args[0]);
                        configs.stripApplicationIds();
                        return null;
                    case "findInfobaseByName": //$NON-NLS-1$
                        for (Section section : infobases)
                        {
                            if (section instanceof InfobaseReference
                                && ((InfobaseReference)section).getName().equals(args[0]))
                            {
                                return Optional.of(section);
                            }
                        }
                        return Optional.empty();
                    default:
                        return FakeLaunchConfigurations.defaultValue(method.getReturnType());
                    }
                });
        }

        @Override
        public IInfobaseAssociationManager associationManager()
        {
            return (IInfobaseAssociationManager)Proxy.newProxyInstance(
                FakeEnvironment.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociationManager.class }, (proxy, method, args) -> {
                    if ("associate".equals(method.getName())) //$NON-NLS-1$
                    {
                        if (associateFailure != null)
                        {
                            throw new IllegalStateException(associateFailure);
                        }
                        bind(((IProject)args[0]).getName(), (InfobaseReference)args[1]);
                    }
                    return FakeLaunchConfigurations.defaultValue(method.getReturnType());
                });
        }

        @Override
        public IApplicationManager applicationManager()
        {
            return (IApplicationManager)Proxy.newProxyInstance(FakeEnvironment.class.getClassLoader(),
                new Class<?>[] { IApplicationManager.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "getApplications": //$NON-NLS-1$
                        if (applicationsReadFailures.contains(((IProject)args[0]).getName()))
                        {
                            throw new IllegalStateException("the application store is closed"); //$NON-NLS-1$
                        }
                        if (hideApplications)
                        {
                            return new ArrayList<IApplication>();
                        }
                        return new ArrayList<IApplication>(
                            applications.getOrDefault(((IProject)args[0]).getName(), List.of()));
                    case "getDefaultApplication": //$NON-NLS-1$
                        if (defaultReadFailure != null)
                        {
                            throw new IllegalStateException(defaultReadFailure);
                        }
                        return Optional.ofNullable(defaultApplication);
                    case "setDefaultApplication": //$NON-NLS-1$
                        defaultSet = (IApplication)args[1];
                        defaultApplication = (IInfobaseApplication)args[1];
                        return null;
                    default:
                        return FakeLaunchConfigurations.defaultValue(method.getReturnType());
                    }
                });
        }

        @Override
        public ILaunchManager launchManager()
        {
            return configs.manager();
        }

        @Override
        public IProject resolveProject(String name)
        {
            return projects.get(name);
        }

        @Override
        public List<IProject> allProjects()
        {
            return new ArrayList<>(projects.values());
        }

        @Override
        public InfobaseAssociationContext associationContext(IProject project)
        {
            return InfobaseAssociationContext.empty();
        }

        @Override
        public String projectRunMode(IProject project)
        {
            return runMode;
        }

        @Override
        public String applyOrdinaryFlag(IApplication application, boolean wanted)
        {
            flagApplication = application;
            flagWanted = wanted;
            return flagOutcome;
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
                    switch (method.getName())
                    {
                    case "getId": //$NON-NLS-1$
                        return applicationId;
                    case "getName": //$NON-NLS-1$
                        return infobase.getName();
                    case "getInfobase": //$NON-NLS-1$
                        return infobase;
                    default:
                        return FakeLaunchConfigurations.defaultValue(method.getReturnType());
                    }
                });
        }
    }

    /** A file infobase reference as an existing list entry carries one. */
    private static InfobaseReference fileInfobase(String path, String name)
    {
        InfobaseReference reference = ModelFactory.eINSTANCE.createInfobaseReference();
        FileConnectionString connection = ModelFactory.eINSTANCE.createFileConnectionString();
        connection.setFile(path);
        reference.setConnectionString(connection);
        reference.setUuid(UUID.randomUUID());
        reference.setName(name);
        return reference;
    }

    /** A server infobase reference as an existing list entry carries one. */
    private static InfobaseReference serverInfobase(String server, String referenceName, String name)
    {
        InfobaseReference infobase = ModelFactory.eINSTANCE.createInfobaseReference();
        ServerConnectionString connection = ModelFactory.eINSTANCE.createServerConnectionString();
        connection.setServer(server);
        connection.setReference(referenceName);
        infobase.setConnectionString(connection);
        infobase.setUuid(UUID.randomUUID());
        infobase.setName(name);
        return infobase;
    }

    /**
     * Whether this platform folds letter case in paths, the way {@link InfobaseIdentity} decides
     * it: the duplicate spelled in another case is the same infobase only where the file system
     * says so.
     */
    private static boolean caseInsensitivePaths()
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        return os.contains("win") || os.contains("mac"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFileInfobaseIsAddedBoundAndMadeTheDefaultOfAProjectWithoutOne()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/NewBase", null, null, null, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertTrue(r.added);
        assertEquals("NewBase", r.infobaseName); //$NON-NLS-1$
        assertNotNull(r.uuid);
        assertEquals("app-1", r.applicationId); //$NON-NLS-1$
        assertTrue("no default stood, so the new one becomes it", r.defaultApplication); //$NON-NLS-1$
        assertNull(r.previousDefault);
        assertEquals(1, env.infobases.size());
        assertEquals("NewBase", env.infobases.get(0).getName()); //$NON-NLS-1$
        assertNotNull("the operation sets the uuid the factory leaves out", //$NON-NLS-1$
            env.infobases.get(0).getUuid());
        assertTrue(r.ordinaryApplicationFlag.startsWith("not set")); //$NON-NLS-1$
    }

    @Test
    public void aServerInfobaseIsAddedWithTheParsedServerAndReference()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", null, //$NON-NLS-1$
            "Srvr=\"srv-1c\";Ref=\"Trade\";", "Trade server", null, env); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(r.error, r.ok);
        assertTrue(r.added);
        assertEquals(1, env.infobases.size());
        InfobaseReference added = (InfobaseReference)env.infobases.get(0);
        assertTrue(added.getConnectionString() instanceof ServerConnectionString);
        ServerConnectionString connection = (ServerConnectionString)added.getConnectionString();
        assertEquals("srv-1c", connection.getServer()); //$NON-NLS-1$
        assertEquals("Trade", connection.getReference()); //$NON-NLS-1$
        assertEquals("Trade server", added.getName()); //$NON-NLS-1$
    }

    @Test
    public void aDuplicateAddressIsReusedNotAddedAgain()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        InfobaseReference existing = fileInfobase("C:/bases/existing", "Existing base"); //$NON-NLS-1$ //$NON-NLS-2$
        env.infobases.add(existing);

        // The same directory under another spelling: a trailing separator everywhere, and another
        // letter case where the file system folds case.
        String path = caseInsensitivePaths() ? "C:/BASES/EXISTING/" : "C:/bases/existing/"; //$NON-NLS-1$ //$NON-NLS-2$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", path, //$NON-NLS-1$
            null, null, null, env);

        assertTrue(r.error, r.ok);
        assertFalse("the existing entry is reused, not added", r.added); //$NON-NLS-1$
        assertEquals("Existing base", r.infobaseName); //$NON-NLS-1$
        assertEquals("the reused entry's uuid is the one answered", //$NON-NLS-1$
            existing.getUuid().toString(), r.uuid);
        assertEquals("no second entry was written", 1, env.infobases.size()); //$NON-NLS-1$
        assertNotNull("the reused entry is still bound to the project", r.applicationId); //$NON-NLS-1$
    }

    @Test
    public void anExistingDefaultIsKeptAndNamedWhenMakeDefaultIsNotPassed()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        InfobaseReference oldBase = fileInfobase("C:/bases/old", "Old base"); //$NON-NLS-1$ //$NON-NLS-2$
        env.infobases.add(oldBase);
        env.defaultApplication = env.bind("project-one", oldBase); //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, null, null, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertFalse("a default already stood, so the new one does not replace it", //$NON-NLS-1$
            r.defaultApplication);
        assertEquals("Old base", r.previousDefault); //$NON-NLS-1$
        assertNull("the standing default was not touched", env.defaultSet); //$NON-NLS-1$
    }

    @Test
    public void makeDefaultTrueReplacesTheStandingDefaultAndNamesIt()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        InfobaseReference oldBase = fileInfobase("C:/bases/old", "Old base"); //$NON-NLS-1$ //$NON-NLS-2$
        env.infobases.add(oldBase);
        env.defaultApplication = env.bind("project-one", oldBase); //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, null, Boolean.TRUE, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertTrue(r.defaultApplication);
        assertEquals("Old base", r.previousDefault); //$NON-NLS-1$
        assertNotNull("the new application was made the default", env.defaultSet); //$NON-NLS-1$
        assertEquals(r.applicationId, env.defaultSet.getId());
    }

    @Test
    public void aFailedBindingRollsTheAddedEntryBackAndRestoresTheForeignConfigurations()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        // Another project's launch configuration carries an application id; the add and the
        // rollback delete both strip it, the guard puts it back.
        env.configs.add("m-foreign", "Foreign run", "project-two", "app-foreign"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        env.associateFailure = "the association was refused"; //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, null, null, env); //$NON-NLS-1$

        assertFalse(r.ok);
        assertEquals(ErrorTags.ASSOCIATE_FAILED.wire(), r.failureKind);
        assertTrue("the answer names that the added entry was removed again", r.rolledBack); //$NON-NLS-1$
        assertTrue(r.error.contains("removed again")); //$NON-NLS-1$
        assertTrue("the entry is gone from the list", env.infobases.isEmpty()); //$NON-NLS-1$
        assertEquals("the foreign configuration got its application id back", //$NON-NLS-1$
            "app-foreign", env.configs.byName("Foreign run").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(r.launchApplicationIds);
        assertTrue(r.launchApplicationIds.contains("Foreign run")); //$NON-NLS-1$
    }

    @Test
    public void anOrdinaryApplicationConfigurationGetsTheRunModeFlag()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        env.runMode = ClientLaunchMode.ORDINARY;

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, null, null, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertEquals("added", r.ordinaryApplicationFlag); //$NON-NLS-1$
        assertNotNull("the flag was applied to the new application", env.flagApplication); //$NON-NLS-1$
        assertEquals(r.applicationId, env.flagApplication.getId());
        assertTrue(env.flagWanted);
    }

    @Test
    public void aSecondCallAnswersTheReusedEntryAsTheDefaultItAlreadyIs()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$

        RegisterResult first = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/NewBase", null, null, null, env); //$NON-NLS-1$
        assertTrue(first.error, first.ok);
        assertTrue(first.defaultApplication);

        RegisterResult second = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/NewBase", null, null, null, env); //$NON-NLS-1$

        assertTrue(second.error, second.ok);
        assertFalse("the second call reuses the entry the first one added", second.added); //$NON-NLS-1$
        assertTrue("the reused entry is the project's default after the call, so the answer " //$NON-NLS-1$
            + "says true", second.defaultApplication); //$NON-NLS-1$
        assertEquals("NewBase", second.previousDefault); //$NON-NLS-1$
        assertEquals("no second entry was written", 1, env.infobases.size()); //$NON-NLS-1$
    }

    @Test
    public void aFailedDefaultReadLeavesTheDefaultAloneAndNamesTheFailure()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        env.defaultReadFailure = "the application store is closed"; //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, null, null, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertNull("an unread default was not replaced", env.defaultSet); //$NON-NLS-1$
        assertFalse(r.defaultApplication);
        assertNotNull("the answer names the failed read", r.defaultWarning); //$NON-NLS-1$
        assertTrue(r.defaultWarning.contains("could not be read")); //$NON-NLS-1$
        assertTrue(r.defaultWarning.contains("the application store is closed")); //$NON-NLS-1$
    }

    @Test
    public void aReusedDuplicateAlreadyBoundToAnotherProjectNamesIt()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        InfobaseReference existing = fileInfobase("C:/bases/existing", "Existing base"); //$NON-NLS-1$ //$NON-NLS-2$
        env.infobases.add(existing);
        env.bind("project-two", existing); //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/existing", null, null, null, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertFalse(r.added);
        assertNotNull(r.alsoAssociatedWith);
        assertEquals(List.of("project-two"), r.alsoAssociatedWith); //$NON-NLS-1$
        assertNotNull("the binding to this project still goes ahead", r.applicationId); //$NON-NLS-1$
    }

    @Test
    public void aNameAnotherEntryCarriesIsRefusedBeforeAnythingIsWritten()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        env.infobases.add(fileInfobase("C:/bases/other", "Taken")); //$NON-NLS-1$ //$NON-NLS-2$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, "Taken", null, env); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(r.ok);
        assertEquals(ErrorTags.ALREADY_EXISTS.wire(), r.failureKind);
        assertTrue(r.error.contains("another address")); //$NON-NLS-1$
        assertEquals("nothing was written", 1, env.infobases.size()); //$NON-NLS-1$
        assertTrue("nothing was bound", env.applications.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void anExplicitMakeDefaultFalseLeavesTheProjectWithoutADefault()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, null, Boolean.FALSE, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertFalse(r.defaultApplication);
        assertNull("no default was set", env.defaultSet); //$NON-NLS-1$
        assertNull("the project still has no default", env.defaultApplication); //$NON-NLS-1$
        assertNull(r.defaultWarning);
    }

    @Test
    public void aFailedRollbackDeleteIsNamedInTheAnswer()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        env.associateFailure = "the association was refused"; //$NON-NLS-1$
        env.deleteFailure = "the list would not save"; //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, null, null, env); //$NON-NLS-1$

        assertFalse(r.ok);
        assertEquals(ErrorTags.ASSOCIATE_FAILED.wire(), r.failureKind);
        assertFalse("the rollback did not complete, so it is not claimed", r.rolledBack); //$NON-NLS-1$
        assertNotNull(r.rollbackFailure);
        assertTrue(r.rollbackFailure.contains("the list would not save")); //$NON-NLS-1$
        assertTrue("the answer names the failed rollback", //$NON-NLS-1$
            r.error.contains("could NOT be removed again")); //$NON-NLS-1$
        assertEquals("the entry stays in the list", 1, env.infobases.size()); //$NON-NLS-1$
    }

    @Test
    public void aServerDuplicateMatchesAcrossKeyCasePortAndExtraKeys()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        InfobaseReference existing = serverInfobase("srv-1c:1541", "Trade", "Trade server"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        env.infobases.add(existing);

        // The same server infobase spelled with another key case, the port kept inside Srvr, and
        // an extra key the parser skips.
        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", null, //$NON-NLS-1$
            "sRvR=\"SRV-1C:1541\";rEf=\"TRADE\";App=\"Debug\";", "Other name", null, env); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(r.error, r.ok);
        assertFalse("the same server address is reused, not added", r.added); //$NON-NLS-1$
        assertEquals("Trade server", r.infobaseName); //$NON-NLS-1$
        assertEquals("the reused entry's uuid is the one answered", //$NON-NLS-1$
            existing.getUuid().toString(), r.uuid);
        assertEquals("no second entry was written", 1, env.infobases.size()); //$NON-NLS-1$
    }

    @Test
    public void aDuplicateNestedInAGroupIsFoundByThePlainListScan()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        InfobaseReference existing = fileInfobase("C:/bases/existing", "Existing base"); //$NON-NLS-1$ //$NON-NLS-2$
        Group group = ModelFactory.eINSTANCE.createGroup();
        group.setName("Bases"); //$NON-NLS-1$
        group.addSubsection(existing);
        env.infobases.add(group);

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/existing", null, null, null, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertFalse("the entry inside the group is found by the plain-list scan", r.added); //$NON-NLS-1$
        assertEquals("Existing base", r.infobaseName); //$NON-NLS-1$
        assertEquals("nothing was added beside the group", 1, env.infobases.size()); //$NON-NLS-1$
    }

    @Test
    public void aFailedDefaultReadWithMakeDefaultTrueSetsTheDefaultAndSaysTheOldOneIsUnnamed()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        env.defaultReadFailure = "the application store is closed"; //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, null, Boolean.TRUE, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertTrue("makeDefault=true sets the default even when the read of the old one failed", //$NON-NLS-1$
            r.defaultApplication);
        assertNotNull("the new application was made the default", env.defaultSet); //$NON-NLS-1$
        assertNotNull(r.defaultWarning);
        assertTrue(r.defaultWarning.contains("could not be read")); //$NON-NLS-1$
        assertTrue(r.defaultWarning.contains("the application store is closed")); //$NON-NLS-1$
        assertFalse("the default was replaced, so the warning must not say it was left alone", //$NON-NLS-1$
            r.defaultWarning.contains("left alone")); //$NON-NLS-1$
    }

    @Test
    public void anApplicationLookupThatFindsNothingIsNotAnsweredAsTheDefault()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        env.hideApplications = true;

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, null, Boolean.FALSE, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertNull(r.applicationId);
        assertFalse("two missing reads are not the one application", r.defaultApplication); //$NON-NLS-1$
    }

    @Test
    public void aStandingDefaultPointingAtTheReusedInfobaseKeepsTheFlagWhenTheLookupFindsNothing()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        InfobaseReference existing = fileInfobase("C:/bases/existing", "Existing base"); //$NON-NLS-1$ //$NON-NLS-2$
        env.infobases.add(existing);
        env.defaultApplication = env.bind("project-one", existing); //$NON-NLS-1$
        env.hideApplications = true;

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/existing", null, null, null, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertFalse(r.added);
        assertNull(r.applicationId);
        assertTrue("the standing default points at this infobase, so it stays the default", //$NON-NLS-1$
            r.defaultApplication);
    }

    @Test
    public void aProjectWhoseApplicationsCouldNotBeReadIsNamedAsUnchecked()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$
        InfobaseReference existing = fileInfobase("C:/bases/existing", "Existing base"); //$NON-NLS-1$ //$NON-NLS-2$
        env.infobases.add(existing);
        env.bind("project-two", existing); //$NON-NLS-1$
        env.project("project-three"); //$NON-NLS-1$
        env.applicationsReadFailures.add("project-three"); //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/existing", null, null, null, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertFalse(r.added);
        assertEquals(List.of("project-two"), r.alsoAssociatedWith); //$NON-NLS-1$
        assertEquals("a failed read is not answered as \"not bound\"", //$NON-NLS-1$
            List.of("project-three"), r.associationCheckFailed); //$NON-NLS-1$
    }

    @Test
    public void theDuplicateSearchRunsUnderTheWriteLock()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.project("project-one"); //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase("project-one", //$NON-NLS-1$
            "C:/bases/new", null, null, null, env); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertTrue("the list is scanned for a duplicate under the lock that guards the write", //$NON-NLS-1$
            env.getAllUnderWriteLock);
    }
}
