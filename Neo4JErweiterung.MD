---
title: "Mediathek"
source: "https://gemini.google.com/app/50e38a691fc16854?utm_source=app_launcher&utm_medium=owned&utm_campaign=base_all"
author:
published:
created: 2026-11-14
description: "Gemini conversation with 16 messages"
tags:
  - "clippings"
---
**You**

Ich will aus einem Java AST den Flow der Zugriffe einer Klasse A auf Klasse B abbilden, d.h. Zugriffe auf Methoden und Felder von Klasse B über Member Variablen, Methodenparameter, lokale Variablen oder Rückgabewerte von Methoden. Ich habe bereits Method, Field und Class-Nodes und methodInvocation Relationships in der Neo4J DB. Schlage mir ein Model von zusätzlichen Nodes und Relationships vor um den Flow abzubilden.

---

**Gemini**

Um den exakten Daten- und Kontrollfluss (Data & Call Flow) zwischen Klasse A und Klasse B über verschiedene Trägerelemente (Fields, Parameter, Locals, Return-Values) abzubilden, reicht eine direkte Kante `(:Method)-[:INVOKES]->(:Method)` nicht aus. Wenn Methode `m1` in Klasse A zweimal `b.doSomething()` auf unterschiedlichen Instanzen aufruft, geht bei einer direkten Kante zwischen den Method-Nodes die Information verloren, *über welche Variable* der jeweilige Aufruf stattfand.

Der Schlüssel liegt darin, **Deklarationen** (Methoden, Felder, Variablen) von **Zugriffs-Stellen im AST (Call-Sites / Access-Sites)** zu trennen oder einen kompakten **Data-Flow-Layer** einzuziehen.

Hier ist ein praxiserprobtes, abfrageoptimiertes Graph-Modell für Neo4j, das sowohl direkte Variablenzugriffe als auch Verkettungen (Fluent APIs / Method Chaining) abdeckt.

### 1\. Neue Node-Labels (Erweiterung deines Modells)

Neben deinen bestehenden `:Class`, `:Method` und `:Field` Nodes benötigst du Repräsentationen für den methodenlokalen Scope und für die konkreten Zugriffsstellen im AST:

- **`:Parameter`**: Repräsentiert einen Methodenparameter.
	- *Properties:* `name`, `index` (Position in der Signatur), `type` (FQN).
- **`:LocalVariable`**: Repräsentiert eine lokale Variable innerhalb eines Methoden-Scopes.
	- *Properties:* `name`, `lineNumber`, `type`, `isFinal`.
- **`:CallSite`** (oder `:Invocation`): Repräsentiert den *konkreten AST-Knoten* eines Methodenaufrufs (`MethodInvocation` bzw. `ObjectCreationExpression` für `new`). Das löst deine bisherige `methodInvocation` -Relationship als Zwischenknoten auf (Reifikation) oder ergänzt sie.
	- *Properties:* `lineNumber`, `column`, `codeSnippet`.
- **`:FieldAccess`**: Repräsentiert den *konkreten AST-Knoten* eines direkten Feldzugriffs (`b.someField = 5` oder `x = b.someField`).
	- *Properties:* `lineNumber`, `accessType` (`READ`, `WRITE`, `READ_WRITE`).

*(Tipp für Cypher-Queries: Gib `:Field`, `:Parameter` und `:LocalVariable` zusätzlich ein gemeinsames Basis-Label **`:Variable`**, um Pfadabfragen massiv zu vereinfachen.)*

### 2\. Neue Relationships

Die Kanten teilen sich in drei logische Ebenen auf: **Struktur/Typisierung**, **Ausführung (AST-Sites)** und **Datenfluss (Receiver & Wertefluss)**.

#### A. Struktur & Typisierung

| Quell-Node | Relationship | Ziel-Node | Bedeutung |
| --- | --- | --- | --- |
| `:Method` | `[:HAS_PARAMETER]` | `:Parameter` | Methode deklariert Parameter. |
| `:Method` | `[:DECLARES_LOCAL]` | `:LocalVariable` | Methode deklariert lokale Variable. |
| `:Variable` | `[:OF_TYPE]` | `:Class` | Statischer Typ von `:Field`, `:Parameter` oder `:LocalVariable`. |
| `:Method` | `[:RETURNS_TYPE]` | `:Class` | Rückgabetyp einer Methode. |

#### B. Ausführung & Ziel-Auflösung (Call / Access Sites)

Anstatt nur `(:Method)-[:METHOD_INVOCATION]->(:Method)` zu speichern (was du für schnelle High-Level-Analysen behalten kannst!), hängst du die konkrete AST-Stelle dazwischen:

- `(:Method)-[:CONTAINS_CALL]->(:CallSite)-[:TARGETS_METHOD]->(:Method)`
- `(:Method)-[:CONTAINS_ACCESS]->(:FieldAccess)-[:TARGETS_FIELD]->(:Field)`

#### C. Der Flow-Layer (Herzstück für deine Anforderung)

Um abzubilden, **worüber** Klasse B aufgerufen wurde, verknüpfst du die `:CallSite` bzw. den `:FieldAccess` mit dem Ursprung der Referenz (dem *Receiver* / *Scope* im AST):

- **`[:ON_RECEIVER]`**: Zeigt von einer `:CallSite` oder einem `:FieldAccess` auf das Element, auf dem der Aufruf stattfindet (`receiver.foo()`).
	- **Fall 1 (Member-Variable):** `(:CallSite)-[:ON_RECEIVER]->(:Field)`
		- **Fall 2 (Methodenparameter):** `(:CallSite)-[:ON_RECEIVER]->(:Parameter)`
		- **Fall 3 (Lokale Variable):** `(:CallSite)-[:ON_RECEIVER]->(:LocalVariable)`
		- **Fall 4 (Direkter Return-Wert / Chaining):** Wenn `getB().doSomething()` aufgerufen wird, gibt es keine Zwischenvariable. Die zweite `:CallSite` hat als Receiver direkt die erste `:CallSite`:`(:CallSite {code: "doSomething()"})-[:ON_RECEIVER]->(:CallSite {code: "getB()"})`
- **`[:FLOWS_TO]`** (oder `[:ASSIGNED_FROM]`): Verbindet die Herkunft eines Wertes mit einer Variablen, um den Fluss über Zuweisungen hinweg zu verfolgen (`B localB = getB(); localB.foo();`):
	- `(:CallSite)-[:FLOWS_TO]->(:LocalVariable | :Field)` (Rückgabewert einer Methode wird in Variable gespeichert)
		- `(:Parameter | :Field | :LocalVariable)-[:FLOWS_TO]->(:LocalVariable | :Field)` (Variablen-Zuweisung / Aliasing)
- **`[:PASSED_AS_ARG {index: 0}]`**: Falls eine Instanz von B (oder ein Wert daraus) als Argument in eine andere Methode gereicht wird:
	- `(:Variable | :CallSite)-[:PASSED_AS_ARG]->(:CallSite)` (oder direkt `[:FLOWS_TO]->(:Parameter)` der Zielmethode für interprozedurales Tracking).

### 3\. Konkretes Beispiel: Alle 4 Fälle in Java & im Graph

Betrachten wir diese Methode in `ClassA`, die auf `ClassB` zugreift:

```
public class ClassA {
    private ClassB memberB;

    public void process(ClassB paramB) {
        // 1. Zugriff über Member-Variable (Field von B lesen)
        int v = this.memberB.statusField;

        // 2. Zugriff über Parameter
        paramB.execViaParam();

        // 3. Zugriff über lokale Variable (gespeist aus Factory-Methoden-Return)
        ClassB localB = createB();
        localB.execViaLocal();

        // 4. Direkter Zugriff über Rückgabewert (Method Chaining)
        createB().execViaReturn();
    }
    
    private ClassB createB() { return new ClassB(); }
}
```

So sieht die Abbildung im Graph-Modell für diese vier Zugriffe aus:

1. **Über Member-Variable (`this.memberB.statusField`):**`(:Class {name:"ClassA"})-[:DECLARES_METHOD]->(m:Method {name:"process"})` `(m)-[:CONTAINS_ACCESS]->(fa:FieldAccess {type:"READ"})-[:TARGETS_FIELD]->(:Field {name:"statusField"})<-[:DECLARES_FIELD]-(:Class {name:"ClassB"})` Und der Träger: `(fa)-[:ON_RECEIVER]->(:Field {name:"memberB"})`
2. **Über Methodenparameter (`paramB.execViaParam()`):**`(m)-[:CONTAINS_CALL]->(cs1:CallSite)-[:TARGETS_METHOD]->(:Method {name:"execViaParam"})` Und der Träger: `(cs1)-[:ON_RECEIVER]->(p:Parameter {name:"paramB"})<-[:HAS_PARAMETER]-(m)`
3. **Über lokale Variable (`localB.execViaLocal()`):**
	- Zuweisung aus `createB()`: `(m)-[:CONTAINS_CALL]->(csCreate1:CallSite)-[:TARGETS_METHOD]->(:Method {name:"createB"})`
		- Datenfluss in Variable: `(csCreate1)-[:FLOWS_TO]->(loc:LocalVariable {name:"localB"})`
		- Aufruf auf Variable: `(m)-[:CONTAINS_CALL]->(cs2:CallSite)-[:TARGETS_METHOD]->(:Method {name:"execViaLocal"})`
		- Verknüpfung: `(cs2)-[:ON_RECEIVER]->(loc)`
4. **Direkt über Rückgabewert (`createB().execViaReturn()`):**
	- Aufruf 1: `(m)-[:CONTAINS_CALL]->(csCreate2:CallSite)-[:TARGETS_METHOD]->(:Method {name:"createB"})`
		- Aufruf 2: `(m)-[:CONTAINS_CALL]->(cs3:CallSite)-[:TARGETS_METHOD]->(:Method {name:"execViaReturn"})`
		- Chaining: `(cs3)-[:ON_RECEIVER]->(csCreate2)`

### 4\. Wie du das Modell in Cypher abfragst

Mit dieser Struktur kannst du mit einer einzigen kompakten Cypher-Query herausfinden, **wie** `ClassA` auf `ClassB` zugreift und über welchen Mechanismus (Field, Parameter, Local, Chained Return) die Referenz gehalten wird:

```
MATCH (classA:Class {name: 'ClassA'})-[:DECLARES_METHOD]->(mA:Method)
MATCH (classB:Class {name: 'ClassB'})-[:DECLARES_METHOD|DECLARES_FIELD]->(targetB)

// Alle CallSites und FieldAccesses in ClassA finden, die auf Member von ClassB zielen
MATCH (mA)-[:CONTAINS_CALL|CONTAINS_ACCESS]->(site)-[:TARGETS_METHOD|TARGETS_FIELD]->(targetB)

// Den unmittelbaren Receiver (Träger des Aufrufs) ermitteln
OPTIONAL MATCH (site)-[:ON_RECEIVER]->(receiver)

// Optional: Woher kam die lokale Variable? (z.B. aus einem vorherigen Methodenaufruf)
OPTIONAL MATCH (source)-[:FLOWS_TO]->(receiver)

RETURN 
    mA.name AS callerMethod,
    labels(site)[0] AS accessKind,
    targetB.name AS accessedBElement,
    labels(receiver)[0] AS viaMechanism, // 'Field', 'Parameter', 'LocalVariable' oder 'CallSite' (Return)
    receiver.name AS variableName,
    labels(source)[0] AS assignedFromType,
    source.name AS assignedFrom
```

### 5\. Architektur-Tipp: Voller AST vs. Data-Flow-Projektion

- **Vermeide feingranulare Expression-Bäume für globale Flow-Analysen:** Wenn du jeden `BinaryExpression` -, `CastExpression` - oder `ParenthesizedExpression` -Knoten zwingend zwischen `:CallSite` und `:Variable` schaltest, werden Pfadabfragen in Neo4j langsam und unübersichtlich (`[:CHILD*1..10]`).
- **Löse den Receiver beim Parsen (z. B. mit JavaParser oder Eclipse JDT) direkt auf:** Wenn im Code `((ClassB) this.memberB).foo()` steht, strippe beim AST-Traversal die Klammern und den Cast-Wrapper (oder speichere den Cast als Property an der `:ON_RECEIVER` -Kante) und ziehe die Kante `(:CallSite)-[:ON_RECEIVER]->(:Field)` direkt.
- **Behalte deine direkte Kante als Shortcut:** Deine bereits existierende `[:METHOD_INVOCATION]` zwischen `(:Method)` und `(:Method)` ist Gold wert für schnelle Architektur-Checks (z. B. Zyklenerkennung oder Package-Dependencies). Behalte sie als aggregierte High-Level-Kante bei (ggf. mit einem `count` -Attribut) und nutze den Weg über `:CallSite` und `:ON_RECEIVER`, sobald du in den detaillierten Datenfluss zwischen zwei Klassen hineinzoomst.

Hier kannst du die vier Zugriffspfade (Member-Feld, Parameter, lokale Variable inkl. Zuweisung und direktes Method-Chaining) im vorgeschlagenen Neo4j-Schema interaktiv durchschalten:

<iframe allow="xr-spatial-tracking; web-share" title="Preview" sandbox="allow-pointer-lock allow-popups allow-forms allow-popups-to-escape-sandbox allow-downloads allow-same-origin allow-scripts" src="https://5veq1jsaxiz3r6tmzdld8911rgsie9e3ly752goiroci2j2yki-h1018257101.scf.usercontent.goog/gemini-code-immersive/shim.html?origin=https%3A%2F%2Fgemini.google.com&amp;cache=1"></iframe>

