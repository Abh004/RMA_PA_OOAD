package src.patterns;

public final class ValidationChainFactory {

    private ValidationChainFactory() {}

    public static ReturnValidationHandler createDefaultChain() {
        ReturnValidationHandler head = new WarrantyWindowHandler();
        // Example for chaining later:
        // head.setNext(new ConditionCheckHandler())
        //     .setNext(new FraudCheckHandler());
        return head;
    }
}
