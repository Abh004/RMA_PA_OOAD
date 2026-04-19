package inventory_subsystem;

import java.util.*;
import java.time.LocalDateTime;

public class InventoryItem {

    private String sku;
    private String locationId;
    private String supplierId;
    private String status;

    private String abcCategory = "C"; // A / B / C (default lowest priority)

    private int reorderThreshold = 0;

    private int safetyStockLevel = 0;

    private int version;

    private List<InventoryBatch> batches = new ArrayList<>();

    public InventoryItem(String sku, String locationId,
                         String supplierId, int quantity) {

        this.sku = sku;
        this.locationId = locationId;
        this.supplierId = supplierId;
        this.status = "AVAILABLE";
        this.version = 0;

        batches.add(new InventoryBatch(
                UUID.randomUUID().toString(),
                quantity,
                LocalDateTime.now(),
                null,
            new ArrayList<>(),
            0.0
        ));
    }

    public String getSku() { return sku; }
    public String getLocationId() { return locationId; }
    public String getSupplierId() { return supplierId; }
    public String getStatus() { return status; }
    public String getAbcCategory() {
        return abcCategory;
    }

    public void setAbcCategory(String abcCategory) {
        this.abcCategory = abcCategory;
    }

    public int getSafetyStockLevel() {
        return safetyStockLevel;
    }

    public void setSafetyStockLevel(int safetyStockLevel) {
        this.safetyStockLevel = safetyStockLevel;
    }

    public int getReorderThreshold() {
        return reorderThreshold;
    }

    public int getVersion() { return version; }
    public List<InventoryBatch> getBatches() { return batches; }

    public void setStatus(String status) { this.status = status; }
    public void setReorderThreshold(int reorderThreshold) {
        this.reorderThreshold = reorderThreshold;
    }
    public void setVersion(int version) { this.version = version; }

    public void addBatch(InventoryBatch batch) { batches.add(batch); }

    public int getTotalQuantity() {
        return batches.stream().mapToInt(InventoryBatch::getQuantity).sum();
    }
}
