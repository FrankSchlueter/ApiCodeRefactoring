package de.tk.refactoring.test.projectA.impl;

import de.tk.refactoring.test.projectA.impl.OrderServiceIntern;
import de.tk.refactoring.test.projectA.impl.BillingServiceIntern;
import de.tk.refactoring.test.projectA.BillingServiceApi;
import de.tk.refactoring.test.projectA.OrderService;

public class InternalWorkflowRunner {

    private final OrderServiceImpl directInternalService;

    private final InternalAuditHelper helper = new InternalAuditHelper();

    public InternalWorkflowRunner() {
        this.directInternalService = new OrderServiceImpl("DIRECT");
    }

    public String runDirectFieldScenario() {
        return directInternalService.calculateInternalAuditHash() + "|" + helper.computeAuditTag("OK");
    }

    public String runChainedApiReturnScenario(OrderService apiService) {
        String hash = ((OrderServiceIntern) apiService.getCollaborator()).calculateInternalAuditHash();
        String baseInternal = ((OrderServiceIntern) apiService.getCollaborator()).getInternalBaseState();
        return hash + "|" + baseInternal;
    }

    public String runInternalMethodUsageScenario(OrderService paramService, BillingServiceApi billingParam) {
        OrderService localCollaborator = paramService.getCollaborator();
        String localHash = ((OrderServiceIntern) localCollaborator).calculateInternalAuditHash();
        String paramHash = ((OrderServiceIntern) paramService).calculateInternalAuditHash();
        String billingReset = ((BillingServiceIntern) billingParam).resetInternalCounter();
        return localHash + "|" + paramHash + "|" + billingReset;
    }

    public String executeAllInternalVerifications() {
        OrderService entry = OrderService.create("WORKFLOW");
        BillingServiceApi billing = BillingServiceApi.create();
        return runDirectFieldScenario() + "/" + runChainedApiReturnScenario(entry) + "/" + runInternalMethodUsageScenario(entry, billing);
    }
}
