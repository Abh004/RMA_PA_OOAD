package inventory_subsystem;

import java.time.LocalDateTime;
import java.util.*;

public class InventoryBatch {

    private String batchId;
    private int quantity;
    private LocalDateTime arrivalTime;
    private LocalDateTime expiryTime;

    private List<String> serialNumbers;

    private double unitCost = 0.0;

    public InventoryBatch(String batchId,
                          int quantity,
                          LocalDateTime arrivalTime,
                          LocalDateTime expiryTime,
                          List<String> serialNumbers,
                          double unitCost) {

        this.batchId = batchId;
        this.quantity = quantity;
        this.arrivalTime = arrivalTime;
        this.expiryTime = expiryTime;
        this.serialNumbers = (serialNumbers != null)
                ? serialNumbers
                : new ArrayList<>();
        this.unitCost = unitCost;
    }

    public String getBatchId() { return batchId; }
    public int getQuantity() { return quantity; }
    public LocalDateTime getArrivalTime() { return arrivalTime; }
    public LocalDateTime getExpiryTime() { return expiryTime; }
    public List<String> getSerialNumbers() { return serialNumbers; }
    public double getUnitCost() {
        return unitCost;
    }

    public void setQuantity(int quantity) { this.quantity = quantity; }

    public void addSerialNumber(String serial) {
        this.serialNumbers.add(serial);
    }
}
