# 참조배치 컴파일 비용 측정 하네스 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** javacs 포크에 참조배치 컴파일 비용 측정 하네스를 붙여, vone-api에서 C0(전체)/C1a(−bin)/C1b(−build)/C2(−둘다) 구성별 컴파일 시간 분해·main-타깃 엣지 손실·진단을 재고, 스테이징 왜곡을 G≡C0 게이트로 차단한다. 스펙: `docs/superpowers/specs/2026-07-20-refbatch-compile-measurement-design.md`.

**Architecture:** 순수 헬퍼(스테이징 트리 빌더·인덱서 로그 파서·엣지 타깃 분류·동등성 게이트·리포트 렌더러)를 각각 TDD로 만들고, Assume-가드된 `@Test` 오케스트레이션이 이들을 엮어 실제 repo에 대해 구성별 프레시 측정을 돈다. 프로덕션 포크 코드 무변경 — 전부 `src/test` 하위 신규 코드.

**Tech Stack:** Java(포크 빌드 JDK), JUnit4(`org.junit.Test`, 포크 관례), java.nio 하드링크, java.util.logging Handler(로그 캡처). 기존 API: `LanguageServerFixture.getJavaLanguageServer(Path,Consumer<Diagnostic>)`, `JavaCompilerService.compile(Path...)→CompileTask.diagnostics`, `ReferenceIndexer.index(Collection<Path>,Set<Path>)→List<Edge>`.

## Global Constraints
- **프로덕션 포크 코드 무변경.** 측정은 `src/test/java/org/javacs/bench/` 신규 코드 + 스테이징만. `src/main` 수정 금지.
- **실제 제외는 FileStore 가시성으로** — roots 제외는 SourceFileManager가 무효화하므로, 구성마다 스테이징 트리(전체 하드링크 미러 후 `build/`·`bin/`만 삭제)를 워크스페이스 루트로 준다.
- **classpath 원본 1회 추론·전 구성 동일 주입**(settings `classPath`, `server.compiler()` 최초 호출 전). classpath ms는 판정 제외, jar셋 동일성만 검증.
- **진단은 별도 `compiler.compile(files)` 패스의 `CompileTask.diagnostics`에서** 수집(onError 아님). 이 패스는 시간 판정 제외.
- **구성당 프레시 JVM = 1 구성**(`-Dsari.refbench.config=`), 각 K회(기본 3) median. 대상 repo는 `-Dsari.refbench.repo=`(기본 vone-api), 부재 시 `Assume` 스킵.
- **G≡C0 동등성 게이트 통과가 이후 판정의 전제**(파일수·jar셋·총진단·main-엣지·엣지셋 해시).
- **커밋 트레일러:** `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- **JDK:** 포크 기존 빌드 JDK. 테스트 실행 = `./mvnw -o test -Dtest=<Class>`(포크 빌드 도구 관례 따름).

## File Structure
- 신규 `src/test/java/org/javacs/bench/StagingTree.java` — 스테이징 트리 빌더(순수 IO 헬퍼).
- 신규 `src/test/java/org/javacs/bench/IndexerLog.java` — ReferenceIndexer 로그 1줄 파서 + 캡처 Handler.
- 신규 `src/test/java/org/javacs/bench/TargetStats.java` — 엣지 targetRelativePath 범주 분류(순수 문자열).
- 신규 `src/test/java/org/javacs/bench/RunResult.java` — 구성 1회 측정 결과 레코드 + 동등성 비교.
- 신규 `src/test/java/org/javacs/bench/RefBenchReport.java` — RunResult → markdown.
- 신규 `src/test/java/org/javacs/bench/RefBatchCompileBenchmark.java` — @Test 오케스트레이션(Assume 가드).
- 신규 테스트: `StagingTreeTest`·`IndexerLogTest`·`TargetStatsTest`·`RunResultTest`·`RefBenchReportTest`(각 순수 헬퍼 단위검증).

---

## Task 1: 스테이징 트리 빌더 (StagingTree)

**Files:**
- Create: `src/test/java/org/javacs/bench/StagingTree.java`
- Test: `src/test/java/org/javacs/bench/StagingTreeTest.java`

**Interfaces:**
- Produces: `StagingTree.mirror(Path src, Path dest, Set<String> excludeDirNames): int` — src 트리를 dest에 하드링크 미러(실패 시 복사 폴백, attributes 보존), 경로 세그먼트가 excludeDirNames에 속한 디렉토리는 통째 스킵, 미러한 .java 파일 수 반환. `.git`은 항상 스킵.

- [ ] **Step 1: 실패 테스트 작성**
```java
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
```
- [ ] **Step 2: 실패 확인** — Run: `./mvnw -o test -Dtest=StagingTreeTest` / Expected: 컴파일 실패(StagingTree 없음).
- [ ] **Step 3: 구현**
```java
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
```
- [ ] **Step 4: 통과 확인** — Run: `./mvnw -o test -Dtest=StagingTreeTest` / Expected: PASS.
- [ ] **Step 5: 커밋** — `git add -A && git commit -m "test(bench): 스테이징 트리 빌더(하드링크 미러+디렉토리 제외)"`

---

## Task 2: 인덱서 로그 파서 + 캡처 Handler (IndexerLog)

**Files:**
- Create: `src/test/java/org/javacs/bench/IndexerLog.java`
- Test: `src/test/java/org/javacs/bench/IndexerLogTest.java`

**의미론 소스:** `ReferenceIndexer`가 방출하는 라인 형식 — `ReferenceIndexer: compiled=%d declSymbols=%d edges=%d | compileMs=%d pass1Ms=%d pass2Ms=%d`.

**Interfaces:**
- Produces: `record Timing(int compiled,int declSymbols,int edges,long compileMs,long pass1Ms,long pass2Ms)`.
- Produces: `IndexerLog.parse(String line): Optional<Timing>` — 위 패턴 정규식 매칭.
- Produces: `IndexerLog.Capture` (AutoCloseable) — root logger에 Handler 부착, 매칭 라인만 수집. `timings(): List<Timing>`. 오케스트레이션이 `index()` 호출을 이 try-with-resources로 감싸 정확히 1개 캡처를 강제한다.

- [ ] **Step 1: 실패 테스트 작성**
```java
package org.javacs.bench;
import org.junit.Test;
import static org.junit.Assert.*;

