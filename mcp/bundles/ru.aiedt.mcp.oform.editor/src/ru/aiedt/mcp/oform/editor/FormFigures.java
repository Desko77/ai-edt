/** AI-EDT Ordinary Form Editor. Copyright (C) 2026 Desko77. AGPL-3.0-or-later. */
package ru.aiedt.mcp.oform.editor;

import org.eclipse.draw2d.ColorConstants;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.geometry.Rectangle;
import ru.aiedt.mcp.oform.session.FormSnapshot;
import ru.aiedt.mcp.oform.session.RenderKinds;

/** Snapshot-only schematic figures. They do not run BSL or emulate a platform widget. */
final class FormFigures
{
    private FormFigures() { }
    static Figure create(FormSnapshot.Element element)
    {
        Figure figure = new Figure() {
            @Override protected void paintFigure(Graphics g)
            {
                super.paintFigure(g);
                Rectangle b = getBounds().getCopy();
                g.setForegroundColor(ColorConstants.darkGray);
                String branch = element.renderBranch;
                if (RenderKinds.SEPARATOR.equals(branch)) {
                    if (b.width >= b.height) { g.drawLine(b.x, b.y + b.height / 2, b.right(), b.y + b.height / 2); }
                    else { g.drawLine(b.x + b.width / 2, b.y, b.x + b.width / 2, b.bottom()); }
                } else if (RenderKinds.TABLE.equals(branch)) {
                    int x = b.x;
                    for (FormSnapshot.Column column : element.columns) {
                        int width = 90;
                        try { width = Math.max(1, Integer.parseInt(column.width)); }
                        catch (NumberFormatException e) { /* explicitly schematic default */ }
                        g.drawText(text(column.title, column.name), x + 3, b.y + 3);
                        x += width;
                        if (x < b.right()) { g.drawLine(x, b.y, x, b.bottom()); }
                    }
                    for (int y = b.y + 24; y < b.bottom(); y += 24) { g.drawLine(b.x, y, b.right(), y); }
                }
            }
        };
        figure.setLayoutManager(new org.eclipse.draw2d.XYLayout());
        String branch = element.renderBranch;
        if (!RenderKinds.LABEL.equals(branch) && !RenderKinds.SEPARATOR.equals(branch)) {
            figure.setBorder(new LineBorder(ColorConstants.gray));
        }
        figure.setOpaque(!RenderKinds.LABEL.equals(branch) && !RenderKinds.SEPARATOR.equals(branch));
        figure.setBackgroundColor(RenderKinds.BUTTON.equals(branch) ? ColorConstants.button : ColorConstants.white);
        String caption = text(element.title, element.name);
        if (RenderKinds.INPUT_FIELD.equals(branch)) { caption = text(element.data, text(element.valueType, "Not read")); }
        if (RenderKinds.CHECK_BOX.equals(branch)) { caption = "[ ] " + caption; }
        if (RenderKinds.RADIO_BUTTON.equals(branch)) { caption = "( ) " + caption; }
        if (RenderKinds.PLACEHOLDER.equals(branch)) { caption = element.kind + ": " + element.name + " (placeholder)"; }
        if (RenderKinds.COMMAND_PANEL.equals(branch)) {
            StringBuilder buttons = new StringBuilder();
            for (FormSnapshot.Button button : element.buttons) { buttons.append("[ ").append(text(button.title, button.name)).append(" ] "); }
            caption = buttons.toString();
        }
        if (!RenderKinds.TABLE.equals(branch) && !RenderKinds.SEPARATOR.equals(branch)) {
            Label label = new Label(caption);
            figure.add(label, new Rectangle(3, 2, Math.max(0, element.box[2] - element.box[0] - 6), 22));
        }
        figure.setToolTip(new Label(element.kind + ": " + element.name + " - schematic; style not read"));
        return figure;
    }
    static String text(Object value, String fallback) { return value == null ? fallback == null ? "" : fallback : String.valueOf(value); }
}
