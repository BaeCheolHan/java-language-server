package org.javacs.bench;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** 벤치용 스테이징 트리: src를 dest에 하드링크 미러(폴백 복사). excludeDirNames 세그먼트를 가진 디렉토리는 통째 스킵. */
public final class StagingTree {
    private StagingTree() {}

    public static int mirror(Path src, Path dest, Set<String> excludeDirNames) throws IOException {
        int[] javaCount = {0};
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(src) && (name.equals(".git") || excludeDirNames.contains(name))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(dest.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                if (a.isSymbolicLink()) return FileVisitResult.CONTINUE;   // FileStore가 심볼릭링크 스킵 — 미러 안 함
                Path target = dest.resolve(src.relativize(file));
                try { Files.createLink(target, file); }
                catch (IOException | UnsupportedOperationException e) {
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
                if (file.getFileName().toString().endsWith(".java")) javaCount[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return javaCount[0];
    }
}
