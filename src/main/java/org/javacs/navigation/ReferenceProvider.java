package org.javacs.navigation;

import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.TypeElement;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.Location;
import java.util.logging.Logger;

public class ReferenceProvider {
    private static final Logger LOG = Logger.getLogger("main");
    private final CompilerProvider compiler;
    private final Path file;
    private final int line, column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public ReferenceProvider(CompilerProvider compiler, Path file, int line, int column) {
        this.compiler = compiler;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        // 메서드/생성자 호출 위치는 narrow word == 커서 토큰이 보장된다(오늘도 findMemberReferences가
        // memberName=토큰으로 narrow). 이 경우 커서-해석용 컴파일(cursorCompile)과 후보 컴파일을
        // 하나로 합쳐 (neighborhood ∪ wordIndex(word))를 1회만 컴파일한다.
        var word = invocationWordAtCursor();
        if (word != null) {
            var r = findInvocationReferences(word);
            if (r != null) return r;   // null == lean 해석 실패(동일 패키지 선언 등) → full neighborhood 폴백
        }
        return findFull();
    }

    private List<Location> findFull() {
        // 먼저 lean importRoots(커서+import)로 해석 시도. 해석 실패(null)면 full neighborhood로 폴백.
        var r = resolveAndDispatch(importRoots(file));
        if (r != null) return r;
        r = resolveAndDispatch(neighborhood(file));
        return r != null ? r : NOT_SUPPORTED;
    }

    // roots를 컴파일해 커서 심볼을 해석하고 종류별로 디스패치. 해석 실패면 null(폴백 신호), 해석했으나 미지원 종류면 빈 리스트.
    private List<Location> resolveAndDispatch(Path[] roots) {
        var c0 = System.nanoTime();
        try (var task = compiler.compile(roots)) {
            var c1 = System.nanoTime();
            var element = NavigationHelper.findElement(task, file, line, column);
            LOG.info(String.format("PERF find: cursorCompile=%dms roots=%d element=%s",
                (c1-c0)/1000000, roots.length, element==null?"NULL":element.getKind()));
            if (element == null) return null;   // 폴백 신호
            if (NavigationHelper.isLocal(element)) {
                return findReferences(task);
            }
            if (NavigationHelper.isType(element)) {
                var type = (TypeElement) element;
                var className = type.getQualifiedName().toString();
                task.close();
                return findTypeReferences(className);
            }
            if (NavigationHelper.isMember(element)) {
                var parentClass = (TypeElement) element.getEnclosingElement();
                var className = parentClass.getQualifiedName().toString();
                var memberName = element.getSimpleName().toString();
                if (memberName.equals("<init>")) {
                    memberName = parentClass.getSimpleName().toString();
                }
                task.close();
                return findMemberReferences(className, memberName);
            }
            return NOT_SUPPORTED;
        }
    }

    // 자바 예약어는 메서드/생성자 이름이 될 수 없다 → this()/super()/if() 등을 병합 경로에서 제외.
    private static final java.util.Set<String> RESERVED = java.util.Set.of(
        "abstract","assert","boolean","break","byte","case","catch","char","class","const",
        "continue","default","do","double","else","enum","extends","final","finally","float",
        "for","goto","if","implements","import","instanceof","int","interface","long","native",
        "new","package","private","protected","public","return","short","static","strictfp",
        "super","switch","synchronized","this","throw","throws","transient","try","void",
        "volatile","while","true","false","null","var","yield","record","sealed","permits");

    // 커서가 "이름(" 형태의 호출 위치면 호출 이름을 반환, 아니면 null(기존 경로 사용).
    // parse(구문만, 컴파일·애노테이션 처리 없음)로 findElement와 동일한 LineMap 좌표를 사용.
    private String invocationWordAtCursor() {
        try {
            var parse = compiler.parse(file);
            long pos = parse.root.getLineMap().getPosition(line, column);
            var text = java.nio.file.Files.readString(file);
            int i = (int) pos;
            if (i < 0 || i >= text.length() || !Character.isJavaIdentifierStart(text.charAt(i))) return null;
            int end = i;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
            int j = end;
            while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
            if (j >= text.length() || text.charAt(j) != '(') return null;   // 호출 위치 아님
            var word = text.substring(i, end);
            if (RESERVED.contains(word)) return null;
            return word;
        } catch (Exception e) {
            return null;
        }
    }

