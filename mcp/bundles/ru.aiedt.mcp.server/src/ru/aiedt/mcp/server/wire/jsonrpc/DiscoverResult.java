/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

import java.util.List;

/**
 * What {@code server/discover} answers: which protocol revisions this server serves, what it can
 * do, and who it is.
 * <p>
 * From revision {@code 2026-07-28} there is no handshake to carry this, and every server is
 * required to answer this one method. It is also how a client that supports both eras finds out
 * which one it is talking to without guessing: a server of the older era does not know the method
 * and says so, and that answer is itself the signal.
 * </p>
 * <p>
 * The result is cacheable and says so ({@code ttlMs}, {@code cacheScope}), because a client that
 * has to ask this before every conversation pays for the answer every time.
 * </p>
 */
public class DiscoverResult
{
    /** Every ordinary result declares its kind; this one is always a finished answer. */
    private final String resultType = ru.aiedt.mcp.server.wire.McpServerMeta.RESULT_COMPLETE;

    private final List<String> supportedVersions;

    private final Capabilities capabilities;

    private final ServerInfo serverInfo;

    private final String instructions;

    private final long ttlMs;

    private final String cacheScope;

    /**
     * Builds the answer.
     *
     * @param supportedVersions the revisions served, newest first.
     * @param instructions natural-language guidance for a model using this server.
     * @param ttlMs how long the answer stays fresh.
     * @param cacheScope {@code public} when shared caches may hold it, {@code private} otherwise.
     */
    public DiscoverResult(List<String> supportedVersions, String instructions, long ttlMs,
        String cacheScope)
    {
        this.supportedVersions = supportedVersions;
        this.capabilities = new Capabilities();
        this.serverInfo = new ServerInfo();
        this.instructions = instructions;
        this.ttlMs = ttlMs;
        this.cacheScope = cacheScope;
    }

    /**
     * Returns the kind of this result.
     *
     * @return always {@code complete}
     */
    public String getResultType()
    {
        return resultType;
    }

    /**
     * Returns the revisions this server serves.
     *
     * @return the versions, newest first
     */
    public List<String> getSupportedVersions()
    {
        return supportedVersions;
    }

    /**
     * Returns what this server can do.
     *
     * @return the capabilities
     */
    public Capabilities getCapabilities()
    {
        return capabilities;
    }

    /**
     * Returns who this server is.
     * <p>
     * Carried in the result rather than in {@code _meta}, because discovery is the method a client
     * calls precisely when it does not yet know which era it is talking to - so it may well be
     * answered in the shape that carries no {@code _meta} at all. Identity belongs to the answer
     * itself, not to a decoration the caller might not receive.
     * </p>
     *
     * @return the server's name, version and title
     */
    public ServerInfo getServerInfo()
    {
        return serverInfo;
    }

    /**
     * Returns the guidance offered to a model using this server.
     *
     * @return the instructions
     */
    public String getInstructions()
    {
        return instructions;
    }

    /**
     * Returns how long this answer stays fresh.
     *
     * @return the lifetime in milliseconds
     */
    public long getTtlMs()
    {
        return ttlMs;
    }

    /**
     * Returns who may cache this answer.
     *
     * @return {@code public} or {@code private}
     */
    public String getCacheScope()
    {
        return cacheScope;
    }

    /**
     * What this server can do. Tools, and nothing else - the same answer the handshake gives, so
     * the two eras cannot describe the server differently.
     */
    /** Who answered. */
    public static class ServerInfo
    {
        private final String name = ru.aiedt.mcp.server.wire.McpServerMeta.SERVER_NAME;

        private final String version = ru.aiedt.mcp.server.wire.McpServerMeta.PLUGIN_VERSION;

        private final String title = ru.aiedt.mcp.server.support.InstanceRegistry.selfTitle();

        /**
         * @return the server's protocol name
         */
        public String getName()
        {
            return name;
        }

        /**
         * @return the plugin version
         */
        public String getVersion()
        {
            return version;
        }

        /**
         * @return a name that tells this instance from the others on the machine
         */
        public String getTitle()
        {
            return title;
        }
    }

    public static class Capabilities
    {
        private final Tools tools = new Tools();

        private final InitializeResult.Resources resources = new InitializeResult.Resources();

        private final java.util.Map<String, Object> extensions = extensionsOffered();

        /**
         * Returns the tools capability. Its mere presence is the announcement.
         *
         * @return the tools capability
         */
        public Tools getTools()
        {
            return tools;
        }

        /**
         * Returns the resources capability, for the same reason the handshake announces it.
         * <p>
         * Discovery exists so a client can learn what this server does without a handshake. Leaving
         * a capability out of it that the handshake announces would make the two disagree, and a
         * client is entitled to trust either.
         * </p>
         *
         * @return the resources capability
         */
        public InitializeResult.Resources getResources()
        {
            return resources;
        }

        /**
         * Returns the extensions this server implements, keyed by their reverse-DNS names.
         * <p>
         * An extension is announced by being listed; the value carries no settings. A client reads
         * this to decide whether it may ask for the things the extension defines - and this server
         * reads the client's matching declaration before it answers with any of them.
         * </p>
         *
         * @return the extensions offered
         */
        public java.util.Map<String, Object> getExtensions()
        {
            return extensions;
        }

        private static java.util.Map<String, Object> extensionsOffered()
        {
            java.util.Map<String, Object> offered = new java.util.LinkedHashMap<>();
            offered.put(ru.aiedt.mcp.server.wire.McpServerMeta.EXTENSION_TASKS,
                new java.util.LinkedHashMap<String, Object>());
            return offered;
        }

        /** Announced by being there; it carries no settings of its own. */
        public static class Tools
        {
        }
    }
}
