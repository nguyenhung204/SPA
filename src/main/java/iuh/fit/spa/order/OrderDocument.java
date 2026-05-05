package iuh.fit.spa.order;

import iuh.fit.spa.cart.CartItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "orders")
public class OrderDocument {

    @Id
    private String orderId;
    private String userId;
    private List<CartItem> items = new ArrayList<>();
    private Instant createdAt;

    public OrderDocument() {
    }

    public OrderDocument(String orderId, String userId, List<CartItem> items) {
        this.orderId = orderId;
        this.userId = userId;
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
        this.createdAt = Instant.now();
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
