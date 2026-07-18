package com.qhx.back.context;

public class AddressContext {
    private static final ThreadLocal<String> addressHolder = new ThreadLocal<>();

    public static void setAddress(String address) {
        addressHolder.set(address);
    }

    public static String getAddress() {
        return addressHolder.get();
    }

    public static void clear() {
        addressHolder.remove();
    }
}
