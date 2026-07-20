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
