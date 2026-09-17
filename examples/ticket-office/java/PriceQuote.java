package tickets;

public class PriceQuote {
    public int totalCents(int unitPriceCents, int quantity) {
        if (unitPriceCents < 0 || quantity <= 0) throw new IllegalArgumentException("Invalid quote inputs");
        return Math.multiplyExact(unitPriceCents, quantity);
    }
}