    // 병합 경로: neighborhood(해석 + Lombok 선언클래스 애노테이션 처리)와 wordIndex(word)(사용처)를
    // 합쳐 1회 컴파일하고, 그 task에서 커서 심볼 해석과 참조 스캔을 모두 수행한다.
    // 스캔은 element 동등성 기반이라 여분 root는 참조를 추가하지 않는다(정확성 보존).
    private List<Location> findInvocationReferences(String word) {
        var t0 = System.nanoTime();
        var set = new java.util.LinkedHashSet<Path>();
        java.util.Collections.addAll(set, importRoots(file));
        java.util.Collections.addAll(set, compiler.findMemberReferences(word, word)); // className 미사용
        var files = set.toArray(Path[]::new);
        var t1 = System.nanoTime();
        try (var task = compiler.compile(files)) {
            var t2 = System.nanoTime();
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) return null;   // 해석 실패 → 폴백 신호(NOT_SUPPORTED 아님)
            var r = findReferences(task);
            var t3 = System.nanoTime();
            LOG.info(String.format("PERF merged %s: candidates=%d narrow=%dms compile=%dms scan=%dms",
                word, files.length, (t1-t0)/1000000, (t2-t1)/1000000, (t3-t2)/1000000));
            return r;
        }
    }

    private List<Location> findTypeReferences(String className) {
        var t0 = System.nanoTime();
        var files = withDeclaringFile(className, compiler.findTypeReferences(className));
        var t1 = System.nanoTime();
        if (files.length == 0) return List.of();
        try (var task = compiler.compile(files)) {
            var t2 = System.nanoTime();
            var r = findReferences(task);
            var t3 = System.nanoTime();
            LOG.info(String.format("PERF type %s: candidates=%d narrow=%dms compile=%dms scan=%dms",
                className, files.length, (t1-t0)/1000000, (t2-t1)/1000000, (t3-t2)/1000000));
            return r;
        }
    }

    private List<Location> findMemberReferences(String className, String memberName) {
        var t0 = System.nanoTime();
        var files = withDeclaringFile(className, compiler.findMemberReferences(className, memberName));
        var t1 = System.nanoTime();
        if (files.length == 0) return List.of();
        try (var task = compiler.compile(files)) {
            var t2 = System.nanoTime();
            var r = findReferences(task);
            var t3 = System.nanoTime();
            LOG.info(String.format("PERF member %s#%s: candidates=%d narrow=%dms compile=%dms scan=%dms",
                className, memberName, files.length, (t1-t0)/1000000, (t2-t1)/1000000, (t3-t2)/1000000));
            return r;
        }
    }

    // 롬복 등 생성 멤버가 해석되려면 선언 클래스가 "명시 컴파일 단위"여야 한다(sourcepath 로 당겨온 파일엔 애노테이션 처리 안 됨).
    // 커서 파일 + 동일 패키지 + import 대상 소스를 함께 컴파일한다.
    private Path[] neighborhood(Path f) {
        return roots(f, true);
    }

    // 병합(호출) 경로용 경량 루트: 커서 + import만. 동일 패키지 전체 파일(수십 개)을 제외해 매 질의 컴파일 비용을 줄인다.
    // 동일 패키지 사용처는 candidates(wordIndex)로 이미 포함되므로, 여기서 빠지는 건 "import 없는 동일 패키지 선언 클래스"뿐.
    private Path[] importRoots(Path f) {
        return roots(f, false);
    }

    private Path[] roots(Path f, boolean includePackage) {
        var set = new java.util.LinkedHashSet<Path>();
        set.add(f);
        try {
            var text = java.nio.file.Files.readString(f);
            if (includePackage) {
                var pkg = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
                if (pkg.find()) {
                    for (var p : org.javacs.FileStore.list(pkg.group(1))) set.add(p);
                }
            }
            var imp = java.util.regex.Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;").matcher(text);
            while (imp.find()) {
                var fqn = imp.group(1);
                if (fqn.endsWith(".*")) continue;
                var decl = compiler.findTypeDeclaration(fqn);
                if (decl != null && java.nio.file.Files.isRegularFile(decl)) set.add(decl);
            }
        } catch (Exception e) { }
        return set.toArray(Path[]::new);
    }

    private Path[] withDeclaringFile(String className, Path[] files) {
        Path decl;
        try { decl = compiler.findTypeDeclaration(className); } catch (RuntimeException e) { return files; }
        if (decl == null || !java.nio.file.Files.isRegularFile(decl)) return files;
        for (var f : files) if (decl.equals(f)) return files;
        var out = new Path[files.length + 1];
        out[0] = decl;
        System.arraycopy(files, 0, out, 1, files.length);
        return out;
    }

    private List<Location> findReferences(CompileTask task) {
        var element = NavigationHelper.findElement(task, file, line, column);
        var paths = new ArrayList<TreePath>();
        for (var root : task.roots) {
            new FindReferences(task.task, element).scan(root, paths);
        }
        var locations = new ArrayList<Location>();
        for (var p : paths) {
            locations.add(FindHelper.location(task, p));
        }
        return locations;
    }
}
