/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectRestorer;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.resource.IResourceStoreManager;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderStateProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.operations.IInfobaseCreationOperation;
import com._1c.g5.v8.dt.platform.services.core.operations.ISectionDeleteOperation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.internal.ClusterManagerImpl;
import ru.aiedt.mcp.server.session.SessionChangeTracker;
import ru.aiedt.mcp.server.settings.MarkerSettingsMigration;
import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.ui.MarkerFilterController;
import ru.aiedt.mcp.server.workbench.NavigatorToolbarTweaker;
import ru.aiedt.mcp.server.support.DebugLog;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.upkeep.ReleaseSweep;

/**
 * The plugin itself: it owns the MCP server, hands out the 1C:EDT services the tools work through,
 * and is where everything in the bundle logs to.
 * <p>
 * Every EDT service is reached through an OSGi tracker, and a tracker that has nothing to give
 * answers <code>null</code> rather than failing. That is deliberate. Services come and go with the
 * workbench, and a tool that finds one missing can tell the agent so; a tool that catches an
 * exception from an accessor cannot say anything useful about it.
 * </p>
 * <p>
 * Two of the services are tracked by name and typed as {@link Object}. The EDT plugin that publishes
 * them is not installed everywhere, and naming its types here would stop this whole bundle from
 * resolving where it is absent. The two tools that use them reflect on what they get back.
 * </p>
 */
