package com.rummy.gameservice.wallet;

public class InsufficientBalanceException extends IllegalStateException {

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
