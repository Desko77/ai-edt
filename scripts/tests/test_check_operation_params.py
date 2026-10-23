#!/usr/bin/env python3
"""The regex that reads a tool's schema has to see every kind of property the schema can declare.

A kind it does not see is a parameter the map does not know the tool accepts. That is not a
cosmetic gap: the map is what `UnreadArguments` asks before deciding an argument is read by nobody,
and that check REFUSES the call rather than warning. A parameter missing from the map is therefore
one refusal away from breaking a call that works.

Measured on 11.09: `stringArrayProperty` was invisible, because the alternation tried `string`
first and then failed on `Array`. Twelve declarations naming objectFqns, objects, sections and tags
went unseen, and `revalidate_objects` stood in the map knowing only `projectName` - while the tool
has taken `objects` all along.
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

SCHEMA_PROP = __import__("importlib").import_module(
    "importlib.util").spec_from_file_location(
        "check_operation_params",
        pathlib.Path(__file__).resolve().parent.parent / "check-operation-params.py")
MODULE = __import__("importlib").util.module_from_spec(SCHEMA_PROP)
SCHEMA_PROP.loader.exec_module(MODULE)


class EveryDeclaredKindIsSeen(unittest.TestCase):
    """Each builder method of SchemaComposer, as a call site the regex has to match."""

    def names_in(self, source):
        return MODULE.SCHEMA_PROP.findall(source)

    def test_a_string_is_seen(self):
        self.assertEqual(["projectName"],
                         self.names_in('.stringProperty("projectName", "the project")'))

    def test_a_boolean_is_seen(self):
        self.assertEqual(["dryRun"], self.names_in('.booleanProperty("dryRun", "preview")'))

    def test_an_integer_is_seen(self):
        self.assertEqual(["limit"], self.names_in('.integerProperty("limit", "how many")'))

    def test_a_string_array_is_seen(self):
        # The one that was not. "string" matched the first six letters and the match then died on
        # "Array", so the declaration read as no declaration at all.
        self.assertEqual(["objects"],
                         self.names_in('.stringArrayProperty("objects", "the addresses")'))

    def test_an_array_is_seen(self):
        self.assertEqual(["operations"], self.names_in('.arrayProperty("operations", "a batch")'))

    def test_an_object_is_seen(self):
        self.assertEqual(["query"], self.names_in('.objectProperty("query", "the filter")'))

    def test_the_required_overload_is_seen_too(self):
        self.assertEqual(["objectFqns"], self.names_in(
            '.stringArrayProperty("objectFqns", "the addresses", true)'))

    def test_every_builder_of_the_composer_is_covered(self):
        """Whatever SchemaComposer can declare, the regex has to read - checked against the class.

        Written against the source rather than a list, so a builder added later fails here instead
        of quietly producing parameters the map cannot see.
        """
        composer = (pathlib.Path(__file__).resolve().parent.parent.parent
                    / "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/wire"
                    / "SchemaComposer.java")
        import re
        declared = set(re.findall(r"public SchemaComposer (\w+Property)\(", composer.read_text(
            encoding="utf-8")))
        self.assertTrue(declared, "no builders found - the path or the class shape changed")
        unseen = [name for name in sorted(declared)
                  if not self.names_in('.%s("someName", "text")' % name)]
        self.assertEqual([], unseen, "builders the map cannot see: %s" % unseen)


class AMethodDeclaredOverSeveralLinesIsStillRead(unittest.TestCase):
    """The body of a method whose declaration spans more than one line.

    Found on 29.09: the signature regex required the opening brace on the line of the closing
    parenthesis or of `throws`, and `GitTool` writes the brace on the line below. Every method of
    that facade came back as an empty body, and the seven operations of it were then derived not
    from what each one reads but from the set of locals the whole facade binds - so each operation
    appeared to read every parameter its siblings read, and a narrowed set was never established.
    """

    SOURCE = (
        '    private String doCommit(IProject project, Git git, Map<String, String> params)\n'
        '        throws Exception\n'
        '    {\n'
        '        String message = JsonUtils.extractStringArgument(params, "message");\n'
        '        return message;\n'
        '    }\n'
        '\n'
        '    private String doStatus(Map<String, String> params) {\n'
        '        return JsonUtils.extractStringArgument(params, "projectName");\n'
        '    }\n'
        '\n'
        '    private String doRevert(Map<String, String> params) throws IOException,\n'
        '        CoreException\n'
        '    {\n'
        '        return JsonUtils.extractStringArgument(params, "fromRef");\n'
        '    }\n')

    def test_a_brace_on_the_line_below_the_declaration_is_seen(self):
        body = MODULE.method_body(self.SOURCE, "doCommit")
        self.assertIn('"message"', body)

    def test_a_throws_list_over_two_lines_is_seen(self):
        self.assertIn('"fromRef"', MODULE.method_body(self.SOURCE, "doRevert"))

    def test_a_declaration_on_one_line_is_still_seen(self):
        self.assertIn('"projectName"', MODULE.method_body(self.SOURCE, "doStatus"))

    def test_the_body_given_back_is_the_one_method(self):
        # The span is balanced, so it ends at this method's closing brace and not at the last
        # brace in the file.
        body = MODULE.method_body(self.SOURCE, "doCommit")
        self.assertNotIn('"projectName"', body)

    def test_every_method_of_the_git_facade_has_a_body(self):
        """The shape that was missed, checked against the file it was missed in."""
        source = (MODULE.OPS / "GitTool.java").read_text(encoding="utf-8")
        empty = [name for name in ("doStatus", "doBranches", "doLog", "doCommit", "doCheckout",
                                   "doShowFileChanges", "doRevertFile")
                 if not MODULE.method_body(source, name)]
        self.assertEqual([], empty, "methods whose body came back empty: %s" % empty)



class BranchReadsStayWithTheirBranch(unittest.TestCase):
    """What a dispatch branch reads belongs to that operation, not to every operation of the facade."""

    SOURCE = (
        'public String execute(Map<String, String> params) {\n'
        '    String projectName = JsonUtils.extractStringArgument(params, "projectName");\n'
        '    switch (operation) {\n'
        '        case "backup":\n'
        '            return runBackup(params);\n'
        '        case "restore":\n'
        '            return runRestore(params);\n'
        '        case "route":\n'
        '            return route(params);\n'
        '        case "list":\n'
        '            return list(projectName);\n'
        '    }\n'
        '}\n'
        '\n'
        'private String runBackup(Map<String, String> params) {\n'
        '    return write(params);\n'
        '}\n'
        '\n'
        'private String write(Map<String, String> params) {\n'
        '    return JsonUtils.extractStringArgument(params, "backupTo");\n'
        '}\n'
        '\n'
        'private String runRestore(Map<String, String> params) {\n'
        '    return JsonUtils.extractStringArgument(params, "path");\n'
        '}\n'
        '\n'
        'private static String route(Map<String, String> params) {\n'
        '    Map<String, String> forwarded = new HashMap<>(params);\n'
        '    return new SyncControlTool().execute(forwarded);\n'
        '}\n')

    def setUp(self):
        self.dispatch = MODULE.dispatch_body(self.SOURCE)
        self.branches = MODULE.branches(self.dispatch)

    def deep(self, operation):
        return MODULE.branch_deep_reads(self.branches[operation], self.SOURCE, self.dispatch)

    def test_only_what_is_read_before_the_switch_is_common(self):
        common = MODULE.facade_common_reads(self.SOURCE, set(), dispatch=self.dispatch)
        self.assertEqual({"projectName"}, common)

    def test_a_branch_reads_what_its_helpers_read_further_down(self):
        self.assertEqual({"backupTo"}, self.deep("backup"))

    def test_a_branch_does_not_read_another_branchs_parameters(self):
        self.assertNotIn("path", self.deep("backup"))
        self.assertEqual(set(), self.deep("list"))

    def test_a_helper_handing_the_map_to_a_tool_reads_that_tools_schema(self):
        self.assertIn("infobaseUuid", self.deep("route"))

    def test_the_infobase_facade_lists_no_other_operations_parameters(self):
        """The case this was found on, checked against the file it was found in."""
        rows = MODULE.collect()
        applications = set(rows["InfobaseAdminFacadeTool:get_applications"]["parameters"])
        self.assertEqual(set(), applications & {"backupTo", "cancel", "path", "syncOperation"})
        sync = set(rows["InfobaseAdminFacadeTool:sync_control"]["parameters"])
        self.assertIn("infobaseUuid", sync)
        self.assertNotIn("backupTo", sync)


class AReadThroughAnotherClassIsCounted(unittest.TestCase):
    """`branch_deep_reads` follows a call into a method of another class.

    A branch may hand the map to a helper that lives in another file - `SnapshotKeeper.run(params)`
    - and what that helper reads is in that file. Resolving every call against the facade's own
    source left the read out of the map, and the unread-argument guard then refused a call that
    works.
    """

    SOURCE = (
        'public String execute(Map<String, String> params) {\n'
        '    switch (operation) {\n'
        '        case "snapshot":\n'
        '            return SnapshotKeeper.run(params);\n'
        '        case "list":\n'
        '            return list(params);\n'
        '    }\n'
        '}\n'
        '\n'
        'private String list(Map<String, String> params) {\n'
        '    return JsonUtils.extractStringArgument(params, "projectName");\n'
        '}\n')

    OWNER = (
        'class SnapshotKeeper {\n'
        '    static String run(Map<String, String> params) {\n'
        '        return SnapshotKeeper.finish(params);\n'
        '    }\n'
        '\n'
        '    static String finish(Map<String, String> params) {\n'
        '        return JsonUtils.extractStringArgument(params, "snapshotName");\n'
        '    }\n'
        '}\n')

    def setUp(self):
        import pathlib
        import tempfile
        self.tmp = tempfile.TemporaryDirectory()
        pathlib.Path(self.tmp.name, "SnapshotKeeper.java").write_text(self.OWNER,
                                                                      encoding="utf-8")
        self.saved_ops = MODULE.OPS
        MODULE.OPS = pathlib.Path(self.tmp.name)
        self.dispatch = MODULE.dispatch_body(self.SOURCE)
        self.branches = MODULE.branches(self.dispatch)

    def tearDown(self):
        MODULE.OPS = self.saved_ops
        self.tmp.cleanup()

    def test_a_read_in_another_class_is_counted(self):
        reads = MODULE.branch_deep_reads(self.branches["snapshot"], self.SOURCE, self.dispatch)
        self.assertIn("snapshotName", reads)

    def test_another_classs_read_stays_with_its_branch(self):
        reads = MODULE.branch_deep_reads(self.branches["snapshot"], self.SOURCE, self.dispatch)
        self.assertNotIn("projectName", reads)
        own = MODULE.branch_deep_reads(self.branches["list"], self.SOURCE, self.dispatch)
        self.assertIn("projectName", own)
        self.assertNotIn("snapshotName", own)


class FakeCatalogTree(unittest.TestCase):
    """A made-up ordinary-forms bundle, so the adapter is tested without the real one.

    The tree mirrors the layout the shared delivery will carry:
    mcp/bundles/ru.aiedt.mcp.oform/src/ru/aiedt/mcp/oform/ops. Over a tree without the
    bundle the adapter must answer nothing and no complaint; these tests say what changes
    the day it appears.
    """

    OFORM = "ru.aiedt.mcp.oform"

    COMMON = (
        '    private static final List<ParamSpec> COMMON = List.of(\n'
        '        ParamSpec.string("projectName", "Name of the EDT project."), //$NON-NLS-1$ //$NON-NLS-2$\n'
        '        ParamSpec.string("formFqn", "The form, Type.Object.Form.Name."), //$NON-NLS-1$\n'
        '        ParamSpec.bool("dryRun", "Report what the write would do (default false)."));\n')

    CATALOG_HEAD = (
        'public final class OperationsCatalog\n'
        '{\n'
        '    private static final Map<String, OperationSpec> BY_NAME = new LinkedHashMap<>();\n'
        '\n'
        '    static\n'
        '    {\n'
        '        List<OperationSpec> all = new ArrayList<>();\n')

    CATALOG_TAIL = (
        '    }\n'
        '}\n')

    def catalog(self, pulls):
        lines = [self.CATALOG_HEAD]
        # A pull names the class the file of that name declares, exactly as the real catalog
        # does: Theme0 here pulls Theme0Operations.specs() out of Theme0Operations.java.
        lines += ['        all.addAll(%sOperations.specs());\n' % name for name in pulls]
        lines.append(self.CATALOG_TAIL)
        return "".join(lines)

    def theme(self, calls):
        """One theme catalog: each call is (factory, name, [param factory lines])."""
        body = [
            'public final class ThemeOperations\n',
            '{\n',
            '    public static List<OperationSpec> specs()\n',
            '    {\n',
            '        List<OperationSpec> out = new ArrayList<>();\n',
        ]
        for factory, name, params in calls:
            body.append('        out.add(OperationSpec.%s("%s", //$NON-NLS-1$\n' % (factory, name))
            body.append('            "the help of %s", //$NON-NLS-1$\n' % name)
            body.append('            List.of(%s),\n' % ", ".join(params))
            body.append('            FormQueries::%s));\n' % name)
        body += [
            '        return out;\n',
            '    }\n',
            '}\n',
        ]
        return "".join(body)

    def setUp(self):
        import tempfile
        self.tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(self.tmp.name)
        self.ops = root / "bundles" / self.OFORM / "src" / "ru/aiedt/mcp/oform/ops"
        self.saved = (MODULE.OFORM_OPS, MODULE.OFORM_CATALOG,
                      sys.modules["bundles"].BUNDLES_DIR, MODULE.MIN_CATALOG_OPERATIONS)
        sys.modules["bundles"].BUNDLES_DIR = root / "bundles"
        # The floor exists to catch a parse of the real catalog collapsing from 23 operations
        # to a few; a fixture of one or two operations is a catalog of that size on purpose.
        MODULE.MIN_CATALOG_OPERATIONS = 1

    def tearDown(self):
        (MODULE.OFORM_OPS, MODULE.OFORM_CATALOG,
         sys.modules["bundles"].BUNDLES_DIR, MODULE.MIN_CATALOG_OPERATIONS) = self.saved
        self.tmp.cleanup()

    def install(self, catalog_text, *themes):
        """Write the catalog and the theme files, and point the adapter at them."""
        self.ops.mkdir(parents=True, exist_ok=True)
        (self.ops / "OperationsCatalog.java").write_text(catalog_text, encoding="utf-8")
        for index, text in enumerate(themes):
            (self.ops / ("Theme%dOperations.java" % index)).write_text(text, encoding="utf-8")
        MODULE.OFORM_OPS = self.ops
        MODULE.OFORM_CATALOG = self.ops / "OperationsCatalog.java"


class TheCatalogRowsAreTheDeclaration(FakeCatalogTree):
    """What the adapter publishes has to be exactly what the specs declare.

    A parameter lost by the parse or invented by it is the failure with teeth: the published
    map is what per-operation help prints, so a wrong row teaches a caller the wrong
    vocabulary. Each mutation below is a declaration changed by hand, and the parse has to
    move with it - both directions, lost and spurious.
    """

    def test_every_operation_carries_its_own_arguments_and_the_common_ones(self):
        self.install(
            self.catalog(["Theme0"]) + self.COMMON,
            self.theme([
                ("query", "audit", ['ParamSpec.integer("limit", "how many to name.")',
                                    'ParamSpec.bool("details", "list every form.")']),
                ("layout", "set_title", ['ParamSpec.string("title", "the new title.")']),
            ]))
        rows, problems = MODULE.catalog_rows()
        self.assertEqual([], problems)
        self.assertEqual({"projectName", "formFqn", "dryRun", "limit", "details"},
                         set(rows["ordinary_forms:audit"]["parameters"]))
        self.assertEqual({"projectName", "formFqn", "dryRun", "title"},
                         set(rows["ordinary_forms:set_title"]["parameters"]))
        self.assertEqual("catalog Theme0Operations", rows["ordinary_forms:audit"]["how"])

    def test_a_declaration_that_loses_an_argument_loses_it_in_the_map(self):
        with_limit = self.theme([
            ("query", "audit", ['ParamSpec.integer("limit", "how many to name.")',
                                'ParamSpec.bool("details", "list every form.")']),
        ])
        without_limit = self.theme([
            ("query", "audit", ['ParamSpec.bool("details", "list every form.")']),
        ])
        self.install(self.catalog(["Theme0"]) + self.COMMON, with_limit)
        rows, _problems = MODULE.catalog_rows()
        self.assertIn("limit", rows["ordinary_forms:audit"]["parameters"])
        # The same operation declared without one of its arguments has to come out without
        # it: nothing in the adapter may paper over a declaration that changed.
        self.install(self.catalog(["Theme0"]) + self.COMMON, without_limit)
        rows, _problems = MODULE.catalog_rows()
        self.assertNotIn("limit", rows["ordinary_forms:audit"]["parameters"])

    def test_an_argument_added_to_the_declaration_appears(self):
        self.install(self.catalog(["Theme0"]) + self.COMMON,
                     self.theme([("layout", "set_title", ['ParamSpec.string("title", "t")'])]))
        before, _problems = MODULE.catalog_rows()
        self.assertNotIn("tooltip", before["ordinary_forms:set_title"]["parameters"])
        self.install(self.catalog(["Theme0"]) + self.COMMON,
                     self.theme([("layout", "set_title",
                                  ['ParamSpec.string("title", "t")',
                                   'ParamSpec.string("tooltip", "the tooltip")'])]))
        after, _problems = MODULE.catalog_rows()
        self.assertIn("tooltip", after["ordinary_forms:set_title"]["parameters"])

    def test_the_kinds_come_from_the_paramspec_factories(self):
        self.install(self.catalog(["Theme0"]) + self.COMMON,
                     self.theme([("query", "audit", ['ParamSpec.integer("limit", "how many.")'])]))
        rows, problems = MODULE.catalog_rows()
        self.assertEqual([], problems)
        kinds = MODULE.catalog_kinds(rows)
        self.assertEqual("integer", kinds["limit"])
        self.assertEqual("boolean", kinds["dryRun"])
        self.assertEqual("string", kinds["projectName"])

    def test_a_bundle_that_is_not_there_answers_nothing(self):
        # The temporary tree has no oform bundle at all until install() runs.
        MODULE.OFORM_OPS = pathlib.Path(self.tmp.name) / "nowhere"
        MODULE.OFORM_CATALOG = MODULE.OFORM_OPS / "OperationsCatalog.java"
        rows, problems = MODULE.catalog_rows()
        self.assertEqual({}, rows)
        self.assertEqual([], problems,
                         "an absent bundle is the pre-merge state, not a finding")


class TheCatalogHasToIntegrityCheckItself(FakeCatalogTree):
    def test_a_pulled_catalog_missing_from_disk_is_named(self):
        self.install(self.catalog(["Theme0", "Theme1"]) + self.COMMON,
                     self.theme([("query", "audit", [])]))
        rows, problems = MODULE.catalog_rows()
        self.assertEqual(1, len(rows))
        self.assertTrue(any("Theme1" in problem for problem in problems),
                        "the missing pull has to be named: %s" % problems)

    def test_an_operation_declared_twice_is_named(self):
        self.install(self.catalog(["Theme0"]) + self.COMMON,
                     self.theme([("query", "audit", []),
                                 ("layout", "audit", []),]),
                     self.theme([]))
        _rows, problems = MODULE.catalog_rows()
        self.assertTrue(any("audit is declared in both" in problem for problem in problems),
                        "a duplicate operation is a defect the runtime would throw at: %s"
                        % problems)

    def test_common_arguments_that_do_not_parse_refuse_rather_than_hide(self):
        catalog = ("public final class OperationsCatalog\n{\n"
                   "    static { List<OperationSpec> all = new ArrayList<>(); "
                   "all.addAll(Theme0.specs()); }\n}\n")
        self.install(catalog, self.theme([("query", "audit", [])]))
        _rows, problems = MODULE.catalog_rows()
        self.assertTrue(any("common" in problem for problem in problems),
                        "an unparsed COMMON set must not read as there being none: %s" % problems)

    def test_a_collapse_below_the_measured_size_refuses(self):
        # One operation out of a catalog this size parses - the adapter stopped matching.
        MODULE.MIN_CATALOG_OPERATIONS = 2
        self.install(self.catalog(["Theme0"]) + self.COMMON,
                     self.theme([("query", "audit", [])]))
        _rows, problems = MODULE.catalog_rows()
        self.assertTrue(any("stopped matching" in problem for problem in problems),
                        "a tiny parse of a real catalog is a parser defect, not a small "
                        "catalog: %s" % problems)

    def test_a_bundle_without_its_ops_package_is_named(self):
        root = pathlib.Path(self.tmp.name)
        (root / "bundles" / self.OFORM / "src" / "ru/aiedt/mcp/oform").mkdir(parents=True,
                                                                             exist_ok=True)
        MODULE.OFORM_OPS = root / "bundles" / self.OFORM / "src" / "ru/aiedt/mcp/oform/ops"
        MODULE.OFORM_CATALOG = MODULE.OFORM_OPS / "OperationsCatalog.java"
        rows, problems = MODULE.catalog_rows()
        self.assertEqual({}, rows)
        self.assertTrue(any("ops package" in problem for problem in problems),
                        "a bundle present without its catalog is a silent gap otherwise: %s"
                        % problems)


if __name__ == "__main__":
    unittest.main()
