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
