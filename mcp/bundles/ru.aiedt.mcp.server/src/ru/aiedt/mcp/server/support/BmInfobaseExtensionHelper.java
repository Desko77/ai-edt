/*
 * Licensed under AGPL-3.0-or-later.
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */
package ru.aiedt.mcp.server.support;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.Lock;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;

/**
 * Lists / uninstalls configuration extensions in a CONNECTED infobase via EDT's
 * thick client ({@code IThickClientLauncher}). Both spawn a 1C:Enterprise
 * DESIGNER batch process (like create_infobase / the .epf build), so they need a
 * resolvable platform runtime with a ThickClient component, a connected infobase
 * reachable with valid stored credentials ({@code set_infobase_credentials}),
 * and the secure storage primed. {@code listConfigurationExtensions} is
 * read-only; {@code deleteConfigurationExtension} mutates the infobase.
 * <p>
 * Resolution chain (from EDT's own ExtensionsViewer): project + infobase ->
 * {@code IResolvableRuntimeInstallationManager.resolveByProjectAndInfobase} ->
 * {@code IResolvableRuntimeInstallation.resolve(ThickClient, appArch)} ->
 * {@code IRuntimeComponentManager.resolveExecutor(...)} -> the launcher +
 * component; arguments built from the stored {@code IInfobaseAccessSettings}.
 * Never throws out; failures land in the result holders.
 */
public final class BmInfobaseExtensionHelper
{
    private static final String RUNTIME_TYPE_ENTERPRISE =
        "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform"; //$NON-NLS-1$

    private BmInfobaseExtensionHelper()
    {
    }

    /** Result of listExtensions. */
    public static final class ListResult
    {
        public boolean ok;
        public String error;
        public String failureKind;
        public String infobaseName;
        public List<String> extensions;
    }

    /** Result of uninstallExtension. */
    public static final class DeleteResult
    {
        public boolean ok;
        public String error;
        public String failureKind;
        public String infobaseName;
        public String extensionName;
    }

    /** Result of exportExtension. */
    public static final class ExportResult
    {
        public boolean ok;
        public String error;
        public String failureKind;
        public String infobaseName;
        public String extensionName;
        public String outputPath;
        public long sizeBytes;
    }

    /** Result of installExtension. */
    public static final class InstallResult
    {
        public boolean ok;
        public String error;
        public String failureKind;
        public String infobaseName;
        public String extensionName;
        public String inputPath;
        public boolean databaseUpdated;
        public String designerLog;
    }

    /** Lists the configuration extensions installed in the project's infobase (read-only). */
    public static ListResult listExtensions(String projectName, String applicationId)
    {
        ListResult r = new ListResult();
        LauncherContext ctx = resolveLauncher(projectName, applicationId);
        if (ctx.error != null)
        {
            r.error = ctx.error;
            r.failureKind = ctx.failureKind;
            r.infobaseName = ctx.infobaseName;
            return r;
        }
        r.infobaseName = ctx.infobaseName;
        try
        {
            if (ctx.lock != null) ctx.lock.lock();
            try
            {
                // Same disconnect/reconnect as install/uninstall: the list
                // thick-client also needs EDT's designer agent off the file
                // infobase, or it blocks on the monopoly (row 55).
                boolean disconnected = disconnectForThickClient(ctx);
                try
                {
                    List<String> exts = ctx.launcher.listConfigurationExtensions(ctx.component,
                        ctx.infobase, ctx.args);
                    r.ok = true;
                    r.extensions = exts != null ? exts : Collections.emptyList();
                }
                finally
                {
                    if (disconnected) reconnectInfobase(ctx);
                }
            }
            finally
            {
                if (ctx.lock != null) ctx.lock.unlock();
            }
        }
        catch (Throwable e)
        {
            classifyThickClientFailure(e, s -> { r.error = s.error; r.failureKind = s.failureKind; });
        }
        return r;
    }

    /**
     * Uninstalls one configuration extension by name (or ALL when name is
     * null/empty) from the project's infobase. Mutates the infobase.
     */
    public static DeleteResult uninstallExtension(String projectName, String applicationId,
        String extensionName)
    {
        DeleteResult r = new DeleteResult();
        r.extensionName = extensionName;
        LauncherContext ctx = resolveLauncher(projectName, applicationId);
        if (ctx.error != null)
        {
            r.error = ctx.error;
            r.failureKind = ctx.failureKind;
            r.infobaseName = ctx.infobaseName;
            return r;
        }
        r.infobaseName = ctx.infobaseName;
        try
        {
            String name = (extensionName != null && !extensionName.isEmpty()) ? extensionName : null;
            if (ctx.lock != null) ctx.lock.lock();
            try
            {
                boolean disconnected = disconnectForThickClient(ctx);
                try
                {
                    ctx.launcher.deleteConfigurationExtension(ctx.component, ctx.infobase, ctx.args, name);
                    r.ok = true;
                }
                finally
                {
                    if (disconnected) reconnectInfobase(ctx);
                }
            }
            finally
            {
                if (ctx.lock != null) ctx.lock.unlock();
            }
        }
        catch (Throwable e)
        {
            classifyThickClientFailure(e, s -> { r.error = s.error; r.failureKind = s.failureKind; });
        }
        return r;
    }