Welchen Parser setzt du aktuell für den AST-Export nach Neo4j ein (z. B. Eclipse JDT, JavaParser oder Spoon), und möchtest du den Datenfluss zunächst rein intraprozedural (innerhalb einer Methode) oder auch interprozedural über Aufrufketten hinweg verfolgen?

---

**You**

Ich nutze eclipse jdt

---

**Gemini**

Mit **Eclipse JDT (`org.eclipse.jdt.core.dom`)** hast du das mächtigste Werkzeug dafür im Einsatz, weil das **Binding-Resolution-System (`IBinding`)** dir die komplette Arbeit der Symbolauflösung abnimmt. Du musst keine eigenen Scope-Tabellen pflegen, um herauszufinden, ob ein `SimpleName` ein Feld, ein Parameter oder eine lokale Variable ist.

So übersetzt du den JDT-AST direkt und sauber in das vorgeschlagene Neo4j-Modell:

### 1\. Der Schlüssel in JDT: IVariableBinding und getKey()

In JDT liefert dir fast jeder relevante Knoten über `.resolveBinding()` ein Binding-Objekt. Jedes `IBinding` besitzt eine Methode **`getKey()`**, die einen eindeutigen, deterministischen String generiert (auch für lokale Variablen und Parameter!). Diesen Key kannst du direkt als eindeutige `id` -Property für `MERGE` -Statements in Neo4j nutzen.

Für Variablen (`IVariableBinding`) liefert JDT bereits die exakte Unterscheidung für deine Node-Labels:

- `varBinding.isField()` $\rightarrow$ **`:Field :Variable`**
- `varBinding.isParameter()` $\rightarrow$ **`:Parameter :Variable`**
- `!varBinding.isField() && !varBinding.isParameter()` (und `!isEnumConstant()`) $\rightarrow$ **`:LocalVariable :Variable`**

Für `:CallSite` und `:FieldAccess` (die selbst keine Bindings sind, sondern AST-Positionen) bildest du die `id` in Neo4j am besten aus dem Binding-Key der umgebenden Methode plus dem Zeichen-Offset im Quellcode: `enclosingMethodKey + "@" + node.getStartPosition()`.

### 2\. Mapping der JDT-ASTNodes auf das Graph-Modell

| JDT `ASTNode` | Aktion im `ASTVisitor` | Erzeugte Neo4j-Struktur |
| --- | --- | --- |
| **`SingleVariableDeclaration`** *(in `MethodDeclaration.parameters()`)* | `node.resolveBinding()` | `(:Method)-[:HAS_PARAMETER]->(:Parameter)` |
| **`VariableDeclarationFragment`** *(in `VariableDeclarationStatement` / `Expression`)* | `node.resolveBinding()` + Check auf `node.getInitializer()` | `(:Method)-[:DECLARES_LOCAL]->(:LocalVariable)` + falls Initializer vorhanden: `(source)-[:FLOWS_TO]->(:LocalVariable)` |
| **`Assignment`** (`a = b`) | `getLeftHandSide()` und `getRightHandSide()` auflösen | `(rhsSource)-[:FLOWS_TO]->(lhsTarget)` |
| **`MethodInvocation`** | `resolveMethodBinding()` + **`getExpression()`** auswerten | `(:Method)-[:CONTAINS_CALL]->(cs:CallSite)-[:TARGETS_METHOD]->(:Method)` + `(cs)-[:ON_RECEIVER]->(receiver)` |
| **`FieldAccess`** (`this.b.x`) & **`QualifiedName`** (`b.x`) | **Wichtig:** JDT nutzt `FieldAccess` oft nur bei komplexen Ausdrücken (`this.b.x` oder `getB().x`). Ein simples `memberB.statusField` ist im JDT-AST meist ein **`QualifiedName`**! | `(:Method)-[:CONTAINS_ACCESS]->(fa:FieldAccess)-[:TARGETS_FIELD]->(:Field)` + `(fa)-[:ON_RECEIVER]->(receiver)` |
| **`SimpleName`** *(als Feldzugriff ohne `this.`)* | Wenn `resolveBinding()` ein `IVariableBinding` mit `isField() == true` ist und der Parent kein `FieldAccess` / `QualifiedName` ist. | Impliziter Zugriff auf ein eigenes Member-Feld (`this.memberB`). |

### 3\. Die zentrale Hilfsmethode: Den Receiver / Datenfluss-Ursprung auflösen

Wenn du in `visit(MethodInvocation node)` bist, liefert dir `node.getExpression()` den **Receiver**, auf dem die Methode aufgerufen wird (also das `b` in `b.doSomething()`). Da im Code aber auch `((ClassB) this.b).doSomething()` oder `getB().doSomething()` stehen kann, brauchst du eine kleine Unwrapping-Methode, die dir den Ziel-Node für die `[:ON_RECEIVER]` - oder `[:FLOWS_TO]` -Kante liefert:

```
/**
 * Löst eine JDT Expression zu unserer Neo4j-Node-ID (Variable oder CallSite) auf.
 */
private String resolveFlowNodeId(Expression expr, String enclosingMethodKey) {
    if (expr == null) {
        return null; // Implizites 'this' oder statischer Import
    }

    // 1. Klammern und Casts auspacken: ((ClassB) b).foo() -> b
    if (expr instanceof ParenthesizedExpression paren) {
        return resolveFlowNodeId(paren.getExpression(), enclosingMethodKey);
    }
    if (expr instanceof CastExpression cast) {
        return resolveFlowNodeId(cast.getExpression(), enclosingMethodKey);
    }

    // 2. Fall 4: Method Chaining / direkter Rückgabewert: createB().foo()
    if (expr instanceof MethodInvocation chainedCall) {
        return enclosingMethodKey + "@" + chainedCall.getStartPosition(); // ID der inneren :CallSite
    }
    if (expr instanceof ClassInstanceCreation newCall) {
        return enclosingMethodKey + "@" + newCall.getStartPosition(); // z.B. new ClassB().foo()
    }

    // 3. Fall 1, 2, 3: Member-Variable, Parameter oder Lokale Variable
    if (expr instanceof Name || expr instanceof FieldAccess) {
        IBinding binding = (expr instanceof Name name) 
            ? name.resolveBinding() 
            : ((FieldAccess) expr).resolveFieldBinding();

        if (binding instanceof IVariableBinding varBinding) {
            // WICHTIG bei Generics: getVariableDeclaration() nutzen!
            return varBinding.getVariableDeclaration().getKey();
        }
    }

    return null;
}
```

### 4\. Drei klassische JDT-Stolperfallen (und wie du sie umgehst)

1. **Generics & Parameterized Types (`getVariableDeclaration()` / `getMethodDeclaration()`):** Wenn `ClassB<T>` generisch ist, erzeugt JDT beim Aufruf `b.doSomething()` ein *parametrisiertes* `IMethodBinding` (z. B. mit `String` statt `T`). Dessen `.getKey()` unterscheidet sich vom `.getKey()` der eigentlichen `MethodDeclaration` in `ClassB`!*Lösung:* Rufe auf Bindings vor dem Extrahieren des Keys **immer** `methodBinding.getMethodDeclaration().getKey()` bzw. `varBinding.getVariableDeclaration().getKey()` auf. Nur so zeigt `[:TARGETS_METHOD]` garantiert auf denselben `:Method` -Knoten, den du beim Parsen von `ClassB` angelegt hast.
2. **`QualifiedName` vs. `FieldAccess`:** Schreibt jemand `paramB.statusField`, erzeugt JDT im AST **keinen** `FieldAccess` -Knoten, sondern einen `QualifiedName` (mit `Qualifier` = `paramB` und `Name` = `statusField`). Erst bei `this.memberB.statusField` oder `getB().statusField` entsteht ein `FieldAccess` -Knoten.*Lösung:* Prüfe im `ASTVisitor` bei `visit(QualifiedName node)`, ob `node.resolveBinding()` ein `IVariableBinding` mit `isField() == true` ist. Falls ja, behandle ihn exakt wie einen `FieldAccess` -Knoten, wobei `node.getQualifier()` dein Receiver für `[:ON_RECEIVER]` ist.
3. **Lese- vs. Schreibzugriffe auf Felder (`accessType`):** Um an deinem `:FieldAccess` -Knoten in Neo4j `READ` oder `WRITE` zu setzen, prüfst du den `getParent()` des Zugriffs-Knotens:
	- Ist der Parent eine `Assignment` und dein Knoten ist `assignment.getLeftHandSide()`:
		- Operator ist `=` (`Assignment.Operator.ASSIGN`) $\rightarrow$ `WRITE`
				- Operator ist `+=`, `-=`, etc. $\rightarrow$ `READ_WRITE`
		- Ist der Parent eine `PostfixExpression` (`b.count++`) oder `PrefixExpression` (`++b.count`, `--b.count`) $\rightarrow$ `READ_WRITE`
		- In allen anderen Fällen $\rightarrow$ `READ`.

### 5\. Zusammenbau im ASTVisitor für Methodenaufrufe & Zuweisungen

Hier ist das Muster für deinen Visitor innerhalb einer Methode:

```
@Override
public boolean visit(MethodInvocation node) {
    IMethodBinding targetBinding = node.resolveMethodBinding();
    if (targetBinding == null) return true;

    String callSiteId = currentMethodKey + "@" + node.getStartPosition();
    String targetMethodKey = targetBinding.getMethodDeclaration().getKey();

    // 1. CallSite und Zielmethode verknüpfen
    // (:Method {id: currentMethodKey})-[:CONTAINS_CALL]->(:CallSite {id: callSiteId})-[:TARGETS_METHOD]->(:Method {id: targetMethodKey})

    // 2. Receiver auflösen (Worüber wurde aufgerufen?)
    Expression receiverExpr = node.getExpression();
    String receiverId = resolveFlowNodeId(receiverExpr, currentMethodKey);
    if (receiverId != null) {
        // (:CallSite {id: callSiteId})-[:ON_RECEIVER]->(Node {id: receiverId})
        // (Node {id: receiverId}) ist entweder :Field, :Parameter, :LocalVariable oder eine andere :CallSite!
    }

    // 3. Optional:Übergebene Argumente tracken (falls B als Parameter übergeben wird)
    List<Expression> args = node.arguments();
    for (int i = 0; i < args.size(); i++) {
        String argSourceId = resolveFlowNodeId(args.get(i), currentMethodKey);
        if (argSourceId != null) {
            // (Node {id: argSourceId})-[:PASSED_AS_ARG {index: i}]->(:CallSite {id: callSiteId})
        }
    }
    return true;
}

@Override
public boolean visit(VariableDeclarationFragment node) {
    IVariableBinding varBinding = node.resolveBinding();
    if (varBinding == null || varBinding.isField()) return true;

    String localId = varBinding.getVariableDeclaration().getKey();
    // (:Method {id: currentMethodKey})-[:DECLARES_LOCAL]->(:LocalVariable:Variable {id: localId, name: varBinding.getName()})

    // Datenfluss aus Initializer (z.B. ClassB localB = createB(); oder ClassB localB = paramB;)
    if (node.getInitializer() != null) {
        String sourceId = resolveFlowNodeId(node.getInitializer(), currentMethodKey);
        if (sourceId != null) {
            // (Node {id: sourceId})-[:FLOWS_TO]->(:LocalVariable {id: localId})
        }
    }
    return true;
}
```

Soll ich dir auch das Visitor-Mapping für FieldAccess, QualifiedName und Chained Field-Reads zeigen?

---

**You**

Ja, zeige mir bitte die konkrete JDT ASTVisitor-Implementierung für FieldAccess und QualifiedName inklusive Erkennung von READ/WRITE und verketteten Zugriffen wie a.getB().fieldC.

---

**Gemini**

Um Feldzugriffe und Verkettungen wie `a.getB().fieldC` oder `this.memberA.fieldB.fieldC = 10` in Eclipse JDT lückenlos zu erfassen, müssen wir drei Herausforderungen im `ASTVisitor` lösen:

1. **`FieldAccess` vs. `QualifiedName`**:
	- `a.getB().fieldC` ist im JDT-AST ein **`FieldAccess`** (weil die Receiver-Expression ein Methodenaufruf ist).
		- `paramB.fieldC` oder `memberA.fieldB.fieldC` sind im JDT-AST verschachtelte **`QualifiedName`** -Knoten (`Name.Name.Name`), keine `FieldAccess` -Knoten!
2. **Zwischenschritte in Ketten sind Lesezugriffe**: Bei `a.fieldB.fieldC = 5` wird `fieldC` **geschrieben** (`WRITE`), aber `fieldB` wird **gelesen** (`READ`), um als Receiver für den Zugriff auf `fieldC` zu dienen.
3. **Vermeidung von Duplikaten beim Traversieren**: Jedes `FieldAccess` und jeder `QualifiedName` enthält als Kind-Knoten einen `SimpleName` für das Feld selbst. Wenn wir zusätzlich unqualifizierte Feldzugriffe (`statusField = 5` ohne `this.`) über `visit(SimpleName)` abfangen wollen, müssen wir dort prüfen, ob der Parent bereits ein `FieldAccess` oder `QualifiedName` war.

