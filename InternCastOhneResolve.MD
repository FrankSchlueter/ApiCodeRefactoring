---
title: "Neuer Chat"
source: "https://gemini.google.com/app/28f4f7e01ca3097a?utm_source=app_launcher&utm_medium=owned&utm_campaign=base_all"
author:
published:
created: 2026-11-11
description: "Gemini conversation with 2 messages"
tags:
  - "clippings"
---
**You**

Ich will den JavaParser zur Codetransformation nutzen um in einer vorgegebenen Zeile einer Javaklasse einen Methodenaufruf durch einen Cast auf ein Intern-Interface zu ergänzen. Hintergrund ist die Ersetzung einer Implementationsklasse durch ein API-Interface das von einem Intern-Interface erweitert wird. Statt der Implementationsklasse bekommt man jetzt nur das API-Interface und dieses muss auf das Intern-Interface hochgecastet werden. Die Quelle für den Methodenaufruf kann ein Methodenparameter vom Interface-Typ sein, eine lokale Variable oder der Rückgabewert eines Methodcalls. Kann ich das mit dem Javaparser machen ohne dass ich ein den Methodencall Resolven muss, nur anhand der Zeilennummer? Kann ich mehrere derartige Transformationen machen ohne dass sich durch Zeilennummernänderungen, z.B. durch zusätzliche Imports Probleme ergeben? Zeige mir ein Codebeispiel.

---

**Gemini**

Beide Anforderungen lassen sich mit **JavaParser** direkt und rein syntaktisch auf Basis des Abstract Syntax Tree (AST) umsetzen – ganz ohne konfigurierten `JavaSymbolSolver` oder Classpath-Auflösung.

### 1\. Transformation ohne Symbol Resolution (nur über Zeile + Methodennamen)

Wenn du den Methodennamen und die Zielzeile (z. B. aus Compiler-Fehlermeldungen oder einer vorangegangenen statischen Analyse) kennst, reicht eine rein syntaktische Suche nach `MethodCallExpr` -Knoten völlig aus.

Im AST von JavaParser besitzt jeder Methodenaufruf der Form `target.internalMethod()` einen sogenannten **Scope** (`target`). Dabei spielt es für die Transformation überhaupt keine Rolle, was dieser Scope syntaktisch ist:

- **Methodenparameter oder lokale Variable:** `target` ist im AST eine `NameExpr` (z. B. `param`).
- **Rückgabewert eines anderen Methodenaufrufs:** `target` ist wiederum eine `MethodCallExpr` (z. B. `getService()`).
- **Feldzugriff:** `target` ist eine `FieldAccessExpr` (z. B. `this.service`).

Da alle diese Knoten von der Basisklasse `Expression` erben, kannst du den bestehenden Scope einfach einheitlich in eine geklammerte `CastExpr` einwickeln: `((InternInterface) <alterScope>).internalMethod()`.

### 2\. Stabilität der Zeilennummern bei Mehrfach-Änderungen & Imports

**Ja, das ist vollkommen problemlos möglich.** Der Grund liegt in der Funktionsweise des AST in JavaParser:

- **Unveränderliche Quell-Positionen (`Range`):** Beim initialen Parsen (`StaticJavaParser.parse(...)`) bekommt jeder AST-Knoten seine ursprünglichen Koordinaten (`begin.line`, `end.line`) aus dem Original-Quelltext zugewiesen.
- **Keine Neuberechnung bei AST-Mutationen:** Wenn du im Speicher neue Knoten einfügst oder mittels `cu.addImport(...)` oben in der Datei neue Imports ergänzt, **aktualisiert JavaParser die Zeilennummern der bestehenden AST-Knoten nicht**. Ein Knoten, der beim Parsen in Zeile 42 stand, behält in seinem `Range` -Objekt weiterhin die Zeile 42, bis die Datei ganz am Ende ausgeschrieben wird.
- **Lexical Preserving Printing:** Damit beim Zurückschreiben der Datei nicht der gesamte Code durch den Standard-Pretty-Printer von JavaParser umformatiert wird (was zu riesigen Git-Diffs führen würde), solltest du den `LexicalPreservingPrinter` aktivieren. Er verändert im Text exakt nur die mutierten AST-Teilbäume und die neuen Imports.

