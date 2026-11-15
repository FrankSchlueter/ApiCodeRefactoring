package de.tk.refactoring.tool;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import de.tk.refactoring.tool.model.ApiClassMetadata;
import de.tk.refactoring.tool.model.ConstructorSignature;
import de.tk.refactoring.tool.model.FieldMetadata;
import de.tk.refactoring.tool.model.MethodSignature;
import de.tk.refactoring.tool.model.ModuleMetamodel;
import de.tk.refactoring.tool.scanner.ApiAnnotationScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Main entry point for the JavaParser-based API/Implementation refactoring tool.
 */
public class ApiRefactoring {

    private static volatile ModuleMetamodel activeMetamodel;

    public static void setActiveMetamodel(ModuleMetamodel metamodel) {
        activeMetamodel = metamodel;
    }

    /**
     * Single-argument entry point as specified in the refactoring requirements.
     * Uses the currently configured {@link ModuleMetamodel} or scans the given compilation unit.
     */
    public static void replaceImplUsage(CompilationUnit implClass) {
        ModuleMetamodel model = activeMetamodel;
        if (model == null) {
            String pkg = implClass.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            model = ApiAnnotationScanner.scan(List.of(implClass), pkg, "impl");
        }
        replaceImplUsage(implClass, model);
    }

