# Stage 5: Initial Zoom Out & Inventive Catalyst Review Report (`design_draft.md`)

## Part 1: Zoom Out Review (`egm-zoom-out`)

**Overall Assessment:** Approved with minor Nits. The design document is well-structured, provides clear architectural context, directly addresses the root problem of decoupling module APIs from implementation classes to minimize transitive dependencies, and stays faithful to the constraints established in [concepts_brief.md](file:///home/frank/projects/ApiCodeRefactoring/concepts_brief.md), [Prompt.txt](file:///home/frank/projects/ApiCodeRefactoring/Prompt.txt), and [InternCastOhneResolve.MD](file:///home/frank/projects/ApiCodeRefactoring/InternCastOhneResolve.MD).

### Findings

#### 1. Unnecessary Dependency (`javaparser-symbol-solver-core`)
* **Severity**: Nit
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L259-L260) (`Detailed Implementation Plan -> 1. pom.xml`)
* **Simplicity Rationale**: Goal G3 and [InternCastOhneResolve.MD](file:///home/frank/projects/ApiCodeRefactoring/InternCastOhneResolve.MD) explicitly emphasize syntactic AST analysis and transformation without requiring `JavaSymbolSolver`. Since `ApiAnnotationScanner` only inspects the controlled test `CompilationUnit`s in `projectA` and `projectB` for `@Api` annotations, local variable/parameter types, and method names, `javaparser-core` alone is sufficient. Dropping `javaparser-symbol-solver-core` avoids pulling in its heavy transitive dependency tree (Guava, Javassist, etc.) unless symbol resolution is strictly required.

#### 2. Granularity of One-Line Metamodel Records
* **Severity**: Nit
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L290-L331) (`Detailed Implementation Plan -> 5..11`)
* **Simplicity Rationale**: Defining seven separate source files for one-line records (`MethodSignature`, `FieldMetadata`, `ConstructorSignature`, `InternCastTarget`, `ConstructorCallTarget`) adds boilerplate file count. Grouping closely related member signatures (`MethodSignature`, `ConstructorSignature`, `FieldMetadata`) or target records (`InternCastTarget`, `ConstructorCallTarget`) as nested records inside `ApiClassMetadata` / `ModuleMetamodel` (or keeping only top-level records where externally referenced) could slightly reduce file clutter, though keeping them separate is also acceptable in Java.

#### 3. Signature Compatibility with `Prompt.txt` (`replaceImplUsage`)
* **Severity**: Nit
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L354-L356) (`Detailed Implementation Plan -> 14. ApiRefactoring.java`)
* **Simplicity Rationale**: [Prompt.txt](file:///home/frank/projects/ApiCodeRefactoring/Prompt.txt#L13) specifies `public static void replaceImplUsage(CompilationUnit implClass)`. The plan proposes `replaceImplUsage(CompilationUnit cu, ModuleMetamodel metamodel)`. Providing a single-argument overload `replaceImplUsage(CompilationUnit implClass)` that delegates to the metamodel-aware method ensures strict signature compatibility with the original specification without adding architectural complexity.

---

## Part 2: Inventive Catalyst Review (`egm-inventive-catalyst`)

### Finding 1: Syntactic-Only Transformation (`G3`) vs. Context-Sensitive Type Split (`<Name>` vs. `<Name>Impl`) in the `impl` Package

* **Severity**: Major
* **Affected Scope**: `Technical Plan — 3. Transformations-Regeln im Detail` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L176-L210)), `14. ApiRefactoring.java` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L354-L365)), and `15. Test-Szenario-Klassen in projectA` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L381-L385)).
* **False Compromise**: The architecture mandates a purely syntactic transformation without a SymbolSolver during refactoring (`G3`), driven solely by `ApiClassMetadata`, `InternCastTarget`, and `ConstructorCallTarget`. At the same time, it requires `InternalWorkflowRunner` (which moves to `projectA.impl`) to support two conflicting usages of the same original type name (e.g., `OrderService`):
  * **API-typed usage** (line 382): Receiving the return value of an API method (`orderService.getCollaborator()`), where the static type must remain the new API interface `OrderService` and internal calls require an `((OrderServiceIntern) ...)` cast.
  * **Direct `Impl`-typed usage** (line 383): Holding an internal field initialized via constructor (`new OrderService(...)`) and invoking an internal method on it *without* a cast.
  Because the original source code before transformation only uses the single identifier `OrderService`, keeping the field type as `OrderService` causes uncasted internal method calls to fail compilation (`OrderService` is now the API interface), leaving un-targeted `new OrderService(...)` calls in `impl` fails compilation (`cannot instantiate interface OrderService`), and blanket-renaming all `OrderService` references in `impl` to `OrderServiceImpl` breaks assignments from API methods returning the `OrderService` interface.
