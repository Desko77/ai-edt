/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.Blob;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Node;
import ru.aiedt.mcp.oform.BraceTree.Token;

/**
 * The columns of a table, as the layout records them.
 *
 * <p>A column is {@code {<kind uuid>, {<n>, {8, <body>, <segment>, <segment>, <segment>}, <tail>}}}.
 * The kind uuid follows what the table's rows are: a list of an object, a tabular section, a
 * value table or tree. The body holds the caption, the width, the order, the name, the type
 * pattern, the uuid of the cell's control and, when the cell has a control, a blob: the
 * control's own definition as brace text, deflated and prefixed with an eighteen-byte header.
 * The three segments bind the column to the row: a standard attribute by code, a member by uuid,
 * or nothing. Measured over 5511 columns of 751 tables.</p>
 */
public final class TableColumns
{
    /** A column of a table over a list of an object. */
    public static final String LIST = "list"; //$NON-NLS-1$

    /** A column of a table over a tabular section. */
    public static final String TABULAR_SECTION = "tabularSection"; //$NON-NLS-1$

    /** A column of a table over a value table or a value tree. */
    public static final String VALUE_TABLE = "valueTable"; //$NON-NLS-1$

    /** The control of an editable cell. */
    public static final String FIELD = "381ed624-9217-4e63-85db-c4c3cb87daae"; //$NON-NLS-1$

    /** The control of a cell with a check box. */
    public static final String CHECK_BOX = "35af3d93-d7c7-4a2e-a8eb-bac87a1a3f26"; //$NON-NLS-1$

    /** The control of a cell with a choice field. */
    public static final String SELECT_FIELD = "64483e7f-3833-48e2-8c75-2c31aac49f6e"; //$NON-NLS-1$

    /** The control uuid of a column that only shows. */
    public static final String NO_CONTROL = "00000000-0000-0000-0000-000000000000"; //$NON-NLS-1$

    private static final String LIST_UUID = "48a6ebc3-fcc8-4f8f-b399-459fb32aa46b"; //$NON-NLS-1$

    private static final String TABULAR_SECTION_UUID = "c2cf1953-2796-4fe2-b78c-ff84140b124e"; //$NON-NLS-1$

    private static final String VALUE_TABLE_UUID = "737535a4-21e6-4971-8513-3e3173a9fedd"; //$NON-NLS-1$

    /** The bytes every blob opens with. */
    private static final byte[] BLOB_HEADER = { 0x02, 0x01, 0x53, 0x4b, 0x6f, (byte)0xf4, (byte)0x88, (byte)0x8d, (byte)0xc1, 0x4e,
        (byte)0xa0, (byte)0xd5, (byte)0xeb, (byte)0xb6, (byte)0xbd, (byte)0xa0, (byte)0xa7, 0x0d };

    private static final byte[] BOM = { (byte)0xEF, (byte)0xBB, (byte)0xBF };

    private static final int BASE64_LINE = 65;

    private TableColumns()
    {
        // static utility
    }

    /**
     * The kind of a column record.
     *
     * @param uuid the first item of the record
     * @return {@link #LIST}, {@link #TABULAR_SECTION}, {@link #VALUE_TABLE}, or {@code null}
     */
    public static String kindOf(String uuid)
    {
        if (uuid == null)
        {
            return null;
        }
        switch (uuid.toLowerCase(Locale.ROOT))
        {
        case LIST_UUID:
            return LIST;
        case TABULAR_SECTION_UUID:
            return TABULAR_SECTION;
        case VALUE_TABLE_UUID:
            return VALUE_TABLE;
        default:
            return null;
        }
    }

