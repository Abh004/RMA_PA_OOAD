package src.integration;

import com.pricingos.pricing.pricelist.PriceListManager;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MultiLevelPricingClient.
 *
 * Tests verify:
 * 1. Happy path: Price found and returned correctly
 * 2. Error paths: NoSuchElementException, invalid inputs
 * 3. Input validation: Null/blank handling
 * 4. Region/channel variations: Different prices
 * 5. Exception propagation: Caller gets correct exceptions
 *
 * All tests use Mockito to mock PriceListManager (no MySQL required).
 */
public class MultiLevelPricingClientTest {

    private PriceListManager mockManager;
    private MultiLevelPricingClient pricingClient;

    @BeforeEach
    public void setUp() {
        mockManager = mock(PriceListManager.class);
        pricingClient = new MultiLevelPricingClient(mockManager);
    }

    /**
     * Test 1: Price found - return correct value.
     */
    @Test
    public void testGetActivePrice_PriceFound_ReturnCorrectValue() {
        // Arrange
        String skuId = "SKU-123";
        String region = "GLOBAL";
        String channel = "ONLINE";
        double expectedPrice = 99.99;

        when(mockManager.getActivePrice("SKU-123", "GLOBAL", "ONLINE"))
                .thenReturn(expectedPrice);

        // Act
        double actualPrice = pricingClient.getActivePrice(skuId, region, channel);

        // Assert
        assertEquals(expectedPrice, actualPrice, 0.01);
        verify(mockManager, times(1)).getActivePrice("SKU-123", "GLOBAL", "ONLINE");
    }

    /**
     * Test 2: Price not found - NoSuchElementException.
     */
    @Test
    public void testGetActivePrice_PriceNotFound_ThrowsNoSuchElementException() {
        // Arrange
        String skuId = "SKU-UNKNOWN";
        String region = "GLOBAL";
        String channel = "ONLINE";

        when(mockManager.getActivePrice("SKU-UNKNOWN", "GLOBAL", "ONLINE"))
                .thenThrow(new NoSuchElementException("Price not found"));

        // Act & Assert
        assertThrows(NoSuchElementException.class, () ->
                pricingClient.getActivePrice(skuId, region, channel)
        );
    }

