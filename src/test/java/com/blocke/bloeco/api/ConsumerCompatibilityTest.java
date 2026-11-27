package com.blocke.bloeco.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerCompatibilityTest {
    @TempDir Path output;

    @Test
    void developerJarContainsExactlyTheApiClassesProvidedByPaperPlugin() throws Exception {
        try (var plugin = new JarFile(System.getProperty("bloeco.shadow.jar"));
             var api = new JarFile(System.getProperty("bloeco.api.jar"))) {
            var expected = plugin.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(name -> name.startsWith("com/blocke/bloeco/api/") && name.endsWith(".class")).toList();
            assertTrue(expected.contains("com/blocke/bloeco/api/BloecoApi.class"));
            var files = api.stream().filter(entry -> !entry.isDirectory())
                    .map(java.util.zip.ZipEntry::getName).filter(name -> !name.equals("META-INF/MANIFEST.MF")).toList();
            assertEquals(java.util.Set.copyOf(expected), java.util.Set.copyOf(files));
            for (String name : expected) {
                try (var packaged = plugin.getInputStream(plugin.getEntry(name));
                     var exported = api.getInputStream(api.getEntry(name))) {
                    org.junit.jupiter.api.Assertions.assertArrayEquals(packaged.readAllBytes(), exported.readAllBytes(), name);
                }
            }
        }
    }

    @Test
    void exampleCompilesAgainstShadedPluginAndApiShipsInOneJar() throws Exception {
        Path jar=Path.of(System.getProperty("bloeco.shadow.jar"));
        assertTrue(Files.isRegularFile(jar));
        try(JarFile archive=new JarFile(jar.toFile())){
            assertNotNull(archive.getEntry("com/blocke/bloeco/api/BloecoApi.class"));
            assertNotNull(archive.getEntry("plugin.yml"));
        }
        Path source=Path.of("examples/bloeco-api-consumer/src/main/java/com/blocke/example/ExampleEconomyHook.java");
        Path plugin=Path.of("examples/bloeco-api-consumer/src/main/java/com/blocke/example/ExamplePlugin.java");
        String classpath=jar+java.io.File.pathSeparator+System.getProperty("java.class.path");
        int result=ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-classpath",classpath,
                "-d",output.toString(),source.toString(),plugin.toString());
        assertEquals(0,result);
        assertTrue(Files.isRegularFile(output.resolve("com/blocke/example/ExampleEconomyHook.class")));
        assertTrue(Files.notExists(output.resolve("com/blocke/bloeco/api/BloecoApi.class")));
    }
}
