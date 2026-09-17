export interface TicketQuote {
  unitPriceCents: number;
  quantity: number;
}

export function formatQuote(quote: TicketQuote): string {
  if (!Number.isInteger(quote.quantity) || quote.quantity <= 0) {
    throw new Error("Quantity must be a positive integer");
  }
  return `$${((quote.unitPriceCents * quote.quantity) / 100).toFixed(2)}`;
}
