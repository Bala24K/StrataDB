import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Build {
    public static void main(String[] args) throws Exception {
        String projectDir = args[0];
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("No system compiler available in this JVM.");
            System.exit(1);
        }

        List<File> mainSources = collect(projectDir + "/src/main/java");
        List<File> testSources = collect(projectDir + "/src/test/java");
        File outMain = new File(projectDir + "/out/classes");
        File outTest = new File(projectDir + "/out/test-classes");
        outMain.mkdirs();
        outTest.mkdirs();

        System.out.println("Compiling " + mainSources.size() + " main source file(s)...");
        if (compileAll(compiler, mainSources, outMain, null) != 0) System.exit(1);

        System.out.println("Compiling " + testSources.size() + " test source file(s)...");
        if (compileAll(compiler, testSources, outTest, outMain) != 0) System.exit(1);

        System.out.println("BUILD OK");
    }

    static int compileAll(JavaCompiler compiler, List<File> sources, File outDir, File classpathDir) {
        List<String> options = new ArrayList<>();
        options.add("-d");
        options.add(outDir.getPath());
        if (classpathDir != null) {
            options.add("-cp");
            options.add(classpathDir.getPath());
        }
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);
        StringWriter diagnostics = new StringWriter();
        boolean ok = compiler.getTask(diagnostics, fm, null, options, null, units).call();
        String out = diagnostics.toString();
        if (!out.isBlank()) System.out.print(out);
        return ok ? 0 : 1;
    }

    static List<File> collect(String dir) throws IOException {
        List<File> files = new ArrayList<>();
        Path root = Paths.get(dir);
        if (!Files.exists(root)) return files;
        Files.walk(root).filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
        return files;
    }
}