    /**
     * Converts a binary external data processor or report ({@code .epf} / {@code .erf}) into
     * Designer-XML on disk.
     * <p>
     * This is the step that was missing between "a customer sent one file" and an EDT project:
     * {@code import_configuration_from_xml} already turns Designer-XML into a project, but nothing
     * turned a binary into that XML, so the file had to make a detour through the Designer by hand.
     * </p>
     * <p>
     * The infobase is only a host for the Designer process - the conversion reads the source file
     * and writes the target directory, and does not touch the infobase's own configuration. It is
     * still taken through the same disconnect/reconnect as the other thick-client calls, because
     * the spawned Designer needs the platform lock EDT's agent session holds.
     * </p>
     *
     * @param projectName the project whose runtime and infobase host the Designer
     * @param applicationId the application, or <code>null</code> to take the project's
     * @param sourcePath the {@code .epf} / {@code .erf} to read
     * @param targetPath the directory to write the XML into
     * @return the outcome, with {@code error} set when it did not happen
     */
    public static ExportResult convertExternalToXml(String projectName, String applicationId,
        String sourcePath, String targetPath)
    {
        ExportResult r = new ExportResult();
        r.outputPath = targetPath;

        java.nio.file.Path source;
        java.nio.file.Path target;
        try
        {
            source = java.nio.file.Paths.get(sourcePath);
            target = java.nio.file.Paths.get(targetPath);
        }
        catch (java.nio.file.InvalidPathException e)
        {
            r.error = "sourcePath or targetPath is not a valid file path: " //$NON-NLS-1$
                + oneLine(causeChainText(e));
            r.failureKind = ErrorTags.INVALID_INPUT_PATH.wire();
            return r;
        }
        if (!java.nio.file.Files.isRegularFile(source))
        {
            // Checked before the Designer runs: a missing file otherwise comes back as a runtime
            // failure, which reads as "the platform is broken" rather than "check the path". The tag
            // says input, not output - a caller that repairs the target directory over and over
            // would never find the real problem.
            r.error = "The source file does not exist: " + source; //$NON-NLS-1$
            r.failureKind = ErrorTags.INPUT_MISSING.wire();
            return r;
        }

        LauncherContext ctx = resolveLauncher(projectName, applicationId);
        if (ctx.error != null)
        {
            r.error = ctx.error;
            r.failureKind = ctx.failureKind;
            r.infobaseName = ctx.infobaseName;
            return r;
        }
        r.infobaseName = ctx.infobaseName;

        try
        {
            java.nio.file.Files.createDirectories(target);
        }
        catch (java.io.IOException | RuntimeException e)
        {
            r.error = "Cannot create the target directory " + target + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + oneLine(causeChainText(e));
            r.failureKind = ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
            return r;
        }

        try
        {
            if (ctx.lock != null) ctx.lock.lock();
            try
            {
                boolean disconnected = disconnectForThickClient(ctx);
                try
                {
                    ctx.launcher.convertBinaryExternalToXml(ctx.component, ctx.infobase, ctx.args,
                        source, target);
                }
                finally
                {
                    if (disconnected)
                    {
                        reconnectInfobase(ctx);
                    }
                }
            }
            finally
            {
                if (ctx.lock != null) ctx.lock.unlock();
            }
            r.ok = true;
        }
        catch (Throwable e)
        {
            classifyThickClientFailure(e, s -> { r.error = s.error; r.failureKind = s.failureKind; });
        }
        return r;
    }

