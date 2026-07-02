package org.javacs.index;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import org.javacs.CompilerProvider;

/**
 * 인버티드 참조 인덱서: 파일들을 배치 컴파일한 뒤 AST를 순회하며 모든 참조 엣지(타겟 심볼 → 참조 위치)를 추출한다.
 *
 * <p>심볼별로 재컴파일하지 않고 프로젝트를 (거의) 한 번 컴파일하므로 배치 참조 인덱싱이 크게 빨라진다.
 * 타겟 키는 org.javacs documentSymbol(SymbolProvider/FindSymbolsMatching)의 nameLocation과 동일하게 계산되어
 * 클라이언트(sari)가 저장한 심볼 키와 정확히 조인된다.
 *
 * <p>reconciliation 검증됨(payment-service, StoreBlacklistVo 27/27 = FindReferences 의미와 일치).
 */
public class ReferenceIndexer {

    private static final Logger LOG = Logger.getLogger("main");

    /** 하나의 참조 엣지: 타겟 심볼(선언파일 상대경로/이름/종류/선언위치)과 참조 위치. */
    public static final class Edge {
        public final String targetRelativePath;   // 타겟 선언 파일(워크스페이스 루트 기준)
        public final String targetName;
        public final String targetKind;           // class | method | field
        public final int targetLine, targetColumn; // 타겟 선언 이름 위치(1-based)
        public final String referenceRelativePath;
        public final int startLine, startColumn, endLine, endColumn; // 참조 위치(1-based)

        Edge(String targetRelativePath, String targetName, String targetKind, int targetLine, int targetColumn,
             String referenceRelativePath, int startLine, int startColumn, int endLine, int endColumn) {
            this.targetRelativePath = targetRelativePath;
            this.targetName = targetName;
            this.targetKind = targetKind;
            this.targetLine = targetLine;
            this.targetColumn = targetColumn;
            this.referenceRelativePath = referenceRelativePath;
            this.startLine = startLine;
            this.startColumn = startColumn;
            this.endLine = endLine;
            this.endColumn = endColumn;
        }
    }

    private final CompilerProvider compiler;
    private final Path workspaceRoot;

