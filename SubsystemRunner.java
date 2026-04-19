import src.core.ReturnRequest;
import src.service.RMAGatewayFacade;

/**
 * SubsystemRunner - Entry point for RMA subsystem.
 *
 * Integrates with scm-exception-handler-v3.jar and database-module-1.0.0-SNAPSHOT-standalone.jar.
 *
 * Exception handling is performed entirely through ProductReturnsSubsystem.INSTANCE — no manual
 * handler registration is needed here. The subsystem singleton (from the JAR) manages the
 * popup, database logging, and HTTP POST to the Exception Handler team automatically.
 *
 * Inventory stock operations go through InventoryUI per the Inventory Interface Specification.
 */
public class SubsystemRunner {

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  RMA Subsystem - Product Returns");
        System.out.println("========================================");
        System.out.println();

        try {
            // 1. Initialize RMA Gateway Facade
            //    Constructor sets up DB connections and InventoryUI instance.
            System.out.println("[*] Initializing RMA Gateway Facade...");
            RMAGatewayFacade rmaFacade = new RMAGatewayFacade();
            System.out.println("[✓] RMA Gateway Facade initialized successfully");
            System.out.println();

            // 2. Create test return request
            System.out.println("--- Processing Return Request ---");
            ReturnRequest returnRequest = new ReturnRequest(
                "ORD-001",       // orderId
                "PROD-001",      // productId
                "Electronics",  // category
                10,              // daysSincePurchase
                "Device stopped working after two days. Very disappointed.", // customerComment
                1200.00          // basePrice
            );

            System.out.println("Order ID:            " + returnRequest.orderId);
            System.out.println("Product ID:          " + returnRequest.productId);
            System.out.println("Category:            " + returnRequest.category);
            System.out.println("Days Since Purchase: " + returnRequest.daysSincePurchase);
            System.out.println("Base Price:          $" + returnRequest.basePrice);
            System.out.println("Customer Comment:    " + returnRequest.customerComment);
            System.out.println();

            // 3. Process the return through RMA facade
            System.out.println("--- Processing Return ---");
            double refund = rmaFacade.processReturn(
                returnRequest,
                "Faulty",
                "Internal circuit failure suspected during inspection"
            );
            System.out.println("[✓] Return request processed — Refund: ₹" + String.format("%.2f", refund));
            System.out.println("[✓] Data persisted to database via database-module jar");
            System.out.println("[✓] Reverse delivery scheduled via ramen-noodles-delivery-monitoring jar");
            System.out.println("[✓] Exceptions handled via ProductReturnsSubsystem (scm-exception-handler jar)");
            System.out.println();

            System.out.println("========================================");
            System.out.println("  RMA Subsystem Completed Successfully");
            System.out.println("========================================");

        } catch (Exception e) {
            System.err.println();
            System.err.println("[ERROR] RMA Subsystem encountered a critical error");
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            e.printStackTrace();
            System.exit(1);
        }
    }
}
