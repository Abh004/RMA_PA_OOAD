package src.core;

/** Stores the initial customer return data */
public class ReturnRequest {

    public final String orderId, productId, category, customerComment;
    public final int daysSincePurchase;
    public final double basePrice;

    public ReturnRequest(
        String orderId,
        String productId,
        String category,
        int days,
        String comment,
        double price
    ) {
        this.orderId = orderId;
        this.productId = productId;
        this.category = category;
        this.daysSincePurchase = days;
        this.customerComment = comment;
        this.basePrice = price;
    }
}