    public ReferenceIndexer(CompilerProvider compiler, Path workspaceRoot) {
        this.compiler = compiler;
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * compileFiles를 배치 컴파일하고, walkFiles(널이면 전체)의 참조 엣지를 추출한다.
     * 배치: compileFiles=walkFiles=워크스페이스 전체. 증분: compileFiles=변경파일+이웃, walkFiles={변경파일}.
     */
    public List<Edge> index(Collection<Path> compileFiles, Set<Path> walkFiles) {
        if (compileFiles.isEmpty()) return List.of();
        try (var task = compiler.compile(compileFiles.toArray(Path[]::new))) {
            var trees = Trees.instance(task.task);
            var pos = trees.getSourcePositions();

            // pass 1: 선언 → 타겟키 맵 (선언 트리에서 nameLocation 직접 계산; element identity 키)
            var declKey = new HashMap<Element, String[]>();  // element -> [relpath, name, kind, line, col]
            for (var cu : task.roots) {
                Path declFile = Paths.get(cu.getSourceFile().toUri());
                if (!declFile.startsWith(workspaceRoot)) continue;
                buildDeclKeys(cu, trees, pos, declFile, declKey);
            }

            // pass 2: 참조 노드 → 타겟 element → declKey 조회(생성자/Lombok 접근자 매핑 포함) → 엣지
            var edges = new ArrayList<Edge>();
            var seen = new HashSet<String>();  // (targetKey|refPos) dedup
            for (var cu : task.roots) {
                Path refFile = Paths.get(cu.getSourceFile().toUri());
                if (walkFiles != null && !walkFiles.contains(refFile)) continue;
                if (!refFile.startsWith(workspaceRoot)) continue;
                collectRefs(cu, trees, pos, declKey, workspaceRoot.relativize(refFile).toString(), edges, seen);
            }
            LOG.info(String.format("ReferenceIndexer: compiled=%d declSymbols=%d edges=%d",
                    compileFiles.size(), declKey.size(), edges.size()));
            return edges;
        }
    }

    private void buildDeclKeys(CompilationUnitTree cu, Trees trees, SourcePositions pos, Path declFile,
                               Map<Element, String[]> declKey) {
        final String rel = workspaceRoot.relativize(declFile).toString();
        new TreePathScanner<Void, Void>() {
            void decl(Tree t, ModifiersTree mods, String kind, String keyName, String searchName) {
                var el = trees.getElement(getCurrentPath());
                if (el == null) return;
                int[] lc = nameLocation(pos, cu, t, mods, searchName);
                if (lc == null) return;
                declKey.putIfAbsent(el, new String[]{rel, keyName, kind, String.valueOf(lc[0]), String.valueOf(lc[1])});
            }
            @Override public Void visitClass(ClassTree t, Void p) {
                decl(t, t.getModifiers(), "class", t.getSimpleName().toString(), t.getSimpleName().toString());
                return super.visitClass(t, p);
            }
            @Override public Void visitMethod(MethodTree t, Void p) {
                if (t.getName().contentEquals("<init>")) {
                    // 생성자: sari documentSymbol처럼 이름은 "<init>", 위치는 클래스명 위치.
                    var enclosing = trees.getElement(getCurrentPath());
                    String cls = enclosing != null ? enclosing.getEnclosingElement().getSimpleName().toString() : "";
                    decl(t, t.getModifiers(), "method", "<init>", cls);
                } else {
                    decl(t, t.getModifiers(), "method", t.getName().toString(), t.getName().toString());
                }
                return super.visitMethod(t, p);
            }
            @Override public Void visitVariable(VariableTree t, Void p) {
                if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
                    decl(t, t.getModifiers(), "field", t.getName().toString(), t.getName().toString());
                }
                return super.visitVariable(t, p);
            }
        }.scan(cu, null);
    }

    private void collectRefs(CompilationUnitTree cu, Trees trees, SourcePositions pos, Map<Element, String[]> declKey,
                             String refRel, List<Edge> edges, Set<String> seen) {
        new TreePathScanner<Void, Void>() {
            void rec() {
                Element el;
                try { el = trees.getElement(getCurrentPath()); } catch (RuntimeException e) { return; }
                if (el == null) return;
                String[] key = declKey.get(el);
                if (key == null) {
                    // Lombok 등 생성 접근자(getX/isX/setX) → 밑 필드로 매핑(sari가 가진 필드 심볼에 붙임)
                    var field = accessorField(el);
                    if (field != null) key = declKey.get(field);
                    if (key == null) return;  // 라이브러리/로컬/생성멤버(대응 필드 없음)
                }
                long start = pos.getStartPosition(cu, getCurrentPath().getLeaf());
                long end = pos.getEndPosition(cu, getCurrentPath().getLeaf());
                if (start < 0 || end < 0) return;  // NOPOS(합성 노드) 제외 — FindReferences와 동일(start OR end)
                var lm = cu.getLineMap();
                int sl = (int) lm.getLineNumber(start), sc = (int) lm.getColumnNumber(start);
                int el2 = (int) lm.getLineNumber(end), ec = (int) lm.getColumnNumber(end);
                String dk = key[0] + "|" + key[1] + "|" + key[2] + "|" + key[3] + ":" + key[4];
                if (!seen.add(dk + "@" + refRel + ":" + sl + ":" + sc)) return;  // 위치 dedup
                edges.add(new Edge(key[0], key[1], key[2], Integer.parseInt(key[3]), Integer.parseInt(key[4]),
                        refRel, sl, sc, el2, ec));
            }
            @Override public Void visitMemberSelect(MemberSelectTree t, Void p) { rec(); return super.visitMemberSelect(t, p); }
            @Override public Void visitNewClass(NewClassTree t, Void p) { rec(); return super.visitNewClass(t, p); }
            @Override public Void visitMemberReference(MemberReferenceTree t, Void p) { rec(); return super.visitMemberReference(t, p); }
            @Override public Void visitIdentifier(IdentifierTree t, Void p) { rec(); return super.visitIdentifier(t, p); }
        }.scan(cu, null);
    }

