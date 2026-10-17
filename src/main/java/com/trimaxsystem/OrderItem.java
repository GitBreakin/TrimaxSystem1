package com.trimaxsystem;

class OrderItem {
    int productId;
    String productName;
    double price;
    int amount;
    double subtotal;

    public OrderItem(int productId, String productName, double price, int amount, double subtotal) {
        this.productId = productId;
        this.productName = productName;
        this.price = price;
        this.amount = amount;
        this.subtotal = subtotal;
    }


}