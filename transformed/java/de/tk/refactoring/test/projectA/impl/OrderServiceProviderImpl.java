package de.tk.refactoring.test.projectA.impl;

import de.tk.util.injection.ImplementationClass;
import jakarta.inject.Inject;
import de.tk.refactoring.test.projectA.OrderServiceProvider;
import de.tk.refactoring.test.projectA.OrderService;

@ImplementationClass
public class OrderServiceProviderImpl implements OrderServiceProvider {

    @ImplementationClass
    @Inject
    public OrderServiceProviderImpl() {
    }

    @Override
public OrderService create(String prefix) {
    return new OrderServiceImpl(prefix);
}

    @Override
public String formatOrderNumber(int num) {
    return OrderServiceImpl.formatOrderNumber(num);
}
}
