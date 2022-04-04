package me.sizableshrimp.mavenreorganizer;

import me.sizableshrimp.mavenreorganizer.data.Artifact;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class MavenReorganizer {
    private final Path releases;
    private final Path proxy;
    private final Path output;
    private final boolean simulate;
    private final Map<Path, Repo> releasesMapper = new LinkedHashMap<>();
    private final Map<Path, Repo> proxyMapper = new LinkedHashMap<>();

    public MavenReorganizer(Path releases, Path proxy, Path output, boolean simulate) {
        this.releases = releases;
        this.proxy = proxy;
        this.output = output;
        this.simulate = simulate;

        createBaseRepos();
    }

    public void run() {
        run(releases, releasesMapper);
        run(proxy, proxyMapper);
    }

    private void run(Path folderPath, Map<Path, Repo> mapper) {
        List<Artifact> artifacts = new ArrayList<>();

        try (Stream<Path> walker = Files.walk(folderPath)) {
            walker.filter(Files::isRegularFile).forEach(filePath -> {
                Artifact artifact = Artifact.createFromPath(folderPath, filePath);

                if (artifact != null) {
                    if (!artifact.isSnapshot() && artifact.isMetadata()) {
                        // Reposilite is dumb and makes these invalid metadata files for release versions if queried.
                        // So, we drop them here.
                        // Lex's fixed version does not do that anymore so deleting them here should keep them gone for good.
                        return;
                    }
                    artifacts.add(artifact);
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // output metadata path -> metadata
        Map<Path, Metadata> metadataMap = new HashMap<>();

        for (Artifact artifact : artifacts) {
            Path outputMetadataPath = getOutputPath(mapper, artifact, true);
            if (outputMetadataPath == null)
                continue;

            Path outputArtifactPath = getOutputPath(mapper, artifact, false);
            if (outputArtifactPath == null)
                continue;

            if (!metadataMap.containsKey(outputMetadataPath)) {
                Path metadataPath = artifact.getMetadataPath(folderPath);
                try {
                    metadataMap.put(outputMetadataPath, MetadataIO.read(metadataPath));

                    // maven-metadata.xml under snapshot version folders will be considered artifacts and copied to the relevant output repo
                    // They need no changes, so this works fine
                } catch (IOException | XmlPullParserException e) {
                    System.err.println("Error when reading metadata: " + metadataPath);
                    e.printStackTrace();
                }
            }

            try {
                copyArtifact(folderPath, artifact, outputArtifactPath);
            } catch (IOException e) {
                System.err.println("Error when copying artifact " + artifact + " to output path " + outputArtifactPath);
                e.printStackTrace();
            }
        }

        metadataMap.forEach((outputMetadataPath, metadata) -> {
            Versioning versioning = metadata.getVersioning();

            // Only keep versions in the maven metadata which exist in the output folder
            int beforeSize = versioning.getVersions().size();
            versioning.getVersions().removeIf(version -> !Files.isDirectory(outputMetadataPath.resolveSibling(version)));
            int afterSize = versioning.getVersions().size();

            if (beforeSize != afterSize && !versioning.getVersions().contains(versioning.getRelease())) {
                String newRelease = versioning.getVersions().stream()
                        .filter(version -> !version.endsWith("-SNAPSHOT"))
                        .findFirst()
                        .orElse(null);
                // If we removed any entries and the release version is not in the list, set it to the first non-SNAPSHOT entry in versions (or null)
                versioning.setRelease(newRelease);
            }
            if (beforeSize != afterSize && !versioning.getVersions().contains(versioning.getLatest())) {
                // If we removed any entries and the latest version is not in the list, set it to the first entry in versions
                versioning.setRelease(versioning.getVersions().get(0));
            }

            // TODO change lastUpdated timestamp based on release/latest versions?

            try {
                writeMetadata(outputMetadataPath, metadata);
                // TODO Do we need to write out all the hash files?
            } catch (IOException e) {
                System.err.println("Error when writing metadata to path: " + outputMetadataPath);
                e.printStackTrace();
            }
        });
    }

    private void writeMetadata(Path metadataPath, Metadata metadata) throws IOException {
        if (this.simulate) {
            System.out.println("Would have wrote metadata to path " + metadataPath);
            return;
        }

        Path parentDir = metadataPath.getParent();
        if (parentDir != null)
            Files.createDirectories(parentDir);

        MetadataIO.write(metadataPath, metadata);
    }

    private void copyArtifact(Path folderPath, Artifact artifact, Path outputArtifactPath) throws IOException {
        if (this.simulate) {
            System.out.println("Would have copied artifact " + artifact + " to output path " + outputArtifactPath);
            return;
        }

        Path parentDir = outputArtifactPath.getParent();
        if (parentDir != null)
            Files.createDirectories(parentDir);

        Files.copy(artifact.getPath(folderPath), outputArtifactPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private Path getOutputPath(Map<Path, Repo> mapper, Artifact artifact, boolean metadata) {
        Path relativePath = artifact.getRelativePath();

        Path parent = relativePath;
        while (parent != null) {
            Repo repo = mapper.get(parent);

            if (repo != null) {
                return metadata ? repo.getMetadataPath(output, artifact) : repo.getPath(output, artifact);
            }

            parent = parent.getParent();
        }

        return null;
    }

    private void createBaseRepos() {
        addReleasesRepo("lex", "net/minecraftforge/lex");
        addReleasesRepo("forge", "net/minecraftforge", "de/oceanlabs");
        addReleasesRepo("sponge", "org/spongepowered");
        addReleasesRepo("cpw", "cpw/mods");
        String[] installerLegacy = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/installer_artifacts.txt"))).lines()
                .map(line -> line.substring(0, line.lastIndexOf('/')))
                .toArray(String[]::new);
        addReleasesRepo("legacy", installerLegacy);

        addProxyRepo("sponge_proxy", "org/spongepowered");
    }

    private void addReleasesRepo(String repo, String... basePaths) {
        addRepo(releasesMapper, repo, basePaths);
    }

    private void addProxyRepo(String repo, String... basePaths) {
        addRepo(proxyMapper, repo, basePaths);
    }

    private void addRepo(Map<Path, Repo> mapper, String repoName, String... basePaths) {
        Repo repo = Repo.create(repoName);
        for (String folder : basePaths) {
            if (mapper.put(Paths.get(folder), repo) != null) {
                throw new IllegalStateException("Duplicate repo path of " + folder);
            }
        }
    }

    private record Repo(Path releases, Path snapshots) {
        static final Repo LEGACY = create("legacy");

        static Repo create(String repo) {
            return new Repo(Paths.get(repo + "-releases"), Paths.get(repo + "-snapshots"));
        }

        Path getPath(Path outputFolder, Artifact artifact) {
            return outputFolder.resolve(artifact.getPath(releases, snapshots));
        }

        Path getMetadataPath(Path outputFolder, Artifact artifact) {
            return outputFolder.resolve(artifact.getMetadataPath(releases, snapshots));
        }
    }
}
