# 참조배치 컴파일 비용 측정 (javacs 포크) — Design

**날짜:** 2026-07-20
**상태:** 🟡 초안 (브레인스토밍 + 셀프리뷰 + codex 리뷰 2회 반영, 사용자 리뷰 대기)
**한 줄:** sari-java L5 참조배치의 지배 비용인 "워크스페이스 전체 javac 컴파일"이 실제로 **어디에 쓰이는지**를, 라이브 데몬 없이 포크의 `ReferenceIndexer`를 **격리된 스테이징 트리**에서 구동하는 측정 하네스로 정량화한다. 이 스펙은 **측정만** 다룬다(수정은 후속 스펙).

## 배경 / 동기
- sari-java 콜드스타트 인덱싱의 지배 비용 = L5 참조배치 javac 컴파일(perf handoff 2026-07-17: 참조배치 워커시간 ~97%, ~2,500s CPU, tail 764파일 262s).
- 포크는 `java/indexReferences`로 워크스페이스 전체를 1회 컴파일하고 2-pass AST로 (target→reference) 엣지를 방출(`ReferenceIndexer`, 전체배치는 `FileStore.all()`). `FileStore.FindJavaSources`는 심볼릭 링크만 스킵 — `build/`·`bin/`·`generated`를 안 거른다.
- 대표 표본 vone-api(Gradle 멀티모듈 14): main 3120 / test 182 / **생성·빌드 산출물 ~840(build/generated 431 + bin/generated-sources ~409)**, Lombok 1529. 진짜 의심 덩어리는 생성/빌드 산출물(~24%)이나, `bin`(Eclipse)·`build`(Gradle QueryDSL Q클래스)는 중복이고 포크는 Lombok만 processor로 올려 QueryDSL APT를 안 돌리므로 `build/generated` 제거 시 `QUser` 등 참조가 깨질 수 있다 → **순진한 제외는 이득 작거나 위험 → 먼저 측정.**

## 측정 유효성의 핵심 제약 (codex 1차 리뷰)
`SourceFileManager.list(SOURCE_PATH)`가 `compileFiles`(roots)와 무관하게 `FileStore.list(pkg)`로 **워크스페이스 전체 소스를 노출**(SourceFileManager.java:25,69). 따라서 roots에서 파일을 빼도 javac가 sourcepath로 도로 끌어온다 — **실제 제외는 FileStore가 보는 트리 자체를 좁혀야** 한다. `getJavaLanguageServer(root)`→`FileStore.setWorkspaceRoots(root)`(JavaLanguageServer.java:173, FileStore.java:37)이므로 **스테이징 트리를 루트로 주면 SOURCE_PATH가 그 트리로 한정**된다. FileStore가 심볼릭 링크를 스킵하니 **하드링크(폴백 복사)**로 만든다.

## 목표
1. 참조배치 컴파일 시간을 파일 범주(main / test / bin-generated / build-generated)별로, **실제로 제외된 트리**에서 정량화.
2. 각 제외가 **(a) compileMs/pass1Ms/pass2Ms를 얼마나 줄이고 (b) sari가 조인 가능한 main-타깃 엣지를 얼마나 잃고 (c) 진단(unresolved)을 얼마나 늘리는지** 동시 측정.
3. 결과로 다음 레버 선택: bin 제외(순수 이득?) / build 제외(QueryDSL APT 필요?) / 남는 비용의 정체(proc·attribution·pass1·진단).

## 비목표
실제 수정(FileStore 필터·QueryDSL APT·샤딩). proc:none 게이팅(Lombok-heavy). 라이브 데몬·프로덕션 state.db. 증분/라이브쿼리 경로.

## 측정 대상
`-Dsari.refbench.repo=<path>`(기본 `/Users/vendys-chulhan/Documents/repositories/vone-api`), 미지정·부재 시 `Assume` 스킵.

