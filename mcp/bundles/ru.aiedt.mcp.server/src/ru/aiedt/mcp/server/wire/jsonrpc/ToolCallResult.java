/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a {@code tools/call}: what the tool produced, in one of the four shapes MCP defines.
 * <p>
 * A {@code content} array is mandatory even when the real payload rides in
 * {@code structuredContent}, so the structured shape also carries a short placeholder block for
 * clients that only render content.
 * </p>
 * <p>
 * Within a content item, exactly one of {@code text} / {@code resource} is filled in, and within a
 * resource, exactly one of {@code text} / {@code blob}. The other stays <code>null</code> and is not
 * serialized, which is what makes the item a well-formed MCP content block.
 * </p>
 */
public class ToolCallResult
{
    /**
     * Stands in for the payload when the payload is structured. Any short string would do; clients
     * that ignore structured output show this one.
     */
    private static final String STRUCTURED_PLACEHOLDER = "Done"; //$NON-NLS-1$

    private final List<ContentItem> content = new ArrayList<>();

    private Object structuredContent;

    private ToolCallResult()
    {
        // use the factories
    }

    /**
     * Builds a plain text result.
     *
     * @param text what the tool produced
     * @return the result
     */
    public static ToolCallResult text(String text)
    {
        ToolCallResult result = new ToolCallResult();
        result.content.add(ContentItem.text(text));
        return result;
    }

    /**
     * Builds a structured result: the real payload as a JSON tree, plus a placeholder text block.
     *
     * @param structuredContent the payload, already parsed; serialized by its runtime type
     * @return the result
     */
    public static ToolCallResult json(Object structuredContent)
    {
        ToolCallResult result = new ToolCallResult();
        result.content.add(ContentItem.text(STRUCTURED_PLACEHOLDER));
        result.structuredContent = structuredContent;
        return result;
    }

    /**
     * Builds a result carrying a textual document as an embedded resource.
     *
     * @param uri the resource URI
     * @param mimeType the media type of the document
     * @param text the document
     * @return the result
     */
    public static ToolCallResult resource(String uri, String mimeType, String text)
    {
        ToolCallResult result = new ToolCallResult();
        result.content.add(ContentItem.resource(uri, mimeType, text, null));
        return result;
    }

    /**
     * Builds a result carrying a binary document as an embedded resource.
     *
     * @param uri the resource URI
     * @param mimeType the media type of the document
     * @param base64Blob the document, base64-encoded
     * @return the result
     */
    public static ToolCallResult resourceBlob(String uri, String mimeType, String base64Blob)
    {
        ToolCallResult result = new ToolCallResult();
        result.content.add(ContentItem.resource(uri, mimeType, null, base64Blob));
        return result;
    }

    /**
     * Returns the content blocks.
     *
     * @return the content, never <code>null</code>
     */
    public List<ContentItem> getContent()
    {
        return content;
    }

    /**
     * Returns the structured payload.
     *
     * @return the payload, or <code>null</code> when the result is not structured
     */
    public Object getStructuredContent()
    {
        return structuredContent;
    }

    /**
     * One block of tool output: either text or an embedded resource.
     */
    public static class ContentItem
    {
        private static final String TYPE_TEXT = "text"; //$NON-NLS-1$

        private static final String TYPE_RESOURCE = "resource"; //$NON-NLS-1$

        private final String type;

        private final String text;

        private final ResourceInfo resource;

        private ContentItem(String type, String text, ResourceInfo resource)
        {
            this.type = type;
            this.text = text;
            this.resource = resource;
        }

        /**
         * Builds a text block.
         *
         * @param text the text
         * @return the block
         */
        public static ContentItem text(String text)
        {
            return new ContentItem(TYPE_TEXT, text, null);
        }

        /**
         * Builds an embedded-resource block. Pass the document in exactly one of the two payload
         * arguments.
         *
         * @param uri the resource URI
         * @param mimeType the media type
         * @param text the textual document, or <code>null</code>
         * @param blob the base64 document, or <code>null</code>
         * @return the block
         */
        public static ContentItem resource(String uri, String mimeType, String text, String blob)
        {
            return new ContentItem(TYPE_RESOURCE, null, new ResourceInfo(uri, mimeType, text, blob));
        }

        /**
         * Returns the discriminator a client switches on.
         *
         * @return {@code text} or {@code resource}
         */
        public String getType()
        {
            return type;
        }

        /**
         * Returns the text of a text block.
         *
         * @return the text, or <code>null</code> on a resource block
         */
        public String getText()
        {
            return text;
        }

        /**
         * Returns the resource of a resource block.
         *
         * @return the resource, or <code>null</code> on a text block
         */
        public ResourceInfo getResource()
        {
            return resource;
        }
    }

    /**
     * An embedded resource: a named document a client can open, render or save.
     */
    public static class ResourceInfo
    {
        private final String uri;

        private final String mimeType;

        private final String text;

        private final String blob;

        /**
         * Creates a resource. Exactly one of the two payload arguments carries the document; the
         * other is <code>null</code> and will not appear on the wire.
         *
         * @param uri the resource URI
         * @param mimeType the media type
         * @param text the textual document, or <code>null</code>
         * @param blob the base64 document, or <code>null</code>
         */
        public ResourceInfo(String uri, String mimeType, String text, String blob)
        {
            this.uri = uri;
            this.mimeType = mimeType;
            this.text = text;
            this.blob = blob;
        }

        /**
         * Returns the resource URI.
         *
         * @return the URI
         */
        public String getUri()
        {
            return uri;
        }

        /**
         * Returns the media type.
         *
         * @return the media type
         */
        public String getMimeType()
        {
            return mimeType;
        }

        /**
         * Returns the textual document.
         *
         * @return the document, or <code>null</code> for a binary resource
         */
        public String getText()
        {
            return text;
        }

        /**
         * Returns the binary document.
         *
         * @return the base64 document, or <code>null</code> for a textual resource
         */
        public String getBlob()
        {
            return blob;
        }
    }
}
