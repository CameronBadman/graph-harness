def summarize_sales(quantities: list[int]) -> dict[str, int]:
    if any(quantity <= 0 for quantity in quantities):
        raise ValueError("Sales quantities must be positive")
    return {"orders": len(quantities), "tickets": sum(quantities)}
