package com.rummy.gameservice.operator;

/** An operator wallet call failed. */
public class OperatorWalletException extends IllegalStateException {

    private final boolean uncertain;

    /**
     * @param uncertain true when the operator may still have applied the call (timeout, server error),
     *                  so a debit must be rolled back and a credit retried with the same transaction id
     */
    public OperatorWalletException(String message, boolean uncertain) {
        super(message);
        this.uncertain = uncertain;
    }

    public boolean isUncertain() {
        return uncertain;
    }
}