Hier ist die vollständige, direkt einsetzbare Implementierung für deinen `ASTVisitor`:

### 1\. Die ASTVisitor-Implementierung für Feldzugriffe & Ketten

```
import org.eclipse.jdt.core.dom.*;

public class DataFlowAstVisitor extends ASTVisitor {

    private String currentMethodKey;

    @Override
    public boolean visit(MethodDeclaration node) {
        IMethodBinding binding = node.resolveBinding();
        this.currentMethodKey = (binding != null) ? binding.getMethodDeclaration().getKey() : null;
        return this.currentMethodKey != null;
    }

    /**
     * Fall A: Echter JDT FieldAccess
     * Tritt auf bei: this.memberB.fieldC, a.getB().fieldC, ((ClassB) obj).fieldC
     */
    @Override
    public boolean visit(FieldAccess node) {
        IVariableBinding fieldBinding = node.resolveFieldBinding();
        if (fieldBinding != null && fieldBinding.isField()) {
            handleFieldAccess(
                node, 
                fieldBinding, 
                node.getExpression() // Der Receiver links vom Punkt (z.B. a.getB())
            );
        }
        return true; // Kinder weiter besuchen, damit z.B. a.getB() als CallSite erfasst wird!
    }

    /**
     * Fall B: QualifiedName als Feldzugriff
     * Tritt auf bei: paramB.fieldC, localB.fieldC, memberA.fieldB.fieldC
     * (Wichtig: Kann auch ein Package-Name wie java.lang.Math.PI sein!)
     */
    @Override
    public boolean visit(QualifiedName node) {
        IBinding binding = node.resolveBinding();
        if (binding instanceof IVariableBinding varBinding && varBinding.isField()) {
            // Prüfen, ob der Qualifier ein echter Wert/Typ ist und kein bloßes Package
            IBinding qualifierBinding = node.getQualifier().resolveBinding();
            if (qualifierBinding instanceof IVariableBinding || qualifierBinding instanceof ITypeBinding) {
                handleFieldAccess(
                    node, 
                    varBinding, 
                    node.getQualifier() // Der Receiver links vom Punkt (z.B. paramB oder memberA.fieldB)
                );
            }
        }
        return true; // Kinder weiter besuchen für Ketten wie memberA.fieldB.fieldC!
    }

    /**
     * Fall C: Unqualifizierter Feldzugriff (implizites 'this', z.B. "memberB.doSomething()")
     * Hier ist "memberB" ein SimpleName ohne "this." davor.
     */
    @Override
    public boolean visit(SimpleName node) {
        ASTNode parent = node.getParent();

        // 1. Ignorieren, wenn der SimpleName nur der rechte Teil (das Ziel-Feld) eines 
        // bereits behandelten FieldAccess oder QualifiedName ist:
        if (parent instanceof FieldAccess fa && fa.getName() == node) {
            return false;
        }
        if (parent instanceof QualifiedName qn && qn.getName() == node) {
            return false;
        }
        // 2. Deklarationen selbst ignorieren (z.B. private ClassB memberB;)
        if (parent instanceof VariableDeclaration && ((VariableDeclaration) parent).getName() == node) {
            return false;
        }

        IBinding binding = node.resolveBinding();
        if (binding instanceof IVariableBinding varBinding && varBinding.isField()) {
            // Es ist ein direkter Feldzugriff auf ein Feld der eigenen Klasse (implizites this)
            handleFieldAccess(node, varBinding, null);
        }
        return false;
    }

    /**
     * Zentrale Methode: Erzeugt den :FieldAccess-Knoten in Neo4j und verknüpft Ziel + Receiver
     */
    private void handleFieldAccess(Expression accessNode, IVariableBinding fieldBinding, Expression receiverExpr) {
        if (currentMethodKey == null) return;

        // 1. Eindeutige ID für diese konkrete Zugriffsstelle im AST
        String accessSiteId = currentMethodKey + "@FA@" + accessNode.getStartPosition();

        // 2. Eindeutige ID des deklarierten Feldes (getVariableDeclaration() wegen Generics!)
        String targetFieldKey = fieldBinding.getVariableDeclaration().getKey();

        // 3. READ / WRITE / READ_WRITE bestimmen
        String accessType = determineAccessType(accessNode);

        // --- NEO4J: Knoten & Ziel-Kante anlegen ---
        // MERGE (fa:FieldAccess {id: accessSiteId})
        // SET fa.accessType = accessType, fa.code = accessNode.toString()
        // MATCH (m:Method {id: currentMethodKey}), (f:Field {id: targetFieldKey})
        // MERGE (m)-[:CONTAINS_ACCESS]->(fa)-[:TARGETS_FIELD]->(f)

        // 4. Receiver auflösen (Worüber wurde auf das Feld zugegriffen?)
        if (receiverExpr != null && !(receiverExpr instanceof ThisExpression)) {
            String receiverId = resolveFlowNodeId(receiverExpr, currentMethodKey);
            if (receiverId != null) {
                // --- NEO4J: Receiver-Kante ziehen ---
                // MATCH (fa:FieldAccess {id: accessSiteId}), (rec {id: receiverId})
                // MERGE (fa)-[:ON_RECEIVER]->(rec)
            }
        }
    }
```

### 2\. Erweiterte resolveFlowNodeId für verkettete Feldzugriffe

Hier steckt ein entscheidender Kniff für Ketten wie **`a.getB().fieldC.doSomething()`** oder **`a.fieldB.fieldC`**: Wenn ein Feld als Zwischenschritt in einer Kette gelesen wird (wie `.fieldB` in `a.fieldB.fieldC`), ist der Receiver von `.fieldC` nicht einfach das statische `:Field` `fieldB`, sondern **der konkrete Lesezugriff (`:FieldAccess`)** auf `fieldB`! Nur so bleibt die Kette `a -> fieldB -> fieldC` im Graphen lückenlos zusammenhängend.

Nur wenn am Anfang der Kette eine direkte Variable (`SimpleName` oder `this.memberB`) steht, verweisen wir direkt auf den `:Variable` -Knoten (`:Field`, `:Parameter`, `:LocalVariable`):

```
private String resolveFlowNodeId(Expression expr, String enclosingMethodKey) {
    if (expr == null) return null;

    // 1. Klammern und Casts auspacken: ((ClassB) a.getB()).fieldC
    if (expr instanceof ParenthesizedExpression paren) {
        return resolveFlowNodeId(paren.getExpression(), enclosingMethodKey);
    }
    if (expr instanceof CastExpression cast) {
        return resolveFlowNodeId(cast.getExpression(), enclosingMethodKey);
    }

    // 2. Receiver ist ein Methodenaufruf: a.getB().fieldC -> verweist auf :CallSite von getB()
    if (expr instanceof MethodInvocation chainedCall) {
        return enclosingMethodKey + "@" + chainedCall.getStartPosition();
    }
    if (expr instanceof ClassInstanceCreation newCall) {
        return enclosingMethodKey + "@" + newCall.getStartPosition();
    }

    // 3. Receiver ist ein einfacher Variablenname (paramB, localB oder unqualifiziertes memberB)
    if (expr instanceof SimpleName simpleName) {
        IBinding b = simpleName.resolveBinding();
        if (b instanceof IVariableBinding vb) {
            return vb.getVariableDeclaration().getKey(); // Direkt auf :Parameter, :LocalVariable oder :Field
        }
    }

    // 4. Receiver ist "this.memberB" -> direkt auf :Field (oder wahlweise auf den :FieldAccess)
    if (expr instanceof FieldAccess fa) {
        if (fa.getExpression() instanceof ThisExpression) {
            IVariableBinding vb = fa.resolveFieldBinding();
            if (vb != null) return vb.getVariableDeclaration().getKey();
        }
        // Verketteter Feldzugriff als Receiver! z.B. (a.getB().fieldB).fieldC
        // -> Verweist auf den :FieldAccess-Knoten des vorherigen Kettenglieds!
        return enclosingMethodKey + "@FA@" + fa.getStartPosition();
    }

    // 5. Receiver ist ein QualifiedName: z.B. (paramA.fieldB).fieldC
    if (expr instanceof QualifiedName qn) {
        IBinding b = qn.resolveBinding();
        if (b instanceof IVariableBinding vb && vb.isField()) {
            // Verweist auf den :FieldAccess-Knoten von paramA.fieldB!
            return enclosingMethodKey + "@FA@" + qn.getStartPosition();
        }
    }

    return null;
}
```

### 3\. Exakte READ / WRITE / READ\_WRITE Erkennung

Ein Feldzugriff in einer Kette (`a.fieldB.fieldC = 10`) steckt als Kind-Knoten im äußeren Ausdruck. Deshalb klettern wir zunächst eventuelle Klammern nach oben (`(a.fieldC) = 10`), prüfen dann aber, ob unser Knoten wirklich **ganz außen links** von der Zuweisung steht:

```
private String determineAccessType(Expression accessNode) {
        ASTNode current = accessNode;
        ASTNode parent = current.getParent();

        // Klammern nach oben überspringen: (b.field) = 5;
        while (parent instanceof ParenthesizedExpression) {
            current = parent;
            parent = current.getParent();
        }

        // Wichtig bei Ketten (a.fieldB.fieldC = 5):
        // Für 'a.fieldB' ist der Parent der äußere QualifiedName/FieldAccess 'a.fieldB.fieldC'.
        // Damit fällt 'a.fieldB' hier korrekterweise durch und bekommt 'READ'!

        // 1. Zuweisung: b.field = 5 oder b.field += 5
        if (parent instanceof Assignment assignment && assignment.getLeftHandSide() == current) {
            if (assignment.getOperator() == Assignment.Operator.ASSIGN) {
                return "WRITE";
            } else {
                return "READ_WRITE"; // +=, -=, *=, etc.
            }
        }

        // 2. Postfix: b.field++ oder b.field--
        if (parent instanceof PostfixExpression) {
            return "READ_WRITE";
        }

        // 3. Prefix: ++b.field oder --b.field (aber NICHT !b.flag oder -b.value)
        if (parent instanceof PrefixExpression prefix) {
            PrefixExpression.Operator op = prefix.getOperator();
            if (op == PrefixExpression.Operator.INCREMENT || op == PrefixExpression.Operator.DECREMENT) {
                return "READ_WRITE";
            }
        }

        // Alles andere (inklusive Receiver in einer Kette wie b.field.doSomething()) ist READ
        return "READ";
    }
}
```

### 4\. Was entsteht dadurch im Graphen bei a.getB().fieldC = 42?

Angenommen, `a` ist ein `:Parameter` vom Typ `ClassA`, `getB()` liefert `ClassB` und `fieldC` ist ein Feld in `ClassB`. Durch das Zusammenspiel von `visit(MethodInvocation)` und `visit(FieldAccess)` entsteht in Neo4j automatisch diese Kette:

1. `visit(FieldAccess)` sieht `a.getB().fieldC`:
	- `determineAccessType` erkennt den Parent `Assignment` (`=`) $\rightarrow$ erzeugt `(:FieldAccess {id: "...@FA@10", accessType: "WRITE"})-[:TARGETS_FIELD]->(:Field {name: "fieldC"})`.
		- `resolveFlowNodeId(node.getExpression())` untersucht `a.getB()`. Da das eine `MethodInvocation` ist, gibt sie die ID der `:CallSite` zurück: `"...@" + getB.getStartPosition()`.
		- Es entsteht die Kante: `(:FieldAccess {name: "fieldC"})-[:ON_RECEIVER]->(:CallSite {name: "getB()"})`.
2. Der Visitor traversiert weiter nach unten in `visit(MethodInvocation)` für `a.getB()`:
	- Erzeugt `(:CallSite {id: "...@10"})-[:TARGETS_METHOD]->(:Method {name: "getB"})`.
		- `resolveFlowNodeId` für `a` liefert den Key von `:Parameter {name: "a"}`.
		- Es entsteht die Kante: `(:CallSite {name: "getB()"})-[:ON_RECEIVER]->(:Parameter {name: "a"})`.

Damit kannst du in Cypher beliebig lange Ketten (`a.getB().getC().fieldD`) mit einem variablen Pfad-Pattern über `[:ON_RECEIVER*1..5]` traversieren:

```
// Findet direkte UND beliebig verkettete Zugriffe von ClassA auf Member von ClassB
MATCH (classA:Class {name: 'ClassA'})-[:DECLARES_METHOD]->(mA:Method)
MATCH (classB:Class {name: 'ClassB'})-[:DECLARES_METHOD|DECLARES_FIELD]->(targetB)
MATCH (mA)-[:CONTAINS_CALL|CONTAINS_ACCESS]->(site)-[:TARGETS_METHOD|TARGETS_FIELD]->(targetB)

// Verfolge die Receiver-Kette zurück bis zur Wurzel-Variable (oder dem ersten Aufruf)
OPTIONAL MATCH path = (site)-[:ON_RECEIVER*1..5]->(rootReceiver)
WHERE NOT (rootReceiver)-[:ON_RECEIVER]->()

RETURN 
    mA.name AS inMethod,
    targetB.name AS accessedInB,
    site.accessType AS readOrWrite,
    [n IN nodes(path) | coalesce(n.name, n.code)] AS receiverChain,
    labels(rootReceiver) AS rootMechanism
```

Soll ich dir noch zeigen, wie du Assignment-Flüsse (a = b) und Return-Statements ergänzt?