    /**
     * The kind of column a table takes, from what its rows are.
     *
     * @param rowTypeName the name of the table's value type as {@link LayoutNames#typeName}
     *        gives it, or {@code null}
     * @param rowTypeUuid the uuid of that type
     * @return the kind, or {@code null} when the rows are of a type the corpus holds no column for
     */
    public static String kindForRows(String rowTypeName, String rowTypeUuid)
    {
        String platform = TypePattern.platformType(rowTypeUuid);
        if ("ValueTable".equals(platform) || "ValueTree".equals(platform)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return VALUE_TABLE;
        }
        if (rowTypeName == null)
        {
            return null;
        }
        if (rowTypeName.contains("TabularSection")) //$NON-NLS-1$
        {
            return TABULAR_SECTION;
        }
        return rowTypeName.contains("List.") ? LIST : null; //$NON-NLS-1$
    }

    /**
     * The settings block a table carries for its rows - the fourth item of the table's own list.
     *
     * @param rowTypeName the name of the table's value type as {@link LayoutNames#typeName}
     *        gives it, or {@code null}
     * @param rowTypeUuid the uuid of that type
     * @return the block, or {@code null} when the corpus holds no table over such rows
     */
    public static ListNode settingsBlock(String rowTypeName, String rowTypeUuid)
    {
        String platform = TypePattern.platformType(rowTypeUuid);
        String text = null;
        if ("ValueTable".equals(platform)) //$NON-NLS-1$
        {
            text = "{342cf854-134c-42bb-8af9-a2103d5d9723,{5,0,0,1}}"; //$NON-NLS-1$
        }
        else if ("ValueTree".equals(platform)) //$NON-NLS-1$
        {
            text = "{9ab3fa70-d2e0-4e44-baac-730682272ed2,{4,1,0,{0}}}"; //$NON-NLS-1$
        }
        else if (rowTypeName != null && rowTypeName.contains("TabularSection")) //$NON-NLS-1$
        {
            text = "{51d1e122-c0f3-496f-901e-806df8206ba9,{2,1,{0,1}}}"; //$NON-NLS-1$
        }
        else if (rowTypeName != null && rowTypeName.contains("RecordSet.")) //$NON-NLS-1$
        {
            text = "{889101b1-b195-4125-88a8-4c7d6ed6d12b,{7,0,1,{0,1}}}"; //$NON-NLS-1$
        }
        else if (rowTypeName != null && rowTypeName.contains("List.")) //$NON-NLS-1$
        {
            String kind = rowTypeName.substring(0, rowTypeName.indexOf("List.")).toLowerCase(Locale.ROOT); //$NON-NLS-1$
            switch (kind)
            {
            case "catalog": //$NON-NLS-1$
                text = "{99f52caa-7b96-4bd4-a649-aedbd230a555,{12,0,0,1,1,2,1,0,{0},{1,0},{3,1},1}}"; //$NON-NLS-1$
                break;
            case "document": //$NON-NLS-1$
            case "documentjournal": //$NON-NLS-1$
                text = "{8d9c2111-ed81-11d5-b9b6-0050bae0a95d,{2,{0},{3,1,{0,1}}}}"; //$NON-NLS-1$
                break;
            case "chartofcharacteristictypes": //$NON-NLS-1$
                text = "{1685b1cc-4969-4b26-a18f-606e51619e38,{3,0,0,1,1,2,1,0,{0},{1,0},{3,1},1}}"; //$NON-NLS-1$
                break;
            case "exchangeplan": //$NON-NLS-1$
                text = "{ed6303ae-cf75-43a2-a6c9-eba5e30352a7,{1,1,0,{0},{1,0},{3,1}}}"; //$NON-NLS-1$
                break;
            case "informationregister": //$NON-NLS-1$
                text = "{4b4bf134-a7d5-4277-bb6b-8b3a07fc1527,{6,0,0,0,{1,0},{3,1,{0,1}}}}"; //$NON-NLS-1$
                break;
            case "accumulationregister": //$NON-NLS-1$
                text = "{9b4b78c0-21ff-11d6-a3c7-0050bae0a776,{3,{1,0},{3,1,{0,1}}}}"; //$NON-NLS-1$
                break;
            case "task": //$NON-NLS-1$
                text = "{6438f3ff-436d-4690-a134-d62bf247035b,{3,1,1,{0},0,{3,1,{0,1}},0}}"; //$NON-NLS-1$
                break;
            default:
                break;
            }
        }
        return text == null ? null : BraceTree.parse(text);
    }

