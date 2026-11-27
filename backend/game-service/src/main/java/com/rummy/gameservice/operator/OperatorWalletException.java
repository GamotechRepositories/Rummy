package com.rummy.gameservice.operator;

/** An operator / operations wallet call failed. */
public class OperatorWalletException extends IllegalStateException {

    private final boolean uncertain;
    private final boolean sessionInvalid;
    private final String errorCode;

    /**
     * @param uncertain true when the operator may still have applied the call (timeout, server error),
     *                  so a debit must be handled carefully and a credit retried with the same transaction id
     */
    public OperatorWalletException(String message, boolean uncertain) {
        this(message, uncertain, false, null);
    }

    public OperatorWalletException(String message, boolean uncertain, boolean sessionInvalid, String errorCode) {
        super(message);
        this.uncertain = uncertain;
        this.sessionInvalid = sessionInvalid;
        this.errorCode = errorCode;
    }

    public boolean isUncertain() {
        return uncertain;
    }

    public boolean isSessionInvalid() {
        return sessionInvalid;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
