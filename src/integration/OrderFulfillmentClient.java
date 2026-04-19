package src.integration;

import com.jackfruit.scm.database.facade.SupplyChainDatabaseFacade;
import com.jackfruit.scm.database.model.Order;
import java.util.Optional;

/**
 * Queries the shared SupplyChainDatabaseFacade to validate orders and retrieve
 * order data on behalf of the RMA subsystem.
 *
 * Uses the same database-module-1.0.0-SNAPSHOT-standalone.jar already on the
 * RMA classpath — no additional JAR from VERTEX required.
 *
 * If VERTEX later publishes a standalone service JAR exposing
 * IOrderFulfillmentService, this class can be replaced with an adapter that
 * delegates to their facade instead of the DB directly.
 *
 * Validation rules enforced:
 *   - Order must exist in the orders table
 *   - paymentStatus must be PAID or CONFIRMED (case-insensitive)
 *   - orderStatus must not be CANCELLED
 */
public class OrderFulfillmentClient implements IOrderFulfillmentClient {

    private final SupplyChainDatabaseFacade db;

    public OrderFulfillmentClient(SupplyChainDatabaseFacade db) {
        this.db = db;
    }

    @Override
    public OrderValidationResult validateOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return OrderValidationResult.rejected("orderId is null or blank");
        }

        // ── Existence check via OrdersSubsystemFacade ─────────────────────────
        Optional<Order> orderOpt;
        try {
            orderOpt = db.orders().getOrder(orderId);
        } catch (Exception e) {
            return OrderValidationResult.rejected(
                    "Order lookup failed for orderId=" + orderId + ": " + e.getMessage());
        }

        if (orderOpt == null || orderOpt.isEmpty()) {
            return OrderValidationResult.rejected(
                    "Order does not exist: " + orderId);
        }

        Order order = orderOpt.get();

        // ── Order status check ────────────────────────────────────────────────
        String orderStatus = order.getOrderStatus();
        if ("CANCELLED".equalsIgnoreCase(orderStatus)) {
            return OrderValidationResult.rejected(
                    "Order is cancelled and cannot be returned: " + orderId);
        }

        // ── Payment status check ──────────────────────────────────────────────
        // Only orders with confirmed payment are eligible for refund.
        String paymentStatus = order.getPaymentStatus();
        boolean paymentConfirmed = "PAID".equalsIgnoreCase(paymentStatus)
                || "CONFIRMED".equalsIgnoreCase(paymentStatus)
                || "REFUNDED".equalsIgnoreCase(paymentStatus);  // already refunded = was paid
        if (!paymentConfirmed) {
            return OrderValidationResult.rejected(
                    "Payment not confirmed for order " + orderId
                    + " (paymentStatus=" + paymentStatus + ")");
        }

        // ── Customer ID ───────────────────────────────────────────────────────
        String customerId = order.getCustomerId();

        // ── Authoritative base price ──────────────────────────────────────────
        // orders.total_amount is used as the refund basis, replacing the
        // customer-supplied basePrice in ReturnRequest.
        double authoritativeBasePrice = (order.getTotalAmount() != null)
                ? order.getTotalAmount().doubleValue()
                : 0.0;

        // ── Delivery address ──────────────────────────────────────────────────
        // Try to get the shipping address from fulfillment_orders for use in
        // the reverse delivery pickup. Fall back to a placeholder if not found.
        String deliveryAddress = resolveDeliveryAddress(orderId);

        return new OrderValidationResult(
                customerId,
                paymentStatus,
                authoritativeBasePrice,
                deliveryAddress,
                orderStatus
        );
    }

    /**
     * Attempts to retrieve the customer's shipping address from fulfillment_orders
     * via the warehouse/fulfillment subsystem. Used as the pickup address for the
     * reverse delivery created in processReturn() Step 10.
     *
     * Currently, the OrdersSubsystemFacade does not expose fulfillment_orders directly.
     * This is a TODO integration point for Phase 5 (Real-Time Delivery).
     * For now, returns a placeholder that includes the order ID.
     *
     * Future integration: call an IWarehouseClient or IDeliveryClient to get the
     * real shipping address from fulfillment_orders.shipping_address.
     */
    private String resolveDeliveryAddress(String orderId) {
        // FUTURE: Query warehouse or fulfillment subsystem for real address
        // For now, return placeholder — RMA will use real address from
        // DeliveryMonitoringAdapter.getDeliveryAddress() in Phase 5.
        return "Customer address for order " + orderId;   // placeholder for Phase 5 enhancement
    }
}
