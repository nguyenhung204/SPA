package iuh.fit.spa.order;

import iuh.fit.spa.cart.CartItem;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class OrderEvent implements Serializable {

    private String orderId;
    private String userId;
    private List<CartItem> items = new ArrayList<>();

    public OrderEvent() {
    }

    public OrderEvent(String orderId, String userId, List<CartItem> items) {
        this.orderId = orderId;
        this.userId = userId;
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public void setItems(List<CartItem> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }
}
