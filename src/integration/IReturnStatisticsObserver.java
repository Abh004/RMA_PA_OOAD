package src.integration;

import com.jackfruit.scm.database.model.ReturnsModels.ProductReturn;

/**
 * Observer interface for return statistics tracking.
 *
 * Defines the contract for observers that want to be notified when a return
 * is approved. Implementations record metrics and analytics for business intelligence.
 *
 * Example use:
 *   observer.onReturnApproved(returnRecord);
 *
 * Error Handling:
 *   - Implementation must handle exceptions gracefully
 *   - Statistics write failures must not block return approval flow
 */
public interface IReturnStatisticsObserver {

    /**
     * Called after a return request has been approved and persisted.
     *
     * Implementation should:
     * 1. Extract returnRequestId from the ProductReturn record
     * 2. Generate unique growthStatId (UUID)
     * 3. Calculate metricPeriod (e.g., "2024-04" or "DAILY")
     * 4. Compute return_rate and resolution_rate metrics
     * 5. Create ReturnGrowthStatistic record
     * 6. Persist via ReturnsAdapter.createReturnGrowthStatistic()
     * 7. Log or handle any database errors without blocking the return flow
     *
     * @param returnRecord the approved ProductReturn record containing:
     *                     - returnRequestId (unique return identifier)
     *                     - orderId (source order)
     *                     - productDetails (product information)
     *                     - defectDetails (damage/condition assessment)
     *                     - warrantyValidUntil (warranty deadline)
     *                     - returnStatus (should be "APPROVED")
     *                     - createdAt (return approval timestamp)
     */
    void onReturnApproved(ProductReturn returnRecord);
}
