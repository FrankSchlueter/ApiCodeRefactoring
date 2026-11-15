package de.tk.refactoring.tool.scanner;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import de.tk.refactoring.tool.model.ApiClassMetadata;
import de.tk.refactoring.tool.model.ConstructorCallTarget;
import de.tk.refactoring.tool.model.ConstructorSignature;
import de.tk.refactoring.tool.model.FieldMetadata;
import de.tk.refactoring.tool.model.InternCastTarget;
import de.tk.refactoring.tool.model.MethodSignature;
import de.tk.refactoring.tool.model.ModuleMetamodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Scans JavaParser {@link CompilationUnit}s annotated with {@code @Api} to build a
 * {@link ModuleMetamodel} including line-number targets for internal casts and constructor replacements.
 */
public class ApiAnnotationScanner {

    private static final String API_ANNOTATION_SIMPLE = "Api";
    private static final String API_ANNOTATION_FQN = "de.tk.dependencyanalyse.transformation.Api";

    public static ModuleMetamodel scan(
            List<CompilationUnit> units,
            String targetModulePackage,
            String implSubPackage) {

        String implPkgFqn = (implSubPackage == null || implSubPackage.isBlank())
                ? targetModulePackage + ".impl"
                : (implSubPackage.startsWith(targetModulePackage + ".")
                        ? implSubPackage
                        : targetModulePackage + "." + implSubPackage);

        // 1. Index all interfaces and classes in targetModulePackage
        Map<String, ClassOrInterfaceDeclaration> moduleInterfaces = new LinkedHashMap<>();
        Map<String, ClassOrInterfaceDeclaration> moduleClasses = new LinkedHashMap<>();

        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            if (!pkg.equals(targetModulePackage) && !pkg.startsWith(targetModulePackage + ".")) {
                continue;
            }
            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (decl.isNestedType()) {
                    continue;
                }
                String fqn = pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString();
                if (decl.isInterface()) {
                    moduleInterfaces.put(fqn, decl);
                } else {
                    moduleClasses.put(fqn, decl);
                }
            }
        }

        // 2. Build ApiClassMetadata for every class/interface in targetModulePackage that is part of the API
        List<ApiClassMetadata> apiClasses = new ArrayList<>();
        Map<String, ApiClassMetadata> bySimpleName = new LinkedHashMap<>();

        // First register existing interfaces in targetModulePackage if they are @Api or implemented by an @Api class
        Set<String> existingApiInterfaceFqns = new LinkedHashSet<>();
        for (Map.Entry<String, ClassOrInterfaceDeclaration> entry : moduleClasses.entrySet()) {
            ClassOrInterfaceDeclaration cls = entry.getValue();
            if (!hasAnyApiElement(cls)) {
                continue;
            }
            for (ClassOrInterfaceType implType : cls.getImplementedTypes()) {
                String candFqn = targetModulePackage + "." + implType.getNameAsString();
                if (moduleInterfaces.containsKey(candFqn)) {
                    existingApiInterfaceFqns.add(candFqn);
                }
            }
        }

        for (Map.Entry<String, ClassOrInterfaceDeclaration> entry : moduleClasses.entrySet()) {
            String fqn = entry.getKey();
            ClassOrInterfaceDeclaration cls = entry.getValue();

            Optional<String> existingApiIface = Optional.empty();
            List<String> implementedIfaces = new ArrayList<>();
            for (ClassOrInterfaceType implType : cls.getImplementedTypes()) {
                String ifaceSimple = implType.getNameAsString();
                String candFqn = targetModulePackage + "." + ifaceSimple;
                if (existingApiInterfaceFqns.contains(candFqn)) {
                    existingApiIface = Optional.of(candFqn);
                }
                implementedIfaces.add(ifaceSimple);
            }

            if (!hasAnyApiElement(cls) && existingApiIface.isEmpty()) {
                // Purely internal class -> not in apiClasses
                continue;
            }

            boolean isApiKonform = isMarkedApiKonform(cls);

            Optional<String> superClassFqn = Optional.empty();
            if (!cls.getExtendedTypes().isEmpty()) {
                String extSimple = cls.getExtendedTypes(0).getNameAsString();
                String candSuperFqn = targetModulePackage + "." + extSimple;
                if (moduleClasses.containsKey(candSuperFqn)) {
                    superClassFqn = Optional.of(candSuperFqn);
                }
            }

            // Collect methods already in existingApiInterface (if any)
            Set<String> ifaceMethodNames = new LinkedHashSet<>();
            List<MethodSignature> apiMethods = new ArrayList<>();
            if (existingApiIface.isPresent()) {
                ClassOrInterfaceDeclaration ifaceDecl = moduleInterfaces.get(existingApiIface.get());
                if (ifaceDecl != null) {
                    for (MethodDeclaration m : ifaceDecl.getMethods()) {
                        if (!m.isStatic()) {
                            apiMethods.add(toMethodSignature(m));
                            ifaceMethodNames.add(methodKey(m));
                        }
                    }
                }
            }

            List<MethodSignature> internalMethods = new ArrayList<>();
            List<MethodSignature> staticApiMethods = new ArrayList<>();

            for (MethodDeclaration m : cls.getMethods()) {
                boolean hasApi = hasApiAnnotation(m);
                if (m.isStatic()) {
                    if (hasApi && !m.isPrivate()) {
                        staticApiMethods.add(toMethodSignature(m));
                    }
                } else if (!m.isPrivate()) {
                    if (hasApi || ifaceMethodNames.contains(methodKey(m))) {
                        if (!ifaceMethodNames.contains(methodKey(m))) {
                            apiMethods.add(toMethodSignature(m));
                            ifaceMethodNames.add(methodKey(m));
                        }
                    } else if (!isApiKonform) {
                        internalMethods.add(toMethodSignature(m));
                    }
                }
            }

            List<FieldMetadata> staticApiFields = new ArrayList<>();
            for (FieldDeclaration f : cls.getFields()) {
                if (f.isStatic() && hasApiAnnotation(f)) {
                    String fieldType = f.getElementType().asString();
                    for (VariableDeclarator var : f.getVariables()) {
                        String init = var.getInitializer().map(Expression::toString).orElse("null");
                        staticApiFields.add(new FieldMetadata(var.getNameAsString(), fieldType, init));
                    }
                }
            }

            List<ConstructorSignature> apiConstructors = new ArrayList<>();
            for (ConstructorDeclaration ctor : cls.getConstructors()) {
                if (hasApiAnnotation(ctor)) {
                    apiConstructors.add(toConstructorSignature(ctor));
                }
            }

            ApiClassMetadata metadata = new ApiClassMetadata(
                    fqn,
                    isApiKonform,
                    existingApiIface,
                    superClassFqn,
                    implementedIfaces,
                    apiMethods,
                    internalMethods,
                    staticApiMethods,
                    staticApiFields,
                    apiConstructors
            );
            apiClasses.add(metadata);
            bySimpleName.put(metadata.simpleName(), metadata);
        }

        // 3. Map internal method names -> owning ApiClassMetadata (including inherited internal methods)
        Map<String, List<ApiClassMetadata>> internalMethodOwners = new LinkedHashMap<>();
        for (ApiClassMetadata meta : apiClasses) {
            if (meta.isApiKonform()) {
                continue;
            }
            for (MethodSignature im : getAllInternalMethodsIncludingSuper(meta, bySimpleName)) {
                internalMethodOwners.computeIfAbsent(im.name(), k -> new ArrayList<>()).add(meta);
            }
        }

        // 4. Scan all CompilationUnits for InternCastTargets and ConstructorCallTargets
        List<InternCastTarget> internCastTargets = new ArrayList<>();
        List<ConstructorCallTarget> constructorCallTargets = new ArrayList<>();

        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            boolean isInsideTargetModule = pkg.equals(targetModulePackage) || pkg.startsWith(targetModulePackage + ".");

            for (ClassOrInterfaceDeclaration typeDecl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (typeDecl.isNestedType() || typeDecl.isInterface()) {
                    continue;
                }
                String currentClassFqn = pkg.isEmpty()
                        ? typeDecl.getNameAsString()
                        : pkg + "." + typeDecl.getNameAsString();

                // Identify internal direct-Impl fields of this class (initialized via new <ClassName>(...) in field or constructor)
                Set<String> directImplFields = isInsideTargetModule
                        ? findDirectImplFields(typeDecl, bySimpleName)
                        : Set.of();

                // 4a. Find ConstructorCallTargets
                for (ObjectCreationExpr newExpr : typeDecl.findAll(ObjectCreationExpr.class)) {
                    String instantiatedSimple = newExpr.getType().getNameAsString();
                    ApiClassMetadata targetMeta = bySimpleName.get(instantiatedSimple);
                    if (targetMeta == null || targetMeta.isApiKonform() || targetMeta.apiConstructors().isEmpty()) {
                        continue;
                    }
                    if (isInsideTargetModule && isDirectImplFieldInitialization(newExpr, directImplFields, bySimpleName)) {
                        // Direct internal Impl field initialization -> becomes new <Name>Impl(...), not a Provider create call
                        continue;
                    }
                    int line = newExpr.getBegin().map(p -> p.line).orElse(-1);
                    if (line > 0) {
                        String providerFqn = targetMeta.packageName() + "." + targetMeta.providerInterfaceSimpleName();
                        constructorCallTargets.add(new ConstructorCallTarget(
                                currentClassFqn,
                                targetMeta.fullyQualifiedName(),
                                providerFqn,
                                line
                        ));
                    }
                }

                // 4b. Find InternCastTargets (only relevant when calling internal methods on API-typed references)
                if (isInsideTargetModule) {
                    Map<String, String> varTypesInClass = collectApiTypedVariablesAndParams(typeDecl, directImplFields, bySimpleName);

                    for (MethodCallExpr call : typeDecl.findAll(MethodCallExpr.class)) {
                        if (call.getScope().isEmpty()) {
                            continue;
                        }
                        String methodName = call.getNameAsString();
                        List<ApiClassMetadata> candidateOwners = internalMethodOwners.get(methodName);
                        if (candidateOwners == null || candidateOwners.isEmpty()) {
                            continue;
                        }

                        Expression scope = call.getScope().get();
                        if (isDirectImplScope(scope, directImplFields)) {
                            // Direct call on an internal *Impl field -> no cast needed
                            continue;
                        }

                        ApiClassMetadata resolvedOwner = resolveScopeOwner(
                                scope, candidateOwners, varTypesInClass, bySimpleName);
                        if (resolvedOwner != null) {
                            int line = call.getName().getBegin()
                                    .or(() -> call.getBegin())
                                    .map(p -> p.line)
                                    .orElse(-1);
                            if (line > 0) {
                                String internFqn = implPkgFqn + "." + resolvedOwner.internInterfaceSimpleName();
                                internCastTargets.add(new InternCastTarget(
                                        currentClassFqn,
                                        methodName,
                                        line,
                                        internFqn
                                ));
                            }
                        }
                    }
                }
            }
        }

        return new ModuleMetamodel(
                targetModulePackage,
                implSubPackage,
                apiClasses,
                internCastTargets,
                constructorCallTargets
        );
    }

    private static List<MethodSignature> getAllInternalMethodsIncludingSuper(
            ApiClassMetadata meta,
            Map<String, ApiClassMetadata> bySimpleName) {
        List<MethodSignature> all = new ArrayList<>(meta.internalMethods());
        Optional<String> superFqn = meta.superClassFqn();
        while (superFqn.isPresent()) {
            String superSimple = superFqn.get().substring(superFqn.get().lastIndexOf('.') + 1);
            ApiClassMetadata superMeta = bySimpleName.get(superSimple);
            if (superMeta == null) {
                break;
            }
            all.addAll(superMeta.internalMethods());
            superFqn = superMeta.superClassFqn();
        }
        return all;
    }

    /**
     * Finds fields of a class inside the target module that are initialized directly via {@code new <ImplClass>(...)}
     * (either at declaration or inside a constructor) and have no existing API interface.
     * These fields represent direct Impl-to-Impl references within the same module.
     */
    public static Set<String> findDirectImplFields(
            ClassOrInterfaceDeclaration typeDecl,
            Map<String, ApiClassMetadata> bySimpleName) {
        Set<String> candidateFields = new LinkedHashSet<>();
        Set<String> initializedWithNew = new LinkedHashSet<>();

        for (FieldDeclaration fd : typeDecl.getFields()) {
            String fieldType = fd.getElementType().asString();
            ApiClassMetadata meta = bySimpleName.get(fieldType);
            if (meta != null && !meta.isApiKonform() && meta.existingApiInterfaceFqn().isEmpty()) {
                for (VariableDeclarator vd : fd.getVariables()) {
                    candidateFields.add(vd.getNameAsString());
                    if (vd.getInitializer().isPresent()
                            && vd.getInitializer().get() instanceof ObjectCreationExpr oce
                            && oce.getType().getNameAsString().equals(fieldType)) {
                        initializedWithNew.add(vd.getNameAsString());
                    }
                }
            }
        }

        for (ConstructorDeclaration ctor : typeDecl.getConstructors()) {
            for (AssignExpr assign : ctor.findAll(AssignExpr.class)) {
                if (assign.getValue() instanceof ObjectCreationExpr oce) {
                    String assignedField = extractFieldName(assign.getTarget());
                    if (assignedField != null && candidateFields.contains(assignedField)) {
                        initializedWithNew.add(assignedField);
                    }
                }
            }
        }
        return initializedWithNew;
    }

    private static boolean isDirectImplFieldInitialization(
            ObjectCreationExpr newExpr,
            Set<String> directImplFields,
            Map<String, ApiClassMetadata> bySimpleName) {
        String typeName = newExpr.getType().getNameAsString();
        ApiClassMetadata meta = bySimpleName.get(typeName);
        if (meta == null || meta.existingApiInterfaceFqn().isPresent()) {
            return false;
        }
        if (newExpr.getParentNode().isPresent()) {
            var parent = newExpr.getParentNode().get();
            if (parent instanceof VariableDeclarator vd
                    && vd.getParentNode().orElse(null) instanceof FieldDeclaration
                    && directImplFields.contains(vd.getNameAsString())) {
                return true;
            }
            if (parent instanceof AssignExpr assign) {
                String assignedField = extractFieldName(assign.getTarget());
                if (assignedField != null && directImplFields.contains(assignedField)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDirectImplScope(Expression scope, Set<String> directImplFields) {
        String fieldName = extractFieldName(scope);
        return fieldName != null && directImplFields.contains(fieldName);
    }

    private static String extractFieldName(Expression expr) {
        if (expr instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        if (expr instanceof FieldAccessExpr fae && fae.getScope().isThisExpr()) {
            return fae.getNameAsString();
        }
        return null;
    }

    private static Map<String, String> collectApiTypedVariablesAndParams(
            ClassOrInterfaceDeclaration typeDecl,
            Set<String> directImplFields,
            Map<String, ApiClassMetadata> bySimpleName) {
        Map<String, String> map = new LinkedHashMap<>();
        for (FieldDeclaration fd : typeDecl.getFields()) {
            String t = fd.getElementType().asString();
            for (VariableDeclarator vd : fd.getVariables()) {
                if (!directImplFields.contains(vd.getNameAsString())) {
                    map.put(vd.getNameAsString(), t);
                }
            }
        }
        for (Parameter param : typeDecl.findAll(Parameter.class)) {
            map.put(param.getNameAsString(), param.getType().asString());
        }
        for (VariableDeclarator vd : typeDecl.findAll(VariableDeclarator.class)) {
            if (vd.getParentNode().orElse(null) instanceof FieldDeclaration) {
                continue;
            }
            map.put(vd.getNameAsString(), vd.getType().asString());
        }
        return map;
    }

    private static ApiClassMetadata resolveScopeOwner(
            Expression scope,
            List<ApiClassMetadata> candidateOwners,
            Map<String, String> varTypesInClass,
            Map<String, ApiClassMetadata> bySimpleName) {
        if (candidateOwners.size() == 1) {
            return candidateOwners.get(0);
        }
        // Try matching by variable/parameter type or method return type
        String varName = extractFieldName(scope);
        if (varName != null && varTypesInClass.containsKey(varName)) {
            String declaredType = varTypesInClass.get(varName);
            for (ApiClassMetadata owner : candidateOwners) {
                if (owner.simpleName().equals(declaredType)
                        || owner.effectiveApiInterfaceSimpleName().equals(declaredType)) {
                    return owner;
                }
            }
        }
        if (scope instanceof MethodCallExpr scopeCall) {
            String calledMethod = scopeCall.getNameAsString();
            for (ApiClassMetadata meta : bySimpleName.values()) {
                for (MethodSignature ms : meta.apiMethods()) {
                    if (ms.name().equals(calledMethod)) {
                        for (ApiClassMetadata owner : candidateOwners) {
                            if (owner.simpleName().equals(ms.returnType())
                                    || owner.effectiveApiInterfaceSimpleName().equals(ms.returnType())) {
                                return owner;
                            }
                        }
                    }
                }
            }
        }
        return candidateOwners.get(0);
    }

    public static boolean hasAnyApiElement(ClassOrInterfaceDeclaration decl) {
        if (hasApiAnnotation(decl)) {
            return true;
        }
        for (MethodDeclaration m : decl.getMethods()) {
            if (hasApiAnnotation(m)) {
                return true;
            }
        }
        for (FieldDeclaration f : decl.getFields()) {
            if (hasApiAnnotation(f)) {
                return true;
            }
        }
        for (ConstructorDeclaration c : decl.getConstructors()) {
            if (hasApiAnnotation(c)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasApiAnnotation(NodeWithAnnotations<?> node) {
        for (AnnotationExpr ann : node.getAnnotations()) {
            String name = ann.getNameAsString();
            if (name.equals(API_ANNOTATION_SIMPLE) || name.equals(API_ANNOTATION_FQN)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMarkedApiKonform(ClassOrInterfaceDeclaration decl) {
        for (AnnotationExpr ann : decl.getAnnotations()) {
            String name = ann.getNameAsString();
            if (name.equals(API_ANNOTATION_SIMPLE) || name.equals(API_ANNOTATION_FQN)) {
                if (ann.isNormalAnnotationExpr()) {
                    for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                        if (pair.getNameAsString().equals("isApiKonform")) {
                            return Boolean.parseBoolean(pair.getValue().toString());
                        }
                    }
                } else if (ann instanceof SingleMemberAnnotationExpr single) {
                    return Boolean.parseBoolean(single.getMemberValue().toString());
                }
            }
        }
        return false;
    }

    private static MethodSignature toMethodSignature(MethodDeclaration m) {
        List<MethodSignature.ParameterSpec> params = m.getParameters().stream()
                .map(p -> new MethodSignature.ParameterSpec(p.getType().asString(), p.getNameAsString()))
                .toList();
        List<String> typeParams = m.getTypeParameters().stream()
                .map(Object::toString)
                .toList();
        List<String> thrown = m.getThrownExceptions().stream()
                .map(Object::toString)
                .toList();
        return new MethodSignature(
                m.getNameAsString(),
                m.getType().asString(),
                params,
                typeParams,
                thrown
        );
    }

    private static ConstructorSignature toConstructorSignature(ConstructorDeclaration c) {
        List<MethodSignature.ParameterSpec> params = c.getParameters().stream()
                .map(p -> new MethodSignature.ParameterSpec(p.getType().asString(), p.getNameAsString()))
                .toList();
        List<String> thrown = c.getThrownExceptions().stream()
                .map(Object::toString)
                .toList();
        return new ConstructorSignature(params, thrown);
    }

    private static String methodKey(MethodDeclaration m) {
        StringBuilder sb = new StringBuilder(m.getNameAsString()).append('(');
        for (int i = 0; i < m.getParameters().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(m.getParameter(i).getType().asString());
        }
        sb.append(')');
        return sb.toString();
    }
}
