package me.sizableshrimp.mavenreorganizer;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MavenReorganizer {
    @SuppressWarnings("deprecation")
    private static final Map<String, HashFunction> METADATA_HASH_FUNCTIONS = Map.of(
            "md5", Hashing.md5(),
            "sha1", Hashing.sha1(),
            "sha256", Hashing.sha256(),
            "sha512", Hashing.sha512()
    );
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
        Set<String> ur = run(releases, releasesMapper, "releases");
        run(proxy, proxyMapper, null);

        if (!ur.isEmpty()) {
            System.out.println("Unclaimed Releases:");
            ur.forEach(System.out::println);
        }
    }

    private Set<String> run(Path folderPath, Map<Path, Repo> mapper, String name) {
        List<Artifact> artifacts = new ArrayList<>();
        Set<String> unclaimed = new TreeSet<>();
        Set<String> deleted = new TreeSet<>();

        try (Stream<Path> walker = Files.walk(folderPath)) {
            walker.filter(Files::isRegularFile).forEach(filePath -> {
                Artifact artifact = Artifact.createFromPath(folderPath, filePath);

                if (artifact != null) {
                    if (!artifact.isSnapshot() && artifact.isMetadata()) {
                        // Reposilite is dumb and makes these invalid metadata files for release versions if queried.
                        // So, we drop them here.
                        // Lex's fixed version does not do that anymore so deleting them here should keep them gone for good.
                        if (!isHash(artifact))
                            deleted.add(artifact.getRelativePath().toString());
                        return;
                    }
                    artifacts.add(artifact);
                }
            });
        } catch (IOException e) {
            sneakyThrow(e);
            return unclaimed;
        }

        // output metadata path -> metadata
        Map<Path, Metadata> metadataMap = new HashMap<>();
        System.out.println("Processing " + artifacts.size() + " aritfacts");
        float idx = 0;
        int percent = 0;

        for (Artifact artifact : artifacts) {
            idx++;
            if (idx / artifacts.size() * 100 >= percent + 10) {
                percent += 10;
                System.out.println("Processed " + percent + "% (" + idx + "/" + artifacts.size() + ")");
            }
            Path outputMetadataPath = getOutputPath(mapper, artifact, true);
            if (outputMetadataPath == null) {
                unclaimed.add(artifact.groupId().replace('.', '/') + '/' + artifact.artifactId());
                continue;
            }

            Path outputArtifactPath = getOutputPath(mapper, artifact, false);
            if (outputArtifactPath == null) {
                unclaimed.add(artifact.groupId().replace('.', '/') + '/' + artifact.artifactId());
                continue;
            }

            if (!metadataMap.containsKey(outputMetadataPath)) {
                Path metadataPath = artifact.getMetadataPath(folderPath);
                try {
                    metadataMap.put(outputMetadataPath, MetadataIO.read(metadataPath));

                    // maven-metadata.xml under snapshot version folders will be considered artifacts and copied to the relevant output repo
                    // They need no changes, so this works fine
                } catch (IOException | XmlPullParserException e) {
                    System.err.println("Error when reading metadata: " + metadataPath);
                    sneakyThrow(e);
                }
            }

            try {
                copyArtifact(folderPath, artifact, outputArtifactPath);
            } catch (IOException e) {
                System.err.println("Error when copying artifact " + artifact + " to output path " + outputArtifactPath);
                sneakyThrow(e);
            }
        }

        metadataMap.forEach((outputMetadataPath, metadata) -> {
            Versioning versioning = metadata.getVersioning();

            if (!this.simulate) {
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
                    versioning.setLatest(versioning.getVersions().isEmpty() ? null : versioning.getVersions().get(0));
                }
            }

            // TODO change lastUpdated timestamp based on release/latest versions?

            try {
                writeMetadata(outputMetadataPath, metadata);
                // TODO Do we need to write out all the hash files?
            } catch (IOException e) {
                System.err.println("Error when writing metadata to path: " + outputMetadataPath);
                sneakyThrow(e);
            }
        });

        if (!deleted.isEmpty()) {
            Path deletedF = this.output.resolve(folderPath.getFileName() + "-deleted.txt");
            try {
                Files.writeString(deletedF, deleted.stream().collect(Collectors.joining("\n")));
            } catch (IOException e) {
                System.err.println("Error when writing deleted list: " + deletedF);
                sneakyThrow(e);
            }
        }

        return unclaimed;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E)e;
    }

    private static boolean isHash(Artifact artifact) {
        for (String key : METADATA_HASH_FUNCTIONS.keySet())
            if (artifact.file().endsWith('.' + key))
                return true;
        return false;
    }

    private void writeMetadata(Path metadataPath, Metadata metadata) throws IOException {
        if (this.simulate) {
            System.out.println("Would have wrote metadata to path " + metadataPath);
        } else {
            Path parentDir = metadataPath.getParent();
            if (parentDir != null)
                Files.createDirectories(parentDir);

            MetadataIO.write(metadataPath, metadata);
        }

        try {
            writeMetadataHashes(metadataPath);
        } catch (IOException e) {
            System.err.println("Error when writing metadata hash");
            sneakyThrow(e);
        }
    }

    private void writeMetadataHashes(Path metadataPath) throws IOException {
        byte[] metadataBytes = this.simulate ? null : Files.readAllBytes(metadataPath);

        for (var entry : METADATA_HASH_FUNCTIONS.entrySet()) {
            Path hashPath = metadataPath.resolveSibling("maven-metadata.xml." + entry.getKey());

            if (this.simulate) {
                System.out.println("Would have wrote hash file to path " + hashPath);
            } else {
                Files.writeString(hashPath, entry.getValue().hashBytes(metadataBytes).toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        }
    }

    private void copyArtifact(Path folderPath, Artifact artifact, Path outputArtifactPath) throws IOException {
        if (this.simulate) {
            System.out.println("Would have copied artifact " + artifact.getPath(folderPath) + " to output path " + outputArtifactPath);
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
        addProxyRepo   ("sponge_proxy", "org/spongepowered");
        addReleasesRepo("cpw", "cpw/mods");
        addReleasesRepo("mcmodlauncher", "org/mcmodlauncher");
        addReleasesRepo("ldtteam", "com/ldtteam");
        addReleasesRepo("legacy", "com/github/glitchfiend", "com/paulscode/soundsystem");

        String[] installer = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/installer_artifacts.txt"))).lines()
            .map(line -> {
                String[] pts = line.split(":");
                return  pts[0].replace('.', '/') + '/' + pts[1] + '/' + pts[2];
            })
            .toArray(String[]::new);
        addRepo(proxyMapper, addReleasesRepo("installer", installer), installer);

    }

    private Repo addReleasesRepo(String repo, String... basePaths) {
        return addRepo(releasesMapper, repo, basePaths);
    }

    private Repo addProxyRepo(String repo, String... basePaths) {
        return addRepo(proxyMapper, repo, basePaths);
    }

    private Repo addRepo(Map<Path, Repo> mapper, String repoName, String... basePaths) {
        return addRepo(mapper, Repo.create(repoName), basePaths);
    }

    private Repo addRepo(Map<Path, Repo> mapper, Repo repo, String... basePaths) {
        for (String folder : basePaths) {
            if (mapper.put(Paths.get(folder), repo) != null) {
                throw new IllegalStateException("Duplicate repo path of " + folder);
            }
        }
        return repo;
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