public class IndexerLogTest {
    @Test public void parsesTimingLine() {
        var t = IndexerLog.parse(
            "ReferenceIndexer: compiled=3120 declSymbols=48000 edges=91000 | compileMs=240000 pass1Ms=8000 pass2Ms=3000").orElseThrow();
        assertEquals(3120, t.compiled());
        assertEquals(91000, t.edges());
        assertEquals(240000L, t.compileMs());
        assertEquals(8000L, t.pass1Ms());
        assertEquals(3000L, t.pass2Ms());
    }
    @Test public void ignoresNonMatchingLine() {
        assertTrue(IndexerLog.parse("something else").isEmpty());
    }
}
```
- [ ] **Step 2: 실패 확인** — Run: `./mvnw -o test -Dtest=IndexerLogTest` / Expected: 컴파일 실패.
- [ ] **Step 3: 구현**
```java
package org.javacs.bench;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;

public final class IndexerLog {
    private IndexerLog() {}

    public record Timing(int compiled, int declSymbols, int edges, long compileMs, long pass1Ms, long pass2Ms) {}

    private static final Pattern P = Pattern.compile(
        "ReferenceIndexer: compiled=(\\d+) declSymbols=(\\d+) edges=(\\d+) \\| compileMs=(\\d+) pass1Ms=(\\d+) pass2Ms=(\\d+)");

    public static Optional<Timing> parse(String line) {
        if (line == null) return Optional.empty();
        Matcher m = P.matcher(line);
        if (!m.find()) return Optional.empty();
        return Optional.of(new Timing(
            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
            Long.parseLong(m.group(4)), Long.parseLong(m.group(5)), Long.parseLong(m.group(6))));
    }

