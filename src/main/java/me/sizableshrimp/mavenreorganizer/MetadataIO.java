package me.sizableshrimp.mavenreorganizer;

import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class MetadataIO {
    private static final MetadataXpp3Reader XML_READER = new MetadataXpp3Reader();
    private static final MetadataXpp3Writer XML_WRITER = new MetadataXpp3Writer();

    public static Metadata read(Path path) throws XmlPullParserException, IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return read(is);
        }
    }

    public static Metadata read(InputStream inputStream) throws XmlPullParserException, IOException {
        return XML_READER.read(inputStream);
    }

    public static void write(Path path, Metadata metadata) throws IOException {
        try (OutputStream os = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            write(os, metadata);
        }
    }

    public static void write(OutputStream outputStream, Metadata metadata) throws IOException {
        XML_WRITER.write(outputStream, metadata);
    }
}