    /**
     * Exports a named configuration extension from the project's infobase to a
     * {@code .cfe} file on disk (read-only on the infobase - writes only the
     * output file).
     */
    public static ExportResult exportExtension(String projectName, String applicationId,
        String extensionName, String outputPath)
    {
        ExportResult r = new ExportResult();
        r.extensionName = extensionName;
        r.outputPath = outputPath;
        LauncherContext ctx = resolveLauncher(projectName, applicationId);
        if (ctx.error != null)
        {
            r.error = ctx.error;
            r.failureKind = ctx.failureKind;
            r.infobaseName = ctx.infobaseName;
            return r;
        }
        r.infobaseName = ctx.infobaseName;

        // Resolve and prepare the output path client-side, before the thick-client call,
        // so a bad path or missing directory is reported as such instead of being
        // misclassified as an infobase/runtime failure by classifyThickClientFailure.
        java.nio.file.Path dest;
        try
        {
            dest = java.nio.file.Paths.get(outputPath);
        }
        catch (java.nio.file.InvalidPathException e)
        {
            r.error = "outputPath is not a valid file path: " + oneLine(causeChainText(e)); //$NON-NLS-1$
            r.failureKind = ErrorTags.INVALID_OUTPUT_PATH.wire();
            return r;
        }
        java.nio.file.Path parent = dest.toAbsolutePath().getParent();
        if (parent != null)
        {
            try
            {
                java.nio.file.Files.createDirectories(parent);
            }
            catch (java.io.IOException | RuntimeException e)
            {
                r.error = "Cannot create the output directory " + parent + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + oneLine(causeChainText(e));
                r.failureKind = ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
                return r;
            }
        }

        try
        {
            if (ctx.lock != null) ctx.lock.lock();
            try
            {
                // Mirror installExtension: temporarily disconnect EDT's persistent
                // designer (/AgentMode) session so the spawned thick-client can take
                // the platform config lock. Without this, on a FILE infobase held by
                // EDT's agent the export thick-client blocks on the monopoly and the
                // call hangs until the MCP timeout (row 55). ctx.lock alone does not
                // release that platform lock (live-verified).
                boolean disconnected = disconnectForThickClient(ctx);
                try
                {
                    ctx.launcher.exportCfFromInfobase(ctx.component, ctx.infobase, ctx.args,
                        extensionName, dest);
                }
                finally
                {
                    if (disconnected) reconnectInfobase(ctx);
                }
            }
            finally
            {
                if (ctx.lock != null) ctx.lock.unlock();
            }
        }
        catch (Throwable e)
        {
            classifyThickClientFailure(e, s -> { r.error = s.error; r.failureKind = s.failureKind; });
            return r;
        }
        java.io.File out = new java.io.File(outputPath);
        if (!out.isFile())
        {
            r.error = "The export reported success but no file was written at " //$NON-NLS-1$
                + outputPath + "."; //$NON-NLS-1$
            r.failureKind = ErrorTags.OUTPUT_MISSING.wire();
            return r;
        }
        r.ok = true;
        r.sizeBytes = out.length();
        return r;
    }

    /**
     * Exports the project's infobase MAIN configuration to a {@code .cf} file on disk
     * (read-only on the infobase - writes only the output file). Dumps the infobase's
     * current configuration via the 1C thick client (DESIGNER) - the same path EDT's GUI
     * "Выгрузить файл конфигурации" takes, so a resolvable platform runtime with a
     * ThickClient component and a connected infobase are required.
     * <p>
     * The {@code .cf} holds the INFOBASE's configuration, not the EDT project's directly:
     * run {@code update_database} first to capture the project's latest changes. Run
     * {@code validate_for_export} first to catch export-breakers (e.g. a {@code <help>}
     * page declared in an {@code .mdo} without its {@code Help/<lang>.html} file) that
     * crash the DESIGNER dump.
     *
     * @param projectName the EDT project that owns the infobase
     * @param applicationId the infobase application id (nullable -> the project default)
     * @param outputPath absolute path of the {@code .cf} file to write
     * @return the export result (check {@link ExportResult#ok})
     */
    public static ExportResult exportConfigurationCf(String projectName, String applicationId,
        String outputPath)
    {
        ExportResult r = new ExportResult();
        r.outputPath = outputPath;
        LauncherContext ctx = resolveLauncher(projectName, applicationId);
        if (ctx.error != null)
        {
            r.error = ctx.error;
            r.failureKind = ctx.failureKind;
            r.infobaseName = ctx.infobaseName;
            return r;
        }
        r.infobaseName = ctx.infobaseName;

        // Resolve and prepare the output path client-side, before the thick-client call,
        // so a bad path or missing directory is reported as such instead of being
        // misclassified as an infobase/runtime failure by classifyThickClientFailure.
        java.nio.file.Path dest;
        try
        {
            dest = java.nio.file.Paths.get(outputPath);
        }
        catch (java.nio.file.InvalidPathException e)
        {
            r.error = "outputPath is not a valid file path: " + oneLine(causeChainText(e)); //$NON-NLS-1$
            r.failureKind = ErrorTags.INVALID_OUTPUT_PATH.wire();
            return r;
        }
        java.nio.file.Path parent = dest.toAbsolutePath().getParent();
        if (parent != null)
        {
            try
            {
                java.nio.file.Files.createDirectories(parent);
            }
            catch (java.io.IOException | RuntimeException e)
            {
                r.error = "Cannot create the output directory " + parent + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + oneLine(causeChainText(e));
                r.failureKind = ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
                return r;
            }
        }

        try
        {
            if (ctx.lock != null) ctx.lock.lock();
            try
            {
                // Same disconnect/reconnect as exportExtension: the dump thick-client needs
                // EDT's designer agent off the file infobase or it blocks on the monopoly.
                boolean disconnected = disconnectForThickClient(ctx);
                try
                {
                    // null extension name = the MAIN configuration (not an extension).
                    ctx.launcher.exportCfFromInfobase(ctx.component, ctx.infobase, ctx.args, null, dest);
                }
                finally
                {
                    if (disconnected) reconnectInfobase(ctx);
                }
            }
            finally
            {
                if (ctx.lock != null) ctx.lock.unlock();
            }
        }
        catch (Throwable e)
        {
            classifyThickClientFailure(e, s -> { r.error = s.error; r.failureKind = s.failureKind; });
            return r;
        }
        java.io.File out = new java.io.File(outputPath);
        if (!out.isFile())
        {
            r.error = "The export reported success but no file was written at " //$NON-NLS-1$
                + outputPath + "."; //$NON-NLS-1$
            r.failureKind = ErrorTags.OUTPUT_MISSING.wire();
            return r;
        }
        r.ok = true;
        r.sizeBytes = out.length();
        return r;
    }