## 스테이징 모델 (codex 2차 리뷰 — 빌드 메타데이터·classpath 보존)
각 구성 트리 = **원본 전체를 하드링크로 미러링한 뒤, 그 구성이 제외하는 Java-출력 디렉토리만 삭제**한다. 즉 "필요 파일만 고르기"가 아니라 "전체에서 출력디렉토리만 빼기". 이로써 wrapper·`gradle/`·`gradle.properties`·`buildSrc`·version catalog·included build·convention plugin 등 **모든 비-Java 빌드 메타데이터가 전 구성에 자동 포함**(원본 상대경로 그대로). 제외 대상은 **Java 출력 디렉토리(`build/`, `bin/`)로만 한정**.

### classpath는 원본에서 1회 고정 (codex 2차)
InferConfig의 Gradle classpath 캐시 키가 `workspaceRoot` 절대경로 해시(InferConfig.java:373,381)라, 구성마다 staged 경로가 다르면 전부 cache-miss + 재추론 변동이 시간을 오염시킨다. → **classpath는 원본 repo에서 1회 추론해 얻은 jar 셋을 모든 구성에 동일 주입**(또는 동일 캐시 재사용). 주입은 `java.classPath` 설정 경로를 쓰되 **`server.compiler()` 최초 호출 전에 `didChangeConfiguration`으로** 넣는다(그 뒤엔 compiler가 이미 CP를 굳힘). classpath 추론 ms는 **컴파일 판정 시간에서 제외**하고, 구성별 **jar 셋 동일성만 검증**(다르면 그 구성 무효).

## 하네스 설계
- 위치: 포크 `src/test/java/org/javacs/RefBatchCompileBenchmark.java`(JUnit, Assume 가드). **프로덕션 코드 무변경.**
- **구성당 프레시 JVM = 1 구성**(`-Dsari.refbench.config=...`, Gradle `forkEvery=1`/개별 invocation). FileStore·ReusableCompiler·classpath 캐시가 정적/재사용이라 한 JVM 다구성은 오염. 각 구성 **K회(기본 3) median**, 순서 교차.
- 구동:
  - `var server = LanguageServerFixture.getJavaLanguageServer(stagedRoot, d -> {});`
  - **시간·엣지:** `new ReferenceIndexer(server.compiler(), stagedRoot).index(compileFiles, walkFiles)` — `compileFiles`=스테이징 트리 전체 .java, `walkFiles`=스테이징 `src/main/**`.
  - **진단(별도 패스, codex 2차 블로커):** onError 콜백은 index() 직접호출엔 안 걸리므로, `server.compiler()`를 `JavaCompilerService`로 써서 `compiler.compile(compileFiles)`의 `CompileTask.diags`를 읽어 error/warn·unresolved(`compiler.err.cant.resolve*`)를 집계. 이 진단 패스는 시간 판정 대상 아님(엣지/시간은 index()에서).
  - **시간 분해:** `ReferenceIndexer`가 방출하는 `LOG.info("ReferenceIndexer: ... compileMs=.. pass1Ms=.. pass2Ms=..")` 한 줄(ReferenceIndexer.java:98)을, 테스트가 `Logger.getLogger("main")`에 부착한 handler로 캡처해 구조적 파싱. **정확히 1줄 매칭 아니면 실패**(구성/run id 혼선 방지).

## 필수 동등성 게이트 (codex 2차 블로커 — 이게 통과해야 이후 판정 유효)
staged 루트는 절대경로·Gradle 실행디렉토리가 원본과 달라 C0부터 어긋날 수 있다. → **`C0-original`(원본 직접) vs `C0-staged`(전체 미러) 동등성 검증을 필수 게이트로** 선행: 파일수·classpath jar 셋·총 진단·main-타깃 엣지 수가 일치해야 한다(디버깅용으로 **엣지 셋 해시**도 함께 남긴다). 불일치면 스테이징이 빌드/classpath를 바꾼 것이므로 **이후 전 구성 판정 무효**(측정 방법 자체를 재검토).

