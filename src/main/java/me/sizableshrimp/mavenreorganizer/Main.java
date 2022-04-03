package me.sizableshrimp.mavenreorganizer;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public class Main {
    private final Path input;
    private final Path output;
    private final Path legacyOutputPath;
    private final Map<Path, Path> basePathsToRepo = new HashMap<>();

    public Main(Path input, Path output) {
        this.input = input;
        this.output = output;
        this.legacyOutputPath = output.resolve("legacy");

        createBaseRepos();
    }

    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        OptionSpec<File> inputO = parser.accepts("input", "Input directory to process").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> outputO = parser.accepts("output", "Output directory to place reorganized files in").withRequiredArg().ofType(File.class).required();

        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (OptionException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.err.println();
            parser.printHelpOn(System.err);
            System.exit(1);
            return;
        }

        File input = options.valueOf(inputO);
        File output = options.valueOf(outputO);

        if (!input.isDirectory())
            throw new IllegalArgumentException("Input must be a directory");

        if (!output.isDirectory() && !output.mkdirs())
            throw new IllegalArgumentException("Could not make output directory with path " + output.getAbsolutePath());

        Main main = new Main(input.toPath(), output.toPath());
        main.run();
    }

    public void run() throws IOException {
        try (Stream<Path> walker = Files.walk(input)) {
            walker.filter(Files::isRegularFile)
                    .forEach(filePath -> {
                        try {
                            Path outputPath = getOutputPath(filePath);
                            Path parentDir = outputPath.getParent();
                            if (parentDir != null)
                                Files.createDirectories(parentDir);
                            Files.copy(filePath, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        }
    }

    private void createBaseRepos() {
        addRepo("forge", "net/minecraftforge", "de/oceanlabs");
        addRepo("sponge", "org/spongepowered");
        addRepo("cpw", "cpw/mods");
    }

    private Path getOutputPath(Path basePath) {
        for (Map.Entry<Path, Path> entry : basePathsToRepo.entrySet()) {
            if (basePath.startsWith(entry.getKey())) {
                return entry.getValue().resolve(input.relativize(basePath));
            }
        }

        return legacyOutputPath.resolve(input.relativize(basePath));
    }

    private void addRepo(String repo, String... basePaths) {
        Path repoPath = output.resolve(repo);

        for (String folder : basePaths) {
            basePathsToRepo.put(input.resolve(folder), repoPath);
        }
    }
}
