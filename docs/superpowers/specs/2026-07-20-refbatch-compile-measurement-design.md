# 참조배치 컴파일 비용 측정 (javacs 포크) — Design

**날짜:** 2026-07-20
**상태:** 🟡 초안 (브레인스토밍 + 셀프리뷰 + codex 리뷰 반영, 사용자 리뷰 대기)
**한 줄:** sari-java L5 참조배치의 지배 비용인 "워크스페이스 전체 javac 컴파일"이 실제로 **어디에 쓰이는지**를, 라이브 데몬 없이 포크의 `ReferenceIndexer`를 **격리된 스테이징 트리**에서 구동하는 측정 하네스로 정량화한다. 이 스펙은 **측정만** 다룬다(수정은 후속 스펙).

## 배경 / 동기
- sari-java 콜드스타트 인덱싱의 지배 비용 = L5 참조배치 javac 컴파일(perf handoff 2026-07-17: 참조배치 워커시간의 ~97%, ~2,500s CPU, tail 워크스페이스 764파일 262s).
- 포크는 `java/indexReferences`로 **워크스페이스 전체를 1회 컴파일**하고 2-pass AST로 (target→reference) 엣지를 방출한다(`ReferenceIndexer`, params 없는 전체배치는 `FileStore.all()` 컴파일). `FileStore.FindJavaSources`는 **심볼릭 링크만 스킵** — `build/`·`bin/`·`generated`를 안 거른다.
- 대표 표본 vone-api(사내 Gradle 멀티모듈, 14모듈): main 3120 / test 182 / **생성·빌드 산출물 ~840(build/generated 431 + bin/generated-sources ~409)**, Lombok 1529파일. 전체의 ~24%가 생성/빌드 파일이며 테스트는 ~4%.
- 진짜 의심 덩어리는 **생성/빌드 산출물 컴파일**이지만, `bin/generated-sources`(Eclipse)와 `build/generated`(Gradle QueryDSL Q클래스)는 중복 QClass이고, 포크는 processorpath에 Lombok만 올려 QueryDSL APT를 안 돌리므로 `build/generated`를 빼면 `QUser` 등 참조가 깨질 수 있다. → **순진한 제외는 이득이 작거나 참조를 깨뜨릴 위험 → 먼저 측정.**

## 측정 유효성의 핵심 제약 (codex 리뷰 블로커 반영)
`SourceFileManager.list(SOURCE_PATH)`는 `compileFiles`(컴파일 roots)와 무관하게 `FileStore.list(pkg)`로 **워크스페이스 전체 소스를 노출**한다(SourceFileManager.java:25,69 확인). 따라서 `compileFiles`에서 파일을 빼도 javac가 필요 시 그 파일을 sourcepath로 도로 끌어온다 — **roots 제외만으로는 실제 제외가 안 된다.** 유효한 제외는 **FileStore가 보는 트리 자체를 좁혀야** 하며, FileStore가 심볼릭 링크를 스킵하므로 **하드링크(또는 복사)로 만든 물리적 스테이징 트리**를 각 구성마다 워크스페이스 루트로 써야 한다.

## 목표
1. 참조배치 컴파일 시간이 파일 범주(main / test / bin-generated / build-generated)별로 어떻게 분포하는지, **격리된(실제로 제외된) 트리**에서 정량화.
2. 각 파일-범주 제외가 **(a) compileMs/pass1Ms/pass2Ms를 얼마나 줄이고 (b) sari가 조인 가능한 main-타깃 엣지를 얼마나 잃는지**를 동시 측정.
3. 결과로 다음 레버를 데이터 기반 선택: bin 제외(순수 이득?) / build 제외(QueryDSL APT 필요?) / 남는 비용의 정체(proc·attribution·진단 폭증 등 → 샤딩이 실제로 답인지).

## 비목표 (범위 밖)
- 실제 수정(FileStore 필터링, QueryDSL APT 배선, 샤딩) — 결과 후 별도 스펙.
- proc:none 게이팅 측정(vone-api Lombok-heavy라 proc 필수).
- 라이브 9090 데몬·프로덕션 state.db.
- 증분/라이브쿼리 경로.

## 측정 대상 (Corpus)
- 주 표본: `-Dsari.refbench.repo=<path>`(기본 `/Users/vendys-chulhan/Documents/repositories/vone-api`). 미지정·부재 시 `Assume`로 스킵(CI 안전, 하드코딩 회피).