    /** root logger에 부착해 매칭 라인만 수집. 오케스트레이션이 index() 호출을 감싼다. */
    public static final class Capture implements AutoCloseable {
        private final Logger root = Logger.getLogger("");
        private final List<Timing> timings = new ArrayList<>();
        private final Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { if (r != null) parse(r.getMessage()).ifPresent(timings::add); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        public Capture() { handler.setLevel(Level.ALL); root.addHandler(handler); }
        public List<Timing> timings() { return timings; }
        @Override public void close() { root.removeHandler(handler); }
    }
}
```
- [ ] **Step 4: 통과 확인** — Run: `./mvnw -o test -Dtest=IndexerLogTest` / Expected: PASS.
- [ ] **Step 5: 커밋** — `git add -A && git commit -m "test(bench): ReferenceIndexer 타이밍 로그 파서 + 캡처 핸들러"`

---

## Task 3: 엣지 타깃 범주 분류 (TargetStats)

**Files:**
- Create: `src/test/java/org/javacs/bench/TargetStats.java`
- Test: `src/test/java/org/javacs/bench/TargetStatsTest.java`

**Interfaces:**
- Produces: `record Counts(int total,int mainTarget,int generatedTarget,int otherTarget)`.
- Produces: `TargetStats.categorize(List<String> targetRelPaths): Counts` — 상대경로 세그먼트로 분류: `/build/`·`/bin/` 포함=generated, `/src/main/` 포함=mainTarget, 그 외=other. (Edge 대신 targetRelativePath 문자열 리스트를 받아 순수 검증 가능 — Edge 생성자가 package-private이므로.)

- [ ] **Step 1: 실패 테스트 작성**
```java
package org.javacs.bench;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class TargetStatsTest {
    @Test public void categorizesByPath() {
        var c = TargetStats.categorize(List.of(
            "core/x/src/main/java/A.java",
            "core/x/build/generated/QA.java",
            "core/x/bin/generated-sources/QB.java",
            "libs/ext/Other.java"));
        assertEquals(4, c.total());
        assertEquals(1, c.mainTarget());
        assertEquals(2, c.generatedTarget());
        assertEquals(1, c.otherTarget());
    }
}
```
- [ ] **Step 2: 실패 확인** — Run: `./mvnw -o test -Dtest=TargetStatsTest` / Expected: 컴파일 실패.
- [ ] **Step 3: 구현**
```java
package org.javacs.bench;
import java.util.List;

public final class TargetStats {
    private TargetStats() {}
    public record Counts(int total, int mainTarget, int generatedTarget, int otherTarget) {}

