package de.tk.refactoring.test.projectB;

import de.tk.refactoring.test.projectA.BillingService;
import de.tk.refactoring.test.projectA.CustomerData;
import de.tk.refactoring.test.projectA.OrderService;

/**
 * External module client (projectB) that exclusively consumes the API of projectA:
 * - Constructor calls (replaced by static create method on API interface)
 * - Static API methods and public static final constants
 * - Instance API methods (including inherited BaseService API method)
 * - Usage of BillingService (replaced by BillingServiceApi across field, parameter, local variable, and return type)
 */
public class ExternalOrderClient {

    private BillingService billingField;

    public ExternalOrderClient() {
        this.billingField = new BillingService();
    }

    public BillingService processBilling(BillingService inputBilling) {
        BillingService localBilling = inputBilling;
        return localBilling;
    }

    public String executeExternalScenario() {
        OrderService service = new OrderService("EXT");
        CustomerData customer = new CustomerData("Alice");
        String orderResult = service.createOrder(customer);
        String formatted = OrderService.formatOrderNumber(42);
        String currency = OrderService.DEFAULT_CURRENCY;
        String baseId = service.getBaseId();

        BillingService activeBilling = processBilling(this.billingField);
        int total = activeBilling.calculateTotal(50);
        String invoiceCode = activeBilling.getInvoiceCode(50);

        return orderResult + "|" + formatted + "|" + currency + "|" + baseId + "|" + total + "|" + invoiceCode;
    }
}
