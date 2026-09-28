/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;

/**
 * A module written through {@code write_module_source} holds no character BSL has no place for:
 * the dash typed as a long dash becomes a hyphen on the way in, the answer says how many were
 * replaced and where, and the off switch leaves the text exactly as it was supplied.
 *
 * <p>The module is held by a provider rather than by a file, so the write goes through the whole
 * path - address resolution, the modes, the character pass, the response - and what was written
 * can be read back from the provider without a live 1C:EDT project behind it.</p>
 *
 * <p>Every character of this family is written as an escape, so that this file holds none of them.
 * </p>
 */
public class ACharacterBslCannotHoldIsReplacedOnWriteTest
{
    private static final String PROJECT = "AiEdtCharacterProbe"; //$NON-NLS-1$

    private static final String ADDRESS = "CommonModules/Probe/Module.bsl"; //$NON-NLS-1$

    private static final String HOLDER = "CommonModules/Probe/Module.probe"; //$NON-NLS-1$

    private static final char EM_DASH = '\u2014';

    private static final char NO_BREAK_SPACE = '\u00A0';

    /** The module as the provider first holds it: one method, and a dash on a line of its body. */
    private static List<String> initialLines()
    {
        List<String> lines = new ArrayList<>();
        lines.add("Процедура Тест()"); //$NON-NLS-1$
        lines.add("\tСообщить(\"старое значение\");"); //$NON-NLS-1$
        lines.add("КонецПроцедуры"); //$NON-NLS-1$
        return lines;
    }

    /** A provider whose one module lives in memory and whose writes are counted. */
    private static final class Probe implements IModuleSourceProvider, IModuleSource
    {
        final List<String> lines = new ArrayList<>();

        int writes;

        @Override
        public String kind()
        {
            return "ProbeModule"; //$NON-NLS-1$
        }

        @Override
        public IModuleSource locate(IProject candidate, String modulePath)
        {
            String path = modulePath.startsWith("src/") ? modulePath.substring(4) : modulePath; //$NON-NLS-1$
            return candidate == project && ADDRESS.equals(path) ? this : null;
        }

        @Override
        public List<IModuleSource> list(IProject candidate)
        {
            return candidate == project ? List.of(this) : List.of();
        }

        @Override
        public String coverage(Collection<IProject> projects, String what)
        {
            return null;
        }

        @Override
        public String source()
        {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String modulePath()
        {
            return ADDRESS;
        }

        @Override
        public String containerPath()
        {
            return HOLDER;
        }

        @Override
        public String fqn()
        {
            return "CommonModule.Probe"; //$NON-NLS-1$
        }

        @Override
        public List<String> lines()
        {
            return new ArrayList<>(lines);
        }

        @Override
        public boolean write(List<String> newLines)
        {
            if (newLines.equals(lines))
            {
                return false;
            }
            lines.clear();
            lines.addAll(newLines);
            writes++;
            return true;
        }

        @Override
        public String afterWrite(boolean written)
        {
            return written ? "the probe delivered it" : "nothing changed"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public String validationNote()
        {
            return "not available: the probe validates nothing"; //$NON-NLS-1$
        }
    }

    private static Path root;

    private static IProject project;

    private static final Probe PROBE = new Probe();

    @BeforeClass
    public static void aProjectWithAProvidedModule() throws Exception
    {
        root = Files.createTempDirectory("aiedt-character-probe"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/CommonModules/Probe")); //$NON-NLS-1$
        Files.write(projectDir.resolve("src/CommonModules/Probe/holder.probe"), //$NON-NLS-1$
            "probe".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        ModuleSources.register(PROBE);
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        ModuleSources.unregister(PROBE);
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    @Before
    public void theModuleIsWhatItWas()
    {
        PROBE.lines.clear();
        PROBE.lines.addAll(initialLines());
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String written()
    {
        return String.join("\n", PROBE.lines); //$NON-NLS-1$
    }

    // ---------- the character pass ----------

    /** A dash reaches the module as a hyphen, and the answer says so with the place it stood at. */
    @Test
    public void aDashInTheWrittenTextBecomesAHyphen()
    {
        String answer = new ModuleSourceWriter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "append", //$NON-NLS-1$ //$NON-NLS-2$
            "source", "Процедура Вторая()\n\t// строка " + EM_DASH + " продолжение\nКонецПроцедуры")); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("invalidCharactersReplaced: 1")); //$NON-NLS-1$
        assertTrue(written(), written().contains(
            "Процедура Вторая()\n\t// строка - продолжение\nКонецПроцедуры")); //$NON-NLS-1$
        assertFalse(written().contains(String.valueOf(EM_DASH)));
        assertTrue("the place is reported", answer.contains("2:12")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The off switch writes the text exactly as it was supplied. */
    @Test
    public void theSwitchOffKeepsTheCharacter()
    {
        String answer = new ModuleSourceWriter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "append", //$NON-NLS-1$ //$NON-NLS-2$
            "normalizeInvalidCharacters", "false", //$NON-NLS-1$ //$NON-NLS-2$
            "source", "// строка " + EM_DASH + " продолжение")); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertFalse("nothing was replaced, so nothing is reported", //$NON-NLS-1$
            answer.contains("invalidCharactersReplaced")); //$NON-NLS-1$
        assertTrue("the character stands in the module as it was supplied", //$NON-NLS-1$
            written().contains("// строка " + EM_DASH + " продолжение")); //$NON-NLS-1$
    }

    /** A preview reports the same replacement and writes nothing. */
    @Test
    public void aPreviewReportsTheReplacementWithoutWriting()
    {
        int before = PROBE.writes;
        String answer = new ModuleSourceWriter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "append", //$NON-NLS-1$ //$NON-NLS-2$
            "dryRun", "true", //$NON-NLS-1$ //$NON-NLS-2$
            "source", "// a" + NO_BREAK_SPACE + "b" + EM_DASH)); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: preview")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("invalidCharactersReplaced: 2")); //$NON-NLS-1$
        assertEquals("a preview writes nothing", before, PROBE.writes); //$NON-NLS-1$
    }