---

**You**

Ja, zeige mir bitte noch die ASTVisitor-Methoden für Assignment (Variablen-Zuweisungen) und ReturnStatement, damit auch der Datenfluss aus Methoden heraus vollständig ist.

---

**Gemini**

Um den Datenfluss (Data Flow) innerhalb einer Methode und über Methodengrenzen hinweg zu schließen, fehlen noch genau drei Bausteine:

1. **`Assignment` (`lhs = rhs`)**: Werte fließen nach der Initialisierung in lokale Variablen, eigene Felder oder fremde Objektfelder.
2. **`ReturnStatement` (`return expr;`)**: Welcher Wert (Variable, Feldzugriff oder Methodenaufruf) verlässt die Methode?
3. **Vermeidung von Lambda-/Anonymous-Class-Leaks**: Wenn innerhalb einer Methode ein Lambda oder eine anonyme Klasse steht, darf deren `return` nicht fälschlicherweise der äußeren Methode zugeordnet werden.

Hier ist die nahtlose Erweiterung für deinen `DataFlowAstVisitor`:

### 1\. Assignment und ReturnStatement im ASTVisitor

Für die Ziel-Auflösung auf der linken Seite einer Zuweisung (`lhs`) müssen wir eine Feinheit beachten:

- Steht links eine **einfache Variable** (`localB = createB();` oder `this.memberB = paramB;`), soll der Datenfluss (`[:FLOWS_TO]`) direkt in den `:LocalVariable` - bzw. `:Field` -Knoten münden, damit spätere Lesezugriffe auf diese Variable die Quelle kennen.
- Steht links ein **verketteter Feldzugriff** auf einem fremden Objekt (`a.getB().fieldC = val;`), wollen wir **sowohl** wissen, dass in die konkrete `:FieldAccess` -Stelle geschrieben wurde, **als auch**, dass das Feld `:Field {name: "fieldC"}` Daten aus `val` erhält.

```
/**
 * 1. Zuweisungen: localB = createB(), this.memberB = paramB, a.getB().fieldC = x
 */
@Override
public boolean visit(Assignment node) {
    if (currentMethodKey == null) return true;

    Expression lhs = node.getLeftHandSide();
    Expression rhs = node.getRightHandSide();

    // Quelle (rechts vom '=') auflösen: Variable, CallSite oder FieldAccess
    String sourceId = resolveFlowNodeId(rhs, currentMethodKey);
    if (sourceId == null) return true;

    // Ziel (links vom '=') auflösen
    String targetId = resolveAssignmentTargetId(lhs, currentMethodKey);
    if (targetId != null) {
        // --- NEO4J: Datenfluss-Kante von rechts nach links ---
        // MATCH (src {id: sourceId}), (tgt {id: targetId})
        // MERGE (src)-[:FLOWS_TO {line: getLine(node)}]->(tgt)
    }

    // Sonderfall: Verketteter Schreibzugriff auf ein Feld (z.B. a.getB().fieldC = rhs)
    // Hier fließt der Wert zusätzlich in den konkreten :FieldAccess-Knoten (WRITE),
    // damit man sieht, WELCHER konkrete Schreibzugriff welchen Wert geschrieben hat.
    if (isChainedFieldAccess(lhs)) {
        String fieldAccessSiteId = currentMethodKey + "@FA@" + unwrap(lhs).getStartPosition();
        // MATCH (src {id: sourceId}), (fa:FieldAccess {id: fieldAccessSiteId})
        // MERGE (src)-[:FLOWS_TO]->(fa)
    }

    return true; // Kinder weiter besuchen, damit CallSites/FieldAccesses in LHS und RHS erfasst werden
}

/**
 * 2. Return-Statements: return localB; / return createB(); / return this.memberB.fieldC;
 */
@Override
public boolean visit(ReturnStatement node) {
    if (currentMethodKey == null || node.getExpression() == null) {
        return true;
    }

    // Sicherheitscheck: Gehört dieses 'return' wirklich zu currentMethodKey 
    // oder steckt es in einem Lambda / einer AnonymousClass innerhalb der Methode?
    if (!belongsToCurrentMethod(node)) {
        return true;
    }

    String returnedSourceId = resolveFlowNodeId(node.getExpression(), currentMethodKey);
    if (returnedSourceId != null) {
        // --- NEO4J: Verbindet die Datenquelle mit der zurückgebenden Methode ---
        // MATCH (src {id: returnedSourceId}), (m:Method {id: currentMethodKey})
        // MERGE (src)-[:RETURNED_BY {pos: node.getStartPosition()}]->(m)
    }

    return true;
}
```

### 2\. Die zugehörigen Hilfsmethoden

```
/**
 * Löst das Ziel (LHS) einer Zuweisung zur zugrundeliegenden Variable (:LocalVariable, :Parameter, :Field) auf.
 */
private String resolveAssignmentTargetId(Expression lhs, String enclosingMethodKey) {
    Expression cleanLhs = unwrap(lhs);

    if (cleanLhs instanceof Name name) {
        IBinding b = name.resolveBinding();
        if (b instanceof IVariableBinding vb) {
            return vb.getVariableDeclaration().getKey();
        }
    }
    if (cleanLhs instanceof FieldAccess fa) {
        IVariableBinding vb = fa.resolveFieldBinding();
        if (vb != null) {
            return vb.getVariableDeclaration().getKey();
        }
    }
    if (cleanLhs instanceof ArrayAccess arrayAccess) {
        // Bei arr[0] = b fließt der Wert in das Array-Objekt
        return resolveAssignmentTargetId(arrayAccess.getArray(), enclosingMethodKey);
    }
    return null;
}

/**
 * Prüft, ob die linke Seite einer Zuweisung ein qualifizierter/verketteter Feldzugriff ist (nicht bloß 'this.f' oder 'f').
 */
private boolean isChainedFieldAccess(Expression lhs) {
    Expression clean = unwrap(lhs);
    if (clean instanceof FieldAccess fa) {
        return !(fa.getExpression() instanceof ThisExpression);
    }
    if (clean instanceof QualifiedName qn) {
        IBinding b = qn.resolveBinding();
        return (b instanceof IVariableBinding vb && vb.isField());
    }
    return false;
}

/**
 * Entfernt Klammern und Casts um eine Expression.
 */
private Expression unwrap(Expression expr) {
    while (expr instanceof ParenthesizedExpression || expr instanceof CastExpression) {
        if (expr instanceof ParenthesizedExpression p) expr = p.getExpression();
        else if (expr instanceof CastExpression c) expr = c.getExpression();
    }
    return expr;
}

/**
 * Verhindert, dass ein 'return' innerhalb eines Lambdas der äußeren Methode zugeschrieben wird.
 */
private boolean belongsToCurrentMethod(ASTNode node) {
    ASTNode parent = node.getParent();
    while (parent != null) {
        if (parent instanceof LambdaExpression || parent instanceof AnonymousClassDeclaration) {
            return false; // Gehört zum Lambda / zur anonymen Klasse!
        }
        if (parent instanceof MethodDeclaration) {
            return true; // Direkt in unserer aktuellen Methode gefunden
        }
        parent = parent.getParent();
    }
    return false;
}
```

### 3\. Kleines, aber wichtiges Detail für resolveFlowNodeId: Ternary Operator (?:)

In realem Java-Code kommt bei Zuweisungen und `return` -Statements häufig eine `ConditionalExpression` vor, z. B.:`ClassB b = (flag) ? createB() : defaultB;` oder `return (b != null) ? b : fallbackB;`

Wenn `resolveFlowNodeId` nur einen einzelnen String zurückgibt, geht bei `a ? b : c` ein Zweig verloren. Deshalb lohnt es sich, `resolveFlowNodeId` auf **`List<String> resolveFlowNodeIds(...)`** umzustellen:

```
private List<String> resolveFlowNodeIds(Expression expr, String enclosingMethodKey) {
    if (expr == null) return List.of();
    Expression clean = unwrap(expr);

    // Ternary Operator (cond ? thenExpr : elseExpr) -> BEIDE Pfade liefern Datenfluss!
    if (clean instanceof ConditionalExpression cond) {
        List<String> ids = new ArrayList<>();
        ids.addAll(resolveFlowNodeIds(cond.getThenExpression(), enclosingMethodKey));
        ids.addAll(resolveFlowNodeIds(cond.getElseExpression(), enclosingMethodKey));
        return ids;
    }

    // Alle bisherigen Fälle (MethodInvocation, SimpleName, FieldAccess, QualifiedName)
    String singleId = resolveSingleFlowNodeId(clean, enclosingMethodKey);
    return (singleId != null) ? List.of(singleId) : List.of();
}
```

Damit iterierst du in `visit(Assignment)`, `visit(ReturnStatement)` und `visit(VariableDeclarationFragment)` einfach mit einer kurzen `for (String sourceId : resolveFlowNodeIds(...))` -Schleife und erzeugst bei einem Ternary-Ausdruck automatisch **zwei** `[:FLOWS_TO]` - bzw. `[:RETURNED_BY]` -Kanten in Neo4j.

### 4\. Wie der interprozedurale Flow damit in Neo4j geschlossen wird

Durch die neue Kante **`(source)-[:RETURNED_BY]->(:Method)`** ist dein Datenfluss jetzt sogar **über Methodengrenzen hinweg (interprozedural)** durchgängig abfragbar.

Betrachten wir dieses Szenario in `ClassA`:

```
private ClassB helperGetB() {
    return this.memberB; // (:Field {name:"memberB"})-[:RETURNED_BY]->(:Method {name:"helperGetB"})
}

public void execute() {
    ClassB local = helperGetB(); // (:CallSite)-[:TARGETS_METHOD]->(:Method {name:"helperGetB"})
                                 // (:CallSite)-[:FLOWS_TO]->(:LocalVariable {name:"local"})
    local.doWork();              // (:CallSite {name:"doWork"})-[:ON_RECEIVER]->(:LocalVariable {name:"local"})
}
```

Obwohl `execute()` formal über eine `:LocalVariable` auf `ClassB.doWork()` zugreift, kannst du mit einer einzigen Cypher-Query herausfinden, dass die Instanz ursprünglich aus dem eigenen Member-Feld `this.memberB` über die Hilfsmethode `helperGetB()` stammt:

```
MATCH (classA:Class {name: 'ClassA'})-[:DECLARES_METHOD]->(mA:Method)
MATCH (classB:Class {name: 'ClassB'})-[:DECLARES_METHOD|DECLARES_FIELD]->(targetB)
MATCH (mA)-[:CONTAINS_CALL|CONTAINS_ACCESS]->(site)-[:TARGETS_METHOD|TARGETS_FIELD]->(targetB)

// 1. Unmittelbarer Receiver (z.B. LocalVariable 'local')
OPTIONAL MATCH (site)-[:ON_RECEIVER]->(directReceiver)

// 2. Interprozedurale Rückverfolgung der Herkunft:
// Folgt Zuweisungen (:FLOWS_TO) UND springt bei Methodenaufrufen über (:TARGETS_METHOD)<-[:RETURNED_BY]
// in die aufgerufene Factory-/Getter-Methode hinein!
OPTIONAL MATCH originPath = (site)-[:ON_RECEIVER]->()
    (()<-[:FLOWS_TO|ON_RECEIVER]-() | ()-[:TARGETS_METHOD]->()<-[:RETURNED_BY]-())*0..5
    (ultimateOrigin:Variable)

RETURN 
    mA.name AS callerMethod,
    targetB.name AS accessedBMember,
    labels(directReceiver)[0] AS directMechanism,
    directReceiver.name AS directVarName,
    labels(ultimateOrigin)[0] AS trueOriginType, // Zeigt 'Field', obwohl direkt über 'LocalVariable' aufgerufen!
    ultimateOrigin.name AS trueOriginName
```

*(Hinweis zur Cypher-Syntax: Das Quantified Path Pattern `(()<-[:FLOWS_TO...])*0..5` wird ab **Neo4j 5.9+** nativ und hochperformant unterstützt.)*

Soll ich dir noch zeigen, wie du die erzeugten Knoten und Kanten per Batch-UNWIND performant nach Neo4j schreibst?

---

**You**

Ja, zeige mir bitte noch, wie ich die im JDT ASTVisitor gesammelten Knoten und Kanten am besten strukturiere und per Batch-UNWIND performant in Neo4j importiere.

---

**Gemini**

Einzelne Cypher-Queries direkt aus dem `ASTVisitor` heraus zu feuern, bremst den Import bei größeren Codebasen massiv aus (Netzwerk-Roundtrips + Transaktions-Overhead). Der schnellste und sauberste Weg mit dem offiziellen **Neo4j Java Driver** besteht darin, die Daten im Visitor zunächst in leichtgewichtigen **Batch-Listen (als `Map<String, Object>`)** nach Label bzw. Relationship-Typ getrennt zu sammeln und sie dann per **`UNWIND $batch AS row`** in Chunks von **2.000 bis 5.000 Elementen** in Neo4j zu schreiben.

Warum getrennt nach Label und Relationship-Typ? Weil der Cypher-Query-Planner in Neo4j keine dynamischen Labels (`MERGE (n:row.label)`) ohne APOC unterstützt und statisch typisierte `UNWIND` -Queries die Indizes auf Anhieb optimal nutzen.

### 1\. Pflicht-Vorbereitung in Neo4j: Unique Constraints (Indizes)

