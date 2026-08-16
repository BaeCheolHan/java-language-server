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
