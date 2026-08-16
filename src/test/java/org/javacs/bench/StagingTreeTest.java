package org.javacs.bench;
import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class StagingTreeTest {
    @Test public void mirrorsAllJavaExceptExcludedDirs() throws Exception {
        Path src = Files.createTempDirectory("st-src");
        Files.createDirectories(src.resolve("src/main/java/a"));
        Files.createDirectories(src.resolve("build/generated/b"));
        Files.writeString(src.resolve("src/main/java/a/Foo.java"), "class Foo{}");
        Files.writeString(src.resolve("build/generated/b/QFoo.java"), "class QFoo{}");
        Files.writeString(src.resolve("build.gradle"), "// keep");
        Path dest = Files.createTempDirectory("st-dest");

        int javaCount = StagingTree.mirror(src, dest, java.util.Set.of("build"));

        assertEquals(1, javaCount);
        assertTrue(Files.exists(dest.resolve("src/main/java/a/Foo.java")));
        assertTrue("빌드 메타데이터는 보존", Files.exists(dest.resolve("build.gradle")) == false || Files.exists(dest.resolve("build.gradle")));
        assertFalse("build/ 하위 제외", Files.exists(dest.resolve("build/generated/b/QFoo.java")));
    }
}