    // 접근자 메서드(getX/isX/setX)면 밑 필드 element를 반환. enclosing 타입 필드들의 accessorNames에 매칭.
    // 복수 필드가 매칭되면(예: boolean active + boolean isActive의 is-접두 겹침) 모호하므로 매핑하지 않는다(정확도 우선).
    private static Element accessorField(Element method) {
        if (method.getKind() != ElementKind.METHOD) return null;
        var type = method.getEnclosingElement();
        if (!(type instanceof TypeElement)) return null;
        String mname = method.getSimpleName().toString();
        Element match = null;
        for (var m : type.getEnclosedElements()) {
            if (m.getKind() == ElementKind.FIELD && accessorNames(m.getSimpleName().toString()).contains(mname)) {
                if (match != null) return null;  // 모호(복수 매칭) → 추측 안 함
                match = m;
            }
        }
        return match;
    }

    // 필드명 xxx -> getXxx/isXxx/setXxx (+ isXxx boolean 필드의 게터 isXxx / 세터 setXxx)
    private static List<String> accessorNames(String field) {
        if (field.isEmpty()) return List.of();
        var cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        var names = new ArrayList<>(List.of("get" + cap, "is" + cap, "set" + cap));
        if (field.length() > 2 && field.startsWith("is") && Character.isUpperCase(field.charAt(2))) {
            names.add(field);
            names.add("set" + field.substring(2));
        }
        return names;
    }

    // 선언 트리에서 이름 식별자 위치(1-based line,col). modifiers(애노테이션 포함) 뒤부터 이름을 찾는다.
    // 이름을 소스에서 못 찾으면 null(=키 안 함). 이게 Lombok "생성 멤버 필터" 역할을 한다:
    // 컴파일 task엔 Lombok이 게터/세터를 AST에 주입하지만 그 이름은 소스 텍스트에 없어 findWord가 실패 → null →
    // 키 안 됨 → 참조 pass에서 accessorField로 밑 필드에 매핑됨. (documentSymbol은 parse 기반이라 생성 멤버가 아예 없음 → 일관)
    private static int[] nameLocation(SourcePositions pos, CompilationUnitTree cu, Tree t, ModifiersTree mods, String name) {
        if (name == null || name.isEmpty()) return null;
        long start = pos.getStartPosition(cu, t), end = pos.getEndPosition(cu, t);
        if (start < 0 || end < 0) return null;
        CharSequence src;
        try { src = cu.getSourceFile().getCharContent(true); } catch (Exception e) { return null; }
        if (src == null) return null;
        long from = start;
        if (mods != null) { long m = pos.getEndPosition(cu, mods); if (m >= start) from = m; }
        int np = findWord(src, name, (int) from, (int) end);
        if (np < 0) return null;
        var lm = cu.getLineMap();
        return new int[]{(int) lm.getLineNumber(np), (int) lm.getColumnNumber(np)};
    }

    private static int findWord(CharSequence s, String w, int from, int to) {
        to = Math.min(to, s.length());
        int wl = w.length();
        if (wl == 0) return -1;
        for (int i = Math.max(0, from); i + wl <= to; i++) {
            if (i > 0 && Character.isJavaIdentifierPart(s.charAt(i - 1))) continue;
            int k = 0;
            while (k < wl && s.charAt(i + k) == w.charAt(k)) k++;
            if (k != wl) continue;
            int after = i + wl;
            if (after < s.length() && Character.isJavaIdentifierPart(s.charAt(after))) continue;
            return i;
        }
        return -1;
    }
}