## 하네스 설계
- 위치: 포크 `src/test/java/org/javacs/RefBatchCompileBenchmark.java` (JUnit, Assume 가드). **프로덕션 코드 무변경.**
- **구성당 스테이징 트리 생성(실제 제외 보장):** 대상 repo를 훑어 해당 구성의 파일 범주만 골라, 임시 디렉터리에 **원본과 동일한 상대경로로 하드링크**(같은 파일시스템; 실패 시 복사 폴백). FileStore는 이 스테이징 루트만 walk하므로 SOURCE_PATH도 이 트리로 한정된다. build.gradle/settings.gradle/pom.xml 등 classpath 추론 입력 파일은 전 구성에 포함(classpath 동일 유지).
- **구성당 프레시 JVM/서버:** FileStore·ReusableCompiler·classpath 캐시가 정적/재사용이라 한 JVM에 여러 구성을 돌리면 오염된다. 따라서 **한 번의 테스트 실행 = 한 구성**(`-Dsari.refbench.config=C0|C1a|C1b|C2|C3`), Gradle `forkEvery=1` 또는 개별 invocation. 각 구성 **K회(기본 3) 반복 median**, 순서는 호출자가 교차/랜덤화.
- 구동 계약(기존 API 그대로):
  - `var server = LanguageServerFixture.getJavaLanguageServer(stagedRoot, d -> diagCount.increment());`
  - `var edges = new org.javacs.index.ReferenceIndexer(server.compiler(), stagedRoot).index(compileFiles, walkFiles);`
  - `compileFiles` = 스테이징 트리의 전체 .java(그 구성이 물리적으로 담은 것). `walkFiles` = 스테이징 트리의 `src/main/**`(엣지 추출 대상 = sari 타깃).

## 측정 매트릭스 (전부 proc:full 고정, 각 구성 = 별도 스테이징 트리 + 프레시 JVM)
| # | 스테이징 트리 구성 | 의도 |
|---|---|---|
| C0 | 전체(원본과 동일) | **기준선 = 현재 포크 동작** |
| C1a | C0 − `bin/**` | Eclipse 중복 산출만 제거 — 순수 이득 여부 |
| C1b | C0 − `build/**` | Gradle 생성만 제거 — QClass 참조 손실 여부 |
| C2 | C0 − `bin/**` − `build/**` | 생성 전부 제거 — 합산 이득/손실 |
| C3 | `src/main` + `src/test` + `src/testFixtures`만 | 소스만 = 하한 + 손실 상한 |

## 산출물 / 지표 (전부 필수 — codex 리뷰 반영)
구성별 median으로 `build/reports/refbench/<repo>-<config>.md` + 표준출력:
- **시간 분해:** `compileMs` / `pass1Ms` / `pass2Ms` (ReferenceIndexer가 이미 방출하는 로그 라인 파싱) + classpath 추론 ms(별도) + 전체 wall.
- **엣지 — 타깃 범주별:** 총 엣지 수, **그중 target이 `src/main` 심볼인 엣지(=sari 조인 가능)**, target이 생성/기타인 엣지. "속도 이득 vs main 참조 손실"을 분리하는 핵심 지표.
- **진단(필수):** 총 error/warn 수, 파일 범주별 error 수, unresolved/`cant.resolve` 계열 수 — 에러 복구로 엣지가 유지돼 손실이 가려지는 걸 드러냄.
- **파일수:** compileFiles를 범주별로.
- **classpath 상태:** 추론된 jar 수 + `~/.cache/javacs-classpath` hit/miss(콜드스타트 왜곡 요인 노출).
- **모듈별 보조표(선택):** 14모듈 각 파일수/엣지/진단/시간 — 특정 모듈이 비용을 지배하는지.

## 판정 규칙 (측정 후 다음 스텝)
- **C1a: 시간↓ & main-타깃 엣지 불변 & 진단 불변** → `bin/**` 제외는 순수 이득. 후속 1순위(저위험 FileStore 필터).
- **C1b/C2: 시간 크게↓ & main-타깃 엣지 불변** → `build/**`도 안전 제외. 반대로 **main-타깃 엣지 하락 or unresolved 급증** → Q클래스가 참조 해소에 필수 → 후속 = "제외 + QueryDSL APT 배선"의 시간 저울질(별도 검토).
- **C3까지 줄여도 시간이 지배적** → 파일 제외로는 부족. 단 원인을 시간 분해로 특정: `compileMs`가 지배면 proc(Lombok)·attribution·classpath, `pass1Ms`가 지배면 선언 스캔. **여기까지 봐야 "샤딩 후보"인지 다른 레버인지** 판단(샤딩을 유일 결론으로 못 박지 않음).

## 제약 (불변)
- 프로덕션 포크 코드 무변경(측정은 테스트 하네스 + 스테이징만).
- 데몬·라이브머신 불필요. JDK21로 포크 테스트 실행.
- proc:full + LOMBOK_JAR는 현행 `CompileBatch` 그대로.
- 대상 repo 부재 시 `Assume` 스킵.

## 리스크
1. **스테이징 하드링크 실패**(교차 파일시스템/권한) → 복사 폴백. 스테이징 파일수를 원본과 대조해 누락 검증.
2. **classpath 추론 불완전**(멀티모듈) → 진단 error 수로 "해소 실패로 인한 저평가"를 드러냄. classpath jar 수·캐시 상태 리포트.
3. **측정 소음/캐시** → 구성당 프레시 JVM + K회 median + 결정적 지표(파일수·엣지·진단)를 시간과 교차검증. Gradle classpath 캐시 상태 명시.
4. **walk=src/main 고정** → compile에서 의존 파일이 빠져 main 참조가 안 잡히면 그게 손실 신호(의도됨). 단 "walk origin=src/main"과 "target category=main 조인가능 심볼"을 리포트에서 분리 명시.

## 후속 (측정 결과 의존)
판정 규칙에 따라 별도 설계: bin/build 제외 필터 · QueryDSL APT 배선 · 모듈 샤딩. 이 스펙은 그중 무엇을 할지 **데이터로 고르기 위한** 사전 단계.