    public static Counts categorize(List<String> targetRelPaths) {
        int total = 0, main = 0, gen = 0, other = 0;
        for (String p : targetRelPaths) {
            if (p == null) continue;
            String s = "/" + p.replace('\\', '/');
            total++;
            if (s.contains("/build/") || s.contains("/bin/")) gen++;
            else if (s.contains("/src/main/")) main++;
            else other++;
        }
        return new Counts(total, main, gen, other);
    }
}
```
- [ ] **Step 4: 통과 확인** — Run: `./mvnw -o test -Dtest=TargetStatsTest` / Expected: PASS.
- [ ] **Step 5: 커밋** — `git add -A && git commit -m "test(bench): 엣지 타깃 범주 분류(main/generated/other)"`

---

## Task 4: 측정 결과 레코드 + 동등성 게이트 (RunResult)

**Files:**
- Create: `src/test/java/org/javacs/bench/RunResult.java`
- Test: `src/test/java/org/javacs/bench/RunResultTest.java`

**Interfaces:**
- Consumes: Task 2 `IndexerLog.Timing`, Task 3 `TargetStats.Counts`.
- Produces: `record RunResult(String config,int javaFileCount,int classpathJarCount,long compileMs,long pass1Ms,long pass2Ms,TargetStats.Counts edges,int diagErrors,int diagWarnings,int unresolvedTotal,int unresolvedQ,long edgeSetHash)`.
- Produces: `RunResult.equivalent(RunResult original, RunResult staged): List<String>` — G≡C0 게이트. 파일수·classpathJarCount·diagErrors·edges.mainTarget()·edgeSetHash 불일치 항목을 문자열로 반환(빈 리스트=통과).

- [ ] **Step 1: 실패 테스트 작성**
```java
package org.javacs.bench;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RunResultTest {
    private RunResult r(String cfg, int files, int jars, int mainEdges, long hash) {
        return new RunResult(cfg, files, jars, 1,1,1,
            new TargetStats.Counts(mainEdges,mainEdges,0,0), 0,0,0,0, hash);
    }
    @Test public void equivalentWhenKeyMetricsMatch() {
        assertTrue(RunResult.equivalent(r("G",100,5,90,42L), r("C0",100,5,90,42L)).isEmpty());
    }
    @Test public void reportsEachMismatch() {
        var diffs = RunResult.equivalent(r("G",100,5,90,42L), r("C0",101,5,88,7L));
        assertEquals(3, diffs.size());   // fileCount, mainEdge, edgeSetHash
    }
}
```
- [ ] **Step 2: 실패 확인** — Run: `./mvnw -o test -Dtest=RunResultTest` / Expected: 컴파일 실패.
- [ ] **Step 3: 구현**
```java
package org.javacs.bench;
import java.util.*;

public record RunResult(
        String config, int javaFileCount, int classpathJarCount,
        long compileMs, long pass1Ms, long pass2Ms,
        TargetStats.Counts edges, int diagErrors, int diagWarnings,
        int unresolvedTotal, int unresolvedQ, long edgeSetHash) {

    /** G(원본) vs C0(스테이징) 동등성 게이트 — 불일치 항목 리스트(빈 리스트=통과). */
    public static List<String> equivalent(RunResult original, RunResult staged) {
        List<String> diffs = new ArrayList<>();
        if (original.javaFileCount != staged.javaFileCount)
            diffs.add("fileCount " + original.javaFileCount + " != " + staged.javaFileCount);
        if (original.classpathJarCount != staged.classpathJarCount)
            diffs.add("classpathJars " + original.classpathJarCount + " != " + staged.classpathJarCount);
        if (original.diagErrors != staged.diagErrors)
            diffs.add("diagErrors " + original.diagErrors + " != " + staged.diagErrors);
        if (original.edges.mainTarget() != staged.edges.mainTarget())
            diffs.add("mainEdges " + original.edges.mainTarget() + " != " + staged.edges.mainTarget());
        if (original.edgeSetHash != staged.edgeSetHash)
            diffs.add("edgeSetHash " + original.edgeSetHash + " != " + staged.edgeSetHash);
        return diffs;
    }
}
```
- [ ] **Step 4: 통과 확인** — Run: `./mvnw -o test -Dtest=RunResultTest` / Expected: PASS.
- [ ] **Step 5: 커밋** — `git add -A && git commit -m "test(bench): RunResult 레코드 + G≡C0 동등성 게이트"`

---

## Task 5: 리포트 렌더러 (RefBenchReport)

**Files:**
- Create: `src/test/java/org/javacs/bench/RefBenchReport.java`
- Test: `src/test/java/org/javacs/bench/RefBenchReportTest.java`

**Interfaces:**
- Consumes: Task 4 `RunResult`.
- Produces: `RefBenchReport.render(String repo, List<RunResult> runs, List<String> gateDiffs): String` — markdown 표(구성별 시간분해·엣지범주·진단·파일수·jar수) + 게이트 결과 섹션.

- [ ] **Step 1: 실패 테스트 작성**
```java
package org.javacs.bench;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RefBenchReportTest {
    @Test public void rendersTableAndGate() {
        var run = new RunResult("C0", 4307, 120, 240000,8000,3000,
            new TargetStats.Counts(91000,85000,6000,0), 12,300, 40,10, 99L);
        String md = RefBenchReport.render("vone-api", List.of(run), List.of());
        assertTrue(md.contains("vone-api"));
        assertTrue(md.contains("C0"));
        assertTrue(md.contains("240000"));      // compileMs
        assertTrue(md.contains("85000"));       // main-타깃 엣지
        assertTrue(md.contains("게이트 통과") || md.contains("PASS"));
    }
    @Test public void showsGateFailure() {
        var md = RefBenchReport.render("r", List.of(), List.of("fileCount 100 != 101"));
        assertTrue(md.contains("fileCount 100 != 101"));
    }
}
```
- [ ] **Step 2: 실패 확인** — Run: `./mvnw -o test -Dtest=RefBenchReportTest` / Expected: 컴파일 실패.
- [ ] **Step 3: 구현**
```java
package org.javacs.bench;
import java.util.List;

public final class RefBenchReport {
    private RefBenchReport() {}
    public static String render(String repo, List<RunResult> runs, List<String> gateDiffs) {
        StringBuilder b = new StringBuilder();
        b.append("# refbatch 측정 — ").append(repo).append("\n\n");
        b.append("## 동등성 게이트 (G≡C0)\n");
        b.append(gateDiffs.isEmpty() ? "게이트 통과 (PASS)\n\n"
                : "게이트 실패 — 이후 판정 무효:\n- " + String.join("\n- ", gateDiffs) + "\n\n");
        b.append("## 구성별 결과\n\n");
        b.append("| config | javaFiles | jars | compileMs | pass1Ms | pass2Ms | edges(total/main/gen) | errors | unresolved(tot/Q) |\n");
        b.append("|---|---|---|---|---|---|---|---|---|\n");
        for (RunResult r : runs) {
            b.append(String.format("| %s | %d | %d | %d | %d | %d | %d/%d/%d | %d | %d/%d |%n",
                r.config(), r.javaFileCount(), r.classpathJarCount(),
                r.compileMs(), r.pass1Ms(), r.pass2Ms(),
                r.edges().total(), r.edges().mainTarget(), r.edges().generatedTarget(),
                r.diagErrors(), r.unresolvedTotal(), r.unresolvedQ()));
        }
        return b.toString();
    }
}
```
- [ ] **Step 4: 통과 확인** — Run: `./mvnw -o test -Dtest=RefBenchReportTest` / Expected: PASS.
- [ ] **Step 5: 커밋** — `git add -A && git commit -m "test(bench): 측정 리포트 markdown 렌더러"`

---

## Task 6: 벤치 오케스트레이션 (RefBatchCompileBenchmark)

**Files:**
- Create: `src/test/java/org/javacs/RefBatchCompileBenchmark.java` — **package `org.javacs`**(package-private `JavaCompilerService.classPath` 필드 읽기 위해. `org.javacs.bench` 아님). 순수 헬퍼(T1~5)는 `org.javacs.bench`에 그대로 두고 import.
- 참조: `LanguageServerFixture`, `org.javacs.index.ReferenceIndexer`, `org.javacs.JavaCompilerService`(classPath 필드 package-private), `org.javacs.CompileTask`, `org.javacs.FileStore`, `org.javacs.lsp.DidChangeConfigurationParams`, `org.javacs.bench.*`.

**classpath 처리(확인된 실 API):** `InferConfig`·`JavaCompilerService.classPath`는 package-private. → 오케스트레이션을 `package org.javacs`에 두고, 원본에서 프로브 서버 1회 띄워 `probe.compiler().classPath`(Set<Path>)를 캡처. 각 구성 서버엔 `didChangeConfiguration`으로 `{java:{classPath:[...]}}` 주입 → `createCompiler`가 `classPath()` 비지 않음을 보고 InferConfig를 **건너뛰고** 동일 CP 사용(JavaLanguageServer.java:99-101 확인). jar 수 = `cp.size()`.

**Interfaces:**
- Consumes: Task 1~5 전부.
- 단위테스트 없음(통합·Assume 가드). 구조적 스모크만.

- [ ] **Step 1: 구현** — 아래 전체를 작성. 한 실행 = `-Dsari.refbench.config`가 지정한 1개 구성(K회 median). classpath는 원본에서 1회 추론해 settings로 주입(compiler 최초 호출 전). 진단은 별도 compile 패스, 시간·엣지는 index()에서 Capture로 수집.
```java
package org.javacs;   // package-private JavaCompilerService.classPath 접근 위해 org.javacs

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.javacs.bench.IndexerLog;
import org.javacs.bench.RefBenchReport;
import org.javacs.bench.RunResult;
import org.javacs.bench.StagingTree;
import org.javacs.bench.TargetStats;
import org.javacs.index.ReferenceIndexer;
import org.javacs.lsp.DidChangeConfigurationParams;
import org.junit.Assume;
import org.junit.Test;

import javax.tools.Diagnostic;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 참조배치 컴파일 비용 측정. 실행: 구성당 프레시 JVM 1개.
 *   ./mvnw -o test -Dtest=RefBatchCompileBenchmark \
 *     -Dsari.refbench.repo=/Users/vendys-chulhan/Documents/repositories/vone-api \
 *     -Dsari.refbench.config=C0   # G|C0|C1a|C1b|C2
 * repo 미지정/부재 시 Assume 스킵. G·C0 결과를 각각 실행한 뒤 RunResult.equivalent로 게이트 판정(리포트 참조).
 */
public class RefBatchCompileBenchmark {
    private static final int K = Integer.getInteger("sari.refbench.k", 3);