    /**
     * The template of a column record of a kind, placeholders in.
     *
     * @param kind the kind
     * @return the record
     * @throws IOException when the template is not packaged
     */
    static ListNode template(String kind) throws IOException
    {
        String name = LIST.equals(kind) ? "ColumnList" : TABULAR_SECTION.equals(kind) ? "ColumnTabularSection" : "ColumnValueTable"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return resource(name);
    }

    /**
     * The uuid a column record carries at body position 38, by the control's name.
     *
     * @param control the control's name: {@code field}, {@code checkBox}, {@code selectField}
     *        or {@code none}
     * @return the uuid, or {@code null} when the name names no control
     */
    public static String controlUuid(String control)
    {
        if (control == null)
        {
            return null;
        }
        switch (control)
        {
        case "field": //$NON-NLS-1$
            return FIELD;
        case "checkBox": //$NON-NLS-1$
            return CHECK_BOX;
        case "selectField": //$NON-NLS-1$
            return SELECT_FIELD;
        case "none": //$NON-NLS-1$
            return NO_CONTROL;
        default:
            return null;
        }
    }

    /**
     * The blob of a cell's control: the control's own definition with what the column's type
     * pattern varies in it.
     *
     * <p>Measured over the corpus of 1335 containers. The check box (254 columns: 193 string,
     * 43 boolean, 18 number) carries {@code 1} at {@code [1][3]} on the number columns and
     * {@code 0} on the string and boolean ones. The choice field (21 columns: 10 string, 6
     * untyped, 2 number, 2 reference, 1 boolean) carries {@code 1} at props position 7 on its
     * boolean column and {@code 1} at props position 12 on its reference columns; the choice
     * lists and events some of its columns carry are data of those columns, not of the control.
     * Neither text holds the type pattern itself; the flags above are what the pattern
     * varies.</p>
     *
     * @param control the control's name
     * @param pattern the column's type pattern, {@code {"Pattern",...}}, or {@code null}
     * @return the blob list, {@code {{#base64:...},0}}, or {@code null} for {@code none}
     * @throws IOException when the control's template is not packaged
     */
    public static ListNode controlBlob(String control, ListNode pattern) throws IOException
    {
        List<String> letters = TypePattern.letters(pattern);
        switch (control == null ? "" : control)
        {
        case "field": //$NON-NLS-1$
            return fieldBlob(pattern);
        case "checkBox": //$NON-NLS-1$
        {
            ListNode node = resource("ColumnCheckBoxControl"); //$NON-NLS-1$
            substitute(node, "@NUMBER", new Token(letters.contains("N") ? "1" : "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return packaged(node);
        }
        case "selectField": //$NON-NLS-1$
        {
            ListNode node = resource("ColumnSelectFieldControl"); //$NON-NLS-1$
            substitute(node, "@BOOLEAN", new Token(letters.contains("B") ? "1" : "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            substitute(node, "@REFERENCE", new Token(letters.contains("#") ? "1" : "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return packaged(node);
        }
        default:
            return null;
        }
    }

    /**
     * The blob of a field cell: the control's definition with the column's pattern in it.
     *
     * @param pattern the column's type pattern, {@code {"Pattern",...}}, or {@code null}
     * @return the blob list, {@code {{#base64:...},0}}
     * @throws IOException when the control's template is not packaged
     */
    static ListNode fieldBlob(ListNode pattern) throws IOException
    {
        ListNode control = resource("ColumnControl"); //$NON-NLS-1$
        ListNode typed = pattern != null ? LayoutEditor.copy(pattern) : BraceTree.parse("{\"Pattern\"}"); //$NON-NLS-1$
        substitute(control, "@PATTERN", typed); //$NON-NLS-1$
        // the input length of a string cell sits in the props of the control: the fourteenth
        // item after the style block, as {"S",<length>,...} of the pattern gives it
        ListNode props = LayoutReader.asList(LayoutReader.asList(control.get(2)).get(0));
        Integer length = stringLength(typed);
        if (props != null && props.size() > 14 && length != null)
        {
            props.items().set(14, new Token(Integer.toString(length.intValue())));
        }
        return packaged(control);
    }

    /** The blob list of a control's definition: deflated behind the header, base64 in lines of 65. */
    private static ListNode packaged(ListNode control)
    {
        String text = BraceTree.serialize(control);
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer document = ByteBuffer.allocate(8 + BOM.length + body.length).order(ByteOrder.LITTLE_ENDIAN);
        document.putLong(BOM.length + body.length).put(BOM).put(body);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(document.array());
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(BLOB_HEADER, 0, BLOB_HEADER.length);
        byte[] chunk = new byte[4096];
        while (!deflater.finished())
        {
            int n = deflater.deflate(chunk);
            out.write(chunk, 0, n);
        }
        deflater.end();
        String base64 = Base64.getEncoder().encodeToString(out.toByteArray());
        StringBuilder raw = new StringBuilder("#base64:"); //$NON-NLS-1$
        for (int at = 0; at < base64.length(); at += BASE64_LINE)
        {
            if (at > 0)
            {
                raw.append("\r\n"); //$NON-NLS-1$
            }
            raw.append(base64, at, Math.min(base64.length(), at + BASE64_LINE));
        }
        ListNode blob = new ListNode();
        blob.items().add(new Blob(raw.toString()));
        return LayoutEditor.list(blob, new Token("0")); //$NON-NLS-1$
    }

    /**
     * The brace text inside a cell's blob.
     *
     * @param blob the blob list {@code {{#base64:...},...}}
     * @return the text, or {@code null} when the list holds no blob or it does not inflate
     */
    public static String blobText(ListNode blob)
    {
        ListNode holder = blob == null || blob.size() == 0 ? null : LayoutReader.asList(blob.get(0));
        if (holder == null || holder.size() != 1 || !(holder.get(0) instanceof Blob))
        {
            return null;
        }
        String raw = ((Blob)holder.get(0)).raw().replace("\r", "").replace("\n", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (!raw.startsWith("#base64:")) //$NON-NLS-1$
        {
            return null;
        }
        try
        {
            byte[] bytes = Base64.getDecoder().decode(raw.substring(8));
            Inflater inflater = new Inflater(true);
            inflater.setInput(bytes, BLOB_HEADER.length, bytes.length - BLOB_HEADER.length);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            while (!inflater.finished() && !inflater.needsInput())
            {
                int n = inflater.inflate(chunk);
                if (n == 0 && inflater.needsDictionary())
                {
                    break;
                }
                out.write(chunk, 0, n);
            }
            inflater.end();
            byte[] document = out.toByteArray();
            int offset = 8 + BOM.length;
            return document.length < offset ? null : new String(document, offset, document.length - offset, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException | java.util.zip.DataFormatException e)
        {
            return null;
        }
    }

    private static Integer stringLength(ListNode pattern)
    {
        if (pattern == null || pattern.size() < 2)
        {
            return null;
        }
        ListNode type = LayoutReader.asList(pattern.get(1));
        if (type == null || type.size() < 2 || !"\"S\"".equals(LayoutReader.tokenText(type.get(0)))) //$NON-NLS-1$
        {
            return null;
        }
        return LayoutReader.intOf(type.get(1));
    }

    private static ListNode resource(String name) throws IOException
    {
        try (InputStream in = TableColumns.class.getResourceAsStream("templates/" + name + ".form")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            if (in == null)
            {
                throw new IOException("no template " + name); //$NON-NLS-1$
            }
            return BraceTree.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static void substitute(ListNode node, String placeholder, Node value)
    {
        for (int i = 0; i < node.size(); i++)
        {
            Node item = node.get(i);
            if (item instanceof ListNode)
            {
                substitute((ListNode)item, placeholder, value);
            }
            else if (placeholder.equals(LayoutReader.tokenText(item)))
            {
                node.items().set(i, value);
            }
        }
    }
}
