/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a {@code tools/list}: the catalogue a client uses to decide what it can call.
 */
public class ToolsListResult
{
    private final List<ToolInfo> tools = new ArrayList<>();

    /**
     * Adds a tool to the catalogue.
     *
     * @param name the tool name, as it will be called
     * @param description what the tool does
     * @param inputSchema the parameter schema as a parsed JSON tree, so that the client receives a
     *            real object rather than a string holding one
     */
    public void addTool(String name, String description, Object inputSchema)
    {
        tools.add(new ToolInfo(name, description, inputSchema));
    }

    /**
     * Returns the catalogue.
     *
     * @return the tools, never <code>null</code>
     */
    public List<ToolInfo> getTools()
    {
        return tools;
    }

    /**
     * One catalogue entry.
     */
    public static class ToolInfo
    {
        private final String name;

        private final String description;

        private final Object inputSchema;

        /**
         * Creates a catalogue entry.
         *
         * @param name the tool name
         * @param description what the tool does
         * @param inputSchema the parameter schema as a parsed JSON tree
         */
        public ToolInfo(String name, String description, Object inputSchema)
        {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        /**
         * Returns the tool name.
         *
         * @return the name
         */
        public String getName()
        {
            return name;
        }

        /**
         * Returns the description.
         *
         * @return the description
         */
        public String getDescription()
        {
            return description;
        }

        /**
         * Returns the parameter schema.
         *
         * @return the schema tree
         */
        public Object getInputSchema()
        {
            return inputSchema;
        }
    }
}