    /** What the caller matched against the module is not rewritten under it. */
    @Test
    public void theTextThatIsMatchedIsNotRewritten()
    {
        PROBE.lines.set(1, "\tСообщить(\"старое " + EM_DASH + " значение\");"); //$NON-NLS-1$
        String oldSource = "\tСообщить(\"старое " + EM_DASH + " значение\");"; //$NON-NLS-1$

        String answer = new ModuleSourceWriter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "searchReplace", //$NON-NLS-1$ //$NON-NLS-2$
            "oldSource", oldSource,
            "source", "\tСообщить(\"новое значение\"); // правка " + EM_DASH)); //$NON-NLS-1$

        assertTrue("oldSource is matched as the caller wrote it, so the write finds its place", //$NON-NLS-1$
            answer.contains("status: success")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("invalidCharactersReplaced: 1")); //$NON-NLS-1$
        assertTrue(written(), written().contains("\tСообщить(\"новое значение\"); // правка -")); //$NON-NLS-1$
    }

    /**
     * A character inside a string literal of the written text stands as supplied: a no-break space
     * inside a format string is the group separator the code means, not a character to fix.
     */
    @Test
    public void aCharacterInsideALiteralOfTheWrittenTextStands()
    {
        String answer = new ModuleSourceWriter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "append", //$NON-NLS-1$ //$NON-NLS-2$
            "source", "Формат(Число, \"ЧРГ='" + NO_BREAK_SPACE + "'; ЧДЦ=2\"); // правка " + EM_DASH)); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue("only the dash of the comment counts", //$NON-NLS-1$
            answer.contains("invalidCharactersReplaced: 1")); //$NON-NLS-1$
        assertTrue("the no-break space stands inside the literal", //$NON-NLS-1$
            written().contains("Формат(Число, \"ЧРГ='" + NO_BREAK_SPACE + "'; ЧДЦ=2\");")); //$NON-NLS-1$
        assertTrue(written(), written().contains("// правка -")); //$NON-NLS-1$
    }

    /** A method written by a replaceMethods call is passed through the same way. */
    @Test
    public void everyMethodOfAMultiMethodWriteIsPassedThrough()
    {
        String body = "Процедура Тест()\n\t// заметка " + EM_DASH + "\nКонецПроцедуры"; //$NON-NLS-1$
        String methods = "[{\"methodName\":\"Тест\",\"source\":" + asJsonText(body) + "}]"; //$NON-NLS-1$ //$NON-NLS-2$

        String answer = new ModuleSourceWriter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "replaceMethods", //$NON-NLS-1$ //$NON-NLS-2$
            "methods", methods));

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("invalidCharactersReplaced: 1")); //$NON-NLS-1$
        assertTrue("the place is named by the method it was measured in", //$NON-NLS-1$
            answer.contains("Тест 2:13")); //$NON-NLS-1$
        assertTrue(written(), written().contains("\t// заметка -")); //$NON-NLS-1$
    }

    /**
     * One piece of text as a JSON string literal.
     * <p>
     * Written as a call rather than as an escaped literal, so that the escaping a JSON body needs is
     * stated once and the text under test stays readable.
     * </p>
     *
     * @param text the text to carry
     * @return the text as a JSON string, quotes included
     */
    private static String asJsonText(String text)
    {
        String escaped = text.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\"", "\\\"") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\n", "\\n") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\t", "\\t"); //$NON-NLS-1$ //$NON-NLS-2$
        return "\"" + escaped + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
