#!/usr/bin/env bash
# Runs the test suite on a plain classpath, outside OSGi, for quick local iteration.
#
# `mvn verify` is the authority: since 2026-07-31 it runs the whole suite through tycho-surefire in
# a real headless OSGi runtime, where the platform and EDT bundles resolve. This runner trades that
# fidelity for speed - it starts in seconds instead of minutes, and it costs the tests that need
# those bundles, which show up below as `missing-runtime` rather than as failures. Use it while
# editing; trust `mvn verify` before concluding anything.
#
# It fails when nothing ran - a suite that silently executes zero tests must not look like success.
#
# Usage:
#   scripts/run-unit-tests.sh                 # every *Test class found in the test fragment
#   scripts/run-unit-tests.sh <FQCN> [<FQCN>] # only the named classes
#
# Requires the classes to be compiled first (mvn clean verify) and picks the JVM from
# JAVA_HOME when set.
set -u

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/.." && pwd)
mcp_root="$repo_root/mcp"

test_classes="$mcp_root/tests/ru.aiedt.mcp.server.tests/target/classes"
plugin_classes="$mcp_root/bundles/ru.aiedt.mcp.server/target/classes"
maven_repo="${MAVEN_REPO:-$HOME/.m2/repository}"
tycho_cache="$maven_repo/.cache/tycho"

if [ -n "${JAVA_HOME:-}" ]; then
    java_bin="$JAVA_HOME/bin/java"
else
    java_bin=$(command -v java)
fi

for required in "$java_bin" "$test_classes" "$plugin_classes"; do
    if [ ! -e "$required" ]; then
        echo "missing: $required" >&2
        echo "compile first: mvn clean verify (see CLAUDE.md)" >&2
        exit 2
    fi
done

# java.exe on Windows rejects MSYS-style paths, so every classpath entry goes through cygpath when
# it is available; on Linux this is a no-op.
to_platform_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

find_jar() {
    find "$maven_repo" "$tycho_cache" -iname "$1" 2>/dev/null | head -1
}

gson_jar=$(find_jar 'gson-2.*.jar')
junit_jar=$(find_jar 'org.junit_4*.jar')
hamcrest_jar=$(find_jar 'org.hamcrest.core_1*.jar')

if [ -z "$junit_jar" ] || [ -z "$hamcrest_jar" ]; then
    echo "JUnit 4 / Hamcrest not found under $maven_repo" >&2
    exit 2
fi

separator=":"
case "$(uname -s 2>/dev/null || echo unknown)" in
    CYGWIN*|MINGW*|MSYS*) separator=";" ;;
esac

classpath="$(to_platform_path "$test_classes")$separator$(to_platform_path "$plugin_classes")"
for jar in "$gson_jar" "$junit_jar" "$hamcrest_jar"; do
    [ -n "$jar" ] && classpath="$classpath$separator$(to_platform_path "$jar")"
done
for bundle in "org.eclipse.osgi_3" "org.eclipse.core.runtime_" "org.eclipse.equinox.common_" \
              "org.eclipse.core.jobs_" "org.eclipse.equinox.registry_" \
              "org.eclipse.equinox.preferences_" "org.eclipse.core.contenttype_" \
              "org.eclipse.equinox.app_"; do
    jar=$(find_jar "${bundle}*.jar")
    [ -n "$jar" ] && classpath="$classpath$separator$(to_platform_path "$jar")"
done

if [ "$#" -gt 0 ]; then
    classes=("$@")
else
    mapfile -t classes < <(cd "$test_classes" && find . -name '*Test.class' ! -name '*$*' \
        | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
fi

if [ "${#classes[@]}" -eq 0 ]; then
    echo "no test classes found under $test_classes" >&2
    exit 1
fi

echo "running ${#classes[@]} test classes"
output=$("$java_bin" -cp "$classpath" org.junit.runner.JUnitCore "${classes[@]}" 2>&1)
echo "$output"

ran=$(printf '%s' "$output" | grep -oE 'OK \(([0-9]+) test|Tests run: ([0-9]+)' | grep -oE '[0-9]+' | head -1)
if [ -z "$ran" ] || [ "$ran" -eq 0 ]; then
    echo "no tests actually executed - treating as failure" >&2
    exit 1
fi

# Part of the suite needs the Eclipse UI and EDT bundles that only a full Tycho/SWT harness
# provides; outside it those classes die on NoClassDefFoundError. Reporting them next to genuine
# assertion failures would make the run unreadable and the gate meaningless, so they are counted
# apart: only a failed assertion fails this script.
summary=$(printf '%s' "$output" | awk '
    /^[0-9]+\) / { name = $0; getline probe; getline probe2
                   if (probe ~ /NoClassDefFound|ClassNotFound/ || probe2 ~ /NoClassDefFound|ClassNotFound/)
                       missing++
                   else { real++; print "  " name > "/dev/stderr" }
                   next }
    END { print (missing + 0) " " (real + 0) }')
missing_deps=${summary%% *}
assertion_failures=${summary##* }

echo
echo "tests run:                 $ran"
echo "missing-runtime failures:  $missing_deps (need the Tycho/SWT harness, not a regression)"
echo "assertion failures:        $assertion_failures"

if [ "$assertion_failures" -gt 0 ]; then
    exit 1
fi
exit 0
