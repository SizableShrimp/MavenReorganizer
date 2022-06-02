package me.sizableshrimp.mavenreorganizer;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import me.sizableshrimp.mavenreorganizer.data.Artifact;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final boolean resume;
    private final Map<Path, Repo> releasesMapper = new LinkedHashMap<>();
    private final Map<Path, Repo> proxyMapper = new LinkedHashMap<>();

    public MavenReorganizer(Path releases, Path proxy, Path output, boolean simulate, boolean resume) {
        this.releases = releases;
        this.proxy = proxy;
        this.output = output;
        this.simulate = simulate;
        this.resume = resume;

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
        System.out.println("Processing " + artifacts.size() + " artifacts");
        float idx = 0;
        int percent = 0;

        for (Artifact artifact : artifacts) {
            idx++;
            if (idx / artifacts.size() * 100 >= percent + 10) {
                percent += 10;
                System.out.println("Processed " + percent + "% (" + (int)idx + "/" + artifacts.size() + ")");
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

        if (!this.simulate && !deleted.isEmpty()) {
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

            boolean shouldWrite = true;

            if (this.resume) {
                try (ByteArrayOutputStream tempOut = new ByteArrayOutputStream()) {
                    MetadataIO.write(tempOut, metadata);
                    String metadataToWrite = tempOut.toString(StandardCharsets.UTF_8);
                    shouldWrite = shouldWrite(metadataToWrite, metadataPath);
                }
            }

            if (shouldWrite)
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
                String metadataHash = entry.getValue().hashBytes(metadataBytes).toString();
                if (!this.resume || shouldWriteHash(metadataHash, hashPath))
                    Files.writeString(hashPath, metadataHash, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        }
    }

    private HashCode getHash(Path path, HashFunction hashFunction) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(path);

            return hashFunction.hashBytes(bytes);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private boolean shouldWrite(Path inputPath, Path outputPath) throws IOException {
        HashFunction hashFunction = Hashing.md5();
        HashCode inputHash = getHash(inputPath, hashFunction);
        HashCode outputHash = getHash(outputPath, hashFunction);

        return !Objects.equals(inputHash, outputHash);
    }

    private boolean shouldWrite(String inputData, Path outputPath) throws IOException {
        HashFunction hashFunction = Hashing.md5();
        HashCode inputHash = hashFunction.hashString(inputData, StandardCharsets.UTF_8);
        HashCode outputHash = getHash(outputPath, hashFunction);

        return !Objects.equals(inputHash, outputHash);
    }

    private boolean shouldWriteHash(String inputHash, Path outputHashPath) throws IOException {
        try {
            return !inputHash.equals(Files.readString(outputHashPath, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return true;
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

        Path inputArtifactPath = artifact.getPath(folderPath);

        if (!this.resume || shouldWrite(inputArtifactPath, outputArtifactPath)) {
            Files.copy(inputArtifactPath, outputArtifactPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
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
        addReleasesRepo("forge", "net/minecraftforge", "de/oceanlabs");
        addReleasesRepo("sponge", "org/spongepowered");
        addProxyRepo   ("sponge_proxy", "org/spongepowered");
        addReleasesRepo("cpw_root", "cpw/mods"); // Can't use 'cpw' as repo name because cpw uses it as the top level..
        addReleasesRepo("mcmodlauncher", "org/mcmodlauncher");
        addReleasesRepo("ldtteam", "com/ldtteam");
        addReleasesRepo("glitchfiend", "com/github/glitchfiend");

        String[] installerFiles = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/installer_artifacts.txt"))).lines()
            .map(line -> {
                String[] pts = line.split(":");
                if ("com.paulscode:soundsystem".equals(pts[0] + ':' + pts[1]))
                    return "com/paulscode/soundsystem"; // We have some versions that arnt found in installers, lets keep them anyways
                return  pts[0].replace('.', '/') + '/' + pts[1] + '/' + pts[2];
            })
            .toArray(String[]::new);
        addRepo(proxyMapper, addReleasesRepo("installer", installerFiles), installerFiles);
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
            return new Repo(Paths.get(repo + "/releases"), Paths.get(repo + "/snapshots"));
        }

        Path getPath(Path outputFolder, Artifact artifact) {
            return outputFolder.resolve(artifact.getPath(releases, snapshots));
        }

        Path getMetadataPath(Path outputFolder, Artifact artifact) {
            return outputFolder.resolve(artifact.getMetadataPath(releases, snapshots));
        }
    }
}