### Vollständiges Codebeispiel

#### Maven-Abhängigkeit

```
<dependency>
    <groupId>com.github.javaparser</groupId>
    <artifactId>javaparser-core</artifactId>
    <version>3.26.2</version>
</dependency>
```

#### Java-Implementierung

```
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.util.List;

public class CastInjector {

    /**
     * Repräsentiert eine durchzuführende Transformation.
     *
     * @param line                Zeilennummer im Original-Quellcode (1-basiert)
     * @param methodName          Name der aufgerufenen Methode des Intern-Interfaces
     * @param targetInterfaceFqn  Vollqualifizierter Name des Intern-Interfaces
     */
    public record CastTarget(int line, String methodName, String targetInterfaceFqn) {}

    public static String applyCasts(String sourceCode, List<CastTarget> targets) {
        // 1. Quellcode in AST parsen
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);

        // 2. Original-Formatierung (Whitespaces, Zeilenumbrüche, Kommentare) einfrieren
        LexicalPreservingPrinter.setup(cu);

        // 3. Alle MethodCallExpr im AST vorab sammeln
        List<MethodCallExpr> allMethodCalls = cu.findAll(MethodCallExpr.class);

        for (CastTarget target : targets) {
            String simpleInterfaceName = getSimpleName(target.targetInterfaceFqn());

            // 4. Import hinzufügen (JavaParser prüft automatisch auf Duplikate).
            // WICHTIG: Dies verändert NICHT die gespeicherten Zeilennummern im AST!
            cu.addImport(target.targetInterfaceFqn());

            // 5. Passenden Methodenaufruf anhand von Original-Zeile und Methodennamen suchen
            for (MethodCallExpr call : allMethodCalls) {
                if (call.getNameAsString().equals(target.methodName()) && matchesLine(call, target.line())) {
                    injectCastIntoScope(call, simpleInterfaceName);
                }
            }
        }

        // 6. Modifizierten AST unter Beibehaltung der Original-Formatierung ausgeben
        return LexicalPreservingPrinter.print(cu);
    }

    private static boolean matchesLine(MethodCallExpr call, int targetLine) {
        // Wir prüfen die Zeile des Methodennamens (NameExpr) UND des gesamten Calls.
        // Das fängt auch mehrzeilige Chained-Calls zuverlässig ab:
        // getApiObject()
        //     .internalMethod(); <-- Compiler meldet oft diese Zeile
        boolean nameOnLine = call.getName().getBegin()
                .map(pos -> pos.line == targetLine)
                .orElse(false);

        boolean callBeginOnLine = call.getBegin()
                .map(pos -> pos.line == targetLine)
                .orElse(false);

        return nameOnLine || callBeginOnLine;
    }

    private static void injectCastIntoScope(MethodCallExpr call, String targetTypeSimpleName) {
        // Falls die Methode ohne explizites Ziel aufgerufen wird (implizites 'this'),
        // setzen wir optional 'this' oder überspringen den Call.
        if (call.getScope().isEmpty()) {
            return;
        }

        Expression currentScope = call.getScope().get();

        // Schutz vor doppeltem Cast, falls dasselbe Ziel mehrfach in der Liste auftaucht
        if (isAlreadyCastTo(currentScope, targetTypeSimpleName)) {
            return;
        }

        // Ziel-Typ erzeugen (z.B. "MyServiceIntern")
        ClassOrInterfaceType castType = StaticJavaParser.parseClassOrInterfaceType(targetTypeSimpleName);

        // Wichtig: currentScope.clone() verwenden, damit der Knoten sauber aus seiner
        // alten Eltern-Beziehung gelöst und im neuen EnclosedExpr eingehängt wird.
        CastExpr castExpr = new CastExpr(castType, currentScope.clone());

        // Wegen der Operator-Präzedenz von '.' gegenüber '(Type)' MUSS der Cast 
        // zwingend in Klammern stehen: ((MyServiceIntern) scope).method()
        EnclosedExpr enclosedCast = new EnclosedExpr(castExpr);

        // Alten Scope durch den geklammerten Cast ersetzen
        call.setScope(enclosedCast);
    }

    private static boolean isAlreadyCastTo(Expression scope, String targetTypeSimpleName) {
        if (scope instanceof EnclosedExpr enclosed && enclosed.getInner() instanceof CastExpr castExpr) {
            return castExpr.getType().asString().equals(targetTypeSimpleName);
        }
        return false;
    }

    private static String getSimpleName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot == -1 ? fqn : fqn.substring(lastDot + 1);
    }

    // --- Demonstration ---
    public static void main(String[] args) {
        String originalCode = """
                package com.example.demo;
                
                import com.example.api.MyServiceApi;
                
                public class ClientClass {
                
                    public void execute(MyServiceApi paramService) {
                        // Fall 1: Aufruf auf Methodenparameter (Zeile 9)
                        paramService.secretInternalAction("test");
                
                        // Fall 2: Aufruf auf lokaler Variable (Zeile 13)
                        MyServiceApi localService = obtainService();
                        localService.anotherInternalCall();
                
                        // Fall 3: Aufruf direkt auf Rückgabewert eines Methodencalls (Zeile 16)
                        obtainService().secretInternalAction("chained");
                    }
                
                    private MyServiceApi obtainService() {
                        return null;
                    }
                }
                """;

        List<CastTarget> transformations = List.of(
                new CastTarget(9, "secretInternalAction", "com.example.internal.MyServiceIntern"),
                new CastTarget(13, "anotherInternalCall", "com.example.internal.MyServiceIntern"),
                new CastTarget(16, "secretInternalAction", "com.example.internal.MyServiceIntern")
        );

        String transformedCode = applyCasts(originalCode, transformations);
        System.out.println(transformedCode);
    }
}
```

