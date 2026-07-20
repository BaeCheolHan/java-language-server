# 참조배치 컴파일 비용 측정 — 결과 (vone-api, 2026-07-20)

설계: `2026-07-20-refbatch-compile-measurement-design.md`. 하네스: `src/test/java/org/javacs/RefBatchCompileBenchmark.java`. 대상: vone-api(Gradle 멀티모듈, main 3120·Lombok 1529). K=1, JDK21, classpath 원본 1회 주입(284 jars).

## 원측정 (구성 = compileFiles 트리)
| 구성 | javaFiles | compileMs | pass1 | pass2 | edges(tot/main/gen) | errors | unresolved(tot/Q) |
|---|---|---|---|---|---|---|---|
| G (원본 직접) | 4349 | 11804 | 884 | 223 | 66132/50771/15361 | 9690 | 9012/164 |
| C0 (전체 미러) | 4349 | 11249 | 861 | 198 | 66132/50771/15361 | 9690 | 9012/164 |
| C1a (−bin) | 3940 | 9261 | 623 | 1621 | 89222/73821/15401 | 442 | 21/10 |
| C1b (−build) | 3918 | 9414 | 692 | 1639 | 88709/73821/14888 | 422 | 25/13 |
| C2 (−bin−build) | 3509 | 9903 | 1069 | 1683 | 73821/73821/0 | 1 | 0/0 |

## 판정
- **G≡C0 동등성 게이트: PASS.** 두 행이 완전 동일(파일수·엣지·에러·unresolved) → 스테이징이 빌드/classpath를 바꾸지 않음. 측정 유효.
- **생성 디렉토리(bin/build)는 순수 해악이었다(예상 뒤집힘).** 우려했던 "제외 시 QueryDSL 참조 깨짐"은 없었다 — Q클래스가 classpath jar로 해소되므로 소스 제외해도 unresolved 0(C2). 오히려 `bin/generated-sources`(Eclipse)와 `build/generated`(Gradle)의 **중복 Q클래스가 duplicate-class 컴파일 에러 9690개를 유발**하고, 그 여파로 영향받은 컴파일 유닛의 어트리뷰션이 실패해 **main-타깃 참조의 45%(50771→73821)를 소실**시키고 있었다.
- **C2(둘 다 제외) = 순수 최적:** errors 9690→**1**, unresolved 9012→**0**, main 엣지 50771→**73821(+45%)**, compileMs 11249→9903(**−12%**), 파일 4349→3509(−19%).
- **이건 성능 이슈일 뿐 아니라 프로덕션 정확성 버그다.** G(원본 직접, 스테이징 무관)도 9690 errors·9012 unresolved → 사내 vone-api류(커밋된 build/generated·bin 존재)에서 sari L5 java 참조가 실제로 ~45% 누락 + 에러 폭증 상태였다.

## 남은 성능
compileMs는 C2에서도 ~9.9s(3509파일) — 생성 제외로 콜드스타트가 크게는 안 빨라진다(~12%). 지배 비용은 여전히 코어 타입 어트리뷰션(+Lombok proc). 콜드스타트를 근본적으로 줄이려면 별도 레버(모듈 샤딩/병렬)가 필요하며, 이는 별도 스펙. pass2가 C0(198)→C2(1683)로 증가한 건 참조가 45% 더 해소돼 엣지 방출이 늘어서(정상, 여전히 컴파일 대비 미미).

## 후속 (권장 우선순위)
1. **[1순위·저위험·고이득] FileStore 소스 walk에서 `build/`·`bin/`(Java 출력 디렉토리) 제외** — 정확성(에러 9690→1, 참조 +45%) + 속도(−12%) 동시. `FileStore.FindJavaSources.preVisitDirectory`에 디렉토리 스킵 추가. 별도 스펙+구현.
2. **[후속] 콜드스타트 compile 자체 단축(모듈 샤딩/병렬)** — C2에서도 ~10s라 구조적 레버 필요. 별도 검토.
