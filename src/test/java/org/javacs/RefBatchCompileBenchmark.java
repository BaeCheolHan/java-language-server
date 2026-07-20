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

        // 콜드 compile + 진단 패스. 이후 index() 내부 compile은 캐시 히트이므로 IndexerLog.compileMs는 무시한다.
        int errors = 0, warnings = 0, unresolved = 0, unresolvedQ = 0;
        long t0 = System.nanoTime();
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
        long compileMs = (System.nanoTime() - t0) / 1_000_000;

        // pass1/pass2·엣지 패스(index — compile은 캐시 히트라 로그의 compileMs는 버린다)
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
            compileMs, timing.pass1Ms(), timing.pass2Ms(), counts,
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
