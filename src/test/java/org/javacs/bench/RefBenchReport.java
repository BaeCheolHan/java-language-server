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
