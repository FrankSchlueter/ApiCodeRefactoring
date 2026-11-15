package de.tk.refactoring.test.projectA.impl;

import de.tk.dependencyanalyse.transformation.Api;
import de.tk.refactoring.test.projectA.OrderService;
import de.tk.refactoring.test.projectA.CustomerData;

public class OrderServiceImpl extends BaseServiceImpl implements OrderServiceIntern {

    private final String prefix;

    private final InternalAuditHelper auditHelper = new InternalAuditHelper();

    @Api
    public OrderServiceImpl(String prefix) {
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
    return OrderService.create(prefix + "-COLLAB");
}

    public String calculateInternalAuditHash() {
        return auditHelper.computeAuditTag(prefix);
    }
}
