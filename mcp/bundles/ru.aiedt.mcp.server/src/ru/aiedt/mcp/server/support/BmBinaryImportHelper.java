/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
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

import ru.aiedt.mcp.server.Activator;

/**
 * Turns a {@code .cf} or {@code .cfe} into the Designer-XML an EDT project can be imported from.
 * <p>
 * EDT imports projects from XML and only from XML - its own import API has no reading of the
 * binary formats at all. The platform can read them, but only into an infobase. So the route runs
 * through one: a throwaway infobase is created, the binary is loaded into it, its XML is dumped
 * back out, and the infobase is deleted. What the caller gets is a directory the ordinary XML
 * import accepts.
 * </p>
 * <p>
 * The staging infobase belongs to nobody: it is created here, named so it is recognisable as ours,
 * and removed in a finally. That is what makes this safe to run against a workspace that is being
 * used - unlike loading into a project's own infobase, which would overwrite the configuration
 * somebody is working on. It also means the monopoly dance the other thick-client operations do
 * (disconnect the EDT designer session, reconnect after) is not needed here: no EDT session ever
 * holds this infobase.
 * </p>
 */
public final class BmBinaryImportHelper
{
    /** Runtime family the thick client is resolved from - the same id the other callers use. */
    private static final String RUNTIME_TYPE_ENTERPRISE =
        "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform"; //$NON-NLS-1$

    /** Prefix of every staging infobase, so an orphan is recognisable as ours. */
    private static final String STAGING_PREFIX = "aiedt-staging-"; //$NON-NLS-1$

    /** What a binary can be. */
    public enum BinaryKind
    {
        /** A whole configuration - {@code .cf}. */
        CONFIGURATION,
        /** A configuration extension - {@code .cfe}. */
        EXTENSION
    }

    /** What {@link #toXml} produced, including why it produced nothing. */
    public static final class XmlResult
    {
        /** Whether the XML is there. */
        public boolean ok;

        /** Why it is not, or <code>null</code> when it is. */
        public String error;

        /** The classification tag for {@link #error}, or <code>null</code>. */
        public String failureKind;

        /** The staging infobase's name, for a message about an orphan left behind. */
        public String stagingInfobaseName;

        /**
         * Whether the staging infobase was ever created.
         * <p>
         * Told apart from {@link #stagingRemoved} because a name is picked before anything exists.
         * Without the distinction a failure to CREATE one reads as a failure to clean one up, and
         * the caller is sent looking for an infobase that was never there.
         * </p>
         */
        public boolean stagingCreated;

        /** Whether the staging infobase was removed again. */
        public boolean stagingRemoved;

        /** What the Designer said, trimmed; <code>null</code> when it said nothing. */
        public String designerLog;

        /** The extension's name as it was loaded, for an extension. */
        public String extensionName;
    }

    private BmBinaryImportHelper()
    {
        // utility
    }

