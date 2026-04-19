package src.service;

import com.jackfruit.scm.database.adapter.ReturnsAdapter;
import com.jackfruit.scm.database.facade.SupplyChainDatabaseFacade;
import com.jackfruit.scm.database.model.ReturnsModels.ProductReturn;
import com.scm.handler.SCMExceptionHandler;
import com.scm.subsystems.ProductReturnsSubsystem;
import inventory_subsystem.InventoryExceptionSource;
import inventory_subsystem.InventoryService;
import inventory_subsystem.InventoryUI;
import java.time.LocalDateTime;
import java.util.UUID;
import src.core.*;
import src.integration.DeliveryMonitoringAdapter;
import src.integration.IDeliveryMonitoringClient;
import src.integration.IMultiLevelPricingClient;
import src.integration.IOrderFulfillmentClient;
import src.integration.IOrderFulfillmentClient.OrderValidationResult;
import src.integration.IReturnStatisticsObserver;
import src.integration.MultiLevelPricingClient;
import src.integration.OrderFulfillmentClient;
import src.integration.ReturnStatisticsObserver;
import src.patterns.*;

/**
 * RMA Gateway Facade — single entry point for all product return operations.
 *
 * Integrations:
 * Exception handling : ProductReturnsSubsystem.INSTANCE
 * (scm-exception-handler-v3.jar)
 * Inventory : InventoryUI / InventoryService (inventory_subsystem)
 * Real-Time Delivery : IDeliveryMonitoringClient / DeliveryMonitoringFacade
 * (ramen-noodles-delivery-monitoring.jar — Team Ramen Noodles)
 *
 * processReturn() complete flow:
 * 1. Validate refund amount
 * 2. Verify ORDER_DELIVERED status (Real-Time Delivery)
 * 3. Compute accurate warranty window from POD timestamp
 * 4. Check product exists in inventory
 * 5. Run validation chain (warranty window + condition)
 * 6. Select DispositionStrategy from condition grade
 * 7. Build RefundCalculator chain from condition grade
 * 8. Process VOC sentiment
 * 9. Execute inventory action based on disposition
 * 10. Create reverse delivery for return pickup (Real-Time Delivery)
 * 11. Persist return record + refund amount to database
 */
public class RMAGatewayFacade {

    // SCM Exception Handler singleton for Product Returns
    private final ProductReturnsSubsystem exceptions = ProductReturnsSubsystem.INSTANCE;

    private final ReturnValidationHandler validationChain;
    private final VocAnalyzer vocAnalyzer = new VocAnalyzer();

    private SupplyChainDatabaseFacade dbFacade;
    private ReturnsAdapter returnsAdapter;
    private InventoryUI inventoryUI; // §3: only interact via this interface
    private final IDeliveryMonitoringClient deliveryClient; // Real-Time Delivery (Ramen Noodles)
    private IOrderFulfillmentClient orderClient; // Order Fulfillment (VERTEX)
    private IReturnStatisticsObserver returnStatsObserver; // Return analytics tracking (Phase 7)
    private IMultiLevelPricingClient pricingClient; // Multi-level pricing (Phase 2)

    public RMAGatewayFacade() {
        this.validationChain = ValidationChainFactory.createDefaultChain();

        // Real-Time Delivery — Team Ramen Noodles
        this.deliveryClient = new DeliveryMonitoringAdapter();

        try {
            this.dbFacade = new SupplyChainDatabaseFacade();
            this.returnsAdapter = new ReturnsAdapter(dbFacade);

            // Order Fulfillment client — VERTEX Team #17
            // Shares the same DB facade as RMA (no additional JAR needed)
            this.orderClient = new OrderFulfillmentClient(dbFacade);

            // Inventory setup — §2 of the Inventory Interface Specification
            InventoryExceptionSource exceptionSource = new InventoryExceptionSource();
            exceptionSource.registerHandler(SCMExceptionHandler.INSTANCE);
            this.inventoryUI = new InventoryService(exceptionSource);

            // Return Statistics Observer — Phase 7 (analytics tracking)
            this.returnStatsObserver = new ReturnStatisticsObserver(returnsAdapter);

            // Multi-Level Pricing Client — Phase 2 (fraud prevention via market prices)
            try {
                // Use reflection to avoid compile-time dependency on pricing subsystem
                Class<?> priceListManagerClass = Class.forName(
                        "com.pricingos.pricing.pricelist.PriceListManager"
                );
                Object priceListManager = priceListManagerClass.getDeclaredConstructor().newInstance();
                this.pricingClient = new MultiLevelPricingClient(priceListManager);
                System.out.println("[RMA] Pricing client initialized successfully");
            } catch (Exception e) {
                System.err.println("[RMA] WARNING: Could not initialize PricingClient: "
                        + e.getMessage() + " — will fall back to request.basePrice");
                this.pricingClient = null;
            }

        } catch (Exception e) {
            System.err.println("[RMA] UNREGISTERED_EXCEPTION: Database initialization failed: "
                    + e.getMessage());
        }
    }