Ohne Indizes macht jedes `MERGE (n {id: row.id})` einen kompletten Label-Scan, was ab 50.000 Knoten extrem langsam wird. Führe diese Statements einmalig beim Start deines Importers aus:

```
// Basis-Label :FlowNode (oder spezifische Labels) für schnelles Lookup bei Relationships!
CREATE CONSTRAINT flow_node_id IF NOT EXISTS FOR (n:FlowNode) REQUIRE n.id IS UNIQUE;

// Falls :Class, :Method, :Field schon existieren, stelle sicher, dass auch sie das Label :FlowNode 
// tragen ODER eigene Unique Constraints auf 'id' haben:
CREATE CONSTRAINT method_id IF NOT EXISTS FOR (m:Method) REQUIRE m.id IS UNIQUE;
CREATE CONSTRAINT field_id IF NOT EXISTS FOR (f:Field) REQUIRE f.id IS UNIQUE;
CREATE CONSTRAINT param_id IF NOT EXISTS FOR (p:Parameter) REQUIRE p.id IS UNIQUE;
CREATE CONSTRAINT local_id IF NOT EXISTS FOR (l:LocalVariable) REQUIRE l.id IS UNIQUE;
CREATE CONSTRAINT callsite_id IF NOT EXISTS FOR (c:CallSite) REQUIRE c.id IS UNIQUE;
CREATE CONSTRAINT fieldaccess_id IF NOT EXISTS FOR (fa:FieldAccess) REQUIRE fa.id IS UNIQUE;
```

**Architektur-Trick für polymorphe Kanten (`:ON_RECEIVER`, `:FLOWS_TO`):** Da eine Kante wie `(:CallSite)-[:ON_RECEIVER]->(receiver)` als Ziel ein `:Field`, einen `:Parameter`, eine `:LocalVariable`, eine andere `:CallSite` oder einen `:FieldAccess` haben kann, gibst du beim Anlegen **allen** am Datenfluss beteiligten Knoten (inklusive `:Method` und `:Field`) zusätzlich das gemeinsame Marker-Label **`:FlowNode`**. Dadurch nutzt ein `MATCH (src:FlowNode {id: row.srcId}), (tgt:FlowNode {id: row.tgtId})` immer denselben ultraschnellen B-Tree/Range-Index!

### 2\. Der GraphBatchBuffer (Sammelstelle für den ASTVisitor)

Anstatt im `ASTVisitor` Strings oder Cypher-Queries zu bauen, übergibst du ihm eine Instanz dieser Puffer-Klasse. Sobald eine Liste die Chunk-Größe (z. B. 3.000) erreicht, kann sie automatisch oder nach jedem geparsten Package/Modul geflusht werden:

```
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import java.util.*;

public class GraphBatchBuffer {

    private static final int BATCH_SIZE = 3000;
    private final Driver driver;

    // 1. Node-Batches (getrennt nach Knoten-Typ)
    private final List> parameters = new ArrayList<>();
    private final List> localVars = new ArrayList<>();
    private final List> callSites = new ArrayList<>();
    private final List> fieldAccesses = new ArrayList<>();

    // 2. Relationship-Batches (gruppiert nach Kanten-Typ -> Liste von {src, tgt, props})
    private final Map<String, List>> relationships = new HashMap<>();

    public GraphBatchBuffer(Driver driver) {
        this.driver = driver;
    }

    // --- Node Collector Methoden für den ASTVisitor ---

    public void addParameter(String id, String name, int index, String typeKey) {
        parameters.add(Map.of("id", id, "name", name, "index", index, "typeKey", typeKey));
    }

    public void addLocalVariable(String id, String name, String typeKey) {
        localVars.add(Map.of("id", id, "name", name, "typeKey", typeKey));
    }

    public void addCallSite(String id, int startPos, String code) {
        callSites.add(Map.of("id", id, "pos", startPos, "code", code));
    }

    public void addFieldAccess(String id, int startPos, String accessType, String code) {
        fieldAccesses.add(Map.of("id", id, "pos", startPos, "accessType", accessType, "code", code));
    }

    // --- Relationship Collector Methode ---

    public void addRelationship(String relType, String srcId, String tgtId, Map<String, Object> props) {
        relationships.computeIfAbsent(relType, k -> new ArrayList<>())
                .add(Map.of(
                        "src", srcId,
                        "tgt", tgtId,
                        "props", (props != null) ? props : Collections.emptyMap()
                ));
    }

    public void addRelationship(String relType, String srcId, String tgtId) {
        addRelationship(relType, srcId, tgtId, Collections.emptyMap());
    }
```

### 3\. Der zweistufige Batch-Flush nach Neo4j (Nodes zuerst, dann Edges)

Beim Schreiben in Neo4j ist die **Reihenfolge entscheidend**:

1. **Phase 1: Alle Nodes schreiben** (damit bei Vorwärts-Referenzen oder Methodenaufrufen quer durch die Klasse garantiert schon jeder Ziel-Knoten existiert).
2. **Phase 2: Alle Relationships schreiben**.

Außerdem vermeidest du bei `UNWIND` -Batches in Neo4j das berüchtigte **"Eager-Operator"-Problem**, indem du Nodes und Relationships niemals im selben Query-Statement mischst:

```
public void flushAll() {
        try (Session session = driver.session()) {
            // 0. Sicherstellen, dass bereits existierende :Method und :Field Nodes das :FlowNode Label haben
            // (Alternativ direkt beim initialen Class/Method/Field-Import setzen!)
            session.executeWrite(tx -> {
                tx.run("MATCH (n:Method|Field) WHERE NOT n:FlowNode SET n:FlowNode");
                return null;
            });

            // PHASE 1: Nodes in Chunks importieren
            flushBatch(session, parameters, """
                UNWIND $batch AS row
                MERGE (p:Parameter:Variable:FlowNode {id: row.id})
                SET p.name = row.name, p.index = row.index, p.typeKey = row.typeKey
                """);

            flushBatch(session, localVars, """
                UNWIND $batch AS row
                MERGE (l:LocalVariable:Variable:FlowNode {id: row.id})
                SET l.name = row.name, l.typeKey = row.typeKey
                """);

            flushBatch(session, callSites, """
                UNWIND $batch AS row
                MERGE (c:CallSite:FlowNode {id: row.id})
                SET c.pos = row.pos, c.code = row.code
                """);

            flushBatch(session, fieldAccesses, """
                UNWIND $batch AS row
                MERGE (fa:FieldAccess:FlowNode {id: row.id})
                SET fa.pos = row.pos, fa.accessType = row.accessType, fa.code = row.code
                """);

            // PHASE 2: Relationships nach Typ getrennt in Chunks importieren
            for (Map.Entry<String, List>> entry : relationships.entrySet()) {
                String relType = entry.getKey(); // z.B. "ON_RECEIVER", "FLOWS_TO", "CONTAINS_CALL"
                List> relList = entry.getValue();

                // Da relType aus unseren eigenen Konstanten stammt, ist String-Interpolation hier sicher & schnell
                String relQuery = String.format("""
                    UNWIND $batch AS row
                    MATCH (src:FlowNode {id: row.src})
                    MATCH (tgt:FlowNode {id: row.tgt})
                    MERGE (src)-[r:%s]->(tgt)
                    SET r += row.props
                    """, relType);

                flushBatch(session, relList, relQuery);
            }
            relationships.clear();
        }
    }

    /**
     * Teilt große Listen in handliche Chunks (z.B. 3.000 Einträge) pro Transaktion auf,
     * damit der Heap-Speicher von Neo4j geschont wird.
     */
    private void flushBatch(Session session, List> list, String cypher) {
        if (list.isEmpty()) return;

        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            List> chunk = list.subList(i, Math.min(i + BATCH_SIZE, list.size()));
            session.executeWrite(tx -> {
                tx.run(cypher, Values.parameters("batch", chunk));
                return null;
            });
        }
        list.clear();
    }
}
```

### 4\. Saubere Einbindung in deinen DataFlowAstVisitor

Dein `ASTVisitor` wird dadurch extrem übersichtlich, weil er nur noch Methoden auf dem `GraphBatchBuffer` aufruft:

```
// Beispiel in handleFieldAccess(...) im DataFlowAstVisitor:
private void handleFieldAccess(Expression accessNode, IVariableBinding fieldBinding, Expression receiverExpr) {
    if (currentMethodKey == null) return;

    String accessSiteId = currentMethodKey + "@FA@" + accessNode.getStartPosition();
    String targetFieldKey = fieldBinding.getVariableDeclaration().getKey();
    String accessType = determineAccessType(accessNode);

    // 1. Node registrieren
    buffer.addFieldAccess(accessSiteId, accessNode.getStartPosition(), accessType, accessNode.toString());

    // 2. Struktur- & Ziel-Kanten registrieren
    buffer.addRelationship("CONTAINS_ACCESS", currentMethodKey, accessSiteId);
    buffer.addRelationship("TARGETS_FIELD", accessSiteId, targetFieldKey);

    // 3. Receiver-Kante registrieren
    if (receiverExpr != null && !(receiverExpr instanceof ThisExpression)) {
        for (String receiverId : resolveFlowNodeIds(receiverExpr, currentMethodKey)) {
            buffer.addRelationship("ON_RECEIVER", accessSiteId, receiverId);
        }
    }
}
```

### 5\. Performance-Tipp für Multi-Threading (Paralleles JDT Parsing)

Falls du mit `ASTParser.createASTs(...)` mehrere CompilationUnits parallel parst:

- Gib jedem Worker-Thread seine eigene Instanz des `DataFlowAstVisitor` und einen lokalen `GraphBatchBuffer` (ohne Driver-Referenz).
- Schreibe die gesammelten Listen über einen **einzigen dedizierten Writer-Thread** (oder sequenziell pro Modul) nach Neo4j.
- Grund: Wenn mehrere Threads gleichzeitig Beziehungen auf denselben Ziel-Knoten (z. B. `(:Field)` oder `(:Method)` einer zentralen Klasse `ClassB`) per `MERGE` anlegen, holt sich Neo4j einen Write-Lock auf den Knoten in `ClassB`. Ein sequenzieller Batch-Writer für die Relationships verhindert **Deadlocks (`TransientException`)** komplett und schafft problemlos **50.000 bis 100.000 Elemente pro Sekunde**.

Soll ich dir noch zeigen, wie du Vererbung und Polymorphie (Interface-Aufrufe auf ClassB) im Modell berücksichtigst?

---

**You**

Wie erweitere ich das Neo4j-Modell und die Cypher-Abfragen, wenn ClassA nur gegen ein Interface IService programmiert ist und ClassB dieses Interface implementiert?

---

**Gemini**

Wenn `ClassA` nur gegen ein Interface `IService` (oder eine abstrakte Basisklasse) programmiert ist, löst Eclipse JDT beim Aufruf `service.doWork()` das `IMethodBinding` statisch zur **Interface-Methode** `IService.doWork()` auf – nicht zur konkreten Implementierung in `ClassB`.

Ohne Erweiterung endet deine Kante `(:CallSite)-[:TARGETS_METHOD]->(:Method)` also an `IService.doWork()`, und eine Abfrage nach Zugriffen von `ClassA` auf `ClassB` findet nichts (es sei denn, `ClassA` instanziiert `new ClassB()` direkt).

Um Polymorphie und Dynamic Dispatch sauber abzubilden, erweiterst du das Graph-Modell auf **zwei Ebenen**: der **Typ-Hierarchie** und der **Methoden-Überschreibung (`OVERRIDES`)**.

### 1\. Erweiterung des Neo4j-Modells

Du benötigst keine neuen Node-Labels (Interfaces sind ebenfalls `:Class` -Nodes mit einem Property `isInterface: true` oder einem Zusatz-Label `:Interface`), sondern drei neue Struktur-Relationships:

- **`(:Class)-[:IMPLEMENTS]->(:Class)`**: `ClassB` implementiert `IService` (bzw. `InterfaceSub` erweitert `InterfaceSuper`).
- **`(:Class)-[:EXTENDS]->(:Class)`**: `ClassB` erbt von `AbstractBaseB`.
- **`(:Method)-[:OVERRIDES]->(:Method)`**: Die konkrete Methode `ClassB.doWork()` überschreibt/implementiert die abstrakte/Interface-Methode `IService.doWork()`.

**Warum ist `[:OVERRIDES]` direkt zwischen `:Method` -Nodes so wichtig?** Wenn du in Cypher nur `[:IMPLEMENTS]` zwischen Klassen hättest, müsstest du zur Abfragezeit über Methodennamen und Parameter-Signaturen joinen (`WHERE mInterface.name = mImpl.name AND mInterface.signature = mImpl.signature`). Mit einer expliziten `[:OVERRIDES]` -Kante von `ClassB.doWork` nach `IService.doWork` wird der polymorphe Sprung zu einer einzigen, blitzschnellen Zeiger-Traversierung im Graphen.

### 2\. Extraktion von IMPLEMENTS, EXTENDS und OVERRIDES mit Eclipse JDT

In JDT gibt es eine wichtige Besonderheit bei `IMethodBinding.overrides(IMethodBinding other)`: Du musst selbst durch die Superklassen und Interfaces (`ITypeBinding`) wandern, um die überschriebenen Basis-Methoden zu finden.

