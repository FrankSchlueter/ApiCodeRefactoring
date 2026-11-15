# Stage 9: Implementation Readiness Review Report (`design_draft.md`, Lines 249–426)

## Part 1: Implementation Readiness Findings (`egm-implementation-readiness`)

### Finding IR1: Ambiguous Type-Rewriting & Constructor-Replacement Rules Inside the Target Module (`projectA`)
* **Severity**: Blocker
* **Affected Scope**: Item 12 ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L332-L343)), Item 14 ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L353-L365)), Item 15 ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L381-L385))
* **Finding & Guidance**:
  * Item 15 (line 383) requires `InternalWorkflowRunner` to have an internal field referencing an implementation class in the same module, initialized in the constructor, calling an internal method directly **without a cast**.
  * When `OrderService` has no existing interface, the API interface takes the name `OrderService` and the implementation class is renamed to `OrderServiceImpl`. If `ApiAnnotationScanner` (Item 12, line 341) records every `new OrderService(...)` call as a `ConstructorCallTarget`, replacing it with `OrderService.create(...)` changes the expression's return type to the API interface `OrderService`—which cannot be assigned to a field of type `OrderServiceImpl` without a cast, nor can an internal method be called on a field of type `OrderService` without a cast.
  * **Actionable Fix**: Specify the exact deterministic rule in Items 12 and 14 for references inside `targetModulePackage`:
    1. Define which internal declarations in `projectA` are rewritten from `<Name>` to `<Name>Impl` (non-API private/package-private fields initialized via `new <Name>(...)` inside `impl` classes, and direct `new <Name>Impl(...)` instantiations not listed in `ConstructorCallTarget`) versus which remain as `<ApiInterface>` (parameters/return types of `@Api` methods, and local variables initialized from `@Api` method return values).
    2. Clarify that `ConstructorCallTarget` replacement (`<Name>.create(...)`) is generated for external module calls (`projectB`) and calls assigned to API-typed variables, whereas internal `Impl`-typed fields in `InternalWorkflowRunner` are rewritten to `OrderServiceImpl` and `new OrderServiceImpl(...)`.

### Finding IR2: Record Field Name & Signature Mismatches Between Architecture Section and Implementation Plan
* **Severity**: Major
* **Affected Scope**: Section 2 ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L148-L174)) vs. Items 8–11 ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L308-L331)) and Item 14 ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L353-L357))
* **Finding & Guidance**:
  * Align the record component names (`ModuleMetamodel`, `ApiClassMetadata`, `InternCastTarget`, `ConstructorCallTarget`) across both sections and include the 1-arg `replaceImplUsage(CompilationUnit implClass)` overload alongside the 2-arg method.

### Finding IR3: ClassLoader Isolation in `ClassGraphGuiceModule` and `RefactoringEndToEndTest`
* **Severity**: Major
* **Affected Scope**: Item 4 ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L281-L289)) and Item 17 ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L408-L415))
* **Finding & Guidance**:
  * Explicitly specify in Item 4 that `ClassGraphGuiceModule` configures `new ClassGraph().overrideClassLoaders(classLoader).enableAllInfo().ignoreFieldVisibility().acceptPackages(scanPackages)` when a custom `ClassLoader` is provided, and specify in Item 17 (Test 4) that the test invokes the entry methods on classes loaded from the parent-last `URLClassLoader` via Reflection (`Class.forName(..., true, transformedClassLoader)`).

### Finding IR4: Missing Cross-Package Import Rules When Moving Classes to `impl`
* **Severity**: Nit
* **Affected Scope**: Item 14 ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L358-L363))
* **Finding & Guidance**:
  * Explicitly note in Item 14 (step 5) that moving a compilation unit to the `impl` package adds explicit imports for all referenced types remaining in the base API package (including the newly generated API interfaces and `isApiKonform = true` classes).

---

## Part 2: Codebase Details Findings (`egm-codebase-details`)

### Finding CD1: Duplicate Class Compilation Conflict in `compile-transformed` Maven Profile
* **Severity**: Blocker
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L253-L266) (`1. pom.xml`), [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L416-L420) (`Verification Plan`)
* **Discrepancy Description & Rationale**:
  * Specify in Item 1 (`pom.xml`) that the `compile-transformed` profile uses `build-helper-maven-plugin` (`add-test-source` for `transformed/java`) together with `<testExcludes>` on `maven-compiler-plugin` for `de/tk/refactoring/test/projectA/**` and `de/tk/refactoring/test/projectB/**` from `src/test/java` so `javac` does not fail with `duplicate class` errors.

### Finding CD2: Ambiguous Distinction Between Internal `Impl` Field Initialization and `@Api` `ConstructorCallTarget`
* **Severity**: Major
* **Affected Scope**: Items 12, 14, 15 (Same as IR1)

### Finding CD3: Missing `ClassGraph` Visibility and ClassLoader Isolation Details + Reflective Execution in `RefactoringEndToEndTest`
* **Severity**: Major
* **Affected Scope**: Items 4 and 17 (Same as IR3)

### Finding CD4: Missing Single-Argument Overload `ApiRefactoring.replaceImplUsage(CompilationUnit)`
* **Severity**: Major
* **Affected Scope**: Item 14 (Same as IR2)

### Finding CD5: AST Mutation Ordering with `LexicalPreservingPrinter` and Original Line Numbers
* **Severity**: Major
* **Affected Scope**: Items 13 & 14 ([design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L344-L365))
* **Discrepancy Description & Rationale**:
  * Specify that all AST mutations on a `CompilationUnit` happen in-memory without re-parsing between steps (preserving initial 1-based line numbers for `CastAndConstructorInjector`), or that `CastAndConstructorInjector` runs before structural header/field removals, and includes the `isAlreadyCastTo`, `call.getScope().isEmpty()`, and `matchesLine` checks from [InternCastOhneResolve.MD](file:///home/frank/projects/ApiCodeRefactoring/InternCastOhneResolve.MD).

### Finding CD6: Field Name Drift in Metamodel Records
* **Severity**: Nit
* **Affected Scope**: Section 2 vs. Items 8–11 (Same as IR2)

---

## Part 3: Style Copy Editor Findings (`egm-style`)

### Finding ST-IR1: Terminology Consistency („Produkt“ vs. „Modul“)
* **Severity**: Nit
* **Affected Scope**: [design_draft.md](file:///home/frank/projects/ApiCodeRefactoring/design_draft.md#L383-L384) (`#### 15. Test-Szenario-Klassen in projectA`)
* **Style Rationale**: Replace *"im gleichen Produkt"* on line 383 with *"im selben Modul"* for consistency.
