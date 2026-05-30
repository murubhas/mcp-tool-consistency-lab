package dev.mcp.toollab.eval;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleBoundaryTest {
    private static final String DOMAIN_PACKAGE = "dev.mcp.toollab." + "domain";

    @Test
    void evaluatorMainDoesNotReferenceDomainPackage() throws IOException {
        Path evaluatorDir = evaluatorDir();
        List<Path> offenders;
        try (var files = Files.walk(evaluatorDir.resolve("src/main/java"))) {
            offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> referencesDomain(path))
                    .toList();
        }

        assertTrue(offenders.isEmpty(), "Evaluator main must not reference the domain package: " + offenders);
    }

    @Test
    void evaluatorPomDoesNotDeclareDomainOrServerDependency() throws IOException {
        String pom = Files.readString(evaluatorDir().resolve("pom.xml"));

        assertTrue(
                !pom.contains("<artifactId>tool-lab-" + "domain</artifactId>"),
                "tool-lab-evaluator must not declare the domain module in any scope");
        assertTrue(
                !pom.contains("<artifactId>tool-lab-" + "mcp-server</artifactId>"),
                "tool-lab-evaluator tests use local MCP stubs instead of the server module");
    }

    private boolean referencesDomain(Path path) {
        try {
            return Files.readString(path).contains(DOMAIN_PACKAGE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private Path evaluatorDir() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("src/main/java")) && cwd.endsWith("tool-lab-evaluator")) {
            return cwd;
        }
        Path fromLabRoot = cwd.resolve("tool-lab-evaluator");
        if (Files.isDirectory(fromLabRoot.resolve("src/main/java"))) {
            return fromLabRoot;
        }
        Path fromRepoRoot = cwd.resolve("mcp-tool-consistency-lab/tool-lab-evaluator");
        if (Files.isDirectory(fromRepoRoot.resolve("src/main/java"))) {
            return fromRepoRoot;
        }
        throw new IllegalStateException("Could not locate tool-lab-evaluator from " + cwd);
    }
}
