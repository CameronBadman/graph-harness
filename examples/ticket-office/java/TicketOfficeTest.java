package tickets;

public class TicketOfficeTest {
    public static void main(String[] args) {
        TicketInventory inventory = new TicketInventory(2);
        if (!inventory.reserve(1)) throw new AssertionError("First reservation failed");
        if (inventory.reserve(2)) throw new AssertionError("Oversold capacity");
        if (inventory.remaining() != 1) throw new AssertionError("Rejected reservation changed capacity");
        if (new PriceQuote().totalCents(1250, 2) != 2500) throw new AssertionError("Incorrect quote");
        System.out.println("Ticket office checks passed");
    }
}
