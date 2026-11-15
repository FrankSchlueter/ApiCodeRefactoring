package de.tk.refactoring.test.projectA;

/**
 * Internal class in the same module (projectA) testing all internal usage scenarios:
 * 1. Calling a method whose return value is replaced by the API interface and invoking an internal method on it (requires Intern cast).
 * 2. Holding a field to an implementation class in the same module initialized via constructor and invoking an internal method on it (NO cast required).
 * 3. Internal usages within methods (local variables, parameters, inherited internal methods, and BillingService).
 */
public class InternalWorkflowRunner {

    private final OrderService directInternalService;
    private final InternalAuditHelper helper = new InternalAuditHelper();

    public InternalWorkflowRunner() {
        this.directInternalService = new OrderService("DIRECT");
    }

    public String runDirectFieldScenario() {
        // Scenario 2: Field initialized via constructor in same module -> direct call without cast
        return directInternalService.calculateInternalAuditHash() + "|" + helper.computeAuditTag("OK");
    }

    public String runChainedApiReturnScenario(OrderService apiService) {
        // Scenario 1: Method return value is API interface -> calling internal method requires cast to OrderServiceIntern
        String hash = apiService.getCollaborator().calculateInternalAuditHash();
        String baseInternal = apiService.getCollaborator().getInternalBaseState();
        return hash + "|" + baseInternal;
    }

    public String runInternalMethodUsageScenario(OrderService paramService, BillingService billingParam) {
        // Scenario 3: Internal usages inside a method on parameter and local variable
        OrderService localCollaborator = paramService.getCollaborator();
        String localHash = localCollaborator.calculateInternalAuditHash();
        String paramHash = paramService.calculateInternalAuditHash();
        String billingReset = billingParam.resetInternalCounter();
        return localHash + "|" + paramHash + "|" + billingReset;
    }

    public String executeAllInternalVerifications() {
        OrderService entry = new OrderService("WORKFLOW");
        BillingService billing = new BillingService();
        return runDirectFieldScenario()
                + "/" + runChainedApiReturnScenario(entry)
                + "/" + runInternalMethodUsageScenario(entry, billing);
    }
}