    @Test public void measure() throws Exception {
        String repoProp = System.getProperty("sari.refbench.repo",
            "/Users/vendys-chulhan/Documents/repositories/vone-api");
        String config = System.getProperty("sari.refbench.config", "C0");
        Path repo = Paths.get(repoProp);
        Assume.assumeTrue("대상 repo 없음: " + repo, Files.isDirectory(repo));

        // 제외 디렉토리 매핑
        Set<String> exclude = switch (config) {
            case "G", "C0" -> Set.of();
            case "C1a" -> Set.of("bin");
            case "C1b" -> Set.of("build");
            case "C2" -> Set.of("bin", "build");
            default -> throw new IllegalArgumentException("unknown config " + config);
        };

        // classpath 원본 1회 캡처(전 구성 동일 주입) — 프로브 서버에서 InferConfig 결과를 읽는다.
        Set<Path> cp;
        {
            var probe = LanguageServerFixture.getJavaLanguageServer(repo, d -> {});
            cp = new LinkedHashSet<>(probe.compiler().classPath);   // package-private 필드, org.javacs 접근
            FileStore.reset();
        }

        Path root = config.equals("G") ? repo : stage(repo, exclude);

        long[] compileMs = new long[K], pass1 = new long[K], pass2 = new long[K];
        RunResult last = null;
        for (int i = 0; i < K; i++) {
            last = runOnce(config, root, cp);
            compileMs[i] = last.compileMs(); pass1[i] = last.pass1Ms(); pass2[i] = last.pass2Ms();
            FileStore.reset();
        }
        RunResult median = new RunResult(config, last.javaFileCount(), last.classpathJarCount(),
            med(compileMs), med(pass1), med(pass2), last.edges(),
            last.diagErrors(), last.diagWarnings(), last.unresolvedTotal(), last.unresolvedQ(), last.edgeSetHash());

        Path out = repo.resolve("build/reports/refbench");
        Files.createDirectories(out);
        Files.writeString(out.resolve(config + ".md"),
            RefBenchReport.render(repo.getFileName().toString(), List.of(median), List.of()));
        System.out.println(RefBenchReport.render(repo.getFileName().toString(), List.of(median), List.of()));
    }

