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
