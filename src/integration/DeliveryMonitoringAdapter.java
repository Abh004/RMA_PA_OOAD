package src.integration;

import com.ramennoodles.delivery.facade.DeliveryMonitoringFacade;
import com.ramennoodles.delivery.observer.DeliveryEventType;
import com.ramennoodles.delivery.model.PODRecord;

/**
 * Real implementation of IDeliveryMonitoringClient.
 * Wraps DeliveryMonitoringFacade from ramen-noodles-delivery-monitoring.jar.
 *
 * HOW TO ACTIVATE:
 *   1. Copy ramen-noodles-delivery-monitoring.jar into RMA_PA/lib/
 *   2. Uncomment the three import lines above
 *   3. Uncomment the facade field and constructor body
 *   4. Uncomment the real method bodies (marked with "REAL IMPL")
 *   5. In RMAGatewayFacade, change new DeliveryMonitoringStub()
 *      to new DeliveryMonitoringAdapter()
 *   6. Add the JAR to your javac -cp command
 *
 * Integration points used (from Ramen Noodles Partner Guide §Key API Methods):
 *   getOrderStatus(orderId)  — confirm ORDER_DELIVERED before accepting return
 *   getPOD(orderId)          — get delivery timestamp for warranty window
 *   getTrackingURL(orderId)  — include in return confirmation to customer
 *   subscribeToEvents(ORDER_DELIVERED, ...) — future: auto-open return window
 */
public class DeliveryMonitoringAdapter implements IDeliveryMonitoringClient {

    private final DeliveryMonitoringFacade facade;

    public DeliveryMonitoringAdapter() {
        this.facade = new DeliveryMonitoringFacade();

        // Subscribe to ORDER_DELIVERED — opens return eligibility window in RMA
        facade.subscribeToEvents(DeliveryEventType.ORDER_DELIVERED, (eventType, data) -> {
            String orderId = (String) data.get("orderId");
            System.out.println("[RMA] Return window opened for order: " + orderId);
        });
    }

    @Override
    public String getOrderStatus(String orderId) {
        com.ramennoodles.delivery.enums.OrderStatus status = facade.getOrderStatus(orderId);
        return status != null ? status.name() : null;
    }

    @Override
    public String getPOD(String orderId) {
        PODRecord pod = facade.getPOD(orderId);
        if (pod == null) return null;
        // Format: "submittedAt|signatureUrl" — parsed in RMAGatewayFacade for warranty window
        return pod.getSubmittedAt() + "|" + pod.getSignatureUrl();
    }

    @Override
    public String getTrackingURL(String orderId) {
        return facade.getTrackingURL(orderId);
    }

    /**
     * Creates a reverse delivery (return pickup) via DeliveryMonitoringFacade.
     * The customer is registered as the pickup origin. The destination address
     * is derived from the disposition label so the physical goods flow to the
     * right facility.
     *
     * Destination address mapping:
     *   WAREHOUSE_RESTOCK  → SCM Central Warehouse
     *   TECHNICAL_REPAIR   → SCM Repair Centre
     *   E_WASTE_SCRAP      → SCM E-Waste Facility
     */
    @Override
    public String createReverseDelivery(String customerId,
                                         String pickupAddress,
                                         String destination) {
        // Map disposition label → physical drop-off address
        String dropoffAddress;
        com.ramennoodles.delivery.model.Coordinate dropoff;
        switch (destination) {
            case "TECHNICAL_REPAIR":
                dropoffAddress = "SCM Repair Centre, Industrial Zone, Block R";
                dropoff = new com.ramennoodles.delivery.model.Coordinate(12.9450, 77.6100);
                break;
            case "E_WASTE_SCRAP":
                dropoffAddress = "SCM E-Waste Facility, Green District, Zone W";
                dropoff = new com.ramennoodles.delivery.model.Coordinate(12.9200, 77.5800);
                break;
            default: // WAREHOUSE_RESTOCK
                dropoffAddress = "SCM Central Warehouse, Logistics Park, Dock A";
                dropoff = new com.ramennoodles.delivery.model.Coordinate(12.9352, 77.6245);
        }

        // Pickup coordinate — approximated from city centre; real system would geocode
        com.ramennoodles.delivery.model.Coordinate pickup =
                new com.ramennoodles.delivery.model.Coordinate(12.9716, 77.5946);

        // Register customer as a temporary profile for this reverse delivery
        // (facade requires a registered customerId before creating an order)
        facade.registerCustomer("RETURN-" + customerId, "", "");
        String reverseCustomerId = facade.getCustomers().keySet().stream()
                .filter(id -> facade.getCustomers().get(id).getName()
                        .equals("RETURN-" + customerId))
                .findFirst().orElse(customerId);

        com.ramennoodles.delivery.model.Order reverseOrder =
                facade.createAndInitializeDelivery(
                        reverseCustomerId,
                        pickupAddress,
                        dropoffAddress,
                        pickup,
                        dropoff
                );

        return reverseOrder != null ? reverseOrder.getOrderId() : null;
    }
}
