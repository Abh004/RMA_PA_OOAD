package src.integration;

/**
 * Client interface for querying authoritative base prices.
 *
 * Prevents refund fraud by ensuring all refund calculations use market prices
 * from the pricing subsystem rather than customer-supplied claims.
 *
 * Example usage:
 *   double marketPrice = pricingClient.getActivePrice("SKU-123", "GLOBAL", "ONLINE");
 *   // Use marketPrice for refund calculation (not customer-supplied price)
 *
 * Implementation Note:
 *   - Delegates to PriceListManager from multilevel_pricing_discount subsystem
 *   - Can be mocked for testing (no external dependencies required)
 *
 * Error Handling:
 * - If price not found for SKU/region/channel, throws NoSuchElementException
 * - Caller should handle gracefully (log warning, use fallback, don't block return)
 */
public interface IMultiLevelPricingClient {

    /**
     * Get the active (current market) base price for a product SKU.
     *
     * Queries the pricing subsystem to retrieve the authoritative market price.
     * This prevents refund fraud by ensuring refunds are calculated on real costs,
     * not customer-supplied claims.
     *
     * @param skuId     product SKU identifier (required, non-null)
     * @param region    geographic region code (e.g., "GLOBAL", "NA", "EU")
     * @param channel   sales channel (e.g., "ONLINE", "RETAIL")
     *
     * @return authoritative base price as double
     *
     * @throws Exception if no active price is found for the given
     *         SKU/region/channel combination at the current time.
     *         Caller should handle by logging warning and using fallback.
     *
     * @throws IllegalArgumentException if skuId is null/blank, region is
     *         null/blank, or channel is null/blank.
     */
    double getActivePrice(String skuId, String region, String channel)
            throws Exception, IllegalArgumentException;
}
