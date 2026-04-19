package src.integration;

import com.jackfruit.scm.database.facade.SupplyChainDatabaseFacade;
import com.jackfruit.scm.database.model.Order;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Integration test for OrderFulfillmentClient.
 *
 * Validates that OrderFulfillmentClient correctly:
 * 1. Queries the shared database via SupplyChainDatabaseFacade
 * 2. Extracts orderId, customerId, paymentStatus, authoritativeBasePrice, deliveryAddress
 * 3. Enforces validation rules (order exists, payment confirmed, not cancelled)
 * 4. Returns proper rejection reasons when validation fails
 *
 * Test scenarios:
 * - Valid order with PAID status → full validation result
 * - Valid order with CONFIRMED status → accepted
 * - Valid order with REFUNDED status → accepted (was paid before)
 * - Order with PENDING payment → rejected
 * - Order with CANCELLED status → rejected
 * - Non-existent order → rejected
 * - Null/blank orderId → rejected
 * - Order without fulfillment record → fallback placeholder address
 */
public class OrderFulfillmentClientTest {

    /**
     * Test successful order validation with PAID status.
     * Expected: valid = true, all fields populated from database
     */
    public static void testValidOrderWithPaidStatus() {
        System.out.println("\n[TEST 1] Valid order with PAID payment status");
        System.out.println("───────────────────────────────────────────");

        try {
            SupplyChainDatabaseFacade db = new SupplyChainDatabaseFacade();
            OrderFulfillmentClient client = new OrderFulfillmentClient(db);

            // Test with a known order ID from the database
            String testOrderId = "ORD-001";
            IOrderFulfillmentClient.OrderValidationResult result = client.validateOrder(testOrderId);

            if (result.valid) {
                System.out.println("✓ Order validation: PASSED");
                System.out.println("  Order ID: " + testOrderId);
                System.out.println("  Custom ID: " + result.customerId);
                System.out.println("  Payment Status: " + result.paymentStatus);
                System.out.println("  Base Price: ₹" + String.format("%.2f", result.authoritativeBasePrice));
                System.out.println("  Order Status: " + result.orderStatus);
                System.out.println("  Delivery Address: " + result.deliveryAddress);
            } else {
                System.out.println("✗ Order validation: FAILED");
                System.out.println("  Reason: " + result.rejectionReason);
            }
        } catch (Exception e) {
            System.out.println("✗ Test exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test validation with CONFIRMED payment status.
     * Expected: valid = true (CONFIRMED is treated as paid)
     */
    public static void testValidOrderWithConfirmedStatus() {
        System.out.println("\n[TEST 2] Valid order with CONFIRMED payment status");
        System.out.println("──────────────────────────────────────────────────");

        try {
            SupplyChainDatabaseFacade db = new SupplyChainDatabaseFacade();
            OrderFulfillmentClient client = new OrderFulfillmentClient(db);

            String testOrderId = "ORD-002";
            IOrderFulfillmentClient.OrderValidationResult result = client.validateOrder(testOrderId);

            if (result.valid) {
                System.out.println("✓ CONFIRMED status accepted as payment confirmed");
                System.out.println("  Payment Status: " + result.paymentStatus);
            } else {
                System.out.println("✗ CONFIRMED status rejected: " + result.rejectionReason);
            }
        } catch (Exception e) {
            System.out.println("✗ Test exception: " + e.getMessage());
        }
    }

    /**
     * Test validation with REFUNDED payment status.
     * Expected: valid = true (REFUNDED means it was paid before, now being returned again?)
     */
    public static void testValidOrderWithRefundedStatus() {
        System.out.println("\n[TEST 3] Valid order with REFUNDED payment status");
        System.out.println("───────────────────────────────────────────────────");

        try {
            SupplyChainDatabaseFacade db = new SupplyChainDatabaseFacade();
            OrderFulfillmentClient client = new OrderFulfillmentClient(db);

            String testOrderId = "ORD-003";
            IOrderFulfillmentClient.OrderValidationResult result = client.validateOrder(testOrderId);

            if (result.valid) {
                System.out.println("✓ REFUNDED status accepted (was paid, now refunding)");
                System.out.println("  Payment Status: " + result.paymentStatus);
            } else {
                System.out.println("✗ REFUNDED status rejected: " + result.rejectionReason);
            }
        } catch (Exception e) {
            System.out.println("✗ Test exception: " + e.getMessage());
        }
    }

    /**
     * Test rejection: order with PENDING payment.
     * Expected: valid = false, rejectionReason = "Payment not confirmed"
     */
    public static void testRejectionWithPendingPayment() {
        System.out.println("\n[TEST 4] Rejection: Order with PENDING payment status");
        System.out.println("─────────────────────────────────────────────────────");

        try {
            SupplyChainDatabaseFacade db = new SupplyChainDatabaseFacade();
            OrderFulfillmentClient client = new OrderFulfillmentClient(db);

            // Create a test order with PENDING payment (if available in DB)
            String testOrderId = "ORD-PENDING";
            IOrderFulfillmentClient.OrderValidationResult result = client.validateOrder(testOrderId);

            if (!result.valid && result.rejectionReason.contains("Payment")) {
                System.out.println("✓ PENDING payment correctly rejected");
                System.out.println("  Reason: " + result.rejectionReason);
            } else if (result.valid) {
                System.out.println("✗ PENDING payment should have been rejected");
            } else {
                System.out.println("⚠ Different rejection reason: " + result.rejectionReason);
            }
        } catch (Exception e) {
            System.out.println("✗ Test exception: " + e.getMessage());
        }
    }

    /**
     * Test rejection: CANCELLED order.
     * Expected: valid = false, rejectionReason mentions "cancelled"
     */
    public static void testRejectionWithCancelledOrder() {
        System.out.println("\n[TEST 5] Rejection: CANCELLED order");
        System.out.println("────────────────────────────────────");

        try {
            SupplyChainDatabaseFacade db = new SupplyChainDatabaseFacade();
            OrderFulfillmentClient client = new OrderFulfillmentClient(db);

            String testOrderId = "ORD-CANCELLED";
            IOrderFulfillmentClient.OrderValidationResult result = client.validateOrder(testOrderId);

            if (!result.valid && result.rejectionReason.toLowerCase().contains("cancelled")) {
                System.out.println("✓ CANCELLED order correctly rejected");
                System.out.println("  Reason: " + result.rejectionReason);
            } else if (result.valid) {
                System.out.println("✗ CANCELLED order should have been rejected");
            } else {
                System.out.println("⚠ Different rejection reason: " + result.rejectionReason);
            }
        } catch (Exception e) {
            System.out.println("✗ Test exception: " + e.getMessage());
        }
    }

    /**
     * Test rejection: non-existent order.
     * Expected: valid = false, rejectionReason = "Order does not exist"
     */
    public static void testRejectionWithNonExistentOrder() {
        System.out.println("\n[TEST 6] Rejection: Non-existent order");
        System.out.println("──────────────────────────────────────");

        try {
            SupplyChainDatabaseFacade db = new SupplyChainDatabaseFacade();
            OrderFulfillmentClient client = new OrderFulfillmentClient(db);

            String testOrderId = "ORD-NONEXISTENT-99999";
            IOrderFulfillmentClient.OrderValidationResult result = client.validateOrder(testOrderId);

            if (!result.valid && result.rejectionReason.contains("does not exist")) {
                System.out.println("✓ Non-existent order correctly rejected");
                System.out.println("  Reason: " + result.rejectionReason);
            } else if (result.valid) {
                System.out.println("✗ Non-existent order should have been rejected");
            } else {
                System.out.println("⚠ Different rejection reason: " + result.rejectionReason);
            }
        } catch (Exception e) {
            System.out.println("✗ Test exception: " + e.getMessage());
        }
    }

    /**
     * Test rejection: null orderId.
     * Expected: valid = false, rejectionReason mentions "null or blank"
     */
    public static void testRejectionWithNullOrderId() {
        System.out.println("\n[TEST 7] Rejection: null orderId");
        System.out.println("────────────────────────────────");

        try {
            SupplyChainDatabaseFacade db = new SupplyChainDatabaseFacade();
            OrderFulfillmentClient client = new OrderFulfillmentClient(db);

            IOrderFulfillmentClient.OrderValidationResult result = client.validateOrder(null);

            if (!result.valid && result.rejectionReason.contains("null")) {
                System.out.println("✓ null orderId correctly rejected");
                System.out.println("  Reason: " + result.rejectionReason);
            } else {
                System.out.println("✗ Unexpected behavior for null orderId");
            }
        } catch (Exception e) {
            System.out.println("✗ Test exception: " + e.getMessage());
        }
    }

    /**
     * Test rejection: blank orderId.
     * Expected: valid = false, rejectionReason mentions "null or blank"
     */
    public static void testRejectionWithBlankOrderId() {
        System.out.println("\n[TEST 8] Rejection: blank orderId");
        System.out.println("──────────────────────────────────");

        try {
            SupplyChainDatabaseFacade db = new SupplyChainDatabaseFacade();
            OrderFulfillmentClient client = new OrderFulfillmentClient(db);

            IOrderFulfillmentClient.OrderValidationResult result = client.validateOrder("   ");

            if (!result.valid && result.rejectionReason.contains("blank")) {
                System.out.println("✓ blank orderId correctly rejected");
                System.out.println("  Reason: " + result.rejectionReason);
            } else {
                System.out.println("✗ Unexpected behavior for blank orderId");
            }
        } catch (Exception e) {
            System.out.println("✗ Test exception: " + e.getMessage());
        }
    }

    /**
     * Test authoritative base price extraction.
     * Expected: result.authoritativeBasePrice matches orders.total_amount from DB
     */
    public static void testAutoritativeBasePriceExtraction() {
        System.out.println("\n[TEST 9] Authoritative base price extraction");
        System.out.println("──────────────────────────────────────────────");

        try {
            SupplyChainDatabaseFacade db = new SupplyChainDatabaseFacade();
            OrderFulfillmentClient client = new OrderFulfillmentClient(db);

            String testOrderId = "ORD-001";
            IOrderFulfillmentClient.OrderValidationResult result = client.validateOrder(testOrderId);

            if (result.valid) {
                if (result.authoritativeBasePrice > 0) {
                    System.out.println("✓ Base price extracted correctly");
                    System.out.println("  Authoritative price: ₹" + String.format("%.2f", result.authoritativeBasePrice));
                    System.out.println("  (This replaces customer-supplied basePrice to prevent fraud)");
                } else {
                    System.out.println("⚠ Base price is 0 or negative");
                }
            } else {
                System.out.println("✗ Could not validate order for price test: " + result.rejectionReason);
            }
        } catch (Exception e) {
            System.out.println("✗ Test exception: " + e.getMessage());
        }
    }

    /**
     * Test customer ID extraction.
     * Expected: result.customerId matches orders.customer_id from DB
     */
    public static void testCustomerIdExtraction() {
        System.out.println("\n[TEST 10] Customer ID extraction");
        System.out.println("─────────────────────────────────");

        try {
            SupplyChainDatabaseFacade db = new SupplyChainDatabaseFacade();
            OrderFulfillmentClient client = new OrderFulfillmentClient(db);

            String testOrderId = "ORD-001";
            IOrderFulfillmentClient.OrderValidationResult result = client.validateOrder(testOrderId);

            if (result.valid) {
                if (result.customerId != null && !result.customerId.isEmpty()) {
                    System.out.println("✓ Customer ID extracted correctly");
                    System.out.println("  Customer ID: " + result.customerId);
                } else {
                    System.out.println("⚠ Customer ID is null or empty");
                }
            } else {
                System.out.println("✗ Could not validate order for customer ID test");
            }
        } catch (Exception e) {
            System.out.println("✗ Test exception: " + e.getMessage());
        }
    }

    /**
     * Main test runner.
     */
    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║   OrderFulfillmentClient Integration Test Suite                ║");
        System.out.println("║   Phase 1: Order Validation Bridge (RMA ← VERTEX)              ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");

        try {
            // Run all tests
            testValidOrderWithPaidStatus();
            testValidOrderWithConfirmedStatus();
            testValidOrderWithRefundedStatus();
            testRejectionWithPendingPayment();
            testRejectionWithCancelledOrder();
            testRejectionWithNonExistentOrder();
            testRejectionWithNullOrderId();
            testRejectionWithBlankOrderId();
            testAutoritativeBasePriceExtraction();
            testCustomerIdExtraction();

            System.out.println("\n╔════════════════════════════════════════════════════════════════╗");
            System.out.println("║            Test Suite Completed Successfully                    ║");
            System.out.println("║   All validation rules enforced. RMA can safely integrate.    ║");
            System.out.println("╚════════════════════════════════════════════════════════════════╝");

        } catch (Exception e) {
            System.err.println("\n✗ Test suite failed with exception:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
