/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Exports the image bytes of a configuration {@code CommonPicture} (ОбщаяКартинка)
 * to a file on disk. Pure filesystem read - a CommonPicture stores its image as a
 * {@code Picture.*} sibling of its {@code .mdo} under {@code src/CommonPictures/<Name>/};
 * no EDT/thick-client API is involved.
 *
 * <p>Two on-disk shapes are handled:
 * <ul>
 *   <li>a flat single image ({@code Picture.png}/{@code .svg}/{@code .ico}/{@code .gif})
 *       - copied verbatim;</li>
 *   <li>a multi-variant {@code Picture.zip} (a base {@code Picture.png} plus DPI tiers
 *       and a {@code manifest.xml}) - by default the base entry is extracted; a single
 *       {@code variant} can be selected, or {@code allVariants=true} dumps every entry
 *       into an output directory.</li>
 * </ul>
 *
 * <p>Listing common pictures is already covered by {@code get_metadata_objects}
 * (metadataType=commonPictures) and {@code edit_metadata operation=listPictures};
 * this tool only adds export.
 */
public class CommonPictureExporter implements IMcpTool
{
    public static final String NAME = "export_common_picture"; //$NON-NLS-1$

    /** The base image entry name inside a multi-variant Picture.zip. */
    private static final String ZIP_BASE_ENTRY = "Picture.png"; //$NON-NLS-1$
    private static final String ZIP_MANIFEST = "manifest.xml"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `config_io` `operation=export_common_picture`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Export a CommonPicture's (ОбщаяКартинка) image bytes to a file. " //$NON-NLS-1$
            + "The picture is read from src/CommonPictures/<Name>/Picture.* (pure filesystem, " //$NON-NLS-1$
            + "no thick client). A flat image (png/svg/ico/gif) is copied verbatim; a " //$NON-NLS-1$
            + "multi-variant Picture.zip extracts the base image by default, one `variant`, or " //$NON-NLS-1$
            + "every entry into a directory with `allVariants=true`. To LIST common pictures " //$NON-NLS-1$
            + "use get_metadata_objects (metadataType=commonPictures) or edit_metadata " //$NON-NLS-1$
            + "operation=listPictures."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "EDT project name that owns the configuration (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", //$NON-NLS-1$
                "CommonPicture name, either 'CommonPicture.<Name>' or the bare '<Name>' (required)", true) //$NON-NLS-1$
            .stringProperty("outputPath", //$NON-NLS-1$
                "Destination path. A FILE path for the default / single-variant export; a " //$NON-NLS-1$
                    + "DIRECTORY path when allVariants=true (required)", true) //$NON-NLS-1$
            .stringProperty("variant", //$NON-NLS-1$
                "Multi-variant only: pick one DPI tier by its manifest entry name (e.g. '200.png'), " //$NON-NLS-1$
                    + "a bare scale ('200'), or a screenDensity token ('hdpi'). Ignored (with a note) " //$NON-NLS-1$
                    + "for a flat single-image picture.") //$NON-NLS-1$
            .booleanProperty("allVariants", //$NON-NLS-1$
                "Multi-variant only: extract every zip entry (all DPI tiers + manifest.xml) into " //$NON-NLS-1$
                    + "outputPath treated as a directory. Default false.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String nameRaw = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String outputPath = JsonUtils.extractStringArgument(params, "outputPath"); //$NON-NLS-1$
        String variant = JsonUtils.extractStringArgument(params, "variant"); //$NON-NLS-1$
        boolean allVariants = JsonUtils.extractBooleanArgument(params, "allVariants", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (nameRaw == null || nameRaw.isEmpty())
        {
            return ToolResult.error("name is required (CommonPicture.<Name> or bare <Name>)").toJson(); //$NON-NLS-1$
        }
        if (outputPath == null || outputPath.isEmpty())
        {
            return ToolResult.error("outputPath is required").toJson(); //$NON-NLS-1$
        }

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        if (project.getLocation() == null)
        {
            return ToolResult.error("Project '" + projectName + "' has no filesystem location.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Accept CommonPicture.<Name> or a bare <Name>.
        String name = nameRaw.startsWith("CommonPicture.") //$NON-NLS-1$
            ? nameRaw.substring("CommonPicture.".length()) //$NON-NLS-1$
            : nameRaw;
        if (name.isEmpty())
        {
            return ToolResult.error("name is missing the picture name after the 'CommonPicture.' prefix.").toJson(); //$NON-NLS-1$
        }

        // The metadata registry intentionally leaves CommonPicture's directoryName null
        // (it is shared with BSL module-path resolution, which must keep rejecting a
        // CommonPicture FQN - the type has no module). "CommonPictures" is this tool's own
        // known folder; getDirectoryName is consulted only so a future registry entry wins.
        String dirName = MetadataTypeCatalog.getDirectoryName("CommonPicture"); //$NON-NLS-1$
        if (dirName == null)
        {
            dirName = "CommonPictures"; //$NON-NLS-1$
        }
        Path pictureDir = project.getLocation().toFile().toPath()
            .resolve("src").resolve(dirName).resolve(name); //$NON-NLS-1$
        if (!Files.isDirectory(pictureDir))
        {
            return ToolResult.error("CommonPicture." + name + " not found in project '" //$NON-NLS-1$ //$NON-NLS-2$
                + projectName + "' (no " + dirName + "/" + name + "/ directory). List available " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "pictures via edit_metadata operation=listPictures.").toJson(); //$NON-NLS-1$
        }

        Path source = findPictureFile(pictureDir);
        if (source == null)
        {
            return ToolResult.success()
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("name", name) //$NON-NLS-1$
                .put("imageMissing", true) //$NON-NLS-1$
                .put("message", "CommonPicture." + name + " exists but has no Picture.* image file " //$NON-NLS-1$ //$NON-NLS-2$
                    + "on disk (the .mdo alone is a valid empty picture).") //$NON-NLS-1$
                .toJson();
        }

        String sourceRel = "src/" + dirName + "/" + name + "/" + source.getFileName(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String ext = fileExtension(source.getFileName().toString());
        long start = System.nanoTime();
        try
        {
            if ("zip".equalsIgnoreCase(ext)) //$NON-NLS-1$
            {
                return exportFromZip(source, sourceRel, projectName, name, outputPath, variant,
                    allVariants, start);
            }
            return exportFlat(source, sourceRel, ext, projectName, name, outputPath, variant,
                allVariants, start);
        }
        catch (Exception e)
        {
            Activator.logError("export_common_picture failed for CommonPicture." + name, e); //$NON-NLS-1$
            return ToolResult.error("Failed to export CommonPicture." + name + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage()).toJson();
        }
    }

    /** Copies a flat single-image picture verbatim to outputPath (a file). */
    private String exportFlat(Path source, String sourceRel, String ext, String projectName,
        String name, String outputPath, String variant, boolean allVariants, long start)
        throws Exception
    {
        Path out = Paths.get(outputPath);
        Path parent = out.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        byte[] bytes = Files.readAllBytes(source);
        Files.write(out, bytes);
        ToolResult ok = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("name", name) //$NON-NLS-1$
            .put("sourcePath", sourceRel) //$NON-NLS-1$
            .put("format", ext.isEmpty() ? "unknown" : ext.toLowerCase()) //$NON-NLS-1$ //$NON-NLS-2$
            .put("variant", "base") //$NON-NLS-1$ //$NON-NLS-2$
            .put("outputPath", out.toAbsolutePath().toString()) //$NON-NLS-1$
            .put("sizeBytes", bytes.length) //$NON-NLS-1$
            .put("elapsedMs", (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
        if ((variant != null && !variant.isEmpty()) || allVariants)
        {
            ok.put("note", "This picture is a single flat image with no variants; " //$NON-NLS-1$ //$NON-NLS-2$
                + "variant / allVariants were ignored."); //$NON-NLS-1$
        }
        return ok.put("message", "Exported CommonPicture." + name + " to " //$NON-NLS-1$ //$NON-NLS-2$
            + out.toAbsolutePath()).toJson();
    }

    /** Extracts from a multi-variant Picture.zip: base entry, a chosen variant, or all entries. */
    private String exportFromZip(Path source, String sourceRel, String projectName, String name,
        String outputPath, String variant, boolean allVariants, long start) throws Exception
    {
        try (ZipFile zip = new ZipFile(source.toFile()))
        {
            List<String> entryNames = new ArrayList<>();
            for (java.util.Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();)
            {
                ZipEntry ze = en.nextElement();
                if (!ze.isDirectory())
                {
                    entryNames.add(ze.getName());
                }
            }
            List<String> available = readManifestVariants(zip, entryNames);

            if (allVariants)
            {
                // Normalize outDir up front so the zip-slip startsWith check compares two
                // normalized paths (an unnormalized outputPath with . / .. would else
                // false-reject legitimate entries).
                Path outDir = Paths.get(outputPath).toAbsolutePath().normalize();
                Files.createDirectories(outDir);
                long total = 0;
                int count = 0;
                for (String entryName : entryNames)
                {
                    ZipEntry ze = zip.getEntry(entryName);
                    // Guard against a zip-slip path in the entry name.
                    Path target = outDir.resolve(entryName).normalize();
                    if (!target.startsWith(outDir))
                    {
                        continue;
                    }
                    if (target.getParent() != null)
                    {
                        Files.createDirectories(target.getParent());
                    }
                    try (InputStream is = zip.getInputStream(ze))
                    {
                        byte[] bytes = readAll(is);
                        Files.write(target, bytes);
                        total += bytes.length;
                        count++;
                    }
                }
                ToolResult all = ToolResult.success()
                    .put("operation", NAME) //$NON-NLS-1$
                    .put("projectName", projectName) //$NON-NLS-1$
                    .put("name", name) //$NON-NLS-1$
                    .put("sourcePath", sourceRel) //$NON-NLS-1$
                    .put("format", "zip-multivariant") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("variant", "all") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("availableVariants", available) //$NON-NLS-1$
                    .put("extractedEntries", count) //$NON-NLS-1$
                    .put("outputPath", outDir.toString()) //$NON-NLS-1$
                    .put("sizeBytes", total) //$NON-NLS-1$
                    .put("elapsedMs", (System.nanoTime() - start) / 1_000_000L); //$NON-NLS-1$
                if (variant != null && !variant.isEmpty())
                {
                    all.put("note", "allVariants=true extracts every entry; the 'variant' " //$NON-NLS-1$ //$NON-NLS-2$
                        + "selector was ignored."); //$NON-NLS-1$
                }
                return all.put("message", "Extracted " + count + " entries of CommonPicture." //$NON-NLS-1$ //$NON-NLS-2$
                    + name + " into " + outDir).toJson(); //$NON-NLS-1$
            }

            // Single-entry extraction: chosen variant, else the base image.
            String resolvedEntry;
            String resolvedLabel;
            if (variant != null && !variant.isEmpty())
            {
                resolvedEntry = matchVariantEntry(zip, entryNames, variant);
                if (resolvedEntry == null)
                {
                    return ToolResult.error("Variant '" + variant + "' not found in CommonPicture." //$NON-NLS-1$ //$NON-NLS-2$
                        + name + ". Available: " + String.join(", ", available) //$NON-NLS-1$ //$NON-NLS-2$
                        + " (or pass allVariants=true).").toJson(); //$NON-NLS-1$
                }
                resolvedLabel = variant;
            }
            else
            {
                resolvedEntry = entryNames.contains(ZIP_BASE_ENTRY) ? ZIP_BASE_ENTRY
                    : firstImageEntry(entryNames);
                if (resolvedEntry == null)
                {
                    return ToolResult.error("CommonPicture." + name + " zip has no extractable " //$NON-NLS-1$ //$NON-NLS-2$
                        + "image entry (entries: " + String.join(", ", entryNames) + ").").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                resolvedLabel = "base"; //$NON-NLS-1$
            }

            Path out = Paths.get(outputPath);
            Path parent = out.getParent();
            if (parent != null)
            {
                Files.createDirectories(parent);
            }
            byte[] bytes;
            try (InputStream is = zip.getInputStream(zip.getEntry(resolvedEntry)))
            {
                bytes = readAll(is);
            }
            Files.write(out, bytes);
            return ToolResult.success()
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("name", name) //$NON-NLS-1$
                .put("sourcePath", sourceRel) //$NON-NLS-1$
                .put("format", "zip-multivariant") //$NON-NLS-1$ //$NON-NLS-2$
                .put("variant", resolvedLabel) //$NON-NLS-1$
                .put("extractedEntry", resolvedEntry) //$NON-NLS-1$
                .put("availableVariants", available) //$NON-NLS-1$
                .put("outputPath", out.toAbsolutePath().toString()) //$NON-NLS-1$
                .put("sizeBytes", bytes.length) //$NON-NLS-1$
                .put("elapsedMs", (System.nanoTime() - start) / 1_000_000L) //$NON-NLS-1$
                .put("message", "Exported CommonPicture." + name + " [" + resolvedEntry + "] to " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + out.toAbsolutePath()) //$NON-NLS-1$
                .toJson();
        }
    }

    /** Finds the first {@code Picture.*} sibling of the .mdo (the image resource). */
    private Path findPictureFile(Path pictureDir)
    {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pictureDir, "Picture.*")) //$NON-NLS-1$
        {
            for (Path p : ds)
            {
                // Skip the .mdo - it collides with the glob only when the picture is
                // literally named "Picture" (Picture.mdo); the image is Picture.<img-ext>.
                if (Files.isRegularFile(p)
                    && !p.getFileName().toString().toLowerCase().endsWith(".mdo")) //$NON-NLS-1$
                {
                    return p;
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("scanning " + pictureDir + " for Picture.* failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Reads the {@code <PictureVariant name=... screenDensity=.../>} names from the zip's
     * {@code manifest.xml}, de-duplicated by entry name. Falls back to the raw image entry
     * names (minus manifest.xml) when there is no manifest or it cannot be parsed.
     */
    private List<String> readManifestVariants(ZipFile zip, List<String> entryNames)
    {
        Set<String> variants = new LinkedHashSet<>();
        ZipEntry manifest = zip.getEntry(ZIP_MANIFEST);
        if (manifest != null)
        {
            try (InputStream is = zip.getInputStream(manifest))
            {
                String xml = new String(readAll(is), java.nio.charset.StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("name=\"([^\"]+)\"").matcher(xml); //$NON-NLS-1$
                while (m.find())
                {
                    variants.add(m.group(1));
                }
            }
            catch (Exception e)
            {
                Activator.logWarning("manifest.xml parse failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        if (variants.isEmpty())
        {
            for (String en : entryNames)
            {
                if (!ZIP_MANIFEST.equalsIgnoreCase(en))
                {
                    variants.add(en);
                }
            }
        }
        return new ArrayList<>(variants);
    }

    /**
     * Resolves a {@code variant} selector to a real zip entry name: an exact entry match,
     * a bare scale ({@code 200} -> {@code 200.png}), or a screenDensity token matched against
     * the manifest. Returns {@code null} when nothing matches.
     */
    private String matchVariantEntry(ZipFile zip, List<String> entryNames, String variant)
    {
        // Exact entry name.
        for (String en : entryNames)
        {
            if (en.equalsIgnoreCase(variant))
            {
                return en;
            }
        }
        // Bare scale -> <scale>.png
        String asPng = variant + ".png"; //$NON-NLS-1$
        for (String en : entryNames)
        {
            if (en.equalsIgnoreCase(asPng))
            {
                return en;
            }
        }
        // screenDensity token via manifest: <PictureVariant name="200.png" screenDensity="hdpi"/>
        ZipEntry manifest = zip.getEntry(ZIP_MANIFEST);
        if (manifest != null)
        {
            try (InputStream is = zip.getInputStream(manifest))
            {
                String xml = new String(readAll(is), java.nio.charset.StandardCharsets.UTF_8);
                Matcher m = Pattern.compile(
                    "<PictureVariant\\b[^>]*?name=\"([^\"]+)\"[^>]*?screenDensity=\"([^\"]*)\"") //$NON-NLS-1$
                    .matcher(xml);
                while (m.find())
                {
                    if (variant.equalsIgnoreCase(m.group(2)) && entryNames.contains(m.group(1)))
                    {
                        return m.group(1);
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logWarning("manifest.xml variant match failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }

    /** First entry that looks like an image (not the manifest). */
    private String firstImageEntry(List<String> entryNames)
    {
        for (String en : entryNames)
        {
            if (!ZIP_MANIFEST.equalsIgnoreCase(en))
            {
                return en;
            }
        }
        return null;
    }

    private static String fileExtension(String fileName)
    {
        int dot = fileName.lastIndexOf('.');
        return (dot >= 0 && dot < fileName.length() - 1) ? fileName.substring(dot + 1) : ""; //$NON-NLS-1$
    }

    private static byte[] readAll(InputStream is) throws java.io.IOException
    {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1)
        {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
