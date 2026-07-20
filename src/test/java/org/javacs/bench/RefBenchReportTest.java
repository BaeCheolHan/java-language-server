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
