/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

/**
 * The result of the {@code initialize} handshake: the revision both sides will speak, what this
 * server can do, and who it is.
 */
public class InitializeResult
{
    private final String protocolVersion;

    private final Capabilities capabilities;

    private final ServerInfo serverInfo;

    /**
     * Builds the handshake result.
     *
     * @param protocolVersion the agreed MCP revision
     * @param serverName the server name
     * @param serverVersion the plugin version
     * @param author the attribution string
     * @param title how a person tells this server from the others running beside it
     */
    public InitializeResult(String protocolVersion, String serverName, String serverVersion,
        String author, String title)
    {
        this.protocolVersion = protocolVersion;
        this.capabilities = new Capabilities();
        this.serverInfo = new ServerInfo(serverName, serverVersion, author, title);
    }

    /**
     * Returns the agreed MCP revision.
     *
     * @return the revision string
     */
    public String getProtocolVersion()
    {
        return protocolVersion;
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
     *
     * @return the server identity
     */
    public ServerInfo getServerInfo()
    {
        return serverInfo;
    }

    /**
     * What the server can do. Tools, and nothing else.
     */
    public static class Capabilities
    {
        private final Tools tools = new Tools();

        private final Resources resources = new Resources();

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
         * Returns the resources capability.
         * <p>
         * Announced because the server answers {@code resources/list} and {@code resources/read}.
         * A capability declared without the methods behind it is worse than none: a client takes
         * the announcement as permission to call them.
         * </p>
         *
         * @return the resources capability
         */
        public Resources getResources()
        {
            return resources;
        }
    }

    /**
     * The resources capability.
     * <p>
     * Empty like {@link Tools}: this server neither subscribes clients to changes nor notifies them
     * when the list changes, and announcing either would promise a message that never comes. The
     * documents it serves are packaged with the plugin and change when the plugin does.
     * </p>
     */
    public static class Resources
    {
        // no members: presence is the signal, and the absent sub-features are absent on purpose
    }

    /**
     * The tools capability. Empty on purpose: this server announces no sub-features of it, so the
     * member serializes to <code>{}</code> and clients read it as "tools are supported".
     */
    public static class Tools
    {
        // no members: presence is the signal
    }

    /**
     * The server identity shown in a client's connection list.
     */
    public static class ServerInfo
    {
        private final String name;

        private final String version;

        private final String author;

        private final String title;

        /**
         * Creates the identity.
         *
         * @param name the server name
         * @param version the plugin version
         * @param author the attribution string; a non-standard member that clients ignore
         * @param title the display name, which names the workspace this server has open -
         *            with several EDTs running, the server name alone is the same everywhere
         *            and tells a client nothing about which one it reached
         */
        public ServerInfo(String name, String version, String author, String title)
        {
            this.name = name;
            this.version = version;
            this.author = author;
            this.title = title;
        }

        /**
         * Returns the display name.
         *
         * @return the title
         */
        public String getTitle()
        {
            return title;
        }

        /**
         * Returns the server name.
         *
         * @return the name
         */
        public String getName()
        {
            return name;
        }

        /**
         * Returns the plugin version.
         *
         * @return the version
         */
        public String getVersion()
        {
            return version;
        }

        /**
         * Returns the attribution string.
         *
         * @return the author
         */
        public String getAuthor()
        {
            return author;
        }
    }
}
