package org.javacs;

import com.google.devtools.build.lib.analysis.AnalysisProtos;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors; // Added for Collectors.toSet()
import java.util.stream.Stream;

class InferConfig {
    private static final Logger LOG = Logger.getLogger("main");

    /** Root of the workspace that is currently open in VSCode */
    private final Path workspaceRoot;
    /** External dependencies specified manually by the user */
    private final Collection<String> externalDependencies;
    /** Location of the maven repository, usually ~/.m2 */
    private final Path mavenHome;
    /** Location of the gradle cache, usually ~/.gradle */
    private final Path gradleHome;
    /** Environment variables, primarily for testing */
    private final Map<String, String> envVars;

    InferConfig(
            Path workspaceRoot,
            Collection<String> externalDependencies,
            Path mavenHome,
            Path gradleHome,
            Map<String, String> envVars) {
        this.workspaceRoot = workspaceRoot;
        this.externalDependencies = externalDependencies;
        this.mavenHome = mavenHome;
        this.gradleHome = gradleHome;
        this.envVars = Objects.requireNonNullElseGet(envVars, System::getenv);
    }

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies, Path mavenHome, Path gradleHome) {
        this(workspaceRoot, externalDependencies, mavenHome, gradleHome, null); // Null envVars defaults to System.getenv()
    }

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies) {
        this(workspaceRoot, externalDependencies, defaultMavenHome(), defaultGradleHome(), null);
    }

    InferConfig(Path workspaceRoot) {
        this(workspaceRoot, Collections.emptySet(), defaultMavenHome(), defaultGradleHome(), null);
    }

    // Constructor for testing, allowing envVars injection.
    InferConfig(Path workspaceRoot, Map<String, String> envVars) {
        this(workspaceRoot, Collections.emptySet(), defaultMavenHome(), defaultGradleHome(), envVars);
    }

    private static Path defaultMavenHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".m2");
    }

    private static Path defaultGradleHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".gradle");
    }

    /** Find .jar files for external dependencies, for examples maven dependencies in ~/.m2 or jars in bazel-genfiles */
    Set<Path> classPath() {
        // Check for CLASSPATH environment variable first
        String classPathEnv = this.envVars.get("CLASSPATH");
        if (classPathEnv != null && !classPathEnv.isEmpty()) {
            // TODO Add source/doc discovery for arbitrary jars provided via CLASSPATH.
            LOG.info("Using CLASSPATH environment variable: " + classPathEnv);
            return Arrays.stream(classPathEnv.split(Pattern.quote(File.pathSeparator)))
                         .map(Paths::get)
                         .collect(Collectors.toSet());
        }

        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            var result = new HashSet<Path>();
            for (var id : externalDependencies) {
                var a = Artifact.parse(id);
                var found = findAnyJar(a, false);
                if (found == NOT_FOUND) {
                    LOG.warning(String.format("Couldn't find jar for %s in %s or %s", a, mavenHome, gradleHome));
                    continue;
                }
                result.add(found);
            }
            return result;
        }

        // Maven
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            return mvnDependenciesCached(pomXml, "dependency:list", this.envVars);
        }

        // Gradle — pom.xml이 없는 build.gradle(.kts) 프로젝트. classpath를 해결하지 않으면 javac가
        // 의존성 타입을 못 찾아 에러 복구 컴파일로 극도로 느려진다(관측: 6분+). Maven과 동일하게 캐시한다.
        var buildGradle = workspaceRoot.resolve("build.gradle");
        var buildGradleKts = workspaceRoot.resolve("build.gradle.kts");
        if (Files.exists(buildGradle) || Files.exists(buildGradleKts)) {
            return gradleDependenciesCached(workspaceRoot, this.envVars);
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (Files.exists(bazelWorkspaceRoot.resolve("WORKSPACE"))) {
            return bazelClasspath(bazelWorkspaceRoot);
        }

        return Collections.emptySet();
    }

    private Path bazelWorkspaceRoot() {
        for (var current = workspaceRoot; current != null; current = current.getParent()) {
            if (Files.exists(current.resolve("WORKSPACE"))) {
                return current;
            }
        }
        return workspaceRoot;
    }

    /** Find source .jar files in local maven repository. */
    Set<Path> buildDocPath() {
        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            var result = new HashSet<Path>();
            for (var id : externalDependencies) {
                var a = Artifact.parse(id);
                var found = findAnyJar(a, true);
                if (found == NOT_FOUND) {
                    LOG.warning(String.format("Couldn't find doc jar for %s in %s or %s", a, mavenHome, gradleHome));
                    continue;
                }
                result.add(found);
            }
            return result;
        }

        // Maven
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            // PERF: source jars are only used for hover/goto-into-libraries, not for references/indexing.
            // Skipping the extra `mvn dependency:sources` invocation (and source-jar downloads) speeds cold start.
            return Collections.emptySet();
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (Files.exists(bazelWorkspaceRoot.resolve("WORKSPACE"))) {
            return bazelSourcepath(bazelWorkspaceRoot);
        }

        return Collections.emptySet();
    }

    private Path findAnyJar(Artifact artifact, boolean source) {
        Path maven = findMavenJar(artifact, source);

        if (maven != NOT_FOUND) {
            return maven;
        } else return findGradleJar(artifact, source);
    }

    Path findMavenJar(Artifact artifact, boolean source) {
        var jar =
                mavenHome
                        .resolve("repository")
                        .resolve(artifact.groupId.replace('.', File.separatorChar))
                        .resolve(artifact.artifactId)
                        .resolve(artifact.version)
                        .resolve(fileName(artifact, source));
        if (!Files.exists(jar)) {
            LOG.warning(jar + " does not exist");
            return NOT_FOUND;
        }
        return jar;
    }

    private Path findGradleJar(Artifact artifact, boolean source) {
        // Search for caches/modules-*/files-*/groupId/artifactId/version/*/artifactId-version[-sources].jar
        var base = gradleHome.resolve("caches");
        var pattern =
                "glob:"
                        + String.join(
                                File.separator,
                                base.toString(),
                                "modules-*",
                                "files-*",
                                artifact.groupId,
                                artifact.artifactId,
                                artifact.version,
                                "*",
                                fileName(artifact, source));
        var match = FileSystems.getDefault().getPathMatcher(pattern);

        try {
            return Files.walk(base, 7).filter(match::matches).findFirst().orElse(NOT_FOUND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String fileName(Artifact artifact, boolean source) {
        return artifact.artifactId + '-' + artifact.version + (source ? "-sources" : "") + ".jar";
    }

    // PERF(A3): cache the resolved classpath on disk keyed by pom.xml mtime, so unchanged projects
    // skip the ~1.6s `mvn dependency:list` subprocess on cold start.
    static Set<Path> mvnDependenciesCached(Path pomXml, String goal, Map<String, String> envVars) {
        try {
            var abs = pomXml.toAbsolutePath();
            var mtime = Files.getLastModifiedTime(abs).toMillis();
            var cacheDir = Paths.get(System.getProperty("user.home"), ".cache", "javacs-classpath");
            Files.createDirectories(cacheDir);
            var key = Integer.toHexString(abs.toString().hashCode()) + "-" + goal.replace(':', '_') + ".txt";
            var cacheFile = cacheDir.resolve(key);
            if (Files.exists(cacheFile)) {
                var lines = Files.readAllLines(cacheFile);
                if (!lines.isEmpty() && lines.get(0).equals("mtime=" + mtime)) {
                    var deps = new HashSet<Path>();
                    for (var i = 1; i < lines.size(); i++) {
                        if (!lines.get(i).isBlank()) deps.add(Paths.get(lines.get(i)));
                    }
                    LOG.info("Using cached classpath (" + deps.size() + " jars) for " + abs);
                    return deps;
                }
            }
            var deps = mvnDependencies(pomXml, goal, envVars);
            var out = new ArrayList<String>();
            out.add("mtime=" + mtime);
            for (var d : deps) out.add(d.toString());
            Files.write(cacheFile, out);
            return deps;
        } catch (IOException e) {
            LOG.warning("classpath cache failed, falling back to mvn: " + e.getMessage());
            return mvnDependencies(pomXml, goal, envVars);
        }
    }

    static Set<Path> mvnDependencies(Path pomXml, String goal, Map<String, String> envVars) {
        Objects.requireNonNull(pomXml, "pom.xml path is null");
        try {
            // TODO consider using mvn valide dependency:copy-dependencies -DoutputDirectory=??? instead
            // Run maven as a subprocess
            String[] command = {
                getMvnCommand(envVars),
                "--batch-mode", // Turns off ANSI control sequences
                "validate",
                goal,
                "-DincludeScope=test",
                "-DoutputAbsoluteArtifactFilename=true",
            };
            var output = Files.createTempFile("java-language-server-maven-output", ".txt");
            LOG.info("Running " + String.join(" ", command) + " ...");
            var workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            var process =
                    new ProcessBuilder()
                            .command(command)
                            .directory(workingDirectory)
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .redirectOutput(output.toFile())
                            .start();
            // Wait for process to exit
            var result = process.waitFor();
            if (result != 0) {
                LOG.severe("`" + String.join(" ", command) + "` returned " + result);
                return Set.of();
            }
            // Read output
            var dependencies = new HashSet<Path>();
            for (var line : Files.readAllLines(output)) {
                var jar = readDependency(line);
                if (jar != NOT_FOUND) {
                    dependencies.add(jar);
                }
            }
            return dependencies;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Pattern DEPENDENCY =
            Pattern.compile("^\\[INFO\\]\\s+(.*:.*:.*:.*:.*):(/.*?)( -- module .*)?$");

    static Path readDependency(String line) {
        var match = DEPENDENCY.matcher(line);
        if (!match.matches()) {
            return NOT_FOUND;
        }
        var artifact = match.group(1);
        var path = match.group(2);
        LOG.info(String.format("...%s => %s", artifact, path));
        return Paths.get(path);
    }

    static String getMvnCommand(Map<String, String> envVars) {
        envVars = Objects.requireNonNullElseGet(envVars, System::getenv);
        var mvnCommand = "mvn";
        if (File.separatorChar == '\\') {
            mvnCommand = findExecutableOnPath("mvn.cmd", envVars);
            if (mvnCommand == null) {
                mvnCommand = findExecutableOnPath("mvn.bat", envVars);
            }
        }
        // If findExecutableOnPath returns null (e.g. PATH is not set), we should still return "mvn"
        // and let the execution fail later if it's not on the (empty) path.
        return mvnCommand == null ? "mvn" : mvnCommand;
    }

    // ---- Gradle classpath 해결 (Maven 방식 미러링) ----

    private static final long GRADLE_TIMEOUT_SEC = 180;

    // 해결 가능한 classpath 구성(compile/runtime)의 jar 절대경로를 "SARI_CP <path>"로 출력하는 init 스크립트.
    // lenientConfiguration + --offline로 다운로드 없이 캐시된 것만 best-effort로 모은다(실패해도 부분 classpath).
    private static final String GRADLE_CLASSPATH_INIT_SCRIPT =
            "gradle.rootProject { rp ->\n"
            + "  rp.tasks.register('sariPrintClasspath') {\n"
            + "    doLast {\n"
            + "      def seen = new LinkedHashSet()\n"
            + "      rp.allprojects.each { p ->\n"
            + "        p.configurations.each { c ->\n"
            + "          if (c.canBeResolved && (c.name in ['compileClasspath','runtimeClasspath','testCompileClasspath','testRuntimeClasspath'])) {\n"
            + "            try { c.resolvedConfiguration.lenientConfiguration.getFiles().each { seen.add(it.absolutePath) } } catch (ignored) {}\n"
            + "          }\n"
            + "        }\n"
            + "      }\n"
            + "      seen.each { println 'SARI_CP ' + it }\n"
            + "    }\n"
            + "  }\n"
            + "}\n";

    static Set<Path> gradleDependenciesCached(Path workspaceRoot, Map<String, String> envVars) {
        try {
            var abs = workspaceRoot.toAbsolutePath();
            var buildFile = Files.exists(abs.resolve("build.gradle"))
                    ? abs.resolve("build.gradle") : abs.resolve("build.gradle.kts");
            var mtime = Files.exists(buildFile) ? Files.getLastModifiedTime(buildFile).toMillis() : 0L;
            var cacheDir = Paths.get(System.getProperty("user.home"), ".cache", "javacs-classpath");
            Files.createDirectories(cacheDir);
            var cacheFile = cacheDir.resolve("gradle-" + Integer.toHexString(abs.toString().hashCode()) + ".txt");
            if (Files.exists(cacheFile)) {
                var lines = Files.readAllLines(cacheFile);
                if (!lines.isEmpty() && lines.get(0).equals("mtime=" + mtime)) {
                    var deps = new HashSet<Path>();
                    for (var i = 1; i < lines.size(); i++) {
                        if (!lines.get(i).isBlank()) deps.add(Paths.get(lines.get(i)));
                    }
                    LOG.info("Using cached gradle classpath (" + deps.size() + " jars) for " + abs);
                    return deps;
                }
            }
            var deps = gradleDependencies(abs, envVars);
            var out = new ArrayList<String>();
            out.add("mtime=" + mtime);
            for (var d : deps) out.add(d.toString());
            Files.write(cacheFile, out);
            return deps;
        } catch (IOException e) {
            LOG.warning("gradle classpath cache failed, running gradle directly: " + e.getMessage());
            return gradleDependencies(workspaceRoot, envVars);
        }
    }

    static Set<Path> gradleDependencies(Path workspaceRoot, Map<String, String> envVars) {
        try {
            var initScript = Files.createTempFile("javacs-gradle-classpath", ".gradle");
            Files.writeString(initScript, GRADLE_CLASSPATH_INIT_SCRIPT);
            var gradleCmd = gradleCommand(workspaceRoot, envVars);
            var command = new ArrayList<String>();
            // 워크스페이스 gradlew는 실행권한(+x)이 없을 수 있어 직접 exec 시 error=13(Permission denied)가 난다.
            // sh로 실행해 권한과 무관하게 동작하게 한다.
            if (gradleCmd.endsWith("gradlew")) {
                command.add("sh");
            }
            command.add(gradleCmd);
            command.add("--quiet");
            command.add("--offline");
            command.add("--init-script");
            command.add(initScript.toString());
            command.add("sariPrintClasspath");
            var output = Files.createTempFile("javacs-gradle-output", ".txt");
            LOG.info("Running " + String.join(" ", command) + " in " + workspaceRoot);
            var pb =
                    new ProcessBuilder()
                            .command(command)
                            .directory(workspaceRoot.toFile())
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .redirectOutput(output.toFile());
            // 리포의 Gradle 래퍼는 구버전(예: 6.x)이라 실행 JDK(여기선 21)를 지원하지 않을 수 있다
            // ("Unsupported class file major version"). JAVA_HOME을 제거해 Gradle이 PATH의 기본 JDK
            // (사용자가 실제 빌드에 쓰는 호환 JDK)를 쓰게 한다.
            pb.environment().remove("JAVA_HOME");
            var process = pb.start();
            if (!process.waitFor(GRADLE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.severe("gradle classpath resolution timed out (" + GRADLE_TIMEOUT_SEC + "s) for " + workspaceRoot);
                return Set.of();
            }
            var dependencies = new HashSet<Path>();
            for (var line : Files.readAllLines(output)) {
                if (line.startsWith("SARI_CP ")) {
                    var p = Paths.get(line.substring("SARI_CP ".length()).trim());
                    if (p.toString().endsWith(".jar") && Files.exists(p)) {
                        dependencies.add(p);
                    }
                }
            }
            LOG.info("Gradle classpath resolved " + dependencies.size() + " jars for " + workspaceRoot);
            return dependencies;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Set.of();
        } catch (IOException e) {
            LOG.warning("gradle classpath resolution failed: " + e.getMessage());
            return Set.of();
        }
    }

    // 워크스페이스의 ./gradlew를 우선 사용(버전 일치), 없으면 PATH의 gradle.
    static String gradleCommand(Path workspaceRoot, Map<String, String> envVars) {
        var wrapperName = File.separatorChar == '\\' ? "gradlew.bat" : "gradlew";
        var wrapper = workspaceRoot.resolve(wrapperName);
        if (Files.exists(wrapper)) {
            return wrapper.toAbsolutePath().toString();
        }
        var onPath = findExecutableOnPath(File.separatorChar == '\\' ? "gradle.bat" : "gradle", envVars);
        return onPath == null ? "gradle" : onPath;
    }

    private static String findExecutableOnPath(String name, Map<String, String> envVars) {
        String pathEnv = envVars.get("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (var dirname : pathEnv.split(File.pathSeparator)) {
            var file = new File(dirname, name);
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private boolean buildProtos(Path bazelWorkspaceRoot) {
        var targets = bazelQuery(bazelWorkspaceRoot, "java_proto_library");
        if (targets.size() == 0) {
            return false;
        }
        bazelDryRunBuild(bazelWorkspaceRoot, targets);
        return true;
    }

    private Set<Path> bazelClasspath(Path bazelWorkspaceRoot) {
        var absolute = new HashSet<Path>();

        // Add protos
        if (buildProtos(bazelWorkspaceRoot)) {
            for (var relative : bazelAQuery(bazelWorkspaceRoot, "Javac", "--output", "proto_library")) {
                absolute.add(bazelWorkspaceRoot.resolve(relative));
            }
        }

        // Add rest of classpath
        for (var relative :
                bazelAQuery(bazelWorkspaceRoot, "Javac", "--classpath", "java_library", "java_test", "java_binary")) {
            absolute.add(bazelWorkspaceRoot.resolve(relative));
        }
        return absolute;
    }

    private Set<Path> bazelSourcepath(Path bazelWorkspaceRoot) {
        var absolute = new HashSet<Path>();
        var outputBase = bazelOutputBase(bazelWorkspaceRoot);
        for (var relative :
                bazelAQuery(
                        bazelWorkspaceRoot, "JavaSourceJar", "--sources", "java_library", "java_test", "java_binary")) {
            absolute.add(outputBase.resolve(relative));
        }

        // Add proto source files
        if (buildProtos(bazelWorkspaceRoot)) {
            for (var relative : bazelAQuery(bazelWorkspaceRoot, "Javac", "--source_jars", "proto_library")) {
                absolute.add(bazelWorkspaceRoot.resolve(relative));
            }
        }

        return absolute;
    }

    private Path bazelOutputBase(Path bazelWorkspaceRoot) {
        // Run bazel as a subprocess
        String[] command = {
            "bazel", "info", "output_base",
        };
        var output = fork(bazelWorkspaceRoot, command, false);
        if (output == NOT_FOUND) {
            return NOT_FOUND;
        }
        // Read output
        try {
            var out = Files.readString(output).trim();
            return Paths.get(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void bazelDryRunBuild(Path bazelWorkspaceRoot, Set<String> targets) {
        var command = new ArrayList<String>();
        command.add("bazel");
        command.add("build");
        command.add("--keep_going");
        command.add("--nobuild");
        command.addAll(targets);
        String[] c = new String[command.size()];
        c = command.toArray(c);
        var output = fork(bazelWorkspaceRoot, c, true);
        if (output == NOT_FOUND) {
            return;
        }
        return;
    }

    private Set<String> bazelQuery(Path bazelWorkspaceRoot, String filterKind) {
        String[] command = {"bazel", "query", "--keep_going", "kind(" + filterKind + ",//...)"};
        var output = fork(bazelWorkspaceRoot, command, true);
        if (output == NOT_FOUND) {
            return Set.of();
        }
        return readQueryResult(output);
    }

    private Set<String> readQueryResult(Path output) {
        try {
            Stream<String> stream = Files.lines(output);
            var targets = new HashSet<String>();
            var i = stream.iterator();
            while (i.hasNext()) {
                var t = i.next();
                targets.add(t);
            }
            return targets;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Set<String> bazelAQuery(
            Path bazelWorkspaceRoot, String filterMnemonic, String filterArgument, String... kinds) {
        String kindUnion = "";
        for (var kind : kinds) {
            if (kindUnion.length() > 0) {
                kindUnion += " union ";
            }
            kindUnion += "kind(" + kind + ", ...)";
        }
        String[] command = {
            "bazel",
            "aquery",
            "--keep_going",
            "--output=proto",
            "--include_aspects", // required for java_proto_library, see
            // https://stackoverflow.com/questions/63430530/bazel-aquery-returns-no-action-information-for-java-proto-library
            "--allow_analysis_failures",
            "mnemonic(" + filterMnemonic + ", " + kindUnion + ")"
        };
        var output = fork(bazelWorkspaceRoot, command, true);
        if (output == NOT_FOUND) {
            return Set.of();
        }
        return readActionGraph(output, filterArgument);
    }

    private Set<String> readActionGraph(Path output, String filterArgument) {
        try {
            var containerV2 = AnalysisProtosV2.ActionGraphContainer.parseFrom(Files.newInputStream(output));
            if (containerV2.getArtifactsCount() != 0 && containerV2.getArtifactsList().get(0).getId() != 0) {
                return readActionGraphFromV2(containerV2, filterArgument);
            }
            var containerV1 = AnalysisProtos.ActionGraphContainer.parseFrom(Files.newInputStream(output));
            return readActionGraphFromV1(containerV1, filterArgument);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Set<String> readActionGraphFromV1(AnalysisProtos.ActionGraphContainer container, String filterArgument) {
        var argumentPaths = new HashSet<String>();
        var outputIds = new HashSet<String>();
        for (var action : container.getActionsList()) {
            var isFilterArgument = false;
            for (var argument : action.getArgumentsList()) {
                if (isFilterArgument && argument.startsWith("-")) {
                    isFilterArgument = false;
                    continue;
                }
                if (!isFilterArgument) {
                    isFilterArgument = argument.equals(filterArgument);
                    continue;
                }
                argumentPaths.add(argument);
            }
            outputIds.addAll(action.getOutputIdsList());
        }
        var artifactPaths = new HashSet<String>();
        for (var artifact : container.getArtifactsList()) {
            if (!argumentPaths.contains(artifact.getExecPath())) {
                // artifact was not specified by --filterArgument
                continue;
            }
            if (outputIds.contains(artifact.getId()) && !filterArgument.equals("--output")) {
                // artifact is the output of another java action
                continue;
            }
            var relative = artifact.getExecPath();
            LOG.info("...found bazel dependency " + relative);
            artifactPaths.add(relative);
        }
        return artifactPaths;
    }

    private Set<String> readActionGraphFromV2(AnalysisProtosV2.ActionGraphContainer container, String filterArgument) {
        var argumentPaths = new HashSet<String>();
        var outputIds = new HashSet<Integer>();
        for (var action : container.getActionsList()) {
            var isFilterArgument = false;
            for (var argument : action.getArgumentsList()) {
                if (isFilterArgument && argument.startsWith("-")) {
                    isFilterArgument = false;
                    continue;
                }
                if (!isFilterArgument) {
                    isFilterArgument = argument.equals(filterArgument);
                    continue;
                }
                argumentPaths.add(argument);
            }
            outputIds.addAll(action.getOutputIdsList());
        }
        var artifactPaths = new HashSet<String>();
        for (var artifact : container.getArtifactsList()) {
            if (outputIds.contains(artifact.getId()) && !filterArgument.equals("--output")) {
                // artifact is the output of another java action
                continue;
            }
            var relative = buildPath(container.getPathFragmentsList(), artifact.getPathFragmentId());
            if (!argumentPaths.contains(relative)) {
                // artifact was not specified by --filterArgument
                continue;
            }
            LOG.info("...found bazel dependency " + relative);
            artifactPaths.add(relative);
        }
        return artifactPaths;
    }

    private static String buildPath(List<PathFragment> fragments, int id) {
        for (PathFragment fragment : fragments) {
            if (fragment.getId() == id) {
                if (fragment.getParentId() != 0) {
                    return buildPath(fragments, fragment.getParentId()) + "/" + fragment.getLabel();
                }
                return fragment.getLabel();
            }
        }
        throw new RuntimeException();
    }

    private static Path fork(Path workspaceRoot, String[] command, boolean allowNonZeroExit) {
        try {
            LOG.info("Running " + String.join(" ", command) + " ...");
            var output = Files.createTempFile("java-language-server-bazel-output", ".proto");
            var process =
                    new ProcessBuilder()
                            .command(command)
                            .directory(workspaceRoot.toFile())
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .redirectOutput(output.toFile())
                            .start();
            // Wait for process to exit
            var result = process.waitFor();
            if (result != 0) {
                LOG.severe("`" + String.join(" ", command) + "` returned " + result);
                if (!allowNonZeroExit) {
                    return NOT_FOUND;
                }
            }
            return output;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Path NOT_FOUND = Paths.get("");
}
