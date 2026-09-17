package tickets;

public class TicketInventory {
    private int remaining;

    public TicketInventory(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Capacity must be nonnegative");
        remaining = capacity;
    }

    public synchronized boolean reserve(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (quantity > remaining) return false;
        remaining -= quantity;
        return true;
    }

    public synchronized int remaining() {
        return remaining;
    }
}
