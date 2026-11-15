package de.tk.refactoring.test.projectA;

import de.tk.dependencyanalyse.transformation.Api;

/**
 * Implementation class without an existing API interface, extending {@link BaseService}.
 * Uses @Api on a constant field, constructor, static method, and instance methods,
 * plus an unannotated internal method and internal helper dependency.
 */
public class OrderService extends BaseService {

    @Api
    public static final String DEFAULT_CURRENCY = "EUR";

    private final String prefix;
    private final InternalAuditHelper auditHelper = new InternalAuditHelper();

    @Api
    public OrderService(String prefix) {
        this.prefix = prefix;
    }

    @Api
    public static String formatOrderNumber(int num) {
        return "ORD-" + num + "-" + DEFAULT_CURRENCY;
    }

    @Api
    public String createOrder(CustomerData customer) {
        return prefix + ":" + customer.getCustomerName() + ":" + DEFAULT_CURRENCY;
    }

    @Api
    public OrderService getCollaborator() {
        return new OrderService(prefix + "-COLLAB");
    }

    public String calculateInternalAuditHash() {
        return auditHelper.computeAuditTag(prefix);
    }
}