* **Proposed Resolution**: Apply **Preliminary Action (Pattern 3)** and **Segmentation (Pattern 1)** by separating API-factory instantiation from internal direct instantiation in the metamodel or AST rules:
  * Have `ApiAnnotationScanner` / `ApiRefactoring` explicitly distinguish between external/API constructor calls (which become `<ApiInterface>.create(...)` via `ConstructorCallTarget`) and internal `impl`-package `new <Name>(...)` calls not in `ConstructorCallTarget` (which must be rewritten to `new <Name>Impl(...)`), and rewrite private/internal fields in `impl` classes initialized with `new <Name>(...)` to `<Name>Impl` while keeping API method signatures and variables assigned from API calls typed as `<Name>`.
* **Secondary Liabilities**: Adds explicit AST initializer-inspection rules or additional metamodel entries, increasing coupling between the scanner and declaration patterns in `impl` classes.

### Finding 2: FQN Preservation (`<pkg>.<Name>`) vs. Dual-Source-Folder Maven Compilation (`-Pcompile-transformed`)

* **Severity**: Major
* **Affected Scope**: `1. pom.xml` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L253-L266)) and `Verification Plan` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L416-L420)).
* **False Compromise**: The design preserves the exact original fully qualified class names (`de.tk.refactoring.test.projectA.OrderService`, `BaseService`, `CustomerData`, `BillingServiceApi`, `de.tk.refactoring.test.projectB.ExternalOrderClient`) in `transformed/java` so external callers require minimal changes, while simultaneously configuring `build-helper-maven-plugin` in the `compile-transformed` profile to add `transformed/java` as an additional source folder to the Maven build (line 265, line 420). When `mvn test-compile -Pcompile-transformed` runs, `maven-compiler-plugin` compiles both `src/test/java` and `transformed/java` in the same compiler pass, which deterministically fails with `javac` `duplicate class` errors for every class in `projectA` and `projectB`.
* **Proposed Resolution**: Apply **Segmentation (Pattern 1)** in `pom.xml`:
  * In the `compile-transformed` profile, either configure `maven-compiler-plugin` (`testCompile`) to exclude `de/tk/refactoring/test/projectA/**` and `de/tk/refactoring/test/projectB/**` from `src/test/java` when `transformed/java` is added, or define a dedicated `maven-compiler-plugin` execution that compiles `transformed/java` in isolation into `target/transformed-classes`.
* **Secondary Liabilities**: Introduces custom compiler execution/exclusion configuration in `pom.xml` and requires `transformed/java` to be populated prior to running `-Pcompile-transformed`.

### Finding 3: Whole-Unit `LexicalPreservingPrinter` vs. Structural AST Rewriting & Nested Node Cloning

* **Severity**: Nit
* **Affected Scope**: `Technical Plan — 3. Transformations-Regeln im Detail` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L206-L210)) and `13. CastAndConstructorInjector.java` ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L344-L352)).
* **False Compromise**: The design relies on `LexicalPreservingPrinter` alongside heavy structural AST mutations on implementation classes (changing package declarations, renaming classes to `*Impl`, rewriting `extends`/`implements` lists, removing `public static final` fields, and replacing parameter/return types) and clones `scope` expressions via `currentScope.clone()` during cast injection. In JavaParser 3.26.x, removing fields or rewriting class headers under active `LexicalPreservingPrinter` tracking can trigger `UnsupportedOperationException` or token stream corruption; additionally, calling `currentScope.clone()` replaces the scope subtree with cloned nodes that are detached from any pre-collected `cu.findAll(...)` lists if an inner expression on the same line also requires a cast or constructor replacement.
* **Proposed Resolution**: Apply **Segmentation (Pattern 1)** via phased AST processing:
  * Perform structural declaration/signature mutations before activating `LexicalPreservingPrinter` (or re-query AST nodes on demand per target in bottom-up/innermost-first order rather than iterating over a single pre-collected `findAll` list), and copy source `Range` metadata onto cloned scope nodes if nested transformations occur on the same line.
* **Secondary Liabilities**: Slightly higher AST traversal overhead due to re-querying nodes per target or splitting structural and lexical transformation phases.
