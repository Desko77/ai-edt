/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.HandshakeOutcome;
import ru.aiedt.mcp.server.support.DumpInfoRebuilder.Abandoned;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Exports selected objects of an INFOBASE's configuration (not the EDT project) into the
 * Configurator's hierarchical XML, through the same Designer-launch machinery the dump-info rebuild
 * uses: the infobase is released, the Designer runs under the cross-process claim, the infobase is
 * taken back.
 *
 * <p>The Designer is pointed at the objects with {@code -listFile}: a UTF-8-with-BOM file of
 * Russian full names, one per line ({@code Справочник.Банки},
 * {@code Справочник.Банки.Форма.ФормаЭлемента}, {@code ОбщаяФорма.Имя}) - the spelling the
 * platform's own partial dump expects, measured in the sprint's 0.4 probe. The file is written
 * beside the service directory and deleted in a {@code finally}.</p>
 *
 * <p>Every step that reaches outside this class is handed in through {@link ExportIo}, so the step
 * order and every failure of it are testable against a stand-in that records what ran - the shape
 * {@link DumpInfoRebuilder} established for the rebuild.</p>
 */
public final class InfobaseObjectsExporter
{
    /** How long one Designer run is waited for before it is abandoned. */
    public static final long DESIGNER_BUDGET_MS = 600_000L;

    /**
     * How long an abandoned run's process is given to leave on its own before the service
     * directory is named {@code leftBehind} in the answer instead of being deleted.
     */
    public static final long PROCESS_EXIT_GRACE_MS = 30_000L;

    /** What the answer's {@code source} field says: the objects came from the infobase. */
    public static final String SOURCE_INFOBASE = "infobase"; //$NON-NLS-1$

    private InfobaseObjectsExporter()
    {
        // static orchestrator
    }

    /** One address as the caller wrote it, parsed into the shapes this operation accepts. */
    public static final class ParsedAddress
    {
        /** The shapes an address may take; anything else is refused before the Designer runs. */
        public enum Shape
        {
            /** {@code Catalog.Банки} - a top object of any metadata type, CommonForm included. */
            TOP,
            /** {@code Catalog.Банки.Form.ФормаЭлемента} - a form owned by a top object. */
            FORM
        }

        /** The shape the address turned out to have. */
        public final Shape shape;

        /** The type segment as the caller wrote it; any spelling the type catalog knows. */
        public final String typeAsWritten;

        /** The canonical English singular of the type ({@code Catalog}). */
        public final String typeEnglish;

        /** The object name segment as the caller wrote it. */
        public final String objectName;

        /** The form name segment of a FORM address, as the caller wrote it. */
        public final String formName;

        /** The address as it arrived, for the answer's {@code objects} map. */
        public final String raw;

        ParsedAddress(Shape shape, String typeAsWritten, String typeEnglish, String objectName,
            String formName, String raw)
        {
            this.shape = shape;
            this.typeAsWritten = typeAsWritten;
            this.typeEnglish = typeEnglish;
            this.objectName = objectName;
            this.formName = formName;
            this.raw = raw;
        }
    }

    /** Why an address cannot be exported, in the sentence the answer carries. */
    public static final class AddressRefusal
    {
        /** The address that was refused. */
        public final String address;

        /** The refusal sentence. */
        public final String reason;

        AddressRefusal(String address, String reason)
        {
            this.address = address;
            this.reason = reason;
        }
    }

    /**
     * Parses one address into the shapes this operation accepts.
     *
     * <p>Accepted: a top object ({@code Catalog.Банки}, {@code Справочник.Банки}), a form
     * ({@code Catalog.Банки.Form.ФормаЭлемента}, Russian kind spellings too) and a common form
     * ({@code CommonForm.Имя}). Any other child kind is refused with the list of the supported
     * ones, and so is a type no metadata catalog knows.</p>
     *
     * @param address the address as the caller wrote it; may be {@code null}
     * @return the parse, or {@code null} with {@code refusal[0]} set when the address is refused
     */
    public static ParsedAddress parseAddress(String address, AddressRefusal[] refusal)
    {
        if (address == null || address.trim().isEmpty())
        {
            refusal[0] = new AddressRefusal(String.valueOf(address), "the address is empty"); //$NON-NLS-1$
            return null;
        }
        String trimmed = address.trim();
        String[] segments = trimmed.split("\\."); //$NON-NLS-1$
        MetadataTypeCatalog.MetadataTypeInfo type = segments.length > 0
            ? MetadataTypeCatalog.resolve(segments[0]) : null;
        if (type == null)
        {
            refusal[0] = new AddressRefusal(address, "'" + segments[0] + "' is not a metadata " //$NON-NLS-1$ //$NON-NLS-2$
                + "type this plugin knows"); //$NON-NLS-1$
            return null;
        }
        if (segments.length == 2 && !segments[1].isEmpty())
        {
            return new ParsedAddress(ParsedAddress.Shape.TOP, segments[0],
                type.getEnglishSingular(), segments[1], null, trimmed);
        }
        if (segments.length == 4 && !segments[1].isEmpty() && !segments[3].isEmpty())
        {
            String getter = BmObjectHelper.childKindGetter(segments[2]);
            if (!"getForms".equals(getter)) //$NON-NLS-1$
            {
                refusal[0] = new AddressRefusal(address, "'" + segments[2] + "' is not a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "supported child kind - this operation exports a whole top object, a form " //$NON-NLS-1$
                    + "(<Type>.<Name>.Form.<FormName>, Russian kind spellings accepted) or a " //$NON-NLS-1$
                    + "common form (CommonForm.<Name>)"); //$NON-NLS-1$
                return null;
            }
            return new ParsedAddress(ParsedAddress.Shape.FORM, segments[0],
                type.getEnglishSingular(), segments[1], segments[3], trimmed);
        }
        refusal[0] = new AddressRefusal(address,
            "the address has " + segments.length + " segments; a top object has two " //$NON-NLS-1$ //$NON-NLS-2$
                + "(Catalog.Банки) and a form has four (Catalog.Банки.Form.ФормаЭлемента)"); //$NON-NLS-1$
        return null;
    }

    /** What the model said about one parsed address: the list-file line, or why there is none. */
    public static final class Resolved
    {
        /** The Russian full name the list file carries ({@code Справочник.Банки.Форма.Ф}). */
        public final String listLine;

        /** The authored object name the model holds, for the answer's {@code objects} map. */
        public final String objectName;

        /** The authored form name of a FORM address, or {@code null}. */
        public final String formName;

        /** Why the model does not have this address, or {@code null}. */
        public final String notFound;

