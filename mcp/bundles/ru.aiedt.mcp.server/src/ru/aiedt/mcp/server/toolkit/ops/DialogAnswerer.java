/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import ru.aiedt.mcp.server.support.ModalDialogWatch;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Answers the modal dialog EDT is sitting on, by naming the button to press.
 *
 * <p>A modal dialog stops the workbench and waits for a person. From outside it is
 * indistinguishable from a hang: the call that raised it never returns and nothing says why.
 * {@code self_status} already reports that one is up, with its title, message and the buttons it
 * offers; this presses one of them.
 *
 * <p><b>Naming the button is the whole design.</b> Nothing here decides which answer is right, and
 * nothing presses anything on its own. The dialogs seen in practice include Eclipse's secure-storage
 * prompt, which chooses between discarding this instance's stored credentials and discarding another
 * instance's - a choice that belongs to whoever is accountable for it, not to a default. A label
 * matching no button is refused; a label matching more than one is refused rather than guessed,
 * because a press on a modal cannot be taken back.
 *
 * <p>The press runs on the modal's own event loop, which is what makes it reachable at all: a modal
 * dialog dispatches posted runnables while it waits.
 *
 * <p>Prevention beats answering. The dialog most often met by an agent is the infobase update
 * question, and it does not have to appear: {@code launch_debugger} updates before launching by
 * default, and {@code infobase_admin operation=start_client} does so with
 * {@code updateBeforeLaunch=true}.
 */
public class DialogAnswerer implements IMcpTool
{
    public static final String NAME = "answer_dialog"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Presses a named button of the modal dialog EDT is waiting on, so a call blocked " //$NON-NLS-1$
            + "behind a question can go on without a person at the keyboard. Read the dialog first " //$NON-NLS-1$
            + "- self_status reports its title, message and buttons - then name one. Nothing is " //$NON-NLS-1$
            + "pressed automatically, and a label matching no button or several is refused rather " //$NON-NLS-1$
            + "than guessed."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("button", //$NON-NLS-1$
                "The button to press, as the dialog spells it (matched ignoring case and the " //$NON-NLS-1$
                    + "underlined mnemonic). Required.", //$NON-NLS-1$
                true)
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String button = JsonUtils.extractStringArgument(params, "button"); //$NON-NLS-1$
        ModalDialogWatch.Press press = ModalDialogWatch.press(button);
        if (!press.isPressed())
        {
            ModalDialogWatch.Reading reading = ModalDialogWatch.current();
            return ToolResult.error(press.getRefusal())
                .put("pressed", Boolean.FALSE) //$NON-NLS-1$
                .put("dialogs", reading.getDialogs()) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("pressed", Boolean.TRUE) //$NON-NLS-1$
            .put("button", press.getLabel()) //$NON-NLS-1$
            .put("message", "Pressed '" + press.getLabel() //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Whatever the dialog was holding goes on from here.") //$NON-NLS-1$
            .toJson();
    }
}
