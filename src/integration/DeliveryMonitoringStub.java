package src.integration;

/**
 * Stub implementation of IDeliveryMonitoringClient.
 *
 * Used when ramen-noodles-delivery-monitoring.jar is NOT yet on the classpath.
 * Replace this with DeliveryMonitoringAdapter (the real implementation) once
 * the JAR is available in lib/.
 *
 * The stub assumes all orders are delivered so that RMA logic can be tested
 * end-to-end before the delivery JAR arrives.
 */
public class DeliveryMonitoringStub implements IDeliveryMonitoringClient {

    @Override
    public String getOrderStatus(String orderId) {
        // Stub: assume delivered — replace with real facade call
        System.out.println("[DeliveryStub] getOrderStatus(" + orderId + ") → ORDER_DELIVERED (stub)");
        return "ORDER_DELIVERED";
    }

    @Override
    public String getPOD(String orderId) {
        // Stub: no POD — RMA falls back to daysSincePurchase for warranty calc
        System.out.println("[DeliveryStub] getPOD(" + orderId + ") → null (stub)");
        return null;
    }

    @Override
    public String getTrackingURL(String orderId) {
        System.out.println("[DeliveryStub] getTrackingURL(" + orderId + ") → null (stub)");
        return null;
    }

    @Override
    public String createReverseDelivery(String customerId,
                                         String pickupAddress,
                                         String destination) {
        String reverseId = "REV-STUB-" + customerId.substring(0, Math.min(4, customerId.length()));
        System.out.println("[DeliveryStub] createReverseDelivery: customer=" + customerId
                + " pickup='" + pickupAddress + "' destination=" + destination
                + " → " + reverseId + " (stub)");
        return reverseId;
    }
}
