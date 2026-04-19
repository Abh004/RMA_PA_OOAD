package src.integration;

import com.jackfruit.scm.database.adapter.ReturnsAdapter;
import com.jackfruit.scm.database.model.ReturnsModels.ProductReturn;
import com.jackfruit.scm.database.model.ReturnsModels.ReturnGrowthStatistic;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReturnStatisticsObserver.
 *
 * Tests verify:
 * 1. Observer correctly extracts return ID and creates statistics
 * 2. Metric period calculation (YYYY-MM format)
 * 3. Return rate and resolution rate computation
 * 4. Database adapter is called correctly
 * 5. Error handling doesn't throw exceptions
 * 6. Graceful degradation when adapter fails
 *
 * All tests use Mockito to mock ReturnsAdapter (no MySQL required).
 */
public class ReturnStatisticsObserverTest {

    private ReturnsAdapter mockAdapter;
    private ReturnStatisticsObserver observer;

    @BeforeEach
    public void setUp() {
        mockAdapter = mock(ReturnsAdapter.class);
        observer = new ReturnStatisticsObserver(mockAdapter);
    }

    /**
     * Test 1: Observer successfully calls adapter to create statistic.
     */
    @Test
    public void testOnReturnApproved_CallsAdapterCreateStatistic() {
        // Arrange
        ProductReturn returnRecord = createMockProductReturn(
                "RET-001",
                "ORD-123",
                "APPROVED"
        );

        when(mockAdapter.listReturnGrowthStatistics()).thenReturn(new ArrayList<>());

        // Act
        observer.onReturnApproved(returnRecord);

        // Assert
        verify(mockAdapter, times(1)).createReturnGrowthStatistic(any(ReturnGrowthStatistic.class));
    }

    /**
     * Test 2: Verify correct return request ID extracted.
     */
    @Test
    public void testOnReturnApproved_ExtractsCorrectReturnRequestId() {
        // Arrange
        String expectedReturnId = "RET-42-2024";
        ProductReturn returnRecord = createMockProductReturn(
                expectedReturnId,
                "ORD-456",
                "APPROVED"
        );

        when(mockAdapter.listReturnGrowthStatistics()).thenReturn(new ArrayList<>());

        // Act
        observer.onReturnApproved(returnRecord);

        // Assert - capture the argument passed to adapter
        var captor = mock(ReturnGrowthStatistic.class);
        verify(mockAdapter).createReturnGrowthStatistic(argThat(stat ->
                stat.returnRequestId().equals(expectedReturnId)
        ));
    }

    /**
     * Test 3: Metric period calculated correctly (YYYY-MM format).
     */
    @Test
    public void testOnReturnApproved_CalculatesMetricPeriodCorrectly() {
        // Arrange
        LocalDateTime approvalDate = LocalDateTime.of(2024, 4, 15, 10, 30, 0);
        ProductReturn returnRecord = new ProductReturn(
                "RET-003",
                "ORD-789",
                "CUST-101",
                "Product: ABC-123",
                "Condition: Damaged",
                "Customer comment",
                "Shipping info",
                LocalDateTime.of(2024, 5, 15, 0, 0, 0),
                "APPROVED",
                approvalDate
        );

        when(mockAdapter.listReturnGrowthStatistics()).thenReturn(new ArrayList<>());

        // Act
        observer.onReturnApproved(returnRecord);

        // Assert - period should be "2024-04"
        verify(mockAdapter).createReturnGrowthStatistic(argThat(stat ->
                stat.metricPeriod().equals("2024-04")
        ));
    }

    /**
     * Test 4: Growth stat ID is valid UUID (non-null, not empty).
     */
    @Test
    public void testOnReturnApproved_GeneratesValidGrowthStatId() {
        // Arrange
        ProductReturn returnRecord = createMockProductReturn(
                "RET-004",
                "ORD-999",
                "APPROVED"
        );

        when(mockAdapter.listReturnGrowthStatistics()).thenReturn(new ArrayList<>());

        // Act
        observer.onReturnApproved(returnRecord);

        // Assert
        verify(mockAdapter).createReturnGrowthStatistic(argThat(stat ->
                stat.growthStatId() != null && !stat.growthStatId().isEmpty()
        ));
    }

    /**
     * Test 5: Return rate calculated (uses default when no history).
     */
    @Test
    public void testOnReturnApproved_CalculatesReturnRate() {
        // Arrange
        ProductReturn returnRecord = createMockProductReturn(
                "RET-005",
                "ORD-111",
                "APPROVED"
        );

        when(mockAdapter.listReturnGrowthStatistics()).thenReturn(new ArrayList<>());

        // Act
        observer.onReturnApproved(returnRecord);

        // Assert - should have a return rate (default 0.03 when no history)
        verify(mockAdapter).createReturnGrowthStatistic(argThat(stat ->
                stat.returnRate() != null && stat.returnRate().compareTo(BigDecimal.ZERO) >= 0
        ));
    }

