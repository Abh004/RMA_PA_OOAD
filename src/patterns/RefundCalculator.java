package src.patterns;

/**
 * SOLID: Open/Closed Principle.
 * New refund rules can be added as decorators without altering existing code.
 */
public interface RefundCalculator {
    double calculateRefund();
}

/** Base implementation providing the original purchase price. */
class BaseRefund implements RefundCalculator {
    private final double originalPrice;
    public BaseRefund(double originalPrice) { this.originalPrice = originalPrice; }
    @Override public double calculateRefund() { return originalPrice; }
}

/** Abstract Decorator for refund modifications. */
abstract class RefundDecorator implements RefundCalculator {
    protected final RefundCalculator wrapped;
    public RefundDecorator(RefundCalculator wrapped) { this.wrapped = wrapped; }
}

/** Deducts 10% for opened items. */
class RestockingFeeDecorator extends RefundDecorator {
    public RestockingFeeDecorator(RefundCalculator wrapped) { super(wrapped); }
    @Override public double calculateRefund() { return wrapped.calculateRefund() * 0.90; }
}

/** Deducts 50% for damaged goods. */
class ConditionDeductionDecorator extends RefundDecorator {
    public ConditionDeductionDecorator(RefundCalculator wrapped) { super(wrapped); }
    @Override public double calculateRefund() { return wrapped.calculateRefund() * 0.50; }
}