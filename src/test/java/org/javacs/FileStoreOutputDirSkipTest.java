package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class FileStoreOutputDirSkipTest {

    @Before
    public void resetSourcesBefore() {
        FileStore.reset();
    }

    /** 실제 repo 회귀: FileStore.all()이 모듈루트 build/bin 산출 소스를 안 잡는다(프로덕션 indexReferences 경로가 이걸 씀). */
    @Test
    public void realRepoExcludesBuildAndBinOutputs() {
        Path repo = Paths.get(System.getProperty("sari.refbench.repo",
            "/Users/vendys-chulhan/Documents/repositories/vone-api"));
        Assume.assumeTrue("대상 repo 없음: " + repo, Files.isDirectory(repo));

        FileStore.setWorkspaceRoots(Set.of(repo));
        for (Path p : FileStore.all()) {
            String s = repo.relativize(p).toString().replace('\\', '/');
            boolean underSrc = s.contains("/src/") || s.startsWith("src/");
            assertTrue("모듈루트 build/ 산출 미제외: " + s, underSrc || !s.matches("(.*/)?build/.*"));
            assertTrue("모듈루트 bin/ 산출 미제외: " + s, underSrc || !s.matches("(.*/)?bin/.*"));
        }
    }

    @Test
    public void skipsOutputDirsButKeepsSrcPackageDirsWithSameName() throws Exception {
        Path root = Files.createTempDirectory("filestore-output-dirs");
        Path generated = root.resolve("build/generated/QFoo.java");
        Path bin = root.resolve("bin/X.java");
        Path realPackage = root.resolve("src/main/java/com/foo/build/RealPkg.java");
        Path source = root.resolve("src/main/java/A.java");
        Files.createDirectories(generated.getParent());
        Files.createDirectories(bin.getParent());
        Files.createDirectories(realPackage.getParent());
        Files.createDirectories(source.getParent());
        Files.writeString(generated, "class QFoo {}\n");
        Files.writeString(bin, "class X {}\n");
        Files.writeString(realPackage, "package com.foo.build; class RealPkg {}\n");
        Files.writeString(source, "class A {}\n");

        FileStore.setWorkspaceRoots(Set.of(root));

        assertThat(FileStore.all(), containsInAnyOrder(source, realPackage));
    }
}