Hier ist die exakte JDT-Logik, die du beim Besuch einer `TypeDeclaration` (bzw. `MethodDeclaration`) in deinen `DataFlowAstVisitor` einhängst:

```
@Override
public boolean visit(TypeDeclaration node) {
    ITypeBinding typeBinding = node.resolveBinding();
    if (typeBinding == null) return true;

    String classKey = typeBinding.getTypeDeclaration().getKey();

    // 1. EXTENDS (Superklasse, falls nicht java.lang.Object)
    ITypeBinding superclass = typeBinding.getSuperclass();
    if (superclass != null && !"java.lang.Object".equals(superclass.getQualifiedName())) {
        String superKey = superclass.getTypeDeclaration().getKey();
        buffer.addRelationship("EXTENDS", classKey, superKey);
    }

    // 2. IMPLEMENTS (Direkte Interfaces)
    for (ITypeBinding iface : typeBinding.getInterfaces()) {
        String ifaceKey = iface.getTypeDeclaration().getKey();
        buffer.addRelationship("IMPLEMENTS", classKey, ifaceKey);
    }

    return true;
}

@Override
public boolean visit(MethodDeclaration node) {
    IMethodBinding methodBinding = node.resolveBinding();
    if (methodBinding == null) return false;

    this.currentMethodKey = methodBinding.getMethodDeclaration().getKey();

    // 3. OVERRIDES-Beziehungen zu Superklassen und Interfaces auflösen
    if (!methodBinding.isConstructor() && !Modifier.isStatic(methodBinding.getModifiers())) {
        collectOverriddenMethods(methodBinding, methodBinding.getDeclaringClass(), new HashSet<>());
    }

    return true;
}

/**
 * Sucht rekursiv in Superklassen und Interfaces nach Methoden, die von 'subMethod'
 * überschrieben oder implementiert werden, und legt (:Method)-[:OVERRIDES]->(:Method) an.
 */
private void collectOverriddenMethods(IMethodBinding subMethod, ITypeBinding currentType, Set<String> visitedTypes) {
    if (currentType == null) return;

    // Alle direkten Supertypen sammeln (Superklasse + Interfaces)
    List<ITypeBinding> superTypes = new ArrayList<>();
    if (currentType.getSuperclass() != null && !"java.lang.Object".equals(currentType.getSuperclass().getQualifiedName())) {
        superTypes.add(currentType.getSuperclass());
    }
    Collections.addAll(superTypes, currentType.getInterfaces());

    String subMethodKey = subMethod.getMethodDeclaration().getKey();

    for (ITypeBinding superType : superTypes) {
        String superTypeKey = superType.getTypeDeclaration().getKey();
        if (!visitedTypes.add(superTypeKey)) continue;

        boolean foundInThisSuperType = false;
        for (IMethodBinding superCandidate : superType.getDeclaredMethods()) {
            // JDT-Prüfung, ob subMethod diese Methode überschreibt (berücksichtigt auch Generics/Erasure!)
            if (subMethod.overrides(superCandidate)) {
                String superMethodKey = superCandidate.getMethodDeclaration().getKey();
                buffer.addRelationship("OVERRIDES", subMethodKey, superMethodKey);
                foundInThisSuperType = true;
            }
        }

        // Falls der direkte Supertyp die Methode nicht deklariert (z.B. abstrakte Zwischenklasse),
        // weiter nach oben in der Hierarchie suchen:
        if (!foundInThisSuperType) {
            collectOverriddenMethods(subMethod, superType, visitedTypes);
        }
    }
}
```

### 3\. Erweiterte Cypher-Abfragen: Statischer vs. Polymorpher Flow

Mit `[:OVERRIDES]` kannst du deine bisherigen Cypher-Queries nun minimal erweitern, um sowohl direkte Zugriffe auf `ClassB` als auch indirekte Zugriffe über `IService` zu finden.

#### Abfrage A: Alle potenziellen Zugriffe von ClassA auf ClassB (inkl. Interface-Dispatch)

Der Trick liegt im Pfad-Muster **`-[:TARGETS_METHOD]->(:Method)<-[:OVERRIDES*0..]-(targetB:Method)`**:

- `*0` deckt den Fall ab, dass `ClassA` direkt `ClassB.doWork()` aufruft.
- `*1..` deckt den Fall ab, dass `ClassA` die Interface-Methode `IService.doWork()` aufruft, welche von `ClassB.doWork()` via `[:OVERRIDES]` implementiert wird!

```
MATCH (classA:Class {name: 'ClassA'})-[:DECLARES_METHOD]->(mA:Method)
MATCH (classB:Class {name: 'ClassB'})-[:DECLARES_METHOD|DECLARES_FIELD]->(targetB)

// Findet sowohl direkte Aufrufe/Feldzugriffe als auch Aufrufe über ein Interface/Superklasse!
MATCH (mA)-[:CONTAINS_CALL|CONTAINS_ACCESS]->(site)-[:TARGETS_METHOD|TARGETS_FIELD]->(calledMember)
WHERE calledMember = targetB 
   OR (targetB)-[:OVERRIDES*1..5]->(calledMember)

// Über welche Variable in ClassA lief der Aufruf?
OPTIONAL MATCH (site)-[:ON_RECEIVER]->(receiver)
OPTIONAL MATCH (calledClass:Class)-[:DECLARES_METHOD|DECLARES_FIELD]->(calledMember)

RETURN 
    mA.name AS callerMethod,
    calledClass.name AS staticTargetType,   // z.B. 'IService' (statischer Typ beim Aufruf)
    calledMember.name AS calledMethod,      // 'doWork'
    classB.name AS concreteTargetType,      // 'ClassB' (konkrete Implementierung)
    CASE WHEN calledMember = targetB THEN 'DIRECT' ELSE 'POLYMORPHIC' END AS dispatchKind,
    labels(receiver)[0] AS viaMechanism,    // 'Field', 'Parameter', 'LocalVariable', 'CallSite'
    receiver.name AS receiverName
```

#### Abfrage B: Einschränkung durch Type-Propagation (Rapid Type Analysis im Graphen)

Was ist, wenn `IService` von **zehn** verschiedenen Klassen (`ClassB`, `ClassC`, `ClassD`...) implementiert wird? Dann liefert Abfrage A für alle zehn Klassen einen potenziellen polymorphen Treffer.

Wenn du jedoch im Graphen bereits `[:FLOWS_TO]` und `[:PASSED_AS_ARG]` erfasst hast, kannst du in Cypher prüfen, **ob überhaupt jemals eine Instanz von `ClassB` in den Receiver (z. B. das Feld `private IService service` in `ClassA`) geflossen ist** (z. B. über Konstruktor-Injection `new ClassA(new ClassB())` oder einen Setter):

```
MATCH (classA:Class {name: 'ClassA'})-[:DECLARES_METHOD]->(mA:Method)
MATCH (classB:Class {name: 'ClassB'})-[:DECLARES_METHOD]->(mB:Method)

// 1. Polymorpher Aufruf in ClassA auf eine Interface-Methode, die ClassB implementiert
MATCH (mA)-[:CONTAINS_CALL]->(site:CallSite)-[:TARGETS_METHOD]->(mIface:Method)<-[:OVERRIDES*1..5]-(mB)
MATCH (site)-[:ON_RECEIVER]->(receiver:Variable)

// 2. Prüfen, ob irgendwo im Datenfluss eine konkrete Referenz vom Typ ClassB 
// (z.B. aus 'new ClassB()' oder einem Parameter vom Typ ClassB) in diesen Receiver fließt:
MATCH (concreteSource)-[:FLOWS_TO|PASSED_AS_ARG*0..5]->(receiver)
WHERE (concreteSource)-[:OF_TYPE]->(classB)
   OR (concreteSource:CallSite)-[:TARGETS_METHOD]->(:Method)<-[:DECLARES_METHOD]-(classB) // Konstruktor/Factory von ClassB

RETURN DISTINCT
    mA.name AS callerMethod,
    receiver.name AS interfaceVariableInA,
    mIface.name AS invokedMethod,
    concreteSource.code AS concreteClassBOrigin
```

Damit hast du eine vollwertige **Points-To- / Call-Graph-Analyse (CHA + RTA)** direkt in deiner Neo4j-Datenbank abgebildet:

- Du musst beim `ASTVisitor` -Durchlauf noch nicht wissen, welche Klasse ein Interface zur Laufzeit implementiert.
- Der Visitor schreibt stur die lokalen Fakten (`TARGETS_METHOD` auf das Interface + `OVERRIDES` bei der implementierenden Klasse).
- Erst die Verknüpfung im Graphen legt offen, dass `ClassA` über das Interface `IService` effektiv mit `ClassB` gekoppelt ist.

Soll ich dir noch zeigen, wie du Konstruktor-Aufrufe (new ClassB) und Dependency Injection (@Inject / @Autowired) in diesen Flow einbindest?

---

**You**

Ja, zeige mir bitte, wie ich Konstruktor-Aufrufe (ClassInstanceCreation) und Dependency-Injection-Annotationen (@Autowired / @Inject) im JDT Visitor erfasse, damit die konkrete Instanziierung von ClassB im Flow sichtbar wird.

---

**Gemini**

Um die Lücke zwischen dem statischen Interface-Typ (`IService`) und der tatsächlich zur Laufzeit vorhandenen Instanz (`ClassB`) im Graphen zu schließen, brauchst du die **Quellen der Objekterzeugung**:

1. **Explizite Instanziierung (`new ClassB(...)`)**: Im JDT-AST ist das kein `MethodInvocation` -Knoten, sondern eine **`ClassInstanceCreation`**. Zudem verknüpfen wir beim Aufruf die übergebenen Argumente direkt mit den `:Parameter` -Knoten der Zielmethode bzw. des Konstruktors, damit Constructor- und Setter-Injection nahtlos durch den Graphen fließen.
2. **Implizite Instanziierung durch Dependency Injection (`@Autowired`, `@Inject`, `@Resource`)**: Hier gibt es im Code kein `new ClassB()`. Der DI-Container injiziert die Instanz zur Laufzeit in Felder, Konstruktoren oder Setter.

So baust du beides in dein Modell und deinen `DataFlowAstVisitor` ein:

### 1\. Erweiterung der Relationships für Instanziierung & Argument-Flow

- **`(:CallSite)-[:INSTANTIATES]->(:Class)`**: Markiert explizit, dass an dieser `:CallSite` (`new ClassB()`) eine neue Instanz von `ClassB` entsteht. (Zusätzlich zeigt `[:TARGETS_METHOD]` auf den konkreten Konstruktor-`:Method` -Knoten von `ClassB`).
- **`(argSource)-[:FLOWS_TO]->(:Parameter)`** *(Interprozeduraler Parameter-Fluss)*: Wenn beim Aufruf `new ClassA(new ClassB())` oder `a.setService(b)` ein Argument übergeben wird, ziehen wir direkt eine `[:FLOWS_TO]` -Kante vom übergebenen Ausdruck in den `:Parameter` -Knoten der aufgerufenen Methode.*Warum?* In `ClassA` steht im Konstruktor `this.service = serviceParam;`. Das erzeugt bereits `(:Parameter)-[:FLOWS_TO]->(:Field)`. Verbinden wir nun beim Aufrufer das Argument mit `:Parameter`, ist die Kette `(new ClassB())-[:FLOWS_TO]->(:Parameter)-[:FLOWS_TO]->(:Field {name: "service"})` komplett geschlossen!
- **`(:Field | :Parameter | :Method {isInjected: true})`**: Ein Property (oder Label `:InjectionPoint`), das signalisiert, dass hier ein DI-Container eine Implementierung bereitstellt.

### 2\. JDT Visitor: ClassInstanceCreation (new ClassB()) & direkter Argument-Flow

In JDT liefert `ClassInstanceCreation.resolveConstructorBinding()` das `IMethodBinding` des Konstruktors. Über `binding.getParameterTypes()` und die Reihenfolge lassen sich die Argumente direkt den Parametern zuordnen.

Da ein `IMethodBinding` am Aufrufort (Call-Site) in JDT keinen direkten Zugriff auf die `IVariableBinding` -Keys der Parameter der Zielmethode hat, nutzen wir einen **deterministischen Parameter-Key**: `targetMethodKey + "#param" + index`.