## 측정 매트릭스 (proc:full 고정, 각 구성 = 별도 스테이징 트리 + 프레시 JVM)
| # | 스테이징 트리 | 의도 |
|---|---|---|
| G  | C0-original (원본 직접) | 동등성 게이트 기준 |
| C0 | 전체 미러 | **기준선 = 현재 포크 동작(스테이징 버전)** |
| C1a | C0 − `bin/**` | Eclipse 중복만 제거 — 순수 이득? |
| C1b | C0 − `build/**` | Gradle 생성만 제거 — QClass 참조 손실? |
| C2 | C0 − `bin/**` − `build/**` | 생성 전부 제거 |

(구 C3 "src-only"는 빌드 메타데이터까지 없애 classpath가 붕괴 → 측정 무효라 폐기. 테스트 제외는 4%로 미미해 별도 구성 불요.)

## 필수 지표 (구성별 median)
`build/reports/refbench/<repo>-<config>.md` + stdout:
- **시간:** compileMs / pass1Ms / pass2Ms(로그 파싱) + 전체 index wall. classpath 추론 ms는 별도(판정 제외).
- **엣지 — 타깃 범주별:** 총 엣지, **target이 `src/main` 심볼인 엣지(=sari 조인 가능, 핵심)**, target이 생성/기타.
- **진단:** 총 error/warn, 범주별 error, unresolved 총수 + **Q클래스 관련/비관련 분류**(codex 2차 — C1b 손실이 "제외로 참조 깨짐"인지 "staging이 빌드 깨뜨림"인지 분리).
- **파일수:** compileFiles 범주별 + classpath jar 수 + Gradle 캐시 hit/miss.
- **모듈별(필수 최소):** 14모듈 각 파일수·main-엣지·진단(시간은 선택).

## 판정 규칙
- **선행:** G≡C0(동등성 게이트) 통과 필수. 실패면 방법 재설계.
- **C1a: 시간↓ & main-엣지 불변 & 진단 불변** → `bin/**` 제외 순수 이득(후속 1순위, 저위험 FileStore 필터).
- **C1b/C2: 시간↓ & main-엣지 불변 & jar셋·비-Q 진단 C0와 동일** → `build/**` 안전 제외. 반대로 **main-엣지 하락 or Q-관련 unresolved 급증** → Q클래스가 참조 해소 필수 → 후속 "제외 + QueryDSL APT 배선"의 시간 저울질.
- **C2까지 줄여도 시간 지배적** → 파일 제외로 부족. `compileMs` 지배면 proc(Lombok)·attribution·classpath, `pass1Ms` 지배면 선언 스캔. **여기까지 분해를 봐야 "샤딩 후보"인지 다른 레버인지** 판단(샤딩 단정 금지).

## 제약 (불변)
프로덕션 포크 코드 무변경(테스트 하네스 + 스테이징만). 데몬·라이브머신 불필요. JDK21. proc:full + LOMBOK_JAR 현행 그대로. repo 부재 시 Assume 스킵.

## 리스크
1. **staged baseline이 원본과 불일치**(Gradle 절대경로/실행디렉토리 의존) → G≡C0 게이트가 필수로 차단.
2. **하드링크 실패**(교차 FS) → 복사 폴백(attributes·mtime 보존, 파일수 대조).
3. **classpath 캐시키 경로 의존** → 원본 1회 추론 주입 + jar셋 동일성 검증 + classpath ms 판정 제외.
4. **C1b 손실의 원인 모호**(제외 vs staging 붕괴) → jar셋·비-Q 진단 C0 대조 + unresolved Q분류로 분리.
5. **로그 파싱 취약** → handler 부착 + 정확히 1줄 매칭 강제(누락/복수 시 실패).

## 후속 (측정 결과 의존)
bin/build 제외 필터 · QueryDSL APT 배선 · 모듈 샤딩 — 데이터로 택일.
