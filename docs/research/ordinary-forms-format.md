# The layout format of an ordinary form

What the platform writes into the `form` entry of `Form.oform`, as measured on a corpus of
1335 ordinary forms of one configuration (Trade Management 10.3 on platform 8.3). Every
statement below is a measurement over that corpus or an experiment on it; a position that is
not listed is not known. The corpus itself is not in this repository.

## The file

`Form.oform` is a platform container ([`V8Container`](../../mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/support/oform/V8Container.java))
with two entries, both UTF-8 text with a BOM: `form` - the layout in the brace format below -
and `module` - the BSL of the form module. Every form of the corpus has exactly these two.

## The brace text

A list opens with `{` and closes with `}`; items are separated by commas; an item is a nested
list, a quoted string (a quote inside is doubled, a line break inside stays), a bare token (a
number, a UUID, a word) or a base64 blob `{#base64:...}` whose lines the platform separates with
`\r\r\n`. The platform's formatting is one rule: a CRLF before every `{` except the first, and a
CRLF before a `}` whose list ends in a list. No trailing line break. The rule reproduced all
1335 forms byte for byte ([`BraceTree`](../../mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/support/oform/BraceTree.java)).

## The module entry

The module is UTF-8 with a BOM, CRLF between lines in every module that has more than one line
(1174 of 1178 non-empty modules; the other four are one line long), and the platform keeps the
text as the Designer saved it: 972 modules end with a line break, 202 do not. 157 forms carry an
empty module. In four containers the name of an entry is followed by two bytes of garbage after
the terminating zero; the reader stops at the zero.

Event handlers are bound by name: a record `{3,"<Procedure>",{1,"<Handler>",...` per binding.
12 301 distinct bindings across the corpus, and every one of them names a procedure the module
declares - which is what makes the guard on a module write sound: a bound name the module no
longer declares is a broken form, not a false alarm.

## Versions

The first token of the text is the version of the layout format, and it is the version of the
platform that last saved that form: one configuration carries several.

| Version | Forms | Root shape |
|---|---|---|
| 27 | 670 | `{27,{18,...}}` |
| 26 | 389 | `{26,{16,...}}` |
| 25 | 214 | `{25,{16,...}}` |
| 20 | 36 | |
| 23 | 21 | |
| 19 | 5 | |

Text size: median 14 KB, largest 2.2 MB.

## Element records, version 27

An element is a list of six items: `{<type UUID>, <id>, [2], [3], [4], [5]}`. The type UUIDs
are the ones v8unpack names ([`FormElements27/FormElement.py`](https://github.com/saby/v8unpack));
22 of its 26 occur in the corpus. Counts, version 27: Label 5035, Field 3357, CommandPanel
1458, Table 751, CheckBox 695, Group 569, Panel 483 (plus the root panel of every form, which
has three items - the type UUID and two lists - not six), Button 475, RadioBtn 337, Image 328,
SelectField 156, TableField 115, Separator 97, FieldHtml 19, ListField 10, Chart 6 (seven
items), CalendarBox 3, TextDocumentField 3, Indicator 2, TrackBar 1.

The four lists of a record, over every record of the eight most frequent types:

| Item | Shape | What it is |
|---|---|---|
| `[2]` | type-specific; fixed length per type: Field 10, Table 5, Button 3, Label 3, CheckBox 3, Panel 3, CommandPanel 2, Group 2 | the properties that exist only for that type (a field's data binding sits here - the `{"Pattern",...}` node) |
| `[3]` | 23 to 29 items for every type (Group up to 36, Field up to 62); always six numbers then lists of three | the properties every element has; the length varies with optional groups, of which the anchors to other elements (four borders, a count each, measured by v8unpack) are one |
| `[4]` | always six: `{<n>, "<Name>", <n>, <n>, <n>, <n>}` | identity: the element's name is the string |
| `[5]` | one number, except Panel: `{<n>, <child>, <child>, ...}` | children: a panel's pages and elements are the six-item records in this list |

Event handlers are bound by procedure name, not by reference: a record `{3,"<Procedure>",{1,"<Handler>"...}` names the procedure of the module (13 940 records in the corpus, 12 301 distinct per form - see the module entry above).

## What is not known yet

The meaning of the six numbers and the three-item lists of `[3]`, the type-specific items of
`[2]` beyond the data binding, the root's two lists (attributes of the form, pages, the id
allocator and the binding registry found empirically in the toolkit scripts), and every version
other than 27. The plan: controlled experiments on the stand - a minimal form, one property
changed at a time in the Designer, the file compared before and after - and a re-save of a
version-25 form, which the platform writes back in the newest version, as the key between the
two.
