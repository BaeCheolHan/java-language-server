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
