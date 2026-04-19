package inventory_subsystem;

import java.time.LocalDateTime;

public class StockTransaction {

    private String sku;
    private String locationId;
    private String supplierId;

    private int quantityChange; // +ve for add, -ve for remove

    private String type; // ADD / REMOVE / TRANSFER_IN / TRANSFER_OUT

    private LocalDateTime timestamp;

    public StockTransaction(String sku, String locationId, String supplierId,
                            int quantityChange, String type) {

        this.sku = sku;
        this.locationId = locationId;
        this.supplierId = supplierId;
        this.quantityChange = quantityChange;
        this.type = type;
        this.timestamp = LocalDateTime.now();
    }

    public String getSku() { return sku; }
    public String getLocationId() { return locationId; }
    public String getSupplierId() { return supplierId; }
    public int getQuantityChange() { return quantityChange; }
    public String getType() { return type; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
