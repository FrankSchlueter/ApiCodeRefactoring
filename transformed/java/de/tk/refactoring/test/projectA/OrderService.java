package de.tk.refactoring.test.projectA;

public interface OrderService extends BaseService {

    public static final String DEFAULT_CURRENCY = "EUR";

    String createOrder(CustomerData customer);

    OrderService getCollaborator();

    class ProviderHolder {

        @jakarta.inject.Inject
public static OrderServiceProvider provider;
    }

    static OrderService create(String prefix) {
        return ProviderHolder.provider.create(prefix);
    }

    static String formatOrderNumber(int num) {
        return ProviderHolder.provider.formatOrderNumber(num);
    }
}
