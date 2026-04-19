package src.integration;

/**
 * RMA's view of the Order Fulfillment subsystem (VERTEX — Team #17).
 *
 * What RMA needs before accepting a return:
 * 1. Order exists in the system
 * 2. Order belongs to the claimed customer
 * 3. Payment was confirmed (only PAID orders are eligible for refund)
 * 4. Order total — authoritative basePrice source to replace customer-supplied
 * value
 * 5. Delivery address — used by createReverseDelivery() for real customer
 * pickup
 *
 * Implementation: OrderFulfillmentClient queries the shared
 * SupplyChainDatabaseFacade.orders().getOrder() — the same DB module RMA
 * already uses, so no additional JAR is needed.
 *
 * If VERTEX publishes a standalone JAR in the future, replace
 * OrderFulfillmentClient with an adapter that calls IOrderFulfillmentService.
 */
public interface IOrderFulfillmentClient {

    /**
     * Validate that an order exists, payment is confirmed, and the order
     * is in a state eligible for return (FULFILLED or DELIVERED).
     *
     * @param orderId the order ID to validate
     * @return validation result with all fields needed for processing
     */
    OrderValidationResult validateOrder(String orderId);

    /**
     * Encapsulates all order data RMA needs in a single call.
     * Fields sourced from the orders table via OrdersSubsystemFacade.
     */
    class OrderValidationResult {

        /** true only if order exists AND paymentStatus = PAID/CONFIRMED */
        public final boolean valid;

        /** Reason string if valid = false */
        public final String rejectionReason;

        /** orders.customer_id — used to verify the return requester owns the order */
        public final String customerId;

        /**
         * orders.payment_status — "PAID" / "CONFIRMED" etc.
         * RMA only refunds if this is a paid order.
         */
        public final String paymentStatus;

        /**
         * orders.total_amount — authoritative purchase price.
         * Replaces the customer-supplied basePrice in ReturnRequest to prevent
         * refund fraud.
         */
        public final double authoritativeBasePrice;

        /**
         * Delivery address from fulfillment_orders.shipping_address.
         * Used as the pickupAddress in createReverseDelivery().
         */
        public final String deliveryAddress;

        /** orders.order_status — e.g. FULFILLED, DELIVERED, CANCELLED */
        public final String orderStatus;

        /** Successful result */
        public OrderValidationResult(String customerId, String paymentStatus,
                double authoritativeBasePrice,
                String deliveryAddress, String orderStatus) {
            this.valid = true;
            this.rejectionReason = null;
            this.customerId = customerId;
            this.paymentStatus = paymentStatus;
            this.authoritativeBasePrice = authoritativeBasePrice;
            this.deliveryAddress = deliveryAddress;
            this.orderStatus = orderStatus;
        }

        /** Rejection result */
        public static OrderValidationResult rejected(String reason) {
            return new OrderValidationResult(reason);
        }

        private OrderValidationResult(String reason) {
            this.valid = false;
            this.rejectionReason = reason;
            this.customerId = null;
            this.paymentStatus = null;
            this.authoritativeBasePrice = 0.0;
            this.deliveryAddress = null;
            this.orderStatus = null;
        }
    }
}
