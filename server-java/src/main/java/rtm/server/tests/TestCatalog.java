package rtm.server.tests;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public final class TestCatalog {

    private TestCatalog() {
    }

    public static List<RemoteTest> loadFromPlugins() {
        List<Path> directories = pluginDirectories();
        Map<String, RemoteTest> tests = new LinkedHashMap<>();

        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path jar : stream) {
                    loadJar(jar, tests);
                }
            } catch (IOException e) {
                System.err.println("Failed to scan plugin directory '" + directory + "': " + e.getMessage());
            }
        }

        return new ArrayList<>(tests.values());
    }

    private static List<Path> pluginDirectories() {
        List<Path> directories = new ArrayList<>();
        String configured = System.getProperty("rtm.plugins.dir");
        if (configured != null && !configured.isEmpty()) {
            directories.add(Path.of(configured));
        }

        directories.add(Path.of("plugins"));
        directories.add(Path.of("target", "plugins"));
        return directories;
    }

    private static void loadJar(Path jar, Map<String, RemoteTest> tests) {
        try {
            URL[] urls = {jar.toUri().toURL()};
            URLClassLoader loader = new URLClassLoader(urls, RemoteTest.class.getClassLoader());
            ServiceLoader<RemoteTest> services = ServiceLoader.load(RemoteTest.class, loader);
            for (RemoteTest test : services) {
                tests.putIfAbsent(test.id(), test);
            }
        } catch (Exception e) {
            System.err.println("Failed to load test plugin '" + jar + "': " + e.getMessage());
        }
    }
}