```
/**
 * 1. Parameter-Deklaration in visit(MethodDeclaration) mit deterministischer ID:
 */
@Override
public boolean visit(MethodDeclaration node) {
    IMethodBinding methodBinding = node.resolveBinding();
    if (methodBinding == null) return false;

    this.currentMethodKey = methodBinding.getMethodDeclaration().getKey();
    boolean isConstructor = methodBinding.isConstructor();

    // DI-Annotation am Konstruktor oder Setter prüfen (@Autowired, @Inject)
    boolean methodInjected = hasInjectionAnnotation(methodBinding);

    List<SingleVariableDeclaration> params = node.parameters();
    for (int i = 0; i < params.size(); i++) {
        SingleVariableDeclaration param = params.get(i);
        IVariableBinding paramBinding = param.resolveBinding();
        if (paramBinding == null) continue;

        // Deterministischer Key, den auch CallSites ohne AST der Zielmethode bilden können!
        String paramId = this.currentMethodKey + "#param" + i;
        String typeKey = paramBinding.getType().getTypeDeclaration().getKey();

        buffer.addParameter(paramId, paramBinding.getName(), i, typeKey);
        buffer.addRelationship("HAS_PARAMETER", this.currentMethodKey, paramId);
        buffer.addRelationship("OF_TYPE", paramId, typeKey);

        // Falls Konstruktor/Methode oder der Parameter selbst @Inject/@Autowired trägt:
        if (methodInjected || hasInjectionAnnotation(paramBinding)) {
            buffer.markAsInjectionPoint(paramId, extractQualifier(paramBinding));
        }
    }
    return true;
}

/**
 * 2. Konstruktor-Aufruf: new ClassB(arg1, arg2)
 */
@Override
public boolean visit(ClassInstanceCreation node) {
    if (currentMethodKey == null) return true;

    IMethodBinding ctorBinding = node.resolveConstructorBinding();
    if (ctorBinding == null) return true;

    String callSiteId = currentMethodKey + "@" + node.getStartPosition();
    String targetCtorKey = ctorBinding.getMethodDeclaration().getKey();
    String instantiatedClassKey = ctorBinding.getDeclaringClass().getTypeDeclaration().getKey();

    // 1. CallSite anlegen und mit aufrufender Methode, Ziel-Konstruktor und Klasse verknüpfen
    buffer.addCallSite(callSiteId, node.getStartPosition(), node.toString());
    buffer.addRelationship("CONTAINS_CALL", currentMethodKey, callSiteId);
    buffer.addRelationship("TARGETS_METHOD", callSiteId, targetCtorKey);
    buffer.addRelationship("INSTANTIATES", callSiteId, instantiatedClassKey);

    // 2. Übergebene Argumente direkt in die Konstruktor-Parameter fließen lassen
    linkArgumentsToParameters(node.arguments(), callSiteId, targetCtorKey);

    return true;
}
```

*(Wichtig: Damit `resolveSingleFlowNodeId` bei der Verwendung eines Parameters im Methodenrumpf denselben Key `methodKey + "#param" + index` zurückgibt, ersetzt du dort für `vb.isParameter()` einfach `vb.getVariableDeclaration().getKey()` durch `vb.getDeclaringMethod().getMethodDeclaration().getKey() + "#param" + vb.getVariableId()` oder merkst dir eine kleine Map `IVariableBinding -> paramId` pro Methode).*

Hier ist die Hilfsmethode `linkArgumentsToParameters`, die du **sowohl in `visit(ClassInstanceCreation)` als auch in `visit(MethodInvocation)`** aufrufst:

```
private void linkArgumentsToParameters(List<?> arguments, String callSiteId, String targetMethodKey) {
    for (int i = 0; i < arguments.size(); i++) {
        Expression argExpr = (Expression) arguments.get(i);
        String targetParamId = targetMethodKey + "#param" + i;

        for (String argSourceId : resolveFlowNodeIds(argExpr, currentMethodKey)) {
            // 1. Dokumentation an der CallSite
            buffer.addRelationship("PASSED_AS_ARG", argSourceId, callSiteId, Map.of("index", i));
            // 2. Direkter interprozeduraler Datenfluss in den Parameter der Zielmethode!
            buffer.addRelationship("FLOWS_TO", argSourceId, targetParamId, Map.of("viaCall", callSiteId));
        }
    }
}
```

### 3\. Dependency Injection (@Autowired, @Inject, @Component) im JDT Visitor erkennen

Wenn Spring, Jakarta CDI, Guice oder Dagger im Einsatz sind, verknüpft kein Java-Aufruf `new ClassB()` mit `ClassA`. Stattdessen erkennen wir im Visitor:

1. **Auf Seiten von `ClassA` (Consumer)**: Welche Felder oder Parameter sind Injection-Points (`@Autowired`, `@Inject`, `@Resource` oder Konstruktor einer `@Component` / `@Service` -Klasse)?
2. **Auf Seiten von `ClassB` (Provider)**: Welche Klassen sind als DI-Beans registriert (`@Service`, `@Component`, `@Repository`, `@Named`) oder werden in einer `@Configuration` -Klasse per `@Bean` -Methode erzeugt?

```
private static final Set<String> INJECT_ANNOTATIONS = Set.of(
    "org.springframework.beans.factory.annotation.Autowired",
    "jakarta.inject.Inject",
    "javax.inject.Inject",
    "jakarta.annotation.Resource",
    "javax.annotation.Resource"
);

private static final Set<String> BEAN_STEREOTYPES = Set.of(
    "org.springframework.stereotype.Component",
    "org.springframework.stereotype.Service",
    "org.springframework.stereotype.Repository",
    "org.springframework.stereotype.Controller",
    "jakarta.inject.Named",
    "javax.inject.Named",
    "jakarta.enterprise.context.ApplicationScoped",
    "jakarta.enterprise.context.RequestScoped"
);

/**
 * Prüft an Field-, Method- oder Variable-Bindings, ob eine DI-Annotation vorliegt.
 */
private boolean hasInjectionAnnotation(IBinding binding) {
    for (IAnnotationBinding annot : binding.getAnnotations()) {
        ITypeBinding type = annot.getAnnotationType();
        if (type != null && INJECT_ANNOTATIONS.contains(type.getQualifiedName())) {
            return true;
        }
    }
    return false;
}

/**
 * Extrahiert optional den @Qualifier("...") oder @Named("...") Wert für präzises DI-Matching.
 */
private String extractQualifier(IBinding binding) {
    for (IAnnotationBinding annot : binding.getAnnotations()) {
        ITypeBinding type = annot.getAnnotationType();
        if (type == null) continue;
        String qName = type.getQualifiedName();
        if (qName.endsWith(".Qualifier") || qName.endsWith(".Named") || qName.endsWith(".Resource")) {
            for (IMemberValuePairBinding pair : annot.getAllMemberValuePairs()) {
                if (("value".equals(pair.getName()) || "name".equals(pair.getName())) && pair.getValue() instanceof String s) {
                    return s;
                }
            }
        }
    }
    return "";
}
```

Beim Besuch einer `FieldDeclaration` (für Field-Injection `@Autowired private IService service;`) prüfst du `hasInjectionAnnotation(fieldBinding)` und setzt am `:Field` -Knoten `isInjected = true` sowie `qualifier = extractQualifier(fieldBinding)`. Ebenso setzt du beim Besuch einer `TypeDeclaration` (`ClassB`) ein Property `isManagedBean = true`, wenn `ClassB` eine Annotation aus `BEAN_STEREOTYPES` trägt.

### 4\. Der Post-Processing-Schritt in Neo4j: Virtuelle \[:INJECTED\_INTO\]-Kanten ziehen

Der große Vorteil von Neo4j: Du musst die komplexe DI-Auflösung **nicht** in Java programmieren! Sobald der `GraphBatchBuffer` alle Klassen, Interfaces, `@Autowired` -Felder/Parameter und `@Service` -Klassen in Neo4j geschrieben hat, führst du am Ende des Imports **ein einziges Cypher-Update** aus.

Dieses Statement simuliert den Spring/CDI-Container und zieht direkte `[:FLOWS_TO {via: 'DI'}]` -Kanten von `ClassB` in die injizierten Felder und Parameter von `ClassA`:

```
// Verbindet alle @Service/@Component-Klassen (oder ihre Unterklassen) mit passenden @Autowired/@Inject Variablen
MATCH (targetVar:Variable {isInjected: true})-[:OF_TYPE]->(declaredType:Class)
MATCH (beanClass:Class {isManagedBean: true})
WHERE beanClass = declaredType 
   OR (beanClass)-[:IMPLEMENTS|EXTENDS*1..5]->(declaredType)

// Optionaler Qualifier-Check: Wenn @Qualifier angegeben ist, muss der Bean-Name passen
WHERE targetVar.qualifier = '' 
   OR targetVar.qualifier IS NULL 
   OR toLower(beanClass.name) = toLower(targetVar.qualifier)
   OR beanClass.beanName = targetVar.qualifier

MERGE (beanClass)-[:FLOWS_TO {mechanism: 'DEPENDENCY_INJECTION'}]->(targetVar);
```

Und falls Beans über **`@Bean` -Factory-Methoden** in `@Configuration` -Klassen erzeugt werden (`@Bean public IService myService() { return new ClassB(); }`), deckst du auch das mit einer zweiten kurzen Query ab:

```
MATCH (factoryMethod:Method {isBeanFactory: true})-[:RETURNS_TYPE]->(declaredType:Class)
MATCH (targetVar:Variable {isInjected: true})-[:OF_TYPE]->(declaredType)
// Welche konkrete Klasse wird in der @Bean-Methode erzeugt und zurückgegeben?
MATCH (concreteCall:CallSite)-[:RETURNED_BY|FLOWS_TO*1..3]->(factoryMethod)
MATCH (concreteCall)-[:INSTANTIATES]->(concreteClass:Class)

MERGE (concreteClass)-[:FLOWS_TO {mechanism: 'BEAN_FACTORY'}]->(targetVar);
```

### 5\. Das Gesamtergebnis: Die universelle Flow-Abfrage (Statisch + new + DI)

Jetzt kannst du in Neo4j für jedes beliebige Paar `(ClassA, ClassB)` abfragen, ob und wie `ClassA` auf `ClassB` zugreift – völlig egal, ob `ClassB` direkt als Feld deklariert, per `new ClassB()` über drei Methoden hinweg durchgereicht oder per `@Autowired` hinter einem Interface `IService` injiziert wurde:

```
MATCH (classA:Class {name: 'ClassA'})-[:DECLARES_METHOD]->(mA:Method)
MATCH (classB:Class {name: 'ClassB'})-[:DECLARES_METHOD|DECLARES_FIELD]->(memberB)

// 1. Alle Aufrufe in ClassA finden, die direkt oder polymorph (via Interface) auf ClassB zielen
MATCH (mA)-[:CONTAINS_CALL|CONTAINS_ACCESS]->(site)-[:TARGETS_METHOD|TARGETS_FIELD]->(calledMember)
WHERE calledMember = memberB 
   OR (memberB)-[:OVERRIDES*1..5]->(calledMember)

// 2. Den Receiver in ClassA ermitteln
OPTIONAL MATCH (site)-[:ON_RECEIVER]->(receiver)

// 3. Prüfen, wie die Instanz von ClassB in diesen Receiver gelangt ist
// (Direkter Typ, 'new ClassB()'-Aufruf oder Dependency Injection!)
OPTIONAL MATCH flowPath = (origin)-[:INSTANTIATES|FLOWS_TO|ON_RECEIVER*0..6]->(receiver)
WHERE origin = classB 
   OR (origin)-[:OF_TYPE]->(classB)
   OR (origin:CallSite)-[:INSTANTIATES]->(classB)

RETURN 
    mA.name AS callerMethodInA,
    memberB.name AS executedInB,
    labels(receiver)[0] AS accessCarrierType,
    receiver.name AS carrierName,
    CASE 
        WHEN calledMember = memberB THEN 'Direct Static Call'
        WHEN any(r IN relationships(flowPath) WHERE r.mechanism = 'DEPENDENCY_INJECTION') THEN 'Polymorphic via DI (@Autowired/@Inject)'
        WHEN any(n IN nodes(flowPath) WHERE n:CallSite) THEN 'Polymorphic via Constructor/Setter Passing'
        ELSE 'Polymorphic (Unresolved Runtime Source)'
    END AS couplingMechanism
```

Soll ich dir noch zeigen, wie du Lambda-Ausdrücke und Method References (b::doWork) in dieses Modell integrierst?

---

**You**

Ja, zeige mir bitte noch, wie ich in Eclipse JDT LambdaExpressions und MethodReferences (z.B. list.forEach(b::doWork)) erfasse und in das Neo4j-Flow-Modell einbinde.

---

**Gemini**

In modernem Java-Code (Streams, `Optional`, Callbacks) werden Zugriffe auf `ClassB` extrem häufig in **Methodenreferenzen (`b::doWork`, `ClassB::doWork`, `ClassB::new`)** oder **Lambda-Ausdrücken (`x -> b.doWork(x)`)** versteckt.

Wenn du nur `MethodInvocation` und `ClassInstanceCreation` besuchst, gehen Methodenreferenzen komplett verloren, weil sie im JDT-AST eigene Knotentypen haben (Subklassen von `MethodReference`). Und bei Lambdas entsteht ohne explizite Parameter-Auflösung ein Loch im Datenfluss zwischen der Stream-Quelle (`listOfB.forEach(...)`) und dem Lambda-Parameter (`b -> b.doWork()`).

So schließt du beide Lücken in deinem Neo4j-Modell und im Eclipse JDT `ASTVisitor`:

### 1\. Methodenreferenzen (::) in JDT: Die 4 AST-Knotentypen

Eclipse JDT unterscheidet vier konkrete AST-Klassen (alle erben von `org.eclipse.jdt.core.dom.MethodReference`). Für dein Neo4j-Modell behandelst du jede davon einfach als **`:CallSite`** (mit einem zusätzlichen Property `isMethodRef: true`), weil dort fachlich exakt wie bei einem normalen Aufruf eine Methode von `ClassB` referenziert und zur Ausführung gebracht wird:

1. **`ExpressionMethodReference`** (`this.memberB::doWork` oder `getB()::doWork`):
	- *Gebundene Instanz:* Der Receiver steht links vom `::` (`node.getExpression()`).
		- *In Neo4j:* Genau wie ein normaler Aufruf `(:CallSite)-[:ON_RECEIVER]->(memberB)`.
