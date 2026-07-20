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
