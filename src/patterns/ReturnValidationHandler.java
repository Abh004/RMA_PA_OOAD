package src.patterns;

import src.core.*;

public abstract class ReturnValidationHandler {

    protected ReturnValidationHandler next;

    public void setNext(ReturnValidationHandler next) {
        this.next = next;
    }

    public abstract ValidationResult handle(InspectedItem item);

    protected ValidationResult passToNext(InspectedItem item) {
        if (next != null) return next.handle(item);
        return ValidationResult.approved();
    }
}

// Concrete Handler: Warranty Window
class WarrantyWindowHandler extends ReturnValidationHandler {

    @Override
    public ValidationResult handle(InspectedItem item) {
        if (
            item.request.daysSincePurchase > 30
        ) return ValidationResult.rejected("Outside 30-day warranty.");
        return passToNext(item);
    }
}