    /**
     * Process a customer return request end-to-end.
     *
     * @param request   customer return data (orderId, productId, basePrice, etc.)
     * @param condition condition grade: Sealed | Open | Damaged | Faulty | Beyond
     * @param notes     inspector notes
     * @return refund amount granted, or 0.0 if rejected
     */
    public double processReturn(ReturnRequest request, String condition, String notes) {

        // ── Step 0: Validate order via Order Fulfillment (VERTEX) ────────────
        // Confirms: order exists, payment confirmed, gets authoritative
        // customerId + basePrice + delivery address from orders table.
        OrderValidationResult orderVal = (orderClient != null)
                ? orderClient.validateOrder(request.orderId)
                : null;

        if (orderVal != null && !orderVal.valid) {
            // ID 201 (ORDER_NOT_FOUND) or payment issue — use onInvalidOrderId if available
            System.err.println("[RMA] UNREGISTERED_EXCEPTION: Order validation failed for "
                    + request.orderId + ": " + orderVal.rejectionReason);
            return 0.0;
        }

        // ── Step 0+: Get authoritative base price from pricing (Phase 2) ────────────
        // Prevent refund fraud by using market price, not customer-supplied claim
        double authoritativeBasePrice = request.basePrice;  // fallback default
        if (this.pricingClient != null) {
            try {
                // Query: getActivePrice(skuId, region, channel)
                authoritativeBasePrice = this.pricingClient.getActivePrice(
                        request.productId,    // SKU
                        "GLOBAL",             // region (from order or default)
                        "ONLINE"              // channel (parameterizable)
                );
                System.out.println("[RMA] Step 0+: Got market price ₹" + authoritativeBasePrice
                        + " from pricing (original claim: ₹" + request.basePrice + ")");

            } catch (Exception e) {
                // Price not found or lookup failed — graceful fallback
                System.err.println("[RMA] Step 0+: Price lookup failed for SKU=" + request.productId
                        + ": " + e.getMessage() + " — using request.basePrice as fallback");
                authoritativeBasePrice = request.basePrice;
            }
        }

        // Override customer-supplied basePrice with the authoritative market price
        double effectiveBasePrice = authoritativeBasePrice;
        if (orderVal != null && orderVal.authoritativeBasePrice > 0) {
            // Order Fulfillment also provided a price — use the minimum (most conservative)
            effectiveBasePrice = Math.min(authoritativeBasePrice, orderVal.authoritativeBasePrice);
        }

        String resolvedCustomerId = (orderVal != null && orderVal.customerId != null)
                ? orderVal.customerId
                : "CUST-UNKNOWN";

        String resolvedPickupAddress = (orderVal != null && orderVal.deliveryAddress != null)
                ? orderVal.deliveryAddress
                : "Customer address for order " + request.orderId;

        // ── Step 1: Validate refund amount (uses authoritative base price) ───
        if (effectiveBasePrice <= 0) {
            exceptions.onInvalidRefundAmount(request.orderId, effectiveBasePrice);
            return 0.0;
        }

        try {
            // ── Step 2: Verify delivery was completed ─────────────────────────
            // Only delivered orders are eligible for return.
            String deliveryStatus = deliveryClient.getOrderStatus(request.orderId);
            if (!"ORDER_DELIVERED".equals(deliveryStatus)) {
                System.err.println("[RMA] UNREGISTERED_EXCEPTION: Return rejected — order not yet"
                        + " delivered. Order: " + request.orderId
                        + " Status: " + deliveryStatus);
                return 0.0;
            }

            // ── Step 3: Refine warranty window using POD timestamp ────────────
            // Prevents warranty fraud: compute days from actual delivery date,
            // not from the customer-supplied daysSincePurchase.
            // deliveredAt is kept at method scope so Step 11 can use it to
            // compute warrantyValidUntil = deliveredAt + warrantyPeriodDays.
            String pod = deliveryClient.getPOD(request.orderId);
            int effectiveDaysSince = request.daysSincePurchase;
            LocalDateTime deliveredAt = null; // set from POD if available
            if (pod != null) {
                try {
                    deliveredAt = LocalDateTime.parse(pod.split("\\|")[0]);
                    effectiveDaysSince = (int) java.time.temporal.ChronoUnit.DAYS
                            .between(deliveredAt.toLocalDate(),
                                    LocalDateTime.now().toLocalDate());
                } catch (Exception parseEx) {
                    System.err.println("[RMA] POD timestamp parse failed — using supplied days: "
                            + parseEx.getMessage());
                }
            }

            // ── Step 4: Verify product exists in inventory ────────────────────
            int currentStock = inventoryUI.getStock(request.productId, "DEFAULT", "RETURNS");
            if (currentStock == 0) {
                System.err.println("[RMA] UNREGISTERED_EXCEPTION: Product not found in inventory."
                        + " Product ID: " + request.productId);
                return 0.0;
            }

            // ── Step 5: Run validation chain ──────────────────────────────────
            ReturnRequest effectiveRequest = new ReturnRequest(
                    request.orderId, request.productId, request.category,
                    effectiveDaysSince, request.customerComment, request.basePrice);
            InspectedItem item = new InspectedItem(effectiveRequest, condition, notes);
            ValidationResult val = validationChain.handle(item);

            if (!val.approved) {
                if (val.message.contains("Warranty") || val.message.contains("warranty")) {
                    String warrantyExpiry = LocalDateTime.now()
                            .minusDays(effectiveDaysSince - 30L)
                            .toLocalDate().toString();
                    exceptions.onWarrantyExpired(request.productId, warrantyExpiry);
                } else {
                    System.err.println("[RMA] UNREGISTERED_EXCEPTION: Return validation rejected: "
                            + val.message + ". Condition: " + condition);
                }
                return 0.0;
            }

            // ── Step 6: Select DispositionStrategy from condition grade ────────
            DispositionStrategy strategy = DispositionStrategyFactory.select(condition);
            String disposition = strategy.route(item); // WAREHOUSE_RESTOCK | TECHNICAL_REPAIR | E_WASTE_SCRAP
            System.out.println("[RMA] Disposition decision: " + disposition
                    + " (condition=" + condition + ")");

            // ── Step 7: Build RefundCalculator chain ──────────────────────────
            RefundCalculator calculator = DispositionStrategyFactory.buildRefundChain(
                    effectiveBasePrice, condition);
            double refundAmount = calculator.calculateRefund();
            System.out.println("[RMA] Refund amount: ₹" + String.format("%.2f", refundAmount)
                    + " (base=₹" + effectiveBasePrice + ", condition=" + condition + ")");

            // ── Step 8: Process Voice of Customer sentiment ───────────────────
            vocAnalyzer.processComment(request.customerComment);

            // ── Step 9: Inventory action based on disposition ─────────────────
            if ("WAREHOUSE_RESTOCK".equals(disposition)) {
                // Return approved for restock — add back to inventory via
                // InventoryUI.addStock() §4.1
                inventoryUI.addStock(request.productId, "DEFAULT", "RETURNS", 1);
            } else if ("TECHNICAL_REPAIR".equals(disposition)) {
                // Route to repair — transfer from RETURNS holding to REPAIR location §4.3
                inventoryUI.transferStock(request.productId, "DEFAULT", "REPAIR", "RETURNS", 1);
            }
            // E_WASTE_SCRAP: item is written off — no inventory action needed

            // ── Step 10: Create reverse delivery for return pickup ─────────────
            // The delivery system orchestrates rider dispatch to collect the item
            // from the customer and route it to the correct facility.
            String pickupAddress = resolvedPickupAddress;
            String reverseDeliveryId = deliveryClient.createReverseDelivery(
                    resolvedCustomerId, // real customerId from Order Fulfillment
                    resolvedPickupAddress, // real shipping address from fulfillment_orders
                    disposition);
            String reverseTrackingUrl = reverseDeliveryId != null
                    ? deliveryClient.getTrackingURL(reverseDeliveryId)
                    : null;
            System.out.println("[RMA] Reverse delivery created: " + reverseDeliveryId
                    + " tracking=" + reverseTrackingUrl);

            // ── Step 11: Persist return record to database ────────────────────
            String returnId = "RET-" + UUID.randomUUID().toString().substring(0, 8);
            String originalTrackingUrl = deliveryClient.getTrackingURL(request.orderId);
            String transportDetails = buildTransportDetails(
                    originalTrackingUrl, reverseTrackingUrl, disposition);

            // warrantyValidUntil: delivery date + product warranty period.
            // deliveredAt comes from the Real-Time Delivery POD (Step 3).
            // warrantyPeriodDays comes from a category-based lookup — this will
            // be replaced with a direct read from the Inventory/Product interface
            // (products.shelf_life_days) once that integration is added.
            int warrantyDays = warrantyPeriodDays(request.category);
            LocalDateTime warrantyValidUntil = (deliveredAt != null)
                    ? deliveredAt.plusDays(warrantyDays)
                    : LocalDateTime.now().plusDays(warrantyDays);
            System.out.println("[RMA] Warranty valid until: " + warrantyValidUntil.toLocalDate()
                    + " (" + warrantyDays + " days from delivery, category=" + request.category + ")");

            ProductReturn returnRecord = new ProductReturn(
                    returnId,
                    request.orderId,
                    resolvedCustomerId, // from Order Fulfillment validation
                    request.productId,
                    notes + " | Refund: ₹" + String.format("%.2f", refundAmount)
                            + " | Disposition: " + disposition,
                    request.customerComment,
                    transportDetails,
                    warrantyValidUntil,
                    "APPROVED",
                    LocalDateTime.now());

            returnsAdapter.createProductReturn(returnRecord);

            // Step 11+: Trigger statistics observer for analytics tracking (Phase 7)
            if (this.returnStatsObserver != null) {
                this.returnStatsObserver.onReturnApproved(returnRecord);
            }

            return refundAmount;

        } catch (Exception e) {
            System.err.println("[RMA] UNREGISTERED_EXCEPTION: Error processing return: "
                    + e.getMessage());
            return 0.0;
        }
    }

