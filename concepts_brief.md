# JavaParser-basierte API/Impl-Codetransformation zur Reduktion transitiver Modulabhängigkeiten

* **Author:** Frank Schlüter
* **Date:** 2026-11-12

## Overview

Dieses Projekt stellt ein JavaParser-basiertes Refactoring-Werkzeug (`de.tk.refactoring.tool`) bereit, das innerhalb eines Zielmoduls die öffentliche Modul-API von den Implementierungsklassen trennt. Ziel ist es, transitive Abhängigkeiten zwischen Modulen auf ein Minimum zu reduzieren, ohne dass externe Aufrufer-Module (sofern noch kein abweichendes API-Interface existierte) angepasst werden müssen.

## Analysis

* **Beibehaltung des vollqualifizierten API-Namens:** Wenn zu einer als API genutzten Implementierungsklasse noch kein geeignetes Interface existiert, wird ein neues API-Interface unter dem exakten vollqualifizierten Originalnamen der Klasse im API-Package erstellt. Die Implementierungsklasse wird mit dem Suffix `Impl` umbenannt und in den konfigurierbaren `impl`-Packagezweig verschoben.
* **Strikte Package-Trennung (ArchUnit-gesichert):** Alle Implementierungsklassen (`*Impl`), Factory-Implementierungen (`*ProviderImpl`), internen Hilfsklassen sowie die `*Intern`-Interfaces liegen im `impl`-Packagezweig. Der API-Packagezweig darf keinerlei Abhängigkeiten zum `impl`-Packagezweig besitzen.
* **Statische Erzeugungs- und Wrapper-Methoden im API-Interface:** Da Java keinen `new`-Aufruf auf einem Interface erlaubt, stellt das API-Interface für extern genutzte Konstruktoren statische `create(...)`-Methoden sowie für extern genutzte statische Methoden statische Wrapper-Methoden bereit. Diese delegieren intern an ein per `@jakarta.inject.Inject` injiziertes `<Name>Provider`-Factory-Interface, dessen Implementierung (`<Name>ProviderImpl`) im `impl`-Package über `@de.tk.util.injection.ImplementationClass` und einen ClassGraph-basierten Guice-Scanner gebunden wird.
* **Syntaktische Transformation über Zeilennummern:** Aufrufe interner Methoden auf Referenzen vom Typ des API-Interfaces werden anhand vorberechneter Zeilennummern-Metadaten rein syntaktisch (ohne `JavaSymbolSolver` während der Transformation) in geklammerte Casts `((<Name>Intern) scope).method()` umgewandelt. Konstruktor-Aufrufe `new <Name>(...)` werden anhand der Zeilennummern-Metadaten in `<Name>.create(...)` überführt.

## Goals vs. Non-Goals

* **In-Scope Goals:**
  * Definition des Abhängigkeits-Metamodells (API-Klassen, `isApiKonform`, genutzte Methoden, statische Methoden, `public static final` Felder, Konstruktoren, Zeilennummern-Listen für Intern-Casts und Konstruktor-Aufrufe).
  * AST-Scanner für Testzwecke, der `@de.tk.dependencyanalyse.transformation.Api`-Annotationen auswertet und das Metamodell aufbaut.
  * Code-Transformation mit `LexicalPreservingPrinter` für Klassen mit und ohne bestehendes API-Interface, inklusive Vererbungshierarchien (`API` und `Intern`), statischen Feldern/Methoden und `Provider`/`ProviderImpl`-Generierung.
  * Kopie-basierte Transformation nach `transformed/java`, Absicherung der Package-Regeln per ArchUnit und automatisierter Kompilier- und Laufzeittest (vor und nach der Transformation) mit JUnit und Google Guice.
* **Out-of-Scope Non-Goals:**
  * Veränderung der fachlichen Geschäftslogik innerhalb von Methoden.
  * Auslagerung von mutablen (nicht-finalen) `public static` Feldern oder direkten Instanzfeld-Zugriffen in Java-Interfaces.

## Alternatives Weighed

* **API-Interface in eigenem `.api`-Subpackage mit Suffix `Api` (bei neu erstelltem Interface):** Rejected by author: Würde zwingend Import- und Typänderungen in allen externen Aufrufer-Modulen erfordern; daher behält das neue API-Interface den exakten vollqualifizierten Namen der ursprünglichen Klasse.
* **Direkte Injection des `Provider`-Interfaces in jede aufrufende Klasse statt statischer `create(...)`-Methode im API-Interface:** Rejected by author: Erfordert Umbau der Aufrufer-Klassen auf Dependency Injection; stattdessen kapselt das API-Interface den injizierten `Provider` hinter statischen `create(...)`- und Wrapper-Methoden.
* **Guice `@ImplementedBy` am `Provider`-Interface:** Rejected by author: Erzeugt eine direkte Compile-Abhängigkeit vom API-Package in das `impl`-Package und verletzt damit die ArchUnit-Regel. Stattdessen wird `@ImplementationClass` auf der `ProviderImpl` im `impl`-Package zusammen mit einem ClassGraph-Scanner verwendet.

## Key Decisions & Staging for Next Steps

* Das `Intern`-Interface liegt im `impl`-Packagezweig (nicht im API-Package) und erweitert das API-Interface.
* Ein ArchUnit-Test prüft, dass Klassen im API-Packagezweig keine Typen aus dem `impl`-Packagezweig referenzieren.
* Die Transformation speichert den generierten und transformierten Code unter `transformed/java` und verifiziert dessen Kompilierbarkeit und Laufzeitverhalten über JUnit.
