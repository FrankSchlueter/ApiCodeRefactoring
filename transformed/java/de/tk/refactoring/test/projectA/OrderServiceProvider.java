package de.tk.refactoring.test.projectA;

public interface OrderServiceProvider {

    OrderService create(String prefix);

    String formatOrderNumber(int num);
}
