/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

/**
 * Machine-readable response tags introduced by the 2026-07 reliability work.
 *
 * <p>Each constant owns the exact wire string it emits, so tool code references
 * the constant instead of a bare string literal. The first block below is the
 * <em>reliability</em> set (waves 1-2 of the remediation plan); the second block
 * is the <em>folded</em> set - the older ad-hoc tag literals that were scattered
 * across the tools ({@code propertyMismatch}, {@code requiresCascadeForms}, the
 * {@code *ApiNotFound} family, the {@code failureKind} discriminator fields on
 * the infobase/extension/external-object helpers, ...), gathered here by a
 * later pass so there is one home for every tag. Folding changed only the
 * source of each literal, never its value - every wire string below is
 * byte-identical to the literal it replaced.
 *
 * <p>Wire strings are stable identifiers - do not rename an existing constant's
 * string once a client may observe it. Add a new constant instead.
 */
public enum ErrorTags
{
    // -------------------------------------------------------------------
    // Reliability set (2026-07 waves 1-2)
    // -------------------------------------------------------------------

    /** A tool needed the EDT UI thread but it was blocked (e.g. a modal dialog). */
    UI_BUSY("uiBusy"), //$NON-NLS-1$

    /** A concurrency limiter refused the call: the heavy-tool semaphore or the
     * request queue is saturated. Pair with a retry hint. */
    BUSY("busy"), //$NON-NLS-1$

    /** A result was produced by a reduced-fidelity fallback (e.g. a file scan
     * because the semantic model was unavailable). The payload is usable but
     * less precise; a full run is advised. */
    DEGRADED("degraded"), //$NON-NLS-1$

    /** The response was cut to the server-side size cap; use offset/limit to
     * page the remainder. */
    TRUNCATED("truncated"), //$NON-NLS-1$

    /** The call was stopped before completion by an operator signal or an
     * internal cancellation. */
    CANCELLED("cancelled"), //$NON-NLS-1$

    /** The client disconnected while the tool was still running, so the call
     * was abandoned. */
    CLIENT_GONE("clientGone"), //$NON-NLS-1$

    // -------------------------------------------------------------------
    // Folded set (2026-07 G2 pass) - MetadataGuards.ErrorTag / BM guards
    // -------------------------------------------------------------------

    /** An idempotent-write target exists but one or more of its properties
     * differ from the requested values; the write was skipped rather than
     * silently overwriting. */
    PROPERTY_MISMATCH("propertyMismatch"), //$NON-NLS-1$

    /** export_object: the requested output kind (.epf/.erf, from
     * {@code outputPath}'s extension) disagrees with the project nature or
     * the resolved root object's actual EClass. */
    KIND_MISMATCH("kindMismatch"), //$NON-NLS-1$

    /** Removing an attribute (or other child) would leave dangling form
     * items; retry with {@code cascadeForms=true} to remove them too. */
    REQUIRES_CASCADE_FORMS("requiresCascadeForms"), //$NON-NLS-1$

    /** The named target child does not exist (edit_metadata / dcs_workshop /
     * mxl_workshop "not found" family). */
    NOT_FOUND("notFound"), //$NON-NLS-1$

    /** The named target already exists at the destination (edit_metadata /
     * dcs_workshop / mxl_workshop / infobase-lifecycle "already exists"
     * family). */
    ALREADY_EXISTS("alreadyExists"), //$NON-NLS-1$

    /** A candidate attribute name shadows a platform-standard attribute
     * (Date / Number / Posted / Code / Description / Owner / ...). */
    STANDARD_ATTRIBUTE_CONFLICT("standardAttributeConflict"), //$NON-NLS-1$

    /** The object is on vendor support with editing not allowed; work via an
     * extension instead. */
    SUPPORT_LOCK("supportLock"), //$NON-NLS-1$

    /** {@code addSubsystemContent}'s target FQN does not resolve to an
     * existing configuration object. */
    TARGET_NOT_FOUND("targetNotFound"), //$NON-NLS-1$

    /** An event-subscription {@code handler} string does not resolve to
     * {@code CommonModule.Method}. */
    HANDLER_INVALID("handlerInvalid"), //$NON-NLS-1$

    /** A predefined item's owner object is Adopted (borrowed into an
     * extension), so its predefined set cannot be edited directly. */
    ADOPTED_OWNER("adoptedOwner"), //$NON-NLS-1$

    /** A DCS query (queryText) or spliced query text has parse errors. */
    QUERY_VALIDATION("queryValidation"), //$NON-NLS-1$

    /** A DCS expression (calculated field / total / parameter) has parse
     * errors. */
    EXPRESSION_VALIDATION("expressionValidation"), //$NON-NLS-1$

    /** A privileged common module is not allowed in an extension - the
     * platform rejects it on UpdateDBCfg. */
    PRIVILEGED_NOT_ALLOWED_IN_EXTENSION("privilegedNotAllowedInExtension"), //$NON-NLS-1$

