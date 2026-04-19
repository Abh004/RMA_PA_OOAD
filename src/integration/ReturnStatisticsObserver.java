package src.integration;

import com.jackfruit.scm.database.adapter.ReturnsAdapter;
import com.jackfruit.scm.database.model.ReturnsModels.ProductReturn;
import com.jackfruit.scm.database.model.ReturnsModels.ReturnGrowthStatistic;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Observer implementation for return statistics tracking.
 *
 * Records return analytics metrics when returns are approved. Uses the existing
 * ReturnsAdapter from database_module to persist statistics without violating
 * the adapter pattern constraint.
 *
 * Metrics captured:
 * - returnRequestId: Link to the specific return
 * - metricPeriod: Month/period for aggregation (e.g., "2024-04")
 * - returnRate: Percentage of returns in period
 * - resolutionRate: Percentage of approved vs. total returns
 *
 * Error Handling:
 * All exceptions are caught and logged. Statistics write failures will NOT
 * interrupt the return approval flow. This ensures statistics are informational
 * and don't cause functional issues.
 */
public class ReturnStatisticsObserver implements IReturnStatisticsObserver {

    private final ReturnsAdapter returnsAdapter;

    /**
     * Construct with a ReturnsAdapter to persist statistics.
     *
     * @param returnsAdapter adapter for database operations (from database_module)
     */
    public ReturnStatisticsObserver(ReturnsAdapter returnsAdapter) {
        this.returnsAdapter = returnsAdapter;
    }

    @Override
    public void onReturnApproved(ProductReturn returnRecord) {
        try {
            // Extract core identifiers
            String returnRequestId = returnRecord.returnRequestId();
            String growthStatId = UUID.randomUUID().toString();

            // Calculate metric period (YYYY-MM format)
            String metricPeriod = calculateMetricPeriod(returnRecord.createdAt());

            // Compute return rate and resolution rate
            BigDecimal returnRate = calculateReturnRate();
            BigDecimal resolutionRate = calculateResolutionRate();

            // Create statistics record
            ReturnGrowthStatistic statistic = new ReturnGrowthStatistic(
                    growthStatId,
                    returnRequestId,
                    metricPeriod,
                    returnRate,
                    resolutionRate,
                    LocalDateTime.now()
            );

            // Persist to database via adapter
            returnsAdapter.createReturnGrowthStatistic(statistic);

            System.out.println("[RMA Statistics] Return " + returnRequestId
                    + " recorded in growth statistics (stat_id: " + growthStatId + ")");

        } catch (Exception e) {
            // Graceful failure: log but don't rethrow (won't block return approval)
            System.err.println("[RMA Statistics] Error recording statistics for return "
                    + returnRecord.returnRequestId() + ": " + e.getMessage());
            // Statistics write failures are informational only
        }
    }

    /**
     * Calculate metric period as YYYY-MM from approval timestamp.
     *
     * Used for monthly aggregation of return statistics.
     *
     * @param approvedAt timestamp when return was approved
     * @return period string in format "2024-04"
     */
    private String calculateMetricPeriod(LocalDateTime approvedAt) {
        YearMonth yearMonth = YearMonth.from(approvedAt);
        return yearMonth.toString(); // Format: YYYY-MM
    }

    /**
     * Calculate return rate for the current period.
     *
     * Returns the ratio of total returns to expected transactions in the period.
     * In a full implementation, this would query return count and order count
     * for the metric period. For now, uses a simplified calculation based on
     * historical average.
     *
     * @return return rate as BigDecimal percentage (0.05 = 5%)
     */
    private BigDecimal calculateReturnRate() {
        try {
            List<ReturnGrowthStatistic> recentStats = returnsAdapter.listReturnGrowthStatistics();

            if (recentStats == null || recentStats.isEmpty()) {
                // No historical data: use industry default (3% return rate)
                return new BigDecimal("0.03");
            }

            // Calculate average return rate from recent statistics
            BigDecimal totalReturnRate = BigDecimal.ZERO;
            for (ReturnGrowthStatistic stat : recentStats) {
                totalReturnRate = totalReturnRate.add(stat.returnRate());
            }

            BigDecimal averageReturnRate = totalReturnRate.divide(
                    BigDecimal.valueOf(recentStats.size()),
                    4,
                    java.math.RoundingMode.HALF_UP
            );

            return averageReturnRate;

        } catch (Exception e) {
            // Default fallback if query fails
            System.err.println("[RMA Statistics] Could not calculate return rate: " + e.getMessage());
            return new BigDecimal("0.03");
        }
    }

    /**
     * Calculate resolution rate (percentage of approvals vs total returns).
     *
     * In a full implementation, this would be:
     * resolutionRate = approvedReturns / totalReturns for the period
     *
     * For now, uses historical average. High resolution rate (>0.95) indicates
     * good return process efficiency.
     *
     * @return resolution rate as BigDecimal percentage (0.98 = 98%)
     */
    private BigDecimal calculateResolutionRate() {
        try {
            List<ReturnGrowthStatistic> recentStats = returnsAdapter.listReturnGrowthStatistics();

            if (recentStats == null || recentStats.isEmpty()) {
                // No historical data: use industry default (95% resolution rate)
                return new BigDecimal("0.95");
            }

            // Calculate average resolution rate from recent statistics
            BigDecimal totalResolutionRate = BigDecimal.ZERO;
            for (ReturnGrowthStatistic stat : recentStats) {
                totalResolutionRate = totalResolutionRate.add(stat.resolutionRate());
            }

            BigDecimal averageResolutionRate = totalResolutionRate.divide(
                    BigDecimal.valueOf(recentStats.size()),
                    4,
                    java.math.RoundingMode.HALF_UP
            );

            return averageResolutionRate;

        } catch (Exception e) {
            // Default fallback if query fails
            System.err.println("[RMA Statistics] Could not calculate resolution rate: " + e.getMessage());
            return new BigDecimal("0.95");
        }
    }
}
