package de.tk.refactoring.test.projectA.impl;

import de.tk.refactoring.test.projectA.OrderService;

public interface OrderServiceIntern extends OrderService, BaseServiceIntern {

    String calculateInternalAuditHash();
}
