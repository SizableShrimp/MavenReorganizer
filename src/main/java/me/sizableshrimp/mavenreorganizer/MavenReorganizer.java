package me.sizableshrimp.mavenreorganizer;

import me.sizableshrimp.mavenreorganizer.data.Artifact;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
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
        run(releases, releasesMapper, true);
        run(proxy, proxyMapper, false);
    }

    private void run(Path folderPath, Map<Path, Repo> mapper, boolean useLegacy) {
        List<Artifact> artifacts = new ArrayList<>();

        try (Stream<Path> walker = Files.walk(folderPath)) {
            walker.filter(Files::isRegularFile).forEach(filePath -> {
                Artifact artifact = Artifact.createFromPath(folderPath, filePath);

                if (artifact != null)
                    artifacts.add(artifact);
            });
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // output metadata path -> metadata + whether its snapshot or release data
        Map<Path, MetadataInfo> metadataMap = new HashMap<>();

        for (Artifact artifact : artifacts) {
            Path outputMetadataPath = getOutputPath(mapper, artifact, true, useLegacy);
            if (outputMetadataPath == null)
                continue;

            Path outputArtifactPath = getOutputPath(mapper, artifact, false, useLegacy);
            if (outputArtifactPath == null)
                continue;

            if (!metadataMap.containsKey(outputMetadataPath)) {
                Path metadataPath = artifact.getMetadataPath(folderPath);
                try {
                    MetadataInfo metadataInfo = new MetadataInfo(MetadataIO.read(metadataPath), artifact.isSnapshot());
                    metadataMap.put(outputMetadataPath, metadataInfo);

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

        metadataMap.forEach((outputMetadataPath, metadataInfo) -> {
            Metadata metadata = metadataInfo.metadata;

            try {
                if (metadataInfo.isSnapshot) {
                    // Snapshots only; clear release versions
                    metadata.getVersioning().getVersions().removeIf(version -> !version.endsWith("-SNAPSHOT"));
                    // Set release to null
                    metadata.getVersioning().setRelease(null);
                    // Set latest to the first entry in versions
                    metadata.getVersioning().setLatest(metadata.getVersioning().getVersions().get(0));
                } else {
                    // Releases only; clear snapshot versions
                    metadata.getVersioning().getVersions().removeIf(version -> version.endsWith("-SNAPSHOT"));
                    // Set latest to release
                    metadata.getVersioning().setLatest(metadata.getVersioning().getRelease());
                }
                // TODO change lastUpdated timestamp based on release/latest versions?

                writeMetadata(outputMetadataPath, metadata);
            } catch (IOException e) {
                System.err.println("Error when writing metadata to path: " + outputMetadataPath);
                e.printStackTrace();
            }
        });

        boolean b = true;
    }

    private void writeMetadata(Path metadataPath, Metadata metadata) throws IOException {
        if (this.simulate)
            return;

        Path parentDir = metadataPath.getParent();
        if (parentDir != null)
            Files.createDirectories(parentDir);

        MetadataIO.write(metadataPath, metadata);
    }

    private void copyArtifact(Path folderPath, Artifact artifact, Path outputArtifactPath) throws IOException {
        if (this.simulate)
            return;

        Path parentDir = outputArtifactPath.getParent();
        if (parentDir != null)
            Files.createDirectories(parentDir);

        Files.copy(artifact.getPath(folderPath), outputArtifactPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private Path getOutputPath(Map<Path, Repo> mapper, Artifact artifact, boolean metadata, boolean useLegacy) {
        Path relativePath = artifact.getRelativePath();

        for (Map.Entry<Path, Repo> entry : mapper.entrySet()) {
            Path basePath = entry.getKey();
            Repo repo = entry.getValue();

            if (relativePath.startsWith(basePath)) {
                return metadata ? repo.getMetadataPath(output, artifact) : repo.getPath(output, artifact);
            }
        }

        if (useLegacy) {
            return metadata
                    ? Repo.LEGACY.getMetadataPath(output, artifact)
                    : Repo.LEGACY.getPath(output, artifact);
        }

        return null;
    }

    private void createBaseRepos() {
        addReleasesRepo("lex", "net/minecraftforge/lex");
        addReleasesRepo("forge", "net/minecraftforge", "de/oceanlabs");
        addReleasesRepo("sponge", "org/spongepowered");
        addReleasesRepo("cpw", "cpw/mods");

        addProxyRepo("sponge_proxy", "org/spongepowered");
    }

    private void addReleasesRepo(String repo, String... basePaths) {
        addRepo(releasesMapper, repo, basePaths);
    }

    private void addProxyRepo(String repo, String... basePaths) {
        addRepo(proxyMapper, repo, basePaths);
    }

    private void addRepo(Map<Path, Repo> mapper, String repo, String... basePaths) {
        for (String folder : basePaths) {
            mapper.put(Paths.get(folder), Repo.create(repo));
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

    private record MetadataInfo(Metadata metadata, boolean isSnapshot) {}
}
