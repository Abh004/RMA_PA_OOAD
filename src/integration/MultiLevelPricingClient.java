package src.integration;

/**
 * Implementation of IMultiLevelPricingClient using PriceListManager.
 *
 * Queries the pricing subsystem to get authoritative market prices for
 * refund calculations. Prevents refund fraud by using real costs instead
 * of customer-supplied price claims.
 *
 * This implementation uses reflection to avoid compile-time dependency
 * on the pricing subsystem JAR (which may not be built yet).
 *
 * Architecture:
 *   • Lazy-loads PriceListManager via reflection
 *   • Queries getActivePrice(skuId, region, channel)
 *   • Handles errors gracefully (returns fallback or rethrows)
 *   • Thread-safe: PriceListManager is thread-safe internally
 *
 * Error Handling:
 *   • Price not found: Logged as warning, rethrown to caller
 *   • Any other exception: Logged as error, rethrown
 *   • Caller handles gracefully (fallback to request.basePrice)
 */
public class MultiLevelPricingClient implements IMultiLevelPricingClient {

    private final Object priceListManager;  // PriceListManager instance (generic Object)
    private final java.lang.reflect.Method getActivePriceMethod;

    /**
     * Construct with a PriceListManager instance (as Object to avoid compile dependency).
     *
     * @param priceListManager manager for accessing active price records
     * @throws IllegalArgumentException if priceListManager is null or doesn't have getActivePrice method
     */
    public MultiLevelPricingClient(Object priceListManager) {
        if (priceListManager == null) {
            throw new IllegalArgumentException("priceListManager cannot be null");
        }
        
        this.priceListManager = priceListManager;
        
        // Reflection: Find getActivePrice(String, String, String) -> double method
        try {
            this.getActivePriceMethod = priceListManager.getClass()
                    .getMethod("getActivePrice", String.class, String.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "priceListManager must have getActivePrice(String, String, String) method",
                    e
            );
        }
    }

    /**
     * Get active price for a SKU in a specific region and channel.
     *
     * Uses reflection to call PriceListManager.getActivePrice(). Validates
     * inputs before querying. Returns the authoritative market price.
     *
     * @param skuId   product SKU (validated: non-null, non-blank)
     * @param region  geographic region (e.g., "GLOBAL", "NA")
     * @param channel sales channel (e.g., "ONLINE", "RETAIL")
     * @return market base price
     * @throws Exception if price not found or query fails
     * @throws IllegalArgumentException if inputs invalid
     */
    @Override
    public double getActivePrice(String skuId, String region, String channel)
            throws Exception {

        // Validate inputs
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId cannot be null or blank");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region cannot be null or blank");
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel cannot be null or blank");
        }

        String normalizedSku = skuId.trim().toUpperCase();
        String normalizedRegion = region.trim().toUpperCase();
        String normalizedChannel = channel.trim().toUpperCase();

        try {
            // Use reflection to call priceListManager.getActivePrice()
            Object result = getActivePriceMethod.invoke(
                    priceListManager,
                    normalizedSku,
                    normalizedRegion,
                    normalizedChannel
            );

            double activePrice = ((Number) result).doubleValue();

            System.out.println("[RMA Pricing] Price lookup successful: SKU=" + normalizedSku
                    + ", region=" + normalizedRegion + ", channel=" + normalizedChannel
                    + ", price=" + activePrice);

            return activePrice;

        } catch (java.lang.reflect.InvocationTargetException e) {
            // Exception from PriceListManager.getActivePrice()
            Throwable cause = e.getCause();
            
            if (cause instanceof java.util.NoSuchElementException) {
                // Price not found — log warning and rethrow
                System.err.println("[RMA Pricing] WARNING: Price not found for SKU=" + normalizedSku
                        + ", region=" + normalizedRegion + ", channel=" + normalizedChannel);
                System.err.println("[RMA Pricing] Caller should handle with fallback to request.basePrice");
                throw (java.util.NoSuchElementException) cause;
            } else {
                // Other exception
                System.err.println("[RMA Pricing] ERROR querying price: " + cause.getMessage());
                throw new Exception("Price lookup failed: " + cause.getMessage(), cause);
            }

        } catch (Exception e) {
            // Reflection error
            System.err.println("[RMA Pricing] ERROR: Reflection call failed: " + e.getMessage());
            e.printStackTrace();
            throw new Exception("Price lookup failed: " + e.getMessage(), e);
        }
    }
}
