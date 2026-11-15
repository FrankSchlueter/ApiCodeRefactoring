package de.tk.refactoring.tool;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import de.tk.refactoring.tool.model.ApiClassMetadata;
import de.tk.refactoring.tool.model.ConstructorCallTarget;
import de.tk.refactoring.tool.model.InternCastTarget;
import de.tk.refactoring.tool.model.ModuleMetamodel;

import java.util.List;
import java.util.Map;

/**
 * Syntactic AST transformer that injects {@code ((<Name>Intern) scope)} casts and replaces
 * {@code new <Name>(...)} constructor calls with {@code <ApiInterface>.create(...)} based purely
 * on line numbers from the dependency metamodel (without requiring a SymbolSolver).
 */
public class CastAndConstructorInjector {

    public static void applyTransformations(CompilationUnit cu, ModuleMetamodel metamodel) {
        String currentClassFqn = getPrimaryClassFqn(cu);
        if (currentClassFqn.isEmpty()) {
            return;
        }
        applyConstructorReplacements(cu, currentClassFqn, metamodel);
        applyInternCasts(cu, currentClassFqn, metamodel.internCastTargets());
    }

    public static void applyInternCasts(
            CompilationUnit cu,
            String currentClassFqn,
            List<InternCastTarget> targets) {

        String currentPkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

        for (InternCastTarget target : targets) {
            if (!target.targetClassFqn().equals(currentClassFqn)
                    && !extractSimpleName(target.targetClassFqn()).equals(extractSimpleName(currentClassFqn))) {
                continue;
            }
            String simpleInternName = target.internInterfaceSimpleName();
            String internPkg = extractPackageName(target.internInterfaceFqn());
            if (!internPkg.isEmpty() && !internPkg.equals(currentPkg)) {
                cu.addImport(target.internInterfaceFqn());
            }

            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals(target.methodName())
                        && matchesLine(call, target.lineNumber())) {
                    injectCastIntoScope(call, simpleInternName);
                }
            }
        }
    }

    public static void applyConstructorReplacements(
            CompilationUnit cu,
            String currentClassFqn,
            ModuleMetamodel metamodel) {

        Map<String, ApiClassMetadata> byFqn = metamodel.apiClassesByFqn();
        Map<String, ApiClassMetadata> bySimple = metamodel.apiClassesBySimpleName();
        String currentPkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

        for (ConstructorCallTarget target : metamodel.constructorCallTargets()) {
            if (!target.targetClassFqn().equals(currentClassFqn)
                    && !extractSimpleName(target.targetClassFqn()).equals(extractSimpleName(currentClassFqn))) {
                continue;
            }

            ApiClassMetadata meta = byFqn.getOrDefault(
                    target.instantiatedClassFqn(),
                    bySimple.get(extractSimpleName(target.instantiatedClassFqn())));
            if (meta == null) {
                continue;
            }

            String apiSimpleName = meta.effectiveApiInterfaceSimpleName();
            String apiFqn = meta.effectiveApiInterfaceFqn();
            String apiPkg = extractPackageName(apiFqn);
            if (!apiPkg.isEmpty() && !apiPkg.equals(currentPkg)) {
                cu.addImport(apiFqn);
            }

            String expectedNewSimple = meta.simpleName();
            for (ObjectCreationExpr oce : cu.findAll(ObjectCreationExpr.class)) {
                if (oce.getType().getNameAsString().equals(expectedNewSimple)
                        && matchesNewLine(oce, target.lineNumber())) {
                    NodeList<Expression> clonedArgs = new NodeList<>();
                    for (Expression arg : oce.getArguments()) {
                        clonedArgs.add(arg.clone());
                    }
                    MethodCallExpr createCall = new MethodCallExpr(
                            new NameExpr(apiSimpleName),
                            "create",
                            clonedArgs
                    );
                    oce.getRange().ifPresent(createCall::setRange);
                    oce.replace(createCall);
                }
            }
        }
    }

    private static void injectCastIntoScope(MethodCallExpr call, String targetTypeSimpleName) {
        if (call.getScope().isEmpty()) {
            return;
        }
        Expression currentScope = call.getScope().get();
        if (isAlreadyCastTo(currentScope, targetTypeSimpleName)) {
            return;
        }
        ClassOrInterfaceType castType = StaticJavaParser.parseClassOrInterfaceType(targetTypeSimpleName);
        CastExpr castExpr = new CastExpr(castType, currentScope.clone());
        EnclosedExpr enclosedCast = new EnclosedExpr(castExpr);
        call.setScope(enclosedCast);
    }

    private static boolean isAlreadyCastTo(Expression scope, String targetTypeSimpleName) {
        if (scope instanceof EnclosedExpr enclosed && enclosed.getInner() instanceof CastExpr castExpr) {
            return castExpr.getType().asString().equals(targetTypeSimpleName);
        }
        return false;
    }

    private static boolean matchesLine(MethodCallExpr call, int targetLine) {
        boolean nameOnLine = call.getName().getBegin()
                .map(pos -> pos.line == targetLine)
                .orElse(false);
        boolean callBeginOnLine = call.getBegin()
                .map(pos -> pos.line == targetLine)
                .orElse(false);
        return nameOnLine || callBeginOnLine;
    }

    private static boolean matchesNewLine(ObjectCreationExpr oce, int targetLine) {
        boolean beginOnLine = oce.getBegin()
                .map(pos -> pos.line == targetLine)
                .orElse(false);
        boolean typeOnLine = oce.getType().getBegin()
                .map(pos -> pos.line == targetLine)
                .orElse(false);
        return beginOnLine || typeOnLine;
    }

    private static String getPrimaryClassFqn(CompilationUnit cu) {
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!decl.isNestedType()) {
                return pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString();
            }
        }
        return "";
    }

    private static String extractSimpleName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx < 0 ? fqn : fqn.substring(idx + 1);
    }

    private static String extractPackageName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx < 0 ? "" : fqn.substring(0, idx);
    }
}