#### Ausgabe des Beispiels

```
package com.example.demo;

import com.example.api.MyServiceApi;
import com.example.internal.MyServiceIntern;

public class ClientClass {

    public void execute(MyServiceApi paramService) {
        // Fall 1: Aufruf auf Methodenparameter (Zeile 9)
        ((MyServiceIntern) paramService).secretInternalAction("test");

        // Fall 2: Aufruf auf lokaler Variable (Zeile 13)
        MyServiceApi localService = obtainService();
        ((MyServiceIntern) localService).anotherInternalCall();

        // Fall 3: Aufruf direkt auf Rückgabewert eines Methodencalls (Zeile 16)
        ((MyServiceIntern) obtainService()).secretInternalAction("chained");
    }

    private MyServiceApi obtainService() {
        return null;
    }
}
```

### Wichtige Details für den Praxiseinsatz

1. **Operator-Präzedenz (`EnclosedExpr`):** Der Punkt-Operator (`.`) bindet in Java stärker als der Typ-Cast `(Type)`. Würdest du nur eine `CastExpr` als Scope setzen, käme `(MyServiceIntern) paramService.secretInternalAction()` heraus (was versucht, den *Rückgabewert* der Methode zu casten). Durch das Verpacken in `new EnclosedExpr(castExpr)` entstehen die zwingend notwendigen äußeren Klammern `((MyServiceIntern) paramService).secretInternalAction()`.
2. **Mehrere Aufrufe in derselben Zeile:** Falls in einer Zeile zwei Aufrufe derselben Methode geschachtelt sind (z. B. `a.foo(b.foo())`), würde die Schleife oben beide transformieren. Falls nur einer davon gemeint ist, lässt sich `CastTarget` noch um die Spaltennummer (`pos.column`) aus der Compiler-/Analyse-Meldung ergänzen.
3. **Namenskollisionen bei Imports:** `cu.addImport("com.example.internal.MyServiceIntern")` fügt den Import oben ein. Sollte in derselben Klasse bereits ein anderer Typ mit demselben Simple-Name importiert sein, kannst du vorab prüfen, ob `cu.getImports()` bereits einen kollidierenden Klassennamen enthält, und in diesem Sonderfall den vollqualifizierten Namen in `parseClassOrInterfaceType(...)` übergeben.