    /** Formats the transport_details field stored on the return record. */
    private String buildTransportDetails(String originalTrackingUrl,
            String reverseTrackingUrl,
            String disposition) {
        StringBuilder sb = new StringBuilder();
        if (originalTrackingUrl != null) {
            sb.append("Original delivery: ").append(originalTrackingUrl).append(" | ");
        }
        if (reverseTrackingUrl != null) {
            sb.append("Return pickup tracking: ").append(reverseTrackingUrl).append(" | ");
        }
        sb.append("Routed to: ").append(disposition);
        return sb.toString();
    }

    /**
     * Returns the warranty period in days for a given product category.
     *
     * SOURCE: This should eventually be replaced with a read from the Inventory
     * subsystem's products.shelf_life_days column (or a dedicated warranty config
     * table) once that interface is integrated.
     *
     * Current values follow standard consumer-electronics and retail norms:
     * Electronics / Appliances → 365 days (1 year)
     * Clothing / Footwear → 90 days (3 months)
     * Furniture → 365 days (1 year)
     * Perishables / Food → 7 days
     * Default → 180 days (6 months)
     */
    private int warrantyPeriodDays(String category) {
        if (category == null)
            return 180;
        switch (category.trim().toLowerCase()) {
            case "electronics":
            case "appliances":
            case "furniture":
                return 365;
            case "clothing":
            case "footwear":
            case "accessories":
                return 90;
            case "food":
            case "perishables":
            case "grocery":
                return 7;
            default:
                return 180;
        }
    }
}
