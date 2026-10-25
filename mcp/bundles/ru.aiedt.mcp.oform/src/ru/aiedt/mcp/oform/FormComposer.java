/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.Anchors;
import ru.aiedt.mcp.oform.layout.CommandPanels;
import ru.aiedt.mcp.oform.layout.ElementStyle;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutFactory;
import ru.aiedt.mcp.oform.layout.MainAttributes;
import ru.aiedt.mcp.oform.layout.TableColumns;
import ru.aiedt.mcp.oform.layout.TypePattern;
import ru.aiedt.mcp.oform.ops.ColumnControls;
import ru.aiedt.mcp.server.wire.JsonUtils;

/**
 * Lays an ordinary form out from an {@link OwnerModel}, and only through the editor: elements,
 * pages, columns, boxes, anchors, command panels and formats. A refusal of the editor is an
 * error of the composition, not a warning.
 *
 * <p>The geometry is fixed. The margin is 8. A label is {@code min(240, 7 * synonym + 8)} wide,
 * and a synonym longer than 33 characters is repeated in the label's tooltip. A field runs from
 * the label to the right edge of its column and is at least 150 wide. A row is 25. Two columns
 * need a form at least {@code 2 * (8 + 240 + 8 + 150) + 8 = 820} wide. A header taller than 400
 * (more than 16 rows in a column) moves into a panel of height 400 whose pages hold 15 rows.
 * Tables are at least 150 high. A form without an explicit size is 500 wide (820 with two
 * columns) and as tall as its content, up to 700.</p>
 */
public final class FormComposer
{
    /** The gap between a form's edge and the first element, and between a label and its field. */
    static final int MARGIN = 8;

    /** How tall one header row is, and how far the next row starts. */
    static final int ROW = 25;

    /** The visible height of a field inside its row, short of the next row so the two do not meet. */
    private static final int FIELD_HEIGHT = 19;

    /** The tallest label. {@code 7 * 33 + 8 = 239}, so a synonym of 33 characters still fits. */
    private static final int LABEL_MAX = 240;

    /** Pixels a synonym character takes. A label of n characters needs {@code 7 * n}. */
    private static final int LABEL_CHAR = 7;

    /** The narrowest field. */
    private static final int FIELD_MIN = 150;

    /** A command panel's height, and the height of a table's own panel. */
    private static final int PANEL_HEIGHT = 25;

    /** The shortest table. */
    private static final int TABLE_MIN = 150;

    /** Header rows past this move into the header panel. 16 rows are 400 tall. */
    private static final int HEADER_ROWS = 16;

    /** The header panel's height, and the point past which the header leaves the form. */
    private static final int HEADER_PANEL = 400;

    /** Rows of one column on one page of the header panel. */
    private static final int PAGE_ROWS = 15;

    /**
     * The room a panel keeps above its pages for the tab strip: 90 of the 124 tabbed panels of
     * the corpus stand this much taller than the deepest element on their pages.
     */
    private static final int TAB_STRIP = 26;

    /** The narrowest form, and the narrowest explicit width. */
    static final int WIDTH_MIN = 500;

    /** The widest explicit width. */
    static final int WIDTH_MAX = 1000;

    /** The shortest explicit height. An implicit height is at least this too. */
    static final int HEIGHT_MIN = 350;

    /** The tallest form. Tables that do not fit under it are refused. */
    static final int HEIGHT_MAX = 700;

    /**
     * The narrowest form with two columns: two times (margin, the widest label, the gap, the
     * narrowest field) and the closing margin.
     */
    static final int TWO_COLUMN_MIN = 2 * (MARGIN + LABEL_MAX + MARGIN + FIELD_MIN) + MARGIN;

    private static final String HEADER_PANEL_NAME = "Шапка"; //$NON-NLS-1$

    private static final String PAGES_PANEL_NAME = "ТабличныеЧасти"; //$NON-NLS-1$

    private static final String FORM_PANEL_NAME = "ОсновныеДействияФормы"; //$NON-NLS-1$

    private static final String MAIN_ATTRIBUTE = "0"; //$NON-NLS-1$

    private FormComposer()
    {
        // static utility
    }

    /** What the composition produced, or why it refused. */
    public static final class Result
    {
        public final boolean ok;

        /** The refusal, or {@code null}. */
        public final String error;

        /** The layout tree, or {@code null} when refused. */
        public final ListNode layout;

        public final int width;

        public final int height;

        /** 1 or 2, or 0 when the form has no header. */
        public final int columns;

        /** {@code pages}, {@code below} or {@code none}. */
        public final String tabularSections;

        /** The names of the fields, check boxes and choice fields that were placed. */
        public final List<String> fields;

        /** The names of the tables that were placed. */
        public final List<String> tables;