    /**
     * Test 6: Resolution rate calculated (uses default when no history).
     */
    @Test
    public void testOnReturnApproved_CalculatesResolutionRate() {
        // Arrange
        ProductReturn returnRecord = createMockProductReturn(
                "RET-006",
                "ORD-222",
                "APPROVED"
        );

        when(mockAdapter.listReturnGrowthStatistics()).thenReturn(new ArrayList<>());

        // Act
        observer.onReturnApproved(returnRecord);

        // Assert - should have resolution rate (default 0.95 when no history)
        verify(mockAdapter).createReturnGrowthStatistic(argThat(stat ->
                stat.resolutionRate() != null && stat.resolutionRate().compareTo(BigDecimal.ZERO) >= 0
        ));
    }

    /**
     * Test 7: Observer handles adapter exceptions gracefully (doesn't throw).
     */
    @Test
    public void testOnReturnApproved_HandlesAdapterExceptionGracefully() {
        // Arrange
        ProductReturn returnRecord = createMockProductReturn(
                "RET-007",
                "ORD-333",
                "APPROVED"
        );

        when(mockAdapter.listReturnGrowthStatistics())
                .thenThrow(new RuntimeException("Database connection failed"));
        when(mockAdapter.createReturnGrowthStatistic(any()))
                .thenThrow(new RuntimeException("Insert failed"));

        // Act & Assert - should not throw
        assertDoesNotThrow(() -> observer.onReturnApproved(returnRecord));
    }

    /**
     * Test 8: Observer works with historical statistics (calculates averages).
     */
    @Test
    public void testOnReturnApproved_UsesHistoricalDataForRateCalculation() {
        // Arrange
        ProductReturn returnRecord = createMockProductReturn(
                "RET-008",
                "ORD-444",
                "APPROVED"
        );

        // Create historical statistics
        List<ReturnGrowthStatistic> historicalStats = new ArrayList<>();
        historicalStats.add(new ReturnGrowthStatistic(
                "STAT-001",
                "RET-OLD-1",
                "2024-03",
                new BigDecimal("0.05"),
                new BigDecimal("0.96"),
                LocalDateTime.now().minusMonths(1)
        ));
        historicalStats.add(new ReturnGrowthStatistic(
                "STAT-002",
                "RET-OLD-2",
                "2024-02",
                new BigDecimal("0.04"),
                new BigDecimal("0.94"),
                LocalDateTime.now().minusMonths(2)
        ));

        when(mockAdapter.listReturnGrowthStatistics()).thenReturn(historicalStats);

        // Act
        observer.onReturnApproved(returnRecord);

        // Assert - should use averaged values
        verify(mockAdapter).createReturnGrowthStatistic(argThat(stat -> {
            // Average return rate: (0.05 + 0.04) / 2 = 0.045
            // Average resolution rate: (0.96 + 0.94) / 2 = 0.95
            BigDecimal expectedReturnRate = new BigDecimal("0.045");
            BigDecimal expectedResolutionRate = new BigDecimal("0.95");

            return stat.returnRate().compareTo(expectedReturnRate) >= 0 &&
                   stat.resolutionRate().compareTo(expectedResolutionRate) >= 0;
        }));
    }

    /**
     * Test 9: Observer doesn't rethrow exceptions (graceful error handling).
     */
    @Test
    public void testOnReturnApproved_NoExceptionThrown_WhenAdapterFails() {
        // Arrange
        ProductReturn returnRecord = createMockProductReturn(
                "RET-009",
                "ORD-555",
                "APPROVED"
        );

        when(mockAdapter.listReturnGrowthStatistics())
                .thenThrow(new NullPointerException("Adapter not initialized"));

        // Act & Assert
        assertDoesNotThrow(() -> observer.onReturnApproved(returnRecord));
    }

    /**
     * Test 10: Recorded at timestamp is current (not the approval time).
     */
    @Test
    public void testOnReturnApproved_RecordedAtIsNow() {
        // Arrange
        LocalDateTime pastTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
        ProductReturn returnRecord = new ProductReturn(
                "RET-010",
                "ORD-666",
                "CUST-102",
                "Product info",
                "Defect info",
                "Feedback",
                "Transport info",
                LocalDateTime.of(2024, 5, 1, 0, 0, 0),
                "APPROVED",
                pastTime
        );

        when(mockAdapter.listReturnGrowthStatistics()).thenReturn(new ArrayList<>());

        LocalDateTime beforeCall = LocalDateTime.now();

        // Act
        observer.onReturnApproved(returnRecord);

        LocalDateTime afterCall = LocalDateTime.now();

        // Assert - recordedAt should be approximately now
        verify(mockAdapter).createReturnGrowthStatistic(argThat(stat -> {
            LocalDateTime recordedAt = stat.recordedAt();
            return !recordedAt.isBefore(beforeCall) && !recordedAt.isAfter(afterCall.plusSeconds(1));
        }));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Create a mock ProductReturn for testing.
     */
    private ProductReturn createMockProductReturn(String returnId, String orderId, String status) {
        return new ProductReturn(
                returnId,
                orderId,
                "CUST-TEST-" + System.nanoTime(),
                "Test Product",
                "Test Condition",
                "Test Comment",
                "Test Transport",
                LocalDateTime.now().plusDays(30),
                status,
                LocalDateTime.now()
        );
    }
}
