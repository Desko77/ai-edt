/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

/**
 * What the configuration knows about the ids a layout records: the type behind a produced-type
 * uuid, the name of a metadata object under that type's owner, and the kind an attribute of a
 * reference type points at.
 *
 * <p>A layout names types by the uuid of a produced type ({@code {"#",<uuid>}} in a pattern is
 * the {@code typeId} of the owner's {@code objectType}, {@code refType}, {@code listType}, ...)
 * and the data an element shows by the uuid of an attribute, a tabular section, a dimension or
 * a resource under the object that type produces. The reader keeps the ids; a tool that has the
 * environment's configuration answers them through this interface, and {@link #NONE} answers
 * nothing, which leaves the ids in the answer as they are.</p>
 */
public interface LayoutNames
{
    /** Resolves nothing. */
    LayoutNames NONE = new LayoutNames()
    {
        @Override
        public String typeName(String producedTypeUuid)
        {
            return null;
        }

        @Override
        public String memberName(String producedTypeUuid, String memberUuid)
        {
            return null;
        }

        @Override
        public String referencedKind(String producedTypeUuid, String memberUuid)
        {
            return null;
        }

        @Override
        public String ownerKind(String producedTypeUuid)
        {
            return null;
        }

        @Override
        public String memberTypeUuid(String producedTypeUuid, String memberUuid)
        {
            return null;
        }

        @Override
        public String memberUuid(String producedTypeUuid, String memberName)
        {
            return null;
        }

        @Override
        public ru.aiedt.mcp.oform.BraceTree.ListNode memberPattern(String producedTypeUuid, String memberUuid)
        {
            return null;
        }
    };

    /**
     * The uuid of a member of an object by name, for a path written in words.
     *
     * @param producedTypeUuid the produced type that names the owner
     * @param memberName the member's name, case-insensitive
     * @return the uuid, lower case, or {@code null}
     */
    String memberUuid(String producedTypeUuid, String memberName);

    /**
     * The type pattern of a member, as an element showing it records its value type:
     * {@code {"Pattern",{"#",<uuid>}}} for a produced type, {@code {"S"}}, {@code {"N"}},
     * {@code {"B"}}, {@code {"D"}} for the primitives.
     *
     * @param producedTypeUuid the produced type that names the owner
     * @param memberUuid the member
     * @return a fresh pattern node, or {@code null} when the member or its types are unknown
     */
    ru.aiedt.mcp.oform.BraceTree.ListNode memberPattern(String producedTypeUuid, String memberUuid);

    /**
     * The produced type a member holds, when the member has one type and that type is produced
     * by an object of the configuration - the object type of a data processor an attribute
     * holds, the reference type of a catalog an attribute points at.
     *
     * @param producedTypeUuid the produced type that names the owner
     * @param memberUuid the member
     * @return the uuid of the member's type, lower case, or {@code null}
     */
    String memberTypeUuid(String producedTypeUuid, String memberUuid);

    /**
     * The kind of the object that produces a type - {@code Catalog} for the object type of a
     * catalog.
     *
     * @param producedTypeUuid the uuid, either case
     * @return the kind in EDT's English singular spelling, or {@code null}
     */
    String ownerKind(String producedTypeUuid);

    /**
     * The type a produced-type uuid stands for, as EDT names it: {@code CatalogObject.Products},
     * {@code DocumentRef.Invoice}, {@code InformationRegisterList.Prices}.
     *
     * @param producedTypeUuid the uuid, either case
     * @return the type name, or {@code null} when the configuration has no such produced type
     */
    String typeName(String producedTypeUuid);

    /**
     * The name of a metadata object under the owner of a produced type.
     *
     * @param producedTypeUuid the produced type that names the owner
     * @param memberUuid the uuid of the attribute, tabular section, dimension, resource or
     *        their attribute
     * @return the name, or {@code null} when nothing under that owner has the uuid
     */
    String memberName(String producedTypeUuid, String memberUuid);

    /**
     * The kind of the object a member of a reference type points at, when the member has one
     * reference type - {@code Catalog} for {@code CatalogRef.Products}.
     *
     * @param producedTypeUuid the produced type that names the owner
     * @param memberUuid the member
     * @return the kind in EDT's English singular spelling, or {@code null} when the member has
     *         no single reference type
     */
    String referencedKind(String producedTypeUuid, String memberUuid);
}