        Resolved(String listLine, String objectName, String formName, String notFound)
        {
            this.listLine = listLine;
            this.objectName = objectName;
            this.formName = formName;
            this.notFound = notFound;
        }
    }

    /**
     * Resolves a parsed address against the EDT project's model, the same way the other metadata
     * tools resolve theirs.
     */
    @FunctionalInterface
    public interface ModelResolver
    {
        /**
         * @param address the parsed address
         * @return the resolution, never {@code null}
         */
        Resolved resolve(ParsedAddress address);
    }

    /**
     * Builds the production resolver over a project's configuration. The kind translation is the
     * catalog's own ({@link MetadataTypeCatalog}); no second table is introduced here.
     *
     * @param project the EDT project whose model the addresses are checked against
     * @return the resolver
     */
    public static ModelResolver modelResolver(IProject project)
    {
        return address -> {
            IConfigurationProvider provider = Activator.getDefault() != null
                ? Activator.getDefault().getConfigurationProvider() : null;
            Configuration config = provider != null ? provider.getConfiguration(project) : null;
            if (config == null)
            {
                return new Resolved(null, null, null,
                    "the project's configuration model is not available yet"); //$NON-NLS-1$
            }
            MetadataTypeCatalog.MetadataTypeInfo type =
                MetadataTypeCatalog.resolve(address.typeAsWritten);
            String russianType = type.getRussianNames()[0];
            MdObject owner = MetadataTypeCatalog.findObject(config, address.typeEnglish,
                address.objectName);
            if (owner == null)
            {
                return new Resolved(null, null, null,
                    "no " + address.typeEnglish + " named '" + address.objectName //$NON-NLS-1$ //$NON-NLS-2$
                        + "' in the project's configuration"); //$NON-NLS-1$
            }
            if (address.shape == ParsedAddress.Shape.TOP)
            {
                return new Resolved(russianType + "." + owner.getName(), owner.getName(), null, //$NON-NLS-1$
                    null);
            }
            for (MdObject child : childrenOfForms(owner))
            {
                if (child != null && child.getName() != null
                    && child.getName().equalsIgnoreCase(address.formName))
                {
                    String formKind = BmObjectHelper.russianChildKindName("Form"); //$NON-NLS-1$
                    return new Resolved(russianType + "." + owner.getName() + "." + formKind //$NON-NLS-1$ //$NON-NLS-2$
                        + "." + child.getName(), owner.getName(), child.getName(), null); //$NON-NLS-1$
                }
            }
            return new Resolved(null, owner.getName(), null, "no form named '" + address.formName //$NON-NLS-1$
                + "' in " + address.typeEnglish + "." + owner.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        };
    }

    /** The forms an owner holds, read the way the child walk reads them; empty when it has none. */
    private static List<MdObject> childrenOfForms(MdObject owner)
    {
        EList<? extends EObject> children = BmObjectHelper.getChildListByKind(owner, "Form"); //$NON-NLS-1$
        List<MdObject> forms = new ArrayList<>();
        if (children == null)
        {
            return forms;
        }
        for (EObject child : children)
        {
            if (child instanceof MdObject)
            {
                forms.add((MdObject)child);
            }
        }
        return forms;
    }

    /**
     * What the whole validation of a call's {@code objects} came to: the list-file lines keyed by
     * the address as the caller wrote it, or the refusals.
     */
    public static final class Validation
    {
        /** Address as written to its list-file line, in the order first asked. */
        public final Map<String, String> lines = new LinkedHashMap<>();

        /** The refusals, empty when every address resolved. */
        public final List<AddressRefusal> refusals = new ArrayList<>();
    }

    /**
     * Parses and resolves every address of a call. Repeats collapse onto one list-file line: the
     * line is built from the model's authored names, so two spellings of one object produce the
     * same line and the file carries it once.
     *
     * @param addresses the addresses as the caller wrote them
     * @param resolver the model resolver
     * @return the validation, never {@code null}
     */
    public static Validation validate(List<String> addresses, ModelResolver resolver)
    {
        Validation validation = new Validation();
        if (addresses == null || addresses.isEmpty())
        {
            validation.refusals.add(new AddressRefusal("", "the objects list is empty")); //$NON-NLS-1$ //$NON-NLS-2$
            return validation;
        }
        for (String address : addresses)
        {
            AddressRefusal[] refusal = new AddressRefusal[1];
            ParsedAddress parsed = parseAddress(address, refusal);
            if (parsed == null)
            {
                validation.refusals.add(refusal[0]);
                continue;
            }
            Resolved resolved = resolver.resolve(parsed);
            if (resolved.notFound != null)
            {
                validation.refusals.add(new AddressRefusal(address, resolved.notFound));
                continue;
            }
            validation.lines.putIfAbsent(parsed.raw, resolved.listLine);
        }
        return validation;
    }

    /**
     * Renders the list file's bytes: a UTF-8 BOM, then one Russian full name per line, in the
     * order the lines were validated, each line terminated by a newline.
     *
     * @param lines the list-file lines
     * @return the bytes to write
     */
    public static byte[] listFileBytes(List<String> lines)
    {
        StringBuilder text = new StringBuilder();
        for (String line : lines)
        {
            text.append(line).append('\n');
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte)0xEF;
        withBom[1] = (byte)0xBB;
        withBom[2] = (byte)0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        return withBom;
    }

    /** What one export came to, every part named on its own. */
    public static final class Outcome
    {
        /** Whether the objects were placed at the output path. */
        public boolean ok;

        /** The refusal or failure sentence, or {@code null} on success. */
        public String error;

        /** The failure kind for an answer's {@code failureKind}, or {@code null}. */
        public String failureKind;

        /** The absolute output path, as the caller named it. */
        public String outputPath;

        /** The files the export placed, relative to the output path, in alphabetical order. */
        public List<String> files = Collections.emptyList();

        /** Address as written to its list-file line. */
        public final Map<String, String> objects = new LinkedHashMap<>();

        /** Always {@link #SOURCE_INFOBASE}. */
        public String source = SOURCE_INFOBASE;

        /** The infobase's name, when the environment resolved one to name. */
        public String infobaseName;

        /** How long the whole export took. */
        public long elapsedMs;

        /** The tail of the Designer's log, when it left one. */
        public String designerLog;

        /**
         * The service directory an abandoned Designer run is still writing into, when the process
         * did not leave within the grace; {@code null} otherwise.
         */
        public String leftBehind;

        /** Whether the cross-process claim is held for a Designer process that is still running. */
        public boolean lockHeldForProcess;

        /** The reconnection failure, named on its own; {@code null} when none. */
        public String reconnectError;

        /** The steps that ran, in order - the record a test reads the order from. */
        public final List<String> sequence = new ArrayList<>();

        /** Whether the call was cancelled before the Designer was started. */
        public boolean cancelledBeforeLaunch;
    }

    /**
     * Every step of the export that reaches outside this class. The production set is assembled by
     * the operation from EDT services; a test hands in a stand-in that records what ran.
     */
    public interface ExportIo
    {
        /**
         * @return the infobase identity {@link MonopolyLock} claims on, or {@code null} when this
         *         base cannot be identified - which refuses the whole export before anything is
         *         released
         */
        String infobaseIdentity();

        /**
         * @return {@code null} when the claim was taken, or the refusal sentence naming who holds
         *         the infobase
         */
        String takeLock();

        /**
         * Lets the claim go. Not called while an abandoned Designer call is still running: the
         * claim stays held until that call returns.
         */
        void releaseLock();

        /**
         * @return the service directory, created, beside the output path's parent - the same
         *         volume the result is placed on
         * @throws IOException when it cannot be created
         */
        Path serviceDirectory() throws IOException;

        /**
         * @return whether the infobase was connected before the release, so the reconnection
         *         knows whether it is owed
         * @throws Exception when the release failed - nothing runs after it
         */
        boolean releaseInfobase() throws Exception;

        /**
         * The Designer run: dumps the listed objects into the service directory. Throws
         * {@link Abandoned} for a run given up on by budget or cancellation.
         *
         * @param serviceDir the directory to dump into
         * @param listFile the list file the platform is pointed at
         * @param cancelled whether the caller's cancellation flag has been raised; a run that
         *            watches it answers its own abandonment
         * @return the Designer's log, or {@code null} when it left none
         * @throws Exception when the platform run failed
         */
        String runDesigner(Path serviceDir, Path listFile, BooleanSupplier cancelled)
            throws Exception;

        /**
         * Takes the infobase back; owed exactly when the release said the infobase was connected.
         *
         * @throws Exception when the reconnection failed
         */
        void reconnectInfobase() throws Exception;

        /**
         * @param outputPath the destination
         * @return whether the destination is absent or holds nothing
         */
        boolean destinationVacant(Path outputPath);

        /**
         * Removes the destination's empty directory, so the service directory can take its place.
         *
         * @param outputPath the destination
         * @throws IOException when the removal fails
         */
        void removeVacantDestination(Path outputPath) throws IOException;

        /**
         * Places the service directory at the destination - a rename within one volume.
         *
         * @param serviceDir the directory the Designer wrote into
         * @param outputPath the destination
         * @throws IOException when the move fails
         */
        void moveIntoPlace(Path serviceDir, Path outputPath) throws IOException;

        /**
         * Removes the service directory; runs at every outcome past its creation, except while an
         * abandoned Designer process is still writing into it.
         *
         * @param dir the service directory
         */
        void deleteDirectory(Path dir);
    }

    /**
     * Runs the export: identify, claim, service directory, list file, release the infobase, run
     * the Designer, take the infobase back, verify, place the result, clean up. The destination is
     * touched only through the place step: everything the Designer writes goes into the service
     * directory beside it, and the destination receives the result by one rename.
     *
     * @param io the environment, step by step
     * @param lines the validated list-file lines, in order
     * @param outputPath the destination the caller named
     * @param cancelled whether the caller's cancellation flag has been raised; checked before the
     *            Designer is started and handed to the run
     * @return the outcome, with the step sequence in it
     */
    public static Outcome performExport(ExportIo io, List<String> lines, Path outputPath,
        BooleanSupplier cancelled)
    {
        long startedAt = System.currentTimeMillis();
        Outcome out = new Outcome();
        out.outputPath = outputPath.toString();
        boolean lockTaken = false;
        Path serviceDir = null;
        Path listFile = null;
        HandshakeOutcome handshake = null;
        AtomicBoolean deferredSettled = new AtomicBoolean(false);

        // Fail-closed on a base this cannot name: an unidentified infobase would proceed without
        // a claim, and the operation that must not race a neighbour must refuse before releasing
        // anything of EDT's.
        out.sequence.add("identity"); //$NON-NLS-1$
        String identity = io.infobaseIdentity();
        if (identity == null || identity.isEmpty())
        {
            out.error = "The infobase cannot be identified, so the export was refused before " //$NON-NLS-1$
                + "anything was released or claimed - an unidentified base cannot be locked " //$NON-NLS-1$
                + "against a neighbouring EDT."; //$NON-NLS-1$
            out.failureKind = ErrorTags.RESOLVE_FAILED.wire();
            out.elapsedMs = System.currentTimeMillis() - startedAt;
            return out;
        }

        out.sequence.add("lock"); //$NON-NLS-1$
        String lockRefusal = io.takeLock();
        if (lockRefusal != null)
        {
            out.error = lockRefusal;
            out.failureKind = ErrorTags.BUSY.wire();
            out.elapsedMs = System.currentTimeMillis() - startedAt;
            return out;
        }
        lockTaken = true;

        try
        {
            // The destination has to start out absent or empty, checked before anything is
            // released: the result is placed by one rename onto a vacant path, and a destination
            // that already holds files is not one this operation may overwrite.
            out.sequence.add("destination"); //$NON-NLS-1$
            if (!io.destinationVacant(outputPath))
            {
                out.error = "The output path is not empty: " + outputPath + ". Point outputPath " //$NON-NLS-1$ //$NON-NLS-2$
                    + "at a new or empty directory - the export places its result there as a " //$NON-NLS-1$
                    + "whole, and mixing it with what is already there would make the result " //$NON-NLS-1$
                    + "impossible to tell apart from what was there before."; //$NON-NLS-1$
                out.failureKind = ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
                return out;
            }

            out.sequence.add("serviceDir"); //$NON-NLS-1$
            serviceDir = io.serviceDirectory();
            listFile = serviceDir.resolveSibling(
                serviceDir.getFileName() + ".objects.txt"); //$NON-NLS-1$
            Files.write(listFile, listFileBytes(lines));
            final Path theServiceDir = serviceDir;
            final Path theListFile = listFile;

            handshake = BmInfobaseExtensionHelper.runUnderHandshake(
                () -> {
                    out.sequence.add("release"); //$NON-NLS-1$
                    return io.releaseInfobase();
                },
                () -> {
                    out.sequence.add("work"); //$NON-NLS-1$
                    if (cancelled != null && cancelled.getAsBoolean())
                    {
                        // Cancelled before the Designer was started: nothing was launched, so
                        // there is nothing to abandon and nothing to wait for.
                        out.cancelledBeforeLaunch = true;
                        return;
                    }
                    out.sequence.add("designer"); //$NON-NLS-1$
                    try
                    {
                        out.designerLog = io.runDesigner(theServiceDir, theListFile, cancelled);
                    }
                    catch (Abandoned abandoned)
                    {
                        if (abandoned.processStillRunning())
                        {
                            out.leftBehind = theServiceDir.toString();
                        }
                        throw abandoned;
                    }
                },
                () -> {
                    if (out.leftBehind != null)
                    {
                        // The Designer process is still writing; reconnecting now would put EDT's
                        // session back onto a base the platform still holds.
                        return;
                    }
                    out.sequence.add("reconnect"); //$NON-NLS-1$
                    io.reconnectInfobase();
                });
            if (out.leftBehind != null)
            {
                // The process did not leave with the abandonment. Give it the grace before the
                // answer names the directory as left behind: a run that finishes within it is
                // cleaned up here rather than left on disk, and the infobase is taken back.
                AtomicBoolean settled = deferredSettled;
                Abandoned abandoned = (Abandoned)handshake.workError;
                abandoned.whenFinished(() -> settled.set(true));
                long deadline = System.currentTimeMillis() + PROCESS_EXIT_GRACE_MS;
                while (!settled.get() && System.currentTimeMillis() < deadline)
                {
                    sleep(100L);
                }
                if (settled.get())
                {
                    out.leftBehind = null;
                    out.sequence.add("graceReturn"); //$NON-NLS-1$
                    if (handshake.released)
                    {
                        try
                        {
                            out.sequence.add("reconnect"); //$NON-NLS-1$
                            io.reconnectInfobase();
                        }
                        catch (Exception alsoFailed)
                        {
                            out.reconnectError = "EDT could not take the infobase back after the " //$NON-NLS-1$
                                + "Designer process finally returned: " + oneLine(alsoFailed) //$NON-NLS-1$
                                + " " + DumpInfoRebuilder.RECONNECT_FAILED_HINT; //$NON-NLS-1$
                        }
                    }
                }
                else
                {
                    out.lockHeldForProcess = true;
                    out.reconnectError = "The infobase was left disconnected because the " //$NON-NLS-1$
                        + "Designer process is still running. The cross-process lock stays held " //$NON-NLS-1$
                        + "until that process finishes, and the service directory was not " //$NON-NLS-1$
                        + "deleted under the writer: " + serviceDir + "."; //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else if (handshake.reconnectError != null)
            {
                out.reconnectError = "EDT could not take the infobase back after the Designer " //$NON-NLS-1$
                    + "run: " + oneLine(handshake.reconnectError) + " " //$NON-NLS-1$ //$NON-NLS-2$
                    + DumpInfoRebuilder.RECONNECT_FAILED_HINT;
            }
            if (handshake.releaseError != null)
            {
                out.error = "EDT could not release the infobase for the Designer, so the export " //$NON-NLS-1$
                    + "did not start and the destination was not touched: " //$NON-NLS-1$
                    + oneLine(handshake.releaseError);
                out.failureKind = ErrorTags.INFOBASE_NOT_RELEASED.wire();
                return out;
            }
            if (out.cancelledBeforeLaunch)
            {
                out.error = "The export was cancelled before the Designer was started; nothing " //$NON-NLS-1$
                    + "was launched and the destination was not touched."; //$NON-NLS-1$
                out.failureKind = ErrorTags.CANCELLED.wire();
                return out;
            }
            if (handshake.workError != null)
            {
                workFailure(handshake.workError, out);
                return out;
            }

            // The Designer answered; the result is only real once it is at the destination.
            out.sequence.add("verifyOutput"); //$NON-NLS-1$
            if (isEmptyDirectory(serviceDir))
            {
                out.error = "The Designer run reported no error but wrote nothing into " //$NON-NLS-1$
                    + serviceDir + ". The objects may not be in the infobase's configuration, or " //$NON-NLS-1$
                    + "the platform may have exited before writing."; //$NON-NLS-1$
                out.failureKind = ErrorTags.OUTPUT_MISSING.wire();
                return out;
            }
            out.sequence.add("place"); //$NON-NLS-1$
            if (!io.destinationVacant(outputPath))
            {
                out.error = "The output path is not empty any more: " + outputPath //$NON-NLS-1$
                    + ". It was vacant when the export started; whatever filled it arrived in " //$NON-NLS-1$
                    + "between. The export's files were discarded and the destination was not " //$NON-NLS-1$
                    + "touched."; //$NON-NLS-1$
                out.failureKind = ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
                return out;
            }
            try
            {
                io.removeVacantDestination(outputPath);
                io.moveIntoPlace(serviceDir, outputPath);
            }
            catch (IOException | RuntimeException moveFailed)
            {
                out.error = "Placing the export at " + outputPath + " failed (" //$NON-NLS-1$ //$NON-NLS-2$
                    + oneLine(moveFailed) + "); the service directory was discarded and nothing " //$NON-NLS-1$
                    + "of the export was left at the destination."; //$NON-NLS-1$
                out.failureKind = ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
                return out;
            }
            out.files = relativeFiles(outputPath);
            out.ok = true;
        }
        catch (IOException | RuntimeException failed)
        {
            out.error = "The export failed: " + oneLine(failed); //$NON-NLS-1$
            out.failureKind = ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
            return out;
        }
        finally
        {
            boolean hold = out.lockHeldForProcess && !deferredSettled.get();
            if (listFile != null)
            {
                out.sequence.add("deleteListFile"); //$NON-NLS-1$
                try
                {
                    Files.deleteIfExists(listFile);
                }
                catch (IOException ignored)
                {
                    // best effort; the list file is disposable by contract
                }
            }
            if (serviceDir != null && !hold)
            {
                out.sequence.add("cleanup"); //$NON-NLS-1$
                io.deleteDirectory(serviceDir);
            }
            if (lockTaken && !hold)
            {
                out.sequence.add("unlock"); //$NON-NLS-1$
                io.releaseLock();
            }
            if (hold && handshake != null && handshake.workError instanceof Abandoned)
            {
                Path left = serviceDir;
                final boolean reconnectOwed = handshake.released;
                AtomicBoolean settled = deferredSettled;
                ((Abandoned)handshake.workError).whenFinished(() -> {
                    settled.set(true);
                    if (left != null)
                    {
                        io.deleteDirectory(left);
                    }
                    if (reconnectOwed)
                    {
                        try
                        {
                            io.reconnectInfobase();
                        }
                        catch (Exception ignored)
                        {
                            // The answer already said the infobase was left disconnected.
                        }
                    }
                    io.releaseLock();
                });
            }
            out.elapsedMs = System.currentTimeMillis() - startedAt;
        }
        return out;
    }

    /**
     * Turns a Designer-run failure into the answer's words. A partial export (the run failed with
     * files in the service directory) is this case too: the service directory is deleted by the
     * finally, the destination was never touched, and the failure carries the log the run left.
     */
    private static void workFailure(Throwable workError, Outcome out)
    {
        if (workError instanceof Abandoned)
        {
            if (out.leftBehind != null)
            {
                out.error = "The Designer run did not finish and was abandoned: " //$NON-NLS-1$
                    + workError.getMessage() + ". The destination was not touched; the platform " //$NON-NLS-1$
                    + "process is still running and the service directory was left in place " //$NON-NLS-1$
                    + "under the writer (" + out.leftBehind + ") - do not start another export " //$NON-NLS-1$ //$NON-NLS-2$
                    + "into it until that process has finished. The cross-process lock stays " //$NON-NLS-1$
                    + "held while the process is alive."; //$NON-NLS-1$
            }
            else
            {
                out.error = "The Designer run did not finish and was abandoned: " //$NON-NLS-1$
                    + workError.getMessage() + ". The destination was not touched; the platform " //$NON-NLS-1$
                    + "process, if it is still running, finishes on its own - do not start " //$NON-NLS-1$
                    + "another export into the same destination until it has."; //$NON-NLS-1$
            }
            out.failureKind = ErrorTags.THICK_CLIENT_FAILED.wire();
            return;
        }
        out.error = "The Designer run failed, so the destination was not touched: " //$NON-NLS-1$
            + oneLine(workError);
        out.failureKind = ErrorTags.THICK_CLIENT_FAILED.wire();
    }

    /**
     * The files an export placed, relative to its root and in alphabetical order.
     *
     * @param root the placed directory
     * @return the relative paths, never {@code null}
     */
    private static List<String> relativeFiles(Path root)
    {
        List<String> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root))
        {
            walk.filter(Files::isRegularFile).forEach(path -> files.add(root.relativize(path)
                .toString().replace('\\', '/')));
        }
        catch (IOException unreadable)
        {
            // The placement already succeeded; an unreadable tree costs the file list, not the
            // result.
        }
        Collections.sort(files);
        return files;
    }

    /**
     * Tells whether a directory holds nothing, treating an unreadable or absent one as empty.
     *
     * @param dir the directory to inspect
     * @return true when it contains no entries
     */
    static boolean isEmptyDirectory(Path dir)
    {
        if (dir == null || !Files.isDirectory(dir))
        {
            return true;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(dir))
        {
            return !entries.findAny().isPresent();
        }
        catch (IOException e)
        {
            return true;
        }
    }

    /**
     * One Designer run, as a value the wait can call.
     */
    interface DesignerCall
    {
        /**
         * @return the Designer's log, or {@code null}
         * @throws Exception when the run failed
         */
        String run() throws Exception;
    }

    /**
     * Runs a Designer call on a worker thread and abandons it when its budget runs out or its
     * caller's cancellation is raised - the same abandonment the dump-info rebuild uses, claim of
     * the launch boundary included.
     *
     * @param what what the run is, as the abandonment names it
     * @param budgetMs how long the run is waited for
     * @param call the platform call
     * @param launchClaim the launch boundary of this run, claimed by the worker under the
     *            per-infobase lock right before it calls the launcher and by the abandonment
     *            before it declares itself, or {@code null} when the call has no launcher boundary
     *            (a stand-in)
     * @param cancelled whether the run's caller has cancelled it; polled while the wait blocks
     * @return the call's own answer
     * @throws Abandoned when the budget ran out or the caller cancelled - the call is interrupted
     *             and the process left to finish on its own
     * @throws Exception when the call itself failed
     */
    static String runUnderBudget(String what, long budgetMs, DesignerCall call,
        AtomicBoolean launchClaim, BooleanSupplier cancelled) throws Exception
    {
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "export-infobase-designer"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        AtomicBoolean started = new AtomicBoolean(false);
        CountDownLatch returned = new CountDownLatch(1);
        Future<String> running = worker.submit(() -> {
            started.set(true);
            try
            {
                return call.run();
            }
            finally
            {
                returned.countDown();
            }
        });
        worker.shutdown();
        long deadline = System.currentTimeMillis() + budgetMs;
        try
        {
            while (true)
            {
                try
                {
                    return running.get(100L, TimeUnit.MILLISECONDS);
                }
                catch (java.util.concurrent.TimeoutException stillGoing)
                {
                    if (cancelled != null && cancelled.getAsBoolean())
                    {
                        throw abandon(what + " was cancelled while it was still running", running, //$NON-NLS-1$
                            started, returned, launchClaim);
                    }
                    if (System.currentTimeMillis() >= deadline)
                    {
                        throw abandon(what + " did not finish within " + (budgetMs / 1000) + "s", //$NON-NLS-1$ //$NON-NLS-2$
                            running, started, returned, launchClaim);
                    }
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    throw abandon(what + " was interrupted while it was still running", running, //$NON-NLS-1$
                        started, returned, launchClaim);
                }
                catch (java.util.concurrent.ExecutionException failed)
                {
                    Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
                    if (cause instanceof Exception)
                    {
                        throw (Exception)cause;
                    }
                    throw new IllegalStateException(cause);
                }
            }
        }
        finally
        {
            worker.shutdownNow();
        }
    }

    /**
     * Builds the abandonment of a wait that gave up: the launch boundary is claimed first, so a
     * worker that has not crossed it yet starts no Designer run at all, and the Future is
     * cancelled. A boundary the worker already claimed means the launcher call is committed, and
     * the caller is told whether that call itself had returned.
     */
    private static Abandoned abandon(String message, Future<String> running,
        AtomicBoolean started, CountDownLatch returned, AtomicBoolean launchClaim)
    {
        boolean launchPrevented = launchClaim != null && launchClaim.compareAndSet(false, true);
        running.cancel(true);
        boolean stillRunning = !launchPrevented && started.get() && returned.getCount() > 0;
        return new Abandoned(message, stillRunning, stillRunning ? task -> {
            Thread watcher = new Thread(() -> {
                try
                {
                    returned.await();
                }
                catch (InterruptedException finishedAnyway)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
                task.run();
            }, "export-infobase-cleanup"); //$NON-NLS-1$
            watcher.setDaemon(true);
            watcher.start();
        } : null);
    }

    /** The runs of this domain that are live right now, keyed by runKey. */
    private static final Map<String, LiveRun> LIVE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The destinations exports are placing into right now, keyed by the destination's canonical
     * path; the value is the runKey of the export that holds it.
     */
    private static final Map<String, String> DESTINATIONS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** What a live run exposes to its own stopper. */
    static final class LiveRun
    {
        /** The launch boundary of the current Designer call, or {@code null} before it starts. */
        volatile AtomicBoolean launchClaim;

        /** Counted down by the stopper; the run's wait polls it. */
        final CountDownLatch stopped = new CountDownLatch(1);
    }

    static
    {
        // tasks/cancel reaches this domain only through the stopper. Without it the registry
        // drops the entry and the Designer keeps writing into the service directory.
        PendingWorkRegistry.EXPORT_INFOBASE.stopsWith(InfobaseObjectsExporter::stopTheRun);
    }

    /**
     * Stops the run a registry cancel names: claims its launch boundary so a worker that has not
     * crossed it starts no Designer, and wakes the wait so the abandonment is answered now rather
     * than at the budget's end.
     *
     * @param runKey the run's key
     * @return {@link PendingWorkRegistry.StopOutcome#NOTHING_TO_STOP} when no run is live,
     *         otherwise {@link PendingWorkRegistry.StopOutcome#STILL_RUNNING}
     */
    private static PendingWorkRegistry.StopOutcome stopTheRun(String runKey)
    {
        LiveRun live = LIVE.get(runKey);
        if (live == null)
        {
            return PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP;
        }
        AtomicBoolean claim = live.launchClaim;
        if (claim != null)
        {
            claim.compareAndSet(false, true);
        }
        live.stopped.countDown();
        return PendingWorkRegistry.StopOutcome.STILL_RUNNING;
    }

    /**
     * The production environment for one export, over the launcher the thick-client calls of this
     * server resolve.
     */
    static final class EdtIo implements ExportIo
    {
        private final BmInfobaseExtensionHelper.LauncherContext ctx;

        private final Path outputPath;

        private final String runKey;

        private final LiveRun live;

        private final BooleanSupplier callerCancelled;

        private MonopolyLock.Claim claim;

        EdtIo(BmInfobaseExtensionHelper.LauncherContext ctx, Path outputPath, String runKey,
            LiveRun live, BooleanSupplier callerCancelled)
        {
            this.ctx = ctx;
            this.outputPath = outputPath;
            this.runKey = runKey;
            this.live = live;
            this.callerCancelled = callerCancelled;
        }

        @Override
        public String infobaseIdentity()
        {
            return InfobaseIdentity.of(ctx.infobase);
        }

        @Override
        public String takeLock()
        {
            MonopolyLock.Claim attempt =
                MonopolyLock.claim(infobaseIdentity(), "export_infobase_objects"); //$NON-NLS-1$
            if (attempt.granted())
            {
                claim = attempt;
                return null;
            }
            String refusal = attempt.refusal();
            attempt.close();
            return refusal != null ? refusal
                : "The export was refused because the infobase lock was not granted."; //$NON-NLS-1$
        }

        @Override
        public void releaseLock()
        {
            MonopolyLock.Claim held = claim;
            claim = null;
            if (held != null)
            {
                held.close();
            }
        }

        @Override
        public Path serviceDirectory() throws IOException
        {
            Path parent = outputPath.toAbsolutePath().getParent();
            Path beside = parent != null ? parent : outputPath.toAbsolutePath();
            Path dir = beside.resolve(".aiedt-export-" + runKey); //$NON-NLS-1$
            Files.createDirectories(dir);
            return dir;
        }

        @Override
        public boolean releaseInfobase() throws Exception
        {
            return BmInfobaseExtensionHelper.releaseForThickClient(ctx);
        }

        @Override
        public String runDesigner(Path serviceDir, Path listFile, BooleanSupplier cancelled)
            throws Exception
        {
            LIVE.put(runKey, live);
            try
            {
                AtomicBoolean launchClaim = new AtomicBoolean();
                ctx.launchClaim = launchClaim;
                live.launchClaim = launchClaim;
                BooleanSupplier watch = () -> live.stopped.getCount() == 0
                    || (cancelled != null && cancelled.getAsBoolean());
                return runUnderBudget("the Designer export", DESIGNER_BUDGET_MS, //$NON-NLS-1$
                    () -> BmInfobaseExtensionHelper.runDesignerExportList(ctx, serviceDir, listFile),
                    launchClaim, watch);
            }
            finally
            {
                LIVE.remove(runKey);
            }
        }

        @Override
        public void reconnectInfobase() throws Exception
        {
            BmInfobaseExtensionHelper.takeInfobaseBack(ctx);
        }

        @Override
        public boolean destinationVacant(Path destination)
        {
            return isEmptyDirectory(destination);
        }

        @Override
        public void removeVacantDestination(Path destination) throws IOException
        {
            if (Files.isDirectory(destination))
            {
                Files.delete(destination);
            }
        }

        @Override
        public void moveIntoPlace(Path dir, Path destination) throws IOException
        {
            Files.move(dir, destination);
        }

        @Override
        public void deleteDirectory(Path dir)
        {
            deleteTree(dir);
        }
    }

    /**
     * Deletes a directory tree, best effort - the service directory is disposable by contract.
     */
    private static void deleteTree(Path dir)
    {
        if (dir == null || !Files.exists(dir))
        {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored)
                {
                    // best effort
                }
            });
        }
        catch (IOException ignored)
        {
            // best effort
        }
    }

    /**
     * Builds the environment one export runs against. Split from the work so a test can hand in
     * its own stand-in.
     */
    @FunctionalInterface
    public interface IoFactory
    {
        /**
         * @param projectName the project that owns the infobase
         * @param applicationId the application naming the infobase; may be {@code null}
         * @param outputPath the destination the caller named
         * @param runKey this export's runKey
         * @param live the live-run handle the stopper reaches
         * @param cancelled the caller's cancellation flag
         * @return the environment, or a resolution failure answered instead of it
         */
        IoResolution ioFor(String projectName, String applicationId, Path outputPath, String runKey,
            LiveRun live, BooleanSupplier cancelled);
    }

    /** Either an environment to run against, or the refusal to answer with. */
    public static final class IoResolution
    {
        /** The environment, when resolution succeeded. */
        public final ExportIo io;

        /** The infobase's name, when one was resolved far enough to name it. */
        public final String infobaseName;

        /** The resolution failure, or {@code null}. */
        public final String error;

        /** The failure kind of {@link #error}, or {@code null}. */
        public final String failureKind;

        IoResolution(ExportIo io, String infobaseName, String error, String failureKind)
        {
            this.io = io;
            this.infobaseName = infobaseName;
            this.error = error;
            this.failureKind = failureKind;
        }

        /**
         * @return a successful resolution
         */
        public static IoResolution of(ExportIo io, String infobaseName)
        {
            return new IoResolution(io, infobaseName, null, null);
        }

        /**
         * @return a failed resolution
         */
        public static IoResolution refused(String error, String failureKind)
        {
            return new IoResolution(null, null, error, failureKind);
        }
    }

    /**
     * The production environment factory: resolves the thick-client launcher the way every other
     * Designer call of this server does.
     */
    public static final IoFactory EDT_IO = (projectName, applicationId, outputPath, runKey, live,
        cancelled) -> {
        BmInfobaseExtensionHelper.LauncherContext ctx =
            BmInfobaseExtensionHelper.resolveLauncher(projectName, applicationId);
        if (ctx.error != null)
        {
            return IoResolution.refused(ctx.error, ctx.failureKind);
        }
        return IoResolution.of(
            new EdtIo(ctx, outputPath, runKey, live, cancelled), ctx.infobaseName);
    };

    /** The soft timeout's clamp range, the same one export_object uses. */
    private static final int MIN_TIMEOUT_SECONDS = 5;

    private static final int MAX_TIMEOUT_SECONDS = 120;

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * The project side of a dispatch: resolves the project a call names and the model resolver its
     * addresses are checked against. Handed in so a test can stand both on fakes.
     */
    public interface DispatchEnv
    {
        /**
         * @param projectName the project a call names
         * @return the project, or {@code null} when there is none
         */
        IProject resolveProject(String projectName);

        /**
         * @param project the resolved project
         * @return the resolver the addresses are checked against
         */
        ModelResolver resolverFor(IProject project);
    }

    /** The production project resolution and model check. */
    public static final DispatchEnv EDT_ENV = new DispatchEnv()
    {
        @Override
        public IProject resolveProject(String projectName)
        {
            return ProjectResolver.resolve(projectName);
        }

        @Override
        public ModelResolver resolverFor(IProject project)
        {
            return modelResolver(project);
        }
    };

    /**
     * The whole dispatch a call to the operation takes: validate the arguments, claim the
     * destination, hand the work to {@link PendingWorkRegistry#EXPORT_INFOBASE}, and answer within
     * the soft timeout or with a {@code Pending} envelope carrying the runKey.
     *
     * <p>The runKey is unique per call: this operation writes files, so identical calls are never
     * coalesced and a finished answer is never replayed.</p>
     *
     * @param params the call arguments, for the soft timeout a poll waits with
     * @param projectName the project a call names
     * @param applicationId the application naming the infobase; may be {@code null}
     * @param objects the object addresses as the caller wrote them
     * @param outputPathRaw the destination the caller named
     * @param runKeyParam a previously-issued runKey to poll, or {@code null}
     * @param env the project side of the dispatch
     * @param io the environment factory
     * @param starter the tool name a poll of this run arrives under
     * @return the answer, or the {@code Pending} envelope
     */
    public static String dispatchExport(Map<String, String> params, String projectName,
        String applicationId, List<String> objects, String outputPathRaw, String runKeyParam,
        DispatchEnv env, IoFactory io, String starter)
    {
        if (runKeyParam != null && !runKeyParam.isEmpty())
        {
            return collect(runKeyParam, params);
        }
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required for export_infobase_objects.") //$NON-NLS-1$
                .put("operation", "export_infobase_objects").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (outputPathRaw == null || outputPathRaw.isEmpty())
        {
            return ToolResult.error("outputPath is required for export_infobase_objects " //$NON-NLS-1$
                + "(an absolute directory path).").put("operation", //$NON-NLS-1$
                    "export_infobase_objects").toJson(); //$NON-NLS-1$
        }
        Path outputPath;
        try
        {
            outputPath = java.nio.file.Paths.get(outputPathRaw).toAbsolutePath();
        }
        catch (java.nio.file.InvalidPathException bad)
        {
            return ToolResult.error("outputPath is not a valid directory path: " + oneLine(bad)) //$NON-NLS-1$
                .put("operation", "export_infobase_objects") //$NON-NLS-1$ //$NON-NLS-2$
                .put(ErrorTags.INVALID_OUTPUT_PATH.wire(), Boolean.TRUE).toJson();
        }

        IProject project = env.resolveProject(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).put("operation", //$NON-NLS-1$
                "export_infobase_objects").toJson(); //$NON-NLS-1$
        }

        Validation validation = validate(objects, env.resolverFor(project));
        if (!validation.refusals.isEmpty())
        {
            StringBuilder refusal = new StringBuilder(
                "The objects list was refused, so nothing was exported and no Designer was " //$NON-NLS-1$
                    + "started:\n"); //$NON-NLS-1$
            for (AddressRefusal one : validation.refusals)
            {
                refusal.append("- '").append(one.address).append("': ").append(one.reason) //$NON-NLS-1$ //$NON-NLS-2$
                    .append('\n');
            }
            return ToolResult.error(refusal.toString().trim())
                .put("operation", "export_infobase_objects") //$NON-NLS-1$ //$NON-NLS-2$
                .put("refusedAddresses", validation.refusals.size()).toJson();
        }

        String destinationKey = destinationKey(outputPath);
        String runKey = "eio-" + UUID.randomUUID(); //$NON-NLS-1$
        String holder;
        synchronized (DESTINATIONS)
        {
            holder = DESTINATIONS.get(destinationKey);
            if (holder == null)
            {
                DESTINATIONS.put(destinationKey, runKey);
            }
        }
        if (holder != null)
        {
            return ToolResult.error("Another export is already placing its result into " //$NON-NLS-1$
                + outputPathRaw + ", runKey=" + holder + ". Wait for it, or stop it with " //$NON-NLS-1$ //$NON-NLS-2$
                + "tasks/cancel and that runKey.").put("operation", //$NON-NLS-1$
                    "export_infobase_objects") //$NON-NLS-1$
                .put(ErrorTags.BUSY.wire(), Boolean.TRUE).put("heldByRunKey", holder).toJson();
        }

        PendingWorkRegistry registry = PendingWorkRegistry.EXPORT_INFOBASE;
        registry.pruneExpired();
        Map<String, String> lines = new LinkedHashMap<>(validation.lines);
        LiveRun live = new LiveRun();
        PendingWorkRegistry.PendingEntry entry = registry.getOrStart(runKey,
            pending -> runTheExport(pending, io, projectName, applicationId, outputPath, runKey,
                live, lines));
        entry.startedBy = starter;
        entry.subject = "export_infobase_objects"; //$NON-NLS-1$
        entry.workKind = "export_infobase_objects"; //$NON-NLS-1$

        String done = entry.await(timeoutMs);
        if (done != null)
        {
            registry.remove(runKey, entry);
            DESTINATIONS.remove(destinationKey, runKey);
            return done;
        }
        return PendingEnvelope.mark(ToolResult.success()
            .put("operation", "export_infobase_objects") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("outputPath", outputPath.toString()) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", timeoutMs) //$NON-NLS-1$
            .put("hint", "The export is still running. Call again with runKey=\"" + runKey //$NON-NLS-1$
                + "\" to resume waiting, or stop it with tasks/cancel and that runKey.")).toJson(); //$NON-NLS-1$
    }

    /**
     * The work body: resolves the environment, runs the export, and renders the answer.
     */
    private static String runTheExport(PendingWorkRegistry.PendingEntry entry, IoFactory io,
        String projectName, String applicationId, Path outputPath, String runKey, LiveRun live,
        Map<String, String> lines)
    {
        String destinationKey = destinationKey(outputPath);
        try
        {
            BooleanSupplier cancelled = () -> entry.cancellation != null
                && entry.cancellation.isCancelled();
            IoResolution resolution = io.ioFor(projectName, applicationId, outputPath, runKey, live,
                cancelled);
            if (resolution.error != null)
            {
                return ToolResult.error(resolution.error)
                    .put("operation", "export_infobase_objects") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("projectName", projectName) //$NON-NLS-1$
                    .put(resolution.failureKind != null ? resolution.failureKind
                        : ErrorTags.RESOLVE_FAILED.wire(), Boolean.TRUE).toJson();
            }
            // Repeats collapse here: two spellings of one object resolve to one list-file line,
            // and the file carries it once while the answer maps each spelling to it.
            Outcome outcome = performExport(resolution.io,
                new ArrayList<>(new LinkedHashSet<>(lines.values())), outputPath, cancelled);
            outcome.objects.putAll(lines);
            outcome.infobaseName = resolution.infobaseName;
            return render(outcome);
        }
        finally
        {
            DESTINATIONS.remove(destinationKey, runKey);
        }
    }

    /**
     * Polls a previously-issued runKey: the cached result, or a fresh {@code Pending} envelope.
     */
    private static String collect(String runKey, Map<String, String> params)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.EXPORT_INFOBASE;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return ToolResult.error("runKey not found - the export either completed and was " //$NON-NLS-1$
                + "already retrieved, or was cancelled or evicted. Issue a new request without " //$NON-NLS-1$
                + "runKey to start over.").put("operation", //$NON-NLS-1$
                    "export_infobase_objects").put("runKey", runKey).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;
        String done = entry.await(timeoutMs);
        if (done != null)
        {
            registry.remove(runKey, entry);
            return done;
        }
        return PendingEnvelope.mark(ToolResult.success()
            .put("operation", "export_infobase_objects") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("hint", "The export is still running. Call again with runKey=\"" + runKey //$NON-NLS-1$
                + "\" to resume waiting, or stop it with tasks/cancel and that runKey.")).toJson(); //$NON-NLS-1$
    }

    /**
     * Renders an outcome as the operation's JSON answer.
     *
     * @param outcome what the export came to
     * @return the answer
     */
    static String render(Outcome outcome)
    {
        ToolResult result = outcome.ok ? ToolResult.success() : ToolResult.error(
            outcome.error == null ? "the export failed without saying how" : outcome.error); //$NON-NLS-1$
        result.put("operation", "export_infobase_objects"); //$NON-NLS-1$ //$NON-NLS-2$
        result.put("ok", Boolean.valueOf(outcome.ok)); //$NON-NLS-1$
        result.put("outputPath", outcome.outputPath); //$NON-NLS-1$
        result.put("source", outcome.source); //$NON-NLS-1$
        result.put("elapsedMs", Long.valueOf(outcome.elapsedMs)); //$NON-NLS-1$
        if (outcome.files != null && !outcome.files.isEmpty())
        {
            result.put("files", outcome.files); //$NON-NLS-1$
        }
        if (!outcome.objects.isEmpty())
        {
            result.put("objects", outcome.objects); //$NON-NLS-1$
        }
        if (outcome.infobaseName != null)
        {
            result.put("infobase", outcome.infobaseName); //$NON-NLS-1$
        }
        if (outcome.failureKind != null)
        {
            result.put("failureKind", outcome.failureKind); //$NON-NLS-1$
        }
        if (outcome.designerLog != null && !outcome.designerLog.isBlank())
        {
            result.put("designerLog", tail(outcome.designerLog)); //$NON-NLS-1$
        }
        if (outcome.leftBehind != null)
        {
            result.put("leftBehind", outcome.leftBehind); //$NON-NLS-1$
        }
        if (outcome.reconnectError != null)
        {
            result.put("reconnectError", outcome.reconnectError); //$NON-NLS-1$
        }
        if (outcome.lockHeldForProcess)
        {
            result.put("lockHeldForProcess", Boolean.TRUE); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * The tail of a log, for an answer that names the platform's own words without dumping the
     * whole transcript.
     */
    private static String tail(String log)
    {
        String trimmed = log.trim();
        if (trimmed.isEmpty())
        {
            return null;
        }
        return trimmed.length() > 2000 ? trimmed.substring(trimmed.length() - 2000) : trimmed;
    }

    /**
     * The key a destination is claimed under: its real path when it exists, lowercased - the same
     * identity the conversion destination lock uses, for the same reason.
     */
    private static String destinationKey(Path outputPath)
    {
        String key;
        try
        {
            key = outputPath.toRealPath().toString();
        }
        catch (IOException | RuntimeException notThereYet)
        {
            try
            {
                key = outputPath.toAbsolutePath().normalize().toString();
            }
            catch (RuntimeException e)
            {
                key = outputPath.toString();
            }
        }
        return key.toLowerCase(Locale.ROOT);
    }

    /**
     * One line out of a failure, for an answer that names its facts without a stack dump.
     */
    private static String oneLine(Throwable failure)
    {
        String message = failure.getMessage();
        String named = message == null || message.isEmpty()
            ? failure.getClass().getSimpleName() : message;
        String line = named.replace('\n', ' ').replace('\r', ' ');
        return line.length() > 400 ? line.substring(0, 400) + "..." : line; //$NON-NLS-1$
    }

    private static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }
}
