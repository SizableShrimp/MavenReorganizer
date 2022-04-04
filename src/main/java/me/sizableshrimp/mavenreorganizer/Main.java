package me.sizableshrimp.mavenreorganizer;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.File;
import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        OptionSpec<File> releasesO = parser.accepts("releases", "Base \"releases\" directory to separate out").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> proxyO = parser.accepts("proxy", "\"proxy\" directory that proxies other mavens").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> outputO = parser.accepts("output", "Output directory to place reorganized files in").withRequiredArg().ofType(File.class).required();
        OptionSpec<Void> simulateO = parser.accepts("simulate", "When this flag is present, the program will parse all the data but not actually copy/add any files");

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

        File releases = options.valueOf(releasesO);
        File proxy = options.valueOf(proxyO);
        File output = options.valueOf(outputO);
        boolean simulate = options.has(simulateO);

        if (!releases.isDirectory())
            throw new IllegalArgumentException("Releases must be an existing directory");

        if (!proxy.isDirectory())
            throw new IllegalArgumentException("Proxy must be an existing directory");

        if (!simulate && !output.isDirectory() && !output.mkdirs())
            throw new IllegalArgumentException("Could not make output directory with path " + output.getAbsolutePath());

        new MavenReorganizer(releases.toPath(), proxy.toPath(), output.toPath(), simulate).run();
    }
}