    private RunResult runOnce(String config, Path root, Set<Path> cp) throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer(root, d -> {});
        injectClasspath(server, cp);                 // compiler() 최초 호출 전 — InferConfig 스킵, 동일 CP 강제
        JavaCompilerService compiler = server.compiler();

        List<Path> compileFiles;
        try (var s = Files.walk(root)) {
            compileFiles = s.filter(p -> p.toString().endsWith(".java")).filter(Files::isRegularFile)
                .collect(Collectors.toList());
        }
        Set<Path> walkFiles = compileFiles.stream()
            .filter(p -> root.relativize(p).toString().replace('\\','/').contains("src/main/"))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        // 진단 패스(별도 compile — 시간 판정 제외)
        int errors = 0, warnings = 0, unresolved = 0, unresolvedQ = 0;
        try (CompileTask t = compiler.compile(compileFiles.toArray(Path[]::new))) {
            for (Diagnostic<?> d : t.diagnostics) {
                if (d.getKind() == Diagnostic.Kind.ERROR) errors++;
                else warnings++;
                String code = d.getCode() == null ? "" : d.getCode();
                if (code.contains("cant.resolve") || code.contains("doesnt.exist") || code.contains("cant.find.symbol")) {
                    unresolved++;
                    String msg = String.valueOf(d.getMessage(null));
                    if (msg.contains("Q") /* QueryDSL Q클래스 근사 — 리포트 후 정밀화 */) unresolvedQ++;
                }
            }
        }

        // 시간·엣지 패스(index — Capture로 로그 타이밍 수집)
        List<ReferenceIndexer.Edge> edges;
        IndexerLog.Timing timing;
        try (var cap = new IndexerLog.Capture()) {
            edges = new ReferenceIndexer(compiler, root).index(compileFiles, walkFiles);
            List<IndexerLog.Timing> ts = cap.timings();
            if (ts.size() != 1) throw new IllegalStateException("ReferenceIndexer 타이밍 로그 " + ts.size() + "개(1개 기대)");
            timing = ts.get(0);
        }

        List<String> targetPaths = edges.stream().map(e -> e.targetRelativePath).collect(Collectors.toList());
        TargetStats.Counts counts = TargetStats.categorize(targetPaths);
        long hash = edges.stream()
            .map(e -> e.targetRelativePath+"|"+e.targetName+"|"+e.targetKind+"|"+e.targetLine+":"+e.targetColumn
                     +"->"+e.referenceRelativePath+":"+e.startLine+":"+e.startColumn)
            .sorted().reduce(0L, (h, s) -> h*31 + s.hashCode(), (a,b)->a+b);