    /** {@code global=true} together with {@code server=true} is not allowed
     * for an extension common module - the platform rejects it on
     * UpdateDBCfg. */
    GLOBAL_SERVER_NOT_ALLOWED_IN_EXTENSION("globalServerNotAllowedInExtension"), //$NON-NLS-1$

    /** An event-subscription {@code handler} names a CommonModule that does
     * not exist in the project. */
    COMMON_MODULE_NOT_FOUND("commonModuleNotFound"), //$NON-NLS-1$

    /** An {@code appearance} value was passed as JSON ({@code {...}} /
     * {@code [...]}) instead of the expected {@code "Name=Value;..."}
     * string (dcs_workshop add_appearance / set_data_set_field_appearance). */
    FONT_COLOR_GUARD("fontColorGuard"), //$NON-NLS-1$

    /** A DCS query has more than one top-level SELECT (English or Russian
     * keyword) or a UNION (English or Russian keyword); the heuristic
     * token-splice editor refuses to touch it. */
    MULTI_STATEMENT_UNSUPPORTED("multiStatementUnsupported"), //$NON-NLS-1$

    /** A DCS factory method (create*) is not exposed on this EDT runtime;
     * the GUI designer is the fallback. */
    DCS_FACTORY_METHOD_NOT_FOUND("dcsFactoryMethodNotFound"), //$NON-NLS-1$

    // -------------------------------------------------------------------
    // Folded set (2026-07 G2 pass) - *ApiNotFound family (bare literal
    // keys only; a dynamically concatenated "xApiNotFound: <detail>"
    // message is a prefix on a larger string and stays a plain literal -
    // see BmFormHelper / FormEventOps)
    // -------------------------------------------------------------------

    /** A form-editing factory method (command interface item, attribute
     * column, dynamic list ext info, ...) is not exposed on this EDT
     * runtime; the GUI form editor is the fallback. */
    FORM_API_NOT_FOUND("formApiNotFound"), //$NON-NLS-1$

    /** A spreadsheet-document (MXL) layout/cell API is not reachable on
     * this EDT runtime (moxel unavailable). */
    MXL_API_NOT_FOUND("mxlApiNotFound"), //$NON-NLS-1$

    /** The EDT {@code cli.api} configuration XML export/import service is
     * not reachable on this EDT runtime. */
    CLI_API_NOT_FOUND("cliApiNotFound"), //$NON-NLS-1$

    /** The extension adopt/borrow probe service is not reachable on this
     * EDT runtime; the GUI workaround hint applies. */
    ADOPT_SERVICE_NOT_FOUND("adoptServiceNotFound"), //$NON-NLS-1$

    // -------------------------------------------------------------------
    // Folded set (2026-07 G2 pass) - misc single-site guards
    // -------------------------------------------------------------------

    /** A breakpoint's condition/hitCount/logpoint options could not be
     * applied because it is a marker-only breakpoint (the EDT BSL
     * breakpoint class could not be loaded on this runtime). */
    OPTIONS_IGNORED("optionsIgnored"), //$NON-NLS-1$

    // -------------------------------------------------------------------
    // Folded set (2026-07 G2 pass) - failureKind discriminator family.
    // These are literal values assigned to a dedicated `failureKind`
    // field on various Bm*Helper Result classes (install/export/list/
    // uninstall extension, create/associate/delete infobase, set
    // credentials, export external object), later surfaced as the JSON
    // tag key itself via `.put(r.failureKind, Boolean.TRUE)`.
    // -------------------------------------------------------------------

    /** install_extension: neither a local {@code .cfe} path, an http(s)://
     * URL, nor a resolvable github:/gh: source was given (or the resolved
     * path does not point to an existing file). */
    INPUT_MISSING("inputMissing"), //$NON-NLS-1$

    /** install_extension: {@code inputPath} could not be parsed as a file
     * path. */
    INVALID_INPUT_PATH("invalidInputPath"), //$NON-NLS-1$

    /** install_extension: a {@code github:owner/repo} source has no
     * matching {@code .cfe} asset in its latest release. */
    GITHUB_ASSET_NOT_FOUND("githubAssetNotFound"), //$NON-NLS-1$

    /** install_extension: resolving the latest GitHub release for a
     * {@code github:owner/repo} source failed. */
    GITHUB_RESOLVE_FAILED("githubResolveFailed"), //$NON-NLS-1$

    /** install_extension: downloading the {@code .cfe} from an http(s)://
     * URL (direct or GitHub-resolved) failed. */
    INPUT_DOWNLOAD_FAILED("inputDownloadFailed"), //$NON-NLS-1$

    /** install_extension: this EDT runtime does not expose the
     * thick-client execution internals required to install an extension. */
    INSTALL_API_NOT_FOUND("installApiNotFound"), //$NON-NLS-1$

    /** export_extension: {@code outputPath} could not be parsed as a file
     * path. */
    INVALID_OUTPUT_PATH("invalidOutputPath"), //$NON-NLS-1$