2. **`TypeMethodReference`** (`ClassB::doWork` – z. B. in `streamOfB.forEach(ClassB::doWork)`):
	- *Ungebundene Instanz-Methode (oder statische Methode):* Links vom `::` steht der Typ `ClassB`, kein Variablenname! Die tatsächliche Instanz von `ClassB` wird zur Laufzeit als erstes Argument in das Functional Interface gereicht (z. B. aus dem Stream).
		- *In Neo4j:* `(:CallSite)-[:TARGETS_METHOD]->(ClassB.doWork)`. Falls der umschließende Aufruf (z. B. `streamOfB.forEach(...)`) auf einer Collection von `ClassB` läuft, liefert der Receiver des äußeren Aufrufs den Kontext.
3. **`CreationReference`** (`ClassB::new`):
	- *Konstruktor-Referenz:* Entspricht `new ClassB()`.
		- *In Neo4j:* `(:CallSite)-[:TARGETS_METHOD]->(ctor)` und `(:CallSite)-[:INSTANTIATES]->(ClassB)`.
4. **`SuperMethodReference`** (`super::doWork`):
	- *Aufruf auf Superklasse:* `(:CallSite)-[:TARGETS_METHOD]->(superMethod)`.

#### JDT-Implementierung für alle MethodReferences im ASTVisitor:

```
/**
 * 1. ExpressionMethodReference: memberB::doWork, paramB::doWork, getB()::doWork
 *    (Achtung: JDT nutzt diesen Knoten manchmal auch für ClassB::staticMethod!)
 */
@Override
public boolean visit(ExpressionMethodReference node) {
    if (currentMethodKey == null) return true;

    IMethodBinding targetMethod = node.resolveMethodBinding();
    if (targetMethod == null) return true;

    String callSiteId = registerMethodRefCallSite(node, targetMethod);

    // Prüfen, ob links vom '::' eine echte Instanz/Variable steht (und nicht bloß ein Klassenname)
    Expression expr = node.getExpression();
    IBinding exprBinding = (expr instanceof Name name) ? name.resolveBinding() : null;

    if (!(exprBinding instanceof ITypeBinding)) {
        // Gebundene Referenz (z.B. this.memberB::doWork) -> Receiver direkt verknüpfen!
        for (String receiverId : resolveFlowNodeIds(expr, currentMethodKey)) {
            buffer.addRelationship("ON_RECEIVER", callSiteId, receiverId);
        }
    } else {
        // Ungebundene Referenz auf Typ (z.B. ClassB::doWork in streamOfB.forEach(ClassB::doWork))
        linkUnboundMethodRefReceiver(node, callSiteId);
    }
    return true;
}

/**
 * 2. TypeMethodReference: ClassB::doWork (z.B. mit Generics ClassB<String>::doWork)
 */
@Override
public boolean visit(TypeMethodReference node) {
    if (currentMethodKey == null) return true;

    IMethodBinding targetMethod = node.resolveMethodBinding();
    if (targetMethod == null) return true;

    String callSiteId = registerMethodRefCallSite(node, targetMethod);
    linkUnboundMethodRefReceiver(node, callSiteId);
    return true;
}

/**
 * 3. CreationReference: ClassB::new (Konstruktor-Referenz in Streams/Optionals/Suppliers)
 */
@Override
public boolean visit(CreationReference node) {
    if (currentMethodKey == null) return true;

    IMethodBinding ctorBinding = node.resolveMethodBinding();
    if (ctorBinding == null) return true;

    String callSiteId = registerMethodRefCallSite(node, ctorBinding);
    String instantiatedClassKey = ctorBinding.getDeclaringClass().getTypeDeclaration().getKey();

    // Genau wie bei 'new ClassB()' die INSTANTIATES-Kante ziehen!
    buffer.addRelationship("INSTANTIATES", callSiteId, instantiatedClassKey);
    return true;
}

/**
 * Hilfsmethode: Legt die :CallSite für eine Methodenreferenz an.
 */
private String registerMethodRefCallSite(MethodReference node, IMethodBinding targetMethod) {
    String callSiteId = currentMethodKey + "@MR@" + node.getStartPosition();
    String targetMethodKey = targetMethod.getMethodDeclaration().getKey();

    buffer.addCallSite(callSiteId, node.getStartPosition(), node.toString());
    buffer.addRelationship("CONTAINS_CALL", currentMethodKey, callSiteId);
    buffer.addRelationship("TARGETS_METHOD", callSiteId, targetMethodKey);
    return callSiteId;
}
```

### 2\. Der Kniff für ungebundene Referenzen (listOfB.forEach(ClassB::doWork))

Wenn in `ClassA` steht: `this.bList.forEach(ClassB::doWork);`, dann steht vor `::doWork` nur der Typ `ClassB`. Woher kam die Instanz? Aus `this.bList`! Da `ClassB::doWork` als Argument an `this.bList.forEach(...)` übergeben wird, ist der `getParent()` der `MethodReference` die `MethodInvocation` von `forEach(...)`. Wir können also den Receiver der umschließenden Stream-/Collection-Pipeline (`this.bList`) direkt als Receiver-Quelle mit der Methodenreferenz verknüpfen:

```
/**
 * Verknüpft eine ungebundene Instanz-Methodenreferenz (ClassB::doWork) mit dem Receiver
 * der umschließenden Pipeline (z.B. this.bList in this.bList.forEach(ClassB::doWork)).
 */
private void linkUnboundMethodRefReceiver(MethodReference node, String methodRefCallSiteId) {
    IMethodBinding mb = node.resolveMethodBinding();
    if (mb == null || Modifier.isStatic(mb.getModifiers())) return;

    if (node.getParent() instanceof MethodInvocation enclosingCall && enclosingCall.getExpression() != null) {
        for (String pipelineSourceId : resolveFlowNodeIds(enclosingCall.getExpression(), currentMethodKey)) {
            buffer.addRelationship("ON_RECEIVER", methodRefCallSiteId, pipelineSourceId);
        }
    }
}
```

### 3\. LambdaExpression (b -> b.doWork()): Parameter & Return-Flow

Bei einem Lambda-Ausdruck wie `this.bList.forEach(b -> b.doWork())` oder `optB.map(b -> b.getStatus())` passieren im JDT-AST drei Dinge, die wir abfangen müssen:

1. **Enclosing Method behalten**: Ein Lambda ist in deinem Modell kein eigener `:Method` -Knoten der Klasse, sondern Teil des Codes von `currentMethodKey`. Deshalb darf `currentMethodKey` beim Betreten eines Lambdas **nicht** überschrieben werden – alle `:CallSite` - und `:FieldAccess` -Knoten im Lambda-Body gehören weiterhin über `[:CONTAINS_CALL]` zur umschließenden Methode `mA`!
2. **Lambda-Parameter erfassen (`b -> ...`)**: In JDT können Lambda-Parameter entweder `SingleVariableDeclaration` (`(ClassB b) -> ...`) oder – viel häufiger – ein bloßes **`VariableDeclarationFragment`** ohne Typangabe (`b -> ...`) sein!
	- Wir legen für jeden Lambda-Parameter einen **`:Parameter` -Knoten** (oder `:LocalVariable`) mit seinem echten `IVariableBinding.getVariableDeclaration().getKey()` an.
		- Wenn das Lambda Argument einer Pipeline ist (`this.bList.stream().filter(b -> b.isValid())`), ziehen wir eine **`[:FLOWS_TO]`** -Kante von der Pipeline (`this.bList.stream()`) in den Lambda-Parameter `b`!
3. **Lambda-Returns erfassen**:
	- **Expression-Body (`b -> b.createSubB()`)**: `node.getBody()` ist direkt eine `Expression`.
		- **Block-Body (`b -> { return b.createSubB(); }`)**: `node.getBody()` ist ein `Block` mit `ReturnStatement`.
		- Wenn das Lambda selbst aufgerufen oder an eine Methode wie `opt.map(...)` übergeben wird, fließt der Rückgabewert des Lambdas in die umschließende `:CallSite` (`map(...)`)!

```
@Override
public boolean visit(LambdaExpression node) {
    if (currentMethodKey == null) return true;

    // 1. Falls das Lambda als Argument in einer Pipeline steckt (z.B. bList.forEach(b -> ...)),
    // ermitteln wir die Quelle der Pipeline (z.B. bList oder den vorherigen Stream-Call):
    List<String> pipelineSources = List.of();
    String enclosingCallSiteId = null;
    if (node.getParent() instanceof MethodInvocation enclosingCall) {
        enclosingCallSiteId = currentMethodKey + "@" + enclosingCall.getStartPosition();
        if (enclosingCall.getExpression() != null) {
            pipelineSources = resolveFlowNodeIds(enclosingCall.getExpression(), currentMethodKey);
        }
    }

    // 2. Lambda-Parameter anlegen (können VariableDeclarationFragment ODER SingleVariableDeclaration sein!)
    List<?> params = node.parameters();
    for (int i = 0; i < params.size(); i++) {
        VariableDeclaration paramDecl = (VariableDeclaration) params.get(i);
        IVariableBinding vb = paramDecl.resolveBinding();
        if (vb == null) continue;

        // WICHTIG: Bei Lambda-Parametern immer vb.getVariableDeclaration().getKey() nutzen,
        // damit SimpleName-Zugriffe im Lambda-Body ('b.doWork()') exakt dieselbe ID treffen!
        String lambdaParamId = vb.getVariableDeclaration().getKey();
        String typeKey = vb.getType().getTypeDeclaration().getKey();

        buffer.addLocalVariable(lambdaParamId, vb.getName(), typeKey);
        buffer.addRelationship("DECLARES_LOCAL", currentMethodKey, lambdaParamId);
        buffer.addRelationship("OF_TYPE", lambdaParamId, typeKey);

        // Datenfluss von der Stream/Optional-Quelle in den Lambda-Parameter ziehen:
        for (String srcId : pipelineSources) {
            buffer.addRelationship("FLOWS_TO", srcId, lambdaParamId);
        }
    }

    // 3. Expression-Lambda Return-Flow: z.B. opt.map(b -> b.getSubB())
    // Wenn der Body eine direkte Expression ist und das Lambda in einem Call steckt,
    // fließt das Ergebnis des Lambda-Bodys in die umschließende CallSite (z.B. 'map()')!
    if (node.getBody() instanceof Expression bodyExpr && enclosingCallSiteId != null) {
        for (String bodyResultId : resolveFlowNodeIds(bodyExpr, currentMethodKey)) {
            buffer.addRelationship("FLOWS_TO", bodyResultId, enclosingCallSiteId);
        }
    }

    return true; // Kinder weiter besuchen, damit b.doWork() im Body ganz normal erfasst wird!
}
```

Und damit auch eine Methodenreferenz als Funktions-Argument (`this.bList::get` oder `ClassB::new` in `supplier.get()`) in `resolveSingleFlowNodeId` aufgelöst wird, ergänzt du in `resolveSingleFlowNodeId` noch diesen Zweig:

```
// In resolveSingleFlowNodeId(Expression expr, String enclosingMethodKey) ergänzen:
if (expr instanceof MethodReference methodRef) {
    return enclosingMethodKey + "@MR@" + methodRef.getStartPosition();
}
```

### 4\. Wie sieht eine Stream-Kette damit in Neo4j aus?

Betrachten wir diesen typischen Code in `ClassA`:

```
public class ClassA {
    private List<ClassB> items;

    public void processAll() {
        this.items.stream()
            .filter(b -> b.isActive())
            .forEach(ClassB::executeTask);
    }
}
```

Durch deine Visitor-Regeln entsteht im Graphen automatisch dieser lückenlose Pfad:

1. `this.items.stream()`:
	- `:CallSite("stream()") -[:ON_RECEIVER]-> :Field("items")`
2. `.filter(b -> b.isActive())`:
	- `:CallSite("filter()") -[:ON_RECEIVER]-> :CallSite("stream()")`
		- Der Lambda-Parameter `b` wird als `:LocalVariable("b")` angelegt und erhält:`:CallSite("stream()") -[:FLOWS_TO]-> :LocalVariable("b")`
		- Im Lambda-Body wird `b.isActive()` aufgerufen:`:CallSite("isActive()") -[:TARGETS_METHOD]-> :Method("ClassB.isActive")``:CallSite("isActive()") -[:ON_RECEIVER]-> :LocalVariable("b")`
3. `.forEach(ClassB::executeTask)`:
	- Die `TypeMethodReference` `ClassB::executeTask` erzeugt `:CallSite("ClassB::executeTask") -[:TARGETS_METHOD]-> :Method("ClassB.executeTask")`.
		- Über `linkUnboundMethodRefReceiver` zeigt ihr Receiver auf den vorherigen Pipeline-Schritt:`:CallSite("ClassB::executeTask") -[:ON_RECEIVER]-> :CallSite("filter()")`

Wenn du jetzt in Cypher nach allen Zugriffen von `ClassA.processAll()` auf `ClassB` fragst und die Kette `(:CallSite)-[:ON_RECEIVER|FLOWS_TO*1..5]->(root:Variable)` zurückverfolgst, landen **sowohl** `b.isActive()` (im Lambda) **als auch** `ClassB::executeTask` (als Methodenreferenz) zielsicher bei deinem Member-Feld **`this.items` (`:Field`)**!

als Feldtyp) in JDT auflöst?" query="Ja, zeige mir bitte noch, wie ich bei generischen Typen wie List
