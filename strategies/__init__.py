from .acceptance_sweep import AcceptanceSweepStrategy
from .ema_cross import EMACrossStrategy


STRATEGIES = {
    "acceptance_sweep": AcceptanceSweepStrategy,
    "ema_cross": EMACrossStrategy,
}


def get_strategy(name: str, params: dict = None):
    if name not in STRATEGIES:
        raise ValueError(
            f"Strategy '{name}' not found. Available: {list(STRATEGIES.keys())}"
        )
    return STRATEGIES[name](params)
