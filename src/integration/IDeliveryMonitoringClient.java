package src.integration;

/**
 * Routes exceptions and queries through DeliveryMonitoringFacade
 * (com.ramennoodles.delivery.facade.DeliveryMonitoringFacade) via
 * DeliveryMonitoringAdapter.
 */
public interface IDeliveryMonitoringClient {

    /**
     * Get the current status of an order in the delivery system.
     * RMA uses this to confirm the order was actually delivered before
     * accepting a return request.
     *
     * @param orderId the order ID (matches orders.order_id in the schema)
     * @return string status — expected "ORDER_DELIVERED" for valid returns
     */
    String getOrderStatus(String orderId);

    /**
     * Get the proof-of-delivery record for an order.
     * Provides the actual delivery timestamp, which RMA uses to compute
     * the warranty window more accurately than daysSincePurchase alone.
     *
     * @param orderId the order ID
     * @return POD metadata as a simple string (timestamp + signature ref),
     *         or null if no POD exists (order not delivered)
     */
    String getPOD(String orderId);

    /**
     * Get the tracking URL for a delivered order.
     * Included in the return confirmation sent to the customer.
     *
     * @param orderId the order ID
     * @return tracking URL string, or null if unavailable
     */
    String getTrackingURL(String orderId);

    /**
     * Create a reverse delivery to pick up the returned item from the customer
     * and route it to the correct destination (warehouse, repair centre, or
     * e-waste facility) based on the disposition decision.
     *
     * Maps to: DeliveryMonitoringFacade.createAndInitializeDelivery()
     *
     * @param customerId     customer to collect from
     * @param pickupAddress  customer's address (collected from delivery record)
     * @param destination    one of "WAREHOUSE_RESTOCK", "TECHNICAL_REPAIR",
     *                       "E_WASTE_SCRAP"
     * @return reverse-delivery order ID, or null on failure
     */
    String createReverseDelivery(String customerId,
                                  String pickupAddress,
                                  String destination);
}