        return new RunResult(config, compileFiles.size(), cp.size(),
            timing.compileMs(), timing.pass1Ms(), timing.pass2Ms(), counts,
            errors, warnings, unresolved, unresolvedQ, hash);
    }

    /** {java:{classPath:[abs...]}}를 didChangeConfiguration으로 주입 — createCompiler가 InferConfig 스킵. */
    private static void injectClasspath(JavaLanguageServer server, Set<Path> cp) {
        JsonArray arr = new JsonArray();
        for (Path p : cp) arr.add(p.toAbsolutePath().toString());
        JsonObject java = new JsonObject();
        java.add("classPath", arr);
        JsonObject settings = new JsonObject();
        settings.add("java", java);
        DidChangeConfigurationParams params = new DidChangeConfigurationParams();
        params.settings = settings;
        server.didChangeConfiguration(params);
    }

    private Path stage(Path repo, Set<String> exclude) throws Exception {
        Path dest = Files.createTempDirectory("refbench-" + repo.getFileName());
        StagingTree.mirror(repo, dest, exclude);
        return dest;
    }

    private static long med(long[] a) { long[] c = a.clone(); Arrays.sort(c); return c[c.length/2]; }
}
```
- [ ] **Step 2: 컴파일 확인** — Run: `./mvnw -o test -Dtest=RefBatchCompileBenchmark` (repo 미지정이면 Assume 스킵) / Expected: BUILD SUCCESS(스킵 또는 통과), 컴파일 에러 0. classpath는 `probe.compiler().classPath`(package-private, org.javacs 접근)로 캡처해 didChangeConfiguration 주입 — 스텁 없음. 만약 `getJavaLanguageServer`가 첫 줄에서 `FileStore.reset()`+`initialize()`로 이미 compiler를 만들어 주입이 늦는다면(확인 필요), 주입을 initialize 전에 하거나 프로브 CP를 `LanguageServerFixture` 오버로드로 전달. 막히면 DONE_WITH_CONCERNS로 보고.
- [ ] **Step 3: 실측 1회(대표 repo)** — Run: `-Dsari.refbench.repo=<vone-api> -Dsari.refbench.config=C0` 및 `=G`. / Expected: `build/reports/refbench/{C0,G}.md` 생성. G·C0로 `RunResult.equivalent` 게이트 수동 대조(불일치면 스테이징 방법 재검토 — 판정 무효).
- [ ] **Step 4: 커밋** — `git add -A && git commit -m "test(bench): 참조배치 측정 오케스트레이션(구성별 프레시 측정+게이트)"`

---

## 범위 밖 / 후속
실제 수정(bin/build 제외 필터·QueryDSL APT 배선·모듈 샤딩)은 측정 결과 판정 후 별도 스펙. classpath 주입/노출 API·unresolvedQ 정밀 분류는 Task 6 실측에서 드러나는 대로 보정.

## Self-Review 노트
- **스펙 커버리지:** 스테이징 실제제외(T1)·시간3분해 로그파싱(T2)·타깃범주 엣지(T3)·동등성 게이트+진단 레코드(T4)·리포트(T5)·오케스트레이션+classpath1회+진단별도패스+K median+프레시JVM(T6). 스펙 매트릭스 G/C0/C1a/C1b/C2 전부 config로 매핑.
- **classpath 확정:** `probe.compiler().classPath`(package-private, org.javacs 접근) 캡처 + `didChangeConfiguration({java:{classPath:[...]}})` 주입으로 실코드화(InferConfig·JavaCompilerService.classPath가 package-private임을 확인해 오케스트레이션을 `org.javacs` 패키지에 배치). 유일 잔여 미확정 = `getJavaLanguageServer`가 반환 전에 compiler를 만들어 주입이 늦는지(T6 Step2에서 확인, 늦으면 initialize 전 주입/픽스처 오버로드). unresolvedQ의 "Q" 근사는 실측 후 정밀화.
- **타입 일관성:** `IndexerLog.Timing`(T2)→`RunResult`(T4)→`RefBenchReport`(T5) 필드명 일치. `TargetStats.Counts`(T3) 소비 동일. `Edge` public 필드(targetRelativePath 등) 실제 확인됨.