    /**
     * Installs a {@code .cfe} file into the project's infobase AS a configuration
     * extension (mutates the infobase), optionally applying it to the database.
     * <p>
     * There is no first-class {@code IThickClientLauncher} verb for this:
     * {@code importCfToInfobase} omits {@code -Extension} and would load the file
     * as the MAIN configuration. So the DESIGNER command
     * ({@code importCfToInfobase(file).forExtension(name)} [+ update DB]) is built
     * and run through the same protected {@code executeRuntimeProcessCommand} that
     * every list / uninstall / export call goes through - it appends the infobase
     * access (stored credentials) and captures the designer log.
     */
    public static InstallResult installExtension(String projectName, String applicationId,
        String extensionName, String inputPath, boolean updateDatabase)
    {
        InstallResult r = new InstallResult();
        r.extensionName = extensionName;
        r.inputPath = inputPath;

        if (inputPath == null || inputPath.trim().isEmpty())
        {
            r.error = "inputPath is required (a .cfe path, an http(s):// URL, or a " //$NON-NLS-1$
                + "github:owner/repo source)."; //$NON-NLS-1$
            r.failureKind = ErrorTags.INVALID_INPUT_PATH.wire();
            return r;
        }

        // Resolve the .cfe source: a local path OR an http(s):// URL (downloaded to a
        // temp file, deleted after install). Resolved client-side first so a bad / missing
        // file (or failed download) is reported clearly, not misclassified as a
        // thick-client / infobase failure. downloadedTemp != null marks a URL download the
        // install block deletes in its finally.
        java.nio.file.Path downloadedTemp = null;
        java.nio.file.Path src;
        String scheme = inputPath == null ? "" //$NON-NLS-1$
            : inputPath.trim().toLowerCase(java.util.Locale.ROOT);

        // GitHub-repo source: "github:owner/repo" / "gh:owner/repo" (optionally
        // "#assetNamePrefix") resolves the LATEST release's matching .cfe asset to a
        // direct download URL, then takes the http path below. Lets a caller install the
        // newest engine release without knowing its tag or asset URL.
        String[] repoSource = GitHubReleaseResolver.parseRepoSource(inputPath);
        if (repoSource != null)
        {
            String resolvedUrl;
            try
            {
                GitHubReleaseResolver.Asset asset =
                    GitHubReleaseResolver.resolveLatestCfe(repoSource[0], repoSource[1]);
                if (asset == null)
                {
                    r.error = "No .cfe asset" //$NON-NLS-1$
                        + (repoSource[1] != null ? " with prefix '" + repoSource[1] + "'" //$NON-NLS-1$ //$NON-NLS-2$
                            : "") //$NON-NLS-1$
                        + " found in the latest release of " + repoSource[0] //$NON-NLS-1$
                        + ". Add '#assetNamePrefix' to disambiguate when a release ships several .cfe."; //$NON-NLS-1$
                    r.failureKind = ErrorTags.GITHUB_ASSET_NOT_FOUND.wire();
                    return r;
                }
                resolvedUrl = asset.url;
                r.inputPath = resolvedUrl;
            }
            catch (Exception ghEx)
            {
                r.error = "Failed to resolve the latest release of " + repoSource[0] //$NON-NLS-1$
                    + " from GitHub: " + oneLine(causeChainText(ghEx)); //$NON-NLS-1$
                r.failureKind = ErrorTags.GITHUB_RESOLVE_FAILED.wire();
                return r;
            }
            java.nio.file.Path tmp = null;
            try
            {
                tmp = java.nio.file.Files.createTempFile("installExt-", ".cfe"); //$NON-NLS-1$ //$NON-NLS-2$
                downloadUrlToFile(resolvedUrl, tmp);
                downloadedTemp = tmp;
                src = tmp;
            }
            catch (Exception dlEx)
            {
                // createTempFile may have succeeded before the download threw - clean up
                // the empty/partial temp file so repeated failures do not litter temp.
                deleteQuietly(tmp);
                r.error = "Failed to download extension from " + resolvedUrl + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + oneLine(causeChainText(dlEx));
                r.failureKind = ErrorTags.INPUT_DOWNLOAD_FAILED.wire();
                return r;
            }
        }
        else if (scheme.startsWith("http://") || scheme.startsWith("https://")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            try
            {
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile("installExt-", ".cfe"); //$NON-NLS-1$ //$NON-NLS-2$
                downloadUrlToFile(inputPath, tmp);
                downloadedTemp = tmp;
                src = tmp;
            }
            catch (Exception dlEx)
            {
                r.error = "Failed to download extension from " + inputPath + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + oneLine(causeChainText(dlEx));
                r.failureKind = ErrorTags.INPUT_DOWNLOAD_FAILED.wire();
                return r;
            }
        }
        else
        {
            try
            {
                src = java.nio.file.Paths.get(inputPath);
            }
            catch (java.nio.file.InvalidPathException e)
            {
                r.error = "inputPath is not a valid file path: " + oneLine(causeChainText(e)); //$NON-NLS-1$
                r.failureKind = ErrorTags.INVALID_INPUT_PATH.wire();
                return r;
            }
            java.io.File cfe = src.toFile();
            if (!cfe.isFile())
            {
                r.error = "The extension file was not found: " + inputPath //$NON-NLS-1$
                    + ". Pass the path to an existing .cfe file or an http(s):// URL."; //$NON-NLS-1$
                r.failureKind = ErrorTags.INPUT_MISSING.wire();
                return r;
            }
        }

        LauncherContext ctx = resolveLauncher(projectName, applicationId);
        if (ctx.error != null)
        {
            // A downloaded temp file (URL/GitHub source) has no install try/finally yet -
            // clean it up here so a launcher/credentials failure does not orphan it.
            deleteQuietly(downloadedTemp);
            r.error = ctx.error;
            r.failureKind = ctx.failureKind;
            r.infobaseName = ctx.infobaseName;
            return r;
        }
        r.infobaseName = ctx.infobaseName;

        try
        {
            java.lang.reflect.Method splitM =
                findMethodUp(ctx.launcher.getClass(), "splitInfobaseConnection"); //$NON-NLS-1$
            java.lang.reflect.Method execM = findMethodUp(ctx.launcher.getClass(),
                "executeRuntimeProcessCommand", RuntimeExecutionCommandBuilder.class, //$NON-NLS-1$
                RuntimeInstallation.class, InfobaseReference.class, RuntimeExecutionArguments.class);
            if (splitM == null || execM == null)
            {
                String missing = execM == null && splitM == null
                    ? "executeRuntimeProcessCommand / splitInfobaseConnection" //$NON-NLS-1$
                    : execM == null ? "executeRuntimeProcessCommand" : "splitInfobaseConnection"; //$NON-NLS-1$ //$NON-NLS-2$
                r.error = "This EDT runtime does not expose the thick-client execution " //$NON-NLS-1$
                    + "internals required to install an extension (" + missing + ")."; //$NON-NLS-1$ //$NON-NLS-2$
                r.failureKind = ErrorTags.INSTALL_API_NOT_FOUND.wire();
                return r;
            }
            boolean split = (Boolean)splitM.invoke(ctx.launcher);
            RuntimeExecutionCommandBuilder command = new RuntimeExecutionCommandBuilder(
                ctx.component.getFile(), RuntimeExecutionCommandBuilder.ThickClientMode.DESIGNER);
            command.forInfobase(ctx.infobase, split).importCfToInfobase(src.toString())
                .forExtension(extensionName);
            if (updateDatabase)
            {
                command.updateDatabaseConfiguration();
            }
            if (ctx.lock != null) ctx.lock.lock();
            try
            {
                boolean disconnected = disconnectForThickClient(ctx);
                try
                {
                    Object out = execM.invoke(ctx.launcher, command,
                        ctx.component.getInstallation(), ctx.infobase, ctx.args);
                    // Defense in depth: strip the infobase password out of the designer log
                    // before it is ever returned, even though the /Out transcript is not the
                    // channel that carries the /P argv token.
                    r.designerLog = trimLog(redactSecret(out == null ? null : (String)out,
                        ctx.args.getPassword()));
                }
                finally
                {
                    if (disconnected) reconnectInfobase(ctx);
                }
            }
            finally
            {
                if (ctx.lock != null) ctx.lock.unlock();
            }
        }
        catch (java.lang.reflect.InvocationTargetException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            classifyThickClientFailure(cause, s -> { r.error = s.error; r.failureKind = s.failureKind; });
            return r;
        }
        catch (Throwable e)
        {
            classifyThickClientFailure(e, s -> { r.error = s.error; r.failureKind = s.failureKind; });
            return r;
        }
        finally
        {
            if (downloadedTemp != null)
            {
                try
                {
                    java.nio.file.Files.deleteIfExists(downloadedTemp);
                }
                catch (Exception ignored)
                {
                    // best-effort cleanup of the downloaded temp .cfe
                }
            }
        }

        r.ok = true;
        r.databaseUpdated = updateDatabase;
        return r;
    }