        /** {@code Table.Column} of every column whose type has no measured control. */
        public final List<String> plainColumns;

        /** Set when {@code withFields} and {@code withTables} do nothing. */
        public final String note;

        private Result(boolean ok, String error, ListNode layout, int width, int height, int columns,
            String tabularSections, List<String> fields, List<String> tables, List<String> plainColumns, String note)
        {
            this.ok = ok;
            this.error = error;
            this.layout = layout;
            this.width = width;
            this.height = height;
            this.columns = columns;
            this.tabularSections = tabularSections;
            this.fields = fields;
            this.tables = tables;
            this.plainColumns = plainColumns;
            this.note = note;
        }

        static Result refused(String error)
        {
            return new Result(false, error, null, 0, 0, 0, "none", List.of(), List.of(), List.of(), null); //$NON-NLS-1$
        }
    }

    /**
     * Lays the form out.
     *
     * @param owner the description, not the EDT model
     * @param params {@code purpose}, {@code title}, {@code language}, {@code width}, {@code height},
     *        {@code withFields}, {@code withTables}, {@code withCommands}, {@code columns}
     *        ({@code 1}, {@code 2} or {@code auto}), {@code tabularSections} ({@code pages} or
     *        {@code below})
     * @return the layout, or the refusal
     */
    public static Result compose(OwnerModel owner, Map<String, String> params)
    {
        try
        {
            return layOut(owner, params);
        }
        catch (ComposeException e)
        {
            return Result.refused(e.getMessage());
        }
    }

