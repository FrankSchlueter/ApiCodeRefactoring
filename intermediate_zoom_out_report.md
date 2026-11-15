# Stage 8: Intermediate Zoom Out Review Report (`design_draft.md`, Lines 1–426)

**Overall Assessment:** The high-altitude architecture across the full document (`design_draft.md`, lines 1–426) remains cohesive, minimal, and directly focused on the root problem (decoupling module APIs from implementation classes via syntactic JavaParser transformations and Guice/ClassGraph provider bindings). To resolve the Stage 5–7 findings without introducing over-engineering or YAGNI complexity, enforce the following simplicity guardrails during revision:

---

### Findings

#### 1. Keep the Internal Direct-Reference vs. API-Reference Rule Simple and Syntactic
* **Severity**: Major
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L175-L210) (Section 3, *Transformations-Regeln im Detail*) & [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L354-L365) (`14. ApiRefactoring.java`)
* **Simplicity Rationale**: Stage 5 (Inventive Catalyst Finding 1) and Stage 7 (Finding D1) flagged the need for a deterministic rule inside `projectA.impl` to distinguish direct implementation usage (`<Name>Impl`) from API-typed usage (`<Name>`). Do **not** introduce a complex data-flow analyzer or `JavaSymbolSolver` to solve this. A simple syntactic rule suffices:
  1. In API method signatures (parameters and return types of methods listed in `apiMethods` / `staticApiMethods`) and in external modules (`projectB`), non-API-conform types are typed as the API interface (`<Name>` or `existingApiInterfaceFqn`).
  2. Within `impl` classes of the target module, `ObjectCreationExpr` (`new <Name>(...)`) calls that are **not** listed in `constructorCallTargets` (i.e., internal direct instantiations) are rewritten to `new <Name>Impl(...)`, and internal fields/variables directly initialized with `new <Name>(...)` (or referencing internal-only helper classes) are rewritten to `<Name>Impl`. Variables receiving return values of API methods keep the API interface type `<Name>` and receive `((<Name>Intern) ...)` casts at the line numbers specified in `internCastTargets`.

#### 2. Avoid Complex Multi-Execution Maven Profiles for `transformed/java`
* **Severity**: Major
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L49-L50), [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L253-L266) (`1. pom.xml`), and [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L416-L420) (`Verification Plan`)
* **Simplicity Rationale**: Stage 5 (Inventive Catalyst Finding 2) and Stage 7 (Finding D3) identified the FQN collision when `transformed/java` and `src/test/java` are compiled together in the `compile-transformed` Maven profile. Keep the solution minimal: in the `compile-transformed` profile, use `build-helper-maven-plugin` to add `transformed/java` as a test-source directory and configure `<testExcludes>` on `maven-compiler-plugin` to exclude `de/tk/refactoring/test/projectA/**` and `de/tk/refactoring/test/projectB/**` from `src/test/java` when that profile is active (while default `mvn test` compiles `transformed/java` into `target/transformed-classes` via `javax.tools.JavaCompiler`). Avoid multi-module Maven splits or custom plugins.

#### 3. Drop Unused `javaparser-symbol-solver-core` Dependency
* **Severity**: Nit
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L259-L260) (`1. pom.xml`)
* **Simplicity Rationale**: Neither `ApiRefactoring` (Goal G3) nor `ApiAnnotationScanner` requires `JavaSymbolSolver` to inspect `@Api` annotations, method names, and AST line numbers across the test compilation units. Removing `javaparser-symbol-solver-core` from `pom.xml` eliminates an unused dependency and keeps the build lightweight.

#### 4. Unify Metamodel Record Naming and Case 2 / Case 3 Target Name Resolution
* **Severity**: Nit
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L148-L205) (Sections 2 & 3) and [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L290-L365) (Items 5–14)
* **Simplicity Rationale**: Align the field names of `ModuleMetamodel`, `ApiClassMetadata` (including `List<String> implementedInterfaces`), `InternCastTarget`, and `ConstructorCallTarget` between Section 2 and the Detailed Implementation Plan (Stage 6 Finding 1 & Stage 7 Finding D4), and use helper methods on `ApiClassMetadata` / `ModuleMetamodel` (e.g., `effectiveApiInterfaceName()`, `effectiveImplClassName()`) so Sections 3.2 and 3.3 handle both Case 2 (`<Name>` / `<Name>Impl`) and Case 3 (`existingApiInterfaceFqn` / `<Name>`) uniformly without branching special cases (Stage 7 Finding D2). Also include the single-argument overload `replaceImplUsage(CompilationUnit implClass)` on `ApiRefactoring` for strict compatibility with `Prompt.txt`.