    /**
     * Downloads {@code url} into {@code dest} via the JDK HTTP client (no external deps), following
     * redirects. Throws on transport failure or an HTTP 4xx/5xx status.
     */
    private static void downloadUrlToFile(String url, java.nio.file.Path dest) throws Exception
    {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .timeout(java.time.Duration.ofSeconds(120))
            .GET()
            .build();
        java.net.http.HttpResponse<java.nio.file.Path> response =
            client.send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(dest));
        if (response.statusCode() >= 400)
        {
            try
            {
                java.nio.file.Files.deleteIfExists(dest);
            }
            catch (Exception ignored)
            {
                // partial body written to temp; best-effort delete
            }
            throw new RuntimeException("HTTP " + response.statusCode() + " for " + url); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Best-effort delete of a path (typically a downloaded temp file); swallows errors. */
    private static void deleteQuietly(java.nio.file.Path path)
    {
        if (path == null)
        {
            return;
        }
        try
        {
            java.nio.file.Files.deleteIfExists(path);
        }
        catch (Exception ignored)
        {
            // best-effort cleanup
        }
    }

    /** Finds a (possibly protected / inherited) method by walking up the class hierarchy. */
    private static java.lang.reflect.Method findMethodUp(Class<?> start, String name,
        Class<?>... params)
    {
        for (Class<?> k = start; k != null; k = k.getSuperclass())
        {
            try
            {
                java.lang.reflect.Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            }
            catch (NoSuchMethodException ignore)
            {
                // not declared here; walk up to the superclass
            }
        }
        return null;
    }

    private static String trimLog(String s)
    {
        if (s == null)
        {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty())
        {
            return null;
        }
        final int max = 4000;
        return t.length() > max ? t.substring(0, max) + " ...[truncated]" : t; //$NON-NLS-1$
    }

    /** Removes the literal password value from a string before it is returned/logged. */
    private static String redactSecret(String s, String secret)
    {
        if (s == null || secret == null || secret.isEmpty())
        {
            return s;
        }
        return s.replace(secret, "***"); //$NON-NLS-1$
    }

    // --- shared resolution ---

    /**
     * Temporarily disconnects EDT's agent session for the infobase so a spawned
     * thick-client can take the platform config lock that EDT otherwise holds
     * continuously via its persistent {@code /AgentMode} designer session.
     * {@code IInfobaseManager.getLock} does NOT release that platform lock (live-verified); only
     * tearing the agent session down does.
     * <p>
     * Returns {@code true} when the infobase WAS connected before this call - the caller then MUST
     * call {@link #reconnectInfobase} to restore that connection. Returns {@code false} when it was
     * already disconnected (a deliberate user state for a read-only export/list), so the caller must
     * NOT reconnect - reconnecting an infobase the user had torn down would silently alter connection
     * state for a nominally read-only operation. {@code disconnectInfobase} on an already-disconnected
     * infobase is a successful no-op, so it cannot tell the two apart on its own.
     * </p>
     */
    private static boolean disconnectForThickClient(LauncherContext ctx)
    {
        IInfobaseSynchronizationManager mgr = ServiceAccess.get(IInfobaseSynchronizationManager.class);
        if (mgr == null || ctx.project == null)
        {
            return false;
        }
        boolean wasConnected = mgr.isConnected(ctx.project, ctx.infobase);
        try
        {
            mgr.disconnectInfobase(ctx.project, ctx.infobase, false, true, new NullProgressMonitor());
            return wasConnected;
        }
        catch (Throwable e)
        {
            Activator.logWarning("disconnectInfobase failed; running thick-client without it: " //$NON-NLS-1$
                + oneLine(causeChainText(e)));
            return false;
        }
    }

    private static void reconnectInfobase(LauncherContext ctx)
    {
        IInfobaseSynchronizationManager mgr = ServiceAccess.get(IInfobaseSynchronizationManager.class);
        if (mgr == null || ctx.project == null)
        {
            return;
        }
        try
        {
            mgr.connectInfobase(ctx.project, ctx.infobase, new NullProgressMonitor());
        }
        catch (Throwable e)
        {
            Activator.logWarning("connectInfobase failed; the infobase may show as " //$NON-NLS-1$
                + "disconnected in EDT - reconnect it manually: " + oneLine(causeChainText(e))); //$NON-NLS-1$
        }
    }

    private static final class LauncherContext
    {
        IThickClientLauncher launcher;
        ILaunchableRuntimeComponent component;
        InfobaseReference infobase;
        IProject project;
        Lock lock;
        RuntimeExecutionArguments args;
        String infobaseName;
        String error;
        String failureKind;
    }

    /** Resolves the ThickClient launcher + component + execution args for the IB. */
    private static LauncherContext resolveLauncher(String projectName, String applicationId)
    {
        LauncherContext ctx = new LauncherContext();
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            ctx.error = ProjectResolver.describeNotFound(projectName);
            ctx.failureKind = ErrorTags.PROJECT_NOT_FOUND.wire();
            return ctx;
        }
        InfobaseReference infobase = resolveInfobase(project, applicationId, ctx);
        if (infobase == null)
        {
            return ctx; // ctx.error set
        }
        ctx.infobase = infobase;
        ctx.infobaseName = infobase.getName();
        ctx.project = project;

        Activator a = Activator.getDefault();
        IResolvableRuntimeInstallationManager riMgr =
            a != null ? a.getResolvableRuntimeInstallationManager() : null;
        IRuntimeComponentManager compMgr = a != null ? a.getRuntimeComponentManager() : null;
        IInfobaseAccessManager accessMgr = a != null ? a.getInfobaseAccessManager() : null;
        IInfobaseManager infobaseManager = a != null ? a.getInfobaseManager() : null;
        if (riMgr == null || compMgr == null || accessMgr == null || infobaseManager == null)
        {
            ctx.error = "Runtime / infobase managers (incl. the per-infobase lock service) " //$NON-NLS-1$
                + "are not available on this EDT runtime."; //$NON-NLS-1$
            ctx.failureKind = ErrorTags.MANAGER_UNAVAILABLE.wire();
            return ctx;
        }
        // EDT holds the connected infobase through a persistent designer session, so a
        // spawned DESIGNER batch cannot take the platform config lock on its own. EDT's
        // own UI (ExportConfigurationFileService) wraps every thick-client IB call in
        // IInfobaseManager.getLock(infobase) to coordinate with that session; mirror it
        // here. Callers still null-guard ctx.lock for the rare getLock()-returns-null case.
        ctx.lock = infobaseManager.getLock(infobase);

        // The credential read below touches encrypted secure storage; prime it so
        // the master-password dialog is not posted on this background thread.
        String primeErr = BmInfobaseCredentialsHelper.primeSecureStorage();
        if (primeErr != null)
        {
            ctx.error = primeErr;
            ctx.failureKind = ErrorTags.STORAGE_LOCKED.wire();
            return ctx;
        }

        try
        {
            IResolvableRuntimeInstallation resolvable = riMgr.resolveByProjectAndInfobase(
                RUNTIME_TYPE_ENTERPRISE, project, infobase, InfobaseAccessType.UPDATE);
            RuntimeInstallation installation = resolvable.resolve(
                Collections.singletonList(IRuntimeComponentTypes.THICK_CLIENT), infobase.getAppArch());
            ComponentExecutorInfo<ILaunchableRuntimeComponent, IThickClientLauncher> info =
                compMgr.resolveExecutor(ILaunchableRuntimeComponent.class, IThickClientLauncher.class,
                    installation, IRuntimeComponentTypes.THICK_CLIENT);
            ctx.launcher = info.getExecutor();
            ctx.component = info.getComponent();
        }
        catch (Throwable e)
        {
            classifyThickClientFailure(e, s -> { ctx.error = s.error; ctx.failureKind = s.failureKind; });
            return ctx;
        }

        RuntimeExecutionArguments args = new RuntimeExecutionArguments();
        try
        {
            IInfobaseAccessSettings s = accessMgr.resolveSettings(infobase);
            if (s != null)
            {
                args.setAccess(s.access());
                args.setUsername(emptyToNull(s.userName()));
                args.setPassword(emptyToNull(s.password()));
            }
        }
        catch (Throwable e)
        {
            Activator.logWarning("extension mgmt: resolveSettings failed (proceeding without " //$NON-NLS-1$
                + "credentials): " + msg(e)); //$NON-NLS-1$
        }
        ctx.args = args;
        return ctx;
    }

