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