public class Activator
    extends AbstractUIPlugin
{
    /** The bundle symbolic name, which is also the id every log entry is filed under. */
    public static final String PLUGIN_ID = "ru.aiedt.mcp.server"; //$NON-NLS-1$

    private static final String CLI_EXPORT_CONFIGURATION_FILES_API =
        "com._1c.g5.v8.dt.cli.api.workspace.IExportConfigurationFilesApi"; //$NON-NLS-1$

    private static final String CLI_IMPORT_CONFIGURATION_FILES_API =
        "com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi"; //$NON-NLS-1$

    private static final String PROP_UI_TESTSUITE = "org.eclipse.ui.testsuite"; //$NON-NLS-1$

    private static final String PROP_ECLIPSE_APPLICATION = "eclipse.application"; //$NON-NLS-1$

    private static final String PROP_AWT_HEADLESS = "java.awt.headless"; //$NON-NLS-1$

    private static final String HEADLESS_MARKER = "headless"; //$NON-NLS-1$

    private static final String TRUE = "true"; //$NON-NLS-1$

    private static Activator plugin;

    private McpHttpEndpoint mcpServer;

    private IClusterManager clusterService;

    private ServiceTracker<IV8ProjectManager, IV8ProjectManager> v8ProjectManagerTracker;

    private ServiceTracker<IDtProjectManager, IDtProjectManager> dtProjectManagerTracker;

    private ServiceTracker<IResourceStoreManager, IResourceStoreManager> resourceStoreManagerTracker;

    private ServiceTracker<IConfigurationProvider, IConfigurationProvider> configurationProviderTracker;

    private ServiceTracker<IMarkerManager, IMarkerManager> markerManagerTracker;

    private ServiceTracker<ICheckScheduler, ICheckScheduler> checkSchedulerTracker;

    private ServiceTracker<ICheckRepository, ICheckRepository> checkRepositoryTracker;

    private ServiceTracker<IBmModelManager, IBmModelManager> bmModelManagerTracker;

    private ServiceTracker<IDerivedDataManagerProvider, IDerivedDataManagerProvider> derivedDataManagerProviderTracker;

    private ServiceTracker<IServicesOrchestrator, IServicesOrchestrator> servicesOrchestratorTracker;

    private ServiceTracker<BmAwareResourceSetProvider, BmAwareResourceSetProvider> resourceSetProviderTracker;

    private ServiceTracker<IApplicationManager, IApplicationManager> applicationManagerTracker;

    private ServiceTracker<IInfobaseAccessManager, IInfobaseAccessManager> infobaseAccessManagerTracker;

    private ServiceTracker<IInfobaseManager, IInfobaseManager> infobaseManagerTracker;

    private ServiceTracker<IInfobaseAssociationManager, IInfobaseAssociationManager> infobaseAssociationManagerTracker;

    private ServiceTracker<IInfobaseCreationOperation, IInfobaseCreationOperation> infobaseCreationOperationTracker;

    private ServiceTracker<ISectionDeleteOperation, ISectionDeleteOperation> sectionDeleteOperationTracker;

    private ServiceTracker<IRuntimeComponentManager, IRuntimeComponentManager> runtimeComponentManagerTracker;

    private ServiceTracker<IResolvableRuntimeInstallationManager,
        IResolvableRuntimeInstallationManager> resolvableRuntimeInstallationManagerTracker;

    private ServiceTracker<INavigatorContentProviderStateProvider,
        INavigatorContentProviderStateProvider> navigatorStateProviderTracker;

    private ServiceTracker<IMdRefactoringService, IMdRefactoringService> mdRefactoringServiceTracker;

    private ServiceTracker<IRuntimeVersionSupport, IRuntimeVersionSupport> runtimeVersionSupportTracker;

    private ServiceTracker<IConfigurationProjectManager, IConfigurationProjectManager> configurationProjectManagerTracker;

    private ServiceTracker<IExtensionProjectManager, IExtensionProjectManager> extensionProjectManagerTracker;

    private ServiceTracker<IExternalObjectProjectManager,
        IExternalObjectProjectManager> externalObjectProjectManagerTracker;

    private ServiceTracker<IExternalObjectRestorer, IExternalObjectRestorer> externalObjectRestorerTracker;

    private ServiceTracker<Object, Object> exportConfigurationFilesApiTracker;

    private ServiceTracker<Object, Object> importConfigurationFilesApiTracker;

    @Override
    public void start(BundleContext context) throws Exception
    {
        super.start(context);
        plugin = this;

        mcpServer = new McpHttpEndpoint();

        if (isHeadless())
        {
            // Nothing below is safe here. A headless test runtime brings the workspace, the UI and the
            // platform up on its own schedule, and reaching for any of them from a bundle activator
            // races it and kills the process. The server object exists; nothing else is touched.
            logInfo("AI-EDT started in headless mode: EDT services and UI are not initialized"); //$NON-NLS-1$
            return;
        }

        // Before anything reads a marker setting: a workspace configured under the older key names
        // has to be carried over first, or the decorator starts up on defaults and the user's
        // choice looks lost.
        MarkerSettingsMigration.run();

        // Eagerly, and before any server start: the Tools preference page reads tool descriptions
        // straight out of the registry, and it has to work whether or not the server was ever started.
        mcpServer.registerTools();

        openServiceTrackers(context);
        SessionChangeTracker.initialize();
        startClusterService();
        initializeUi();

        logInfo("AI-EDT plugin started"); //$NON-NLS-1$
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        if (mcpServer != null && mcpServer.isRunning())
        {
            mcpServer.stop();
        }

        closeServiceTrackers();
        disposeUi();
        SessionChangeTracker.shutdown();
        stopClusterService();
        // Hands back the debug-event listener. Without this every p2 update of the plugin left its
        // listener attached to the debug plugin, pinning this bundle's classloader, and the next
        // version's listener joined it.
        DebugSessionBook.get().shutdown();
        // Same story for the marker service's workspace listener - drop it on the way out.
        MarkerManager.dispose();
        // And for the update watcher: a preference listener and a job that may be waiting on a
        // socket, either of which would keep this classloader alive past the bundle.
        ReleaseSweep.get().shutdown();

        logInfo("AI-EDT plugin stopped"); //$NON-NLS-1$
        plugin = null;
        super.stop(context);
    }

    /**
     * Returns the running plugin.
     *
     * @return the plugin, or <code>null</code> before it has started and after it has stopped
     */
    public static Activator getDefault()
    {
        return plugin;
    }

    /**
     * Returns the MCP server, started or not.
     *
     * @return the server; <code>null</code> only if the plugin failed to start
     */
    public McpHttpEndpoint getMcpServer()
    {
        return mcpServer;
    }

    /**
     * Returns the service backing the custom folders in the Navigator.
     *
     * @return the cluster service, or <code>null</code> when the plugin is headless or stopped
     */
    public IClusterManager getClusterService()
    {
        return clusterService;
    }

    /**
     * Returns the cluster service without going through the plugin, for callers that may run before it
     * is up or after it has gone.
     *
     * @return the cluster service, or <code>null</code> when it is not available
     */
    public static IClusterManager getClusterServiceStatic()
    {
        Activator activator = plugin;
        return activator != null ? activator.getClusterService() : null;
    }

    /**
     * Returns the manager of open 1C projects.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IV8ProjectManager getV8ProjectManager()
    {
        return service(v8ProjectManagerTracker);
    }

    /**
     * Returns the manager of DT projects.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IDtProjectManager getDtProjectManager()
    {
        return service(dtProjectManagerTracker);
    }

    /**
     * Returns the resource store manager.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IResourceStoreManager getResourceStoreManager()
    {
        return service(resourceStoreManagerTracker);
    }

    /**
     * Returns the provider of configuration models.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IConfigurationProvider getConfigurationProvider()
    {
        return service(configurationProviderTracker);
    }

    /**
     * Returns the manager of validation markers.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IMarkerManager getMarkerManager()
    {
        return service(markerManagerTracker);
    }

    /**
     * Returns the validation scheduler.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public ICheckScheduler getCheckScheduler()
    {
        return service(checkSchedulerTracker);
    }

    /**
     * Returns the repository of validation checks and their settings.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public ICheckRepository getCheckRepository()
    {
        return service(checkRepositoryTracker);
    }

    /**
     * Returns the manager of the BM model, through which metadata is read and written.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IBmModelManager getBmModelManager()
    {
        return service(bmModelManagerTracker);
    }

    /**
     * Returns the provider of derived-data managers.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IDerivedDataManagerProvider getDerivedDataManagerProvider()
    {
        return service(derivedDataManagerProviderTracker);
    }

    /**
     * Returns the orchestrator that knows when a project's services have finished starting.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IServicesOrchestrator getServicesOrchestrator()
    {
        return service(servicesOrchestratorTracker);
    }

    /**
     * Returns the provider of BM-aware Xtext resource sets.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public BmAwareResourceSetProvider getResourceSetProvider()
    {
        return service(resourceSetProviderTracker);
    }

    /**
     * Returns the manager of applications - the launch targets a project can be run against.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IApplicationManager getApplicationManager()
    {
        return service(applicationManagerTracker);
    }

    /**
     * Returns the manager of infobase credentials.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IInfobaseAccessManager getInfobaseAccessManager()
    {
        return service(infobaseAccessManagerTracker);
    }

    /**
     * Returns the manager of infobases.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IInfobaseManager getInfobaseManager()
    {
        return service(infobaseManagerTracker);
    }

    /**
     * Returns the manager of the links between projects and infobases.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IInfobaseAssociationManager getInfobaseAssociationManager()
    {
        return service(infobaseAssociationManagerTracker);
    }

    /**
     * Returns the operation that creates an infobase.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IInfobaseCreationOperation getInfobaseCreationOperation()
    {
        return service(infobaseCreationOperationTracker);
    }

    /**
     * Returns the operation that deletes an infobase.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public ISectionDeleteOperation getSectionDeleteOperation()
    {
        return service(sectionDeleteOperationTracker);
    }

    /**
     * Returns the manager of runtime components.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IRuntimeComponentManager getRuntimeComponentManager()
    {
        return service(runtimeComponentManagerTracker);
    }

    /**
     * Returns the manager of installed 1C platform runtimes.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IResolvableRuntimeInstallationManager getResolvableRuntimeInstallationManager()
    {
        return service(resolvableRuntimeInstallationManagerTracker);
    }

    /**
     * Returns the provider of Navigator content-provider state.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public INavigatorContentProviderStateProvider getNavigatorStateProvider()
    {
        return service(navigatorStateProviderTracker);
    }

    /**
     * Returns the metadata refactoring service.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IMdRefactoringService getMdRefactoringService()
    {
        return service(mdRefactoringServiceTracker);
    }

    /**
     * Returns the service that reports which platform version a project targets.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IRuntimeVersionSupport getRuntimeVersionSupport()
    {
        return service(runtimeVersionSupportTracker);
    }

    /**
     * Returns the manager of extension projects.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IExtensionProjectManager getExtensionProjectManager()
    {
        return service(extensionProjectManagerTracker);
    }

    /**
     * Returns the manager of base configuration projects.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IConfigurationProjectManager getConfigurationProjectManager()
    {
        return service(configurationProjectManagerTracker);
    }

    /**
     * Returns the manager of external object projects - external data processors and reports.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IExternalObjectProjectManager getExternalObjectProjectManager()
    {
        return service(externalObjectProjectManagerTracker);
    }

    /**
     * Returns the EDT service that imports an external object (.erf/.epf) into an EXISTING
     * external-object container project (mirrors EDT GUI "Import"). Backs
     * {@code external_object_workshop operation=import_external_object}.
     *
     * @return the service, or <code>null</code> when EDT does not offer it
     */
    public IExternalObjectRestorer getExternalObjectRestorer()
    {
        return service(externalObjectRestorerTracker);
    }

    /**
     * Returns the EDT command-line API that exports a configuration to XML files.
     * <p>
     * Untyped on purpose: see the note on this class. The caller reflects on it.
     * </p>
     *
     * @return the service, or <code>null</code> when this EDT installation has no CLI API
     */
    public Object getExportConfigurationFilesApi()
    {
        return service(exportConfigurationFilesApiTracker);
    }

    /**
     * Returns the EDT command-line API that imports a configuration from XML files.
     * <p>
     * Untyped on purpose: see the note on this class. The caller reflects on it.
     * </p>
     *
     * @return the service, or <code>null</code> when this EDT installation has no CLI API
     */
    public Object getImportConfigurationFilesApi()
    {
        return service(importConfigurationFilesApiTracker);
    }

    /**
     * Writes an informational entry to the platform log.
     *
     * @param message what happened
     */
    public static void logInfo(String message)
    {
        log(IStatus.INFO, message, null);
    }

    /**
     * Writes a warning to the platform log.
     *
     * @param message what looks wrong
     */
    public static void logWarning(String message)
    {
        log(IStatus.WARNING, message, null);
    }

    /**
     * Writes an error to the platform log.
     *
     * @param message what failed
     * @param e what it failed with; may be <code>null</code>
     */
    public static void logError(String message, Throwable e)
    {
        log(IStatus.ERROR, message, e);
    }

    /**
     * Does nothing, on purpose.
     * <p>
     * The callers sit on hot paths and this drowned the platform log. They are left standing so that
     * the places worth tracing stay marked, and so that turning tracing back on is a change to this
     * method rather than to a dozen files. Do not give it a body without dealing with the volume.
     * </p>
     *
     * @param message ignored
     */
    public static void logDebug(String message)
    {
        DebugLog.write(message);
    }

    /**
     * Files an entry with the platform log, if there is a plugin to file it against.
     * <p>
     * Tools log from whatever thread they happen to be on, including while the workbench is shutting
     * down and the plugin has already gone. Logging then is pointless, but throwing would be worse.
     * </p>
     *
     * @param severity one of the {@link IStatus} severities
     * @param message what happened
     * @param e the cause; may be <code>null</code>
     */
    private static void log(int severity, String message, Throwable e)
    {
        Activator activator = plugin;
        if (activator == null)
        {
            return;
        }
        activator.getLog().log(new Status(severity, PLUGIN_ID, message, e));
    }

    /**
     * Tells whether this workbench has no user interface.
     * <p>
     * By asking the system properties, and not by asking SWT. Reaching for a {@code Display} to find
     * out whether there is one initializes the native toolkit, which is precisely what fails - and
     * fails hard - in the environment being asked about.
     * </p>
     *
     * @return <code>true</code> when there is no UI to talk to
     */
    private static boolean isHeadless()
    {
        if (TRUE.equals(System.getProperty(PROP_UI_TESTSUITE)))
        {
            return true;
        }
        String application = System.getProperty(PROP_ECLIPSE_APPLICATION);
        if (application != null && application.contains(HEADLESS_MARKER))
        {
            return true;
        }
        return Boolean.parseBoolean(System.getProperty(PROP_AWT_HEADLESS));
    }

    /**
     * Starts tracking every EDT service the tools work through.
     *
     * @param context this bundle's context
     */
    private void openServiceTrackers(BundleContext context)
    {
        v8ProjectManagerTracker = openTracker(context, IV8ProjectManager.class);
        dtProjectManagerTracker = openTracker(context, IDtProjectManager.class);
        resourceStoreManagerTracker = openTracker(context, IResourceStoreManager.class);
        configurationProviderTracker = openTracker(context, IConfigurationProvider.class);
        markerManagerTracker = openTracker(context, IMarkerManager.class);
        checkSchedulerTracker = openTracker(context, ICheckScheduler.class);
        checkRepositoryTracker = openTracker(context, ICheckRepository.class);
        bmModelManagerTracker = openTracker(context, IBmModelManager.class);
        derivedDataManagerProviderTracker = openTracker(context, IDerivedDataManagerProvider.class);
        servicesOrchestratorTracker = openTracker(context, IServicesOrchestrator.class);
        resourceSetProviderTracker = openTracker(context, BmAwareResourceSetProvider.class);
        applicationManagerTracker = openTracker(context, IApplicationManager.class);
        infobaseAccessManagerTracker = openTracker(context, IInfobaseAccessManager.class);
        infobaseManagerTracker = openTracker(context, IInfobaseManager.class);
        infobaseAssociationManagerTracker = openTracker(context, IInfobaseAssociationManager.class);
        infobaseCreationOperationTracker = openTracker(context, IInfobaseCreationOperation.class);
        sectionDeleteOperationTracker = openTracker(context, ISectionDeleteOperation.class);
        runtimeComponentManagerTracker = openTracker(context, IRuntimeComponentManager.class);
        resolvableRuntimeInstallationManagerTracker =
            openTracker(context, IResolvableRuntimeInstallationManager.class);
        navigatorStateProviderTracker = openTracker(context, INavigatorContentProviderStateProvider.class);
        mdRefactoringServiceTracker = openTracker(context, IMdRefactoringService.class);
        runtimeVersionSupportTracker = openTracker(context, IRuntimeVersionSupport.class);
        extensionProjectManagerTracker = openTracker(context, IExtensionProjectManager.class);
        configurationProjectManagerTracker = openTracker(context, IConfigurationProjectManager.class);
        externalObjectProjectManagerTracker = openTracker(context, IExternalObjectProjectManager.class);
        externalObjectRestorerTracker = openTracker(context, IExternalObjectRestorer.class);

        // By name, so that this bundle does not depend on the CLI API plugin at build time.
        exportConfigurationFilesApiTracker = openTracker(context, CLI_EXPORT_CONFIGURATION_FILES_API);
        importConfigurationFilesApiTracker = openTracker(context, CLI_IMPORT_CONFIGURATION_FILES_API);
    }

    /**
     * Stops tracking every EDT service. After this the accessors answer <code>null</code>, which is a
     * state the callers already know how to deal with.
     */
    private void closeServiceTrackers()
    {
        v8ProjectManagerTracker = closeTracker(v8ProjectManagerTracker);
        dtProjectManagerTracker = closeTracker(dtProjectManagerTracker);
        resourceStoreManagerTracker = closeTracker(resourceStoreManagerTracker);
        configurationProviderTracker = closeTracker(configurationProviderTracker);
        markerManagerTracker = closeTracker(markerManagerTracker);
        checkSchedulerTracker = closeTracker(checkSchedulerTracker);
        checkRepositoryTracker = closeTracker(checkRepositoryTracker);
        bmModelManagerTracker = closeTracker(bmModelManagerTracker);
        derivedDataManagerProviderTracker = closeTracker(derivedDataManagerProviderTracker);
        servicesOrchestratorTracker = closeTracker(servicesOrchestratorTracker);
        resourceSetProviderTracker = closeTracker(resourceSetProviderTracker);
        applicationManagerTracker = closeTracker(applicationManagerTracker);
        infobaseAccessManagerTracker = closeTracker(infobaseAccessManagerTracker);
        infobaseManagerTracker = closeTracker(infobaseManagerTracker);
        infobaseAssociationManagerTracker = closeTracker(infobaseAssociationManagerTracker);
        infobaseCreationOperationTracker = closeTracker(infobaseCreationOperationTracker);
        sectionDeleteOperationTracker = closeTracker(sectionDeleteOperationTracker);
        runtimeComponentManagerTracker = closeTracker(runtimeComponentManagerTracker);
        resolvableRuntimeInstallationManagerTracker =
            closeTracker(resolvableRuntimeInstallationManagerTracker);
        navigatorStateProviderTracker = closeTracker(navigatorStateProviderTracker);
        mdRefactoringServiceTracker = closeTracker(mdRefactoringServiceTracker);
        runtimeVersionSupportTracker = closeTracker(runtimeVersionSupportTracker);
        extensionProjectManagerTracker = closeTracker(extensionProjectManagerTracker);
        configurationProjectManagerTracker = closeTracker(configurationProjectManagerTracker);
        externalObjectProjectManagerTracker = closeTracker(externalObjectProjectManagerTracker);
        externalObjectRestorerTracker = closeTracker(externalObjectRestorerTracker);
        exportConfigurationFilesApiTracker = closeTracker(exportConfigurationFilesApiTracker);
        importConfigurationFilesApiTracker = closeTracker(importConfigurationFilesApiTracker);
    }

    /**
     * Brings up the cluster service.
     * <p>
     * It is built here rather than published as a declarative service: it needs the plugin, and the
     * plugin needs it, and letting OSGi work that out means one waiting for the other.
     * </p>
     */
    private void startClusterService()
    {
        ClusterManagerImpl service = new ClusterManagerImpl();
        service.activate();
        clusterService = service;
    }

    /**
     * Takes the cluster service down.
     */
    private void stopClusterService()
    {
        if (clusterService instanceof ClusterManagerImpl)
        {
            ((ClusterManagerImpl)clusterService).deactivate();
        }
        clusterService = null;
    }

    /**
     * Puts the plugin's contributions to the workbench in place.
     */
    private static void initializeUi()
    {
        // Touching the manager brings it up with its filter switched off, which is where a new session
        // should start from however the last one ended.
        MarkerFilterController.getInstance();
        try
        {
            Display.getDefault().asyncExec(() -> {
                try
                {
                    NavigatorToolbarTweaker.getInstance().initialize();
                }
                catch (RuntimeException e)
                {
                    logError("Could not add the AI-EDT buttons to the Navigator toolbar", e); //$NON-NLS-1$
                }
            });
        }
        catch (RuntimeException e)
        {
            logError("Could not reach the display to set up the Navigator toolbar", e); //$NON-NLS-1$
        }
    }

    /**
     * Takes the plugin's contributions back out of the workbench.
     * <p>
     * Failures here are swallowed. By the time a bundle is stopping, the workbench may already have
     * disposed the widgets this is trying to tidy up, and complaining about it in the log at shutdown
     * helps nobody.
     * </p>
     */
    private static void disposeUi()
    {
        if (isHeadless())
        {
            return;
        }
        try
        {
            Display.getDefault().syncExec(() -> {
                try
                {
                    NavigatorToolbarTweaker.getInstance().dispose();
                }
                catch (RuntimeException e)
                {
                    // The workbench is going; the widgets may be gone already.
                }
            });
        }
        catch (RuntimeException e)
        {
            // The display may be disposed by now.
        }
    }

    /**
     * Starts tracking a service by type.
     *
     * @param <S> the service type
     * @param context this bundle's context
     * @param serviceClass the service interface
     * @return an open tracker
     */
    private static <S> ServiceTracker<S, S> openTracker(BundleContext context, Class<S> serviceClass)
    {
        ServiceTracker<S, S> tracker = new ServiceTracker<>(context, serviceClass, null);
        tracker.open();
        return tracker;
    }

    /**
     * Starts tracking a service by name, for a type this bundle must not be compiled against.
     *
     * @param context this bundle's context
     * @param serviceName the fully qualified name of the service interface
     * @return an open tracker
     */
    private static ServiceTracker<Object, Object> openTracker(BundleContext context, String serviceName)
    {
        ServiceTracker<Object, Object> tracker = new ServiceTracker<>(context, serviceName, null);
        tracker.open();
        return tracker;
    }

    /**
     * Asks a tracker for its service.
     *
     * @param <S> the service type
     * @param tracker the tracker; may be <code>null</code>
     * @return the service, or <code>null</code> when there is no tracker or nothing registered
     */
    private static <S> S service(ServiceTracker<S, S> tracker)
    {
        return tracker != null ? tracker.getService() : null;
    }

    /**
     * Closes a tracker.
     *
     * @param <S> the service type
     * @param tracker the tracker; may be <code>null</code>
     * @return <code>null</code>, to be assigned back over the field
     */
    private static <S> ServiceTracker<S, S> closeTracker(ServiceTracker<S, S> tracker)
    {
        if (tracker != null)
        {
            tracker.close();
        }
        return null;
    }
}