    private static InfobaseReference resolveInfobase(IProject project, String applicationId,
        LauncherContext ctx)
    {
        IApplicationManager appMgr = Activator.getDefault() != null
            ? Activator.getDefault().getApplicationManager() : null;
        if (appMgr == null)
        {
            ctx.error = "IApplicationManager is not available on this EDT runtime."; //$NON-NLS-1$
            ctx.failureKind = ErrorTags.MANAGER_UNAVAILABLE.wire();
            return null;
        }
        try
        {
            IApplication app;
            if (applicationId != null && !applicationId.isEmpty())
            {
                app = appMgr.getApplication(project, applicationId).orElse(null);
                if (app == null)
                {
                    ctx.error = "No application '" + applicationId + "' in project '" //$NON-NLS-1$ //$NON-NLS-2$
                        + project.getName() + "'. Use get_applications to list ids."; //$NON-NLS-1$
                    ctx.failureKind = ErrorTags.RESOLVE_FAILED.wire();
                    return null;
                }
            }
            else
            {
                List<IApplication> apps = appMgr.getApplications(project);
                if (apps == null || apps.isEmpty())
                {
                    ctx.error = "Project '" + project.getName() + "' has no infobase application."; //$NON-NLS-1$ //$NON-NLS-2$
                    ctx.failureKind = ErrorTags.RESOLVE_FAILED.wire();
                    return null;
                }
                if (apps.size() > 1)
                {
                    ctx.error = "Project '" + project.getName() + "' has multiple applications; " //$NON-NLS-1$ //$NON-NLS-2$
                        + "pass applicationId (see get_applications)."; //$NON-NLS-1$
                    ctx.failureKind = ErrorTags.RESOLVE_FAILED.wire();
                    return null;
                }
                app = apps.get(0);
            }
            if (!(app instanceof IInfobaseApplication))
            {
                ctx.error = "Application is not an infobase application; extension management " //$NON-NLS-1$
                    + "applies only to infobases."; //$NON-NLS-1$
                ctx.failureKind = ErrorTags.NOT_INFOBASE.wire();
                return null;
            }
            InfobaseReference ib = ((IInfobaseApplication) app).getInfobase();
            if (ib == null)
            {
                ctx.error = "The infobase application has no infobase reference."; //$NON-NLS-1$
                ctx.failureKind = ErrorTags.RESOLVE_FAILED.wire();
                return null;
            }
            return ib;
        }
        catch (Exception e)
        {
            ctx.error = "Failed to resolve the infobase: " + msg(e); //$NON-NLS-1$
            ctx.failureKind = ErrorTags.RESOLVE_FAILED.wire();
            return null;
        }
    }

