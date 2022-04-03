package me.sizableshrimp.mavenreorganizer.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public record Artifact(String groupId, String artifactId, String version, boolean isSnapshot, String file) {
    public static Artifact createFromPath(Path folderPath, Path path) {
        path = folderPath.relativize(path);

        Path versionPath = path.getParent();
        if (versionPath == null)
            return null;
        String version = versionPath.getFileName().toString();
        boolean isSnapshot = version.endsWith("-SNAPSHOT");

        Path artifactIdPath = versionPath.getParent();
        if (artifactIdPath == null)
            return null;
        String artifactId = artifactIdPath.getFileName().toString();

        if (!Files.exists(folderPath.resolve(artifactIdPath).resolve("maven-metadata.xml")))
            return null;

        Path groupIdPath = artifactIdPath.getParent();
        if (groupIdPath == null || groupIdPath.getNameCount() <= 1)
            return null;
        String groupId = groupIdPath.toString().replace('/', '.').replace('\\', '.');

        return new Artifact(groupId, artifactId, version, isSnapshot, path.getFileName().toString());
    }

    public boolean isMetadata() {
        // Includes maven-metadata.xml and any hash files
        return file.startsWith("maven-metadata.xml");
    }

    public Path getPath(Path path) {
        return path.resolve(getRelativePath());
    }

    public Path getPath(Path releasesPath, Path snapshotsPath) {
        return isSnapshot
                ? snapshotsPath.resolve(getRelativePath())
                : releasesPath.resolve(getRelativePath());
    }

    public Path getRelativePath() {
        return Paths.get(groupId.replace('.', '/'), artifactId, version, file);
    }

    public Path getMetadataPath(Path path) {
        return path.resolve(getRelativeMetadataPath());
    }

    public Path getMetadataPath(Path releasesPath, Path snapshotsPath) {
        return isSnapshot
                ? snapshotsPath.resolve(getRelativeMetadataPath())
                : releasesPath.resolve(getRelativeMetadataPath());
    }

    public Path getRelativeMetadataPath() {
        return Paths.get(groupId.replace('.', '/'), artifactId, "maven-metadata.xml");
    }
}