    /** export_extension: the parent directory of {@code outputPath} could
     * not be created. */
    OUTPUT_DIRECTORY_ERROR("outputDirectoryError"), //$NON-NLS-1$

    /** export_extension: the thick-client call reported success but no
     * file was written at {@code outputPath}. Also used by export_object
     * for the same condition. */
    OUTPUT_MISSING("outputMissing"), //$NON-NLS-1$

    /** install/export/list/uninstall extension (thick-client): the infobase
     * managers (incl. the per-infobase lock service) or the credentials
     * secure-storage manager are not available on this EDT runtime. */
    MANAGER_UNAVAILABLE("managerUnavailable"), //$NON-NLS-1$

    /** set_infobase_credentials / extension thick-client ops: priming the
     * shared secure-storage root failed (it is locked or unavailable). */
    STORAGE_LOCKED("storageLocked"), //$NON-NLS-1$

    /** install/export/list/uninstall extension: the given project name does
     * not resolve to an open project. */
    PROJECT_NOT_FOUND("projectNotFound"), //$NON-NLS-1$

    /** install/export/list/uninstall extension: {@code applicationId} does
     * not resolve, the project has no (or several ambiguous) infobase
     * applications, or the infobase reference is missing. */
    RESOLVE_FAILED("resolveFailed"), //$NON-NLS-1$

    /** install/export/list/uninstall extension: the resolved application is
     * not an infobase application (extension management applies only to
     * infobases). */
    NOT_INFOBASE("notInfobase"), //$NON-NLS-1$

    /** extension thick-client ops: the .cfe/operation requires a platform
     * version that the infobase's associated runtime does not match. */
    PLATFORM_VERSION_MISMATCH("platformVersionMismatch"), //$NON-NLS-1$

    /** extension thick-client ops / export_object: no resolvable
     * 1C:Enterprise platform runtime (with a thick client) for the
     * infobase/project. */
    RUNTIME_NOT_FOUND("runtimeNotFound"), //$NON-NLS-1$

    /** extension thick-client ops: the infobase rejected the stored
     * credentials (authentication / access rights). */
    AUTH_FAILED("authFailed"), //$NON-NLS-1$

    /** extension thick-client ops: the thick-client operation failed for a
     * reason not otherwise classified (infobase locked by a running 1C
     * client, unreachable, extension not found, ...). */
    THICK_CLIENT_FAILED("thickClientFailed"), //$NON-NLS-1$

    /** set_infobase_credentials: writing the settings to secure storage
     * failed. */
    WRITE_FAILED("writeFailed"), //$NON-NLS-1$

    /** set_infobase_credentials: the write succeeded but the confirmation
     * readback failed; the result is still reported as {@code ok}. */
    READBACK_FAILED("readbackFailed"), //$NON-NLS-1$

    /** create_infobase / delete_infobase / associate: the infobase manager
     * could not create/delete/associate the infobase for a reason not
     * otherwise classified. */
    CREATE_FAILED("createFailed"), //$NON-NLS-1$

    /** associate: attaching an existing infobase to a project failed. */
    ASSOCIATE_FAILED("associateFailed"), //$NON-NLS-1$

    /** delete_infobase: removing the infobase (and optionally its content)
     * failed. */
    DELETE_FAILED("deleteFailed"), //$NON-NLS-1$

    /** associate / delete_infobase: no infobase with the given name is
     * registered in EDT's infobase list. */
    INFOBASE_NOT_FOUND("infobaseNotFound"), //$NON-NLS-1$

    /** export_object (external data processor/report): the
     * {@code IExternalObjectDumper} service or the platform-services
     * bundle is not available on this EDT runtime. */
    SERVICE_UNAVAILABLE("serviceUnavailable"), //$NON-NLS-1$

    /** export_object: the base configuration's project has no developing
     * infobase application to build against. */
    NO_INFOBASE("noInfobase"), //$NON-NLS-1$

    /** export_object: the reflective {@code dumper.dump(...)} invocation
     * itself threw, outside the classified failure reasons. */
    INVOCATION("invocation"), //$NON-NLS-1$

    /** export_object: the external-object build failed for a reason not
     * otherwise classified. */
    DUMP_FAILED("dumpFailed"); //$NON-NLS-1$

    private final String wire;

    ErrorTags(String wire)
    {
        this.wire = wire;
    }

    /**
     * The machine-readable tag string emitted in responses.
     *
     * @return the stable wire identifier, never {@code null}
     */
    public String wire()
    {
        return this.wire;
    }

    /**
     * Builds a fresh {@link MetadataGuards.ErrorTag} carrying this tag name and
     * an empty data map, ready for {@code put(...)} chaining.
     *
     * @return a new mutable error tag, never {@code null}
     */
    public MetadataGuards.ErrorTag tag()
    {
        return new MetadataGuards.ErrorTag(this.wire);
    }
}