    // --- failure classification ---

    private static final class Classified
    {
        String error;
        String failureKind;
    }

    private interface Sink
    {
        void accept(Classified c);
    }

    private static void classifyThickClientFailure(Throwable cause, Sink sink)
    {
        Classified c = new Classified();
        String chain = causeChainText(cause);
        String lower = chain.toLowerCase(Locale.ROOT);
        if (lower.contains("runtimeversionrequired")) //$NON-NLS-1$
        {
            // The .cfe / operation requires a platform version that the infobase's
            // associated runtime does not match. Public launcher verbs retry on a
            // fallback installation (findFallbackClient); the direct install path
            // cannot, so surface this distinctly instead of as a generic failure.
            c.failureKind = ErrorTags.PLATFORM_VERSION_MISMATCH.wire();
            c.error = "This operation requires a 1C:Enterprise platform version that does not " //$NON-NLS-1$
                + "match the infobase's associated runtime. Associate/install the required " //$NON-NLS-1$
                + "platform version for this infobase, then retry. Underlying: " //$NON-NLS-1$
                + oneLine(chain);
        }
        else if (lower.contains("matchingruntimenotfound") //$NON-NLS-1$
            || (lower.contains("runtime") && (lower.contains("not found") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("no matching") || lower.contains("cannot be resolved")))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            c.failureKind = ErrorTags.RUNTIME_NOT_FOUND.wire();
            c.error = "No resolvable 1C:Enterprise platform runtime (with a thick client) for " //$NON-NLS-1$
                + "this infobase. Install/associate a matching platform version. Underlying: " //$NON-NLS-1$
                + oneLine(chain);
        }
        else if (lower.contains("authentication") || lower.contains("noaccessright") //$NON-NLS-1$ //$NON-NLS-2$
            || lower.contains("access right") || lower.contains("no access") //$NON-NLS-1$ //$NON-NLS-2$
            || lower.contains("аутентификаци") || lower.contains("недостаточно прав") //$NON-NLS-1$ //$NON-NLS-2$
            || lower.contains("прав доступа")) //$NON-NLS-1$
        {
            c.failureKind = ErrorTags.AUTH_FAILED.wire();
            c.error = "The infobase rejected the stored credentials. Set the correct user / " //$NON-NLS-1$
                + "password with set_infobase_credentials, then retry. Underlying: " //$NON-NLS-1$
                + oneLine(chain);
        }
        else
        {
            c.failureKind = ErrorTags.THICK_CLIENT_FAILED.wire();
            c.error = "The thick-client operation failed (the infobase may be locked by a " //$NON-NLS-1$
                + "running 1C client, unreachable, or the extension was not found): " //$NON-NLS-1$
                + oneLine(chain);
        }
        sink.accept(c);
    }

    private static String causeChainText(Throwable t)
    {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        Throwable c = t;
        while (c != null && depth < 8)
        {
            if (sb.length() > 0)
            {
                sb.append(" | "); //$NON-NLS-1$
            }
            sb.append(c.getClass().getSimpleName());
            if (c.getMessage() != null)
            {
                sb.append(": ").append(c.getMessage()); //$NON-NLS-1$
            }
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static String oneLine(String s)
    {
        if (s == null)
        {
            return ""; //$NON-NLS-1$
        }
        // Flatten to a single line, preserving the whole cause chain (joined by
        // " | ") rather than truncating at the first embedded newline.
        String line = s.replace('\n', ' ').replace('\r', ' ');
        return line.length() > 500 ? line.substring(0, 500) + "..." : line; //$NON-NLS-1$
    }

    private static String emptyToNull(String s)
    {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static String msg(Throwable e)
    {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
