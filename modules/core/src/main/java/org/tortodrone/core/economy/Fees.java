package org.tortodrone.core.economy;

/** Fixed stock fees read by both the wire codec and the transaction services. */
public final class Fees {
    public static final int SPARK_COST = 50;
    public static final int MAIL_FEE = 25, MAIL_ATTACH_FEE = 75;

    private Fees() {}
}
