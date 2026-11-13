# Ordinary form viewer - stage 7

The ordinary form editor now displays three panes: the complete element tree, a scrollable and zoomable Draw2D schematic, and a read-only property table. The UI consumes only the session facade and `FormSnapshot`; it does not parse layouts, execute BSL, invoke layout operations or write containers.

## Navigation and rendering

- Root and nested page navigation is local to each editor. Selecting a hidden element in the tree reveals its ancestor pages. Selection is retained by element id after reconciliation; removed selections are cleared.
- Snapshot render branches select panel/group frames, column grids, command captions, labels, buttons, flags, radio buttons, separators, input fields or explicit placeholders. Unknown container records are not invented as elements.
- Elements without a box remain in the tree and property table and are not drawn. Unread record counts, unsupported versions, unread properties and properties not measured for a kind are explicitly distinguished.
- The snapshot does not expose styles, fonts or tab visibility. The viewer uses the system font and labels these limitations. Nested page strips are viewer navigation, not evidence of a platform tab strip. Unread form dimensions and column widths use schematic defaults.

## Resource lifecycle

Each editor acquires and releases the shared file session. Independent notice subscriptions prevent one editor from replacing another's callbacks. Resource notifications queue reconciliation on the SWT display, outside resource dispatch and registry locks. Queued callbacks check disposal before touching the editor. Move notifications retarget the editor input to the session's current file; clean deletion closes without saving. If another editor has dirty shared-session state, deletion retains a read-only terminal view with an explicit warning instead of silently closing and discarding it. Queued refreshes cannot overwrite that terminal warning. Content changes reconcile against the session's existing conflict policy rather than creating a new session or overwriting dirty state.

The only session API additions are independent notice registration/removal, content-change notices and the pure `FormViewState` navigation helper. The legacy notice setter remains compatible with the stage 6 tests. There are no new layout/parser/style APIs.

## Deliberately disabled

Saving, dirty-state editing, property writes, drag/resize, undo/redo UI and palette operations belong to later stages. This viewer is always clean, does not offer Save As and never calls session save/edit. Opening and navigation must leave the container unchanged.

## Verification limits

Headless regression tests cover page filtering/reveal/reconciliation, shared resource notice fanout and unregistering. The full Maven/Tycho reactor verifies Java and OSGi compatibility. No plugin installation, live EDT/UI acceptance, information-base operation or comparison against a running 1C client at 100%/150% DPI is claimed. Stage 7's live visual/DPI criteria remain pending.