    /**
     * Test 3: Null SKU - IllegalArgumentException.
     */
    @Test
    public void testGetActivePrice_NullSku_ThrowsIllegalArgumentException() {
        // Arrange
        String region = "GLOBAL";
        String channel = "ONLINE";

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                pricingClient.getActivePrice(null, region, channel)
        );
    }

    /**
     * Test 4: Blank SKU - IllegalArgumentException.
     */
    @Test
    public void testGetActivePrice_BlankSku_ThrowsIllegalArgumentException() {
        // Arrange
        String region = "GLOBAL";
        String channel = "ONLINE";

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                pricingClient.getActivePrice("   ", region, channel)
        );
    }

    /**
     * Test 5: Null region - IllegalArgumentException.
     */
    @Test
    public void testGetActivePrice_NullRegion_ThrowsIllegalArgumentException() {
        // Arrange
        String skuId = "SKU-123";
        String channel = "ONLINE";

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                pricingClient.getActivePrice(skuId, null, channel)
        );
    }

    /**
     * Test 6: Null channel - IllegalArgumentException.
     */
    @Test
    public void testGetActivePrice_NullChannel_ThrowsIllegalArgumentException() {
        // Arrange
        String skuId = "SKU-123";
        String region = "GLOBAL";

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                pricingClient.getActivePrice(skuId, region, null)
        );
    }

    /**
     * Test 7: Region variation - Different prices for different regions.
     */
    @Test
    public void testGetActivePrice_RegionVariation_DifferentPrices() {
        // Arrange
        String skuId = "SKU-456";
        String channel = "ONLINE";

        // Global price
        when(mockManager.getActivePrice("SKU-456", "GLOBAL", "ONLINE"))
                .thenReturn(100.0);

        // NA (North America) price
        when(mockManager.getActivePrice("SKU-456", "NA", "ONLINE"))
                .thenReturn(110.0);

        // EU price
        when(mockManager.getActivePrice("SKU-456", "EU", "ONLINE"))
                .thenReturn(95.0);

        // Act
        double globalPrice = pricingClient.getActivePrice(skuId, "GLOBAL", channel);
        double naPrice = pricingClient.getActivePrice(skuId, "NA", channel);
        double euPrice = pricingClient.getActivePrice(skuId, "EU", channel);

        // Assert
        assertEquals(100.0, globalPrice, 0.01);
        assertEquals(110.0, naPrice, 0.01);
        assertEquals(95.0, euPrice, 0.01);
    }

    /**
     * Test 8: Channel variation - Different prices for ONLINE vs RETAIL.
     */
    @Test
    public void testGetActivePrice_ChannelVariation_DifferentPrices() {
        // Arrange
        String skuId = "SKU-789";
        String region = "GLOBAL";

        // Online channel price
        when(mockManager.getActivePrice("SKU-789", "GLOBAL", "ONLINE"))
                .thenReturn(75.0);

        // Retail channel price
        when(mockManager.getActivePrice("SKU-789", "GLOBAL", "RETAIL"))
                .thenReturn(85.0);

        // Act
        double onlinePrice = pricingClient.getActivePrice(skuId, region, "ONLINE");
        double retailPrice = pricingClient.getActivePrice(skuId, region, "RETAIL");

        // Assert
        assertEquals(75.0, onlinePrice, 0.01);
        assertEquals(85.0, retailPrice, 0.01);
    }

    /**
     * Test 9: Case insensitivity - Inputs normalized to uppercase.
     */
    @Test
    public void testGetActivePrice_CaseInsensitive_SuccessfulQuery() {
        // Arrange
        String skuId = "sku-999";  // lowercase
        String region = "global";   // lowercase
        String channel = "online";  // lowercase
        double expectedPrice = 50.0;

        // Manager is called with uppercase
        when(mockManager.getActivePrice("SKU-999", "GLOBAL", "ONLINE"))
                .thenReturn(expectedPrice);

        // Act
        double actualPrice = pricingClient.getActivePrice(skuId, region, channel);

        // Assert
        assertEquals(expectedPrice, actualPrice, 0.01);
        // Verify uppercase call was made
        verify(mockManager, times(1)).getActivePrice("SKU-999", "GLOBAL", "ONLINE");
    }

    /**
     * Test 10: Manager exception - Other exceptions propagated.
     */
    @Test
    public void testGetActivePrice_ManagerException_PropagatesAsIllegalStateException() {
        // Arrange
        String skuId = "SKU-ERROR";
        String region = "GLOBAL";
        String channel = "ONLINE";

        when(mockManager.getActivePrice("SKU-ERROR", "GLOBAL", "ONLINE"))
                .thenThrow(new RuntimeException("Database connection failed"));

        // Act & Assert
        assertThrows(IllegalStateException.class, () ->
                pricingClient.getActivePrice(skuId, region, channel)
        );
    }

    /**
     * Test 11: Null constructor argument - IllegalArgumentException.
     */
    @Test
    public void testConstructor_NullManager_ThrowsIllegalArgumentException() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                new MultiLevelPricingClient(null)
        );
    }

    /**
     * Test 12: Whitespace-only region - Treated as blank.
     */
    @Test
    public void testGetActivePrice_WhitespaceOnlyRegion_ThrowsIllegalArgumentException() {
        // Arrange
        String skuId = "SKU-123";
        String channel = "ONLINE";

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                pricingClient.getActivePrice(skuId, "   ", channel)
        );
    }

    /**
     * Test 13: Whitespace-only channel - Treated as blank.
     */
    @Test
    public void testGetActivePrice_WhitespaceOnlyChannel_ThrowsIllegalArgumentException() {
        // Arrange
        String skuId = "SKU-123";
        String region = "GLOBAL";

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                pricingClient.getActivePrice(skuId, region, "\t\n")
        );
    }

    /**
     * Test 14: Multiple sequential queries - Manager called correctly each time.
     */
    @Test
    public void testGetActivePrice_MultipleQueries_CallsManagerEachTime() {
        // Arrange
        when(mockManager.getActivePrice("SKU-A", "GLOBAL", "ONLINE")).thenReturn(100.0);
        when(mockManager.getActivePrice("SKU-B", "GLOBAL", "ONLINE")).thenReturn(200.0);

        // Act
        double priceA = pricingClient.getActivePrice("SKU-A", "GLOBAL", "ONLINE");
        double priceB = pricingClient.getActivePrice("SKU-B", "GLOBAL", "ONLINE");

        // Assert
        assertEquals(100.0, priceA, 0.01);
        assertEquals(200.0, priceB, 0.01);
        verify(mockManager, times(2)).getActivePrice(any(), any(), any());
    }

    /**
     * Test 15: Zero price - Valid return (edge case).
     */
    @Test
    public void testGetActivePrice_ZeroPrice_ReturnedSuccessfully() {
        // Arrange
        when(mockManager.getActivePrice("FREE-ITEM", "GLOBAL", "ONLINE"))
                .thenReturn(0.0);

        // Act
        double price = pricingClient.getActivePrice("FREE-ITEM", "GLOBAL", "ONLINE");

        // Assert
        assertEquals(0.0, price, 0.01);
    }

    /**
     * Test 16: Large price - High-value items.
     */
    @Test
    public void testGetActivePrice_LargePrice_ReturnedSuccessfully() {
        // Arrange
        when(mockManager.getActivePrice("LUXURY-ITEM", "GLOBAL", "ONLINE"))
                .thenReturn(99999.99);

        // Act
        double price = pricingClient.getActivePrice("LUXURY-ITEM", "GLOBAL", "ONLINE");

        // Assert
        assertEquals(99999.99, price, 0.01);
    }
}