    private static Result layOut(OwnerModel owner, Map<String, String> params)
    {
        if (owner == null)
        {
            throw new ComposeException("create_form has no owner to lay the form out from."); //$NON-NLS-1$
        }
        MainAttributes.Purpose purpose = MainAttributes.purposeOf(text(params, "purpose"), owner.kind); //$NON-NLS-1$
        if (purpose == null)
        {
            throw new ComposeException("Unknown purpose " + text(params, "purpose") //$NON-NLS-1$ //$NON-NLS-2$
                + "; known: ItemForm, ListForm, ChoiceForm, FolderForm, RecordForm, RecordSetForm, None."); //$NON-NLS-1$
        }
        MainAttributes.Spec main = purpose == MainAttributes.Purpose.NONE ? null : MainAttributes.of(owner.kind, purpose);
        if (purpose != MainAttributes.Purpose.NONE && main == null)
        {
            throw new ComposeException("No " + purpose.name().toLowerCase(Locale.ROOT) + " form was measured for a " //$NON-NLS-1$ //$NON-NLS-2$
                + owner.kind + "; purposes: " + MainAttributes.purposesOf(owner.kind) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String language = text(params, "language"); //$NON-NLS-1$
        if (language == null)
        {
            language = "ru"; //$NON-NLS-1$
        }
        String title = text(params, "title"); //$NON-NLS-1$
        if (title == null)
        {
            title = owner.name;
        }
        boolean objectLike = purpose == MainAttributes.Purpose.OBJECT || purpose == MainAttributes.Purpose.FOLDER
            || purpose == MainAttributes.Purpose.RECORD;
        boolean listLike = purpose == MainAttributes.Purpose.LIST || purpose == MainAttributes.Purpose.CHOICE;
        boolean recordSet = purpose == MainAttributes.Purpose.RECORD_SET;
        Boolean fieldsFlag = flag(params, "withFields"); //$NON-NLS-1$
        Boolean tablesFlag = flag(params, "withTables"); //$NON-NLS-1$
        Boolean commandsFlag = flag(params, "withCommands"); //$NON-NLS-1$
        boolean withFields = fieldsFlag == null ? objectLike : fieldsFlag.booleanValue();
        boolean withTables = tablesFlag == null ? purpose == MainAttributes.Purpose.OBJECT && !owner.tabularSections.isEmpty()
            : tablesFlag.booleanValue();
        boolean withCommands = commandsFlag == null ? purpose != MainAttributes.Purpose.NONE : commandsFlag.booleanValue();
        String note = null;
        if (purpose == MainAttributes.Purpose.NONE)
        {
            withFields = false;
            withTables = false;
            if (commandsFlag == null)
            {
                withCommands = false;
            }
            note = "withFields and withTables do nothing on a form without a main attribute"; //$NON-NLS-1$
        }
        List<OwnerModel.Attribute> header = withFields ? owner.header(purpose) : List.of();
        List<OwnerModel.TabularSection> sections = withTables ? owner.tables(purpose) : List.of();
        int columnCount = columnsOf(params, header.size(), objectLike && withFields);
        Integer widthArg = explicit(params, "width", WIDTH_MIN, WIDTH_MAX, "width"); //$NON-NLS-1$ //$NON-NLS-2$
        Integer heightArg = explicit(params, "height", HEIGHT_MIN, HEIGHT_MAX, "height"); //$NON-NLS-1$ //$NON-NLS-2$
        int width = widthArg == null ? columnCount == 2 ? TWO_COLUMN_MIN : WIDTH_MIN : widthArg.intValue();
        if (columnCount == 2 && width < TWO_COLUMN_MIN)
        {
            throw new ComposeException("two columns need a width of at least " + TWO_COLUMN_MIN); //$NON-NLS-1$
        }
        String mode = tabularMode(params, sections.size());
        int rows = header.isEmpty() || columnCount == 0 ? 0 : (header.size() + columnCount - 1) / columnCount;
        boolean headerPanel = rows > HEADER_ROWS;
        int contentBottom = headerBottom(header.isEmpty(), rows, headerPanel);
        int formPanel = withCommands ? PANEL_HEIGHT : 0;
        int height = heightOf(heightArg, contentBottom, sections.size(), mode, listLike || recordSet, formPanel, withCommands);
        String typeId = main == null ? null : owner.producedUuid(main.producedType);
        if (main != null && (typeId == null || typeId.isEmpty()))
        {
            throw new ComposeException(owner.fqn + " has no produced type " + main.producedType + " for the main attribute " //$NON-NLS-1$ //$NON-NLS-2$
                + main.name + "."); //$NON-NLS-1$
        }
        ListNode root = LayoutFactory.newLayout(title, width, height, language, main, typeId);
        LayoutEditor editor = LayoutEditor.over(root, owner.kind, language);
        Work work = new Work(editor, owner, width, height, language, withCommands, columnCount);
        if (listLike)
        {
            placeList(work, owner.listColumns(), owner.listTypeUuid, owner.listTypeName, main.name);
        }
        else if (recordSet)
        {
            placeList(work, owner.recordColumns(), owner.recordSetTypeUuid, owner.recordSetTypeName, main.name);
        }
        else
        {
            if (!header.isEmpty())
            {
                placeHeader(work, header, headerPanel);
            }
            if (!sections.isEmpty())
            {
                if ("pages".equals(mode)) //$NON-NLS-1$
                {
                    placePages(work, sections, contentBottom);
                }
                else
                {
                    placeBelow(work, sections, contentBottom);
                }
            }
        }
        if (withCommands)
        {
            placeFormPanel(work, listLike || recordSet);
        }
        return new Result(true, null, root, width, height, header.isEmpty() ? 0 : columnCount,
            sections.isEmpty() && !listLike && !recordSet ? "none" : listLike || recordSet ? "below" : mode, //$NON-NLS-1$ //$NON-NLS-2$
            List.copyOf(work.fields), List.copyOf(work.tables), List.copyOf(work.plain), note);
    }

    private static int columnsOf(Map<String, String> params, int headerSize, boolean laysHeader)
    {
        String value = text(params, "columns"); //$NON-NLS-1$
        if (value == null || "auto".equalsIgnoreCase(value)) //$NON-NLS-1$
        {
            if (!laysHeader)
            {
                return 1;
            }
            return headerSize > 12 ? 2 : 1;
        }
        if ("1".equals(value)) //$NON-NLS-1$
        {
            return 1;
        }
        if ("2".equals(value)) //$NON-NLS-1$
        {
            return 2;
        }
        throw new ComposeException("columns must be 1, 2 or auto."); //$NON-NLS-1$
    }

    private static String tabularMode(Map<String, String> params, int sections)
    {
        String value = text(params, "tabularSections"); //$NON-NLS-1$
        if (value == null)
        {
            return sections >= 2 ? "pages" : "below"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("pages".equalsIgnoreCase(value)) //$NON-NLS-1$
        {
            return "pages"; //$NON-NLS-1$
        }
        if ("below".equalsIgnoreCase(value)) //$NON-NLS-1$
        {
            return "below"; //$NON-NLS-1$
        }
        throw new ComposeException("tabularSections must be pages or below."); //$NON-NLS-1$
    }

    /** Where the tables start: under the header, or the top margin when the header is absent. */
    private static int headerBottom(boolean noHeader, int rows, boolean panel)
    {
        if (noHeader)
        {
            return MARGIN;
        }
        if (panel)
        {
            return MARGIN + HEADER_PANEL + (headerPages(rows) > 1 ? TAB_STRIP : 0);
        }
        return MARGIN + rows * ROW;
    }

    /**
     * @param rows the rows the header takes
     * @return the pages the header panel is cut into
     */
    private static int headerPages(int rows)
    {
        return (rows + PAGE_ROWS - 1) / PAGE_ROWS;
    }

    private static int heightOf(Integer explicit, int contentBottom, int sections, String mode, boolean fill,
        int formPanel, boolean withCommands)
    {
        if (fill)
        {
            int needed = TABLE_MIN + formPanel;
            if (explicit != null && explicit.intValue() < needed)
            {
                throw new ComposeException("the table needs a height of at least " + needed); //$NON-NLS-1$
            }
            return explicit == null ? Math.max(HEIGHT_MIN, needed) : explicit.intValue();
        }
        int stack = 0;
        if (sections > 0 && "below".equals(mode)) //$NON-NLS-1$
        {
            stack = sections * (TABLE_MIN + (withCommands ? PANEL_HEIGHT : 0));
        }
        else if (sections > 0)
        {
            stack = TABLE_MIN + (withCommands ? PANEL_HEIGHT : 0);
        }
        int needed = contentBottom + stack + formPanel;
        int limit = explicit == null ? HEIGHT_MAX : explicit.intValue();
        if (needed > limit)
        {
            if (sections > 0 && "below".equals(mode)) //$NON-NLS-1$
            {
                throw new ComposeException("the tables do not fit one under another within a height of " + limit //$NON-NLS-1$
                    + "; pass tabularSections=pages"); //$NON-NLS-1$
            }
            throw new ComposeException("the content needs a height of at least " + needed); //$NON-NLS-1$
        }
        if (explicit != null)
        {
            return explicit.intValue();
        }
        return Math.max(HEIGHT_MIN, Math.min(HEIGHT_MAX, Math.max(needed, HEIGHT_MIN)));
    }

    private static void placeHeader(Work work, List<OwnerModel.Attribute> header, boolean panel)
    {
        String parent = null;
        if (panel)
        {
            int perPage = PAGE_ROWS * work.columns;
            int pages = (header.size() + perPage - 1) / perPage;
            // a panel of several pages draws a tab per page, and the strip takes its room off the top
            int[] box = { 0, MARGIN, work.width, MARGIN + HEADER_PANEL + (pages > 1 ? TAB_STRIP : 0) };
            must(work.editor.addElement("Panel", HEADER_PANEL_NAME, null, null, box, null, null, null)); //$NON-NLS-1$
            anchorRight(work, HEADER_PANEL_NAME, work.width);
            for (int page = 1; page < pages; page++)
            {
                String pageName = "Страница" + (page + 1); //$NON-NLS-1$
                must(work.editor.addPage(HEADER_PANEL_NAME, pageName, pageName));
            }
            parent = HEADER_PANEL_NAME;
        }
        int perPage = panel ? PAGE_ROWS * work.columns : header.size();
        for (int i = 0; i < header.size(); i++)
        {
            int onPage = panel ? i % perPage : i;
            int pageIndex = panel ? i / perPage : 0;
            String page = pageName(panel, pageIndex);
            int column = onPage % work.columns;
            int row = onPage / work.columns;
            int top = MARGIN + row * ROW;
            placeHeaderItem(work, header.get(i), parent, page, column, top, work.width);
        }
    }

    private static String pageName(boolean panel, int pageIndex)
    {
        if (!panel)
        {
            return null;
        }
        return pageIndex == 0 ? LayoutEditor.BORN_PAGE_NAME : "Страница" + (pageIndex + 1); //$NON-NLS-1$
    }

    private static void placeHeaderItem(Work work, OwnerModel.Attribute attribute, String parent, String page, int column,
        int top, int spaceWidth)
    {
        int columnRight = columnRight(column, work.columns, spaceWidth);
        int left = columnLeft(column, spaceWidth, work.columns) + MARGIN;
        boolean stretch = work.columns == 1 || column == work.columns - 1;
        String synonym = attribute.synonym == null || attribute.synonym.isEmpty() ? attribute.name : attribute.synonym;
        if (isBoolean(attribute.pattern))
        {
            int right = columnRight;
            int[] box = { left, top, right, top + FIELD_HEIGHT };
            mustWide(attribute.name, right - left);
            LayoutEditor.Outcome added = must(work.editor.addElement("CheckBox", attribute.name, parent, page, box, //$NON-NLS-1$
                synonym, null, copy(attribute.pattern)));
            bind(work, added.elementId, attribute);
            if (stretch)
            {
                anchorRight(work, attribute.name, right);
            }
            work.fields.add(attribute.name);
            return;
        }
        int labelWidth = Math.min(LABEL_MAX, LABEL_CHAR * synonym.length() + MARGIN);
        int labelRight = left + labelWidth;
        String tooltip = synonym.length() > 33 ? synonym : null;
        String labelName = "Надпись" + attribute.name; //$NON-NLS-1$
        int[] labelBox = { left, top, labelRight, top + FIELD_HEIGHT };
        must(work.editor.addElement("Label", labelName, parent, page, labelBox, synonym + ":", tooltip, null)); //$NON-NLS-1$ //$NON-NLS-2$
        int fieldLeft = labelRight + MARGIN;
        int fieldRight = columnRight;
        mustWide(attribute.name, fieldRight - fieldLeft);
        String type = fieldType(attribute.pattern);
        int[] fieldBox = { fieldLeft, top, fieldRight, top + FIELD_HEIGHT };
        LayoutEditor.Outcome field = must(work.editor.addElement(type, attribute.name, parent, page, fieldBox, null, null,
            copy(attribute.pattern)));
        bind(work, field.elementId, attribute);
        String format = "Field".equals(type) ? formatOf(attribute.pattern) : null; //$NON-NLS-1$
        if (format != null)
        {
            must(ElementStyle.setFormat(work.editor.root(), attribute.name, format, work.language));
        }
        anchorEdge(work, labelName, "right", attribute.name, "left", labelRight, fieldLeft); //$NON-NLS-1$ //$NON-NLS-2$
        if (stretch)
        {
            anchorRight(work, attribute.name, fieldRight);
        }
        work.fields.add(attribute.name);
    }

    private static void placeBelow(Work work, List<OwnerModel.TabularSection> sections, int yStart)
    {
        int chrome = work.withCommands ? PANEL_HEIGHT : 0;
        int formPanel = work.withCommands ? PANEL_HEIGHT : 0;
        int space = work.height - yStart - formPanel;
        int extra = space - sections.size() * (TABLE_MIN + chrome);
        int y = yStart;
        for (int i = 0; i < sections.size(); i++)
        {
            OwnerModel.TabularSection section = sections.get(i);
            int tableHeight = TABLE_MIN + extra / sections.size() + (i == sections.size() - 1 ? extra % sections.size() : 0);
            if (work.withCommands)
            {
                placeTablePanel(work, section.name, y);
                y += PANEL_HEIGHT;
            }
            placeTable(work, section, null, null, y, tableHeight, i == sections.size() - 1);
            y += tableHeight;
        }
    }

    private static void placePages(Work work, List<OwnerModel.TabularSection> sections, int yStart)
    {
        int formPanel = work.withCommands ? PANEL_HEIGHT : 0;
        int bottom = work.height - formPanel;
        int[] box = { 0, yStart, work.width, bottom };
        must(work.editor.addElement("Panel", PAGES_PANEL_NAME, null, null, box, null, null, null)); //$NON-NLS-1$
        anchorRight(work, PAGES_PANEL_NAME, work.width);
        anchorBottom(work, PAGES_PANEL_NAME, bottom);
        work.stretch = PAGES_PANEL_NAME;
        // the panel is born with one page of the editor's own name; the first section takes it, so
        // every section sits under a tab named after it and a section named Страница1 collides with none
        must(work.editor.renamePage(PAGES_PANEL_NAME, LayoutEditor.BORN_PAGE_NAME, sections.get(0).name,
            sectionTitle(sections.get(0))));
        for (int i = 1; i < sections.size(); i++)
        {
            String page = sections.get(i).name;
            must(work.editor.addPage(PAGES_PANEL_NAME, page, sectionTitle(sections.get(i))));
        }
        // the tab strip takes its room off the top of every page, so the content of a tabbed
        // panel fits into that much less than the panel's own height
        int innerHeight = bottom - yStart - (sections.size() > 1 ? TAB_STRIP : 0);
        int tableTop = work.withCommands ? PANEL_HEIGHT : 0;
        int tableHeight = innerHeight - tableTop;
        for (int i = 0; i < sections.size(); i++)
        {
            OwnerModel.TabularSection section = sections.get(i);
            String page = section.name;
            if (work.withCommands)
            {
                placeTablePanel(work, section.name, PAGES_PANEL_NAME, page, 0);
            }
            placeTable(work, section, PAGES_PANEL_NAME, page, tableTop, tableHeight, true);
        }
    }

    private static void placeList(Work work, List<OwnerModel.Attribute> columns, String typeUuid, String typeName,
        String tableName)
    {
        if (typeUuid == null || typeName == null)
        {
            throw new ComposeException("the form has no produced type for its table"); //$NON-NLS-1$
        }
        int formPanel = work.withCommands ? PANEL_HEIGHT : 0;
        int bottom = work.height - formPanel;
        int[] box = { 0, 0, work.width, bottom };
        ListNode pattern = TypePattern.ofProducedType(typeUuid);
        ListNode settings = TableColumns.settingsBlock(typeName, typeUuid);
        if (settings == null)
        {
            // no table of the corpus shows rows of this type, so a list form over it is not
            // something the composer can lay out - said plainly rather than as an internal fault
            throw new ComposeException("a list form over " + typeName //$NON-NLS-1$
                + " is not laid out: no table of the corpus shows rows of that type, so there is no " //$NON-NLS-1$
                + "measured settings block to copy. Create the form with purpose=None and add the " //$NON-NLS-1$
                + "table by hand."); //$NON-NLS-1$
        }
        LayoutEditor.Outcome added = must(work.editor.addElement("Table", tableName, null, null, box, null, null, pattern, //$NON-NLS-1$
            settings));
        must(work.editor.bindData(added.elementId, List.of(new FormLayout.Segment(FormLayout.Segment.ATTRIBUTE, MAIN_ATTRIBUTE))));
        anchorRight(work, tableName, work.width);
        anchorBottom(work, tableName, bottom);
        work.stretch = tableName;
        work.tables.add(tableName);
        String kind = columnKind(typeName, typeUuid);
        for (OwnerModel.Attribute column : columns)
        {
            addColumn(work, tableName, kind, column);
        }
    }

    private static void placeTable(Work work, OwnerModel.TabularSection section, String parent, String page, int top,
        int tableHeight, boolean stretchBottom)
    {
        String tableName = section.name;
        int right = work.width - MARGIN;
        int[] box = { MARGIN, top, right, top + tableHeight };
        ListNode pattern = TypePattern.ofProducedType(section.rowTypeUuid);
        ListNode settings = TableColumns.settingsBlock(section.rowTypeName, section.rowTypeUuid);
        if (settings == null)
        {
            throw new ComposeException("no settings block for rows " + section.rowTypeName); //$NON-NLS-1$
        }
        LayoutEditor.Outcome added = must(work.editor.addElement("Table", tableName, parent, page, box, null, null, pattern, //$NON-NLS-1$
            settings));
        must(work.editor.bindData(added.elementId, List.of(new FormLayout.Segment(FormLayout.Segment.ATTRIBUTE, MAIN_ATTRIBUTE),
            new FormLayout.Segment(FormLayout.Segment.MEMBER, section.uuid))));
        anchorRight(work, tableName, right);
        if (stretchBottom && parent == null)
        {
            anchorBottom(work, tableName, top + tableHeight);
            work.stretch = tableName;
        }
        else if (stretchBottom)
        {
            // a table on a page keeps the bottom of its panel; the panel itself is anchored to the form
            anchorBottom(work, tableName, top + tableHeight);
        }
        work.tables.add(tableName);
        String kind = columnKind(section.rowTypeName, section.rowTypeUuid);
        for (OwnerModel.Attribute column : section.attributes)
        {
            addColumn(work, tableName, kind, column);
        }
        if (work.withCommands)
        {
            must(CommandPanels.setActionSource(work.editor.root(), panelName(tableName), tableName, null));
        }
    }

    private static void placeTablePanel(Work work, String tableName, int top)
    {
        placeTablePanel(work, tableName, null, null, top);
    }

    private static void placeTablePanel(Work work, String tableName, String parent, String page, int top)
    {
        int right = parent == null ? work.width - MARGIN : work.width - MARGIN;
        int[] box = { MARGIN, top, right, top + PANEL_HEIGHT };
        must(work.editor.addElement("CommandPanel", panelName(tableName), parent, page, box, null, null, null)); //$NON-NLS-1$
        anchorRight(work, panelName(tableName), right);
    }

    /** The form's own panel, pressed to the bottom edge. A list panel takes its actions from the table. */
    private static void placeFormPanel(Work work, boolean fromTable)
    {
        int top = work.height - PANEL_HEIGHT;
        int[] box = { 0, top, work.width, work.height };
        if (fromTable)
        {
            must(work.editor.addElement("CommandPanel", FORM_PANEL_NAME, null, null, box, null, null, null)); //$NON-NLS-1$
            if (work.stretch != null)
            {
                must(CommandPanels.setActionSource(work.editor.root(), FORM_PANEL_NAME, work.stretch, null));
            }
        }
        else
        {
            must(work.editor.addStandardActions(FORM_PANEL_NAME, box));
        }
        anchorRight(work, FORM_PANEL_NAME, work.width);
        anchorBottom(work, FORM_PANEL_NAME, work.height);
        if (work.stretch != null)
        {
            anchorEdge(work, FORM_PANEL_NAME, "top", work.stretch, "bottom", top, top); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void addColumn(Work work, String tableName, String kind, OwnerModel.Attribute attribute)
    {
        if (!attribute.placeable())
        {
            return;
        }
        String control = controlFor(attribute.pattern);
        List<FormLayout.Segment> path = new ArrayList<>();
        if (attribute.uuid != null)
        {
            path.add(new FormLayout.Segment(FormLayout.Segment.MEMBER, attribute.uuid));
        }
        else
        {
            path.add(new FormLayout.Segment(FormLayout.Segment.STANDARD, Integer.toString(attribute.standardCode.intValue())));
        }
        String caption = attribute.synonym == null || attribute.synonym.isEmpty() ? attribute.name : attribute.synonym;
        must(work.editor.addColumn(tableName, kind, attribute.name, caption, null, copy(attribute.pattern), path, null, control));
        if ("none".equals(control)) //$NON-NLS-1$
        {
            work.plain.add(tableName + "." + attribute.name); //$NON-NLS-1$
        }
    }

    /**
     * The cell control measured for the attribute's type. A pattern of several types, or a type
     * the preferred control was not measured with, is a column without a control.
     */
    static String controlFor(ListNode pattern)
    {
        List<String> letters = TypePattern.letters(pattern);
        if (letters.size() != 1)
        {
            return "none"; //$NON-NLS-1$
        }
        String letter = letters.get(0);
        String preferred;
        if ("B".equals(letter)) //$NON-NLS-1$
        {
            preferred = "checkBox"; //$NON-NLS-1$
        }
        else if ("#".equals(letter)) //$NON-NLS-1$
        {
            preferred = "selectField"; //$NON-NLS-1$
        }
        else if ("S".equals(letter) || "N".equals(letter) || "D".equals(letter)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            preferred = "field"; //$NON-NLS-1$
        }
        else
        {
            return "none"; //$NON-NLS-1$
        }
        ColumnControls.ColumnControl control = ColumnControls.find(preferred);
        if (control == null)
        {
            return "none"; //$NON-NLS-1$
        }
        if (control.valueTypes.isEmpty() || control.valueTypes.contains(letter))
        {
            return preferred;
        }
        return "none"; //$NON-NLS-1$
    }

    private static String columnKind(String rowTypeName, String rowTypeUuid)
    {
        String kind = TableColumns.kindForRows(rowTypeName, rowTypeUuid);
        if (kind == null && rowTypeName != null && rowTypeName.contains("RecordSet.")) //$NON-NLS-1$
        {
            // the corpus measured a settings block for a record set and no column kind of its own;
            // a list column is the one that binds a member of the row
            kind = TableColumns.LIST;
        }
        if (kind == null)
        {
            throw new ComposeException("no column kind for rows " + rowTypeName); //$NON-NLS-1$
        }
        return kind;
    }

    private static String fieldType(ListNode pattern)
    {
        List<String> letters = TypePattern.letters(pattern);
        if (letters.size() == 1 && "#".equals(letters.get(0))) //$NON-NLS-1$
        {
            return "SelectField"; //$NON-NLS-1$
        }
        return "Field"; //$NON-NLS-1$
    }

    private static boolean isBoolean(ListNode pattern)
    {
        List<String> letters = TypePattern.letters(pattern);
        return letters.size() == 1 && "B".equals(letters.get(0)); //$NON-NLS-1$
    }

    /**
     * The format of a date or a number field, from the qualifiers the pattern records.
     * A number without a precision, or a date without a fractions token, is left without a format.
     */
    static String formatOf(ListNode pattern)
    {
        if (pattern == null)
        {
            return null;
        }
        for (int i = 1; i < pattern.size(); i++)
        {
            ListNode item = item(pattern, i);
            if (item == null || item.size() == 0)
            {
                continue;
            }
            String letter = textOf(item, 0);
            if ("\"N\"".equals(letter)) //$NON-NLS-1$
            {
                String digits = item.size() > 1 ? textOf(item, 1) : null;
                if (digits == null)
                {
                    return null;
                }
                String fraction = item.size() > 2 ? textOf(item, 2) : null;
                String format = "ЧЦ=" + digits; //$NON-NLS-1$
                if (fraction != null && !"0".equals(fraction)) //$NON-NLS-1$
                {
                    format += "; ЧДЦ=" + fraction; //$NON-NLS-1$
                }
                return format;
            }
            if ("\"D\"".equals(letter)) //$NON-NLS-1$
            {
                String fractions = item.size() > 1 ? textOf(item, 1) : null;
                if ("\"T\"".equals(fractions)) //$NON-NLS-1$
                {
                    return "ДФ=HH:mm:ss"; //$NON-NLS-1$
                }
                if ("\"DT\"".equals(fractions)) //$NON-NLS-1$
                {
                    return "ДФ=dd.MM.yyyy HH:mm:ss"; //$NON-NLS-1$
                }
                if ("\"D\"".equals(fractions)) //$NON-NLS-1$
                {
                    return "ДФ=dd.MM.yyyy"; //$NON-NLS-1$
                }
                return null;
            }
        }
        return null;
    }

    private static void bind(Work work, String elementId, OwnerModel.Attribute attribute)
    {
        FormLayout.Segment segment = attribute.uuid != null ? new FormLayout.Segment(FormLayout.Segment.MEMBER, attribute.uuid)
            : new FormLayout.Segment(FormLayout.Segment.STANDARD, Integer.toString(attribute.standardCode.intValue()));
        must(work.editor.bindData(elementId, List.of(new FormLayout.Segment(FormLayout.Segment.ATTRIBUTE, MAIN_ATTRIBUTE), segment)));
    }

    private static int columnLeft(int column, int spaceWidth, int columns)
    {
        int span = (spaceWidth - MARGIN) / columns;
        return column * span;
    }

    private static int columnRight(int column, int columns, int spaceWidth)
    {
        int inner = spaceWidth - MARGIN;
        int span = inner / columns;
        int extra = inner - span * columns;
        int width = span + (column == columns - 1 ? extra : 0);
        return column * span + width;
    }

    private static void anchorRight(Work work, String name, int right)
    {
        must(Anchors.set(work.editor.root(), name, "right", "form", Integer.valueOf(right - work.width))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void anchorBottom(Work work, String name, int bottom)
    {
        must(Anchors.set(work.editor.root(), name, "bottom", "form", Integer.valueOf(bottom - work.height))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void anchorEdge(Work work, String name, String edge, String target, String targetEdge, int mine, int theirs)
    {
        must(Anchors.set(work.editor.root(), name, edge, target + "." + targetEdge, Integer.valueOf(mine - theirs))); //$NON-NLS-1$
    }

    private static void mustWide(String name, int width)
    {
        if (width < FIELD_MIN)
        {
            throw new ComposeException("the field " + name + " would be " + width + " wide; the minimum is " + FIELD_MIN); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private static String panelName(String tableName)
    {
        return "КоманднаяПанель" + tableName; //$NON-NLS-1$
    }

    private static String sectionTitle(OwnerModel.TabularSection section)
    {
        return section.synonym == null || section.synonym.isEmpty() ? section.name : section.synonym;
    }

    private static ListNode copy(ListNode pattern)
    {
        return pattern == null ? null : LayoutEditor.copyOf(pattern);
    }

    private static ListNode item(ListNode pattern, int index)
    {
        return pattern.get(index) instanceof ListNode ? (ListNode)pattern.get(index) : null;
    }

    private static String textOf(ListNode node, int index)
    {
        return node.get(index) instanceof BraceTree.Token ? ((BraceTree.Token)node.get(index)).text() : null;
    }

    private static LayoutEditor.Outcome must(LayoutEditor.Outcome outcome)
    {
        if (outcome == null || !outcome.done)
        {
            throw new ComposeException(outcome == null ? "the editor returned nothing" : outcome.message); //$NON-NLS-1$
        }
        return outcome;
    }

    private static Integer explicit(Map<String, String> params, String key, int low, int high, String name)
    {
        if (text(params, key) == null)
        {
            return null;
        }
        Integer value = JsonUtils.extractIntegerArgument(params, key);
        if (value == null || value.intValue() < low || value.intValue() > high)
        {
            throw new ComposeException(name + " must be from " + low + " to " + high); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return value;
    }

    private static Boolean flag(Map<String, String> params, String key)
    {
        String value = text(params, key);
        if (value == null)
        {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) //$NON-NLS-1$
        {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) //$NON-NLS-1$
        {
            return Boolean.FALSE;
        }
        throw new ComposeException(key + " must be true or false."); //$NON-NLS-1$
    }

    private static String text(Map<String, String> params, String key)
    {
        if (params == null)
        {
            return null;
        }
        String value = params.get(key);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** The editor and the names the placement collects. */
    private static final class Work
    {
        final LayoutEditor editor;

        final OwnerModel owner;

        final int width;

        final int height;

        final String language;

        final boolean withCommands;

        final int columns;

        final List<String> fields = new ArrayList<>();

        final List<String> tables = new ArrayList<>();

        final List<String> plain = new ArrayList<>();

        /** The element whose bottom the form panel's top follows, when there is one. */
        String stretch;

        Work(LayoutEditor editor, OwnerModel owner, int width, int height, String language, boolean withCommands, int columns)
        {
            this.editor = editor;
            this.owner = owner;
            this.width = width;
            this.height = height;
            this.language = language;
            this.withCommands = withCommands;
            this.columns = columns;
        }
    }

    /** A refusal of the composition. The message is the operation's error. */
    private static final class ComposeException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        ComposeException(String message)
        {
            super(message);
        }
    }
}