    /**
     * Tells a binary's kind from its name.
     *
     * @param binary the file
     * @return the kind, or <code>null</code> when the name says neither
     */
    public static BinaryKind kindOf(Path binary)
    {
        String name = binary.getFileName() == null ? "" //$NON-NLS-1$
            : binary.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".cf")) //$NON-NLS-1$
        {
            return BinaryKind.CONFIGURATION;
        }
        if (name.endsWith(".cfe")) //$NON-NLS-1$
        {
            return BinaryKind.EXTENSION;
        }
        return null;
    }

    /**
     * The name to load an extension under when the caller did not pick one.
     *
     * @param binary the {@code .cfe} file
     * @return the file's base name with everything the platform would refuse removed
     */
    public static String extensionNameFrom(Path binary)
    {
        String name = binary.getFileName() == null ? "" : binary.getFileName().toString(); //$NON-NLS-1$
        int dot = name.lastIndexOf('.');
        if (dot >= 0)
        {
            // A file called just ".cfe" has no base name at all; taking the extension as the name
            // would call the extension "cfe", which says nothing about what it is.
            name = name.substring(0, dot);
        }
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '_';
            cleaned.append(allowed ? c : '_');
        }
        if (cleaned.length() == 0 || Character.isDigit(cleaned.charAt(0)))
        {
            // A platform name may not start with a digit, and an empty one is not a name at all.
            cleaned.insert(0, "Ext_"); //$NON-NLS-1$
        }
        return cleaned.toString();
    }

    /**
     * Dumps a binary's Designer-XML into a directory, through a staging infobase.
     *
     * @param binary the {@code .cf} or {@code .cfe} to read
     * @param platform the platform version for the staging infobase, or <code>null</code> for the
     *            newest installed
     * @param extensionName the name to load an extension under; ignored for a configuration
     * @param baseConfiguration a {@code .cf} to seed the staging infobase with before an extension
     *            is applied, or <code>null</code> for an empty one
     * @param xmlTarget the directory to write the XML into; must be empty or absent
     * @return the outcome; check {@link XmlResult#ok}
     */
    public static XmlResult toXml(Path binary, String platform, String extensionName,
        Path baseConfiguration, Path xmlTarget)
    {
        XmlResult r = new XmlResult();
        BinaryKind kind = kindOf(binary);
        if (kind == null)
        {
            r.error = "Expected a .cf or a .cfe, got: " + binary; //$NON-NLS-1$
            r.failureKind = ErrorTags.INVALID_INPUT_PATH.wire();
            return r;
        }
        if (!Files.isRegularFile(binary))
        {
            r.error = "The file does not exist: " + binary; //$NON-NLS-1$
            r.failureKind = ErrorTags.INPUT_MISSING.wire();
            return r;
        }
        if (kind == BinaryKind.EXTENSION)
        {
            r.extensionName = extensionName == null || extensionName.isBlank()
                ? extensionNameFrom(binary) : extensionName.trim();
        }

        Path stagingDir;
        try
        {
            stagingDir = Files.createTempDirectory("aiedt-staging-"); //$NON-NLS-1$
            Files.createDirectories(xmlTarget);
        }
        catch (IOException | RuntimeException e)
        {
            r.error = "Cannot prepare the staging directories: " + oneLine(e); //$NON-NLS-1$
            r.failureKind = ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
            return r;
        }

        String infobaseName = STAGING_PREFIX + System.currentTimeMillis();
        r.stagingInfobaseName = infobaseName;
        boolean created = false;
        try
        {
            // A .cf goes straight in as the infobase's configuration - the platform does the
            // loading as part of creating it, so there is no second Designer run for that case.
            String seed = kind == BinaryKind.CONFIGURATION ? binary.toString()
                : baseConfiguration == null ? null : baseConfiguration.toString();
            BmInfobaseLifecycleHelper.CreateResult create = BmInfobaseLifecycleHelper.createInfobase(
                infobaseName, stagingDir.resolve("ib").toString(), platform, seed, null); //$NON-NLS-1$
            if (!create.ok)
            {
                r.error = "The staging infobase could not be created: " + create.error //$NON-NLS-1$
                    + ". The platform used was " //$NON-NLS-1$
                    + (platform == null || platform.isBlank() ? "the newest installed" : platform) //$NON-NLS-1$
                    + " - a configuration built for an older platform is refused by a newer one, " //$NON-NLS-1$
                    + "so name the version the file was built for in the platform parameter."; //$NON-NLS-1$
                r.failureKind = create.failureKind;
                return r;
            }
            created = true;
            r.stagingCreated = true;

            Staging staging = resolveStaging(infobaseName, platform);
            if (staging.error != null)
            {
                r.error = staging.error;
                r.failureKind = staging.failureKind;
                return r;
            }

            if (kind == BinaryKind.EXTENSION)
            {
                String loaded = run(staging,
                    command -> command.importCfToInfobase(binary.toString())
                        .forExtension(r.extensionName));
                if (staging.failure != null)
                {
                    r.error = "Loading the extension into the staging infobase failed: " //$NON-NLS-1$
                        + staging.failure;
                    r.failureKind = staging.failureKind;
                    r.designerLog = loaded;
                    return r;
                }
                r.designerLog = loaded;
            }

            String dumped = run(staging, command -> {
                command.exportXmlFromInfobase(xmlTarget);
                if (kind == BinaryKind.EXTENSION)
                {
                    command.forExtension(r.extensionName);
                }
                return command;
            });
            if (staging.failure != null)
            {
                r.error = "Dumping the XML out of the staging infobase failed: " + staging.failure; //$NON-NLS-1$
                r.failureKind = staging.failureKind;
                r.designerLog = dumped != null ? dumped : r.designerLog;
                return r;
            }
            if (dumped != null)
            {
                r.designerLog = dumped;
            }

            // The Designer is a separate process and a run that writes nothing returns just as
            // quietly as one that works. The directory was empty going in, so what is here now is
            // this dump's own output - and if there is none, saying so beats handing the caller an
            // empty directory to import.
            if (isEmpty(xmlTarget))
            {
                r.error = "The dump reported no error but wrote nothing to " + xmlTarget //$NON-NLS-1$
                    + ". The file was loaded into a staging infobase and produced no XML, which " //$NON-NLS-1$
                    + "usually means it is not a configuration or extension this platform " //$NON-NLS-1$
                    + "version can read."; //$NON-NLS-1$
                r.failureKind = ErrorTags.OUTPUT_MISSING.wire();
                return r;
            }
            r.ok = true;
            return r;
        }
        catch (Throwable e)
        {
            r.error = "Staging the binary failed: " + oneLine(e); //$NON-NLS-1$
            return r;
        }
        finally
        {
            if (created)
            {
                r.stagingRemoved = removeStaging(infobaseName);
            }
            deleteTree(stagingDir);
        }
    }

    /**
     * What the platform said when the extension was put in front of it.
     * <p>
     * {@link #applies} is deliberately three-valued. A run that could not be made at all is not a
     * verdict, and reporting it as <code>false</code> would read as "the platform refused this
     * extension" when the truth is that the platform never saw it.
     * </p>
     */
    public static final class Verdict
    {
        /** Whether a verdict was reached; <code>false</code> means {@link #error} says why not. */
        public boolean ok;

        /** The verdict, or <code>null</code> when the run could not be made. */
        public Boolean applies;

        /** What the platform said, in its own words. Never paraphrased. */
        public String platformSaid;

        /** Why no verdict was reached. */
        public String error;

        /** Wire tag for {@link #error}. */
        public String failureKind;

        /** The name the extension was loaded under. */
        public String extensionName;

        /**
         * Where a refusal happened: {@code load} or {@code applicability}.
         * <p>
         * <b>Loading is not the verdict, and measuring it as one got the answer wrong.</b> The
         * platform takes an extension file into the configuration store without asking whether it
         * fits: a delivery with the borrowed catalogue deleted still answered "Загрузка
         * конфигурации успешно завершена". Applicability is a question of its own, and the platform
         * has a command for asking it.
         * </p>
         */
        public String refusedAt;

        /** The staging infobase's name, so a leftover can be found by name. */
        public String stagingInfobaseName;

        /** Whether the staging infobase was created. */
        public boolean stagingCreated;

        /** Whether it is gone again. Reported rather than assumed. */
        public boolean stagingRemoved;
    }

    /**
     * Puts an extension in front of the platform, against a delivery it has never met, and reports
     * what the platform said.
     * <p>
     * <b>Why a staging infobase rather than the working one.</b> The verdict wanted here is the
     * platform's, and the only way to get it is to have the platform load the extension. Doing that
     * against the working infobase would mean loading an extension nobody has decided to keep -
     * and taking the configuration lock away from the open EDT session to do it. The staging
     * infobase is created for this run, holds the delivery and nothing else, and is removed
     * afterwards; the working infobase is not opened, locked or altered.
     * </p>
     * <p>
     * <b>What separates a refusal from a broken run.</b> Creating the infobase and seeding it with
     * the delivery happen before the extension is touched. A failure there is environmental and is
     * reported as no verdict. Everything after it is an answer about the extension - handed back in
     * the platform's own words, because a paraphrase of a refusal is a second-hand refusal.
     * </p>
     * <p>
     * <b>The extension going in is not the verdict.</b> Measured: against a delivery with the
     * borrowed catalogue deleted, loading the extension still answered "Загрузка конфигурации
     * успешно завершена". The platform takes the file into the configuration store and checks
     * whether it fits only when asked - which is what
     * {@code checkConfigurationExtensionsApplicability} asks, and what {@link Verdict#refusedAt}
     * distinguishes from a file that would not load at all.
     * </p>
     *
     * @param configuration the delivery, as a .cf.
     * @param extension the extension, as a .cfe.
     * @param platform the platform version to run, or <code>null</code> for the newest installed.
     * @param extensionName the name to load it under, or <code>null</code> to read it off the file.
     * @return the verdict, or the reason there is none
     */
    public static Verdict verdict(Path configuration, Path extension, String platform,
        String extensionName)
    {
        Verdict v = new Verdict();
        if (kindOf(configuration) != BinaryKind.CONFIGURATION)
        {
            v.error = "Expected a .cf holding the delivery, got: " + configuration; //$NON-NLS-1$
            v.failureKind = ErrorTags.INVALID_INPUT_PATH.wire();
            return v;
        }
        if (kindOf(extension) != BinaryKind.EXTENSION)
        {
            v.error = "Expected a .cfe holding the extension, got: " + extension; //$NON-NLS-1$
            v.failureKind = ErrorTags.INVALID_INPUT_PATH.wire();
            return v;
        }
        for (Path file : new Path[] { configuration, extension })
        {
            if (!Files.isRegularFile(file))
            {
                v.error = "The file does not exist: " + file; //$NON-NLS-1$
                v.failureKind = ErrorTags.INPUT_MISSING.wire();
                return v;
            }
        }
        v.extensionName = extensionName == null || extensionName.isBlank()
            ? extensionNameFrom(extension) : extensionName.trim();

        Path stagingDir;
        try
        {
            stagingDir = Files.createTempDirectory(STAGING_PREFIX);
        }
        catch (IOException | RuntimeException e)
        {
            v.error = "Cannot prepare the staging directory: " + oneLine(e); //$NON-NLS-1$
            v.failureKind = ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
            return v;
        }

        String infobaseName = STAGING_PREFIX + System.currentTimeMillis();
        v.stagingInfobaseName = infobaseName;
        boolean created = false;
        try
        {
            BmInfobaseLifecycleHelper.CreateResult create = BmInfobaseLifecycleHelper.createInfobase(
                infobaseName, stagingDir.resolve("ib").toString(), platform, //$NON-NLS-1$
                configuration.toString(), null);
            if (!create.ok)
            {
                v.error = "The delivery could not be loaded into a staging infobase, so the " //$NON-NLS-1$
                    + "extension was never put to the platform: " + create.error //$NON-NLS-1$
                    + ". The platform used was " //$NON-NLS-1$
                    + (platform == null || platform.isBlank() ? "the newest installed" : platform) //$NON-NLS-1$
                    + "."; //$NON-NLS-1$
                v.failureKind = create.failureKind;
                return v;
            }
            created = true;
            v.stagingCreated = true;

            Staging staging = resolveStaging(infobaseName, platform);
            if (staging.error != null)
            {
                v.error = staging.error;
                v.failureKind = staging.failureKind;
                return v;
            }

            String loaded = run(staging, command -> command.importCfToInfobase(extension.toString())
                .forExtension(v.extensionName));
            v.ok = true;
            if (staging.failure != null)
            {
                // The file would not go in at all. That is an answer about the extension - the
                // delivery was already loaded - but a different one from "it does not fit", and
                // saying which is what lets the reader tell a broken file from a mismatched one.
                v.applies = Boolean.FALSE;
                v.refusedAt = "load"; //$NON-NLS-1$
                v.platformSaid = join(staging.failure, loaded);
                return v;
            }

            String checked =
                run(staging, command -> command.checkConfigurationExtensionsApplicability(
                    v.extensionName));
            v.applies = staging.failure == null;
            if (staging.failure != null)
            {
                v.refusedAt = "applicability"; //$NON-NLS-1$
                v.platformSaid = join(staging.failure, checked);
            }
            else
            {
                v.platformSaid = emptyToNull(checked) != null ? checked : emptyToNull(loaded);
            }
            return v;
        }
        catch (Throwable e)
        {
            v.error = "Putting the extension to the platform failed: " + oneLine(e); //$NON-NLS-1$
            return v;
        }
        finally
        {
            if (created)
            {
                v.stagingRemoved = removeStaging(infobaseName);
            }
            deleteTree(stagingDir);
        }
    }

    /**
     * Joins the failure and the transcript, keeping whichever of them there is.
     *
     * @param failure what the run threw.
     * @param transcript what it printed, possibly nothing.
     * @return both, or the one that exists
     */
    private static String join(String failure, String transcript)
    {
        String tail = emptyToNull(transcript);
        return tail == null ? failure : failure + System.lineSeparator() + tail;
    }

    /** Everything one Designer run needs, or the reason there is none. */
    private static final class Staging
    {
        private InfobaseReference infobase;

        private ILaunchableRuntimeComponent component;

        private IThickClientLauncher launcher;

        private RuntimeExecutionArguments args;

        private Method execute;

        private boolean split;

        private String error;

        private String failureKind;

        /** Set by {@link #run} when a Designer run threw. */
        private String failure;
    }

    /** Shapes one Designer command. */
    private interface CommandShape
    {
        RuntimeExecutionCommandBuilder apply(RuntimeExecutionCommandBuilder command);
    }

    /**
     * Resolves the thick client for a staging infobase, which has no project behind it.
     *
     * @param infobaseName the staging infobase's name
     * @param platform the platform version asked for, or <code>null</code> for the newest
     * @return the context, or one carrying {@link Staging#error}
     */
    private static Staging resolveStaging(String infobaseName, String platform)
    {
        Staging s = new Staging();
        Activator a = Activator.getDefault();
        IInfobaseManager infobases = a == null ? null : a.getInfobaseManager();
        IResolvableRuntimeInstallationManager runtimes =
            a == null ? null : a.getResolvableRuntimeInstallationManager();
        IRuntimeComponentManager components = a == null ? null : a.getRuntimeComponentManager();
        IInfobaseAccessManager access = a == null ? null : a.getInfobaseAccessManager();
        if (infobases == null || runtimes == null || components == null)
        {
            s.error = "Runtime and infobase managers are not available on this EDT runtime."; //$NON-NLS-1$
            s.failureKind = ErrorTags.MANAGER_UNAVAILABLE.wire();
            return s;
        }
        Optional<InfobaseReference> ref = infobases.findInfobaseByName(infobaseName);
        if (!ref.isPresent())
        {
            s.error = "The staging infobase '" + infobaseName + "' was created but is not in " //$NON-NLS-1$ //$NON-NLS-2$
                + "EDT's infobase list."; //$NON-NLS-1$
            s.failureKind = ErrorTags.INFOBASE_NOT_FOUND.wire();
            return s;
        }
        s.infobase = ref.get();

        try
        {
            // Resolved by version rather than by project: this infobase deliberately belongs to no
            // project, which is the whole reason it is safe to load a stranger's binary into it.
            IResolvableRuntimeInstallation resolvable = platform == null || platform.isBlank()
                ? runtimes.resolveLatest(RUNTIME_TYPE_ENTERPRISE)
                : runtimes.resolveByVersionOrMask(RUNTIME_TYPE_ENTERPRISE, platform);
            RuntimeInstallation installation = resolvable.resolve(
                Collections.singletonList(IRuntimeComponentTypes.THICK_CLIENT),
                s.infobase.getAppArch());
            ComponentExecutorInfo<ILaunchableRuntimeComponent, IThickClientLauncher> info =
                components.resolveExecutor(ILaunchableRuntimeComponent.class,
                    IThickClientLauncher.class, installation, IRuntimeComponentTypes.THICK_CLIENT);
            s.launcher = info.getExecutor();
            s.component = info.getComponent();
        }
        catch (Throwable e)
        {
            s.error = "No 1C:Enterprise thick client could be resolved" //$NON-NLS-1$
                + (platform == null || platform.isBlank() ? "" : " for platform " + platform) //$NON-NLS-1$ //$NON-NLS-2$
                + ": " + oneLine(e); //$NON-NLS-1$
            s.failureKind = ErrorTags.RUNTIME_NOT_FOUND.wire();
            return s;
        }

        Method splitMethod = findMethod(s.launcher.getClass(), "splitInfobaseConnection"); //$NON-NLS-1$
        s.execute = findMethod(s.launcher.getClass(), "executeRuntimeProcessCommand", //$NON-NLS-1$
            RuntimeExecutionCommandBuilder.class, RuntimeInstallation.class, InfobaseReference.class,
            RuntimeExecutionArguments.class);
        if (splitMethod == null || s.execute == null)
        {
            s.error = "This EDT runtime does not expose the thick-client execution internals " //$NON-NLS-1$
                + "this import needs (executeRuntimeProcessCommand / splitInfobaseConnection)."; //$NON-NLS-1$
            s.failureKind = ErrorTags.INSTALL_API_NOT_FOUND.wire();
            return s;
        }
        try
        {
            s.split = (Boolean)splitMethod.invoke(s.launcher);
        }
        catch (ReflectiveOperationException e)
        {
            s.error = "The thick-client launcher refused to say how it addresses an infobase: " //$NON-NLS-1$
                + oneLine(e);
            s.failureKind = ErrorTags.INSTALL_API_NOT_FOUND.wire();
            return s;
        }

        s.args = new RuntimeExecutionArguments();
        try
        {
            IInfobaseAccessSettings settings = access == null ? null
                : access.resolveSettings(s.infobase);
            if (settings != null)
            {
                s.args.setAccess(settings.access());
                s.args.setUsername(emptyToNull(settings.userName()));
                s.args.setPassword(emptyToNull(settings.password()));
            }
        }
        catch (Throwable e)
        {
            // A freshly created infobase has no credentials to find, which is not a problem -
            // there is no user to authenticate as yet.
            Activator.logWarning("staging infobase: no access settings (proceeding): " //$NON-NLS-1$
                + oneLine(e));
        }
        return s;
    }

    /**
     * Runs one Designer command against the staging infobase.
     *
     * @param staging the resolved context; {@link Staging#failure} is set when the run threw
     * @param shape what the command should do
     * @return the Designer transcript, trimmed, or <code>null</code>
     */
    private static String run(Staging staging, CommandShape shape)
    {
        staging.failure = null;
        staging.failureKind = null;
        try
        {
            RuntimeExecutionCommandBuilder command = new RuntimeExecutionCommandBuilder(
                staging.component.getFile(), RuntimeExecutionCommandBuilder.ThickClientMode.DESIGNER);
            command.forInfobase(staging.infobase, staging.split);
            shape.apply(command);
            Object out = staging.execute.invoke(staging.launcher, command,
                staging.component.getInstallation(), staging.infobase, staging.args);
            return trim(out instanceof String ? (String)out : null);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            staging.failure = oneLine(cause);
            staging.failureKind = ErrorTags.THICK_CLIENT_FAILED.wire();
            return null;
        }
        catch (Throwable e)
        {
            staging.failure = oneLine(e);
            staging.failureKind = ErrorTags.THICK_CLIENT_FAILED.wire();
            return null;
        }
    }

    /**
     * Removes the staging infobase and its files.
     *
     * @param infobaseName the name it was created under
     * @return whether it is gone
     */
    private static boolean removeStaging(String infobaseName)
    {
        try
        {
            BmInfobaseLifecycleHelper.DeleteResult delete =
                BmInfobaseLifecycleHelper.deleteInfobase(infobaseName, true, null);
            if (!delete.ok)
            {
                // Worth a log and not worth failing the import: the caller has the XML, and a
                // leftover infobase is a tidiness problem they can act on by name.
                Activator.logWarning("staging infobase '" + infobaseName //$NON-NLS-1$
                    + "' could not be deleted: " + delete.error); //$NON-NLS-1$
            }
            return delete.ok;
        }
        catch (Throwable e)
        {
            Activator.logWarning("staging infobase '" + infobaseName + "' could not be deleted: " //$NON-NLS-1$ //$NON-NLS-2$
                + oneLine(e));
            return false;
        }
    }

    /**
     * Whether a directory holds nothing; an absent or unreadable one counts as empty.
     *
     * @param dir the directory
     * @return <code>true</code> when there is nothing in it
     */
    private static boolean isEmpty(Path dir)
    {
        if (dir == null || !Files.isDirectory(dir))
        {
            return true;
        }
        try (Stream<Path> entries = Files.list(dir))
        {
            return entries.findAny().isEmpty();
        }
        catch (IOException e)
        {
            return true;
        }
    }

    /**
     * Deletes a directory and everything under it, best effort.
     *
     * @param root the directory
     */
    private static void deleteTree(Path root)
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored)
                {
                    // A file the platform still holds open outlives the import; temp is temp.
                }
            });
        }
        catch (IOException e)
        {
            Activator.logWarning("staging directory " + root + " was left behind: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
        }
    }

    /**
     * Finds a method on a class or any of its superclasses, made accessible.
     *
     * @param type where to start looking
     * @param name the method's name
     * @param parameters its parameter types
     * @return the method, or <code>null</code> when this runtime has no such method
     */
    private static Method findMethod(Class<?> type, String name, Class<?>... parameters)
    {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
        {
            try
            {
                Method m = c.getDeclaredMethod(name, parameters);
                m.setAccessible(true);
                return m;
            }
            catch (NoSuchMethodException keepLooking)
            {
                // The launcher's hierarchy is EDT's, not ours - which class declares what is not
                // something to depend on.
            }
        }
        return null;
    }

    private static String emptyToNull(String s)
    {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * A throwable as one line of text, causes included.
     *
     * @param e the throwable
     * @return the message
     */
    private static String oneLine(Throwable e)
    {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 600; t = t.getCause())
        {
            String message = t.getMessage();
            if (message == null || message.isBlank())
            {
                message = t.getClass().getSimpleName();
            }
            if (sb.length() > 0)
            {
                sb.append(" <- "); //$NON-NLS-1$
            }
            sb.append(message.replace('\n', ' ').replace('\r', ' ').trim());
        }
        return sb.toString();
    }

    /**
     * Keeps a Designer transcript short enough to travel in a reply.
     *
     * @param log the transcript
     * @return the tail of it, or <code>null</code>
     */
    private static String trim(String log)
    {
        if (log == null || log.isBlank())
        {
            return null;
        }
        String text = log.trim();
        int max = 4000;
        return text.length() <= max ? text : "..." + text.substring(text.length() - max); //$NON-NLS-1$
    }
}
