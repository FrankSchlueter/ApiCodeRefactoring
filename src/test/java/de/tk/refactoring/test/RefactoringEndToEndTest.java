package de.tk.refactoring.test;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.google.inject.Guice;
import com.google.inject.Injector;
import de.tk.refactoring.test.projectA.InternalWorkflowRunner;
import de.tk.refactoring.test.projectB.ExternalOrderClient;
import de.tk.refactoring.tool.ApiRefactoring;
import de.tk.refactoring.tool.model.ApiClassMetadata;
import de.tk.refactoring.tool.model.ModuleMetamodel;
import de.tk.util.injection.ClassGraphGuiceModule;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive verification suite for the JavaParser-based API/Implementation refactoring tool.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RefactoringEndToEndTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final Path SOURCE_TEST_ROOT = PROJECT_ROOT.resolve("src/test/java");
    private static final Path TRANSFORMED_SOURCE_ROOT = PROJECT_ROOT.resolve("transformed/java");
    private static final Path TRANSFORMED_CLASSES_DIR = PROJECT_ROOT.resolve("target/transformed-classes");
    private static final String TARGET_MODULE_PKG = "de.tk.refactoring.test.projectA";
    private static final String IMPL_SUB_PKG = "impl";

    private static ModuleMetamodel metamodel;

    @BeforeAll
    static void transformAndCompile() throws Exception {
        metamodel = ApiRefactoring.transformDirectory(
                SOURCE_TEST_ROOT,
                TRANSFORMED_SOURCE_ROOT,
                TARGET_MODULE_PKG,
                IMPL_SUB_PKG
        );
        compileTransformedSources();
    }

    @Test
    @Order(1)
    void testOriginalCodeExecutesCorrectly() {
        InternalWorkflowRunner internalRunner = new InternalWorkflowRunner();
        String internalResult = internalRunner.executeAllInternalVerifications();
        assertEquals(
                "AUDIT[DIRECT]|AUDIT[OK]/"
                        + "AUDIT[WORKFLOW-COLLAB]|INTERNAL-BASE-STATE/"
                        + "AUDIT[WORKFLOW-COLLAB]|AUDIT[WORKFLOW]|COUNTER-RESET:0",
                internalResult
        );

        ExternalOrderClient externalClient = new ExternalOrderClient();
        String externalResult = externalClient.executeExternalScenario();
        assertEquals(
                "EXT:Alice:EUR|ORD-42-EUR|EUR|BASE-100|100|INV-100",
                externalResult
        );
    }

    @Test
    @Order(2)
    void testMetamodelScannerExtractsAllMetadata() {
        assertNotNull(metamodel);
        Map<String, ApiClassMetadata> apiClasses = metamodel.apiClassesBySimpleName();

        // 1. CustomerData (isApiKonform = true)
        ApiClassMetadata customerMeta = apiClasses.get("CustomerData");
        assertNotNull(customerMeta);
        assertTrue(customerMeta.isApiKonform());

        // 2. BaseService (isApiKonform = false, no existing interface)
        ApiClassMetadata baseMeta = apiClasses.get("BaseService");
        assertNotNull(baseMeta);
        assertFalse(baseMeta.isApiKonform());
        assertTrue(baseMeta.existingApiInterfaceFqn().isEmpty());
        assertEquals(1, baseMeta.apiMethods().size());
        assertEquals("getBaseId", baseMeta.apiMethods().getFirst().name());
        assertEquals(1, baseMeta.internalMethods().size());
        assertEquals("getInternalBaseState", baseMeta.internalMethods().getFirst().name());

        // 3. OrderService (extends BaseService, has static field, static method, constructor, API & internal methods)
        ApiClassMetadata orderMeta = apiClasses.get("OrderService");
        assertNotNull(orderMeta);
        assertFalse(orderMeta.isApiKonform());
        assertTrue(orderMeta.existingApiInterfaceFqn().isEmpty());
        assertEquals("de.tk.refactoring.test.projectA.BaseService", orderMeta.superClassFqn().orElse(""));
        assertEquals(1, orderMeta.staticApiFields().size());
        assertEquals("DEFAULT_CURRENCY", orderMeta.staticApiFields().getFirst().name());
        assertEquals(1, orderMeta.staticApiMethods().size());
        assertEquals("formatOrderNumber", orderMeta.staticApiMethods().getFirst().name());
        assertEquals(1, orderMeta.apiConstructors().size());
        assertEquals(2, orderMeta.apiMethods().size());
        assertEquals(1, orderMeta.internalMethods().size());
        assertEquals("calculateInternalAuditHash", orderMeta.internalMethods().getFirst().name());

        // 4. BillingService (has existing incomplete API interface BillingServiceApi)
        ApiClassMetadata billingMeta = apiClasses.get("BillingService");
        assertNotNull(billingMeta);
        assertFalse(billingMeta.isApiKonform());
        assertEquals(
                "de.tk.refactoring.test.projectA.BillingServiceApi",
                billingMeta.existingApiInterfaceFqn().orElse("")
        );
        assertEquals(2, billingMeta.apiMethods().size());
        assertEquals(1, billingMeta.internalMethods().size());
        assertEquals("resetInternalCounter", billingMeta.internalMethods().getFirst().name());

        // 5. Internal classes are NOT in apiClasses
        assertFalse(apiClasses.containsKey("InternalAuditHelper"));
        assertFalse(apiClasses.containsKey("InternalWorkflowRunner"));

        // 6. Verify InternCastTargets (5 internal calls on API-typed references, excluding direct field)
        assertEquals(5, metamodel.internCastTargets().size());

        // 7. Verify ConstructorCallTargets (excludes directInternalService initialization in InternalWorkflowRunner)
        assertFalse(metamodel.constructorCallTargets().isEmpty());
        boolean hasExternalOrderCtor = metamodel.constructorCallTargets().stream()
                .anyMatch(t -> t.targetClassFqn().endsWith("ExternalOrderClient")
                        && t.instantiatedClassFqn().endsWith("OrderService"));
        assertTrue(hasExternalOrderCtor);
    }

    @Test
    @Order(3)
    void testTransformationStructureAndCompilationInTransformedFolder() throws IOException {
        Path apiDir = TRANSFORMED_SOURCE_ROOT.resolve("de/tk/refactoring/test/projectA");
        Path implDir = apiDir.resolve("impl");
        Path projectBDir = TRANSFORMED_SOURCE_ROOT.resolve("de/tk/refactoring/test/projectB");

        // Verify expected files in API package
        assertTrue(Files.exists(apiDir.resolve("CustomerData.java")));
        assertTrue(Files.exists(apiDir.resolve("BaseService.java")));
        assertTrue(Files.exists(apiDir.resolve("OrderService.java")));
        assertTrue(Files.exists(apiDir.resolve("OrderServiceProvider.java")));
        assertTrue(Files.exists(apiDir.resolve("BillingServiceApi.java")));
        assertTrue(Files.exists(apiDir.resolve("BillingServiceProvider.java")));

        // Verify no Impl, Intern, or internal helper classes remain in API package
        assertFalse(Files.exists(apiDir.resolve("OrderServiceImpl.java")));
        assertFalse(Files.exists(apiDir.resolve("OrderServiceIntern.java")));
        assertFalse(Files.exists(apiDir.resolve("BillingService.java")));
        assertFalse(Files.exists(apiDir.resolve("InternalAuditHelper.java")));
        assertFalse(Files.exists(apiDir.resolve("InternalWorkflowRunner.java")));

        // Verify expected files in impl package
        assertTrue(Files.exists(implDir.resolve("BaseServiceImpl.java")));
        assertTrue(Files.exists(implDir.resolve("BaseServiceIntern.java")));
        assertTrue(Files.exists(implDir.resolve("OrderServiceImpl.java")));
        assertTrue(Files.exists(implDir.resolve("OrderServiceIntern.java")));
        assertTrue(Files.exists(implDir.resolve("OrderServiceProviderImpl.java")));
        assertTrue(Files.exists(implDir.resolve("BillingService.java")));
        assertTrue(Files.exists(implDir.resolve("BillingServiceIntern.java")));
        assertTrue(Files.exists(implDir.resolve("BillingServiceProviderImpl.java")));
        assertTrue(Files.exists(implDir.resolve("InternalAuditHelper.java")));
        assertTrue(Files.exists(implDir.resolve("InternalWorkflowRunner.java")));

        // Inspect transformed InternalWorkflowRunner.java
        String workflowCode = Files.readString(implDir.resolve("InternalWorkflowRunner.java"), StandardCharsets.UTF_8);
        // Direct field uses OrderServiceImpl without cast
        assertTrue(workflowCode.contains("private final OrderServiceImpl directInternalService;"));
        assertTrue(workflowCode.contains("this.directInternalService = new OrderServiceImpl(\"DIRECT\");"));
        assertTrue(workflowCode.contains("return directInternalService.calculateInternalAuditHash()"));
        // Chained and parameter calls use ((OrderServiceIntern) ...) and ((BillingServiceIntern) ...) casts
        assertTrue(workflowCode.contains("((OrderServiceIntern) apiService.getCollaborator()).calculateInternalAuditHash()"));
        assertTrue(workflowCode.contains("((OrderServiceIntern) apiService.getCollaborator()).getInternalBaseState()"));
        assertTrue(workflowCode.contains("((OrderServiceIntern) localCollaborator).calculateInternalAuditHash()"));
        assertTrue(workflowCode.contains("((OrderServiceIntern) paramService).calculateInternalAuditHash()"));
        assertTrue(workflowCode.contains("((BillingServiceIntern) billingParam).resetInternalCounter()"));

        // Inspect transformed ExternalOrderClient.java
        String externalCode = Files.readString(projectBDir.resolve("ExternalOrderClient.java"), StandardCharsets.UTF_8);
        assertTrue(externalCode.contains("import de.tk.refactoring.test.projectA.BillingServiceApi;"));
        assertFalse(externalCode.contains("import de.tk.refactoring.test.projectA.BillingService;"));
        assertTrue(externalCode.contains("private BillingServiceApi billingField;"));
        assertTrue(externalCode.contains("public BillingServiceApi processBilling(BillingServiceApi inputBilling)"));
        assertTrue(externalCode.contains("OrderService.create(\"EXT\")"));
        assertTrue(externalCode.contains("BillingServiceApi.create()"));

        // Verify original source in src/test/java was not modified
        String origExternalCode = Files.readString(
                SOURCE_TEST_ROOT.resolve("de/tk/refactoring/test/projectB/ExternalOrderClient.java"),
                StandardCharsets.UTF_8
        );
        assertTrue(origExternalCode.contains("new OrderService(\"EXT\")"));
        assertTrue(origExternalCode.contains("private BillingService billingField;"));
    }

    @Test
    @Order(4)
    void testTransformedCodeRuntimeWithGuice() throws Exception {
        try (URLClassLoader transformedLoader = createParentLastClassLoader()) {
            Injector injector = Guice.createInjector(
                    new ClassGraphGuiceModule(transformedLoader, TARGET_MODULE_PKG)
            );
            assertNotNull(injector);

            // 1. Run InternalWorkflowRunner from transformed projectA.impl
            Class<?> runnerClass = Class.forName(
                    "de.tk.refactoring.test.projectA.impl.InternalWorkflowRunner",
                    true,
                    transformedLoader
            );
            Object runnerInstance = runnerClass.getDeclaredConstructor().newInstance();
            Method internalVerifyMethod = runnerClass.getMethod("executeAllInternalVerifications");
            String transformedInternalResult = (String) internalVerifyMethod.invoke(runnerInstance);

            InternalWorkflowRunner origRunner = new InternalWorkflowRunner();
            assertEquals(origRunner.executeAllInternalVerifications(), transformedInternalResult);

            // 2. Run ExternalOrderClient from transformed projectB
            Class<?> clientClass = Class.forName(
                    "de.tk.refactoring.test.projectB.ExternalOrderClient",
                    true,
                    transformedLoader
            );
            Object clientInstance = clientClass.getDeclaredConstructor().newInstance();
            Method externalVerifyMethod = clientClass.getMethod("executeExternalScenario");
            String transformedExternalResult = (String) externalVerifyMethod.invoke(clientInstance);

            ExternalOrderClient origClient = new ExternalOrderClient();
            assertEquals(origClient.executeExternalScenario(), transformedExternalResult);
        }
    }

    @Test
    @Order(5)
    void testArchUnitApiHasNoDependencyOnImplPackage() throws Exception {
        try (URLClassLoader transformedLoader = createParentLastClassLoader();
             ScanResult scanResult = new ClassGraph()
                     .overrideClassLoaders(transformedLoader)
                     .enableAllInfo()
                     .enableInterClassDependencies()
                     .acceptPackages("de.tk.refactoring.test")
                     .scan()) {

            List<ClassInfo> apiPackageClasses = scanResult.getAllClasses().stream()
                    .filter(ci -> ci.getPackageName().equals(TARGET_MODULE_PKG))
                    .toList();

            assertFalse(apiPackageClasses.isEmpty(), "API package must contain generated interfaces and classes");

            for (ClassInfo apiClass : apiPackageClasses) {
                assertFalse(
                        apiClass.getSimpleName().endsWith("Impl") || apiClass.getSimpleName().endsWith("Intern"),
                        "No *Impl or *Intern type is allowed in the API package: " + apiClass.getName()
                );

                for (ClassInfo dep : apiClass.getClassDependencies()) {
                    assertFalse(
                            dep.getPackageName().equals(TARGET_MODULE_PKG + ".impl")
                                    || dep.getPackageName().startsWith(TARGET_MODULE_PKG + ".impl."),
                            () -> "Architecture violation: API class " + apiClass.getName()
                                    + " depends on implementation class " + dep.getName()
                    );
                }
            }

            // Also verify external module (projectB) has zero dependencies on projectA.impl
            List<ClassInfo> projectBClasses = scanResult.getAllClasses().stream()
                    .filter(ci -> ci.getPackageName().equals("de.tk.refactoring.test.projectB"))
                    .toList();
            assertFalse(projectBClasses.isEmpty());

            for (ClassInfo bClass : projectBClasses) {
                for (ClassInfo dep : bClass.getClassDependencies()) {
                    assertFalse(
                            dep.getPackageName().equals(TARGET_MODULE_PKG + ".impl")
                                    || dep.getPackageName().startsWith(TARGET_MODULE_PKG + ".impl."),
                            () -> "Architecture violation: External class " + bClass.getName()
                                    + " depends on implementation class " + dep.getName()
                    );
                }
            }
        }
    }

    @Test
    @Order(6)
    void testSingleArgumentReplaceImplUsageEntryPoint() throws IOException {
        Path clientPath = SOURCE_TEST_ROOT.resolve("de/tk/refactoring/test/projectB/ExternalOrderClient.java");
        CompilationUnit cu = StaticJavaParser.parse(Files.readString(clientPath, StandardCharsets.UTF_8));
        ApiRefactoring.setActiveMetamodel(metamodel);
        ApiRefactoring.replaceImplUsage(cu);
        String result = cu.toString();
        assertTrue(result.contains("OrderService.create(\"EXT\")"));
        assertTrue(result.contains("BillingServiceApi"));
    }

    private static void compileTransformedSources() throws IOException {
        if (Files.exists(TRANSFORMED_CLASSES_DIR)) {
            try (Stream<Path> walk = Files.walk(TRANSFORMED_CLASSES_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
        Files.createDirectories(TRANSFORMED_CLASSES_DIR);

        List<File> sourceFiles;
        try (Stream<Path> walk = Files.walk(TRANSFORMED_SOURCE_ROOT)) {
            sourceFiles = walk
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .toList();
        }
        assertFalse(sourceFiles.isEmpty(), "transformed/java must contain generated source files");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK JavaCompiler must be available");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            String classpath = System.getProperty("java.class.path");
            List<String> options = List.of(
                    "--release", "21",
                    "-classpath", classpath,
                    "-d", TRANSFORMED_CLASSES_DIR.toString()
            );

            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits).call();
            if (!Boolean.TRUE.equals(success)) {
                StringBuilder err = new StringBuilder("Compilation of transformed/java failed:\n");
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    err.append(d.getKind()).append(": ")
                            .append(d.getSource() != null ? d.getSource().getName() : "<unknown>")
                            .append(" line ").append(d.getLineNumber())
                            .append(": ").append(d.getMessage(null)).append("\n");
                }
                throw new AssertionError(err.toString());
            }
        }
    }

    private static URLClassLoader createParentLastClassLoader() throws Exception {
        URL transformedUrl = TRANSFORMED_CLASSES_DIR.toUri().toURL();
        ClassLoader parent = RefactoringEndToEndTest.class.getClassLoader();
        return new URLClassLoader(new URL[]{transformedUrl}, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("de.tk.refactoring.test.projectA.")
                        || name.startsWith("de.tk.refactoring.test.projectB.")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            c = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(c);
                        }
                        return c;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }
}