    /**
     * Applies in-place type, cast, and constructor replacements on a single {@link CompilationUnit}.
     */
    public static void replaceImplUsage(CompilationUnit cu, ModuleMetamodel metamodel) {
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        boolean isTargetModule = pkg.equals(metamodel.targetModulePackage())
                || pkg.startsWith(metamodel.targetModulePackage() + ".");

        Map<String, ApiClassMetadata> bySimple = metamodel.apiClassesBySimpleName();

        // Pre-record direct Impl fields before CastAndConstructorInjector runs
        Map<ClassOrInterfaceDeclaration, Set<String>> directFieldsByClass = new LinkedHashMap<>();
        if (isTargetModule) {
            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!decl.isNestedType() && !decl.isInterface()) {
                    directFieldsByClass.put(decl, ApiAnnotationScanner.findDirectImplFields(decl, bySimple));
                }
            }
        }

        // 1. Run line-number based cast and constructor transformations first on original line numbers
        CastAndConstructorInjector.applyTransformations(cu, metamodel);

        // 2. Replace types for classes that have an existingApiInterfaceFqn
        if (!isTargetModule) {
            replaceExternalTypeReferences(cu, metamodel);
        } else {
            for (Map.Entry<ClassOrInterfaceDeclaration, Set<String>> entry : directFieldsByClass.entrySet()) {
                rewriteDirectImplFields(entry.getKey(), entry.getValue(), bySimple);
            }
            replaceInternalApiSignatureTypes(cu, metamodel);
        }
    }

    /**
     * Transforms an entire set of module and caller {@link CompilationUnit}s according to the metamodel,
     * returning all modified and newly generated {@link CompilationUnit}s.
     */
    public static List<CompilationUnit> transformProject(
            List<CompilationUnit> sourceUnits,
            ModuleMetamodel metamodel) {

        String targetPkg = metamodel.targetModulePackage();
        String implPkg = metamodel.implPackageFqn();
        Map<String, ApiClassMetadata> byFqn = metamodel.apiClassesByFqn();
        Map<String, ApiClassMetadata> bySimple = metamodel.apiClassesBySimpleName();

        // Collect all types that will reside in the API package after transformation
        Set<String> apiPackageTypeSimpleNames = new LinkedHashSet<>();
        for (ApiClassMetadata meta : metamodel.apiClasses()) {
            apiPackageTypeSimpleNames.add(meta.effectiveApiInterfaceSimpleName());
            if (meta.requiresProvider()) {
                apiPackageTypeSimpleNames.add(meta.providerInterfaceSimpleName());
            }
        }

        // Map existing API interfaces in targetPkg -> their CompilationUnit
        Map<String, CompilationUnit> existingInterfaceUnits = new LinkedHashMap<>();
        for (CompilationUnit cu : sourceUnits) {
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            if (pkg.equals(targetPkg)) {
                for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    if (!decl.isNestedType() && decl.isInterface()) {
                        String fqn = pkg + "." + decl.getNameAsString();
                        existingInterfaceUnits.put(fqn, cu);
                        apiPackageTypeSimpleNames.add(decl.getNameAsString());
                    }
                }
            }
        }

        List<CompilationUnit> resultUnits = new ArrayList<>();

        for (CompilationUnit cu : sourceUnits) {
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            boolean isInTargetModule = pkg.equals(targetPkg) || pkg.startsWith(targetPkg + ".");

            ClassOrInterfaceDeclaration primaryType = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(d -> !d.isNestedType())
                    .findFirst()
                    .orElse(null);

            if (primaryType == null) {
                resultUnits.add(cu);
                continue;
            }

            String primaryFqn = pkg.isEmpty()
                    ? primaryType.getNameAsString()
                    : pkg + "." + primaryType.getNameAsString();

            if (!isInTargetModule) {
                // External caller module (e.g., projectB)
                LexicalPreservingPrinter.setup(cu);
                CastAndConstructorInjector.applyTransformations(cu, metamodel);
                replaceExternalTypeReferences(cu, metamodel);
                resultUnits.add(cu);
                continue;
            }

            if (primaryType.isInterface()) {
                // Existing interface in target module (may need missing API methods/statics/constructors added)
                augmentExistingApiInterfaceIfNeeded(cu, primaryType, primaryFqn, metamodel);
                resultUnits.add(cu);
                continue;
            }

            ApiClassMetadata meta = byFqn.get(primaryFqn);

            if (meta != null && meta.isApiKonform()) {
                // Case 1: API-conformant class -> stays in API package as-is
                resultUnits.add(cu);
                continue;
            }

            // Record direct Impl fields BEFORE line-number constructor replacement runs
            Set<String> directImplFields = ApiAnnotationScanner.findDirectImplFields(primaryType, bySimple);

            // Apply line-number-based Intern casts and constructor replacements on original AST coordinates
            CastAndConstructorInjector.applyTransformations(cu, metamodel);

            // Rewrite direct internal Impl fields (e.g. OrderService -> OrderServiceImpl, new OrderService -> new OrderServiceImpl)
            rewriteDirectImplFields(primaryType, directImplFields, bySimple);

            // Replace references to classes that have an existingApiInterfaceFqn (e.g. BillingService -> BillingServiceApi)
            replaceInternalApiSignatureTypes(cu, metamodel);

            if (meta != null) {
                // Case 2 or Case 3: Non-API-conformant API class
                if (meta.existingApiInterfaceFqn().isEmpty()) {
                    // Case 2: Generate new API interface under the exact original FQN
                    CompilationUnit apiInterfaceCu = generateApiInterfaceUnit(meta, metamodel);
                    resultUnits.add(apiInterfaceCu);
                }

                if (meta.requiresProvider()) {
                    // Generate <Name>Provider in API package and <Name>ProviderImpl in impl package
                    resultUnits.add(generateProviderInterfaceUnit(meta, metamodel));
                    resultUnits.add(generateProviderImplUnit(meta, metamodel, apiPackageTypeSimpleNames));
                }

                // Generate <Name>Intern interface in impl package
                resultUnits.add(generateInternInterfaceUnit(meta, metamodel, apiPackageTypeSimpleNames));

                // Transform implementation class itself into impl package
                transformImplementationClassUnit(cu, primaryType, meta, metamodel, apiPackageTypeSimpleNames);
                resultUnits.add(cu);
            } else {
                // Case 4: Purely internal class -> move to impl package and add imports for API-package types
                moveInternalClassToImplPackage(cu, primaryType, metamodel, apiPackageTypeSimpleNames);
                resultUnits.add(cu);
            }
        }

        return resultUnits;
    }

    /**
     * Parses all Java files under {@code sourceRoot}, builds the {@link ModuleMetamodel} via
     * {@link ApiAnnotationScanner}, transforms the project, and writes all resulting files into {@code targetRoot}.
     */
    public static ModuleMetamodel transformDirectory(
            Path sourceRoot,
            Path targetRoot,
            String targetModulePackage,
            String implSubPackage) throws IOException {

        List<CompilationUnit> parsedUnits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            List<Path> javaFiles = walk
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path file : javaFiles) {
                String rel = sourceRoot.relativize(file).toString().replace('\\', '/');
                if (!rel.startsWith("de/tk/refactoring/test/projectA/")
                        && !rel.startsWith("de/tk/refactoring/test/projectB/")) {
                    continue;
                }
                parsedUnits.add(StaticJavaParser.parse(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }

        ModuleMetamodel metamodel = ApiAnnotationScanner.scan(parsedUnits, targetModulePackage, implSubPackage);
        activeMetamodel = metamodel;

        List<CompilationUnit> transformedUnits = transformProject(parsedUnits, metamodel);

        if (Files.exists(targetRoot)) {
            try (Stream<Path> walk = Files.walk(targetRoot)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        for (CompilationUnit unit : transformedUnits) {
            String pkg = unit.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            String typeName = unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(d -> !d.isNestedType())
                    .map(ClassOrInterfaceDeclaration::getNameAsString)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("CompilationUnit without top-level type"));

            Path outDir = pkg.isEmpty()
                    ? targetRoot
                    : targetRoot.resolve(pkg.replace('.', '/'));
            Files.createDirectories(outDir);
            Path outFile = outDir.resolve(typeName + ".java");
            Files.writeString(outFile, printUnit(unit), StandardCharsets.UTF_8);
        }

        return metamodel;
    }

    private static String printUnit(CompilationUnit unit) {
        try {
            return LexicalPreservingPrinter.print(unit);
        } catch (Exception e) {
            return unit.toString();
        }
    }

    private static void augmentExistingApiInterfaceIfNeeded(
            CompilationUnit cu,
            ClassOrInterfaceDeclaration ifaceDecl,
            String ifaceFqn,
            ModuleMetamodel metamodel) {

        Map<String, ApiClassMetadata> bySimple = metamodel.apiClassesBySimpleName();
        for (ApiClassMetadata meta : metamodel.apiClasses()) {
            if (meta.existingApiInterfaceFqn().isPresent()
                    && meta.existingApiInterfaceFqn().get().equals(ifaceFqn)) {

                Set<String> existingMethodNames = new LinkedHashSet<>();
                for (MethodDeclaration m : ifaceDecl.getMethods()) {
                    existingMethodNames.add(m.getNameAsString() + "/" + m.getParameters().size());
                }

                for (FieldMetadata field : meta.staticApiFields()) {
                    if (ifaceDecl.getFieldByName(field.name()).isEmpty()) {
                        String fieldCode = "public static final " + mapTypeString(field.type(), bySimple)
                                + " " + field.name() + " = " + field.initializerExpr() + ";";
                        ifaceDecl.addMember(StaticJavaParser.parseBodyDeclaration(fieldCode));
                    }
                }

                for (MethodSignature ms : meta.apiMethods()) {
                    String key = ms.name() + "/" + ms.parameters().size();
                    if (!existingMethodNames.contains(key)) {
                        ifaceDecl.addMember(StaticJavaParser.parseBodyDeclaration(
                                buildInterfaceMethodDeclaration(ms, bySimple)));
                    }
                }

                if (meta.requiresProvider()) {
                    appendProviderHolderAndStatics(ifaceDecl, meta, bySimple);
                }
            }
        }
    }

    private static CompilationUnit generateApiInterfaceUnit(
            ApiClassMetadata meta,
            ModuleMetamodel metamodel) {

        Map<String, ApiClassMetadata> bySimple = metamodel.apiClassesBySimpleName();
        Map<String, ApiClassMetadata> byFqn = metamodel.apiClassesByFqn();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(meta.packageName()).append(";\n\n");

        List<String> extendsList = new ArrayList<>();
        if (meta.superClassFqn().isPresent()) {
            ApiClassMetadata superMeta = byFqn.get(meta.superClassFqn().get());
            if (superMeta != null) {
                extendsList.add(superMeta.effectiveApiInterfaceSimpleName());
            }
        }
        for (String iface : meta.implementedInterfaces()) {
            // Only extend external or API interfaces
            extendsList.add(iface);
        }

        sb.append("public interface ").append(meta.simpleName());
        if (!extendsList.isEmpty()) {
            sb.append(" extends ").append(String.join(", ", extendsList));
        }
        sb.append(" {\n\n");

        // Public static final constants
        for (FieldMetadata field : meta.staticApiFields()) {
            sb.append("    public static final ")
                    .append(mapTypeString(field.type(), bySimple))
                    .append(" ")
                    .append(field.name())
                    .append(" = ")
                    .append(field.initializerExpr())
                    .append(";\n\n");
        }

        // Instance API methods
        for (MethodSignature ms : meta.apiMethods()) {
            sb.append("    ").append(buildInterfaceMethodDeclaration(ms, bySimple)).append("\n\n");
        }

        sb.append("}\n");

        CompilationUnit cu = StaticJavaParser.parse(sb.toString());
        ClassOrInterfaceDeclaration ifaceDecl = cu.getInterfaceByName(meta.simpleName()).orElseThrow();
        if (meta.requiresProvider()) {
            appendProviderHolderAndStatics(ifaceDecl, meta, bySimple);
        }
        return cu;
    }

    private static void appendProviderHolderAndStatics(
            ClassOrInterfaceDeclaration ifaceDecl,
            ApiClassMetadata meta,
            Map<String, ApiClassMetadata> bySimple) {

        String providerSimple = meta.providerInterfaceSimpleName();
        String apiSimple = meta.effectiveApiInterfaceSimpleName();

        String holderCode = """
                class ProviderHolder {
                    @jakarta.inject.Inject
                    public static %s provider;
                }
                """.formatted(providerSimple);
        ifaceDecl.addMember(StaticJavaParser.parseBodyDeclaration(holderCode));

        for (ConstructorSignature ctor : meta.apiConstructors()) {
            String paramsDecl = formatParameters(ctor.parameters(), bySimple);
            String argsCall = formatArgumentNames(ctor.parameters());
            String createMethod = """
                    static %s create(%s) {
                        return ProviderHolder.provider.create(%s);
                    }
                    """.formatted(apiSimple, paramsDecl, argsCall);
            ifaceDecl.addMember(StaticJavaParser.parseBodyDeclaration(createMethod));
        }

        for (MethodSignature staticMs : meta.staticApiMethods()) {
            String retType = mapTypeString(staticMs.returnType(), bySimple);
            String paramsDecl = formatParameters(staticMs.parameters(), bySimple);
            String argsCall = formatArgumentNames(staticMs.parameters());
            String returnPrefix = retType.equals("void") ? "" : "return ";
            String wrapperMethod = """
                    static %s %s(%s) {
                        %sProviderHolder.provider.%s(%s);
                    }
                    """.formatted(retType, staticMs.name(), paramsDecl, returnPrefix, staticMs.name(), argsCall);
            ifaceDecl.addMember(StaticJavaParser.parseBodyDeclaration(wrapperMethod));
        }
    }

    private static CompilationUnit generateProviderInterfaceUnit(
            ApiClassMetadata meta,
            ModuleMetamodel metamodel) {

        Map<String, ApiClassMetadata> bySimple = metamodel.apiClassesBySimpleName();
        String apiSimple = meta.effectiveApiInterfaceSimpleName();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(meta.packageName()).append(";\n\n");
        sb.append("public interface ").append(meta.providerInterfaceSimpleName()).append(" {\n\n");

        for (ConstructorSignature ctor : meta.apiConstructors()) {
            String paramsDecl = formatParameters(ctor.parameters(), bySimple);
            sb.append("    ").append(apiSimple).append(" create(").append(paramsDecl).append(");\n\n");
        }

        for (MethodSignature staticMs : meta.staticApiMethods()) {
            String retType = mapTypeString(staticMs.returnType(), bySimple);
            String paramsDecl = formatParameters(staticMs.parameters(), bySimple);
            sb.append("    ").append(retType).append(" ").append(staticMs.name())
                    .append("(").append(paramsDecl).append(");\n\n");
        }

        sb.append("}\n");
        return StaticJavaParser.parse(sb.toString());
    }

    private static CompilationUnit generateProviderImplUnit(
            ApiClassMetadata meta,
            ModuleMetamodel metamodel,
            Set<String> apiPackageTypeSimpleNames) {

        Map<String, ApiClassMetadata> bySimple = metamodel.apiClassesBySimpleName();
        String implPkg = metamodel.implPackageFqn();
        String apiPkg = metamodel.targetModulePackage();
        String apiSimple = meta.effectiveApiInterfaceSimpleName();
        String implSimple = meta.effectiveImplSimpleName();
        String providerSimple = meta.providerInterfaceSimpleName();
        String providerImplSimple = meta.providerImplSimpleName();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(implPkg).append(";\n\n");
        sb.append("import de.tk.util.injection.ImplementationClass;\n");
        sb.append("import jakarta.inject.Inject;\n");
        sb.append("import ").append(apiPkg).append(".").append(providerSimple).append(";\n");
        sb.append("import ").append(apiPkg).append(".").append(apiSimple).append(";\n\n");

        sb.append("@ImplementationClass\n");
        sb.append("public class ").append(providerImplSimple)
                .append(" implements ").append(providerSimple).append(" {\n\n");

        sb.append("    @ImplementationClass\n");
        sb.append("    @Inject\n");
        sb.append("    public ").append(providerImplSimple).append("() {\n");
        sb.append("    }\n\n");

        for (ConstructorSignature ctor : meta.apiConstructors()) {
            String paramsDecl = formatParameters(ctor.parameters(), bySimple);
            String argsCall = formatArgumentNames(ctor.parameters());
            sb.append("    @Override\n");
            sb.append("    public ").append(apiSimple).append(" create(").append(paramsDecl).append(") {\n");
            sb.append("        return new ").append(implSimple).append("(").append(argsCall).append(");\n");
            sb.append("    }\n\n");
        }

        for (MethodSignature staticMs : meta.staticApiMethods()) {
            String retType = mapTypeString(staticMs.returnType(), bySimple);
            String paramsDecl = formatParameters(staticMs.parameters(), bySimple);
            String argsCall = formatArgumentNames(staticMs.parameters());
            String returnPrefix = retType.equals("void") ? "" : "return ";
            sb.append("    @Override\n");
            sb.append("    public ").append(retType).append(" ").append(staticMs.name())
                    .append("(").append(paramsDecl).append(") {\n");
            sb.append("        ").append(returnPrefix).append(implSimple).append(".")
                    .append(staticMs.name()).append("(").append(argsCall).append(");\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");

        CompilationUnit cu = StaticJavaParser.parse(sb.toString());
        addImportsForApiPackageTypes(cu, apiPkg, apiPackageTypeSimpleNames);
        return cu;
    }

    private static CompilationUnit generateInternInterfaceUnit(
            ApiClassMetadata meta,
            ModuleMetamodel metamodel,
            Set<String> apiPackageTypeSimpleNames) {

        Map<String, ApiClassMetadata> bySimple = metamodel.apiClassesBySimpleName();
        Map<String, ApiClassMetadata> byFqn = metamodel.apiClassesByFqn();
        String implPkg = metamodel.implPackageFqn();
        String apiPkg = metamodel.targetModulePackage();
        String apiSimple = meta.effectiveApiInterfaceSimpleName();
        String internSimple = meta.internInterfaceSimpleName();

        List<String> extendsTypes = new ArrayList<>();
        extendsTypes.add(apiSimple);
        if (meta.superClassFqn().isPresent()) {
            ApiClassMetadata superMeta = byFqn.get(meta.superClassFqn().get());
            if (superMeta != null) {
                extendsTypes.add(superMeta.internInterfaceSimpleName());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(implPkg).append(";\n\n");
        sb.append("import ").append(apiPkg).append(".").append(apiSimple).append(";\n\n");
        sb.append("public interface ").append(internSimple)
                .append(" extends ").append(String.join(", ", extendsTypes)).append(" {\n\n");

        for (MethodSignature ms : meta.internalMethods()) {
            sb.append("    ").append(buildInterfaceMethodDeclaration(ms, bySimple)).append("\n\n");
        }

        sb.append("}\n");

        CompilationUnit cu = StaticJavaParser.parse(sb.toString());
        addImportsForApiPackageTypes(cu, apiPkg, apiPackageTypeSimpleNames);
        return cu;
    }

    private static void transformImplementationClassUnit(
            CompilationUnit cu,
            ClassOrInterfaceDeclaration cls,
            ApiClassMetadata meta,
            ModuleMetamodel metamodel,
            Set<String> apiPackageTypeSimpleNames) {

        String implPkg = metamodel.implPackageFqn();
        String apiPkg = metamodel.targetModulePackage();
        Map<String, ApiClassMetadata> byFqn = metamodel.apiClassesByFqn();

        // 1. Move package to implPkg
        cu.setPackageDeclaration(implPkg);

        // 2. Rename class and its constructors if needed (Case 2: <Name> -> <Name>Impl)
        String newImplSimpleName = meta.effectiveImplSimpleName();
        cls.setName(newImplSimpleName);
        for (ConstructorDeclaration ctor : cls.getConstructors()) {
            ctor.setName(newImplSimpleName);
        }

        // 3. Update superclass to <Super>Impl if superclass is a refactored class in the module
        if (meta.superClassFqn().isPresent()) {
            ApiClassMetadata superMeta = byFqn.get(meta.superClassFqn().get());
            if (superMeta != null && !superMeta.isApiKonform()) {
                NodeList<ClassOrInterfaceType> ext = cls.getExtendedTypes();
                if (!ext.isEmpty()) {
                    ext.set(0, StaticJavaParser.parseClassOrInterfaceType(superMeta.effectiveImplSimpleName()));
                }
            }
        }

        // 4. Implement <Name>Intern (which extends <effectiveApiInterfaceSimpleName>)
        NodeList<ClassOrInterfaceType> implemented = cls.getImplementedTypes();
        implemented.removeIf(t -> t.getNameAsString().equals(meta.effectiveApiInterfaceSimpleName()));
        boolean alreadyImplementsIntern = implemented.stream()
                .anyMatch(t -> t.getNameAsString().equals(meta.internInterfaceSimpleName()));
        if (!alreadyImplementsIntern) {
            implemented.add(StaticJavaParser.parseClassOrInterfaceType(meta.internInterfaceSimpleName()));
        }

        // 5. Remove public static final fields that were moved to the API interface
        Set<String> movedFieldNames = new LinkedHashSet<>();
        for (FieldMetadata fm : meta.staticApiFields()) {
            movedFieldNames.add(fm.name());
        }
        List<FieldDeclaration> fieldsToRemove = new ArrayList<>();
        for (FieldDeclaration fd : cls.getFields()) {
            if (fd.isStatic()) {
                fd.getVariables().removeIf(v -> movedFieldNames.contains(v.getNameAsString()));
                if (fd.getVariables().isEmpty()) {
                    fieldsToRemove.add(fd);
                }
            }
        }
        fieldsToRemove.forEach(FieldDeclaration::remove);

        // 6. Add imports for all referenced API-package types
        cu.addImport(apiPkg + "." + meta.effectiveApiInterfaceSimpleName());
        addImportsForApiPackageTypes(cu, apiPkg, apiPackageTypeSimpleNames);
    }

    private static void moveInternalClassToImplPackage(
            CompilationUnit cu,
            ClassOrInterfaceDeclaration cls,
            ModuleMetamodel metamodel,
            Set<String> apiPackageTypeSimpleNames) {

        String implPkg = metamodel.implPackageFqn();
        String apiPkg = metamodel.targetModulePackage();
        cu.setPackageDeclaration(implPkg);
        addImportsForApiPackageTypes(cu, apiPkg, apiPackageTypeSimpleNames);
    }

    private static void rewriteDirectImplFields(
            ClassOrInterfaceDeclaration typeDecl,
            Set<String> directImplFields,
            Map<String, ApiClassMetadata> bySimple) {

        if (directImplFields.isEmpty()) {
            return;
        }

        Map<String, ApiClassMetadata> fieldToMeta = new LinkedHashMap<>();
        for (FieldDeclaration fd : typeDecl.getFields()) {
            String fieldType = fd.getElementType().asString();
            ApiClassMetadata meta = bySimple.get(fieldType);
            if (meta != null) {
                boolean matchesDirect = fd.getVariables().stream()
                        .anyMatch(v -> directImplFields.contains(v.getNameAsString()));
                if (matchesDirect) {
                    fd.setAllTypes(StaticJavaParser.parseClassOrInterfaceType(meta.effectiveImplSimpleName()));
                    for (VariableDeclarator vd : fd.getVariables()) {
                        if (directImplFields.contains(vd.getNameAsString())) {
                            fieldToMeta.put(vd.getNameAsString(), meta);
                            if (vd.getInitializer().isPresent()
                                    && vd.getInitializer().get() instanceof ObjectCreationExpr oce) {
                                oce.setType(StaticJavaParser.parseClassOrInterfaceType(meta.effectiveImplSimpleName()));
                            }
                        }
                    }
                }
            }
        }

        for (ConstructorDeclaration ctor : typeDecl.getConstructors()) {
            for (AssignExpr assign : ctor.findAll(AssignExpr.class)) {
                if (assign.getValue() instanceof ObjectCreationExpr oce) {
                    String targetField = extractFieldName(assign.getTarget());
                    if (targetField != null && fieldToMeta.containsKey(targetField)) {
                        ApiClassMetadata meta = fieldToMeta.get(targetField);
                        oce.setType(StaticJavaParser.parseClassOrInterfaceType(meta.effectiveImplSimpleName()));
                    }
                }
            }
        }
    }

    private static String extractFieldName(Expression expr) {
        if (expr.isNameExpr()) {
            return expr.asNameExpr().getNameAsString();
        }
        if (expr.isFieldAccessExpr() && expr.asFieldAccessExpr().getScope().isThisExpr()) {
            return expr.asFieldAccessExpr().getNameAsString();
        }
        return null;
    }

    private static void replaceInternalApiSignatureTypes(CompilationUnit cu, ModuleMetamodel metamodel) {
        Map<String, ApiClassMetadata> bySimple = metamodel.apiClassesBySimpleName();
        replaceTypesInAst(cu, bySimple);
    }

    private static void replaceExternalTypeReferences(CompilationUnit cu, ModuleMetamodel metamodel) {
        Map<String, ApiClassMetadata> bySimple = metamodel.apiClassesBySimpleName();
        Map<String, ApiClassMetadata> byFqn = metamodel.apiClassesByFqn();

        replaceTypesInAst(cu, bySimple);

        for (ImportDeclaration imp : new ArrayList<>(cu.getImports())) {
            String importedFqn = imp.getNameAsString();
            ApiClassMetadata meta = byFqn.get(importedFqn);
            if (meta != null && !meta.isApiKonform()) {
                imp.remove();
                cu.addImport(meta.effectiveApiInterfaceFqn());
            }
        }
    }

    private static void replaceTypesInAst(CompilationUnit cu, Map<String, ApiClassMetadata> bySimple) {
        for (VariableDeclarator vd : cu.findAll(VariableDeclarator.class)) {
            ApiClassMetadata meta = bySimple.get(vd.getType().asString());
            if (meta != null && !meta.isApiKonform() && meta.existingApiInterfaceFqn().isPresent()) {
                vd.setType(StaticJavaParser.parseClassOrInterfaceType(meta.effectiveApiInterfaceSimpleName()));
            }
        }
        for (com.github.javaparser.ast.body.Parameter param : cu.findAll(com.github.javaparser.ast.body.Parameter.class)) {
            ApiClassMetadata meta = bySimple.get(param.getType().asString());
            if (meta != null && !meta.isApiKonform() && meta.existingApiInterfaceFqn().isPresent()) {
                param.setType(StaticJavaParser.parseClassOrInterfaceType(meta.effectiveApiInterfaceSimpleName()));
            }
        }
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            ApiClassMetadata meta = bySimple.get(md.getType().asString());
            if (meta != null && !meta.isApiKonform() && meta.existingApiInterfaceFqn().isPresent()) {
                md.setType(StaticJavaParser.parseClassOrInterfaceType(meta.effectiveApiInterfaceSimpleName()));
            }
        }
        for (ClassOrInterfaceType typeNode : cu.findAll(ClassOrInterfaceType.class)) {
            if (typeNode.getParentNode().orElse(null) instanceof ObjectCreationExpr) {
                continue;
            }
            ApiClassMetadata meta = bySimple.get(typeNode.getNameAsString());
            if (meta != null && !meta.isApiKonform() && meta.existingApiInterfaceFqn().isPresent()) {
                typeNode.setName(meta.effectiveApiInterfaceSimpleName());
            }
        }
    }

    private static void addImportsForApiPackageTypes(
            CompilationUnit cu,
            String apiPkg,
            Set<String> apiPackageTypeSimpleNames) {

        Set<String> referencedSimpleNames = new LinkedHashSet<>();
        cu.findAll(ClassOrInterfaceType.class).forEach(t -> referencedSimpleNames.add(t.getNameAsString()));
        cu.findAll(com.github.javaparser.ast.expr.NameExpr.class)
                .forEach(n -> referencedSimpleNames.add(n.getNameAsString()));

        for (String apiSimple : apiPackageTypeSimpleNames) {
            if (referencedSimpleNames.contains(apiSimple)) {
                cu.addImport(apiPkg + "." + apiSimple);
            }
        }
    }

    private static String buildInterfaceMethodDeclaration(
            MethodSignature ms,
            Map<String, ApiClassMetadata> bySimple) {

        StringBuilder sb = new StringBuilder();
        if (!ms.typeParameters().isEmpty()) {
            sb.append("<").append(String.join(", ", ms.typeParameters())).append("> ");
        }
        sb.append(mapTypeString(ms.returnType(), bySimple))
                .append(" ")
                .append(ms.name())
                .append("(")
                .append(formatParameters(ms.parameters(), bySimple))
                .append(")");
        if (!ms.thrownExceptions().isEmpty()) {
            sb.append(" throws ").append(String.join(", ", ms.thrownExceptions()));
        }
        sb.append(";");
        return sb.toString();
    }

    private static String formatParameters(
            List<MethodSignature.ParameterSpec> params,
            Map<String, ApiClassMetadata> bySimple) {
        List<String> parts = new ArrayList<>();
        for (MethodSignature.ParameterSpec p : params) {
            parts.add(mapTypeString(p.type(), bySimple) + " " + p.name());
        }
        return String.join(", ", parts);
    }

    private static String formatArgumentNames(List<MethodSignature.ParameterSpec> params) {
        List<String> names = new ArrayList<>();
        for (MethodSignature.ParameterSpec p : params) {
            names.add(p.name());
        }
        return String.join(", ", names);
    }

    private static String mapTypeString(String rawType, Map<String, ApiClassMetadata> bySimple) {
        ApiClassMetadata meta = bySimple.get(rawType);
        if (meta != null && !meta.isApiKonform()) {
            return meta.effectiveApiInterfaceSimpleName();
        }
        return rawType;
    }
}
