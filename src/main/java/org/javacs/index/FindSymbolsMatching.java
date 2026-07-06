package org.javacs.index;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.List;
import java.util.Objects;
import org.javacs.ParseTask;
import org.javacs.StringSearch;
import org.javacs.lsp.Location;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.SymbolInformation;
import org.javacs.lsp.SymbolKind;

class FindSymbolsMatching extends TreePathScanner<Void, List<SymbolInformation>> {

    private final ParseTask task;
    private final String query;
    private CompilationUnitTree root;
    private CharSequence containerName;
    private CharSequence source;   // 파일 소스(nameLocation용) — 컴파일유닛에서 1회만 읽음

    FindSymbolsMatching(ParseTask task, String query) {
        this.task = task;
        this.query = query;
    }

    @Override
    public Void visitCompilationUnit(CompilationUnitTree t, List<SymbolInformation> list) {
        root = t;
        containerName = Objects.toString(t.getPackageName(), "");
        try {
            source = t.getSourceFile().getCharContent(true);
        } catch (Exception e) {
            source = null;
        }
        return super.visitCompilationUnit(t, list);
    }

    @Override
    public Void visitClass(ClassTree t, List<SymbolInformation> list) {
        if (StringSearch.matchesTitleCase(t.getSimpleName(), query)) {
            var info = new SymbolInformation();
            info.name = t.getSimpleName().toString();
            info.kind = asSymbolKind(t.getKind());
            info.location = nameLocation(t, t.getModifiers(), info.name);
            info.containerName = containerName.toString();
            list.add(info);
        }
        var push = containerName;
        containerName = t.getSimpleName();
        super.visitClass(t, list);
        containerName = push;
        return null;
    }

    @Override
    public Void visitMethod(MethodTree t, List<SymbolInformation> list) {
        if (StringSearch.matchesTitleCase(t.getName(), query)) {
            var info = new SymbolInformation();
            info.name = t.getName().toString();
            info.kind = asSymbolKind(t.getKind());
            // 생성자(name="<init>")는 소스에 없으므로 enclosing 클래스명으로 이름 위치를 찾는다.
            var searchName = t.getName().contentEquals("<init>") ? containerName.toString() : info.name;
            info.location = nameLocation(t, t.getModifiers(), searchName);
            info.containerName = containerName.toString();
            list.add(info);
        }
        var push = containerName;
        containerName = t.getName();
        super.visitMethod(t, list);
        containerName = push;
        return null;
    }

    @Override
    public Void visitVariable(VariableTree t, List<SymbolInformation> list) {
        if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree
                && StringSearch.matchesTitleCase(t.getName(), query)) {
            var info = new SymbolInformation();
            info.name = t.getName().toString();
            info.kind = asSymbolKind(t.getKind());
            info.location = nameLocation(t, t.getModifiers(), info.name);
            info.containerName = containerName.toString();
            list.add(info);
        }
        var push = containerName;
        containerName = t.getName();
        super.visitVariable(t, list);
        containerName = push;
        return null;
    }

    // 반환은 primitive int(SymbolInformation.kind가 int) — null 반환 시 언박싱 NPE로 LSP가 크래시했음.
    private static int asSymbolKind(Tree.Kind k) {
        switch (k) {
            case ANNOTATION_TYPE:
            case CLASS:
            case RECORD:   // Java 16+ 레코드: 누락 시 null→NPE로 심볼 인덱싱 중 LSP 프로세스 크래시
                return SymbolKind.Class;
            case ENUM:
                return SymbolKind.Enum;
            case INTERFACE:
                return SymbolKind.Interface;
            case METHOD:
                return SymbolKind.Method;
            case TYPE_PARAMETER:
                return SymbolKind.TypeParameter;
            case VARIABLE:
                // This method is used for symbol-search functionality,
                // where we only return fields, not local variables
                return SymbolKind.Field;
            default:
                return SymbolKind.Class;   // 미지 kind 폴백 — 절대 null 반환 안 함(크래시 방지)
        }
    }

    private Location location(Tree t) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var lines = task.root.getLineMap();
        var start = pos.getStartPosition(root, t);
        var end = pos.getEndPosition(root, t);
        var startLine = (int) lines.getLineNumber(start);
        var startColumn = (int) lines.getColumnNumber(start);
        var endLine = (int) lines.getLineNumber(end);
        var endColumn = (int) lines.getColumnNumber(end);
        var range = new Range(new Position(startLine - 1, startColumn - 1), new Position(endLine - 1, endColumn - 1));
        return new Location(root.getSourceFile().toUri(), range);
    }

    // FIX: 심볼 위치를 선언 전체 시작(수식어/애노테이션)이 아니라 "이름 식별자"로 반환.
    // 그래야 클라이언트가 이 위치에서 findReferences 할 때 심볼이 해석된다(선언 시작을 질의하면 element=NULL).
    // 검색은 modifiers(애노테이션 포함) 끝부터 시작 → @Column(name="startTime") 같은 애노테이션 문자열 오매칭 방지.
    private Location nameLocation(Tree t, ModifiersTree mods, String name) {
        if (source == null || name == null || name.isEmpty()) return location(t);
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var lines = task.root.getLineMap();
        long start = pos.getStartPosition(root, t);
        long end = pos.getEndPosition(root, t);
        if (start < 0 || end < 0) return location(t);
        long from = start;
        if (mods != null) {
            long modEnd = pos.getEndPosition(root, mods);
            if (modEnd >= start) from = modEnd;   // 애노테이션/수식어 뒤부터
        }
        long namePos = findWord(source, name, (int) from, (int) end);
        if (namePos < 0) return location(t);
        long nameEnd = namePos + name.length();
        var range = new Range(
                new Position((int) lines.getLineNumber(namePos) - 1, (int) lines.getColumnNumber(namePos) - 1),
                new Position((int) lines.getLineNumber(nameEnd) - 1, (int) lines.getColumnNumber(nameEnd) - 1));
        return new Location(root.getSourceFile().toUri(), range);
    }

    // [from, to) 범위에서 name을 "완전한 식별자"(앞뒤가 식별자 문자가 아님)로 처음 등장하는 위치.
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
